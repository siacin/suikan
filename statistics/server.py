"""Installation statistics and access management. WSGI entry: server:application."""
import argparse
from collections import OrderedDict
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
from urllib.parse import parse_qs
from wsgiref.simple_server import make_server, WSGIRequestHandler

CHINA = timezone(timedelta(hours=8))
ROOT = Path(__file__).resolve().parent
UUID4 = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z")
VERSION = re.compile(r"[0-9][0-9A-Za-z.+_-]{0,39}\Z")
HASH_ID = re.compile(r"[0-9a-f]{64}\Z")
OUTCOMES = ("started", "succeeded", "failed", "cancelled")
COUNTERS = tuple(kind+"_"+outcome for kind in ("extract", "summary") for outcome in OUTCOMES)
SUM_COLUMNS = ",".join(f"coalesce(sum({c}),0) AS {c}" for c in COUNTERS)


def metrics(row=None):
    row = dict(row) if row is not None else {}
    result = {kind: {outcome: row.get(kind+"_"+outcome, 0) for outcome in OUTCOMES} for kind in ("extract", "summary")}
    result.update({outcome: result["extract"][outcome]+result["summary"][outcome] for outcome in OUTCOMES})
    return result


def installation_hash(identifier):
    return hashlib.sha256(("suikan-usage-v1:" + identifier).encode()).hexdigest()


class UsageDatabase:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS installations (
                    id TEXT PRIMARY KEY, first_day TEXT NOT NULL,
                    last_day TEXT NOT NULL, app_version TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS active (
                    day TEXT NOT NULL, id TEXT NOT NULL,
                    PRIMARY KEY(day,id));
                CREATE INDEX IF NOT EXISTS installations_first ON installations(first_day);
                CREATE INDEX IF NOT EXISTS installations_last ON installations(last_day);
                CREATE TABLE IF NOT EXISTS installation_access (
                    id TEXT PRIMARY KEY, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
                reason TEXT NOT NULL);
            """)
            columns = ",".join(c+" INTEGER NOT NULL DEFAULT 0" for c in COUNTERS)
            db.executescript(f"""CREATE TABLE IF NOT EXISTS operation_daily (
                day TEXT NOT NULL,id TEXT NOT NULL,{columns},PRIMARY KEY(day,id));
                CREATE TABLE IF NOT EXISTS operation_totals (id TEXT PRIMARY KEY,{columns});
                CREATE INDEX IF NOT EXISTS operation_daily_id ON operation_daily(id);""")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def record(self, identifier, version, days, today, operations=()):
        # The random client ID is hashed; no raw ID, IP, user-agent or payload is persisted.
        identifier = installation_hash(identifier)
        with self.connect() as db:
            if days:
                first, last = min(days), max(days)
                db.execute("""INSERT INTO installations VALUES(?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                    first_day=min(first_day,excluded.first_day),
                    app_version=CASE WHEN excluded.last_day>=last_day THEN excluded.app_version ELSE app_version END,
                    last_day=max(last_day,excluded.last_day)""", (identifier, first, last, version))
                db.executemany("INSERT OR IGNORE INTO active VALUES(?,?)", [(d, identifier) for d in days])
            for record in operations:
                day, values = record["day"], record["extract"]+record["summary"]
                old = db.execute("SELECT * FROM operation_daily WHERE day=? AND id=?", (day, identifier)).fetchone()
                if all(n <= (old[c] if old else 0) for c, n in zip(COUNTERS, values)):
                    continue
                deltas = [max(n-(old[c] if old else 0), 0) for c, n in zip(COUNTERS, values)]
                db.execute(f"INSERT INTO operation_totals(id,{','.join(COUNTERS)}) VALUES({','.join('?' for _ in range(9))}) ON CONFLICT(id) DO UPDATE SET "+
                           ",".join(f"{c}={c}+excluded.{c}" for c in COUNTERS), (identifier, *deltas))
                db.execute(f"INSERT INTO operation_daily(day,id,{','.join(COUNTERS)}) VALUES({','.join('?' for _ in range(10))}) ON CONFLICT(day,id) DO UPDATE SET "+
                           ",".join(f"{c}=max({c},excluded.{c})" for c in COUNTERS), (day, identifier, *values))
            db.execute("DELETE FROM active WHERE day<?", ((today-timedelta(days=89)).isoformat(),))
            db.execute("DELETE FROM operation_daily WHERE day<?", ((today-timedelta(days=89)).isoformat(),))

    def access(self, identifier):
        with self.connect() as db:
            row = db.execute("SELECT enabled,reason FROM installation_access WHERE id=?", (installation_hash(identifier),)).fetchone()
        return {"schema": 1, "allowed": bool(row[0]) if row else True, "reason": row[1] if row and not row[0] else ""}

    def set_access(self, identifier, enabled, reason):
        with self.connect() as db:
            if not db.execute("SELECT 1 FROM installations WHERE id=?", (identifier,)).fetchone():
                return False
            db.execute("INSERT INTO installation_access VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,reason=excluded.reason", (identifier, int(enabled), "" if enabled else reason))
        return True

    def installations(self, offset, limit, query):
        with self.connect() as db:
            db.execute("BEGIN")
            count = db.execute("SELECT count(*) FROM installations WHERE id LIKE ?", (query+"%",)).fetchone()[0]
            rows = [dict(row) for row in db.execute(f"""SELECT i.id,i.first_day,i.last_day,i.app_version,
                coalesce(a.enabled,1) AS enabled,coalesce(a.reason,'') AS reason,{','.join('coalesce(t.'+c+',0) AS '+c for c in COUNTERS)}
                FROM installations i LEFT JOIN installation_access a ON i.id=a.id LEFT JOIN operation_totals t ON i.id=t.id
                WHERE i.id LIKE ? ORDER BY i.last_day DESC,i.first_day DESC,i.id LIMIT ? OFFSET ?""", (query+"%", limit, offset))]
        for row in rows:
            row["enabled"] = bool(row["enabled"])
            row["usage"] = metrics(row)
            for c in COUNTERS:
                del row[c]
        return {"total": count, "offset": offset, "limit": limit, "items": rows}

    def summary(self, today):
        end = today.isoformat()
        start = (today-timedelta(days=29)).isoformat()
        with self.connect() as db:
            # One read transaction makes cards and series reflect the same snapshot.
            db.execute("BEGIN")
            scalar = lambda sql, args=(): db.execute(sql, args).fetchone()[0]
            total = scalar("SELECT count(*) FROM installations")
            new = scalar("SELECT count(*) FROM installations WHERE first_day=?", (end,))
            dau = scalar("SELECT count(*) FROM active WHERE day=?", (end,))
            mau = scalar("SELECT count(DISTINCT id) FROM active WHERE day BETWEEN ? AND ?", (start, end))
            active = dict(db.execute("SELECT day,count(*) FROM active WHERE day BETWEEN ? AND ? GROUP BY day", (start, end)))
            new_days = dict(db.execute("SELECT first_day,count(*) FROM installations WHERE first_day BETWEEN ? AND ? GROUP BY first_day", (start, end)))
            versions = [dict(row) for row in db.execute("SELECT app_version AS version,count(*) AS installations FROM installations GROUP BY app_version ORDER BY installations DESC,version")]
            total_uses = metrics(db.execute(f"SELECT {SUM_COLUMNS} FROM operation_totals").fetchone())
            today_uses = metrics(db.execute(f"SELECT {SUM_COLUMNS} FROM operation_daily WHERE day=?", (end,)).fetchone())
            uses = {row["day"]: metrics(row) for row in db.execute(f"SELECT day,{SUM_COLUMNS} FROM operation_daily WHERE day BETWEEN ? AND ? GROUP BY day", (start, end))}
        daily = []
        for i in range(30):
            day = (today-timedelta(days=29-i)).isoformat()
            daily.append({"day": day, "active": active.get(day, 0), "new": new_days.get(day, 0), "usage": uses.get(day, metrics())})
        return {"timezone": "Asia/Shanghai", "date": end, "total_installations": total,
                "new_today": new, "dau": dau, "mau_30d": mau, "daily": daily, "versions": versions,
                "operations": {"total": total_uses, "today": today_uses}}


class RateLimit:
    """Bounded in-memory limits. No network identifiers are retained in the database."""
    def __init__(self):
        self.buckets = OrderedDict()
        self.lock = threading.Lock()

    def allow(self, category, key, limit):
        minute = int(time.monotonic()//60)
        bucket = (category, hashlib.sha256(key.encode()).digest())
        with self.lock:
            old_minute, count = self.buckets.pop(bucket, (minute, 0))
            count = count+1 if old_minute == minute else 1
            self.buckets[bucket] = (minute, count)
            while len(self.buckets) > 10000:
                self.buckets.popitem(last=False)
            return count <= limit


class UsageApplication:
    def __init__(self, database, admin_token, now=None):
        if len(admin_token) < 32 or admin_token.startswith("replace-"):
            raise ValueError("SUIKAN_ADMIN_TOKEN must contain at least 32 characters; keep it only on the server.")
        self.database = UsageDatabase(database)
        self.token = admin_token
        self.now = now or (lambda: datetime.now(CHINA).date())
        self.limit = RateLimit()

    def __call__(self, env, start_response):
        status, data, mime = "404 Not Found", b'{"error":"not_found"}', "application/json; charset=utf-8"
        extra = []
        path, method = env.get("PATH_INFO", ""), env.get("REQUEST_METHOD", "GET")
        try:
            if path == "/" and method == "GET":
                status, data, mime = "200 OK", (ROOT/"dashboard.html").read_bytes(), "text/html; charset=utf-8"
            elif path in ("/dashboard.js", "/dashboard.css", "/favicon.svg") and method == "GET":
                mime = "image/svg+xml" if path.endswith(".svg") else ("text/javascript; charset=utf-8" if path.endswith(".js") else "text/css; charset=utf-8")
                status, data = "200 OK", (ROOT/path[1:]).read_bytes()
            elif path == "/healthz" and method == "GET":
                status, data = "200 OK", b'{"ok":true}'
            elif path in ("/api/summary", "/api/installations", "/api/access"):
                # Check both values as bytes to safely reject arbitrary Unicode/oversized credentials.
                supplied = env.get("HTTP_AUTHORIZATION", "").encode("utf-8")
                authorized = len(supplied) < 512 and hmac.compare_digest(supplied, ("Bearer "+self.token).encode("utf-8"))
                if not self.limit.allow("admin", env.get("REMOTE_ADDR", "unknown"), 60):
                    status, data = "429 Too Many Requests", b'{"error":"rate_limited"}'
                elif not authorized:
                    status, data = "401 Unauthorized", b'{"error":"unauthorized"}'
                elif path == "/api/access" and method == "POST":
                    status, data = self.change_access(env)
                elif path == "/api/installations" and method == "GET":
                    status, data = self.list_installations(env)
                elif path == "/api/summary" and method == "GET":
                    status, data = "200 OK", json.dumps(self.database.summary(self.now()), ensure_ascii=False).encode()
                else:
                    status, data = "405 Method Not Allowed", b'{"error":"method_not_allowed"}'
            elif path == "/v1/usage" and method == "POST":
                status, data = self.ingest(env)
            elif path == "/v1/access" and method == "POST":
                status, data = self.client_access(env)
            elif path in ("/v1/usage", "/v1/access", "/healthz"):
                status, data = "405 Method Not Allowed", b'{"error":"method_not_allowed"}'
            if status.startswith("429"):
                extra.append(("Retry-After", "60"))
        except (sqlite3.Error, OSError):
            # Do not return database paths, submitted content or configuration in errors.
            status, data = "503 Service Unavailable", b'{"error":"temporarily_unavailable"}'
        headers = [("Content-Type", mime), ("Content-Length", str(len(data))),
                   ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff"),
                   ("Referrer-Policy", "no-referrer"), ("X-Frame-Options", "DENY"),
                   ("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")] + extra
        start_response(status, headers)
        return [data]

    @staticmethod
    def read_json(env):
        if env.get("CONTENT_TYPE", "").split(";")[0].strip().lower() != "application/json":
            raise ValueError("content_type")
        length = int(env.get("CONTENT_LENGTH", ""))
        if not 1 <= length <= 2048:
            raise ValueError("body_size")
        raw = env["wsgi.input"].read(length)
        if len(raw) != length:
            raise ValueError("body_size")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("invalid_payload")
        return value

    def client_access(self, env):
        if not self.limit.allow("global_access", "all", 6000):
            return "429 Too Many Requests", b'{"error":"rate_limited"}'
        try:
            value = self.read_json(env)
            if set(value) != {"schema", "installation_id", "app_version"} or type(value["schema"]) is not int or value["schema"] != 1:
                raise ValueError()
            identifier, version = value["installation_id"], value["app_version"]
            if not isinstance(identifier, str) or not UUID4.fullmatch(identifier) or not isinstance(version, str) or not VERSION.fullmatch(version):
                raise ValueError()
        except (ValueError, TypeError, KeyError, UnicodeError):
            return "400 Bad Request", b'{"error":"invalid_payload"}'
        if not self.limit.allow("access", identifier, 12):
            return "429 Too Many Requests", b'{"error":"rate_limited"}'
        today = self.now()
        self.database.record(identifier, version, [today.isoformat()], today)
        return "200 OK", json.dumps(self.database.access(identifier), ensure_ascii=False).encode()

    def change_access(self, env):
        try:
            value = self.read_json(env)
            if set(value) != {"id", "enabled", "reason"} or type(value["enabled"]) is not bool:
                raise ValueError()
            identifier, reason = value["id"], value["reason"]
            if not isinstance(identifier, str) or not HASH_ID.fullmatch(identifier) or not isinstance(reason, str) or len(reason) > 200 or any(ord(c)<32 for c in reason):
                raise ValueError()
        except (ValueError, TypeError, KeyError, UnicodeError):
            return "400 Bad Request", b'{"error":"invalid_payload"}'
        if not self.database.set_access(identifier, value["enabled"], reason.strip()):
            return "404 Not Found", b'{"error":"installation_not_found"}'
        return "200 OK", b'{"ok":true}'

    def list_installations(self, env):
        try:
            query_string = env.get("QUERY_STRING", "")
            if len(query_string) > 256:
                raise ValueError()
            args = parse_qs(query_string, keep_blank_values=True)
            if set(args)-{"offset", "limit", "q"} or any(len(v)!=1 for v in args.values()):
                raise ValueError()
            offset, limit, query = int(args.get("offset", ["0"])[0]), int(args.get("limit", ["50"])[0]), args.get("q", [""])[0]
            if not 0 <= offset <= 1000000000 or not 1 <= limit <= 100 or not re.fullmatch(r"[0-9a-f]{0,64}", query):
                raise ValueError()
        except (ValueError, TypeError):
            return "400 Bad Request", b'{"error":"invalid_query"}'
        return "200 OK", json.dumps(self.database.installations(offset, limit, query), ensure_ascii=False).encode()

    def ingest(self, env):
        if env.get("CONTENT_TYPE", "").split(";")[0].strip().lower() != "application/json":
            return "415 Unsupported Media Type", b'{"error":"content_type"}'
        try:
            length = int(env.get("CONTENT_LENGTH", ""))
        except ValueError:
            return "411 Length Required", b'{"error":"length_required"}'
        if length < 1 or length > 8192:
            return "413 Content Too Large", b'{"error":"body_size"}'
        if not self.limit.allow("global_ingest", "all", 6000):
            return "429 Too Many Requests", b'{"error":"rate_limited"}'
        try:
            raw = env["wsgi.input"].read(length)
            if len(raw) != length:
                raise ValueError()
            value = json.loads(raw)
            modern = isinstance(value, dict) and type(value.get("schema")) is int and value["schema"] == 2
            fields = {"schema", "installation_id", "app_version", "active_days"}
            if modern:
                fields.add("operation_days")
            if not isinstance(value, dict) or set(value) != fields:
                raise ValueError()
            if type(value["schema"]) is not int or value["schema"] not in (1, 2):
                raise ValueError()
            identifier, version, days = value["installation_id"], value["app_version"], value["active_days"]
            if not isinstance(identifier, str) or not UUID4.fullmatch(identifier):
                raise ValueError()
            if not isinstance(version, str) or not VERSION.fullmatch(version):
                raise ValueError()
            if not isinstance(days, list) or not (0 if modern else 1) <= len(days) <= 31:
                raise ValueError()
            operations = value.get("operation_days", [])
            if not isinstance(operations, list) or len(operations) > 31 or (not days and not operations):
                raise ValueError()
            seen = set()
            for record in operations:
                if not isinstance(record, dict) or set(record) != {"day", "extract", "summary"}:
                    raise ValueError()
                day = record["day"]
                if not isinstance(day, str) or len(day) != 10 or date.fromisoformat(day).isoformat() != day or day in seen:
                    raise ValueError()
                seen.add(day)
                for kind in ("extract", "summary"):
                    if not isinstance(record[kind], list) or len(record[kind]) != 4 or any(type(n) is not int or not 0 <= n <= 1000000000 for n in record[kind]):
                        raise ValueError()
            today = self.now()
            valid_days = set()
            for day in days:
                if not isinstance(day, str) or len(day) != 10 or date.fromisoformat(day).isoformat() != day:
                    raise ValueError()
                # Acknowledge expired/incorrect-clock dates without counting them, to avoid permanent retries.
                if today-timedelta(days=30) <= date.fromisoformat(day) <= today:
                    valid_days.add(day)
            operations = [r for r in operations if today-timedelta(days=30) <= date.fromisoformat(r["day"]) <= today]
            valid_days.update(r["day"] for r in operations)
        except (ValueError, TypeError, KeyError, UnicodeError):
            return "400 Bad Request", b'{"error":"invalid_payload"}'
        if not self.limit.allow("installation", identifier, 12):
            return "429 Too Many Requests", b'{"error":"rate_limited"}'
        self.database.record(identifier, version, sorted(valid_days), today, operations)
        return "204 No Content", b""


_application = None
_init_lock = threading.Lock()


def application(env, start_response):
    global _application
    if _application is None:
        with _init_lock:
            if _application is None:
                _application = UsageApplication(os.environ.get("SUIKAN_DB", "data/usage.sqlite3"), os.environ.get("SUIKAN_ADMIN_TOKEN", ""))
    return _application(env, start_response)


class QuietHandler(WSGIRequestHandler):
    def log_message(self, *args):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Local development only; deploy behind HTTPS with Gunicorn/Caddy.")
    parser.add_argument("--port", type=int, default=18766)
    args = parser.parse_args()
    # Fail on missing credentials before accepting requests.
    _application = UsageApplication(os.environ.get("SUIKAN_DB", "data/usage.sqlite3"), os.environ.get("SUIKAN_ADMIN_TOKEN", ""))
    with make_server("127.0.0.1", args.port, application, handler_class=QuietHandler) as httpd:
        print(f"Local statistics dashboard: http://127.0.0.1:{args.port}", flush=True)
        httpd.serve_forever()

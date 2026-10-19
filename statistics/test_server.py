"""Run: python -m unittest discover -s statistics -p test_*.py -v"""
import copy
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import date, timedelta
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from server import UsageApplication, installation_hash


class UsageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)/"stats.sqlite3"
        self.today = date(2026, 10, 9)
        self.token = "test-only-admin-"+"x"*32
        self.app = UsageApplication(self.path, self.token, now=lambda: self.today)
        self.payload = {"schema": 1, "installation_id": str(uuid.uuid4()), "app_version": "0.1.8", "active_days": [self.today.isoformat()]}

    def tearDown(self):
        self.temp.cleanup()

    def request(self, path, method="GET", value=None, **kwargs):
        raw = b"" if value is None else json.dumps(value).encode()
        env = {"PATH_INFO": path, "REQUEST_METHOD": method, "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(raw)), "wsgi.input": io.BytesIO(raw), "REMOTE_ADDR": "127.0.0.1"}
        env.update(kwargs)
        response = []
        body = b"".join(self.app(env, lambda status, headers: response.append((status, dict(headers)))))
        return int(response[0][0].split()[0]), body, response[0][1]

    def summary(self):
        status, body, headers = self.request("/api/summary", HTTP_AUTHORIZATION="Bearer "+self.token)
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        return json.loads(body)

    def test_empty_statistics_and_zero_filled_series(self):
        s = self.summary()
        self.assertEqual([s[k] for k in ("total_installations", "new_today", "dau", "mau_30d")], [0, 0, 0, 0])
        self.assertEqual(len(s["daily"]), 30)
        self.assertEqual(s["daily"][0]["day"], "2026-09-10")
        self.assertEqual(s["timezone"], "Asia/Shanghai")

    def test_dashboard_visits_do_not_count_as_app_installations(self):
        self.request("/"); self.request("/healthz")
        self.assertEqual(self.summary()["total_installations"], 0)

    def test_repeat_and_lost_acknowledgment_do_not_inflate(self):
        for i in range(8):
            self.assertEqual(self.request("/v1/usage", "POST", self.payload)[0], 204)
        s = self.summary()
        self.assertEqual([s[k] for k in ("total_installations", "new_today", "dau", "mau_30d")], [1, 1, 1, 1])

    def test_new_installation_and_version_distribution(self):
        self.request("/v1/usage", "POST", self.payload)
        other = dict(self.payload, installation_id=str(uuid.uuid4()), app_version="0.1.9")
        self.request("/v1/usage", "POST", other)
        s = self.summary()
        self.assertEqual(s["total_installations"], 2)
        self.assertEqual({v["version"]: v["installations"] for v in s["versions"]}, {"0.1.8": 1, "0.1.9": 1})

    def test_offline_days_and_rolling_month(self):
        self.payload["active_days"] = ["2026-09-09", "2026-09-10", "2026-10-08", "2026-10-09"]
        self.request("/v1/usage", "POST", self.payload)
        self.assertEqual(self.summary()["new_today"], 0)
        self.assertEqual(self.summary()["mau_30d"], 1)
        self.assertEqual(self.summary()["daily"][-2]["active"], 1)
        self.today = date(2026, 11, 8)
        self.assertEqual(self.summary()["mau_30d"], 0)
        self.assertEqual(self.summary()["total_installations"], 1)

    def test_old_and_future_dates_acknowledged_without_counting(self):
        self.payload["active_days"] = ["2026-01-01", "2026-10-10"]
        self.assertEqual(self.request("/v1/usage", "POST", self.payload)[0], 204)
        self.assertEqual(self.summary()["total_installations"], 0)

    def test_first_day_corrected_by_delayed_older_activity(self):
        self.request("/v1/usage", "POST", self.payload)
        self.payload.update(app_version="0.1.7", active_days=["2026-10-08"])
        self.request("/v1/usage", "POST", self.payload)
        s = self.summary()
        self.assertEqual(s["new_today"], 0)
        self.assertEqual(s["versions"][0]["version"], "0.1.8")
        self.assertEqual(s["daily"][-2]["new"], 1)

    def test_concurrent_first_requests_are_atomic(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(lambda _: self.request("/v1/usage", "POST", self.payload)[0], range(8)))
        self.assertEqual(statuses, [204]*8)
        self.assertEqual(self.summary()["total_installations"], 1)

    def test_data_survives_restart_and_raw_identifiers_not_stored(self):
        self.request("/v1/usage", "POST", self.payload)
        self.app = UsageApplication(self.path, self.token, now=lambda: self.today)
        self.assertEqual(self.summary()["total_installations"], 1)
        with closing(sqlite3.connect(self.path)) as db:
            row = db.execute("SELECT * FROM installations").fetchone()
            self.assertEqual(len(row[0]), 64)
            self.assertNotIn(self.payload["installation_id"], str(row))
            self.assertEqual(len(row), 4)

    def test_active_history_is_bounded_but_total_is_preserved(self):
        self.request("/v1/usage", "POST", self.payload)
        self.today += timedelta(days=90)
        self.payload["active_days"] = [self.today.isoformat()]
        self.request("/v1/usage", "POST", self.payload)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM active").fetchone()[0], 1)
        self.assertEqual(self.summary()["total_installations"], 1)

    def test_unknown_content_fields_are_rejected_and_not_reflected(self):
        self.payload["api_key"] = "PRIVATE-SECRET-MUST-NOT-RETURN"
        status, body, _ = self.request("/v1/usage", "POST", self.payload)
        self.assertEqual(status, 400)
        self.assertNotIn(b"PRIVATE-SECRET", body)
        self.assertEqual(self.summary()["total_installations"], 0)

    def test_malformed_payloads(self):
        for key, values in {"schema": [True, 2, "1"], "installation_id": [None, 123, "device-imei", str(uuid.uuid1())], "app_version": [None, "<script>", "x"*41], "active_days": [[], ["2026-02-30"], ["2026-1-1"], [123], "2026-10-09", ["2026-10-09"]*32]}.items():
            for value in values:
                payload = copy.deepcopy(self.payload); payload[key] = value
                with self.subTest(key=key, value=value):
                    self.assertEqual(self.request("/v1/usage", "POST", payload)[0], 400)

    def test_body_limit_content_type_and_method(self):
        self.assertEqual(self.request("/v1/usage", "POST", self.payload, CONTENT_LENGTH="8193")[0], 413)
        self.assertEqual(self.request("/v1/usage", "POST", self.payload, CONTENT_LENGTH="bad")[0], 411)
        self.assertEqual(self.request("/v1/usage", "POST", self.payload, CONTENT_LENGTH="2048")[0], 400)
        self.assertEqual(self.request("/v1/usage", "POST", self.payload, CONTENT_TYPE="text/plain")[0], 415)
        self.assertEqual(self.request("/v1/usage")[0], 405)
        self.assertEqual(self.request("/../server.py")[0], 404)

    def test_admin_auth_not_in_url_no_cors_and_no_sensitive_response(self):
        for auth in ["", "Bearer wrong", "Bearer "+"a"*1000, "Bearer 中文"]:
            status, body, headers = self.request("/api/summary", HTTP_AUTHORIZATION=auth, QUERY_STRING="token="+self.token, HTTP_ORIGIN="https://evil.invalid")
            self.assertEqual(status, 401)
            self.assertNotIn(self.token.encode(), body)
            self.assertNotIn("Access-Control-Allow-Origin", headers)

    def test_rate_limit_and_response_headers(self):
        for _ in range(12): self.assertEqual(self.request("/v1/usage", "POST", self.payload)[0], 204)
        status, _, headers = self.request("/v1/usage", "POST", self.payload)
        self.assertEqual(status, 429)
        self.assertEqual(headers["Retry-After"], "60")
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])

    def test_dashboard_files_no_external_services_or_inline_scripts(self):
        for path in ["/", "/dashboard.js", "/dashboard.css"]:
            status, body, headers = self.request(path)
            self.assertEqual(status, 200)
            self.assertNotIn(b"https://", body)
            self.assertEqual(headers["Cache-Control"], "no-store")
        script = self.request("/dashboard.js")[1]
        self.assertNotIn(b"localStorage", script)
        self.assertNotIn(b"innerHTML", script)

    def test_weak_or_example_admin_token_rejected(self):
        for token in ["", "password", "replace-with-a-random-management-token-of-at-least-32-characters"]:
            with self.assertRaises(ValueError): UsageApplication(self.path, token)

    def operations(self, extract=None, summary=None, day=None):
        return dict(self.payload, schema=2, active_days=[], operation_days=[{
            "day": day or self.today.isoformat(), "extract": extract or [2, 1, 1, 0], "summary": summary or [3, 1, 1, 1]}])

    def test_operations_summary_daily_installation_and_restart(self):
        self.assertEqual(self.request("/v1/usage", "POST", self.operations())[0], 204)
        s = self.summary()
        self.assertEqual(s["operations"]["total"]["started"], 5)
        self.assertEqual(s["operations"]["total"], s["operations"]["today"])
        self.assertEqual(s["daily"][-1]["usage"]["summary"]["cancelled"], 1)
        self.assertEqual(json.loads(self.admin("/api/installations")[1])["items"][0]["usage"], s["operations"]["total"])
        self.app = UsageApplication(self.path, self.token, now=lambda: self.today)
        self.assertEqual(self.summary()["operations"], s["operations"])

    def test_operations_duplicate_out_of_order_and_concurrent_snapshots(self):
        values = [self.operations([3, 1, 0, 0], [4, 2, 0, 0]), self.operations([2, 2, 0, 0], [2, 1, 1, 0]), self.operations([1, 0, 1, 0], [1, 0, 0, 1])]
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = list(pool.map(lambda i: self.request("/v1/usage", "POST", values[i % 3])[0], range(9)))
        self.assertEqual(statuses, [204]*9)
        total = self.summary()["operations"]["total"]
        self.assertEqual(total["extract"], dict(started=3, succeeded=2, failed=1, cancelled=0))
        self.assertEqual(total["summary"], dict(started=4, succeeded=2, failed=1, cancelled=1))
        self.request("/v1/usage", "POST", values[2])
        self.assertEqual(self.summary()["operations"]["total"], total)

    def test_operations_offline_backfill_expired_dates_and_old_clients(self):
        self.request("/v1/usage", "POST", self.payload)
        self.assertEqual(self.summary()["operations"]["total"]["started"], 0)
        self.request("/v1/usage", "POST", self.operations())
        self.request("/v1/usage", "POST", self.operations(day="2026-10-08"))
        self.request("/v1/usage", "POST", self.operations(day="2026-09-08"))
        self.request("/v1/usage", "POST", self.operations(day="2026-10-10"))
        s = self.summary()
        self.assertEqual(s["operations"]["total"]["started"], 10)
        self.assertEqual(s["operations"]["today"]["started"], 5)
        self.assertEqual(s["daily"][-2]["usage"]["started"], 5)

    def test_operations_strict_counters_and_unknown_content_rejected(self):
        for n in [-1, True, 1.5, "2", None, 1000000001]:
            p = self.operations(); p["operation_days"][0]["extract"][0] = n
            self.assertEqual(self.request("/v1/usage", "POST", p)[0], 400)
        for patch in [{"day": "2026-02-30"}, {"video": "private"}, {"extract": [1]}, {"summary": "private"}]:
            p = self.operations(); p["operation_days"][0].update(patch)
            self.assertEqual(self.request("/v1/usage", "POST", p)[0], 400)
        p = self.operations(); p["operation_days"] *= 2
        self.assertEqual(self.request("/v1/usage", "POST", p)[0], 400)
        self.assertEqual(self.summary()["operations"]["total"]["started"], 0)

    def test_operations_maximum_payload_and_lifetime_retention(self):
        p = self.operations()
        p["operation_days"] = [dict(day=(self.today-timedelta(days=i)).isoformat(), extract=[1000000000]*4, summary=[1000000000]*4) for i in range(31)]
        p["active_days"] = [r["day"] for r in p["operation_days"]]
        self.assertLess(len(json.dumps(p).encode()), 8192)
        self.assertEqual(self.request("/v1/usage", "POST", p)[0], 204)
        total = self.summary()["operations"]["total"]
        self.assertEqual(total["started"], 62000000000)
        self.today += timedelta(days=120)
        self.assertEqual(self.request("/v1/usage", "POST", p)[0], 204)
        self.assertEqual(self.summary()["operations"]["total"], total)
        self.assertEqual(self.summary()["operations"]["today"]["started"], 0)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(db.execute("SELECT count(*) FROM operation_daily").fetchone()[0], 0)

    def client_access(self):
        value = {k: self.payload[k] for k in ("schema", "installation_id", "app_version")}
        status, body, _ = self.request("/v1/access", "POST", value)
        self.assertEqual(status, 200)
        return json.loads(body)

    def admin(self, path, value=None, **kwargs):
        return self.request(path, "POST" if value is not None else "GET", value, HTTP_AUTHORIZATION="Bearer "+self.token, **kwargs)

    def test_default_access_registers_and_deduplicates(self):
        self.assertTrue(self.client_access()["allowed"])
        self.request("/v1/usage", "POST", self.payload)
        self.assertTrue(self.client_access()["allowed"])
        self.assertEqual(self.summary()["total_installations"], 1)
        self.assertEqual(self.summary()["dau"], 1)

    def test_disable_survives_heartbeat_restart_and_reenable(self):
        self.client_access()
        change = {"id": installation_hash(self.payload["installation_id"]), "enabled": False, "reason": "测试停用"}
        self.assertEqual(self.admin("/api/access", change)[0], 200)
        self.assertEqual(self.client_access(), {"schema": 1, "allowed": False, "reason": "测试停用"})
        self.request("/v1/usage", "POST", self.payload)
        self.app = UsageApplication(self.path, self.token, now=lambda: self.today)
        self.assertFalse(self.client_access()["allowed"])
        change.update(enabled=True, reason="ignored when enabling")
        self.assertEqual(self.admin("/api/access", change)[0], 200)
        self.assertEqual(self.client_access()["reason"], "")
        self.assertTrue(self.client_access()["allowed"])

    def test_admin_controls_require_header_token_and_never_accept_public_changes(self):
        self.client_access()
        change = {"id": installation_hash(self.payload["installation_id"]), "enabled": False, "reason": ""}
        for path in ("/api/access", "/api/installations"):
            self.assertEqual(self.request(path, "POST" if path.endswith("access") else "GET", change)[0], 401)
        public = {k: self.payload[k] for k in ("schema", "installation_id", "app_version")}
        public["allowed"] = False
        self.assertEqual(self.request("/v1/access", "POST", public)[0], 400)
        self.assertTrue(self.client_access()["allowed"])
        self.assertEqual(self.request("/api/access", "POST", change, QUERY_STRING="token="+self.token)[0], 401)

    def test_controls_are_isolated_per_installation(self):
        self.client_access()
        change = {"id": installation_hash(self.payload["installation_id"]), "enabled": False, "reason": ""}
        self.admin("/api/access", change)
        self.payload["installation_id"] = str(uuid.uuid4())
        self.assertTrue(self.client_access()["allowed"])

    def test_access_input_limits_and_unknown_id(self):
        change = {"id": "a"*64, "enabled": False, "reason": ""}
        self.assertEqual(self.admin("/api/access", change)[0], 404)
        for key, values in {"id": [None, "short", "A"*64, "' OR 1=1 --"], "enabled": [0, "false", None], "reason": [None, "x"*201, "line\nnext"]}.items():
            for value in values:
                bad = dict(change); bad[key] = value
                self.assertEqual(self.admin("/api/access", bad)[0], 400)
        client = {k: self.payload[k] for k in ("schema", "installation_id", "app_version")}
        for bad in [dict(client, schema=True), dict(client, installation_id="not-a-uuid"), dict(client, app_version="<script>"), dict(client, content="subtitle")]:
            self.assertEqual(self.request("/v1/access", "POST", bad)[0], 400)
        self.assertEqual(self.request("/v1/access")[0], 405)
        self.assertEqual(self.admin("/api/access")[0], 405)

    def test_installation_list_pagination_prefix_and_status(self):
        self.client_access()
        for i in range(105):
            self.app.database.record(str(uuid.uuid4()), "0.1.9", [self.today.isoformat()], self.today)
        first = json.loads(self.admin("/api/installations")[1])
        self.assertEqual(first["total"], 106)
        self.assertEqual(len(first["items"]), 50)
        second = json.loads(self.admin("/api/installations", QUERY_STRING="offset=50&limit=50")[1])
        self.assertFalse({r["id"] for r in first["items"]} & {r["id"] for r in second["items"]})
        identifier = installation_hash(self.payload["installation_id"])
        self.admin("/api/access", {"id": identifier, "enabled": False, "reason": "<script>plain text</script>"})
        result = json.loads(self.admin("/api/installations", QUERY_STRING="q="+identifier)[1])
        self.assertEqual(result["total"], 1)
        self.assertFalse(result["items"][0]["enabled"])
        self.assertEqual(result["items"][0]["reason"], "<script>plain text</script>")
        for query in ["offset=-1", "limit=101", "q=xyz", "q=a&q=b", "secret=x"]:
            self.assertEqual(self.admin("/api/installations", QUERY_STRING=query)[0], 400)

    def test_existing_database_upgrades_without_losing_counts_or_access(self):
        self.request("/v1/usage", "POST", self.payload)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("DROP TABLE installation_access");db.commit()
        self.app = UsageApplication(self.path, self.token, now=lambda: self.today)
        self.assertEqual(self.summary()["total_installations"], 1)
        self.assertTrue(self.client_access()["allowed"])

    def test_access_endpoint_rate_limit(self):
        client = {k: self.payload[k] for k in ("schema", "installation_id", "app_version")}
        for _ in range(12):
            self.assertEqual(self.request("/v1/access", "POST", client)[0], 200)
        self.assertEqual(self.request("/v1/access", "POST", client)[0], 429)


if __name__ == "__main__":
    unittest.main()

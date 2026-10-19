"""Local Windows convenience launcher; no public listener or bundled credentials."""
import argparse
import os
from pathlib import Path
import secrets
import threading
import webbrowser
from socketserver import ThreadingMixIn
from wsgiref.simple_server import WSGIServer, make_server
from server import UsageApplication, QuietHandler


def load_token(directory):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "admin-token.txt"
    if not path.exists():
        token = secrets.token_urlsafe(36)
        try:
            with path.open("x", encoding="utf-8") as stream:
                stream.write(token + "\n")
        except FileExistsError:
            pass
    token = path.read_text(encoding="utf-8").strip()
    if len(token) < 32 or token.startswith("replace-"):
        raise ValueError("管理口令文件无效。请备份数据后修正 admin-token.txt，口令至少 32 字符。")
    return token


class LocalServer(ThreadingMixIn, WSGIServer):
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser(description="Windows 本机统计后台。仅本机访问，不接收公网手机数据。")
    default = Path(os.environ.get("LOCALAPPDATA", str(Path(__file__).parent / ".local-data"))) / "SuiKanStatistics"
    parser.add_argument("--data-dir", type=Path, default=default)
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--show-token", action="store_true")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--quiet", action="store_true", help="Do not print the management credential (automated tests).")
    args = parser.parse_args()
    directory = args.data_dir.resolve()
    try:
        token = load_token(directory)
        if args.show_token:
            if not args.quiet:
                print("管理口令：" + token)
                print("本机私有口令文件：" + str(directory / "admin-token.txt"))
            return 0
        app = UsageApplication(directory / "usage.sqlite3", token)
        try:
            httpd = make_server("127.0.0.1", args.port, app, server_class=LocalServer, handler_class=QuietHandler)
        except OSError:
            print(f"无法启动：本机端口 {args.port} 可能已被占用。可访问已有后台，或加 --port 18769 换端口。")
            return 1
        with httpd:
            url = f"http://127.0.0.1:{httpd.server_port}/"
            print("本机统计管理页面：" + url, flush=True)
            if not args.quiet:
                print("管理口令：" + token, flush=True)
            print("口令保存在：" + str(directory / "admin-token.txt"), flush=True)
            print("这是本机后台，尚未接收分发用户数据。保持此窗口开启；按 Ctrl+C 或关闭窗口停止。", flush=True)
            if not args.no_browser:
                threading.Timer(0.3, lambda: webbrowser.open(url)).start()
            try:
                httpd.serve_forever()
            except KeyboardInterrupt:
                print("本机统计后台已停止，口令和数据保留。")
        return 0
    except (OSError, ValueError) as error:
        print("启动失败：" + str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

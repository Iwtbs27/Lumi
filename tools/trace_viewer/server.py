"""Small localhost server; all phone access stays in DeviceSync."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
from pathlib import Path
import threading
from urllib.parse import unquote, urlparse

from .records import RecordStore
from .sync import DeviceSync, SyncError


class Viewer:
    def __init__(self, records, device, interval=15):
        self.records = records
        self.device = device
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.sync_state = {"running": False, "auto": False, "interval": interval,
                           "last_result": None, "error": None}
        self.device_state = {"available": False, "devices": [], "serial": None,
                             "bridge_ready": False, "message": "正在检测手机连接…"}

    def status(self):
        with self.lock:
            return {"device": dict(self.device_state), "sync": dict(self.sync_state)}

    def start_sync(self, conversation_id=None, automatic=False):
        with self.lock:
            if self.sync_state["running"]:
                return False
            self.sync_state.update(running=True, error=None)

        def work():
            try:
                result = self.device.sync_changed() if automatic else self.device.sync(conversation_id)
                with self.lock:
                    if result != []:
                        self.sync_state["last_result"] = result
            except (SyncError, OSError, ValueError) as exc:
                with self.lock:
                    self.sync_state["error"] = str(exc)
            finally:
                with self.lock:
                    self.sync_state["running"] = False

        threading.Thread(target=work, daemon=True, name="trace-sync").start()
        return True

    def monitor(self):
        while not self.stop.is_set():
            state = self.device.status()
            with self.lock:
                self.device_state = state
                automatic = self.sync_state["auto"]
            if automatic and state["bridge_ready"]:
                self.start_sync(automatic=True)
            self.stop.wait(self.sync_state["interval"])


def make_handler(viewer):
    web_root = Path(__file__).parent / "web"

    class Handler(BaseHTTPRequestHandler):
        def send_bytes(self, body, content_type, status=200):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def json(self, value, status=200):
            self.send_bytes(json.dumps(value, ensure_ascii=False).encode("utf-8"),
                            "application/json; charset=utf-8", status)

        def do_GET(self):
            path = unquote(urlparse(self.path).path)
            parts = path.strip("/").split("/")
            try:
                if path == "/api/status":
                    self.json(viewer.status())
                elif path == "/api/runs":
                    self.json({"runs": viewer.records.list_runs()})
                elif len(parts) >= 3 and parts[:2] == ["api", "runs"]:
                    run_id = parts[2]
                    if len(parts) == 3:
                        self.json(viewer.records.run(run_id))
                    elif len(parts) == 5 and parts[3] == "steps":
                        self.json(viewer.records.step(run_id, int(parts[4])))
                    elif len(parts) >= 5 and parts[3] == "files":
                        file = viewer.records.asset(run_id, "/".join(parts[4:]))
                        self.send_bytes(file.read_bytes(), mimetypes.guess_type(file.name)[0]
                                        or "application/octet-stream")
                    else:
                        raise FileNotFoundError("接口不存在。")
                elif path in ("/", "/index.html", "/app.js", "/styles.css"):
                    file = web_root / ("index.html" if path == "/" else path.lstrip("/"))
                    self.send_bytes(file.read_bytes(), (mimetypes.guess_type(file.name)[0]
                                    or "text/plain") + "; charset=utf-8")
                else:
                    raise FileNotFoundError("页面不存在。")
            except FileNotFoundError as exc:
                self.json({"error": str(exc)}, 404)
            except (ValueError, OSError) as exc:
                self.json({"error": f"读取轨迹失败：{exc}"}, 500)

        def do_POST(self):
            origin = self.headers.get("Origin")
            if origin and urlparse(origin).netloc != self.headers.get("Host"):
                self.json({"error": "请从本地查看页操作。"}, 403)
                return
            try:
                data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                if self.path == "/api/sync":
                    conversation_id = data.get("conversation_id")
                    if conversation_id is not None:
                        conversation_id = int(conversation_id)
                    viewer.start_sync(conversation_id)
                    self.json({"running": True}, 202)
                elif self.path == "/api/auto-sync":
                    if not isinstance(data.get("enabled"), bool):
                        raise ValueError("enabled 必须是布尔值。")
                    with viewer.lock:
                        viewer.sync_state["auto"] = data["enabled"]
                    self.json(viewer.status()["sync"])
                else:
                    self.json({"error": "接口不存在。"}, 404)
            except (ValueError, TypeError) as exc:
                self.json({"error": str(exc)}, 400)

        def log_message(self, format, *args):
            # The browser polls regularly; only failed requests need terminal output.
            if len(args) > 1 and str(args[1]).startswith(("4", "5")):
                super().log_message(format, *args)

    return Handler


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Lumi 本地轨迹查看器")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--data-dir", type=Path, default=root / ".trace-data")
    parser.add_argument("--records", type=Path, action="append", default=[],
                        help="额外只读加载的归档目录，可重复指定")
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="ai.opencyvis.standard")
    parser.add_argument("--auto-sync", action="store_true")
    args = parser.parse_args()
    records = RecordStore([*args.records, args.data_dir / "conversations"])
    viewer = Viewer(records, DeviceSync(args.data_dir, args.adb, args.package, args.serial))
    viewer.sync_state["auto"] = args.auto_sync
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(viewer))
    threading.Thread(target=viewer.monitor, daemon=True, name="trace-monitor").start()
    print(f"Lumi 轨迹查看器：http://127.0.0.1:{args.port}", flush=True)
    print("按 Ctrl+C 停止；服务仅在本机开放。", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        viewer.stop.set()
        server.server_close()


if __name__ == "__main__":
    main()

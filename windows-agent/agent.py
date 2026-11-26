import argparse
import io
import json
import secrets
import socket
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import mss
from PIL import Image

DISCOVERY_PORT = 8766

class Handler(BaseHTTPRequestHandler):
    server_version = "LocalView/0.1.02"

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"app": "LocalView", "status": "ok"})
            return
        if self.path != "/screenshot":
            self.send_json(404, {"error": "not_found"})
            return
        auth = self.headers.get("Authorization", "")
        if not secrets.compare_digest(auth, "Bearer " + self.server.token):
            self.send_json(401, {"error": "unauthorized"})
            return
        try:
            with mss.mss() as capture:
                shot = capture.grab(capture.monitors[0])
                image = Image.frombytes("RGB", shot.size, shot.rgb)
                image.thumbnail((3840, 2160))
                buffer = io.BytesIO()
                image.save(buffer, format="JPEG", quality=88, optimize=True)
                payload = buffer.getvalue()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("X-Captured-At", datetime.now(timezone.utc).isoformat())
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)
        except Exception as exc:
            self.send_json(500, {"error": "capture_failed", "detail": str(exc)})

    def send_json(self, code, data):
        payload = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()

def discovery_listener(port, http_port, name):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", port))
    response = json.dumps({"type": "localview-agent", "name": name, "port": http_port}).encode("utf-8")
    print("Автопоиск в локальной сети включён (UDP %s)." % port)
    while True:
        data, address = sock.recvfrom(2048)
        try:
            request = json.loads(data.decode("utf-8"))
            if request.get("type") == "localview-discover":
                sock.sendto(response, address)
        except (ValueError, UnicodeDecodeError):
            continue

def main():
    parser = argparse.ArgumentParser(description="LocalView — ручной агент снимков экрана")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default=None)
    args = parser.parse_args()
    token = args.token or secrets.token_urlsafe(24)
    name = socket.gethostname()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.token = token
    listener = threading.Thread(target=discovery_listener, args=(DISCOVERY_PORT, args.port, name), daemon=True)
    listener.start()
    print("\nLocalView 0.1.02 запущен. Для остановки закройте это окно или нажмите Ctrl+C.")
    print("Имя ПК: %s" % name)
    print("Адрес этого ПК: %s:%s" % (local_ip(), args.port))
    print("Токен доступа (введите его в Android-приложении):")
    print(token)
    print("\nАгент не устанавливается как служба и не добавляется в автозагрузку.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print("LocalView остановлен.")

if __name__ == "__main__":
    main()

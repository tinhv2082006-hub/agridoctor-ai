"""Local HTTP API for the Android prototype. Uses Python's standard library."""

import argparse
import base64
import binascii
from collections import deque
import hmac
from http import HTTPStatus
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import sqlite3
import secrets
import socket
import webbrowser
import io
from threading import BoundedSemaphore, Lock
from time import monotonic

from lookup import lookup
from chat_agent import ChatAgent, ChatError
from admin_access import local_admin_token
from local_model import start as start_local_model, LocalUnavailable
from transcription import validated_recording, transcribe_audio, TranscriptionUnavailable
from vision import analyze_image, VisionUnavailable
from image_validation import valid_jpeg, ImageValidationUnavailable


DATABASE = Path(__file__).with_name("agridoctor.sqlite")
CHAT_STATE = Path(os.environ["AGRIDOCTOR_STATE_DIR"]) / "agent-state.sqlite" if "AGRIDOCTOR_STATE_DIR" in os.environ else DATABASE.with_name("agent-state.sqlite")
CROPS = {"durian", "coffee", "rice"}
OPTIONAL_FIELDS = ("crop_type", "growth_stage", "plant_part", "region")
MAX_BODY_BYTES = 8192
MAX_IMAGE_BODY_BYTES = 1_100_000
MAX_AUDIO_BYTES = 200_000
MAX_AUDIO_BODY_BYTES = 268_000
AI_ROUTES = {"/v1/vision": MAX_IMAGE_BODY_BYTES, "/v1/transcribe": MAX_AUDIO_BODY_BYTES, "/v1/chat": 65536}
ADMIN_ROUTES = {"/admin/state", "/admin/publish", "/admin/revert", "/admin/preview", "/admin/key", "/admin/local/start"}


def open_admin_page(url):
    if os.name == "nt":
        os.startfile(url)
    else:
        webbrowser.open(url)


def validate_unicode(payload):
    pending = [payload]
    while pending:
        value = pending.pop()
        if isinstance(value, str):
            value.encode("utf-8")
        elif isinstance(value, dict):
            pending.extend(value.keys())
            pending.extend(value.values())
        elif isinstance(value, list):
            pending.extend(value)


class AgriDoctorServer(ThreadingHTTPServer):
    if os.name == "nt":
        allow_reuse_address = False

    def server_bind(self):
        if os.name == "nt":
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()

    def __init__(self, address, demo, ai_access_token="", allow_local_ai=False, chat_state=None, admin_token=None):
        self.demo = demo
        self.ai_access_token = ai_access_token.encode("ascii")
        self.allow_local_ai = allow_local_ai
        self.admin_token = (admin_token or secrets.token_urlsafe(32)).encode("ascii")
        self.chat = ChatAgent(DATABASE, chat_state or CHAT_STATE)
        self.connections = BoundedSemaphore(12)
        self.connection_counts = {}
        self.connection_lock = Lock()
        self.header_timeout = 5
        self.body_timeout = 10
        self.ai_workers = BoundedSemaphore(2)
        self.ai_history = deque()
        self.ai_history_lock = Lock()
        super().__init__(address, RequestHandler)

    def process_request(self, request, client_address):
        address = client_address[0]
        with self.connection_lock:
            accepted = self.connection_counts.get(address, 0) < 3 and self.connections.acquire(blocking=False)
            if accepted:
                self.connection_counts[address] = self.connection_counts.get(address, 0) + 1
        if not accepted:
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.release_connection(address)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.release_connection(client_address[0])

    def release_connection(self, address):
        with self.connection_lock:
            remaining = self.connection_counts[address] - 1
            if remaining:
                self.connection_counts[address] = remaining
            else:
                del self.connection_counts[address]
            self.connections.release()

    def reserve_ai_request(self):
        with self.ai_history_lock:
            now = monotonic()
            while self.ai_history and now - self.ai_history[0] >= 60:
                self.ai_history.popleft()
            if len(self.ai_history) >= 12:
                return False
            self.ai_history.append(now)
            return True


class DeadlineInput(io.RawIOBase):
    """Apply the remaining total budget to every socket read, including idle reads."""
    def __init__(self, handler):
        self.handler = handler
        self.raw = handler.connection.makefile("rb", buffering=0)

    def readable(self):
        return True

    def readinto(self, buffer):
        remaining = self.handler.deadline - monotonic() if self.handler.deadline is not None else 10
        if remaining <= 0:
            raise TimeoutError("Request deadline exceeded")
        self.handler.connection.settimeout(min(10, remaining))
        return self.raw.readinto(buffer)

    def close(self):
        self.raw.close()
        super().close()


class RequestHandler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)
        self.deadline = None
        self.rfile.close()
        self.rfile = io.BufferedReader(DeadlineInput(self))
        self.start_deadline(self.server.header_timeout)

    def start_deadline(self, seconds):
        self.deadline = monotonic() + seconds

    def cancel_deadline(self):
        self.deadline = None
        self.connection.settimeout(10)

    def handle(self):
        try:
            super().handle()
        except (ConnectionError, TimeoutError):
            pass

    def finish(self):
        self.cancel_deadline()
        try:
            super().finish()
        except (ConnectionError, TimeoutError):
            pass

    def send_json(self, status, payload):
        try:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        except UnicodeEncodeError:
            status = HTTPStatus.BAD_GATEWAY
            data = json.dumps({"error": "Dữ liệu phản hồi chưa hợp lệ. Bác thử lại."}, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.cancel_deadline()
        if self.path == "/health":
            self.send_json(HTTPStatus.OK, {"status": "ok", "demo": self.server.demo})
        elif self.path == "/admin" and self.local_admin():
            data=Path(__file__).with_name("admin.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type","text/html; charset=utf-8")
            self.send_header("Content-Length",str(len(data)))
            self.send_header("Cache-Control","no-store")
            self.send_header("Referrer-Policy","no-referrer")
            self.send_header("X-Frame-Options","DENY")
            self.send_header("Content-Security-Policy","default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Đường dẫn không tồn tại."})

    def do_POST(self):
        self.start_deadline(self.server.body_timeout)
        if self.path not in AI_ROUTES and self.path not in ADMIN_ROUTES and self.path != "/v1/lookup":
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "Đường dẫn không tồn tại."})
            return
        if self.path in AI_ROUTES and not self.ai_authorized():
            return
        if self.path in ADMIN_ROUTES and (not self.local_admin() or not hmac.compare_digest(
                self.headers.get("Authorization","").encode(),b"Bearer "+self.server.admin_token)):
            self.send_json(403, {"error":"Cần mở trang bằng liên kết quản trị riêng trên máy chủ."})
            return
        if self.headers.get_content_type() != "application/json":
            self.send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Cần gửi dữ liệu JSON."})
            return

        try:
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit():
                raise ValueError("Expected a single message length")
            size = int(lengths[0])
            if size == 0:
                raise ValueError("Thiếu nội dung tra cứu.")
            limit = 65536 if self.path in ADMIN_ROUTES else AI_ROUTES.get(self.path, MAX_BODY_BYTES)
            if size > limit:
                self.send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Dữ liệu gửi lên quá dài."})
                return
            body = self.rfile.read(size)
            self.cancel_deadline()
            if len(body) != size:
                raise ValueError("Incomplete request")
            payload = json.loads(body.decode("utf-8"))
            validate_unicode(payload)
        except (ValueError, RecursionError, OSError):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Nội dung JSON không hợp lệ."})
            return

        if not isinstance(payload, dict):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Nội dung tra cứu không hợp lệ."})
            return

        if self.path in ADMIN_ROUTES:
            self.manage_chat(payload)
            return
        crop, symptom = payload.get("crop_id"), payload.get("symptoms")
        if not isinstance(crop, str) or crop not in CROPS:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Hãy chọn một loại cây hợp lệ."})
            return
        if self.path in AI_ROUTES:
            self.process_ai(crop, payload)
            return
        if not isinstance(symptom, str) or not symptom.strip() or len(symptom) > 500:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Nhập triệu chứng từ 1 đến 500 ký tự."})
            return

        context = {}
        for field in OPTIONAL_FIELDS:
            value = payload.get(field)
            if value is None:
                continue
            if not isinstance(value, str) or len(value) > 100:
                self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Thông tin bổ sung phải tối đa 100 ký tự."})
                return
            context[field] = value.strip() or None

        try:
            result = lookup(DATABASE, crop, symptom.strip(), demo=self.server.demo, **context)
        except sqlite3.Error as error:
            self.log_error("Lookup database error: %s", error)
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Kho tri thức hiện chưa truy cập được."})
            return
        self.send_json(HTTPStatus.OK, result)

    def ai_authorized(self):
        token = self.server.ai_access_token
        if not token:
            if self.server.allow_local_ai:
                return True
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Máy chủ chưa bật quyền truy cập AI."})
            return False
        value = self.headers.get("Authorization", "").encode("utf-8")
        if hmac.compare_digest(value, b"Bearer " + token):
            return True
        self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Mã truy cập máy chủ AI thiếu hoặc không đúng."})
        return False

    def local_admin(self):
        host = self.headers.get("Host","")
        return (self.client_address[0] == "127.0.0.1" and host in {
            f"localhost:{self.server.server_port}", f"127.0.0.1:{self.server.server_port}"}
            and not any(h.lower().startswith(("x-forwarded","forwarded")) for h in self.headers))

    def manage_chat(self, payload):
        agent=self.server.chat
        try:
            if self.path in ("/admin/publish","/admin/revert"):
                agent.publish(payload,self.path=="/admin/revert")
            elif self.path == "/admin/preview":
                if not isinstance(payload.get("config"),dict):
                    raise ChatError(400,"Thiếu bản nháp.")
                self.send_json(200,agent.reply(payload,payload["config"]))
                return
            elif self.path == "/admin/key":
                key=payload.get("key")
                if not isinstance(key,str) or not 16<=len(key)<=256 or not all(33<=ord(c)<=126 for c in key):
                    raise ChatError(400,"Khóa API không hợp lệ.")
                agent.key=key
            elif self.path == "/admin/local/start":
                start_local_model()
            self.send_json(200,agent.panel())
        except LocalUnavailable as error:
            self.send_json(503,{"error":str(error)})
        except ChatError as error:
            self.send_json(error.status,{"error":str(error)})
        except sqlite3.Error:
            self.send_json(503,{"error":"Kho quản trị đang bận. Vui lòng thử lại."})

    def process_ai(self, crop, payload):
        if not self.server.ai_workers.acquire(blocking=False):
            self.send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Máy chủ AI đang bận. Bác thử lại sau."})
            return
        try:
            if self.path == "/v1/vision":
                self.vision(crop, payload.get("image"))
            elif not self.server.reserve_ai_request():
                self.send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Đã đạt giới hạn yêu cầu AI. Bác thử lại sau một phút."})
            elif self.path == "/v1/chat":
                try:
                    self.send_json(200,self.server.chat.reply(payload))
                except ChatError as error:
                    self.send_json(error.status,{"error":str(error)})
                except sqlite3.Error:
                    self.send_json(503,{"error":"Kho trợ lý đang bận. Bác thử lại sau."})
            else:
                self.transcribe(crop, payload.get("audio"))
        finally:
            self.server.ai_workers.release()

    def vision(self, crop, image):
        try:
            decoded = base64.b64decode(image, validate=True)
            if not valid_jpeg(decoded):
                raise ValueError("Expected JPEG")
        except (ValueError, TypeError, binascii.Error):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Ảnh JPEG chưa hợp lệ hoặc quá lớn."})
            return
        except ImageValidationUnavailable as error:
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(error)})
            return
        if not self.server.reserve_ai_request():
            self.send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Đã đạt giới hạn yêu cầu AI. Bác thử lại sau một phút."})
            return
        try:
            self.send_json(HTTPStatus.OK, analyze_image(crop, image))
        except VisionUnavailable as error:
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(error)})

    def transcribe(self, crop, audio):
        try:
            if not isinstance(audio, str):
                raise ValueError("Expected base64 text")
            decoded = base64.b64decode(audio, validate=True)
            verified = validated_recording(decoded) if len(decoded) <= MAX_AUDIO_BYTES else None
            if not verified:
                raise ValueError("Expected a short MPEG-4 recording")
        except (ValueError, TypeError, binascii.Error):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Bản ghi âm M4A chưa hợp lệ, quá dài hoặc quá lớn."})
            return
        except TranscriptionUnavailable as error:
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(error)})
            return
        try:
            self.send_json(HTTPStatus.OK, transcribe_audio(crop, base64.b64encode(verified).decode("ascii")))
        except TranscriptionUnavailable as error:
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": str(error)})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--demo", action="store_true", help="Include records pending expert review.")
    parser.add_argument("--open-admin", action="store_true", help="Open the local private agent dashboard.")
    parser.add_argument("--allow-local-ai", action="store_true", help="Allow AI without an access token for local development only.")
    parser.add_argument("--local-ai", action="store_true", help="Start the installed private CPU model.")
    args = parser.parse_args()
    token = os.environ.get("AGRIDOCTOR_AI_TOKEN", "").strip()
    if token and (len(token) < 24 or len(token) > 200 or not token.isascii() or not all(33 <= ord(char) <= 126 for char in token)):
        parser.error("AGRIDOCTOR_AI_TOKEN must be 24–200 printable ASCII characters without spaces.")
    if args.allow_local_ai and token:
        parser.error("Choose either AGRIDOCTOR_AI_TOKEN or --allow-local-ai.")
    admin_token = local_admin_token(CHAT_STATE)
    admin_url = f"http://127.0.0.1:{args.port}/admin#{admin_token}"
    if args.open_admin:
        connection = HTTPConnection("127.0.0.1", args.port, timeout=2)
        try:
            connection.request("POST", "/admin/state", "{}", {
                "Content-Type": "application/json", "Authorization": "Bearer " + admin_token})
            response = connection.getresponse()
            if response.status == 200:
                open_admin_page(admin_url)
                raise SystemExit(0)
        except OSError:
            pass
        finally:
            connection.close()
    with AgriDoctorServer(("127.0.0.1", args.port), args.demo, token, args.allow_local_ai, admin_token=admin_token) as server:
        if args.local_ai:
            snapshot = server.chat.panel()
            if 'engine' not in snapshot['config']:
                server.chat.publish({'version':snapshot['version'],
                                     'config':dict(snapshot['config'],engine='local',cloud=False)})
            if server.chat.panel()['config'].get('engine') == 'local':
                try:
                    start_local_model()
                except LocalUnavailable as error:
                    print(str(error), flush=True)
        if args.open_admin:
            open_admin_page(admin_url)
        print(f"AgriDoctor API: http://127.0.0.1:{server.server_port} (demo={args.demo})", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

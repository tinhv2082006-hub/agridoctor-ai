"""Private CPU inference. This module never connects to an external AI service."""
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
from threading import Lock

from admin_access import protect

ROOT = Path(__file__).resolve().parent.parent / ".local-ai"
MODEL = ROOT / "Qwen3-4B-Q4_K_M.gguf"
EXECUTABLE = ROOT / "runtime" / "llama-server.exe"
MODEL_ID = "agridoctor-qwen3-4b"
MANAGED_RUNTIME = "AGRIDOCTOR_LLAMA_PORT" in os.environ
PORT = int(os.environ.get("AGRIDOCTOR_LLAMA_PORT", "8766"))
_generation = Lock()
_startup = Lock()
_process = None


class LocalUnavailable(Exception):
    pass


class LocalBusy(LocalUnavailable):
    pass


def credential(create=False):
    if MANAGED_RUNTIME:
        key = os.environ.get("LLAMA_API_KEY", "")
        if len(key) != 64 or any(c not in "0123456789abcdef" for c in key):
            raise LocalUnavailable("Máy chủ chưa cấu hình mã kết nối AI cục bộ.")
        return key
    path = ROOT / ("runtime-key.dpapi" if os.name == "nt" else "runtime-key")
    if path.exists():
        data = path.read_bytes()
        key = (protect(data, decrypt=True) if os.name == "nt" else data).decode("ascii")
        if len(key) != 64 or any(c not in "0123456789abcdef" for c in key):
            raise LocalUnavailable("Thông tin kết nối AI cục bộ chưa hợp lệ.")
        return key
    if not create:
        raise LocalUnavailable("Mở lại Mo_Tro_Ly.cmd để khởi động AI cục bộ.")
    ROOT.mkdir(parents=True, exist_ok=True)
    key = secrets.token_hex(32)
    data = protect(key.encode()) if os.name == "nt" else key.encode()
    with path.open("xb") as stream:
        stream.write(data)
    if os.name != "nt":
        path.chmod(0o600)
    return key


def request(path, body=None, timeout=2):
    # HTTPConnection avoids environment proxy settings and a configurable destination.
    connection = HTTPConnection("127.0.0.1", PORT, timeout=timeout)
    try:
        headers = {"Authorization": "Bearer " + credential()}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        connection.request("POST" if body is not None else "GET", path, data, headers)
        response = connection.getresponse()
        content = response.read(131073)
        if len(content) > 131072:
            raise LocalUnavailable("AI trả về nội dung quá dài.")
        if response.status != 200:
            if response.status == 503:
                raise LocalUnavailable("Mô hình đang khởi động. Bác đợi một chút rồi gửi lại.")
            raise LocalUnavailable("Bộ xử lý AI cục bộ chưa xử lý được câu hỏi.")
        return json.loads(content)
    except (OSError, ValueError) as error:
        message = ("Chưa kết nối được AI cục bộ trên máy chủ." if MANAGED_RUNTIME else
                   "Chưa kết nối được AI cục bộ. Mở lại Mo_Tro_Ly.cmd.")
        raise LocalUnavailable(message) from error
    finally:
        connection.close()


def status():
    installed = MANAGED_RUNTIME or (EXECUTABLE.is_file() and MODEL.is_file())
    ready = False
    if installed:
        try:
            ready = any(m.get("id") == MODEL_ID for m in request("/v1/models").get("data", []))
        except LocalUnavailable:
            pass
    return {"installed": installed, "ready": ready, "model": "Qwen3-4B · CPU",
            "message": ("AI cục bộ sẵn sàng." if ready else
                        "Mô hình cục bộ trên máy chủ chưa sẵn sàng." if MANAGED_RUNTIME else
                        "Mô hình chưa sẵn sàng. Mở lại Mo_Tro_Ly.cmd." if installed else
                        "Chưa cài mô hình. Chạy Cai_AI_Cuc_Bo.cmd một lần.")}


def start():
    global _process
    with _startup:
        if _process is not None and _process.poll() is None:
            return None
        _process = _start()
        return _process


def _start():
    if status()["ready"]:
        return None
    if MANAGED_RUNTIME:
        raise LocalUnavailable("Mô hình cục bộ trên máy chủ chưa sẵn sàng.")
    if not EXECUTABLE.is_file() or not MODEL.is_file():
        raise LocalUnavailable("Chạy Cai_AI_Cuc_Bo.cmd để tải mô hình trước.")
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=1):
            raise LocalUnavailable("Bộ xử lý đang khởi động hoặc cổng AI đang được dùng. Bấm kiểm tra lại sau vài giây.")
    except OSError:
        pass
    key = credential(create=True)
    env = dict(os.environ, LLAMA_API_KEY=key)
    # A single slot keeps CPU use bounded; all generation stays on this computer.
    command = [str(EXECUTABLE), "-m", str(MODEL), "--alias", MODEL_ID,
               "--host", "127.0.0.1", "--port", str(PORT), "-ngl", "0",
               "-t", "4", "-tb", "4", "-c", "6144", "-np", "1",
               "-b", "256", "-ub", "128", "--cache-ram", "128",
               "--reasoning", "off", "--reasoning-budget", "0", "--no-webui"]
    with (ROOT / "runtime.log").open("ab") as log:
        return subprocess.Popen(command, cwd=EXECUTABLE.parent, env=env,
                                stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)


def generate(system, turns, allowed_ids=None):
    if not _generation.acquire(blocking=False):
        raise LocalBusy("AI cục bộ đang trả lời một câu hỏi khác. Bác thử lại sau.")
    try:
        schema = {"type": "object", "properties": {
            "answer": {"type": "string"},
            "case_ids": {"type": "array", "items": {"type": "string"}}},
            "required": ["answer", "case_ids"], "additionalProperties": False}
        if allowed_ids:
            schema['properties']['case_ids']['items']['enum'] = allowed_ids
            schema['properties']['case_ids']['minItems'] = 1
            schema['properties']['case_ids']['maxItems'] = min(3, len(allowed_ids))
        elif allowed_ids is not None:
            schema['properties']['case_ids']['maxItems'] = 0
        history = [{"role": t["role"], "content": t["text"][:600]}
                   for t in turns[-5:]]
        result = request("/v1/chat/completions", {
            "model": MODEL_ID, "messages": [{"role": "system", "content": system}, *history],
            "stream": False, "temperature": 0.4, "top_p": 0.8, "seed": 42,
            "max_tokens": 360, "cache_prompt": True,
            "response_format": {"type": "json_schema", "json_schema": {
                "name": "agridoctor_answer", "strict": True, "schema": schema}}}, timeout=105)
        choice = result["choices"][0]
        if choice.get("finish_reason") != "stop":
            raise LocalUnavailable("Câu trả lời của AI chưa hoàn tất. Bác thử câu hỏi ngắn hơn.")
        answer = json.loads(choice["message"]["content"])
        answer["timings"] = {k: result.get("timings", {}).get(k)
                             for k in ("prompt_ms", "predicted_ms", "predicted_per_second")}
        return answer
    except (KeyError, IndexError, TypeError, ValueError) as error:
        raise LocalUnavailable("AI trả về nội dung chưa đúng định dạng hội thoại.") from error
    finally:
        _generation.release()

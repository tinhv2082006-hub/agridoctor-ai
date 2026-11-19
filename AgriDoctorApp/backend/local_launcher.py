"""Open the private dashboard, keeping server windows out of the user's way."""
from http.client import HTTPConnection
import os
from pathlib import Path
import subprocess
import sys
import time

from admin_access import local_admin_token
from server import open_admin_page

ROOT = Path(__file__).resolve().parent


def running(token):
    connection = HTTPConnection("127.0.0.1", 8765, timeout=2)
    try:
        connection.request("POST", "/admin/state", "{}", {
            "Content-Type": "application/json", "Authorization": "Bearer " + token})
        response = connection.getresponse()
        response.read()
        return response.status == 200
    except OSError:
        return False
    finally:
        connection.close()


def main():
    token = local_admin_token(ROOT / "agent-state.sqlite")
    if not running(token):
        with (ROOT.parent / ".local-ai" / "backend.log").open("ab") as log:
            subprocess.Popen([sys.executable, "-X", "utf8", str(ROOT / "server.py"),
                              "--allow-local-ai", "--local-ai"], cwd=ROOT,
                             stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        for _ in range(30):
            if running(token):
                break
            time.sleep(.5)
        else:
            raise SystemExit("Không mở được máy chủ. Xem .local-ai/backend.log.")
    open_admin_page(f"http://127.0.0.1:8765/admin#{token}")


if __name__ == "__main__":
    (ROOT.parent / ".local-ai").mkdir(exist_ok=True)
    main()

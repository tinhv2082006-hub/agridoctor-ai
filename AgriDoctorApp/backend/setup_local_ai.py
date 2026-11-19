"""Download the pinned, official CPU runtime and model; verify before use."""
import hashlib
import json
from pathlib import Path
import shutil
import time
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parent.parent / ".local-ai"
ASSETS = (
    ("llama-b12359-bin-win-cpu-x64.zip",
     "https://github.com/ggml-org/llama.cpp/releases/download/b12359/llama-b12359-bin-win-cpu-x64.zip",
     "837c1655e87df3b66989f4be33a792a1399c785bf259038dc75c900096683e69"),
    ("Qwen3-4B-Q4_K_M.gguf",
     "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
     "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5"),
)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(name, url, expected):
    target = ROOT / name
    if target.is_file() and digest(target) == expected:
        print(f"Verified: {name}", flush=True)
        return target
    partial = target.with_suffix(target.suffix + ".part")
    for attempt in range(5):
        offset = partial.stat().st_size if partial.exists() else 0
        headers = {"User-Agent": "AgriDoctor-local-setup/1.0"}
        if offset:
            headers["Range"] = f"bytes={offset}-"
        try:
            with urlopen(Request(url, headers=headers), timeout=45) as response:
                append = response.status == 206 and offset > 0
                if append and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                    raise ValueError("Unexpected download range")
                if not append:
                    offset = 0
                total = offset + int(response.headers.get("Content-Length", 0))
                count, last = offset, time.monotonic()
                print(f"Downloading {name}: {offset / 1e6:.0f}/{total / 1e6:.0f} MB", flush=True)
                with partial.open("ab" if append else "wb") as stream:
                    while block := response.read(1024 * 1024):
                        stream.write(block)
                        count += len(block)
                        if time.monotonic() - last > 8:
                            print(f"  {count / 1e6:.0f}/{total / 1e6:.0f} MB", flush=True)
                            last = time.monotonic()
                if total and count != total:
                    raise OSError("Incomplete download")
            print(f"Checking SHA-256: {name}", flush=True)
            if digest(partial) != expected:
                # Only this task's invalid partial download is removed.
                partial.unlink()
                raise ValueError("Checksum mismatch")
            partial.replace(target)
            return target
        except (OSError, ValueError) as error:
            print(f"Retry {attempt + 1}: {type(error).__name__}", flush=True)
            if attempt == 4:
                raise
            time.sleep(2)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(ROOT).free < 3_500_000_000 and not (ROOT / ASSETS[1][0]).exists():
        raise SystemExit("Need at least 3.5 GB free for the local AI model.")
    archive = download(*ASSETS[0])
    runtime = ROOT / "runtime"
    runtime.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            resolved = (runtime / member.filename).resolve()
            if not resolved.is_relative_to(runtime.resolve()):
                raise ValueError("Unsafe archive path")
        bundle.extractall(runtime)
    download(*ASSETS[1])
    manifest = {"runtime": "llama.cpp b12359", "model": "Qwen3-4B-Q4_K_M",
                "assets": [{"file": n, "url": u, "sha256": s} for n, u, s in ASSETS]}
    (ROOT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Local AI installed and verified.", flush=True)


if __name__ == "__main__":
    main()

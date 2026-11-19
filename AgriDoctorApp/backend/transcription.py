"""Convert a short Vietnamese voice recording to text using Gemini Transcribe."""

import json
import os
import struct
import shutil
import subprocess
import tempfile
import io
import wave
from pathlib import Path
from urllib.request import Request, urlopen


VOCABULARY = {
    "rice": ["bông bạc", "cổ gié", "đạo ôn", "khô vằn", "rầy nâu", "sâu đục thân"],
    "coffee": ["cà phê vối", "rỉ sắt", "đốm mắt cua", "lở cổ rễ", "nấm hồng"],
    "durian": ["xì mủ", "rỉ mủ", "thối trái", "rầy nhảy", "thán thư"],
}


class TranscriptionUnavailable(RuntimeError):
    pass


def validated_recording(audio):
    """Validate the container and decoded samples, not just declared duration."""
    if not 12 <= len(audio) <= 200_000:
        return None

    def boxes(data):
        offset = 0
        while offset + 8 <= len(data):
            size, kind = struct.unpack_from(">I4s", data, offset)
            header_size = 8
            if size == 1:
                size, = struct.unpack_from(">Q", data, offset + 8)
                header_size = 16
            elif size == 0:
                size = len(data) - offset
            if not header_size <= size <= len(data) - offset:
                raise ValueError("Invalid box size")
            yield kind, data[offset + header_size:offset + size]
            offset += size
        if offset != len(data):
            raise ValueError("Truncated box")

    try:
        chunks = list(boxes(memoryview(audio)))
        if (not chunks or chunks[0][0] != b"ftyp"
                or not any(kind == b"mdat" and data for kind, data in chunks)
                or any(kind == b"moof" for kind, _ in chunks)):
            return False
        movie, = (data for kind, data in chunks if kind == b"moov")
        header, = (data for kind, data in boxes(movie) if kind == b"mvhd")
        if header[0] == 0:
            scale, duration = struct.unpack_from(">II", header, 12)
        elif header[0] == 1:
            scale, duration = struct.unpack_from(">IQ", header, 20)
        else:
            return False
        if not (scale > 0 and 0 < duration <= scale * 17):
            return False
    except (ValueError, IndexError, struct.error):
        return False

    executable = os.environ.get("AGRIDOCTOR_FFMPEG", "") or shutil.which("ffmpeg")
    if not executable:
        try:
            import imageio_ffmpeg
            executable = imageio_ffmpeg.get_ffmpeg_exe()
        except (ImportError, RuntimeError, OSError) as error:
            raise TranscriptionUnavailable("Máy chủ chưa có bộ kiểm tra âm thanh. Cài requirements.txt hoặc đặt AGRIDOCTOR_FFMPEG.") from error
    try:
        with tempfile.TemporaryDirectory(prefix="agri-audio-") as directory:
            path = Path(directory) / "recording.m4a"
            path.write_bytes(audio)
            result = subprocess.run([
                executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
                "-max_alloc", "16777216", "-threads", "1", "-protocol_whitelist", "file,pipe",
                "-f", "mov", "-i", str(path), "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-threads", "1", "-ac", "1", "-ar", "16000", "-t", "18", "-f", "s16le", "pipe:1",
            ], capture_output=True, timeout=5, check=False,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            if result.returncode != 0 or not 0 < len(result.stdout) <= 17 * 16000 * 2:
                return None
            output = io.BytesIO()
            with wave.open(output, "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16000)
                wav.writeframes(result.stdout)
            return output.getvalue()
    except subprocess.TimeoutExpired:
        return False
    except OSError as error:
        raise TranscriptionUnavailable("Bộ kiểm tra âm thanh chưa hoạt động. Bác dùng mục Nhập bằng Android.") from error


def is_short_recording(audio):
    return bool(validated_recording(audio))


def transcribe_audio(crop, encoded_audio):
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        raise TranscriptionUnavailable("Máy chủ chưa có khóa Gemini. Bác có thể dùng mục Nhập bằng Android.")

    payload = {
        "contents": [{"parts": [{"inlineData": {"mimeType": "audio/wav", "data": encoded_audio}}]}],
        "generationConfig": {"audioTranscriptionConfig": {
            "languageCodes": ["vi-VN"],
            "customVocabulary": VOCABULARY[crop],
            "mode": "VERBATIM",
        }},
    }
    request = Request(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"x-goog-api-key": key, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=24) as reply:
            result = json.loads(reply.read(128_000))
        candidate = result["candidates"][0]
        if candidate.get("finishReason") != "STOP":
            raise ValueError("Incomplete transcription")
        parts = candidate["content"]["parts"]
        transcript = " ".join(part["text"].strip() for part in parts if "text" in part and not part.get("thought")).strip()
    except (OSError, ValueError, TypeError, KeyError, IndexError, AttributeError) as error:
        raise TranscriptionUnavailable("AI chưa chuyển được đoạn ghi âm thành chữ. Bác thử lại hoặc dùng mục Nhập bằng Android.") from error

    if len(transcript) > 500:
        return {"transcript": "", "message": "Câu nói hơi dài. Bác thử nói từng dấu hiệu trong một câu ngắn."}
    return {"transcript": transcript}

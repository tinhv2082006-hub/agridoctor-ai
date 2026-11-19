"""Bounded JPEG decoding using the already-required local FFmpeg installation."""
import os
import shutil
import subprocess


class ImageValidationUnavailable(RuntimeError):
    pass


def valid_jpeg(data):
    if not 4 <= len(data) <= 800_000 or not data.startswith(b"\xff\xd8\xff") or not data.endswith(b"\xff\xd9"):
        return False
    executable = os.environ.get("AGRIDOCTOR_FFMPEG", "") or shutil.which("ffmpeg")
    if not executable:
        try:
            import imageio_ffmpeg
            executable = imageio_ffmpeg.get_ffmpeg_exe()
        except (ImportError, RuntimeError, OSError) as error:
            raise ImageValidationUnavailable("Bộ kiểm tra ảnh trên máy chủ chưa hoạt động.") from error
    try:
        # Require an actual MJPEG frame. Bound input, pixels, allocation and runtime;
        # emit only one grayscale pixel rather than capturing a full decoded image.
        result = subprocess.run([
            executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
            "-max_alloc", "67108864", "-max_pixels", "16777216", "-threads", "1",
            "-protocol_whitelist", "pipe", "-f", "image2pipe", "-c:v", "mjpeg", "-i", "pipe:0",
            "-map", "0:v:0", "-an", "-sn", "-dn", "-threads", "1", "-frames:v", "1",
            "-vf", "scale=1:1", "-pix_fmt", "gray", "-f", "rawvideo", "pipe:1",
        ], input=data, capture_output=True, timeout=5, check=False,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        return result.returncode == 0 and len(result.stdout) == 1
    except subprocess.TimeoutExpired:
        return False
    except OSError as error:
        raise ImageValidationUnavailable("Bộ kiểm tra ảnh trên máy chủ chưa hoạt động.") from error

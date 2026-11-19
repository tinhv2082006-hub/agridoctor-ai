"""Regression tests: no Gemini requests or real credentials."""
import base64
import http.client
import io
import json
from pathlib import Path
import socket
import sqlite3
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import wave
from functools import lru_cache

import imageio_ffmpeg
from lookup import lookup
from server import AgriDoctorServer
from transcription import is_short_recording, validated_recording, TranscriptionUnavailable
from image_validation import valid_jpeg, ImageValidationUnavailable

DATABASE = Path(__file__).with_name("agridoctor.sqlite")


def fake_audio():
    def box(kind, data): return struct.pack(">I4s", len(data) + 8, kind) + data
    header = b"\0" * 12 + struct.pack(">II", 1000, 1000)
    return box(b"ftyp", b"M4A ") + box(b"mdat", b"not audio" * 100) + box(b"moov", box(b"mvhd", header))


@lru_cache(maxsize=1)
def sample_jpeg():
    return subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-nostdin", "-loglevel", "error",
                           "-f", "lavfi", "-i", "color=c=green:s=32x24", "-frames:v", "1",
                           "-c:v", "mjpeg", "-f", "image2pipe", "pipe:1"],
                          capture_output=True, check=True, timeout=5).stdout


class LookupTests(unittest.TestCase):
    def test_negation_uncertainty_and_conflicts(self):
        for text in ("hổng thấy bông bạc", "hông có bông bạc", "hình như bông bạc",
                     "không chắc là bông bạc", "bông bạc và không có bông bạc",
                     "bông bạc, à không có bông bạc"):
            with self.subTest(text=text):
                result = lookup(DATABASE, "rice", text, demo=True)
                self.assertEqual([], result["candidate_ids"])
                self.assertEqual("unconfirmed_sign", result["reason"])

    def test_positive_sign_after_unrelated_negation(self):
        result = lookup(DATABASE, "rice", "không thấy sâu nhưng bông bạc", demo=True)
        self.assertEqual(["R02", "R08"], result["candidate_ids"])

    def test_regional_signs(self):
        for crop, text, expected in (("durian", "gốc sầu riêng rỉ mủ", ["D02"]),
                                     ("rice", "bông lúa nó trắng hết", ["R02", "R08"]),
                                     ("coffee", "hình như rỉ sắt", [])):
            with self.subTest(text=text):
                self.assertEqual(expected, lookup(DATABASE, crop, text, demo=True)["candidate_ids"])

    def test_demo_filter_remains_enforced(self):
        self.assertEqual(["R02", "R08"], lookup(DATABASE, "rice", "bông bạc")["candidate_ids"])
        with tempfile.TemporaryDirectory() as temp:
            fixture = Path(temp) / "catalog.sqlite"
            shutil.copyfile(DATABASE, fixture)
            from contextlib import closing
            with closing(sqlite3.connect(fixture)) as db, db:
                db.execute("UPDATE cases SET review_status='pending' WHERE id IN ('R02','R08')")
            self.assertEqual([], lookup(fixture, "rice", "bông bạc")["candidate_ids"])
            self.assertEqual(["R02","R08"], lookup(fixture, "rice", "bông bạc", demo=True)["candidate_ids"])

    def test_quoted_sql_input_is_only_text(self):
        self.assertEqual([], lookup(DATABASE, "rice", "' OR 1=1 --", demo=True)["candidate_ids"])

    def test_negation_after_sign_is_not_a_positive_match(self):
        for text in ("bông bạc thì không có", "bông bạc không thấy", "bông bạc chưa xuất hiện",
                     "bông bạc thì hông có đâu"):
            with self.subTest(text=text):
                result = lookup(DATABASE, "rice", text, demo=True)
                self.assertEqual([], result["candidate_ids"])
                self.assertEqual("unconfirmed_sign", result["reason"])

    def test_positive_descriptions_are_unchanged(self):
        for text in ("bông bạc", "bông bạc thì có", "bông bạc không chỉ xuất hiện trên lá"):
            self.assertEqual(["R02", "R08"], lookup(DATABASE, "rice", text, demo=True)["candidate_ids"])


class ImageTests(unittest.TestCase):
    def test_real_jpeg_is_accepted(self):
        self.assertTrue(valid_jpeg(sample_jpeg()))

    def test_fake_truncated_and_oversized_jpeg_are_rejected(self):
        for data in (b"\xff\xd8\xff\xd9", sample_jpeg()[:30] + b"\xff\xd9", b"x" * 800001):
            with self.subTest(length=len(data)):
                self.assertFalse(valid_jpeg(data))

    def test_pixel_limit_is_enforced_before_full_decode(self):
        data = bytearray(sample_jpeg())
        marker = data.index(b"\xff\xc0")
        struct.pack_into(">HH", data, marker + 5, 50000, 50000)
        self.assertFalse(valid_jpeg(data))

    def test_decoder_unavailable_fails_closed(self):
        with patch.dict("os.environ", {"AGRIDOCTOR_FFMPEG": "missing-agridoctor-decoder"}):
            with self.assertRaises(ImageValidationUnavailable): valid_jpeg(sample_jpeg())


class AudioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.samples = {}
        for seconds in (1, 15, 20):
            path = Path(cls.directory.name) / f"{seconds}.m4a"
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-nostdin", "-loglevel", "error",
                            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000", "-t", str(seconds),
                            "-ac", "1", "-c:a", "aac", "-b:a", "48000", str(path)], check=True,
                           capture_output=True, timeout=10)
            cls.samples[seconds] = path.read_bytes()

    @classmethod
    def tearDownClass(cls): cls.directory.cleanup()

    def test_fake_container_has_no_decodable_audio(self):
        self.assertFalse(is_short_recording(fake_audio()))

    def test_real_recordings_remain_supported_and_normalized(self):
        for seconds in (1, 15):
            with self.subTest(seconds=seconds):
                verified = validated_recording(self.samples[seconds])
                self.assertTrue(verified)
                with wave.open(io.BytesIO(verified)) as wav:
                    self.assertEqual((1, 2, 16000), (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()))
                    self.assertLessEqual(wav.getnframes() / wav.getframerate(), 17)

    def test_long_audio_is_rejected_even_with_short_movie_header(self):
        data = bytearray(self.samples[20])
        index = data.index(b"mvhd") + 4
        self.assertEqual(0, data[index])
        struct.pack_into(">I", data, index + 16, 1000)
        self.assertFalse(is_short_recording(data))

    def test_malformed_and_oversized_inputs(self):
        for data in (b"", b"not audio", b"\0\0\0\1ftyp", b"x" * 200001):
            self.assertFalse(is_short_recording(data))

    def test_decoder_failure_fails_closed(self):
        with patch.dict("os.environ", {"AGRIDOCTOR_FFMPEG": "missing-agridoctor-decoder"}):
            with self.assertRaises(TranscriptionUnavailable):
                validated_recording(self.samples[1])


class HttpTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.server = AgriDoctorServer(("127.0.0.1", 0), True, "a" * 24, chat_state=Path(temp.name)/"state.sqlite")
        self.server.header_timeout = .4
        self.server.body_timeout = .4
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.sockets = []

    def tearDown(self):
        for connection in self.sockets: connection.close()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def connect(self):
        connection = socket.create_connection(self.server.server_address, timeout=1)
        self.sockets.append(connection)
        return connection

    def wait_slots(self, expected):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if self.server.connections._value == expected: return
            time.sleep(.01)
        self.assertEqual(expected, self.server.connections._value)

    def post(self, route, payload, authorized=True):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        headers = {"Content-Type": "application/json"}
        if authorized: headers["Authorization"] = "Bearer " + "a" * 24
        try:
            connection.request("POST", route, json.dumps(payload), headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally: connection.close()

    def test_one_source_cannot_take_all_global_slots(self):
        for _ in range(12):
            connection = self.connect()
            try: connection.sendall(b"POST /v1/vision HTTP/1.1\r\nHost: localhost\r\n")
            except OSError: pass
        time.sleep(.05)
        self.assertGreaterEqual(self.server.connections._value, 9)
        self.wait_slots(12)
        self.assertEqual(200, self.post("/v1/lookup", {"crop_id": "rice", "symptoms": "bông bạc"})[0])

    def test_absolute_header_deadline_stops_drip_feed(self):
        connection = self.connect()
        connection.sendall(b"POST /v1/vision HTTP/1.1\r\n")
        self.wait_slots(11)
        start = time.monotonic()
        while time.monotonic() - start < .65:
            try: connection.sendall(b"X")
            except OSError: break
            time.sleep(.04)
        self.wait_slots(12)
        self.assertLess(time.monotonic() - start, 1.2)

    def test_absolute_body_deadline_stops_drip_feed(self):
        connection = self.connect()
        connection.sendall(b"POST /v1/lookup HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{")
        self.wait_slots(11)
        start = time.monotonic()
        while time.monotonic() - start < .65:
            try: connection.sendall(b" ")
            except OSError: break
            time.sleep(.04)
        self.wait_slots(12)

    def test_authentication_still_required(self):
        self.assertEqual(401, self.post("/v1/transcribe", {"crop_id": "rice", "audio": "AA=="}, False)[0])
        self.assertEqual(0, len(self.server.ai_history))

    def test_chat_and_private_dashboard_have_distinct_permissions(self):
        message = {"crop_id":"rice","messages":[{"role":"user","text":"bông bạc"}]}
        self.assertEqual(401, self.post("/v1/chat", message, False)[0])
        self.assertEqual(200, self.post("/v1/chat", message)[0])
        self.assertEqual(403, self.post("/admin/state", {})[0])

    def test_invalid_audio_never_reaches_gemini(self):
        with patch("server.transcribe_audio") as gemini:
            status, _ = self.post("/v1/transcribe", {"crop_id": "rice", "audio": base64.b64encode(fake_audio()).decode()})
            self.assertEqual(400, status)
            gemini.assert_not_called()

    def test_lookup_and_input_validation(self):
        self.assertEqual(200, self.post("/v1/lookup", {"crop_id": "rice", "symptoms": "bông bạc"})[0])
        self.assertEqual(400, self.post("/v1/lookup", {"crop_id": "rice", "symptoms": "x" * 501})[0])
        self.assertEqual(400, self.post("/v1/lookup", {"crop_id": "invalid", "symptoms": "test"})[0])

    def test_bad_unicode_is_a_json_error_not_a_disconnect(self):
        for region in ("\ud800", "\udfff"):
            status, response = self.post("/v1/lookup", {"crop_id": "rice", "symptoms": "bông bạc", "region": region})
            self.assertEqual(400, status)
            self.assertIn("error", response)
        status, response = self.post("/v1/lookup", {"crop_id": "rice", "symptoms": "bông bạc", "region": "Đắk Lắk 🌱"})
        self.assertEqual(200, status)
        self.assertEqual("Đắk Lắk 🌱", response["context"]["region"])

    def test_invalid_jpeg_does_not_call_ai_or_use_ai_quota(self):
        with patch("server.analyze_image") as model:
            status, _ = self.post("/v1/vision", {"crop_id": "rice", "image": base64.b64encode(b"\xff\xd8\xff\xd9").decode()})
            self.assertEqual(400, status)
            self.assertEqual(0, len(self.server.ai_history))
            model.assert_not_called()

    def test_valid_jpeg_keeps_existing_vision_response(self):
        expected = {"usable": True, "part": "bông", "signs": ["bông bạc"]}
        with patch("server.analyze_image", return_value=expected) as model:
            status, response = self.post("/v1/vision", {"crop_id": "rice", "image": base64.b64encode(sample_jpeg()).decode()})
            self.assertEqual(200, status)
            self.assertEqual(expected, response)
            self.assertEqual(1, len(self.server.ai_history))
            model.assert_called_once()

    def test_invalid_unicode_from_ai_returns_json_error(self):
        with patch("server.analyze_image", return_value={"usable": True, "part": "\ud800", "signs": []}):
            status, response = self.post("/v1/vision", {"crop_id": "rice", "image": base64.b64encode(sample_jpeg()).decode()})
            self.assertEqual(502, status)
            self.assertIn("error", response)


if __name__ == "__main__": unittest.main(verbosity=2)

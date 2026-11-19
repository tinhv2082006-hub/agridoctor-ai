import os
from pathlib import Path
import tempfile
import unittest

from admin_access import local_admin_token
from server import AgriDoctorServer


class AdminAccessTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Windows DPAPI")
    def test_credential_survives_restart_and_is_encrypted(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.sqlite"
            token = local_admin_token(state)
            self.assertEqual(token, local_admin_token(state))
            self.assertNotIn(token.encode(), state.read_bytes())

    @unittest.skipUnless(os.name == "nt", "Windows exclusive address binding")
    def test_second_server_cannot_take_existing_port(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state.sqlite"
            with AgriDoctorServer(("127.0.0.1", 0), False, chat_state=state) as first:
                with self.assertRaises(OSError):
                    AgriDoctorServer(first.server_address, False, chat_state=state)

from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from chat_agent import ChatAgent, ChatError, provider_issue
from urllib.error import HTTPError


class AgentTests(unittest.TestCase):
    def test_google_error_exposes_only_status_and_known_reason(self):
        body = io.BytesIO(json.dumps({"error":{"message":"private-content",
            "details":[{"reason":"API_KEY_INVALID"}]}}).encode())
        error = HTTPError("https://example.invalid",400,"ignored",{},body)
        self.assertEqual("HTTP 400 · Khóa API không hợp lệ.", provider_issue(error))
        self.assertTrue(body.closed)
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.state = Path(temp.name) / "state.sqlite"
        self.catalog = Path(__file__).with_name("agridoctor.sqlite")
        self.agent = ChatAgent(self.catalog, self.state)
        self.question = {"crop_id":"rice","messages":[{"role":"user","text":"bông bạc"}]}

    def change(self, **settings):
        snapshot = self.agent.panel()
        config = dict(snapshot["config"], **settings)
        self.agent.publish({"version":snapshot["version"],"config":config})

    def test_followup_and_sources(self):
        first = self.agent.reply(self.question)
        self.assertIn("đạo ôn cổ bông",first["answer"])
        self.assertIn("sâu đục thân",first["answer"])
        self.question["messages"].extend([{"role":"assistant","text":first["answer"]},
                                         {"role":"user","text":"Tôi chưa kiểm tra thân"}])
        second = self.agent.reply(self.question)
        self.assertNotEqual(first["answer"],second["answer"])
        self.assertTrue(second["sources"])

    def test_publish_revoke_rollback_and_stale_write(self):
        self.change(cases=["R08"],sources=["RICE_PEST"])
        self.assertEqual(["RICE_PEST"],[s["id"] for s in self.agent.reply(self.question)["sources"]])
        with self.assertRaises(ChatError):
            self.agent.publish({"version":1,"config":{}})
        self.change(enabled=False)
        with self.assertRaises(ChatError):
            self.agent.reply(self.question)
        self.agent.publish({"version":3},revert=True)
        self.assertTrue(self.agent.reply(self.question)["sources"])

    def test_quota_survives_concurrency_and_restart(self):
        self.change(limit=2)
        def call(i):
            try:
                self.agent.reply(self.question)
                return 200
            except ChatError as error:
                return error.status
        with ThreadPoolExecutor(4) as pool:
            self.assertEqual([200,200,429,429],sorted(pool.map(call,range(4))))
        restarted=ChatAgent(self.catalog,self.state)
        self.assertEqual(2,restarted.panel()["calls"])
        with self.assertRaises(ChatError):
            restarted.reply(self.question)

    def test_cloud_draft_never_sends_unselected_evidence(self):
        self.agent.key="mock-key"
        draft=dict(self.agent.panel()["config"],cloud=True,cases=["R02"],sources=["RICE_BLAST"])
        candidate={"finishReason":"STOP","content":{"parts":[{"text":json.dumps({"answer":"Bác quan sát cổ bông nhé.","case_ids":["R02"]})}]}}
        with patch("chat_agent.urlopen",return_value=io.BytesIO(json.dumps({"candidates":[candidate]}).encode())) as call:
            response=self.agent.reply(self.question,draft)
        body=json.loads(call.call_args.args[0].data)
        self.assertIn("R02",body["systemInstruction"]["parts"][0]["text"])
        self.assertNotIn("R08",body["systemInstruction"]["parts"][0]["text"])
        self.assertEqual("Gemini",response["mode"])
        self.assertEqual("RICE_BLAST",response["sources"][0]["id"])
        self.assertFalse(self.agent.panel()["config"]["cloud"])
        with patch("chat_agent.urlopen",side_effect=OSError):
            self.assertIn("gián đoạn",self.agent.reply(self.question,draft)["mode"])
        self.assertEqual(1,self.agent.panel()["errors"])

    def test_engine_selection_respects_opt_in_and_preserves_published_engine(self):
        self.agent.key = "mock-key"
        request = dict(self.question, engine="gemini")
        with patch.dict("os.environ", {"AGRIDOCTOR_CHAT_ENGINE_SELECTION": "0"}):
            with self.assertRaises(ChatError) as rejected:
                self.agent.reply(request)
        self.assertEqual(400, rejected.exception.status)
        self.assertEqual(0, self.agent.panel()["calls"])
        with patch.dict("os.environ", {"AGRIDOCTOR_CHAT_ENGINE_SELECTION": "1"}):
            with patch("chat_agent.gemini_reply", return_value={"answer": "Em chào bác.", "mode": "Gemini", "sources": []}):
                self.assertEqual("Gemini", self.agent.reply(request)["mode"])
        self.assertFalse(self.agent.panel()["config"]["cloud"])

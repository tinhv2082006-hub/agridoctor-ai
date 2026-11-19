from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from agent_workflow import KnowledgeWorkflow
from chat_agent import ChatAgent, ChatError
import local_model


class LocalAgentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.agent = ChatAgent(Path(__file__).with_name('agridoctor.sqlite'),
                               Path(temporary.name) / 'state.sqlite')
        self.question = {'crop_id': 'rice', 'messages': [{'role': 'user', 'text': 'Bông lúa bạc trắng'}]}

    def config(self, **kwargs):
        return dict(self.agent.panel()['config'], engine='local', **kwargs)

    def allowed(self, crop):
        return [dict(case, refs=[]) for case in self.agent.cases.values() if case['crop_id'] == crop]

    def test_local_never_calls_cloud_and_respects_selected_sources(self):
        config = self.config(cases=['R02'], sources=['RICE_BLAST'])
        generated = {'answer': 'Em tìm được một mục để đối chiếu. Bác thấy cổ bông có vết thâm không?', 'case_ids': ['R02']}
        with patch('chat_agent.local_generate', return_value=generated) as local, patch('chat_agent.gemini_reply') as cloud:
            response = self.agent.reply(self.question, config)
        cloud.assert_not_called()
        instruction, turns, ids = local.call_args.args
        self.assertEqual(['R02'], ids)
        self.assertNotIn('R08', instruction)
        self.assertEqual(self.question['messages'], turns)
        self.assertEqual(['RICE_BLAST'], [s['id'] for s in response['sources']])
        self.assertEqual('AI cục bộ · Qwen3-4B', response['mode'])

    def test_revoked_evidence_and_fabricated_citation_fail_closed(self):
        generated = {'answer': 'Bác có thể xem mục này.', 'case_ids': ['R02']}
        with patch('chat_agent.local_generate', return_value=generated) as call:
            response = self.agent.reply(self.question, self.config(sources=[]))
        self.assertEqual([], call.call_args.args[2])
        self.assertEqual([], response['sources'])
        self.assertIn('Kho tra cứu', response['mode'])

    def test_pesticide_dose_request_does_not_reach_model(self):
        self.question['messages'][0]['text'] = 'Pha thuốc bao nhiêu ml để phun thuốc?'
        with patch('chat_agent.local_generate') as call:
            response = self.agent.reply(self.question, self.config())
        call.assert_not_called()
        self.assertEqual('boundary', response['workflow']['stage'])
        self.assertEqual([], response['sources'])

    def test_model_dosage_and_invented_urls_are_rejected(self):
        for answer in ['Pha 10 g cho một lít nước.', 'Dùng 0,5% nhé.', 'Xem https://fake.invalid']:
            with self.subTest(answer=answer), patch('chat_agent.local_generate', return_value={'answer': answer, 'case_ids': ['R02']}):
                response = self.agent.reply(self.question, self.config())
            self.assertIn('Kho tra cứu', response['mode'])
            self.assertNotEqual(answer, response['answer'])

    def test_failed_local_connection_falls_back_without_cloud(self):
        with patch('chat_agent.local_generate', side_effect=local_model.LocalUnavailable('Mô hình chưa sẵn sàng.')), patch('chat_agent.gemini_reply') as cloud:
            response = self.agent.reply(self.question, self.config())
        cloud.assert_not_called()
        self.assertTrue(response['sources'])
        self.assertIn('AI cục bộ chưa trả lời', response['mode'])

    def test_coffee_context_is_required_and_latest_reply_overrides_type(self):
        workflow = self.agent.workflow
        turns = [{'role': 'user', 'text': 'Cà phê chè bị gỉ sắt'},
                 {'role': 'assistant', 'text': 'Bác xem lại loại cà phê nhé.'},
                 {'role': 'user', 'text': 'Tôi nói nhầm, đây là cà phê vối'}]
        plan = workflow.plan('coffee', turns, self.allowed('coffee'))
        self.assertEqual('robusta', plan['context']['crop_type'])
        self.assertNotIn('C13', [c['id'] for c in plan['cases']])
        missing = workflow.plan('coffee', [{'role': 'user', 'text': 'Cây thiếu kali'}], self.allowed('coffee'))
        self.assertEqual('context', missing['stage'])
        self.assertEqual([], missing['cases'])

    def test_negated_case_is_not_retrieved_as_positive(self):
        plan = self.agent.workflow.plan('rice', [{'role': 'user', 'text': 'Ruộng không có rầy nâu'}], self.allowed('rice'))
        self.assertEqual([], plan['cases'])

    def test_negation_in_other_sentence_does_not_hide_observed_sign(self):
        turns = [{'role': 'user', 'text': 'Không thấy cháy lá. Bông lúa bạc trắng.'}]
        plan = self.agent.workflow.plan('rice', turns, self.allowed('rice'))
        self.assertIn('R02', [c['id'] for c in plan['cases']])

    def test_exact_signs_do_not_introduce_an_unrelated_third_case(self):
        turns = [{'role': 'user', 'text': 'Ruộng lúa vừa trổ bông, một số bông bạc trắng, tôi nên xem gì trước?'}]
        plan = self.agent.workflow.plan('rice', turns, self.allowed('rice'))
        self.assertEqual({'R02', 'R08'}, {c['id'] for c in plan['cases']})

    def test_followup_uses_reported_observations_without_reasking(self):
        turns = [{'role': 'user', 'text': 'Bông lúa bạc trắng'},
                 {'role': 'assistant', 'text': 'Cổ bông có vết thâm nâu không?'},
                 {'role': 'user', 'text': 'Ở cổ bông có đoạn thâm nâu, nhưng tôi chưa kiểm tra thân.'}]
        plan = self.agent.workflow.plan('rice', turns, self.allowed('rice'))
        self.assertEqual('reported', plan['context']['observations']['neck_dark'])
        self.assertIn('thân', plan['question'])
        turns.extend([{'role': 'assistant', 'text': plan['question']},
                      {'role': 'user', 'text': 'Tôi vừa xem thân, không thấy lỗ đục.'}])
        plan = self.agent.workflow.plan('rice', turns, self.allowed('rice'))
        self.assertEqual('absent', plan['context']['observations']['stem_holes'])
        self.assertNotIn('có lỗ', plan['question'])

    def test_mixed_disease_claim_is_rejected_during_observation(self):
        plan = self.agent.workflow.plan('rice', self.question['messages'], self.allowed('rice'))
        result = {'answer': 'Dấu thâm nâu ở cổ bông có thể liên quan đến sâu đục thân.', 'case_ids': ['R08']}
        with self.assertRaises(ValueError):
            self.agent.workflow.validate_answer(result, plan)

    def test_text_chat_does_not_promise_to_inspect_photos(self):
        plan = self.agent.workflow.plan('rice', self.question['messages'], self.allowed('rice'))
        result = {'answer': 'Bác nên chụp cổ bông để đối chiếu. Em sẽ giúp phân tích sau khi bác cung cấp hình ảnh.', 'case_ids': ['R02']}
        answer, _ = self.agent.workflow.validate_answer(result, plan)
        self.assertNotIn('Em sẽ giúp phân tích', answer)
        self.assertNotIn('Khung chat này nhận mô tả bằng chữ', answer)

    def test_unverified_severity_assessment_is_not_shown(self):
        generated = {'answer': 'Nếu thân không đục thì có thể là tình trạng nhẹ.', 'case_ids': ['R02']}
        with patch('chat_agent.local_generate', return_value=generated):
            response = self.agent.reply(self.question, self.config())
        self.assertNotIn('tình trạng nhẹ', response['answer'])
        self.assertIn('chưa đủ căn cứ', response['answer'])
        self.assertIn('Kho tra cứu', response['mode'])

    def test_safe_question_survives_when_an_unsupported_sentence_is_removed(self):
        plan = self.agent.workflow.plan('rice', self.question['messages'], self.allowed('rice'))
        result = {'answer': 'Bác quan sát cổ bông có vết thâm không? Dấu thâm nâu có thể liên quan đến sâu đục thân.',
                  'case_ids': ['R02', 'R08']}
        answer, used = self.agent.workflow.validate_answer(result, plan)
        self.assertEqual('Bác quan sát cổ bông có vết thâm không?', answer)
        self.assertTrue(used)

    def test_repeated_start_does_not_launch_duplicate_cpu_models(self):
        with tempfile.TemporaryDirectory() as folder, patch('local_model.ROOT', Path(folder)), \
                patch('local_model._process', None), patch('local_model.status', return_value={'ready': False}), \
                patch.object(Path, 'is_file', return_value=True), \
                patch('local_model.credential', return_value='test-local-key'), \
                patch('local_model.socket.create_connection', side_effect=OSError), \
                patch('local_model.subprocess.Popen') as process:
            process.return_value.poll.return_value = None
            local_model.start()
            local_model.start()
        process.assert_called_once()

    def test_wrong_crop_does_not_reach_model(self):
        self.question['messages'][0]['text'] = 'Cà phê bị vàng lá'
        with patch('chat_agent.local_generate') as call:
            response = self.agent.reply(self.question, self.config())
        call.assert_not_called()
        self.assertEqual('crop_mismatch', response['workflow']['stage'])

    def test_invalid_engine_is_rejected(self):
        with self.assertRaises(ChatError):
            self.agent.validate(dict(self.agent.panel()['config'], engine='http://untrusted.invalid'))

    def test_local_generation_is_bounded(self):
        self.assertTrue(local_model._generation.acquire(blocking=False))
        try:
            with self.assertRaises(local_model.LocalBusy):
                local_model.generate('test', [{'role': 'user', 'text': 'test'}])
        finally:
            local_model._generation.release()

    def test_local_request_uses_only_loopback_and_closes_connection(self):
        with patch('local_model.credential', return_value='local-key'), patch('local_model.HTTPConnection') as connection:
            response = connection.return_value.getresponse.return_value
            response.status = 200
            response.read.return_value = b'{"data": []}'
            self.assertEqual({'data': []}, local_model.request('/v1/models'))
        connection.assert_called_once_with('127.0.0.1', 8766, timeout=2)
        connection.return_value.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from chat_agent import ChatAgent, ChatError
from conversation import conversation_state, guard_repeated_questions, next_question


def dialogue(question, reply, initial='Lúa của tôi bị vàng lá, có mảng khô'):
    return [{'role': 'user', 'text': initial}, {'role': 'assistant', 'text': question}, {'role': 'user', 'text': reply}]


class ConversationTests(unittest.TestCase):
    def test_coffee_root_nodules_get_a_sourced_assessment(self):
        turns = dialogue("Rễ có nốt sần hay chỉ bị đen hoặc thối?", "có nốt sần",
                         "Lá có màu vàng, còn bị rụng trái")
        response = self.agent.reply({"crop_id": "coffee", "messages": turns})
        self.assertIn("tuyến trùng hại rễ", response["answer"])
        self.assertTrue(response["sources"])
        self.assertNotIn("?", response["answer"])
        turns.append({"role": "user", "text": "Tôi nhìn lại, rễ không có nốt sần."})
        self.assertEqual("absent", conversation_state(turns)["observations"]["root_nodules"])

    def test_known_photo_part_replaces_generic_location_question(self):
        turns = [{"role": "user", "text": "Bộ phận thấy trong ảnh: lá. AI gợi ý dấu hiệu từ ảnh: đốm lá, vàng lá."}]
        state = conversation_state(turns)
        self.assertEqual("la", state["photo_part"])
        generic = "Bác thấy các đốm nằm ở phần nào của cây?"
        followup = next_question(generic, state)
        self.assertIn("nhiều cây", followup)
        answer, corrected = guard_repeated_questions(generic, {"context": state, "question": generic})
        self.assertTrue(corrected)
        self.assertNotIn("phần nào", answer)
        specific = "Mặt dưới lá có lớp bột màu cam không?"
        self.assertEqual(specific, next_question(specific, state))

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.agent = ChatAgent(Path(__file__).with_name('agridoctor.sqlite'), Path(temp.name) / 'state.sqlite')
        self.allowed = [dict(c, refs=[]) for c in self.agent.cases.values() if c['crop_id'] == 'rice']

    def test_short_yes_no_and_not_checked_bind_to_previous_question(self):
        for reply, expected in [('có', 'reported'), ('Dạ có ạ', 'reported'), ('không', 'absent'),
                                ('không thấy', 'absent'), ('hổng có', 'absent'), ('chưa kiểm tra', 'unconfirmed')]:
            with self.subTest(reply=reply):
                turns = dialogue('Bác có thấy sâu bọ dưới gốc không?', reply)
                state = conversation_state(turns)
                self.assertEqual(expected, state['observations']['base_pests'])
                plan = self.agent.workflow.plan('rice', turns, self.allowed)
                self.assertNotIn('gốc không', plan['question'])

    def test_actual_repeated_model_question_is_replaced_by_next_step(self):
        turns = dialogue('Bác có thấy sâu bọ dưới gốc không?', 'có')
        generated = {'answer': 'Em hiểu rồi. Bác kiểm tra xem có côn trùng ở sát gốc không?', 'case_ids': []}
        config = dict(self.agent.panel()['config'], engine='local')
        with patch('chat_agent.local_generate', return_value=generated):
            result = self.agent.reply({'crop_id': 'rice', 'messages': turns}, config)
        self.assertNotIn('gốc không?', result['answer'])
        self.assertIn('màu sắc', result['answer'])
        self.assertIn('đã thấy', result['answer'])

    def test_rice_short_replies_advance_neck_then_stem_then_observation(self):
        turns = dialogue('Cổ bông có vết thâm nâu không?', 'có', 'Bông bạc trắng')
        first = self.agent.workflow.plan('rice', turns, self.allowed)
        self.assertEqual('reported', first['context']['observations']['neck_dark'])
        self.assertIn('thân', first['question'])
        turns += [{'role': 'assistant', 'text': first['question']}, {'role': 'user', 'text': 'không'}]
        second = self.agent.workflow.plan('rice', turns, self.allowed)
        self.assertEqual('absent', second['context']['observations']['stem_holes'])
        self.assertNotIn('có lỗ', second['question'])

    def test_unchecked_is_not_absence_or_a_diagnosis(self):
        state = conversation_state(dialogue('Bác có thấy sâu dưới gốc không?', 'Tôi chưa kiểm tra'))
        self.assertEqual('unconfirmed', state['observations']['base_pests'])
        self.assertNotIn('không thấy', state['acknowledgement'])

    def test_assistant_suggestions_alone_never_become_user_observations(self):
        state = conversation_state(dialogue('Cổ bông thâm nâu hoặc thân có lỗ đục không?', 'có', 'Bông bạc'))
        self.assertTrue(state['ambiguous_reply'])
        self.assertEqual({}, state['observations'])
        state = conversation_state(dialogue('Bác có thấy sâu dưới gốc không?', 'Tôi không hiểu câu hỏi'))
        self.assertNotIn('base_pests', state['observations'])

    def test_user_correction_replaces_old_observation(self):
        turns = dialogue('Bác có thấy sâu ở gốc không?', 'có')
        turns += [{'role': 'assistant', 'text': 'Bác mô tả con vật nhé.'},
                  {'role': 'user', 'text': 'Tôi nói nhầm, dưới gốc không có sâu'}]
        self.assertEqual('absent', conversation_state(turns)['observations']['base_pests'])

    def test_answered_appearance_is_not_asked_again(self):
        turns = dialogue('Bác có sâu bọ ở gốc không?', 'có')
        turns += [{'role': 'assistant', 'text': 'Bác mô tả hình dạng, màu sắc và vị trí của những con bác thấy ở gốc nhé.'},
                  {'role': 'user', 'text': 'Con nhỏ màu nâu nằm sát gốc'}]
        plan = self.agent.workflow.plan('rice', turns, self.allowed)
        answer, changed = guard_repeated_questions('Bác mô tả hình dạng, màu sắc và vị trí của những con bác thấy ở gốc nhé.', plan)
        self.assertTrue(changed)
        self.assertNotIn('Bác mô tả hình dạng', answer)

    def test_same_subject_at_another_position_is_not_falsely_resolved(self):
        state = conversation_state(dialogue('Bác có sâu bọ dưới gốc không?', 'có'))
        plan = {'context': state, 'question': 'Bác có thấy sâu trong nếp lá không?'}
        answer, changed = guard_repeated_questions(plan['question'], plan)
        self.assertFalse(changed)
        self.assertEqual(plan['question'], answer)

    def test_longer_context_is_accepted_but_still_bounded_and_ordered(self):
        turns = [{'role': 'user', 'text': 'Bông bạc'}]
        for _ in range(10):
            turns += [{'role': 'assistant', 'text': 'Bác quan sát thêm nhé.'}, {'role': 'user', 'text': 'Tôi đang xem'}]
        self.agent.reply({'crop_id': 'rice', 'messages': turns})
        with self.assertRaises(ChatError):
            self.agent.reply({'crop_id': 'rice', 'messages': turns + turns[:2]})


if __name__ == '__main__':
    unittest.main()

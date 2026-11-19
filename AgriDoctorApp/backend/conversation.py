"""Resolve short replies against questions, without treating suggestions as facts."""
import re

from lookup import normalize

TOPICS = {
    'root_nodules': lambda p: bool(re.search(r'\bre\b', p) and re.search(r'\b(?:not san|u sung|not u)\b', p)),
    'base_pests': lambda p: bool(re.search(r'\b(?:goc|re|ke be)\b', p) and re.search(r'\b(?:sau|bo|ray|con trung)\b', p)),
    'neck_dark': lambda p: bool(re.search(r'\bco (?:bong|gie)\b', p) and re.search(r'\b(?:tham|sam|nau|den)\b', p)),
    'stem_holes': lambda p: bool(re.search(r'\b(?:lo duc|dau duc|vet duc)\b', p) or
                               (re.search(r'\b(?:than|danh)\b', p) and re.search(r'\b(?:lo|duc)\b', p))),
}
UNKNOWN = re.compile(r'\b(?:chua (?:kiem tra|xem|quan sat|biet|ro)|khong (?:biet|ro|chac)|chua chac|hinh nhu|co the)\b')
YES = re.compile(r'(?:(?:da|vang|toi|minh|em) )?(?:co|co thay|thay co|dung|dung roi|co roi|yes)(?: (?:a|nha|nhe|roi|do|day|bac|oi))*')
NO = re.compile(r'(?:(?:da|vang|toi|minh|em) )?(?:khong|khong co|khong thay|chua thay|hong|hong co|khong dau|no)(?: (?:a|nha|nhe|roi|do|day|bac|oi))*')
FILLERS = set('bac em toi minh co khong thay o sat duoi tren ngay la hay va hoac nhe nha a oi roi cho biet xem quan kiem tra'.split())


PHOTO_PART = re.compile(r'\bbo phan thay trong anh\s+(la|than|qua|bong|goc)\b')
GENERIC_LOCATION = re.compile(r'\b(?:phan nao cua cay|bo phan nao(?: cua cay)?|nam o dau|xuat hien o dau|o dau tren cay)\b')


def topics(text):
    plain = normalize(text)
    return {name for name, matches in TOPICS.items() if matches(plain)}


def questions(text):
    return [part.strip() for part in re.split(r'(?<=[.!?])\s+|\n+', text) if
            '?' in part or re.search(r'\bco\b.+\b(?:khong|chua)\b', normalize(part)) or
            re.search(r'\bbac\b.*\b(?:mo ta|cho em biet|cho biet)\b', normalize(part))]


def open_question(text):
    return bool(re.search(r'\b(?:mo ta|hinh dang|mau sac|mau gi|kich thuoc|nhu the nao|dau hieu gi)\b', normalize(text)))


def short_reply(text):
    plain = normalize(text)
    if topics(text):
        return None
    plain = re.sub(r'^(?:toi |minh )?(?:noi nham|dinh chinh|nham roi) ', '', plain)
    if len(plain) <= 90 and UNKNOWN.search(plain):
        return 'unconfirmed'
    if YES.fullmatch(plain):
        return 'reported'
    if NO.fullmatch(plain):
        return 'absent'
    if re.fullmatch(r'(?:(?:toi|minh|em) )?(?:khong co|khong thay|hong co) (?:sau|sau bo|con trung|ray)', plain):
        return 'absent'
    if re.fullmatch(r'(?:(?:toi|minh|em) (?:co )?thay )?co (?:sau|sau bo|con trung|ray)', plain):
        return 'reported'
    return None


def same_question(first, second):
    a, b = normalize(first), normalize(second)
    if a == b:
        return True
    ta, tb = topics(first), topics(second)
    if ta and ta == tb and not re.search(r'\b(?:hinh dang|mau sac|mau gi|kich thuoc|nhu the nao)\b', a + ' ' + b):
        return True
    wa, wb = set(a.split()) - FILLERS, set(b.split()) - FILLERS
    return len(wa & wb) >= 3 and len(wa & wb) / max(1, len(wa | wb)) >= .72


def conversation_state(turns):
    state = {'observations': {}, 'answered_questions': []}
    pending = []
    for turn in turns:
        if turn['role'] == 'assistant':
            pending = questions(turn['text'])
            continue
        text = turn['text']
        photo_part = PHOTO_PART.search(normalize(text))
        if photo_part:
            state['photo_part'] = photo_part.group(1)
        state.pop('acknowledgement', None)
        state.pop('ambiguous_reply', None)
        # Read explicit user statements clause by clause, so one negation cannot
        # erase an unrelated sign. Hypotheses and questions are not observations.
        for clause in re.split(r'[,.;!?]|\b(?:nhưng|nhung|tuy nhiên)\b', text):
            plain = normalize(clause)
            if re.search(r'\b(?:neu|gia su|lieu|co phai)\b', plain) or '?' in text:
                continue
            observed_topics = topics(clause)
            if (len(pending) == 1 and 'root_nodules' in topics(pending[0])
                    and re.search(r'\b(?:not san|u sung|not u)\b', plain)):
                observed_topics.add('root_nodules')
            for topic in observed_topics:
                value = ('unconfirmed' if UNKNOWN.search(plain) else 'absent' if
                         re.search(r'\b(?:khong|hong|chua thay)\b', plain) else 'reported')
                state['observations'][topic] = value
        value = short_reply(text)
        if value and pending:
            if len(pending) != 1 or len(topics(pending[0])) > 1 or (open_question(pending[0]) and value != 'unconfirmed'):
                state['ambiguous_reply'] = True
            else:
                question = pending[0]
                record = {'question': question, 'reply': text, 'status': value}
                state['answered_questions'] = [r for r in state['answered_questions'] if not same_question(question, r['question'])]
                state['answered_questions'].append(record)
                for topic in topics(question):
                    state['observations'][topic] = value
                if 'base_pests' in topics(question):
                    state['acknowledgement'] = {
                        'reported': 'Em ghi nhận bác đã thấy sâu/bọ ở gốc.',
                        'absent': 'Em ghi nhận bác báo không thấy sâu/bọ ở gốc.',
                        'unconfirmed': 'Em ghi nhận bác chưa xác nhận được dấu hiệu ở gốc.',
                    }[value]
                else:
                    state['acknowledgement'] = 'Em đã ghi nhận câu trả lời của bác.'
        # Explicit full-sentence answers also resolve the matching question.
        elif pending:
            for question in pending:
                linked = topics(question) & topics(text)
                if (linked or (open_question(question) and len(normalize(text).split()) > 2)) and '?' not in text:
                    state['answered_questions'].append({'question': question, 'reply': text, 'status': 'described'})
                    state['acknowledgement'] = 'Em đã ghi nhận mô tả của bác.'
        pending = []
    return state


def next_question(question, state):
    if state.get('ambiguous_reply'):
        return 'Bác đang xác nhận dấu hiệu nào trong câu hỏi vừa rồi? Bác nói rõ vị trí và dấu hiệu đã thấy nhé.'
    if state.get('photo_part') and GENERIC_LOCATION.search(normalize(question)):
        return 'Bác thấy dấu hiệu này ở nhiều cây hay chỉ vài cây?'
    answered = state.get('answered_questions', [])
    if not any(same_question(q, r['question']) for q in questions(question) for r in answered):
        # An explicit observation may have been supplied before it was asked.
        if not (topics(question) and all(state.get('observations', {}).get(t, 'unknown') != 'unknown' for t in topics(question))):
            return question
    if 'base_pests' in topics(question):
        value = state['observations'].get('base_pests')
        candidate = ('Bác mô tả hình dạng, màu sắc và vị trí của những con bác thấy ở gốc nhé.' if value == 'reported' else
                     'Bác mô tả dấu hiệu khác đang thấy ở lá hoặc thân nhé.')
        if not any(same_question(candidate, r['question']) for r in answered):
            return candidate
        return 'Bác có thể chụp cận con vật và vị trí bị ảnh hưởng để đối chiếu tài liệu hoặc nhờ cán bộ kỹ thuật xác nhận nhé.'
    return 'Bác mô tả dấu hiệu khác đang thấy; ảnh cận vị trí bị ảnh hưởng có thể dùng để đối chiếu tài liệu hoặc hỏi cán bộ kỹ thuật nhé.'


def guard_repeated_questions(answer, plan):
    state = plan.get('context', {})
    answered = state.get('answered_questions', [])
    parts = re.split(r'(?<=[.!?])\s+', answer)
    kept = [part for part in parts
            if not (state.get('photo_part') and GENERIC_LOCATION.search(normalize(part)))
            and not any(same_question(q, r['question']) for q in questions(part) for r in answered)]
    if len(kept) == len(parts) and not state.get('ambiguous_reply'):
        return answer, False
    # Rebuild only when the generated question repeats a resolved one. Never
    # promote "có" into a disease diagnosis or copy unconfirmed suggestions.
    prefix = state.get('acknowledgement', 'Em đã ghi nhận câu trả lời của bác.')
    question = next_question(plan.get('question', ''), state)
    rebuilt = ' '.join([prefix, *kept, question]).strip()
    return (rebuilt if len(rebuilt) <= 1800 else prefix + ' ' + question), True

package vn.agridoctor.app

import org.json.JSONArray
import org.json.JSONObject

internal fun chatRequestTurns(messages: JSONArray, outgoing: JSONObject): JSONArray = JSONArray().apply {
    for (i in (messages.length() - 20).coerceAtLeast(0) until messages.length()) {
        val row = messages.getJSONObject(i)
        put(JSONObject().put("role", row.getString("role"))
            .put("text", row.optString("requestText", row.getString("text"))))
    }
    put(outgoing)
}

private fun chatTopic(text: String): String? {
    val p = normalize(text)
    val found = listOfNotNull(
        "base_pests".takeIf { Regex("\\b(goc|re|ke be)\\b").containsMatchIn(p) && Regex("\\b(sau|bo|ray|con trung)\\b").containsMatchIn(p) },
        "neck_dark".takeIf { Regex("\\bco (bong|gie)\\b").containsMatchIn(p) && Regex("\\b(tham|sam|nau|den)\\b").containsMatchIn(p) },
        "stem_holes".takeIf { Regex("\\b(lo duc|dau duc|vet duc)\\b").containsMatchIn(p) ||
            Regex("\\b(than|danh)\\b").containsMatchIn(p) && Regex("\\b(lo|duc)\\b").containsMatchIn(p) },
    )
    return found.singleOrNull()
}

private fun shortChatReply(text: String): String? {
    val p = normalize(text)
    if (chatTopic(text) != null) return null
    if (p.length < 90 && Regex("\\b(chua (kiem tra|xem|quan sat|biet)|khong (biet|ro|chac)|hinh nhu)\\b").containsMatchIn(p)) return "unconfirmed"
    if (Regex("(?:(da|vang|toi|minh|em) )?(co|co thay|thay co|dung|dung roi|yes)( (a|nha|nhe|roi|do|day))*").matches(p)) return "reported"
    if (Regex("(?:(da|vang|toi|minh|em) )?(khong|khong co|khong thay|chua thay|hong|hong co|no)( (a|nha|nhe|roi))*").matches(p)) return "absent"
    return null
}

/** Bounded, in-memory question/answer links for the offline lookup fallback. */
internal fun offlineFollowup(question: String, turns: JSONArray, caseIds: Set<String>): String {
    val observations = mutableMapOf<String, String>()
    val answered = mutableSetOf<String>()
    var latest: Pair<String?, String>? = null
    for (i in 0 until turns.length()) {
        val row = turns.getJSONObject(i)
        if (row.getString("role") != "user") continue
        latest = null
        val text = row.getString("text")
        val value = shortChatReply(text)
        val previous = if (i > 0) turns.getJSONObject(i - 1).optString("text") else ""
        val asks = previous.split(Regex("(?<=[.!?])\\s+")).filter { '?' in it || normalize(it).startsWith("bac mo ta") }
        if (value != null && asks.size == 1) {
            val topic = chatTopic(asks.single())
            if (topic != null) observations[topic] = value
            answered.add(normalize(asks.single()))
            latest = topic to value
        } else {
            if (asks.size == 1 && normalize(asks.single()).startsWith("bac mo ta") && normalize(text).split(" ").size > 2 && '?' !in text) {
                answered.add(normalize(asks.single()))
            }
            for (clause in text.split(Regex("[,.;!?]|nhưng|nhung"))) {
                val topic = chatTopic(clause) ?: continue
                if ('?' in text) continue
                val p = normalize(clause)
                observations[topic] = if (Regex("\\b(chua (kiem tra|xem)|khong (biet|ro|chac)|hinh nhu)\\b").containsMatchIn(p)) "unconfirmed"
                    else if (Regex("\\b(khong|hong|chua thay)\\b").containsMatchIn(p)) "absent" else "reported"
            }
        }
    }
    var pending = when {
        caseIds.containsAll(setOf("R02", "R08")) -> when {
            observations["neck_dark"] == null -> "Bác quan sát cổ bông ngay dưới phần bạc trắng: có vết sẫm hoặc thâm nâu không?"
            observations["stem_holes"] == null -> "Bác kiểm tra phần thân ngay dưới bông bạc: có lỗ hoặc dấu đục không?"
            else -> "Bác có thể chụp cận cổ bông, toàn bông và thân để đối chiếu tài liệu hoặc hỏi cán bộ kỹ thuật nhé."
        }
        latest?.first == "base_pests" || chatTopic(question) == "base_pests" && observations.containsKey("base_pests") ->
            if (observations["base_pests"] == "reported") "Bác mô tả hình dạng, màu sắc và vị trí của những con bác thấy ở gốc nhé."
            else "Bác mô tả dấu hiệu khác đang thấy ở lá hoặc thân nhé."
        normalize(question) in answered -> "Bác mô tả dấu hiệu khác đang thấy để tiếp tục đối chiếu tài liệu nhé."
        else -> question
    }
    if (normalize(pending) in answered) pending = "Bác có thể chụp cận vị trí bị ảnh hưởng để đối chiếu tài liệu hoặc nhờ cán bộ kỹ thuật xác nhận nhé."
    val ack = latest?.let { (topic, status) -> when {
        topic == "base_pests" && status == "reported" -> "Em ghi nhận bác đã thấy sâu/bọ ở gốc. "
        topic == "base_pests" && status == "absent" -> "Em ghi nhận bác báo không thấy sâu/bọ ở gốc. "
        status == "unconfirmed" -> "Em ghi nhận bác chưa xác nhận được dấu hiệu này. "
        else -> "Em đã ghi nhận câu trả lời của bác. "
    } }.orEmpty()
    return ack + pending
}

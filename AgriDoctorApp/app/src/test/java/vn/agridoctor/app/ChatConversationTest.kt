package vn.agridoctor.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class ChatConversationTest {
    private fun turn(role: String, text: String) = JSONObject().put("role", role).put("text", text)
    private fun turns(question: String, reply: String) = JSONArray()
        .put(turn("user", "Lúa bị vàng lá" )).put(turn("assistant", question)).put(turn("user", reply))

    @Test fun offlineYesToBaseInsectsMovesToTheirAppearance() {
        val result = localAnswer(Crop.RICE, turns("Bác có thấy sâu bọ dưới gốc không?", "có"), Knowledge(RuntimeEnvironment.getApplication()).cases)
        assertTrue(result.getString("answer").contains("màu sắc"))
        assertFalse(result.getString("answer").contains("gốc không?"))
    }

    @Test fun uncheckedIsAcknowledgedWithoutBeingReportedAsAbsent() {
        val answer = offlineFollowup("Bác có sâu dưới gốc không?", turns("Bác có sâu dưới gốc không?", "Tôi chưa kiểm tra"), emptySet())
        assertTrue(answer.contains("chưa xác nhận"))
        assertFalse(answer.contains("không thấy"))
    }

    @Test fun shortRiceAnswersAdvanceAcrossTheTwoDifferentQuestions() {
        val turns = turns("Cổ bông có vết thâm nâu không?", "có")
        val first = offlineFollowup("Cổ bông có vết thâm nâu không?", turns, setOf("R02", "R08"))
        assertTrue(first.contains("thân"))
        turns.put(turn("assistant", "Thân có lỗ đục không?")).put(turn("user", "không"))
        val next = offlineFollowup("Cổ bông có vết thâm nâu không?", turns, setOf("R02", "R08"))
        assertFalse(next.contains("có lỗ"))
        assertTrue(next.contains("đối chiếu"))
    }

    @Test fun requestKeepsTenPairsAndStripsDisplayMetadata() {
        val history = JSONArray()
        for (i in 0 until 12) {
            history.put(turn("user", "Câu $i"))
            history.put(turn("assistant", "Trả lời $i").put("sources", JSONArray()))
        }
        val outgoing = turn("user", "có")
        val payload = chatRequestTurns(history, outgoing)
        assertEquals(21, payload.length())
        assertEquals("Câu 2", payload.getJSONObject(0).getString("text"))
        assertEquals("có", payload.getJSONObject(20).getString("text"))
        assertFalse(payload.getJSONObject(1).has("sources"))
    }

    @Test fun fullObservationAndAnUnrelatedUncheckedPartAreKeptSeparate() {
        val turns = turns("Cổ bông có vết thâm nâu không?", "Ở cổ bông có đoạn thâm nâu, nhưng tôi chưa kiểm tra thân.")
        val answer = offlineFollowup("Cổ bông có vết thâm nâu không?", turns, setOf("R02", "R08"))
        assertFalse(answer.contains("cổ bông ngay"))
        assertTrue(answer.contains("thân"))
    }

    @Test fun insectAppearanceIsNotRequestedAgainAfterItWasDescribed() {
        val turns = turns("Bác có thấy sâu bọ dưới gốc không?", "có")
        turns.put(turn("assistant", "Bác mô tả hình dạng, màu sắc và vị trí của những con bác thấy ở gốc nhé."))
            .put(turn("user", "Con nhỏ màu nâu ở gốc"))
        val answer = offlineFollowup("Bác có thấy sâu bọ dưới gốc không?", turns, emptySet())
        assertFalse(answer.contains("Bác mô tả hình dạng"))
    }
}

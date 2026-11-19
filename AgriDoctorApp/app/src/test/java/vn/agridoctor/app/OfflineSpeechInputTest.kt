package vn.agridoctor.app

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class OfflineSpeechInputTest {
    private class Engine : OfflineSpeechEngine {
        lateinit var events: SpeechEvents
        var closed = false
        var stopped = false
        override fun start(events: SpeechEvents) { this.events = events }
        override fun stop() { stopped = true }
        override fun close() { closed = true }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val partials = mutableListOf<String>()
    private val finals = mutableListOf<List<String>>()
    private val notices = mutableListOf<String>()
    private val engines = mutableListOf<Engine>()
    private fun input(factory: SpeechFactory = { _, ready, _ -> ready(Engine().also { engines.add(it) }) }) =
        OfflineSpeechInput(context, { partials.add(it) }, { finals.add(it) }, { notices.add(it) }, factory)

    @Test fun wordsAppearBeforeFinalAndPausesDoNotLosePreviousSentences() {
        val input = input()
        input.start(Crop.RICE)
        val engine = engines.single()
        engine.events.level(.4f)
        engine.events.partial("không có")
        engine.events.partial("không có bông bạc")
        assertEquals(listOf("không có", "không có bông bạc"), partials)
        assertTrue(finals.isEmpty())
        engine.events.segment("không có bông bạc")
        engine.events.partial("nhưng cổ bông thâm")
        assertEquals("không có bông bạc nhưng cổ bông thâm", partials.last())
        input.finish()
        assertTrue(engine.stopped)
        engine.events.final("nhưng cổ bông thâm nâu")
        assertEquals(listOf("không có bông bạc nhưng cổ bông thâm nâu"), finals.single())
        assertFalse(input.active)
        assertTrue(engine.closed)
    }

    @Test fun oldOrCancelledSessionCannotChangeNewInput() {
        val input = input()
        input.start(Crop.RICE)
        val old = engines.last()
        input.cancel()
        input.start(Crop.RICE)
        old.events.partial("sai từ phiên trước")
        old.events.final("sai kết quả cũ")
        assertTrue(partials.isEmpty())
        assertTrue(finals.isEmpty())
        engines.last().events.final("cổ bông thâm nâu")
        assertEquals(listOf("cổ bông thâm nâu"), finals.single())
    }

    @Test fun cancellingDuringModelLoadingDoesNotStartTheMicrophoneLater() {
        lateinit var prepared: (OfflineSpeechEngine) -> Unit
        val input = input { _, ready, _ -> prepared = ready }
        input.start(Crop.RICE)
        input.cancel()
        val delayed = Engine()
        prepared(delayed)
        assertTrue(delayed.closed)
        assertFalse(input.active)
        assertTrue(partials.isEmpty())
    }

    @Test fun timeoutKeepsRecognizedWordsAndLeavesAVisibleError() {
        val input = input()
        input.start(Crop.RICE)
        val engine = engines.single()
        engine.events.partial("bông bạc nhưng chưa kiểm tra thân")
        input.finish()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(input.active)
        assertTrue(engine.closed)
        assertEquals("bông bạc nhưng chưa kiểm tra thân", partials.last())
        assertTrue(finals.isEmpty())
        assertEquals(input.errorText, notices.single())
    }

    @Test fun silenceDoesNotEraseTheExistingFormOrFabricateATranscript() {
        val input = input()
        input.start(Crop.RICE)
        engines.single().events.partial("")
        engines.single().events.final("")
        assertTrue(partials.isEmpty())
        assertTrue(finals.isEmpty())
        assertTrue(input.errorText.isNotBlank())
    }

    @Test fun soundWithoutRecognizedWordsIsReportedWithoutBlamingMicrophoneDistance() {
        val input = input()
        input.start(Crop.RICE)
        engines.single().events.level(.4f)
        assertEquals(.4f, input.level)
        engines.single().events.final("")
        assertTrue(input.errorText.contains("âm thanh"))
        assertFalse(input.errorText.contains("gần mic"))
        assertTrue(finals.isEmpty())
    }

    @Test fun bundledModelIsVerifiedAndInstalledAwayFromJournalData() {
        val sentinel = java.io.File(context.noBackupFilesDir, "journal-sentinel.txt").apply { writeText("keep") }
        val target = unpackVoiceModel(context)
        assertEquals(VOICE_MODEL_SHA, java.io.File(target, ".ready").readText())
        assertTrue(java.io.File(target, "am/final.mdl").length() > 1000)
        assertTrue(java.io.File(target, "graph/HCLr.fst").isFile)
        assertEquals(target, unpackVoiceModel(context))
        assertEquals("keep", sentinel.readText())
    }
}

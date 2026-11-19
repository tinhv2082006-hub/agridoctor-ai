package vn.agridoctor.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale

internal class VoiceReader(private val notice: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var utterance = 0L
    private var activeText by mutableStateOf<String?>(null)
    val isReading: Boolean get() = activeText != null

    fun isReading(text: String): Boolean = activeText == text

    fun start(context: Context) {
        engine = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val current = engine ?: return@post
                if (status == TextToSpeech.SUCCESS) {
                    current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) = finish(utteranceId)
                        @Deprecated("Required by Android's speech listener")
                        override fun onError(utteranceId: String) = finish(utteranceId, error = true)
                    })
                    ready = true
                    activeText?.let(::speak)
                } else {
                    failed = true
                    if (isReading) {
                        stop()
                        notice("Bác bật dịch vụ chuyển văn bản thành giọng nói trong Cài đặt Android rồi mở lại ứng dụng.")
                    }
                }
            }
        }
    }

    fun toggle(text: String) {
        if (text.isBlank()) return
        if (isReading(text)) {
            stop()
        } else if (failed) {
            notice("Bác bật dịch vụ chuyển văn bản thành giọng nói trong Cài đặt Android rồi mở lại ứng dụng.")
        } else {
            activeText = text
            if (ready) speak(text)
        }
    }

    private fun speak(text: String) {
        val current = engine ?: return
        val id = (++utterance).toString()
        val language = current.setLanguage(Locale.forLanguageTag("vi-VN"))
        if (language < TextToSpeech.LANG_AVAILABLE) {
            stop()
            notice("Bác vào mục Chuyển văn bản thành giọng nói trong Cài đặt Android để cài giọng tiếng Việt.")
            return
        }
        if (current.speak(spokenText(text), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finish(id, error = true)
        }
    }

    private fun finish(id: String, error: Boolean = false) {
        main.post {
            if (id == utterance.toString() && isReading) {
                activeText = null
                if (error) notice("Chưa phát được giọng đọc. Bác kiểm tra giọng tiếng Việt trên thiết bị rồi thử lại.")
            }
        }
    }

    fun stop() {
        if (!isReading) return
        ++utterance
        activeText = null
        engine?.stop()
    }

    fun close() {
        stop()
        engine?.shutdown()
        engine = null
        main.removeCallbacksAndMessages(null)
    }
}

private val quantityRange = Regex("(?<=\\d)[–—-](?=\\d)")
private val units = Regex("(?<=\\d)\\s*(kg|ml|g|lít|L)\\s*/\\s*(ha|kg|lít|L)\\b", RegexOption.IGNORE_CASE)
private val grams = Regex("(?<=\\d)\\s+g\\b")

internal fun spokenText(text: String): String = text
    .replace(quantityRange, " đến ")
    .replace(units) { match ->
        val names = mapOf("kg" to "ki lô gam", "ml" to "mi li lít", "g" to "gam", "ha" to "héc ta", "l" to "lít")
        val (amount, per) = match.destructured
        " ${names[amount.lowercase()] ?: amount} trên ${names[per.lowercase()] ?: per}"
    }
    .replace(grams, " gam")
    .replace("kg thuốc/ha", "ki lô gam thuốc trên héc ta")
    .replace(" g thuốc/", " gam thuốc/")
    .replace("lít thuốc/ha", "lít thuốc trên héc ta")
    .replace("lít nước/ha", "lít nước trên héc ta")
    .replace(Regex("\\s*/\\s*(?=\\d+ lít nước)"), " trong ")
    .replace(Regex("(?<=\\d)%"), " phần trăm")
    .replace(" · ", ". ")
    .replace(" → ", ". ")

@Composable
internal fun rememberVoiceReader(context: Context, lifecycle: Lifecycle, notice: (String) -> Unit): VoiceReader {
    val currentNotice by rememberUpdatedState(notice)
    val reader = remember(context, lifecycle) { VoiceReader { currentNotice(it) } }
    DisposableEffect(reader) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) reader.stop() }
        lifecycle.addObserver(observer)
        reader.start(context)
        onDispose {
            lifecycle.removeObserver(observer)
            reader.close()
        }
    }
    return reader
}

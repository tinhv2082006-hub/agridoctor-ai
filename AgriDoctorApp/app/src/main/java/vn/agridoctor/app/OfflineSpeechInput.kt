package vn.agridoctor.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal interface SpeechEvents {
    fun level(value: Float) = Unit
    fun partial(text: String)
    fun segment(text: String)
    fun final(text: String)
    fun error()
}

internal interface OfflineSpeechEngine {
    fun start(events: SpeechEvents)
    fun stop()
    fun close()
}

internal typealias SpeechFactory = (Context, (OfflineSpeechEngine) -> Unit, () -> Unit) -> Unit

internal class OfflineSpeechInput(
    private val context: Context,
    private val partial: (String) -> Unit,
    private val complete: (List<String>) -> Unit,
    private val notice: (String) -> Unit,
    private val prepare: SpeechFactory = ::prepareOfflineSpeech,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: OfflineSpeechEngine? = null
    private var generation = 0L
    var active by mutableStateOf(false)
        private set
    var finishing by mutableStateOf(false)
        private set
    var errorText by mutableStateOf("")
        private set
    var status by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    private var heardSound = false

    private fun fail() {
        cancel()
        errorText = "Chưa nhận được câu nói. Bác kiểm tra quyền mic, thử dịch vụ giọng nói của Android hoặc nhập bằng bàn phím."
        notice(errorText)
    }

    fun start(@Suppress("UNUSED_PARAMETER") crop: Crop) {
        if (active) return
        val session = ++generation
        active = true
        finishing = true
        errorText = ""
        level = 0f
        heardSound = false
        status = "Đang chuẩn bị nhận dạng tiếng Việt trên điện thoại…"
        val segments = mutableListOf<String>()
        var latest = ""
        fun current() = active && generation == session
        fun sentence(tail: String) = (segments + tail).filter { it.isNotBlank() }.joinToString(" ").take(500)
        handler.postDelayed({ if (current()) { if (engine == null) fail() else finish() } }, 30_000)
        try {
            prepare(context, { ready ->
                if (!current()) { ready.close(); return@prepare }
                engine = ready
                finishing = false
                status = "Đang nghe trên điện thoại · Bác nói triệu chứng."
                try {
                    ready.start(object : SpeechEvents {
                        override fun level(value: Float) {
                            if (!current()) return
                            level = value.coerceIn(0f, 1f)
                            if (value > .03f) heardSound = true
                        }
                        override fun partial(text: String) {
                            if (!current()) return
                            if (text.isNotBlank()) latest = text
                            sentence(text).takeIf { it.isNotBlank() }?.let(partial)
                        }
                        override fun segment(text: String) {
                            if (!current()) return
                            if (text.isNotBlank()) segments.add(text)
                            latest = ""
                            sentence("").takeIf { it.isNotBlank() }?.let(partial)
                        }
                        override fun final(text: String) {
                            if (!current()) return
                            val result = sentence(text.ifBlank { latest })
                            cancel()
                            if (result.isNotBlank()) complete(listOf(result)) else {
                                errorText = if (heardSound) "App đã nhận được âm thanh nhưng chưa chuyển được thành chữ. Bác thử ứng dụng giọng nói của Android hoặc nhập bằng bàn phím."
                                    else "App chưa nhận được tín hiệu mic. Bác kiểm tra quyền mic, thiết bị âm thanh đang kết nối hoặc thử ứng dụng giọng nói của Android."
                                notice(errorText)
                            }
                        }
                        override fun error() { if (current()) fail() }
                    })
                } catch (_: Exception) { fail() }
            }, { if (current()) fail() })
        } catch (_: Exception) { fail() }
    }

    fun finish() {
        if (!active || finishing) return
        finishing = true
        status = "Đang hoàn tất câu nói…"
        try { engine?.stop() } catch (_: Exception) { fail(); return }
        val session = generation
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (active && session == generation) fail() }, 10_000)
    }

    fun cancel(@Suppress("UNUSED_PARAMETER") reason: String = "user") {
        ++generation
        active = false
        finishing = false
        handler.removeCallbacksAndMessages(null)
        val old = engine
        engine = null
        old?.close()
    }
}

@Composable
internal fun rememberOfflineSpeechInput(context: Context, lifecycle: Lifecycle, partial: (String) -> Unit,
                                        complete: (List<String>) -> Unit, notice: (String) -> Unit): OfflineSpeechInput {
    val onPartial by rememberUpdatedState(partial)
    val onComplete by rememberUpdatedState(complete)
    val onNotice by rememberUpdatedState(notice)
    val input = remember(context, lifecycle) { OfflineSpeechInput(context, { onPartial(it) }, { onComplete(it) }, { onNotice(it) }) }
    DisposableEffect(input) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) input.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); input.cancel() }
    }
    return input
}

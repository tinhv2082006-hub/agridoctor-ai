package vn.agridoctor.app

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

internal const val VOICE_MODEL_SHA = "efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5"
private const val VOICE_ASSET = "speech/vosk-model-small-vn-0.4.zip"
private val speechWorker = Executors.newSingleThreadExecutor()
private val speechMain = Handler(Looper.getMainLooper())
private var loadedVoiceModel: Model? = null // Only accessed on speechWorker.

internal fun unpackVoiceModel(context: Context): File {
    val root = context.noBackupFilesDir.canonicalFile
    val target = File(root, "voice-vn-0.4")
    val stage = File(root, "voice-vn-0.4.installing")
    check(target.canonicalFile.parentFile == root && stage.canonicalFile.parentFile == root)
    if (File(target, ".ready").run { isFile && readText() == VOICE_MODEL_SHA } &&
        File(target, "am/final.mdl").isFile && File(target, "graph/HCLr.fst").isFile) return target
    val digest = MessageDigest.getInstance("SHA-256")
    context.assets.open(VOICE_ASSET).use { stream ->
        val buffer = ByteArray(65536)
        while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == VOICE_MODEL_SHA)
    check(!stage.exists() || stage.deleteRecursively())
    check(stage.mkdirs())
    var total = 0L
    ZipInputStream(context.assets.open(VOICE_ASSET)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val prefix = "vosk-model-small-vn-0.4/"
            check(entry.name.startsWith(prefix))
            val relative = entry.name.removePrefix(prefix)
            if (relative.isEmpty()) continue
            val output = File(stage, relative).canonicalFile
            check(output.path.startsWith(stage.canonicalPath + File.separator))
            if (entry.isDirectory) check(output.isDirectory || output.mkdirs()) else {
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                output.outputStream().use { file ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = zip.read(buffer)
                        if (size < 0) break
                        total += size
                        check(total <= 100_000_000)
                        file.write(buffer, 0, size)
                    }
                }
            }
        }
    }
    File(stage, ".ready").writeText(VOICE_MODEL_SHA)
    check(!target.exists() || target.deleteRecursively())
    check(stage.renameTo(target))
    return target
}

internal fun prepareOfflineSpeech(context: Context, ready: (OfflineSpeechEngine) -> Unit, failed: () -> Unit) {
    val app = context.applicationContext
    speechWorker.execute {
        var recognizer: Recognizer? = null
        var recorder: AudioRecord? = null
        try {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission is required")
            }
            val model = loadedVoiceModel ?: Model(unpackVoiceModel(app).path).also { loadedVoiceModel = it }
            recognizer = Recognizer(model, 16000f)
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(32000, AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)).build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { recorder.setPreferredDevice(it) }
            val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val engine = VoskSpeechEngine(app, recognizer, recorder, debug)
            speechMain.post { ready(engine) }
        } catch (error: Exception) {
            if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.w("AgriDoctorVoice", "prepare_failed kind=${error.javaClass.simpleName}")
            recorder?.release(); recognizer?.close(); speechMain.post(failed)
        } catch (_: LinkageError) { recorder?.release(); recognizer?.close(); speechMain.post(failed) }
    }
}

private class VoskSpeechEngine(private val context: Context, private val recognizer: Recognizer, private val recorder: AudioRecord,
                              private val debug: Boolean) : OfflineSpeechEngine {
    private val closed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var thread: Thread? = null
    private fun text(value: String?, key: String) = runCatching { JSONObject(value.orEmpty()).optString(key).trim() }.getOrDefault("")
    override fun start(events: SpeechEvents) {
        check(thread == null && !closed.get())
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required")
        }
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        if (debug) Log.d("AgriDoctorVoice", "capture_started route_type=${recorder.routedDevice?.type}")
        thread = Thread({
            var samples = 0L
            var peak = 0.0
            var partials = 0
            var segments = 0
            try {
                val buffer = ShortArray(3200)
                while (!stopping.get() && !closed.get()) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    check(count > 0)
                    samples += count
                    var energy = 0.0
                    for (index in 0 until count) energy += buffer[index].toDouble() * buffer[index]
                    val rms = sqrt(energy / count) / 32768.0
                    peak = maxOf(peak, rms)
                    val level = (rms * 8).toFloat().coerceIn(0f, 1f)
                    val segment = recognizer.acceptWaveForm(buffer, count)
                    val words = if (segment) text(recognizer.result, "text") else text(recognizer.partialResult, "partial")
                    if (words.isNotBlank()) { if (segment) segments++ else partials++ }
                    speechMain.post {
                        if (!closed.get()) { events.level(level); if (segment) events.segment(words) else events.partial(words) }
                    }
                }
                if (!closed.get()) {
                    val raw = recognizer.finalResult
                    val words = text(raw, "text")
                    val keys = runCatching { JSONObject(raw).keys().asSequence().joinToString(",") }.getOrDefault("invalid_json")
                    if (debug) Log.d("AgriDoctorVoice", "finished samples=$samples peak_rms=$peak partial_events=$partials segments=$segments final_chars=${words.length} result_keys=$keys")
                    speechMain.post { if (!closed.get()) events.final(words) }
                }
            } catch (error: Exception) {
                if (debug) Log.w("AgriDoctorVoice", "capture_failed kind=${error.javaClass.simpleName} samples=$samples peak_rms=$peak")
                speechMain.post { if (!closed.get()) events.error() }
            } finally { runCatching { recorder.stop() } }
        }, "AgriDoctor-Voice").also { it.start() }
    }
    override fun stop() { stopping.set(true) }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopping.set(true)
        speechWorker.execute {
            runCatching { recorder.stop() }
            thread?.join()
            try { recorder.release() } finally { recognizer.close() }
        }
    }
}

package vn.agridoctor.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal const val RECORDING_SECONDS = 15
private const val MAX_RECORDING_BYTES = 200_000L

@Suppress("DEPRECATION")
internal class AudioInput(context: Context, private val onLimit: (AudioInput) -> Unit) {
    val file = File.createTempFile("voice-", ".m4a", context.cacheDir)
    private var recorder: MediaRecorder? = null

    init {
        try {
            val device = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = device
            device.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(48_000)
                setMaxDuration(RECORDING_SECONDS * 1_000)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, info, _ ->
                    if (info == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit(this@AudioInput)
                }
                prepare()
                start()
            }
        } catch (error: Exception) {
            discard()
            throw error
        }
    }

    fun finish(reachedLimit: Boolean = false): File? {
        val device = recorder ?: return null
        val stopped = runCatching { device.stop() }.isSuccess
        recorder = null
        device.release()
        if ((stopped || reachedLimit) && file.length() in 12..MAX_RECORDING_BYTES) return file
        file.delete()
        return null
    }

    fun discard() {
        recorder?.release()
        recorder = null
        file.delete()
    }
}

internal fun transcribeRecording(context: Context, file: File, crop: Crop, endpoint: String, accessToken: String): String {
    if (file.length() !in 12..MAX_RECORDING_BYTES) throw IOException("Bản ghi âm quá ngắn hoặc quá lớn.")
    val payload = JSONObject().put("crop_id", crop.id)
        .put("audio", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    val response = requestJson(context, endpoint, "/v1/transcribe", payload, accessToken)
    return response.optString("transcript").trim().takeIf { it.isNotEmpty() && it.length <= 500 }
        ?: throw IOException(response.optString("message", "AI chưa nghe rõ. Bác thử nói lại.").take(240))
}

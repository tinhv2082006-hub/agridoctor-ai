package vn.agridoctor.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.util.AtomicFile
import androidx.core.content.edit
import androidx.core.graphics.scale
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

internal data class Scan(val id: String, val time: Long, val crop: Crop, val text: String, val headline: String, val question: String, val cases: List<String>, val demo: Boolean,
    val kind: String = "", val stage: String = "", val part: String = "", val region: String = "",
    val knowledgeVersion: String = "", val algorithmVersion: String = "")

internal class Journal(context: Context) {
    private val prefs = context.getSharedPreferences("agri_journal", Context.MODE_PRIVATE)
    private val recoveryDirectory = File(context.noBackupFilesDir, "journal-recovery")
    private val tokens = SecureToken()
    var recoveryNotice: String? = null
        private set
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(value) { prefs.edit { putBoolean("demo", value) } }
    val endpoint: String
        get() = prefs.getString("endpoint", "").orEmpty()
    val aiAccessToken: String
        @Synchronized get() = runCatching {
            val encrypted = prefs.getString("ai_access_token_encrypted", "").orEmpty()
            if (encrypted.isNotEmpty()) tokens.decrypt(encrypted) else {
                val legacy = prefs.getString("ai_access_token", "").orEmpty()
                if (legacy.isNotEmpty()) setAiServer(endpoint, legacy)
                legacy
            }
        }.getOrDefault("")

    @Synchronized
    fun setAiServer(address: String, token: String) {
        val encrypted = if (token.isEmpty()) "" else tokens.encrypt(token)
        val editor = prefs.edit()
        editor.putString("endpoint", address).putString("ai_access_token_encrypted", encrypted).remove("ai_access_token")
        check(editor.commit()) { "Không lưu được cấu hình máy chủ." }
    }

    @Synchronized
    fun entries(): List<Scan> {
        val raw = prefs.getString("scans", "[]").orEmpty()
        val array = try { JSONArray(raw) } catch (_: org.json.JSONException) {
            preserveDamagedJournal(raw)
            return emptyList()
        }
        var damaged = false
        val seenIds = mutableSetOf<String>()
        val entries = buildList {
            for (index in 0 until array.length()) try {
            val row = array.getJSONObject(index)
            val ids = row.getJSONArray("cases")
            val scan = Scan(row.getString("id"), row.getLong("time"), Crop.valueOf(row.getString("crop")), row.getString("text"),
                row.getString("headline"), row.getString("question"), List(ids.length()) { ids.getString(it) }, row.optBoolean("demo", true),
                row.optString("kind"), row.optString("stage"), row.optString("part"), row.optString("region"),
                row.optString("knowledge_version"), row.optString("algorithm_version"))
            require(scan.id.isNotBlank() && seenIds.add(scan.id))
            add(scan)
            } catch (_: org.json.JSONException) { damaged = true }
              catch (_: IllegalArgumentException) { damaged = true }
        }
        if (damaged) preserveDamagedJournal(raw)
        return entries
    }

    private fun preserveDamagedJournal(raw: String) {
        check(recoveryDirectory.isDirectory || recoveryDirectory.mkdirs()) { "Không sao lưu được nhật ký lỗi." }
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(recoveryDirectory, "$hash.json")
        if (!target.exists()) {
            val file = AtomicFile(target)
            val output = file.startWrite()
            try { output.write(raw.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
        recoveryNotice = "Một phần nhật ký bị lỗi. App đã giữ bản gốc để khôi phục và chỉ hiển thị các lượt đọc được."
    }

    @Synchronized
    fun save(input: LookupInput, result: LookupResult, demo: Boolean, knowledgeVersion: String): List<Scan> = store(
        (listOf(Scan(UUID.randomUUID().toString(), System.currentTimeMillis(), input.crop, input.symptoms,
            result.headline, result.question, result.cases.map { it.id }, demo, input.kind, input.stage, input.part, input.region,
            knowledgeVersion, LOOKUP_ALGORITHM_VERSION)) + entries()).take(100),
    )

    @Synchronized
    fun remove(id: String) = store(entries().filterNot { it.id == id })

    private fun store(entries: List<Scan>): List<Scan> {
        val array = JSONArray()
        entries.forEach { scan -> array.put(JSONObject().put("id", scan.id).put("time", scan.time)
            .put("crop", scan.crop.name).put("text", scan.text).put("headline", scan.headline)
            .put("question", scan.question).put("cases", JSONArray(scan.cases)).put("demo", scan.demo)
            .put("kind", scan.kind).put("stage", scan.stage).put("part", scan.part).put("region", scan.region)
            .put("knowledge_version", scan.knowledgeVersion).put("algorithm_version", scan.algorithmVersion).put("schema_version", 2)) }
        val editor = prefs.edit()
        editor.putString("scans", array.toString())
        check(editor.commit()) { "Không lưu được nhật ký." }
        return entries
    }
}

internal data class VisionResult(val usable: Boolean, val signs: List<String>, val part: String)
internal class VisionRejected(message: String) : IOException(message)

internal fun validEndpoint(raw: String, debug: Boolean): Boolean = runCatching {
    val uri = URI(raw)
    val local = debug && Build.VERSION.SDK_INT >= 24 && uri.scheme == "http" &&
        uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
    (uri.scheme == "https" || local) && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

internal fun validAiToken(value: String): Boolean = value.isEmpty() ||
    (value.length in 24..200 && value.all { it in '!'..'~' })

internal fun serviceEndpoint(stored: String, debug: Boolean, provisioned: String = BuildConfig.AI_SERVICE_URL): String {
    if (debug && stored.isNotBlank() && validEndpoint(stored, true)) return stored
    return provisioned.takeIf { it.isNotBlank() && validEndpoint(it, false) }.orEmpty()
}

internal fun photoBitmap(context: Context, uri: Uri, limit: Int): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1f, limit.toFloat() / max(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((info.size.width * ratio).roundToInt().coerceAtLeast(1), (info.size.height * ratio).roundToInt().coerceAtLeast(1))
        }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Không đọc được ảnh.")
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (max(options.outWidth, options.outHeight) / options.inSampleSize > limit * 2) options.inSampleSize *= 2
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IOException("Không đọc được ảnh.")
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { if (it !== decoded) decoded.recycle() }
    val ratio = limit.toFloat() / max(bitmap.width, bitmap.height)
    if (ratio >= 1) return bitmap
    return bitmap.scale((bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1))
        .also { if (it !== bitmap) bitmap.recycle() }
}

internal fun analyzePhoto(context: Context, uri: Uri, crop: Crop, endpoint: String, accessToken: String): VisionResult {
    val bitmap = photoBitmap(context, uri, 1024)
    val bytes = try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output))
            if (output.size() > 800_000) {
                output.reset()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 65, output))
            }
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
    val payload = JSONObject().put("crop_id", crop.id).put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
    if (bytes.size > 800_000) throw VisionRejected("Ảnh còn quá lớn. Bác thử ảnh khác hoặc chụp gần dấu hiệu hơn.")
    val response = requestJson(context, endpoint, "/v1/vision", payload, accessToken)
    val signs = response.getJSONArray("signs")
    return VisionResult(response.getBoolean("usable"), List(minOf(3, signs.length())) { signs.getString(it).take(100) }, response.optString("part").take(40))
}

internal fun requestJson(context: Context, endpoint: String, path: String, payload: JSONObject, accessToken: String): JSONObject {
    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!validEndpoint(endpoint, debug = debug && accessToken.isEmpty()) || !validAiToken(accessToken)) {
        throw VisionRejected("Địa chỉ hoặc mã truy cập máy chủ AI chưa hợp lệ. Mã truy cập cần địa chỉ HTTPS.")
    }
    val request = URL("${endpoint.trimEnd('/')}$path").openConnection() as HttpURLConnection
    try {
        request.requestMethod = "POST"
        request.instanceFollowRedirects = false
        request.connectTimeout = 6_000
        request.readTimeout = if (path == "/v1/chat") 120_000 else 30_000
        request.doOutput = true
        request.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        request.useCaches = false
        if (accessToken.isNotEmpty()) request.setRequestProperty("Authorization", "Bearer $accessToken")
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        request.setFixedLengthStreamingMode(body.size)
        request.outputStream.use { it.write(body) }
        val status = request.responseCode
        val stream = if (status == 200) request.inputStream else request.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val buffer = CharArray(8192)
            val count = reader.read(buffer)
            val text = StringBuilder()
            var read = count
            while (read > 0) {
                text.append(buffer, 0, read)
                if (text.length > 32_768) throw IOException("Phản hồi AI quá lớn.")
                read = reader.read(buffer)
            }
            JSONObject(text.toString())
        } ?: throw IOException("Máy chủ AI chưa phản hồi.")
        if (status != 200) throw VisionRejected(response.optString("error", "Máy chủ AI chưa xử lý được yêu cầu.").take(240))
        return response
    } finally { request.disconnect() }
}

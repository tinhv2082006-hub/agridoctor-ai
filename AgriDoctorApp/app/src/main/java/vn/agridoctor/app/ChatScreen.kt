package vn.agridoctor.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun message(role: String, text: String) = JSONObject().put("role", role).put("text", text)

internal fun localAnswer(crop: Crop, turns: JSONArray, catalog: List<PlantCase>): JSONObject {
    val questions = (0 until turns.length()).map { turns.getJSONObject(it) }
        .filter { it.getString("role") == "user" }.map { it.getString("text") }
    fun find(query: String) = Symptoms.lookup(LookupInput(crop, query, "", "", "", ""), catalog, false)
    val latest = find(questions.last())
    val result = if (latest.cases.isEmpty() && questions.size > 1) find(questions.joinToString(". ")) else latest
    val titles = result.cases.take(3).joinToString(", ") { it.title.lowercase() }
    val followup = offlineFollowup(result.question, turns, result.cases.map { it.id }.toSet())
    val answer = if (titles.isEmpty()) followup else "Bác có thể đối chiếu các mục $titles. "+followup
    val sources = JSONArray()
    result.cases.flatMap { it.sources }.distinctBy { it.url to it.locator }.take(6).forEach {
        sources.put(JSONObject().put("title", it.title).put("url", it.url).put("locator", it.locator))
    }
    return JSONObject().put("answer", answer).put("sources", sources).put("mode", "Tra cứu trên máy")
}

private data class PendingChat(val note: String, val photo: Uri? = null, val observation: String? = null) {
    fun bubble() = message("user", note.ifBlank { "Ảnh cây trồng" }).apply {
        photo?.let { put("photo", it.toString()) }
    }
}

private val pendingChatSaver = listSaver<PendingChat?, String>(
    save = { item ->
        if (item == null) emptyList()
        else listOf(item.note, item.photo?.toString().orEmpty(), item.observation.orEmpty())
    },
    restore = { values ->
        if (values.isEmpty()) null else PendingChat(
            values[0], values[1].takeIf(String::isNotBlank)?.let(Uri::parse),
            values[2].takeIf(String::isNotBlank),
        )
    },
)

@Composable
private fun ChatPhoto(photo: String) {
    val context = LocalContext.current
    val preview by produceState<android.graphics.Bitmap?>(null, photo) {
        value = withContext(Dispatchers.IO) {
            try { photoBitmap(context, Uri.parse(photo), 560) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    preview?.let {
        Image(it.asImageBitmap(), contentDescription = "Ảnh cây trồng đã gửi",
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun ChatScreen(initialCrop: Crop, catalog: List<PlantCase>?, endpoint: String, token: String,
                        settings: () -> Unit, offline: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val scroll = rememberLazyListState()
    var crop by rememberSaveable { mutableStateOf(initialCrop) }
    var history by rememberSaveable(crop, endpoint) { mutableStateOf("[]") }
    var draft by rememberSaveable(crop, endpoint) { mutableStateOf("") }
    var engine by rememberSaveable(endpoint) { mutableStateOf("local") }
    var error by remember(crop, endpoint) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingPhoto by remember(crop, endpoint) { mutableStateOf<Uri?>(null) }
    var pending by rememberSaveable(crop, endpoint, stateSaver = pendingChatSaver) {
        mutableStateOf<PendingChat?>(null)
    }
    var analyzingPhoto by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
        if (selected != null) pendingPhoto = selected
    }
    val waiting = busy || analyzingPhoto
    val messages = remember(history) { JSONArray(history) }
    val visibleCount = messages.length() + if (pending != null) 1 else 0

    fun send(item: PendingChat) {
        pending = item
        error = ""
        busy = true
        analyzingPhoto = item.photo != null && item.observation == null
        scope.launch {
            try {
                val observation = item.observation ?: item.photo?.let { selected ->
                    val result = withContext(Dispatchers.IO) { analyzePhoto(context, selected, crop, endpoint, token) }
                    if (!result.usable) "Ảnh không đủ rõ hoặc chưa phù hợp với cây đã chọn."
                    else buildString {
                        if (result.part.isNotBlank()) append("Bộ phận thấy trong ảnh: ${result.part}. ")
                        if (result.signs.isNotEmpty()) append("AI gợi ý dấu hiệu từ ảnh: ${result.signs.joinToString(", ")}.")
                        else append("AI chưa thấy dấu hiệu rõ từ ảnh.")
                    }
                }
                pending = item.copy(observation = observation)
                analyzingPhoto = false
                val text = if (observation == null) item.note else
                    listOf(item.note.take((599 - observation.length).coerceAtLeast(0)), observation)
                        .filter(String::isNotBlank).joinToString(" ")
                val turns = chatRequestTurns(messages, message("user", text))
                val payload = JSONObject().put("crop_id", crop.id).put("messages", turns)
                if (endpoint.startsWith("https://")) payload.put("engine", engine)
                val reply = withContext(Dispatchers.IO) {
                    if (endpoint.isBlank()) localAnswer(crop, turns, catalog.orEmpty())
                    else requestJson(context, endpoint, "/v1/chat", payload, token)
                }
                val next = JSONArray()
                for (i in (messages.length() - 18).coerceAtLeast(0) until messages.length()) {
                    next.put(messages.getJSONObject(i))
                }
                next.put(item.bubble().put("requestText", text))
                next.put(message("assistant", reply.getString("answer"))
                    .put("mode", reply.getString("mode")).put("sources", reply.getJSONArray("sources")))
                history = next.toString()
                pending = null
            } catch (e: CancellationException) { throw e }
            catch (e: VisionRejected) { error = e.message ?: "Máy chủ chưa trả lời." }
            catch (_: Exception) { error = "Chưa nhận được phản hồi. Bác có thể thử lại." }
            finally {
                busy = false
                analyzingPhoto = false
            }
        }
    }

    pendingPhoto?.let { selected ->
        AlertDialog(
            onDismissRequest = { pendingPhoto = null },
            title = { Text("Gửi ảnh cây để phân tích?") },
            text = { Text("Ảnh sẽ được gửi tới Gemini để tìm dấu hiệu. Trợ lý sẽ dùng kết quả đó và lời mô tả của bác để trả lời ngay trong chat.") },
            dismissButton = { TextButton(onClick = { pendingPhoto = null }) { Text("Hủy") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingPhoto = null
                    val item = PendingChat(draft.trim(), selected)
                    draft = ""
                    send(item)
                }) { Text("Gửi ảnh") }
            },
        )
    }
    LaunchedEffect(visibleCount) {
        if (visibleCount > 0) scroll.animateScrollToItem(visibleCount - 1)
    }
    Column(Modifier.fillMaxSize().widthIn(max = 700.dp).padding(horizontal = 14.dp),
           verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Trợ lý cây trồng", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { history = "[]"; pending = null; draft = ""; error = "" }, enabled = !waiting && visibleCount > 0) { Text("Chat mới") }
        }
        if (visibleCount == 0) {
            CropChoices(crop, !waiting) { crop = it }
            if (endpoint.startsWith("https://")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = engine == "local", enabled = !waiting,
                        onClick = { engine = "local" }, label = { Text("Agent Qwen3") })
                    FilterChip(selected = engine == "gemini", enabled = !waiting,
                        onClick = { engine = "gemini" }, label = { Text("Gemini") })
                }
            }
            Text(if (endpoint.isBlank()) "Đang tra cứu tài liệu trên điện thoại. Dịch vụ trò chuyện AI chưa được kết nối."
                else if (endpoint.startsWith("https://") && engine == "gemini") "Câu hỏi và ngữ cảnh chat gần đây sẽ được gửi tới Gemini để trả lời."
                else "Bác mô tả dấu hiệu đang quan sát. Nội dung hội thoại gần đây được gửi đến dịch vụ AgriDoctor để trả lời.",
                style = MaterialTheme.typography.bodySmall)
            if (endpoint.isBlank()) TextButton(onClick = settings) { Text("Thông tin dịch vụ") }
        } else Text(crop.label, color = Muted, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleCount) { i ->
                val msg = if (i < messages.length()) messages.getJSONObject(i) else pending?.bubble() ?: return@items
                val mine = msg.getString("role") == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(modifier = Modifier.fillMaxWidth(if (mine) .83f else .95f),
                        shape = RoundedCornerShape(16.dp), color = if (mine) Leaf else White) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (mine) "Bạn" else "AgriDoctor", color = Forest, style = MaterialTheme.typography.labelMedium)
                            msg.optString("photo").takeIf(String::isNotBlank)?.let { ChatPhoto(it) }
                            Text(msg.getString("text"))
                            val refs = msg.optJSONArray("sources")
                            if (!mine) {
                                var expanded by remember(msg.toString()) { mutableStateOf(false) }
                                TextButton(onClick = { expanded = !expanded }) {
                                    Text(if (expanded) "Ẩn nguồn tham khảo" else "Nguồn tham khảo (${refs?.length() ?: 0})")
                                }
                                if (expanded) {
                                    Text("Cách trả lời: ${msg.optString("mode", "Tra cứu tài liệu")}", color = Muted,
                                        style = MaterialTheme.typography.bodyMedium)
                                    if ((refs?.length() ?: 0) == 0) Text("Chưa có tài liệu đi kèm câu trả lời này.", color = Muted)
                                    for (j in 0 until (refs?.length() ?: 0)) {
                                        val ref = refs!!.getJSONObject(j)
                                        val url = ref.optString("url")
                                        HorizontalDivider(color = Line)
                                        Text("${j + 1}. ${ref.optString("title")}", style = MaterialTheme.typography.bodyMedium)
                                        ref.optString("locator").takeIf { it.isNotBlank() }?.let {
                                            Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        if (url.startsWith("https://")) TextButton(onClick = {
                                            runCatching { uri.openUri(url) }.onFailure { error = "Chưa mở được tài liệu." }
                                        }) { Text("Mở tài liệu ${j + 1}") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (busy && !analyzingPhoto) Text("Trợ lý đang trả lời…", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (analyzingPhoto) Text("Đang phân tích ảnh…", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (error.isNotEmpty() || (pending != null && !waiting)) {
            Text(error.ifEmpty { "Lượt gửi bị gián đoạn. Bác có thể thử lại." },
                color = MaterialTheme.colorScheme.error)
            pending?.let { failed ->
                Row {
                    TextButton(onClick = { send(failed) }, enabled = !waiting) { Text("Thử lại") }
                    TextButton(onClick = { pending = null; draft = failed.note; error = "" }, enabled = !waiting) { Text("Bỏ lượt gửi") }
                }
            } ?: TextButton(onClick = offline) { Text("Tra cứu tại Trang chủ") }
        }
        if (endpoint.isNotBlank()) {
            TextButton(onClick = { picker.launch("image/*") }, enabled = !waiting && pending == null) { Text("Gửi ảnh") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(draft, { draft = it.take(600) }, Modifier.weight(1f), enabled = !waiting,
                maxLines = 3, placeholder = { Text("Nhắn cho trợ lý…") })
            Button(enabled = !waiting && pending == null && draft.isNotBlank() &&
                (endpoint.isNotBlank() || catalog != null), onClick = {
                val item = PendingChat(draft.trim())
                draft = ""
                send(item)
            }) { Text("Gửi") }
        }
        Spacer(Modifier.height(3.dp))
    }
}

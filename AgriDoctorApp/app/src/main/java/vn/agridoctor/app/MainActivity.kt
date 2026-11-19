package vn.agridoctor.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class Page(val label: String, val icon: String) {
    HOME("Trang chủ", "home"), CHAT("Trợ lý", "chat"), LIBRARY("Cẩm nang", "book"), HISTORY("Nhật ký", "history"), SETTINGS("Cài đặt", "settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val knowledge = Knowledge(applicationContext)
        val journal = Journal(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Forest, onPrimary = White, secondaryContainer = Mist, onSecondaryContainer = Ink,
                background = Paper, surface = Paper, onSurface = Ink, onSurfaceVariant = Muted, outline = Line,
            )) { AgriDoctor(knowledge, journal, lifecycle) }
        }
    }
}

@Composable
private fun AgriDoctor(knowledge: Knowledge, journal: Journal, lifecycle: Lifecycle) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val homeList = rememberLazyListState()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    val chatState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var crop by rememberSaveable { mutableStateOf(Crop.RICE) }
    var text by rememberSaveable { mutableStateOf("") }
    var voiceChoices by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var kind by rememberSaveable { mutableStateOf("") }
    var stage by rememberSaveable { mutableStateOf("") }
    var part by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }
    val debug = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    var endpoint by remember { mutableStateOf(serviceEndpoint(journal.endpoint, debug)) }
    var aiAccessToken by remember { mutableStateOf(if (endpoint == journal.endpoint) journal.aiAccessToken else "") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var voiceConsent by rememberSaveable { mutableStateOf(false) }
    var recording by remember { mutableStateOf<AudioInput?>(null) }
    var recordedVoice by remember { mutableStateOf<File?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var savedScanId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryCrop by rememberSaveable { mutableStateOf<Crop?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var catalog by remember { mutableStateOf<List<PlantCase>?>(null) }
    var scans by remember { mutableStateOf<List<Scan>>(emptyList()) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    var suggestion by remember { mutableStateOf<VisionResult?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }
    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    val reader = rememberVoiceReader(context, lifecycle, ::notice)
    fun clearResult() { reader.stop(); result = null; saved = false }
    fun edit(value: String) { text = value.take(500); voiceChoices = arrayListOf(); clearResult() }
    fun input() = LookupInput(crop, text, kind, stage, part, region)
    fun showCase(id: String) { selectedId = id }
    fun clearPhoto() { reader.stop(); photo = null; suggestion = null }
    fun forgetRecording() {
        recording?.discard()
        recording = null
        recordedVoice?.delete()
        recordedVoice = null
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (recording != null || recordedVoice != null)) forgetRecording()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            forgetRecording()
        }
    }

    LaunchedEffect(page, selectedId, savedScanId) { reader.stop() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Clean up files left by an interrupted process without touching an active recording.
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1_000
            context.cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("voice-") && it.extension == "m4a" && it.lastModified() < cutoff
            }?.forEach { it.delete() }
        }
    }
    LaunchedEffect(retry) {
        try {
            catalog = withContext(Dispatchers.IO) { knowledge.cases }
            scans = withContext(Dispatchers.IO) { journal.entries() }
            journal.recoveryNotice?.let(::notice)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { catalogError = true }
    }
    LaunchedEffect(photo) {
        preview = null
        val uri = photo ?: return@LaunchedEffect
        try {
            preview = withContext(Dispatchers.IO) { photoBitmap(context, uri.toUri(), 640) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { clearPhoto(); notice("Không mở được ảnh. Hãy chọn lại.") }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { reader.stop(); photo = uri.toString(); suggestion = null }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) { photo = capture; suggestion = null }
        capture = null
    }
    val liveSpeech = rememberOfflineSpeechInput(context, lifecycle, partial = ::edit, complete = { choices ->
        edit(choices.first())
        voiceChoices = ArrayList(choices)
    }, notice = ::notice)
    LaunchedEffect(page, selectedId, savedScanId) { liveSpeech.cancel("page_changed") }
    val androidSpeech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reply ->
        if (reply.resultCode == Activity.RESULT_OK) {
            val choices = speechOptions(reply.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty())
            if (choices.isNotEmpty()) { edit(choices.first()); voiceChoices = ArrayList(choices) }
            else notice("Ứng dụng giọng nói chưa trả chữ. Bác kiểm tra mạng và tiếng Việt trong dịch vụ giọng nói của Android.")
        }
    }
    fun launchAndroidSpeech() {
        liveSpeech.cancel("android_fallback")
        reader.stop()
        focus.clearFocus()
        try {
            androidSpeech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, VOICE_ALTERNATIVES))
        } catch (_: ActivityNotFoundException) { notice("Điện thoại chưa có ứng dụng nhập giọng nói.") }
    }
    fun startLiveSpeech() {
        forgetRecording()
        reader.stop()
        focus.clearFocus()
        liveSpeech.start(crop)
        scope.launch { homeList.animateScrollToItem(2) }
    }
    val liveMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLiveSpeech() else notice("Bác cho phép dùng mic để nhập giọng nói, hoặc nhập bằng bàn phím.")
    }
    fun stopRecording(reachedLimit: Boolean = false) {
        val active = recording ?: return
        recording = null
        recordedVoice = active.finish(reachedLimit)
        if (recordedVoice == null) notice("Chưa ghi được câu nói. Bác thử ghi lại.")
    }
    fun startRecording() {
        forgetRecording()
        reader.stop()
        focus.clearFocus()
        try {
            recording = AudioInput(context) { active ->
                if (recording === active) stopRecording(reachedLimit = true)
            }
        } catch (_: Exception) {
            notice("Không mở được mic. Bác có thể dùng mục Nhập bằng Android hoặc gõ triệu chứng.")
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else notice("Bác cấp quyền mic để ghi âm, hoặc dùng mục Nhập bằng Android.")
    }
    fun recordVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else mic.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun sendRecording() {
        val file = recordedVoice ?: return
        val chosenCrop = crop
        val address = endpoint
        val accessToken = aiAccessToken
        recordedVoice = null
        working = "Đang chuyển giọng nói thành chữ…"
        scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) { transcribeRecording(context, file, chosenCrop, address, accessToken) }
                edit(transcript)
                voiceChoices = arrayListOf(transcript)
                homeList.animateScrollToItem(2)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VisionRejected) {
                notice(e.message ?: "Chưa nhận dạng được câu nói.")
            } catch (e: java.io.IOException) {
                notice(e.message?.takeIf { it.startsWith("AI chưa nghe rõ") || it.startsWith("Câu nói hơi dài") }
                    ?: "Chưa chuyển được đoạn ghi âm thành chữ. Bác thử lại hoặc dùng mục Nhập bằng Android.")
            } catch (_: Exception) {
                notice("Dịch vụ AI chưa phản hồi đúng. Bác có thể dùng mục Nhập bằng Android.")
            } finally {
                file.delete()
                working = null
            }
        }
    }
    fun launchCamera() {
        try {
            val folder = File(context.cacheDir, "capture").apply { mkdirs() }
            val keep = photo?.toUri()?.lastPathSegment
            folder.listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
            val file = File.createTempFile("plant-", ".jpg", folder)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
            capture = uri.toString()
            camera.launch(uri)
        } catch (_: Exception) { capture = null; notice("Không mở được camera. Bác có thể chọn ảnh có sẵn.") }
    }
    fun launchSpeech() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startLiveSpeech()
        else liveMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun analyze() {
        val uri = photo ?: return
        reader.stop()
        val chosenCrop = crop
        val address = endpoint
        val accessToken = aiAccessToken
        working = "Đang phân tích ảnh…"
        suggestion = null
        scope.launch {
            try {
                suggestion = withContext(Dispatchers.IO) { analyzePhoto(context, uri.toUri(), chosenCrop, address, accessToken) }
            } catch (e: CancellationException) { throw e }
            catch (e: java.net.SocketTimeoutException) { notice("AI phản hồi chậm. Hãy thử lại hoặc nhập triệu chứng.") }
            catch (e: java.net.ConnectException) { notice("Chưa kết nối được dịch vụ phân tích ảnh. Bác kiểm tra mạng rồi thử lại.") }
            catch (e: VisionRejected) { notice(e.message ?: "AI ảnh chưa phản hồi.") }
            catch (_: Exception) { notice("Dịch vụ chưa phân tích được ảnh. Bác thử lại hoặc nhập dấu hiệu quan sát.") }
            finally { working = null }
        }
    }

    LaunchedEffect(result) {
        if (result != null && page == Page.HOME) homeList.animateScrollToItem(5)
    }
    fun lookup() {
        val cases = catalog ?: return
        val values = input()
        val useDemo = false
        focus.clearFocus()
        working = "Đang đối chiếu triệu chứng…"
        voiceChoices = arrayListOf()
        clearResult()
        scope.launch {
            try {
                result = withContext(Dispatchers.Default) { Symptoms.lookup(values, cases, useDemo) }
                if (result?.field != null) more = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice("Chưa tra cứu được. Bác thử lại.")
            } finally { working = null }
        }
    }
    fun save(current: LookupResult) {
        val values = input()
        val useDemo = false
        saveBusy = true
        scope.launch {
            try {
                scans = withContext(Dispatchers.IO) { journal.save(values, current, useDemo, knowledge.version) }
                saved = true
                notice("Đã lưu vào nhật ký.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Chưa lưu được nhật ký.") }
            finally { saveBusy = false }
        }
    }

    val shownCase = catalog?.firstOrNull { it.id == selectedId && !it.pending }
    val shownScan = scans.firstOrNull { it.id == savedScanId && (showArchived || !it.demo) }
    val detail = shownCase != null || shownScan != null
    fun back() { if (selectedId != null) selectedId = null else savedScanId = null }
    BackHandler(liveSpeech.active || recording != null || recordedVoice != null || detail || page != Page.HOME) {
        if (liveSpeech.active) liveSpeech.cancel()
        else if (recording != null || recordedVoice != null) forgetRecording()
        else if (detail) back() else page = Page.HOME
    }

    if (voiceConsent) AlertDialog(
        onDismissRequest = { voiceConsent = false },
        title = { Text("Nhập bằng giọng nói") },
        text = { Text(if (endpoint.isBlank()) "Dịch vụ nhận dạng bản ghi chưa được kết nối. Bác có thể chọn Nói trực tiếp hoặc nhập bằng bàn phím."
            else "Bác có thể ghi tối đa $RECORDING_SECONDS giây. Sau khi dừng, bác chọn gửi bản ghi đến dịch vụ AgriDoctor và Google Gemini để chuyển thành chữ.") },
        confirmButton = {
            Button(onClick = {
                voiceConsent = false
                if (endpoint.isBlank()) page = Page.SETTINGS else recordVoice()
            }) { Text(if (endpoint.isBlank()) "Mở cài đặt" else "Bắt đầu ghi âm") }
        },
        dismissButton = { TextButton(onClick = { voiceConsent = false; launchSpeech() }) { Text("Nói trực tiếp") } },
    )

    if (recording != null) AlertDialog(
        onDismissRequest = ::forgetRecording,
        title = { Text("Đang ghi âm") },
        text = { Text("Bác nói một câu ngắn về dấu hiệu trên ${crop.label.lowercase()}. Bản ghi tự dừng sau $RECORDING_SECONDS giây.") },
        confirmButton = { Button(onClick = { stopRecording() }) { Text("Dừng ghi") } },
        dismissButton = { TextButton(onClick = ::forgetRecording) { Text("Hủy") } },
    )

    if (recordedVoice != null) AlertDialog(
        onDismissRequest = ::forgetRecording,
        title = { Text("Gửi bản ghi để nhận dạng?") },
        text = { Text("Bản ghi sẽ được gửi đến dịch vụ AgriDoctor và Google Gemini để chuyển thành chữ.") },
        confirmButton = { Button(onClick = ::sendRecording) { Text("Gửi để nhận dạng") } },
        dismissButton = { TextButton(onClick = ::forgetRecording) { Text("Bỏ bản ghi") } },
    )

    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text("Gửi ảnh để phân tích?") },
        text = { Text("Ảnh sẽ được gửi đến dịch vụ AgriDoctor và Google Gemini để nhận diện dấu hiệu. Chỉ gửi ảnh cây trồng.") },
        confirmButton = { Button(onClick = { consent = false; analyze() }) { Text("Gửi ảnh") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Hủy") } },
    )
    deleteId?.let { id -> AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("Xóa lượt tra cứu?") },
        confirmButton = {
            TextButton(onClick = {
                deleteId = null
                scope.launch {
                    try {
                        scans = withContext(Dispatchers.IO) { journal.remove(id) }
                        savedScanId = null
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("Chưa xóa được lượt tra cứu.") }
                }
            }) { Text("Xóa") }
        },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Hủy") } },
    ) }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (detail) IconButton(onClick = ::back) { Glyph("back", Forest, description = "Quay lại") }
                else Brand()
                Text(if (detail) "Chi tiết" else "AgriDoctor AI", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (reader.isReading) TextButton(onClick = reader::stop) { Text("Dừng đọc") }
                else if (!detail && page == Page.HOME) Pill("Tra cứu offline")
            }
        },
        bottomBar = {
            if (!detail) NavigationBar(containerColor = White, tonalElevation = 0.dp) {
                Page.entries.forEach { tab ->
                    NavigationBarItem(selected = page == tab, enabled = working == null && !saveBusy,
                        onClick = { focus.clearFocus(); page = tab },
                        icon = { Glyph(tab.icon, if (page == tab) Forest else Muted) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Leaf, selectedTextColor = Forest))
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            when {
                shownCase != null -> CaseDetail(shownCase, reader, ::notice)
                shownScan != null -> Feed {
                    item {
                        Header(shownScan.crop.label, shownScan.text)
                        if (shownScan.demo) Text("Lượt lưu từ chế độ thử trước đây, chưa xác nhận là quan sát thực tế.", color = Muted)
                        ListenButton("Nghe mô tả đã lưu", "${shownScan.crop.label}. ${shownScan.text}", reader)
                        val details = listOf("Loại cây" to shownScan.kind, "Giai đoạn" to shownScan.stage,
                            "Bộ phận" to shownScan.part, "Vùng trồng" to shownScan.region).filter { it.second.isNotBlank() }
                        details.forEach { (label, value) -> Text("$label: $value", color = Muted) }
                        if (shownScan.knowledgeVersion.isEmpty()) Text("Lượt cũ chưa lưu bối cảnh và phiên bản kho.", color = Muted)
                        if (shownScan.knowledgeVersion.isNotEmpty() && shownScan.knowledgeVersion != knowledge.version)
                            Text("Kho đã được cập nhật kể từ lượt tra cứu này. Nội dung chi tiết đang dùng kho hiện tại.", color = Muted)
                    }
                    item { ResultPanel(LookupResult(shownScan.headline, shownScan.question, catalog.orEmpty().filter {
                        it.id in shownScan.cases && !it.pending
                    }), false, ::showCase, reader) }
                    item { TextButton(onClick = { deleteId = shownScan.id }) { Text("Xóa lượt tra cứu") } }
                }
                page == Page.HOME -> Feed(homeList) {
                    item { Hero(onCamera = ::launchCamera, onVoice = { if (liveSpeech.active) liveSpeech.finish() else launchSpeech() },
                        enabled = working == null && !saveBusy, listening = liveSpeech.active, finishing = liveSpeech.finishing) }
                    item {
                        Label("Cây trồng")
                        CropChoices(crop, working == null && !saveBusy && !liveSpeech.active) { selected ->
                            if (crop != selected) { crop = selected; kind = ""; stage = ""; part = ""; suggestion = null; edit("") }
                        }
                    }
                    item {
                        Panel {
                            Label("Dấu hiệu quan sát")
                            val editable = working == null && !saveBusy && !liveSpeech.active
                            OutlinedTextField(value = text, onValueChange = ::edit, modifier = Modifier.fillMaxWidth(), enabled = editable,
                                placeholder = { Text("Ví dụ: ${crop.sample}") }, minLines = 2, maxLines = 5,
                                shape = RoundedCornerShape(16.dp))
                            if (liveSpeech.active) {
                                Text(liveSpeech.status,
                                    color = Forest, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                Text("Tín hiệu mic", color = Muted, style = MaterialTheme.typography.bodySmall)
                                LinearProgressIndicator(progress = { liveSpeech.level }, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { liveSpeech.finish() }, enabled = !liveSpeech.finishing) { Text("Nói xong") }
                                    TextButton(onClick = { liveSpeech.cancel() }) { Text("Dừng nghe") }
                                }
                            }
                            if (liveSpeech.errorText.isNotEmpty() && !liveSpeech.active) {
                                Text(liveSpeech.errorText, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = ::launchAndroidSpeech, enabled = editable) { Text("Thử ứng dụng giọng nói của Android") }
                            }
                            if (text.isNotBlank() && !liveSpeech.active) ListenButton("Nghe mô tả", text, reader)
                            if (voiceChoices.isNotEmpty()) {
                                Label("Kiểm tra câu vừa nói")
                                Text("Bác xem lại câu trong ô mô tả. Nếu chưa đúng, bác sửa chữ hoặc nói lại.", color = Muted)
                                if (voiceChoices.size > 1) {
                                    Label("Các cách app nghe được")
                                    voiceChoices.forEach { choice ->
                                        val selected = choice == text
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                                .selectable(selected, enabled = editable, role = Role.RadioButton, onClick = {
                                                    text = choice
                                                    clearResult()
                                                }).padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            RadioButton(selected = selected, onClick = null, enabled = editable)
                                            Text(choice)
                                        }
                                    }
                                }
                                TextButton(onClick = ::launchSpeech, enabled = editable) { Text("Nói lại") }
                                Button(
                                    onClick = ::lookup,
                                    enabled = editable && text.isNotBlank() && catalog != null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Xác nhận và tra cứu") }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(enabled = editable, onClick = { more = !more }) { Text(if (more) "Ẩn bối cảnh" else "+ Bổ sung bối cảnh") }
                            }
                            TextButton(enabled = editable && endpoint.isNotBlank(), onClick = { voiceConsent = true }) { Text("Gửi bản ghi để nhận dạng") }
                            if (more) {
                                if (crop == Crop.COFFEE) Field("Loại cà phê", kind, editable) { kind = it; clearResult() }
                                Field("Giai đoạn sinh trưởng", stage, editable) { stage = it; clearResult() }
                                Field("Bộ phận bị ảnh hưởng", part, editable) { part = it; clearResult() }
                                Field("Tỉnh hoặc vùng trồng", region, editable) { region = it; clearResult() }
                            }
                        }
                    }
                    item {
                        Panel {
                            Label("Ảnh cây trồng")
                            preview?.let { bitmap -> Image(bitmap.asImageBitmap(), "Ảnh cây đã chọn", Modifier.fillMaxWidth()
                                .height(180.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = working == null && !liveSpeech.active) {
                                    Text(if (photo == null) "Chọn ảnh" else "Đổi ảnh")
                                }
                                if (photo != null) TextButton(onClick = ::clearPhoto, enabled = working == null) { Text("Bỏ ảnh") }
                            }
                            if (photo != null) OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = preview != null && working == null && !saveBusy && !liveSpeech.active,
                                onClick = {
                                    if (endpoint.isEmpty()) notice("Dịch vụ phân tích ảnh chưa được kết nối. Bác có thể nhập dấu hiệu để tra cứu trên điện thoại.")
                                    else consent = true
                                }) { Text("Phân tích ảnh bằng AI") }
                            suggestion?.let { vision ->
                                if (vision.usable && vision.signs.isNotEmpty()) {
                                    val message = "AI nhìn thấy: ${vision.signs.joinToString(", ")}. Bác kiểm tra trước khi dùng."
                                    Text(message)
                                    ListenButton("Nghe dấu hiệu từ AI", message, reader)
                                    TextButton(onClick = {
                                        edit(listOf(text.trim(), vision.signs.joinToString(", ")).filter(String::isNotBlank).joinToString(", "))
                                        if (part.isBlank()) part = vision.part
                                        suggestion = null
                                    }, enabled = working == null && !saveBusy) { Text("Thêm vào mô tả") }
                                } else {
                                    val message = "AI chưa thấy dấu hiệu rõ. Bác thử ảnh cận cảnh có đủ ánh sáng."
                                    Text(message)
                                    ListenButton("Nghe hướng dẫn", message, reader)
                                }
                            }
                        }
                    }
                    item {
                        if (catalogError) TextButton(onClick = { catalogError = false; retry++ }) { Text("Tải lại kho tri thức") }
                        if (voiceChoices.isEmpty()) {
                            Button(onClick = ::lookup, enabled = text.isNotBlank() && catalog != null && working == null && !saveBusy && !liveSpeech.active,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) { Text("Tra cứu triệu chứng", fontSize = 16.sp) }
                        }
                        working?.let {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                            Text(it, Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = Muted)
                        }
                    }
                    result?.let { current ->
                        item {
                            ResultPanel(current, current.cases.any { it.pending }, ::showCase, reader)
                            Button(onClick = { save(current) }, enabled = !saved && !saveBusy && !liveSpeech.active, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                Text(if (saved) "Đã lưu nhật ký" else "Lưu vào nhật ký")
                            }
                        }
                    }
                }
                page == Page.CHAT -> chatState.SaveableStateProvider("chat") {
                    ChatScreen(crop, catalog, endpoint, aiAccessToken, { page = Page.SETTINGS }, { page = Page.HOME })
                }
                page == Page.LIBRARY -> {
                    val visible = remember(catalog, libraryCrop, query) {
                        val needle = normalize(query)
                        catalog.orEmpty().filter { !it.pending && (libraryCrop == null || it.crop == libraryCrop) &&
                            (needle.isEmpty() || normalize(it.title + " " + it.signs).contains(needle) || it.aliases.any { alias -> alias.contains(needle) }) }
                    }
                    Feed {
                        item { Header("Cẩm nang cây trồng", "Tìm dấu hiệu và xem nguồn tham khảo.") }
                        item { Field("Tìm trong cẩm nang", query, true) { query = it } }
                        item { CropChoices(libraryCrop, true) { libraryCrop = if (libraryCrop == it) null else it } }
                        if (visible.isEmpty()) item { Empty(if (catalogError) "Chưa mở được kho tri thức." else if (catalog == null) "Đang tải cẩm nang…" else "Chưa có mục phù hợp.") }
                        items(visible, key = { it.id }) { item -> CaseRow(item, reader) { showCase(item.id) } }
                    }
                }
                page == Page.HISTORY -> Feed {
                    item {
                        Header(if (showArchived) "Lượt lưu từ bản cũ" else "Nhật ký tra cứu",
                            if (showArchived) "Các lượt mang cờ chế độ thử trước đây, chưa xác nhận là quan sát thực tế." else "Các lượt đã lưu trên thiết bị này.")
                        if (scans.any { it.demo }) TextButton(onClick = { showArchived = !showArchived }) {
                            Text(if (showArchived) "Về nhật ký hiện tại" else "Xem lượt lưu từ bản cũ (${scans.count { it.demo }})")
                        }
                    }
                    val liveScans = scans.filter { it.demo == showArchived }
                    if (liveScans.isEmpty()) item { Empty("Chưa có lượt tra cứu nào được lưu.") }
                    items(liveScans, key = { it.id }) { scan ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(onClick = { savedScanId = scan.id }, color = White, shape = Radius, modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(scan.crop.label, color = Forest, fontWeight = FontWeight.Bold)
                                    Text(scan.text, maxLines = 2)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag("vi-VN"))
                                        .format(Date(scan.time)), style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }
                            ListenButton("Nghe lượt tra cứu ${scan.crop.label}", "${scan.crop.label}. ${scan.text}. ${scan.question}", reader, compact = true)
                        }
                    }
                }
                else -> Settings(endpoint, aiAccessToken, catalog.orEmpty().count { !it.pending }, onServer = { address, token ->
                    try {
                        journal.setAiServer(address, token)
                        chatState.removeState("chat")
                        endpoint = serviceEndpoint(address, debug)
                        aiAccessToken = if (endpoint == address) token else ""
                    } catch (_: Exception) { notice("Chưa lưu được cấu hình an toàn. Bác thử lại.") }
                })
            }
        }
    }
}

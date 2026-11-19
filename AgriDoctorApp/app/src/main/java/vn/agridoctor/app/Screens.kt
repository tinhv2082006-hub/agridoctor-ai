package vn.agridoctor.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Forest = Color(0xFF124633)
internal val Ink = Color(0xFF142B20)
internal val Muted = Color(0xFF4A6254)
internal val Leaf = Color(0xFFCBE985)
internal val Mist = Color(0xFFE5F0E7)
internal val Paper = Color(0xFFF6F7F2)
internal val Line = Color(0xFFD5DED3)
internal val White = Color.White
internal val Radius = RoundedCornerShape(24.dp)

@Composable
internal fun Feed(state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
    )
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = White, shape = Radius, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
internal fun Brand() {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Forest), contentAlignment = Alignment.Center) {
        Glyph("leaf", Leaf)
    }
}

@Composable
internal fun Pill(value: String) {
    Surface(color = Mist, shape = RoundedCornerShape(50)) {
        Text(value, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Forest)
    }
}

@Composable
internal fun Hero(onCamera: () -> Unit, onVoice: () -> Unit, enabled: Boolean, listening: Boolean = false, finishing: Boolean = false) {
    Box(Modifier.fillMaxWidth().clip(Radius).background(Forest)) {
        Canvas(Modifier.matchParentSize()) {
            val x = size.width
            val y = size.height
            val branches = listOf(0.58f, 0.72f, 0.86f, 1f)
            for (start in branches) {
                val path = Path().apply {
                    moveTo(x * start, y * 1.03f)
                    cubicTo(x * (start + 0.08f), y * 0.72f, x * 0.68f, y * 0.37f, x * 1.10f, y * 0.13f)
                }
                drawPath(path, Leaf.copy(alpha = 0.16f), style = Stroke(width = 1.dp.toPx()))
            }
            drawCircle(Leaf.copy(alpha = 0.08f), radius = x * 0.17f, center = Offset(x * 0.89f, y * 0.25f))
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Quan sát cây,\nhiểu dấu hiệu", color = White, fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Text("Chụp ảnh, nói hoặc nhập triệu chứng.", color = Color(0xFFDCECE0), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onVoice, enabled = enabled && !finishing, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Ink,
                    disabledContainerColor = Mist, disabledContentColor = Muted)) {
                Glyph("mic", Ink)
                Spacer(Modifier.width(8.dp))
                Text(if (listening) "Nói xong" else "Nói triệu chứng", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onCamera, enabled = enabled && !listening, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = White, disabledContentColor = Mist),
                border = androidx.compose.foundation.BorderStroke(1.dp, Leaf.copy(alpha = 0.6f))) {
                Glyph("camera", White)
                Spacer(Modifier.width(8.dp))
                Text("Chụp ảnh cây", fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun Header(title: String, supporting: String) {
    Column(Modifier.padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Text(supporting, color = Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CropChoices(current: Crop?, enabled: Boolean, onSelect: (Crop) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Crop.entries.forEach { crop ->
            FilterChip(selected = current == crop, onClick = { onSelect(crop) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp), label = { Text(crop.label, fontWeight = FontWeight.Medium) },
                leadingIcon = { Glyph(crop.icon, Forest, 17) }, shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = White, selectedContainerColor = Leaf))
        }
    }
}

@Composable
internal fun Field(label: String, value: String, enabled: Boolean, update: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { update(it.take(100)) }, modifier = Modifier.fillMaxWidth(),
        enabled = enabled, singleLine = true, shape = RoundedCornerShape(16.dp), label = { Text(label) })
}

@Composable
internal fun Empty(text: String) {
    Panel { Text(text, color = Muted, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
internal fun ResultPanel(result: LookupResult, pending: Boolean, onCase: (String) -> Unit, reader: VoiceReader) {
    Surface(shape = Radius, color = Mist, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (pending) Pill("Chờ duyệt nội dung")
            Text(result.headline, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(result.question, style = MaterialTheme.typography.bodyLarge)
            val options = result.cases.joinToString(", ") { it.title }
            val summary = listOfNotNull(if (pending) "Nội dung chờ duyệt" else null,
                result.headline, result.question, options.takeIf { it.isNotBlank() }?.let { "Tình huống để đối chiếu. $it" })
            ListenButton("Nghe kết quả", summary.joinToString(". "), reader)
            if (result.cases.isNotEmpty()) Label("Tình huống để đối chiếu")
            if (result.cases.size == 1 && Treatments.forCase(result.cases.single()).isNotEmpty()) {
                Text("Mở chi tiết để đối chiếu dấu hiệu và xem tài liệu về thuốc.", color = Muted)
            }
            result.cases.forEach { entry -> CaseRow(entry, reader) { onCase(entry.id) } }
        }
    }
}

private fun categoryLabel(category: String): String = when (category) {
    "pest" -> "Sinh vật gây hại"
    "abiotic" -> "Điều kiện sinh trưởng"
    else -> "Bệnh cây"
}

@Composable
internal fun CaseRow(item: PlantCase, reader: VoiceReader, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onClick, color = White, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Mist), contentAlignment = Alignment.Center) {
                    Glyph(item.crop.icon, Forest, 20)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                    Text("${item.crop.label} · ${categoryLabel(item.category)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Glyph("next", Forest, 18)
            }
        }
        ListenButton("Nghe ${item.title}", "${item.title}. ${item.crop.label}. ${item.signs}", reader, compact = true)
    }
}

@Composable
internal fun ListenButton(label: String, speech: String, reader: VoiceReader, compact: Boolean = false) {
    val reading = reader.isReading(speech)
    val action = if (reading) "Dừng đọc" else label
    if (compact) {
        IconButton(onClick = { reader.toggle(speech) }) {
            if (reading) Text("■", modifier = Modifier.semantics { contentDescription = action }, color = Forest)
            else Glyph("sound", Forest, 20, action)
        }
    } else {
        TextButton(onClick = { reader.toggle(speech) }) {
            if (reading) Text("■", color = Forest) else Glyph("sound", Forest, 18)
            Spacer(Modifier.width(6.dp))
            Text(action)
        }
    }
}

@Composable
internal fun CaseDetail(item: PlantCase, reader: VoiceReader, notice: (String) -> Unit) {
    val uri = LocalUriHandler.current
    val treatments = Treatments.forCase(item)
    var showTreatments by remember(item.id) { mutableStateOf(false) }
    Feed {
        item {
            Header(item.title, "${item.crop.label} · ${categoryLabel(item.category)}")
            if (item.pending) Pill("Chờ duyệt nội dung")
            if (item.projectApproved) Pill("Chủ dự án đã duyệt sử dụng")
            val review = if (item.pending) ". Nội dung chờ duyệt." else "."
            ListenButton("Nghe thông tin tình huống", "${item.title}. ${item.crop.label}. ${categoryLabel(item.category)}$review", reader)
        }
        item {
            Panel {
                Label("Dấu hiệu thường gặp")
                Text(item.signs, style = MaterialTheme.typography.bodyLarge)
                if (item.organs.isNotBlank()) Text("Vị trí: ${item.organs}", color = Muted)
                ListenButton("Nghe dấu hiệu", "${item.title}. ${item.signs} ${if (item.organs.isBlank()) "" else "Vị trí. ${item.organs.replace(';', ',')}."}", reader)
            }
        }
        item {
            Panel {
                Label("Cần quan sát thêm")
                Text(item.question, style = MaterialTheme.typography.bodyLarge)
                if (item.observation.isNotBlank()) Text(item.observation)
                ListenButton("Nghe hướng dẫn", "${item.question} ${item.observation}", reader)
                if (item.lookalikes.isNotBlank()) {
                    Label("Dấu hiệu dễ nhầm")
                    Text(item.lookalikes)
                    ListenButton("Nghe dấu hiệu dễ nhầm", "Dấu hiệu dễ nhầm. ${item.lookalikes}", reader)
                }
            }
        }
        if (item.category == "disease" || item.category == "pest") {
            item {
                Panel {
                    Label("Thuốc và liều dùng")
                    val instructions = if (treatments.isEmpty()) {
                        "Chưa có thuốc kèm liều dùng đã đối chiếu nguồn cho tình huống này."
                    } else {
                        "Chỉ cân nhắc dùng thuốc khi cán bộ kỹ thuật đã xác nhận đúng dịch hại. Hãy đối chiếu nhãn trên bao bì về cây trồng, liều dùng, cách phun và thời gian cách ly."
                    }
                    Text(instructions)
                    ListenButton("Nghe lưu ý về thuốc", instructions, reader)
                    if (treatments.isNotEmpty()) {
                        OutlinedButton(onClick = { reader.stop(); showTreatments = !showTreatments }) {
                            Text(if (showTreatments) "Ẩn thuốc và liều dùng" else "Xem tài liệu về thuốc")
                        }
                        TextButton(onClick = {
                            runCatching { uri.openUri(Treatments.safeUse) }.onFailure { notice("Thiết bị chưa mở được nguồn.") }
                        }) { Text("Quy định sử dụng thuốc · Điều 72") }
                    }
                }
            }
            if (showTreatments) {
                items(treatments.size) { index -> TreatmentCard(treatments[index], reader, notice) }
            }
        }
        item {
            Panel {
                Label("Nguồn tham khảo")
                item.sources.forEach { source ->
                    Text(source.title, fontWeight = FontWeight.SemiBold)
                    val description = listOf(source.publisher, source.locator, source.region).filter(String::isNotBlank).joinToString(" · ")
                    if (description.isNotBlank()) Text(description, color = Muted)
                    ListenButton("Nghe nguồn tham khảo", "${source.title}. $description", reader)
                    if (source.url.startsWith("https://") || source.url.startsWith("http://")) TextButton(onClick = {
                        runCatching { uri.openUri(source.url) }.onFailure { notice("Thiết bị chưa mở được nguồn.") }
                    }) { Text("Mở nguồn") }
                }
            }
        }
    }
}

@Composable
private fun TreatmentCard(treatment: Treatment, reader: VoiceReader, notice: (String) -> Unit) {
    val uri = LocalUriHandler.current
    fun open(url: String) {
        runCatching { uri.openUri(url) }.onFailure { notice("Thiết bị chưa mở được nguồn.") }
    }

    Panel {
        Text(treatment.product, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(treatment.activeIngredients, color = Muted)
        Label("Liều dùng tham khảo")
        Text(treatment.dose, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(treatment.application)
        Text("Cách ly trước thu hoạch: ${treatment.preHarvestDays} ngày", fontWeight = FontWeight.SemiBold)
        ListenButton("Nghe thuốc và liều dùng", "${treatment.product}. Hoạt chất. ${treatment.activeIngredients}. " +
            "Liều dùng tham khảo. ${treatment.dose}. ${treatment.application} " +
            "Cách ly trước thu hoạch ${treatment.preHarvestDays} ngày. Đối chiếu nhãn trên bao bì trước khi dùng.", reader)
        HorizontalDivider(color = Line)
        Text("Đối chiếu nguồn ngày ${Treatments.checkedOn}", color = Muted)
        Text(treatment.directions.title, fontWeight = FontWeight.SemiBold)
        Text(treatment.directions.locator, color = Muted)
        TextButton(onClick = { open(treatment.directions.url) }) { Text("Mở nguồn hướng dẫn sử dụng") }
        treatment.intervalSource?.let { source ->
            Text(source.title, fontWeight = FontWeight.SemiBold)
            Text(source.locator, color = Muted)
            TextButton(onClick = { open(source.url) }) { Text("Mở nguồn thời gian cách ly") }
        }
        Text("Phụ lục I · Thông tư 75/2025/TT-BNNMT", fontWeight = FontWeight.SemiBold)
        Text(treatment.registrationLocator, color = Muted)
        val sources = listOfNotNull(treatment.directions.title, treatment.directions.locator,
            treatment.intervalSource?.title, treatment.intervalSource?.locator, "Phụ lục một Thông tư 75 năm 2025", treatment.registrationLocator)
        ListenButton("Nghe nguồn của thuốc", sources.joinToString(". "), reader)
        TextButton(onClick = { open(Treatments.register) }) { Text("Mở danh mục đăng ký") }
        TextButton(onClick = { open(Treatments.amendment) }) { Text("Mở Thông tư sửa đổi 28/2026/TT-BNNMT") }
    }
}

@Composable
internal fun Settings(
    endpoint: String, accessToken: String, approvedCount: Int,
    onServer: (String, String) -> Unit,
) {
    val debug = (androidx.compose.ui.platform.LocalContext.current.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    var draft by rememberSaveable(endpoint) { mutableStateOf(endpoint) }
    var token by remember(endpoint, accessToken) { mutableStateOf(accessToken) }
    val address = draft.trim().trimEnd('/')
    val normalizedToken = token.trim()
    val valid = address.isEmpty() || validEndpoint(address, debug && normalizedToken.isEmpty())
    val tokenValid = validAiToken(normalizedToken)
    var advanced by rememberSaveable { mutableStateOf(false) }
    Feed {
        item { Header("Cài đặt", "Thông tin ứng dụng và dữ liệu của bác.") }
        item {
            Panel {
                Label("Trợ lý cây trồng")
                Text(when {
                    endpoint.isBlank() -> "Dịch vụ trò chuyện AI chưa được thiết lập. Bác vẫn có thể tra cứu triệu chứng và cẩm nang trên điện thoại."
                    endpoint.startsWith("http://") -> "Đang kết nối trợ lý trên máy tính của nhóm phát triển. Dịch vụ trực tuyến cho khách hàng chưa được thiết lập."
                    else -> "Đã thiết lập dịch vụ trợ lý. Bác kết nối Internet để trò chuyện."
                }, color = Muted)
                Text("Khách hàng không cần tự nhập địa chỉ kết nối. Dịch vụ trực tuyến sẽ do nhóm phát triển thiết lập sẵn trong bản phát hành.", color = Muted)
            }
        }
        item {
            Panel {
                Label("Kho kiến thức")
                Text("$approvedCount tình huống có tài liệu tham khảo, thuộc lúa nước, cà phê và sầu riêng.")
                Text("Chỉ sử dụng nội dung đã được chủ dự án duyệt. Việc duyệt sử dụng chưa thay thế thẩm định chuyên môn độc lập.", color = Muted)
                Label("Dữ liệu và quyền riêng tư")
                Text("Nhật ký lưu trên điện thoại khi bác chọn lưu. Nói triệu chứng được xử lý ngay trên điện thoại, không gửi âm thanh đi. Ảnh và bản ghi chỉ được gửi khi bác chọn gửi và xác nhận.", color = Muted)
                Text("AgriDoctor AI · ${BuildConfig.VERSION_NAME}", color = Muted)
            }
        }
        if (debug) item {
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ẩn thiết lập kỹ thuật" else "Thiết lập dành cho kỹ thuật") }
        }
        if (debug && advanced) item {
            Panel {
                Label("Máy chủ AI")
                Text("Chỉ dành cho nhóm phát triển. Bản phát hành dùng địa chỉ HTTPS được thiết lập khi đóng gói ứng dụng.", color = Muted)
                OutlinedTextField(value = draft, onValueChange = { updated ->
                    if (updated.trim().trimEnd('/') != draft.trim().trimEnd('/')) token = ""
                    draft = updated.take(240)
                }, singleLine = true, isError = !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    label = { Text("Địa chỉ máy chủ") }, placeholder = { Text("https://…") })
                if (!valid) Text("Nhập địa chỉ HTTPS hợp lệ. Kết nối cục bộ dành cho kỹ thuật không dùng mã truy cập.", color = MaterialTheme.colorScheme.error)
                if (address.startsWith("https://")) OutlinedTextField(
                    value = token, onValueChange = { token = it.take(200) }, singleLine = true, isError = !tokenValid,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), label = { Text("Mã truy cập máy chủ AI") },
                )
                if (debug && android.os.Build.VERSION.SDK_INT >= 24) {
                    TextButton(onClick = { draft = "http://127.0.0.1:8765"; token = "" }) { Text("Dùng máy chủ qua USB") }
                    Text("Cắm cáp vào laptop và mở Mo_Demo_Dien_Thoai.cmd trên laptop trước khi chat.", color = Muted)
                    TextButton(onClick = { draft = "http://10.0.2.2:8765"; token = "" }) { Text("Dùng máy chủ trên máy ảo") }
                }
                Button(
                    onClick = { onServer(address, if (address.isEmpty()) "" else normalizedToken) },
                    enabled = valid && (address.isEmpty() || tokenValid) && (address != endpoint || normalizedToken != accessToken),
                ) { Text("Lưu cấu hình") }
            }
        }
    }
}

@Composable
internal fun Glyph(name: String, color: Color, dimension: Int = 24, description: String? = null) {
    val modifier = Modifier.size(dimension.dp).let { base -> if (description == null) base else base.semantics { contentDescription = description } }
    Canvas(modifier) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, point(x, y), point(a, b), strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * unit, point(x, y), style = Stroke(1.65f * unit))
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) = drawRoundRect(color, point(x, y), Size(w * unit, h * unit), CornerRadius(r * unit), style = Stroke(1.65f * unit))
        when (name) {
            "chat" -> { rect(3f, 4f, 18f, 13f, 3f); line(7f, 17f, 7f, 21f); line(7f, 21f, 12f, 17f); line(7f, 10f, 16f, 10f) }
            "home" -> { line(3f, 11f, 12f, 3f); line(12f, 3f, 21f, 11f); rect(6f, 10f, 12f, 11f) }
            "book" -> { rect(4f, 4f, 16f, 16f); line(9f, 4f, 9f, 20f); line(12f, 9f, 17f, 9f); line(12f, 13f, 17f, 13f) }
            "history" -> { circle(12f, 12f, 8f); line(12f, 7f, 12f, 12f); line(12f, 12f, 15f, 14f) }
            "settings" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f); circle(9f, 6f, 2f); circle(15f, 12f, 2f); circle(10f, 18f, 2f) }
            "camera" -> { rect(3f, 7f, 18f, 13f); rect(8f, 4f, 8f, 3f, 1f); circle(12f, 13.5f, 3f) }
            "mic" -> { rect(9f, 3f, 6f, 12f, 3f); line(5f, 12f, 5f, 14f); line(19f, 12f, 19f, 14f); line(5f, 14f, 9f, 18f); line(19f, 14f, 15f, 18f); line(9f, 18f, 15f, 18f); line(12f, 18f, 12f, 22f) }
            "back" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "next" -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            "sound" -> { rect(4f, 9f, 4f, 6f, 0f); line(8f, 9f, 13f, 5f); line(8f, 15f, 13f, 19f); line(13f, 5f, 13f, 19f); line(17f, 8f, 20f, 12f); line(20f, 12f, 17f, 16f) }
            "coffee" -> { circle(8f, 14f, 4f); circle(16f, 14f, 4f); line(12f, 9f, 15f, 4f); line(15f, 4f, 20f, 4f) }
            "rice" -> { line(7f, 22f, 17f, 3f); line(11f, 15f, 7f, 9f); line(14f, 10f, 11f, 4f); line(12f, 13f, 20f, 11f); line(9f, 18f, 16f, 17f) }
            "durian" -> { circle(12f, 13f, 7f); line(12f, 6f, 15f, 2f); line(6f, 8f, 3f, 6f); line(18f, 10f, 21f, 7f); line(7f, 18f, 5f, 21f); line(16f, 18f, 19f, 21f) }
            else -> {
                val leaf = Path().apply {
                    moveTo(6f * unit, 17f * unit)
                    cubicTo(3f * unit, 7f * unit, 11f * unit, 5f * unit, 21f * unit, 4f * unit)
                    cubicTo(20f * unit, 15f * unit, 15f * unit, 20f * unit, 8f * unit, 18f * unit)
                    close()
                }
                drawPath(leaf, color)
                line(4f, 21f, 14f, 11f)
            }
        }
    }
}

package vn.agridoctor.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest
import android.util.AtomicFile
import java.io.InputStream

internal enum class Crop(val id: String, val label: String, val sample: String, val icon: String) {
    DURIAN("durian", "Sầu riêng", "xì mủ", "durian"),
    COFFEE("coffee", "Cà phê", "rỉ sắt", "coffee"),
    RICE("rice", "Lúa nước", "bông bạc", "rice");
}

internal data class Source(val title: String, val url: String, val locator: String, val region: String, val publisher: String = "")
internal data class PlantCase(
    val id: String, val crop: Crop, val title: String, val category: String, val organs: String,
    val signs: String, val question: String, val observation: String, val lookalikes: String,
    val pending: Boolean, val aliases: List<String>, val rules: Map<String, String>, val sources: List<Source>,
    val projectApproved: Boolean = false,
)
internal data class LookupInput(val crop: Crop, val symptoms: String, val kind: String, val stage: String, val part: String, val region: String)
internal data class LookupResult(val headline: String, val question: String, val cases: List<PlantCase>, val field: String? = null)
internal const val LOOKUP_ALGORITHM_VERSION = "2"

private fun InputStream.sha256(): String = use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal class Knowledge(private val context: Context) {
    val cases: List<PlantCase> by lazy { load() }
    var version: String = ""
        private set
    companion object { private val installLock = Any() }

    private fun load(): List<PlantCase> {
        val target = File(context.filesDir, "knowledge-v1.3.sqlite")
        synchronized(installLock) {
            val assetHash = context.assets.open("agridoctor.sqlite").sha256()
            val stored = AtomicFile(target)
            val currentHash = runCatching { stored.openRead().sha256() }.getOrNull()
            if (currentHash != assetHash) {
                val output = stored.startWrite()
                try {
                    context.assets.open("agridoctor.sqlite").use { it.copyTo(output) }
                    stored.finishWrite(output)
                } catch (error: Exception) { stored.failWrite(output); throw error }
            }
            version = "1.3:$assetHash"
        }
        return SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val aliases = mutableMapOf<String, MutableList<String>>()
            val rules = mutableMapOf<String, MutableMap<String, String>>()
            val sources = mutableMapOf<String, MutableList<Source>>()
            db.rawQuery("SELECT case_id, normalized FROM aliases", null).use { rows ->
                while (rows.moveToNext()) aliases.getOrPut(rows.getString(0), ::mutableListOf).add(rows.getString(1))
            }
            db.rawQuery("SELECT case_id, dimension, value FROM case_scope", null).use { rows ->
                while (rows.moveToNext()) rules.getOrPut(rows.getString(0), ::mutableMapOf)[rows.getString(1)] = rows.getString(2)
            }
            db.rawQuery("""SELECT cc.id, source_title, url, locator, coalesce(sr.region, ''), publisher FROM case_catalog cc
                LEFT JOIN source_region sr ON sr.source_id=cc.source_id ORDER BY cc.source_id, locator""", null).use { rows ->
                while (rows.moveToNext()) sources.getOrPut(rows.getString(0), ::mutableListOf).add(
                    Source(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5)),
                )
            }
            db.rawQuery("""SELECT id,crop_id,title,category,organs,signs,follow_up,observation,lookalikes,review_status,approval_scope
                FROM cases WHERE review_status IN ('approved','pending') ORDER BY id""", null).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val id = rows.getString(0)
                        val evidence = sources[id].orEmpty()
                        if (evidence.isEmpty()) continue
                        add(PlantCase(id, Crop.entries.first { it.id == rows.getString(1) }, rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                            rows.getString(9) == "pending", aliases[id].orEmpty(), rules[id].orEmpty(), evidence, rows.getString(10) == "project"))
                    }
                }
            }
        }
    }
}

private val marks = Regex("\\p{Mn}+")
private val symbols = Regex("[^a-z0-9]+")
internal fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
    .replace(marks, "").replace(symbols, " ").trim()
private fun contains(text: String, phrase: String) = phrase.isNotEmpty() && " $phrase " in " $text "

internal object Symptoms {
    private val separators = Regex("[,.;!?\\n]+")
    private val boundary = Regex("\\b(?:nhung|tuy nhien|ma lai|ma co|ma la|va co|va thay|va)\\b")
    private val negation = Regex("\\b(?:khong(?! (?:chi|nhung)\\b)|chua|chang|cha)\\b")
    private val postNegation = Regex("^ *(?:thi )?(?:khong|chua|chang|cha)(?: (?:co|thay|xuat hien))?(?: (?:dau|ca|gi|het|nhe|nha|a))* *$")
    private val uncertainty = Regex("\\b(?:khong ro|khong biet|khong chac|chua chac|co phai|lieu co|hinh nhu)\\b")
    private const val general = "Bác xem dấu hiệu nằm ở lá, thân, gốc hay quả/bông và xuất hiện từ khi nào."
    private val types = mapOf("robusta" to listOf("ca phe voi", "voi"), "arabica" to listOf("ca phe che", "che"), "liberica" to listOf("ca phe mit", "mit"))
    private val coffeeStages = mapOf("nursery" to listOf("cay giong", "vuon uom", "bau uom"), "fruiting" to listOf("mang trai", "mang qua", "nuoi trai", "nuoi qua", "dang co trai", "dang co qua", "dang dau trai", "dang dau qua"), "flowering" to listOf("ra hoa", "no hoa"), "production" to listOf("kinh doanh"))
    private val riceStages = mapOf("seedling" to listOf("ma", "cay ma", "moi sa", "moi cay"), "tillering" to listOf("de nhanh"), "booting" to listOf("lam dong"), "heading" to listOf("tro bong", "tro"), "ripening" to listOf("chin", "sap gat"))
    private data class Match(val start: Int, val end: Int, val id: String, val unresolved: Boolean)

    private fun match(text: String, aliases: List<Pair<String, String>>, crop: Crop? = null): Pair<Set<String>, Boolean> {
        val found = mutableSetOf<String>()
        val unconfirmed = mutableSetOf<String>()
        var ambiguous = false
        for (rawClause in separators.split(text).flatMap { boundary.split(normalize(it)) }) {
            val clause = speechNegation(crop?.let { regionalClause(rawClause, it) } ?: rawClause)
            val padded = " $clause "
            val tentative = uncertainty.find(clause)?.range?.first
            val mentions = mutableListOf<Match>()
            for ((alias, id) in aliases) {
                if (alias.isEmpty()) continue
                var offset = 0
                while (true) {
                    val start = padded.indexOf(" $alias ", offset)
                    if (start < 0) break
                    val unresolved = (tentative != null && tentative <= start) || negation.containsMatchIn(clause.substring(0, start)) ||
                        postNegation.matches(clause.substring(start + alias.length))
                    mentions.add(Match(start, start + alias.length, id, unresolved))
                    offset = start + 1
                }
            }
            mentions.filter { item -> mentions.none { other ->
                other.start <= item.start && other.end >= item.end && (other.start != item.start || other.end != item.end)
            } }.forEach {
                if (it.unresolved) { unconfirmed.add(it.id); ambiguous = true } else found.add(it.id)
            }
        }
        if (found.any { it in unconfirmed }) return emptySet<String>() to true
        return found to ambiguous
    }

    private fun context(options: Map<String, List<String>>, supplied: String, text: String): String? {
        val value = normalize(supplied)
        if (value.isNotBlank()) return options.entries.firstOrNull { value == it.key || value in it.value }?.key ?: value
        return match(text, options.flatMap { (key, names) -> names.filter { ' ' in it }.map { it to key } }).first.singleOrNull()
    }

    fun lookup(input: LookupInput, catalog: List<PlantCase>, demo: Boolean): LookupResult {
        val eligible = catalog.filter { it.crop == input.crop && (demo || !it.pending) }
        if (eligible.isEmpty()) return LookupResult("Kho đã duyệt chưa có mục phù hợp", "Bác mô tả thêm dấu hiệu và liên hệ cán bộ kỹ thuật nếu cây bị ảnh hưởng nặng.", emptyList())
        val (ids, ambiguous) = match(input.symptoms, eligible.flatMap { item -> item.aliases.map { it to item.id } }, input.crop)
        val values = mapOf("crop_type" to context(if (input.crop == Crop.COFFEE) types else emptyMap(), input.kind, input.symptoms),
            "growth_stage" to context(when (input.crop) { Crop.COFFEE -> coffeeStages; Crop.RICE -> riceStages; else -> emptyMap() }, input.stage, input.symptoms))
        val part = normalize(input.part).let { mapOf("trai" to "qua", "cuong trai" to "cuong qua", "co gie" to "co bong")[it] ?: it }
        val missing = mutableSetOf<String>()
        val candidates = eligible.filter { item ->
            if (item.id !in ids || item.rules.any { (key, expected) -> values[key]?.let { it != expected } == true }) return@filter false
            if (part.isNotEmpty() && item.organs.split(';').map(::normalize).none { contains(it, part) || contains(part, it) }) return@filter false
            val unknown = item.rules.keys.filter { values[it] == null }
            missing.addAll(unknown)
            unknown.isEmpty()
        }
        val region = normalize(input.region)
        val prioritized = candidates.map { item -> item.copy(sources = item.sources.sortedWith(
            compareBy<Source> { !contains(region, normalize(it.region)) }.thenBy { it.region.isBlank() },
        )) }
        return when {
            prioritized.size > 1 -> {
                val found = prioritized.map { it.id }.toSet()
                val question = when {
                    found.containsAll(listOf("R02", "R08")) -> "Bác xem cổ bông có vết thâm và thân có lỗ đục hay không. Chụp cận cả hai vị trí và toàn bụi."
                    found.containsAll(listOf("D01", "D19")) -> "Bác xem đốm bắt đầu ở mép lá hay rải rác trên hai mặt lá."
                    else -> general
                }
                LookupResult("Cần phân biệt thêm", question, prioritized)
            }
            prioritized.size == 1 -> LookupResult("Có dấu hiệu tương tự", prioritized.first().question, prioritized)
            "crop_type" in missing -> LookupResult("Cần biết loại cà phê", "Bác trồng cà phê vối, cà phê chè hay cà phê mít?", emptyList(), "crop_type")
            "growth_stage" in missing -> LookupResult("Cần biết giai đoạn sinh trưởng", "Cây đang ở vườn ươm, ra hoa hay mang trái?", emptyList(), "growth_stage")
            ids.isNotEmpty() -> LookupResult("Dấu hiệu chưa khớp bối cảnh", general, emptyList())
            ambiguous -> LookupResult("Cần xác nhận dấu hiệu", "Bác mô tả dấu hiệu đã nhìn thấy trực tiếp. $general", emptyList())
            else -> LookupResult("Chưa tìm thấy tình huống phù hợp", general, emptyList())
        }
    }
}

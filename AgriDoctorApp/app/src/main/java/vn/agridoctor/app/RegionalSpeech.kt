package vn.agridoctor.app

internal const val VOICE_ALTERNATIVES = 5

private val localNegation = Regex("(?<!nam )\\b(?:hong|hem|hok|khum) (?=(?:co|bi|thay|ro|biet|chac|phai)\\b)")
private val uncertainNegation = Regex("\\bkhong (?:chac|phai) la\\b")
private val riceWhitePanicle = Regex("\\bbong (?:lua )?(?:no )?(?:bi )?(?:lep )?trang(?: het| ca bong)?\\b")
private val riceDarkNeck = Regex("\\bco (?:bong|gie) (?:lua )?(?:no )?(?:bi )?(?:tham den|den tham|tham|sam)\\b")
private val riceBoredStem = Regex("\\b(?:than|danh) (?:lua )?(?:bi duc lo|co lo duc)\\b")
private val durianSap = Regex("\\b(?:xi|ri|chay) (?:ra )?(?:mu|nhua)\\b")
private val coffeeRust = Regex("\\b(?:ri|gi) set\\b")
private val coffeeOrangePowder = Regex("\\b(?:bot|phan) (?:mau )?(?:vang )?cam (?:o |duoi |bam )?(?:mat )?duoi la\\b|\\bmat duoi la co (?:lop )?(?:bot|phan) (?:mau )?(?:vang )?cam\\b")

/** For matching only. The transcription shown to the grower remains unchanged. */
internal fun regionalClause(clause: String, crop: Crop): String {
    return when (crop) {
        Crop.RICE -> clause.replace(riceWhitePanicle, "bong bac")
            .replace(riceDarkNeck, "tham co bong")
            .replace(riceBoredStem, "sau duc than")
        Crop.DURIAN -> clause.replace(durianSap, "xi mu")
        Crop.COFFEE -> clause.replace(coffeeRust, "ri sat")
            .replace(coffeeOrangePowder, "phan cam mat duoi")
    }
}

internal fun speechNegation(text: String): String = text.replace(localNegation, "khong ")
    .replace(uncertainNegation, "khong chac ")

internal fun speechOptions(candidates: List<String>): List<String> = candidates.asSequence()
    .map { it.trim().take(500) }
    .filter { it.isNotBlank() }
    .distinct()
    .take(VOICE_ALTERNATIVES)
    .toList()

internal fun speechVocabulary(crop: Crop): ArrayList<String> {
    val phrases = when (crop) {
        Crop.RICE -> listOf("bông trắng", "cổ bông", "cổ gié", "đốm vằn", "rầy nâu")
        Crop.DURIAN -> listOf("xì mủ", "rỉ mủ", "thối trái", "chảy nhựa", "cuống trái")
        Crop.COFFEE -> listOf("rỉ sắt", "bột vàng cam", "đốm mắt cua", "lở cổ rễ")
    }
    return ArrayList(listOf(crop.label) + phrases)
}

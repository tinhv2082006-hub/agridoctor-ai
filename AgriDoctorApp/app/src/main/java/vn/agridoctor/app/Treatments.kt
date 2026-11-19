package vn.agridoctor.app

internal data class Treatment(
    val product: String,
    val activeIngredients: String,
    val dose: String,
    val application: String,
    val preHarvestDays: Int,
    val registrationLocator: String,
    val directions: Source,
    val intervalSource: Source? = null,
)

internal object Treatments {
    const val checkedOn = "14/11/2026"
    const val register = "https://www.ppd.gov.vn/FileUpload/Documents/Thuoc%20BVTV/25.12.30_PL%201%20-%20Danh%20m%E1%BB%A5c%20%C4%91%C6%B0%E1%BB%A3c%20ph%C3%A9p%20s%E1%BB%AD%20d%E1%BB%A5ng.pdf"
    const val amendment = "https://vanban.chinhphu.vn/?docid=218752&pageid=27160&typegroupid=6"
    const val safeUse = "https://vanban.org/van-ban/luat-bao-ve-va-kiem-dich-thuc-vat-2013-41-2013-qh13"

    private fun amistarTop(dose: String, application: String, locator: String, detailed: Boolean = false) = Treatment(
        product = "Amistar Top 325SC",
        activeIngredients = "Azoxystrobin 200 g/L + Difenoconazole 125 g/L",
        dose = dose,
        application = application,
        preHarvestDays = 10,
        registrationLocator = "I.2 Thuốc trừ bệnh, STT 27 · Amistar Top 325SC",
        directions = Source(
            "Syngenta Việt Nam · Amistar Top 325SC",
            if (detailed) "https://www.syngenta.com.vn/product/crop-protection/thuoc-tru-benh/amistar-top-325sc"
            else "https://www.syngenta.com.vn/amistar-topr-325sc",
            locator,
            "Việt Nam",
        ),
    )

    private val anvil = Treatment(
        product = "Anvil 5SC",
        activeIngredients = "Hexaconazole 50 g/L",
        dose = "1–2 lít thuốc/ha",
        application = "Phun với 600–800 lít nước/ha. Trang sản phẩm ghi thời điểm phun khi khoảng 15% lá bị hại.",
        preHarvestDays = 14,
        registrationLocator = "I.2 Thuốc trừ bệnh, STT 480 · Anvil 5SC",
        directions = Source("Syngenta Việt Nam · Anvil 5SC", "https://www.syngenta.com.vn/anvilr-5sc",
            "Hướng dẫn sử dụng → Cà phê → Rỉ sắt, nấm hồng", "Việt Nam"),
    )

    private val riceBlast = amistarTop(
        "0,5 lít thuốc/ha",
        "Hướng dẫn của hãng nêu phun khi mới thấy vết chấm kim, trong thời kỳ từ đẻ nhánh đến trỗ.",
        "Đối tượng và hướng dẫn sử dụng → Lúa → Đạo ôn (lá, cổ bông)",
        detailed = true,
    )

    private val byCase = mapOf(
        "C01" to listOf(anvil),
        "C02" to listOf(anvil),
        "C09" to listOf(amistarTop("Nồng độ pha 0,1–0,2%", "Phun khi bệnh mới xuất hiện, phủ đều cây.",
            "Hướng dẫn sử dụng → Cà phê → Thán thư")),
        "D01" to listOf(Treatment(
            product = "Amistar 250SC",
            activeIngredients = "Azoxystrobin 250 g/L",
            dose = "Nồng độ pha 0,1%",
            application = "Lượng nước 1.000–1.200 lít/ha. Hãng hướng dẫn phun hai lần, cách nhau 7 ngày, bắt đầu khi bệnh mới xuất hiện ở khoảng 5%.",
            preHarvestDays = 5,
            registrationLocator = "I.2 Thuốc trừ bệnh, STT 9 · Amistar 250SC",
            directions = Source("Syngenta Việt Nam · Amistar 250SC", "https://www.syngenta.com.vn/amistarr-250sc",
                "Hướng dẫn sử dụng → Sầu riêng → Thán thư", "Việt Nam"),
            intervalSource = Source("Dữ Liệu Pháp Luật · Bảng tra cứu Amistar 250SC",
                "https://dulieuphapluat.vn/cong-cu/thuoc-bvtv/amistar-250-sc.html",
                "Phạm vi sử dụng → Sầu riêng → Thán thư · Nguồn tra cứu thứ cấp, đối chiếu nhãn bao bì", "Việt Nam"),
        )),
        "D02" to listOf(Treatment(
            product = "Alimet 80WP",
            activeIngredients = "Fosetyl-aluminium 80% khối lượng",
            dose = "40–60 g thuốc/16 lít nước",
            application = "Phun khi bệnh mới xuất hiện. Hướng dẫn ghi 400–500 lít nước/ha.",
            preHarvestDays = 7,
            registrationLocator = "I.2 Thuốc trừ bệnh, STT 465 · Alimet 80WP (dạng 80WP)",
            directions = Source("Thái Sơn Agri · Hướng dẫn Alimet 80WP của Kiên Nam",
                "https://www.thaisonagri.com/thuoc-tru-benh-alimet-80wp",
                "Hướng dẫn sử dụng → Sầu riêng, cam → Xì mủ · SĐK 575/CNĐKT-BVTV", "Việt Nam"),
        )),
        "R01" to listOf(riceBlast),
        "R02" to listOf(riceBlast),
        "R03" to listOf(amistarTop("0,25–0,30 lít thuốc/ha", "Trang sản phẩm hướng dẫn phun lúc lúa làm đòng.",
            "Hướng dẫn sử dụng → Lúa → Khô vằn")),
        "R06" to listOf(Treatment(
            product = "Chess 50WG",
            activeIngredients = "Pymetrozine 500 g/kg",
            dose = "0,3 kg thuốc/ha",
            application = "Hãng hướng dẫn phun khi rầy mới xuất hiện và mật số đã đến ngưỡng gây hại.",
            preHarvestDays = 7,
            registrationLocator = "I.1 Thuốc trừ sâu, STT 840 · Chess 50WG",
            directions = Source("Syngenta Việt Nam · Chess 50WG",
                "https://www.syngenta.com.vn/product/crop-protection/thuoc-tru-sau/chess-50wg/1000",
                "Đối tượng và hướng dẫn sử dụng → Lúa → Rầy nâu; Tư vấn ứng dụng", "Việt Nam"),
        )),
        "R24" to listOf(amistarTop("0,35 lít thuốc/ha", "Hãng hướng dẫn phun ở hai thời điểm, trước và sau khi lúa trỗ.",
            "Hướng dẫn sử dụng → Lúa → Lem lép hạt")),
    )

    fun forCase(item: PlantCase): List<Treatment> =
        if (item.category in setOf("disease", "pest") && item.id.startsWith(item.crop.id.first(), ignoreCase = true)) {
            byCase[item.id].orEmpty()
        } else {
            emptyList()
        }
}

"""Add the sourced v1.3 records to a copy of the existing schema-2 catalog."""

import json
from pathlib import Path
import sqlite3

from lookup import normalize


SOURCES = [
    ("CF_COL_VN", "Tài liệu hướng dẫn sản xuất cà phê chè bền vững tại Việt Nam", "Trung tâm Khuyến nông Quốc gia (nơi công bố)", "https://khuyennongvn.gov.vn/portals/0/tailieu/2021/09/hangweb/tot_tai_lieu_huong_dan_san_xuat_ca_phe_ben_vung_danh_cho_tap_huan_vien_final_da_nen.pdf"),
    ("CF_COL_WASI", "Quy trình tạm thời sản xuất giống cà phê", "Viện Khoa học Kỹ thuật Nông Lâm nghiệp Tây Nguyên (WASI, nơi công bố)", "https://wasi.org.vn/wp-content/uploads/2021/09/QUYET-DINH-BAN-HANH-CAC-QUY-TRINH-TAM-THOI-SAN-XUAT-GIONG-CA-PHE-20.4.2021.pdf"),
    ("CF_INCAPER", "Conilon Coffee Disease Management", "Incaper, Espírito Santo, Brazil", "https://biblioteca.incaper.es.gov.br/digital/bitstream/123456789/3529/1/chapter-18-diseases-management.pdf"),
    ("DUR_MY", "Pakej Teknologi Durian", "Jabatan Pertanian Malaysia (bản PDF lưu trên azmi.my)", "https://azmi.my/wp-content/uploads/2024/09/Buku-Pakej-Teknologi-Durian-17-September-2024.pdf"),
    ("DUR_ID", "Pedoman Pengembangan Durian di Lahan Rawa", "Tổng cục Làm vườn, Bộ Nông nghiệp Indonesia", "https://hortikultura.pertanian.go.id/wp-content/uploads/2024/10/Buku-Pedoman-Pengembangan-Durian-Rawa-Rev-3-5-21_watermark.pdf"),
    ("DUR_VEXANS", "First report of Phytopythium vexans causing root rot disease on durian in Vietnam", "Thao và cộng sự, New Disease Reports (2020)", "https://www.ndrs.org.uk/pdfs/041/NDR_041002.pdf"),
    ("RICE_STEMROT", "Stem Rot of Rice", "UC Statewide IPM Program, California", "https://ipm.ucanr.edu/agriculture/rice/stem-rot-of-rice/"),
    ("RICE_AGGREGATE", "Aggregate Sheath Spot of Rice", "UC Statewide IPM Program, California", "https://ipm.ucanr.edu/agriculture/rice/aggregate-sheath-spot-of-rice/"),
    ("RICE_KERNEL", "Kernel Smut", "UC Statewide IPM Program, California", "https://ipm.ucanr.edu/agriculture/rice/kernel-smut/"),
    ("RICE_SEEDROT", "Seed Rot and Seedling Diseases", "UC Statewide IPM Program, California", "https://ipm.ucanr.edu/agriculture/rice/seed-rot-and-seedling-diseases/"),
]

# ID, crop, title, affected organs, documented signs, question, observation,
# lookalikes, extra lookup phrases, and page/section references.
CASES = [
    ("C19", "coffee", "Lở cổ rễ cây con", "cổ rễ;gốc;thân;rễ",
     "Vùng cổ rễ sát mặt giá thể có thể thối sẫm và teo lại. Cây con có thể chậm lớn, vàng lá và suy dần.",
     "Phần thân sát mặt bầu có bị thắt lại đúng ở chỗ vết thâm không?",
     "Chụp toàn cây và cổ rễ ngang mặt bầu, kèm ảnh cây khỏe ở bên cạnh.",
     "Vàng lá thối rễ, úng bầu, tổn thương ở gốc",
     ("lở cổ rễ", "cổ rễ thối đen", "cổ rễ bị teo", "thắt cổ rễ"),
     (("CF_COL_VN", "Hợp phần 7, mục 6.2: Bệnh lở cổ rễ"), ("CF_COL_WASI", "Mục Bệnh lở cổ rễ: mô tả trên cây con và giai đoạn kiến thiết cơ bản"))),
    ("C20", "coffee", "Đốm lá nghi Corynespora", "lá;gân lá;cuống lá;quả;cành",
     "Trên cà phê vối Conilon ở Brazil, tài liệu ghi nhận đốm nâu có quầng vàng trên lá và có thể kèm rụng lá. Đốm sẫm trên quả có thể lan nhập lại.",
     "Ngoài đốm có quầng vàng trên lá, cuống lá, gân lá hoặc quả có đốm tương tự không?",
     "Chụp hai mặt lá và cả chùm quả nếu có đốm. Ghi loại cà phê đang trồng.",
     "Đốm mắt cua, gỉ sắt, các dạng đốm lá khác",
     ("đốm lá corynespora", "corynespora", "đốm lá mục tiêu"),
     (("CF_INCAPER", "Chương 18, mục 2.5.1: Target Leaf Spot, Hình 12"),)),
    ("C21", "coffee", "Cháy lá dạng mạng tơ", "lá;cành",
     "Tài liệu Brazil mô tả sợi nấm trắng như mạng nhện phủ lá và cành. Lá có thể khô rồi vẫn treo trên cành nhờ các sợi này.",
     "Có lá đã khô nhưng còn treo trên cành bởi những sợi trắng nối liền không?",
     "Chụp chỗ sợi trắng nối lá với cành và ảnh toàn phần tán bị hại.",
     "Mạng nhện thường, nấm hồng trên vỏ cành",
     ("lá khô treo bằng sợi trắng", "cháy lá mạng nhện", "thread blight"),
     (("CF_INCAPER", "Chương 18, mục 2.5.2: Coffee Thread Blight"),)),
    ("C22", "coffee", "Đốm lá nghi Phoma", "lá;cành;quả",
     "Tài liệu Brazil ghi nhận mảng sẫm thường khởi đầu ở mép lá, đôi khi có các dải màu. Khi ẩm, có thể thấy chấm đen nhỏ trên vết bệnh.",
     "Vết bắt đầu ở mép lá và có chấm đen li ti trên nền vết sẫm không?",
     "Chụp rìa lá, bề mặt vết bệnh và cành có vết lõm nếu có.",
     "Đốm lá vi khuẩn, thán thư, cháy mép lá do điều kiện canh tác",
     ("đốm lá phoma", "phoma cà phê", "mép lá sẫm có chấm đen"),
     (("CF_INCAPER", "Chương 18, mục 2.5.4: Phoma Leaf Spot, phần Symptoms và Hình 13"),)),
    ("C23", "coffee", "Đốm lá cây giống nghi Myrothecium", "lá",
     "Trên cây giống ở Brazil, đốm nâu tròn có rìa vàng ở hai mặt lá. Vết già có thể có tâm xám và cụm chấm đen đi kèm sợi trắng.",
     "Trên cùng vết đốm, có thấy cụm chấm đen kèm sợi trắng ở hai mặt lá không?",
     "Chụp cả hai mặt của lá cây giống và ghi tình trạng ẩm trong vườn ươm.",
     "Đốm mắt cua, đốm lá nghi Phoma",
     ("đốm lá myrothecium", "myrothecium", "đốm lá cây giống có cụm chấm đen"),
     (("CF_INCAPER", "Chương 18, mục 2.5.6: Myrothecium Leaf Spot"),)),
    ("C24", "coffee", "Thối đen rễ nghi Rosellinia", "rễ;cổ rễ;tán",
     "Tài liệu Brazil ghi nhận cây vàng, héo và khô cành. Rễ gần cổ rễ có thể sẫm màu, với sợi trắng rồi sẫm dần ở mô bị hại.",
     "Ở phần rễ đã lộ, có mảng sẫm kèm sợi bám hay chỉ thấy tán vàng?",
     "Chụp toàn cây và rễ đã lộ. Nhờ cán bộ kỹ thuật kiểm tra mẫu để phân biệt với các nguyên nhân thối rễ khác.",
     "Vàng lá thối rễ, tuyến trùng, úng nước",
     ("thối đen rễ", "thối rễ rosellinia", "rosellinia cà phê"),
     (("CF_INCAPER", "Chương 18, mục 3.1: Black Root Rot, Hình 14"),)),
    ("D20", "durian", "Nấm hồng trên cành", "cành;thân",
     "Tài liệu Malaysia và Indonesia mô tả lớp sợi trắng ban đầu trên vỏ cành, sau có thể thành mảng hồng nhạt. Vỏ có thể nứt và cành khô.",
     "Ở ranh giới đoạn cành xanh và đoạn khô, vỏ có lớp hồng nhạt bám rõ không?",
     "Chụp cả cành, mặt trên và mặt dưới chỗ có lớp hồng; ghi có lỗ đục ở gần không.",
     "Xì mủ thân, cành bị mọt đục, tổn thương cơ học",
     ("nấm hồng", "lớp hồng trên vỏ cành", "mảng hồng trên cành sầu riêng"),
     (("DUR_MY", "Mục 6.2, bệnh số 10: Cendawan Angin, phần dấu hiệu"), ("DUR_ID", "Mục Penyakit, số 2: Mati cabang atau Ranting"))),
    ("D21", "durian", "Thối quả rụng nghi Sclerotium", "quả",
     "Tài liệu Malaysia mô tả quả rụng chạm đất có thể xuất hiện vết nâu ướt trên vỏ, phủ tơ nấm dày tỏa như hình quạt.",
     "Quả đã chạm đất và lớp tơ trắng có tỏa từ vết nâu ướt không?",
     "Chụp toàn quả, vị trí chạm đất và mép vùng bị thối.",
     "Thối quả do Phytophthora, quả dập sau khi rụng",
     ("thối quả sclerotium", "quả rụng có tơ trắng hình quạt", "sclerotium sầu riêng"),
     (("DUR_MY", "Mục 6.2, bệnh số 3: Reput Buah Sclerotium, phần dấu hiệu"),)),
    ("D22", "durian", "Nấm rễ trắng nghi Rigidoporus", "rễ;gốc;tán",
     "Tài liệu Malaysia ghi nhận sợi nấm trắng phủ rễ, đôi lúc có thể thấy thể nấm ở gốc. Tán có thể vàng rồi khô và rụng lá.",
     "Trên rễ đã lộ có dải sợi trắng bám dọc rễ hoặc thể nấm ở sát gốc không?",
     "Chụp vùng gốc và rễ đã lộ, kèm ảnh toàn tán. Nhờ cán bộ kỹ thuật kiểm tra rễ.",
     "Thối rễ, úng nước, rệp sáp ở rễ",
     ("nấm rễ trắng", "sợi nấm trắng phủ rễ", "rigidoporus sầu riêng"),
     (("DUR_MY", "Mục 6.2, bệnh số 11: Akar Putih, phần dấu hiệu"),)),
    ("D23", "durian", "Thối rễ nghi Phytopythium", "rễ;tán;lá",
     "Báo cáo tại Việt Nam ghi nhận thối rễ và rụng lá. Tài liệu Malaysia minh họa cây bị vàng lá và héo; những dấu hiệu trên tán có thể trùng với các dạng thối rễ khác.",
     "Cây vàng, héo hoặc rụng lá có đi kèm rễ đã lộ bị thối không?",
     "Chụp tán, gốc và rễ đã lộ. Ghi tình trạng đọng nước, rồi nhờ cán bộ kỹ thuật kiểm tra mẫu rễ để phân biệt tác nhân.",
     "Thối rễ do Phytophthora, úng nước, nấm rễ trắng",
     ("thối rễ phytopythium", "phytopythium vexans", "thối rễ pythium"),
     (("DUR_VEXANS", "Báo cáo trang 2, phần mô tả ca bệnh và Hình 1"), ("DUR_MY", "Mục 6.2, bệnh số 12: Reput Akar Pythium, phần dấu hiệu"))),
    ("R26", "rice", "Cháy lá có vân sọc (leaf scald)", "lá",
     "Vết có thể bắt đầu ở chóp hoặc mép lá trưởng thành, với các dải nâu đậm và nhạt xen nhau. Khi các vết lan nhập, phần lá lớn có thể khô cháy.",
     "Vết bắt đầu ở đầu hoặc mép lá và có các dải màu nâu xen nhau không?",
     "Chụp trọn lá và vùng chuyển từ xanh sang cháy; chụp thêm lá lân cận.",
     "Bạc lá vi khuẩn, đạo ôn lá, cháy chóp do điều kiện canh tác",
     ("leaf scald", "cháy lá có vân sọc", "vết nâu xen dải ở chóp lá"),
     (("RICE_DIS", "Mục 3.1.4.1: Leaf Scald – Symptoms, ảnh 17"),)),
    ("R27", "rice", "Thối thân lúa", "bẹ lá;thân;gốc",
     "Trong lúa gieo trên ruộng có nước, vết đen nhỏ có thể xuất hiện ở bẹ lá ngang mực nước. Về sau bẹ có thể chết và vết lan vào thân.",
     "Vết đen bắt đầu ở bẹ thấp sát mực nước hay ở bẹ trên cùng quanh đòng?",
     "Chụp bẹ sát mực nước và toàn dảnh lúa để thấy vị trí vết.",
     "Khô vằn, đốm vòng bẹ lá, thối bẹ lá đòng",
     ("thối thân lúa", "vết đen bẹ sát mực nước", "stem rot"),
     (("RICE_STEMROT", "Mục Symptoms, đoạn mô tả vết sát mực nước"), ("RICE_DIS", "Mục 3.1.6: Stem Rot"))),
    ("R28", "rice", "Đốm vòng trên bẹ lá lúa", "bẹ lá;thân",
     "Tài liệu UC IPM mô tả vết tròn hoặc bầu dục trên bẹ thấp, tâm xám xanh hoặc vàng rơm, viền nâu rõ. Một số vết có nhiều vòng đồng tâm.",
     "Vết nằm trên bẹ thấp và có viền nâu thành vòng quanh tâm nhạt không?",
     "Chụp sát viền vết, đồng thời chụp toàn dảnh để ghi vết đã lan lên cao đến đâu.",
     "Khô vằn, thối thân lúa",
     ("đốm vòng bẹ lá", "đốm vòng trên bẹ lá", "aggregate sheath spot"),
     (("RICE_AGGREGATE", "Mục Symptoms, đoạn mô tả các vòng đồng tâm trên bẹ"),)),
    ("R29", "rice", "Than đen hạt lúa", "bông;hạt",
     "Gần lúc chín, một vài hạt trên bông có thể phồng nhẹ hoặc nứt vỏ, lộ lớp bào tử đen thay một phần hoặc toàn bộ ruột hạt.",
     "Màu đen lộ từ trong hạt nứt hay chỉ là vết lem ngoài vỏ?",
     "Chụp cận hạt có vết nứt và toàn bông; ghi lúa đã gần thu hoạch chưa.",
     "Vàng giả hạt, lem lép hạt",
     ("than đen hạt", "hạt lúa nứt có bột đen", "kernel smut"),
     (("RICE_KERNEL", "Mục Symptoms, mô tả bào tử trong từng hạt lúc gần chín"),)),
    ("R30", "rice", "Thối hạt giống và mạ do nấm nước", "hạt;mạ;gốc;rễ",
     "Tài liệu UC IPM mô tả sợi trắng quanh vết nứt trên hạt hoặc gốc mạ mới mọc. Mạ sống sót có thể chậm lớn và vàng lá.",
     "Mạ mới mọc có sợi trắng quanh hạt hoặc gốc và có mọc thưa thành vùng không?",
     "Chụp hạt và gốc mạ, thêm ảnh vùng mạ mọc thưa. Ghi số ngày sau gieo.",
     "Lúa von, ngộ độc hữu cơ, hạt không nảy mầm vì nguyên nhân khác",
     ("thối hạt giống lúa", "nấm nước trên mạ", "sợi trắng quanh hạt lúa mới gieo", "seed rot"),
     (("RICE_SEEDROT", "Mục Symptoms: Seed Rot and Seedling Diseases"),)),
    ("R31", "rice", "Nghi bệnh Tungro", "lá;toàn cây",
     "Tài liệu IRRI mô tả cây thấp và đẻ ít nhánh; lá có thể vàng đến vàng cam, đôi khi có đốm màu gỉ.",
     "Bụi có lá vàng cam đồng thời thấp và ít nhánh rõ hơn bụi khỏe bên cạnh không?",
     "Chụp cạnh nhau bụi bệnh và bụi khỏe, thêm ảnh phần lá vàng. Nhờ cán bộ kỹ thuật đánh giá nếu chỉ có dấu hiệu vàng và lùn.",
     "Vàng lùn, thiếu dinh dưỡng, ngộ độc hữu cơ",
     ("tungro", "lùn lá vàng cam", "lúa vàng cam ít nhánh"),
     (("RICE_DIS", "Mục 3.3.1: Tungro, ảnh 30"),)),
    ("R32", "rice", "Nghi tuyến trùng thân lúa (Ufra)", "lá;bông;bẹ lá;thân",
     "Tài liệu IRRI ghi nhận mô lá nhạt màu lúc đầu; cây có thể thấp, lá xoắn và bông trổ kẹt trong bẹ. Bông trổ ra có thể méo xoắn và hạt lép.",
     "Ngoài lá bị xoắn, bông có trổ kẹt trong bẹ hoặc méo xoắn rõ không?",
     "Chụp bẹ ôm bông, bông biến dạng và cả bụi; nhờ cán bộ kỹ thuật kiểm tra mẫu khi dấu hiệu trùng lùn xoắn lá.",
     "Lùn xoắn lá, thối bẹ lá đòng, nhện gié",
     ("ufra", "tuyến trùng thân lúa", "bông méo xoắn trổ kẹt"),
     (("RICE_DIS", "Mục 3.4.1: Ufra or Stem Nematode, ảnh 33"),)),
    ("R33", "rice", "Trắng chóp lá nghi tuyến trùng", "lá;lá đòng;bông",
     "Theo IRRI, đầu lá có thể mất màu rồi khô rách. Lá đòng đôi khi xoắn và bông trổ kém hoặc có nhiều hạt lép.",
     "Chóp lá trắng có khô rách và đi kèm lá đòng bị xoắn không?",
     "Chụp cận chóp lá, toàn lá đòng và bông nếu đã trổ; ghi giai đoạn sinh trưởng.",
     "Tổn thương do mặn, cháy lá, lùn xoắn lá",
     ("trắng chóp lá do tuyến trùng", "chóp lá trắng khô rách", "white tip lúa"),
     (("RICE_DIS", "Mục 3.4.2: White Tip, ảnh 34"),)),
]


def add_records(db):
    db.execute("PRAGMA foreign_keys = ON")
    with db:
        db.executemany("INSERT INTO sources VALUES (?,?,?,?)", SOURCES)
        db.executemany("INSERT INTO source_region VALUES (?,?)", [
            ("CF_COL_VN", "Việt Nam"), ("CF_COL_WASI", "Việt Nam"), ("CF_INCAPER", "Brazil"),
            ("DUR_MY", "Malaysia"), ("DUR_ID", "Indonesia"), ("DUR_VEXANS", "Việt Nam"),
            *((name, "California, Hoa Kỳ") for name in ("RICE_STEMROT", "RICE_AGGREGATE", "RICE_KERNEL", "RICE_SEEDROT")),
        ])
        for case_id, crop, title, organs, signs, question, observation, lookalikes, aliases, refs in CASES:
            db.execute("""INSERT INTO cases (id,crop_id,category,title,organs,signs,follow_up,observation,lookalikes)
                VALUES (?,?,'disease',?,?,?,?,?,?)""",
                (case_id, crop, title, organs, signs, question, observation, lookalikes))
            phrases = {normalize(text): text for text in (title, *aliases)}
            db.executemany("INSERT INTO aliases VALUES (?,?,?)",
                ((case_id, phrase, normalized) for normalized, phrase in phrases.items()))
            db.executemany("INSERT INTO case_sources VALUES (?,?,?)",
                ((case_id, source, locator) for source, locator in refs))


def export(db, destination, original_metadata):
    tables = {
        "crops": "id", "cases": "id", "aliases": "case_id, normalized",
        "sources": "id", "case_sources": "case_id, source_id, locator",
        "case_scope": "case_id, dimension", "source_region": "source_id",
    }
    db.row_factory = sqlite3.Row
    catalog = {"version": "1.3", "sources_accessed_on": original_metadata["sources_accessed_on"],
               "v1_3_sources_accessed_on": "2026-11-14"}
    for table, order in tables.items():
        catalog[table] = [dict(row) for row in db.execute(f"SELECT * FROM {table} ORDER BY {order}")]
    (destination / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (destination / "agridoctor.sql").write_text("\n".join(db.iterdump()) + "\n", encoding="utf-8")


def main():
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path, help="Directory containing the v1.2 database and catalog")
    parser.add_argument("destination", type=Path, help="New directory for the v1.3 exports")
    args = parser.parse_args()
    metadata = json.loads((args.base / "catalog.json").read_text(encoding="utf-8"))
    if metadata["version"] != "1.2":
        parser.error("The base catalog must be v1.2.")
    args.destination.mkdir(parents=True, exist_ok=False)
    source = sqlite3.connect((args.base / "agridoctor.sqlite").resolve().as_uri() + "?mode=ro", uri=True)
    db = sqlite3.connect(args.destination / "agridoctor.sqlite")
    try:
        source.backup(db)
        add_records(db)
        if db.execute("PRAGMA foreign_key_check").fetchone() or db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("The expanded catalog failed integrity checks.")
        export(db, args.destination, metadata)
    finally:
        db.close()
        source.close()


if __name__ == "__main__":
    main()

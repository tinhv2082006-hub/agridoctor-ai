"""Tra cứu tình huống quan sát bằng văn bản tiếng Việt."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import re
import sqlite3
import unicodedata


def normalize(text):
    plain = unicodedata.normalize("NFD", text.casefold()).replace("đ", "d")
    plain = "".join(c for c in plain if unicodedata.category(c) != "Mn")
    return " ".join(re.findall(r"[a-z0-9]+", plain))


COFFEE_TYPES = {"robusta": ("ca phe voi", "robusta", "voi"),
                "arabica": ("ca phe che", "arabica", "che"),
                "liberica": ("ca phe mit", "liberica", "mit")}
COFFEE_STAGES = {
    "nursery": ("cay giong", "vuon uom", "bau uom", "nursery"),
    "fruiting": ("mang trai", "mang qua", "nuoi trai", "nuoi qua", "dang co trai",
                 "dang co qua", "dang dau trai", "dang dau qua", "fruiting"),
    "flowering": ("ra hoa", "no hoa", "flowering"),
    "production": ("kinh doanh", "production"),
}
RICE_STAGES = {
    "seedling": ("ma", "cay ma", "moi sa", "moi cay", "seedling"),
    "tillering": ("de nhanh", "tillering"),
    "booting": ("lam dong", "booting"),
    "heading": ("tro bong", "tro", "heading"),
    "ripening": ("chin", "sap gat", "ripening"),
}
TYPES = {"coffee": COFFEE_TYPES}
STAGES = {"coffee": COFFEE_STAGES, "rice": RICE_STAGES}
NEGATION = re.compile(r"\b(?:khong(?! (?:chi|nhung)\b)|chua|chang|cha|khong he)\b")
POST_NEGATION = re.compile(r"^ *(?:thi )?(?:khong|chua|chang|cha)(?: (?:co|thay|xuat hien))?(?: (?:dau|ca|gi|het|nhe|nha|a))* *$")
UNCERTAINTY = re.compile(r"\b(?:khong ro|khong biet|khong chac|chua chac|co phai|lieu co|hinh nhu)\b")
BOUNDARY = re.compile(r"\b(?:nhung|tuy nhien|ma lai|ma co|ma la|va co|va thay|va)\b")
LOCAL_NEGATION = re.compile(r"(?<!nam )\b(?:hong|hem|hok|khum) (?=(?:co|bi|thay|ro|biet|chac|phai)\b)")
REGIONAL = {
    "rice": ((r"\bbong (?:lua )?(?:no )?(?:bi )?(?:lep )?trang(?: het| ca bong)?\b", "bong bac"),
             (r"\bco (?:bong|gie) (?:lua )?(?:no )?(?:bi )?(?:tham den|den tham|tham|sam)\b", "tham co bong"),
             (r"\b(?:than|danh) (?:lua )?(?:bi duc lo|co lo duc)\b", "sau duc than")),
    "durian": ((r"\b(?:xi|ri|chay) (?:ra )?(?:mu|nhua)\b", "xi mu"),),
    "coffee": ((r"\b(?:ri|gi) set\b", "ri sat"),
               (r"\b(?:bot|phan) (?:mau )?(?:vang )?cam (?:o |duoi |bam )?(?:mat )?duoi la\b|\bmat duoi la co (?:lop )?(?:bot|phan) (?:mau )?(?:vang )?cam\b", "phan cam mat duoi")),
}


def regional_clause(clause, crop):
    for pattern, replacement in REGIONAL.get(crop, ()):
        clause = re.sub(pattern, replacement, clause)
    clause = LOCAL_NEGATION.sub("khong ", clause)
    return re.sub(r"\bkhong (?:chac|phai) la\b", "khong chac ", clause)
QUESTIONS = {
    "crop_type": "Bác cho biết đây là cà phê vối, cà phê chè hay cà phê mít?",
    "growth_stage": "Bác cho biết cây đang ở giai đoạn nào, ví dụ cây giống, ra hoa hoặc mang trái?",
    "plant_part": "Bác cho biết dấu hiệu nằm trên lá, thân, gốc, rễ hay quả/bông?",
}
GENERAL = "Bác gửi ảnh cận, ảnh toàn cây hoặc bụi và cho biết dấu hiệu xuất hiện từ khi nào."


def contains(text, phrase):
    return f" {phrase} " in f" {text} "


def context_value(options, supplied, query):
    if supplied:
        value = normalize(supplied)
        return next((key for key, aliases in options.items()
                     if value == key or value in aliases), value or None)
    matches, _ = search_phrases(query, [(alias, key) for key, aliases in options.items()
                                        for alias in aliases if " " in alias])
    return next(iter(matches)) if len(matches) == 1 else None


def search_phrases(text, aliases, crop=None):
    """Giữ các cụm ở vị trí khác nhau, bỏ cụm ngắn nằm bên trong cụm cụ thể hơn."""
    cases = set()
    unconfirmed = set()
    ambiguous_input = False
    for sentence in re.split(r"[,.;!?\n]+", text):
        for clause in BOUNDARY.split(normalize(sentence)):
            clause = regional_clause(clause, crop)
            mentions = []
            uncertain = UNCERTAINTY.search(clause)
            for phrase, case_id in aliases:
                for match in re.finditer(r"\b" + re.escape(phrase) + r"\b", clause):
                    unresolved = bool((uncertain and uncertain.start() <= match.start()) or
                                      NEGATION.search(clause[:match.start()]) or
                                      POST_NEGATION.fullmatch(clause[match.end():]))
                    mentions.append((match.start(), match.end(), case_id, unresolved))
            for start, end, case_id, unresolved in mentions:
                if not any(a <= start and end <= b and (a, b) != (start, end)
                           for a, b, _, _ in mentions):
                    if unresolved:
                        unconfirmed.add(case_id)
                        ambiguous_input = True
                    else:
                        cases.add(case_id)
    if cases & unconfirmed:
        return set(), True
    return cases, ambiguous_input


def matches_part(part, organs):
    equivalents = {"trai": "qua", "cuong trai": "cuong qua", "co gie": "co bong"}
    part = equivalents.get(part, part)
    return any(contains(organ, part) or contains(part, organ)
               for organ in (normalize(item) for item in organs.split(";")) if organ)


def lookup(path, crop, text, demo=False, *, crop_type=None, growth_stage=None,
           plant_part=None, region=None):
    """Trả bước quan sát. Cần truyền loại cây và giai đoạn nếu app đã hỏi."""
    context = {
        "crop_type": context_value(TYPES.get(crop, {}), crop_type, text),
        "growth_stage": context_value(STAGES.get(crop, {}), growth_stage, text),
        "plant_part": normalize(plant_part) if plant_part else None,
        "region": region.strip() if region else None,
    }
    result = {"status": "need_more_info", "crop_id": crop, "candidate_ids": [],
              "demo": demo, "context": context}
    uri = Path(path).resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True)) as db:
        db.row_factory = sqlite3.Row
        if not db.execute("SELECT 1 FROM crops WHERE id=?", (crop,)).fetchone():
            raise ValueError("Mã cây không hợp lệ")
        rows = db.execute("""
            SELECT c.id, c.organs, a.normalized FROM cases c
            JOIN aliases a ON a.case_id=c.id
            WHERE c.crop_id=?
              AND (c.review_status='approved' OR (? AND c.review_status='pending'))
              AND EXISTS(SELECT 1 FROM case_sources s WHERE s.case_id=c.id)
        """, (crop, demo)).fetchall()
        matches, uncertain = search_phrases(text, {(r["normalized"], r["id"]) for r in rows}, crop)
        organs = {r["id"]: r["organs"] for r in rows}
        scopes = {}
        for row in db.execute("""
            SELECT sc.* FROM case_scope sc JOIN cases c ON c.id=sc.case_id
            WHERE c.crop_id=?
        """, (crop,)):
            scopes.setdefault(row["case_id"], {})[row["dimension"]] = row["value"]

        candidates, missing, excluded = [], set(), False
        for case_id in sorted(matches):
            rules = scopes.get(case_id, {})
            if any(context[key] and context[key] != expected for key, expected in rules.items()):
                excluded = True
                continue
            if context["plant_part"] and not matches_part(context["plant_part"], organs[case_id]):
                excluded = True
                continue
            required = {key for key in rules if not context[key]}
            if required:
                missing.update(required)
                continue
            candidates.append(case_id)

        result["candidate_ids"] = candidates
        if not candidates:
            if missing:
                field = next(key for key in QUESTIONS if key in missing)
                result.update(reason="missing_context", question_field=field, summary=QUESTIONS[field])
            elif excluded:
                result.update(reason="context_mismatch",
                              summary="Chưa có mục khớp với bối cảnh này. Bác mô tả dấu hiệu thấy trực tiếp và gửi ảnh bộ phận bị ảnh hưởng.")
            elif uncertain:
                result.update(reason="unconfirmed_sign",
                              summary="Bác cho biết dấu hiệu nào đã nhìn thấy trực tiếp. " + GENERAL)
            else:
                result.update(reason="no_match", summary=GENERAL)
            return result

        if len(candidates) > 1:
            summary = "Có nhiều tình huống cần phân biệt. " + GENERAL
            if {"R02", "R08"}.issubset(candidates):
                summary = "Bác xem cổ bông có vết thâm và thân có lỗ đục hay không. Chụp cận cả hai vị trí và toàn bụi."
            if {"D01", "D19"}.issubset(candidates):
                summary = "Bác cho biết đốm bắt đầu từ mép lá hay là nhiều đốm tròn rải rác. Chụp cả hai mặt lá và toàn cành."
            result.update(reason="multiple_candidates", summary=summary)
            return result

        found = db.execute("""
            SELECT cc.*, sr.region AS evidence_region FROM case_catalog cc
            LEFT JOIN source_region sr ON sr.source_id=cc.source_id
            WHERE cc.id=? ORDER BY cc.source_id, cc.locator
        """, (candidates[0],)).fetchall()
        region_key = normalize(context["region"] or "")
        found = sorted(found, key=lambda row: (
            not (region_key and row["evidence_region"] and
                 contains(region_key, normalize(row["evidence_region"]))),
            row["evidence_region"] is None,
        ))
        result.update(reason="follow_up", summary=found[0]["follow_up"],
                      observation=found[0]["observation"], review_status=found[0]["review_status"],
                      sources=[{key: row[key] for key in ("source_title", "url", "locator", "evidence_region")}
                               for row in found])
        if "approval_scope" in found[0].keys():
            result["approval_scope"] = found[0]["approval_scope"]
        return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("crop", choices=("durian", "coffee", "rice"))
    parser.add_argument("query")
    parser.add_argument("--demo", action="store_true")
    parser.add_argument("--crop-type")
    parser.add_argument("--growth-stage")
    parser.add_argument("--plant-part")
    parser.add_argument("--region")
    args = parser.parse_args()
    result = lookup(Path(__file__).with_name("agridoctor.sqlite"), args.crop, args.query,
                    args.demo, crop_type=args.crop_type, growth_stage=args.growth_stage,
                    plant_part=args.plant_part, region=args.region)
    print(json.dumps(result, ensure_ascii=False, indent=2))

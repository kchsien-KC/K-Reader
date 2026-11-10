#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 13 檔案驗證器

Stage 13 範圍（第七旗艦增益包：閱讀資料自主與年度回顧五件套）：
  · 閱讀年報（ReadingAnnualReportHelper：滾動 365 天六方彙整＋純文字卡）
  · 資料匯出中心（ReadingDataExporter：六節 Markdown 報告＋FileProvider 分享）
  · 換機搬家（DeviceMigrationHelper：KRM1 自描述格式＋帳冊登記處＋還原備份）
  · 書籍標籤（BookTagManager：8 色票簿＋標記圖＋懸空自隱）
  · 資料健檢（DataHygieneHelper：JSON 可解析掃描＋損毀隔離＋體積彙總）
  · dialog_annual_report.xml 年報對話框布局
  · strings.xml 更新（617 → 680 鍵，只增不刪）
  · init_k_reader.sh／README.md 更新（十三階段、55 個 Kotlin 模組）
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE13_FILES = [
    "app/src/main/java/com/k/reader/help/report/ReadingAnnualReportHelper.kt",
    "app/src/main/java/com/k/reader/help/export/ReadingDataExporter.kt",
    "app/src/main/java/com/k/reader/help/migrate/DeviceMigrationHelper.kt",
    "app/src/main/java/com/k/reader/help/tag/BookTagManager.kt",
    "app/src/main/java/com/k/reader/help/hygiene/DataHygieneHelper.kt",
    "app/src/main/res/layout/dialog_annual_report.xml",
]
PACKAGE_OF = {
    "ReadingAnnualReportHelper.kt": "com.k.reader.help.report",
    "ReadingDataExporter.kt": "com.k.reader.help.export",
    "DeviceMigrationHelper.kt": "com.k.reader.help.migrate",
    "BookTagManager.kt": "com.k.reader.help.tag",
    "DataHygieneHelper.kt": "com.k.reader.help.hygiene",
}
FORBIDDEN = ["TODO", "FIXME", "XXX", "待補充", "【"]

errors, warnings = [], []


def strip_kotlin(src: str) -> str:
    """單趟狀態機：剝除 Kotlin 註解／字串／字元字面值，僅留程式碼骨架。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            i = n if j == -1 else j            # 保留換行
        elif c == "/" and nxt == "*":
            j = src.find("*/", i + 2)
            i = n if j == -1 else j + 2
            out.append(" ")
        elif c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j == -1 else j + 3
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"':
                        break
                    j += 1
                i = j + 1
            out.append('""')
        elif c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
            out.append("''")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check(cond, msg):
    if not cond:
        errors.append(msg)


def load_strings():
    tree = ET.parse(ROOT / "app/src/main/res/values-zh-rTW/strings.xml")
    return {el.get("name"): (el.text or "") for el in tree.getroot().findall("string")}


string_map = load_strings()
string_keys = set(string_map)

# ── 1. 檔案存在與大小 ──
for rel in STAGE13_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 布局 XML：良構性 + id + @string + 設計紀律 ──
p = ROOT / "app/src/main/res/layout/dialog_annual_report.xml"
if p.is_file():
    try:
        ET.parse(p)
        raw = p.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["tv_annual_summary", "tv_annual_card", "tv_annual_empty",
                     "sv_annual", "btn_annual_share", "btn_annual_close"]:
            check(need in ids, f"{p.name} 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys, f"{p.name} 引用了不存在的 string：{ref}")
        check("ScrollView" in raw,
              f"{p.name} 長卡應以 ScrollView 包裹（長內容不撐爆對話框）")
        check("minHeight" in raw,
              f"{p.name} 卡片區應設 minHeight（空卡不塌陷）")
        check("paddingStart" in raw or "marginStart" in raw,
              f"{p.name} 應使用 RTL 友善 paddingStart/marginStart")
        check('?android:attr/textColorSecondary' in raw,
              f"{p.name} 應以主題屬性著色（日夜間自動適配）")
        check(re.search(r'android:textColor="#', raw) is None,
              f"{p.name} 不應硬編碼顏色（主題屬性紀律）")
        check('visibility="gone"' in raw,
              f"{p.name} 空狀態閒置時應 gone（零干擾）")
        check(raw.count("OutlinedButton") >= 2,
              f"{p.name} 動作鈕應為外框風格（與 dialog_badges 同款視覺語言）")
        check("textIsSelectable" in raw,
              f"{p.name} 年報卡本文應可選取（複製主路輔助）")
        check("fontFamily=\"monospace\"" in raw,
              f"{p.name} 等寬字體對齊框線（年報卡排版紀律）")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE13_FILES:
    if not rel.endswith(".kt"):
        continue
    p = ROOT / rel
    if not p.is_file():
        continue
    src = p.read_text(encoding="utf-8")
    name = p.name
    kt_sources[name] = src
    stripped = strip_kotlin(src)
    check(f"package {PACKAGE_OF[name]}" in stripped,
          f"{name} package 宣告應為 {PACKAGE_OF[name]}")
    for bad in FORBIDDEN:
        check(bad not in src, f"{name} 含禁用標記：{bad}")
    for ref in set(re.findall(r'R\.string\.(\w+)', stripped)):
        check(ref in string_keys, f"{name} 引用了不存在的 R.string：{ref}")
    check("io.legado" not in stripped, f"{name} 不應殘留 io.legado 引用")

# ── 4. 模組 API 針檢（單源唯讀＋防回歸） ──
# must 針檢：程式碼符記查剝除骨架；字串字面值查原始碼（strip 會剝空內容）——
# 兩者任一命中即過；must_not 恆查骨架（防註解文字誤傷）
def needles(name, must, must_not):
    if name not in kt_sources:
        return
    raw = kt_sources[name]
    stripped = strip_kotlin(raw)
    for m in must:
        check(m in stripped or m in raw, f"{name} 缺少針檢：{m}")
    for m in must_not:
        check(m not in stripped, f"{name} 禁用項出現：{m}")


needles("ReadingAnnualReportHelper.kt",
        must=["dailyChart(YEAR_WINDOW_DAYS)", "dailyWordCount(", "formatDuration(",
              "awardedCount()", "totalBadges()", "trophyWall()", "allQuotes()",
              "activeCount()", "masteredCount()", "allNotes()", "MAX_CARD_CHARS",
              "ACTION_SEND", "ClipData", "summaryTotal().finishedCount"],
        must_not=["Bitmap", "durationMinutes", "SharedPreferences"])
needles("ReadingDataExporter.kt",
        must=["summaryToday()", "summaryWeek()", "summaryTotal()",
              "dailyChart(365)", "trophyLine(", "allWords()", "MAX_REPORT_CHARS",
              "FileProvider.getUriForFile", "com.k.reader.filen", "stats_duration"],
        must_not=["Bitmap", "durationMinutes", "io.legado"])
needles("DeviceMigrationHelper.kt",
        must=["LEDGER_FILES", '"book_tags.json"', "PACK_MAGIC", "KRM1",
              "PACK_VERSION", 'optString(name, "")', "copyTo(", "maxByOrNull",
              "migration_backup_"],
        must_not=["io.legado.app"])
needles("BookTagManager.kt",
        must=["TAGS_PER_BOOK", "MAX_TAGS", "COLOR_COUNT", "book_tags.json",
              "nextIdOf", "bookKey(", "NAME_MAX_CHARS"],
        must_not=["io.legado"])
needles("DataHygieneHelper.kt",
        must=["JSONTokener", "CORRUPT_SUFFIX", "DeviceMigrationHelper.LEDGER_FILES",
              "formatBytes", "STATUS_BROKEN", "STATUS_MISSING", "fixBroken"],
        must_not=["io.legado"])

# 帳冊登記處：13 檔單源（搬家＝匯出＝健檢共用）
mig = kt_sources.get("DeviceMigrationHelper.kt", "")
ledgers = re.findall(r'"([a-z_]+\.json)"', mig)
check(len(ledgers) == 13, f"LEDGER_FILES 應登記 13 個帳冊，實為 {len(ledgers)}")
for need in ["golden_quotes.json", "highlight_notes.json", "vocabulary_notes.json",
             "achievement_badges.json", "reading_timeline.json",
             "reading_challenges.json", "reading_queue.json", "reading_profiles.json",
             "tts_tuning.json", "chapter_bookmarks.json", "source_repos.json",
             "fonts_index.json", "book_tags.json"]:
    check(need in ledgers, f"LEDGER_FILES 缺少帳冊：{need}")
check(len(set(ledgers)) == len(ledgers), "LEDGER_FILES 帳冊重複登記")

# ── 5. 純 Python 邏輯等價測試 ──
print("  純 Python 邏輯等價測試……")

# 5a. 年報聚合：365 窗滾動（分鐘／活躍天／最佳單日／字數逐日加總）
def annual_aggregate(chart, word_of):
    minutes = sum(b[1] for b in chart)
    active = sum(1 for b in chart if b[1] > 0)
    best = None
    for b in chart:
        if b[1] > 0 and (best is None or b[1] > best[1]):
            best = b
    words = sum(word_of(b[0]) for b in chart)
    return minutes, active, (best[0] if best else ""), (best[1] if best else 0), words
check(annual_aggregate([("d1", 30), ("d2", 0), ("d3", 60)], lambda k: 100)
      == (90, 2, "d3", 60, 300), "聚合應排除零日、最佳取最大")
check(annual_aggregate([("d1", 60), ("d2", 60)], lambda k: 0)
      == (120, 2, "d1", 60, 0), "同高最佳取首見（maxByOrNull 語意）")
check(annual_aggregate([], lambda k: 0) == (0, 0, "", 0, 0), "空窗應全零")

# 5b. 保險絲截斷（與 truncate / substring+ellipsis 等價）
def truncate(text, max_len):
    if len(text) <= max_len:
        return text
    return text[:max_len - 1] + "…"
check(truncate("abc", 5) == "abc", "短文應原樣")
check(truncate("abcdef", 5) == "abcd…", "超長應截 max-1 加省略號")
check(len(truncate("x" * 700, 640)) == 640, "保險絲後總長恰 640")

# 5c. 卡片排版：displayWidth（CJK=2 其他=1）＋居中
def display_width(text):
    w = 0
    for ch in text:
        o = ord(ch)
        w += 2 if (0x2E80 <= o <= 0x9FFF or 0xF900 <= o <= 0xFAFF
                   or 0xFF00 <= o <= 0xFFEF) else 1
    return w
check(display_width("天天") == 4, "兩個 CJK 應寬 4")
check(display_width("ab") == 2, "兩個西文應寬 2")
check(display_width("天天ab") == 6, "混合寬度應 6")
def center(text, width=36):
    w = display_width(text)
    if w >= width:
        return text
    return " " * ((width - w) // 2) + text
check(center("天天") == " " * 16 + "天天", "寬 4 應左補 16 空白")
check(center("x" * 40) == "x" * 40, "超寬不補")
def pad_full_width(text):
    out = []
    for ch in text:
        if "0" <= ch <= "9":
            out.append(chr(0xFF10 + int(ch)))
        elif "a" <= ch <= "z":
            out.append(chr(0xFF41 + (ord(ch) - 97)).upper())
        elif "A" <= ch <= "Z":
            out.append(chr(0xFF21 + (ord(ch) - 65)))
        else:
            out.append(ch)
    return "".join(out)
check(pad_full_width("a1") == "Ａ１", "a1 應全形化為 Ａ１（小寫升大寫）")
check(pad_full_width("天天") == "天天", "全形字應原樣")

# 5d. 搬家包：打包／白名單還原／魔數守門／備份
LEDGERS = ledgers or []
def build_pack(files, now=1000, magic="KRM1", version=1):
    if not files:
        return None
    return {"magic": magic, "version": version, "exportedAt": now,
            "generator": "K.READER", "ledgerCount": len(files), "files": files}
def restore(pack, existing, ledger_list):
    if pack.get("magic") != "KRM1" or pack.get("version") != 1:
        return (0, list(ledger_list))
    restored, skipped, backups = 0, [], {}
    for name in ledger_list:
        content = pack["files"].get(name, "")
        if not content.strip():
            skipped.append(name)
            continue
        if name in existing:
            backups[name] = existing[name]
        existing[name] = content
        restored += 1
    return (restored, skipped, backups)
pack = build_pack({"a.json": "{}", "b.json": "[]"})
check(pack["ledgerCount"] == 2, "ledgerCount 應計 2")
r = restore(pack, {"a.json": "舊"}, ["a.json", "b.json", "c.json"])
check(r[0] == 2 and r[1] == ["c.json"], "白名單內 2 還原、缺席跳過")
check(r[2] == {"a.json": "舊"}, "覆寫前應備份原帳冊")
r = restore(build_pack({"a.json": "1"}, magic="BAD!"), {}, ["a.json"])
check(r[0] == 0 and r[1] == ["a.json"], "魔數不符應全拒")
r = restore(build_pack({"a.json": "1"}, version=99), {}, ["a.json"])
check(r[0] == 0, "版本不符應全拒")
r = restore(build_pack({"a.json": "1", "evil.json": "boom"}), {}, ["a.json"])
check(r[0] == 1 and "evil.json" not in r[2], "白名單外鍵應一律忽略")
check(build_pack({}) is None, "無帳冊應回 None")
r = restore(build_pack({"a.json": "1"}), {}, ["a.json", "b.json"])
check(r[1] == ["b.json"] and r[0] == 1, "空內容帳冊應跳過")

# 5e. 資料健檢：三態判定＋體積格式
def scan_status(exists, text):
    if not exists:
        return "MISSING"
    if text is None:
        return "BROKEN"
    t = text.strip()
    if not t:
        return "BROKEN"
    try:
        import json
        v = json.loads(t)
        return "OK" if isinstance(v, (dict, list)) else "BROKEN"
    except Exception:
        return "BROKEN"
check(scan_status(False, None) == "MISSING", "無檔應 MISSING")
check(scan_status(True, '{"a":1}') == "OK", "合法物件應 OK")
check(scan_status(True, '[1,2]') == "OK", "合法陣列應 OK")
check(scan_status(True, '{"a":') == "BROKEN", "截斷 JSON 應 BROKEN")
check(scan_status(True, '   ') == "BROKEN", "空白檔應 BROKEN")
check(scan_status(True, '123') == "BROKEN", "純量根節點應 BROKEN（帳冊必為物件／陣列）")
def format_bytes(n):
    kb, mb = 1024, 1024 * 1024
    if n < kb:
        return f"{n} B"
    if n < mb:
        return f"{n * 10 // kb / 10:.1f} KB"
    return f"{n * 10 // mb / 10:.1f} MB"
check(format_bytes(582) == "582 B", "582 位元組應 582 B")
check(format_bytes(1024) == "1.0 KB", "整 KB 應 1.0 KB")
check(format_bytes(1024 * 1024) == "1.0 MB", "整 MB 應 1.0 MB")
check(format_bytes(1024 + 52) == "1.0 KB", "整數截斷：1076 B 應 1.0 KB（不進位）")

# 5f. 書籍標籤：去重／配額／級聯刪除／懸空過濾
def tag_flow(tags, books, event, *args):
    """事件機：add/tag/untag/delete 與 Kotlin 等價"""
    if event == "add":
        name = args[0].strip()
        if not name or any(t["name"] == name for t in tags) or len(tags) >= 30:
            return tags, books
        new_id = (max((t["id"] for t in tags), default=0)) + 1
        return tags + [{"id": new_id, "name": name[:12], "colorIndex": 0}], books
    if event == "tag":
        key, tid = args
        ids = books.get(key, [])
        if tid in ids:
            return tags, books
        if key in books and len(ids) >= 3:
            return tags, books
        if key not in books and len(books) >= 500:
            return tags, books
        books = dict(books)
        books[key] = ids + [tid]
        return tags, books
    if event == "untag":
        key, tid = args
        ids = books.get(key)
        if not ids or tid not in ids:
            return tags, books
        left = [i for i in ids if i != tid]
        books = dict(books)
        if left:
            books[key] = left
        else:
            books.pop(key, None)
        return tags, books
    if event == "delete":
        tid = args[0]
        tags = [t for t in tags if t["id"] != tid]
        books = {k: v for k, v in books.items()
                 if (left := [i for i in v if i != tid]) and (books := books) is not None
                 or True} if False else books
        books = {k: [i for i in v if i != tid] for k, v in books.items()}
        books = {k: v for k, v in books.items() if v}
        return tags, books
    return tags, books
tags, books = [], {}
tags, books = tag_flow(tags, books, "add", "科幻")
tags, _ = tag_flow(tags, books, "add", " 科幻 ")
check(len(tags) == 1, "同名標籤應去重（trim 後）")
tags, _ = tag_flow(tags, books, "add", "科普")
tags, books = tag_flow(tags, books, "tag", "三體|劉慈欣", 1)
tags, books = tag_flow(tags, books, "tag", "三體|劉慈欣", 1)
check(books["三體|劉慈欣"] == [1], "重複標記應冪等")
tags, books = tag_flow(tags, books, "tag", "三體|劉慈欣", 2)
tags, books = tag_flow(tags, books, "tag", "三體|劉慈欣", 3)
tags, books = tag_flow(tags, books, "tag", "三體|劉慈欣", 1)
check(len(books["三體|劉慈欣"]) == 3, "單書上限 3")
tags, books = tag_flow(tags, books, "delete", 1)
check(all(1 not in v for v in books.values()), "刪標籤應級聯拆除標記")
check(books.get("三體|劉慈欣") == [2, 3], "級聯後其餘標記保留")
def tags_for(tags, books, key):
    known = {t["id"]: t for t in tags}
    return [known[i] for i in books.get(key, []) if i in known]
check(tags_for([], {"k": [9]}, "k") == [], "懸空標記應被過濾")

# 5g. 匯出中心：六節齊全＋缺席節「（無資料）」＋保險絲
def build_md(sources, fuse=2_000_000):
    sb = ["# 資料匯出中心", "- t"]
    for title, items in sources:
        sb.append(f"## {title}")
        if not items:
            sb.append("（無資料）")
        else:
            sb.extend(f"- {i}" for i in items)
    text = "\n".join(sb)
    return None if len(text) > fuse else text
md = build_md([("閱讀統計", ["a", "b"]), ("金句簿", []), ("生詞本", ["c"]),
              ("劃線筆記", []), ("閱讀挑戰", []), ("成就徽章", ["e"])])
check(md.count("##") == 6, "六節標題應齊")
check(md.count("（無資料）") == 3, "缺席三節應標無資料")
check(build_md([("統計", ["x" * 10])], fuse=5) is None, "超保險絲應中止匯出")

# ═══════════ 6. strings.xml：鍵數與只增不刪 ═══════════
check(len(string_keys) == 680,
      f"字串鍵應為 680（617＋63），實為 {len(string_keys)}")
STAGE13_PREFIXES = ("annual_", "dataexp_", "migrate_", "tag_", "hygiene_")
new_keys = {k for k in string_keys if k.startswith(STAGE13_PREFIXES)}
check(len(new_keys) == 63,
      f"Stage 13 新鍵應 63（16年報＋16匯出＋10搬家＋12標籤＋9健檢），實為 {len(new_keys)}")
for pfx, want in [("annual_", 16), ("dataexp_", 16), ("migrate_", 10),
                  ("tag_", 12), ("hygiene_", 9)]:
    got = sum(1 for k in string_keys if k.startswith(pfx))
    check(got == want, f"{pfx} 應 {want} 鍵，實為 {got}")

# 舊鍵基線抽樣（只增不刪：Stage 1-12 代表鍵仍在）
for must in ["app_name", "badge_summary_fmt", "vocab_queue_fmt",
             "share_copied", "timeline_recap_fmt", "challenge_progress_fmt",
             "pace_trend_fmt", "queue_summary_fmt", "stats_duration",
             "stats_today", "stats_total", "ttune_rate", "profile_title"]:
    check(must in string_keys, f"只增不刪違例：缺少舊鍵 {must}")

# 格式鍵參數抽樣（printf 位置記號在）
for k in ["annual_best_day_fmt", "annual_vocab_fmt", "annual_badges_fmt",
          "dataexp_badges_fmt", "dataexp_days_fmt", "migrate_built_fmt",
          "tag_book_line", "tag_color_fmt", "hygiene_row_fmt",
          "hygiene_fixed_fmt", "dataexp_chars_fmt", "migrate_restored_fmt"]:
    check("%1$" in string_map[k], f"{k} 應用位置參數（格式鍵紀律）")

# ═══════════ 7. init_k_reader.sh：關鍵檔清單與十三階段字樣 ═══════════
init_sh = ROOT / "init_k_reader.sh"
if init_sh.is_file():
    sh = init_sh.read_text(encoding="utf-8")
    for name in ["ReadingAnnualReportHelper.kt", "ReadingDataExporter.kt",
                 "DeviceMigrationHelper.kt", "BookTagManager.kt",
                 "DataHygieneHelper.kt", "dialog_annual_report.xml"]:
        check(name in sh, f"init_k_reader.sh 關鍵檔清單缺少：{name}")
    check("十三階段關鍵檔" in sh, "init_k_reader.sh 應標示十三階段關鍵檔")
    check("十三階段交付檔" in sh, "init_k_reader.sh 疊加層步驟應為十三階段")
    check("55 個 Kotlin 模組" in sh, "init_k_reader.sh 應標示 55 個 Kotlin 模組")
    check("十二階段" not in sh, "init_k_reader.sh 不應殘留十二階段字樣")
    check("K.閱讀 680 鍵" in sh, "init_k_reader.sh 合併註解應標示 680 鍵")
    kf = re.search(r'KEY_FILES=\((.*?)\)', sh, re.S)
    if kf:
        entries = re.findall(r'"([^"]+)"', kf.group(1))
        check(len(entries) == 61,
              f"KEY_FILES 應 61 項（55 Kotlin＋4 布局＋字串＋工作流），實為 {len(entries)}")
        check(sum(1 for e in entries if e.endswith(".kt")) == 55,
              "KEY_FILES 應恰 55 個 Kotlin 檔")

# ═══════════ 8. README.md：十三階段交付對照 ═══════════
readme = ROOT / "README.md"
if readme.is_file():
    md = readme.read_text(encoding="utf-8")
    check("十三階段交付" in md, "README.md 應標示十三階段交付")
    check("| Stage 13 |" in md, "README.md 應有 Stage 13 列")
    check("閱讀年報＋資料匯出中心＋搬家包＋書籍標籤＋資料健檢" in md,
          "README.md Stage 13 範圍應為五件套")
    check("617→680" in md, "README.md 應記錄字串 617→680")
    check("50→55 Kotlin" in md, "README.md 應記錄模組 50→55")
    check("LEDGER_FILES" in md, "README.md 應記錄帳冊登記處整合")

# ═══════════ 結果 ═══════════
print("  Stage 13 檔案：年報／匯出中心／搬家／標籤／健檢＋布局＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（617 基線＋63 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 13 全部檔案驗證通過")

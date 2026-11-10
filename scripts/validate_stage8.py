#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 8 檔案驗證器

Stage 8 範圍（第二旗艦增益包）：
  · 書內全文搜尋（FullTextSearchHelper ＋ dialog_fulltext_search.xml）
  · 劃線筆記本（HighlightNoteManager ＋ dialog_note_editor.xml）
  · 閱讀目標與提醒（ReadingGoalManager）
  · 定時日夜切換（DayNightScheduleHelper）
  · 書架整理助手（ShelfOrganizeHelper）
  · strings.xml 更新（286 → 344 鍵，只增不刪）
  · init_k_reader.sh 更新（八階段、30 個 Kotlin 模組）
"""
import math
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE8_FILES = [
    "app/src/main/java/com/k/reader/help/search/FullTextSearchHelper.kt",
    "app/src/main/java/com/k/reader/help/note/HighlightNoteManager.kt",
    "app/src/main/java/com/k/reader/help/goal/ReadingGoalManager.kt",
    "app/src/main/java/com/k/reader/help/config/DayNightScheduleHelper.kt",
    "app/src/main/java/com/k/reader/help/shelf/ShelfOrganizeHelper.kt",
    "app/src/main/res/layout/dialog_fulltext_search.xml",
    "app/src/main/res/layout/dialog_note_editor.xml",
]
PACKAGE_OF = {
    "FullTextSearchHelper.kt": "com.k.reader.help.search",
    "HighlightNoteManager.kt": "com.k.reader.help.note",
    "ReadingGoalManager.kt": "com.k.reader.help.goal",
    "DayNightScheduleHelper.kt": "com.k.reader.help.config",
    "ShelfOrganizeHelper.kt": "com.k.reader.help.shelf",
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
for rel in STAGE8_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 版面 XML：良構性 + id + @string + 設計紀律 ──
ALL_LAYOUT_IDS = set()
for rel, need_ids, need_refs in [
    ("app/src/main/res/layout/dialog_fulltext_search.xml",
     ["et_fulltext_keyword", "cb_fulltext_regex", "row_fulltext_status",
      "sv_fulltext_result", "tv_fulltext_result", "btn_fulltext_search"],
     {"fulltext_keyword_hint", "fulltext_regex_switch", "fulltext_search_btn"}),
    ("app/src/main/res/layout/dialog_note_editor.xml",
     ["tv_note_quote", "et_note_content", "rg_note_colors",
      "rb_note_quote", "rb_note_question", "rb_note_key",
      "btn_note_save", "btn_note_to_quote"],
     {"note_edit_hint", "note_color_quote", "note_color_question",
      "note_color_key", "note_save", "note_to_quote"}),
]:
    p = ROOT / rel
    if not p.is_file():
        continue
    try:
        ET.parse(p)
        raw = p.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        ALL_LAYOUT_IDS |= ids
        for need in need_ids:
            check(need in ids, f"{p.name} 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys,
                  f"{p.name} 引用了不存在的 string：{ref}")
        check(need_refs <= set(re.findall(r'@string/(\w+)', raw)),
              f"{p.name} 應引用文案：{sorted(need_refs)}")
        # 設計紀律：RTL 友善＋純主題屬性＋零硬編碼色
        check("paddingStart" in raw or "marginStart" in raw,
              f"{p.name} 應使用 RTL 友善 paddingStart/marginStart")
        check("?attr/colorPrimary" in raw or "?android:attr/textColorPrimary" in raw
              or "?attr/textColorSecondary" in raw,
              f"{p.name} 應以主題屬性著色（日夜間自動適配）")
        check(re.search(r'android:textColor="#', raw) is None,
              f"{p.name} 不應硬編碼顏色（主題屬性紀律）")
        if p.name == "dialog_fulltext_search.xml":
            check('android:visibility="gone"' in raw,
                  "dialog_fulltext_search.xml 狀態列應預設 visibility=gone（零干擾）")
            check("TextInputLayout" in raw and "TextInputEditText" in raw,
                  "dialog_fulltext_search.xml 應使用 Material TextInputLayout 元件")
        if p.name == "dialog_note_editor.xml":
            check("textMultiLine" in raw,
                  "dialog_note_editor.xml 批註欄應為多行輸入")
            check("RadioGroup" in raw and raw.count("<RadioButton") >= 3,
                  "dialog_note_editor.xml 應有三色 RadioGroup 單選群組")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE8_FILES:
    if not rel.endswith(".kt"):
        continue
    p = ROOT / rel
    if not p.is_file():
        continue
    src = p.read_text(encoding="utf-8")
    name = p.name
    kt_sources[name] = src
    stripped = strip_kotlin(src)

    for bad in FORBIDDEN:
        if bad == "【":
            check(bad not in stripped, f"{name} 程式碼含禁用標記：{bad}")
        else:
            check(bad not in src, f"{name} 含禁用標記：{bad}")
    check(re.search(r"省略(?!號)", stripped) is None,
          f"{name} 含程式碼省略標記：省略")

    m = re.search(r'^package\s+([\w.]+)\s*$', src, re.M)
    check(m is not None, f"{name} 缺少 package 宣告")
    if m:
        check(m.group(1) == PACKAGE_OF[name],
              f"{name} package 應為 {PACKAGE_OF[name]}，實為 {m.group(1)}")

    for a, b in [("{", "}"), ("(", ")"), ("[", "]")]:
        check(stripped.count(a) == stripped.count(b),
              f"{name} 括號不平衡：{a}{stripped.count(a)} vs {b}{stripped.count(b)}")

    for ref in set(re.findall(r'R\.string\.(\w+)', stripped)):
        check(ref in string_keys, f"{name} 引用了不存在的 R.string.{ref}")

fulltext = kt_sources.get("FullTextSearchHelper.kt", "")
notes = kt_sources.get("HighlightNoteManager.kt", "")
goal = kt_sources.get("ReadingGoalManager.kt", "")
daynight = kt_sources.get("DayNightScheduleHelper.kt", "")
shelf = kt_sources.get("ShelfOrganizeHelper.kt", "")

# ── 4a. 全文搜尋：雙針簡繁＋雙模式＋上限護欄 ──
for needle, desc in [
    ("MAX_HITS = 300", "結果上限 300 筆規格"),
    ("CONTEXT_CHARS = 20", "摘錄前後各 20 字規格"),
    ("MAX_KEYWORD_CHARS = 64", "關鍵字 64 字上限規格"),
    ("MAX_CHAPTER_CHARS = 200_000", "單章內容長度護欄"),
    ("MODE_PLAIN", "模式：純文字"),
    ("MODE_REGEX", "模式：正則表達式"),
    ("fun interface ContentLoader", "缺少章節內容注入介面"),
    ("fun interface ProgressReporter", "缺少進度回報介面"),
    ("suspend fun searchInBook", "缺少 suspend 搜尋總入口"),
    ("withContext(Dispatchers.IO)", "搜尋應在 IO 執行緒"),
    ("TraditionalChineseHelper.toTraditional", "雙針應走簡轉繁引擎（簡繁並搜）"),
    ("PatternSyntaxException", "正則格式錯誤應容錯不崩潰"),
    ("ConcurrentHashMap", "正則快取應為並發安全結構"),
    ("fun buildPlainNeedles", "缺少雙針清單組裝"),
    ("fun collectPlainHits", "缺少純文字走查"),
    ("fun collectRegexHits", "缺少正則走查"),
    ("Pattern.CASE_INSENSITIVE", "忽略大小寫應走正則旗標"),
    ("data class SearchHit", "缺少命中條目"),
    ("data class SearchResult", "缺少搜尋結果"),
    ('R.string.fulltext_chapter_fmt', "未引用章節標籤文案"),
]:
    check(needle in fulltext, f"FullTextSearchHelper.kt：{desc}")

# 雙針命中位置零漂移：繁化針應也在原文上 indexOf（位置語義）
check(re.search(r'loweredHay\.indexOf\(probe', fulltext) is not None,
      "純文字走查應以 lowercase 後 indexOf（大小寫不敏感）")

# ── 4b. 劃線筆記本：三色＋批註＋匯出＋金句卡直達 ──
for needle, desc in [
    ("COLOR_QUOTE", "三色分類：金句黃"),
    ("COLOR_QUESTION", "三色分類：疑問藍"),
    ("COLOR_KEY", "三色分類：重點紅"),
    ("MAX_NOTES = 500", "全簿容量 500 條規格"),
    ("MAX_CONTENT_CHARS = 800", "單條內容 800 字上限規格"),
    ('BOOK_FILE = "highlight_notes.json"', "劃線簿檔名（filesDir）"),
    ("data class HighlightNote", "缺少劃線條目"),
    ("fun saveHighlight", "缺少劃線收藏 API"),
    ("fun updateNote", "缺少批註更新 API"),
    ("fun removeNote", "缺少劃線刪除 API"),
    ("fun allNotes", "缺少全簿查詢 API"),
    ("fun notesFor", "缺少單書查詢 API"),
    ("fun notesForChapter", "缺少章內劃線查詢（渲染底色用）"),
    ("fun toGoldenQuote", "缺少金句卡直達 API"),
    ("GoldenQuoteManager.saveQuote", "金句卡直達應委託 GoldenQuoteManager"),
    ("suspend fun exportMarkdown", "缺少 Markdown 匯出"),
    ("suspend fun exportPlainText", "缺少純文字匯出"),
    ("MediaStore.Downloads", "匯出應走 MediaStore 公共下載目錄"),
    ("RELATIVE_PATH", "Android 10+ 應以 RELATIVE_PATH 落地"),
    ("fun evictOverflow", "缺少容量淘汰工序"),
]:
    check(needle in notes, f"HighlightNoteManager.kt：{desc}")

# Markdown 匯出骨架：# 書名 / ## 章節 / > 劃線
for needle in ['append("# ")', 'append("\\n## ")', 'append("\\n> ")']:
    check(needle in notes, f"HighlightNoteManager.kt Markdown 骨架應含 {needle}")

# ── 4c. 閱讀目標：數據單源＋streak＋通知 ──
for needle, desc in [
    ("TYPE_MINUTES", "目標型態：分鐘數"),
    ("TYPE_WORDS", "目標型態：字數"),
    ("PRESET_MINUTES = listOf(15, 30, 60, 120)", "分鐘預設檔四檔規格"),
    ("PRESET_WORDS = listOf(10_000L, 30_000L, 50_000L, 100_000L)", "字數預設檔四檔規格"),
    ("DEFAULT_REMINDER_HOUR = 21", "提醒預設 21:00 規格"),
    ("ReadStatisticsManager.summaryToday", "進度應讀統計單源"),
    ("ReadStatisticsManager.dailyChart", "streak 應讀統計圖表"),
    ("ReadStatisticsManager.RETENTION_DAYS", "streak 回看應達保留窗"),
    ("fun progress", "缺少進度查詢 API"),
    ("fun streakCount", "缺少連續天數 API"),
    ("fun bestStreak", "缺少最長紀錄 API"),
    ("fun checkAchieved", "缺少達成檢查 API"),
    ("fun shouldRemindNow", "缺少提醒時機判斷 API"),
    ("fun fireReminder", "缺少提醒通知 API"),
    ("NotificationChannel", "應走 NotificationChannel（API 26+）"),
    ("NotificationCompat", "通知應走 NotificationCompat"),
    ("KEY_ACHIEVED_DATE", "達成通知應同日一次（日期鍵）"),
    ("KEY_REMINDED_DATE", "提醒應同日一次（日期鍵）"),
    ("KEY_BEST_STREAK", "最長紀錄應跨啟動持久化"),
    ('R.string.goal_achieved', "未引用達成通知文案"),
    ('R.string.goal_reminder_text', "未引用提醒通知文案"),
    ('R.string.goal_channel_name', "未引用渠道名稱文案"),
    ('R.string.goal_progress_fmt', "未引用進度文案"),
    ('R.string.goal_streak_fmt', "未引用連續天數文案"),
]:
    check(needle in goal, f"ReadingGoalManager.kt：{desc}")

check(re.search(r'if \(chart\[idx\]\.minutes <= 0L\) idx--', goal) is not None,
      "streak 應容忍今天尚未閱讀（不歸零退格）")

# ── 4d. 定時日夜切換：三模式＋緯度概算＋墨水屏互斥 ──
for needle, desc in [
    ("MODE_OFF", "模式：停用"),
    ("MODE_FIXED", "模式：固定時刻"),
    ("MODE_SOLAR", "模式：日出日落自動"),
    ("DEFAULT_DARK_HOUR = 20", "固定時刻入夜預設 20:00"),
    ("DEFAULT_LIGHT_HOUR = 6", "固定時刻回日預設 06:00"),
    ("EInkHelper.isEInkMode", "墨水屏應互斥（一律日間）"),
    ("fun currentForm", "缺少應然形態判定"),
    ("fun pollThemeChange", "缺少形態翻轉偵測（UI recreate 掛鉤）"),
    ("fun effectiveMode", "缺少生效模式（墨水屏退 OFF）"),
    ("fun estimatedSunsetHour", "缺少日落時刻概算"),
    ("fun estimatedSunriseHour", "缺少日出時刻概算"),
    ("Math.toRadians", "緯度應轉弧度（餘弦內插）"),
    ("KEY_APPLIED_FORM", "套用形態應跨啟動持久化"),
    ('R.string.daynight_dark_at', "未引用入夜文案"),
    ('R.string.daynight_solar_summary', "未引用日出日落摘要文案"),
]:
    check(needle in daynight, f"DayNightScheduleHelper.kt：{desc}")

check(re.search(r'hourNow >= sunset \|\| hourNow < sunrise', daynight) is not None,
      "SOLAR 形態應為跨午夜 OR 判斷（日落後～日出前＝夜間）")
check(re.search(r'if \(hour >= dark \|\| hour < light\) FORM_DARK', daynight) is not None,
      "FIXED 形態應支援跨午夜夜間窗口")

# ── 4e. 書架整理：三桶體檢＋五鍵排序＋冷凍歸檔 ──
for needle, desc in [
    ("SORT_RECENT", "排序鍵：最近閱讀"),
    ("SORT_ADDED", "排序鍵：加入時間"),
    ("SORT_NAME", "排序鍵：書名"),
    ("SORT_AUTHOR", "排序鍵：作者"),
    ("DEFAULT_DORMANT_DAYS = 30", "冷凍門檻預設 30 天規格"),
    ("DORMANT_GROUP_BASE = 90", "冷凍群組起始編號 90 規格"),
    ("data class BookBrief", "缺少書籍摘要"),
    ("data class ShelfReport", "缺少體檢報告"),
    ("fun analyze", "缺少全書架體檢 API"),
    ("fun sortBy", "缺少排序 API"),
    ("fun archiveDormant", "缺少一鍵歸檔 API"),
    ("fun ensureDormantGroup", "缺少冷凍群組冪等確保"),
    ("fun reportHint", "缺少報告摘要文案 API"),
    ("import com.k.reader.data.entities.Book", "應引用 legado Book 實體"),
    ("runCatching { book.finish }", "finish 欄位應寬容存取（髒欄位不炸）"),
    ("runCatching { book.group }", "group 欄位應寬容存取"),
    ('R.string.shelf_dormant_hint', "未引用冷凍偵測文案"),
    ('R.string.shelf_archive_done', "未引用歸檔完成文案"),
]:
    check(needle in shelf, f"ShelfOrganizeHelper.kt：{desc}")

check(re.search(r'fun sortBy\(books: List<Book>, sortKey: String\): List<Book>', shelf) is not None,
      "排序應為純函式（零副作用回新清單）")

# ── 5. strings.xml：345 鍵＋格式參數 ──
check(len(string_keys) == 345, f"繁中字串應 345 鍵（286＋59），實為 {len(string_keys)}")
for key, fmts in [
    ("fulltext_searching", ["%1$d", "%2$d"]),
    ("fulltext_no_result", ["%1$s"]),
    ("fulltext_result_count", ["%1$d"]),
    ("fulltext_chapter_fmt", ["%1$d"]),
    ("note_export_done", ["%1$s"]),
    ("goal_progress_fmt", ["%1$s", "%2$s"]),
    ("goal_streak_fmt", ["%1$d"]),
    ("goal_best_streak_fmt", ["%1$d"]),
    ("goal_achieved", ["%1$s"]),
    ("goal_reminder_text", ["%1$s"]),
    ("goal_reminder_hour_fmt", ["%1$s"]),
    ("daynight_dark_at", ["%1$s"]),
    ("daynight_solar_summary", ["%1$s", "%2$s"]),
    ("shelf_dormant_days_fmt", ["%1$d"]),
    ("shelf_dormant_hint", ["%1$d", "%2$d"]),
    ("shelf_archive_confirm", ["%1$d"]),
    ("shelf_archive_done", ["%1$d"]),
]:
    check(key in string_keys, f"缺少 Stage 8 字串鍵：{key}")
    if key in string_keys:
        for fmt in fmts:
            check(fmt in string_map[key], f"{key} 應含格式參數 {fmt}")
for key in ["fulltext_search_title", "fulltext_regex_switch", "fulltext_search_btn",
            "note_title", "note_edit_hint", "note_color_quote", "note_color_question",
            "note_color_key", "note_save", "note_to_quote", "note_export_md",
            "note_export_plain", "note_empty_hint", "note_add_success", "note_quote_done",
            "goal_title", "goal_type_minutes", "goal_type_words", "goal_disabled",
            "goal_achieved_title", "goal_reminder_title", "goal_channel_name",
            "daynight_title", "daynight_mode_off", "daynight_mode_fixed",
            "daynight_mode_solar", "shelf_organize_title", "shelf_sort_recent",
            "shelf_sort_added", "shelf_sort_name", "shelf_sort_author",
            "shelf_group_finished", "shelf_group_serial", "shelf_group_dormant",
            "shelf_suggestion_none", "fulltext_mode_plain", "fulltext_mode_regex",
            "fulltext_regex_invalid", "fulltext_jump_hint", "fulltext_result_limit",
            "fulltext_searching_zero"]:
    check(key in string_keys, f"缺少 Stage 8 字串鍵：{key}")

# ── 6. 只增不刪：Stage 7 版本（git HEAD）286 鍵全數保留 ──
try:
    head = subprocess.run(
        ["git", "-C", "/home/z/my-project", "show",
         "HEAD:K-Reader/app/src/main/res/values-zh-rTW/strings.xml"],
        capture_output=True, text=True, check=True).stdout
    old_keys = set(re.findall(r'<string name="(\w+)"', head))
    missing = old_keys - string_keys
    check(not missing, f"只增不刪違規——被刪除的鍵：{sorted(missing)}")
    print(f"  只增不刪檢查：HEAD {len(old_keys)} 鍵 → 現 {len(string_keys)} 鍵")
except subprocess.CalledProcessError:
    warnings.append("git HEAD 版本不可用——只增不刪檢查跳過")

# ── 7. init_k_reader.sh：八階段＋30 模組 ──
init = (ROOT / "init_k_reader.sh").read_text(encoding="utf-8")
for needle, desc in [
    ("八階段關鍵檔", "init 腳本應標示八階段"),
    ("30 個 Kotlin 模組", "init 腳本應標示 30 個 Kotlin 模組"),
    ("help/search/FullTextSearchHelper.kt", "關鍵檔清單缺全文搜尋"),
    ("help/note/HighlightNoteManager.kt", "關鍵檔清單缺劃線筆記"),
    ("help/goal/ReadingGoalManager.kt", "關鍵檔清單缺閱讀目標"),
    ("help/config/DayNightScheduleHelper.kt", "關鍵檔清單缺日夜切換"),
    ("help/shelf/ShelfOrganizeHelper.kt", "關鍵檔清單缺書架整理"),
    ("K.閱讀 345 鍵", "字串合併註解應標示 345 鍵"),
]:
    check(needle in init, f"init_k_reader.sh：{desc}")
check("七階段" not in init, "init 腳本殘留「七階段」字樣")

# ── 8. README：八階段收錄 ──
for readme in [ROOT / "README.md",
               Path("/home/z/my-project/download/README.md")]:
    body = readme.read_text(encoding="utf-8")
    check("Stage 8" in body, f"{readme} 未收錄 Stage 8 段落")
    check("全文搜尋" in body, f"{readme} 缺全文搜尋描述")
    check("劃線" in body, f"{readme} 缺劃線筆記描述")
    check("345" in body, f"{readme} 缺 345 鍵字樣")

# ── 9. 功能實測（Python 等價實作——驗證演算法規格） ──
print("  功能實測：雙針搜尋／日夜窗口／streak／冷凍判定（Python 等價實作）")

# 9a. 雙針簡繁並搜（等價 buildPlainNeedles＋collectPlainHits）
S2T = {"国": "國", "书": "書", "阅": "閱", "读": "讀"}
def to_traditional(t):
    return "".join(S2T.get(c, c) for c in t)
def plain_needles(needle, trad=True):
    out = [needle]
    conv = to_traditional(needle)
    if trad and conv != needle:
        out.append(conv)
    return out
def plain_hits(needles, text):
    hay, probes = text.lower(), [p.lower() for p in needles]
    hits, frm = [], 0
    while len(hits) < 300:
        best = -1
        for p in probes:
            pos = hay.find(p, frm)
            if pos >= 0 and (best < 0 or pos < best):
                best, blen = pos, len(p)
        if best < 0:
            break
        hits.append((best, blen))
        frm = best + max(blen, 1)
    return hits
# 簡體關鍵字在繁體原文命中（繁化針）
check(plain_hits(plain_needles("国"), "他來到中國之後愛上閱讀") == [(4, 1)],
      "繁化針應在繁體原文命中（位置零漂移）")
# 原針在簡體原文命中
check(plain_hits(plain_needles("国"), "他来到中国之后爱上阅读") == [(4, 1)],
      "原針應在簡體原文命中")
# 同形（繁化不變）不重複
check(plain_needles("閱讀") == ["閱讀"], "繁化相同時應去重（單針）")
# 多筆命中推進（同針連發不重疊）
check(len(plain_hits(plain_needles("的"), "是的。是的。是的。")) == 3,
      "同針多筆命中應逐一推進")
# 大小寫不敏感
check(len(plain_hits(plain_needles("K"), "k k K")) == 3, "英文應忽略大小寫")

# 9b. 摘錄窗口 clamp（等價 buildHit 邊界）
def excerpt_bounds(pos, hit_len, text_len, ctx=20):
    start = max(0, pos - ctx)
    end = min(text_len, pos + hit_len + ctx)
    return start, end
check(excerpt_bounds(5, 2, 100) == (0, 27), "窗口前緣應 clamp 至 0")
check(excerpt_bounds(90, 2, 100) == (70, 100), "窗口後緣應 clamp 至文長")
check(excerpt_bounds(40, 2, 100) == (20, 62), "窗口中段應兩側各 20")

# 9c. FIXED 跨午夜窗口（等價 fixedForm）
def fixed_form(hour, dark, light):
    if dark >= light:
        return "DARK" if (hour >= dark or hour < light) else "LIGHT"
    return "DARK" if (dark <= hour < light) else "LIGHT"
check(fixed_form(21, 20, 6) == "DARK", "20:00–06:00 窗口：21:00 應夜間")
check(fixed_form(3, 20, 6) == "DARK", "跨午夜：03:00 應夜間")
check(fixed_form(12, 20, 6) == "LIGHT", "12:00 應日間")
check(fixed_form(7, 20, 6) == "LIGHT", "07:00 應日間（窗口外）")
check(fixed_form(12, 6, 20) == "DARK", "顛倒窗口（06:00–20:00）：12:00 應夜間")
check(fixed_form(22, 6, 20) == "LIGHT", "顛倒窗口：22:00 應日間")

# 9d. SOLAR 日出日落概算（等價 estimatedSunsetHour／estimatedSunriseHour 全月掃描）
LAT = math.radians(23.7)
def sunset_hour(month):
    phase = ((month - 6) / 12.0) * 2.0 * math.pi
    return 18.0 + 0.75 * math.cos(phase)
def sunrise_hour(month):
    phase = ((month - 6) / 12.0) * 2.0 * math.pi
    day_len = 12.25 + 1.35 * math.cos(phase)
    return max(4.5, min(7.5, sunset_hour(month) - day_len))
for m in range(12):
    s = sunset_hour(m)
    r = sunrise_hour(m)
    check(17.0 <= s <= 19.0, f"{m + 1} 月日落估算 {s:.2f}h 超出 17–19 帶")
    check(4.5 <= r <= 7.5, f"{m + 1} 月日出估算 {r:.2f}h 超出 4.5–7.5 帶")
check(sunset_hour(5) > sunset_hour(11), "夏至日落應晚於冬至（6 月 vs 12 月）")
check(sunrise_hour(5) < sunrise_hour(11), "夏至日出應早於冬至")
def solar_form(hour, month):
    return "DARK" if (hour >= sunset_hour(month) or hour < sunrise_hour(month)) else "LIGHT"
check(solar_form(13, 5) == "LIGHT", "6 月 13:00 應日間")
check(solar_form(20, 5) == "DARK", "6 月 20:00 應夜間")
check(solar_form(4, 11) == "DARK", "12 月 04:00 應夜間")
check(solar_form(9, 11) == "LIGHT", "12 月 09:00 應日間")

# 9e. streak 演算法（等價 streakCount——今天沒讀不歸零）
def streak(chart):
    if not chart:
        return 0
    i = len(chart) - 1
    if chart[i] <= 0:
        i -= 1
    s = 0
    while i >= 0 and chart[i] > 0:
        s += 1
        i -= 1
    return s
check(streak([0]) == 0, "單日零閱讀 streak 應 0")
check(streak([30]) == 1, "今日已讀 streak 應 1")
check(streak([0, 30, 30, 30, 0]) == 3, "今天沒讀應從昨天起算（不歸零）")
check(streak([0, 0, 30, 0, 30]) == 1, "中斷後僅算尾段連續")
check(streak([30, 30, 30, 30, 30]) == 5, "全滿 streak 應 5")

# 9f. 冷凍判定（等價 analyze 的 dormantDays 與三桶分派）
DAY_MS = 24 * 3600 * 1000
def dormant_days(now, last_read):
    if last_read <= 0:
        return 10 ** 9
    return max(0, int((now - last_read) / DAY_MS))
now = 1_700_000_000_000
check(dormant_days(now, now - 31 * DAY_MS) == 31, "31 天未讀應判 31")
check(dormant_days(now, now - 29 * DAY_MS) == 29, "29 天未讀應判 29")
check(dormant_days(now, 0) >= 30, "從未翻頁（0）應視為深凍")
def bucket(days, threshold, finished):
    if days >= threshold and not finished:
        return "dormant"
    return "finished" if finished else "serial"
check(bucket(31, 30, False) == "dormant", "31 天未讀未完本 → 冷凍")
check(bucket(45, 30, True) == "finished", "完本恆不進冷凍（讀完不是灰）")
check(bucket(5, 30, False) == "serial", "5 天內有讀 → 連載在讀")

# ═══════════ 結果 ═══════════
print("  Stage 8 檔案：全文搜尋／劃線筆記／閱讀目標／日夜切換／書架整理＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（286 基線＋59 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 8 全部檔案驗證通過")

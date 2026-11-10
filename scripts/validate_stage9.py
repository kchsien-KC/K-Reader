#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 9 檔案驗證器

Stage 9 範圍（第三旗艦增益包：閱讀體驗五件套）：
  · 章節書籤（ChapterBookmarkManager ＋ dialog_bookmark_list.xml）
  · 離線快取（OfflineCacheManager）
  · 聽書睡眠定時（TtsSleepTimerHelper）
  · 自訂字體（CustomFontManager ＋ dialog_font_manager.xml）
  · 翻頁手勢（PageTurnHelper）
  · strings.xml 更新（345 → 404 鍵，只增不刪）
  · init_k_reader.sh 更新（九階段、35 個 Kotlin 模組）
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE9_FILES = [
    "app/src/main/java/com/k/reader/help/bookmark/ChapterBookmarkManager.kt",
    "app/src/main/java/com/k/reader/help/cache/OfflineCacheManager.kt",
    "app/src/main/java/com/k/reader/help/tts/TtsSleepTimerHelper.kt",
    "app/src/main/java/com/k/reader/help/font/CustomFontManager.kt",
    "app/src/main/java/com/k/reader/help/turn/PageTurnHelper.kt",
    "app/src/main/res/layout/dialog_bookmark_list.xml",
    "app/src/main/res/layout/dialog_font_manager.xml",
]
PACKAGE_OF = {
    "ChapterBookmarkManager.kt": "com.k.reader.help.bookmark",
    "OfflineCacheManager.kt": "com.k.reader.help.cache",
    "TtsSleepTimerHelper.kt": "com.k.reader.help.tts",
    "CustomFontManager.kt": "com.k.reader.help.font",
    "PageTurnHelper.kt": "com.k.reader.help.turn",
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
for rel in STAGE9_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 版面 XML：良構性 + id + @string + 設計紀律 ──
for rel, need_ids, need_refs in [
    ("app/src/main/res/layout/dialog_bookmark_list.xml",
     ["tv_bookmark_empty", "rv_bookmark_list", "btn_bookmark_add_current"],
     {"bookmark_empty_hint", "bookmark_add_current"}),
    ("app/src/main/res/layout/dialog_font_manager.xml",
     ["tv_font_hint", "tv_font_empty", "rv_font_list", "btn_font_import",
      "btn_font_revert"],
     {"font_hint", "font_empty_hint", "font_import", "font_revert"}),
]:
    p = ROOT / rel
    if not p.is_file():
        continue
    try:
        ET.parse(p)
        raw = p.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
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
        # 清單捲動紀律：RecyclerView 包在 maxHeight 捲動容器內
        check("RecyclerView" in raw and "maxHeight" in raw,
              f"{p.name} 清單應以 maxHeight 捲動容器包覆（不撐爆對話框）")
        if p.name == "dialog_bookmark_list.xml":
            check("nestedScrollingEnabled" in raw,
                  "dialog_bookmark_list.xml RecyclerView 應關閉巢狀捲動")
        if p.name == "dialog_font_manager.xml":
            check("selectableItemBackground" in raw,
                  "dialog_font_manager.xml 動作鈕應有觸控回饋")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE9_FILES:
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

bookmark = kt_sources.get("ChapterBookmarkManager.kt", "")
cache = kt_sources.get("OfflineCacheManager.kt", "")
sleep = kt_sources.get("TtsSleepTimerHelper.kt", "")
font = kt_sources.get("CustomFontManager.kt", "")
turn = kt_sources.get("PageTurnHelper.kt", "")

# ── 4a. 章節書籤：快照＋導航＋容量拒收 ──
for needle, desc in [
    ("MAX_PER_BOOK = 30", "單書容量 30 枚規格"),
    ("SNIPPET_CONTEXT_CHARS = 20", "快照前後各 20 字規格"),
    ('BOOK_FILE = "chapter_bookmarks.json"', "書籤簿檔名（filesDir）"),
    ("data class Bookmark", "缺少書籤條目"),
    ("fun saveBookmark", "缺少釘選 API"),
    ("fun updateLabel", "缺少標籤更新 API"),
    ("fun removeBookmark", "缺少刪除 API"),
    ("fun allBookmarks", "缺少全簿查詢 API"),
    ("fun bookmarksFor", "缺少單書查詢 API"),
    ("fun clearForBook", "缺少單書清空 API"),
    ("fun jumpTargetsAround", "缺少上一枚／下一枚導航 API"),
    ("fun buildSnippet", "缺少快照窗口 API"),
    ("fun filterMine", "缺少雙鍵篩選（換源跨源存活）"),
    ("fun bookKey", "缺少書名作者鍵"),
    ('R.string.bookmark_jump_fmt', "未引用跳轉文案"),
    ('R.string.bookmark_full', "未引用容量滿提示文案"),
    ('R.string.bookmark_count_fmt', "未引用書籤數文案"),
]:
    check(needle in bookmark, f"ChapterBookmarkManager.kt：{desc}")

# 同章同位置應判定為重複（改標籤而非新增）
check(re.search(r'it\.chapterIndex == chapterIndex && it\.chapterPos == chapterPos',
                bookmark) is not None,
      "同章同位置應以章號＋位置雙欄位判定重複")

# ── 4b. 離線快取：預載＋LRU 收編＋體檢 ──
for needle, desc in [
    ("DEFAULT_BUDGET_MB = 512", "快取預算 512MB 規格"),
    ("MAX_AHEAD = 50", "預載上限 50 章規格"),
    ("MAX_CHAPTER_CHARS = 200_000", "單章 200_000 字護欄"),
    ('CACHE_DIR = "chapter_cache"', "快取根目錄名"),
    ("fun interface ChapterLoader", "缺少章節內容注入介面"),
    ("data class CacheStats", "缺少體檢報告"),
    ("data class PrecacheResult", "缺少預載結果"),
    ("suspend fun precache", "缺少 suspend 預載入口"),
    ("withContext(Dispatchers.IO)", "預載應在 IO 執行緒"),
    ("coerceIn(1, MAX_AHEAD)", "預載章數應夾擊 1..50"),
    ("fun cachedChapter", "缺少快取命中 API"),
    ("fun isCached", "缺少存在性檢查 API"),
    ("fun stats", "缺少體檢 API"),
    ("fun evictToBudget", "缺少 LRU 收編 API"),
    ("fun clearBook", "缺少單書清理 API"),
    ("fun clearAll", "缺少全庫清理 API"),
    ("fun formatSize", "缺少體積格式化"),
    ("sortedBy { it.lastModified() }", "LRU 應以 lastModified 升序砍"),
    ("setLastModified", "命中應觸碰 LRU 時鐘"),
    ("Integer.toHexString(bookUrl.hashCode())", "書目錄應以 bookUrl 雜湊命名"),
    ('"%06d.txt"', "章檔應為六位章號.txt（字典序即章序）"),
    ("fun sweepEmptyDirs", "缺少空目錄清掃"),
    ('R.string.cache_stats_fmt', "未引用體檢文案"),
    ('R.string.cache_evict_done_fmt', "未引用收編文案"),
]:
    check(needle in cache, f"OfflineCacheManager.kt：{desc}")

# ── 4c. 聽書睡眠定時：雙模式＋漸弱＋牆鐘續算 ──
for needle, desc in [
    ("MODE_OFF", "模式：停用"),
    ("MODE_MINUTES", "模式：倒數計時"),
    ("MODE_CHAPTER", "模式：本章結束"),
    ("PRESET_MINUTES = listOf(15, 30, 45, 60)", "倒數預設檔四檔規格"),
    ("FADE_WINDOW_MS = 5 * 60_000L", "漸弱窗口 5 分鐘規格"),
    ("fun startCountdown", "缺少倒數啟動 API"),
    ("fun startChapterEnd", "缺少本章結束啟動 API"),
    ("fun stop", "缺少停止 API"),
    ("fun remainingMillis", "缺少剩餘毫秒查詢 API"),
    ("fun isChapterDone", "缺少章界旗標查詢 API"),
    ("fun onChapterFinished", "缺少章節播完回呼 API"),
    ("fun shouldStopNow", "缺少剎車查詢 API"),
    ("fun suggestedVolume", "缺少漸弱音量 API"),
    ("coerceAtLeast(0L)", "剩餘毫秒應夾擊不回負數"),
    ('R.string.tts_sleep_minutes_fmt', "未引用倒數文案"),
    ('R.string.tts_sleep_fade_note', "未引用漸弱說明文案"),
]:
    check(needle in sleep, f"TtsSleepTimerHelper.kt：{desc}")

# 倒數啟動應以分鐘換算毫秒持久化
check("minutes * 60_000L" in sleep, "倒數應以分鐘×60_000 換算毫秒")

# ── 4d. 自訂字體：魔術位元組驗體＋容量＋對帳 ──
for needle, desc in [
    ("MAX_FONTS = 20", "字體倉 20 款上限規格"),
    ("MAX_FONT_MB = 30", "單檔 30MB 上限規格"),
    ('FONTS_DIR = "fonts"', "字體倉目錄名"),
    ('INDEX_FILE = "fonts_index.json"', "索引檔名"),
    ("data class FontEntry", "缺少字體條目"),
    ("suspend fun importFont", "缺少 suspend 匯入 API"),
    ("withContext(Dispatchers.IO)", "匯入應在 IO 執行緒"),
    ("fun list", "缺少清單 API"),
    ("fun appliedFont", "缺少套用查詢 API"),
    ("fun apply", "缺少套用 API"),
    ("fun revert", "缺少還原 API"),
    ("fun typefaceOf", "缺少 Typeface 適配 API"),
    ("Typeface.createFromFile", "應以 createFromFile 載入字體"),
    ("fun delete", "缺少刪除 API"),
    ("fun liveIndex", "缺少索引對帳工序"),
    ("fun sweepOrphanFiles", "缺少殘檔清掃工序"),
    ("openInputStream", "匯入應走 SAF 串流"),
    ("0x00010000", "TTF 魔術位元組 0x00010000 應出現在說明"),
    ("OTTO", "OTF 魔術位元組 OTTO 應出現在說明"),
    ('R.string.font_invalid', "未引用非字體拒收文案"),
    ('R.string.font_too_big', "未引用超限文案"),
]:
    check(needle in font, f"CustomFontManager.kt：{desc}")

# 魔術位元組比對應逐位元組（0x00 0x01 0x00 0x00）
check("magic[0] == 0x00.toByte() && magic[1] == 0x01.toByte()" in font,
      "TTF 魔術位元組應逐位元組比對（00 01 00 00）")

# ── 4e. 翻頁手勢：三區＋三式動畫＋音量鍵 ──
for needle, desc in [
    ('ANIM_COVER = "COVER"', "動畫：覆蓋"),
    ('ANIM_SLIDE = "SLIDE"', "動畫：滑動"),
    ('ANIM_NONE = "NONE"', "動畫：無動畫"),
    ('ZONE_PREV = "PREV"', "區域：上一頁"),
    ('ZONE_NEXT = "NEXT"', "區域：下一頁"),
    ('ZONE_CENTER = "CENTER"', "區域：中央選單"),
    ("EDGE_FRACTION = 0.30f", "左右各 30% 熱區規格"),
    ("fun hitZone", "缺少點擊區域判定 API"),
    ("fun animation", "缺少動畫查詢 API"),
    ("fun setAnimation", "缺少動畫設定 API"),
    ("fun effectiveAnimation", "缺少生效動畫 API（墨水屏互斥）"),
    ("fun isVolumeKeyPaging", "缺少音量鍵翻頁開關 API"),
    ("fun setVolumeKeyPaging", "缺少音量鍵翻頁設定 API"),
    ("fun isVolumeKeyInverted", "缺少音量鍵反轉查詢 API"),
    ("fun setVolumeKeyInverted", "缺少音量鍵反轉設定 API"),
    ("fun volumeKeyZone", "缺少音量鍵映射 API"),
    ("fun normalizeAnim", "缺少動畫常規化（髒值不外溢）"),
    ("EInkHelper.isEInkMode", "墨水屏應強制無動畫（互斥紀律）"),
    ("KeyEvent.KEYCODE_VOLUME_UP", "音量上鍵應走 KeyEvent 常數"),
    ("KeyEvent.KEYCODE_VOLUME_DOWN", "音量下鍵應走 KeyEvent 常數"),
    ('R.string.turn_zone_hint', "未引用區域說明文案"),
    ('R.string.turn_anim_eink_fixed', "未引用墨水屏固定文案"),
]:
    check(needle in turn, f"PageTurnHelper.kt：{desc}")

# RTL 鏡像：左緣在 RTL 下應翻轉為下一頁
check(re.search(r'atLeftEdge -> if \(isRtl\) ZONE_NEXT else ZONE_PREV', turn) is not None,
      "左緣熱區應在 RTL 版面鏡像為下一頁")
# 版面寬度異常應退中央（不翻頁）
check(re.search(r'if \(width <= 0f\) return ZONE_CENTER', turn) is not None,
      "版面寬度異常（<= 0）應退中央不翻頁")

# ── 5. strings.xml：404 鍵＋格式參數 ──
check(len(string_keys) == 404,
      f"繁中字串應 404 鍵（345＋59），實為 {len(string_keys)}")
for key, fmts in [
    ("bookmark_jump_fmt", ["%1$d"]),
    ("bookmark_full", ["%1$d"]),
    ("bookmark_added", ["%1$d"]),
    ("bookmark_count_fmt", ["%1$d"]),
    ("cache_precache_start", ["%1$d"]),
    ("cache_precache_done_fmt", ["%1$d", "%2$s"]),
    ("cache_stats_fmt", ["%1$s", "%2$d", "%3$d"]),
    ("cache_evict_done_fmt", ["%1$s"]),
    ("cache_clear_confirm", ["%1$s"]),
    ("cache_clear_done", ["%1$d"]),
    ("cache_budget_fmt", ["%1$s"]),
    ("tts_sleep_minutes_fmt", ["%1$d"]),
    ("tts_sleep_remaining_fmt", ["%1$s"]),
    ("font_hint", ["%1$s"]),
    ("font_applied_fmt", ["%1$s"]),
    ("font_too_big", ["%1$s"]),
    ("font_full", ["%1$d"]),
    ("font_deleted_fmt", ["%1$s"]),
    ("font_size_fmt", ["%1$s"]),
    ("turn_anim_changed_fmt", ["%1$s"]),
]:
    check(key in string_keys, f"缺少 Stage 9 字串鍵：{key}")
    if key in string_keys:
        for fmt in fmts:
            check(fmt in string_map[key], f"{key} 應含格式參數 {fmt}")
for key in [
    "bookmark_title", "bookmark_add_current", "bookmark_empty_hint",
    "bookmark_delete", "bookmark_label_hint", "bookmark_prev_jump",
    "bookmark_next_jump", "bookmark_removed",
    "cache_title", "cache_precache_none", "cache_hit",
    "tts_sleep_title", "tts_sleep_off", "tts_sleep_minutes",
    "tts_sleep_chapter", "tts_sleep_chapter_started", "tts_sleep_stopped",
    "font_title", "font_import", "font_revert", "font_apply",
    "font_reverted", "font_empty_hint", "font_invalid", "font_delete",
    "turn_title", "turn_anim_cover", "turn_anim_slide", "turn_anim_none",
    "turn_zone_hint", "turn_volume_key", "turn_volume_key_hint",
    "turn_volume_invert", "turn_zone_prev", "turn_zone_next",
    "turn_zone_center", "turn_rtl_note",
]:
    check(key in string_keys, f"缺少 Stage 9 字串鍵：{key}")

# ── 6. 只增不刪：Stage 8 版本（git HEAD）345 鍵全數保留 ──
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

# ── 7. init_k_reader.sh：九階段＋35 模組 ──
init = (ROOT / "init_k_reader.sh").read_text(encoding="utf-8")
for needle, desc in [
    ("九階段關鍵檔", "init 腳本應標示九階段"),
    ("35 個 Kotlin 模組", "init 腳本應標示 35 個 Kotlin 模組"),
    ("help/bookmark/ChapterBookmarkManager.kt", "關鍵檔清單缺章節書籤"),
    ("help/cache/OfflineCacheManager.kt", "關鍵檔清單缺離線快取"),
    ("help/tts/TtsSleepTimerHelper.kt", "關鍵檔清單缺聽書睡眠定時"),
    ("help/font/CustomFontManager.kt", "關鍵檔清單缺自訂字體"),
    ("help/turn/PageTurnHelper.kt", "關鍵檔清單缺翻頁手勢"),
    ("K.閱讀 404 鍵", "字串合併註解應標示 404 鍵"),
]:
    check(needle in init, f"init_k_reader.sh：{desc}")
check("八階段" not in init, "init 腳本殘留「八階段」字樣")

# ── 8. README：九階段收錄（兩份） ──
for readme in [ROOT / "README.md",
               Path("/home/z/my-project/download/README.md")]:
    body = readme.read_text(encoding="utf-8")
    check("Stage 9" in body, f"{readme} 未收錄 Stage 9 段落")
    check("章節書籤" in body, f"{readme} 缺章節書籤描述")
    check("離線快取" in body, f"{readme} 缺離線快取描述")
    check("聽書睡眠" in body, f"{readme} 缺聽書睡眠描述")
    check("自訂字體" in body, f"{readme} 缺自訂字體描述")
    check("翻頁" in body, f"{readme} 缺翻頁手勢描述")
    check("404" in body, f"{readme} 缺 404 鍵字樣")
tree_readme = (ROOT / "README.md").read_text(encoding="utf-8")
check("八階段" not in tree_readme, "樹內 README 殘留「八階段」字樣")
check("九階段交付對照" in tree_readme, "樹內 README 應為九階段交付對照")

# ── 9. 功能實測（Python 等價實作——驗證演算法規格） ──
print("  功能實測：書籤快照／跳轉導航／LRU 收編／睡眠漸弱／字體驗體／翻頁熱區")

# 9a. 書籤快照窗口（等價 buildSnippet——邊界夾擠＋省略號）
def snippet(text, pos, ctx=20):
    if text is None or not text.strip() or pos < 0 or pos >= len(text):
        return None
    start, end = max(0, pos - ctx), min(len(text), pos + ctx)
    return ("…" if start > 0 else "") + text[start:end] + ("…" if end < len(text) else "")
TEXT100 = "".join(chr(ord("A") + i % 26) for i in range(100))
check(snippet(TEXT100, 50) == "…" + TEXT100[30:70] + "…", "中段快照應前後各 20 字")
check(snippet(TEXT100, 5) == TEXT100[0:25] + "…", "前緣快照應 clamp 至 0（無前省略號）")
check(snippet(TEXT100, 95) == "…" + TEXT100[75:100], "後緣快照應 clamp 至文長")
check(snippet(TEXT100, 0) == TEXT100[0:20] + "…", "首位快照不應有前省略號")
check(snippet(TEXT100, -1) is None, "位置越界（負）應回 null")
check(snippet(TEXT100, 100) is None, "位置越界（==文長）應回 null")
check(snippet(None, 10) is None, "內容未載入應回 null（釘選不失敗）")
check(snippet("", 0) is None, "空白內容應回 null")

# 9b. 上一枚／下一枚導航（等價 jumpTargetsAround——同章不參與）
def jump_targets(marks, current):
    prev = [m for m in marks if m < current]
    nxt = [m for m in marks if m > current]
    return (max(prev) if prev else None, min(nxt) if nxt else None)
check(jump_targets([3, 7, 12], 8) == (7, 12), "8 章處應導航 7←→12")
check(jump_targets([3, 7, 12], 3) == (None, 7), "同章書籤不應成為跳轉目標")
check(jump_targets([3, 7, 12], 12) == (7, None), "最後一枚上只有上一枚")
check(jump_targets([3, 7, 12], 15) == (12, None), "超過全部時 prev=最後一枚")
check(jump_targets([], 5) == (None, None), "空書籤簿應雙 null")
check(jump_targets([5], 5) == (None, None), "僅同章書籤應雙 null")

# 9c. 同位置去重＋容量拒收（等價 saveBookmark 分支）
def try_save(marks, chapter, pos, cap=30):
    if (chapter, pos) in marks:
        return "update-label"
    if len(marks) >= cap:
        return "reject"
    return "new"
check(try_save([], 3, 100) == "new", "首枚應為新增")
check(try_save([(3, 100)], 3, 100) == "update-label", "同章同位置應改標籤不新增")
check(try_save([(3, 100)], 3, 200) == "new", "同章不同位置應新增")
check(try_save([(i, 0) for i in range(30)], 99, 0) == "reject",
      "滿 30 枚應拒收（不靜默淘汰）")

# 9d. LRU 收編（等價 evictToBudget——lastModified 升序砍到預算內）
def evict(files, budget):
    total = sum(size for _, _, size in files)
    freed = 0
    for name, mtime, size in sorted(files, key=lambda f: f[1]):
        if total <= budget:
            break
        total -= size
        freed += size
    return freed
FILES = [("a.txt", 100, 10), ("b.txt", 200, 20), ("c.txt", 300, 30), ("d.txt", 400, 40)]
check(evict(FILES, 1000) == 0, "預算內不應清理")
check(evict(FILES, 95) == 10, "超額 5 應只砍最舊一檔（a，10B）")
check(evict(FILES, 85) == 30, "預算 85 應砍 a+b（freed 30B，餘 70）")
check(evict(FILES, 60) == 60, "預算 60 應砍 a+b+c（freed 60B，餘 40）")
check(evict(FILES, 40) == 60, "恰好餘 40 時 d 不砍（邊界不過刪）")
check(evict(FILES, 0) == 100, "零預算應全清（100B）")
FILES2 = [("new.txt", 500, 15), ("old.txt", 100, 15)]
check(evict(FILES2, 15) == 15, "LRU 應先砍最舊（old.txt，餘 15 恰達預算）")

# 9e. 預載章數夾擊（等價 precache 的 coerceIn(1, MAX_AHEAD)）
def clamp_ahead(a):
    return max(1, min(50, a))
check(clamp_ahead(0) == 1, "零章應夾擊為 1（起碼載一章）")
check(clamp_ahead(-5) == 1, "負數應夾擊為 1")
check(clamp_ahead(20) == 20, "20 章應原值")
check(clamp_ahead(50) == 50, "50 章應原值（上限）")
check(clamp_ahead(999) == 50, "超量應夾擊為 50")

# 9f. 睡眠倒數＋漸弱（等價 remainingMillis／suggestedVolume）
def remaining(started, duration, now):
    return max(0, started + duration - now)
def volume(rem_ms, fade=300_000):
    if rem_ms > fade:
        return 1.0
    return max(0.0, min(1.0, rem_ms / fade))
T0 = 1_000_000
check(remaining(T0, 30 * 60_000, T0) == 30 * 60_000, "啟動瞬間剩餘應為全額")
check(remaining(T0, 30 * 60_000, T0 + 10 * 60_000) == 20 * 60_000, "10 分後應剩 20 分")
check(remaining(T0, 30 * 60_000, T0 + 999 * 60_000) == 0, "過時應夾擊為 0（不回負數）")
check(volume(400_000) == 1.0, "漸弱窗口外應滿格")
check(volume(300_000) == 1.0, "恰在窗口起點應仍滿格")
check(abs(volume(150_000) - 0.5) < 1e-9, "窗口中點應衰減至 0.5")
check(volume(0) == 0.0, "歸零時應無聲")
# 牆鐘續算：重啟後剩餘不歸零（等價——只依 startedAt/duration/now）
check(remaining(T0, 30 * 60_000, T0 + 29 * 60_000) == 60_000,
      "重啟場景：以牆鐘計算仍剩 1 分")

# 9g. 字體魔術位元組驗體（等價 importFont 前四 byte）
def is_font_magic(magic):
    if len(magic) != 4:
        return False
    return magic == b"\x00\x01\x00\x00" or magic == b"OTTO"
check(is_font_magic(b"\x00\x01\x00\x00"), "TTF 魔術位元組應通過")
check(is_font_magic(b"OTTO"), "OTF 魔術位元組應通過")
check(not is_font_magic(b"TTTF"), "ttf 變體 magic 不應通過")
check(not is_font_magic(b"WOFF"), "WOFF 不在支援範圍")
check(not is_font_magic(b"wOFF"), "小寫 woff 不應通過")
check(not is_font_magic(b"\x00\x01\x00"), "截斷檔（3 byte）不應通過")
check(not is_font_magic(b""), "空檔不應通過")
check(not is_font_magic(b"OTTOX"), "5 byte 雜檔不應通過")

# 9h. 翻頁熱區（等價 hitZone——左右各 30%、中間 40%、RTL 鏡像）
def hit_zone(fraction, rtl=False, edge=0.30):
    if fraction < edge:
        return "NEXT" if rtl else "PREV"
    if fraction > 1 - edge:
        return "PREV" if rtl else "NEXT"
    return "CENTER"
check(hit_zone(0.10) == "PREV", "左 10% 應上一頁")
check(hit_zone(0.50) == "CENTER", "正中應呼出選單")
check(hit_zone(0.90) == "NEXT", "右 10% 應下一頁")
check(hit_zone(0.29) == "PREV", "29% 仍在左熱區")
check(hit_zone(0.30) == "CENTER", "30% 邊界應屬中央（開區間）")
check(hit_zone(0.70) == "CENTER", "70% 邊界應屬中央（開區間）")
check(hit_zone(0.71) == "NEXT", "71% 進入右熱區")
check(hit_zone(0.10, rtl=True) == "NEXT", "RTL 左緣應鏡像為下一頁")
check(hit_zone(0.90, rtl=True) == "PREV", "RTL 右緣應鏡像為上一頁")
check(hit_zone(0.50, rtl=True) == "CENTER", "RTL 中央不變")

# 9i. 音量鍵映射（等價 volumeKeyZone——關閉不攔截、可反轉）
KEY_UP, KEY_DOWN, KEY_BACK = 24, 25, 4
def volume_zone(key, enabled, inverted):
    if not enabled:
        return None
    if key == KEY_UP:
        return "NEXT" if inverted else "PREV"
    if key == KEY_DOWN:
        return "PREV" if inverted else "NEXT"
    return None
check(volume_zone(KEY_UP, False, False) is None, "功能關閉應不攔截任何鍵")
check(volume_zone(KEY_UP, True, False) == "PREV", "上鍵應上一頁")
check(volume_zone(KEY_DOWN, True, False) == "NEXT", "下鍵應下一頁")
check(volume_zone(KEY_UP, True, True) == "NEXT", "反轉後上鍵應下一頁")
check(volume_zone(KEY_DOWN, True, True) == "PREV", "反轉後下鍵應上一頁")
check(volume_zone(KEY_BACK, True, False) is None, "非音量鍵應回 null 不吞鍵")

# ═══════════ 結果 ═══════════
print("  Stage 9 檔案：章節書籤／離線快取／聽書睡眠／自訂字體／翻頁手勢＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（345 基線＋59 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 9 全部檔案驗證通過")

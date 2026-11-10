#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 5 檔案驗證器

Stage 5 範圍（README 規格）：
  · 淨化規則包（CleanRuleManager）
  · 回收站（RecycleBinManager）
  · 金句卡（GoldenQuoteManager）
  · 閱讀統計（ReadStatisticsManager）
  · E-Ink 專屬模式（EInkHelper）
  · WebDAV 雲端備份（WebDavHelper ＋ WorkManager 自動增量）
  · AI 閱讀助手（AiAssistantHelper ＋ dialog_ai_assistant.xml）
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE5_FILES = [
    "app/src/main/java/com/k/reader/help/storage/RecycleBinManager.kt",
    "app/src/main/java/com/k/reader/help/content/CleanRuleManager.kt",
    "app/src/main/java/com/k/reader/help/quote/GoldenQuoteManager.kt",
    "app/src/main/java/com/k/reader/help/statistics/ReadStatisticsManager.kt",
    "app/src/main/java/com/k/reader/help/config/EInkHelper.kt",
    "app/src/main/java/com/k/reader/help/webdav/WebDavHelper.kt",
    "app/src/main/java/com/k/reader/help/ai/AiAssistantHelper.kt",
    "app/src/main/res/layout/dialog_ai_assistant.xml",
]
PACKAGE_OF = {
    "RecycleBinManager.kt": "com.k.reader.help.storage",
    "CleanRuleManager.kt": "com.k.reader.help.content",
    "GoldenQuoteManager.kt": "com.k.reader.help.quote",
    "ReadStatisticsManager.kt": "com.k.reader.help.statistics",
    "EInkHelper.kt": "com.k.reader.help.config",
    "WebDavHelper.kt": "com.k.reader.help.webdav",
    "AiAssistantHelper.kt": "com.k.reader.help.ai",
}
FORBIDDEN = ["TODO", "FIXME", "XXX", "待補充", "【"]

errors, warnings = [], []


def strip_kotlin(src: str) -> str:
    """單趟狀態機：正確剝除 Kotlin 註解／字串／字元字面值，僅留程式碼骨架。"""
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
                i = (j + 1) if j < n else n
            out.append("S")
        elif c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = (j + 1) if j < n else n
            out.append("C")
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
for rel in STAGE5_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 500, f"檔案過小（可疑不完整）：{rel}")

# ── 2. dialog_ai_assistant.xml 良構性 + id + @string + 設計紀律 ──
xml_path = ROOT / "app/src/main/res/layout/dialog_ai_assistant.xml"
if xml_path.is_file():
    try:
        ET.parse(xml_path)
        raw = xml_path.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["et_ai_input", "btn_ai_summary", "btn_ai_classical",
                     "btn_ai_explain", "row_ai_status", "tv_ai_status",
                     "tv_ai_output", "btn_ai_copy", "progress_ai"]:
            check(need in ids, f"dialog_ai_assistant.xml 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys,
                  f"dialog_ai_assistant.xml 引用了不存在的 string：{ref}")
        # 設計紀律：狀態列預設 gone＋RTL 友善＋純主題屬性＋零硬編碼色
        check('android:visibility="gone"' in raw,
              "dialog_ai_assistant.xml 狀態列應預設 visibility=gone（零干擾）")
        check("paddingStart" in raw and "marginStart" in raw,
              "dialog_ai_assistant.xml 應使用 RTL 友善 paddingStart/marginStart")
        check("?attr/colorPrimary" in raw and "?attr/colorOnPrimary" in raw,
              "dialog_ai_assistant.xml 應以主題屬性著色（日夜間自動適配）")
        check(re.search(r'android:textColor="#', raw) is None,
              "dialog_ai_assistant.xml 不應硬編碼顏色（主題屬性紀律）")
        expect_refs = {"ai_summary", "ai_translate_classical", "ai_explain",
                       "ai_running", "copy"}
        check(expect_refs <= set(re.findall(r'@string/(\w+)', raw)),
              "dialog_ai_assistant.xml 應引用三動作＋狀態＋複製文案")
    except ET.ParseError as e:
        errors.append(f"dialog_ai_assistant.xml XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE5_FILES:
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
        if bad in ("【",):
            # 【】僅掃程式碼骨架：字串內容中的【（如正則比對廣告標記
            # 【廣告】）屬功能資料，非佔位符；程式碼位置的【才是未清 placeholder
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

    # R.string / R.id 引用存在性（以剝殼後程式骨架掃描）
    layout_raw = xml_path.read_text(encoding="utf-8") if xml_path.is_file() else ""
    layout_ids = set(re.findall(r'@\+id/(\w+)', layout_raw))
    for ref in set(re.findall(r'R\.string\.(\w+)', stripped)):
        check(ref in string_keys, f"{name} 引用了不存在的 R.string.{ref}")
    for ref in set(re.findall(r'R\.id\.(\w+)', stripped)):
        check(ref in layout_ids,
              f"{name} 引用了 dialog_ai_assistant.xml 不存在的 R.id.{ref}")

recycle = kt_sources.get("RecycleBinManager.kt", "")
clean = kt_sources.get("CleanRuleManager.kt", "")
quote = kt_sources.get("GoldenQuoteManager.kt", "")
stats = kt_sources.get("ReadStatisticsManager.kt", "")
eink = kt_sources.get("EInkHelper.kt", "")
webdav = kt_sources.get("WebDavHelper.kt", "")
ai = kt_sources.get("AiAssistantHelper.kt", "")

# ── 4a. 回收站：30 天規格＋四形態＋冪等寫回 ──
for needle, desc in [
    ("RETENTION_DAYS = 30", "回收站保留天數應為 30 天規格值"),
    ("MAX_ENTRIES = 200", "回收站容量上限應為 200 條"),
    ("suspend fun recycle", "缺少進站 API（刪除前存證）"),
    ("suspend fun restore", "缺少還原 API"),
    ("suspend fun clearAll", "缺少一鍵清空 API"),
    ("TYPE_BOOK", "缺少書籍形態"),
    ("TYPE_BOOK_SOURCE", "缺少書源形態"),
    ("TYPE_RSS_SOURCE", "缺少訂閱源形態"),
    ("TYPE_PURIFY_RULE", "缺少淨化規則形態"),
    ("insertOrUpdate", "還原未走冪等寫回（insertOrUpdate）"),
    ("AtomicLong", "未使用並發安全的 id 序號（AtomicLong）"),
    ("purgeExpired 或過濾式自潔", ""),
]:
    if needle == "purgeExpired 或過濾式自潔":
        check("now - it.deletedAt < retentionMs" in recycle,
              "回收站缺少逾期自潔邏輯")
    else:
        check(needle in recycle, f"RecycleBinManager.kt：{desc}")
for key in ["purify_recycle", "purify_recycle_restore",
            "purify_recycle_empty", "success", "error", "empty"]:
    check(f"R.string.{key}" in recycle,
          f"RecycleBinManager.kt 未引用 R.string.{key}")

# ── 4b. 淨化規則包：512 上限＋預編譯快取＋台灣包＋回收站聯動 ──
for needle, desc in [
    ("MAX_PATTERN_LENGTH = 512", "單規則 pattern 上限應為 512 規格值"),
    ("ConcurrentHashMap<String, Regex>", "缺少正則預編譯快取容器"),
    ("fun builtinTwPack", "缺少內建台灣規則包"),
    ("fun ensureTwPackInstalled", "缺少台灣包冪等安裝"),
    ("fun setTwPackEnabled", "缺少台灣包整包啟停"),
    ("fun apply(", "缺少淨化主流程"),
    ("fun deleteRule", "缺少規則刪除 API"),
    ("fun importRule", "缺少規則匯入 API（回收站還原消費）"),
    ("fun importRulesJson", "缺少規則 JSON 匯入"),
    ("fun exportRulesJson", "缺少規則 JSON 匯出"),
    ("SCOPE_GLOBAL", "缺少全域綁定範圍"),
    ("SCOPE_BOOK", "缺少書名綁定範圍"),
    ("SCOPE_SOURCE", "缺少書源綁定範圍"),
    ("RecycleBinManager.recycle", "刪除規則未進回收站"),
    ("RecycleBinManager.TYPE_PURIFY_RULE", "回收站形態標記未對齊"),
    ('Regex(pattern)', "預編譯快取未真正編譯 Regex"),
]:
    check(needle in clean, f"CleanRuleManager.kt：{desc}")
# 台灣包規則數量（≥ 11 條 id 前綴）
tw_rules = re.findall(r'twRule\(\s*"tw-pack-[\w-]+"', clean)
check(len(tw_rules) >= 11,
      f"內建台灣規則包應至少 11 條，實為 {len(tw_rules)}")
for key in ["purify_title", "purify_tw_pack", "purify_scope_book",
            "purify_scope_source", "purify_regex_precompiled"]:
    check(f"R.string.{key}" in clean,
          f"CleanRuleManager.kt 未引用 R.string.{key}")
# 跨管理器：回收站還原 → 淨化規則匯入
check("CleanRuleManager.importRule" in recycle,
      "RecycleBinManager 還原未接回 CleanRuleManager.importRule")

# ── 4c. 金句卡：1080×1620＋程序化繪製＋媒體庫＋FileProvider ──
for needle, desc in [
    ("CARD_WIDTH = 1080", "卡片寬度應為 1080 規格值"),
    ("CARD_HEIGHT = 1620", "卡片高度應為 1620 規格值"),
    ("MAX_QUOTE_CHARS = 400", "單條金句字數上限應為 400 規格值"),
    ("Bitmap.createBitmap", "缺少程序化點陣創建"),
    ("StaticLayout", "缺少自動換行排版"),
    ("Typeface.SERIF", "文青卡片應用襯線字體"),
    ("MediaStore.Images.Media.RELATIVE_PATH", "未走 MediaStore 落圖庫"),
    ("DIRECTORY_PICTURES", "圖庫目標應為 Pictures"),
    ('FILE_PROVIDER_AUTHORITY = "com.k.reader.filen"', "FileProvider authority 應為 com.k.reader.filen"),
    ("FileProvider.getUriForFile", "分享未走 FileProvider"),
    ("Intent.ACTION_SEND", "缺少系統分享動作"),
    ("fun saveQuote", "缺少金句收藏 API"),
    ("fun allQuotes", "缺少金句簿列表 API"),
    ("fun deleteQuote", "缺少金句刪除 API"),
    ("fun generateCardBitmap", "缺少卡片渲染 API"),
    ("fun saveCardToGallery", "缺少圖庫落地 API"),
    ("fun shareCard", "缺少分享 API"),
]:
    check(needle in quote, f"GoldenQuoteManager.kt：{desc}")
for key in ["quote_card", "quote_card_generate", "quote_card_share",
            "quote_card_saved", "quote_card_select_hint", "app_slogan"]:
    check(f"R.string.{key}" in quote,
          f"GoldenQuoteManager.kt 未引用 R.string.{key}")

# ── 4d. 閱讀統計：365/14/60 規格＋四期匯總＋格式化 ──
for needle, desc in [
    ("RETENTION_DAYS = 365", "統計保留天數應為 365 規格值"),
    ("CHART_DAYS = 14", "圖表天數應為 14 規格值"),
    ("MAX_SESSION_MINUTES = 60", "單次時長上限應為 60 分鐘規格值"),
    ("WEEK_WINDOW_DAYS = 7", "本週滾動窗口應為 7 天"),
    ("MONTH_WINDOW_DAYS = 30", "本月滾動窗口應為 30 天"),
    ("fun onReadingSession", "缺少閱讀時段落帳 API"),
    ("fun summaryToday", "缺少今日匯總"),
    ("fun summaryWeek", "缺少本週匯總"),
    ("fun summaryMonth", "缺少本月匯總"),
    ("fun summaryTotal", "缺少累計匯總"),
    ("fun dailyChart", "缺少長條圖資料 API"),
    ("fun formatDuration", "缺少時長格式化"),
    ("fun formatWords", "缺少字數格式化"),
    ("book?.finish == true", "完本登記未讀取 book.finish"),
]:
    check(needle in stats, f"ReadStatisticsManager.kt：{desc}")
for key in ["stats_title", "stats_today", "stats_week", "stats_month",
            "stats_total", "stats_duration", "stats_words", "stats_finish",
            "stats_hour_fmt", "stats_minute_fmt", "stats_words_fmt",
            "stats_chart_daily"]:
    check(f"R.string.{key}" in stats,
          f"ReadStatisticsManager.kt 未引用 R.string.{key}")

# ── 4e. E-Ink：動畫畫歸零＋純黑白＋裝置偵測 ──
for needle, desc in [
    ("setWindowAnimations(0)", "視窗動畫未歸零"),
    ("fun isEInkDevice", "缺少電子紙裝置偵測"),
    ("fun initOnFirstLaunch", "缺少首次啟動自動開啟"),
    ("fun pureBlackWhite", "缺少高對比純黑白配對"),
    ("fun recommendedPageModeLabel", "缺少翻頁方式建議（無動畫）"),
    ("Color.WHITE to Color.BLACK", "純黑白配對應為白底黑字"),
]:
    check(needle in eink, f"EInkHelper.kt：{desc}")
for key in ["eink_mode", "eink_on", "eink_off", "eink_no_animation",
            "page_mode_none"]:
    check(f"R.string.{key}" in eink, f"EInkHelper.kt 未引用 R.string.{key}")

# ── 4f. WebDAV：五動詞＋三件套＋10 份／12 小時＋WorkManager ──
for needle, desc in [
    ("Credentials.basic", "缺少 Basic 驗證"),
    ('"PROPFIND"', "缺少 PROPFIND（清單／探測）"),
    ('"MKCOL"', "缺少 MKCOL（建目錄）"),
    (".put(", "缺少 PUT（上傳）"),
    ('.delete()', "缺少 DELETE（清理舊備份）"),
    ("SourceRateLimiter.awaitPermit", "WebDAV 請求未經單源限流"),
    ("UserAgentPool.browserHeaders", "WebDAV 請求未用瀏覽器級標頭"),
    ("SslTrustHelper.isSslError", "未接入 SSL 錯誤分類（NAS 自簽日常）"),
    ("SslTrustHelper.tolerantClient", "未接入 SSL 寬容通道重試"),
    ("KEEP_BACKUP_COUNT = 10", "雲端保留份數應為 10 規格值"),
    ("AUTO_BACKUP_INTERVAL_HOURS = 12", "自動備份週期應為 12 小時規格值"),
    ("CoroutineWorker", "自動增量未走 WorkManager CoroutineWorker"),
    ("PeriodicWorkRequestBuilder", "缺少週期任務構建"),
    ("enqueueUniquePeriodicWork", "未以唯一週期任務冪等排程"),
    ("NetworkType.CONNECTED", "週期任務未約束網路可用"),
    ("ZipOutputStream", "備份未走 zip 打包"),
    ("ZipInputStream", "還原未走 zip 解包"),
    ("MediaStore.Downloads", "本地備份未走 MediaStore（Download/KReader）"),
    ("fun testConnection", "缺少連線測試 API"),
    ("fun backupToCloud", "缺少雲端備份 API"),
    ("fun restoreFromCloud", "缺少雲端還原 API"),
    ("fun localBackup", "缺少本地備份 API"),
    ("suspend fun buildPayload", "缺少備份裝載構建（DAO 接縫區）"),
    ("insertOrUpdate", "還原未走冪等寫回"),
]:
    check(needle in webdav, f"WebDavHelper.kt：{desc}")
for key in ["webdav_config", "webdav_url", "webdav_account", "webdav_password",
            "webdav_auto_increment", "webdav_backup_now", "webdav_restore_now",
            "webdav_sync_progress", "webdav_test_ok", "webdav_test_fail",
            "backup_multi_device", "success", "error", "empty"]:
    check(f"R.string.{key}" in webdav,
          f"WebDavHelper.kt 未引用 R.string.{key}")

# ── 4g. AI 助手：OpenAI 相容＋三件套＋60s／1024＋對話框綁定 ──
for needle, desc in [
    ("REQUEST_TIMEOUT_MS = 60_000L", "AI 呼叫逾時應為 60 秒規格值"),
    ("MAX_OUTPUT_TOKENS = 1024", "單次回應 token 上限應為 1024 規格值"),
    ("/v1/chat/completions", "端點未正規化為 chat/completions"),
    ('"Bearer ${config.apiKey}"', "缺少 Bearer 鑑權標頭"),
    ('"model" to config.model', "請求體缺少 model 欄位"),
    ('"max_tokens" to MAX_OUTPUT_TOKENS', "請求體未套用 token 上限"),
    ("JsonParser.parseString", "回應解析未走結構化 JSON"),
    ('"content"', "回應解析未取 message.content"),
    ("SourceRateLimiter.awaitPermit", "AI 呼叫未經單源限流"),
    ("UserAgentPool.browserHeaders", "AI 請求未用瀏覽器級標頭"),
    ("SslTrustHelper.isSslError", "未接入 SSL 錯誤分類"),
    ("SslTrustHelper.tolerantClient", "未接入 SSL 寬容通道重試"),
    ("ACTION_SUMMARY", "缺少段落摘要動作"),
    ("ACTION_CLASSICAL", "缺少文言文白話翻譯動作"),
    ("ACTION_EXPLAIN", "缺少深度解讀動作"),
    ("R.layout.dialog_ai_assistant", "未綁定 AI 對話框布局"),
    ("R.id.et_ai_input", "未綁定輸入區 id"),
    ("R.id.btn_ai_summary", "未綁定摘要按鈕 id"),
    ("R.id.btn_ai_classical", "未綁文言翻譯按鈕 id"),
    ("R.id.btn_ai_explain", "未綁深度解讀按鈕 id"),
    ("R.id.tv_ai_output", "未綁結果區 id"),
    ("R.id.row_ai_status", "未綁狀態列 id"),
    ("R.id.btn_ai_copy", "未綁一鍵複製 id"),
    ("ClipData.newPlainText", "複製未走剪貼簿 ClipData"),
]:
    check(needle in ai, f"AiAssistantHelper.kt：{desc}")
for key in ["ai_assistant", "ai_summary", "ai_translate_classical", "ai_explain",
            "ai_api_config", "ai_api_url", "ai_api_key", "ai_api_model",
            "ai_running", "ai_error", "cancel", "copied"]:
    check(f"R.string.{key}" in ai,
          f"AiAssistantHelper.kt 未引用 R.string.{key}")

# ── 5. 規格一致性（字串規格 ↔ 常數） ──
hour_val = string_map.get("stats_hour_fmt", "")
check("%1$d" in hour_val and "%2$d" in hour_val,
      "字串 stats_hour_fmt 應為兩參數格式（小時＋分）")
check("%1$d" in string_map.get("stats_minute_fmt", ""),
      "字串 stats_minute_fmt 應為單參數格式")
check("%1$s" in string_map.get("stats_words_fmt", ""),
      "字串 stats_words_fmt 應為 %1$s 萬字格式")
check("%1$s" in string_map.get("webdav_sync_progress", ""),
      "字串 webdav_sync_progress 應為單參數格式")

# ── 6. 格式參數個數抽查（呼叫實參） ──
check(re.search(
    r'R\.string\.stats_hour_fmt\s*,\s*total\s*/\s*60L\s*,\s*total\s*%\s*60L',
    stats) is not None,
    "stats_hour_fmt（%1$d、%2$d）呼叫應帶小時／分兩個參數")
check(re.search(
    r'R\.string\.stats_minute_fmt\s*,\s*total\)', stats) is not None,
    "stats_minute_fmt（%1$d）呼叫應帶分鐘參數")
check(re.search(
    r'R\.string\.stats_words_fmt\s*,', stats) is not None,
    "stats_words_fmt（%1$s）呼叫應帶字數參數")
check(re.search(
    r'R\.string\.webdav_sync_progress\s*,\s*\w+', webdav) is not None,
    "webdav_sync_progress（%1$s）呼叫應帶進度說明參數")

# ── 7. 跨階段整合：三件套消費統計（WebDAV／AI 兩網路模組） ──
for name, src in [("WebDavHelper.kt", webdav), ("AiAssistantHelper.kt", ai)]:
    for needle in ["SourceRateLimiter.awaitPermit",
                   "UserAgentPool.browserHeaders",
                   "SslTrustHelper.isSslError",
                   "SslTrustHelper.tolerantClient"]:
        check(needle in src, f"{name} 未消費三件套：{needle}")

# ── 結果 ──
print(f"  字串資源：{len(string_keys)} 鍵")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 5 全部檔案驗證通過")

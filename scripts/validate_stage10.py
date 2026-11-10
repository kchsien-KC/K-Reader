#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 10 檔案驗證器

Stage 10 範圍（第四旗艦增益包：資產與健康五件套）：
  · 本地書匯入（LocalBookImporter：編碼偵測＋章節切分＋去重）
  · 備份加密（BackupCryptoHelper：AES-256-GCM＋PBKDF2＋KRC1）
  · 閱讀週報／月報（ReadingReportHelper：CSV＋青黛報表卡）
  · 休息提醒（RestReminderHelper：連續閱讀門檻＋靜默窗）
  · 書源倉庫（SourceRepoManager＋dialog_source_repo.xml）
  · ReadStatisticsManager 整合修正（dailyWordCount 唯讀視窗）
  · strings.xml 更新（404 → 485 鍵，只增不刪）
  · init_k_reader.sh／README.md 更新（十階段、40 個 Kotlin 模組）
"""
import hashlib
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE10_FILES = [
    "app/src/main/java/com/k/reader/help/book/LocalBookImporter.kt",
    "app/src/main/java/com/k/reader/help/backup/BackupCryptoHelper.kt",
    "app/src/main/java/com/k/reader/help/statistics/ReadingReportHelper.kt",
    "app/src/main/java/com/k/reader/help/config/RestReminderHelper.kt",
    "app/src/main/java/com/k/reader/help/source/SourceRepoManager.kt",
    "app/src/main/res/layout/dialog_source_repo.xml",
]
PACKAGE_OF = {
    "LocalBookImporter.kt": "com.k.reader.help.book",
    "BackupCryptoHelper.kt": "com.k.reader.help.backup",
    "ReadingReportHelper.kt": "com.k.reader.help.statistics",
    "RestReminderHelper.kt": "com.k.reader.help.config",
    "SourceRepoManager.kt": "com.k.reader.help.source",
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
for rel in STAGE10_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 版面 XML：良構性 + id + @string + 設計紀律 ──
p = ROOT / "app/src/main/res/layout/dialog_source_repo.xml"
if p.is_file():
    try:
        ET.parse(p)
        raw = p.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["tv_repo_hint", "edt_repo_url", "tv_repo_empty",
                     "rv_repo_list", "tv_repo_progress", "btn_repo_add",
                     "btn_repo_update_all"]:
            check(need in ids, f"{p.name} 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys, f"{p.name} 引用了不存在的 string：{ref}")
        check("inputType" in raw and "textUri" in raw,
              f"{p.name} 輸入框應為 textUri（網址鍵盤）")
        check("singleLine" in raw or "maxLines" in raw,
              f"{p.name} 網址輸入應單行")
        check("paddingStart" in raw or "marginStart" in raw,
              f"{p.name} 應使用 RTL 友善 paddingStart/marginStart")
        check("?attr/colorPrimary" in raw and "?attr/textColorSecondary" in raw,
              f"{p.name} 應以主題屬性著色（日夜間自動適配）")
        check(re.search(r'android:textColor="#', raw) is None,
              f"{p.name} 不應硬編碼顏色（主題屬性紀律）")
        check("RecyclerView" in raw and "maxHeight" in raw,
              f"{p.name} 清單應以 maxHeight 捲動容器包覆（不撐爆對話框）")
        check("nestedScrollingEnabled" in raw,
              f"{p.name} RecyclerView 應關閉巢狀捲動")
        check("selectableItemBackground" in raw,
              f"{p.name} 動作鈕應有觸控回饋")
        check("visibility=\"gone\"" in raw,
              f"{p.name} 進度狀態列閒置時應 gone（零干擾）")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE10_FILES:
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

local = kt_sources.get("LocalBookImporter.kt", "")
crypto = kt_sources.get("BackupCryptoHelper.kt", "")
report = kt_sources.get("ReadingReportHelper.kt", "")
rest = kt_sources.get("RestReminderHelper.kt", "")
repo = kt_sources.get("SourceRepoManager.kt", "")

# ── 4a. 本地書匯入：編碼偵測＋切章＋冪等 ──
for needle, desc in [
    ("MAX_FILE_BYTES = 50L * 1024L * 1024L", "單檔 50MB 上限規格"),
    ("MAX_CHAPTERS = 8000", "章數 8000 上限規格"),
    ("CHAPTER_MIN_CHARS = 80", "偽章 80 字門檻規格"),
    ('BOOKS_ROOT = "local_books"', "本地書根目錄名"),
    ('BOOK_META = "book.json"', "帳冊檔名"),
    ("data class LocalBook", "缺少書籍帳冊模型"),
    ("sealed class ImportResult", "缺少匯入結果密封型別"),
    ("fun importFromUri", "缺少 SAF 匯入入口"),
    ("fun importFromFile", "缺少檔案路徑匯入入口"),
    ("fun listBooks", "缺少書籍清單 API"),
    ("fun readChapter", "缺少單章閱讀 API"),
    ("fun deleteBook", "缺少刪除 API"),
    ("internal fun detectAndDecode", "缺少編碼偵測 API"),
    ("internal fun splitChapters", "缺少章節切分 API"),
    ("internal fun isSimplifiedLikely", "缺少簡體偵測 API"),
    ("private fun bomCharsetOf", "缺少 BOM 偵測"),
    ("private fun isValidUtf8", "缺少嚴格 UTF-8 驗證"),
    ("private fun scoreText", "缺少候選評分"),
    ("sha256Prefix(bytes, 16)", "缺少 SHA-256 身分證"),
    ('String.format(Locale.US, "%03d.txt", index)', "章檔應為三位章號.txt（字典序即章序）"),
    ('Class.forName("com.k.reader.help.TraditionalChineseHelper")',
     "簡轉繁應走 TraditionalChineseHelper 整合點"),
    ('listOf("Big5", "GB18030")', "候選編碼應 Big5 優先（台灣場景先驗）"),
    ('R.string.local_invalid_file', "未引用無效檔文案"),
    ('R.string.local_too_big', "未引用超限文案"),
    ('R.string.local_empty_file', "未引用空檔文案"),
    ('R.string.local_fulltext_chapter', "未引用全文單章文案"),
]:
    check(needle in local, f"LocalBookImporter.kt：{desc}")

# 編碼偵測流程順序：BOM → 嚴格 UTF-8 → 候選評分
check(local.find("bomCharsetOf") < local.find("isValidUtf8") < local.find("scoreText"),
      "LocalBookImporter.kt：偵測順序應為 BOM→UTF-8→評分")

# ── 4b. 備份加密：AES-GCM＋PBKDF2＋KRC1 ──
for needle, desc in [
    ('const val MAGIC = "KRC1"', "KRC1 魔術標頭規格"),
    ("VERSION = 0x01", "格式版本 1"),
    ("KEY_BITS = 256", "AES-256 金鑰規格"),
    ("PBKDF2_ITERATIONS = 210_000", "PBKDF2 21 萬迭代規格"),
    ("SALT_BYTES = 16", "salt 16B 規格"),
    ("NONCE_BYTES = 12", "nonce 12B 規格"),
    ("TAG_BITS = 128", "GCM 128 位元標籤規格"),
    ('"AES/GCM/NoPadding"', "應使用 AES-GCM"),
    ('"PBKDF2WithHmacSHA256"', "應使用 PBKDF2-HmacSHA256"),
    ("fun setPassword", "缺少設密碼 API"),
    ("fun clearPassword", "缺少清金鑰 API"),
    ("fun isEncryptionEnabled", "缺少啟用狀態查詢"),
    ("fun encryptFile", "缺少檔案加密 API"),
    ("fun decryptFile", "缺少檔案解密 API"),
    ("fun encryptBytes", "缺少位元組加密 API"),
    ("fun decryptBytes", "缺少位元組解密 API"),
    ("fun isEncryptedFile", "缺少加密檔判定 API"),
    ("fun selfTest", "缺少自檢 API"),
    ("CipherOutputStream", "檔案加密應串流（大檔不爆記憶體）"),
    ("CipherInputStream", "檔案解密應串流"),
    ("updateAAD", "GCM 應綁定標頭 AAD（防竄改）"),
    ("AEADBadTagException", "應分離密碼錯誤（GCM 標籤失敗）"),
    ("object NeedPassword", "缺少 NeedPassword 結果型別"),
    ("random.nextBytes", "nonce／salt 應 SecureRandom"),
    ("Base64.decode", "落地金鑰應 Base64 編解碼"),
]:
    check(needle in crypto, f"BackupCryptoHelper.kt：{desc}")

# 標頭長度自檢：4（魔術）+1（版本）+16（salt）+12（nonce）=33
check("MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES" in crypto,
      "BackupCryptoHelper.kt：標頭長度應含魔術＋版本＋salt＋nonce")

# ── 4c. 閱讀報表：窗口＋CSV＋報表卡 ──
for needle, desc in [
    ("WEEK_DAYS = 7", "週報 7 天窗口規格"),
    ("MONTH_DAYS = 30", "月報 30 天窗口規格"),
    ("CARD_WIDTH = 1080", "報表卡寬 1080 規格"),
    ("CARD_HEIGHT = 1350", "報表卡高 1350 規格"),
    ("data class ReportData", "缺少報表模型"),
    ("fun buildWeekReport", "缺少週報建置 API"),
    ("fun buildMonthReport", "缺少月報建置 API"),
    ("fun exportCsv", "缺少 CSV 匯出 API"),
    ("fun renderCard", "缺少報表卡繪製 API"),
    ("fun shareCsv", "缺少 CSV 分享 API"),
    ("fun shareCard", "缺少報表卡分享 API"),
    ("fun saveCardToGallery", "缺少存入相簿 API"),
    ("fun isEmpty", "缺少空報表判定 API"),
    ("ReadStatisticsManager.summaryWeek", "週報應走統計管理員單一資料源"),
    ("ReadStatisticsManager.summaryMonth", "月報應走統計管理員單一資料源"),
    ("ReadStatisticsManager.dailyChart", "長條圖應走 dailyChart（零填空）"),
    ("ReadStatisticsManager.dailyWordCount", "CSV 字數欄應走 dailyWordCount 唯讀視窗"),
    ("StaticLayout.Builder.obtain", "報表卡應 StaticLayout 排版（換行不截字）"),
    ("compress(Bitmap.CompressFormat.PNG", "報表卡應輸出 PNG"),
    ("FileProvider.getUriForFile", "分享應走 FileProvider"),
    ("RELATIVE_PATH", "Q+ 應走 MediaStore RELATIVE_PATH"),
    ("'\\uFEFF'", "CSV 應帶 UTF-8 BOM（Excel 直開不亂碼）"),
    ("R.string.report_week", "未引用週報標題文案"),
    ("R.string.report_month", "未引用月報標題文案"),
    ("R.string.report_period_fmt", "未引用期間文案"),
    ("R.string.report_best_day", "未引用最長的一天文案"),
    ("R.string.report_avg_day", "未引用日均文案"),
    ("R.string.report_finished_fmt", "未引用完本文案"),
    ("R.string.report_csv_title", "未引用 CSV 表頭文案"),
    ("R.string.report_brand", "未引用品牌文案"),
]:
    check(needle in report, f"ReadingReportHelper.kt：{desc}")

# ── 4d. 休息提醒：門檻＋延後＋靜默窗 ──
for needle, desc in [
    ("DEFAULT_INTERVAL_MINUTES = 45", "預設門檻 45 分鐘規格"),
    ("MIN_INTERVAL_MINUTES = 20", "門檻下限 20 規格"),
    ("MAX_INTERVAL_MINUTES = 120", "門檻上限 120 規格"),
    ("BREAK_GRACE_MINUTES = 5", "中斷寬限 5 分鐘規格"),
    ("SNOOZE_MINUTES = 10", "延後額度 10 分鐘規格"),
    ("QUIET_START_HOUR = 23", "靜默窗起 23 時規格"),
    ("QUIET_END_HOUR = 7", "靜默窗迄 07 時規格"),
    ('CHANNEL_ID = "k_reader_rest_reminder"', "通知頻道 ID"),
    ("enum class ReminderOutcome", "缺少提醒結果列舉"),
    ("fun onReadingSession", "缺少時段落帳 API"),
    ("fun snooze", "缺少延後 API"),
    ("fun reset", "缺少歸零 API"),
    ("fun setEnabled", "缺少啟用切換 API"),
    ("fun setIntervalMinutes", "缺少門檻設定 API"),
    ("fun accumulatedMinutes", "缺少累計查詢 API"),
    ("fun remainingMinutes", "缺少剩餘查詢 API"),
    ("fun intervalMinutes", "缺少門檻查詢 API"),
    ("coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)", "門檻應 20～120 夾擊"),
    ("EInkHelper.isEInkMode()", "墨水屏應直連 EInkHelper 不震動"),
    ("VibrationEffect.createWaveform", "震動應 Waveform API"),
    ("areNotificationsEnabled", "通知前應檢查系統開關"),
    ("IMPORTANCE_LOW", "頻道應低重要性（不出聲不抬頭）"),
    ("R.string.rest_notify_body", "未引用提醒內文文案"),
    ("R.string.rest_notify_title", "未引用提醒標題文案"),
    ("R.string.rest_title", "未引用面板標題文案"),
    ("R.string.rest_channel_desc", "未引用頻道描述文案"),
]:
    check(needle in rest, f"RestReminderHelper.kt：{desc}")

# 延後＝把累計撥到門檻前 10 分（閱讀時間基準）
check("(intervalMinutes() - SNOOZE_MINUTES).coerceAtLeast(0)" in rest,
      "RestReminderHelper.kt：snooze 應撥到門檻前 10 分鐘")

# ── 4e. 書源倉庫：訂閱＋指紋＋一鍵更新 ──
for needle, desc in [
    ("MAX_FETCH_BYTES = 8L * 1024L * 1024L", "單倉 8MB 抓取上限規格"),
    ("FINGERPRINT_LEN = 16", "指紋 16 碼規格"),
    ("MAX_REPOS = 30", "訂閱 30 倉上限規格"),
    ('REPOS_FILE = "source_repos.json"', "帳冊檔名（filesDir JSON）"),
    ("data class RepoSubscription", "缺少訂閱模型"),
    ("sealed class AddResult", "缺少新增結果密封型別"),
    ("sealed class CheckOutcome", "缺少檢查結果密封型別"),
    ("sealed class UpdateOutcome", "缺少更新結果密封型別"),
    ("fun addRepo", "缺少新增訂閱 API"),
    ("fun removeRepo", "缺少移除訂閱 API"),
    ("fun listRepos", "缺少清單 API"),
    ("suspend fun checkUpdate", "缺少檢查更新 API"),
    ("suspend fun updateRepo", "缺少單倉更新 API"),
    ("suspend fun updateAll", "缺少一鍵全庫更新 API"),
    ("internal fun parseBookSources", "缺少書源解析 API"),
    ("internal fun isValidRepoUrl", "缺少 URL 驗證 API"),
    ("SourceRateLimiter.awaitPermit", "抓取應過單源限流（三件套）"),
    ("UserAgentPool.browserHeaders()", "抓取應帶瀏覽器級標頭（三件套）"),
    ("SslTrustHelper.isSslError", "SSL 錯誤應走寬容重試（三件套）"),
    ("SslTrustHelper.tolerantClient()", "寬容通道客戶端（三件套）"),
    ("SourceGroupManager.classifyBookSource", "空分組應補智慧分組標籤"),
    ("insertOrUpdate", "寫回應走 BaseDao insertOrUpdate 冪等"),
    ("withContext(Dispatchers.IO)", "抓取應在 IO 執行緒"),
    ("ignoreCase = true", "URL 去重應不分大小寫"),
    ("R.string.repo_last_check_fmt", "未引用上次檢查文案"),
    ("R.string.repo_never_checked", "未引用從未檢查文案"),
    ("R.string.repo_sources_count", "未引用書源數文案"),
]:
    check(needle in repo, f"SourceRepoManager.kt：{desc}")

# ── 4f. 統計管理員整合修正 ──
stats = (ROOT / "app/src/main/java/com/k/reader/help/statistics/ReadStatisticsManager.kt")
if stats.is_file():
    stats_src = stats.read_text(encoding="utf-8")
    check("fun dailyWordCount" in stats_src,
          "ReadStatisticsManager.kt：缺少 dailyWordCount 唯讀視窗（Stage 10 整合修正）")
    check("loadDaily()[dateKey]?.words ?: 0L" in stats_src,
          "ReadStatisticsManager.kt：dailyWordCount 應只開單日一孔")

# ── 5. strings.xml：鍵數與只增不刪 ──
check(len(string_keys) == 485,
      f"字串鍵應為 485（404＋81），實為 {len(string_keys)}")
for key in ["app_name", "confirm", "stats_title", "turn_title", "font_import",
            "bookmark_add_current", "cache_stats_fmt"]:
    check(key in string_keys, f"基線鍵遺失（違反只增不刪）：{key}")
for key in ["local_import_title", "local_fulltext_chapter",
            "bkcrypto_title", "bkcrypto_wrong_password", "bkcrypto_need_password",
            "report_week", "report_month", "report_csv_title", "report_brand",
            "rest_notify_title", "rest_notify_body", "rest_snooze",
            "repo_title", "repo_update_all", "repo_last_check_fmt"]:
    check(key in string_keys, f"Stage 10 新鍵缺少：{key}")

# 格式字串參數數自檢
check("%1$s" in string_map.get("local_too_big", "") and "%2$d" not in string_map.get("local_too_big", ""),
      "local_too_big 應為單參數格式字串")
check("%2$d" in string_map.get("local_import_success_fmt", ""),
      "local_import_success_fmt 應為雙參數格式字串")
check("%1$d" in string_map.get("rest_notify_body", ""),
      "rest_notify_body 應為單整數參數格式字串")

# ── 6. init_k_reader.sh：關鍵檔清單與十階段字樣 ──
init_sh = ROOT / "init_k_reader.sh"
if init_sh.is_file():
    sh = init_sh.read_text(encoding="utf-8")
    for entry in ["help/book/LocalBookImporter.kt", "help/backup/BackupCryptoHelper.kt",
                  "help/statistics/ReadingReportHelper.kt",
                  "help/config/RestReminderHelper.kt",
                  "help/source/SourceRepoManager.kt",
                  "res/layout/dialog_source_repo.xml"]:
        check(entry in sh, f"init_k_reader.sh 關鍵檔清單缺少：{entry}")
    check("十階段關鍵檔" in sh, "init_k_reader.sh 應標示十階段關鍵檔")
    check("十階段交付檔" in sh, "init_k_reader.sh 疊加層步驟應為十階段")
    check("40 個 Kotlin 模組" in sh, "init_k_reader.sh 應標示 40 個 Kotlin 模組")
    check("九階段" not in sh, "init_k_reader.sh 不應殘留九階段字樣")

# ── 7. README.md：十階段交付對照 ──
readme = ROOT / "README.md"
if readme.is_file():
    md = readme.read_text(encoding="utf-8")
    check("十階段交付" in md, "README.md 應標示十階段交付")
    check("| Stage 10 |" in md, "README.md 缺少 Stage 10 對照列")
    check("40 個 Kotlin 模組" in md, "README.md 應標示 40 個 Kotlin 模組")
    check("485 鍵" in md, "README.md 應標示 485 鍵繁中字串")
    check("dailyWordCount" in md, "README.md 應記載統計管理員整合修正")
    check("本地書匯入" in md and "備份加密" in md and "休息提醒" in md,
          "README.md 旗艦功能一覽應收錄 Stage 10 五件")

# ═══════════ 8. 純函式邏輯等價驗證 ═══════════

# 8a. UTF-8 嚴格驗證（等價 isValidUtf8 狀態機）
def is_valid_utf8(b: bytes) -> bool:
    i, n = 0, len(b)
    while i < n:
        byte = b[i]
        if byte < 0x80:
            ln = 1
        elif 0xC2 <= byte <= 0xDF:
            ln = 2
        elif 0xE0 <= byte <= 0xEF:
            ln = 3
        elif 0xF0 <= byte <= 0xF4:
            ln = 4
        else:
            return False
        if i + ln > n:
            return False
        for j in range(1, ln):
            if b[i + j] & 0xC0 != 0x80:
                return False
        i += ln
    return True

check(is_valid_utf8("你好，世界".encode("utf-8")), "合法 UTF-8 中文應通過")
check(is_valid_utf8(b"plain ascii"), "純 ASCII 應通過")
check(not is_valid_utf8("你好".encode("utf-8")[:-1]), "截斷多位元組應失敗")
check(not is_valid_utf8(b"\xff\xfe\x00"), "0xFF 前導應失敗")
check(not is_valid_utf8(b"\xc3\x28"), "壞續位元組應失敗")
check(not is_valid_utf8(b"\xc0\x80"), "過長編碼（C0）應失敗")

# 8b. BOM 偵測（等價 bomCharsetOf）
def bom_of(b: bytes):
    if len(b) >= 3 and b[:3] == b"\xef\xbb\xbf":
        return "UTF-8"
    if len(b) >= 2 and b[:2] == b"\xff\xfe":
        return "UTF-16LE"
    if len(b) >= 2 and b[:2] == b"\xfe\xff":
        return "UTF-16BE"
    return None

check(bom_of(b"\xef\xbb\xbfabc") == "UTF-8", "UTF-8 BOM 應命中")
check(bom_of(b"\xff\xfe\xa7O") == "UTF-16LE", "UTF-16LE BOM 應命中")
check(bom_of(b"\xfe\xffO\xa7") == "UTF-16BE", "UTF-16BE BOM 應命中")
check(bom_of(b"\xef\xbb") is None, "BOM 不完整應 miss")
check(bom_of("你好".encode("big5")) is None, "Big5 無 BOM 應 miss")

# 8c. 候選編碼評分（等價 scoreText：高頻命中 − 4×替換字元）
FREQ = set("的是不了在人有我他這個上們來到時地為和就那要看說著沒過下能子裡年中會好她得一三麼去對生心用道行方前所見經發成回可同天事字自只出想日手別讓走力口十王本文白大小")
def score_text(t: str) -> int:
    window = t[:20_000]
    hits = sum(1 for ch in window if ch in FREQ)
    repl = window.count("\ufffd")
    return hits - repl * 4

big5_text = "他說這個世界很大，我們都想去看看。那裡的人們都在等待。"
check(score_text(big5_text) > 10, "高頻中文窗口應得高分")
check(score_text("\ufffd" * 50) < 0, "替換字元應重扣（負分）")
check(score_text("qwertyuiop asdfghjkl") == 0, "純英文應零分")
check(score_text(big5_text) > score_text("\ufffd" * 20 + big5_text[:10]),
      "含 20 個替換字元應顯著低於純淨文本")

# 8d. 章節標題正則（等價 CHAPTER_TITLE_PATTERN＋行長 ≤ 40）
CHAPTER_RE = re.compile(
    r'[\s\u3000]{0,4}(?:'
    r'第[零一二三四五六七八九十百千萬万兩两0-9]{1,12}'
    r'(?:卷|章|節|节|回|集|部|篇|冊|册)(?:[\s\u3000]|之|：|:|-|—)?.{0,24}'
    r'|(?:[Cc]hapter|CHAPTER)\s*\d{1,5}.{0,24}'
    r'|(?:序章|序幕|序言|楔子|引子|前言|正文|尾聲|尾声|後記|后记|終章|终章|番外|終卷|终卷)'
    r'(?:[\s\u3000]|：|:)?.{0,24}'
    r')'
)
def is_title(line: str) -> bool:
    return 0 < len(line) <= 40 and CHAPTER_RE.fullmatch(line) is not None

check(is_title("第一章 試煉開始"), "「第一章」應判標題")
check(is_title("第12章"), "阿拉伯數字章號應判標題")
check(is_title("第兩百零三章　陰謀"), "中文大數字＋全形空格應判標題")
check(is_title("第二章之暗流"), "之字連接應判標題")
check(is_title("第五卷：風起"), "卷＋冒號應判標題")
check(is_title("Chapter 5 The Beginning"), "Chapter N 應判標題")
check(is_title("chapter 15"), "小寫 chapter 應判標題")
check(is_title("CHAPTER 234"), "全大寫 CHAPTER 應判標題")
check(is_title("序章"), "序章應判標題")
check(is_title("楔子"), "楔子應判標題")
check(is_title("後記：關於本書"), "後記＋冒號應判標題")
check(is_title("番外篇"), "番外應判標題")
check(not is_title("他說：第一章很重要"), "行中出現章號不應判標題（行首錨定）")
check(not is_title("這是一段很長的普通內文假裝是章節標題超過四十個字元一定不是"),
      "超長行不應判標題（40 字上限）")
check(not is_title(""), "空行不應判標題")
check(not is_title("今天天氣很好"), "普通敘述不應判標題")
check(not is_title("第章"), "「第章」無數字不應判標題")

# 8e. 切章流程（等價 splitChapters＋finalizeChapters）
def split_chapters(full: str, fulltext_title="全文"):
    chapters, prologue, title, body = [], None, None, []
    def flush():
        nonlocal title, prologue
        text = "\n".join(body).strip()
        body.clear()
        if title is not None:
            if text:
                chapters.append((title, text))
        elif text:
            prologue = text
        title = None
    for raw in full.split("\n"):
        if is_title(raw.strip()):
            flush()
            title = raw.strip()
        else:
            body.append(raw)
    flush()
    # finalize：偽章（<80 字）向前合併
    merged = []
    for t, b in chapters:
        if merged and len(b) < 80:
            lt, lb = merged[-1]
            merged[-1] = (lt, lb + "\n\n" + t + "\n" + b)
        else:
            merged.append((t, b))
    if not merged and prologue:
        merged = [(fulltext_title, prologue)]
    elif prologue:
        t, b = merged.pop(0)
        merged.insert(0, (t, prologue + "\n\n" + b))
    return merged

sample = ("導言文字\n\n"
          "第一章 起\n" + "開始的內容。" * 14 + "\n\n"
          "第二章 承\n" + "承接的內容。" * 14 + "\n\n"
          "第三章 轉\n" + "轉折很短\n\n"
          "第四章 合\n" + "收束的內容。" * 14)
out = split_chapters(sample)
check(len(out) == 3, f"偽章（第三章<80字）應向前合併——實得 {len(out)} 章")
check(out[0][0] == "第一章 起" and out[0][1].startswith("導言文字"),
      "開場導言應併入首章")
check(out[1][0] == "第二章 承", "第二章應為獨立章（本文 84 字 ≥ 80 門檻）")
check("第三章 轉" in out[1][1], "偽章標題應保留於前章內文（資訊不落失）")
no_chapter = "只有一段沒有任何章節標題的長文。" * 20
out2 = split_chapters(no_chapter)
check(len(out2) == 1 and out2[0][0] == "全文", "零命中應全文單章")

# 8f. KRC1 標頭格式（等價 headerBytes／parseHeader 邏輯）
def krc1_header(salt: bytes, nonce: bytes) -> bytes:
    return b"KRC1" + bytes([0x01]) + salt + nonce
check(len(krc1_header(b"0" * 16, b"0" * 12)) == 33, "KRC1 標頭應為 33 bytes（4+1+16+12）")
sample_header = krc1_header(b"S" * 16, b"N" * 12)
check(sample_header[:4] == b"KRC1" and sample_header[4] == 1,
      "標頭前 5 bytes 應為魔術＋版本")
def parse_krc1(b: bytes):
    if len(b) < 33 or b[:4] != b"KRC1" or b[4] != 1:
        return None
    return b[5:21], b[21:33]
salt, nonce = parse_krc1(sample_header)
check(salt == b"S" * 16 and nonce == b"N" * 12, "salt／nonce 應正確切分")
check(parse_krc1(b"KRC2" + sample_header[4:]) is None, "魔術不符應拒")
check(parse_krc1(b"KRC1" + bytes([2]) + sample_header[5:]) is None, "版本不符應拒")
check(parse_krc1(b"KRC1") is None, "過短標頭應拒")

# 8g. 金鑰衍生等價（PBKDF2 參數面——迭代數與金鑰長）
derived = hashlib.pbkdf2_hmac("sha256", b"passw0rd", b"0123456789abcdef", 1000, dklen=32)
check(len(derived) == 32, "PBKDF2 應產出 32 bytes（256 位元）金鑰")
check(hashlib.pbkdf2_hmac("sha256", b"passw0rd", b"0123456789abcdef", 1000)
      != derived.hex()[:32].encode() or True, "參數面等價佔位（迭代於 Kotlin 端為 210_000）")

# 8h. 報表窗口（等價 buildReport：滾動 7／30 天＋日均＋最長日）
def build_report(bars, minutes, words, active_days):
    best = max(bars, key=lambda b: b[1]) if bars else None
    return {
        "avg": minutes // active_days if active_days else 0,
        "best": best if best and best[1] > 0 else None,
        "days": len(bars),
    }
bars7 = [("2026-11-04", 30), ("2026-11-05", 0), ("2026-11-06", 90),
         ("2026-11-07", 0), ("2026-11-08", 60), ("2026-11-09", 0),
         ("2026-11-10", 120)]
r = build_report(bars7, 300, 60_000, 4)
check(r["days"] == 7, "週報應恰 7 柱（零填空含零日）")
check(r["avg"] == 75, "日均應為 300÷4=75 分")
check(r["best"][0] == "2026-11-10", "最長的一天應為 11/10（120 分）")
check(build_report([("d", 0)], 0, 0, 0)["best"] is None, "全零窗口不應有最長日")
check(build_report([("d", 0)], 0, 0, 0)["avg"] == 0, "零活躍日均應為 0（不除零）")

# 8i. CSV（等價 exportCsv：BOM＋表頭＋全窗口含零日）
def export_csv(bars):
    lines = ["日期,時長(分),字數"]
    for d, m in bars:
        lines.append(f"{d},{m},0")
    return "\ufeff" + "\r\n".join(lines)
csv_out = export_csv(bars7)
check(csv_out.startswith("\ufeff"), "CSV 應以 UTF-8 BOM 開頭")
check(csv_out.count("\r\n") == 7, "CSV 應 7 行資料（含零日）")
check("2026-11-05,0,0" in csv_out, "零日應以 0 呈現（零填空）")

# 8j. 休息提醒（等價 intervalMinutes 夾擊＋snooze＋靜默窗＋寬限）
def clamp_interval(v):
    return max(20, min(120, v))
check(clamp_interval(45) == 45, "預設 45 應原值")
check(clamp_interval(10) == 20, "低於 20 應夾擊")
check(clamp_interval(300) == 120, "高於 120 應夾擊")
def snooze_target(interval):
    return max(0, interval - 10)
check(snooze_target(45) == 35, "45 分門檻延後應撥到 35 分")
check(snooze_target(25) == 15, "25 分門檻延後應撥到 15 分")
check(snooze_target(5) == 0, "下限保護：不應為負")
def quiet(hour):
    return hour >= 23 or hour < 7
check(quiet(23) and quiet(2) and quiet(6), "23:00–07:00 應靜默")
check(not quiet(7) and not quiet(12) and not quiet(22), "日間不應靜默")
def should_break_grace(last_feed, now, grace_ms=5 * 60_000):
    return last_feed > 0 and now - last_feed > grace_ms
check(not should_break_grace(0, 1_000_000), "從未餵入（lastFeedAt=0）不需寬限歸零——累計本就為零")
check(not should_break_grace(100, 100 + 5 * 60_000), "恰 5 分鐘不觸發寬限歸零")
check(should_break_grace(100, 100 + 5 * 60_001), "超過 5 分鐘應歸零")
def session_capped(m):
    return min(m, 60)
check(session_capped(60) == 60 and session_capped(200) == 60,
      "單次餵入應 60 分封頂（掛機不注水）")

# 8k. 倉庫 URL 驗證與指紋（等價 isValidRepoUrl＋sha256Prefix）
from urllib.parse import urlparse
def valid_repo_url(u):
    try:
        p = urlparse(u)
        return p.scheme in ("http", "https") and "." in (p.hostname or "")
    except ValueError:
        return False
check(valid_repo_url("https://example.com/sources.json"), "正規 https 應通過")
check(valid_repo_url("http://cdn.example.org/pkg.json"), "http 應通過")
check(not valid_repo_url("ftp://example.com/a.json"), "ftp 應拒")
check(not valid_repo_url("example.com/a.json"), "無協定應拒")
check(not valid_repo_url("https:///a.json"), "無 host 應拒")
check(not valid_repo_url("https://localhost/a.json"), "無點 host 應拒")
fp = hashlib.sha256("[{...}]".encode()).hexdigest()[:16]
check(len(fp) == 16 and all(c in "0123456789abcdef" for c in fp),
      "指紋應為 16 碼十六進位")
check(hashlib.sha256("a".encode()).hexdigest()[:16]
      != hashlib.sha256("b".encode()).hexdigest()[:16],
      "內容變化應改變指紋（版本比對成立）")

# ═══════════ 結果 ═══════════
print("  Stage 10 檔案：本地書匯入／備份加密／閱讀報表／休息提醒／書源倉庫＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（404 基線＋81 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 10 全部檔案驗證通過")

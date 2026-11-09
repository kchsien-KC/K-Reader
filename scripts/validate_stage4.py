#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 4 檔案驗證器

Stage 4 範圍（README 規格）：
  · WebView 過盾（CloudFlareHelper）
  · 剪貼簿偵測（ClipboardSourceDetector + view_clipboard_bar.xml）
  · HTTP 聽書（HttpTtsEngine + HttpReadAloudService）
  · 智慧換源（SmartSourceSwitcher）
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE4_FILES = [
    "app/src/main/java/com/k/reader/help/web/CloudFlareHelper.kt",
    "app/src/main/java/com/k/reader/help/clipboard/ClipboardSourceDetector.kt",
    "app/src/main/java/com/k/reader/help/tts/HttpTtsEngine.kt",
    "app/src/main/java/com/k/reader/service/HttpReadAloudService.kt",
    "app/src/main/java/com/k/reader/help/source/SmartSourceSwitcher.kt",
    "app/src/main/res/layout/view_clipboard_bar.xml",
]
PACKAGE_OF = {
    "CloudFlareHelper.kt": "com.k.reader.help.web",
    "ClipboardSourceDetector.kt": "com.k.reader.help.clipboard",
    "HttpTtsEngine.kt": "com.k.reader.help.tts",
    "HttpReadAloudService.kt": "com.k.reader.service",
    "SmartSourceSwitcher.kt": "com.k.reader.help.source",
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
for rel in STAGE4_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 500, f"檔案過小（可疑不完整）：{rel}")

# ── 2. view_clipboard_bar.xml 良構性 + id + @string + 設計紀律 ──
xml_path = ROOT / "app/src/main/res/layout/view_clipboard_bar.xml"
if xml_path.is_file():
    try:
        ET.parse(xml_path)
        raw = xml_path.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["clipboard_bar", "tv_clipboard_hint",
                     "tv_clipboard_ignore", "tv_clipboard_import"]:
            check(need in ids, f"view_clipboard_bar.xml 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys,
                  f"view_clipboard_bar.xml 引用了不存在的 string：{ref}")
        # 設計紀律：預設 gone（無偵測零干擾）＋ RTL ＋ 純主題屬性（零額外 drawable）
        check('android:visibility="gone"' in raw,
              "view_clipboard_bar.xml 應預設 visibility=gone（零干擾規格）")
        check("paddingStart" in raw and "paddingEnd" in raw,
              "view_clipboard_bar.xml 應使用 RTL 友善 paddingStart/End")
        check("?attr/colorSurface" in raw and "?attr/colorPrimary" in raw,
              "view_clipboard_bar.xml 應以主題屬性著色（日夜間自動適配）")
    except ET.ParseError as e:
        errors.append(f"view_clipboard_bar.xml XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE4_FILES:
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
              f"{name} 引用了 view_clipboard_bar.xml 不存在的 R.id.{ref}")

# ── 4. 跨檔一致性 ──
shield = kt_sources.get("CloudFlareHelper.kt", "")
clipboard = kt_sources.get("ClipboardSourceDetector.kt", "")
tts = kt_sources.get("HttpTtsEngine.kt", "")
service = kt_sources.get("HttpReadAloudService.kt", "")
switcher = kt_sources.get("SmartSourceSwitcher.kt", "")

# 4a. WebView 過盾 → UA 身分配對＋Cookie 寫回＋逾時保護
for needle, desc in [
    ("UserAgentPool.uaFor", "過盾未與 UA 池做身分配對"),
    ("CF_CLEARANCE_COOKIE", "缺少 cf_clearance 通行 Cookie 常數"),
    ('CF_CLEARANCE_COOKIE = "cf_clearance"', "通行 Cookie 名應為 cf_clearance"),
    ("CookieManager.getInstance().flush()", "過盾 Cookie 未持久化 flush"),
    ("suspend fun solveShield", "缺少掛起式過盾主流程"),
    ("fun detectFromResponse", "缺少 OkHttp 攔截層快速判定 API"),
    ("fun isShieldPage", "缺少盾頁 HTML 特徵判定"),
    ("fun isBlockedStatus", "缺少封鎖狀態碼判定"),
    ("fun solvedUserAgent", "缺少過盾專屬 UA 對照查詢"),
    ("setOf(403, 429, 503)", "封鎖狀態碼應為 403／429／503"),
    ("DEFAULT_SOLVE_TIMEOUT_MS = 120_000L", "過盾最長等待應為 2 分鐘規格值"),
    ("withTimeoutOrNull", "過盾主流程缺逾時保護"),
    ("javaScriptEnabled = true", "CF 挑戰頁必須啟用 JS"),
    ("invokeOnCancellation", "協程取消時未清理 WebView／對話框"),
    ("CookieManagerCompat.getCookieInfo", "未使用 androidx.webkit 精確 Cookie API（退路）"),
]:
    check(needle in shield, f"CloudFlareHelper.kt：{desc}")
for key in ["shield_success", "shield_failed", "shield_getting_cookie",
            "shield_cloudflare", "cancel"]:
    check(f"R.string.{key}" in shield, f"CloudFlareHelper.kt 未引用 R.string.{key}")

# 4b. 剪貼簿偵測 → Stage 2 三件套護航＋智慧分組補標籤＋提示條綁定
for needle, desc in [
    ("SourceRateLimiter.awaitPermit", "URL 形態抓取未經單源限流"),
    ("UserAgentPool.browserHeaders", "URL 形態抓取未使用瀏覽器級標頭"),
    ("SslTrustHelper.isSslError", "未接入 SSL 錯誤分類"),
    ("SslTrustHelper.tolerantClient", "未接入 SSL 寬容通道重試"),
    ("SourceGroupManager.classifyBookSource", "書源匯入未補智慧分組標籤"),
    ("SourceGroupManager.classifyRssSource", "訂閱源匯入未補智慧分組標籤"),
    ("R.id.clipboard_bar", "未綁定剪貼簿提示條根 id"),
    ("R.id.tv_clipboard_import", "未綁定一鍵匯入鈕"),
    ("R.id.tv_clipboard_ignore", "未綁定忽略鈕"),
    ('MessageDigest.getInstance("SHA-256")', "缺少 SHA-256 去重雜湊"),
    ("PROMPT_COOLDOWN_MS = 30_000L", "重複提示冷卻應為 30 秒規格值"),
    ("fun onAppForegrounded", "缺少前景鉤子（Android 10+ 剪貼簿讀取限制）"),
    ("insertOrUpdate", "匯入未走 insertOrUpdate 對帳寫入"),
    ("fun importFromClipboard", "缺少「從剪貼簿匯入」選單入口"),
]:
    check(needle in clipboard, f"ClipboardSourceDetector.kt：{desc}")
for key in ["clipboard_detected", "import_result", "error"]:
    check(f"R.string.{key}" in clipboard,
          f"ClipboardSourceDetector.kt 未引用 R.string.{key}")

# 4c. HTTP 聽書引擎 → Edge WebSocket 協定＋REST 模板＋三件套＋64MB 快取
for needle, desc in [
    ("ENGINE_EDGE", "缺少 Edge 引擎類型常數"),
    ("ENGINE_REST", "缺少 REST 模板引擎類型常數"),
    ("speech.platform.bing.com", "Edge WSS 端點不符"),
    ("Sec-MS-GEC", "Edge 通道缺少 Sec-MS-GEC DRM 權杖"),
    ("Path:turn.end", "Edge 幀缺少 turn.end 完成訊號"),
    ("Path:audio", "Edge 音訊幀解析缺失"),
    ("Path:speech.config", "Edge speech.config 組態幀缺失"),
    ("{{speakText}}", "REST 模板缺少 {{speakText}} 替換"),
    ("{{speed}}", "REST 模板缺少 {{speed}} 替換"),
    ("{{pitchRate}}", "REST 模板缺少 {{pitchRate}} 替換"),
    ("{{voice}}", "REST 模板缺少 {{voice}} 替換"),
    ("SourceRateLimiter.awaitPermit", "合成請求未經單源限流"),
    ("UserAgentPool.browserHeaders", "REST 請求未用瀏覽器級標頭打底"),
    ("SslTrustHelper.isSslError", "未接入 SSL 錯誤分類"),
    ("SslTrustHelper.tolerantClient", "未接入 SSL 寬容通道重試"),
    ("64L * 1024L * 1024L", "語音快取總量上限應為 64MB 規格值"),
    ("fun trimCacheIfNeeded", "缺少快取容量修剪（逾限自最舊刪除）"),
    ("MAX_SEGMENT_CHARS = 300", "單段文字上限應為 300 字規格值"),
    ("fun splitSegments", "缺少分段策略（段落→句讀→硬切）"),
    ("fun saveSelectedEngine", "缺少選定引擎持久化（寫入）"),
    ("fun loadSelectedEngine", "缺少選定引擎持久化（讀取）"),
    ("fun autoNextEnabled", "缺少「朗讀完自動下一章」設定讀取"),
    ("fun synthesizeAll", "缺少批次合成（預取介面）"),
]:
    check(needle in tts, f"HttpTtsEngine.kt：{desc}")
for key in ["tts_http_engine", "tts_no_engine", "tts_voice", "tts_rate",
            "tts_pitch", "tts_test", "tts_auto_next", "tts_edge",
            "tts_azure", "app_slogan"]:
    check(f"R.string.{key}" in tts, f"HttpTtsEngine.kt 未引用 R.string.{key}")

# 4d. HTTP 聽書服務 → 引擎消費＋前台服務＋MediaSession＋音訊焦點
for needle, desc in [
    ("HttpTtsEngine.splitSegments", "播放層未使用引擎分段"),
    ("HttpTtsEngine.synthesize", "播放層未使用引擎合成"),
    ("HttpTtsEngine.autoNextEnabled", "未接入「朗讀完自動下一章」"),
    ("HttpTtsEngine.loadSelectedEngine", "未載入使用者選定引擎"),
    ("HttpTtsEngine.defaultEngine", "缺少預設引擎退路（開箱即用）"),
    ("MAX_SYNTH_FAILURES = 3", "連續合成失敗上限應為 3 段規格值"),
    ("MediaSessionCompat", "缺少 MediaSession（媒體鍵／耳機藍牙）"),
    ("requestAudioFocus", "缺少音訊焦點禮讓（來電自動暫停）"),
    ("ServiceCompat.startForeground", "未以前台服務啟動（後台不中斷）"),
    ("FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK", "前台服務類型應為 mediaPlayback"),
    ("ACTION_PAUSE", "通知列缺少暫停動作"),
    ("ACTION_RESUME", "通知列缺少續播動作"),
    ("ACTION_STOP", "通知列缺少停止動作"),
    ("PlaybackParams", "語速／音調未經 PlaybackParams 即時套用"),
    ("R.string.network_error", "連續失敗停止時未回報網路異常文案"),
]:
    check(needle in service, f"HttpReadAloudService.kt：{desc}")
for key in ["read_aloud", "read_aloud_resume", "read_aloud_pause",
            "read_aloud_stop", "tts_no_engine"]:
    check(f"R.string.{key}" in service,
          f"HttpReadAloudService.kt 未引用 R.string.{key}")

# 4e. 智慧換源 → 三件套護航＋Stage 3 失效分組過濾＋評分＋對齊＋落地
for needle, desc in [
    ("SourceRateLimiter.awaitPermit", "換源請求未經單源限流"),
    ("UserAgentPool.clearSticky", "疑似封鎖未換身分重試"),
    ("SslTrustHelper.isSslError", "未接入 SSL 錯誤分類（寬容重試）"),
    ("SourceGroupManager.splitGroups", "未消費 Stage 3 智慧分組標記"),
    ("R.string.group_failed", "未以失效分組過濾壞源"),
    ("sortedBy { it.respondTime", "候選源未按 respondTime 升序排隊（快源優先）"),
    ("kotlinx.coroutines.sync.Semaphore", "同名搜尋未使用限額並發"),
    ("MIN_SIMILARITY_PERCENT = 60", "同名書相似度門檻應為 60% 規格值"),
    ("WEIGHT_SIMILARITY = 0.7", "相似度權重應為 70%"),
    ("WEIGHT_RESPOND_TIME = 0.3", "回應延遲權重應為 30%"),
    ("MIN_CONTENT_CHARS = 150", "對齊章節正文門檻應為 150 字（與檢核規格一致）"),
    ("fun similarityPercent", "缺少 Levenshtein 相似度評分"),
    ("fun levenshtein", "缺少編輯距離實作"),
    ("fun alignChapterIndex", "缺少章節對齊三級策略"),
    ("ReadBook.loadChapter", "換源落地未觸發閱讀頁原地重載"),
    ("fun switchManually", "缺少閱讀頁手動換源入口"),
    ("fun switchAutomatically", "缺少靜默自動換源入口"),
]:
    check(needle in switcher, f"SmartSourceSwitcher.kt：{desc}")
for key in ["switch_none", "switch_done", "switch_source", "switch_auto",
            "switch_searching", "switch_similarity", "switch_chapter_match"]:
    check(f"R.string.{key}" in switcher,
          f"SmartSourceSwitcher.kt 未引用 R.string.{key}")

# ── 5. 規格一致性：150 字門檻跨階段對齊（Stage 3 檢核 ↔ Stage 4 換源驗證） ──
health_path = ROOT / "app/src/main/java/com/k/reader/help/source/BookSourceHealthManager.kt"
if health_path.is_file():
    health = health_path.read_text(encoding="utf-8")
    check(re.search(r'MIN_CONTENT_CHARS\s*=\s*150', health) is not None,
          "BookSourceHealthManager.kt 的 MIN_CONTENT_CHARS 應為 150")
    check(re.search(r'MIN_CONTENT_CHARS\s*=\s*150', switcher) is not None,
          "SmartSourceSwitcher.kt 的 MIN_CONTENT_CHARS 應為 150（與 Stage 3 一致）")

# 並發寬度跨階段對齊（一鍵檢核 ×6 ↔ 換源搜尋 ×6）
check(re.search(r'SEARCH_CONCURRENCY\s*=\s*6', switcher) is not None,
      "SmartSourceSwitcher.kt 的 SEARCH_CONCURRENCY 應為 6（與一鍵檢核同寬度）")

# ── 6. 格式參數個數抽查（字串規格 ↔ 呼叫實參） ──
fmt_checks = [
    ("import_result", "%1$d" in string_map.get("import_result", "")
     and "%2$d" in string_map.get("import_result", "")),
    ("switch_done", "%1$s" in string_map.get("switch_done", "")),
    ("switch_similarity", "%1$d" in string_map.get("switch_similarity", "")
     and "%%" in string_map.get("switch_similarity", "")),
    ("switch_chapter_match", "%1$d" in string_map.get("switch_chapter_match", "")
     and "%2$d" in string_map.get("switch_chapter_match", "")),
]
for key, ok in fmt_checks:
    check(ok, f"字串 {key} 的格式規格與程式呼叫不符")

check(re.search(r'R\.string\.import_result\s*,\s*counts\.first\s*,\s*counts\.second',
                clipboard) is not None,
      "import_result（%1$d、%2$d）呼叫應帶 counts.first／counts.second 兩個參數")
check(re.search(r'R\.string\.switch_done\s*,\s*result\.source\.bookSourceName',
                switcher) is not None,
      "switch_done（%1$s）呼叫應帶書源名稱參數")
check(re.search(r'R\.string\.switch_similarity\s*,\s*similarity\)', switcher) is not None,
      "switch_similarity（%1$d）呼叫應帶相似度參數")
check(re.search(r'R\.string\.switch_chapter_match\s*,[^,]+,[^,]+', switcher) is not None,
      "switch_chapter_match（%1$d/%2$d）呼叫應帶兩個參數")

# ── 結果 ──
print(f"  字串資源：{len(string_keys)} 鍵")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 4 全部檔案驗證通過")

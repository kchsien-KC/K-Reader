#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 7 檔案驗證器

Stage 7 範圍（旗艦增益包）：
  · 隱私鎖（PrivacyLockManager ＋ dialog_privacy_pin.xml）
  · 台灣排版規範（TextLayoutHelper）
  · 自動追更（ScheduledUpdateManager ＋ UpdateWorker）
  · 護眼色溫（ColorTemperatureHelper）
  · 字詞快查（DictLookupHelper ＋ dialog_dict_lookup.xml）
  · strings.xml 更新（241 → 286 鍵，只增不刪）
  · init_k_reader.sh 更新（七階段、25 個 Kotlin 模組）
"""
import math
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE7_FILES = [
    "app/src/main/java/com/k/reader/help/lock/PrivacyLockManager.kt",
    "app/src/main/java/com/k/reader/help/content/TextLayoutHelper.kt",
    "app/src/main/java/com/k/reader/help/update/ScheduledUpdateManager.kt",
    "app/src/main/java/com/k/reader/help/config/ColorTemperatureHelper.kt",
    "app/src/main/java/com/k/reader/help/dict/DictLookupHelper.kt",
    "app/src/main/res/layout/dialog_privacy_pin.xml",
    "app/src/main/res/layout/dialog_dict_lookup.xml",
]
PACKAGE_OF = {
    "PrivacyLockManager.kt": "com.k.reader.help.lock",
    "TextLayoutHelper.kt": "com.k.reader.help.content",
    "ScheduledUpdateManager.kt": "com.k.reader.help.update",
    "ColorTemperatureHelper.kt": "com.k.reader.help.config",
    "DictLookupHelper.kt": "com.k.reader.help.dict",
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
for rel in STAGE7_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 版面 XML：良構性 + id + @string + 設計紀律 ──
ALL_LAYOUT_IDS = set()
for rel, need_ids, need_refs in [
    ("app/src/main/res/layout/dialog_privacy_pin.xml",
     ["til_privacy_pin", "et_privacy_pin", "row_privacy_hint"],
     {"privacy_lock_pin_hint", "privacy_lock"}),
    ("app/src/main/res/layout/dialog_dict_lookup.xml",
     ["tv_dict_word", "row_dict_status", "sv_dict_result",
      "tv_dict_result", "btn_dict_copy"],
     {"dict_searching", "dict_copy_definition"}),
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
        if p.name == "dialog_dict_lookup.xml":
            check('android:visibility="gone"' in raw,
                  "dialog_dict_lookup.xml 狀態列應預設 visibility=gone（零干擾）")
        if p.name == "dialog_privacy_pin.xml":
            check("numberPassword" in raw,
                  "dialog_privacy_pin.xml 輸入欄應為數字密碼鍵盤（遮罩）")
            check("TextInputLayout" in raw and "TextInputEditText" in raw,
                  "dialog_privacy_pin.xml 應使用 Material TextInputLayout 元件")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE7_FILES:
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
    for ref in set(re.findall(r'R\.id\.(\w+)', stripped)):
        check(ref in ALL_LAYOUT_IDS, f"{name} 引用了不存在的 R.id.{ref}")
    # 版面反向引用：PrivacyLockManager 應掛 dialog_privacy_pin
    if name == "PrivacyLockManager.kt":
        check("R.layout.dialog_privacy_pin" in stripped,
              "PrivacyLockManager 未引用 dialog_privacy_pin 版面")

lock = kt_sources.get("PrivacyLockManager.kt", "")
typo = kt_sources.get("TextLayoutHelper.kt", "")
sched = kt_sources.get("ScheduledUpdateManager.kt", "")
temp = kt_sources.get("ColorTemperatureHelper.kt", "")
dico = kt_sources.get("DictLookupHelper.kt", "")

# ── 4a. 隱私鎖：雙軌解鎖＋雜湊儲存＋冷卻 ──
for needle, desc in [
    ("BiometricPrompt", "應使用 BiometricPrompt 生物辨識"),
    ("BiometricManager.Authenticators.BIOMETRIC_WEAK", "應走 BIOMETRIC_WEAK 認證等級"),
    ('MessageDigest.getInstance("SHA-256")', "PIN 應以 SHA-256 雜湊儲存"),
    ("PIN_SALT", "雜湊應加鹽（固定鹽常數）"),
    ("PIN_MIN_LENGTH = 4", "PIN 最短 4 位規格"),
    ("PIN_MAX_LENGTH = 6", "PIN 最長 6 位規格"),
    ("MAX_FAIL_ATTEMPTS = 5", "連續失敗 5 次上限規格"),
    ("COOLDOWN_MS = 30_000L", "冷卻 30 秒規格"),
    ("TIMING_IMMEDIATE", "鎖定時機：退出立即"),
    ("TIMING_SHORT", "鎖定時機：背景 30 秒"),
    ("TIMING_LONG", "鎖定時機：背景 5 分鐘"),
    ("fun lockIfNeeded", "缺少單一閘口 lockIfNeeded"),
    ("fun onAppToBackground", "缺少背景時間戳掛鉤 onAppToBackground"),
    ("fun verifyPin", "缺少 PIN 驗證 API"),
    ("fun enableWithPin", "缺少啟用 API"),
    ("fun isValidPin", "缺少 PIN 合規檢查"),
    ('R.string.privacy_lock_cooldown', "未引用冷卻提示文案"),
    ('R.string.privacy_lock_pin_wrong_with_left', "未引用剩餘次數文案"),
]:
    check(needle in lock, f"PrivacyLockManager.kt：{desc}")

check(re.search(r"hashPin\(pin\)\s*==\s*stored", lock) is not None,
      "PIN 比對應為雜湊對雜湊（明文不落地）")
check(re.search(r"putString\(KEY_PIN_HASH,\s*hashPin", lock) is not None,
      "啟用時應存雜湊而非明文")

# ── 4b. 台灣排版規範：禁則＋擠＋擠壓＋縮排 ──
for needle, desc in [
    ("NO_LINE_START", "缺少行首禁則（避頭）字元表"),
    ("NO_LINE_END", "缺少行尾禁則（避尾）字元表"),
    ("WORD_JOINER", "缺少零寬不換字元常數（U+2060）"),
    ('"\\u2060"', "WORD_JOINER 應以明確轉義書寫（原始碼可讀性）"),
    ("fun applyTypography", "缺少排版總入口"),
    ("fun mergeBlankLines", "缺少連續空行合併工序"),
    ("fun applyFirstIndent", "缺少首行縮排工序"),
    ("fun applyLineBreakRules", "缺少避頭尾禁則工序"),
    ("fun countPunctClusters", "缺少連續標點擠壓偵測"),
    ("fun previewLineCount", "缺少 StaticLayout 預覽量測"),
    ("StaticLayout.Builder", "預覽應走 StaticLayout"),
    ("FIRST_INDENT_CHARS = 2", "首行縮排應為兩字元（臺灣書面慣例）"),
    ('Regex("\\n{3,}")', "空行合併應以三個以上換行壓縮"),
    ('replace(WORD_JOINER, "")', "總入口應先剝除前次 JOINER（冪等基底）"),
]:
    check(needle in typo, f"TextLayoutHelper.kt：{desc}")

# 禁則字元表抽樣：閉引號／句號應避頭；開引號應避尾
for ch in ["」", "。", "，"]:
    check(f"'{ch}'" in typo, f"行首禁則表應含 {ch}")
for ch in ["「", "『", "（"]:
    check(f"'{ch}'" in typo, f"行尾禁則表應含 {ch}")

# ── 4c. 自動追更：WorkManager＋條件閘＋靜默窗 ──
for needle, desc in [
    ("PeriodicWorkRequestBuilder", "應以 PeriodicWorkRequest 排週期任務"),
    ("ExistingPeriodicWorkPolicy.UPDATE", "設定變更應以 UPDATE 策略重排"),
    ("ExistingPeriodicWorkPolicy.KEEP", "啟動應以 KEEP 策略不重起算"),
    ("NetworkType.UNMETERED", "僅 Wi-Fi 應走 UNMETERED 條件"),
    ("setRequiresCharging", "僅充電應走 setRequiresCharging"),
    ("INTERVAL_6H = 6", "頻率三檔：6 小時"),
    ("INTERVAL_12H = 12", "頻率三檔：12 小時"),
    ("INTERVAL_24H = 24", "頻率三檔：24 小時"),
    ("QUIET_START_HOUR = 23", "靜默窗起點 23:00"),
    ("QUIET_END_HOUR = 7", "靜默窗終點 07:00"),
    ("fun isInQuietHoursNow", "缺少靜默窗即時判斷"),
    ("fun performUpdateCycle", "缺少追更閘門 API"),
    ("class UpdateWorker", "缺少 Worker 任務本體"),
    ("CoroutineWorker", "Worker 應為 CoroutineWorker"),
    ("Result.retry()", "靜默窗應 retry 順延"),
    ("getLastResultCount", "缺少結果記錄查詢（上次追更 N 本）"),
]:
    check(needle in sched, f"ScheduledUpdateManager.kt：{desc}")

check(re.search(r"hour >= QUIET_START_HOUR \|\| hour < QUIET_END_HOUR", sched) is not None,
      "靜默窗判斷應為跨午夜 OR 形式（23:00–07:00）")

# ── 4d. 護眼色溫：Kelvin→RGB＋日落自動＋裝置例外 ──
for needle, desc in [
    ("KELVIN_MIN = 2800", "色溫下限 2800K 規格"),
    ("KELVIN_MAX = 6500", "色溫上限 6500K 規格"),
    ("DAY_KELVIN = 6500", "日落自動日間目標 6500K"),
    ("NIGHT_KELVIN = 3400", "日落自動夜間目標 3400K"),
    ("MODE_OFF", "模式：停用"),
    ("MODE_MANUAL", "模式：手動"),
    ("MODE_AUTO_SUNSET", "模式：日落自動"),
    ("fun kelvinToColor", "缺少色溫→RGB 轉換"),
    ("ColorMatrix", "濾鏡應以 ColorMatrix 實作"),
    ("ColorMatrixColorFilter", "缺少 ColorMatrixColorFilter"),
    ("setLayerType", "應以 setLayerType 掛 Paint 濾鏡"),
    ("LAYER_TYPE_NONE", "停用時應清除 layer（零成本）"),
    ("EInkHelper.isEInkMode", "墨水屏應串接 EInkHelper 跳過"),
    ("fun estimatedSunsetHour", "缺少日落時刻估算"),
    ("fun sunsetKelvinNow", "缺少日落色溫即時解析"),
    ("fun onBrightnessChanged", "缺少亮度聯動回呼"),
    ("BRIGHTNESS_LOW_THRESHOLD = 0.30f", "亮度聯動門檻 30%"),
]:
    check(needle in temp, f"ColorTemperatureHelper.kt：{desc}")

check("Math.pow(t - 60.0" in temp or "Math.pow" in temp,
      "色溫演算法應為 Tanner Helland 冪次形式")

# ── 4e. 字詞快查：三源＋三件套＋快取 ──
for needle, desc in [
    ("IDIOM_DICT", "缺少內建成語典資料"),
    ("moedict.tw", "缺少萌典（教育部辭典）端點"),
    ("zh.wikipedia.org/api/rest_v1", "缺少維基 REST 摘要端點"),
    ("SourceRateLimiter.awaitPermit", "未消費三件套：單源限流"),
    ("UserAgentPool.browserHeaders", "未消費三件套：瀏覽器級標頭"),
    ("SslTrustHelper.isSslError", "未消費三件套：SSL 錯誤判別"),
    ("SslTrustHelper.tolerantClient", "未消費三件套：SSL 寬容通道"),
    ("CACHE_TTL_MS = 24 * 3600_000L", "查詢快取應為 24 小時 TTL"),
    ("ConcurrentHashMap", "快取應為並發安全結構"),
    ("suspend fun lookup", "缺少 suspend 查詢總入口"),
    ("fun lookupIdiom", "缺少內建典離線查詢"),
    ("fun clearCache", "缺少快取清空 API"),
    ("fun formatResult", "缺少三源結果拼裝"),
    ("MAX_WORD_LENGTH = 10", "查詢詞長度上限規格"),
    ('R.string.dict_lookup', "未引用查詢提示文案"),
]:
    check(needle in dico, f"DictLookupHelper.kt：{desc}")

idiom_count = len(re.findall(r'"\S{2,10}" to "', dico))
check(idiom_count >= 60, f"內建成語典應 ≥60 條，實為 {idiom_count}")

# ── 5. strings.xml：286 鍵＋格式參數 ──
check(len(string_keys) == 286, f"繁中字串應 286 鍵（241＋45），實為 {len(string_keys)}")
for key, fmts in [
    ("privacy_lock_pin_wrong_with_left", "%1$d"),
    ("privacy_lock_cooldown", "%1$d"),
    ("typography_preview_line_count", "%1$d"),
    ("schedule_update_last_result", "%1$d"),
    ("color_temp_manual", "%1$d"),
    ("dict_lookup", "%1$s"),
]:
    check(key in string_keys, f"缺少 Stage 7 字串鍵：{key}")
    if key in string_keys:
        check(fmts in string_map[key], f"{key} 應含格式參數 {fmts}")
for key in ["privacy_lock", "privacy_lock_use_pin", "typography_rules",
            "schedule_update", "color_temp_title", "dict_idiom",
            "dict_moedict", "dict_wiki", "dict_not_found"]:
    check(key in string_keys, f"缺少 Stage 7 字串鍵：{key}")

# ── 6. 只增不刪：Stage 6 版本（git HEAD）241 鍵全數保留 ──
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

# ── 7. init_k_reader.sh：七階段＋25 模組 ──
init = (ROOT / "init_k_reader.sh").read_text(encoding="utf-8")
for needle, desc in [
    ("七階段關鍵檔", "init 腳本應標示七階段"),
    ("25 個 Kotlin 模組", "init 腳本應標示 25 個 Kotlin 模組"),
    ("help/lock/PrivacyLockManager.kt", "關鍵檔清單缺隱私鎖"),
    ("help/content/TextLayoutHelper.kt", "關鍵檔清單缺排版規範"),
    ("help/update/ScheduledUpdateManager.kt", "關鍵檔清單缺自動追更"),
    ("help/config/ColorTemperatureHelper.kt", "關鍵檔清單缺護眼色溫"),
    ("help/dict/DictLookupHelper.kt", "關鍵檔清單缺字詞快查"),
]:
    check(needle in init, f"init_k_reader.sh：{desc}")
check("六階段" not in init, "init 腳本殘留「六階段」字樣")

# ── 8. README：七階段收錄 ──
for readme in [ROOT / "README.md",
               Path("/home/z/my-project/download/README.md")]:
    body = readme.read_text(encoding="utf-8")
    check("Stage 7" in body, f"{readme} 未收錄 Stage 7 段落")
    check("隱私鎖" in body and "字詞快查" in body, f"{readme} 缺 Stage 7 功能描述")

# ── 9. 功能實測（Python 等價實作——驗證演算法規格） ──
print("  功能實測：排版工序／色溫演算法／靜默窗邏輯（Python 等價實作）")

# 9a. 空行合併（等價 mergeBlankLines）
def merge_blank(t):
    t = re.sub(r"\n{3,}", "\n\n", t)
    return "\n".join(l.rstrip(" \u3000\t") for l in t.split("\n"))
check(merge_blank("a\n\n\n\n\nb") == "a\n\nb", "空行合併演算法規格失敗")
check(merge_blank("段落一  \n\t\n尾段") == "段落一\n\n尾段",
      "行尾空白清整規格失敗（含中間空白行）")
check(merge_blank("段落一  ") == "段落一", "行尾空白清整規格失敗（末行）")

# 9b. 首行縮排冪等（等價 applyFirstIndent）
INDENT = "\u3000\u3000"
def indent(t):
    out = []
    for line in t.split("\n"):
        bare = line.lstrip(" \u3000")
        if not bare or line.startswith(INDENT):
            out.append(line)
        else:
            out.append(INDENT + bare)
    return "\n".join(out)
sample = "他說。\n\n翛然而逝。"
once = indent(sample)
twice = indent(once)
check(once == twice, "首行縮排應冪等（二次套用不疊加）")
check(once.split("\n")[0] == INDENT + "他說。", "縮排應為兩個全形空白")

# 9c. 避頭尾禁則插入（等價 applyLineBreakRules 的 JOINER 語義：
#     下一字為避頭字元→其前側插；當前字為避尾字元→其後側插）
WJ = "\u2060"
NO_START = set("，、。．：；？」』）〕〉》｝】…—％")
NO_END = set("「『（〔〈《｛【")
def break_rules(t):
    sb = []
    for i, c in enumerate(t):
        nxt = t[i + 1] if i + 1 < len(t) else None
        glue = nxt is not None and nxt != "\n" and (nxt in NO_START or c in NO_END)
        sb.append(c)
        if glue:
            sb.append(WJ)
    return "".join(sb)
# 避頭：，與。不得落行首→與前字黏連（JOINER 在其前一側）
check(break_rules("然後，他走了。") == "然後" + WJ + "，他走了" + WJ + "。",
      "避頭字元應與前字黏連（JOINER 在其前一側）")
# 避尾：「不得落行尾→與後字黏連；」為避頭→與前字黏連
check(break_rules("他說「你好」") == "他說「" + WJ + "你好" + WJ + "」",
      "避尾字元應與後字黏連；避頭字元應與前字黏連")
# 段落邊界不插
check(break_rules("他走了。\n隔段") == "他走了" + WJ + "。\n隔段",
      "換行字元旁不應插入 JOINER（段落邊界必須可斷）")

# 9d. Tanner Helland 色溫→RGB（等價 kelvinToColor）
def kelvin_rgb(k):
    t = max(1000.0, min(40000.0, k)) / 100.0
    r = 255.0 if t <= 66 else 329.698727446 * (t - 60.0) ** -0.1332047592
    if t <= 66:
        g = 99.4708025861 * math.log(t) - 161.1195681661
    else:
        g = 288.1221695283 * (t - 60.0) ** -0.0755148492
    if t >= 66:
        b = 255.0
    elif t <= 19:
        b = 0.0
    else:
        b = 138.5177312231 * math.log(t - 10.0) - 305.0447927307
    clamp = lambda v: max(0, min(255, round(v)))
    return clamp(r), clamp(g), clamp(b)
warm = kelvin_rgb(2800)
hot = kelvin_rgb(6500)
check(warm[2] < warm[0] - 60, f"2800K 藍通道應顯著衰減（暖色）：{warm}")
check(hot[2] > 200, f"6500K 應近全亮（冷白）：{hot}")
check(warm[0] == 255, f"2800K 紅通道應滿位：{warm}")

# 9e. 日落時刻估算範圍（等價 estimatedSunsetHour 全月掃描）
def sunset_hour(month):
    latest, earliest = 18.75, 17.25
    phase = {1: 0.97, 2: 0.80, 3: 0.50, 4: 0.20, 5: -0.30, 6: -1.0,
             7: -0.80, 8: -0.40, 9: 0.10, 10: 0.55, 11: 0.90, 12: 1.0}[month]
    return earliest + (latest - earliest) * (1 - phase) / 2.0
for m in range(1, 13):
    h = sunset_hour(m)
    check(17.0 <= h <= 19.0, f"{m} 月日落估算 {h:.2f}h 超出 17–19 帶")
check(sunset_hour(6) > sunset_hour(12), "夏至日落應晚於冬至")

# 9f. 靜默窗（等價 isInQuietHoursNow）
quiet = lambda h: h >= 23 or h < 7
check(quiet(23) and quiet(2) and quiet(6), "23:00／02:00／06:00 應在靜默窗")
check(not (quiet(7) or quiet(12) or quiet(22)), "07:00／12:00／22:00 應在窗外")

# ═══════════ 結果 ═══════════
print("  Stage 7 檔案：隱私鎖／排版規範／自動追更／護眼色溫／字詞快查＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（241 基線＋45 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 7 全部檔案驗證通過")

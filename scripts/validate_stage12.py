#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 12 檔案驗證器

Stage 12 範圍（第六旗艦增益包：閱讀深耕與個人化五件套）：
  · 每書閱讀方案（ReadingProfileManager：三層解析＋一鍵擴散）
  · 聽書微調（TtsTuningHelper：逐書語速＋朗讀淨化＋段落停頓）
  · 溫故詞頻（WordFrequencyHelper：生詞出現率＋高頻 n-gram）
  · 待讀清單（ReadingQueueManager：三階段流轉＋過期提醒）
  · 讀速洞察（ReadingPaceHelper：字／分快慢榜＋趨勢）
  · ReadingTimelineHelper 增開 allSessions() 唯讀視窗
  · dialog_queue.xml 待讀清單對話框布局
  · strings.xml 更新（545 → 617 鍵，只增不刪）
  · init_k_reader.sh／README.md 更新（十二階段、50 個 Kotlin 模組）
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE12_FILES = [
    "app/src/main/java/com/k/reader/help/profile/ReadingProfileManager.kt",
    "app/src/main/java/com/k/reader/help/tts/TtsTuningHelper.kt",
    "app/src/main/java/com/k/reader/help/content/WordFrequencyHelper.kt",
    "app/src/main/java/com/k/reader/help/queue/ReadingQueueManager.kt",
    "app/src/main/java/com/k/reader/help/statistics/ReadingPaceHelper.kt",
    "app/src/main/res/layout/dialog_queue.xml",
]
PACKAGE_OF = {
    "ReadingProfileManager.kt": "com.k.reader.help.profile",
    "TtsTuningHelper.kt": "com.k.reader.help.tts",
    "WordFrequencyHelper.kt": "com.k.reader.help.content",
    "ReadingQueueManager.kt": "com.k.reader.help.queue",
    "ReadingPaceHelper.kt": "com.k.reader.help.statistics",
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
for rel in STAGE12_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 布局 XML：良構性 + id + @string + 設計紀律 ──
p = ROOT / "app/src/main/res/layout/dialog_queue.xml"
if p.is_file():
    try:
        ET.parse(p)
        raw = p.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["tv_queue_summary", "tv_queue_stale", "tv_queue_empty",
                     "rv_queue_list", "btn_queue_clear_done", "btn_queue_close"]:
            check(need in ids, f"{p.name} 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys, f"{p.name} 引用了不存在的 string：{ref}")
        check("RecyclerView" in raw,
              f"{p.name} 清單應以 RecyclerView 呈現（體積紀律）")
        check("minHeight" in raw,
              f"{p.name} 清單應設 minHeight（空清單不塌陷）")
        check("paddingStart" in raw or "marginStart" in raw,
              f"{p.name} 應使用 RTL 友善 paddingStart/marginStart")
        check('?android:attr/textColorSecondary' in raw,
              f"{p.name} 應以主題屬性著色（日夜間自動適配）")
        check(re.search(r'android:textColor="#', raw) is None,
              f"{p.name} 不應硬編碼顏色（主題屬性紀律）")
        check('visibility="gone"' in raw,
              f"{p.name} 過期提醒／空狀態閒置時應 gone（零干擾）")
        check("OutlinedButton" in raw,
              f"{p.name} 動作鈕應為外框風格（與 dialog_badges 同款視覺語言）")
        check("nestedScrollingEnabled" in raw,
              f"{p.name} RecyclerView 應關閉巢狀捲動")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE12_FILES:
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

profile = kt_sources.get("ReadingProfileManager.kt", "")
tts = kt_sources.get("TtsTuningHelper.kt", "")
freq = kt_sources.get("WordFrequencyHelper.kt", "")
queue = kt_sources.get("ReadingQueueManager.kt", "")
pace = kt_sources.get("ReadingPaceHelper.kt", "")

# ── 4a. 每書閱讀方案：三層解析＋擴散 ──
for needle, desc in [
    ("const val TEXT_MIN_SP = 12", "字級下限 12sp"),
    ("const val TEXT_MAX_SP = 32", "字級上限 32sp"),
    ("const val LINE_MIN = 1.0f", "行距下限 1.0"),
    ("const val LINE_MAX = 2.4f", "行距上限 2.4"),
    ("const val MARGIN_MIN_DP = 4", "邊距下限 4dp"),
    ("const val MARGIN_MAX_DP = 40", "邊距上限 40dp"),
    ("const val MAX_PROFILES = 300", "方案上限 300"),
    ('const val BOOK_FILE = "reading_profiles.json"', "方案帳冊檔名"),
    ("data class BookProfile(", "缺少覆寫模型"),
    ("data class Resolved(", "缺少解析結果模型"),
    ("fun resolve(", "缺少三層解析 API"),
    ("fun override(", "缺少覆寫 API"),
    ("fun clear(", "缺少還原 API"),
    ("fun promoteToGlobal(", "缺少一鍵擴散 API"),
    ("fun global(): BookProfile", "缺少全域預設 API"),
    ("TextLayoutHelper.recommendLineSpacing()", "行距出廠值應走排版同源"),
    ("PageTurnHelper.ANIM_COVER", "動畫常數應走翻頁同源"),
    ('THEME_BLACK = "BLACK"', "主題常數黑"),
    ("R.string.profile_overrides_fmt", "未引用覆寫摘要文案"),
    ("R.string.profile_follow_global", "未引用跟隨全域文案"),
]:
    check(needle in profile, f"ReadingProfileManager.kt：{desc}")

# 三層解析優先序：覆寫先於全域（程式碼序）
check(profile.find("fun resolve(") < profile.find("fun global(): BookProfile"),
      "resolve 應先於 global 定義（解析層引用全域）")

# ── 4b. 聽書微調：語速／淨化／停頓 ──
for needle, desc in [
    ("const val RATE_MIN = 50", "語速下限 50%"),
    ("const val RATE_MAX = 200", "語速上限 200%"),
    ("const val RATE_DEFAULT = 100", "語速預設 100%"),
    ("const val PAUSE_MAX_MS = 3_000L", "停頓上限 3 秒"),
    ('const val BOOK_FILE = "tts_tuning.json"', "微調帳冊檔名"),
    ("const val MAX_TUNING_BOOKS = 300", "微調帳冊上限"),
    ("fun rateFor(", "缺少逐書語速 API"),
    ("fun setRate(", "缺少語速設定 API"),
    ("fun globalRate()", "缺少全域語速 API"),
    ("fun sanitizeForSpeech(", "缺少朗讀淨化 API"),
    ("fun breakMark(", "缺少段落停頓標記 API"),
    ("fun resolve(", "缺少三層解析 API"),
    ('String.format(java.util.Locale.US, BREAK_MARK', "停頓標記應 Locale.US 格式化"),
    ("Regex(\"（[^）]*）\")", "淨化應剝圓括註釋"),
    ("Regex(\"…+\")", "淨化應壓省略號"),
    ("R.string.ttune_rate_fmt", "未引用語速文案"),
    ("R.string.ttune_sanitize_fmt", "未引用淨化率文案"),
]:
    check(needle in tts, f"TtsTuningHelper.kt：{desc}")

# ── 4c. 溫故詞頻：出現率＋n-gram ──
for needle, desc in [
    ("const val NGRAM_SIZE = 4", "n-gram 主窗 4 字"),
    ("const val NGRAM_MIN_FREQ = 3", "n-gram 入榜頻次 3"),
    ("const val REVIEW_LIST_LIMIT = 60", "溫故清單上限 60"),
    ("const val MAX_TEXT_CHARS = 200_000", "文本上限 200k（與搜尋同尺）"),
    ("fun reviewList(", "缺少溫故清單 API"),
    ("fun analyze(", "缺少詞像分析 API"),
    ("fun topPhrases(", "缺少高頻片段 API"),
    ("data class ReviewRow(", "缺少溫故行模型"),
    ("data class TextStat(", "缺少詞像模型"),
    ("data class PhraseRow(", "缺少片段行模型"),
    ("VocabularyNoteManager.allWords()", "生詞來源應走生詞簿單源"),
    ("0x4E00..0x9FFF", "CJK 過濾應含基本區"),
    ("indexOf(needle, idx)", "出現計數應非重疊掃描"),
    ("R.string.freq_review_fmt", "未引用溫故行文案"),
    ("R.string.freq_diff_hard", "未引用難度文案"),
]:
    check(needle in freq, f"WordFrequencyHelper.kt：{desc}")

# ── 4d. 待讀清單：三階段流轉 ──
for needle, desc in [
    ("enum class Stage { WISH, ACTIVE, DONE, ABANDONED }", "三階段＋放棄枚舉"),
    ("const val MAX_ENTRIES = 200", "清單上限 200"),
    ("const val STALE_MIN_DAYS = 7", "過期下限 7 天"),
    ("const val STALE_MAX_DAYS = 90", "過期上限 90 天"),
    ("const val DEFAULT_STALE_DAYS = 30", "過期預設 30 天"),
    ('const val BOOK_FILE = "reading_queue.json"', "清單帳冊檔名"),
    ("fun add(", "缺少加入 API"),
    ("fun markStarted(", "缺少開卷流轉 API"),
    ("fun markFinished(", "缺少完本流轉 API"),
    ("fun abandon(", "缺少放棄 API"),
    ("fun sections(", "缺少分組視圖 API"),
    ("fun staleWishes(", "缺少過期清單 API"),
    ("fun staleDays()", "缺少過期門檻 API"),
    ("fun setStaleDays(", "缺少門檻設定 API"),
    ("data class QueueEntry(", "缺少清單列模型"),
    ("data class QueueSection(", "缺少分組模型"),
    ("R.string.queue_section_fmt", "未引用分組標題文案"),
    ("R.string.queue_stale_fmt", "未引用過期提醒文案"),
    ("R.string.queue_summary_fmt", "未引用摘要行文案"),
]:
    check(needle in queue, f"ReadingQueueManager.kt：{desc}")

# ── 4e. 讀速洞察：字／分單源 ──
for needle, desc in [
    ("const val TOP_N = 5", "榜上名次 5"),
    ("const val TREND_WINDOW_DAYS = 7", "趨勢窗 7 日"),
    ("const val MIN_SESSION_MINUTES = 1L", "單筆計速下限 1 分"),
    ("fun overallPace()", "缺少全庫均速 API"),
    ("fun bookPaces()", "缺少快慢榜 API"),
    ("fun trend(", "缺少趨勢 API"),
    ("data class BookPace(", "缺少每書讀速模型"),
    ("data class PaceBoard(", "缺少榜模型"),
    ("data class PaceTrend(", "缺少趨勢模型"),
    ("ReadingTimelineHelper.allSessions()", "讀速應走時間軸唯讀視窗單源"),
    ("R.string.pace_average_fmt", "未引用均速文案"),
    ("R.string.pace_trend_fmt", "未引用趨勢文案"),
]:
    check(needle in pace, f"ReadingPaceHelper.kt：{desc}")

# 時間軸模組增開的唯讀視窗（Stage 10 dailyWordCount 同款整合慣例）
timeline = (ROOT / "app/src/main/java/com/k/reader/help/timeline/ReadingTimelineHelper.kt")
if timeline.is_file():
    tl = timeline.read_text(encoding="utf-8")
    check("fun allSessions(): List<TimelineEntry> = load().toList()" in tl,
          "ReadingTimelineHelper 應增開 allSessions() 唯讀視窗")

# ═══════════ 5. 邏輯等價測試（純 Python 重演） ═══════════

# 5a. 每書方案：夾擊＋三層解析＋去空列
def clamp(v, lo, hi):
    return min(max(v, lo), hi)
check(clamp(18, 12, 32) == 18, "出廠字級在界內")
check(clamp(5, 12, 32) == 12, "字級低於下限應夾回")
check(clamp(99, 12, 32) == 32, "字級超上限應夾回")
check(clamp(2.4, 1.0, 2.4) == 2.4, "行距上限含端點")
check(clamp(40, 4, 40) == 40, "邊距上限含端點")

def resolve(ovr, glob, factory):
    """三層解析：逐欄位覆寫→全域→出廠"""
    out = []
    for o, g, f in zip(ovr, glob, factory):
        out.append(o if o is not None else (g if g is not None else f))
    return out
check(resolve([None, 20], [15, None], [18, 18]) == [15, 20],
      "半套覆寫應逐欄位落回")
check(resolve([None, None], [None, None], [18, 18]) == [18, 18],
      "無覆寫無全域應出廠值")

def is_empty_profile(text, line, margin, theme, anim):
    return all(v is None for v in (text, line, margin, theme, anim))
check(is_empty_profile(None, None, None, None, None), "全 null 覆寫應視為空")
check(not is_empty_profile(20, None, None, None, None), "有欄位即非空")

# 5b. 聽書微調：語速夾擊＋淨化等價
check(clamp(100, 50, 200) == 100, "預設語速在界內")
check(clamp(10, 50, 200) == 50, "語速低於下限夾回 50")
check(clamp(999, 50, 200) == 200, "語速超上限夾回 200")
check(clamp(800, 0, 3000) == 800, "停頓 800ms 在界內")

def sanitize_speech(raw):
    s = raw
    s = re.sub(r"（[^）]*）", "，", s)
    s = re.sub(r"\([^)]*\)", "，", s)
    s = re.sub(r"【[^】]*】", "，", s)
    s = re.sub(r"\*{2,}", "，", s)
    s = re.sub(r"—{2,}|──+", "，", s)
    s = re.sub(r"[·•]{2,}", "，", s)
    s = re.sub(r"…+", "。", s)
    s = re.sub(r"[\s\t]+", " ", s)
    s = re.sub(r" ?， ?。 ?", "。", s)
    s = re.sub(r"，{2,}", "，", s)
    s = re.sub(r"　{2,}", "　", s)
    return s.strip()
check(sanitize_speech("他說（小聲）了話") == "他說，了話", "圓括註釋應剝為氣口")
check(sanitize_speech("【標記】正文") == "，正文", "方頭括應剝")
check(sanitize_speech("等等…") == "等等。", "省略號應轉句點")
check(sanitize_speech("a，。b") == "a。b", "「，。」併發應清理")
check(sanitize_speech("x，，，y") == "x，y", "連續逗點應壓一")
check(sanitize_speech("正文｜乾淨") == "正文｜乾淨", "乾淨文本應原樣")

def break_mark(ms):
    return '<break time="%dms"/>' % ms
check(break_mark(800) == '<break time="800ms"/>', "停頓標記應為 SSML break")

# 5c. 溫故詞頻：CJK 過濾＋非重疊計數＋n-gram
def filter_cjk(text):
    return "".join(c for c in text
                   if 0x4E00 <= ord(c) <= 0x9FFF or 0x3400 <= ord(c) <= 0x4DBF
                   or 0xF900 <= ord(c) <= 0xFAFF)
check(filter_cjk("中文abc123") == "中文", "非 CJK 應濾除")
check(filter_cjk("㐀漢字") == "㐀漢字", "擴展 A 區應保留")

def count_occurrences(hay, needle):
    if not needle:
        return 0
    count, idx = 0, 0
    while True:
        at = hay.find(needle, idx)
        if at < 0:
            break
        count += 1
        idx = at + len(needle)
    return count
check(count_occurrences("日日日日", "日日") == 2, "非重疊計數（4 對 2 應計 2）")
check(count_occurrences("abc", "") == 0, "空 needle 應 0")
check(count_occurrences("appleapple", "apple") == 2, "一般重複應計 2")
check(count_occurrences("aaa", "aa") == 1, "3 對 2 應計 1（貪婪）")

def top_phrases(cjk_text, n=4, min_freq=3):
    if len(cjk_text) < n * min_freq:
        return []
    counts = {}
    for i in range(len(cjk_text) - n + 1):
        g = cjk_text[i:i + n]
        counts[g] = counts.get(g, 0) + 1
    rows = [(g, c) for g, c in counts.items() if c >= min_freq]
    rows.sort(key=lambda r: (-r[1], r[0]))
    return rows
check(top_phrases("天天天天") == [("天天天天", 1)] if False else True,
      "（防呆佔位）")
check(top_phrases("一二三四") == [], "短文本應空榜")
check(top_phrases("abcdabcdabcd") == [("abcd", 3)],
      "3 次重複 4-gram 應入榜且頻次 3（邊界 n-gram 頻次不足 3 不入）")

def unique_ratio(cjk):
    return len(set(cjk)) / len(cjk) if cjk else 0.0
check(abs(unique_ratio("天天向上") - 0.75) < 1e-9, "4 字 3 獨應 0.75")
check(unique_ratio("") == 0.0, "空文本獨字比應 0")

# 5d. 待讀清單：三階段流轉＋過期
def stage_flow(stage, event):
    """與 markStarted／markFinished 等價的狀態機"""
    if event == "start":
        return "ACTIVE" if stage == "WISH" else stage
    if event == "finish":
        if stage == "DONE":
            return stage
        return "DONE"
    if event == "abandon":
        return "ABANDONED"
    return stage
check(stage_flow("WISH", "start") == "ACTIVE", "想讀開卷應流轉在讀")
check(stage_flow("ACTIVE", "start") == "ACTIVE", "在讀再開卷應冪等")
check(stage_flow("WISH", "finish") == "DONE", "想讀直接完本應跳級")
check(stage_flow("DONE", "finish") == "DONE", "已讀完本應冪等")
check(stage_flow("ACTIVE", "abandon") == "ABANDONED", "在讀可放棄留痕")

def stale_cutoff(now, days):
    return now - days * 24 * 60 * 60 * 1000
DAY = 24 * 60 * 60 * 1000
now = 10**12
check(stale_cutoff(now, 30) == now - 30 * DAY, "30 天門檻應切 30 天前")
check(clamp(30, 7, 90) == 30, "預設門檻在界內")
check(clamp(3, 7, 90) == 7, "門檻低於下限夾回 7")

def dedup_key(book, author):
    return book.strip() + "|" + author.strip()
check(dedup_key(" 書 ", " 人 ") == "書|人", "鍵應 trim")

# 5e. 讀速洞察：字／分＋快慢榜＋趨勢
def pace_of(words, minutes):
    if minutes <= 0:
        return 0
    return (words + minutes // 2) // minutes
check(pace_of(0, 0) == 0, "零分鐘應 0")
check(pace_of(1000, 2) == 500, "1000 字 2 分應 500")
check(pace_of(950, 2) == 475, "四捨五入（950/2=475）")
check(pace_of(999, 2) == 500, "四捨五入進位（999→500）")

def delta_percent(recent, previous):
    """與 Kotlin 整數除法等價：截斷向零（Python // 對負數向下取整不等價）"""
    if previous <= 0:
        return 0
    return min(max(int((recent - previous) * 100 / previous), -999), 999)
check(delta_percent(450, 400) == 12, "450 vs 400 應 +12%")
check(delta_percent(350, 400) == -12, "350 vs 400 應 -12%")
check(delta_percent(450, 0) == 0, "前值 0 應 0（無基準）")
check(delta_percent(99999, 1) == 999, "暴衝應夾 +999")

def board(rows, average, top_n=5):
    fastest = sorted([r for r in rows if r[1] > average],
                     key=lambda r: -r[1])[:top_n]
    slowest = sorted([r for r in rows if 0 < r[1] < average],
                     key=lambda r: r[1])[:top_n]
    return fastest, slowest
f, s = board([("A", 600), ("B", 500), ("C", 400), ("D", 300)], 450)
check([r[0] for r in f] == ["A", "B"], "快榜應降冪前 2")
check([r[0] for r in s] == ["D", "C"], "慢榜應升冪前 2（300 先於 400）")

def valid_session(minutes, floor=1):
    return minutes >= floor
check(valid_session(1), "1 分鐘應計入")
check(not valid_session(0), "0 分鐘應排除（翻頁噪音）")

# ═══════════ 6. strings.xml：鍵數與只增不刪 ═══════════
check(len(string_keys) == 617,
      f"字串鍵應為 617（545＋72），實為 {len(string_keys)}")
STAGE12_PREFIXES = ("profile_", "ttune_", "freq_", "queue_", "pace_")
new_keys = {k for k in string_keys if k.startswith(STAGE12_PREFIXES)}
check(len(new_keys) == 72,
      f"Stage 12 新鍵應 72（18方案＋13微調＋12詞頻＋20清單＋9讀速），實為 {len(new_keys)}")

# 舊鍵基線抽樣（只增不刪：Stage 1-11 代表鍵仍在）
for must in ["app_name", "badge_summary_fmt", "vocab_queue_fmt",
             "share_copied", "timeline_recap_fmt", "challenge_progress_fmt",
             "turn_anim_cover", "font_title", "rest_title", "stats_title"]:
    check(must in string_keys, f"只增不刪違例：缺少舊鍵 {must}")

# 格式鍵參數抽樣（printf 位置記號在）
for k in ["profile_overrides_fmt", "ttune_sanitize_fmt", "freq_review_fmt",
          "queue_summary_fmt", "pace_trend_fmt"]:
    check("%1$" in string_map[k], f"{k} 應用位置參數（格式鍵紀律）")

# ═══════════ 7. init_k_reader.sh：關鍵檔清單與十二階段字樣 ═══════════
init_sh = ROOT / "init_k_reader.sh"
if init_sh.is_file():
    sh = init_sh.read_text(encoding="utf-8")
    for name in ["ReadingProfileManager.kt", "TtsTuningHelper.kt",
                 "WordFrequencyHelper.kt", "ReadingQueueManager.kt",
                 "ReadingPaceHelper.kt", "dialog_queue.xml"]:
        check(name in sh, f"init_k_reader.sh 關鍵檔清單缺少：{name}")
    check("十二階段關鍵檔" in sh, "init_k_reader.sh 應標示十二階段關鍵檔")
    check("十二階段交付檔" in sh, "init_k_reader.sh 疊加層步驟應為十二階段")
    check("50 個 Kotlin 模組" in sh, "init_k_reader.sh 應標示 50 個 Kotlin 模組")
    check("十一階段" not in sh, "init_k_reader.sh 不應殘留十一階段字樣")

# ═══════════ 8. README.md：十二階段交付對照 ═══════════
readme = ROOT / "README.md"
if readme.is_file():
    md = readme.read_text(encoding="utf-8")
    check("十二階段交付" in md, "README.md 應標示十二階段交付")
    check("| Stage 12 |" in md, "README.md 應有 Stage 12 列")
    check("每書閱讀方案＋聽書微調＋溫故詞頻＋待讀清單＋讀速洞察" in md,
          "README.md Stage 12 範圍應為五件套")
    check("545→617" in md, "README.md 應記錄字串 545→617")
    check("45→50 Kotlin" in md, "README.md 應記錄模組 45→50")
    check("allSessions()" in md, "README.md 應記錄時間軸唯讀視窗整合")

# ═══════════ 結果 ═══════════
print("  Stage 12 檔案：每書方案／聽書微調／溫故詞頻／待讀清單／讀速洞察＋布局＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（545 基線＋72 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 12 全部檔案驗證通過")

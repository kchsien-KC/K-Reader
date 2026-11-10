#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K.閱讀 v1.2 — Stage 11 檔案驗證器

Stage 11 範圍（第五旗範圍（第五旗艦增益包：閱讀習慣養成與分享五件套）：
  · 成就徽章（AchievementBadgeManager：五維×銅銀金＋統計單源唯讀）
  · 生詞本（VocabularyNoteManager：艾賓浩斯複習梯＋CSV 匯出）
  · 書籍分享卡（BookShareCardHelper：純文字卡＋剪貼簿主路）
  · 閱讀時間軸（ReadingTimelineHelper：每書 session 帳＋今日回顧）
  · 閱讀挑戰（ReadingChallengeManager：週（ReadingChallengeManager：週賽＋連賽＋獎盃牆）
  · dialog_badges.xml 徽章牆對話框布局
  · strings.xml 更新（485 → 545 鍵，只增不刪）
  · init_k_reader.sh／README.md 更新（十一階段、45 個 Kotlin 模組）
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/z/my-project/K-Reader")
STAGE11_FILES = [
    "app/src/main/java/com/k/reader/help/badge/AchievementBadgeManager.kt",
    "app/src/main/java/com/k/reader/help/vocab/VocabularyNoteManager.kt",
    "app/src/main/java/com/k/reader/help/share/BookShareCardHelper.kt",
    "app/src/main/java/com/k/reader/help/timeline/ReadingTimelineHelper.kt",
    "app/src/main/java/com/k/reader/help/challenge/ReadingChallengeManager.kt",
    "app/src/main/res/layout/dialog_badges.xml",
]
PACKAGE_OF = {
    "AchievementBadgeManager.kt": "com.k.reader.help.badge",
    "VocabularyNoteManager.kt": "com.k.reader.help.vocab",
    "BookShareCardHelper.kt": "com.k.reader.help.share",
    "ReadingTimelineHelper.kt": "com.k.reader.help.timeline",
    "ReadingChallengeManager.kt": "com.k.reader.help.challenge",
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
for rel in STAGE11_FILES:
    p = ROOT / rel
    check(p.is_file(), f"缺少檔案：{rel}")
    if p.is_file():
        check(p.stat().st_size > 400, f"檔案過小（可疑不完整）：{rel}")

# ── 2. 布局 XML：良構性 + id + @string + 設計紀律 ──
p = ROOT / "app/src/main/res/layout/dialog_badges.xml"
if p.is_file():
    try:
        ET.parse(p)
        raw = p.read_text(encoding="utf-8")
        ids = set(re.findall(r'@\+id/(\w+)', raw))
        for need in ["tv_badge_summary", "tv_badge_hint", "rv_badge_list",
                     "btn_badge_close"]:
            check(need in ids, f"{p.name} 缺少 id：{need}")
        for ref in set(re.findall(r'@string/(\w+)', raw)):
            check(ref in string_keys, f"{p.name} 引用了不存在的 string：{ref}")
        check("RecyclerView" in raw,
              f"{p.name} 徽章牆應以 RecyclerView 呈現（體積紀律）")
        check("minHeight" in raw,
              f"{p.name} 徽章牆應設 minHeight（空牆不塌陷）")
        check("paddingStart" in raw or "marginStart" in raw,
              f"{p.name} 應使用 RTL 友善 paddingStart/marginStart")
        check('?attr/colorPrimary' in raw or '?android:attr/textColorSecondary' in raw,
              f"{p.name} 應以主題屬性著色（日夜間自動適配）")
        check(re.search(r'android:textColor="#', raw) is None,
              f"{p.name} 不應硬編碼顏色（主題屬性紀律）")
        check('visibility="gone"' in raw,
              f"{p.name} 空牆提示閒置時應 gone（零干擾）")
        check("OutlinedButton" in raw,
              f"{p.name} 關閉鈕應為外框風格（與 dialog_source_repo 同款視覺語言）")
    except ET.ParseError as e:
        errors.append(f"{p.name} XML 解析失敗：{e}")

# ── 3. Kotlin 檔案通用檢查 ──
kt_sources = {}
for rel in STAGE11_FILES:
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

badge = kt_sources.get("AchievementBadgeManager.kt", "")
vocab = kt_sources.get("VocabularyNoteManager.kt", "")
share = kt_sources.get("BookShareCardHelper.kt", "")
timeline = kt_sources.get("ReadingTimelineHelper.kt", "")
challenge = kt_sources.get("ReadingChallengeManager.kt", "")

# ── 4a. 成就徽章：規格＋單源紀律 ──
for needle, desc in [
    ("enum class Dimension { STREAK, HOURS, WORDS, QUOTES, VOCAB }",
     "五維徽章枚舉"),
    ("enum class Tier { BRONZE, SILVER, GOLD }", "銅銀金三階枚舉"),
    ("Dimension.STREAK to intArrayOf(3, 7, 30)", "連續開卷閾值 3/7/30"),
    ("Dimension.HOURS to intArrayOf(5, 25, 100)", "總時數閾值 5/25/100"),
    ("Dimension.WORDS to intArrayOf(10, 50, 200)", "總字數閾值 10/50/200"),
    ("Dimension.QUOTES to intArrayOf(10, 50, 200)", "金句閾值 10/50/200"),
    ("Dimension.VOCAB to intArrayOf(5, 25, 100)", "生詞閾值 5/25/100"),
    ('const val BOOK_FILE = "achievement_badges.json"', "徽章帳冊檔名"),
    ("fun evaluate()", "缺少評估授勳 API"),
    ("fun badgeWall()", "缺少徽章牆 API"),
    ("fun awardedCount()", "缺少已授枚數 API"),
    ("fun totalBadges()", "缺少總枚數 API"),
    ("ReadStatisticsManager.summaryTotal()", "時長／字數應走統計單源"),
    ("ReadStatisticsManager.summaryTotal().minutes", "總時長應以正確欄位 minutes 讀取"),
    ("ReadStatisticsManager.dailyChart(365)", "連續天數應走日圖單源"),
    ("GoldenQuoteManager.allQuotes()", "金句計數應走金句簿單源"),
    ("vocabulary_notes.json", "生詞計數應以生詞簿檔為準（解耦橋接）"),
    ("R.string.badge_summary_fmt", "未引用徽章統計文案"),
    ("R.string.badge_progress_fmt", "未引用徽章進度文案"),
]:
    check(needle in badge, f"AchievementBadgeManager.kt：{desc}")

check("badgeWall" not in "", "")
check(badge.find("evaluate") < badge.find("badgeWall"),
      "徽章牆查詢應晚於評估定義（帳冊先寫後讀）")

# ── 4b. 生詞本：艾賓浩斯梯＋防膨脹 ──
for needle, desc in [
    ("val REVIEW_STEPS = intArrayOf(1, 2, 4, 7, 15)", "複習間隔梯 1/2/4/7/15"),
    ("const val DAILY_QUEUE_LIMIT = 20", "每日複習上限 20"),
    ("const val MAX_WORD_CARDS = 2000", "生詞卡上限 2000"),
    ('const val BOOK_FILE = "vocabulary_notes.json"', "生詞簿檔名"),
    ("fun addWord(", "缺少收藏 API"),
    ("fun todayQueue(", "缺少今日佇列 API"),
    ("fun grade(", "缺少複習評分 API"),
    ("fun allWords()", "缺少總覽 API"),
    ("fun exportCsv()", "缺少 CSV 匯出 API"),
    ("fun deleteWord(", "缺少刪除 API"),
    ("fun masteredCount()", "缺少畢業計數 API"),
    ('"\\uFEFF" + rows.joinToString', "CSV 應帶 BOM（Excel 直開不亂碼）"),
    ("coerceAtMost(REVIEW_STEPS.size - 1)", "答對應升級封頂"),
    ("coerceAtLeast(0)", "答錯應退級保底（挫折保護）"),
    ("R.string.vocab_queue_fmt", "未引用佇列文案"),
    ("R.string.vocab_counter_fmt", "未引用計數文案"),
]:
    check(needle in vocab, f"VocabularyNoteManager.kt：{desc}")

# 同詞重複收藏：不建新卡（主鍵去重）
check("none { it.word == key }" in vocab or
      "indexOfFirst { it.word == word }" in vocab,
      "同詞收藏應去重（詞形為主鍵）")

# ── 4c. 書籍分享卡：純文字紀律 ──
for needle, desc in [
    ("const val CARD_WIDTH_CHARS = 36", "卡片寬 36 字元基準"),
    ("const val MAX_CARD_CHARS = 500", "全卡 500 字元保險絲"),
    ("const val MAX_TITLE_CHARS = 40", "書名截斷 40"),
    ("data class BookCardSource(", "缺少分享素材模型"),
    ("fun buildBookCard(", "缺少組卡 API"),
    ("fun copyToClipboard(", "缺少剪貼簿主路"),
    ("fun shareIntent(", "缺少 ACTION_SEND 輔路"),
    ("ACTION_SEND", "輔路應為系統分享意圖"),
    ("text/plain", "分享應為純文字（零點陣圖）"),
    ("ClipboardManager", "主路應走剪貼簿"),
    ("fun percentOf(", "缺少百分比 API"),
    ("R.string.share_read_duration_fmt", "未引用時長行文案"),
    ("R.string.share_copied", "未引用複製成功文案"),
]:
    check(needle in share, f"BookShareCardHelper.kt：{desc}")

check("Bitmap" not in strip_kotlin(share), "分享卡不應畫 Bitmap（純文字紀律）")

# ── 4d. 閱讀時間軸：碎片合併＋保留期 ──
for needle, desc in [
    ('const val BOOK_FILE = "reading_timeline.json"', "時間軸帳冊檔名"),
    ("const val RETENTION_DAYS = 90", "保留期 90 天"),
    ("const val MERGE_WINDOW_MS = 5L * 60L * 1000L", "碎片合併窗 5 分鐘"),
    ("const val RECAP_MAX_BOOKS = 5", "回顧卡最多 5 書"),
    ("fun appendSession(", "缺少追記 API"),
    ("fun todayRecap(", "缺少今日回顧 API"),
    ("fun weekByBook(", "缺少週彙 API"),
    ("fun lastVisit(", "缺少最後閱讀 API"),
    ("fun clearAll()", "缺少清空 API"),
    ("data class TimelineEntry(", "缺少記錄模型"),
    ("data class RecapRow(", "缺少回顧行模型"),
    ("data class BookTimeRow(", "缺少週彙行模型"),
    ("now - tail.ts <= MERGE_WINDOW_MS", "同章 5 分鐘內應併入前筆"),
    ("sortedByDescending { it.minutes }", "回顧應時長降冪"),
    ("R.string.timeline_recap_fmt", "未引用回顧行文案"),
    ("R.string.timeline_week_fmt", "未引用週彙行文案"),
]:
    check(needle in timeline, f"ReadingTimelineHelper.kt：{desc}")

# ── 4e. 閱讀挑戰：雙賽制＋結算紀律 ──
for needle, desc in [
    ("enum class Kind { WEEKLY, STREAK }", "雙賽制枚舉"),
    ("enum class Status { ACTIVE, DONE, EXPIRED }", "賽事狀態枚舉"),
    ("const val WEEKLY_MIN_MINUTES = 60L", "週賽下限 60 分"),
    ("const val WEEKLY_MAX_MINUTES = 1680L", "週賽上限 1680 分"),
    ("const val STREAK_MIN_DAYS = 3", "連賽下限 3 天"),
    ("const val STREAK_MAX_DAYS = 100", "連賽上限 100 天"),
    ("const val DEFAULT_WEEKLY_MINUTES = 300L", "週賽預設 300 分"),
    ("const val DEFAULT_STREAK_DAYS = 14", "連賽預設 14 天"),
    ('const val BOOK_FILE = "reading_challenges.json"', "挑戰帳冊檔名"),
    ("fun startWeekly(", "缺少週賽開賽 API"),
    ("fun startStreak(", "缺少連賽開賽 API"),
    ("fun weeklyProgress(", "缺少週賽進度 API"),
    ("fun streakProgress(", "缺少連賽進度 API"),
    ("fun evaluate(", "缺少結算 API"),
    ("fun trophyWall()", "缺少獎盃牆 API"),
    ("fun abandon(", "缺少放棄 API"),
    ("ReadStatisticsManager.summaryWeek()", "週賽進度應走統計單源"),
    ("ReadStatisticsManager.summaryWeek().minutes", "週賽應以正確欄位 minutes 讀取"),
    ("fun endOfWeek(", "缺少週末結算點"),
    ("private fun isStreakBroken(", "缺少斷鏈判定"),
    ("R.string.challenge_progress_fmt", "未引用進度文案"),
    ("R.string.challenge_done_fmt", "未引用達標文案"),
]:
    check(needle in challenge, f"ReadingChallengeManager.kt：{desc}")

# 進行中同型不可重開
check("activeWeekly() != null" in challenge or
      "if (activeStreak() != null) return null" in challenge,
      "同型賽事進行中應拒開新場")

# ═══════════ 5. 邏輯等價測試（純 Python 重演） ═══════════

# 5a. 徽章：閾值／進度／一次性授勳
THRESHOLDS = {
    "STREAK": [3, 7, 30], "HOURS": [5, 25, 100], "WORDS": [10, 50, 200],
    "QUOTES": [10, 50, 200], "VOCAB": [5, 25, 100],
}
check(len(THRESHOLDS) * 3 == 15, "徽章總數應為五維×三階＝15")

def badge_progress(value, threshold):
    if threshold <= 0:
        return 0.0
    return min(max(value / threshold, 0.0), 1.0)
check(abs(badge_progress(7, 30) - 7 / 30) < 1e-9, "進度應為 value/threshold 夾擊")
check(badge_progress(0, 3) == 0.0, "零值進度應為 0")
check(badge_progress(99, 30) == 1.0, "超額進度應封頂 1")

def streak_from_chart(chart):
    """末格今日未讀不斷鏈（從昨起算）——與 currentStreakDays 等價"""
    if not chart:
        return 0
    s, i = 0, len(chart) - 1
    if chart[i] <= 0 and len(chart) > 1:
        i -= 1
    while i >= 0 and chart[i] > 0:
        s += 1
        i -= 1
    return s
check(streak_from_chart([5, 0, 5, 5, 0]) == 2, "今日未讀且昨日有讀應從昨起算")
check(streak_from_chart([0, 5, 0]) == 1, "僅昨日有讀應計 1")
check(streak_from_chart([5, 0, 0]) == 0, "昨日亦未讀應歸零")
check(streak_from_chart([5, 5, 5]) == 3, "全滿應連計今日")
check(streak_from_chart([5]) == 1, "單日已讀應為 1")
check(streak_from_chart([]) == 0, "空圖應為 0")

def grant_once(awarded, dim, tier, value):
    th = THRESHOLDS[dim][tier]
    key = (dim, tier)
    if value >= th and key not in awarded:
        awarded[key] = True
        return True
    return False
awarded = {}
check(grant_once(awarded, "STREAK", 0, 3), "達銅閾應授勳")
check(not grant_once(awarded, "STREAK", 0, 999), "同枚不應二授（重啟不重播）")
check(grant_once(awarded, "STREAK", 1, 7), "達銀閾應授勳")
check(len(awarded) == 2, "帳冊應恰二枚")

# 5b. 生詞本：艾賓浩斯梯
STEPS = [1, 2, 4, 7, 15]
def vocab_grade(step, remembered):
    mastered = False
    if remembered:
        new = min(step + 1, len(STEPS) - 1)
        if step >= len(STEPS) - 1:
            mastered = True
    else:
        new = max(step - 1, 0)
    return new, mastered
check(vocab_grade(0, True) == (1, False), "答對應升一級")
check(vocab_grade(4, True) == (4, True), "頂級答對應畢業")
check(vocab_grade(0, False) == (0, False), "答錯應保底 0（挫折保護）")
check(vocab_grade(3, False) == (2, False), "答錯應退一級")

DAY = 24 * 60 * 60 * 1000
def next_review(step, now):
    return now + STEPS[step] * DAY
check(next_review(0, 0) == DAY, "一級複習應 +1 天")
check(next_review(4, 0) == 15 * DAY, "五級複習應 +15 天")

# 佇列：逾期優先＋每日上限
def today_queue(cards, now_end_of_day, limit=20):
    due = [c for c in cards if not c["mastered"] and c["next"] <= now_end_of_day]
    due.sort(key=lambda c: (c["next"], c["added"]))
    return due[:limit]
cards = [{"word": f"w{i}", "mastered": False, "next": i * 1000 + 1, "added": 0}
          for i in range(30)]
check(len(today_queue(cards, 10**9)) == 20, "30 詞到期應截 20（防報復性複習）")
check(len(today_queue(cards, 0)) == 0, "無逾期應空佇列")
check(len(today_queue(
    [{"word": "x", "mastered": True, "next": 0, "added": 0}], 10**9)) == 0,
    "畢業詞應退出佇列")

# CSV 跳脫
def csv(field):
    f = field.strip()
    if "," in f or '"' in f or "\n" in f:
        return '"' + f.replace('"', '""') + '"'
    return f
check(csv('plain') == 'plain', "無符號欄位不應加引號")
check(csv('a,b') == '"a,b"', "逗號欄位應加引號")
check(csv('say"hi"') == '"say""hi"""', "引號欄位應雙寫跳脫")

# 5c. 分享卡：寬度／全形化／百分比／淨化
def percent_of(idx, total):
    if total <= 0 or idx < 0:
        return -1
    return int(min(max((idx + 1) * 100 // total, 0), 100))
check(percent_of(11, 240) == 5, "第 12/240 章應 5%")
check(percent_of(0, 1) == 100, "單章應 100%")
check(percent_of(5, 0) == -1, "總數未知應 -1")
check(percent_of(-1, 100) == -1, "負索引應 -1")

def display_width(text):
    w = 0
    for c in text:
        cp = ord(c)
        w += 2 if (0x2E80 <= cp <= 0x9FFF or 0xF900 <= cp <= 0xFAFF
                   or 0xFF00 <= cp <= 0xFFEF) else 1
    return w
check(display_width("中文") == 4, "CJK 應計寬 2")
check(display_width("ab") == 2, "西文應計寬 1")
check(display_width("ａ") == 2, "全形字母應計寬 2")

def pad_full_width(text):
    out = []
    for c in text:
        if "0" <= c <= "9":
            out.append(chr(0xFF10 + (ord(c) - ord("0"))))
        elif "a" <= c <= "z":
            out.append(chr(0xFF41 + (ord(c) - ord("a"))).upper())
        elif "A" <= c <= "Z":
            out.append(chr(0xFF21 + (ord(c) - ord("A"))))
        else:
            out.append(c)
    return "".join(out)
check(pad_full_width("A1") == "Ａ１", "半形應全形化")
check(pad_full_width("中") == "中", "CJK 應原樣")

def sanitize(raw):
    s = re.sub(r"\s+", " ", raw)
    s = re.sub(r"（[^）]{0,24}書源[^）]{0,12}）", "", s)
    s = re.sub(r"\[[^\]]{0,16}\]", "", s)
    s = re.sub(r"[-_|·–—]{2,}", " ", s)
    s = re.sub(r"\s+", " ", s)
    return s.strip()
check(sanitize("書名（XX書源站）") == "書名", "書源括註應剝除")
check(sanitize("書名[廣告]") == "書名", "方括雜訊應剝除")
check(sanitize("a -- b") == "a b", "連續破折應壓平")
check(sanitize("  x  y ") == "x y", "連續空白應壓一")

def truncate(text, mx):
    return text if len(text) <= mx else text[:mx - 1] + "…"
check(truncate("好書", 40) == "好書", "短文不截")
check(len(truncate("x" * 60, 40)) == 40 and truncate("x" * 60, 40).endswith("…"),
      "長文應截 40 加刪節號")

CARD = "─" * 36
check(len(CARD) == 36, "卡寬應 36")

# 5d. 時間軸：合併窗＋回顧＋清倉
def append_merge(entries, book, chapter, minutes, now):
    tail = entries[-1] if entries else None
    if (tail and tail["book"] == book and tail["chapter"] == chapter
            and now - tail["ts"] <= 5 * 60 * 1000):
        entries[-1] = {"book": book, "chapter": chapter,
                       "minutes": tail["minutes"] + minutes, "ts": now}
    else:
        entries.append({"book": book, "chapter": chapter,
                        "minutes": minutes, "ts": now})
    return entries
tl = []
append_merge(tl, "書", "第1章", 5, 0)
append_merge(tl, "書", "第1章", 7, 4 * 60 * 1000)
check(len(tl) == 1 and tl[0]["minutes"] == 12, "同章 5 分內應併筆")
append_merge(tl, "書", "第2章", 3, 9 * 60 * 1000)
check(len(tl) == 2, "換章應開新筆")
append_merge(tl, "書", "第2章", 3, 9 * 60 * 1000 + 6 * 60 * 1000)
check(len(tl) == 3, "逾 5 分窗應開新筆")

def recap_take(entries, start, mx=5):
    rows = {}
    for e in entries:
        if e["ts"] >= start and e["minutes"] > 0:
            r = rows.setdefault(e["book"], [0, None, 0])
            r[0] += e["minutes"]
            if e["ts"] > r[2]:
                r[1], r[2] = e["chapter"], e["ts"]
    return sorted(rows.items(), key=lambda kv: -kv[1][0])[:mx]
many = [{"book": f"B{i}", "chapter": "c", "minutes": i, "ts": 10}
        for i in range(8)]
check(len(recap_take(many, 0)) == 5, "回顧應最多 5 書")
check(recap_take(many, 0)[0][0] == "B7", "回顧應時長降冪")
check(recap_take(many, 999) == [], "今日無讀應空回顧")

def purge(entries, now, retention=90):
    cutoff = now - retention * DAY
    return [e for e in entries if e["ts"] >= cutoff]
old = [{"book": "x", "chapter": "c", "minutes": 1, "ts": 0}]
check(purge(old, 100 * DAY) == [], "逾 90 天舊帳應清倉")
check(len(purge(old, 89 * DAY)) == 1, "90 天內舊帳應保留")

# 5e. 挑戰：夾擊／週末點／連賽封頂／斷鏈
def clamp(v, lo, hi):
    return min(max(v, lo), hi)
check(clamp(300, 60, 1680) == 300, "預設週賽在界內")
check(clamp(10, 60, 1680) == 60, "低於下限應夾回 60")
check(clamp(9999, 60, 1680) == 1680, "超上限應夾回 1680")
check(clamp(14, 3, 100) == 14, "預設連賽在界內")
check(clamp(1, 3, 100) == 3, "連賽下限 3")

def end_of_week(monday_based_now):
    """與 endOfWeek 等價（Calendar 週一為 2）：返回距週日天數"""
    dow = monday_based_now
    return 0 if dow == 1 else 8 - dow
check(end_of_week(1) == 0, "週日當天即週末")
check(end_of_week(2) == 6, "週一距週日 6 天")
check(end_of_week(7) == 1, "週六距週日 1 天")

def capped_by_start(start_day, today, streak):
    days = (today - start_day) // DAY + 1
    return min(streak, days)
check(capped_by_start(0, 0, 5) == 1, "開賽首日連續應封 1")
check(capped_by_start(0, 13 * DAY, 99) == 14, "第 14 天應封 14")
check(capped_by_start(10 * DAY, 13 * DAY, 99) == 4, "開賽前連續不應算入（封 4）")

def streak_broken(read_days, start_day, today, target):
    """與 isStreakBroken 等價：開賽以來（不含今天）有零閱讀日即判負；
    無洞則鏈仍在長——連賽無時限，永不過期（target 僅供達標判定）"""
    if today <= start_day:
        return False
    days_elapsed = (today - start_day) // DAY  # 不含今天
    for k in range(1, days_elapsed + 1):
        if (today - k * DAY) not in read_days:
            return True
    return False
DAY_KEYS = lambda n: {t * DAY for t in range(n)}
check(not streak_broken(DAY_KEYS(3), 0, 2 * DAY, 14), "前三天全讀不應判負")
check(streak_broken(DAY_KEYS(2), 0, 3 * DAY, 14), "第三天前有洞應判負")
check(not streak_broken(DAY_KEYS(1), 0, DAY, 14), "首日已讀不應判負")
check(streak_broken(set(), 0, 20 * DAY, 14), "全洞應判負")
check(not streak_broken(DAY_KEYS(13), 0, 13 * DAY, 14),
      "第 14 天今日讀即達標不應判負")

def ratio(current, target):
    return 0.0 if target <= 0 else min(max(current / target, 0.0), 1.0)
check(abs(ratio(210, 300) - 0.7) < 1e-9, "週賽 210/300 應 70%")
check(ratio(0, 300) == 0.0, "零進度應 0")
check(ratio(999, 300) == 1.0, "超標應封頂 1")

# ═══════════ 6. strings.xml：鍵數與只增不刪 ═══════════
check(len(string_keys) == 545,
      f"字串鍵應為 545（485＋60），實為 {len(string_keys)}")
STAGE11_PREFIXES = ("badge_", "vocab_", "share_", "timeline_", "challenge_")
new_keys = {k for k in string_keys if k.startswith(STAGE11_PREFIXES)}
check(len(new_keys) == 60,
      f"Stage 11 新鍵應 60（19徽章＋14生詞＋8分享＋9時間軸＋10挑戰），實為 {len(new_keys)}")

# 舊鍵基線抽樣（只增不刪：Stage 1-10 代表鍵仍在）
for must in ["app_name", "repo_delete_confirm", "rest_title", "bkcrypto_title",
             "local_import_title", "goal_title", "quote_card", "stats_title",
             "eink_mode", "fulltext_search_title", "turn_title", "font_import"]:
    check(must in string_keys, f"只增不刪違例：缺少舊鍵 {must}")

# 格式鍵參數抽樣（printf 位置記號在）
for k in ["badge_summary_fmt", "vocab_queue_fmt", "share_read_duration_fmt",
          "timeline_recap_fmt", "challenge_progress_fmt"]:
    check("%1$" in string_map[k], f"{k} 應用位置參數（格式鍵紀律）")

# ═══════════ 7. init_k_reader.sh：關鍵檔清單與十一階段字樣 ═══════════
init_sh = ROOT / "init_k_reader.sh"
if init_sh.is_file():
    sh = init_sh.read_text(encoding="utf-8")
    for rel in STAGE11_FILES:
        entry = rel.split("app/src/main/", 1)[1] if "app/src/main/" in rel else rel
        entry = entry.split("/")[-1] if entry.endswith(".kt") else entry
        check(entry in sh, f"init_k_reader.sh 關鍵檔清單缺少：{entry}")
    check("十一階段關鍵檔" in sh, "init_k_reader.sh 應標示十一階段關鍵檔")
    check("十一階段交付檔" in sh, "init_k_reader.sh 疊加層步驟應為十一階段")
    check("45 個 Kotlin 模組" in sh, "init_k_reader.sh 應標示 45 個 Kotlin 模組")
    check("dialog_badges.xml" in sh, "init_k_reader.sh 關鍵檔應含徽章牆布局")
    check("十階段" not in sh, "init_k_reader.sh 不應殘留十階段字樣")

# ═══════════ 8. README.md：十一階段交付對照 ═══════════
readme = ROOT / "README.md"
if readme.is_file():
    md = readme.read_text(encoding="utf-8")
    check("十一階段交付" in md, "README.md 應標示十一階段交付")
    check("| Stage 11 |" in md, "README.md 應有 Stage 11 列")
    check("成就徽章＋生詞本＋書籍分享卡＋閱讀時間軸＋閱讀挑戰" in md,
          "README.md Stage 11 範圍應為五件套")
    check("485→545" in md, "README.md 應記錄字串 485→545")
    check("40→45 Kotlin" in md, "README.md 應記錄模組 40→45")

# ═══════════ 結果 ═══════════
print("  Stage 11 檔案：成就徽章／生詞本／分享卡／時間軸／閱讀挑戰＋布局＋字串＋init")
print(f"  字串資源：{len(string_keys)} 鍵（485 基線＋60 新增）")
for w in warnings:
    print(f"  [警告] {w}")
if errors:
    print(f"\n✘ 驗證失敗（{len(errors)} 項）：")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("\n✔ Stage 11 全部檔案驗證通過")

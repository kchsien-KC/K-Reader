package com.k.reader.help.content

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.vocab.VocabularyNoteManager

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 溫故詞頻分析器（Stage 12：閱讀深耕與個人化）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（複習要排排隊，隊伍按「再見率」排）：
 *   Stage 11 的生詞本把「查過的詞」收進了艾賓浩斯梯，但梯
 *   只認「查的時間」不認「詞的重要性」——一個在本書出現三十
 *   次的詞，比只出現一次的詞值得先複習。本分析器把「書的
 *   內容」和「生詞簿」對上了：
 *
 *    1. 溫故清單：生詞簿全部詞彙在指定文本（本章／近三章／
 *       全書）的出現次數——降冪排列；「頻次權重」直接餵生
 *       詞本優先級（出現越多的詞，答錯的代價越大）；
 *    2. 獨字比：文本的 CJK 總字數／獨字數比——衡量文本難度
 *       的粗尺（古文獨字比高、爽文低）；
 *    3. 重複片段：高頻四字組（如成語、反覆詠嘆的句子）——
 *       詞頻的中文近似法（無詞典環境下以 4-gram 為主窗，
 *       頻次≥門檻才入榜，避免碎片噪音）。
 *
 *  規格值：n-gram 主窗 4 字（3-5 夾擊實驗窗）；n-gram 入榜
 *  頻次 ≥3；溫故清單上限 60 詞；文本長度上限 200_000 字（與
 *  FullTextSearchHelper.MAX_CHAPTER_CHARS 同尺）。
 *
 *  整合點：
 *   · 生詞本複習排序 → reviewList(text)（頻次權重）
 *   · 章節讀取完成 → analyze(text)（書頁「本章詞像」）
 *   · VocabularyNoteManager.allWords() 唯讀（單源）
 */
object WordFrequencyHelper {

    private const val TAG = "WordFrequencyHelper"

    /** n-gram 主窗（字） */
    const val NGRAM_SIZE = 4

    /** n-gram 入榜最低頻次 */
    const val NGRAM_MIN_FREQ = 3

    /** 溫故清單上限 */
    const val REVIEW_LIST_LIMIT = 60

    /** 分析文本長度上限（與全文搜尋同尺） */
    const val MAX_TEXT_CHARS = 200_000

    /** 溫故清單單行（word＋出現次數） */
    data class ReviewRow(
        val word: String,
        val hits: Int,
        val mastered: Boolean,
    )

    /** 文本詞像（統計面板用） */
    data class TextStat(
        val totalChars: Int,
        val cjkChars: Int,
        val uniqueChars: Int,
        val uniqueRatio: Float,
        val topPhrases: List<PhraseRow>,
    )

    /** 高頻片段行 */
    data class PhraseRow(
        val phrase: String,
        val freq: Int,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 溫故清單：生詞簿全部詞彙在文本中的出現次數（降冪）。
     * 沒收過生詞／文本為空 → 空清單（誠實空狀態）。
     */
    fun reviewList(text: String): List<ReviewRow> {
        val clean = filterCjk(text)
        if (clean.isEmpty()) return emptyList()
        val words = try {
            VocabularyNoteManager.allWords()
        } catch (t: Throwable) {
            Log.w(TAG, "生詞簿不可讀——溫故清單空手而回", t)
            emptyList()
        }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<ReviewRow>()
        for (w in words) {
            val key = w.word.trim()
            if (key.length < 2) continue
            val hits = countOccurrences(clean, key)
            if (hits > 0) {
                out += ReviewRow(word = key, hits = hits, mastered = w.mastered)
            }
        }
        return out.sortedWith(
            compareByDescending<ReviewRow> { it.hits }.thenBy { it.word }
        ).take(REVIEW_LIST_LIMIT)
    }

    /**
     * 文本詞像：總字數／CJK 數／獨字數／獨字比＋高頻四字組。
     * 文本超長先截斷（MAX_TEXT_CHARS 保險絲）。
     */
    fun analyze(raw: String): TextStat {
        val text = raw.take(MAX_TEXT_CHARS)
        val cjk = filterCjk(text)
        val total = text.length
        val unique = cjk.toSet().size
        val topPhrases = topPhrases(cjk)
        return TextStat(
            totalChars = total,
            cjkChars = cjk.length,
            uniqueChars = unique,
            uniqueRatio = if (cjk.isEmpty()) 0f
            else unique.toFloat() / cjk.length,
            topPhrases = topPhrases,
        )
    }

    /** 高頻 n-gram（無詞典環境的中文詞頻近似） */
    fun topPhrases(cjkText: String, n: Int = NGRAM_SIZE): List<PhraseRow> {
        if (cjkText.length < n * NGRAM_MIN_FREQ) return emptyList()
        val counts = HashMap<String, Int>()
        var i = 0
        while (i + n <= cjkText.length) {
            val gram = cjkText.substring(i, i + n)
            counts[gram] = (counts[gram] ?: 0) + 1
            i += 1
        }
        return counts.entries
            .filter { it.value >= NGRAM_MIN_FREQ }
            .map { PhraseRow(phrase = it.key, freq = it.value) }
            .sortedWith(compareByDescending<PhraseRow> { it.freq }.thenBy { it.phrase })
            .take(REVIEW_LIST_LIMIT)
    }

    // ── 內部 ───────────────────────────────────────────────────

    /** 只留 CJK（數字英文標點全濾——詞頻只算漢字） */
    private fun filterCjk(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            val cp = c.code
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF
                || cp in 0xF900..0xFAFF
            ) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 非重疊計數（貪婪掃描——「日日日日」對「日日」計 2 不計 3） */
    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val at = haystack.indexOf(needle, idx)
            if (at < 0) break
            count += 1
            idx = at + needle.length
        }
        return count
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.freq_title)
    fun reviewTitle(): String = AppContext.ctx.getString(R.string.freq_review)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.freq_empty)
    fun statTitle(): String = AppContext.ctx.getString(R.string.freq_stat)
    fun phraseTitle(): String = AppContext.ctx.getString(R.string.freq_phrase)

    /** 溫故行：「「徑自」出現 12 次」 */
    fun reviewLine(row: ReviewRow): String =
        AppContext.ctx.getString(R.string.freq_review_fmt, row.word, row.hits)

    /** 片段行：「出現 5 次——不知天高地厚」 */
    fun phraseLine(row: PhraseRow): String =
        AppContext.ctx.getString(R.string.freq_phrase_fmt, row.freq, row.phrase)

    /** 統計行：「全文 8,412 字・獨字比 0.18」 */
    fun statLine(stat: TextStat): String =
        AppContext.ctx.getString(
            R.string.freq_stat_fmt,
            stat.totalChars,
            String.format(java.util.Locale.TAIWAN, "%.2f", stat.uniqueRatio),
        )

    /** 詞像難度粗尺（獨字比→難度標籤） */
    fun difficultyLabel(stat: TextStat): String {
        val r = stat.uniqueRatio
        return when {
            r >= 0.35f -> AppContext.ctx.getString(R.string.freq_diff_hard)
            r >= 0.22f -> AppContext.ctx.getString(R.string.freq_diff_medium)
            stat.cjkChars == 0 -> AppContext.ctx.getString(R.string.freq_diff_none)
            else -> AppContext.ctx.getString(R.string.freq_diff_easy)
        }
    }
}

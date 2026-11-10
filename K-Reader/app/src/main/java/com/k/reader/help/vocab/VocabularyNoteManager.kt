package com.k.reader.help.vocab

import android.util.Log
import android.widget.Toast
import com.k.reader.R
import com.k.reader.app.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 生詞本管理器（Stage 11：閱讀習慣養成）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（查過的詞，別讓它白查）：
 *   Stage 7 的字詞快查把「不認識」變成了「查得到」，但查完即
 *   忘——查十遍還是生詞。本管理器把快查的出口接上記憶科學：
 *
 *    1. 收藏：快查面板一鍵「存入生詞本」——記錄詞形、釋義、
 *       出處書名與章節（語境比釋義更利於回想）；同一詞重複
 *       收藏只更新出處，不重複建卡（帳冊以詞形為主鍵）；
 *    2. 複習：艾賓浩斯間隔重現——1／2／4／7／15 天五級梯，
 *       答對升一級、答錯退回一級（永遠有一級可退，不歸零——
 *       挫折保護）；「今日待複習」佇列即時可查，每日上限
 *       20 詞（防報復性複習）；
 *    3. 畢業：連續答對走完五級梯標記 mastered——從複習佇列
 *       退役，僅在總覽可見；CSV 匯出（詞形／釋義／出處／
 *       級數／畢業與否）供 Anki 等外部工具接手。
 *
 *  規格值：間隔梯 REVIEW_STEPS＝[1,2,4,7,15] 天；每日複習上限
 *  20 詞；帳冊 filesDir/vocabulary_notes.json；生詞卡上限 2000
 *  （超過提示先匯出清倉——防止 JSON 無限膨脹拖慢啟動）。
 *
 *  整合點：
 *   · DictLookupHelper 快查面板 → addWord()（收藏動作）
 *   · 複習對話框（每日一開） → todayQueue()／grade()
 *   · 成就徽章 VOCAB 維度 → 檔案計數（外部唯讀）
 *   · 備份層 → vocabularyFile()（只拷檔不解析）
 */
object VocabularyNoteManager {

    private const val TAG = "VocabularyNoteManager"

    /** 生詞簿檔名（filesDir 下，JSON 陣列；徽章層以此名唯讀計數） */
    const val BOOK_FILE = "vocabulary_notes.json"

    /** 艾賓浩斯間隔梯（天）：答對升一級，答錯退一級 */
    val REVIEW_STEPS = intArrayOf(1, 2, 4, 7, 15)

    /** 每日複習佇列上限（防報復性複習） */
    const val DAILY_QUEUE_LIMIT = 20

    /** 生詞卡上限（超過提示匯出清倉） */
    const val MAX_WORD_CARDS = 2000

    /** 單詞釋義欄位截斷長度 */
    const val MAX_MEANING_CHARS = 120

    /** 生詞卡（step：0..4 間隔梯級數；mastered＝走完五級） */
    data class VocabWord(
        val word: String,
        val meaning: String,
        val bookName: String,
        val chapterTitle: String,
        val step: Int,
        val mastered: Boolean,
        val addedAt: Long,
        val lastReviewAt: Long,
        val nextReviewAt: Long,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 收藏一詞：同詞形已存在→只更新出處（不重複建卡）；
     * 回傳「本次是否新建」（新建才觸發徽章評估與祝賀）。
     */
    fun addWord(
        word: String,
        meaning: String,
        bookName: String,
        chapterTitle: String,
    ): Boolean {
        val key = word.trim()
        if (key.isEmpty()) return false
        if (!load().none { it.word == key }) {
            // 已存在：僅刷新出處語境
            updateContext(key, bookName, chapterTitle)
            return false
        }
        if (load().size >= MAX_WORD_CARDS) {
            Log.w(TAG, "生詞卡達上限 $MAX_WORD_CARDS——拒絕新增，提示匯出")
            Toast.makeText(
                AppContext.ctx,
                quotaExceededNotice(),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        val now = System.currentTimeMillis()
        val card = VocabWord(
            word = key,
            meaning = meaning.trim().take(MAX_MEANING_CHARS),
            bookName = bookName,
            chapterTitle = chapterTitle,
            step = 0,
            mastered = false,
            addedAt = now,
            lastReviewAt = 0L,
            nextReviewAt = now + days(REVIEW_STEPS[0].toLong()),
        )
        val list = load()
        list.add(card)
        persist()
        Log.i(TAG, "收藏生詞：$key（${list.size} 詞）")
        return true
    }

    /** 今日待複習佇列（逾期優先、其次快到期；截取每日上限） */
    fun todayQueue(now: Long = System.currentTimeMillis()): List<VocabWord> =
        load()
            .filter { !it.mastered && it.nextReviewAt <= endOfDay(now) }
            .sortedWith(compareBy({ it.nextReviewAt }, { it.addedAt }))
            .take(DAILY_QUEUE_LIMIT)

    /**
     * 複習評分：remembered=true 升一級（走完五級→mastered畢業）；
     * false 退一級（一級為底，挫折保護）。回傳更新後的卡。
     */
    fun grade(word: String, remembered: Boolean): VocabWord? {
        val key = word.trim()
        val list = load()
        val idx = list.indexOfFirst { it.word == key }
        if (idx < 0) return null
        val old = list[idx]
        val now = System.currentTimeMillis()
        val newStep: Int = when {
            remembered -> (old.step + 1).coerceAtMost(REVIEW_STEPS.size - 1)
            else -> (old.step - 1).coerceAtLeast(0)
        }
        val masteredNow = remembered && old.step >= REVIEW_STEPS.size - 1
        val updated = old.copy(
            step = if (masteredNow) newStep else newStep,
            mastered = old.mastered || masteredNow,
            lastReviewAt = now,
            nextReviewAt = now + days(REVIEW_STEPS[newStep].toLong()),
        )
        list[idx] = updated
        persist()
        return updated
    }

    /** 全簿（總覽用：複習中＋畢業） */
    fun allWords(): List<VocabWord> = load().toList()

    /** 複習中詞數（徽章維度以此檔計數，兩處口徑一致） */
    fun activeCount(): Int = load().count { !it.mastered }

    /** 畢業詞數 */
    fun masteredCount(): Int = load().count { it.mastered }

    /** 刪除一詞 */
    fun deleteWord(word: String): Boolean {
        val list = load()
        val removed = list.removeAll { it.word == word.trim() }
        if (removed) persist()
        return removed
    }

    /** 生詞簿檔（供備份層與徽章層唯讀） */
    fun vocabularyFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    /** 匯出 CSV（UTF-8 BOM——Excel 直開不亂碼；回傳行數） */
    fun exportCsv(): Int {
        val rows = mutableListOf(CSV_HEADER)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)
        for (w in load()) {
            rows += listOf(
                csv(w.word),
                csv(w.meaning),
                csv(w.bookName),
                csv(w.chapterTitle),
                "${w.step + 1}/${REVIEW_STEPS.size}",
                if (w.mastered) "1" else "0",
                fmt.format(Date(w.addedAt)),
            ).joinToString(",")
        }
        val file = File(AppContext.ctx.getExternalFilesDir(null) ?: AppContext.ctx.filesDir, "vocab_notes.csv")
        try {
            file.parentFile?.mkdirs()
            file.writeText("\uFEFF" + rows.joinToString("\n") + "\n", Charsets.UTF_8)
            Log.i(TAG, "生詞 CSV 匯出：${rows.size - 1} 詞 → ${file.path}")
            return rows.size - 1
        } catch (t: Throwable) {
            Log.w(TAG, "生詞 CSV 匯出失敗", t)
            return 0
        }
    }

    // ── 內部 ───────────────────────────────────────────────────

    private const val CSV_HEADER =
        "word,meaning,book,chapter,step,mastered,added_at"

    private var cache: MutableList<VocabWord>? = null

    private fun load(): MutableList<VocabWord> {
        cache?.let { return it }
        val out = mutableListOf<VocabWord>()
        val file = vocabularyFile()
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += VocabWord(
                        word = o.optString("word"),
                        meaning = o.optString("meaning"),
                        bookName = o.optString("book"),
                        chapterTitle = o.optString("chapter"),
                        step = o.optInt("step", 0).coerceIn(0, REVIEW_STEPS.size - 1),
                        mastered = o.optBoolean("mastered", false),
                        addedAt = o.optLong("addedAt", 0L),
                        lastReviewAt = o.optLong("lastReviewAt", 0L),
                        nextReviewAt = o.optLong("nextReviewAt", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "生詞簿解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist() {
        val arr = JSONArray()
        for (w in load()) {
            arr.put(JSONObject().apply {
                put("word", w.word)
                put("meaning", w.meaning)
                put("book", w.bookName)
                put("chapter", w.chapterTitle)
                put("step", w.step)
                put("mastered", w.mastered)
                put("addedAt", w.addedAt)
                put("lastReviewAt", w.lastReviewAt)
                put("nextReviewAt", w.nextReviewAt)
            })
        }
        try {
            vocabularyFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "生詞簿寫入失敗", t)
        }
    }

    /** 同詞重複收藏：僅刷新出處語境 */
    private fun updateContext(word: String, bookName: String, chapterTitle: String) {
        val list = load()
        val idx = list.indexOfFirst { it.word == word }
        if (idx < 0) return
        list[idx] = list[idx].copy(
            bookName = bookName,
            chapterTitle = chapterTitle,
        )
        persist()
    }

    private fun days(d: Long): Long = d * 24L * 60L * 60L * 1000L

    private fun endOfDay(now: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        return cal.timeInMillis
    }

    /** CSV 欄位跳脫（逗號／引號／換行） */
    private fun csv(field: String): String {
        val f = field.trim()
        return if (f.contains(',') || f.contains('"') || f.contains('\n')) {
            "\"" + f.replace("\"", "\"\"") + "\""
        } else {
            f
        }
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.vocab_title)
    fun addLabel(): String = AppContext.ctx.getString(R.string.vocab_add)
    fun addedNotice(): String = AppContext.ctx.getString(R.string.vocab_added)
    fun existsNotice(): String = AppContext.ctx.getString(R.string.vocab_exists)
    fun quotaExceededNotice(): String = AppContext.ctx.getString(R.string.vocab_quota)
    fun reviewTitle(): String = AppContext.ctx.getString(R.string.vocab_review_title)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.vocab_empty)
    fun rememberLabel(): String = AppContext.ctx.getString(R.string.vocab_remember)
    fun forgotLabel(): String = AppContext.ctx.getString(R.string.vocab_forgot)
    fun masteredLabel(): String = AppContext.ctx.getString(R.string.vocab_mastered)
    fun exportLabel(): String = AppContext.ctx.getString(R.string.vocab_export)
    fun exportDoneNotice(): String = AppContext.ctx.getString(R.string.vocab_export_done)

    fun queueSummary(pending: Int): String =
        AppContext.ctx.getString(R.string.vocab_queue_fmt, pending)

    fun counterLine(active: Int, mastered: Int): String =
        AppContext.ctx.getString(R.string.vocab_counter_fmt, active, mastered)
}

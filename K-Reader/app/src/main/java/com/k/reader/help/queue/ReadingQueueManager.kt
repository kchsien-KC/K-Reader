package com.k.reader.help.queue

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 待讀清單管理器（Stage 12：閱讀深耕與個人化）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（書架是現在式，待讀是未來式）：
 *   書架按「最近翻開」排序，收藏欲卻沒有家：瀏覽書源時看到好
 *   書、朋友推薦一本、書單裡掃到遺珠——這些「想讀但還沒開始」
 *   的書塞進書架就會被追更和整理淹沒。本管理器給「未來的閱讀」
 *   一個獨立的流水帳：
 *
 *    1. 三階段：WISH（想讀）→ ACTIVE（開卷即自動流轉）→
 *       DONE（完本自動流轉）；ABANDONED（放棄）不入榜只留痕
 *       ——放棄不是失敗，是篩選；
 *    2. 流轉自動化：add() 只進 WISH；書籍首次閱讀（閱讀頁回報）
 *       → markStarted()（WISH→ACTIVE）；完本（閱讀統計同源
 *       的 finished 事件）→ markFinished()（→DONE）——使用者
 *       只需要在「發現好書」時點一下，後面全是自動的；
 *    3. 提醒：想讀超過 N 天（預設 30）未開卷 → staleWishes()
 *       過期清單（「這本你 45 天前就想讀了」——書架整理的
 *       另一面）。帳冊 filesDir/reading_queue.json，書名＋作者
 *       為鍵（換源不改鍵）。
 *
 *  規格值：清單上限 200（待讀不是藏書閣）；過期門檻 7..90 天
 *  （預設 30）；同一書名＋作者不重複建列（去重口徑與完本登記
 *  同源）。
 *
 *  整合點：
 *   · 書源瀏覽「加入待讀」 → add()
 *   · 閱讀頁首次開卷 → markStarted()
 *   · ReadStatisticsManager 完本事件 → markFinished()
 *   · 設定頁 queue_title 面板 → sections()／staleWishes()
 *   · 備份層 → queueFile()（只拷檔不解析）
 */
object ReadingQueueManager {

    private const val TAG = "ReadingQueueManager"

    /** 待讀清單檔名（filesDir 下） */
    const val BOOK_FILE = "reading_queue.json"

    /** 清單上限 */
    const val MAX_ENTRIES = 200

    /** 想讀過期門檻（天）界域 */
    const val STALE_MIN_DAYS = 7
    const val STALE_MAX_DAYS = 90

    /** 預設過期門檻（天） */
    const val DEFAULT_STALE_DAYS = 30

    /** 三階段＋放棄 */
    enum class Stage { WISH, ACTIVE, DONE, ABANDONED }

    /** 清單單列 */
    data class QueueEntry(
        val bookName: String,
        val author: String,
        val stage: Stage,
        val addedAt: Long,
        val startedAt: Long,
        val finishedAt: Long,
    )

    /** 面板分組視圖 */
    data class QueueSection(
        val stage: Stage,
        val entries: List<QueueEntry>,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 加入想讀：同名同作者已列→回 false（不重複建列）；
     * 已 DONE／ACTIVE 的書→也回 false（別把讀完的再排隊）。
     */
    fun add(bookName: String, author: String): Boolean {
        val key = keyOf(bookName, author)
        val list = load()
        if (list.any { keyOf(it) == key }) return false
        if (list.size >= MAX_ENTRIES) {
            Log.w(TAG, "待讀清單達上限 $MAX_ENTRIES——拒絕新增")
            return false
        }
        list += QueueEntry(
            bookName = bookName.trim(),
            author = author.trim(),
            stage = Stage.WISH,
            addedAt = System.currentTimeMillis(),
            startedAt = 0L,
            finishedAt = 0L,
        )
        persist()
        return true
    }

    /** 開卷流轉（WISH→ACTIVE；已在讀冪等；已完本不動） */
    fun markStarted(bookName: String, author: String): Boolean {
        val list = load()
        val idx = list.indexOfFirst { keyOf(it) == keyOf(bookName, author) }
        if (idx < 0) return false
        val e = list[idx]
        if (e.stage == Stage.ACTIVE) return false
        if (e.stage != Stage.WISH) return false
        list[idx] = e.copy(stage = Stage.ACTIVE, startedAt = System.currentTimeMillis())
        persist()
        return true
    }

    /** 完本流轉（ACTIVE→DONE；WISH 直接 DONE 也收——跳級完本） */
    fun markFinished(bookName: String, author: String): Boolean {
        val list = load()
        val idx = list.indexOfFirst { keyOf(it) == keyOf(bookName, author) }
        if (idx < 0) return false
        val e = list[idx]
        if (e.stage == Stage.DONE) return false
        list[idx] = e.copy(
            stage = Stage.DONE,
            startedAt = if (e.startedAt > 0) e.startedAt else System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
        )
        persist()
        return true
    }

    /** 放棄（任意階段→ABANDONED，不入榜只留痕） */
    fun abandon(bookName: String, author: String): Boolean {
        val list = load()
        val idx = list.indexOfFirst { keyOf(it) == keyOf(bookName, author) }
        if (idx < 0) return false
        if (list[idx].stage == Stage.ABANDONED) return false
        list[idx] = list[idx].copy(stage = Stage.ABANDONED)
        persist()
        return true
    }

    /** 移除一列（完本／放棄後的清理動作） */
    fun remove(bookName: String, author: String): Boolean {
        val list = load()
        val before = list.size
        list.removeAll { keyOf(it) == keyOf(bookName, author) }
        val removed = before != list.size
        if (removed) persist()
        return removed
    }

    /** 面板分組視圖：WISH→ACTIVE→DONE（ABANDONED 不入榜） */
    fun sections(): List<QueueSection> {
        val list = load().filter { it.stage != Stage.ABANDONED }
        return listOf(Stage.WISH, Stage.ACTIVE, Stage.DONE).map { st ->
            QueueSection(
                stage = st,
                entries = list.filter { it.stage == st }
                    .sortedByDescending { it.addedAt },
            )
        }
    }

    /** 想讀過期清單（加入滿 staleDays 未開卷） */
    fun staleWishes(now: Long = System.currentTimeMillis()): List<QueueEntry> {
        val cutoff = now - staleDays() * DAY_MS
        return load().filter { it.stage == Stage.WISH && it.addedAt < cutoff }
            .sortedBy { it.addedAt }
    }

    /** 過期門檻（天） */
    fun staleDays(): Int =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_STALE_DAYS, DEFAULT_STALE_DAYS)
            .coerceIn(STALE_MIN_DAYS, STALE_MAX_DAYS)

    /** 設定過期門檻 */
    fun setStaleDays(days: Int) {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_STALE_DAYS, days.coerceIn(STALE_MIN_DAYS, STALE_MAX_DAYS))
            .apply()
    }

    /** 各階段計數（面板摘要行） */
    fun stageCount(stage: Stage): Int = load().count { it.stage == stage }

    /** 帳冊檔（供備份層只拷檔不解析） */
    fun queueFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ── 內部 ───────────────────────────────────────────────────

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_STALE_DAYS = "queue_stale_days"
    private const val MODE_PRIVATE = android.content.Context.MODE_PRIVATE

    private var cache: MutableList<QueueEntry>? = null

    private fun keyOf(bookName: String, author: String): String =
        "${bookName.trim()}|${author.trim()}"

    private fun keyOf(e: QueueEntry): String = keyOf(e.bookName, e.author)

    private fun load(): MutableList<QueueEntry> {
        cache?.let { return it }
        val out = mutableListOf<QueueEntry>()
        val file = queueFile()
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += QueueEntry(
                        bookName = o.optString("book"),
                        author = o.optString("author"),
                        stage = Stage.entries.getOrElse(o.optInt("stage", 0)) { Stage.WISH },
                        addedAt = o.optLong("addedAt", 0L),
                        startedAt = o.optLong("startedAt", 0L),
                        finishedAt = o.optLong("finishedAt", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "待讀清單解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in load()) {
            arr.put(JSONObject().apply {
                put("book", e.bookName)
                put("author", e.author)
                put("stage", e.stage.ordinal)
                put("addedAt", e.addedAt)
                put("startedAt", e.startedAt)
                put("finishedAt", e.finishedAt)
            })
        }
        try {
            queueFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "待讀清單寫入失敗", t)
        }
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.queue_title)
    fun addLabel(): String = AppContext.ctx.getString(R.string.queue_add)
    fun addedNotice(): String = AppContext.ctx.getString(R.string.queue_added)
    fun existsNotice(): String = AppContext.ctx.getString(R.string.queue_exists)
    fun quotaNotice(): String = AppContext.ctx.getString(R.string.queue_quota)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.queue_empty)
    fun abandonLabel(): String = AppContext.ctx.getString(R.string.queue_abandon)
    fun clearLabel(): String = AppContext.ctx.getString(R.string.queue_clear_done)
    fun staleTitle(): String = AppContext.ctx.getString(R.string.queue_stale_title)

    fun stageName(stage: Stage): String = when (stage) {
        Stage.WISH -> AppContext.ctx.getString(R.string.queue_stage_wish)
        Stage.ACTIVE -> AppContext.ctx.getString(R.string.queue_stage_active)
        Stage.DONE -> AppContext.ctx.getString(R.string.queue_stage_done)
        Stage.ABANDONED -> AppContext.ctx.getString(R.string.queue_stage_abandoned)
    }

    /** 分組標題：「想讀（12）」 */
    fun sectionTitle(section: QueueSection): String =
        AppContext.ctx.getString(
            R.string.queue_section_fmt,
            stageName(section.stage),
            section.entries.size,
        )

    /** 清單行：「《書名》・作者」 */
    fun entryLine(e: QueueEntry): String =
        if (e.author.isBlank()) {
            AppContext.ctx.getString(R.string.queue_entry_no_author_fmt, e.bookName)
        } else {
            AppContext.ctx.getString(R.string.queue_entry_fmt, e.bookName, e.author)
        }

    /** 過期提醒行：「《書名》——45 天前就想讀了」 */
    fun staleLine(e: QueueEntry, daysAgo: Int): String =
        AppContext.ctx.getString(R.string.queue_stale_fmt, e.bookName, daysAgo)

    /** 摘要行：「想讀 12・在讀 3・已讀 5」 */
    fun summaryLine(): String =
        AppContext.ctx.getString(
            R.string.queue_summary_fmt,
            stageCount(Stage.WISH),
            stageCount(Stage.ACTIVE),
            stageCount(Stage.DONE),
        )
}

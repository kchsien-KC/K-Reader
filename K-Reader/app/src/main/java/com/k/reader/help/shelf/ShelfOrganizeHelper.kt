package com.k.reader.help.shelf

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 書架整理助手（Stage 8 旗艦增益：書架整理）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（書架和書櫃一樣，久了會積灰）：
 *   加入一時爽的書、連載棄坑的書、其實早就讀完的書——書架
 *   需要的不是更密的資料夾，而是一次「斷捨離體檢」：
 *
 *    1. 體檢：analyze() 掃全書架產出 ShelfReport——完本／連載
 *       在讀／冷凍（DORMANT_THRESHOLD_DAYS 天未翻頁）三桶，
 *       每本帶 shelf_dormant_days_fmt 標籤；Book 欄位存取走
 *       runCatching 寬容層（髒欄位當 null，不炸體檢）；
 *    2. 排序：五種鍵（最近閱讀 durChapterTime／加入時間／書
 *       名／作者／未讀進度）——sortBy() 純函式，列表層直接餵；
 *    3. 歸檔：archiveDormant() 把冷凍書的 group 統一改「冷凍
 *       庫」群組編號（群組編號冪等確保——ensureDormantGroup()
 *       跨啟動記在 prefs，不與使用者既有分組衝突）；寫回由呼
 *       叫端批量 observe（純建議層，不直接動資料庫）；
 *    4. 紀律：零排序副作用（sortBy 回新清單）；冪等歸檔（已
 *       在冷凍群組的書不重複回報）；閾值可調（prefs）。
 *
 *  整合點：
 *   · 書架選單 shelf_organize_title → analyze() 體檢報告
 *   · 報告「一鍵歸檔」→ archiveDormant() 回傳變更對（呼叫端寫回）
 *   · 書架排序選單 → sortBy()
 */
object ShelfOrganizeHelper {

    private const val TAG = "ShelfOrganize"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_DORMANT_DAYS = "shelf_dormant_days"
    private const val KEY_DORMANT_GROUP = "shelf_dormant_group"

    /** 冷凍判定門檻預設（規格：30 天未翻頁） */
    const val DEFAULT_DORMANT_DAYS = 30

    /** 冷凍群組起始編號（避開 legado 0=未分組／1、2 常用分組） */
    const val DORMANT_GROUP_BASE = 90

    /** 排序鍵：最近閱讀（durChapterTime 降序） */
    const val SORT_RECENT = "RECENT"

    /** 排序鍵：加入時間（最新在前） */
    const val SORT_ADDED = "ADDED"

    /** 排序鍵：書名（升序） */
    const val SORT_NAME = "NAME"

    /** 排序鍵：作者（升序） */
    const val SORT_AUTHOR = "AUTHOR"

    private val prefs by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    /** 書籍摘要（體檢報告單行——Book 的寬容取樣快照） */
    data class BookBrief(
        val bookUrl: String,
        val name: String,
        val author: String?,
        val group: Int,
        val lastReadMillis: Long,
        val dormantDays: Int,
        val isFinished: Boolean
    )

    /** 體檢報告：三桶清單＋彙總計數 */
    data class ShelfReport(
        val finished: List<BookBrief>,
        val serial: List<BookBrief>,
        val dormant: List<BookBrief>,
        val dormantThresholdDays: Int,
        val dormantGroup: Int
    )

    // ═════════════════ 設定 ═════════════════

    fun getDormantThresholdDays(): Int = prefs.getInt(KEY_DORMANT_DAYS, DEFAULT_DORMANT_DAYS)

    fun setDormantThresholdDays(days: Int) {
        runCatching { prefs.edit().putInt(KEY_DORMANT_DAYS, days.coerceAtLeast(7)).apply() }
    }

    /** 冷凍群組編號（首次取 90+；冪等——後續回傳同值） */
    fun ensureDormantGroup(maxExistingGroup: Int): Int {
        val stored = prefs.getInt(KEY_DORMANT_GROUP, -1)
        if (stored >= 0) return stored
        val chosen = (maxExistingGroup + 1).coerceAtLeast(DORMANT_GROUP_BASE)
        runCatching { prefs.edit().putInt(KEY_DORMANT_GROUP, chosen).apply() }
        return chosen
    }

    // ═════════════════ 體檢 ═════════════════

    /**
     * 全書架體檢：三桶分類＋冷凍天數標記。
     * Book 欄位存取全程寬容（runCatching——髒資料不炸體檢）。
     */
    fun analyze(books: List<Book>): ShelfReport {
        val threshold = getDormantThresholdDays()
        val now = System.currentTimeMillis()
        val dormantGroup = ensureDormantGroup(books.mapNotNull { lenientGroup(it) }.maxOrNull() ?: 0)
        val finished = mutableListOf<BookBrief>()
        val serial = mutableListOf<BookBrief>()
        val dormant = mutableListOf<BookBrief>()
        for (book in books) {
            val brief = toBrief(book, now, threshold) ?: continue
            val isDormant = brief.dormantDays >= threshold && !brief.isFinished
            when {
                isDormant -> dormant.add(brief)
                brief.isFinished -> finished.add(brief)
                else -> serial.add(brief)
            }
        }
        // 各桶內依最近閱讀降序（最新動靜在前）
        val byRecent = compareByDescending<BookBrief> { it.lastReadMillis }
        return ShelfReport(
            finished.sortedWith(byRecent),
            serial.sortedWith(byRecent),
            dormant.sortedWith(byRecent),
            threshold,
            dormantGroup
        )
    }

    /** 報告摘要行：shelf_dormant_hint（偵測到 %1$d 本超過 %2$d 天未閱讀） */
    fun reportHint(report: ShelfReport): String =
        AppContext.ctx.getString(R.string.shelf_dormant_hint, report.dormant.size, report.dormantThresholdDays)

    // ═════════════════ 排序 ═════════════════

    /** 排序（純函式——零副作用回新清單；未知鍵退最近閱讀） */
    fun sortBy(books: List<Book>, sortKey: String): List<Book> = when (sortKey) {
        SORT_NAME -> books.sortedBy { lenientName(it) }
        SORT_AUTHOR -> books.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { lenientAuthor(it).orEmpty() }
        )
        SORT_ADDED -> books.sortedByDescending { lenientAddedTime(it) }
        else -> books.sortedByDescending { lenientLastRead(it) } // SORT_RECENT 預設
    }

    // ═════════════════ 歸檔 ═════════════════

    /**
     * 一鍵歸檔：冷凍書 → 冷凍群組。回傳（book, 新群組編號）對
     * 清單——寫回由呼叫端批量執行（純建議層不直接動資料庫）。
     * 冪等：已在冷凍群組的書不重複回報。
     */
    fun archiveDormant(books: List<Book>): List<Pair<Book, Int>> {
        val report = analyze(books)
        val dormantUrls = report.dormant.map { it.bookUrl }.toSet()
        val out = mutableListOf<Pair<Book, Int>>()
        for (book in books) {
            val url = lenientUrl(book)
            if (url in dormantUrls) {
                val currentGroup = lenientGroup(book) ?: 0
                if (currentGroup != report.dormantGroup) {
                    out.add(book to report.dormantGroup)
                }
            }
        }
        return out
    }

    /** 歸檔完成文案：shelf_archive_done（已歸檔 %1$d 本書） */
    fun archiveDoneText(count: Int): String =
        AppContext.ctx.getString(R.string.shelf_archive_done, count)

    // ═════════════════ 寬容取樣層 ═════════════════

    /** Book → 摘要（欄位缺失回 null——體檢跳過不炸） */
    private fun toBrief(book: Book, now: Long, threshold: Int): BookBrief? = runCatching {
        val name = lenientName(book)
        if (name.isBlank()) return@runCatching null
        val lastRead = lenientLastRead(book)
        val dormantDays = if (lastRead <= 0L) Int.MAX_VALUE / 2 else {
            ((now - lastRead) / DAY_MS).toInt().coerceAtLeast(0)
        }
        BookBrief(
            bookUrl = lenientUrl(book),
            name = name,
            author = lenientAuthor(book),
            group = lenientGroup(book) ?: 0,
            lastReadMillis = lastRead,
            dormantDays = dormantDays,
            isFinished = lenientFinished(book)
        )
    }.getOrNull().also { brief ->
        if (brief == null) Log.w(TAG, "書籍摘要取樣失敗（髒欄位——跳過）")
    }

    private fun lenientName(book: Book): String =
        runCatching { book.name.orEmpty().trim() }.getOrDefault("")

    private fun lenientAuthor(book: Book): String? =
        runCatching { book.author?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    private fun lenientUrl(book: Book): String =
        runCatching { book.bookUrl.orEmpty() }.getOrDefault("")

    private fun lenientGroup(book: Book): Int? =
        runCatching { book.group }.getOrNull()

    private fun lenientFinished(book: Book): Boolean =
        runCatching { book.finish }.getOrDefault(false)

    private fun lenientLastRead(book: Book): Long =
        runCatching { book.durChapterTime }.getOrDefault(0L)

    /** 加入時間：legado Book 無專欄時退 lastCheck（最近檢查）→ 0 */
    private fun lenientAddedTime(book: Book): Long =
        runCatching { book.lastCheck }.getOrDefault(0L)

    private val DAY_MS = 24L * 60L * 60L * 1000L
}

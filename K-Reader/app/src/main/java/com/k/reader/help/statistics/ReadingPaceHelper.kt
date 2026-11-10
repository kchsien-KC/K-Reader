package com.k.reader.help.statistics

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.timeline.ReadingTimelineHelper

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 讀速洞察助手（Stage 12：閱讀深耕與個人化）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（知道自己的速度，才敢安排自己的量）：
 *   Stage 11 的時間軸記下了每書每筆的「分鐘＋字數」，本助手把
 *   這本流水帳翻譯成人話——「這本書你一分鐘讀 480 字，比你的
 *   平均快兩成」：
 *
 *    1. 每書讀速：TimelineEntry 的 words/minutes 逐書聚合
 *       （單筆鐘點不足 1 分鐘不計——防翻頁噪音）；
 *    2. 快慢榜：全庫平均為基準，快榜／慢榜各前 5（慢不是壞事
 *       ——慢的書多半是難的書，配溫故詞頻一起看）；
 *    3. 趨勢：近 7 日 vs 前 7 日的全庫讀速對比——「這週你讀
 *       得快了 12%」（速度是注意力的體溫計）。
 *
 *  規格值：單筆計速下限 1 分鐘；榜上名次 5；趨勢窗各 7 日；
 *  讀速顯示字／分整數；資料單源 ReadingTimelineHelper 唯讀
 *  （不記帳——時間軸清空，讀速即歸零，兩處永不矛盾）。
 *
 *  整合點：
 *   · 設定頁 pace_title 面板 → bookPaces()／trend()
 *   · 統計頁增益欄 → overallPace()
 *   · ReadingTimelineHelper.allSessions 唯讀視窗
 */
object ReadingPaceHelper {

    private const val TAG = "ReadingPaceHelper"

    /** 榜上名次 */
    const val TOP_N = 5

    /** 趨勢窗（日） */
    const val TREND_WINDOW_DAYS = 7

    /** 單筆計速下限（分鐘）——防翻頁噪音 */
    const val MIN_SESSION_MINUTES = 1L

    /** 單書讀速行 */
    data class BookPace(
        val bookName: String,
        val minutes: Long,
        val words: Long,
        val pace: Int,
    )

    /** 讀速榜（快／慢各前 5） */
    data class PaceBoard(
        val fastest: List<BookPace>,
        val slowest: List<BookPace>,
        val averagePace: Int,
    )

    /** 趨勢（近 7 日 vs 前 7 日） */
    data class PaceTrend(
        val recentPace: Int,
        val previousPace: Int,
        val deltaPercent: Int,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /** 全庫平均讀速（字／分；無數據回 0） */
    fun overallPace(): Int {
        val entries = validEntries()
        val minutes = entries.sumOf { it.minutes }
        val words = entries.sumOf { it.words }
        return paceOf(words, minutes)
    }

    /** 快慢榜（以全庫平均為基準各取前 5） */
    fun bookPaces(): PaceBoard {
        val rows = perBook()
        val average = paceOf(
            rows.sumOf { it.words },
            rows.sumOf { it.minutes },
        )
        return PaceBoard(
            fastest = rows.filter { it.pace > average }
                .sortedByDescending { it.pace }.take(TOP_N),
            slowest = rows.filter { it.pace < average && it.pace > 0 }
                .sortedBy { it.pace }.take(TOP_N),
            averagePace = average,
        )
    }

    /** 趨勢：近 7 日 vs 前 7 日（全庫聚合讀速對比） */
    fun trend(now: Long = System.currentTimeMillis()): PaceTrend {
        val dayMs = 24L * 60L * 60L * 1000L
        val recentStart = now - TREND_WINDOW_DAYS * dayMs
        val previousStart = now - 2L * TREND_WINDOW_DAYS * dayMs
        val entries = allEntries()
        val recent = entries.filter { it.ts >= recentStart }
        val previous = entries.filter { it.ts in previousStart until recentStart }
        val recentPace = paceOf(
            recent.sumOf { it.words },
            recent.sumOf { it.minutes },
        )
        val previousPace = paceOf(
            previous.sumOf { it.words },
            previous.sumOf { it.minutes },
        )
        return PaceTrend(
            recentPace = recentPace,
            previousPace = previousPace,
            deltaPercent = deltaPercentOf(recentPace, previousPace),
        )
    }

    // ── 內部：資料單源（時間軸唯讀） ─────────────────────────────

    /** 時間軸唯讀視窗（Stage 12 整合：allSessions()——單源不記帳） */
    private fun allEntries(): List<ReadingTimelineHelper.TimelineEntry> = try {
        ReadingTimelineHelper.allSessions()
    } catch (t: Throwable) {
        Log.w(TAG, "時間軸不可讀——讀速按 0 處理", t)
        emptyList()
    }

    /** 有效單筆：鐘點足 1 分鐘（防翻頁噪音） */
    private fun validEntries(): List<ReadingTimelineHelper.TimelineEntry> =
        allEntries().filter { it.minutes >= MIN_SESSION_MINUTES }

    /** 逐書聚合（words／minutes 各自求和再相除——均值不等於均值的均值） */
    private fun perBook(): List<BookPace> {
        val grouped = validEntries().groupBy { it.bookName }
        return grouped.map { (book, list) ->
            val minutes = list.sumOf { it.minutes }
            val words = list.sumOf { it.words }
            BookPace(
                bookName = book,
                minutes = minutes,
                words = words,
                pace = paceOf(words, minutes),
            )
        }.filter { it.pace > 0 }.sortedByDescending { it.pace }
    }

    /** 字／分（分鐘為 0 回 0；四捨五入整數） */
    private fun paceOf(words: Long, minutes: Long): Int =
        if (minutes <= 0L) 0
        else ((words + minutes / 2L) / minutes).toInt()

    /** 變化百分比（前值 0 回 0；夾 ±999） */
    private fun deltaPercentOf(recent: Int, previous: Int): Int {
        if (previous <= 0) return 0
        return ((recent - previous) * 100 / previous).coerceIn(-999, 999)
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.pace_title)
    fun boardTitle(): String = AppContext.ctx.getString(R.string.pace_board)
    fun trendTitle(): String = AppContext.ctx.getString(R.string.pace_trend)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.pace_empty)
    fun fastestLabel(): String = AppContext.ctx.getString(R.string.pace_fastest)
    fun slowestLabel(): String = AppContext.ctx.getString(R.string.pace_slowest)

    /** 平均行：「全庫平均 420 字／分」 */
    fun averageLine(pace: Int): String =
        AppContext.ctx.getString(R.string.pace_average_fmt, pace)

    /** 榜行：「《書名》480 字／分」 */
    fun bookLine(row: BookPace): String =
        AppContext.ctx.getString(R.string.pace_book_fmt, row.bookName, row.pace)

    /** 趨勢行：「近 7 日 450 vs 前 7 日 400 字／分（+12%）」 */
    fun trendLine(t: PaceTrend): String =
        AppContext.ctx.getString(
            R.string.pace_trend_fmt,
            t.recentPace,
            t.previousPace,
            t.deltaPercent,
        )
}

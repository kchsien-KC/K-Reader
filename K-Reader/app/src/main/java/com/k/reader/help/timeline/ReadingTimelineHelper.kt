package com.k.reader.help.timeline

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 閱讀時間軸助手（Stage 11：閱讀習慣養成）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（統計是總量，時間軸是故事）：
 *   ReadStatisticsManager 記「每天總共讀多久」，回答不出
 *   「我昨天晚上在讀哪本、讀到哪裡」。時間軸補上書目維度：
 *
 *    1. 記帳：與閱讀統計同一餵入點（onReadingSession 之後追
 *       一筆）——每筆 {書名、章節、分鐘、時間戳}；總量帳仍
 *       歸統計模組，時間軸只記「誰在何時」；
 *    2. 視圖：todayRecap()＝「我的今天」回顧卡（今天讀過的
 *       書、每書時長、最後停留章節——隔日早晨開書的暖場）；
 *       weekByBook()＝近 7 日每書彙總（書架整理的時間軸側
 *       寫）；lastVisit(book)＝某書最後閱讀（「上次讀到」）；
 *    3. 紀律：90 天保留期（TIMELINE_RETENTION_DAYS，時間軸
 *       是回顧不是永久檔案館——舊帳自動清倉防膨脹）；同
 *       5 分鐘內同書同章的連續筆合併（防碎片——翻頁頻餵
 *       入不會刷出長蛇帳）。
 *
 *  規格值：保留 90 天；碎片合併窗 5 分鐘；回顧卡最多列 5 書
 *  （長日讀多書按時長截尾）；帳冊 filesDir/reading_timeline.json。
 *
 *  整合點：
 *   · onReadingSession 寫統計之後 → appendSession()
 *   · 書架長按／書籍詳情 → lastVisit()
 *   · 設定頁 timeline_title 面板 → todayRecap()／weekByBook()
 *   · 備份層 → timelineFile()（只拷檔不解析）
 */
object ReadingTimelineHelper {

    private const val TAG = "ReadingTimelineHelper"

    /** 時間軸帳冊檔名（filesDir 下） */
    const val BOOK_FILE = "reading_timeline.json"

    /** 保留期（天）：超過自動清倉 */
    const val RETENTION_DAYS = 90

    /** 碎片合併窗（毫秒）：同書同章 5 分鐘內的筆併入前筆 */
    const val MERGE_WINDOW_MS = 5L * 60L * 1000L

    /** 回顧卡最多列書數 */
    const val RECAP_MAX_BOOKS = 5

    /** 單筆閱讀記錄 */
    data class TimelineEntry(
        val bookName: String,
        val chapterTitle: String,
        val minutes: Long,
        val words: Long,
        val ts: Long,
    )

    /** 「我的今天」回顧卡單行 */
    data class RecapRow(
        val bookName: String,
        val minutes: Long,
        val lastChapter: String,
        val lastAt: Long,
    )

    /** 近 7 日每書彙總行 */
    data class BookTimeRow(
        val bookName: String,
        val minutes: Long,
        val days: Int,
        val lastVisit: Long,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 追記一筆（閱讀統計落帳後呼叫）：同書同章 5 分鐘內併入
     * 前筆（碎片防護）；每次載入時順手清倉 90 天前舊帳。
     */
    fun appendSession(
        bookName: String,
        chapterTitle: String,
        minutes: Long,
        words: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        if (minutes <= 0L && words <= 0L) return
        val list = load()
        val tail = list.lastOrNull()
        if (tail != null
            && tail.bookName == bookName
            && tail.chapterTitle == chapterTitle
            && now - tail.ts <= MERGE_WINDOW_MS
        ) {
            val merged = tail.copy(
                minutes = tail.minutes + minutes,
                words = tail.words + words,
                ts = now,
            )
            list[list.size - 1] = merged
        } else {
            list += TimelineEntry(
                bookName = bookName,
                chapterTitle = chapterTitle,
                minutes = minutes.coerceAtLeast(0L),
                words = words.coerceAtLeast(0L),
                ts = now,
            )
        }
        persist(purge(list, now))
    }

    /** 「我的今天」回顧卡：今天讀過的書（時長降冪，最多 5 本） */
    fun todayRecap(now: Long = System.currentTimeMillis()): List<RecapRow> {
        val dayStart = startOfDay(now)
        return load()
            .filter { it.ts >= dayStart && it.minutes > 0L }
            .groupBy { it.bookName }
            .map { (book, entries) ->
                val last = entries.maxBy { it.ts }
                RecapRow(
                    bookName = book,
                    minutes = entries.sumOf { it.minutes },
                    lastChapter = last.chapterTitle,
                    lastAt = last.ts,
                )
            }
            .sortedByDescending { it.minutes }
            .take(RECAP_MAX_BOOKS)
    }

    /** 近 7 日每書彙總（書架時間軸側寫） */
    fun weekByBook(now: Long = System.currentTimeMillis()): List<BookTimeRow> {
        val windowStart = startOfDay(now) - 6L * DAY_MS
        return load()
            .filter { it.ts >= windowStart && it.minutes > 0L }
            .groupBy { it.bookName }
            .map { (book, entries) ->
                BookTimeRow(
                    bookName = book,
                    minutes = entries.sumOf { it.minutes },
                    days = entries.map { dayKeyOf(it.ts) }.toSet().size,
                    lastVisit = entries.maxOf { it.ts },
                )
            }
            .sortedByDescending { it.minutes }
    }

    /** 某書最後閱讀記錄（「上次讀到」書架長按用）；無記錄回 null */
    fun lastVisit(bookName: String): TimelineEntry? =
        load().filter { it.bookName == bookName && it.minutes > 0L }
            .maxByOrNull { it.ts }

    /** 帳冊檔（供備份層只拷檔不解析） */
    fun timelineFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    /** 清倉（設定頁「清空時間軸」）：回傳清除筆數 */
    fun clearAll(): Int {
        val n = load().size
        cache = mutableListOf()
        persist(cache!!)
        return n
    }

    // ── 內部 ───────────────────────────────────────────────────

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private var cache: MutableList<TimelineEntry>? = null

    private fun load(): MutableList<TimelineEntry> {
        cache?.let { return it }
        val out = mutableListOf<TimelineEntry>()
        val file = timelineFile()
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += TimelineEntry(
                        bookName = o.optString("book"),
                        chapterTitle = o.optString("chapter"),
                        minutes = o.optLong("minutes", 0L),
                        words = o.optLong("words", 0L),
                        ts = o.optLong("ts", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "時間軸解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist(list: List<TimelineEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("book", e.bookName)
                put("chapter", e.chapterTitle)
                put("minutes", e.minutes)
                put("words", e.words)
                put("ts", e.ts)
            })
        }
        try {
            timelineFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "時間軸寫入失敗", t)
        }
    }

    /** 清倉：保留最近 RETENTION_DAYS 天 */
    private fun purge(list: List<TimelineEntry>, now: Long): List<TimelineEntry> {
        val cutoff = now - RETENTION_DAYS * DAY_MS
        return list.filter { it.ts >= cutoff }
    }

    private fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayKeyOf(ts: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.TAIWAN).format(Date(ts))

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.timeline_title)
    fun todayTitle(): String = AppContext.ctx.getString(R.string.timeline_today)
    fun weekTitle(): String = AppContext.ctx.getString(R.string.timeline_week)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.timeline_empty)
    fun clearLabel(): String = AppContext.ctx.getString(R.string.timeline_clear)
    fun clearedNotice(): String = AppContext.ctx.getString(R.string.timeline_cleared)

    /** 回顧行：「《書名》42 分——第 3 章」 */
    fun recapLine(row: RecapRow): String =
        AppContext.ctx.getString(
            R.string.timeline_recap_fmt,
            row.bookName,
            row.minutes,
            row.lastChapter,
        )

    /** 週彙行：「《書名》共 5 小時 20 分（4 天）」 */
    fun weekLine(row: BookTimeRow): String =
        AppContext.ctx.getString(
            R.string.timeline_week_fmt,
            row.bookName,
            row.minutes,
            row.days,
        )

    /** 「上次讀到：第 X 章（3 天前）」 */
    fun lastVisitLine(entry: TimelineEntry, daysAgo: Int): String =
        AppContext.ctx.getString(
            R.string.timeline_last_visit_fmt,
            entry.chapterTitle,
            daysAgo,
        )
}

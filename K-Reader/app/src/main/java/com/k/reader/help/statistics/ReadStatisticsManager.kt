package com.k.reader.help.statistics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 閱讀統計管理器（十大旗艦功能：閱讀統計）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（閱讀的重量，讓數字說話）：
 *   閱讀是複利行為，但記憶會美化或貶低它。以「日」為最小單位
 *   累計兩個硬指標——時長（分鐘）與字數——完讀一本書時順手記
 *   一筆完本：
 *
 *    1. 記錄：onReadingSession(book, minutes, words)——閱讀頁
 *       onResume/onPause 差值餵入；單次上限 60 分鐘截斷（掛機
 *       / 忘關螢幕不注水）；book.finish 為真時書名作者鍵入
 *       完本集（完本數跨期穩定，不隨刪書消失）；
 *    2. 查詢：今日／本週／本月／累計四期匯總（時長＋字數＋完本
 *       數＋活躍天數）；本週／本月採滾動窗口（7／30 天）——
 *       跨月跨週不歸零，行為連續性優於日曆邊界；
 *    3. 圖表：dailyChart() 供 stats_chart_daily 長條圖——最近
 *       14 天（規格值）、零填空、日期鍵升序，UI 端直接餵高；
 *    4. 格式化：formatDuration／formatWords 對應 stats_hour_fmt
 *       ／stats_minute_fmt／stats_words_fmt 三支格式字串；
 *    5. 自潔：僅保留最近 365 天（規格值）記錄——統計檔案恆小。
 *
 *  整合點：
 *   · 閱讀頁生命週期（onResume/onPause 差值）→ onReadingSession
 *   · 「我的」頁 stats_title 面板 → summary*/dailyChart/format*
 */
object ReadStatisticsManager {

    private const val TAG = "ReadStatisticsManager"

    private const val PREFS_NAME = "k_reader_statistics"
    private const val KEY_DAILY = "daily_records_json"
    private const val KEY_FINISHED = "finished_books_json"

    /** 統計保留天數（規格：365 天） */
    const val RETENTION_DAYS = 365

    /** 每日閱讀時長圖表天數（規格：14 天） */
    const val CHART_DAYS = 14

    /** 單次記錄時長上限（規格：60 分鐘——掛機截斷） */
    const val MAX_SESSION_MINUTES = 60

    /** 滾動窗口：本週＝最近 7 天 */
    const val WEEK_WINDOW_DAYS = 7

    /** 滾動窗口：本月＝最近 30 天 */
    const val MONTH_WINDOW_DAYS = 30

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 單日累計（時長分鐘／字數） */
    data class DailyRecord(var minutes: Long, var words: Long)

    /** 四期匯總（stats_duration／stats_words／stats_finish／活躍天數） */
    data class StatsSummary(
        val minutes: Long,
        val words: Long,
        val finishedCount: Int,
        val activeDays: Int
    )

    /** 長條圖單柱（stats_chart_daily 資料源） */
    data class DailyBar(val dateKey: String, val minutes: Long)

    // ═════════════════ 記錄 ═════════════════

    /**
     * 閱讀時段落帳：累入當日記錄＋完本集登記＋逾期自潔。
     * book 傳 null 時僅累計數值（不登記完本）。
     */
    fun onReadingSession(book: Book?, minutes: Long, words: Long) {
        runCatching {
            val cappedMinutes = minutes.coerceIn(0L, MAX_SESSION_MINUTES.toLong())
            val cappedWords = words.coerceAtLeast(0L)
            if (cappedMinutes == 0L && cappedWords == 0L) return
            val today = dateKeyOf(System.currentTimeMillis())
            val daily = loadDaily().toMutableMap()
            val record = daily.getOrPut(today) { DailyRecord(0L, 0L) }
            record.minutes += cappedMinutes
            record.words += cappedWords
            persistDaily(purgeOld(daily))

            // 完本登記：書名＋作者為鍵（換源不改書名，跨源不重計）
            val finishedFlag = runCatching { book?.finish == true }.getOrDefault(false)
            if (finishedFlag && book != null) {
                val key = finishedKeyOf(book)
                val finishedSet = loadFinished().toMutableSet()
                if (finishedSet.add(key)) {
                    persistFinished(finishedSet)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "閱讀統計落帳失敗", e)
        }
    }

    // ═════════════════ 四期匯總 ═════════════════

    fun summaryToday(): StatsSummary = summaryOf(1)

    fun summaryWeek(): StatsSummary = summaryOf(WEEK_WINDOW_DAYS)

    fun summaryMonth(): StatsSummary = summaryOf(MONTH_WINDOW_DAYS)

    fun summaryTotal(): StatsSummary {
        val daily = loadDaily()
        return StatsSummary(
            minutes = daily.values.sumOf { it.minutes },
            words = daily.values.sumOf { it.words },
            finishedCount = loadFinished().size,
            activeDays = daily.count { it.value.minutes > 0L || it.value.words > 0L }
        )
    }

    /** 滾動窗口匯總（含今天共 windowDays 天；完本數／活躍天僅總口徑） */
    private fun summaryOf(windowDays: Int): StatsSummary {
        val daily = loadDaily()
        val windowStart = dayStartOf(nowMillis() - (windowDays - 1L) * DAY_MS)
        var minutes = 0L
        var words = 0L
        var activeDays = 0
        for ((key, record) in daily) {
            if (dayStartOf(key) >= windowStart) {
                minutes += record.minutes
                words += record.words
                if (record.minutes > 0L || record.words > 0L) activeDays++
            }
        }
        return StatsSummary(minutes, words, loadFinished().size, activeDays)
    }

    // ═════════════════ 圖表資料 ═════════════════

    /**
     * 最近 days 天（預設 14）每日時長序列：
     * 日期鍵升序、缺日零填——長條圖直接上柱，無需 UI 補空。
     */
    fun dailyChart(days: Int = CHART_DAYS): List<DailyBar> {
        val daily = loadDaily()
        val out = mutableListOf<DailyBar>()
        for (offset in (days - 1) downTo 0) {
            val key = dateKeyOf(nowMillis() - offset * DAY_MS)
            out.add(DailyBar(key, daily[key]?.minutes ?: 0L))
        }
        return out
    }

    // ═════════════════ 格式化（字串規格承接） ═════════════════

    /** 時長格式化：stats_hour_fmt（%1$d 小時 %2$d 分）／stats_minute_fmt（%1$d 分） */
    fun formatDuration(minutes: Long): String {
        val total = minutes.coerceAtLeast(0L)
        val ctx = AppContext.ctx
        return if (total >= 60L) {
            ctx.getString(R.string.stats_hour_fmt, total / 60L, total % 60L)
        } else {
            ctx.getString(R.string.stats_minute_fmt, total)
        }
    }

    /** 字數格式化：stats_words_fmt（%1$s 萬字；未滿萬以千位原值呈現） */
    fun formatWords(words: Long): String {
        val ctx = AppContext.ctx
        val safe = words.coerceAtLeast(0L)
        return if (safe >= 10_000L) {
            ctx.getString(R.string.stats_words_fmt, String.format(Locale.TAIWAN, "%.1f", safe / 10_000.0))
        } else {
            ctx.getString(R.string.stats_words_fmt, safe.toString())
        }
    }

    // ═════════════════ UI 文案 ═════════════════

    fun panelTitle(): String = AppContext.ctx.getString(R.string.stats_title)

    fun todayLabel(): String = AppContext.ctx.getString(R.string.stats_today)

    fun weekLabel(): String = AppContext.ctx.getString(R.string.stats_week)

    fun monthLabel(): String = AppContext.ctx.getString(R.string.stats_month)

    fun totalLabel(): String = AppContext.ctx.getString(R.string.stats_total)

    fun durationLabel(): String = AppContext.ctx.getString(R.string.stats_duration)

    fun wordsLabel(): String = AppContext.ctx.getString(R.string.stats_words)

    fun finishLabel(): String = AppContext.ctx.getString(R.string.stats_finish)

    fun chartTitle(): String = AppContext.ctx.getString(R.string.stats_chart_daily)

    // ═════════════════ 持久化與工具 ═════════════════

    /** 每毫秒數（day key 以本地時區日界切分） */
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun nowMillis(): Long = System.currentTimeMillis()

    private fun dateKeyOf(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

    /** 日期鍵 → 當日零點毫秒（本地時區） */
    private fun dayStartOf(dateKey: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)?.time ?: 0L
    }.getOrDefault(0L)

    private fun dayStartOf(millis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /** 逾期自潔：僅保留最近 RETENTION_DAYS 天 */
    private fun purgeOld(daily: Map<String, DailyRecord>): Map<String, DailyRecord> {
        val cutoff = dayStartOf(nowMillis() - RETENTION_DAYS * DAY_MS)
        return daily.filterKeys { dayStartOf(it) >= cutoff }
    }

    private fun finishedKeyOf(book: Book): String =
        "${book.name.orEmpty().trim()}|${book.author.orEmpty().trim()}"

    private fun loadDaily(): Map<String, DailyRecord> = runCatching {
        prefs.getString(KEY_DAILY, null)?.let {
            gson.fromJson(it, object : TypeToken<Map<String, DailyRecord>>() {}.type)
                as? Map<String, DailyRecord>
        }
    }.getOrNull().orEmpty()

    private fun persistDaily(daily: Map<String, DailyRecord>) {
        runCatching {
            prefs.edit().putString(KEY_DAILY, gson.toJson(daily)).apply()
        }.onFailure { e ->
            Log.w(TAG, "閱讀統計持久化失敗", e)
        }
    }

    private fun loadFinished(): Set<String> = runCatching {
        prefs.getStringSet(KEY_FINISHED, emptySet())?.toSet()
    }.getOrNull().orEmpty()

    private fun persistFinished(finished: Set<String>) {
        runCatching {
            prefs.edit().putStringSet(KEY_FINISHED, finished).apply()
        }.onFailure { e ->
            Log.w(TAG, "完本集持久化失敗", e)
        }
    }
}

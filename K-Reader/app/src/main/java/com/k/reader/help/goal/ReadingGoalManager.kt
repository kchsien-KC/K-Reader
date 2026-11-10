package com.k.reader.help.goal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.statistics.ReadStatisticsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 閱讀目標與提醒管理器（Stage 8 旗艦增益：閱讀目標）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（每天一點點，習慣自己長出來）：
 *   統計是回顧，目標是前瞻。本管理器在 ReadStatisticsManager
 *   的數據底盤上疊三層行為設計：
 *
 *    1. 目標：每日「分鐘數」或「字數」二擇一目標（0＝停用）；
 *       進度即時從 summaryToday() 讀——不另記帳，數據單源，
 *       統計頁／目標頁永遠一致；
 *    2. 連續天數（streak）：從 dailyChart() 反推——今天沒讀
 *       不歸零（日曆還沒翻頁，從昨天起算），最長紀錄同時維護
 *       （跨啟動持久化）；
 *    3. 提醒：goal_reminder_title 晚間提醒（時鐘整點比對，
 *       排程層每分鐘呼叫 shouldRemindNow()）＋ goal_achieved
 *       達成即報（同日只報一次——prefs 記通知日鍵，重啟不重播）。
 *
 *   通知紀律：NotificationChannel 使用者可整類關閉；零輪詢、
 *   零背景服務——達成檢查掛在 onReadingSession 之後（本來就
 *   要寫統計的那個時刻），提醒檢查掛在前景分鐘 tick。
 *
 *  整合點：
 *   · 閱讀頁 onResume/onPause 統計落帳後 → checkAchieved()
 *   · 前景分鐘 tick（閱讀頁翻頁時）→ shouldRemindNow()
 *   · 「我的」頁 goal_title 面板 → progress()/streak*()
 */
object ReadingGoalManager {

    private const val TAG = "ReadingGoal"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_GOAL_TYPE = "goal_type"
    private const val KEY_GOAL_MINUTES = "goal_minutes"
    private const val KEY_GOAL_WORDS = "goal_words"
    private const val KEY_REMINDER_HOUR = "goal_reminder_hour"
    private const val KEY_ACHIEVED_DATE = "goal_achieved_notified_date"
    private const val KEY_REMINDED_DATE = "goal_reminded_date"
    private const val KEY_BEST_STREAK = "goal_best_streak"

    /** 目標型態：分鐘數 */
    const val TYPE_MINUTES = "MINUTES"

    /** 目標型態：字數 */
    const val TYPE_WORDS = "WORDS"

    /** 目標分鐘數預設檔（規格：15／30／60／120） */
    val PRESET_MINUTES = listOf(15, 30, 60, 120)

    /** 目標字數預設檔（規格：1／3／5／10 萬字） */
    val PRESET_WORDS = listOf(10_000L, 30_000L, 50_000L, 100_000L)

    /** 提醒預設時（規格：21:00） */
    const val DEFAULT_REMINDER_HOUR = 21

    /** 通知渠道 ID（設定頁可整類關閉） */
    const val CHANNEL_ID = "k_reader_goal"

    /** 達成通知 ID */
    const val NOTIFY_ID_ACHIEVED = 0x51

    /** 提醒通知 ID */
    const val NOTIFY_ID_REMINDER = 0x52

    private val prefs by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 目標進度（ratio 0.0~1.0＋文案；停用時 goal 為 null） */
    data class GoalProgress(
        val type: String,
        val goalMinutes: Long,
        val goalWords: Long,
        val doneMinutes: Long,
        val doneWords: Long,
        val ratio: Float,
        val achieved: Boolean
    )

    // ═════════════════ 目標設定 ═════════════════

    fun getGoalType(): String = prefs.getString(KEY_GOAL_TYPE, TYPE_MINUTES) ?: TYPE_MINUTES

    fun setGoalType(type: String) {
        runCatching { prefs.edit().putString(KEY_GOAL_TYPE, type).apply() }
    }

    fun getGoalMinutes(): Int = prefs.getInt(KEY_GOAL_MINUTES, 0)

    fun setGoalMinutes(minutes: Int) {
        runCatching { prefs.edit().putInt(KEY_GOAL_MINUTES, minutes.coerceAtLeast(0)).apply() }
    }

    fun getGoalWords(): Long = prefs.getLong(KEY_GOAL_WORDS, 0L)

    fun setGoalWords(words: Long) {
        runCatching { prefs.edit().putLong(KEY_GOAL_WORDS, words.coerceAtLeast(0L)).apply() }
    }

    fun getReminderHour(): Int = prefs.getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR)

    fun setReminderHour(hour: Int) {
        runCatching { prefs.edit().putInt(KEY_REMINDER_HOUR, hour.coerceIn(0, 23)).apply() }
    }

    /** 目標是否啟用（分鐘或字數其一 > 0） */
    fun isGoalEnabled(): Boolean =
        (getGoalType() == TYPE_MINUTES && getGoalMinutes() > 0) ||
            (getGoalType() == TYPE_WORDS && getGoalWords() > 0L)

    // ═════════════════ 進度 ═════════════════

    /** 今日進度（數據單源：直接讀 ReadStatisticsManager.summaryToday） */
    fun progress(): GoalProgress? {
        if (!isGoalEnabled()) return null
        val today = ReadStatisticsManager.summaryToday()
        val type = getGoalType()
        return if (type == TYPE_WORDS) {
            val goal = getGoalWords()
            GoalProgress(
                type, 0L, goal, today.minutes, today.words,
                if (goal <= 0L) 0f else (today.words.toFloat() / goal).coerceAtMost(1f),
                today.words >= goal && goal > 0L
            )
        } else {
            val goal = getGoalMinutes().toLong()
            GoalProgress(
                type, goal, 0L, today.minutes, today.words,
                if (goal <= 0L) 0f else (today.minutes.toFloat() / goal).coerceAtMost(1f),
                today.minutes >= goal && goal > 0L
            )
        }
    }

    /** 進度文案：goal_progress_fmt（今日 %1$s／目標 %2$s） */
    fun progressLabel(): String {
        val p = progress() ?: return AppContext.ctx.getString(R.string.goal_disabled)
        val done: String
        val goal: String
        if (p.type == TYPE_WORDS) {
            done = ReadStatisticsManager.formatWords(p.doneWords)
            goal = ReadStatisticsManager.formatWords(p.goalWords)
        } else {
            done = ReadStatisticsManager.formatDuration(p.doneMinutes)
            goal = ReadStatisticsManager.formatDuration(p.goalMinutes)
        }
        return AppContext.ctx.getString(R.string.goal_progress_fmt, done, goal)
    }

    // ═════════════════ 連續天數 ═════════════════

    /**
     * 連續閱讀天數：從 dailyChart() 反推。
     * 今天有讀從今天起算；今天還沒讀從昨天起算（不歸零）。
     */
    fun streakCount(): Int {
        val chart = ReadStatisticsManager.dailyChart(ReadStatisticsManager.RETENTION_DAYS)
        if (chart.isEmpty()) return 0
        var streak = 0
        var idx = chart.size - 1
        // 今天（最後一柱）沒讀——先退一格，streak 不因此歸零
        if (chart[idx].minutes <= 0L) idx--
        while (idx >= 0 && chart[idx].minutes > 0L) {
            streak++
            idx--
        }
        updateBestStreak(streak)
        return streak
    }

    /** 最長連續紀錄（跨啟動持久化，只增不減） */
    fun bestStreak(): Int = prefs.getInt(KEY_BEST_STREAK, 0)

    private fun updateBestStreak(current: Int) {
        if (current > bestStreak()) {
            runCatching { prefs.edit().putInt(KEY_BEST_STREAK, current).apply() }
        }
    }

    /** 連續天數文案：goal_streak_fmt（連續閱讀 %1$d 天） */
    fun streakLabel(): String =
        AppContext.ctx.getString(R.string.goal_streak_fmt, streakCount())

    // ═════════════════ 通知 ═════════════════

    /**
     * 達成檢查：掛閱讀時段落帳之後。已達標且本日未報過 →
     * 發 goal_achieved 通知（同日一次——prefs 記日期鍵）。
     * 回傳是否本次發出（UI 可改用 Toast 內聯呈現）。
     */
    fun checkAchieved(): Boolean {
        val p = progress() ?: return false
        if (!p.achieved) return false
        val today = dateKeyOf(System.currentTimeMillis())
        if (prefs.getString(KEY_ACHIEVED_DATE, null) == today) return false
        runCatching { prefs.edit().putString(KEY_ACHIEVED_DATE, today).apply() }
        val doneText = if (p.type == TYPE_WORDS) {
            ReadStatisticsManager.formatWords(p.doneWords)
        } else {
            ReadStatisticsManager.formatDuration(p.doneMinutes)
        }
        notify(
            NOTIFY_ID_ACHIEVED,
            AppContext.ctx.getString(R.string.goal_achieved_title),
            AppContext.ctx.getString(R.string.goal_achieved, doneText)
        )
        return true
    }

    /**
     * 提醒時機判斷（前景分鐘 tick 呼叫）：時鐘整點等於設定時、
     * 目標未達成、本日未提醒過 → true（呼叫端接著發提醒通知）。
     */
    fun shouldRemindNow(hourOfDay: Int): Boolean {
        if (!isGoalEnabled()) return false
        if (hourOfDay != getReminderHour()) return false
        val p = progress() ?: return false
        if (p.achieved) return false
        val today = dateKeyOf(System.currentTimeMillis())
        if (prefs.getString(KEY_REMINDED_DATE, null) == today) return false
        runCatching { prefs.edit().putString(KEY_REMINDED_DATE, today).apply() }
        return true
    }

    /** 發提醒通知（goal_reminder_title／goal_reminder_text） */
    fun fireReminder() {
        if (!isGoalEnabled()) return
        val goalText = if (getGoalType() == TYPE_WORDS) {
            ReadStatisticsManager.formatWords(getGoalWords())
        } else {
            ReadStatisticsManager.formatDuration(getGoalMinutes().toLong())
        }
        notify(
            NOTIFY_ID_REMINDER,
            AppContext.ctx.getString(R.string.goal_reminder_title),
            AppContext.ctx.getString(R.string.goal_reminder_text, goalText)
        )
    }

    // ═════════════════ 通知基建 ═════════════════

    private fun notify(id: Int, title: String, text: String) {
        runCatching {
            val ctx = AppContext.ctx
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.goal_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationCompat.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                NotificationCompat.Builder(ctx)
            }
            builder.setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
            // 通知點擊：開啟應用（對話框不指定 Activity——疊加層不綁 UI 類）
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            launch?.let { intent ->
                val pending = PendingIntent.getActivity(
                    ctx, id, Intent(intent), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pending)
            }
            manager.notify(id, builder.build())
        }.onFailure { e ->
            Log.w(TAG, "閱讀目標通知發送失敗", e)
        }
    }

    // ═════════════════ 工具 ═════════════════

    private fun dateKeyOf(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}

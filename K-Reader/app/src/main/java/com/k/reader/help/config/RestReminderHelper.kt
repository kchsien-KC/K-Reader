package com.k.reader.help.config

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.k.reader.R
import com.k.reader.app.AppContext

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 休息提醒（Stage 10：久坐護體）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（沉浸是特色，也是代價）：
 *   長篇連載一開就很難停下來——眼睛、頸椎與腰都替你付帳。
 *   休息提醒以「連續閱讀分鐘數」為唯一計量：
 *
 *    1. 計時：閱讀頁 onResume／onPause 差值餵入 onReadingSession
 *       （與閱讀統計同一餵入點，零額外感測）；餵入間隔超過
 *       5 分鐘視為已中斷——累計歸零重來（真正意義上的「連續」）；
 *    2. 提醒：累計觸及門檻（預設 45 分鐘，20～120 夾擊）即發
 *       通知＋輕震動，累計隨之清零進入下一輪；可延後（rest_
 *       snooze——閱讀時間基準的「再讀 10 分鐘」：把累計撥到
 *       門檻前 10 分鐘，暫停多久都不吃額度——追章追到關鍵
 *       處的體面退路）；
 *    3. 禮貌：夜間靜默窗（23:00–07:00 只亮通知不震動）；墨水
 *       屏模式永不震動（EInkHelper 同包直連——電子紙裝置的
 *       震動馬達多半很糟）；通知被系統關閉時靜默跳過不報錯。
 *
 *  規格值：預設門檻 45 分鐘（20～120）；中斷寬限 5 分鐘；
 *  延後額度 10 分鐘（閱讀時間）；頻道 k_reader_rest_reminder（低重要性）。
 *
 *  整合點：
 *   · 閱讀頁生命週期（onResume/onPause 差值）→ onReadingSession
 *   · 提醒通知動作列 → snooze（PendingIntent 由 UI 層掛）
 *   · 設定頁 rest_title 面板 → enable／intervalMinutes／狀態查詢
 */
object RestReminderHelper {

    private const val TAG = "RestReminderHelper"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_ENABLED = "rest_reminder_enabled"
    private const val KEY_INTERVAL = "rest_reminder_interval"
    private const val KEY_ACCUMULATED = "rest_reminder_accumulated"
    private const val KEY_LAST_FEED_AT = "rest_last_feed_at"

    /** 預設提醒門檻（規格：45 分鐘） */
    const val DEFAULT_INTERVAL_MINUTES = 45

    /** 門檻下限（規格：20 分鐘） */
    const val MIN_INTERVAL_MINUTES = 20

    /** 門檻上限（規格：120 分鐘） */
    const val MAX_INTERVAL_MINUTES = 120

    /** 中斷寬限（規格：5 分鐘——餵入間隔超過即歸零） */
    const val BREAK_GRACE_MINUTES = 5

    /** 延後額度（規格：再讀 10 分鐘——閱讀時間基準） */
    const val SNOOZE_MINUTES = 10

    /** 夜間靜默窗起（規格：23 時） */
    const val QUIET_START_HOUR = 23

    /** 夜間靜默窗迄（規格：07 時） */
    const val QUIET_END_HOUR = 7

    private const val CHANNEL_ID = "k_reader_rest_reminder"
    private const val NOTIFICATION_ID = 10_010

    private const val MINUTE_MS = 60_000L

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 提醒結果（onReadingSession 回報——UI 端可加 Toast） */
    enum class ReminderOutcome {
        NONE,            // 未達門檻
        REMINDED,        // 已發提醒（本輪歸零）
        REMINDED_QUIET   // 已發提醒（靜默窗——未震動）
    }

    // ═════════════════ 設定 ═════════════════

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) ensureChannel()
        if (!enabled) reset()
    }

    /** 門檻（分鐘） */
    fun intervalMinutes(): Int = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)

    /** 設定門檻（20～120 夾擊——髒值常規化） */
    fun setIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        prefs.edit().putInt(KEY_INTERVAL, clamped).apply()
    }

    // ═════════════════ 計時主流程 ═════════════════

    /**
     * 閱讀時段落帳（與 ReadStatisticsManager.onReadingSession 同一餵入點）：
     * 餵入間隔 > 5 分鐘先歸零；累計觸及門檻即提醒＋歸零。
     * 未啟用時直接 NONE（零開銷）。
     */
    fun onReadingSession(minutes: Long): ReminderOutcome {
        if (!isEnabled() || minutes <= 0L) return ReminderOutcome.NONE
        return runCatching {
            val interval = intervalMinutes()
            val accumulated = accumulate(minutes)
            if (accumulated >= interval) {
                reset()
                return remind(interval)
            }
            ReminderOutcome.NONE
        }.getOrElse { e ->
            Log.w(TAG, "休息提醒計時失敗", e)
            ReminderOutcome.NONE
        }
    }

    /**
     * 延後（rest_snooze）：閱讀時間基準的「再讀 10 分鐘」——
     * 把累計撥到門檻前 10 分鐘；暫停不折損額度。
     */
    fun snooze() {
        val target = (intervalMinutes() - SNOOZE_MINUTES).coerceAtLeast(0)
        prefs.edit().putLong(KEY_ACCUMULATED, target.toLong()).apply()
        Log.i(TAG, "休息提醒已延後（再讀 $SNOOZE_MINUTES 分鐘）")
    }

    /** 立即歸零（rest_reset——使用者主動休息／關閉提醒） */
    fun reset() {
        prefs.edit()
            .putLong(KEY_ACCUMULATED, 0L)
            .apply()
    }

    /** 目前累計分鐘（UI 進度條用） */
    fun accumulatedMinutes(): Long = prefs.getLong(KEY_ACCUMULATED, 0L)

    /** 距離下次提醒剩餘分鐘（負值＝已逾期即將提醒） */
    fun remainingMinutes(): Int =
        (intervalMinutes() - accumulatedMinutes().toInt()).coerceAtLeast(0)

    // ═════════════════ 提醒發射 ═════════════════

    /** 發通知＋視情況震動；回報是否落在靜默窗 */
    private fun remind(intervalMinutes: Int): ReminderOutcome {
        ensureChannel()
        val vibrated = if (shouldVibrate()) {
            vibrate()
            true
        } else false
        val ctx = AppContext.ctx
        val body = ctx.getString(R.string.rest_notify_body, intervalMinutes)
        val posted = runCatching {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
                return@runCatching false
            }
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(ctx.getString(R.string.rest_notify_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
            true
        }.getOrElse { e ->
            Log.w(TAG, "休息通知發送失敗", e)
            false
        }
        Log.i(TAG, "休息提醒已觸發（通知=${posted}／震動=${vibrated}）")
        return if (isQuietHours()) ReminderOutcome.REMINDED_QUIET else ReminderOutcome.REMINDED
    }

    /** 震動決策：夜間靜默窗＋墨水屏模式皆不震 */
    internal fun shouldVibrate(): Boolean =
        !isQuietHours() && !EInkHelper.isEInkMode()

    /** 夜間靜默窗（23:00–07:00——跨午夜區間判定） */
    internal fun isQuietHours(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
    }

    /** 輕震動（雙短震——0.4s＋0.4s 間隔 0.3s；API 26 以下走 legacy 波形） */
    private fun vibrate() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = AppContext.ctx.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as? VibratorManager
                manager?.defaultVibrator?.vibrate(waveformEffect())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                (AppContext.ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    ?.vibrate(waveformEffect())
            } else {
                @Suppress("DEPRECATION")
                (AppContext.ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    ?.vibrate(VIBRATE_TIMINGS, -1)
            }
        }.onFailure { e ->
            Log.w(TAG, "震動失敗（部分裝置無馬達——靜默跳過）", e)
        }
    }

    /** 震動節奏（規格：0.4s＋停 0.3s＋0.4s） */
    private val VIBRATE_TIMINGS = longArrayOf(0, 400, 300, 400)

    private fun waveformEffect(): VibrationEffect =
        VibrationEffect.createWaveform(VIBRATE_TIMINGS, -1)

    /** 通知頻道（低重要性——不出聲、不亮屏抬頭） */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = AppContext.ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                AppContext.ctx.getString(R.string.rest_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = AppContext.ctx.getString(R.string.rest_channel_desc)
                enableVibration(false)   // 震動由本管理器自主決策
            }
            manager.createNotificationChannel(channel)
        }
    }

    // ═════════════════ 累計 ═════════════════

    /**
     * 累計落帳：
     * 中斷寬限檢查（上次餵入距今 > 5 分鐘即歸零）；
     * 單次餵入上限 60 分鐘（與統計管理員同一截斷口徑——掛機不注水）。
     */
    private fun accumulate(minutes: Long): Long {
        val now = System.currentTimeMillis()
        val lastFeedAt = prefs.getLong(KEY_LAST_FEED_AT, 0L)
        var accumulated = prefs.getLong(KEY_ACCUMULATED, 0L)
        if (lastFeedAt > 0L &&
            now - lastFeedAt > BREAK_GRACE_MINUTES * MINUTE_MS
        ) {
            accumulated = 0L
        }
        val capped = minutes.coerceAtMost(60L)
        accumulated += capped
        prefs.edit()
            .putLong(KEY_ACCUMULATED, accumulated)
            .putLong(KEY_LAST_FEED_AT, now)
            .apply()
        return accumulated
    }
}

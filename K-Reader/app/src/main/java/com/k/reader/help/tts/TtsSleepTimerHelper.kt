package com.k.reader.help.tts

import android.content.SharedPreferences
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 聽書睡眠定時器（Stage 9 旗艦增益：聽書睡眠定時）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（枕邊一章，眠而不驚）：
 *   聽書入睡是老派享受——但整夜朗讀到天亮，傷電傷眠。本定時器
 *   給 HTTP 聽書引擎（HttpTtsEngine）掛上溫柔的剎車：
 *
 *    1. 雙模式：MODE_MINUTES 倒數計時（15/30/45/60 預設檔）／
 *       MODE_CHAPTER 本章結束——「這一章聽完就睡」是最自然的
 *       助眠邊界；
 *    2. 漸弱：停止前最後五分鐘（FADE_WINDOW_MS）音量線性衰減
 *       1.0 → 0.0（suggestedVolume）——不是急停，是呼吸變輕；
 *       本章結束模式不衰減（一章的長度就是緩衝）；
 *    3. 狀態機：偏好檔持久化——App 被系統殺掉重啟，倒數仍按
 *       startedAt 續算（牆鐘時間，不隨重啟歸零重來）；本章
 *       結束旗標（chapterDone）由播放端的 onChapterFinished()
 *       立桿；
 *    4. 剎車查詢：shouldStopNow() 供播放迴圈每段語音前檢查；
 *       MODE_CHAPTER 命中後自動 stop()——停一次就停乾淨。
 *
 *  整合點：
 *   · 聽書面板「睡眠定時」 → startCountdown()／startChapterEnd()
 *   · HttpTtsEngine 播放迴圈 → shouldStopNow()＋suggestedVolume()
 *   · 章節播完回呼 → onChapterFinished()
 */
object TtsSleepTimerHelper {

    private const val TAG = "TtsSleepTimer"

    /** 偏好檔（與 EInkHelper／PageTurnHelper 同一家族：k_reader_config） */
    private const val PREFS_NAME = "k_reader_config"

    private const val KEY_MODE = "tts_sleep_mode"
    private const val KEY_STARTED_AT = "tts_sleep_started_at"
    private const val KEY_DURATION_MS = "tts_sleep_duration_ms"
    private const val KEY_CHAPTER_DONE = "tts_sleep_chapter_done"

    /** 模式：停用 */
    const val MODE_OFF = 0

    /** 模式：倒數計時（分鐘） */
    const val MODE_MINUTES = 1

    /** 模式：本章結束 */
    const val MODE_CHAPTER = 2

    /** 倒數預設檔（規格：15／30／45／60 分鐘） */
    val PRESET_MINUTES = listOf(15, 30, 45, 60)

    /** 漸弱窗口（規格：停止前最後 5 分鐘線性衰減） */
    const val FADE_WINDOW_MS = 5 * 60_000L

    // ═════════════════ 狀態機 ═════════════════

    /** 啟動倒數（minutes <= 0 拒啟動——零時定時是 Bug 不是功能） */
    fun startCountdown(minutes: Int): Boolean {
        if (minutes <= 0) return false
        prefs().edit()
            .putInt(KEY_MODE, MODE_MINUTES)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putLong(KEY_DURATION_MS, minutes * 60_000L)
            .putBoolean(KEY_CHAPTER_DONE, false)
            .apply()
        return true
    }

    /** 啟動本章結束模式（這一章聽完就停） */
    fun startChapterEnd() {
        prefs().edit()
            .putInt(KEY_MODE, MODE_CHAPTER)
            .putBoolean(KEY_CHAPTER_DONE, false)
            .apply()
    }

    /** 停止（清回 MODE_OFF——狀態殘留會讓重啟後默默剎車） */
    fun stop() {
        prefs().edit()
            .putInt(KEY_MODE, MODE_OFF)
            .putBoolean(KEY_CHAPTER_DONE, false)
            .apply()
    }

    /** 目前模式（OFF／MINUTES／CHAPTER） */
    fun mode(): Int = prefs().getInt(KEY_MODE, MODE_OFF)

    // ═════════════════ 倒數查詢 ═════════════════

    /**
     * 剩餘毫秒（牆鐘續算——重啟不歸零）；非倒數模式回 -1。
     * 夾擊 >= 0（過時即 0，不回負數嚇 UI）。
     */
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long {
        if (mode() != MODE_MINUTES) return -1L
        val startedAt = prefs().getLong(KEY_STARTED_AT, 0L)
        val duration = prefs().getLong(KEY_DURATION_MS, 0L)
        if (startedAt <= 0L || duration <= 0L) return -1L
        return (startedAt + duration - now).coerceAtLeast(0L)
    }

    /** 本章結束旗標（播放端立桿後為 true） */
    fun isChapterDone(): Boolean =
        mode() == MODE_CHAPTER && prefs().getBoolean(KEY_CHAPTER_DONE, false)

    /**
     * 章節播完回呼：本章結束模式立旗並回 true（播放端應停）；
     * 其他模式回 false（章節界線與倒數互不干擾）。
     */
    fun onChapterFinished(): Boolean {
        if (mode() != MODE_CHAPTER) return false
        prefs().edit().putBoolean(KEY_CHAPTER_DONE, true).apply()
        return true
    }

    /**
     * 剎車查詢（播放迴圈每段語音前檢查）：
     * 倒數歸零 → true；本章結束旗標 → true 並自動 stop()。
     */
    fun shouldStopNow(now: Long = System.currentTimeMillis()): Boolean {
        return when (mode()) {
            MODE_MINUTES -> remainingMillis(now) <= 0L
            MODE_CHAPTER -> {
                if (isChapterDone()) {
                    stop()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * 建議音量（0..1）：倒數模式最後五分鐘線性衰減 1.0 → 0.0；
     * 其餘情境滿格 1.0（衰減是倒數的溫柔，不是全體的）。
     */
    fun suggestedVolume(now: Long = System.currentTimeMillis()): Float {
        if (mode() != MODE_MINUTES) return 1.0f
        val remaining = remainingMillis(now)
        if (remaining > FADE_WINDOW_MS) return 1.0f
        return (remaining.toFloat() / FADE_WINDOW_MS).coerceIn(0f, 1f)
    }

    // ═════════════════ 文案 ═════════════════

    fun title(): String = AppContext.ctx.getString(R.string.tts_sleep_title)

    fun offLabel(): String = AppContext.ctx.getString(R.string.tts_sleep_off)

    fun minutesLabel(): String = AppContext.ctx.getString(R.string.tts_sleep_minutes)

    fun chapterLabel(): String = AppContext.ctx.getString(R.string.tts_sleep_chapter)

    fun minutesPresetLabel(minutes: Int): String =
        AppContext.ctx.getString(R.string.tts_sleep_minutes_fmt, minutes)

    fun remainingLabel(remainingMillis: Long): String =
        AppContext.ctx.getString(
            R.string.tts_sleep_remaining_fmt,
            formatClock(remainingMillis)
        )

    fun chapterStartedLabel(): String =
        AppContext.ctx.getString(R.string.tts_sleep_chapter_started)

    fun stoppedLabel(): String = AppContext.ctx.getString(R.string.tts_sleep_stopped)

    fun fadeNote(): String = AppContext.ctx.getString(R.string.tts_sleep_fade_note)

    /** 毫秒 → 分:秒（例：4:59——漸弱期倒數顯示用） */
    private fun formatClock(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        return "${totalSeconds / 60}:${String.format("%02d", totalSeconds % 60)}"
    }

    // ═════════════════ 基礎設施 ═════════════════

    private fun prefs(): SharedPreferences =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, 0)
}

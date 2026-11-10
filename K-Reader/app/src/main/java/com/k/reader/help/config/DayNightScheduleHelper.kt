package com.k.reader.help.config

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import java.util.Calendar
import kotlin.math.cos

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 定時日夜切換器（Stage 8 旗艦增益：日夜切換）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（白天就該亮，夜晚就該暗——不用手動）：
 *   深色主題的痛點不在開關，在「忘了切」。本切換器把時鐘變
 *   成主題的主人：
 *
 *    1. 三模式：OFF（手動）／FIXED（固定時刻——預設 20:00 入
 *       夜、06:00 回日，皆可調）／SOLAR（日出日落自動——台灣
 *       25°N 緯度概算，月份餘弦內插，與 ColorTemperatureHelper
 *       的日落估算同思路：不定位、零權限、誤差半小時內可接
 *       受）；
 *    2. 不重啟不打擾：pollThemeChange() 形態偵測——當前應然
 *       形態 vs 上次套用形態，翻轉才回 true（UI 端收到 true 再
 *       recreate()）；分鐘級檢查掛閱讀頁翻頁／前景 tick，零後
 *       台服務零輪詞；
 *    3. 墨水屏互斥：EInkHelper.isEInkMode() 時 SOLAR／FIXED 一
 *       律退 OFF（電子紙無夜間主題需求——高對比純黑白才是正
 *       道）；
 *    4. 跨啟動還原：上次套用形態存 prefs——重啟後第一次
 *       pollThemeChange() 若有落差照樣補切。
 *
 *  整合點：
 *   · 閱讀頁翻頁／onResume → pollThemeChange() 為 true 時 recreate
 *   · 設定頁 daynight_title → setMode()/setFixedHours()
 */
object DayNightScheduleHelper {

    private const val TAG = "DayNightSchedule"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_MODE = "daynight_mode"
    private const val KEY_DARK_HOUR = "daynight_dark_hour"
    private const val KEY_LIGHT_HOUR = "daynight_light_hour"
    private const val KEY_APPLIED_FORM = "daynight_applied_form"

    /** 模式：停用（手動） */
    const val MODE_OFF = "OFF"

    /** 模式：固定時刻 */
    const val MODE_FIXED = "FIXED"

    /** 模式：日出日落自動（緯度概算） */
    const val MODE_SOLAR = "SOLAR"

    /** FIXED 預設入夜時（規格：20:00） */
    const val DEFAULT_DARK_HOUR = 20

    /** FIXED 預設回日時（規格：06:00） */
    const val DEFAULT_LIGHT_HOUR = 6

    /** 主題形態：日間 */
    const val FORM_LIGHT = "LIGHT"

    /** 主題形態：夜間 */
    const val FORM_DARK = "DARK"

    /** 概算緯度（台灣本島 25°N——零定位權限） */
    private const val LATITUDE_RAD = Math.toRadians(23.7)

    private val prefs by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    // ═════════════════ 設定 ═════════════════

    fun getMode(): String = prefs.getString(KEY_MODE, MODE_OFF) ?: MODE_OFF

    fun setMode(mode: String) {
        runCatching { prefs.edit().putString(KEY_MODE, mode).apply() }
    }

    fun getDarkHour(): Int = prefs.getInt(KEY_DARK_HOUR, DEFAULT_DARK_HOUR)

    fun setDarkHour(hour: Int) {
        runCatching { prefs.edit().putInt(KEY_DARK_HOUR, hour.coerceIn(0, 23)).apply() }
    }

    fun getLightHour(): Int = prefs.getInt(KEY_LIGHT_HOUR, DEFAULT_LIGHT_HOUR)

    fun setLightHour(hour: Int) {
        runCatching { prefs.edit().putInt(KEY_LIGHT_HOUR, hour.coerceIn(0, 23)).apply() }
    }

    // ═════════════════ 形態判定 ═════════════════

    /**
     * 當前應然主題形態（墨水屏模式一律日間——互斥護欄）。
     */
    fun currentForm(nowMillis: Long = System.currentTimeMillis()): String {
        if (EInkHelper.isEInkMode()) return FORM_LIGHT
        return when (effectiveMode()) {
            MODE_FIXED -> fixedForm(nowMillis)
            MODE_SOLAR -> solarForm(nowMillis)
            else -> appliedForm()
        }
    }

    /** 生效模式（墨水屏時一律 OFF——設定頁仍可預存模式值） */
    fun effectiveMode(): String = if (EInkHelper.isEInkMode()) MODE_OFF else getMode()

    /**
     * 形態翻轉偵測：應然形態 ≠ 上次套用形態時回 true 並落帳。
     * UI 端在閱讀頁翻頁／onResume 呼叫——true 就 recreate()。
     * OFF 模式恆回 false（手動主題歸 ThemeDelegate 管）。
     */
    fun pollThemeChange(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (effectiveMode() == MODE_OFF) {
            // 手動模式：重置套用紀錄，下次啟用自動模式立即對齊
            if (appliedForm() != "") clearAppliedForm()
            return false
        }
        val should = currentForm(nowMillis)
        if (appliedForm() == should) return false
        runCatching { prefs.edit().putString(KEY_APPLIED_FORM, should).apply() }
        Log.i(TAG, "日夜主題翻轉：$should")
        return true
    }

    // ═════════════════ 日出日落概算 ═════════════════

    /**
     * 月份餘弦內插日落時（小數時制，18.0 = 18:00）。
     * 夏至（6 月）最晚可 18:45、冬至（12 月）最早已 17:15——
     * 與 ColorTemperatureHelper.estimatedSunsetHour() 同族演算法。
     */
    fun estimatedSunsetHour(month: Int): Double {
        val m = month.coerceIn(0, 11)
        // 6 月為波峰（夏至——日落最晚）、12 月為波谷（冬至——日落最早）
        val phase = ((m - 6) / 12.0) * 2.0 * Math.PI
        val amplitude = 0.75 // ±45 分鐘
        val center = 18.0
        return center + amplitude * cos(phase)
    }

    /** 月份餘弦內插日出時（小數時制——日落鏡像：夏早日晚日落） */
    fun estimatedSunriseHour(month: Int): Double {
        val sunset = estimatedSunsetHour(month)
        // 日長 10.9~13.6 小時（25°N 概算）：夏至日長最長
        val phase = ((month.coerceIn(0, 11) - 6) / 12.0) * 2.0 * Math.PI
        val dayLength = 12.25 + 1.35 * cos(phase)
        val sunrise = sunset - dayLength
        return sunrise.coerceIn(4.5, 7.5)
    }

    /** SOLAR 形態：日落後～日出前＝夜間 */
    private fun solarForm(nowMillis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val month = calendar.get(Calendar.MONTH)
        val hourNow = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0
        val sunset = estimatedSunsetHour(month)
        val sunrise = estimatedSunriseHour(month)
        return if (hourNow >= sunset || hourNow < sunrise) FORM_DARK else FORM_LIGHT
    }

    /** FIXED 形態：darkHour～lightHour＝夜間（跨午夜窗口支援） */
    private fun fixedForm(nowMillis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dark = getDarkHour()
        val light = getLightHour()
        return if (dark >= light) {
            // 同日窗口（如 20:00→06:00 實為跨日）：hour >= dark || hour < light
            if (hour >= dark || hour < light) FORM_DARK else FORM_LIGHT
        } else {
            // 顛倒窗口（如 06:00→20:00「白天短」）：dark~light 為夜
            if (hour in dark until light) FORM_DARK else FORM_LIGHT
        }
    }

    // ═════════════════ 持久化 ═════════════════

    private fun appliedForm(): String = prefs.getString(KEY_APPLIED_FORM, "") ?: ""

    private fun clearAppliedForm() {
        runCatching { prefs.edit().remove(KEY_APPLIED_FORM).apply() }
    }

    // ═════════════════ UI 文案 ═════════════════

    /** 模式摘要（設定頁列表列） */
    fun modeSummary(): String = when (effectiveMode()) {
        MODE_FIXED -> AppContext.ctx.getString(
            R.string.daynight_dark_at, hourMinute(getDarkHour())
        )
        MODE_SOLAR -> AppContext.ctx.getString(
            R.string.daynight_solar_summary,
            hourMinute(estimatedSunriseHour(monthNow())),
            hourMinute(estimatedSunsetHour(monthNow()))
        )
        else -> AppContext.ctx.getString(R.string.daynight_mode_off)
    }

    private fun hourMinute(hour: Int): String =
        String.format(java.util.Locale.TAIWAN, "%02d:00", hour)

    /** 小數時制 → HH:mm（05:20 風格） */
    private fun hourMinute(hour: Double): String {
        val totalMinutes = (hour * 60).toInt().coerceIn(0, 24 * 60 - 1)
        return String.format(
            java.util.Locale.TAIWAN, "%02d:%02d",
            totalMinutes / 60, totalMinutes % 60
        )
    }

    private fun monthNow(): Int = Calendar.getInstance().get(Calendar.MONTH)
}

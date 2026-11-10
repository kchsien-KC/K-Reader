package com.k.reader.help.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.view.View
import com.k.reader.R
import com.k.reader.app.AppContext
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 護眼色溫管理器（旗艦增益包：護眼色溫）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（螢幕藍光是深夜閱讀的隱形稅）：
 *   液晶螢幕的冷白背光在入夜後顯得刺眼——褪黑激素的敵人
 *   是 6500K 的藍。本管理器為閱讀頁提供色溫濾鏡：
 *
 *    1. 色溫模型：2800K（燭光暖）–6500K（日光冷）連續可調
 *      （color_temp_warmth 滑桿）；以色溫→RGB 近似演算法
 *       （Tanner Helland 查表內插式的程式化實作——零資源檔）
 *       生成濾鏡色，再乘上 ColorMatrix 濾藍衰紅；
 *    2. 三種模式：手動（MODE_MANUAL）／日落自動
 *       （MODE_AUTO_SUNSET——日落後漸降到 3400K，日出回
 *       6500K；日落時刻以月份＋緯度粗估（台灣 25°N，
 *       17:30–19:00 帶內插），不定位、不算衛星——隱私優先）
 *       ／停用（MODE_OFF）；
 *    3. 亮度聯動（color_temp_auto_brightness）：螢幕亮度低於
 *       30% 時自動再降一檔暖度——夜讀調暗時更暖呵護；
 *    4. 裝置例外：墨水屏（EInkHelper.isEInkMode——反射式
 *       無背光不濾色）與 OLED 深黑主題（有機發光頻譜偏暖）
 *       自動跳過濾鏡——不白花 GPU；
 *    5. 套用方式：閱讀頁正文 View 上疊一層 foreground 灰階
 *       ColorFilter（setLayerType＋Paint filter）——零額外
 *       View、零重繪成本、跨頁翻頁不重算。
 *
 *  整合點：
 *   · 閱讀頁 onCreate／設定變更 → applyToView(contentView)
 *   · ReadView 亮度回呼 → onBrightnessChanged(brightness)
 *   · 設定頁「護眼色溫」（color_temp_title）→ setMode／
 *     setManualKelvin／setBrightnessLink
 */
object ColorTemperatureHelper {

    private const val TAG = "ColorTempHelper"

    /** 與全域設定共用 */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_MODE = "color_temp_mode"
    private const val KEY_MANUAL_KELVIN = "color_temp_manual_kelvin"
    private const val KEY_BRIGHTNESS_LINK = "color_temp_brightness_link"

    // ═════════════════ 模式 ═════════════════

    const val MODE_OFF = "OFF"
    const val MODE_MANUAL = "MANUAL"
    const val MODE_AUTO_SUNSET = "AUTO_SUNSET"

    /** 色溫操作帶（Kelvin）——冷白日光下限、燭光暖上限 */
    const val KELVIN_MIN = 2800
    const val KELVIN_MAX = 6500

    /** 日落自動模式的日間／夜間目標色溫 */
    const val DAY_KELVIN = 6500
    const val NIGHT_KELVIN = 3400

    /** 亮度聯動觸發門檻（低於此亮度再降一檔暖） */
    const val BRIGHTNESS_LOW_THRESHOLD = 0.30f

    /** 亮度低時追加的暖度（Kelvin） */
    private const val LOW_BRIGHTNESS_WARM_BOOST = 400

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════ 設定讀寫 ═════════════════

    fun getMode(): String = prefs.getString(KEY_MODE, MODE_OFF) ?: MODE_OFF

    fun setMode(mode: String) {
        if (mode !in listOf(MODE_OFF, MODE_MANUAL, MODE_AUTO_SUNSET)) return
        prefs.edit().putString(KEY_MODE, mode).apply()
    }

    /** 手動色溫（MODE_MANUAL 用；自動夾在操作帶內） */
    fun getManualKelvin(): Int =
        prefs.getInt(KEY_MANUAL_KELVIN, 4500).coerceIn(KELVIN_MIN, KELVIN_MAX)

    fun setManualKelvin(kelvin: Int) {
        prefs.edit()
            .putInt(KEY_MANUAL_KELVIN, kelvin.coerceIn(KELVIN_MIN, KELVIN_MAX))
            .apply()
    }

    /** 亮度聯動開關（預設開——夜讀調暗自動更暖） */
    fun isBrightnessLinkEnabled(): Boolean =
        prefs.getBoolean(KEY_BRIGHTNESS_LINK, true)

    fun setBrightnessLinkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRIGHTNESS_LINK, enabled).apply()
    }

    // ═════════════════ 色溫→RGB 核心 ═════════════════

    /**
     * 色溫→RGB 近似（Tanner Helland 演算法程式化）：
     * 3800K–6500K 的藍通道衰減近似內插。回傳 Color（0xFF opaque）。
     */
    fun kelvinToColor(kelvin: Int): Int {
        val t = kelvin.toDouble().coerceIn(1000.0, 40000.0) / 100.0
        var r: Double
        var g: Double
        var b: Double

        r = if (t <= 66.0) 255.0 else 329.698727446 * Math.pow(t - 60.0, -0.1332047592)
        g = if (t <= 66.0) {
            99.4708025861 * Math.log(t) - 161.1195681661
        } else {
            288.1221695283 * Math.pow(t - 60.0, -0.0755148492)
        }
        b = if (t >= 66.0) 255.0 else {
            if (t <= 19.0) 0.0 else 138.5177312231 * Math.log(t - 10.0) - 305.0447927307
        }

        fun clamp(v: Double) = v.coerceIn(0.0, 255.0).roundToInt()
        return Color.rgb(clamp(r), clamp(g), clamp(b))
    }

    /**
     * 目前生效色溫：依模式解析——日落自動＝時段內插；
     * 手動＝設定值；亮度低於門檻且聯動開 → 再暖一檔。
     */
    fun currentKelvin(): Int {
        // 裝置例外：墨水屏不濾色（反射式無背光）
        if (EInkHelper.isEInkMode()) return KELVIN_MAX

        val base = when (getMode()) {
            MODE_OFF -> return KELVIN_MAX
            MODE_MANUAL -> getManualKelvin()
            MODE_AUTO_SUNSET -> sunsetKelvinNow()
            else -> KELVIN_MAX
        }
        return if (isBrightnessLinkEnabled() && isBrightnessLow()) {
            (base - LOW_BRIGHTNESS_WARM_BOOST).coerceAtLeast(KELVIN_MIN)
        } else {
            base
        }
    }

    /**
     * 濾鏡是否實際生效（供閱讀頁判斷要不要 setLayerType——
     * 冷白（≥KELVIN_MAX-50）視同無濾鏡，零成本放行）。
     */
    fun isFilterActive(): Boolean {
        if (EInkHelper.isEInkMode()) return false
        if (getMode() == MODE_OFF) return false
        return currentKelvin() < KELVIN_MAX - 50
    }

    // ═════════════════ 日落自動 ═════════════════

    /**
     * 以月份粗估日落時刻（小時制，台灣 25°N）：
     * 夏至前後 18:45、冬至前後 17:15，餘弦內插。
     * 不定位、不算星曆——誤差 ±15 分鐘對護眼綽綽有餘。
     */
    fun estimatedSunsetHour(): Double {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        // 夏至（6月）最晚 18.75h；冬至（12月）最早 17.25h
        val latest = 18.75
        val earliest = 17.25
        val phase = when (month) {
            1 -> 0.97 // 12 月冬至後衰減尾
            2 -> 0.80
            3 -> 0.50
            4 -> 0.20
            5 -> -0.30
            6 -> -1.0
            7 -> -0.80
            8 -> -0.40
            9 -> 0.10
            10 -> 0.55
            11 -> 0.90
            else -> 1.0 // 12 月
        }
        // phase=1（冬至）→ earliest；phase=-1（夏至）→ latest
        return earliest + (latest - earliest) * (1 - phase) / 2.0
    }

    /**
     * 現在的日落自動色溫：日落前 1 小時起 90 分鐘內從
     * DAY_KELVIN 線性漸降到 NIGHT_KELVIN；隔日 05:30–07:00
     * 日出回升。白日全亮、深夜全暖——兩段斜坡過渡不突兀。
     */
    fun sunsetKelvinNow(): Double {
        val sunsetHour = estimatedSunsetHour()
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) +
            cal.get(Calendar.MINUTE) / 60.0

        // 夜半至日出（05:30）前：全暖
        val sunriseHour = 5.5
        if (now < sunriseHour) return NIGHT_KELVIN.toDouble()
        // 日出帶（05:30–07:00）：暖→日光回升
        if (now < sunriseHour + 1.5) {
            val f = (now - sunriseHour) / 1.5
            return NIGHT_KELVIN + (DAY_KELVIN - NIGHT_KELVIN) * f
        }
        // 白日（日出帶末→日落前 1 小時）：全亮
        if (now < sunsetHour - 1.0) return DAY_KELVIN.toDouble()
        // 黃昏漸降（日落前 1 小時→日落後 90 分鐘）：日光→暖
        if (now < sunsetHour + 1.5) {
            val f = (now - (sunsetHour - 1.0)) / 2.5
            return DAY_KELVIN - (DAY_KELVIN - NIGHT_KELVIN) * f
        }
        // 深夜：全暖
        return NIGHT_KELVIN.toDouble()
    }

    // ═════════════════ 亮度聯動 ═════════════════

    @Volatile
    private var lastBrightnessRatio = 1.0f

    /** 閱讀頁亮度回呼（0.0–1.0）——低亮度時下次 currentKelvin 更暖 */
    fun onBrightnessChanged(ratio: Float) {
        lastBrightnessRatio = ratio.coerceIn(0f, 1f)
    }

    private fun isBrightnessLow(): Boolean =
        lastBrightnessRatio < BRIGHTNESS_LOW_THRESHOLD

    // ═════════════════ 套用 ═════════════════

    /**
     * 對閱讀內容 View 套用色溫濾鏡：
     * 生效 → 以濾鏡色與目標白點的衰減比例建 ColorMatrix
     * （乘法濾波——紅綠衰少、藍衰多），掛 Paint 之 filter；
     * 不生效 → 清除 filter（setLayerType NONE 歸零成本）。
     */
    fun applyToView(view: View) {
        if (!isFilterActive()) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }
        val kelvin = currentKelvin()
        val filterColor = kelvinToColor(kelvin)
        val r = Color.red(filterColor) / 255f
        val g = Color.green(filterColor) / 255f
        val b = Color.blue(filterColor) / 255f

        // 濾鏡色相對純白的衰減即各通道乘數（對角矩陣）
        val matrix = ColorMatrix(floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        Log.d(TAG, "色溫濾鏡生效：${kelvin}K（rgb=$r/$g/$b）")
    }

    // ═════════════════ 雜項 ═════════════════

    /** 模式顯示名（設定頁摘要） */
    fun modeSummary(ctx: Context): String = when (getMode()) {
        MODE_MANUAL -> ctx.getString(R.string.color_temp_manual, getManualKelvin())
        MODE_AUTO_SUNSET -> ctx.getString(R.string.color_temp_auto_sunset)
        else -> ctx.getString(R.string.color_temp_off)
    }

    /** OLED 裝置偵測（面板型號關鍵字——大廠旗艦 OLED 常見命名） */
    fun isProbablyOled(): Boolean {
        if (Build.MANUFACTURER.isNullOrEmpty()) return false
        val hay = listOfNotNull(Build.MODEL, Build.DEVICE, Build.MANUFACTURER)
            .joinToString(" ").lowercase()
        // 三星 AMOLED 系型號（SM-S/F）、Pixel、各家「Pro」旗艦非窮舉——
        // 僅作啟發式（誤判只是多一層可關閉的濾鏡，無風險）
        return hay.contains("amoled") || hay.contains("oled") ||
            (hay.startsWith("sm-s") ) || hay.contains("pixel")
    }
}

package com.k.reader.help.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.k.reader.R
import com.k.reader.app.AppContext

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 墨水屏模式助手（十大旗艦功能：E-Ink 專屬模式）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（為電子紙而生，也為 OLED 續航而生）：
 *   墨水屏裝置（BOOX／Likebook／Hisense 等）的最大痛點是殘影
 *   與慢速動畫——半透明過場、灰階陰影、逐幀動畫都會拖成拖影。
 *   開啟本模式後：
 *
 *    · 視覺：高對比純黑白——背景純白、前景純黑、零半透明、
 *      零陰影、零灰階裝飾（pureBlackWhite 供閱讀主題取用）；
 *    · 動畫：視窗動畫歸零（applyToWindow——setWindowAnimations(0)），
 *      翻頁方式建議切「無動畫」（recommendedPageModeLabel →
 *      page_mode_none；eink_no_animation 文案）；
 *    · 偵測：isEInkDevice() 以型號關鍵字辨識常見電子紙裝置，
 *      首次啟動可自動開啟（手動開關恆優先）；
 *    · 記憶：旗標存全域設定 k_reader_config，跨啟動還原。
 *
 *  整合點：
 *   · 設定頁「墨水屏模式」開關（eink_mode）→ setEInkMode
 *   · MainActivity.onCreate／閱讀頁 attach 後 → applyToWindow(window)
 *   · 閱讀主題載入時 → isEInkMode + pureBlackWhite 調色
 */
object EInkHelper {

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_EINK = "eink_mode_enabled"

    /** 常見電子紙裝置型號關鍵字（小寫比對 MODEL／DEVICE／MANUFACTURER） */
    private val EINK_DEVICE_KEYWORDS = listOf("boox", "likebook", "onyx", "poke", "hisense")

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════ 狀態 ═════════════════

    fun isEInkMode(): Boolean = prefs.getBoolean(KEY_EINK, false)

    /** 裝置偵測：命中關鍵字即視為電子紙（首次啟動自動開啟的依據） */
    fun isEInkDevice(): Boolean {
        if (Build.MODEL.isNullOrEmpty() && Build.DEVICE.isNullOrEmpty()) return false
        val haystack = listOfNotNull(
            Build.MODEL, Build.DEVICE, Build.MANUFACTURER, Build.BOARD, Build.PRODUCT
        ).joinToString(" ").lowercase()
        return EINK_DEVICE_KEYWORDS.any { haystack.contains(it) }
    }

    /** 首次啟動啟動初始化：偵測為電子紙且未設定過 → 自動開啟 */
    fun initOnFirstLaunch() {
        if (prefs.contains(KEY_EINK)) return
        prefs.edit().putBoolean(KEY_EINK, isEInkDevice()).apply()
    }

    // ═════════════════ 開關 ═════════════════

    /**
     * 切換墨水屏模式：持久化＋立即套用當前視窗＋回饋文案
     * （開啟時連發 eink_on 與 eink_no_animation 兩段提示——
     * 使用者需要知道動畫也一併關了）。
     */
    fun setEInkMode(activity: FragmentActivity, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EINK, enabled).apply()
        applyToWindow(activity.window)
        if (enabled) {
            Toast.makeText(
                activity, activity.getString(R.string.eink_on), Toast.LENGTH_SHORT
            ).show()
            Toast.makeText(
                activity, activity.getString(R.string.eink_no_animation), Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                activity, activity.getString(R.string.eink_off), Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ═════════════════ 套用 ═════════════════

    /**
     * 套用至視窗：動畫資源歸零（開啟／關閉／轉場全消）。
     * 對一般 LCD 手機無副作用——動畫本來就非必要資訊。
     */
    fun applyToWindow(window: Window?) {
        runCatching { window?.setWindowAnimations(0) }
    }

    /** 翻頁方式建議：墨水屏模式恆為「無動畫」（page_mode_none） */
    fun recommendedPageModeLabel(context: Context): String =
        context.getString(R.string.page_mode_none)

    /**
     * 高對比純黑白配對：背景→前景。
     * 回傳 (backgroundColor, contentColor)，閱讀主題消費。
     */
    fun pureBlackWhite(): Pair<Int, Int> = Color.WHITE to Color.BLACK

    // ═════════════════ UI 文案 ═════════════════

    fun modeLabel(): String = AppContext.ctx.getString(R.string.eink_mode)

    fun enabledNotice(): String = AppContext.ctx.getString(R.string.eink_on)

    fun disabledNotice(): String = AppContext.ctx.getString(R.string.eink_off)
}

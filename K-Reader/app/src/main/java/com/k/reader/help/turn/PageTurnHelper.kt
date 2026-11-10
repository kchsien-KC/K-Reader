package com.k.reader.help.turn

import android.content.SharedPreferences
import android.view.KeyEvent
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.config.EInkHelper

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 翻頁手勢管理器（Stage 9 旗艦增益：翻頁手勢）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（手指的最短路徑）：
 *   單手握持、拇指可及——翻頁是閱讀器被觸發最頻繁的動作，每
 *   一毫米的誤觸都會被放大千萬次。本管理器把點擊區域、翻頁
 *   動畫、音量鍵三件事一次管好：
 *
 *    1. 三區點擊：左右各 30%（EDGE_FRACTION）翻頁、中間
 *       40% 呼出選單——拇指熱區最大化、誤觸選單最小化；
 *       RTL 版面（阿文／希伯來文書源）自動鏡像（isRtl）；
 *    2. 三式動畫：覆蓋（紙頁疊上前）、滑動（平移推進）、
 *       無動畫（瞬切）——預設覆蓋；墨水屏一律強制無動畫
 *       （effectiveAnimation()——與日夜切換「墨水屏互斥」
 *       同一紀律：E-Ink 的殘影是物理，不是偏好）；
 *    3. 音量鍵翻頁：上鍵上一頁、下鍵下一頁，可反轉、可關——
 *       volumeKeyZone() 把 KeyEvent 映射為翻頁區域，閱讀頁
 *       dispatchKeyEvent 一行接入；非音量鍵回 null 不攔截
 *       （不跟系統音量搶不必要的按鍵）。
 *
 *  整合點：
 *   · 閱讀頁 onTouch → hitZone(x, width) → prev/next/menu
 *   · 閱讀頁 dispatchKeyEvent → volumeKeyZone(keyCode)
 *   · 設定「翻頁與手勢」 → setAnimation()／setVolumeKeyPaging()
 *   · 渲染層動畫分支 → effectiveAnimation()
 */
object PageTurnHelper {

    /** 偏好檔（與 EInkHelper／睡眠定時同一家族：k_reader_config） */
    private const val PREFS_NAME = "k_reader_config"

    private const val KEY_ANIM = "page_turn_anim"
    private const val KEY_VOLUME_PAGING = "volume_key_paging"
    private const val KEY_VOLUME_INVERTED = "volume_key_inverted"

    /** 動畫：覆蓋翻頁（紙頁疊上前——預設） */
    const val ANIM_COVER = "COVER"

    /** 動畫：滑動翻頁（平移推進） */
    const val ANIM_SLIDE = "SLIDE"

    /** 動畫：無動畫（瞬切） */
    const val ANIM_NONE = "NONE"

    /** 點擊區域：上一頁（左緣；RTL 鏡像後為右緣） */
    const val ZONE_PREV = "PREV"

    /** 點擊區域：下一頁（右緣；RTL 鏡像後為左緣） */
    const val ZONE_NEXT = "NEXT"

    /** 點擊區域：中央（呼出閱讀選單） */
    const val ZONE_CENTER = "CENTER"

    /** 左右翻頁熱區寬度占比（規格：各 30%——中間 40% 給選單） */
    const val EDGE_FRACTION = 0.30f

    // ═════════════════ 點擊區域 ═════════════════

    /**
     * 點擊區域判定（x 相對座標 / 版面寬）：
     * 左 30% → PREV、右 30% → NEXT、中間 40% → CENTER；
     * RTL 版面鏡像（左 30% → NEXT）。寬度異常（<= 0）一律
     * CENTER——版面還沒量好時不翻頁。
     */
    fun hitZone(x: Float, width: Float, isRtl: Boolean = false): String {
        if (width <= 0f) return ZONE_CENTER
        val fraction = (x / width).coerceIn(0f, 1f)
        val atLeftEdge = fraction < EDGE_FRACTION
        val atRightEdge = fraction > 1f - EDGE_FRACTION
        return when {
            atLeftEdge -> if (isRtl) ZONE_NEXT else ZONE_PREV
            atRightEdge -> if (isRtl) ZONE_PREV else ZONE_NEXT
            else -> ZONE_CENTER
        }
    }

    // ═════════════════ 動畫 ═════════════════

    /** 目前設定的動畫（未設定回預設覆蓋；髒值常規化） */
    fun animation(): String = normalizeAnim(prefs().getString(KEY_ANIM, null))

    /** 設定動畫（髒值常規化——偏好檔不收垃圾） */
    fun setAnimation(mode: String) {
        prefs().edit().putString(KEY_ANIM, normalizeAnim(mode)).apply()
    }

    /**
     * 生效動畫：墨水屏強制無動畫（E-Ink 殘影是物理不是偏好——
     * 與日夜切換的墨水屏互斥同一紀律）；其餘照設定。
     */
    fun effectiveAnimation(): String =
        if (EInkHelper.isEInkMode()) ANIM_NONE else animation()

    // ═════════════════ 音量鍵 ═════════════════

    /** 音量鍵翻頁是否啟用（預設關——不跟系統音量搶按鍵） */
    fun isVolumeKeyPaging(): Boolean = prefs().getBoolean(KEY_VOLUME_PAGING, false)

    fun setVolumeKeyPaging(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_VOLUME_PAGING, enabled).apply()
    }

    /** 音量鍵方向反轉（左撇子／躺側翻身的自由） */
    fun isVolumeKeyInverted(): Boolean = prefs().getBoolean(KEY_VOLUME_INVERTED, false)

    fun setVolumeKeyInverted(inverted: Boolean) {
        prefs().edit().putBoolean(KEY_VOLUME_INVERTED, inverted).apply()
    }

    /**
     * 音量鍵 → 翻頁區域映射（閱讀頁 dispatchKeyEvent 一行接入）：
     * 上鍵 PREV、下鍵 NEXT（反轉時鏡像）；非音量鍵或功能關閉回
     * null——不攔截、不吞鍵。
     */
    fun volumeKeyZone(keyCode: Int): String? {
        if (!isVolumeKeyPaging()) return null
        val inverted = isVolumeKeyInverted()
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (inverted) ZONE_NEXT else ZONE_PREV
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (inverted) ZONE_PREV else ZONE_NEXT
            else -> null
        }
    }

    // ═════════════════ 文案 ═════════════════

    fun title(): String = AppContext.ctx.getString(R.string.turn_title)

    fun coverLabel(): String = AppContext.ctx.getString(R.string.turn_anim_cover)

    fun slideLabel(): String = AppContext.ctx.getString(R.string.turn_anim_slide)

    fun noneLabel(): String = AppContext.ctx.getString(R.string.turn_anim_none)

    fun einkFixedNotice(): String = AppContext.ctx.getString(R.string.turn_anim_eink_fixed)

    fun zoneHint(): String = AppContext.ctx.getString(R.string.turn_zone_hint)

    fun volumeKeyLabel(): String = AppContext.ctx.getString(R.string.turn_volume_key)

    fun volumeKeyHint(): String = AppContext.ctx.getString(R.string.turn_volume_key_hint)

    fun volumeInvertLabel(): String = AppContext.ctx.getString(R.string.turn_volume_invert)

    fun zoneLabel(zone: String): String = when (zone) {
        ZONE_PREV -> AppContext.ctx.getString(R.string.turn_zone_prev)
        ZONE_NEXT -> AppContext.ctx.getString(R.string.turn_zone_next)
        else -> AppContext.ctx.getString(R.string.turn_zone_center)
    }

    fun rtlNote(): String = AppContext.ctx.getString(R.string.turn_rtl_note)

    fun animChangedNotice(mode: String): String =
        AppContext.ctx.getString(R.string.turn_anim_changed_fmt, animationLabel(mode))

    /** 動畫名稱（設定面板顯示用） */
    fun animationLabel(mode: String): String = when (normalizeAnim(mode)) {
        ANIM_SLIDE -> slideLabel()
        ANIM_NONE -> noneLabel()
        else -> coverLabel()
    }

    // ═════════════════ 工具 ═════════════════

    /** 動畫常規化（未知值退覆蓋——髒資料不外溢） */
    private fun normalizeAnim(mode: String?): String = when (mode) {
        ANIM_SLIDE, ANIM_NONE -> mode
        else -> ANIM_COVER
    }

    private fun prefs(): SharedPreferences =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, 0)
}

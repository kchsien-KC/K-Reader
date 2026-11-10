package com.k.reader.help.profile

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.content.TextLayoutHelper
import com.k.reader.help.turn.PageTurnHelper
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 每書閱讀方案管理器（Stage 12：閱讀深耕與個人化）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（沒有兩本書該用同一套排版）：
 *   全域閱讀設定是「公約數」——小說要寬行距大字、技術書要
 *   密排小字、深夜讀恐怖小說想換黑底。Stage 1-11 的排版／
 *   字體／翻頁／主題全走全域，本管理器補上「每書覆寫」層：
 *
 *    1. 三層解析：書籍覆寫 → 全域預設 → 出廠值（逐欄位，
 *       覆寫沒有的欄位落回全域——半套覆寫也成立）；
 *    2. 五欄位：文字大小（sp）、行距倍數、頁邊距（dp）、
 *       背景主題（白／米／綠／黑）、翻頁動畫（COVER／
 *       SLIDE／NONE——常數取自 PageTurnHelper 同源）；
 *    3. 一鍵擴散：promoteToGlobal() 把某書覆寫升為全域
 *       預設（調好一本，全書架跟進）；clear() 還原跟隨全域。
 *       覆寫帳冊 filesDir/reading_profiles.json（書名為鍵，
 *       換源不改鍵）。
 *
 *  規格值：textSize 12..32sp（出廠 18）；lineSpacing 1.0..2.4
 *  （出廠 TextLayoutHelper.recommendLineSpacing()＝1.6）；邊距
 *  4..40dp（出廠 16）；主題四色；動畫三式；方案上限 300 書
 *  （防 JSON 膨脹）。
 *
 *  整合點：
 *   · 閱讀頁排版取值 → resolve(bookName)（三層解析）
 *   · 閱讀頁「本書方案」面板 → override()／clear()
 *   · 排版偏好頁 → promoteToGlobal()
 *   · 備份層 → profileFile()（只拷檔不解析）
 */
object ReadingProfileManager {

    private const val TAG = "ReadingProfileManager"

    /** 覆寫帳冊檔名（filesDir 下，書名→欄位集） */
    const val BOOK_FILE = "reading_profiles.json"

    /** 方案上限（防 JSON 膨脹） */
    const val MAX_PROFILES = 300

    /** 欄位值域 */
    const val TEXT_MIN_SP = 12
    const val TEXT_MAX_SP = 32
    const val DEFAULT_TEXT_SP = 18

    const val LINE_MIN = 1.0f
    const val LINE_MAX = 2.4f

    const val MARGIN_MIN_DP = 4
    const val MARGIN_MAX_DP = 40
    const val DEFAULT_MARGIN_DP = 16

    /** 背景主題四色（與閱讀頁主題常數同名同值） */
    const val THEME_WHITE = "WHITE"
    const val THEME_CREAM = "CREAM"
    const val THEME_GREEN = "GREEN"
    const val THEME_BLACK = "BLACK"
    val THEMES = listOf(THEME_WHITE, THEME_CREAM, THEME_GREEN, THEME_BLACK)

    /**
     * 每書覆寫（null＝未覆寫，落回全域；避免全欄位硬抄——
     * 全域改了，沒覆寫的欄位自動跟進）
     */
    data class BookProfile(
        val textSize: Int?,
        val lineSpacing: Float?,
        val marginDp: Int?,
        val theme: String?,
        val turnAnim: String?,
        val updatedAt: Long,
    ) {
        val isEmpty: Boolean
            get() = textSize == null && lineSpacing == null && marginDp == null
                && theme == null && turnAnim == null
    }

    /** 三層解析結果（出廠值兜底——任何欄位都有值） */
    data class Resolved(
        val textSize: Int,
        val lineSpacing: Float,
        val marginDp: Int,
        val theme: String,
        val turnAnim: String,
        val overrides: Int,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /** 套用覆寫（逐欄位傳 null＝不覆寫該欄；全 null＝等同清空） */
    fun override(
        bookName: String,
        textSize: Int? = null,
        lineSpacing: Float? = null,
        marginDp: Int? = null,
        theme: String? = null,
        turnAnim: String? = null,
    ): Boolean {
        val key = bookName.trim()
        if (key.isEmpty()) return false
        val map = load().toMutableMap()
        val old = map[key]
        val merged = BookProfile(
            textSize = textSize?.coerceIn(TEXT_MIN_SP, TEXT_MAX_SP),
            lineSpacing = lineSpacing?.coerceIn(LINE_MIN, LINE_MAX),
            marginDp = marginDp?.coerceIn(MARGIN_MIN_DP, MARGIN_MAX_DP),
            theme = theme?.takeIf { it in THEMES },
            turnAnim = turnAnim?.takeIf {
                it == PageTurnHelper.ANIM_COVER
                    || it == PageTurnHelper.ANIM_SLIDE
                    || it == PageTurnHelper.ANIM_NONE
            },
            updatedAt = System.currentTimeMillis(),
        )
        if (merged.isEmpty) {
            map.remove(key)
        } else {
            if (map.size >= MAX_PROFILES && old == null) {
                Log.w(TAG, "方案帳冊達上限 $MAX_PROFILES——拒絕新增")
                return false
            }
            map[key] = merged
        }
        persist(map)
        return true
    }

    /** 三層解析：書籍覆寫 → 全域預設（同管理器）→ 出廠值 */
    fun resolve(bookName: String): Resolved {
        val p = load()[bookName.trim()]
        val g = global()
        return Resolved(
            textSize = (p?.textSize ?: g.textSize).coerceIn(TEXT_MIN_SP, TEXT_MAX_SP),
            lineSpacing = (p?.lineSpacing ?: g.lineSpacing).coerceIn(LINE_MIN, LINE_MAX),
            marginDp = (p?.marginDp ?: g.marginDp).coerceIn(MARGIN_MIN_DP, MARGIN_MAX_DP),
            theme = (p?.theme ?: g.theme).takeIf { it in THEMES } ?: THEME_CREAM,
            turnAnim = (p?.turnAnim ?: g.turnAnim).takeIf {
                it == PageTurnHelper.ANIM_COVER
                    || it == PageTurnHelper.ANIM_SLIDE
                    || it == PageTurnHelper.ANIM_NONE
            } ?: PageTurnHelper.ANIM_COVER,
            overrides = listOfNotNull(
                p?.textSize, p?.lineSpacing, p?.marginDp,
                p?.theme, p?.turnAnim,
            ).size,
        )
    }

    /** 某書覆寫（無則 null——面板顯示目前覆寫用） */
    fun profileOf(bookName: String): BookProfile? = load()[bookName.trim()]

    /** 清除某書覆寫（還原跟隨全域） */
    fun clear(bookName: String): Boolean {
        val key = bookName.trim()
        val map = load().toMutableMap()
        if (map.remove(key) == null) return false
        persist(map)
        return true
    }

    /** 一鍵擴散：某書覆寫升為全域預設（調好一本全書架跟進） */
    fun promoteToGlobal(bookName: String): Boolean {
        val p = load()[bookName.trim()] ?: return false
        val g = global()
        persistGlobal(g.copy(
            textSize = p.textSize ?: g.textSize,
            lineSpacing = p.lineSpacing ?: g.lineSpacing,
            marginDp = p.marginDp ?: g.marginDp,
            theme = p.theme ?: g.theme,
            turnAnim = p.turnAnim ?: g.turnAnim,
        ))
        return true
    }

    /** 全域預設（resolve 的第二層；SharedPreferences 讀取） */
    fun global(): BookProfile = readGlobal()

    /** 覆寫方案數（面板統計） */
    fun profileCount(): Int = load().size

    /** 帳冊檔（供備份層只拷檔不解析） */
    fun profileFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ── 內部：覆寫帳冊（書名為鍵） ───────────────────────────────

    private var cache: MutableMap<String, BookProfile>? = null

    private fun load(): MutableMap<String, BookProfile> {
        cache?.let { return it }
        val out = mutableMapOf<String, BookProfile>()
        val file = profileFile()
        if (file.exists()) {
            try {
                val o = JSONObject(file.readText(Charsets.UTF_8))
                for (key in o.keySet()) {
                    val p = o.optJSONObject(key) ?: continue
                    out[key] = BookProfile(
                        textSize = if (p.has("textSize")) p.getInt("textSize") else null,
                        lineSpacing = if (p.has("lineSpacing")) {
                            p.getDouble("lineSpacing").toFloat()
                        } else null,
                        marginDp = if (p.has("marginDp")) p.getInt("marginDp") else null,
                        theme = if (p.has("theme")) p.getString("theme") else null,
                        turnAnim = if (p.has("turnAnim")) p.getString("turnAnim") else null,
                        updatedAt = p.optLong("updatedAt", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "方案帳冊解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist(map: Map<String, BookProfile>) {
        try {
            val o = JSONObject()
            for ((k, p) in map) {
                o.put(k, JSONObject().apply {
                    p.textSize?.let { put("textSize", it) }
                    p.lineSpacing?.let { put("lineSpacing", it.toDouble()) }
                    p.marginDp?.let { put("marginDp", it) }
                    p.theme?.let { put("theme", it) }
                    p.turnAnim?.let { put("turnAnim", it) }
                    put("updatedAt", p.updatedAt)
                })
            }
            profileFile().writeText(o.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "方案帳冊寫入失敗", t)
        }
    }

    // ── 內部：全域預設（SharedPreferences，與全案 k_reader_config 同慣例） ──

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_G_TEXT = "profile_global_text_size"
    private const val KEY_G_LINE = "profile_global_line_spacing"
    private const val KEY_G_MARGIN = "profile_global_margin"
    private const val KEY_G_THEME = "profile_global_theme"
    private const val KEY_G_ANIM = "profile_global_turn_anim"

    private fun prefs(): android.content.SharedPreferences =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private fun readGlobal(): BookProfile {
        val sp = prefs()
        val lineDefault = TextLayoutHelper.recommendLineSpacing()
        return BookProfile(
            textSize = sp.getInt(KEY_G_TEXT, DEFAULT_TEXT_SP),
            lineSpacing = sp.getFloat(KEY_G_LINE, lineDefault),
            marginDp = sp.getInt(KEY_G_MARGIN, DEFAULT_MARGIN_DP),
            theme = sp.getString(KEY_G_THEME, THEME_CREAM) ?: THEME_CREAM,
            turnAnim = sp.getString(KEY_G_ANIM, PageTurnHelper.ANIM_COVER)
                ?: PageTurnHelper.ANIM_COVER,
            updatedAt = 0L,
        )
    }

    private fun persistGlobal(g: BookProfile) {
        prefs().edit()
            .putInt(KEY_G_TEXT, g.textSize)
            .putFloat(KEY_G_LINE, g.lineSpacing)
            .putInt(KEY_G_MARGIN, g.marginDp)
            .putString(KEY_G_THEME, g.theme)
            .putString(KEY_G_ANIM, g.turnAnim)
            .apply()
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.profile_title)
    fun globalLabel(): String = AppContext.ctx.getString(R.string.profile_global)
    fun overrideLabel(): String = AppContext.ctx.getString(R.string.profile_override)
    fun clearLabel(): String = AppContext.ctx.getString(R.string.profile_clear)
    fun promoteLabel(): String = AppContext.ctx.getString(R.string.profile_promote)
    fun textSizeLabel(): String = AppContext.ctx.getString(R.string.profile_text_size)
    fun lineSpacingLabel(): String = AppContext.ctx.getString(R.string.profile_line_spacing)
    fun marginLabel(): String = AppContext.ctx.getString(R.string.profile_margin)
    fun themeLabel(): String = AppContext.ctx.getString(R.string.profile_theme)
    fun turnAnimLabel(): String = AppContext.ctx.getString(R.string.profile_turn_anim)

    /** 主題顯示名（面板選單用） */
    fun themeName(theme: String): String = when (theme) {
        THEME_WHITE -> AppContext.ctx.getString(R.string.profile_theme_white)
        THEME_CREAM -> AppContext.ctx.getString(R.string.profile_theme_cream)
        THEME_GREEN -> AppContext.ctx.getString(R.string.profile_theme_green)
        else -> AppContext.ctx.getString(R.string.profile_theme_black)
    }

    /** 動畫顯示名 */
    fun animName(anim: String): String = when (anim) {
        PageTurnHelper.ANIM_COVER -> AppContext.ctx.getString(R.string.turn_anim_cover)
        PageTurnHelper.ANIM_SLIDE -> AppContext.ctx.getString(R.string.turn_anim_slide)
        else -> AppContext.ctx.getString(R.string.turn_anim_none)
    }

    /** 覆寫摘要：「3 項覆寫」／「跟隨全域」 */
    fun overrideSummary(resolved: Resolved): String =
        if (resolved.overrides == 0) {
            AppContext.ctx.getString(R.string.profile_follow_global)
        } else {
            AppContext.ctx.getString(R.string.profile_overrides_fmt, resolved.overrides)
        }

    fun clearedNotice(): String = AppContext.ctx.getString(R.string.profile_cleared)
    fun promotedNotice(): String = AppContext.ctx.getString(R.string.profile_promoted)
}

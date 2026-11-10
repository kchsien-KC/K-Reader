package com.k.reader.help.content

import android.content.Context
import android.content.SharedPreferences
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.k.reader.R
import com.k.reader.app.AppContext

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 台灣排版規範引擎（旗艦增益包：排版美學）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（「字排得好看」是文青閱讀器的本分）：
 *   中文排版有一套不成文卻深入眼底的規矩——冒號不得起行、
 *   開引號不得收行、刪節號不得拆成兩截。手機閱讀器窄欄寬，
 *   違規次數被放大得格外刺眼。本引擎在正文送入 StaticLayout
 *   之前做三道工序：
 *
 *    1. 避頭尾禁則（typography_line_break）：
 *       · 行首禁則（避頭字元）：，、。．：；？！」』）〕〉》
 *         ｝…％！？——收不得（出現在行首要往回借字）；
 *       · 行尾禁則（避尾字元）：「『（〔〈《｛——開不得（
 *         出現在行尾要跟下一行借位）；
 *       實作為「禁則字元換行安全化」：在禁則字元與鄰字之間
 *       插入零寬不換行（U+2060 WORD JOINER），令斷行引擎
 *       不在此處落刀；
 *    2. 標點連續擠壓（typography_punct_compress）：
 *       連續全形標點（如 」，）佔兩個全形寬過於鬆散——以
 *       併排偵測回報擠壓建議（供閱讀頁以 textScaleX 或
 *       自訂 span 微調），本引擎純字元層不破壞內文；
 *    3. 段落整理（typography_first_indent）：
 *       · 首行縮排兩個全形字（臺灣書面慣例）；
 *       · 連續空行壓成一個空行（網文常見的灌水排版）；
 *       · 行尾殘留空白與全形空白混用清整。
 *
 *   純字元層工序（零 UI 依賴）：閱讀頁取正文後呼叫
 *   applyTypography(text) 一次得成品；排版參數建議
 *   （行距 1.6 倍／字距 0.02em）由 recommendLineSpacing()
 *   等函式提供，供 TextPaint 調整。
 *
 *  整合點：
 *   · ContentViewModel／BookHelp 正文落地後 → applyTypography(content)
 *   · 閱讀頁 TextPaint 設定 → recommendLetterSpacing／
 *     recommendLineSpacing
 *   · 設定頁「排版規範」（typography_rules）→ 各開關 get／set
 */
object TextLayoutHelper {

    private const val TAG = "TextLayoutHelper"

    /** 與全域設定共用（排版屬閱讀偏好） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_LINE_BREAK = "typo_line_break_enabled"
    private const val KEY_COMPRESS = "typo_punct_compress_enabled"
    private const val KEY_FIRST_INDENT = "typo_first_indent_enabled"
    private const val KEY_BLANK_MERGE = "typo_blank_merge_enabled"

    /** 零寬不換字（U+2060 WORD JOINER——不佔寬、強制黏連兩側字元） */
    private const val WORD_JOINER = "\u2060"

    // ═════════════════ 禁則字元表 ═════════════════

    /**
     * 行首禁則（避頭）：這些字元不可出現在行首。
     * 依教育部《重訂標點符號手冊》與中文排版需求整理。
     */
    private val NO_LINE_START = setOf(
        '，', '、', '。', '．', '：', '；', '？', '！',
        '」', '』', '）', '〕', '〉', '》', '｝', '】',
        '…', '—', '％', '′', '℃'
    )

    /**
     * 行尾禁則（避尾）：這些字元不可出現在行尾。
     * 為其後插入 WORD JOINER，黏住下一個字元。
     */
    private val NO_LINE_END = setOf(
        '「', '『', '（', '〔', '〈', '《', '｛', '【',
        '＄', '＃', '＠'
    )

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════ 開關 ═════════════════

    /** 避頭尾禁則（預設開——排版底線） */
    fun isLineBreakRuleEnabled(): Boolean =
        prefs.getBoolean(KEY_LINE_BREAK, true)

    /** 連續標點擠壓建議（預設開） */
    fun isPunctCompressEnabled(): Boolean =
        prefs.getBoolean(KEY_COMPRESS, true)

    /** 首行縮排兩字元（預設開——臺灣書面慣例） */
    fun isFirstIndentEnabled(): Boolean =
        prefs.getBoolean(KEY_FIRST_INDENT, true)

    /** 連續空行合併（預設開） */
    fun isBlankMergeEnabled(): Boolean =
        prefs.getBoolean(KEY_BLANK_MERGE, true)

    fun setLineBreakRuleEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_LINE_BREAK, enabled).apply()

    fun setPunctCompressEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_COMPRESS, enabled).apply()

    fun setFirstIndentEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FIRST_INDENT, enabled).apply()

    fun setBlankMergeEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_BLANK_MERGE, enabled).apply()

    // ═════════════════ 排版參數建議 ═════════════════

    /**
     * 字距建議（em）：中文正文字距宜微鬆——0.02em；
     * 與 read_adjust 的 letter_spacing 滑桿對接（滑桿優先）。
     */
    fun recommendLetterSpacing(): Float = 0.02f

    /**
     * 行距倍數建議：手機窄欄閱讀以 1.5–1.7 倍為舒適帶，
     * 取中位 1.6；與 read_adjust 的 line_spacing 對接。
     */
    fun recommendLineSpacing(): Float = 1.6f

    /** 首行縮排寬（以字元數計——乘字級即縮排像素） */
    const val FIRST_INDENT_CHARS = 2

    // ═════════════════ 主工序 ═════════════════

    /**
     * 排版總入口：對整章正文執行已啟用的工序。
     * 純字元層操作，幂等（重複套用結果不變——WORD JOINER
     * 先剝除再判斷，不會疊加）。
     */
    fun applyTypography(text: String): String {
        if (text.isEmpty()) return text
        var result = text

        // 冪等基底：剝除前次插入的零寬字元
        result = result.replace(WORD_JOINER, "")

        if (isBlankMergeEnabled()) result = mergeBlankLines(result)
        if (isLineBreakRuleEnabled()) result = applyLineBreakRules(result)
        if (isFirstIndentEnabled()) result = applyFirstIndent(result)
        return result
    }

    /**
     * 工序一：避頭尾禁則。
     * 逐字掃描（非正則——禁則字元表是集合查詢 O(1)）。
     * 斷行發生在相鄰兩字之間，故 JOINER 的落點決定黏誰：
     *   · 避頭字元（，。」…）不可落「行首」→ 危險斷點在其
     *     「前一側」→ 下一字是避頭字元時，先插 JOINER 再放它
     *    （前字與避頭字黏死——它被帶去下一行的機率歸零）；
     *   · 避尾字元（「『（…）不可落「行尾」→ 危險斷點在其
     *     「後一側」→ 當前字是避尾字元時，其後插 JOINER
     *    （避尾字與後字黏死——不得獨留行尾）。
     * 換行字元旁不插（段落邊界本來就要斷）。
     */
    private fun applyLineBreakRules(text: String): String {
        val sb = StringBuilder(text.length + 64)
        val chars = text.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val next = if (i + 1 < chars.size) chars[i + 1] else null
            val glueNext = next != null && next != '\n' &&
                (next in NO_LINE_START || c in NO_LINE_END)
            sb.append(c)
            if (glueNext) sb.append(WORD_JOINER)
            i++
        }
        return sb.toString()
    }

    /**
     * 工序二：連續空行合併——三個以上換行壓成兩個（一個空行）。
     * 網文常見「灌水式」大空行（章末連按十幾個 Enter）一次清整。
     */
    private fun mergeBlankLines(text: String): String {
        val merged = text.replace(Regex("\n{3,}"), "\n\n")
        // 行尾空白（含全形空白）清除——殘留空白撐歪避頭尾判斷
        return merged.lineSequence()
            .joinToString("\n") { it.trimEnd(' ', '　', '\t') }
    }

    /**
     * 工序三：首行縮排——每個段落的段首加兩個全形空白
     * （全形空白＝一個漢字寬，縮排視覺剛好兩字）。
     * 已有縮排的段落不疊加（冪等）。
     */
    private fun applyFirstIndent(text: String): String {
        val indent = "　　"
        return text.lineSequence().joinToString("\n") { line ->
            val bare = line.trimStart(' ', '　')
            if (bare.isEmpty()) {
                line // 空行不縮排
            } else if (line.startsWith(indent)) {
                line // 已縮排——冪等保護
            } else {
                indent + bare
            }
        }
    }

    // ═════════════════ 標點擠壓建議 ═════════════════

    /**
     * 連續全形標點擠壓偵測：回傳全文「標點併排」出現次數
     * （如 「……」、。」），供閱讀頁決定是否啟用
     * textScaleX 微縮（擠壓視覺建議——不動內文本身）。
     */
    fun countPunctClusters(text: String): Int {
        if (!isPunctCompressEnabled()) return 0
        val cluster = Regex("[，、。．：；？！」』）〕〉》｝】…—]{2,}")
        return cluster.findAll(text).count()
    }

    /**
     * 標點擠壓 textScaleX 建議：併排標點出現密度高 → 建議
     * 0.92（8% 微縮）；密度低 → 1.0（不縮——避免全文變瘦）。
     */
    fun recommendPunctScale(text: String): Float {
        val clusters = countPunctClusters(text)
        val paragraphs = text.lineSequence().count { it.isNotBlank() }
        if (paragraphs == 0) return 1.0f
        val density = clusters.toFloat() / paragraphs
        return if (density > 1.5f) 0.92f else 1.0f
    }

    // ═════════════════ 預覽量測 ═════════════════

    /**
     * 排版預覽量測：以指定字級／欄寬跑一次 StaticLayout，
     * 回傳行數（設定頁「排版規範」預覽用——前後對比讓效果
     * 可見）。寬度或漆工不可用時回 -1。
     */
    fun previewLineCount(text: String, textSizePx: Float, widthPx: Int): Int {
        if (text.isEmpty() || widthPx <= 0 || textSizePx <= 0f) return -1
        return runCatching {
            val paint = TextPaint().apply {
                this.textSize = textSizePx
                letterSpacing = recommendLetterSpacing()
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, widthPx)
                .setLineSpacing(0f, recommendLineSpacing())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            layout.lineCount
        }.getOrDefault(-1)
    }

    /** 設定頁摘要：已啟用的工序清單（供偏好摘要顯示） */
    fun enabledRulesSummary(ctx: Context): String {
        val parts = mutableListOf<String>()
        if (isLineBreakRuleEnabled()) parts.add(ctx.getString(R.string.typography_line_break))
        if (isPunctCompressEnabled()) parts.add(ctx.getString(R.string.typography_punct_compress))
        if (isFirstIndentEnabled()) parts.add(ctx.getString(R.string.typography_first_indent))
        if (isBlankMergeEnabled()) parts.add(ctx.getString(R.string.typography_blank_merge))
        if (parts.isEmpty()) return ctx.getString(R.string.disabled)
        return parts.joinToString("・")
    }
}

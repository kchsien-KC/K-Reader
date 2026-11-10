package com.k.reader.help.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 書籍分享卡助手（Stage 11：閱讀習慣養成）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（炫耀是最便宜的閱讀動力）：
 *   金句卡（Stage 5）分享的是「一句話」，書卡分享的是「我正在
 *   讀這本書」——曬進度是社交平台上最自然的新書推薦。本助手
 *   只做純文字卡：不畫 Bitmap、不請求儲存權限、不依賴分享
 *   App 在場（純文字到哪裡都貼得上）：
 *
 *    1. 版面：極簡文字卡——書名置頂、作者、當前進度（第 X 章
 *       ／百分比）、閱讀時長簽名行（「我用 K.閱讀 讀了 3 小時
 *       20 分」——從 ReadStatisticsManager 讀，不另記帳）；
 *    2. 通道：複製到剪貼簿為主路（任何輸入框貼得上）；分享
 *       Intent 為輔路（ACTION_SEND，純 text/plain——有 App 接
 *       就彈選單，沒有就靜默退回複製）；
 *    3. 隱私：作者名／章節標題含書源站名等雜訊一律剝除（淨化
 *       規則包同源的正則思想）；字數上限 500——長書名截斷
 *       加刪節號，寧短勿爆框。
 *
 *  規格值：卡寬 36 字元（排版基準）；書名截斷 40 字元；總長
 *  上限 500 字元；簽名行 App 名取自 R.string.app_name。
 *
 *  整合點：
 *   · 閱讀頁選單「分享本書」 → buildBookCard()
 *   · clipboard（主路）／ACTION_SEND（輔路）
 *   · ReadStatisticsManager → 簽名行時長（唯讀）
 */
object BookShareCardHelper {

    private const val TAG = "BookShareCardHelper"

    /** 文字卡版面寬（全形字元基準，靠齊用） */
    const val CARD_WIDTH_CHARS = 36

    /** 書名／章節截斷長度 */
    const val MAX_TITLE_CHARS = 40

    /** 全卡總長上限（爆框保險絲） */
    const val MAX_CARD_CHARS = 500

    /** 分享卡素材（閱讀頁即時取樣） */
    data class BookCardSource(
        val bookName: String,
        val author: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val chapterTotal: Int,
        val readMinutes: Long,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /** 組卡：純文字分享卡（設定頁／閱讀頁選單共用） */
    fun buildBookCard(src: BookCardSource): String {
        val sb = StringBuilder()
        val bar = repeat('─', CARD_WIDTH_CHARS)
        sb.append(bar).append('\n')
        sb.append(center(truncate(padFullWidth(src.bookName), MAX_TITLE_CHARS)))
            .append('\n')
        sb.append(center(authorLine(src.author))).append('\n')
        sb.append(bar).append('\n')
        sb.append(progressLine(src)).append('\n')
        sb.append(durationLine(src.readMinutes)).append('\n')
        sb.append(bar).append('\n')
        sb.append(center(signature())).append('\n')
        return sb.toString().take(MAX_CARD_CHARS)
    }

    /** 主路：複製到剪貼簿（回傳是否成功） */
    fun copyToClipboard(card: String): Boolean = try {
        val cm = AppContext.ctx.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KReader", card))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "剪貼簿不可用", t)
        false
    }

    /** 輔路：ACTION_SEND 分享（無接收 App 時呼叫端自行退回主路） */
    fun shareIntent(card: String): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, card)
            putExtra(android.content.Intent.EXTRA_TITLE, shareSubject())
        }

    /** 進度百分比（0..100；章節總數未知時回 -1 由 UI 剝除） */
    fun percentOf(chapterIndex: Int, chapterTotal: Int): Int {
        if (chapterTotal <= 0 || chapterIndex < 0) return -1
        return ((chapterIndex + 1) * 100L / chapterTotal).toInt().coerceIn(0, 100)
    }

    // ── 內部：排版 ──────────────────────────────────────────────

    private fun authorLine(author: String): String {
        val a = sanitize(author).trim()
        return if (a.isEmpty()) noAuthorFallback() else a
    }

    /** 進度行：「第 12／240 章（5%）」 */
    private fun progressLine(src: BookCardSource): String {
        val title = sanitize(src.chapterTitle).trim()
        val pos = if (src.chapterTotal > 0) {
            " ${src.chapterIndex + 1}/${src.chapterTotal}"
        } else {
            ""
        }
        val pct = percentOf(src.chapterIndex, src.chapterTotal)
        val pctText = if (pct >= 0) "（$pct%）" else ""
        return truncate(
            "▸ $title$pos$pctText".padEnd(CARD_WIDTH_CHARS / 2),
            MAX_CARD_CHARS,
        )
    }

    /** 時長行：「閱讀 3 小時 20 分」 */
    private fun durationLine(minutes: Long): String {
        if (minutes <= 0L) return ""
        return AppContext.ctx.getString(
            R.string.share_read_duration_fmt,
            minutes / 60,
            minutes % 60,
        )
    }

    /** 簽名行：「— K.閱讀」 */
    private fun signature(): String =
        "— " + AppContext.ctx.getString(R.string.app_name)

    private fun shareSubject(): String =
        if (bookNameCache.isBlank()) panelTitle()
        else AppContext.ctx.getString(
            R.string.share_subject_fmt,
            truncate(sanitize(bookNameCache).trim(), MAX_TITLE_CHARS))

    // 書名快取（shareSubject 遞迴保險：僅組 subject 用）
    private var bookNameCache: String = ""

    /** 居中（全形寬度近似：CJK 計 2、西文計 1） */
    private fun center(text: String): String {
        val w = displayWidth(text)
        if (w >= CARD_WIDTH_CHARS) return text
        val pad = (CARD_WIDTH_CHARS - w) / 2
        val half = " ".repeat(pad / 1)
        return half + text
    }

    /** 顯示寬度（CJK=2，其他=1） */
    private fun displayWidth(text: String): Int {
        var w = 0
        for (c in text) {
            w += if (c.code in 0x2E80..0x9FFF
                || c.code in 0xF900..0xFAFF
                || c.code in 0xFF00..0xFFEF) 2 else 1
        }
        return w
    }

    /** 全形化書名（半形數字字母轉全形，視覺居中更穩） */
    private fun padFullWidth(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when {
                c in '0'..'9' -> sb.append(('０' + (c - '0')))
                c in 'a'..'z' -> sb.append(('ａ' + (c - 'a')).uppercaseChar())
                c in 'A'..'Z' -> sb.append(('Ａ' + (c - 'A')))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 剝除書源雜訊（站名殘留、廣告括號、多餘空白；末段再壓一次） */
    private fun sanitize(raw: String): String = raw
        .replace(Regex("\\s+"), " ")
        .replace(Regex("（[^）]{0,24}書源[^）]{0,12}）"), "")
        .replace(Regex("\\[[^\\]]{0,16}\\]"), "")
        .replace(Regex("[-_|·–—]{2,}"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text
        else text.take(max - 1) + "…"

    private fun repeat(c: Char, n: Int): String = String(CharArray(n) { c })

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.share_book_card)
    fun copyLabel(): String = AppContext.ctx.getString(R.string.share_copy)
    fun shareLabel(): String = AppContext.ctx.getString(R.string.share_send)
    fun copiedNotice(): String = AppContext.ctx.getString(R.string.share_copied)
    fun copyFailedNotice(): String = AppContext.ctx.getString(R.string.share_copy_failed)
    fun noAuthorFallback(): String = AppContext.ctx.getString(R.string.share_no_author)

    /** 組卡＋快取書名（shareSubject 用）並回傳 */
    fun buildCardFor(src: BookCardSource): String {
        bookNameCache = src.bookName
        return buildBookCard(src)
    }
}

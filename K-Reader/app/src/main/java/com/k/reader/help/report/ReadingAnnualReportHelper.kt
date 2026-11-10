package com.k.reader.help.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.badge.AchievementBadgeManager
import com.k.reader.help.challenge.ReadingChallengeManager
import com.k.reader.help.note.HighlightNoteManager
import com.k.reader.help.quote.GoldenQuoteManager
import com.k.reader.help.statistics.ReadStatisticsManager
import com.k.reader.help.vocab.VocabularyNoteManager

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 閱讀年報助手（Stage 13：閱讀資料自主與年度回顧）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（一年的字，值得一句話的交代）：
 *   把散落在六個模組裡的成長痕跡——統計、徽章、金句、生詞、
 *   筆記、挑戰——拼成一張可以貼給任何人看的年度文字卡：
 *
 *    1. 年度窗口：滾動 365 天（dailyChart 全窗）——分鐘逐柱
 *       加總、字數逐日 dailyWordCount 加總、活躍天＝非零柱數、
 *       最佳單日＝最大柱；
 *    2. 六方計數：徽章（已授／總數）、金句、生詞（收錄／已掌
 *       握）、劃線筆記、挑戰獎盃、累計完本——全部單源唯讀，
 *       不另記帳（來源缺席即該項歸零，兩處永不矛盾）；
 *    3. 純文字卡：36 顯示寬度、零 Bitmap、總長保險絲 640 字元
 *       ——剪貼簿主路＋ACTION_SEND 輔路（與書籍分享卡同款
 *       視覺語言：CJK 計 2、西文計 1、全形化、居中）。
 *
 *  規格值：年度窗 365 天；卡寬 36；保險絲 640；完本數取
 *  summaryTotal()（累計口径，卡面誠實標注「累計」）。
 *
 *  整合點：
 *   · 設定頁 annual_title 面板 → buildReport()＋buildCard()
 *   · dialog_annual_report.xml 年報對話框（Stage 13 布局）
 *   · ReadStatisticsManager／五模組唯讀視窗
 */
object ReadingAnnualReportHelper {

    private const val TAG = "ReadingAnnualReport"

    /** 年度窗口（滾動天數） */
    const val YEAR_WINDOW_DAYS = 365

    /** 卡寬（顯示寬度：CJK 計 2、西文計 1） */
    const val CARD_WIDTH_CHARS = 36

    /** 卡總長保險絲（字元）——防異常資料炸卡 */
    const val MAX_CARD_CHARS = 640

    /** 年報聚合行 */
    data class AnnualReport(
        val minutes: Long,
        val words: Long,
        val activeDays: Int,
        val bestDayKey: String,
        val bestDayMinutes: Long,
        val finishedTotal: Int,
        val badges: Int,
        val badgeTotal: Int,
        val quotes: Int,
        val vocabActive: Int,
        val vocabMastered: Int,
        val notes: Int,
        val trophies: Int,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 聚合年度報告（單源唯讀；任何來源缺席以 0 計，不拋錯）。
     * 字數採 dailyWordCount 逐日加總（與報表 CSV 同一口徑）。
     */
    fun buildReport(): AnnualReport {
        val chart = try {
            ReadStatisticsManager.dailyChart(YEAR_WINDOW_DAYS)
        } catch (t: Throwable) {
            Log.w(TAG, "統計不可讀——年報按 0 處理", t)
            emptyList()
        }
        val minutes = chart.sumOf { it.minutes }
        val activeDays = chart.count { it.minutes > 0L }
        val best = chart.filter { it.minutes > 0L }.maxByOrNull { it.minutes }
        var words = 0L
        for (bar in chart) {
            words += try {
                ReadStatisticsManager.dailyWordCount(bar.dateKey)
            } catch (t: Throwable) {
                Log.w(TAG, "字數不可讀：${bar.dateKey}", t)
                0L
            }
        }
        return AnnualReport(
            minutes = minutes,
            words = words,
            activeDays = activeDays,
            bestDayKey = best?.dateKey ?: "",
            bestDayMinutes = best?.minutes ?: 0L,
            finishedTotal = safely { ReadStatisticsManager.summaryTotal().finishedCount } ?: 0,
            badges = safely { AchievementBadgeManager.awardedCount() } ?: 0,
            badgeTotal = safely { AchievementBadgeManager.totalBadges() } ?: 0,
            quotes = safely { GoldenQuoteManager.allQuotes().size } ?: 0,
            vocabActive = safely { VocabularyNoteManager.activeCount() } ?: 0,
            vocabMastered = safely { VocabularyNoteManager.masteredCount() } ?: 0,
            notes = safely { HighlightNoteManager.allNotes().size } ?: 0,
            trophies = safely { ReadingChallengeManager.trophyWall().size } ?: 0,
        )
    }

    /** 年報是否空白（分鐘與字數皆零） */
    fun isEmpty(report: AnnualReport): Boolean =
        report.minutes <= 0L && report.words <= 0L

    /**
     * 組年度文字卡（零 Bitmap）：36 顯示寬度框線＋居中標題＋
     * 十二行數據＋品牌簽名；總長超過保險絲即截斷收尾。
     */
    fun buildCard(report: AnnualReport): String {
        val ctx = AppContext.ctx
        val bar = repeat('─', CARD_WIDTH_CHARS / 2)
        val sb = StringBuilder()
        sb.append(bar).append('\n')
        sb.append(center(panelTitle())).append('\n')
        sb.append(center(windowLabel())).append('\n')
        sb.append(bar).append('\n')
        sb.append(line(ctx.getString(R.string.annual_minutes_fmt,
            ReadStatisticsManager.formatDuration(report.minutes))))
        sb.append(line(ctx.getString(R.string.annual_words_fmt,
            ReadStatisticsManager.formatWords(report.words))))
        sb.append(line(ctx.getString(R.string.annual_active_days_fmt, report.activeDays)))
        sb.append(line(ctx.getString(R.string.annual_best_day_fmt,
            report.bestDayKey.ifBlank { "—" },
            ReadStatisticsManager.formatDuration(report.bestDayMinutes))))
        sb.append(line(ctx.getString(R.string.annual_finished_fmt, report.finishedTotal)))
        sb.append(bar).append('\n')
        sb.append(line(ctx.getString(R.string.annual_badges_fmt,
            report.badges, report.badgeTotal)))
        sb.append(line(ctx.getString(R.string.annual_quotes_fmt, report.quotes)))
        sb.append(line(ctx.getString(R.string.annual_vocab_fmt,
            report.vocabActive, report.vocabMastered)))
        sb.append(line(ctx.getString(R.string.annual_notes_fmt, report.notes)))
        sb.append(line(ctx.getString(R.string.annual_trophies_fmt, report.trophies)))
        sb.append(bar).append('\n')
        sb.append(center(brandLine())).append('\n')
        sb.append(bar)
        return if (sb.length <= MAX_CARD_CHARS) sb.toString()
        else sb.substring(0, MAX_CARD_CHARS - 1) + "…"
    }

    /** 主路：複製年報卡到剪貼簿 */
    fun copyCard(card: String): Boolean = try {
        val cm = AppContext.ctx.getSystemService(Context.CLIPBOARD_SERVICE)
        (cm as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(panelTitle(), card))
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
            putExtra(android.content.Intent.EXTRA_TITLE, panelTitle())
        }

    // ── 內部：排版（與 BookShareCardHelper 同款視覺語言） ───────

    /** 單行：貼齊卡寬（右側補空白至滿寬，超寬截斷） */
    private fun line(text: String): String =
        truncate(padFullWidth(text), MAX_CARD_CHARS) + "\n"

    /** 居中（全形寬度近似：CJK 計 2、西文計 1） */
    private fun center(text: String): String {
        val w = displayWidth(text)
        if (w >= CARD_WIDTH_CHARS) return text
        val pad = (CARD_WIDTH_CHARS - w) / 2
        return " ".repeat(pad) + text
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

    /** 全形化（半形數字字母轉全形，視覺對齊更穩） */
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

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text
        else text.take(max - 1) + "…"

    private fun repeat(c: Char, n: Int): String = String(CharArray(n) { c })

    /** 來源缺席歸零（單源唯讀——模組不在場不致命） */
    private fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "年報來源缺席——該項按 0 計", t)
        null
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.annual_title)
    fun windowLabel(): String = AppContext.ctx.getString(R.string.annual_window)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.annual_empty)
    fun shareLabel(): String = AppContext.ctx.getString(R.string.annual_share)
    fun closeLabel(): String = AppContext.ctx.getString(R.string.annual_close)
    fun copiedNotice(): String = AppContext.ctx.getString(R.string.share_copied)
    fun copyFailedNotice(): String = AppContext.ctx.getString(R.string.share_copy_failed)
    fun brandLine(): String = AppContext.ctx.getString(R.string.annual_brand)
}

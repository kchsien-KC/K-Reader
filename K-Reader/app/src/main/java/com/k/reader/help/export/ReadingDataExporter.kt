package com.k.reader.help.export

import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.badge.AchievementBadgeManager
import com.k.reader.help.challenge.ReadingChallengeManager
import com.k.reader.help.note.HighlightNoteManager
import com.k.reader.help.quote.GoldenQuoteManager
import com.k.reader.help.statistics.ReadStatisticsManager
import com.k.reader.help.vocab.VocabularyNoteManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 資料匯出中心（Stage 13：閱讀資料自主與年度回顧）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（資料是你的，格式也該是你的）：
 *   搬家包（DeviceMigrationHelper）管機器格式 KRM1——換機用；
 *   匯出中心管人類可讀 Markdown——存檔、列印、貼部落格用。
 *   一份報告把六個帳冊的「內容本體」完整抄錄：
 *
 *    1. 閱讀統計：今日／本週／累計三欄＋滾動 365 天逐日表
 *       （分鐘＋字數，與報表 CSV 同一口徑）；
 *    2. 金句簿：全數抄錄（書名／章節／全文／收藏日期）；
 *    3. 生詞本：全數抄錄（詞形／釋義／出處／溫故階梯狀態）；
 *    4. 劃線筆記：全數抄錄（內容＋批註＋出處）；
 *    5. 閱讀挑戰：獎盃牆逐座（trophyLine 原文）；
 *    6. 成就徽章：已授／總數計數行。
 *
 *  全部單源唯讀——不記帳；來源缺席即該節標「（無資料）」。
 *  保險絲：全文 2M 字元，超限中止匯出（防異常帳冊炸檔）。
 *
 *  整合點：
 *   · 設定頁 dataexp_title 面板 → exportMarkdown()／shareFile()
 */
object ReadingDataExporter {

    private const val TAG = "ReadingDataExporter"

    /** 匯出輸出子目錄 */
    const val EXPORTS_DIR = "exports"

    /** 檔名前綴 */
    const val EXPORT_PREFIX = "k_reader_export_"

    /** FileProvider 授權（與金句卡／報表 CSV 同源） */
    const val FILE_PROVIDER_AUTHORITY = "com.k.reader.filen"

    /** 全文保險絲（字元）——超限中止匯出 */
    const val MAX_REPORT_CHARS = 2_000_000

    /** 匯出結果（chars＝報告字元數；sections＝收錄節數） */
    data class ExportResult(val chars: Int, val sections: Int)

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 組 Markdown 報告（人類可讀）。各節單源唯讀；來源缺席標
     * 「（無資料）」。全文超保險絲回 null（防異常帳冊）。
     */
    fun buildMarkdown(now: Long = System.currentTimeMillis()): String? {
        val sb = StringBuilder()
        sb.append("# ").append(panelTitle()).append('\n')
        sb.append("- ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)
            .format(Date(now))).append('\n')
        var sections = 0

        sb.append('\n').append("## ").append(sectionStats()).append('\n')
        appendStats(sb)
        sections++

        sb.append('\n').append("## ").append(sectionQuotes()).append('\n')
        appendQuotes(sb)
        sections++

        sb.append('\n').append("## ").append(sectionVocab()).append('\n')
        appendVocab(sb)
        sections++

        sb.append('\n').append("## ").append(sectionNotes()).append('\n')
        appendNotes(sb)
        sections++

        sb.append('\n').append("## ").append(sectionChallenges()).append('\n')
        appendChallenges(sb)
        sections++

        sb.append('\n').append("## ").append(sectionBadges()).append('\n')
        appendBadges(sb)
        sections++

        if (sb.length > MAX_REPORT_CHARS) {
            Log.w(TAG, "報告超保險絲（${sb.length} 字元）——中止匯出")
            return null
        }
        return sb.toString()
    }

    /** 落盤 Markdown（filesDir/exports/；無資料或失敗回 null） */
    fun exportMarkdown(now: Long = System.currentTimeMillis()): ExportResult? {
        val md = buildMarkdown(now) ?: return null
        return try {
            val dir = File(AppContext.ctx.filesDir, EXPORTS_DIR).apply { mkdirs() }
            val file = File(dir, EXPORT_PREFIX + dateKeyOf(now) + ".md")
            file.writeText(md, Charsets.UTF_8)
            Log.i(TAG, "Markdown 匯出成功：${file.name}（${md.length} 字元）")
            ExportResult(chars = md.length, sections = 6)
        } catch (t: Throwable) {
            Log.w(TAG, "Markdown 匯出失敗", t)
            null
        }
    }

    /** 分享（FileProvider——com.k.reader.filen） */
    fun shareFile(file: File): Intent {
        val uri = FileProvider.getUriForFile(AppContext.ctx, FILE_PROVIDER_AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── 內部：各節抄錄（單源唯讀，缺席即空） ────────────────────

    /** 統計節：三欄摘要＋滾動 365 天逐日表 */
    private fun appendStats(sb: StringBuilder) {
        val today = safely { ReadStatisticsManager.summaryToday() }
        val week = safely { ReadStatisticsManager.summaryWeek() }
        val total = safely { ReadStatisticsManager.summaryTotal() }
        if (today == null && week == null && total == null) {
            sb.append(noData()).append('\n')
            return
        }
        sb.append('|').append(periodLabel()).append('|')
            .append(durationLabel()).append('|').append(wordsLabel()).append('\n')
        sb.append("|---|---|---|\n")
        appendStatRow(sb, todayLabel(), today?.minutes, today?.words)
        appendStatRow(sb, weekLabel(), week?.minutes, week?.words)
        appendStatRow(sb, totalLabel(), total?.minutes, total?.words)
        val chart = safely { ReadStatisticsManager.dailyChart(365) } ?: emptyList()
        if (chart.isNotEmpty()) {
            sb.append('\n').append("- ")
                .append(AppContext.ctx.getString(R.string.dataexp_days_fmt, chart.size))
                .append('\n')
            for (bar in chart.filter { it.minutes > 0L }) {
                sb.append("  - ").append(bar.dateKey).append("：")
                    .append(bar.minutes).append(" 分／")
                    .append(safely { ReadStatisticsManager.dailyWordCount(bar.dateKey) } ?: 0L)
                    .append(" 字\n")
            }
        }
    }

    private fun appendStatRow(sb: StringBuilder, label: String, minutes: Long?, words: Long?) {
        sb.append('|').append(label).append('|')
            .append(minutes ?: 0L).append(" 分|")
            .append(words ?: 0L).append(" 字|\n")
    }

    /** 金句節：全數抄錄 */
    private fun appendQuotes(sb: StringBuilder) {
        val quotes = safely { GoldenQuoteManager.allQuotes() } ?: emptyList()
        if (quotes.isEmpty()) {
            sb.append(noData()).append('\n')
            return
        }
        for (q in quotes) {
            sb.append("- 《").append(q.bookName).append("》")
                .append(q.chapterTitle?.let { "・$it" } ?: "")
                .append("：").append(q.content.replace('\n', ' '))
                .append('\n')
        }
    }

    /** 生詞節：詞形＋釋義＋出處＋狀態 */
    private fun appendVocab(sb: StringBuilder) {
        val words = safely { VocabularyNoteManager.allWords() } ?: emptyList()
        if (words.isEmpty()) {
            sb.append(noData()).append('\n')
            return
        }
        for (w in words) {
            sb.append("- ").append(w.word).append("：")
                .append(w.meaning.ifBlank { "—" })
                .append("（《").append(w.bookName).append("》")
                .append(w.chapterTitle.ifBlank { "—" })
                .append(if (w.mastered) "，已掌握）" else "，溫故中）")
                .append('\n')
        }
    }

    /** 劃線節：內容＋批註＋出處 */
    private fun appendNotes(sb: StringBuilder) {
        val notes = safely { HighlightNoteManager.allNotes() } ?: emptyList()
        if (notes.isEmpty()) {
            sb.append(noData()).append('\n')
            return
        }
        for (n in notes) {
            sb.append("- 《").append(n.bookName).append("》")
                .append(n.chapterTitle?.let { "・$it" } ?: "")
                .append("：").append(n.content.replace('\n', ' '))
            if (!n.note.isNullOrBlank()) {
                sb.append("｜批註：").append(n.note!!.replace('\n', ' '))
            }
            sb.append('\n')
        }
    }

    /** 挑戰節：獎盃牆原文（trophyLine 單源） */
    private fun appendChallenges(sb: StringBuilder) {
        val trophies = safely { ReadingChallengeManager.trophyWall() } ?: emptyList()
        if (trophies.isEmpty()) {
            sb.append(noData()).append('\n')
            return
        }
        for (c in trophies) {
            sb.append("- ").append(ReadingChallengeManager.trophyLine(c)).append('\n')
        }
    }

    /** 徽章節：計數行 */
    private fun appendBadges(sb: StringBuilder) {
        val awarded = safely { AchievementBadgeManager.awardedCount() } ?: 0
        val total = safely { AchievementBadgeManager.totalBadges() } ?: 0
        sb.append("- ").append(badgeLineOf(awarded, total)).append('\n')
    }

    /** 來源缺席歸零（單源唯讀——模組不在場不致命） */
    private fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "匯出來源缺席——該節按空處理", t)
        null
    }

    /** 「已授 6／全 15 枚」 */
    private fun badgeLineOf(awarded: Int, total: Int): String =
        AppContext.ctx.getString(R.string.dataexp_badges_fmt, awarded, total)

    private fun noData(): String = "（無資料）"

    private fun dateKeyOf(now: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(Date(now))

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.dataexp_title)
    fun markdownLabel(): String = AppContext.ctx.getString(R.string.dataexp_markdown)
    fun emptyNotice(): String = AppContext.ctx.getString(R.string.dataexp_empty)
    fun failedNotice(): String = AppContext.ctx.getString(R.string.dataexp_failed)
    fun shareLabel(): String = AppContext.ctx.getString(R.string.dataexp_share)
    fun sectionStats(): String = AppContext.ctx.getString(R.string.dataexp_section_stats)
    fun sectionQuotes(): String = AppContext.ctx.getString(R.string.dataexp_section_quotes)
    fun sectionVocab(): String = AppContext.ctx.getString(R.string.dataexp_section_vocab)
    fun sectionNotes(): String = AppContext.ctx.getString(R.string.dataexp_section_notes)
    fun sectionChallenges(): String =
        AppContext.ctx.getString(R.string.dataexp_section_challenges)
    fun sectionBadges(): String = AppContext.ctx.getString(R.string.dataexp_section_badges)
    fun periodLabel(): String = AppContext.ctx.getString(R.string.dataexp_period)

    /** 表頭欄位與三欄列名：複用統計模組既有鍵（單源不重複） */
    fun durationLabel(): String = AppContext.ctx.getString(R.string.stats_duration)
    fun wordsLabel(): String = AppContext.ctx.getString(R.string.stats_words)
    fun todayLabel(): String = AppContext.ctx.getString(R.string.stats_today)
    fun weekLabel(): String = AppContext.ctx.getString(R.string.stats_week)
    fun totalLabel(): String = AppContext.ctx.getString(R.string.stats_total)

    /** 「已匯出 k_reader_export_20251110.md」 */
    fun exportedNotice(file: File): String =
        AppContext.ctx.getString(R.string.dataexp_exported_fmt, file.name)

    /** 「共 58213 字元」 */
    fun charsLine(chars: Int): String =
        AppContext.ctx.getString(R.string.dataexp_chars_fmt, chars)
}

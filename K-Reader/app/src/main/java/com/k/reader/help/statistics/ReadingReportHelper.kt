package com.k.reader.help.statistics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.FileProvider
import com.k.reader.R
import com.k.reader.app.AppContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 閱讀週報／月報（Stage 10：閱讀報表）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（數字不會說謊，但需要被好好呈現）：
 *   ReadStatisticsManager 已把「日」累好——週報／月報把沉在
 *   偏好檔裡的複利翻成兩種可帶走的形態：
 *
 *    1. 報表：滾動窗口（7／30 天，與統計管理員同一口徑——跨月
 *       跨週不歸零）彙整時長／字數／活躍天／完本數＋日均、最長
 *       的一天；資料零填空（dailyChart 已保證缺日補零）；
 *    2. CSV：UTF-8 BOM 開頭（Excel 直開不亂碼——台灣場景常見
 *       困擾）、日期鍵升序、report_csv_title 三欄表頭；落
 *       filesDir/reports/，FileProvider（com.k.reader.filen）分享；
 *    3. 報表卡：1080×1350 純程序化 PNG——青黛宣紙視覺語言與
 *       金句卡同宗（宣紙底／青黛墨／朱砂點睛）；頂部標題＋期間
 *       行、三宮格大數字（時長／字數／活躍天）、日閱讀量長條圖
 *       （週報 7 柱、月報 30 柱）、最長的一天一行註腳；StaticLayout
 *       排版（超寬自動換行不截字）；MediaStore 落 Pictures/KReader/
 *       （Q+）或 legacy 路徑（Q 以下）——與金句卡同一分享管道。
 *
 *  規格值：卡寬 1080／高 1350；CSV 保留全部窗口天數（含零日）。
 *
 *  整合點：
 *   · ReadStatisticsManager.dailyChart／summaryWeek／summaryMonth
 *     ——單一資料源，報表不另立帳本
 *   · 「我的」頁 report_title 面板 → buildWeekReport／buildMonthReport
 *   · 分享按鈕 → exportCsv／renderCard＋shareCard／saveCardToGallery
 */
object ReadingReportHelper {

    private const val TAG = "ReadingReportHelper"

    private const val REPORTS_DIR = "reports"

    /** 週報窗口（規格：7 天——與統計管理員滾動窗口同口徑） */
    const val WEEK_DAYS = 7

    /** 月報窗口（規格：30 天） */
    const val MONTH_DAYS = 30

    /** 報表卡寬（規格：1080px） */
    const val CARD_WIDTH = 1080

    /** 報表卡高（規格：1350px） */
    const val CARD_HEIGHT = 1350

    /** FileProvider authority（與金句卡共用） */
    private const val FILE_PROVIDER_AUTHORITY = "com.k.reader.filen"

    /** MediaStore 相簿目錄（與金句卡共用） */
    private const val GALLERY_DIR = "KReader"

    // ═════════════════ 青黛宣紙色票（與金句卡同宗） ═════════════════

    private const val COLOR_PAPER = 0xFFF7F3EA.toInt()      // 宣紙底
    private const val COLOR_INDIGO = 0xFF2F3E5C.toInt()     // 青黛墨
    private const val COLOR_INDIGO_SOFT = 0xFF8A93A6.toInt() // 淡黛（次要字）
    private const val COLOR_VERMILION = 0xFFB03A2E.toInt()  // 朱砂（點睛）
    private const val COLOR_CARD_LINE = 0xFFE3DCCB.toInt()  // 框線

    // ═════════════════ 報表模型 ═════════════════

    /** 期間（report_period_fmt：起訖日） */
    data class ReportPeriod(val startKey: String, val endKey: String)

    /** 報表彙整（單一資料源：ReadStatisticsManager） */
    data class ReportData(
        val isWeek: Boolean,
        val period: ReportPeriod,
        val minutes: Long,
        val words: Long,
        val finishedCount: Int,
        val activeDays: Int,
        val dailyBars: List<ReadStatisticsManager.DailyBar>,
        val bestDay: ReadStatisticsManager.DailyBar?
    ) {
        /** 日均時長（活躍日口徑；零活躍回 0） */
        val avgMinutesPerActiveDay: Long
            get() = if (activeDays > 0) minutes / activeDays else 0L

        /** 最長的一天文案（report_best_day） */
        val bestDayText: String?
            get() = bestDay?.let { bar ->
                if (bar.minutes <= 0L) null else
                    AppContext.ctx.getString(
                        R.string.report_best_day,
                        shortDateOf(bar.dateKey),
                        ReadStatisticsManager.formatDuration(bar.minutes)
                    )
            }
    }

    /** 週報（滾動 7 天） */
    fun buildWeekReport(): ReportData = buildReport(isWeek = true)

    /** 月報（滾動 30 天） */
    fun buildMonthReport(): ReportData = buildReport(isWeek = false)

    private fun buildReport(isWeek: Boolean): ReportData {
        val days = if (isWeek) WEEK_DAYS else MONTH_DAYS
        val summary = if (isWeek) {
            ReadStatisticsManager.summaryWeek()
        } else {
            ReadStatisticsManager.summaryMonth()
        }
        val bars = ReadStatisticsManager.dailyChart(days)
        val bestDay = bars.maxByOrNull { it.minutes }?.takeIf { it.minutes > 0L }
        return ReportData(
            isWeek = isWeek,
            period = ReportPeriod(
                startKey = bars.firstOrNull()?.dateKey ?: todayKey(),
                endKey = bars.lastOrNull()?.dateKey ?: todayKey()
            ),
            minutes = summary.minutes,
            words = summary.words,
            finishedCount = summary.finishedCount,
            activeDays = summary.activeDays,
            dailyBars = bars,
            bestDay = bestDay
        )
    }

    /** 是否空報表（期間零記錄——report_empty 提示用） */
    fun isEmpty(report: ReportData): Boolean = report.minutes <= 0L && report.words <= 0L

    // ═════════════════ CSV 匯出 ═════════════════

    /**
     * CSV 匯出（report_export_csv）：
     * UTF-8 BOM＋report_csv_title 表頭＋日期鍵升序全窗口（含零日）。
     * 落 filesDir/reports/k_reader_report_{week|month}_{endKey}.csv。
     */
    fun exportCsv(report: ReportData): File? {
        if (isEmpty(report)) return null
        return runCatching {
            val dir = File(AppContext.ctx.filesDir, REPORTS_DIR).apply { mkdirs() }
            val kind = if (report.isWeek) "week" else "month"
            val file = File(dir, "k_reader_report_${kind}_${report.period.endKey}.csv")
            val sb = StringBuilder()
            sb.append('\uFEFF')   // BOM：Excel 直開不亂碼
            sb.append(AppContext.ctx.getString(R.string.report_csv_title)).append("\r\n")
            for (bar in report.dailyBars) {
                sb.append(bar.dateKey).append(',')
                    .append(bar.minutes).append(',')
                    .append(ReadStatisticsManager.dailyWordCount(bar.dateKey))
                    .append("\r\n")
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            Log.i(TAG, "CSV 匯出成功：${file.name}（${report.dailyBars.size} 行）")
            file
        }.getOrElse { e ->
            Log.w(TAG, "CSV 匯出失敗", e)
            null
        }
    }

    /** CSV 分享（FileProvider——com.k.reader.filen） */
    fun shareCsv(file: File): Intent {
        val uri = FileProvider.getUriForFile(AppContext.ctx, FILE_PROVIDER_AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ═════════════════ 報表卡繪製 ═════════════════

    /**
     * 報表卡繪製（report_export_image）：
     * 1080×1350 PNG 落 filesDir/reports/——純程序化，無點陣資源。
     */
    fun renderCard(report: ReportData): File? {
        if (isEmpty(report)) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(COLOR_PAPER)

            val margin = 72f
            var y = margin

            // ── 標題（閱讀週報／閱讀月報）──
            val titlePaint = textPaint(64f, COLOR_INDIGO, bold = true)
            canvas.drawText(
                if (report.isWeek) AppContext.ctx.getString(R.string.report_week)
                else AppContext.ctx.getString(R.string.report_month),
                margin, y + 64f, titlePaint
            )
            y += 110f

            // ── 期間行（report_period_fmt：MM/dd ～ MM/dd）──
            val periodPaint = textPaint(30f, COLOR_INDIGO_SOFT)
            val periodText = AppContext.ctx.getString(
                R.string.report_period_fmt,
                shortDateOf(report.period.startKey), shortDateOf(report.period.endKey)
            )
            canvas.drawText(periodText, margin, y + 30f, periodPaint)
            y += 86f

            // ── 三宮格大數字：時長／字數／活躍天 ──
            val gridTop = y
            val gridBottom = y + 250f
            val colW = (CARD_WIDTH - margin * 2f) / 3f
            val stats = listOf(
                Triple(
                    ReadStatisticsManager.formatDuration(report.minutes),
                    AppContext.ctx.getString(R.string.report_col_duration),
                    COLOR_INDIGO
                ),
                Triple(
                    ReadStatisticsManager.formatWords(report.words),
                    AppContext.ctx.getString(R.string.report_col_words),
                    COLOR_INDIGO
                ),
                Triple(
                    AppContext.ctx.getString(R.string.report_col_days_fmt, report.activeDays),
                    AppContext.ctx.getString(R.string.report_col_days),
                    COLOR_VERMILION
                )
            )
            stats.forEachIndexed { index, (value, label, color) ->
                val cx = margin + colW * index + colW / 2f
                val valuePaint = textPaint(46f, color, bold = true)
                valuePaint.textAlign = Paint.Align.CENTER
                canvas.drawText(ellipsize(value, 12), cx, gridTop + 74f, valuePaint)
                val labelPaint = textPaint(26f, COLOR_INDIGO_SOFT)
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, cx, gridTop + 126f, labelPaint)
            }
            y = gridBottom + 40f

            // ── 日閱讀量長條圖（週 7 柱／月 30 柱）──
            val chartTitlePaint = textPaint(30f, COLOR_INDIGO, bold = true)
            canvas.drawText(
                AppContext.ctx.getString(R.string.report_chart_daily), margin, y + 30f, chartTitlePaint
            )
            y += 66f

            val chartTop = y
            val chartBottom = chartTop + 380f
            val chartLeft = margin
            val chartRight = CARD_WIDTH - margin
            drawBarChart(canvas, report, chartLeft, chartTop, chartRight, chartBottom)
            y = chartBottom + 70f

            // ── 註腳：最長的一天／日均 ──
            val footLines = buildList {
                report.bestDayText?.let { add(it) }
                if (report.avgMinutesPerActiveDay > 0L) {
                    add(
                        AppContext.ctx.getString(
                            R.string.report_avg_day,
                            ReadStatisticsManager.formatDuration(report.avgMinutesPerActiveDay)
                        )
                    )
                }
                if (report.finishedCount > 0) {
                    add(
                        AppContext.ctx.getString(
                            R.string.report_finished_fmt, report.finishedCount
                        )
                    )
                }
            }
            for (line in footLines.take(3)) {
                y = drawWrappedText(canvas, line, margin, y, CARD_WIDTH - margin * 2f, 30f, COLOR_INDIGO) + 26f
            }

            // ── 底部：框線＋品牌 ──
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_CARD_LINE
                strokeWidth = 3f
            }
            canvas.drawLine(margin, CARD_HEIGHT - 130f, CARD_WIDTH - margin, CARD_HEIGHT - 130f, linePaint)
            val brandPaint = textPaint(26f, COLOR_INDIGO_SOFT)
            canvas.drawText(
                AppContext.ctx.getString(R.string.report_brand),
                margin, CARD_HEIGHT - 84f, brandPaint
            )
            val datePaint = textPaint(26f, COLOR_INDIGO_SOFT)
            datePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).format(Date()),
                CARD_WIDTH - margin, CARD_HEIGHT - 84f, datePaint
            )

            // ── 落檔 ──
            val dir = File(AppContext.ctx.filesDir, REPORTS_DIR).apply { mkdirs() }
            val kind = if (report.isWeek) "week" else "month"
            val file = File(dir, "k_reader_report_${kind}_${report.period.endKey}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            Log.i(TAG, "報表卡繪製成功：${file.name}")
            file
        }.getOrElse { e ->
            Log.w(TAG, "報表卡繪製失敗", e)
            null
        }
    }

    /** 長條圖：零填空柱體＋最高柱朱砂點睛＋日期軸（首末＋峰值標註） */
    private fun drawBarChart(
        canvas: Canvas,
        report: ReportData,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val bars = report.dailyBars
        if (bars.isEmpty()) return
        val maxMinutes = (bars.maxOfOrNull { it.minutes } ?: 0L).coerceAtLeast(1L)
        val gap = 14f
        val barW = ((right - left) - gap * (bars.size - 1)) / bars.size
        val usableH = bottom - top - 34f   // 底部留日期軸高度

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_INDIGO }
        val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_VERMILION }
        val axisPaint = textPaint(20f, COLOR_INDIGO_SOFT)

        val peakIndex = bars.indices.maxByOrNull { bars[it].minutes } ?: -1
        bars.forEachIndexed { index, bar ->
            val h = (bar.minutes.toFloat() / maxMinutes) * usableH
            val rectLeft = left + index * (barW + gap)
            val rect = RectF(rectLeft, bottom - 34f - h, rectLeft + barW, bottom - 34f)
            canvas.drawRoundRect(rect, 6f, 6f, if (index == peakIndex) hiPaint else barPaint)
        }
        // 日期軸：首末二柱標 MM/dd（柱多時僅標兩端）
        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(shortDateOf(bars.first().dateKey), left, bottom, axisPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(shortDateOf(bars.last().dateKey), right, bottom, axisPaint)
    }

    /** StaticLayout 換行繪製（超寬自動折行不截字）；回傳繪製後底部 y */
    private fun drawWrappedText(
        canvas: Canvas, text: String, x: Float, topY: Float, maxWidth: Float,
        textSize: Float, color: Int
    ): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color
            isFakeBoldText = false
        }
        val layout = StaticLayout.Builder.obtain(
            text, 0, text.length, paint, maxWidth.toInt()
        ).setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .build()
        canvas.save()
        canvas.translate(x, topY)
        layout.draw(canvas)
        canvas.restore()
        return topY + layout.height
    }

    /** 報表卡分享（report_export_image 按鈕 → 系統分享面板） */
    fun shareCard(file: File): Intent {
        val uri = FileProvider.getUriForFile(AppContext.ctx, FILE_PROVIDER_AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 報表卡存入相簿（MediaStore——Q+ 走 RELATIVE_PATH Pictures/KReader；
     * Q 以下 legacy 路徑）。回 null＝失敗（report_exported_fmt 提示用 null 判定）。
     */
    fun saveCardToGallery(file: File): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$GALLERY_DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Images.Media.DATA,
                    legacyGalleryDir().absolutePath + File.separator + file.name
                )
            }
        }
        val resolver = AppContext.ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }.getOrElse { e ->
        Log.w(TAG, "報表卡存入相簿失敗", e)
        null
    }

    /** Q 以下 legacy 相簿目錄 */
    private fun legacyGalleryDir(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            ), GALLERY_DIR
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ═════════════════ 工具 ═════════════════

    private fun textPaint(size: Float, color: Int, bold: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            isFakeBoldText = bold
        }

    /** 超長截斷（三宮格數字最長 12 字元——「xx 小時 xx 分」安全邊際） */
    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /** 日期鍵 yyyy-MM-dd → MM/dd（軸與期間行用） */
    internal fun shortDateOf(dateKey: String): String = runCatching {
        val src = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
        SimpleDateFormat("MM/dd", Locale.TAIWAN).format(src ?: Date())
    }.getOrDefault(dateKey.takeLast(5))

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

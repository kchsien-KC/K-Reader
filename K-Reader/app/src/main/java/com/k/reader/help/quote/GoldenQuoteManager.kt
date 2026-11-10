package com.k.reader.help.quote

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 文青金句卡片管理器（十大旗艦功能：金句卡）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（把心動的一段，裱成一張卡）：
 *   長按選取段落劃線批註（quote_card_select_hint）之後，感動
 *   不該停在資料庫裡。本管理器把金句渲染為 1080×1620（2:3）
 *   的「青黛宣紙」風格卡片：
 *
 *    · 純程式繪製：Bitmap＋Canvas＋StaticLayout——零布局檔、
 *      零點陣素材，字級 54→34 自動收縮直到排得下；
 *    · 視覺語言：宣紙米白底＋墨色正文＋青黛飾件（與 Stage 1
 *      圖示提案 A 同源）；大號「引號開角起始，章節／書名作者
 *      收尾，右下角 K.閱讀＋應用標語浮水印；
 *    · 落地：MediaStore 寫入公共圖庫 Pictures/KReader/
 *      （quote_card_saved 文案），分享走 FileProvider（authority
 *      與 manifestPlaceholder com.k.reader.filen 對齊）；
 *    · 金句簿：劃線批註落在 filesDir/golden_quotes.json，
 *      可回看／複製／刪除——卡片只是它們的一種投影。
 *
 *  整合點：
 *   · 閱讀頁長按選取 → quote_card_generate 生成金句卡片
 *   · 金句簿列表 → quote_card_share 分享金句卡片
 *   · 批註（annotation）寫入 → saveQuote()
 */
object GoldenQuoteManager {

    private const val TAG = "GoldenQuoteManager"

    /** 卡片尺寸（規格：1080×1620——2:3 直式，社交平台通用） */
    const val CARD_WIDTH = 1080
    const val CARD_HEIGHT = 1620

    /** 單條金句字數上限（規格：400 字——卡片可承載的閱讀量） */
    const val MAX_QUOTE_CHARS = 400

    /** 金句簿檔名（filesDir 下） */
    private const val BOOK_FILE = "golden_quotes.json"

    /** 公開輸出目錄名（與本地備份同一家族：KReader/） */
    const val PUBLIC_DIR = "KReader"

    /** FileProvider authority（與 build.gradle manifestPlaceholder 對齊） */
    const val FILE_PROVIDER_AUTHORITY = "com.k.reader.filen"

    // ─── 青黛宣紙配色（與 Stage 1 圖示提案 A 同源） ───
    private const val PAPER = 0xFFFAF7EF
    private const val INK = 0xFF2E2C28
    private const val INDIGO = 0xFF2B4A55
    private const val INK_SOFT = 0xFF8A857B

    private val gson = Gson()

    /** 金句條目 */
    data class GoldenQuote(
        var id: Long,
        var bookName: String,
        var author: String?,
        var chapterTitle: String?,
        var content: String,
        var createdAt: Long
    ) {
        companion object {
            fun fromJson(json: String): GoldenQuote? = runCatching {
                Gson().fromJson(json, GoldenQuote::class.java)
            }.getOrNull()
        }
    }

    // ═════════════════ 金句簿 CRUD ═════════════════

    /** 收藏一條金句（空白／超長自動整理；回寫入後的條目） */
    fun saveQuote(
        bookName: String,
        author: String?,
        chapterTitle: String?,
        content: String
    ): GoldenQuote? {
        val text = content.trim().take(MAX_QUOTE_CHARS)
        if (text.isEmpty() || bookName.isBlank()) return null
        val quote = GoldenQuote(
            id = System.currentTimeMillis(),
            bookName = bookName.trim(),
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            chapterTitle = chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
            content = text,
            createdAt = System.currentTimeMillis()
        )
        runCatching {
            bookFile().writeText(gson.toJson(listOf(quote) + loadQuotes()))
        }.onFailure { e ->
            Log.w(TAG, "金句簿寫入失敗", e)
            return null
        }
        return quote
    }

    /** 金句簿列表（新→舊） */
    fun allQuotes(): List<GoldenQuote> = loadQuotes()

    /** 刪除單條（金句簿——不入回收站：文字再複製即得，無還原價值） */
    fun deleteQuote(quoteId: Long): Boolean {
        val next = loadQuotes().filterNot { it.id == quoteId }
        return runCatching { bookFile().writeText(gson.toJson(next)) }.isSuccess
    }

    private fun loadQuotes(): List<GoldenQuote> = runCatching {
        val file = bookFile()
        if (file.isFile) {
            gson.fromJson(file.readText(), object : TypeToken<List<GoldenQuote>>() {}.type)
                as? List<GoldenQuote>
        } else null
    }.getOrNull().orEmpty()

    private fun bookFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ═════════════════ 卡片繪製（純程式構建） ═════════════════

    /**
     * 渲染金句卡（1080×1620，ARGB_8888）。
     * 字級自 54dp 起每輪縮 4，直至正文排入可用區——長句自動縮字
     * 而非截斷，完整保留使用者劃下的每個字。
     */
    fun generateCardBitmap(quote: GoldenQuote): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 宣紙底色（不透明）
        canvas.drawColor(PAPER.toInt())

        val margin = 96f
        val contentWidth = (CARD_WIDTH - margin * 2).toInt()

        // 青黛飾件：左上大引號「＋頂部細線
        val decorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INDIGO.toInt()
            textSize = 148f
            typeface = Typeface.SERIF
        }
        canvas.drawText("「", margin - 24f, margin + 148f, decorPaint)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INDIGO.toInt()
            strokeWidth = 3f
        }
        canvas.drawLine(margin, 72f, margin + 240f, 72f, rule)

        // 正文（StaticLayout 自動換行；字級收縮循環）
        val bodyTop = margin + 168f
        val footerHeight = 300f
        val bodyMaxHeight = CARD_HEIGHT - bodyTop - footerHeight
        var textSize = 54f
        var layout: StaticLayout
        while (true) {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK.toInt()
                this.textSize = textSize
                typeface = Typeface.SERIF
            }
            layout = StaticLayout(
                quote.content, textPaint, contentWidth,
                Layout.Alignment.ALIGN_NORMAL, 1.6f, 0f, false
            )
            if (layout.height <= bodyMaxHeight || textSize <= 34f) break
            textSize -= 4f
        }
        canvas.withTranslation(margin, bodyTop) { drawLayout(layout) }

        // 頁腳：章節（淡墨）＋書名・作者（墨色）＋分隔細線
        var footerY = CARD_HEIGHT - footerHeight + 48f
        quote.chapterTitle?.let {
            val chapterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK_SOFT.toInt()
                textSize = 36f
                typeface = Typeface.SERIF
            }
            canvas.drawText(it, margin, footerY, chapterPaint)
            footerY += 72f
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK.toInt()
            textSize = 44f
            typeface = Typeface.SERIF
            isFakeBoldText = true
        }
        val meta = quote.author?.let { "${quote.bookName}・$it" } ?: quote.bookName
        canvas.drawText(meta, margin, footerY, metaPaint)
        canvas.drawLine(
            margin, CARD_HEIGHT - 168f, CARD_WIDTH - margin, CARD_HEIGHT - 168f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INDIGO.toInt(); strokeWidth = 2f }
        )

        // 右下浮水印：K.閱讀＋應用標語
        val mark = "K.閱讀"
        val slogan = AppContext.ctx.getString(R.string.app_slogan)
        val markPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INDIGO.toInt()
            textSize = 34f
            typeface = Typeface.SERIF
            isFakeBoldText = true
        }
        val sloganPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_SOFT.toInt()
            textSize = 28f
            typeface = Typeface.SERIF
        }
        canvas.drawText(mark, CARD_WIDTH - margin - markPaint.measureText(mark),
            CARD_HEIGHT - 108f, markPaint)
        canvas.drawText(slogan, CARD_WIDTH - margin - sloganPaint.measureText(slogan),
            CARD_HEIGHT - 66f, sloganPaint)

        return bitmap
    }

    /** Canvas 平移後繪製 StaticLayout 的輔助（避免巢狀閉包歧義） */
    private fun Canvas.withTranslation(x: Float, y: Float, block: Canvas.() -> Unit) {
        val checkpoint = save()
        translate(x, y)
        try {
            block()
        } finally {
            restoreToCount(checkpoint)
        }
    }

    private fun Canvas.drawLayout(layout: StaticLayout) {
        layout.draw(this)
    }

    // ═════════════════ 落地：圖庫／分享 ═════════════════

    /**
     * 卡片存入公共圖庫 Pictures/KReader/（quote_card_saved 文案）。
     * API 29+ 走 MediaStore（無需儲存權限）；舊版走公開目錄直寫
     * （需 WRITE_EXTERNAL_STORAGE，Stage 6 manifest 統一處理）。
     * 回傳可供顯示的路徑字串；失敗回 null。
     */
    suspend fun saveCardToGallery(quote: GoldenQuote): String? = withContext(Dispatchers.IO) {
        val bitmap = generateCardBitmap(quote)
        val name = "k_reader_quote_${quote.id}.png"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$PUBLIC_DIR"
                    )
                }
                val uri = AppContext.ctx.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                AppContext.ctx.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                "Pictures/$PUBLIC_DIR/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    PUBLIC_DIR
                )
                if (!dir.exists() && !dir.mkdirs()) return@runCatching null
                val file = File(dir, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file.absolutePath
            }
        }.onFailure { e ->
            Log.w(TAG, "金句卡存檔失敗", e)
        }.getOrNull()
    }

    /**
     * 分享金句卡：先落圖庫（quote_card_saved），再以 FileProvider
     * content URI 發送 ACTION_SEND（image/png）。
     */
    suspend fun shareCard(context: Context, quote: GoldenQuote): Boolean =
        withContext(Dispatchers.IO) {
            val saved = saveCardToGallery(quote) ?: return@withContext false
            val uri = mediaStoreUriOf(quote) ?: return@withContext false
            runCatching {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setClipData(
                        android.content.ClipData.newRawUri("image/png", uri)
                    )
                }
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.quote_card_share))
                )
                Log.i(TAG, "金句卡已分享：$saved")
            }.isSuccess
        }

    /** 依金句 id 找回 MediaStore content URI（分享用；舊版退 FileProvider） */
    private fun mediaStoreUriOf(quote: GoldenQuote): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            AppContext.ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media.DISPLAY_NAME}=?",
                arrayOf("k_reader_quote_${quote.id}.png"),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    return Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn).toString()
                    )
                }
            }
            null
        } else {
            @Suppress("DEPRECATION")
            val file = File(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    PUBLIC_DIR
                ),
                "k_reader_quote_${quote.id}.png"
            )
            if (file.isFile) {
                FileProvider.getUriForFile(AppContext.ctx, FILE_PROVIDER_AUTHORITY, file)
            } else null
        }
    }.getOrNull()

    // ═════════════════ UI 文案 ═════════════════

    fun cardTitle(): String = AppContext.ctx.getString(R.string.quote_card)

    fun generateLabel(): String = AppContext.ctx.getString(R.string.quote_card_generate)

    fun shareLabel(): String = AppContext.ctx.getString(R.string.quote_card_share)

    fun savedNotice(): String = AppContext.ctx.getString(R.string.quote_card_saved)

    fun selectHint(): String = AppContext.ctx.getString(R.string.quote_card_select_hint)
}

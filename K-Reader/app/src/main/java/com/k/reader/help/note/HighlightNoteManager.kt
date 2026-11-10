package com.k.reader.help.note

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.help.quote.GoldenQuoteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 劃線筆記本管理器（Stage 8 旗艦增益：劃線筆記）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（讀過的痕跡，值得被收藏與再利用）：
 *   閱讀的當下總有想說的話——劃線（quote_card_select_hint 選取
 *   範圍）只是起點。本管理器把每一道筆跡管理成三段式資產：
 *
 *    1. 劃線：章內字元範圍（startPos／endPos）＋內容快照＋三色
 *       分類（金句黃 QUOTE／疑問藍 QUESTION／重點紅 KEY）；
 *       書名作者＋bookUrl 雙鍵定位——換源不改書名，劃線跨源
 *       存活（與 ReadStatisticsManager 完本集同思路）；
 *    2. 批註：每條劃線可掛一段筆記（note）——疑問、感想、考證
 *       皆宜；dialog_note_editor 編輯，純主題屬性；
 *    3. 匯出：Markdown（# 書名 / ## 章節 / > 劃線 / 批註縮排）
 *       ＋純文字兩種格式，MediaStore 落 Downloads/KReader/
 *       （note_export_done 帶檔名回報）——與本地備份同一家族；
 *    4. 金句卡直達：toGoldenQuote() 一鍵把劃線餵 GoldenQuoteManager
 *       ——「裱成一張卡」不必重選字；
 *    5. 護欄：單條內容 800 字上限；全簿 500 條容量——滿了從最
 *       舊淘汰（與回收站容量紀律同款）。
 *
 *  整合點：
 *   · 閱讀頁長按選取 → saveHighlight()
 *   · 選取氣泡「筆記」 → dialog_note_editor 編輯批註＋三色
 *   · 選取氣泡「金句卡」→ toGoldenQuote()
 *   · 「我的」頁 note_title 列表 → allNotes()/notesFor()
 */
object HighlightNoteManager {

    private const val TAG = "HighlightNote"

    /** 劃線簿檔名（filesDir 下——與金句簿同家族） */
    private const val BOOK_FILE = "highlight_notes.json"

    /** 全簿容量上限（規格：500 條——滿了從最舊淘汰） */
    const val MAX_NOTES = 500

    /** 單條劃線內容上限（規格：800 字） */
    const val MAX_CONTENT_CHARS = 800

    /** 匯出目錄（與本地備份／金句卡同一家族：KReader/） */
    const val PUBLIC_DIR = "KReader"

    /** 三色分類：金句黃 */
    const val COLOR_QUOTE = "QUOTE"

    /** 三色分類：疑問藍 */
    const val COLOR_QUESTION = "QUESTION"

    /** 三色分類：重點紅 */
    const val COLOR_KEY = "KEY"

    private val gson = Gson()

    /** 劃線條目（章內字元範圍＋內容快照＋批註＋三色） */
    data class HighlightNote(
        var id: Long,
        var bookName: String,
        var author: String?,
        var bookUrl: String?,
        var chapterIndex: Int,
        var chapterTitle: String?,
        var startPos: Int,
        var endPos: Int,
        var content: String,
        var note: String?,
        var colorType: String,
        var createdAt: Long
    )

    // ═════════════════ 劃線 CRUD ═════════════════

    /**
     * 收藏一條劃線（內容空白／超長自動整理；新條目插最前）。
     * 回寫入後的條目（失敗回 null）。
     */
    fun saveHighlight(
        bookName: String,
        author: String?,
        bookUrl: String?,
        chapterIndex: Int,
        chapterTitle: String?,
        startPos: Int,
        endPos: Int,
        content: String,
        note: String? = null,
        colorType: String = COLOR_QUOTE
    ): HighlightNote? {
        val text = content.trim().take(MAX_CONTENT_CHARS)
        if (text.isEmpty() || bookName.isBlank()) return null
        if (endPos < startPos) return null
        val entry = HighlightNote(
            id = System.currentTimeMillis(),
            bookName = bookName.trim(),
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            bookUrl = bookUrl?.trim()?.takeIf { it.isNotEmpty() },
            chapterIndex = chapterIndex.coerceAtLeast(0),
            chapterTitle = chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
            startPos = startPos.coerceAtLeast(0),
            endPos = endPos,
            content = text,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            colorType = normalizeColor(colorType),
            createdAt = System.currentTimeMillis()
        )
        return runCatching {
            val merged = mutableListOf(entry) + loadNotes()
            persist(evictOverflow(merged))
            entry
        }.onFailure { e ->
            Log.w(TAG, "劃線簿寫入失敗", e)
        }.getOrNull()
    }

    /** 更新批註與顏色（id 不存在回 false） */
    fun updateNote(id: Long, note: String?, colorType: String): Boolean {
        val notes = loadNotes()
        val target = notes.firstOrNull { it.id == id } ?: return false
        target.note = note?.trim()?.takeIf { it.isNotEmpty() }
        target.colorType = normalizeColor(colorType)
        return runCatching { persist(notes); true }
            .onFailure { e -> Log.w(TAG, "劃線更新失敗", e) }
            .getOrDefault(false)
    }

    /** 刪除單條（回傳被刪條目——UI 端可做撤銷） */
    fun removeNote(id: Long): HighlightNote? {
        val notes = loadNotes()
        val target = notes.firstOrNull { it.id == id } ?: return null
        runCatching { persist(notes.filterNot { it.id == id }) }
            .onFailure { e -> Log.w(TAG, "劃線刪除失敗", e) }
        return target
    }

    /** 全簿（新到舊） */
    fun allNotes(): List<HighlightNote> = loadNotes()

    /** 單書劃線（bookUrl 缺失時退書名作者鍵比對） */
    fun notesFor(bookUrl: String?, bookName: String): List<HighlightNote> {
        val notes = loadNotes()
        return if (!bookUrl.isNullOrBlank()) {
            notes.filter { it.bookUrl == bookUrl }
        } else {
            val key = bookKey(bookName, null)
            notes.filter { bookKey(it.bookName, it.author) == key }
        }
    }

    /** 某章的劃線（閱讀頁渲染劃線底色用——startPos 升序） */
    fun notesForChapter(bookUrl: String?, bookName: String, chapterIndex: Int): List<HighlightNote> =
        notesFor(bookUrl, bookName)
            .filter { it.chapterIndex == chapterIndex }
            .sortedBy { it.startPos }

    // ═════════════════ 金句卡直達 ═════════════════

    /** 一鍵把劃線裱成金句卡（委託 GoldenQuoteManager.saveQuote） */
    fun toGoldenQuote(note: HighlightNote): Boolean =
        GoldenQuoteManager.saveQuote(
            bookName = note.bookName,
            author = note.author,
            chapterTitle = note.chapterTitle,
            content = note.content
        ) != null

    // ═════════════════ 匯出 ═════════════════

    /**
     * 匯出 Markdown（IO 執行緒）：# 書名 / ## 章節 / > 劃線 / 批註縮排。
     * MediaStore 落 Downloads/KReader/——回傳檔名（失敗 null）。
     */
    suspend fun exportMarkdown(bookName: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val notes = if (bookName.isNullOrBlank()) allNotes() else notesFor(null, bookName)
            if (notes.isEmpty()) return@withContext null
            val body = buildString {
                notes.groupBy { it.bookName }.forEach { (name, bookNotes) ->
                    append("# ").append(name).append('\n')
                    bookNotes.sortedWith(compareBy({ it.chapterIndex }, { it.startPos }))
                        .groupBy { it.chapterTitle ?: "" }
                        .forEach { (chapterTitle, chapterNotes) ->
                            append("\n## ").append(chapterTitle.ifBlank { "—" }).append('\n')
                            chapterNotes.forEach { n ->
                                append("\n> ").append(n.content.replace('\n', ' ')).append('\n')
                                n.note?.let { append(">> ").append(it.replace('\n', ' ')).append('\n') }
                            }
                        }
                    append('\n')
                }
            }
            writePublicFile("KReader_Notes.md", "text/markdown", body)
        }.onFailure { e ->
            Log.w(TAG, "Markdown 匯出失敗", e)
        }.getOrNull()?.let { "KReader_Notes.md" }
    }

    /** 匯出純文字（IO 執行緒）：書名【章節】劃線——批註格式，適合貼文分享 */
    suspend fun exportPlainText(bookName: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val notes = if (bookName.isNullOrBlank()) allNotes() else notesFor(null, bookName)
            if (notes.isEmpty()) return@withContext null
            val body = buildString {
                notes.forEach { n ->
                    append("【").append(n.bookName)
                    n.chapterTitle?.let { append("・").append(it) }
                    append("】").append(n.content.replace('\n', ' '))
                    n.note?.let { append("——").append(it.replace('\n', ' ')) }
                    append('\n')
                }
            }
            writePublicFile("KReader_Notes.txt", "text/plain", body)
        }.onFailure { e ->
            Log.w(TAG, "純文字匯出失敗", e)
        }.getOrNull()?.let { "KReader_Notes.txt" }
    }

    /** MediaStore 公共寫入（Android 10+ RELATIVE_PATH；舊機直寫 File） */
    private fun writePublicFile(fileName: String, mimeType: String, content: String): Uri? {
        val ctx = com.k.reader.app.AppContext.ctx
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_DIR)
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            Uri.fromFile(file)
        }
    }

    // ═════════════════ 持久化與工具 ═════════════════

    /** 容量護欄：超過 MAX_NOTES 從最舊淘汰（清單本身新到舊——砍尾巴） */
    private fun evictOverflow(notes: List<HighlightNote>): List<HighlightNote> =
        notes.take(MAX_NOTES)

    /** 顏色常規化（未知值退金句黃——髒資料不外溢） */
    private fun normalizeColor(colorType: String): String = when (colorType) {
        COLOR_QUESTION, COLOR_KEY -> colorType
        else -> COLOR_QUOTE
    }

    /** 書名作者鍵（與 ReadStatisticsManager.finishedKeyOf 同思路） */
    private fun bookKey(name: String, author: String?): String =
        "${name.trim()}|${author.orEmpty().trim()}"

    private fun notesFile(): File = File(com.k.reader.app.AppContext.ctx.filesDir, BOOK_FILE)

    private fun loadNotes(): List<HighlightNote> = runCatching {
        val file = notesFile()
        if (!file.exists()) return emptyList()
        gson.fromJson(file.readText(), object : TypeToken<List<HighlightNote>>() {}.type)
            as? List<HighlightNote>
    }.getOrNull() ?: emptyList()

    private fun persist(notes: List<HighlightNote>) {
        runCatching {
            notesFile().writeText(gson.toJson(notes))
        }.onFailure { e ->
            Log.w(TAG, "劃線簿持久化失敗", e)
        }
    }
}

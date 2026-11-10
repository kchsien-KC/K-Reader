package com.k.reader.help.book

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.k.reader.R
import com.k.reader.app.AppContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 本地書籍匯入器（Stage 10：本地書匯入）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（手邊的 TXT，值得被溫柔對待）：
 *   台灣讀者手裡常躺著一批早年下載的 TXT——Big5 與 UTF-8 混雜、
 *   GB 簡體書檔也時有所聞。匯入器把三件事做到穩：
 *
 *    1. 編碼：先認 BOM（UTF-8／UTF-16 LE／BE 三種），無 BOM 時
 *       走候選評分——UTF-8 嚴格解碼（非法序列即淘汰）→ Big5 →
 *       GB18030，以高頻字元命中計分＋替換字元 U+FFFD 重扣；
 *       評分最高者勝出，同分取 Big5（台灣場景先驗）；
 *    2. 切章：行首錨定＋獨立短行（≤ 40 字元）雙約束的章節正則，
 *       涵蓋「第X卷／章／節／回／集／部／篇」（繁簡數字並寫）、
 *       「Chapter N」「序章／楔子／正文／尾聲／後記／終章」；
 *       偽章（低於 80 字元）向前合併；一個章都沒有時全文單章；
 *    3. 落庫：全檔 SHA-256 前 16 碼為身分證——同一檔案重複匯入
 *       直接回既有書籍（冪等）；chapters/NNN.txt 三位章號（字典
 *       序即章序，與離線快取同一紀律）；book.json 記書名、作者、
 *       章節目錄與匯入時間。偵測簡體比例過半可順手簡轉繁
 *       （TraditionalChineseHelper 整合點，預設關閉零打擾）。
 *
 *  規格值：單檔上限 50MB；章數上限 8000；目錄 filesDir/local_books。
 *
 *  整合點：
 *   · 書架「匯入本地書」（SAF ACTION_OPEN_DOCUMENT）→ importFromUri
 *   · 檔案總管第三方呼入（File path）→ importFromFile
 *   · TraditionalChineseHelper.convertToTraditional —— 可選簡轉繁
 *   · 書架長按刪除 → deleteBook；listBooks／readChapter 供書架與閱讀頁
 */
object LocalBookImporter {

    private const val TAG = "LocalBookImporter"

    private const val BOOKS_ROOT = "local_books"
    private const val BOOK_META = "book.json"
    private const val CHAPTERS_DIR = "chapters"

    /** 單檔大小上限（規格：50MB） */
    const val MAX_FILE_BYTES = 50L * 1024L * 1024L

    /** 章節數上限（規格：8000 章） */
    const val MAX_CHAPTERS = 8000

    /** 偽章門檻（規格：低於 80 字元視為切分誤傷，向前合併） */
    const val CHAPTER_MIN_CHARS = 80

    /** 章節標題行長上限（規格：40 字元——超長即非標題） */
    private const val TITLE_LINE_MAX = 40

    /** 高頻字元計分窗口（規格：前 20,000 字元） */
    private const val SCORE_WINDOW = 20_000

    /** 簡轉繁建議門檻（規格：命中簡體特徵字 ≥ 30% 窗口字元） */
    const val SIMPLIFIED_HINT_RATIO = 0.30f

    private val gson = Gson()

    // ═════════════════ 書籍模型 ═════════════════

    /** 本地書籍帳冊（book.json） */
    data class LocalBook(
        val bookId: String,          // SHA-256 前 16 碼（身分證）
        val name: String,
        val author: String,
        val charset: String,         // 偵測出的編碼名（帳冊留底）
        val importedAt: Long,
        val totalChars: Long,
        val chapters: List<ChapterMeta>
    )

    /** 章節目錄項 */
    data class ChapterMeta(val index: Int, val title: String)

    /** 匯入結果 */
    sealed class ImportResult {
        data class Success(val book: LocalBook, val simplifiedConverted: Boolean) : ImportResult()
        data class Duplicate(val book: LocalBook) : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    // ═════════════════ 對外入口 ═════════════════

    /**
     * SAF 匯入（ACTION_OPEN_DOCUMENT 取得的 content Uri）。
     * 主流程與 importFromFile 同源：讀全檔 → 偵測 → 切章 → 落庫。
     */
    fun importFromUri(uri: Uri, fallbackName: String?, convertToTraditional: Boolean = false): ImportResult {
        return runCatching {
            AppContext.ctx.contentResolver.openInputStream(uri)?.use { stream ->
                doImport(stream, fallbackName ?: "local_book", convertToTraditional)
            } ?: ImportResult.Failure(AppContext.ctx.getString(R.string.local_invalid_file))
        }.getOrElse { e ->
            Log.w(TAG, "SAF 匯入失敗", e)
            ImportResult.Failure(e.message ?: "IO")
        }
    }

    /** 檔案路徑匯入（第三方檔案總管／本機檔案） */
    fun importFromFile(path: File, convertToTraditional: Boolean = false): ImportResult {
        if (!path.isFile) {
            return ImportResult.Failure(AppContext.ctx.getString(R.string.local_invalid_file))
        }
        if (path.length() > MAX_FILE_BYTES) {
            return ImportResult.Failure(
                AppContext.ctx.getString(R.string.local_too_big, formatBytes(MAX_FILE_BYTES))
            )
        }
        return runCatching {
            path.inputStream().use { stream ->
                doImport(stream, path.nameWithoutExtension, convertToTraditional)
            }
        }.getOrElse { e ->
            Log.w(TAG, "檔案匯入失敗", e)
            ImportResult.Failure(e.message ?: "IO")
        }
    }

    /** 已匯入的本地書清單（書架整合點：匯入時間升序） */
    fun listBooks(): List<LocalBook> {
        val out = mutableListOf<LocalBook>()
        booksRoot().listFiles()?.forEach { dir ->
            val meta = File(dir, BOOK_META)
            if (meta.isFile) {
                runCatching {
                    gson.fromJson(meta.readText(Charsets.UTF_8), LocalBook::class.java)
                }.getOrNull()?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.importedAt }
    }

    /** 讀單章正文（閱讀頁整合點：與離線快取同樣的檔案粒度） */
    fun readChapter(bookId: String, chapterIndex: Int): String? = runCatching {
        chapterFileOf(bookId, chapterIndex).readText(Charsets.UTF_8)
    }.getOrNull()

    /** 刪除本地書（清整棵書目錄；冪等——不存在回 false） */
    fun deleteBook(bookId: String): Boolean {
        val dir = booksRoot().listFiles()?.firstOrNull { it.name == bookId } ?: return false
        val name = runCatching {
            gson.fromJson(File(dir, BOOK_META).readText(Charsets.UTF_8), LocalBook::class.java)?.name
        }.getOrNull()
        val deleted = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (deleted) {
            Log.i(TAG, "已刪除本地書 ${name ?: bookId}")
        }
        return deleted
    }

    // ═════════════════ 編碼偵測 ═════════════════

    /** 偵測結果：編碼名＋解碼後全文 */
    data class DecodedText(val charset: String, val text: String)

    /**
     * 編碼智慧偵測（核心）：
     * BOM → 嚴格 UTF-8 → Big5／GB18030 候選評分。
     * 評分＝高頻字元命中數 − 4 × U+FFFD 替換數；同分取 Big5
     * （台灣場景先驗：本地檔 Big5 機率高於 GB——候選序即優先序）。
     */
    internal fun detectAndDecode(bytes: ByteArray): DecodedText? {
        // 1. BOM 快速通道
        bomCharsetOf(bytes)?.let { name ->
            val text = String(bytes, charset(name))
            return DecodedText(name, text)
        }
        // 2. 嚴格 UTF-8：任何非法位元組序列即淘汰
        if (isValidUtf8(bytes)) {
            return DecodedText("UTF-8", String(bytes, Charsets.UTF_8))
        }
        // 3. 候選評分（Big5 在前＝同分優先）
        var best: DecodedText? = null
        var bestScore = Int.MIN_VALUE
        for (name in listOf("Big5", "GB18030")) {
            val text = runCatching { String(bytes, charset(name)) }.getOrNull() ?: continue
            val score = scoreText(text)
            if (score > bestScore) {
                bestScore = score
                best = DecodedText(name, text)
            }
        }
        return best
    }

    /** BOM 偵測（UTF-8／UTF-16 LE／UTF-16 BE） */
    private fun bomCharsetOf(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        return null
    }

    /** 嚴格 UTF-8 驗證（前導位元組＋續位元組 10xx xxxx 逐組檢查） */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val len = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false
            }
            if (i + len > bytes.size) return false
            for (j in 1 until len) {
                if (bytes[i + j].toInt() and 0xC0 != 0x80) return false
            }
            i += len
        }
        return true
    }

    /** 中文字高頻字元表（評分用——繁簡兩側高頻字各半） */
    private val FREQ_CHARS = setOf(
        '的', '是', '不', '了', '在', '人', '有', '我', '他', '這',
        '個', '上', '們', '來', '到', '時', '地', '為', '和', '就',
        '那', '要', '你', '看', '說', '著', '沒', '過', '下', '能',
        '子', '裡', '年', '中', '會', '好', '她', '得', '一', '二',
        '三', '麼', '去', '對', '生', '心', '用', '道', '行', '方',
        '前', '所', '見', '經', '發', '成', '回', '可', '同', '天',
        '事', '字', '自', '只', '出', '想', '日', '手', '別', '讓',
        '走', '力', '口', '十', '王', '本', '文', '白', '大', '小'
    )

    /** 簡體特徵字（簡轉繁建議偵測用——繁體文本極少命中） */
    private val SIMPLIFIED_MARKERS = setOf(
        '们', '这', '边', '说', '对', '发', '时', '过', '还', '进',
        '东', '车', '乐', '习', '书', '写', '见', '觉', '风', '飞',
        '马', '鸟', '门', '齐', '气', '钱', '区', '让', '识', '术'
    )

    /** 評分：高頻字命中 − 4 × 替換字元（U+FFFD——Big5 解 GB 檔的典型症狀） */
    private fun scoreText(text: String): Int {
        val window = if (text.length > SCORE_WINDOW) text.substring(0, SCORE_WINDOW) else text
        var hits = 0
        var replacements = 0
        for (ch in window) {
            if (ch == '�') replacements++
            if (ch in FREQ_CHARS) hits++
        }
        return hits - replacements * 4
    }

    // ═════════════════ 章節切分 ═════════════════

    /**
     * 章節標題正則（整行匹配）：
     * 涵蓋 第X卷/章/節/回/集/部/篇（繁簡數字）、Chapter N、
     * 序章/楔子/正文/尾聲/後記/終章/番外（繁簡並寫）。
     */
    private val CHAPTER_TITLE_PATTERN: Pattern = Pattern.compile(
        "[\\s\\u3000]{0,4}(?:" +
            "(?:第)[零一二三四五六七八九十百千萬万兩两0-9]{1,12}" +
            "(?:卷|章|節|节|回|集|部|篇|冊|册)(?:[\\s\\u3000]|之|：|:|-|—)?.{0,24}" +
            "|(?:[Cc]hapter|CHAPTER)[\\s]*\\d{1,5}.{0,24}" +
            "|(?:序章|序幕|序言|楔子|引子|前言|正文|尾聲|尾声|後記|后记" +
            "|終章|终章|番外|終卷|终卷)(?:[\\s\\u3000]|：|:)?.{0,24}" +
            ")"
    )

    /** 切分結果 */
    data class SplittedChapter(val title: String, val body: String)

    /**
     * 章節切分（核心）：
     * 逐行掃描，標題行（正則命中＋行長 ≤ 40）立樁；開場無標題
     * 的導言暫存、終局併入首章；偽章（低於 80 字元）向前合併；
     * 零命中時全文自成單章。
     */
    internal fun splitChapters(fullText: String): List<SplittedChapter> {
        val chapters = mutableListOf<SplittedChapter>()
        var prologue: String? = null
        var currentTitle: String? = null
        val body = StringBuilder()

        fun flush() {
            val title = currentTitle
            val text = body.toString().trim()
            if (title != null) {
                if (text.isNotEmpty()) {
                    chapters.add(SplittedChapter(title, text))
                }
                // 標題獨行無內容——視為切分誤傷，丟棄
            } else if (text.isNotEmpty()) {
                prologue = text   // 開場導言：終局併入首章
            }
            body.setLength(0)
        }

        for (rawLine in fullText.split('\n')) {
            val trimmed = rawLine.trim()
            if (isChapterTitle(trimmed)) {
                flush()
                currentTitle = trimmed
            } else {
                body.append(rawLine).append('\n')
            }
        }
        flush()
        return finalizeChapters(chapters, prologue)
    }

    /** 標題判定：正則命中＋行長約束 */
    private fun isChapterTitle(line: String): Boolean {
        if (line.isEmpty() || line.length > TITLE_LINE_MAX) return false
        return CHAPTER_TITLE_PATTERN.matcher(line).matches()
    }

    /** 導言歸位＋偽章合併＋章數夾擊 */
    private fun finalizeChapters(
        raw: List<SplittedChapter>,
        prologue: String?
    ): List<SplittedChapter> {
        val merged = mutableListOf<SplittedChapter>()
        for (chapter in raw) {
            val last = merged.lastOrNull()
            if (last != null && chapter.body.length < CHAPTER_MIN_CHARS) {
                // 偽章向前合併：保留標題於內文，不落失資訊
                merged[merged.size - 1] = last.copy(
                    body = last.body + "\n\n" + chapter.title + "\n" + chapter.body
                )
            } else {
                merged.add(chapter)
            }
        }
        when {
            merged.isEmpty() && prologue != null ->
                // 零命中：全文單章（local_fulltext_chapter）
                merged.add(
                    SplittedChapter(
                        AppContext.ctx.getString(R.string.local_fulltext_chapter),
                        prologue
                    )
                )
            prologue != null -> {
                val first = merged.removeAt(0)
                merged.add(0, first.copy(body = prologue + "\n\n" + first.body))
            }
        }
        return merged.take(MAX_CHAPTERS)
    }

    // ═════════════════ 匯入主流程 ═════════════════

    private fun doImport(
        stream: InputStream,
        fallbackName: String,
        convertToTraditional: Boolean
    ): ImportResult {
        val bytes = readAll(stream)
        if (bytes.isEmpty()) {
            return ImportResult.Failure(AppContext.ctx.getString(R.string.local_empty_file))
        }
        if (bytes.size > MAX_FILE_BYTES) {
            return ImportResult.Failure(
                AppContext.ctx.getString(R.string.local_too_big, formatBytes(MAX_FILE_BYTES))
            )
        }
        val decoded = detectAndDecode(bytes)
            ?: return ImportResult.Failure(AppContext.ctx.getString(R.string.local_invalid_file))

        var text = decoded.text
        val simplifiedConverted = if (convertToTraditional && isSimplifiedLikely(text)) {
            convertSimplified(text)?.let { converted ->
                if (converted != text) {
                    text = converted
                    true
                } else false
            } ?: false
        } else false

        val bookId = sha256Prefix(bytes, 16)
        // 冪等：同一檔案（位元組級相同）重複匯入直接回既有
        findExisting(bookId)?.let { return ImportResult.Duplicate(it) }

        val chapters = splitChapters(text)
        if (chapters.isEmpty()) {
            return ImportResult.Failure(AppContext.ctx.getString(R.string.local_empty_file))
        }

        // 落庫：chapters/NNN.txt（三位章號，字典序即章序）
        val bookDir = File(booksRoot(), bookId).apply { mkdirs() }
        val chapterDir = File(bookDir, CHAPTERS_DIR).apply { mkdirs() }
        chapters.forEachIndexed { index, chapter ->
            File(chapterDir, chapterFileName(index)).writeText(chapter.body, Charsets.UTF_8)
        }
        val book = LocalBook(
            bookId = bookId,
            name = guessBookName(text, fallbackName),
            author = guessAuthor(text),
            charset = decoded.charset,
            importedAt = System.currentTimeMillis(),
            totalChars = text.length.toLong(),
            chapters = chapters.mapIndexed { index, chapter -> ChapterMeta(index, chapter.title) }
        )
        File(bookDir, BOOK_META).writeText(gson.toJson(book), Charsets.UTF_8)
        Log.i(TAG, "本地書匯入成功：${book.name}（${chapters.size} 章／${decoded.charset}）")
        return ImportResult.Success(book, simplifiedConverted)
    }

    /** 簡體特徵偵測（窗口內命中比例 ≥ 30% 才建議轉換） */
    internal fun isSimplifiedLikely(text: String): Boolean {
        val window = if (text.length > SCORE_WINDOW) text.substring(0, SCORE_WINDOW) else text
        if (window.isEmpty()) return false
        var hits = 0
        for (ch in window) if (ch in SIMPLIFIED_MARKERS) hits++
        return hits.toFloat() / window.length >= SIMPLIFIED_HINT_RATIO
    }

    /** 簡轉繁（TraditionalChineseHelper 整合點；反射呼叫保持模組鬆耦合） */
    private fun convertSimplified(text: String): String? = runCatching {
        val helper = Class.forName("com.k.reader.help.TraditionalChineseHelper")
        val method = helper.getMethod("convertToTraditional", String::class.java)
        method.invoke(null, text) as? String
    }.getOrNull()

    // ═════════════════ 檔名／書名／作者推導 ═════════════════

    /** 書名推導：首行像書名（短、無冒號）優先，否則用檔名 */
    private fun guessBookName(text: String, fallbackName: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.length in 2..TITLE_LINE_MAX &&
            !firstLine.contains("：") && !firstLine.contains(":")
        ) {
            return firstLine
        }
        return fallbackName.trim().ifEmpty {
            AppContext.ctx.getString(R.string.local_fulltext_chapter)
        }
    }

    /** 作者推導：掃前 50 行找「作者：」前綴（繁簡並寫） */
    private fun guessAuthor(text: String): String {
        for (line in text.lineSequence().take(50)) {
            val trimmed = line.trim()
            for (prefix in listOf("作者：", "作者:", "作者 ")) {
                if (trimmed.startsWith(prefix) && trimmed.length > prefix.length) {
                    return trimmed.removePrefix(prefix).trim().take(24)
                }
            }
        }
        return ""
    }

    private fun chapterFileName(index: Int): String =
        String.format(Locale.US, "%03d.txt", index)

    private fun chapterFileOf(bookId: String, index: Int): File =
        File(File(File(booksRoot(), bookId), CHAPTERS_DIR), chapterFileName(index))

    private fun booksRoot(): File =
        File(AppContext.ctx.filesDir, BOOKS_ROOT).apply { mkdirs() }

    private fun findExisting(bookId: String): LocalBook? {
        val meta = File(File(booksRoot(), bookId), BOOK_META)
        if (!meta.isFile) return null
        return runCatching {
            gson.fromJson(meta.readText(Charsets.UTF_8), LocalBook::class.java)
        }.getOrNull()
    }

    // ═════════════════ 工具 ═════════════════

    private fun readAll(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sha256Prefix(bytes: ByteArray, length: Int): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }
            .take(length)

    /** 檔案大小格式化（local_too_big 參數用） */
    fun formatBytes(bytes: Long): String =
        String.format(Locale.TAIWAN, "%.0fMB", bytes / (1024.0 * 1024.0))

    /** 匯入時間格式化（repo_last_check 同款格式） */
    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(millis))

    private fun charset(name: String): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}

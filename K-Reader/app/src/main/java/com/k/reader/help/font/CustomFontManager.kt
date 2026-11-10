package com.k.reader.help.font

import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 自訂字體管理器（Stage 9 旗艦增益：自訂字體）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（字體是閱讀的口音）：
 *   同一段文字，明體讀來沉靜、黑體讀來爽利——字體是排版之外
 *   最貼身的閱讀體驗。本管理器把外來字體檔管理成可套用的資產：
 *
 *    1. 匯入：SAF Uri（使用者從檔案總管挑選）串流複製進
 *       filesDir/fonts/——先驗魔術位元組（TTF 0x00010000／
 *       OTF "OTTO"），拒收偽裝成字體的雜檔（font_invalid）；
 *       複製途中執行 30MB 上限夾擊——超限即棄（font_too_big）；
 *    2. 清單：fonts_index.json 記帳（名稱／檔名／體積）——
 *       上限 20 款（font_full）；list() 順手剔除已被系統清理
 *       工具誤刪的檔案（索引與檔案系統對帳）；
 *    3. 套用：apply() 把字體 id 落偏好檔——閱讀頁 TextView 以
 *       typefaceOf() 取 Typeface.createFromFile；revert() 一鍵
 *       回系統預設（與台灣排版規範相容——字體換、禁則不換）；
 *    4. 刪除：delete() 檔案＋索引＋（若套用中）自動 revert——
 *       套用中的字體被刪不留下懸空引用。
 *
 *  整合點：
 *   · 設定「字體管理」 → dialog_font_manager（匯入／清單／套用）
 *   · 閱讀頁文字渲染 → typefaceOf(appliedFont())
 *   · 排版管線（TextLayoutHelper）字體在其下游——互不干擾
 */
object CustomFontManager {

    private const val TAG = "CustomFont"

    /** 字體倉目錄（filesDir 下——應用私有） */
    private const val FONTS_DIR = "fonts"

    /** 字體索引檔（記帳：名稱／檔名／體積） */
    private const val INDEX_FILE = "fonts_index.json"

    /** 偏好檔（與 EInkHelper／睡眠定時同一家族：k_reader_config） */
    private const val PREFS_NAME = "k_reader_config"

    private const val KEY_APPLIED_ID = "applied_font_id"

    /** 字體容量上限（規格：20 款——字體倉不是字體博物院） */
    const val MAX_FONTS = 20

    /** 單檔體積上限（規格：30MB——超過的字體請自行重製子集） */
    const val MAX_FONT_MB = 30

    private val MB_BYTES = 1024L * 1024L

    private val gson = Gson()

    /** 字體條目（索引記帳——檔名即磁碟事實） */
    data class FontEntry(
        var id: Long,
        var name: String,
        var fileName: String,
        var bytes: Long
    )

    // ═════════════════ 匯入 ═════════════════

    /**
     * 從 SAF Uri 匯入字體（IO 執行緒）：
     * 魔術位元組驗體（TTF／OTF）→ 容量檢查 → 串流複製（途中
     * 30MB 夾擊）→ 索引記帳。任何一關不過回 null（UI 依失敗
     * 原因提示），不留半套檔案。
     */
    suspend fun importFont(uri: Uri, displayName: String? = null): FontEntry? =
        withContext(Dispatchers.IO) {
            runCatching {
                val ctx = AppContext.ctx
                val stream = ctx.contentResolver.openInputStream(uri) ?: return@withContext null
                stream.use { input ->
                    // ── 魔術位元組驗體：TTF（00 01 00 00）或 OTF（OTTO）──
                    val magic = ByteArray(4)
                    var read = 0
                    while (read < 4) {
                        val n = input.read(magic, read, 4 - read)
                        if (n < 0) return@withContext null
                        read += n
                    }
                    val isTtf = magic[0] == 0x00.toByte() && magic[1] == 0x01.toByte() &&
                        magic[2] == 0x00.toByte() && magic[3] == 0x00.toByte()
                    val isOtf = magic[0] == 'O'.code.toByte() && magic[1] == 'T'.code.toByte() &&
                        magic[2] == 'T'.code.toByte() && magic[3] == 'O'.code.toByte()
                    if (!isTtf && !isOtf) {
                        Log.w(TAG, "非字體檔案——魔術位元組不符，拒收")
                        return@withContext null
                    }
                    // ── 容量護欄：款數上限 ──
                    if (liveIndex().size >= MAX_FONTS) return@withContext null
                    // ── 落地：邊複製邊夾擊 30MB ──
                    val dir = fontsDir()
                    if (!dir.exists() && !dir.mkdirs()) return@withContext null
                    val id = System.currentTimeMillis()
                    val ext = if (isOtf) "otf" else "ttf"
                    val fileName = "font_${id}.${ext}"
                    val file = File(dir, fileName)
                    val capBytes = MAX_FONT_MB * MB_BYTES
                    var copied = 0L
                    file.outputStream().use { output ->
                        output.write(magic)
                        copied += 4
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            if (copied > capBytes) {
                                output.flush()
                                throw IllegalStateException("字體超過 ${MAX_FONT_MB}MB 上限")
                            }
                            output.write(buf, 0, n)
                        }
                        output.flush()
                    }
                    val entry = FontEntry(
                        id = id,
                        name = (displayName?.trim()?.takeIf { it.isNotEmpty() }
                            ?: file.nameWithoutExtension.removePrefix("font_")),
                        fileName = fileName,
                        bytes = file.length()
                    )
                    val index = liveIndex()
                    persistIndex(index + entry)
                    entry
                }
            }.onFailure { e ->
                Log.w(TAG, "字體匯入失敗", e)
                // 不留半套檔案：清掉可能的殘檔
                sweepOrphanFiles()
            }.getOrNull()
        }

    // ═════════════════ 清單與套用 ═════════════════

    /** 字體清單（索引與檔案系統對帳——被誤刪的檔案自動除帳） */
    fun list(): List<FontEntry> = liveIndex()

    /** 目前套用中的字體（未套用回 null） */
    fun appliedFont(): FontEntry? {
        val appliedId = prefs().getLong(KEY_APPLIED_ID, -1L)
        if (appliedId <= 0L) return null
        return liveIndex().firstOrNull { it.id == appliedId }
    }

    /** 套用字體（id 不存在回 false） */
    fun apply(id: Long): Boolean {
        val entry = liveIndex().firstOrNull { it.id == id } ?: return false
        prefs().edit().putLong(KEY_APPLIED_ID, id).apply()
        return true
    }

    /** 還原系統預設字體 */
    fun revert() {
        prefs().edit().remove(KEY_APPLIED_ID).apply()
    }

    /** 取 Typeface（檔案遺失回 null——渲染端退回系統預設） */
    fun typefaceOf(entry: FontEntry): Typeface? =
        runCatching { Typeface.createFromFile(File(fontsDir(), entry.fileName)) }
            .onFailure { e -> Log.w(TAG, "字體載入失敗：${entry.name}", e) }
            .getOrNull()

    /** 刪除字體（套用中先自動 revert——不留懸空引用） */
    fun delete(id: Long): Boolean {
        val index = liveIndex()
        val target = index.firstOrNull { it.id == id } ?: return false
        runCatching {
            File(fontsDir(), target.fileName).delete()
            persistIndex(index.filterNot { it.id == id })
            if (appliedFont()?.id == id) revert()
            true
        }.onFailure { e ->
            Log.w(TAG, "字體刪除失敗", e)
        }.getOrDefault(false)
    }

    // ═════════════════ 文案 ═════════════════

    fun title(): String = AppContext.ctx.getString(R.string.font_title)

    fun hintLabel(): String =
        AppContext.ctx.getString(R.string.font_hint, formatSize(MAX_FONT_MB * MB_BYTES))

    fun importLabel(): String = AppContext.ctx.getString(R.string.font_import)

    fun revertLabel(): String = AppContext.ctx.getString(R.string.font_revert)

    fun applyLabel(): String = AppContext.ctx.getString(R.string.font_apply)

    fun appliedNotice(name: String): String =
        AppContext.ctx.getString(R.string.font_applied_fmt, name)

    fun revertedNotice(): String = AppContext.ctx.getString(R.string.font_reverted)

    fun emptyHint(): String = AppContext.ctx.getString(R.string.font_empty_hint)

    fun invalidNotice(): String = AppContext.ctx.getString(R.string.font_invalid)

    fun tooBigNotice(): String =
        AppContext.ctx.getString(R.string.font_too_big, formatSize(MAX_FONT_MB * MB_BYTES))

    fun fullNotice(): String =
        AppContext.ctx.getString(R.string.font_full, MAX_FONTS)

    fun deleteLabel(): String = AppContext.ctx.getString(R.string.font_delete)

    fun deletedNotice(name: String): String =
        AppContext.ctx.getString(R.string.font_deleted_fmt, name)

    fun sizeLabel(bytes: Long): String =
        AppContext.ctx.getString(R.string.font_size_fmt, formatSize(bytes))

    /** 體積格式化（B／KB／MB——與離線快取同風格） */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes}B"
        if (bytes < MB_BYTES) {
            return String.format(Locale.TAIWAN, "%.1fKB", bytes / 1024.0)
        }
        return String.format(Locale.TAIWAN, "%.1fMB", bytes / MB_BYTES.toDouble())
    }

    // ═════════════════ 索引與檔案系統 ═════════════════

    /** 對帳後的活索引（檔案還在的才算數） */
    private fun liveIndex(): List<FontEntry> {
        val index = loadIndex()
        val dir = fontsDir()
        val alive = index.filter { File(dir, it.fileName).isFile }
        if (alive.size != index.size) {
            // 檔案被系統清理工具誤刪——索引自動除帳
            persistIndex(alive)
        }
        return alive
    }

    private fun loadIndex(): List<FontEntry> = runCatching {
        val file = indexFile()
        if (!file.exists()) return emptyList()
        gson.fromJson(file.readText(), object : TypeToken<List<FontEntry>>() {}.type)
            as? List<FontEntry>
    }.getOrNull() ?: emptyList()

    private fun persistIndex(index: List<FontEntry>) {
        runCatching {
            fontsDir().mkdirs()
            indexFile().writeText(gson.toJson(index))
        }.onFailure { e ->
            Log.w(TAG, "字體索引持久化失敗", e)
        }
    }

    /** 清掉無索引對應的殘檔（匯入中途失敗的半套檔案） */
    private fun sweepOrphanFiles() {
        runCatching {
            val dir = fontsDir()
            if (!dir.isDirectory) return
            val known = loadIndex().map { it.fileName }.toSet()
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in known) file.delete()
            }
        }
    }

    private fun fontsDir(): File = File(AppContext.ctx.filesDir, FONTS_DIR)

    private fun indexFile(): File = File(fontsDir(), INDEX_FILE)

    private fun prefs() =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, 0)
}

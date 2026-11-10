package com.k.reader.help.bookmark

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 章節書籤管理器（Stage 9 旗艦增益：章節書籤）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（讀到哪，釘到哪）：
 *   長篇連載動輒數百章——「上次讀到精彩決戰」不能只靠腦記。
 *   本管理器把每一枚書籤管理成可跳轉的資產：
 *
 *    1. 釘選：章號＋章內字元位置（chapterPos 捲動定位）＋前後文
 *       快照（snippet，前後各 20 字窗口）＋自訂標籤（label）；
 *       書名作者＋bookUrl 雙鍵定位——換源不改書名，書籤跨源
 *       存活（與劃線簿／完本集完全同思路）；
 *    2. 導航：jumpTargetsAround() 給「上一枚／下一枚」書籤跳轉
 *       ——閱讀頁工具列一鍵來回，比目錄翻找快；
 *    3. 容量紀律：單書上限 30 枚——與回收站／金句簿同家族的護欄
 *       設計。滿了「拒收新書籤」而非靜默淘汰舊書籤——書籤是
 *       讀者有意識的釘選，自動刪除等於踩掉讀者的路標；由
 *       讀者自行刪一騰位（bookmark_full 提示）；
 *    4. 快照寬容：章節內容可能尚未載入（離線／跨源）——
 *       snippet 允許為 null，釘選不因缺內容而失敗。
 *
 *  整合點：
 *   · 閱讀頁工具列「書籤」 → dialog_bookmark_list 列表
 *   · 「釘住目前位置」按鈕 → saveBookmark()
 *   · 工具列「上一枚／下一枚」→ jumpTargetsAround()
 *   · 列表滑動刪除 → removeBookmark()
 */
object ChapterBookmarkManager {

    private const val TAG = "ChapterBookmark"

    /** 書籤簿檔名（filesDir 下——與劃線簿同家族） */
    private const val BOOK_FILE = "chapter_bookmarks.json"

    /** 單書書籤容量上限（規格：30 枚） */
    const val MAX_PER_BOOK = 30

    /** 快照前後文字元數（規格：前後各 20 字） */
    const val SNIPPET_CONTEXT_CHARS = 20

    private val gson = Gson()

    /** 書籤條目（章號＋章內位置＋快照＋標籤） */
    data class Bookmark(
        var id: Long,
        var bookName: String,
        var author: String?,
        var bookUrl: String?,
        var chapterIndex: Int,
        var chapterTitle: String?,
        var chapterPos: Int,
        var snippet: String?,
        var label: String?,
        var createdAt: Long
    )

    // ═════════════════ 書籤 CRUD ═════════════════

    /**
     * 釘一枚書籤（同章同位置視為重複——更新標籤而非新增）。
     * 單書滿 30 枚時回 null（拒收不淘汰——讀者的路標不靜默刪）。
     */
    fun saveBookmark(
        bookName: String,
        author: String?,
        bookUrl: String?,
        chapterIndex: Int,
        chapterTitle: String?,
        chapterPos: Int,
        chapterContent: String? = null,
        label: String? = null
    ): Bookmark? {
        if (bookName.isBlank() || chapterIndex < 0) return null
        return runCatching {
            val all = loadAll()
            val mine = filterMine(all, bookUrl, bookName)
            // 同章同位置：視為改標籤（釘兩次不長兩枚）
            val dup = mine.firstOrNull {
                it.chapterIndex == chapterIndex && it.chapterPos == chapterPos
            }
            if (dup != null) {
                dup.label = label?.trim()?.takeIf { it.isNotEmpty() }
                persist(all)
                return dup
            }
            // 容量護欄：滿了拒收（UI 以 bookmark_full 提示）
            if (mine.size >= MAX_PER_BOOK) return null
            val entry = Bookmark(
                id = System.currentTimeMillis(),
                bookName = bookName.trim(),
                author = author?.trim()?.takeIf { it.isNotEmpty() },
                bookUrl = bookUrl?.trim()?.takeIf { it.isNotEmpty() },
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
                chapterPos = chapterPos.coerceAtLeast(0),
                snippet = buildSnippet(chapterContent, chapterPos),
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = System.currentTimeMillis()
            )
            persist(listOf(entry) + all)
            entry
        }.onFailure { e ->
            Log.w(TAG, "書籤寫入失敗", e)
        }.getOrNull()
    }

    /** 更新標籤（id 不存在回 false） */
    fun updateLabel(id: Long, label: String?): Boolean {
        val all = loadAll()
        val target = all.firstOrNull { it.id == id } ?: return false
        target.label = label?.trim()?.takeIf { it.isNotEmpty() }
        return runCatching { persist(all); true }
            .onFailure { e -> Log.w(TAG, "書籤標籤更新失敗", e) }
            .getOrDefault(false)
    }

    /** 刪除單枚（回傳被刪條目——UI 端可做撤銷） */
    fun removeBookmark(id: Long): Bookmark? {
        val all = loadAll()
        val target = all.firstOrNull { it.id == id } ?: return null
        runCatching { persist(all.filterNot { it.id == id }) }
            .onFailure { e -> Log.w(TAG, "書籤刪除失敗", e) }
        return target
    }

    /** 全簿（新到舊） */
    fun allBookmarks(): List<Bookmark> = loadAll()

    /** 單書書籤（章號升序、同章以位置升序——列表與跳轉共用） */
    fun bookmarksFor(bookUrl: String?, bookName: String): List<Bookmark> =
        filterMine(loadAll(), bookUrl, bookName)
            .sortedWith(compareBy({ it.chapterIndex }, { it.chapterPos }))

    /** 清空單書書籤（換源清理／刪書連帶——回傳移除枚數） */
    fun clearForBook(bookUrl: String?, bookName: String): Int {
        val all = loadAll()
        val mine = filterMine(all, bookUrl, bookName)
        if (mine.isEmpty()) return 0
        val mineIds = mine.map { it.id }.toSet()
        runCatching { persist(all.filterNot { it.id in mineIds }) }
            .onFailure { e -> Log.w(TAG, "書籤清空失敗", e) }
        return mine.size
    }

    // ═════════════════ 導航 ═════════════════

    /**
     * 上一枚／下一枚書籤（閱讀頁來回跳轉）：
     * prev＝章號小於目前的最後一枚；next＝章號大於目前的第一枚。
     * 同章書籤不參與導航（貼身來回交給劃線與目錄）。
     */
    fun jumpTargetsAround(
        bookUrl: String?,
        bookName: String,
        chapterIndex: Int
    ): Pair<Bookmark?, Bookmark?> {
        val sorted = bookmarksFor(bookUrl, bookName)
        val prev = sorted.lastOrNull { it.chapterIndex < chapterIndex }
        val next = sorted.firstOrNull { it.chapterIndex > chapterIndex }
        return prev to next
    }

    // ═════════════════ 快照 ═════════════════

    /** 前後各 20 字窗口（邊界夾擠；內容空白或位置越界回 null——釘選不失敗） */
    fun buildSnippet(content: String?, chapterPos: Int): String? {
        if (content.isNullOrBlank()) return null
        val text = content.trim()
        if (chapterPos < 0 || chapterPos >= text.length) return null
        val start = (chapterPos - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
        val end = (chapterPos + SNIPPET_CONTEXT_CHARS).coerceAtMost(text.length)
        return buildString {
            if (start > 0) append("…")
            append(text, start, end)
            if (end < text.length) append("…")
        }
    }

    // ═════════════════ 文案 ═════════════════

    fun title(): String = AppContext.ctx.getString(R.string.bookmark_title)

    fun addCurrentLabel(): String = AppContext.ctx.getString(R.string.bookmark_add_current)

    fun emptyHint(): String = AppContext.ctx.getString(R.string.bookmark_empty_hint)

    fun jumpLabel(chapterIndex: Int): String =
        AppContext.ctx.getString(R.string.bookmark_jump_fmt, chapterIndex + 1)

    fun fullNotice(): String =
        AppContext.ctx.getString(R.string.bookmark_full, MAX_PER_BOOK)

    fun addedNotice(chapterIndex: Int): String =
        AppContext.ctx.getString(R.string.bookmark_added, chapterIndex + 1)

    fun countLabel(count: Int): String =
        AppContext.ctx.getString(R.string.bookmark_count_fmt, count)

    fun removedNotice(): String = AppContext.ctx.getString(R.string.bookmark_removed)

    fun prevJumpLabel(): String = AppContext.ctx.getString(R.string.bookmark_prev_jump)

    fun nextJumpLabel(): String = AppContext.ctx.getString(R.string.bookmark_next_jump)

    fun deleteLabel(): String = AppContext.ctx.getString(R.string.bookmark_delete)

    fun labelHint(): String = AppContext.ctx.getString(R.string.bookmark_label_hint)

    // ═════════════════ 持久化與工具 ═════════════════

    /** 雙鍵篩選：bookUrl 優先；缺失時退書名作者鍵（換源跨源存活） */
    private fun filterMine(
        all: List<Bookmark>,
        bookUrl: String?,
        bookName: String
    ): List<Bookmark> {
        return if (!bookUrl.isNullOrBlank()) {
            all.filter { it.bookUrl == bookUrl }
        } else {
            val key = bookKey(bookName, null)
            all.filter { it.bookUrl.isNullOrBlank() && bookKey(it.bookName, it.author) == key }
        }
    }

    /** 書名作者鍵（與劃線簿／統計完本集同思路） */
    private fun bookKey(name: String, author: String?): String =
        "${name.trim()}|${author.orEmpty().trim()}"

    private fun bookFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    private fun loadAll(): List<Bookmark> = runCatching {
        val file = bookFile()
        if (!file.exists()) return emptyList()
        gson.fromJson(file.readText(), object : TypeToken<List<Bookmark>>() {}.type)
            as? List<Bookmark>
    }.getOrNull() ?: emptyList()

    private fun persist(bookmarks: List<Bookmark>) {
        runCatching {
            bookFile().writeText(gson.toJson(bookmarks))
        }.onFailure { e ->
            Log.w(TAG, "書籤簿持久化失敗", e)
        }
    }
}

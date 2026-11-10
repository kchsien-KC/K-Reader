package com.k.reader.help.tag

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 書籍標籤管理（Stage 13：閱讀資料自主與年度回顧）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（書架的秩序，長在使用者自己的腦子裡）：
 *   書源分組是機器的事（網域聚攏），標籤是人的事——「科幻」
 *   「睡前讀」「要寫報告」：
 *
 *    1. 標籤簿：id／名稱／色票索引（8 色），名稱唯一去重、
 *       12 字上限、全庫 30 個上限（夠用且防濫用）；
 *    2. 標記圖：書（名稱｜作者 為鍵）→ 標籤 id 清單；每書
 *       最多 3 個標籤（多即是無）、標記書籍上限 500；
 *    3. 純計數視窗：tagsFor() 給 UI 徽章行、booksOf() 給
 *       標籤詳情——唯讀取數，與其他模組零耦合。
 *
 *  帳冊：filesDir/book_tags.json（單一 JSON：tags＋books 兩節）。
 */
object BookTagManager {

    private const val TAG = "BookTagManager"

    private const val BOOK_FILE = "book_tags.json"

    /** 標籤名上限（字元） */
    const val NAME_MAX_CHARS = 12

    /** 全庫標籤上限 */
    const val MAX_TAGS = 30

    /** 單書標籤上限 */
    const val TAGS_PER_BOOK = 3

    /** 色票數（0..7，UI 以色相環取色） */
    const val COLOR_COUNT = 8

    /** 標記書籍上限 */
    const val MAX_TAGGED_BOOKS = 500

    /** 標籤（色票索引 0..7） */
    data class Tag(
        val id: Long,
        val name: String,
        val colorIndex: Int,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /** 新增標籤（同名去重、數量上限、色票夾 0..7；失敗回 null） */
    fun addTag(name: String, colorIndex: Int): Tag? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val root = load()
        val tags = tagListOf(root)
        if (tags.any { it.name == clean }) return null
        if (tags.size >= MAX_TAGS) return null
        val tag = Tag(
            id = nextIdOf(tags),
            name = clean.take(NAME_MAX_CHARS),
            colorIndex = colorIndex.coerceIn(0, COLOR_COUNT - 1),
        )
        root.put("tags", tagArrayFrom(tags + tag))
        persist(root)
        Log.i(TAG, "標籤新增：${tag.name}（色票 ${tag.colorIndex}）")
        return tag
    }

    /** 刪除標籤（連動拆除所有標記；回傳是否刪除） */
    fun deleteTag(tagId: Long): Boolean {
        val root = load()
        val tags = tagListOf(root)
        if (tags.none { it.id == tagId }) return false
        root.put("tags", tagArrayFrom(tags.filter { it.id != tagId }))
        val books = booksMapOf(root).toMutableMap()
        for ((key, ids) in books) {
            val left = ids.filter { it != tagId }
            if (left.isEmpty()) books.remove(key) else books[key] = left
        }
        root.put("books", booksFrom(books))
        persist(root)
        return true
    }

    /** 標記一本書（每書上限 3、標記書籍上限 500；回傳是否標上） */
    fun tagBook(bookName: String, author: String?, tagId: Long): Boolean {
        val key = bookKey(bookName, author)
        val root = load()
        if (tagListOf(root).none { it.id == tagId }) return false
        val books = booksMapOf(root).toMutableMap()
        val ids = books[key]?.toMutableList() ?: mutableListOf()
        if (ids.contains(tagId)) return true          // 冪等
        if (books.containsKey(key)) {
            if (ids.size >= TAGS_PER_BOOK) return false
        } else if (books.size >= MAX_TAGGED_BOOKS) {
            return false
        }
        ids.add(tagId)
        books[key] = ids
        root.put("books", booksFrom(books))
        persist(root)
        return true
    }

    /** 取消標記（回傳是否取消） */
    fun untagBook(bookName: String, author: String?, tagId: Long): Boolean {
        val key = bookKey(bookName, author)
        val root = load()
        val books = booksMapOf(root).toMutableMap()
        val ids = books[key] ?: return false
        if (!ids.contains(tagId)) return false
        val left = ids.filter { it != tagId }
        if (left.isEmpty()) books.remove(key) else books[key] = left
        root.put("books", booksFrom(books))
        persist(root)
        return true
    }

    /** 某書的標籤（依標籤簿過濾——簿中已刪的懸空 id 不顯示） */
    fun tagsFor(bookName: String, author: String?): List<Tag> {
        val root = load()
        val known = tagListOf(root).associateBy { it.id }
        val ids = booksMapOf(root)[bookKey(bookName, author)] ?: return emptyList()
        return ids.mapNotNull { known[it] }
    }

    /** 某標籤被幾本書使用（懸空標記不計） */
    fun booksOf(tagId: Long): Int {
        val root = load()
        val ids = booksMapOf(root).values
        return ids.count { it.contains(tagId) }
    }

    /** 標籤簿全列（建立時間序） */
    fun allTags(): List<Tag> = tagListOf(load())

    /** 標籤數 */
    fun tagCount(): Int = tagListOf(load()).size

    /** 標記書籍數（清理懸空鍵後——簿中無此標籤的標記不計） */
    fun taggedBookCount(): Int {
        val root = load()
        val knownIds = tagListOf(root).map { it.id }.toSet()
        return booksMapOf(root).values.count { ids -> ids.any { it in knownIds } }
    }

    /** 帳冊檔（資料健檢／搬家登記共用） */
    fun tagFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ── 內部：帳冊 ────────────────────────────────────────────

    /** 讀帳（損毀回空簿——模組自癒重建） */
    private fun load(): JSONObject = try {
        val f = tagFile()
        if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
    } catch (t: Throwable) {
        Log.w(TAG, "標籤帳冊損毀——重建空簿", t)
        JSONObject()
    }

    private fun persist(root: JSONObject) {
        try {
            tagFile().writeText(root.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "標籤帳冊寫入失敗", t)
        }
    }

    private fun tagListOf(root: JSONObject): List<Tag> {
        val arr = root.optJSONArray("tags") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) return@mapNotNull null
            Tag(
                id = o.optLong("id", 0L),
                name = name.take(NAME_MAX_CHARS),
                colorIndex = o.optInt("colorIndex", 0).coerceIn(0, COLOR_COUNT - 1),
            )
        }
    }

    private fun booksMapOf(root: JSONObject): Map<String, List<Long>> {
        val obj = root.optJSONObject("books") ?: return emptyMap()
        val out = mutableMapOf<String, List<Long>>()
        for (key in obj.keys()) {
            val ids = mutableListOf<Long>()
            val arr = obj.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optLong(i, -1L)
                if (v > 0L) ids.add(v)
            }
            if (ids.isNotEmpty()) out[key] = ids
        }
        return out
    }

    private fun tagArrayFrom(tags: List<Tag>): JSONArray =
        JSONArray().apply { tags.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("colorIndex", it.colorIndex)) } }

    private fun booksFrom(books: Map<String, List<Long>>): JSONObject =
        JSONObject().apply { books.forEach { (k, v) -> put(k, JSONArray(v)) } }

    /** 新 id（簿中最大＋1；空簿 1） */
    private fun nextIdOf(tags: List<Tag>): Long =
        (tags.maxOfOrNull { it.id } ?: 0L) + 1L

    /** 書鍵：名稱｜作者（皆 trim；作者空為空串） */
    private fun bookKey(bookName: String, author: String?): String =
        bookName.trim() + "|" + (author?.trim() ?: "")

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.tag_title)
    fun addLabel(): String = AppContext.ctx.getString(R.string.tag_add)
    fun nameHint(): String = AppContext.ctx.getString(R.string.tag_name_hint)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.tag_empty)
    fun existsNotice(): String = AppContext.ctx.getString(R.string.tag_exists)
    fun quotaNotice(): String = AppContext.ctx.getString(R.string.tag_quota)
    fun bookQuotaNotice(): String = AppContext.ctx.getString(R.string.tag_book_quota)
    fun clearLabel(): String = AppContext.ctx.getString(R.string.tag_clear)

    /** 「色票 3」 */
    fun colorLabel(colorIndex: Int): String =
        AppContext.ctx.getString(R.string.tag_color_fmt, colorIndex)

    /** 「已新增『科幻』」 */
    fun addedNotice(tag: Tag): String =
        AppContext.ctx.getString(R.string.tag_added_fmt, tag.name)

    /** 「已移除『科幻』」 */
    fun clearedNotice(name: String): String =
        AppContext.ctx.getString(R.string.tag_cleared_fmt, name)

    /** 「《三體》3 個標籤」 */
    fun bookLine(bookName: String, count: Int): String =
        AppContext.ctx.getString(R.string.tag_book_line, bookName, count)
}

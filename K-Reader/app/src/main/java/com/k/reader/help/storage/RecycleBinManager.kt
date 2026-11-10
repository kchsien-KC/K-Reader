package com.k.reader.help.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book
import com.k.reader.data.entities.BookSource
import com.k.reader.data.entities.RssSource
import com.k.reader.help.content.CleanRuleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 回收站管理器（十大旗艦功能：回收站）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（刪除不是永別——30 天可後悔）：
 *   手滑誤刪是閱讀 App 的高頻悲劇：刪掉一本追了三百章的書、整套
 *   書源設定、或一條好不容易調好的淨化規則。與其彈確認框打斷
 *   操作節奏，不如「快刪＋可還原」：
 *
 *    1. 進站：任何支援回收站的刪除動作，先把實體 Gson 序列化為
 *       payload 存入回收站（type＋摘要＋JSON），再真正移除；
 *    2. 還原：單向操作——反序列化後走各 DAO 的 insertOrUpdate
 *       冪等寫回（存在即覆蓋），淨化規則走 CleanRuleManager.
 *       importRule；
 *    3. 自潔：30 天（規格值）逾期自動清除；容量上限 200 條，
 *       逾限自最舊淘汰——回收站本身也保持輕量；
 *    4. 一鍵清空：purify_recycle_empty 對應「回收站已清空」。
 *
 *  支援的實體形態：
 *    · BOOK        書架書籍（含閱讀進度欄位一併存還）
 *    · BOOK_SOURCE 書源設定
 *    · RSS_SOURCE  訂閱源設定
 *    · PURIFY_RULE 淨化規則（Stage 5 CleanRuleManager 刪除時進站）
 *
 *  整合點：
 *   · 書架長按刪除 / 書源批次刪除（batch_delete）→ recycle()
 *   · 淨化規則刪除（CleanRuleManager.deleteRule）→ recycle()
 *   · 「我的」頁面 → purify_recycle 回收站列表（還原／永久刪除／清空）
 *
 *  ─── 儲存設計 ──────────────────────────────────────────
 *  獨立設定檔 k_reader_recycle_bin（與全域設定 k_reader_config
 *  刻意分離——回收站是暫存資料，避免撐大主設定檔）。實體量級
 *  上限 200 條 × payload 數 KB，SharedPreferences 足堪負荷。
 */
object RecycleBinManager {

    private const val TAG = "RecycleBinManager"

    private const val PREFS_NAME = "k_reader_recycle_bin"
    private const val KEY_ENTRIES = "recycle_entries_json"

    /** 回收站保留天數（規格：30 天——逾期自動清除） */
    const val RETENTION_DAYS = 30

    /** 回收站容量上限（逾限自最舊淘汰） */
    const val MAX_ENTRIES = 200

    /** 同毫秒多重入站時的 id 遞增步進 */
    private const val ID_STEP = 1_000L

    // ═════════════════ 進站形態 ═════════════════

    const val TYPE_BOOK = "BOOK"
    const val TYPE_BOOK_SOURCE = "BOOK_SOURCE"
    const val TYPE_RSS_SOURCE = "RSS_SOURCE"
    const val TYPE_PURIFY_RULE = "PURIFY_RULE"

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 進站條目（payloadJson 為實體的 Gson 序列化） */
    data class RecycleEntry(
        val id: Long,
        val type: String,
        val summary: String,
        val payloadJson: String,
        val deletedAt: Long
    )

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 條目 id 序號（初始化為既有最大 id 之後，保證單調遞增） */
    private val idSeq by lazy {
        val maxId = loadEntries().maxOfOrNull { it.id } ?: 0L
        AtomicLong(maxId + ID_STEP)
    }

    // ═════════════════ 進站 ═════════════════

    /**
     * 刪除前的進站：實體序列化＋摘要登記＋容量自潔＋持久化。
     * 呼叫端慣例：recycle() 回 true 才執行真正的刪除。
     */
    suspend fun recycle(type: String, summary: String, payload: Any): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val entry = RecycleEntry(
                    id = idSeq.getAndIncrement(),
                    type = type,
                    summary = summary.take(80),
                    payloadJson = gson.toJson(payload),
                    deletedAt = System.currentTimeMillis()
                )
                val next = (listOf(entry) + loadEntries())
                    .sortedByDescending { it.deletedAt }
                    .take(MAX_ENTRIES)
                persist(next)
            }.onFailure { e ->
                Log.w(TAG, "回收站進站失敗：$summary", e)
            }.isSuccess
        }

    // ═════════════════ 查詢 ═════════════════

    /** 目前列表（新→舊；存取即自潔——逾期條目順手清掉） */
    suspend fun entries(): List<RecycleEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val retentionMs = RETENTION_DAYS * 24L * 60L * 60L * 1000L
        val loaded = loadEntries()
        val fresh = loaded.filter { now - it.deletedAt < retentionMs }
        if (fresh.size != loaded.size) persist(fresh)
        fresh
    }

    // ═════════════════ 還原 ═════════════════

    /**
     * 單條還原：依形態反序列化 → 對應寫回 → 出站。
     * 成敗以 success／error 文案回報（還原動作由 UI 觸發，需要回饋）。
     */
    suspend fun restore(entryId: Long): Boolean = withContext(Dispatchers.IO) {
        val list = loadEntries()
        val entry = list.firstOrNull { it.id == entryId } ?: return@withContext false
        val ok = runCatching { restorePayload(entry) }.onFailure { e ->
            Log.w(TAG, "回收站還原失敗：${entry.summary}", e)
        }.isSuccess
        if (ok) {
            persist(list.filterNot { it.id == entryId })
            toastOnMain(AppContext.ctx.getString(R.string.success))
        } else {
            toastOnMain(AppContext.ctx.getString(R.string.error))
        }
        ok
    }

    /** 形態分發還原（冪等寫回——存在即覆蓋，重複還原安全） */
    private suspend fun restorePayload(entry: RecycleEntry) {
        when (entry.type) {
            TYPE_BOOK ->
                AppContext.db.bookDao()
                    .insertOrUpdate(gson.fromJson(entry.payloadJson, Book::class.java))
            TYPE_BOOK_SOURCE ->
                AppContext.db.bookSourceDao().insertOrUpdate(
                    gson.fromJson(entry.payloadJson, BookSource::class.java)
                )
            TYPE_RSS_SOURCE ->
                AppContext.db.rssSourceDao().insertOrUpdate(
                    gson.fromJson(entry.payloadJson, RssSource::class.java)
                )
            TYPE_PURIFY_RULE ->
                CleanRuleManager.importRule(
                    gson.fromJson(entry.payloadJson, CleanRuleManager.PurifyRule::class.java)
                )
            else -> error("未支援的回收站形態：${entry.type}")
        }
    }

    // ═════════════════ 出站／清空 ═════════════════

    /** 單條永久刪除（不經還原直接出站） */
    suspend fun remove(entryId: Long): Boolean = withContext(Dispatchers.IO) {
        val list = loadEntries()
        persist(list.filterNot { it.id == entryId })
    }

    /** 一鍵清空（purify_recycle_empty 文案） */
    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        val ok = persist(emptyList())
        if (ok) toastOnMain(AppContext.ctx.getString(R.string.purify_recycle_empty))
        ok
    }

    /** UI 標題／動作文案（回收站列表頁） */
    fun binTitle(): String = AppContext.ctx.getString(R.string.purify_recycle)

    fun restoreLabel(): String = AppContext.ctx.getString(R.string.purify_recycle_restore)

    fun emptyHint(): String = AppContext.ctx.getString(R.string.empty)

    // ═════════════════ 持久化 ═════════════════

    private fun loadEntries(): List<RecycleEntry> = runCatching {
        prefs.getString(KEY_ENTRIES, null)?.let {
            gson.fromJson(it, object : TypeToken<List<RecycleEntry>>() {}.type)
                as? List<RecycleEntry>
        }
    }.getOrNull().orEmpty()

    private fun persist(entries: List<RecycleEntry>): Boolean = runCatching {
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
    }.isSuccess

    private fun toastOnMain(text: String) {
        mainHandler.post {
            Toast.makeText(AppContext.ctx, text, Toast.LENGTH_SHORT).show()
        }
    }
}

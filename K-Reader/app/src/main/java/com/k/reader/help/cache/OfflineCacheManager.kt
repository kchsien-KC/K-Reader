package com.k.reader.help.cache

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 離線快取管理器（Stage 9 旗艦增益：離線快取）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（走出電梯，書還在讀）：
 *   通勤捷運鑽進地下、飛機起飛——沒有網路的十分鐘，正是讀
 *   長篇的黃金檔。本管理器把章節正文快取成檔案資產：
 *
 *    1. 預載：precache() 從目前章往後抓 N 章（1..50 夾擊）——
 *       章節內容由 ChapterLoader 介面注入（與 FullTextSearchHelper
 *       的 ContentLoader 同思路——管理器不綁定任何抓取實作，
 *       原版 ContentDispatcher 也能直接餵）；
 *    2. 命中：cachedChapter() 先查快取再走網路（命中順手以
 *       setLastModified 更新 LRU 時鐘——檔案系統即時鐘，零
 *       額外索引檔）；
 *    3. 體檢：stats() 走訪全樹（bytes／章數／書數），
 *       evictToBudget() 依 lastModified 升序砍到預算內（512MB
 *       預設——手機空間是讀者的，不是快取的）；
 *    4. 護欄：單章 200_000 字上限（與全文搜尋同款）；章檔以
 *       「bookUrl 雜湊 / 六位章號.txt」落 filesDir/chapter_cache/
 *       ——應用私有目錄，解除安裝自動清場，零公共空間污染。
 *
 *  整合點：
 *   · 閱讀頁翻章 → cachedChapter() 先命中 → 未中走原版抓取
 *   · 書籍詳情「離線」 → precache(目前章, 20, loader)
 *   · 設定「儲存空間」 → stats()／evictToBudget()／clearAll()
 *   · 刪書 → clearBook() 連帶清快取
 */
object OfflineCacheManager {

    private const val TAG = "OfflineCache"

    /** 快取根目錄（filesDir 下——應用私有，解除安裝自動清場） */
    private const val CACHE_DIR = "chapter_cache"

    /** 快取預算預設（規格：512MB——體積紀律） */
    const val DEFAULT_BUDGET_MB = 512

    /** 預載章數上限（規格：50 章——一次抓整本書交給「全文匯入」場景另行處理） */
    const val MAX_AHEAD = 50

    /** 單章內容長度護欄（與全文搜尋同款：200_000 字） */
    const val MAX_CHAPTER_CHARS = 200_000

    private val MB_BYTES = 1024L * 1024L

    /** 章節內容注入介面（不綁定抓取實作——原版 ContentDispatcher 可直餵） */
    fun interface ChapterLoader {
        suspend fun load(bookUrl: String, chapterIndex: Int): String?
    }

    /** 快取體檢報告（bytes／章數／書數） */
    data class CacheStats(val totalBytes: Long, val chapterCount: Int, val bookCount: Int)

    /** 預載結果（requested 夾擊後實際值／快取成功／跳過已存／失敗） */
    data class PrecacheResult(
        val requested: Int,
        val cached: Int,
        val skipped: Int,
        val failed: Int,
        val totalBytes: Long
    )

    // ═════════════════ 預載 ═════════════════

    /**
     * 從目前章往後預載 N 章（IO 執行緒；ahead 夾擊 1..50）。
     * 已快取的章跳過（增量式）；loader 回 null 記為失敗不中斷
     * ——單章失敗不拖垮整批。
     */
    suspend fun precache(
        bookUrl: String,
        currentIndex: Int,
        ahead: Int,
        loader: ChapterLoader
    ): PrecacheResult = withContext(Dispatchers.IO) {
        val want = ahead.coerceIn(1, MAX_AHEAD)
        var cached = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        if (bookUrl.isBlank() || currentIndex < 0) {
            return@withContext PrecacheResult(want, cached, skipped, failed, bytes)
        }
        for (offset in 1..want) {
            val index = currentIndex + offset
            if (isCached(bookUrl, index)) {
                skipped++
                continue
            }
            runCatching {
                val content = loader.load(bookUrl, index)
                if (content.isNullOrBlank()) {
                    failed++
                } else {
                    val trimmed = content.trim().take(MAX_CHAPTER_CHARS)
                    val file = fileOf(bookUrl, index)
                    file.parentFile?.mkdirs()
                    file.writeText(trimmed)
                    cached++
                    bytes += file.length()
                }
            }.onFailure { e ->
                failed++
                Log.w(TAG, "章節 ${index} 預載失敗", e)
            }
        }
        PrecacheResult(want, cached, skipped, failed, bytes)
    }

    // ═════════════════ 命中 ═════════════════

    /** 讀快取（命中順手觸碰 LRU 時鐘；未命中回 null——呼叫端走網路） */
    fun cachedChapter(bookUrl: String, chapterIndex: Int): String? {
        if (bookUrl.isBlank() || chapterIndex < 0) return null
        return runCatching {
            val file = fileOf(bookUrl, chapterIndex)
            if (!file.isFile) return null
            file.setLastModified(System.currentTimeMillis())
            file.readText().take(MAX_CHAPTER_CHARS)
        }.onFailure { e ->
            Log.w(TAG, "快取讀取失敗", e)
        }.getOrNull()
    }

    /** 是否已快取（純存在性檢查——不觸碰 LRU 時鐘） */
    fun isCached(bookUrl: String, chapterIndex: Int): Boolean =
        bookUrl.isNotBlank() && chapterIndex >= 0 && fileOf(bookUrl, chapterIndex).isFile

    // ═════════════════ 體檢與清理 ═════════════════

    /** 全樹體檢（bytes／章數／書數） */
    fun stats(): CacheStats {
        val root = cacheRoot()
        if (!root.isDirectory) return CacheStats(0L, 0, 0)
        return runCatching {
            var bytes = 0L
            var chapters = 0
            val books = root.listFiles { f -> f.isDirectory } ?: emptyArray()
            books.forEach { dir ->
                dir.listFiles { f -> f.isFile }?.forEach { file ->
                    bytes += file.length()
                    chapters++
                }
            }
            CacheStats(bytes, chapters, books.size)
        }.onFailure { e ->
            Log.w(TAG, "快取體檢失敗", e)
            CacheStats(0L, 0, 0)
        }.getOrDefault(CacheStats(0L, 0, 0))
    }

    /**
     * LRU 收編：依 lastModified 升序刪檔直到總量 <= 預算。
     * 回傳釋放 bytes（空目錄順手掃掉）。
     */
    fun evictToBudget(budgetBytes: Long = DEFAULT_BUDGET_MB * MB_BYTES): Long {
        val root = cacheRoot()
        if (!root.isDirectory) return 0L
        return runCatching {
            val files = (root.listFiles { f -> f.isDirectory } ?: emptyArray())
                .flatMap { dir -> dir.listFiles { f -> f.isFile }?.toList() ?: emptyList() }
                .sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            var freed = 0L
            for (file in files) {
                if (total <= budgetBytes) break
                val size = file.length()
                if (file.delete()) {
                    total -= size
                    freed += size
                }
            }
            sweepEmptyDirs()
            freed
        }.onFailure { e ->
            Log.w(TAG, "快取收編失敗", e)
            0L
        }.getOrDefault(0L)
    }

    /** 清空單書快取（刪書連帶——回傳移除章數） */
    fun clearBook(bookUrl: String): Int {
        if (bookUrl.isBlank()) return 0
        val dir = dirOf(bookUrl)
        if (!dir.isDirectory) return 0
        return runCatching {
            val files = dir.listFiles { f -> f.isFile } ?: emptyArray()
            files.forEach { it.delete() }
            dir.delete()
            files.size
        }.onFailure { e ->
            Log.w(TAG, "單書快取清理失敗", e)
            0
        }.getOrDefault(0)
    }

    /** 清空全部快取（回傳移除章數） */
    fun clearAll(): Int {
        val root = cacheRoot()
        if (!root.isDirectory) return 0
        return runCatching {
            var chapters = 0
            root.listFiles()?.forEach { entry ->
                if (entry.isDirectory) {
                    entry.listFiles { f -> f.isFile }?.forEach { file ->
                        if (file.delete()) chapters++
                    }
                }
                entry.delete()
            }
            root.delete()
            chapters
        }.onFailure { e ->
            Log.w(TAG, "快取全清失敗", e)
            0
        }.getOrDefault(0)
    }

    // ═════════════════ 文案 ═════════════════

    fun title(): String = AppContext.ctx.getString(R.string.cache_title)

    fun precacheStartLabel(ahead: Int): String =
        AppContext.ctx.getString(R.string.cache_precache_start, ahead)

    fun precacheDoneLabel(cached: Int, sizeText: String): String =
        AppContext.ctx.getString(R.string.cache_precache_done_fmt, cached, sizeText)

    fun precacheNoneLabel(): String = AppContext.ctx.getString(R.string.cache_precache_none)

    fun statsLabel(stats: CacheStats): String = AppContext.ctx.getString(
        R.string.cache_stats_fmt, formatSize(stats.totalBytes),
        stats.chapterCount, stats.bookCount
    )

    fun evictDoneLabel(freedBytes: Long): String =
        AppContext.ctx.getString(R.string.cache_evict_done_fmt, formatSize(freedBytes))

    fun clearConfirmLabel(sizeText: String): String =
        AppContext.ctx.getString(R.string.cache_clear_confirm, sizeText)

    fun clearDoneLabel(chapters: Int): String =
        AppContext.ctx.getString(R.string.cache_clear_done, chapters)

    fun hitLabel(): String = AppContext.ctx.getString(R.string.cache_hit)

    fun budgetLabel(): String =
        AppContext.ctx.getString(R.string.cache_budget_fmt, formatSize(DEFAULT_BUDGET_MB * MB_BYTES))

    /** 體積格式化（B／KB／MB——與統計 formatWords 同風格） */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes}B"
        if (bytes < MB_BYTES) {
            return String.format(Locale.TAIWAN, "%.1fKB", bytes / 1024.0)
        }
        return String.format(Locale.TAIWAN, "%.1fMB", bytes / MB_BYTES.toDouble())
    }

    // ═════════════════ 檔案佈局 ═════════════════

    private fun cacheRoot(): File = File(AppContext.ctx.filesDir, CACHE_DIR)

    /** 書目錄＝bookUrl 雜湊（16 進位——負值雜湊也是合法目錄名） */
    private fun dirOf(bookUrl: String): File =
        File(cacheRoot(), Integer.toHexString(bookUrl.hashCode()))

    /** 章檔＝六位章號.txt（字典序即章序——體檢與收編免解析檔名） */
    private fun fileOf(bookUrl: String, chapterIndex: Int): File =
        File(dirOf(bookUrl), String.format(Locale.US, "%06d.txt", chapterIndex))

    /** 掃掉空書目錄（收編後殼不留） */
    private fun sweepEmptyDirs() {
        val root = cacheRoot()
        root.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
    }
}

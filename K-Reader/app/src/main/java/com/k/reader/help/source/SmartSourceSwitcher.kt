package com.k.reader.help.source

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book
import com.k.reader.data.entities.BookChapter
import com.k.reader.data.entities.BookSource
import com.k.reader.help.book.BookHelp
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.UserAgentPool
import com.k.reader.model.webBook.WebBook
import com.k.reader.model.ReadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 智慧換源管理器（十大旗艦功能：智慧換源）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（斷源不斷讀，全自動找「同一本書」）：
 *   書源失效是小說閱讀的第一大痛點——正讀到關鍵章節，源掛了。
 *   本管理器把「換源」從手動重搜重選，壓成一鍵／全自動：
 *
 *    1. 候選收集：啟用＋可搜尋＋非當前源＋「非失效分組」
 *       （Stage 3 BookSourceHealthManager 檢核寫回的失效標記
 *       直接把壞源擋在門外），並以 respondTime（Stage 3 實測
 *       延遲）升序排隊——快源優先驗證；
 *    2. 並發同名搜尋：Semaphore 限額並發 × 單源限流 × UA 身分
 *       輪替 × SSL 寬容重試（Stage 2 三件套全程護航）；
 *    3. 相似度評分：書名規範化（去空白標點＋小寫）後 Levenshtein
 *       相似度，門檻 60%（規格值）；
 *    4. 綜合排序：相似度權重 70% ＋ 回應延遲權重 30%（0~3000ms
 *       線性映射 100~0 分）；
 *    5. 章節對齊：當前章節標題先精確後模糊（就近）比對，退回
 *       比例定位（舊目錄第 i 章 ↔ 新目錄等比例位置）；
 *    6. 正文驗證：對齊章節正文 ≥ 150 字（與檢核規格一致）才放行
 *       ——「換完源章節內容是空的」比換不了更糟；
 *    7. 落地：書籍欄位寫回（bookSourceUrl／tocUrl／進度）、
 *       目錄寫入、閱讀頁原地重載——進度無縫接軌。
 *
 *  入口：
 *   · switchManually(activity)：閱讀頁選單「換源」（switch_source）
 *   · switchAutomatically(activity)：正文連續載入失敗時的靜默
 *     自動換源（switch_auto）——建議由 ContentViewModel 錯誤
 *     分支連續失敗 3 次後呼叫。
 *
 *  ─── Legado 引擎接縫區 ────────────────────────────────────
 *  searchBooks / loadToc / loadContent / applySwitch 四個私有函數
 *  是本管理器與 Legado 書源引擎／閱讀核心的唯一接觸點（鏡射
 *  SearchModel / BookInfoViewModel＋TocViewModel / ContentViewModel /
 *  ChangeSourceActivity）。若套用的基底版本簽名略有差異，僅需對照
 *  上述 ViewModel／Activity 修正該區，評分、對齊、併發邏輯完全
 *  不受影響。
 */
object SmartSourceSwitcher {

    private const val TAG = "SmartSourceSwitcher"

    /** 同名書相似度門檻（規格：60%） */
    const val MIN_SIMILARITY_PERCENT = 60

    /** 換源搜尋跨源並發（與一鍵檢核同寬度：6） */
    const val SEARCH_CONCURRENCY = 6

    /** 對齊章節正文字數門檻（與檢核規格一致：150 字） */
    const val MIN_CONTENT_CHARS = 150

    /** 相似度權重 */
    const val WEIGHT_SIMILARITY = 0.7

    /** 回應延遲權重 */
    const val WEIGHT_RESPOND_TIME = 0.3

    private const val SEARCH_TIMEOUT_MS = 15_000L
    private const val TOC_TIMEOUT_MS = 20_000L
    private const val CONTENT_TIMEOUT_MS = 20_000L

    /** 參與搜尋的候選源上限（回應延遲升序取前 50） */
    private const val MAX_CANDIDATE_SOURCES = 50

    /** 疑似封鎖的錯誤訊息特徵（命中即換身分重試一次） */
    private val BLOCK_HINTS = listOf(
        "403", "429", "forbidden", "cloudflare", "too many",
        "blocked", "captcha", "频繁", "頻繁"
    )

    /** 管理器專用作用域（存活於 Activity 之外——切換必須完整落地） */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 換源進行中標記（防重入） */
    private val switching = AtomicBoolean(false)

    // ═════════════════ 資料模型 ═════════════════

    /** 搜尋命中的同名書候選（已評分） */
    data class SearchHit(
        val source: BookSource,
        val book: Book,
        val similarityPercent: Int,
        val respondTimeMs: Long,
        val score: Double
    )

    /** 換源成功結果（對齊完成、正文已驗證） */
    data class SwitchResult(
        val source: BookSource,
        val book: Book,
        val chapters: List<BookChapter>,
        val alignedIndex: Int,
        val similarityPercent: Int
    )

    // ═════════════════ 入口 ═════════════════

    /** 閱讀頁選單「換源」入口（switch_source） */
    fun switchManually(activity: FragmentActivity) {
        val book = ReadBook.book ?: return
        runSwitch(activity, book, automatic = false)
    }

    /**
     * 靜默自動換源入口（switch_auto）：
     * 建議由 ContentViewModel 正文連續載入失敗分支呼叫。
     */
    fun switchAutomatically(activity: FragmentActivity) {
        val book = ReadBook.book ?: return
        runSwitch(activity, book, automatic = true)
    }

    private fun runSwitch(activity: FragmentActivity, book: Book, automatic: Boolean) {
        if (!switching.compareAndSet(false, true)) return
        val dialog = SwitchProgressDialog(activity, automatic)
        managerScope.launch(Dispatchers.Main) {
            try {
                val sources = withContext(Dispatchers.IO) { candidateSources(book) }
                if (sources.isEmpty()) {
                    toastOnMain(activity.getString(R.string.switch_none))
                    return@launch
                }
                dialog.show(sources.size)
                val result = withContext(Dispatchers.IO) {
                    performSwitch(
                        book, sources,
                        onSearch = { done, total, name, similarity ->
                            dialog.updateSearch(done, total, name, similarity)
                        },
                        onAlign = { name, index, total ->
                            dialog.updateAlign(name, index, total)
                        }
                    )
                }
                if (result == null) {
                    toastOnMain(activity.getString(R.string.switch_none))
                } else {
                    val applied = withContext(Dispatchers.IO) { applySwitch(book, result) }
                    if (applied) {
                        toastOnMain(
                            activity.getString(R.string.switch_done, result.source.bookSourceName)
                        )
                    } else {
                        toastOnMain(activity.getString(R.string.error))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "換源中斷", e)
            } finally {
                switching.set(false)
                dialog.dismiss()
            }
        }
    }

    // ═════════════════ 換源主流程 ═════════════════

    /**
     * 換源管線：並發同名搜尋 → 評分排序 → 逐名對齊驗證（首名
     * 通過即回，其餘不再消耗流量）。
     */
    private suspend fun performSwitch(
        book: Book,
        sources: List<BookSource>,
        onSearch: suspend (done: Int, total: Int, sourceName: String, similarity: Int) -> Unit,
        onAlign: suspend (sourceName: String, alignedIndex: Int, totalChapters: Int) -> Unit
    ): SwitchResult? {
        val hits = searchSameNameBooks(book, sources, onSearch)
        if (hits.isEmpty()) return null
        for (hit in hits.sortedByDescending { it.score }) {
            onAlign(hit.source.bookSourceName, book.durChapterIndex, 0)
            val aligned = alignAndVerify(book, hit) ?: continue
            onAlign(
                hit.source.bookSourceName,
                aligned.alignedIndex,
                aligned.chapters.size
            )
            return aligned
        }
        return null
    }

    // ═════════════════ 候選收集 ═════════════════

    /**
     * 候選書源：啟用＋可搜尋＋非當前源＋非「失效」分組（Stage 3
     * 檢核標記），以 respondTime 升序（快源先試）取前 50。
     */
    private suspend fun candidateSources(book: Book): List<BookSource> =
        withContext(Dispatchers.IO) {
            val failedLabel = AppContext.ctx.getString(R.string.group_failed)
            val currentUrl = book.bookSourceUrl
            runCatching {
                /* 接縫：3.0 為 allBookSourceNotLive（若版本差異請對照 BookSourceDao） */
                AppContext.db.bookSourceDao().all
            }.getOrNull().orEmpty()
                .filter { it.enabled == true && !it.searchUrl.isNullOrBlank() }
                .filter { it.bookSourceUrl != currentUrl }
                .filter { failedLabel !in SourceGroupManager.splitGroups(it.bookSourceGroup) }
                .sortedBy { it.respondTime ?: Long.MAX_VALUE }
                .take(MAX_CANDIDATE_SOURCES)
        }

    // ═════════════════ 並發同名搜尋 ═════════════════

    private suspend fun searchSameNameBooks(
        book: Book,
        sources: List<BookSource>,
        onSearch: suspend (done: Int, total: Int, sourceName: String, similarity: Int) -> Unit
    ): List<SearchHit> = coroutineScope {
        val total = sources.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val semaphore = Semaphore(SEARCH_CONCURRENCY.coerceIn(1, 16))
        val expectedTitle = normalizeTitle(book.name.orEmpty())

        sources.map { source ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val hits = searchOne(book, source, expectedTitle)
                    val doneNow = done.incrementAndGet()
                    val best = hits.maxByOrNull { it.similarityPercent }
                    onSearch(doneNow, total, source.bookSourceName, best?.similarityPercent ?: 0)
                    hits
                }
            }
        }.awaitAll().flatten()
    }

    /** 單源同名搜尋＋評分（防封鎖重試護航） */
    private suspend fun searchOne(
        book: Book,
        source: BookSource,
        expectedTitle: String
    ): List<SearchHit> {
        if (expectedTitle.isEmpty()) return emptyList()
        SourceRateLimiter.awaitPermit(source.bookSourceUrl)
        val t0 = SystemClock.elapsedRealtime()
        val results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            guardedRetry(source) { searchBooks(book.name.orEmpty(), source) }
        }?.getOrDefault(emptyList()).orEmpty()
        val liveRespondTime = SystemClock.elapsedRealtime() - t0

        val out = mutableListOf<SearchHit>()
        for (candidate in results) {
            val name = candidate.name.orEmpty()
            if (name.isBlank()) continue
            val similarity = similarityPercent(expectedTitle, normalizeTitle(name))
            if (similarity < MIN_SIMILARITY_PERCENT) continue
            val respondTime = source.respondTime ?: liveRespondTime
            val score = WEIGHT_SIMILARITY * similarity +
                WEIGHT_RESPOND_TIME * respondTimeScore(respondTime)
            out.add(SearchHit(source, candidate, similarity, respondTime, score))
        }
        return out
    }

    /** 回應延遲 → 0~100 分（0ms→100 分；≥3000ms→0 分） */
    private fun respondTimeScore(respondTimeMs: Long): Double =
        (3000L - respondTimeMs.coerceIn(0L, 3000L)).toDouble() / 30.0

    /**
     * 通用「防封鎖重試」框架（與 BookSourceHealthManager 同型）：
     * SSL 類 → 寬容重試一次；疑似封鎖 → 換身分重試一次；其他不重試。
     */
    private suspend fun <T> guardedRetry(
        source: BookSource,
        block: suspend () -> T
    ): Result<T> {
        val first = runCatching { block() }
        if (first.isSuccess) return first
        val error = first.exceptionOrNull()
        return when {
            SslTrustHelper.isSslError(error) -> {
                SourceRateLimiter.awaitPermit(source.bookSourceUrl)
                Log.w(TAG, "SSL 類失敗，重試一次：${source.bookSourceName}")
                runCatching { block() }
            }
            looksBlocked(error?.message) -> {
                UserAgentPool.clearSticky(source.bookSourceUrl)
                SourceRateLimiter.awaitPermit(source.bookSourceUrl)
                Log.w(TAG, "疑似封鎖，更換身分重試：${source.bookSourceName}")
                runCatching { block() }
            }
            else -> first
        }
    }

    private fun looksBlocked(message: String?): Boolean {
        if (message == null) return false
        return BLOCK_HINTS.any { message.contains(it, ignoreCase = true) }
    }

    // ═════════════════ 相似度 ═════════════════

    /** 書名規範化：去空白／中西標點·「」《》等＋小寫 */
    private fun normalizeTitle(raw: String): String =
        raw.replace(
            Regex("[\\s\\p{Punct}。，！？：；、「」『』《》〈〉…—～·]+"),
            ""
        ).lowercase()

    /**
     * 規範化 Levenshtein 相似度（0~100）：
     * 100 × (1 - 編輯距離 / 最長字串長度)。
     */
    fun similarityPercent(a: String, b: String): Int {
        if (a.isEmpty() && b.isEmpty()) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val distance = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return ((1.0 - distance.toDouble() / maxLen) * 100).toInt().coerceIn(0, 100)
    }

    /** 雙行滾動陣列 Levenshtein 編輯距離（O(min²) 記憶體） */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    // ═════════════════ 章節對齊與正文驗證 ═════════════════

    /** 詳情＋目錄＋章節對齊＋對齊章節正文 150 字驗證（全過才回非 null） */
    private suspend fun alignAndVerify(book: Book, hit: SearchHit): SwitchResult? {
        val source = hit.source
        val candidate = hit.book

        SourceRateLimiter.awaitPermit(source.bookSourceUrl)
        val chapters = withTimeoutOrNull(TOC_TIMEOUT_MS) {
            coroutineScope {
                WebBook.getBookInfoAwait(source, candidate)
                WebBook.getChapterListAwait(source, candidate).getOrDefault(emptyList())
            }
        } ?: return null
        if (chapters.isEmpty()) return null

        val alignedIndex = alignChapterIndex(book, chapters)
        val chapter = chapters.getOrNull(alignedIndex) ?: return null

        // 對齊章節正文驗證（與檢核同規格：去空白 ≥ 150 字）
        SourceRateLimiter.awaitPermit(source.bookSourceUrl)
        val contentChars = withTimeoutOrNull(CONTENT_TIMEOUT_MS) {
            WebBook.getContentAwait(source, candidate, chapter)
            BookHelp.getContent(candidate, chapter)?.count { !it.isWhitespace() } ?: 0
        } ?: 0
        if (contentChars < MIN_CONTENT_CHARS) {
            Log.w(TAG, "換源驗證失敗（正文 $contentChars 字）：${source.bookSourceName}")
            return null
        }

        return SwitchResult(source, candidate, chapters, alignedIndex, hit.similarityPercent)
    }

    /**
     * 章節對齊三級策略：
     *   1. 當前章節標題精確命中（規範化後全等）；
     *   2. 標題包含式模糊命中（就近——同名番外章不搶位）；
     *   3. 比例定位：舊目錄第 i 章 ↔ 新目錄 round(i × 新長 / 舊長)。
     */
    private suspend fun alignChapterIndex(book: Book, newToc: List<BookChapter>): Int {
        val currentTitle = normalizeTitle(book.durChapterTitle.orEmpty())
        if (currentTitle.length >= 4) {
            val exact = newToc.indexOfFirst {
                normalizeTitle(it.title.orEmpty()) == currentTitle
            }
            if (exact >= 0) return exact
            val fuzzy = newToc
                .withIndex()
                .filter {
                    val t = normalizeTitle(it.value.title.orEmpty())
                    t.contains(currentTitle) || currentTitle.contains(t)
                }
                .minByOrNull { kotlin.math.abs(it.index - book.durChapterIndex) }
            fuzzy?.let { return it.index }
        }
        val oldTocSize = oldChapterCountOf(book)
        if (oldTocSize > 0) {
            val proportional =
                (book.durChapterIndex.toDouble() * newToc.size / oldTocSize).toInt()
            return proportional.coerceIn(0, newToc.size - 1)
        }
        return 0
    }

    private suspend fun oldChapterCountOf(book: Book): Int = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：BookChapterDao.getChapterList（版本差異請對照） */
            AppContext.db.bookChapterDao().getChapterList(book.bookUrl).size
        }.getOrDefault(0)
    }

    // ═════════════════ 切換落地 ═════════════════

    /** 書籍欄位寫回＋目錄寫入＋閱讀頁原地重載（進度無縫接軌） */
    private suspend fun applySwitch(book: Book, result: SwitchResult): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                /* 接縫：對照 ChangeSourceActivity 的切換落地欄位 */
                book.apply {
                    bookSourceUrl = result.source.bookSourceUrl
                    tocUrl = result.book.tocUrl
                    durChapterIndex = result.alignedIndex
                    durChapterPos = 0
                    durChapterTime = System.currentTimeMillis()
                    durChapterTitle = result.chapters[result.alignedIndex].title
                }
                AppContext.db.bookDao().insert(book)
                AppContext.db.bookChapterDao().insert(*result.chapters.toTypedArray())
                /* 接縫：閱讀頁原地重載（loadContent(index, upContent, resetPageOffset)） */
                ReadBook.loadContent(result.alignedIndex, true, true)
            }.onFailure { e ->
                Log.w(TAG, "換源落地失敗：${book.name}", e)
            }.isSuccess
        }

    // ═════════════════ Legado 引擎接縫區 ═════════════════

    /** 搜尋（新版 WebBook.searchBookAwait 直取；SearchBook.toBook 轉書） */
    private suspend fun searchBooks(key: String, source: BookSource): List<Book> =
        coroutineScope {
            val out = mutableListOf<Book>()
            runCatching {
                out.addAll(WebBook.searchBookAwait(source, key).map { it.toBook() })
            }.onFailure { e ->
                Log.w(TAG, "換源搜尋失敗：${source.bookSourceName}", e)
            }
            out
        }

    // ═════════════════ 換源進度對話框（零布局檔——純程式構建） ═════════════════

    /**
     * 換源進度對話框：標題（換源／自動換源）→ 搜尋階段明細（源名 ·
     * 相似度 %）→ 對齊階段明細（源名 · 章節比對）。
     * 執行緒安全：update 系列可自任意執行緒呼叫。
     */
    private class SwitchProgressDialog(
        private val activity: FragmentActivity,
        automatic: Boolean
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())
        private var dialog: AlertDialog? = null
        private var progressBar: ProgressBar? = null
        private var tvStatus: TextView? = null
        private var tvDetail: TextView? = null

        fun show(total: Int) {
            mainHandler.post {
                val pad = dp(22)
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(pad, pad / 2, pad, pad / 2)
                }
                val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
                bar.isIndeterminate = false
                bar.max = total.coerceAtLeast(1)
                root.addView(
                    bar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                val status = TextView(activity).apply {
                    setText(R.string.switch_searching)
                    textSize = 15f
                    val gap = dp(12)
                    setPadding(0, gap, 0, dp(4))
                }
                root.addView(
                    status,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                val detail = TextView(activity).apply {
                    textSize = 13f
                }
                root.addView(
                    detail,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                progressBar = bar
                tvStatus = status
                tvDetail = detail
                dialog = AlertDialog.Builder(activity)
                    .setTitle(if (automaticTitle) R.string.switch_auto else R.string.switch_source)
                    .setView(root)
                    .setCancelable(true)
                    .create()
                    .also { it.show() }
            }
        }

        private val automaticTitle = automatic

        /** 搜尋階段：已完成數／總數／當前源／最佳相似度 */
        fun updateSearch(done: Int, total: Int, sourceName: String, similarity: Int) {
            mainHandler.post {
                progressBar?.let { bar ->
                    bar.max = total.coerceAtLeast(1)
                    bar.progress = done.coerceIn(0, total)
                }
                tvDetail?.text = buildString {
                    append(sourceName)
                    append(" · ")
                    append(activity.getString(R.string.switch_similarity, similarity))
                }
            }
        }

        /** 對齊階段：章節比對（對齊章節序號／新目錄長度） */
        fun updateAlign(sourceName: String, alignedIndex: Int, totalChapters: Int) {
            mainHandler.post {
                tvDetail?.text = buildString {
                    append(sourceName)
                    append(" · ")
                    append(
                        activity.getString(
                            R.string.switch_chapter_match,
                            if (totalChapters > 0) alignedIndex + 1 else 0,
                            if (totalChapters > 0) totalChapters else alignedIndex
                        )
                    )
                }
            }
        }

        fun dismiss() {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.post { dialog?.dismiss() }
        }

        private fun dp(value: Int): Int =
            (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    }

    // ═════════════════ 工具 ═════════════════

    private fun toastOnMain(text: String) {
        mainHandler.post {
            Toast.makeText(AppContext.ctx, text, Toast.LENGTH_SHORT).show()
        }
    }
}

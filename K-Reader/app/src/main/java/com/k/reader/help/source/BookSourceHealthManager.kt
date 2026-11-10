package com.k.reader.help.source

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 書源完整鏈路檢核管理器（十大旗艦功能：真實鏈路檢核）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（防「假活源」誤判）：
 *   傳統檢核只 ping 首頁——首頁可達但搜尋／目錄／正文掛掉的
 *   書源會被誤判為有效。本管理器走「真實讀者動線」三步全鏈路：
 *
 *    1. 關鍵字搜尋（SearchModel 動線）——有結果才算活；
 *    2. 詳情＋目錄（BookInfoViewModel / TocViewModel 動線）
 *       ——章節清單非空才算活；
 *    3. 首章正文（ContentViewModel 動線）——去空白字數達
 *       150 字門檻（對應字串規格 verify_content_short）才算活。
 *
 *  三步全過 → 有效；任一步失敗 → 失效，並自動歸入「失效」分組
 *  （SourceGroupManager.syncBookSourceFailureGroup）。
 *
 *  防封鎖紀律（直接消費 Stage 2 三件套）：
 *   · SourceRateLimiter：每次引擎呼叫前 awaitPermit——「一鍵併發
 *     檢核」本身就是高頻行為，絕不能反而把書源打成封鎖；
 *   · UserAgentPool：疑似封鎖（403／429／Cloudflare 特徵）時
 *     clearSticky 換一支身分，重試一次；
 *   · SslTrustHelper：SSL 憑證類失敗分類判定後重試一次（基底
 *     OkHttp 若已套用寬容設定即可直接通過）。
 *
 *  併發模型：Semaphore 限額並發（預設 6）× 單源限流——並發寬度
 *  跨「不同」書源，單源節奏仍是擬真閱讀速度。
 *
 *  檢核後寫回 BookSource.respondTime（實測搜尋延遲），換源排序
 *  與來源列表即時受益。
 *
 *  ─── Legado 引擎接縫區 ────────────────────────────────────
 *  searchBooks / loadChapterList / loadFirstChapterText 三個私有
 *  函數是本管理器與 Legado 書源引擎的唯一接觸點，分別鏡射
 *  SearchModel / BookInfoViewModel＋TocViewModel / ContentViewModel
 *  內部的公開呼叫（與 RandomTrialManager 之接縫完全同型）。若套用
 *  的基底版本簽名略有差異，僅需對照上述 ViewModel 修正該區，
 *  其餘所有邏邏輯（併發、重試、分組、UI）完全不受影響。
 *
 *  整合點：
 *   · 書源管理頁工具列「一鍵併發檢核」按鈕 → startVerifyAll(activity)
 *   · 進度對話框布局：res/layout/dialog_source_verify.xml
 */
object BookSourceHealthManager {

    private const val TAG = "BookSourceHealthManager"

    /** 首章正文字數門檻（規格：150 字，與字串 verify_content_short 一致） */
    const val MIN_CONTENT_CHARS = 150

    /** 併發上限（跨源並發；單源節奏由 SourceRateLimiter 控制） */
    const val DEFAULT_CONCURRENCY = 6

    private const val SEARCH_TIMEOUT_MS = 15_000L
    private const val TOC_TIMEOUT_MS = 20_000L
    private const val CONTENT_TIMEOUT_MS = 20_000L

    /** 進度回呼步驟碼（0 搜尋／1 目錄／2 正文／3 單源完成） */
    const val STEP_SEARCH = 0
    const val STEP_TOC = 1
    const val STEP_CONTENT = 2
    const val STEP_DONE = 3

    /** 管理器專用作用域（存活於 Activity 之外——檢核結果必須完整寫回） */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一鍵併發檢核進行中標記（防重入） */
    private val running = AtomicBoolean(false)

    /**
     * 檢核用搜尋關鍵字池（刻意使用簡體——絕大多數書源站點為簡中站，
     * 以簡體關鍵字命中率最高）。與 RandomTrialManager 的池子獨立
     * 維護、互不共用狀態；每輪檢核前洗牌，把單一關鍵字的站點負載
     * 分散到不同來源上。
     */
    private val SEARCH_KEYWORDS = listOf(
        "剑", "仙", "都市", "重生", "江湖", "少年",
        "星辰", "传说", "神话", "王朝", "山海", "人间"
    )

    /** 疑似封鎖的錯誤訊息特徵（命中即換身分重試一次） */
    private val BLOCK_HINTS = listOf(
        "403", "429", "forbidden", "cloudflare", "too many",
        "blocked", "captcha", "频繁", "頻繁"
    )

    // ═════════════════ 檢核報告 ═════════════════

    /**
     * 單一書源檢核報告。
     *
     * @param searchOk       步驟一：關鍵字搜尋有結果
     * @param tocOk          步驟二：詳情＋目錄取回非空章節清單
     * @param contentOk      步驟三：首章正文達 [MIN_CONTENT_CHARS] 字數門檻
     * @param contentChars   首章正文字數（去空白計數）
     * @param respondTimeMs  搜尋步實測延遲（已寫回 BookSource.respondTime）
     * @param totalMs        本源檢核總耗時（含限流等待）
     * @param identityRotated 曾因疑似封鎖更換 UA 身分重試
     * @param sslRetryUsed   曾觸發 SSL 類重試
     */
    data class VerifyReport(
        val bookSourceUrl: String,
        val bookSourceName: String,
        val searchOk: Boolean,
        val tocOk: Boolean,
        val contentOk: Boolean,
        val contentChars: Int,
        val respondTimeMs: Long,
        val totalMs: Long,
        val identityRotated: Boolean,
        val sslRetryUsed: Boolean
    ) {
        /** 三步全過才算有效（防「首頁活、正文死」的假活源） */
        val ok: Boolean get() = searchOk && tocOk && contentOk

        /** UI 現成文案（直接消費 strings.xml 前瞻鍵） */
        fun describe(): String {
            val ctx = AppContext.ctx
            return when {
                !searchOk -> ctx.getString(R.string.verify_search_fail)
                !tocOk -> ctx.getString(R.string.verify_toc_fail)
                !contentOk -> ctx.getString(R.string.verify_content_short, contentChars)
                else -> ctx.getString(R.string.verify_content_ok, contentChars)
            }
        }
    }

    // ═════════════════ 一鍵併發檢核入口（含進度對話框） ═════════════════

    /**
     * 一鍵併發檢核入口（書源管理頁工具列按鈕呼叫）。
     *
     * 流程：收集啟用可搜尋書源 → 進度對話框 → 併發檢核
     * → 失效源自動歸入「失效」分組（SourceGroupManager）。
     *
     * 註：作用域為管理器層級（SupervisorJob），使用者關閉對話框僅
     * 隱藏進度顯示，背景檢核仍會完成並寫回結果——檢核全程限流、
     * 輕量可控，不構成負擔。
     */
    fun startVerifyAll(activity: FragmentActivity) {
        if (!running.compareAndSet(false, true)) return
        val dialog = VerifyProgressDialog(activity)
        managerScope.launch(Dispatchers.Main) {
            try {
                val sources = withContext(Dispatchers.IO) { collectVerifiableSources() }
                if (sources.isEmpty()) {
                    Toast.makeText(activity, R.string.verify_no_valid_source, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                dialog.show(sources.size)
                val reports = withContext(Dispatchers.IO) {
                    verifyAll(sources, onProgress = { done, total, name, step ->
                        dialog.update(done, total, name, step)
                    })
                }
                val valid = reports.count { it.ok }
                dialog.finish(valid, reports.size - valid)
                applyFailureGroups(reports)
            } catch (e: Exception) {
                Log.w(TAG, "併發檢核中斷", e)
            } finally {
                running.set(false)
                dialog.dismiss()
            }
        }
    }

    /** 收集可檢核書源：啟用且具備搜尋網址者 */
    private suspend fun collectVerifiableSources(): List<BookSource> = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：3.0 為 allBookSourceNotLive（若版本差異請對照 BookSourceDao） */
            AppContext.db.bookSourceDao().all
        }.getOrNull().orEmpty()
            .filter { it.enabled == true && !it.searchUrl.isNullOrBlank() }
    }

    // ═════════════════ 併發檢核核心 ═════════════════

    /**
     * 併發檢核一批書源。
     *
     * @param concurrency 並發寬度上限（跨源），建議 4~8
     * @param onProgress  進度回呼：(已完成數, 總數, 當前源名稱, 步驟碼 STEP_*)
     * @param onSourceDone 單源完成回呼（報告逐一交付，UI 可即時上色）
     */
    suspend fun verifyAll(
        sources: List<BookSource>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: suspend (done: Int, total: Int, sourceName: String, step: Int) -> Unit = { _, _, _, _ -> },
        onSourceDone: suspend (VerifyReport) -> Unit = { _ -> }
    ): List<VerifyReport> = coroutineScope {
        if (sources.isEmpty()) return@coroutineScope emptyList()
        val total = sources.size
        val done = AtomicInteger(0)
        val semaphore = Semaphore(concurrency.coerceIn(1, 16))
        val keywords = SEARCH_KEYWORDS.shuffled()

        sources.mapIndexed { index, source ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val keyword = keywords[index % keywords.size]
                    val report = verifySource(source, keyword) { step ->
                        onProgress(done.get(), total, source.bookSourceName, step)
                    }
                    val doneNow = done.incrementAndGet()
                    onSourceDone(report)
                    onProgress(doneNow, total, source.bookSourceName, STEP_DONE)
                    report
                }
            }
        }.awaitAll()
    }

    /**
     * 單源三步鏈路檢核（永不拋例外，一律以 [VerifyReport] 交卷）。
     *
     * @param keyword 搜尋用關鍵字（呼叫端輪替分發）
     * @param onStep  步驟切換回呼（STEP_SEARCH / STEP_TOC / STEP_CONTENT）
     */
    suspend fun verifySource(
        source: BookSource,
        keyword: String,
        onStep: suspend (Int) -> Unit = { _ -> }
    ): VerifyReport {
        val t0 = SystemClock.elapsedRealtime()
        var identityRotated = false
        var sslRetryUsed = false

        // ── 步驟一：關鍵字搜尋 ──
        onStep(STEP_SEARCH)
        SourceRateLimiter.awaitPermit(source.bookSourceUrl)
        val searchStart = SystemClock.elapsedRealtime()
        val searchResult = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            guardedRetry(source, { rotated, ssl ->
                identityRotated = identityRotated || rotated
                sslRetryUsed = sslRetryUsed || ssl
            }) { searchBooks(keyword, source) }
        }
        val respondTimeMs = SystemClock.elapsedRealtime() - searchStart
        val books = searchResult?.getOrDefault(emptyList()).orEmpty()
            .filter { !it.name.isNullOrBlank() }
        val searchOk = books.isNotEmpty()

        if (!searchOk) {
            Log.w(TAG, "檢核失敗（搜尋）：${source.bookSourceName}")
            persistRespondTime(source, respondTimeMs)
            return VerifyReport(
                source.bookSourceUrl, source.bookSourceName,
                false, false, false, 0,
                respondTimeMs, SystemClock.elapsedRealtime() - t0,
                identityRotated, sslRetryUsed
            )
        }

        // ── 步驟二：書籍詳情＋目錄 ──
        val book = books.first()
        onStep(STEP_TOC)
        SourceRateLimiter.awaitPermit(source.bookSourceUrl)
        val tocResult = withTimeoutOrNull(TOC_TIMEOUT_MS) {
            guardedRetry(source, { rotated, ssl ->
                identityRotated = identityRotated || rotated
                sslRetryUsed = sslRetryUsed || ssl
            }) { loadChapterList(book, source) }
        }
        val chapters = tocResult?.getOrDefault(emptyList()).orEmpty()
        val tocOk = chapters.isNotEmpty()

        if (!tocOk) {
            Log.w(TAG, "檢核失敗（目錄）：${source.bookSourceName}")
            persistRespondTime(source, respondTimeMs)
            return VerifyReport(
                source.bookSourceUrl, source.bookSourceName,
                true, false, false, 0,
                respondTimeMs, SystemClock.elapsedRealtime() - t0,
                identityRotated, sslRetryUsed
            )
        }

        // ── 步驟三：首章正文 ──
        val firstChapter = chapters.first()
        onStep(STEP_CONTENT)
        SourceRateLimiter.awaitPermit(source.bookSourceUrl)
        val contentResult = withTimeoutOrNull(CONTENT_TIMEOUT_MS) {
            guardedRetry(source, { rotated, ssl ->
                identityRotated = identityRotated || rotated
                sslRetryUsed = sslRetryUsed || ssl
            }) { loadFirstChapterText(book, firstChapter, source) }
        }
        val contentChars = contentResult?.getOrNull()?.count { !it.isWhitespace() } ?: 0
        val contentOk = contentChars >= MIN_CONTENT_CHARS

        if (!contentOk) {
            Log.w(TAG, "檢核失敗（正文 ${contentChars} 字）：${source.bookSourceName}")
        }
        persistRespondTime(source, respondTimeMs)
        return VerifyReport(
            source.bookSourceUrl, source.bookSourceName,
            true, true, contentOk, contentChars,
            respondTimeMs, SystemClock.elapsedRealtime() - t0,
            identityRotated, sslRetryUsed
        )
    }

    /**
     * 通用「防封鎖重試」框架：首跑失敗後依錯誤特徵分流處置——
     *   · SSL 憑證類 → 重試一次（基底 OkHttp 已套 SslTrustHelper
     *     寬容設定時即可通過；未套用亦無副作用）；
     *   · 疑似封鎖 → 換身分（clearSticky 解除黏住 → 下次分發新 UA）
     *     後重試一次；
     *   · 其他錯誤 → 不重試（逾時／解析失敗重試亦無益）。
     */
    private suspend fun <T> guardedRetry(
        source: BookSource,
        flags: (rotated: Boolean, ssl: Boolean) -> Unit,
        block: suspend () -> T
    ): Result<T> {
        val first = runCatching { block() }
        if (first.isSuccess) return first
        val error = first.exceptionOrNull()
        return when {
            SslTrustHelper.isSslError(error) -> {
                flags(false, true)
                SourceRateLimiter.awaitPermit(source.bookSourceUrl)
                Log.w(TAG, "SSL 類失敗，重試一次：${source.bookSourceName}")
                runCatching { block() }
            }
            looksBlocked(error?.message) -> {
                flags(true, false)
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

    /** 檢核後把實測回應延遲寫回書源（換源排序／來源列表即時受益） */
    private suspend fun persistRespondTime(source: BookSource, latencyMs: Long) =
        withContext(Dispatchers.IO) {
            runCatching {
                source.respondTime = latencyMs
                /* 接縫：Room 寫回（BaseDao 泛型 insertOrUpdate） */
                AppContext.db.bookSourceDao().insert(source)
            }.onFailure { e ->
                Log.w(TAG, "respondTime 寫回失敗：${source.bookSourceName}", e)
            }
        }

    /** 檢核完成後同步「失效」分組（失敗者標記、康復者移除） */
    suspend fun applyFailureGroups(reports: List<VerifyReport>) = withContext(Dispatchers.IO) {
        val failedUrls = reports.filter { !it.ok }.map { it.bookSourceUrl }
        SourceGroupManager.syncBookSourceFailureGroup(failedUrls)
    }

    // ═════════════════ Legado 引擎接縫區 ═════════════════

    /** 搜尋（新版 WebBook.searchBookAwait 直取；失敗直接拋出） */
    private suspend fun searchBooks(key: String, source: BookSource): List<Book> =
        WebBook.searchBookAwait(source, key).map { it.toBook() }

    /** 詳情＋目錄（新版 getBookInfoAwait＋getChapterListAwait 組合） */
    private suspend fun loadChapterList(
        book: Book,
        source: BookSource
    ): List<BookChapter> = coroutineScope {
        WebBook.getBookInfoAwait(source, book)
        WebBook.getChapterListAwait(source, book).getOrDefault(emptyList())
    }

    /** 首章正文（鏡射 ContentViewModel：觸發抓取寫入快取後讀回） */
    private suspend fun loadFirstChapterText(
        book: Book,
        chapter: BookChapter,
        source: BookSource
    ): String? = coroutineScope {
        WebBook.getContentAwait(source, book, chapter)
        BookHelp.getContent(book, chapter)
    }
}

/**
 * 併發檢核進度對話框（dialog_source_verify.xml）。
 *
 * 執行緒安全：update／finish 可自任意執行緒呼叫，內部一律調度至
 * 主執行緒。生命週期：finish() 顯示檢核結果並短暫停留後自動關閉；
 * 在此之前 dismiss() 立即收掉對話框（異常或使用者取消情境）。
 */
private class VerifyProgressDialog(private val activity: FragmentActivity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var tvProgress: TextView? = null
    private var tvDetail: TextView? = null

    @Volatile
    private var finished = false

    /** 展示對話框（total 為本次檢核的來源總數） */
    fun show(total: Int) {
        mainHandler.post {
            val view = activity.layoutInflater.inflate(R.layout.dialog_source_verify, null)
            progressBar = view.findViewById(R.id.progress_verify)
            tvProgress = view.findViewById(R.id.tv_verify_progress)
            tvDetail = view.findViewById(R.id.tv_verify_detail)
            progressBar?.apply {
                isIndeterminate = false
                max = total.coerceAtLeast(1)
                progress = 0
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.verify_all)
                .setView(view)
                .setCancelable(true)
                .create()
                .also { it.show() }
        }
    }

    /** 進度更新（step 見 BookSourceHealthManager.STEP_* 常數） */
    fun update(done: Int, total: Int, sourceName: String, step: Int) {
        mainHandler.post {
            progressBar?.let { bar ->
                bar.max = total.coerceAtLeast(1)
                bar.progress = done.coerceIn(0, total)
            }
            tvDetail?.text = buildString {
                append(sourceName)
                append(" · ")
                append(stepLabelOf(step))
            }
        }
    }

    /** 檢核完成：顯示結果文案，短暫停留後自動關閉 */
    fun finish(valid: Int, invalid: Int) {
        finished = true
        mainHandler.post {
            tvProgress?.text = activity.getString(R.string.verify_result, valid, invalid)
            progressBar?.let { bar -> bar.progress = bar.max }
            tvDetail?.setText(R.string.done)
            mainHandler.postDelayed({ dialog?.dismiss() }, RESULT_LINGER_MS)
        }
    }

    /** 立即關閉（finish 之後由自動關閉接手，避免吃掉結果畫面） */
    fun dismiss() {
        if (finished) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { dialog?.dismiss() }
    }

    private fun stepLabelOf(step: Int): String = when (step) {
        BookSourceHealthManager.STEP_SEARCH -> activity.getString(R.string.verify_step_search)
        BookSourceHealthManager.STEP_TOC -> activity.getString(R.string.verify_step_toc)
        BookSourceHealthManager.STEP_CONTENT -> activity.getString(R.string.verify_step_content)
        else -> activity.getString(R.string.done)
    }

    private companion object {
        const val RESULT_LINGER_MS = 2400L
    }
}

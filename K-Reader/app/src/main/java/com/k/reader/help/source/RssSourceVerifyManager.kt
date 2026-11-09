package com.k.reader.help.source

import android.os.SystemClock
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.RssSource
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 訂閱源檢核管理器（十大旗艦功能：真實鏈路檢核）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（擬真瀏覽器直訪，而非機器人探測）：
 *   訂閱源（RSS／Atom）站點多有防爬機制，乾淨的 HEAD／無標頭
 *   GET 會直接吃 403。本管理器以「真人打開首頁文章」的動線檢核：
 *
 *    1. GET + 瀏覽器級完整標頭（UserAgentPool.browserHeaders()：
 *       動態 UA／Accept／Accept-Language／快取控制）；
 *    2. 動態 Referer：以 sourceUrl 推導「站點根網址」作為來源頁，
 *       模擬「從首頁點進文章」的瀏覽動線；
 *    3. Feed 嗅探：RSS（含 RDF）／Atom／JSON Feed／HTML 網頁型
 *       四類判定——HTML 網頁型訂閱源為合法型態（有實質內容即
 *       通過），RSS／Atom 則要求解析出至少一個條目。
 *
 *  防封鎖紀律（直接消費 Stage 2 三件套）：
 *   · SourceRateLimiter.awaitPermit：單源 700ms+ 節奏；
 *   · UserAgentPool：403／429 等疑似封鎖 → clearSticky 換身分，
 *     下一輪 browserHeaders() 自動分發新 UA（動態 UA 規格）；
 *   · SslTrustHelper：SSL 憑證類失敗 → tolerantClient() 寬容
 *     通道就地重試一次（老站憑證過期／自簽常態）。
 *
 *  檢核結果交由 SourceGroupManager.syncRssSourceFailureGroup()
 *  標記「失效」分組，與書源側（BookSourceHealthManager）行為對稱。
 *
 *  本管理器直接操作 OkHttp（不經 Legado Rss 解析器）——訂閱源
 *  「可達性＋格式合法性」檢核無需完整解析條目，輕量即可信任。
 */
object RssSourceVerifyManager {

    private const val TAG = "RssSourceVerifyManager"

    /** 併發上限（跨源並發；單源節奏由 SourceRateLimiter 控制） */
    const val DEFAULT_CONCURRENCY = 6

    /** 單源 GET 逾時（連線 12s／讀取 20s，見 strictClient） */
    private const val CONNECT_TIMEOUT_S = 12L
    private const val READ_TIMEOUT_S = 20L

    /** Feed 內容嗅探上限（逾此不讀，防超大回應體拖慢檢核） */
    private const val MAX_BODY_BYTES: Long = 256L * 1024L

    /** HTML 網頁型訂閱源的最低實質內容量（位元組） */
    private const val MIN_HTML_BODY_BYTES = 512

    /** Feed 嗅探掃描的文件頭長度（RSS/Atom 宣告皆在文件頭） */
    private const val SNIFF_HEAD_CHARS = 4096

    // ═════════════════ HTTP 通道 ═════════════════

    /** 嚴格 SSL 客客戶端（憑證異常交由寬容通道處理） */
    private val strictClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** 檢核結局（密封類：成功帶內文，失敗帶特徵） */
    private sealed class HttpOutcome {
        data class Success(
            val code: Int,
            val body: String,
            val latencyMs: Long
        ) : HttpOutcome()

        data class Failure(
            val code: Int?,
            val error: String?,
            val sslRelated: Boolean,
            val latencyMs: Long
        ) : HttpOutcome()
    }

    /** 訂閱源內容型態 */
    enum class FeedKind { RSS, ATOM, JSON, HTML, UNKNOWN }

    /**
     * 單一訂閱源檢核結果。
     *
     * @param ok               擷取判定通過
     * @param httpCode         HTTP 狀態碼（網路層失敗時為 null）
     * @param latencyMs        單次請求實測延遲
     * @param feedKind         嗅探型態（RSS／Atom／JSON／HTML／未知）
     * @param itemCount        解析出的條目數（僅 RSS／Atom 有意義）
     * @param sslTolerantUsed  曾走 SSL 寬容重試通道
     * @param identityRotated  曾因疑似封鎖更換身分重試
     * @param errorText        失敗原因（成功時為 null；內部診斷用）
     */
    data class RssCheckResult(
        val sourceUrl: String,
        val sourceName: String,
        val ok: Boolean,
        val httpCode: Int?,
        val latencyMs: Long,
        val feedKind: FeedKind,
        val itemCount: Int,
        val sslTolerantUsed: Boolean,
        val identityRotated: Boolean,
        val errorText: String?
    ) {
        /** UI 現成文案（直接消費 strings.xml 前瞻鍵） */
        fun describe(): String =
            if (ok) {
                AppContext.ctx.getString(R.string.rss_verify_ok)
            } else {
                AppContext.ctx.getString(R.string.rss_verify_fail)
            }
    }

    // ═════════════════ 併發檢核 ═════════════════

    /**
     * 併發檢核一批訂閱源。
     *
     * @param concurrency 並發寬度上限（跨源）
     * @param onProgress  進度回呼：(已完成數, 總數, 當前源名稱)
     * @param onSourceDone 單源完成回呼
     */
    suspend fun verifyAll(
        sources: List<RssSource>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: suspend (done: Int, total: Int, sourceName: String) -> Unit = { _, _, _ -> },
        onSourceDone: suspend (RssCheckResult) -> Unit = { _ -> }
    ): List<RssCheckResult> = coroutineScope {
        if (sources.isEmpty()) return@coroutineScope emptyList()
        val total = sources.size
        val done = AtomicInteger(0)
        val semaphore = Semaphore(concurrency.coerceIn(1, 16))

        sources.map { source ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    onProgress(done.get(), total, source.sourceName)
                    val result = runCatching { verifySource(source) }
                        .getOrElse { e ->
                            Log.w(TAG, "檢核異常：${source.sourceName}", e)
                            RssCheckResult(
                                sourceUrl = source.sourceUrl,
                                sourceName = source.sourceName,
                                ok = false,
                                httpCode = null,
                                latencyMs = 0L,
                                feedKind = FeedKind.UNKNOWN,
                                itemCount = 0,
                                sslTolerantUsed = false,
                                identityRotated = false,
                                errorText = e.message ?: e.javaClass.simpleName
                            )
                        }
                    val doneNow = done.incrementAndGet()
                    onSourceDone(result)
                    onProgress(doneNow, total, source.sourceName)
                    result
                }
            }
        }.awaitAll()
    }

    /**
     * 一鍵檢核所有啟用中的訂閱源（訂閱源管理頁入口）：
     * 收集 → 併發檢核 → 失效者自動歸入「失效」分組。
     */
    suspend fun verifyAllEnabled(
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: suspend (done: Int, total: Int, sourceName: String) -> Unit = { _, _, _ -> }
    ): List<RssCheckResult> {
        val sources = collectEnabledSources()
        if (sources.isEmpty()) return emptyList()
        val results = verifyAll(sources, concurrency, onProgress)
        applyFailureGroups(results)
        return results
    }

    /** 收集啟用中的訂閱源 */
    private suspend fun collectEnabledSources(): List<RssSource> = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：3.0 為 allRssSourceNotLive（若版本差異請對照 RssSourceDao） */
            AppContext.db.rssSourceDao().allRssSourceNotLive
        }.getOrNull().orEmpty()
            .filter { it.enabled == true }
    }

    /** 檢核完成後同步「失效」分組（失敗者標記、康復者移除） */
    suspend fun applyFailureGroups(results: List<RssCheckResult>) = withContext(Dispatchers.IO) {
        val failedUrls = results.filter { !it.ok }.map { it.sourceUrl }
        SourceGroupManager.syncRssSourceFailureGroup(failedUrls)
    }

    // ═════════════════ 單源檢核 ═════════════════

    /** 單源檢核（永不拋例外，一律以 [RssCheckResult] 交卷） */
    suspend fun verifySource(source: RssSource): RssCheckResult {
        var identityRotated = false
        var sslTolerantUsed = false

        // 併發紀律：尊重單源限流（防封鎖三件套之一）
        SourceRateLimiter.awaitPermit(source.sourceUrl)

        val outcome = executeGuarded(source) { rotated, ssl ->
            identityRotated = identityRotated || rotated
            sslTolerantUsed = sslTolerantUsed || ssl
        }

        return when (outcome) {
            is HttpOutcome.Success -> {
                val (kind, itemCount) = classifyBody(outcome.body)
                val ok = when (kind) {
                    FeedKind.RSS, FeedKind.ATOM -> itemCount > 0
                    FeedKind.JSON -> outcome.body.contains("\"items\"")
                    FeedKind.HTML -> outcome.body.length >= MIN_HTML_BODY_BYTES
                    FeedKind.UNKNOWN -> false
                }
                val errorText: String? = if (ok) null else when (kind) {
                    FeedKind.RSS, FeedKind.ATOM -> "未解析到任何條目"
                    FeedKind.JSON -> "非 JSON Feed 結構"
                    FeedKind.HTML -> "網頁內容過短"
                    FeedKind.UNKNOWN -> "無法辨識的內容格式"
                }
                RssCheckResult(
                    sourceUrl = source.sourceUrl,
                    sourceName = source.sourceName,
                    ok = ok,
                    httpCode = outcome.code,
                    latencyMs = outcome.latencyMs,
                    feedKind = kind,
                    itemCount = itemCount,
                    sslTolerantUsed = sslTolerantUsed,
                    identityRotated = identityRotated,
                    errorText = errorText
                )
            }
            is HttpOutcome.Failure -> RssCheckResult(
                sourceUrl = source.sourceUrl,
                sourceName = source.sourceName,
                ok = false,
                httpCode = outcome.code,
                latencyMs = outcome.latencyMs,
                feedKind = FeedKind.UNKNOWN,
                itemCount = 0,
                sslTolerantUsed = sslTolerantUsed,
                identityRotated = identityRotated,
                errorText = outcome.error ?: "網路異常"
            )
        }
    }

    /**
     * 帶防封鎖分流的執行框架：
     *   · SSL 憑證類失敗 → 寬容通道（SslTrustHelper.tolerantClient()）
     *     重試一次；
     *   · 疑似封鎖（403／429／Cloudflare 特徵）→ 換身分
     *     （UserAgentPool.clearSticky → browserHeaders 重新分發）
     *     後重試一次；
     *   · 其他失敗 → 直接交卷（逾時／DNS 重試無益）。
     */
    private suspend fun executeGuarded(
        source: RssSource,
        flags: (rotated: Boolean, ssl: Boolean) -> Unit
    ): HttpOutcome {
        val first = tryCall(source, tolerant = false)
        if (first is HttpOutcome.Success) return first

        return when {
            first is HttpOutcome.Failure && first.sslRelated -> {
                flags(false, true)
                SourceRateLimiter.awaitPermit(source.sourceUrl)
                Log.w(TAG, "SSL 寬容重試：${source.sourceName}")
                tryCall(source, tolerant = true)
            }
            first is HttpOutcome.Failure && looksBlocked(first.code, first.error) -> {
                flags(true, false)
                UserAgentPool.clearSticky(source.sourceUrl)
                SourceRateLimiter.awaitPermit(source.sourceUrl)
                Log.w(TAG, "疑似封鎖，更換身分重試：${source.sourceName}")
                tryCall(source, tolerant = false)
            }
            else -> first
        }
    }

    /** 發出一次瀏覽器級 GET（含動態 Referer），回傳密封結局 */
    private fun tryCall(source: RssSource, tolerant: Boolean): HttpOutcome {
        val headers = UserAgentPool.browserHeaders(referer = siteRootOf(source.sourceUrl))
        val request = Request.Builder()
            .url(source.sourceUrl.trim())
            .headers(Headers.of(headers))
            .get()
            .build()
        val client = if (tolerant) SslTrustHelper.tolerantClient() else strictClient
        val t0 = SystemClock.elapsedRealtime()
        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val latencyMs = SystemClock.elapsedRealtime() - t0
                if (code in 200..399) {
                    /* peekBody：讀取上限內容且不消耗回應體（安全上限） */
                    val body = response.peekBody(MAX_BODY_BYTES).string()
                    HttpOutcome.Success(code, body, latencyMs)
                } else {
                    HttpOutcome.Failure(code, "HTTP $code", sslRelated = false, latencyMs = latencyMs)
                }
            }
        } catch (e: Exception) {
            HttpOutcome.Failure(
                code = null,
                error = e.message ?: e.javaClass.simpleName,
                sslRelated = SslTrustHelper.isSslError(e),
                latencyMs = SystemClock.elapsedRealtime() - t0
            )
        }
    }

    /** 疑似封鎖判定：狀態碼 403／429 或錯誤訊息特徵命中 */
    private fun looksBlocked(code: Int?, error: String?): Boolean {
        if (code == 403 || code == 429) return true
        if (error == null) return false
        return BLOCK_HINTS.any { error.contains(it, ignoreCase = true) }
    }

    private val BLOCK_HINTS = listOf(
        "403", "429", "forbidden", "cloudflare", "too many",
        "blocked", "captcha", "频繁", "頻繁"
    )

    /**
     * Feed 嗅探：判定內容型態與條目數。
     * （啟發式判定——僅掃描文件頭 4KB 判型，全文計數條目標籤；
     *   RSS 2.0 / RDF / Atom / JSON Feed / HTML 五類覆蓋絕大多數訂閱源）
     */
    internal fun classifyBody(body: String): Pair<FeedKind, Int> {
        val head = body.substring(0, minOf(body.length, SNIFF_HEAD_CHARS))
        return when {
            head.contains("<feed", ignoreCase = true) ->
                FeedKind.ATOM to Regex("<entry\\b", RegexOption.IGNORE_CASE).findAll(body).count()
            head.contains("<rss", ignoreCase = true) ||
                head.contains("<rdf:RDF", ignoreCase = true) ->
                FeedKind.RSS to Regex("<item\\b", RegexOption.IGNORE_CASE).findAll(body).count()
            head.trimStart().startsWith("{") ->
                FeedKind.JSON to 0
            head.contains("<html", ignoreCase = true) ||
                head.contains("<!DOCTYPE html", ignoreCase = true) ->
                FeedKind.HTML to 0
            else ->
                FeedKind.UNKNOWN to 0
        }
    }

    /**
     * 動態 Referer：由 sourceUrl 推導「站點根網址」。
     * 例：https://example.com/feed.xml → https://example.com/
     * （模擬「自首頁點進文章」的瀏覽動線；非正規網址回 null 不送）
     */
    internal fun siteRootOf(url: String): String? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd)
        val rest = trimmed.substring(schemeEnd + 3)
        if (rest.isBlank()) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank()) return null
        return "$scheme://$authority/"
    }
}

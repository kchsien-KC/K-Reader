package com.k.reader.help.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.k.reader.R
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — Cloudflare 過盾助手（十大旗艦功能：網路防封鎖）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（真實瀏覽器環境換取通行憑證）：
 *   純 HTTP 層（Stage 2 三件套）已把「疑似機器人」特徵壓到最低，
 *   但部分站點啟用 Cloudflare「受管理挑戰／Turnstile 人機驗證」，
 *   只發 cf_clearance Cookie 給「真跑過 JS 的真瀏覽器」。此時唯一
 *   可靠解法是把系統 WebView 抬上檯面：
 *
 *    1. 偵測：isShieldPage() 掃 HTML 特徵（Just a moment…／
 *       challenge-platform／__cf_chl／Turnstile 容器…）；
 *       isBlockedStatus() 命中 403／429／503；
 *    2. 過盾：彈出內嵌 WebView 對話框（可互動——使用者可自行
 *       勾選 Turnstile 核取方塊），背景每 500ms 輪詢 Cookie；
 *    3. 寫回：cf_clearance 落地（或頁面跳回目標站且標題正常）即
 *       判定通過，CookieManager.flush() 持久化——OkHttp 端的
 *       Cookie 儲存與 WebView 共用（Legado CookieStore 慣例），
 *       之後的純 HTTP 請求自動帶上過盾 Cookie；
 *    4. 身分配對：cf_clearance 與「取得當下的 User-Agent＋IP」
 *       綁定。本類以 UserAgentPool.uaFor(origin) 作為 WebView
 *       的 UA，並以 solvedUserAgent(origin) 另表鎖定「過盾 UA」
 *       ——即使其後 UA 池輪替／clearSticky 換身分重試，這個
 *       origin 的後續請求仍必須用同一支過盾 UA，否則 Cookie
 *       立即失效（常見誤區）。
 *
 *  ─── OkHttp 攔截層接縫 ────────────────────────────────────
 *  AnalyzeUrl / Response 攔截處：
 * ```
 * val prefix = body.take(4096)
 * if (CloudFlareHelper.detectFromResponse(code, prefix)
 *     && !CloudFlareHelper.hasClearanceCookie(sourceUrl)) {
 *     CloudFlareHelper.solveShield(activity, sourceUrl)   // 掛起等待
 * }
 * // 後續請求的 UA 改取：
 * CloudFlareHelper.solvedUserAgent(origin) ?: UserAgentPool.uaFor(origin)
 * ```
 *
 *  整合點：
 *   · 書源搜尋／正文抓取失敗且命中盾頁特徵 → solveShield()
 *   · Stage 3 檢核 BookSourceHealthManager 的「疑似封鎖」分支
 *     可先嘗試過盾再判定失效（避免把「有盾的好源」誤殺）
 */
object CloudFlareHelper {

    private const val TAG = "CloudFlareHelper"

    /** 過盾最長等待（規格：2 分鐘——含 Turnstile 人機勾選的互動時間） */
    const val DEFAULT_SOLVE_TIMEOUT_MS = 120_000L

    /** Cookie 輪詢間隔 */
    private const val COOKIE_POLL_INTERVAL_MS = 500L

    /** Cloudflare 過盾通行 Cookie 名 */
    const val CF_CLEARANCE_COOKIE = "cf_clearance"

    /** 盾頁／人機驗證頁 HTML 特徵（小寫比對；中英站點並列） */
    private val SHIELD_MARKERS = listOf(
        "just a moment", "checking your browser", "attention required",
        "cf-browser-verification", "cf-mitigated", "challenge-platform",
        "__cf_chl", "cf_chl_opt", "cf-turnstile", "turnstile-container",
        "enable javascript and cookies to continue", "請稍候片刻", "安全驗證"
    )

    /** 疑似遭封鎖／被盾攔下的 HTTP 狀態碼 */
    val BLOCK_STATUS_CODES = setOf(403, 429, 503)

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 過盾成功結果。
     *
     * @param url        目標網址（呼叫端原樣）
     * @param cookies    過盾後該站完整 Cookie 串（name=value; …）
     * @param userAgent  過盾時使用的 UA（後續請求必須沿用同一支）
     */
    data class ShieldSolved(
        val url: String,
        val cookies: String,
        val userAgent: String
    )

    /** 過盾 UA 對照表（origin → UA）：與 UA 池的黏住表刻意分離，見類註解第 4 點 */
    private val solvedUserAgents = ConcurrentHashMap<String, String>()

    // ═════════════════ 偵測 API ═════════════════

    /** HTTP 狀態碼是否為疑似封鎖／被盾 */
    fun isBlockedStatus(statusCode: Int): Boolean = statusCode in BLOCK_STATUS_CODES

    /** HTML 片段是否為 Cloudflare 盾頁（傳入回應內文前 4KB 即足夠） */
    fun isShieldPage(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val lower = html.lowercase()
        return SHIELD_MARKERS.any { lower.contains(it) }
    }

    /** OkHttp 攔截層快速判定：狀態碼或內文特徵任一命中 */
    fun detectFromResponse(statusCode: Int, bodyPrefix: String?): Boolean =
        isShieldPage(bodyPrefix) || isBlockedStatus(statusCode)

    /** 該站是否已持有過盾 Cookie（已有則無需再彈 WebView） */
    fun hasClearanceCookie(url: String): Boolean =
        readCookiesOf(url).contains(CF_CLEARANCE_COOKIE)

    /**
     * 過盾後的專屬 UA（cf_clearance 綁定身分）。
     * 未過盾前回 null——呼叫端退回 UserAgentPool.uaFor(origin)。
     */
    fun solvedUserAgent(origin: String): String? = solvedUserAgents[originKeyOf(origin)]

    // ═════════════════ 過盾主流程 ═════════════════

    /**
     * 彈出 WebView 過盾對話框，掛起等待直到：
     *   · 成功 → 回傳 [ShieldSolved]（Cookie 已寫回系統 CookieManager）；
     *   · 逾時／使用者取消／關閉 → 回傳 null。
     *
     * @param timeoutMs 最長等待（預設 [DEFAULT_SOLVE_TIMEOUT_MS]）
     */
    suspend fun solveShield(
        activity: FragmentActivity,
        url: String,
        timeoutMs: Long = DEFAULT_SOLVE_TIMEOUT_MS
    ): ShieldSolved? {
        val origin = originKeyOf(url)
        // 與 OkHttp 端共用同一支黏住 UA——cf_clearance 的身分配對起點
        val ua = UserAgentPool.uaFor(origin)
        val solved = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<ShieldSolved?> { cont ->
                    openSolveDialog(activity, url, ua, cont)
                }
            }
        }
        return if (solved != null) {
            solvedUserAgents[origin] = solved.userAgent
            toastOnMain(activity, activity.getString(R.string.shield_success))
            solved
        } else {
            toastOnMain(activity, activity.getString(R.string.shield_failed))
            null
        }
    }

    // ═════════════════ WebView 過盾對話框（零布局檔——純程式構建） ═════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun openSolveDialog(
        activity: FragmentActivity,
        url: String,
        ua: String,
        cont: CancellableContinuation<ShieldSolved?>
    ) {
        val cookieManager = CookieManager.getInstance()
        runCatching {
            cookieManager.setAcceptCookie(true)
        }

        val pad = dp(activity, 20)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        root.addView(
            ProgressBar(activity),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val statusText = TextView(activity).apply {
            setText(R.string.shield_getting_cookie)
            textSize = 14f
            val gap = dp(activity, 8)
            setPadding(0, gap, 0, gap)
        }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val webView = WebView(activity)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 340)
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.shield_cloudflare)
            .setView(root)
            .setCancelable(true)
            .create()

        var settled = false

        /** 收束（幂等）：標記停止輪詢＋關閉對話框＋銷毀 WebView＋單次 resume */
        fun settle(result: ShieldSolved?) {
            if (settled) return
            settled = true
            runCatching { dialog.dismiss() }
            runCatching { webView.destroy() }
            if (cont.isActive) cont.resume(result)
        }

        /**
         * 輪詢本體：settled 後自然停止（佇列中殘留的一次觸發直接返回）。
         */
        val poll = object : Runnable {
            override fun run() {
                if (settled) return
                val targetOrigin = originKeyOf(url)
                val cookies = readCookiesOf(webView, url)
                val cleared = cookies.contains(CF_CLEARANCE_COOKIE)
                val titleOk = !isChallengeTitle(webView.title)
                val reached = webView.url?.let { originKeyOf(it) == targetOrigin } ?: false
                if (cleared || (titleOk && reached)) {
                    runCatching { CookieManager.getInstance().flush() }
                    settle(ShieldSolved(url, cookies, ua))
                } else {
                    mainHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
                }
            }
        }

        dialog.setButton(
            AlertDialog.BUTTON_NEGATIVE,
            activity.getString(R.string.cancel)
        ) { _, _ -> settle(null) }
        dialog.setOnCancelListener { settle(null) }

        webView.settings.apply {
            javaScriptEnabled = true                 // CF 挑戰必須跑 JS
            domStorageEnabled = true                 // 部分挑戰寫 localStorage
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = ua                    // 身分配對核心
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        runCatching {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, startUrl: String, favicon: Bitmap?) {
                statusText.setText(R.string.shield_getting_cookie)
            }

            override fun onPageFinished(view: WebView, finishedUrl: String) {
                poll.run()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // 子資源（挑戰腳本／圖片）載入失敗不中斷——主文件仍在輪詢範圍內
                Log.d(TAG, "子資源載入失敗：${request.url}")
            }
        }

        cont.invokeOnCancellation {
            mainHandler.post {
                settled = true
                runCatching { dialog.dismiss() }
                runCatching { webView.destroy() }
            }
        }

        dialog.show()
        webView.loadUrl(url)
        mainHandler.postDelayed(poll, COOKIE_POLL_INTERVAL_MS)
    }

    /** 挑戰頁標題特徵（null 標題視為未完成，不放行） */
    private fun isChallengeTitle(title: String?): Boolean {
        if (title == null) return true
        val lower = title.lowercase()
        return SHIELD_MARKERS.any { lower.contains(it) }
    }

    /**
     * 讀取 WebView 眼中該站的 Cookie 串。
     * androidx.webkit 的 GET_COOKIE_INFO 支援時走精確 API（帶安全旗標
     * 的成對讀取），否則退回傳統 CookieManager.getCookie。
     */
    private fun readCookiesOf(webView: WebView, url: String): String =
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
                CookieManagerCompat.getCookieInfo(webView, url)
                    .joinToString("; ") { "${it.first}=${it.second}" }
            } else {
                CookieManager.getInstance().getCookie(url).orEmpty()
            }
        }.getOrDefault("")

    /** 無 WebView 情境（快速路徑）的 Cookie 讀取 */
    private fun readCookiesOf(url: String): String =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()

    // ═════════════════ 工具 ═════════════════

    /** origin 識別：host 為準（與 UserAgentPool 的 originKey 慣例對齊） */
    internal fun originKeyOf(url: String): String {
        val trimmed = url.trim()
        val host = runCatching { URI(trimmed).host }.getOrNull()
            ?: Regex("//([^/?#]+)").find(trimmed)?.groupValues?.get(1)
        return (host ?: trimmed).lowercase()
    }

    private fun toastOnMain(activity: Context, text: String) {
        mainHandler.post {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

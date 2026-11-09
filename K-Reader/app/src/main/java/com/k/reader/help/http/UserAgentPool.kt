package com.k.reader.help.http

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 動態 User-Agent 池（十大旗艦功能：網路防封鎖）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（擬真瀏覽器行為，降低書源站點風控封鎖率）：
 *   1. 「來源黏住」：同一書源在一段時間內固定使用同一支 UA——
 *      真實讀者不會每秒更換瀏覽器型號，頻繁跳動反而觸發風控。
 *   2. 「定期輪替」：每 30 分鐘全池輪換一次，避免長期指紋固化。
 *   3. 「行動優先」：預設送出 Android 行動瀏覽器 UA（與本機
 *      身分一致）；特定站點擋行動版時可切換桌面級 UA。
 *   4. 「瀏覽器級標頭」：訂閱源檢核（Stage 3 RssSourceVerifyManager）
 *      以 GET + 完整瀏覽器標頭 + 動態 Referer 模擬真人瀏覽。
 *
 *  本類零協力廠商依賴（純 JDK），可直接單元測試。
 *
 *  整合點：
 *   · AnalyzeUrl 組 headers 時呼叫 applyTo(headers, sourceKey)
 *   · Stage 3 訂閱源檢核直接引用 browserHeaders()
 */
object UserAgentPool {

    /** 行動瀏覽器 UA 池（主池：Chrome / Edge / Firefox / Samsung / iOS Safari） */
    private val MOBILE_UAS: List<String> = listOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 7a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; 23113RKC6C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Redmi Note 13 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 EdgA/131.0.2903.87",
        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/27.0 Chrome/125.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Android 14; Mobile; rv:133.0) Gecko/133.0 Firefox/133.0",
        "Mozilla/5.0 (Android 13; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
    )

    /** 桌面瀏覽器 UA 池（備用：部分站點擋行動版排版） */
    private val DESKTOP_UAS: List<String> = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.2849.80",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    )

    /** 來源 → 黏住 UA（擬真：同源不亂換身分） */
    private val stickyMap = ConcurrentHashMap<String, String>()

    /** 上一次全池輪替時間（elapsedRealtime 基準） */
    private val lastRotateAt = AtomicLong(0L)

    /** 輪替週期：30 分鐘 */
    private const val ROTATE_INTERVAL_MS = 30L * 60L * 1000L

    const val KEY_USER_AGENT = "User-Agent"
    const val KEY_REFERER = "Referer"

    // ─────────────────── 對外 API ───────────────────

    /** 隨機抽一支行動瀏覽器 UA */
    fun randomMobile(): String =
        MOBILE_UAS[ThreadLocalRandom.current().nextInt(MOBILE_UAS.size)]

    /** 隨機抽一支桌面瀏覽器 UA */
    fun randomDesktop(): String =
        DESKTOP_UAS[ThreadLocalRandom.current().nextInt(DESKTOP_UAS.size)]

    /**
     * 取得指定來源的「黏住 UA」（不存在則隨機分發一支並記住）。
     *
     * @param origin 來源識別（建議直接用 bookSourceUrl / 訂閱源 sourceUrl）
     */
    fun uaFor(origin: String): String {
        checkRotate()
        return stickyMap.computeIfAbsent(origin) { randomMobile() }
    }

    /** 同 [uaFor]，但強制桌面級身分（擋行動版的站點用） */
    fun desktopUaFor(origin: String): String {
        checkRotate()
        return stickyMap.computeIfAbsent("desktop::$origin") { randomDesktop() }
    }

    /** 立即解除某來源的黏住（檢核失敗、換身分重試時用） */
    fun clearSticky(origin: String) {
        stickyMap.remove(origin)
        stickyMap.remove("desktop::$origin")
    }

    /** 手動觸發全池輪替（下次 uaFor 重新分發） */
    fun rotateAll() {
        stickyMap.clear()
        lastRotateAt.set(SystemClock.elapsedRealtime())
    }

    /** 若 headers 尚未指定 User-Agent，注入本池的黏住 UA */
    fun applyTo(headers: MutableMap<String, String>, origin: String): MutableMap<String, String> {
        if (headers.keys.none { it.equals(KEY_USER_AGENT, ignoreCase = true) }) {
            headers[KEY_USER_AGENT] = uaFor(origin)
        }
        return headers
    }

    /**
     * 瀏覽器級完整標頭組合（訂閱源檢核 / 過盾前探測用）：
     * GET + Accept / Accept-Language / 動態 UA /（可選）Referer。
     * 每次呼叫隨機抽取 UA——「動態 UA」規格由此實現。
     */
    fun browserHeaders(referer: String? = null, desktop: Boolean = false): Map<String, String> {
        val ua = if (desktop) randomDesktop() else randomMobile()
        val headers = linkedMapOf(
            KEY_USER_AGENT to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Upgrade-Insecure-Requests" to "1"
        )
        referer?.let { headers[KEY_REFERER] = it }
        return headers
    }

    // ─────────────────── 內部 ───────────────────

    /** 週期到期時自動輪替（良性競態：多執行緒同時觸發也無妨） */
    private fun checkRotate() {
        val now = SystemClock.elapsedRealtime()
        val last = lastRotateAt.get()
        if (last == 0L || now - last >= ROTATE_INTERVAL_MS) {
            if (lastRotateAt.compareAndSet(last, now)) {
                stickyMap.clear()
            }
        }
    }
}

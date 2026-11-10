package com.k.reader.help.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.BookSource
import com.k.reader.data.entities.RssSource
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.UserAgentPool
import com.k.reader.help.source.SourceGroupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 剪貼簿來源偵測器（十大旗艦功能：剪貼簿偵測）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（複製即得書源——零手續匯入動線）：
 *   書源社群的分享形態九成是「複製一段 JSON」：書源陣列、訂閱源
 *   陣列、或一條直達來源 JSON 的網址。與其要求使用者鑽選單，不如
 *   在回到 App 的當下主動偵測：
 *
 *    1. 分類：剪貼簿內容比對三種形態——
 *       BOOK_SOURCE（含 "bookSourceUrl" 之 JSON）
 *       RSS_SOURCE（含 "sourceUrl"＋"sourceName" 之 JSON）
 *       URL（單一 http(s) 網址——匯入時先抓取再分類）；
 *    2. 防打擾：同一內容 30 秒冷卻；已匯入／已忽略的內容以 SHA-256
 *       雜湊永久記憶（SharedPreferences），不二次打擾；
 *    3. 匯入：提示條「一鍵匯入」→ Gson 解析 → 與資料庫既有來源
 *       對帳（新增／更新計數）→ 來源分組為空者自動補上智慧分組
 *       標籤（SourceGroupManager.classifyBookSource）；
 *    4. URL 形態：以瀏覽器級標頭抓取（Stage 2 三件套全程護航：
 *       單源限流＋動態 UA＋SSL 寬容重試）→ 內容再走 JSON 分類。
 *
 *  Android 10+ 剪貼簿限制：僅「取得焦點的前景 App」可讀剪貼簿——
 *  本偵測器只在 MainActivity onResume 時讀取一次（onAppForegrounded），
 *  絕不背景輪詢，兼顧隱私與續航。
 *
 *  整合點：
 *   · MainActivity.onResume() → ClipboardSourceDetector.onAppForegrounded(this)
 *   · 主頁根容器 include view_clipboard_bar.xml（預設 gone）
 *   · 書源／訂閱源管理頁選單「從剪貼簿匯入」→ importFromClipboard(activity)
 *
 *  ─── Legado 引擎接縫區 ────────────────────────────────────
 *  parseBookSources / parseRssSources 走 Gson 反序列化（與 Legado
 *  來源匯入共用同一 Entity 定義）；資料庫寫入走 BaseDao 泛型
 *  insertOrUpdate（若套用版本差異請對照 BookSourceDao／RssSourceDao）。
 */
object ClipboardSourceDetector {

    private const val TAG = "ClipboardSourceDetector"

    /** 與 RandomTrialManager 共用同一設定檔（K.閱讀全域設定） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_SEEN_HASHES = "clipboard_seen_hashes"

    /** 剪貼簿內容最短長度（低於此不視為來源設定） */
    const val MIN_TEXT_LENGTH = 12

    /** 同一內容的重複提示冷卻（規格：30 秒） */
    const val PROMPT_COOLDOWN_MS = 30_000L

    /** URL 形態抓取上限（2MB——來源 JSON 封裝上限） */
    private const val FETCH_MAX_BYTES = 2 * 1024 * 1024

    /** 抓取逾時 */
    private const val FETCH_TIMEOUT_MS = 15_000L

    /** 回前景後延遲綁定（等佈局穩定，避免提示條閃跳） */
    private const val BIND_DELAY_MS = 600L

    private val gson = Gson()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 剪貼簿擷取形態 */
    enum class FindingType { BOOK_SOURCE, RSS_SOURCE, URL }

    /** 擷取結果（sha256 用於冷卻與去重） */
    data class ClipboardFinding(
        val type: FindingType,
        val rawText: String,
        val sha256: String
    )

    private val URL_REGEX = Regex("^https?://[\\w.-]+\\.[a-zA-Z]{2,}(/\\S*)?$")

    // ═════════════════ 偵測 ═════════════════

    /**
     * 讀取並分類剪貼簿（前景有焦點時呼叫）。
     * 已套用冷卻與「已處理」過濾——偵測到值得提示的內容才回非 null。
     */
    fun sniffClipboard(context: Context): ClipboardFinding? {
        val finding = readFinding(context) ?: return null
        if (seenHashes.contains(finding.sha256)) return null
        if (inCooldown(finding.sha256)) return null
        return finding
    }

    /** 純讀取＋分類（不過濾——手動「從剪貼簿匯入」路徑用） */
    private fun readFinding(context: Context): ClipboardFinding? {
        val text = primaryClipText(context) ?: return null
        if (text.length < MIN_TEXT_LENGTH) return null
        return classify(text)
    }

    private fun primaryClipText(context: Context): String? {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
        val clip: ClipData? = runCatching { clipboard.primaryClip }.getOrNull()
            ?: return null
        val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun classify(text: String): ClipboardFinding? {
        val sha = sha256(text)
        return when {
            text.contains("\"bookSourceUrl\"") && looksLikeJson(text) ->
                ClipboardFinding(FindingType.BOOK_SOURCE, text, sha)
            text.contains("\"sourceUrl\"") && text.contains("\"sourceName\"")
                && looksLikeJson(text) ->
                ClipboardFinding(FindingType.RSS_SOURCE, text, sha)
            URL_REGEX.matches(text) ->
                ClipboardFinding(FindingType.URL, text, sha)
            else -> null
        }
    }

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("[") || text.startsWith("{")

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }

    // ═════════════════ 冷卻與去重 ═════════════════

    /** sha → 上次提示時間（elapsedRealtime 基準） */
    private val lastPromptAt = ConcurrentHashMap<String, Long>()

    private fun inCooldown(sha: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastPromptAt[sha] ?: 0L
        return if (now - last < PROMPT_COOLDOWN_MS) {
            true
        } else {
            lastPromptAt[sha] = now
            false
        }
    }

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 已匯入／已忽略的內容雜湊（跨重啟記憶） */
    private val seenHashes: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().also { set ->
            prefs.getStringSet(KEY_SEEN_HASHES, emptySet())?.let { set.addAll(it) }
        }
    }

    private fun markSeen(sha: String) {
        if (seenHashes.add(sha)) {
            prefs.edit().putStringSet(KEY_SEEN_HASHES, seenHashes.toSet()).apply()
        }
    }

    // ═════════════════ 前景鉤子＋提示條綁定 ═════════════════

    /**
     * MainActivity.onResume 鉤子：偵測到未處理的來源設定 → 綁出提示條。
     * 無偵測／已處理 → 完全靜默（提示條維持 gone）。
     */
    fun onAppForegrounded(activity: FragmentActivity) {
        val finding = runCatching { sniffClipboard(activity) }.getOrNull() ?: return
        mainHandler.postDelayed({
            runCatching { bindClipboardBar(activity, finding) }
        }, BIND_DELAY_MS)
    }

    /**
     * 綁定剪貼簿提示條（view_clipboard_bar.xml）。
     * 「一鍵匯入」成功後以 import_result（新增 X、更新 Y）回報並收起。
     */
    fun bindClipboardBar(activity: FragmentActivity, finding: ClipboardFinding) {
        val bar = activity.findViewById<View>(R.id.clipboard_bar) ?: return
        bar.findViewById<TextView>(R.id.tv_clipboard_hint)?.text =
            activity.getString(R.string.clipboard_detected)
        bar.visibility = View.VISIBLE
        bar.findViewById<View>(R.id.tv_clipboard_import)?.setOnClickListener {
            bar.visibility = View.GONE
            managerScope.launch {
                val counts = importFinding(finding)
                if (counts != null) {
                    toastOnMain(
                        activity.getString(R.string.import_result, counts.first, counts.second)
                    )
                } else {
                    toastOnMain(activity.getString(R.string.error))
                }
            }
        }
        bar.findViewById<View>(R.id.tv_clipboard_ignore)?.setOnClickListener {
            markSeen(finding.sha256)
            bar.visibility = View.GONE
        }
    }

    /**
     * 「從剪貼簿匯入」選單入口（import_from_clipboard）：
     * 跳過提示條直接匯入；無有效內容時以 error 文案回報。
     */
    fun importFromClipboard(activity: FragmentActivity) {
        managerScope.launch {
            val finding = runCatching { readFinding(activity) }.getOrNull()
            val counts = finding?.let { importFinding(it) }
            if (counts != null) {
                toastOnMain(
                    activity.getString(R.string.import_result, counts.first, counts.second)
                )
            } else {
                toastOnMain(activity.getString(R.string.error))
            }
        }
    }

    // ═════════════════ 匯入核心 ═════════════════

    /**
     * 依形態匯入；成功回 (新增數, 更新數)，失敗回 null。
     * URL 形態先抓取再遞歸分類（僅一層——抓回的內容必須是 JSON 形態）。
     */
    private suspend fun importFinding(finding: ClipboardFinding): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            val counts = when (finding.type) {
                FindingType.BOOK_SOURCE -> importBookSources(finding.rawText)
                FindingType.RSS_SOURCE -> importRssSources(finding.rawText)
                FindingType.URL -> {
                    val fetched = fetchUrlAsText(finding.rawText) ?: return@withContext null
                    val inner = classify(fetched) ?: return@withContext null
                    if (inner.type == FindingType.URL) null else importFinding(inner)
                }
            }
            if (counts != null) markSeen(finding.sha256)
            counts
        }

    private suspend fun importBookSources(json: String): Pair<Int, Int>? {
        val sources = parseBookSources(json)
        if (sources.isNullOrEmpty()) return null
        val existing = existingBookSourceUrls()
        var added = 0
        var updated = 0
        for (source in sources) {
            if (source.bookSourceUrl in existing) updated++ else added++
            // 分組為空者自動補上智慧分組標籤（自訂分組恆不覆寫）
            if (source.bookSourceGroup.isNullOrBlank()) {
                source.bookSourceGroup = SourceGroupManager.classifyBookSource(source)
            }
        }
        val ok = runCatching {
            /* 接縫：BaseDao 泛型 insertOrUpdate（存在即更新） */
            AppContext.db.bookSourceDao().insert(*sources.toTypedArray())
        }.onFailure { e ->
            Log.w(TAG, "書源匯入寫入失敗", e)
        }.isSuccess
        if (!ok) return null
        Log.i(TAG, "剪貼簿匯入書源：新增 $added、更新 $updated")
        return added to updated
    }

    private suspend fun importRssSources(json: String): Pair<Int, Int>? {
        val sources = parseRssSources(json)
        if (sources.isNullOrEmpty()) return null
        val existing = existingRssSourceUrls()
        var added = 0
        var updated = 0
        for (source in sources) {
            if (source.sourceUrl in existing) updated++ else added++
            if (source.sourceGroup.isNullOrBlank()) {
                source.sourceGroup = SourceGroupManager.classifyRssSource(source)
            }
        }
        val ok = runCatching {
            AppContext.db.rssSourceDao().insert(*sources.toTypedArray())
        }.onFailure { e ->
            Log.w(TAG, "訂閱源匯入寫入失敗", e)
        }.isSuccess
        if (!ok) return null
        Log.i(TAG, "剪貼簿匯入訂閱源：新增 $added、更新 $updated")
        return added to updated
    }

    // ═════════════════ 解析 ═════════════════

    @Suppress("UNCHECKED_CAST")
    private fun parseBookSources(json: String): List<BookSource>? = runCatching {
        val trimmed = json.trim()
        val parsed: List<BookSource>? = if (trimmed.startsWith("[")) {
            gson.fromJson(trimmed, object : TypeToken<List<BookSource>>() {}.type)
                as? List<BookSource>
        } else {
            listOfNotNull(gson.fromJson(trimmed, BookSource::class.java))
        }
        parsed.orEmpty().filter { !it.bookSourceUrl.isNullOrBlank() }
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun parseRssSources(json: String): List<RssSource>? = runCatching {
        val trimmed = json.trim()
        val parsed: List<RssSource>? = if (trimmed.startsWith("[")) {
            gson.fromJson(trimmed, object : TypeToken<List<RssSource>>() {}.type)
                as? List<RssSource>
        } else {
            listOfNotNull(gson.fromJson(trimmed, RssSource::class.java))
        }
        parsed.orEmpty().filter { !it.sourceUrl.isNullOrBlank() }
    }.getOrNull()

    private suspend fun existingBookSourceUrls(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：3.0 為 allBookSourceNotLive（若版本差異請對照 BookSourceDao） */
            AppContext.db.bookSourceDao().all
        }.getOrNull().orEmpty().mapNotNull { it.bookSourceUrl }.toSet()
    }

    private suspend fun existingRssSourceUrls(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            /* 接縫：3.0 為 allRssSourceNotLive（若版本差異請對照 RssSourceDao） */
            AppContext.db.rssSourceDao().all
        }.getOrNull().orEmpty().mapNotNull { it.sourceUrl }.toSet()
    }

    // ═════════════════ URL 形態抓取（Stage 2 三件套護航） ═════════════════

    /** 抓取網址內容為文字（僅接受 json／text 類回應；逾時或過大回 null） */
    private suspend fun fetchUrlAsText(url: String): String? = withContext(Dispatchers.IO) {
        val origin = url.trim()
        SourceRateLimiter.awaitPermit(origin)
        val request = Request.Builder()
            .url(origin)
            .apply {
                UserAgentPool.browserHeaders().forEach { (k, v) -> header(k, v) }
            }
            .build()
        val client = OkHttpClient.Builder()
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .recoverCatching {
                if (SslTrustHelper.isSslError(it)) {
                    SslTrustHelper.tolerantClient().newCall(request).execute()
                } else throw it
            }
            .getOrNull() ?: return@withContext null
        response.use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotEmpty() &&
                !contentType.contains("json") && !contentType.contains("text")
            ) {
                return@withContext null
            }
            val bytes = resp.body?.bytes() ?: return@withContext null
            if (bytes.isEmpty() || bytes.size > FETCH_MAX_BYTES) return@withContext null
            String(bytes, Charsets.UTF_8)
        }
    }

    // ═════════════════ 工具 ═════════════════

    private fun toastOnMain(text: String) {
        mainHandler.post {
            Toast.makeText(AppContext.ctx, text, Toast.LENGTH_SHORT).show()
        }
    }
}

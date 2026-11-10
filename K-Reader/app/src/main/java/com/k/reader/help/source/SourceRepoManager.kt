package com.k.reader.help.source

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.BookSource
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 書源倉庫訂閱（Stage 10：書源倉庫）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（書源會老，訂閱讓它保鮮）：
 *   書源是消耗品——站點改版、網域搬家，昨天的能用的今天 404。
 *   倉庫訂閱把「手動匯入」升級為「追蹤更新」：
 *
 *    1. 訂閱：以 URL 為鍵的倉庫清單（filesDir JSON 帳冊——與
 *       金句簿同一持久化紀律）；新增驗 http／https、URL 去重、
 *       名稱取 host（可改）；移除只退訂閱不動已匯入書源；
 *    2. 檢查：抓全文算 SHA-256 前 16 碼指紋，與上次比對——
 *       ETag／Last-Modified 不可靠（一半網盤直鏈不回應），
 *       內容雜湊是唯一誠實的版本號；抓取全程三件套護航
 *       （單源限流＋瀏覽器級標頭＋SSL 寬容重試）；
 *    3. 更新：指紋變化才落地——解析書源 JSON 陣列（與剪貼簿
 *       匯入共用 Entity 定義）、空分組自動補智慧分組標籤、
 *       BaseDao insertOrUpdate 冪等寫回；一鍵全庫更新逐倉
 *       串行（進度逐倉回報，失敗不拖垮後續倉）。
 *
 *  規格值：單倉抓取上限 8MB；指紋 16 碼；清單上限 30 倉。
 *
 *  整合點：
 *   · 書源管理頁 repo_title 面板 → addRepo／removeRepo／listRepos
 *   · 檢查更新／一鍵全部更新 → checkUpdate／updateAll
 *   · ClipboardSourceDetector 同款匯入路徑（Entity＋分組＋insertOrUpdate）
 */
object SourceRepoManager {

    private const val TAG = "SourceRepoManager"

    private const val REPOS_FILE = "source_repos.json"

    /** 單倉抓取上限（規格：8MB——大型書源包封裝上限） */
    const val MAX_FETCH_BYTES = 8L * 1024L * 1024L

    /** 內容指紋長度（規格：SHA-256 前 16 碼） */
    const val FINGERPRINT_LEN = 16

    /** 訂閱倉數上限（規格：30 倉） */
    const val MAX_REPOS = 30

    /** 請求逾時（毫秒——與剪貼簿偵測器同款） */
    private const val TIMEOUT_MS = 30_000L

    private val gson = Gson()

    // ═════════════════ 帳冊模型 ═════════════════

    /** 倉庫訂閱（source_repos.json 帳冊項） */
    data class RepoSubscription(
        val url: String,
        var name: String,
        val addedAt: Long,
        var lastCheckAt: Long = 0L,
        var lastFingerprint: String? = null,
        var sourceCount: Int = 0,
        var lastError: String? = null
    )

    /** 新增結果 */
    sealed class AddResult {
        data class Added(val repo: RepoSubscription) : AddResult()
        object Duplicate : AddResult()
        object InvalidUrl : AddResult()
        object Full : AddResult()
        data class Failed(val reason: String) : AddResult()
    }

    /** 檢查結果 */
    sealed class CheckOutcome {
        object UpToDate : CheckOutcome()
        data class HasUpdate(val newFingerprint: String) : CheckOutcome()
        data class Failed(val reason: String) : CheckOutcome()
    }

    /** 更新結果 */
    sealed class UpdateOutcome {
        data class Updated(val sourceCount: Int, val fingerprint: String) : UpdateOutcome()
        object UpToDate : UpdateOutcome()
        object InvalidJson : UpdateOutcome()
        data class Failed(val reason: String) : UpdateOutcome()
    }

    // ═════════════════ 訂閱管理 ═════════════════

    /** 新增訂閱：驗 URL 格式→去重→容量夾擊→落帳冊 */
    fun addRepo(url: String, customName: String? = null): AddResult {
        val normalized = url.trim()
        if (!isValidRepoUrl(normalized)) return AddResult.InvalidUrl
        val repos = loadRepos().toMutableList()
        if (repos.any { it.url.equals(normalized, ignoreCase = true) }) {
            return AddResult.Duplicate
        }
        if (repos.size >= MAX_REPOS) return AddResult.Full
        val repo = RepoSubscription(
            url = normalized,
            name = customName?.trim().takeUnless { it.isNullOrBlank() } ?: hostOf(normalized),
            addedAt = System.currentTimeMillis()
        )
        repos.add(repo)
        return if (persistRepos(repos)) {
            Log.i(TAG, "已訂閱書源倉庫：${repo.name}")
            AddResult.Added(repo)
        } else {
            AddResult.Failed("persist")
        }
    }

    /** 移除訂閱（僅退訂閱——已匯入書源不受影響） */
    fun removeRepo(url: String): Boolean {
        val repos = loadRepos().toMutableList()
        val before = repos.size
        repos.removeAll { it.url.equals(url.trim(), ignoreCase = true) }
        return if (repos.size < before) persistRepos(repos) else false
    }

    /** 訂閱清單（訂閱時間升序） */
    fun listRepos(): List<RepoSubscription> = loadRepos().sortedBy { it.addedAt }

    // ═════════════════ 檢查與更新 ═════════════════

    /**
     * 檢查單倉更新：抓全文算指紋，與帳冊指紋比對。
     * 不落任何變更（純讀）——repo_last_check_fmt 由 UI 讀帳冊。
     */
    suspend fun checkUpdate(repo: RepoSubscription): CheckOutcome = withContext(Dispatchers.IO) {
        val text = fetchRepoText(repo.url)
            ?: return@withContext CheckOutcome.Failed(fetchLastError ?: "IO")
        val fingerprint = sha256Prefix(text)
        if (fingerprint == repo.lastFingerprint) {
            CheckOutcome.UpToDate
        } else {
            CheckOutcome.HasUpdate(fingerprint)
        }
    }

    /**
     * 更新單倉：指紋未變→UpToDate（零寫入）；已變→解析＋
     * 分組補標＋insertOrUpdate→帳冊記指紋與書源數。
     */
    suspend fun updateRepo(repo: RepoSubscription): UpdateOutcome = withContext(Dispatchers.IO) {
        val text = fetchRepoText(repo.url)
            ?: return@withContext UpdateOutcome.Failed(fetchLastError ?: "IO")
        val fingerprint = sha256Prefix(text)
        if (fingerprint == repo.lastFingerprint) {
            touchRepo(repo.url)
            return@withContext UpdateOutcome.UpToDate
        }

        // 解析（與剪貼簿匯入共用 Entity 定義）
        val sources = parseBookSources(text)
        if (sources.isNullOrEmpty()) {
            touchRepo(repo.url)
            return@withContext UpdateOutcome.InvalidJson
        }

        // 空分組自動補智慧分組標籤（自訂分組恆不覆寫）
        for (source in sources) {
            if (source.bookSourceGroup.isNullOrBlank()) {
                source.bookSourceGroup = SourceGroupManager.classifyBookSource(source)
            }
        }
        val ok = runCatching {
            /* 接縫：BaseDao 泛型 insertOrUpdate（存在即更新） */
            AppContext.db.bookSourceDao().insertOrUpdate(*sources.toTypedArray())
        }.onFailure { e ->
            Log.w(TAG, "倉庫書源寫入失敗：${repo.name}", e)
        }.isSuccess
        if (!ok) {
            return@withContext UpdateOutcome.Failed("db")
        }

        // 帳冊記帳
        val repos = loadRepos().toMutableList()
        val index = repos.indexOfFirst { it.url.equals(repo.url, ignoreCase = true) }
        if (index >= 0) {
            repos[index].lastCheckAt = System.currentTimeMillis()
            repos[index].lastFingerprint = fingerprint
            repos[index].sourceCount = sources.size
            repos[index].lastError = null
            persistRepos(repos)
        }
        Log.i(TAG, "倉庫更新成功：${repo.name}（${sources.size} 個書源）")
        UpdateOutcome.Updated(sources.size, fingerprint)
    }

    /**
     * 一鍵全部更新：逐倉串行（進度逐倉回報）；
     * 單倉失敗記帳繼續——不讓一個壞倉拖垮整批。
     */
    suspend fun updateAll(
        onRepoDone: (index: Int, total: Int, repo: RepoSubscription, outcome: UpdateOutcome) -> Unit
    ): List<Pair<RepoSubscription, UpdateOutcome>> = withContext(Dispatchers.IO) {
        val repos = listRepos()
        val results = mutableListOf<Pair<RepoSubscription, UpdateOutcome>>()
        repos.forEachIndexed { index, repo ->
            val outcome = runCatching { updateRepo(repo) }.getOrElse { e ->
                Log.w(TAG, "倉庫更新異常：${repo.name}", e)
                UpdateOutcome.Failed(e.message ?: "IO")
            }
            results.add(repo to outcome)
            onRepoDone(index + 1, repos.size, repo, outcome)
        }
        results
    }

    // ═════════════════ 抓取（三件套護航） ═════════════════

    /** 最近一次抓取錯誤（checkUpdate／updateRepo 錯誤文案用） */
    private var fetchLastError: String? = null

    /**
     * 抓取倉庫全文：單源限流→瀏覽器級標頭→SSL 寬容重試
     * （與 ClipboardSourceDetector.fetchUrlAsText 同款護航）。
     */
    private suspend fun fetchRepoText(url: String): String? {
        fetchLastError = null
        return runCatching {
            val origin = originOf(url)
            SourceRateLimiter.awaitPermit(origin)

            val request = Request.Builder().url(url).apply {
                UserAgentPool.browserHeaders().forEach { (k, v) -> header(k, v) }
            }.build()

            val client = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            val response = runCatching { client.newCall(request).execute() }.recoverCatching {
                if (SslTrustHelper.isSslError(it)) {
                    SslTrustHelper.tolerantClient().newCall(request).execute()
                } else {
                    throw it
                }
            }.getOrNull() ?: run {
                fetchLastError = "network"
                return@runCatching null
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    fetchLastError = "HTTP ${resp.code}"
                    Log.w(TAG, "倉庫抓取失敗：$url → HTTP ${resp.code}")
                    return@runCatching null
                }
                val body = resp.body?.bytes()
                    ?: run {
                        fetchLastError = "empty"
                        return@runCatching null
                    }
                if (body.size > MAX_FETCH_BYTES) {
                    fetchLastError = "too_large"
                    return@runCatching null
                }
                String(body, Charsets.UTF_8)
            }
        }.getOrElse { e ->
            fetchLastError = e.message ?: "IO"
            null
        }
    }

    // ═════════════════ 解析與工具 ═════════════════

    /** 書源 JSON 陣列解析（與剪貼簿匯入共用 Entity 定義） */
    internal fun parseBookSources(json: String): List<BookSource>? = runCatching {
        gson.fromJson<List<BookSource>>(
            json, object : TypeToken<List<BookSource>>() {}.type
        )
    }.getOrNull()?.filter { !it.bookSourceUrl.isNullOrBlank() }

    /** URL 格式驗證（http／https＋有 host） */
    internal fun isValidRepoUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol in listOf("http", "https") &&
            !parsed.host.isNullOrBlank() &&
            parsed.host.contains('.')
    }.getOrDefault(false)

    private fun hostOf(url: String): String = runCatching {
        URL(url).host
    }.getOrDefault(url.take(24))

    private fun originOf(url: String): String = runCatching {
        val parsed = URL(url)
        "${parsed.protocol}://${parsed.host}"
    }.getOrDefault(url)

    private fun sha256Prefix(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }
            .take(FINGERPRINT_LEN)

    /** 僅更新檢查時間（不動指紋——檢查未變／解析失敗路徑） */
    private fun touchRepo(url: String) {
        val repos = loadRepos().toMutableList()
        val index = repos.indexOfFirst { it.url.equals(url, ignoreCase = true) }
        if (index >= 0) {
            repos[index].lastCheckAt = System.currentTimeMillis()
            persistRepos(repos)
        }
    }

    // ═════════════════ 帳冊持久化（filesDir JSON） ═════════════════

    private fun reposFile(): File = File(AppContext.ctx.filesDir, REPOS_FILE)

    private fun loadRepos(): List<RepoSubscription> = runCatching {
        val file = reposFile()
        if (!file.isFile) return emptyList()
        gson.fromJson(
            file.readText(Charsets.UTF_8),
            object : TypeToken<List<RepoSubscription>>() {}.type
        ) as? List<RepoSubscription>
    }.getOrNull().orEmpty()

    private fun persistRepos(repos: List<RepoSubscription>): Boolean = runCatching {
        reposFile().writeText(gson.toJson(repos), Charsets.UTF_8)
        true
    }.getOrElse { e ->
        Log.w(TAG, "倉庫帳冊持久化失敗", e)
        false
    }

    // ═════════════════ UI 文案 ═════════════════

    /** 上次檢查文案（repo_last_check_fmt／repo_never_checked） */
    fun lastCheckText(repo: RepoSubscription): String {
        val ctx = AppContext.ctx
        return if (repo.lastCheckAt <= 0L) {
            ctx.getString(R.string.repo_never_checked)
        } else {
            ctx.getString(
                R.string.repo_last_check_fmt,
                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(repo.lastCheckAt))
            )
        }
    }

    /** 書源數文案（repo_sources_count） */
    fun sourceCountText(repo: RepoSubscription): String =
        AppContext.ctx.getString(R.string.repo_sources_count, repo.sourceCount)
}

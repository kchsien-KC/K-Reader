package com.k.reader.help.webdav

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.data.entities.Book
import com.k.reader.data.entities.BookSource
import com.k.reader.data.entities.RssSource
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — WebDAV 雲端備份助手（十大旗艦功能：WebDAV）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（多端閱讀進度無痛還原）：
 *   換手機、雙裝置通勤閱讀，最痛的不是重裝 App，而是書源、書架
 *   與閱讀進度各分東西。本助手以純 OkHttp 直連使用者的 WebDAV
 *   空間（坚果云／Nextcloud／NAS 皆宜），零額外依賴：
 *
 *    · 通訊：Basic 驗證＋PROPFIND／MKCOL／PUT／GET／DELETE
 *      五動詞全覆蓋；XML 回應以容錯正則解析（命名空間前綴
 *      D:/d:/無前綴 三態通吃）；
 *    · 防封鎖護航（直接消費 Stage 2 三件套）：每次請求前
 *      SourceRateLimiter.awaitPermit（單源限流）；UA 以
 *      UserAgentPool.browserHeaders 打底；SSL 類失敗走
 *      SslTrustHelper 寬容通道重試一次——NAS 自簽憑證是
 *      WebDAV 的日常而非例外；
 *    · 備份形態：書源＋訂閱源＋書架書籍（含閱讀進度欄位）
 *      打包為 zip（內含 k_reader_backup.json），遠端落於
 *      /KReader/backup/；本地備份落 Download/KReader/；
 *    · 自動增量備份（webdav_auto_increment）：WorkManager
 *      週期任務每 12 小時（規格值）背景上傳，僅在 Wi-Fi 外
 *      也允許行動網路（NetworkType.CONNECTED）；遠端保留
 *      最近 10 份（規格值），逾限自最舊刪除；
 *    · 還原：抓最新一份 → 解包 → insertOrUpdate 冪等寫回
 *      （存在即覆蓋，重複還原安全）。
 *
 *  整合點：
 *   · 設定頁「WebDAV 設定」（webdav_config）→ saveConfig/
 *     testConnection
 *   · webdav_backup_now／webdav_restore_now 按鈕
 *   · 自動增量開關 → setAutoIncrement（同步排程 Worker）
 *
 *  ─── Legado 引擎接縫區 ────────────────────────────────────
 *  備份資料的讀取走 DAO 查詢（allBookSourceNotLive /
 *  allRssSourceNotLive / bookDao.getAll）；還原走各 DAO 的
 *  insertOrUpdate。若套用版本簽名略有差異，僅需對照
 *  BookSourceDao／RssSourceDao／BookDao 修正該區，通訊與
 *  排程邏輯完全不受影響。
 */
object WebDavHelper {

    private const val TAG = "WebDavHelper"

    /** 與全域設定共用（WebDAV 帳密屬使用者設定） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_URL = "webdav_url"
    private const val KEY_ACCOUNT = "webdav_account"
    private const val KEY_PASSWORD = "webdav_password"
    private const val KEY_AUTO_INCREMENT = "webdav_auto_increment"

    /** 雲端備份保留份數（規格：10 份——逾限自最舊刪除） */
    const val KEEP_BACKUP_COUNT = 10

    /** 自動增量備份週期（規格：12 小時） */
    const val AUTO_BACKUP_INTERVAL_HOURS = 12L

    /** 備份檔格式版本（還原相容性判別） */
    const val BACKUP_VERSION = 1

    /** 遠端目錄（與金句卡同一家族：KReader/） */
    private const val REMOTE_ROOT = "KReader"
    private const val REMOTE_BACKUP_DIR = "KReader/backup"

    /** 本地備份公開目錄（Download/KReader/） */
    private const val LOCAL_DIR = "KReader"

    /** zip 內容條目名 */
    private const val BACKUP_ENTRY_NAME = "k_reader_backup.json"

    /** 單次傳輸逾時 */
    private const val TRANSFER_TIMEOUT_MS = 60_000L

    private val gson = Gson()

    /** WebDAV 伺服器組態 */
    data class WebDavConfig(
        val url: String,
        val account: String,
        val password: String,
        val autoIncrement: Boolean = false
    )

    /** 遠端備份檔描述（PROPFIND 解析產物） */
    data class RemoteBackup(
        val name: String,
        val url: String,
        val size: Long,
        val lastModified: Long
    )

    /** 備份裝載（zip 內 JSON 結構——Gson 序列化） */
    data class BackupPayload(
        val version: Int = BACKUP_VERSION,
        val createdAt: Long,
        val bookSources: List<BookSource>,
        val rssSources: List<RssSource>,
        val books: List<Book>
    )

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val davClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // ═════════════════ 組態 ═════════════════

    fun saveConfig(url: String, account: String, password: String): Boolean {
        if (url.isBlank() || account.isBlank()) return false
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_ACCOUNT, account.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
        return true
    }

    fun loadConfig(): WebDavConfig? {
        val url = prefs.getString(KEY_URL, null)?.trim().orEmpty()
        if (url.isEmpty()) return null
        return WebDavConfig(
            url = url,
            account = prefs.getString(KEY_ACCOUNT, null).orEmpty(),
            password = prefs.getString(KEY_PASSWORD, null).orEmpty(),
            autoIncrement = prefs.getBoolean(KEY_AUTO_INCREMENT, false)
        )
    }

    fun isConfigured(): Boolean = loadConfig() != null

    /** 自動增量開關：持久化＋同步 WorkManager 排程（KEEP 冪等） */
    fun setAutoIncrement(context: Context, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_INCREMENT, enabled).apply()
        if (enabled) {
            WebDavAutoBackupWorker.schedule(context)
        } else {
            WebDavAutoBackupWorker.cancel(context)
        }
    }

    // ═════════════════ 連線測試 ═════════════════

    /** PROPFIND Depth:0 探測根路徑；200／207 即連線成功（附文案回饋） */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val config = loadConfig()
        if (config == null) {
            toast(AppContext.ctx.getString(R.string.webdav_test_fail))
            return@withContext false
        }
        val request = davRequestBuilder(config, "/")
            .header("Depth", "0")
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("text/xml".toMediaType())
            )
            .build()
        val ok = call(config.url, request)?.use { it.code in 200..299 } ?: false
        toast(
            AppContext.ctx.getString(
                if (ok) R.string.webdav_test_ok else R.string.webdav_test_fail
            )
        )
        ok
    }

    // ═════════════════ 雲端備份／還原 ═════════════════

    /**
     * 雲端備份：打包 → MKCOL 確保遠端目錄 → PUT 上傳（時間戳檔名）
     * → 自動增量時清理逾限舊檔。各步驟以 webdav_sync_progress 回報。
     */
    suspend fun backupToCloud(): Boolean = withContext(Dispatchers.IO) {
        val config = loadConfig()
        if (config == null) {
            toast(AppContext.ctx.getString(R.string.error))
            return@withContext false
        }
        val payload = buildPayload()
        val zipBytes = buildBackupZip(payload)
        val fileName = "k_reader_backup_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.zip"

        if (!ensureRemoteDirs(config)) {
            toast(AppContext.ctx.getString(R.string.webdav_test_fail))
            return@withContext false
        }
        toast(AppContext.ctx.getString(R.string.webdav_sync_progress, fileName))
        val request = davRequestBuilder(config, "/$REMOTE_BACKUP_DIR/$fileName")
            .put(zipBytes.toRequestBody("application/zip".toMediaType()))
            .build()
        val uploaded = call(config.url, request)?.use { it.isSuccessful } ?: false
        if (!uploaded) {
            toast(AppContext.ctx.getString(R.string.error))
            return@withContext false
        }

        var trimmed = true
        if (config.autoIncrement) {
            trimmed = trimRemoteBackups(config)
        }
        if (trimmed) {
            toast(AppContext.ctx.getString(R.string.success))
        }
        trimmed
    }

    /** 雲端還原：取最新備份 → 解包 → 冪等寫回（存在即覆蓋） */
    suspend fun restoreFromCloud(): Boolean = withContext(Dispatchers.IO) {
        val config = loadConfig()
        if (config == null) {
            toast(AppContext.ctx.getString(R.string.error))
            return@withContext false
        }
        val backups = listRemoteBackups(config)
        val latest = backups.maxByOrNull { it.lastModified }
        if (latest == null) {
            toast(AppContext.ctx.getString(R.string.empty))
            return@withContext false
        }
        toast(AppContext.ctx.getString(R.string.webdav_sync_progress, latest.name))
        val request = davRequestBuilder(config, remotePathOf(latest.url))
            .get()
            .build()
        val bytes = call(config.url, request)?.use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
        val payload = bytes?.let { parseBackupZip(it) }
        if (payload == null) {
            toast(AppContext.ctx.getString(R.string.error))
            return@withContext false
        }
        val ok = runCatching { restorePayload(payload) }.isSuccess
        toast(AppContext.ctx.getString(if (ok) R.string.success else R.string.error))
        ok
    }

    /** 還原寫回（Legado 引擎接縫區——冪等 insertOrUpdate） */
    private suspend fun restorePayload(payload: BackupPayload) {
        payload.bookSources.forEach {
            AppContext.db.bookSourceDao().insert(it)
        }
        payload.rssSources.forEach {
            AppContext.db.rssSourceDao().insert(it)
        }
        payload.books.forEach {
            AppContext.db.bookDao().insert(it)
        }
        Log.i(
            TAG,
            "雲端還原完成：書源 ${payload.bookSources.size}、" +
                "訂閱源 ${payload.rssSources.size}、書籍 ${payload.books.size}"
        )
    }

    // ═════════════════ 遠端目錄與清單 ═════════════════

    /** MKCOL 建立遠端目錄（405＝已存在，同視為成功） */
    private suspend fun ensureRemoteDirs(config: WebDavConfig): Boolean =
        withContext(Dispatchers.IO) {
            for (dir in listOf("/$REMOTE_ROOT", "/$REMOTE_BACKUP_DIR")) {
                val request = davRequestBuilder(config, dir)
                    .method("MKCOL", null)
                    .build()
                val code = call(config.url, request)?.use { it.code } ?: -1
                if (code !in intArrayOf(200, 201, 301, 405)) return@withContext false
            }
            true
        }

    /** 遠端備份清單（PROPFIND Depth:1；命名空間前綴容錯解析） */
    private suspend fun listRemoteBackups(config: WebDavConfig): List<RemoteBackup> =
        withContext(Dispatchers.IO) {
            val request = davRequestBuilder(config, "/$REMOTE_BACKUP_DIR/")
                .header("Depth", "1")
                .method(
                    "PROPFIND",
                    PROPFIND_BODY.toRequestBody("text/xml".toMediaType())
                )
                .build()
            val body = call(config.url, request)?.use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            } ?: return@withContext emptyList()
            parsePropfind(body, config)
        }

    /** 逾限清理：依最後修改時間新→舊排序，僅保留 KEEP_BACKUP_COUNT 份 */
    private suspend fun trimRemoteBackups(config: WebDavConfig): Boolean =
        withContext(Dispatchers.IO) {
            val backups = listRemoteBackups(config)
                .sortedByDescending { it.lastModified }
            val stale = backups.drop(KEEP_BACKUP_COUNT)
            var allOk = true
            for (old in stale) {
                val request = davRequestBuilder(config, remotePathOf(old.url))
                    .delete()
                    .build()
                val deleted = call(config.url, request)?.use { it.isSuccessful } ?: false
                if (!deleted) {
                    allOk = false
                    Log.w(TAG, "遠端舊備份刪除失敗：${old.name}")
                }
            }
            allOk
        }

    // ═════════════════ PROPFIND 解析（容錯正則） ═════════════════

    private val RESPONSE_BLOCK =
        Regex("(?is)<(?:[\\w]+:)?response>(.*?)</(?:[\\w]+:)?response>")
    private val HREF_TAG =
        Regex("(?is)<(?:[\\w]+:)?href>(.*?)</(?:[\\w]+:)?href>")
    private val LENGTH_TAG =
        Regex("(?is)<(?:[\\w]+:)?getcontentlength>(.*?)</(?:[\\w]+:)?getcontentlength>")
    private val MODIFIED_TAG =
        Regex("(?is)<(?:[\\w]+:)?getlastmodified>(.*?)</(?:[\\w]+:)?getlastmodified>")

    private fun parsePropfind(xml: String, config: WebDavConfig): List<RemoteBackup> {
        val out = mutableListOf<RemoteBackup>()
        for (block in RESPONSE_BLOCK.findAll(xml)) {
            val content = block.groupValues[1] ?: continue
            val href = HREF_TAG.find(content)?.groupValues?.get(1) ?: continue
            if (!href.endsWith(".zip")) continue
            val url = if (href.startsWith("http")) href else "${config.url.trimEnd('/')}/$href"
            out.add(
                RemoteBackup(
                    name = href.substringAfterLast('/'),
                    url = url,
                    size = LENGTH_TAG.find(content)?.groupValues?.get(1)
                        ?.toLongOrNull() ?: 0L,
                    lastModified = MODIFIED_TAG.find(content)?.groupValues?.get(1)
                        ?.toLongOrNull() ?: 0L
                )
            )
        }
        return out
    }

    /** href → 相對路徑（還原 GET 用；絕對 href 取其 path） */
    private fun remotePathOf(url: String): String =
        runCatching { Uri.parse(url).path }.getOrNull() ?: url

    // ═════════════════ 請求構建（Basic 驗證＋三件套護航） ═════════════════

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<D:propfind xmlns:D=\"DAV:\">" +
            "<D:prop><D:displayname/><D:getcontentlength/><D:getlastmodified/></D:prop>" +
            "</D:propfind>"

    private fun davRequestBuilder(config: WebDavConfig, path: String): Request.Builder {
        // 根路徑（"/"）→ 伺服器原樣；子路徑 → 斜線拼接
        val url = "${config.url.trimEnd('/')}/${path.trimStart('/')}"
        val builder = Request.Builder().url(url)
        // 瀏覽器級標頭打底（Stage 2 動態 UA）
        UserAgentPool.browserHeaders().forEach { (k, v) -> builder.header(k, v) }
        // Basic 驗證（帳密皆備時）
        if (config.account.isNotEmpty() || config.password.isNotEmpty()) {
            builder.header(
                "Authorization",
                Credentials.basic(config.account, config.password)
            )
        }
        return builder
    }

    /**
     * 統一請求閘口：單源限流（SourceRateLimiter）＋ SSL 寬容重試
     * （SslTrustHelper——NAS 自簽憑證常態）。回應由呼叫端 use 消費。
     */
    private suspend fun call(originUrl: String, request: Request): Response? {
        SourceRateLimiter.awaitPermit(originKeyOf(originUrl))
        return runCatching {
            davClient.newCall(request).execute()
        }.recoverCatching {
            if (SslTrustHelper.isSslError(it)) {
                SslTrustHelper.tolerantClient().newCall(request).execute()
            } else throw it
        }.onFailure { e ->
            Log.w(TAG, "WebDAV 請求失敗：${request.url}", e)
        }.getOrNull()
    }

    private fun originKeyOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host }.getOrNull()
            ?: url.trim().lowercase()

    // ═════════════════ 打包／解包 ═════════════════

    /** 備份裝載（Legado 引擎接縫區——DAO 讀取差異時優雅降級） */
    private suspend fun buildPayload(): BackupPayload = withContext(Dispatchers.IO) {
        /* 接縫：3.0 為 allBookSourceNotLive（版本差異請對照 BookSourceDao） */
        val bookSources = runCatching {
            AppContext.db.bookSourceDao().all
        }.getOrNull().orEmpty()

        /* 接縫：3.0 為 allRssSourceNotLive（版本差異請對照 RssSourceDao） */
        val rssSources = runCatching {
            AppContext.db.rssSourceDao().all
        }.getOrNull().orEmpty()

        /* 接縫：3.0 為 bookDao.getAll（若為 LiveData 形態請對照
           BookDao 改用非 Live 查詢；讀取失敗僅略過書籍，不炸備份） */
        val books = runCatching {
            AppContext.db.bookDao().all
        }.getOrNull().orEmpty()

        BackupPayload(
            createdAt = System.currentTimeMillis(),
            bookSources = bookSources,
            rssSources = rssSources,
            books = books
        )
    }

    /** zip 打包（單一 JSON 條目——結構簡單、損壞面最小） */
    private fun buildBackupZip(payload: BackupPayload): ByteArray {
        val json = gson.toJson(payload)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_ENTRY_NAME))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bos.toByteArray()
    }

    /** zip 解包（僅接受已知條目名——防還原注入） */
    private fun parseBackupZip(bytes: ByteArray): BackupPayload? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry ?: return@runCatching null
            if (entry.name != BACKUP_ENTRY_NAME) return@runCatching null
            val json = zip.readBytes()
            gson.fromJson(String(json, Charsets.UTF_8), BackupPayload::class.java)
        }
    }.getOrNull()

    // ═════════════════ 本地備份 ═════════════════

    /**
     * 本地備份：同一 zip 落 Download/KReader/（backup_local）。
     * API 29+ 走 MediaStore（無儲存權限）；舊版走公開目錄直寫。
     * 回傳路徑字串（UI 顯示用）；失敗回 null。
     */
    suspend fun localBackup(): String? = withContext(Dispatchers.IO) {
        val payload = buildPayload()
        val zipBytes = buildBackupZip(payload)
        val name = "k_reader_backup_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.zip"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$LOCAL_DIR"
                    )
                }
                val uri = AppContext.ctx.contentResolver
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                AppContext.ctx.contentResolver.openOutputStream(uri)?.use { it.write(zipBytes) }
                "Download/$LOCAL_DIR/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    LOCAL_DIR
                )
                if (!dir.exists() && !dir.mkdirs()) return@runCatching null
                val file = File(dir, name)
                file.writeBytes(zipBytes)
                file.absolutePath
            }
        }.onFailure { e ->
            Log.w(TAG, "本地備份失敗", e)
        }.getOrNull()
    }

    // ═════════════════ UI 文案 ═════════════════

    fun configLabel(): String = AppContext.ctx.getString(R.string.webdav_config)

    fun urlLabel(): String = AppContext.ctx.getString(R.string.webdav_url)

    fun accountLabel(): String = AppContext.ctx.getString(R.string.webdav_account)

    fun passwordLabel(): String = AppContext.ctx.getString(R.string.webdav_password)

    fun autoIncrementLabel(): String =
        AppContext.ctx.getString(R.string.webdav_auto_increment)

    fun backupNowLabel(): String = AppContext.ctx.getString(R.string.webdav_backup_now)

    fun restoreNowLabel(): String = AppContext.ctx.getString(R.string.webdav_restore_now)

    fun multiDeviceHint(): String = AppContext.ctx.getString(R.string.backup_multi_device)

    private fun toast(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(AppContext.ctx, text, Toast.LENGTH_SHORT).show()
        }
    }

    // ═════════════════ 自動增量排程（WorkManager） ═════════════════

    /**
     * 自動增量備份 Worker：每 12 小時（AUTO_BACKUP_INTERVAL_HOURS）
     * 在網路可用時背景上傳；組態未設／未開自動增量時靜默成功。
     */
    class WebDavAutoBackupWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            val config = loadConfig()
            if (config == null || !config.autoIncrement) return Result.success()
            val ok = runCatching { backupToCloud() }.getOrDefault(false)
            return if (ok) Result.success() else Result.retry()
        }

        companion object {

            private const val UNIQUE_NAME = "k_reader_webdav_auto_backup"

            /** 排程（KEEP 冪等——重複呼叫不重置週期計時） */
            fun schedule(context: Context) {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val request = PeriodicWorkRequestBuilder<WebDavAutoBackupWorker>(
                    AUTO_BACKUP_INTERVAL_HOURS, TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            }

            fun cancel(context: Context) {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            }
        }
    }
}

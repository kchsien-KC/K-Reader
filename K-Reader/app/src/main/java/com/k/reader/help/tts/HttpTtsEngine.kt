package com.k.reader.help.tts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — HTTP 線上聽書引擎（十大旗艦功能：HTTP 聽書）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（系統 TTS 之外的「真人級」線上語音）：
 *   Android 系統 TTS 音色機械、台灣裝置常缺裝中文聲包。本引擎以
 *   兩種真實可用的線上語音服務補位，播放層（HttpReadAloudService）
 *   只面對「傳入文字 → 取回音訊檔」的單一介面：
 *
 *    1. ENGINE_EDGE——微軟 Edge 神經語音（免費）：
 *       以 OkHttp WebSocket 走 Edge 瀏覽器朗讀協定（wss://
 *       speech.platform.bing.com），含 Sec-MS-GEC DRM 權杖
 *       （SHA-256(unixTime/30 + TrustedClientToken) 大寫十六進位）、
 *       speech.config 幀、SSML 幀、二進位音訊幀（前 2 bytes
 *       大端標頭長）與 Path:turn.end 完成訊號；
 *    2. ENGINE_REST——泛用 HTTP 模板引擎：
 *       url 模板替換 {{speakText}}／{{speed}}／{{pitchRate}}／
 *       {{voice}}，headersJson 供自訂標頭（金鑰／鑑權），可選
 *       POST＋SSML（Azure 語音服務預設即此形態）。
 *
 *  防封鎖紀律（直接消費 Stage 2 三件套）：
 *   · 每次合成前 SourceRateLimiter.awaitPermit（單源 QPS）；
 *   · REST 請求 UserAgentPool.browserHeaders 動態身分；
 *   · SSL 類失敗 SslTrustHelper 分類後寬容通道重試一次。
 *
 *  快取：每段（引擎 id＋文字）SHA-256 為檔名，落於 cacheDir/
 *  tts_audio，總量上限 64MB、逾限自最舊刪除——同一段落重聽
 *  （回退章節／AB 重複）零流量零延遲。
 *
 *  分段：TTS 引擎對單次請求文字長度敏感（URL 模板型尤甚），
 *   splitSegments 先按段落、再按句讀切，單段上限 MAX_SEGMENT_CHARS。
 *
 *  整合點：
 *   · com.k.reader.service.HttpReadAloudService（播放層）
 *   · 設定頁「HTTP 線上語音引擎」區塊——本類的 label 系列函數
 *     供 UI 取文案（tts_http_engine／tts_voice／tts_rate／
 *     tts_pitch／tts_test／tts_auto_next），零硬編碼中文。
 *   · 選定引擎持久化：saveSelectedEngine／loadSelectedEngine。
 */
object HttpTtsEngine {

    private const val TAG = "HttpTtsEngine"

    /** 單段文字上限（URL 模板引擎的長度安全上限） */
    const val MAX_SEGMENT_CHARS = 300

    /** 單段合成逾時 */
    const val SYNTHESIZE_TIMEOUT_MS = 20_000L

    /** 語音快取總量上限（64MB——重聽零流量） */
    private const val CACHE_MAX_BYTES = 64L * 1024L * 1024L

    private const val CACHE_DIR_NAME = "tts_audio"

    /** 引擎類型：Edge WebSocket 協定 */
    const val ENGINE_EDGE = "EDGE"

    /** 引擎類型：泛用 HTTP 模板（GET 或 POST＋SSML） */
    const val ENGINE_REST = "REST"

    /** 與全域共用之設定檔（RandomTrialManager 同款） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_SELECTED_ENGINE = "http_tts_engine_json"
    private const val KEY_AUTO_NEXT = "http_tts_auto_next"

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─────────────────── Edge 協定常數 ───────────────────

    private const val EDGE_WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val EDGE_TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val EDGE_GEC_VERSION = "1-130.0.2849.68"

    private const val EDGE_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private const val EDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

    /** Edge 通道的限流 origin（固定服務端點） */
    private const val EDGE_ORIGIN_KEY = "speech.platform.bing.com"

    private val SSML_MEDIA = "application/ssml+xml".toMediaType()

    /** 純程式構建的 WebView 對話框之外，本類全部走 IO——共用兩個客戶端 */
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .build()
    }

    private val restClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(60L, TimeUnit.SECONDS)
            .build()
    }

    // ═════════════════ 引擎資料模型 ═════════════════

    /**
     * HTTP 語音引擎定義（可 Gson 序列化——設定頁編輯／匯入匯出）。
     *
     * @param engineType  ENGINE_EDGE（Edge 神經語音）／ENGINE_REST（模板引擎）
     * @param url         REST：含 {{speakText}} 等模板；EDGE：wss 端點
     * @param headersJson REST 額外標頭（JSON；Azure 金鑰放這裡，覆寫瀏覽器標頭）
     * @param post        REST 是否以 SSML POST（Azure 形態）
     */
    data class HttpTts(
        var id: String,
        var name: String,
        var engineType: String,
        var url: String,
        var contentType: String? = null,
        var headersJson: String? = null,
        var voice: String = "",
        var rate: Float = 1.0f,
        var pitch: Float = 1.0f,
        var post: Boolean = false,
        var enabled: Boolean = true
    ) {
        companion object {
            /** 使用者自訂引擎（匯入形態：書源社群 TTS 分享 JSON） */
            fun fromJson(json: String): HttpTts? = runCatching {
                Gson().fromJson(json, HttpTts::class.java)
            }.getOrNull()
        }
    }

    // ─────────────────── 內建音色（Edge／Azure 通用神經音色名） ───────────────────

    const val EDGE_VOICE_FEMALE_TW = "zh-TW-HsiaoChenNeural"
    const val EDGE_VOICE_MALE_TW = "zh-TW-YunJheNeural"
    const val EDGE_VOICE_FEMALE_CN = "zh-CN-XiaoxiaoNeural"
    const val EDGE_VOICE_MALE_CN = "zh-CN-YunxiNeural"
    const val AZURE_VOICE_TW = "zh-TW-HsiaoChenNeural"

    /** 設定頁音色選單（tts_voice）資料源 */
    fun builtinVoices(): List<String> = listOf(
        EDGE_VOICE_FEMALE_TW, EDGE_VOICE_MALE_TW,
        EDGE_VOICE_FEMALE_CN, EDGE_VOICE_MALE_CN,
        "zh-HK-HiuMaanNeural", "zh-CN-YunyangNeural"
    )

    // ─────────────────── 內建引擎預設 ───────────────────

    /** 微軟 Edge 線上真人語音（免費、開箱即用——預設引擎） */
    fun edgePreset(
        voice: String = EDGE_VOICE_FEMALE_TW,
        rate: Float = 1.0f,
        pitch: Float = 1.0f
    ): HttpTts = HttpTts(
        id = "edge-neural",
        name = AppContext.ctx.getString(R.string.tts_edge),
        engineType = ENGINE_EDGE,
        url = EDGE_WSS_URL,
        contentType = "audio/mpeg",
        voice = voice,
        rate = rate,
        pitch = pitch
    )

    /** Azure 語音服務（POST＋SSML＋訂閱金鑰——使用者自填 region／金鑰） */
    fun azurePreset(
        region: String,
        apiKey: String,
        voice: String = AZURE_VOICE_TW,
        rate: Float = 1.0f,
        pitch: Float = 1.0f
    ): HttpTts {
        val headers = linkedMapOf(
            "Ocp-Apim-Subscription-Key" to apiKey,
            "X-Microsoft-OutputFormat" to "audio-24khz-48kbitrate-mono-mp3",
            "Content-Type" to "application/ssml+xml"
        )
        return HttpTts(
            id = "azure-$region",
            name = AppContext.ctx.getString(R.string.tts_azure),
            engineType = ENGINE_REST,
            url = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1",
            contentType = "audio/mpeg",
            headersJson = gson.toJson(headers),
            voice = voice,
            rate = rate,
            pitch = pitch,
            post = true
        )
    }

    /** 使用者自訂模板引擎（GET 形態） */
    fun customPreset(
        id: String,
        name: String,
        url: String,
        contentType: String? = null,
        headersJson: String? = null,
        voice: String = ""
    ): HttpTts = HttpTts(
        id = id,
        name = name,
        engineType = ENGINE_REST,
        url = url,
        contentType = contentType,
        headersJson = headersJson,
        voice = voice
    )

    // ─────────────────── 選定引擎與設定持久化 ───────────────────

    fun saveSelectedEngine(engine: HttpTts) {
        prefs.edit().putString(KEY_SELECTED_ENGINE, gson.toJson(engine)).apply()
    }

    fun loadSelectedEngine(): HttpTts? = runCatching {
        prefs.getString(KEY_SELECTED_ENGINE, null)?.let {
            gson.fromJson(it, HttpTts::class.java)
        }
    }.getOrNull()

    /** 預設引擎（未設定時開箱即用：Edge 台灣女聲） */
    fun defaultEngine(): HttpTts = edgePreset()

    /** 朗讀完自動下一章（預設開啟——tts_auto_next） */
    fun autoNextEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_NEXT, true)

    fun setAutoNextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
    }

    // ─────────────────── 設定頁文案（零硬編碼中文） ───────────────────

    fun engineGroupTitle(): String = AppContext.ctx.getString(R.string.tts_http_engine)

    fun noEngineHint(): String = AppContext.ctx.getString(R.string.tts_no_engine)

    fun voiceLabel(): String = AppContext.ctx.getString(R.string.tts_voice)

    fun rateLabel(): String = AppContext.ctx.getString(R.string.tts_rate)

    fun pitchLabel(): String = AppContext.ctx.getString(R.string.tts_pitch)

    fun testLabel(): String = AppContext.ctx.getString(R.string.tts_test)

    fun autoNextLabel(): String = AppContext.ctx.getString(R.string.tts_auto_next)

    /** 試聽語句（tts_test 按鈕的合成內容——取應用標語，語意自洽） */
    fun previewText(): String = AppContext.ctx.getString(R.string.app_slogan)

    // ═════════════════ 合成主入口 ═════════════════

    /**
     * 合成一段文字為音訊檔（快取命中零網路）。
     *
     * @return 可供 MediaPlayer 播放的本機檔；失敗回 null（呼叫端
     *         依連續失敗次數決定跳段或停止）
     */
    suspend fun synthesize(engine: HttpTts, text: String): File? = withContext(Dispatchers.IO) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return@withContext null
        val cached = cacheFileOf(engine.id, normalized)
        if (cached.isFile && cached.length() > 0L) return@withContext cached
        when (engine.engineType) {
            ENGINE_EDGE -> edgeSynthesize(engine, normalized)
            else -> restSynthesize(engine, normalized)
        }
    }

    /**
     * 依序合成整批段落（供預取——當前段播放時預先備妥下一段，
     * 服務層以呼叫本函數達成無縫銜接）。
     */
    suspend fun synthesizeAll(engine: HttpTts, segments: List<String>): List<File> =
        segments.mapNotNull { synthesize(engine, it) }

    // ═════════════════ 分段 ═════════════════

    /**
     * 分段策略：先按換行段落，再按句讀（。！？!?；;…）切，
     * 超長句硬切。保證每段 ≤ [maxChars] 且不切斷句意。
     */
    fun splitSegments(text: String, maxChars: Int = MAX_SEGMENT_CHARS): List<String> {
        val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()
        val segments = mutableListOf<String>()
        val sentenceSplit = Regex("(?<=[。！？!?；;…])")
        for (paragraph in paragraphs) {
            if (paragraph.length <= maxChars) {
                segments.add(paragraph)
                continue
            }
            var buffer = StringBuilder()
            for (sentence in sentenceSplit.split(paragraph).map { it.trim() }) {
                if (sentence.isEmpty()) continue
                if (buffer.isNotEmpty() && buffer.length + sentence.length > maxChars) {
                    segments.add(buffer.toString())
                    buffer = StringBuilder()
                }
                if (sentence.length <= maxChars) {
                    buffer.append(sentence)
                } else {
                    // 超長無句讀句：硬切
                    var rest = sentence
                    while (rest.length > maxChars) {
                        segments.add(rest.substring(0, maxChars))
                        rest = rest.substring(maxChars)
                    }
                    buffer.append(rest)
                }
            }
            if (buffer.isNotEmpty()) segments.add(buffer.toString())
        }
        return segments
    }

    // ═════════════════ REST 模板引擎 ═════════════════

    private suspend fun restSynthesize(engine: HttpTts, speakText: String): File? {
        val origin = hostOf(engine.url) ?: engine.id
        SourceRateLimiter.awaitPermit(origin)
        val expanded = expandTemplate(engine, speakText)
        val builder = Request.Builder().url(expanded)
        // 瀏覽器級標頭打底，自訂標頭（金鑰／鑑權）覆寫其上
        UserAgentPool.browserHeaders(referer = rootUrlOf(engine.url)).forEach { (k, v) ->
            builder.header(k, v)
        }
        extraHeadersOf(engine).forEach { (k, v) -> builder.header(k, v) }
        if (engine.post) {
            builder.post(ssmlBody(engine, speakText).toRequestBody(SSML_MEDIA))
        }
        val request = builder.build()
        val response = runCatching { restClient.newCall(request).execute() }
            .recoverCatching {
                if (SslTrustHelper.isSslError(it)) {
                    SslTrustHelper.tolerantClient().newCall(request).execute()
                } else throw it
            }
            .getOrNull() ?: return null
        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "TTS 回應碼 ${resp.code}（${engine.name}）")
                return null
            }
            val contentType = (resp.header("Content-Type") ?: engine.contentType ?: "")
                .lowercase()
            if (contentType.isNotEmpty() && !contentType.startsWith("audio") &&
                !contentType.contains("octet-stream")
            ) {
                Log.w(TAG, "TTS 回應非音訊（$contentType）：${engine.name}")
                return null
            }
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null
            return saveAudioBytes(bytes, engine.id, speakText)
        }
    }

    /** URL 模板展開（REST 引擎的通用契約） */
    private fun expandTemplate(engine: HttpTts, speakText: String): String =
        engine.url
            .replace("{{speakText}}", urlEncode(speakText))
            .replace("{{speed}}", percentOf(engine.rate))
            .replace("{{pitchRate}}", percentOf(engine.pitch))
            .replace("{{voice}}", urlEncode(engine.voice))

    @Suppress("UNCHECKED_CAST")
    private fun extraHeadersOf(engine: HttpTts): Map<String, String> {
        val raw = engine.headersJson ?: return emptyMap()
        return runCatching {
            gson.fromJson(raw, object : TypeToken<Map<String, String>>() {}.type)
                as? Map<String, String>
        }.getOrNull().orEmpty()
    }

    // ═════════════════ Edge WebSocket 引擎 ═════════════════

    /**
     * Sec-MS-GEC DRM 權杖：SHA-256( unix 秒 / 30 + TrustedClientToken )
     * 之十六進位大寫——2024 年後 Edge 朗讀端點的必要參數。
     */
    private fun edgeSecGec(): String {
        val ticks = System.currentTimeMillis() / 1000L / 30L
        val seed = "$ticks$EDGE_TRUSTED_TOKEN".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(seed)
            .joinToString("") { String.format("%02X", it) }
    }

    private suspend fun edgeSynthesize(engine: HttpTts, speakText: String): File? {
        // Edge 為固定服務端點：以服務 host 作為限流 origin
        SourceRateLimiter.awaitPermit(EDGE_ORIGIN_KEY)
        val request = Request.Builder()
            .url("$EDGE_WSS_URL&Sec-MS-GEC=${edgeSecGec()}")
            .header("Origin", EDGE_ORIGIN)
            .header("User-Agent", EDGE_UA)
            .header("Sec-MS-GEC-Version", EDGE_GEC_VERSION)
            .build()
        return withTimeoutOrNull(SYNTHESIZE_TIMEOUT_MS) {
            suspendCancellableCoroutine<File?> { cont ->
                val audio = ByteArrayOutputStream()
                var socket: WebSocket? = null
                val listener = object : WebSocketListener() {

                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // 協定兩幀：語音輸出組態 → 合成請求（SSML）
                        webSocket.send(speechConfigMessage())
                        webSocket.send(ssmlMessage(engine, speakText))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        parseEdgeAudioFrame(bytes)?.let { audio.write(it) }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("Path:turn.end")) {
                            runCatching { webSocket.close(1000, "synthesis-complete") }
                            if (cont.isActive) {
                                cont.resume(
                                    saveAudioBytes(audio.toByteArray(), engine.id, speakText)
                                )
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        Log.w(TAG, "Edge TTS 通道失敗", t)
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        // 未必走到 turn.end 就關閉（服務端拒絕／限流）→ 視為失敗
                        if (cont.isActive) cont.resume(null)
                    }
                }
                socket = wsClient.newWebSocket(request, listener)
                cont.invokeOnCancellation { socket?.cancel() }
            }
        }
    }

    /** 二進位音訊幀解析：前 2 bytes 大端標頭長 → Path:audio 後為 MP3 資料 */
    private fun parseEdgeAudioFrame(bytes: ByteString): ByteArray? {
        if (bytes.size < 2) return null
        val headerLen = ((bytes.get(0).toInt() and 0xFF) shl 8) or
            (bytes.get(1).toInt() and 0xFF)
        if (headerLen < 0 || headerLen + 2 > bytes.size) return null
        val header = bytes.substring(2, 2 + headerLen).utf8()
        if (!header.contains("Path:audio")) return null
        return bytes.substring(2 + headerLen, bytes.size).toByteArray()
    }

    /** 幀一：speech.config（輸出格式：24kHz 48kbps 單聲道 MP3） */
    private fun speechConfigMessage(): String {
        val config = "{\"context\":{\"synthesis\":{\"audio\":{" +
            "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
            "\"wordBoundaryEnabled\":\"true\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        return "X-Timestamp:${edgeTimestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            config
    }

    /** 幀二：SSML 合成請求（音色／語速／音調即由 prosody 屬性承載） */
    private fun ssmlMessage(engine: HttpTts, speakText: String): String =
        "X-RequestId:${randomHex32()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${edgeTimestamp()}\r\n" +
            "Path:ssml\r\n\r\n" +
            ssmlBody(engine, speakText)

    /** SSML 本體（Edge 幀二與 REST POST 共用） */
    private fun ssmlBody(engine: HttpTts, speakText: String): String {
        val escaped = escapeXml(speakText)
        val lang = languageOf(engine.voice)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
            "xml:lang='$lang'>" +
            "<voice name='${engine.voice}'>" +
            "<prosody pitch='${percentOf(engine.pitch)}' " +
            "rate='${percentOf(engine.rate)}' volume='+0%'>" +
            escaped + "</prosody></voice></speak>"
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** 音色名 → BCP-47 語言碼（zh-TW-HsiaoChenNeural → zh-TW） */
    private fun languageOf(voice: String): String {
        val parts = voice.split("-")
        return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "zh-TW"
    }

    /** 倍率 → SSML 百分比（1.12 → +12%；0.92 → -8%） */
    private fun percentOf(ratio: Float): String {
        val pct = ((ratio - 1.0f) * 100f).toInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private fun edgeTimestamp(): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }

    private fun randomHex32(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    // ═════════════════ 音訊快取 ═════════════════

    private fun cacheDir(): File = File(AppContext.ctx.cacheDir, CACHE_DIR_NAME)

    /** 快取鍵：SHA-256( 引擎 id + NUL + 文字 ) 前 24 碼 */
    private fun cacheFileOf(engineId: String, text: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((engineId + " " + text).toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
        return File(cacheDir(), "${digest.take(24)}.mp3")
    }

    /** 落盤＋觸發容量修剪；任何 IO 失敗回 null（呼叫端視為合成失敗） */
    private fun saveAudioBytes(bytes: ByteArray, engineId: String, text: String): File? =
        runCatching {
            val file = cacheFileOf(engineId, text)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            trimCacheIfNeeded()
            file
        }.getOrNull()

    /** 逾限自最舊刪除（同一段落重聽零流量的代價是 64MB 上限） */
    private fun trimCacheIfNeeded() {
        val files = cacheDir().listFiles()?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= CACHE_MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_MAX_BYTES) break
            total -= file.length()
            runCatching { file.delete() }
        }
    }

    /** 手動清空語音快取（設定頁儲存空間工具） */
    fun clearCache() {
        cacheDir().listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ═════════════════ 工具 ═════════════════

    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        return runCatching { URI(trimmed).host }.getOrNull()
            ?: Regex("//([^/?#]+)").find(trimmed)?.groupValues?.get(1)
    }

    private fun rootUrlOf(url: String): String? {
        val host = hostOf(url) ?: return null
        val scheme = if (trimmedSchemeIsHttps(url)) "https" else "http"
        return "$scheme://$host/"
    }

    private fun trimmedSchemeIsHttps(url: String): Boolean =
        url.trim().startsWith("https", ignoreCase = true)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

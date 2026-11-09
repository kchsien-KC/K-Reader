package com.k.reader.help.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — AI 閱讀助手（十大旗艦功能：AI 助手）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（帶著上下文問書，而不是問搜尋引擎）：
 *   讀到文言文卡住、伏筆看不懂、或單純想複習前情——把「選中
 *   的那段」直接交給大模型，比跳出去問搜尋引擎少了全部的複製
 *   貼上摩擦。本助手走 OpenAI 相容端點（ai_api_config——OpenAI/
 *   Azure/OpenRouter/Groq/本地 Ollama 皆宜，使用者自填網址＋
 *   金鑰＋模型名）：
 *
 *    · 三個動作（皆以選中文字為輸入）：
 *       ai_summary 段落摘要——濃縮情節，三句以內；
 *       ai_translate_classical 文言文白話翻譯——逐意對譯；
 *       ai_explain 深度解讀——技法／意象／情感分點剖析；
 *    · 系統提示詞：繁體中文寫死的角色設定（與繁轉繁保底詞典
 *      同類——屬功能資料而非 UI 文案，UI 文案零硬編碼原則不破）；
 *    · 防封鎖護航（直接消費 Stage 2 三件套）：每次呼叫前
 *      SourceRateLimiter.awaitPermit（API host 為限流源）；
 *      UserAgentPool.browserHeaders 打底；SSL 類失敗
 *      SslTrustHelper 寬容通道重試一次（本地閘道自簽常見）；
 *    · 逾時 60 秒（規格值）＋單次回應上限 1024 tokens——深度
 *      解讀需要餘裕，但也不放任無限燒 token；
 *    · 對話框：dialog_ai_assistant.xml（輸入預填選中文字、
 *      三動作按鈕、ai_running 狀態列、結果區＋一鍵複製）。
 *
 *  整合點：
 *   · 閱讀頁長按選取 → 選單「AI 閱讀助手」→ showDialog(text)
 *   · 設定頁「自訂 API（OpenAI 相容）」→ saveConfig/loadConfig
 */
object AiAssistantHelper {

    private const val TAG = "AiAssistantHelper"

    /** 與全域設定共用（API 組態屬使用者設定） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_API_URL = "ai_api_url"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_API_MODEL = "ai_api_model"

    /** AI 呼叫逾時（規格：60 秒——長文深度解讀需時） */
    const val REQUEST_TIMEOUT_MS = 60_000L

    /** 單次回應 token 上限（規格：1024） */
    const val MAX_OUTPUT_TOKENS = 1024

    // ═════════════════ 動作定義 ═════════════════

    const val ACTION_SUMMARY = "SUMMARY"
    const val ACTION_CLASSICAL = "CLASSICAL"
    const val ACTION_EXPLAIN = "EXPLAIN"

    /** 動作規格：顯示文案資源＋系統提示詞＋溫度 */
    private data class ActionSpec(
        val labelRes: Int,
        val systemPrompt: String,
        val temperature: Double
    )

    /*
     * 系統提示詞（繁體中文功能資料——比照 TraditionalChineseHelper
     * 內建詞典的定位：非 UI 文案，故不入 strings.xml）。
     */
    private val ACTIONS = linkedMapOf(
        ACTION_SUMMARY to ActionSpec(
            labelRes = R.string.ai_summary,
            systemPrompt = "你是精煉的文學摘要助手。將使用者提供的段落濃縮為三句以內" +
                "的繁體中文摘要，只保留情節推進與關鍵轉折，不添加原文沒有的推測。",
            temperature = 0.3
        ),
        ACTION_CLASSICAL to ActionSpec(
            labelRes = R.string.ai_translate_classical,
            systemPrompt = "你是古典文學譯者。將使用者提供的文言文段落譯為流暢的白話" +
                "繁體中文，語氣貼近原意，人名地名保留原文，不添加註解。",
            temperature = 0.2
        ),
        ACTION_EXPLAIN to ActionSpec(
            labelRes = R.string.ai_explain,
            systemPrompt = "你是資深的文學評論家。深度解讀使用者提供的段落：點出寫作" +
                "技法、意象與情感層次，回覆使用繁體中文，分點不超過五點。",
            temperature = 0.5
        )
    )

    private val gson = Gson()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** API 組態（OpenAI 相容） */
    data class ApiConfig(
        val url: String,
        val apiKey: String,
        val model: String
    )

    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // ═════════════════ 組態 ═════════════════

    fun saveConfig(url: String, apiKey: String, model: String): Boolean {
        if (url.isBlank() || apiKey.isBlank() || model.isBlank()) return false
        prefs.edit()
            .putString(KEY_API_URL, url.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_API_MODEL, model.trim())
            .apply()
        return true
    }

    fun loadConfig(): ApiConfig? {
        val url = prefs.getString(KEY_API_URL, null)?.trim().orEmpty()
        val key = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val model = prefs.getString(KEY_API_MODEL, null)?.trim().orEmpty()
        if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
        return ApiConfig(url, key, model)
    }

    fun isConfigured(): Boolean = loadConfig() != null

    /**
     * 端點正規化：基底網址 → /v1/chat/completions；
     * 使用者已填完整端點或 /v1 結尾時尊重原樣。
     */
    private fun chatEndpointOf(rawUrl: String): String {
        val base = rawUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    // ═════════════════ 呼叫主流程 ═════════════════

    /**
     * 執行一次 AI 動作：選中文字 → 系統提示＋使用者訊息 →
     * chat/completions → choices[0].message.content。
     * 失敗回 null（呼叫端以 ai_error 回報）。
     */
    suspend fun ask(action: String, text: String): String? = withContext(Dispatchers.IO) {
        val config = loadConfig() ?: return@withContext null
        val spec = ACTIONS[action] ?: return@withContext null
        val content = text.trim()
        if (content.isEmpty()) return@withContext null

        val body = linkedMapOf<String, Any>(
            "model" to config.model,
            "messages" to listOf(
                linkedMapOf<String, Any>(
                    "role" to "system",
                    "content" to spec.systemPrompt
                ),
                linkedMapOf<String, Any>(
                    "role" to "user",
                    "content" to content
                )
            ),
            "temperature" to spec.temperature,
            "max_tokens" to MAX_OUTPUT_TOKENS,
            "stream" to false
        )
        val request = Request.Builder()
            .url(chatEndpointOf(config.url))
            .apply {
                UserAgentPool.browserHeaders().forEach { (k, v) -> header(k, v) }
                header("Authorization", "Bearer ${config.apiKey}")
            }
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .build()

        // 單源限流（API host）＋ SSL 寬容重試
        SourceRateLimiter.awaitPermit(originKeyOf(config.url))
        val response = runCatching {
            apiClient.newCall(request).execute()
        }.recoverCatching {
            if (SslTrustHelper.isSslError(it)) {
                SslTrustHelper.tolerantClient().newCall(request).execute()
            } else throw it
        }.onFailure { e ->
            Log.w(TAG, "AI 呼叫失敗（$action）", e)
        }.getOrNull() ?: return@withContext null

        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "AI 回應碼 ${resp.code}（$action）")
                return@withContext null
            }
            val payload = resp.body?.string() ?: return@withContext null
            parseReply(payload)
        }
    }

    /** OpenAI 相容回應解析：choices[0].message.content */
    private fun parseReply(payload: String): String? = runCatching {
        val choices = JsonParser.parseString(payload).asJsonObject
            .getAsJsonArray("choices")
            ?: return null
        val message = choices.get(0)?.asJsonObject
            ?.getAsJsonObject("message") ?: return null
        message.get("content")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    // ═════════════════ 對話框（dialog_ai_assistant.xml） ═════════════════

    /**
     * AI 助手對話框：輸入預填選中文字 → 三動作按鈕 →
     * ai_running 狀態列 → 結果區（一鍵複製）。
     */
    fun showDialog(activity: FragmentActivity, selectedText: String) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_ai_assistant, null)
        val input = view.findViewById<EditText>(R.id.et_ai_input)
        val output = view.findViewById<TextView>(R.id.tv_ai_output)
        val statusRow = view.findViewById<View>(R.id.row_ai_status)
        val statusText = view.findViewById<TextView>(R.id.tv_ai_status)

        input.setText(selectedText)
        statusRow.visibility = View.GONE

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.ai_assistant)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun runAction(action: String) {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return
            if (!isConfigured()) {
                toastOnMain(activity, activity.getString(R.string.ai_error))
                return
            }
            statusText.setText(R.string.ai_running)
            statusRow.visibility = View.VISIBLE
            output.text = ""
            managerScope.launch {
                val reply = runCatching { ask(action, text) }.getOrNull()
                mainHandler.post {
                    statusRow.visibility = View.GONE
                    if (reply == null) {
                        toastOnMain(activity, activity.getString(R.string.ai_error))
                    } else {
                        output.text = reply
                    }
                }
            }
        }

        view.findViewById<View>(R.id.btn_ai_summary)
            .setOnClickListener { runAction(ACTION_SUMMARY) }
        view.findViewById<View>(R.id.btn_ai_classical)
            .setOnClickListener { runAction(ACTION_CLASSICAL) }
        view.findViewById<View>(R.id.btn_ai_explain)
            .setOnClickListener { runAction(ACTION_EXPLAIN) }

        view.findViewById<View>(R.id.btn_ai_copy).setOnClickListener {
            val text = output.text.toString()
            if (text.isEmpty()) return@setOnClickListener
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("K.Reader", text))
            toastOnMain(activity, activity.getString(R.string.copied))
        }

        dialog.show()
    }

    // ═════════════════ UI 文案 ═════════════════

    fun assistantTitle(): String = AppContext.ctx.getString(R.string.ai_assistant)

    fun actionLabel(action: String): String? =
        ACTIONS[action]?.let { AppContext.ctx.getString(it.labelRes) }

    fun configLabel(): String = AppContext.ctx.getString(R.string.ai_api_config)

    fun apiUrlLabel(): String = AppContext.ctx.getString(R.string.ai_api_url)

    fun apiKeyLabel(): String = AppContext.ctx.getString(R.string.ai_api_key)

    fun modelLabel(): String = AppContext.ctx.getString(R.string.ai_api_model)

    fun runningLabel(): String = AppContext.ctx.getString(R.string.ai_running)

    fun errorLabel(): String = AppContext.ctx.getString(R.string.ai_error)

    // ═════════════════ 工具 ═════════════════

    private fun originKeyOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host }.getOrNull()
            ?: url.trim().lowercase()

    private fun toastOnMain(activity: FragmentActivity, text: String) {
        mainHandler.post {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }
}

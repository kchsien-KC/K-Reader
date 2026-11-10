package com.k.reader.help.dict

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.http.SourceRateLimiter
import com.k.reader.help.http.SslTrustHelper
import com.k.reader.help.http.UserAgentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 字詞快查助手（旗艦增益包：字詞快查）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（選詞即查——文青的閱讀不該被生詞打斷）：
 *   讀到「翛然」不知道意思、看到「筆耕不輟」想確認用法，
 *   切去瀏覽器搜尋再回來，沉浸感已碎。本助手把三座詞庫
 *   拉到長按選詞的彈指之間：
 *
 *    1. 內建成語典（dict_idiom）：精選 60 條高頻成語，
 *       程式內建資料（比照 TraditionalChineseHelper 詞典
 *       定位——非 UI 文案不入 strings.xml），零網路零延遲，
 *       離線即查；
 *    2. 教育部辭典（dict_moedict）：萌典開放 API
 *      （moedict.tw/〈詞〉.json）——《重編國語辭典修訂本》
 *       條目釋義，權威繁中；
 *    3. 維基百科（dict_wiki）：zh.wikipedia REST 摘要
 *      （api/rest_v1/page/summary）——人物／地名／術語
 *       的百科開場白。
 *
 *  網路兩路全程三件套護航（與 AI 助手同款）：UserAgentPool
 *   瀏覽器級標頭打底、SourceRateLimiter 單源限流（API host
 *   為限流源）、SSL 類失敗 SslTrustHelper 寬容通道重試。
 *
 *  快取：詞→釋義 24 小時 TTL（ConcurrentHashMap——重查
 *   零流量）；三路結果獨立快取（一路失敗不污染另兩路）。
 *
 *  整合點：
 *   · 閱讀頁長按選詞 → lookup(activity, word) → 彙整結果
 *     投 dialog_dict_lookup.xml
 *   · 選詞動作列「查」入口 → buildLookupHint(ctx)
 */
object DictLookupHelper {

    private const val TAG = "DictLookupHelper"

    /** 萌典 API（教育部《重編國語辭典修訂本》開放資料） */
    private const val MOEDICT_BASE = "https://www.moedict.tw/"

    /** 維基百科 REST 摘要 API（zh 繁體站） */
    private const val WIKI_SUMMARY_BASE =
        "https://zh.wikipedia.org/api/rest_v1/page/summary/"

    /** 查詢逾時（毫秒） */
    private const val TIMEOUT_MS = 12_000L

    /** 快取 TTL（24 小時） */
    private const val CACHE_TTL_MS = 24 * 3600_000L

    /** 查詢詞長度上限（成語以四字為主；開放至十詞條名） */
    const val MAX_WORD_LENGTH = 10

    /** 查詢結果（三源獨立——哪路有給哪路） */
    data class LookupResult(
        val word: String,
        val idiom: String?,        // 內建成語典釋義
        val moedict: String?,      // 教育部辭典釋義
        val wiki: String?          // 維基摘要
    ) {
        val hasAny: Boolean get() = idiom != null || moedict != null || wiki != null
    }

    private val gson = Gson()

    /** 查詢詞 → (結果, 快取時間戳)——三源整包快取 */
    private val cache = ConcurrentHashMap<String, Pair<LookupResult, Long>>()

    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8L, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    // ═════════════════ 入口 ═════════════════

    /**
     * 查詢總入口：選詞 → 三源並嘗試（內建即時＋網路兩路）。
     * 內建成語典命中即回（離線可用）；網路路徑於 IO 派發。
     * 詞太長／空白 → null（呼叫端以 dict_lookup_not_found 提示）。
     */
    suspend fun lookup(word: String): LookupResult? = withContext(Dispatchers.IO) {
        val query = word.trim()
        if (query.isEmpty() || query.length > MAX_WORD_LENGTH) return@withContext null

        // 快取命中（TTL 內）——零流量回
        cache[query]?.let { (result, at) ->
            if (System.currentTimeMillis() - at < CACHE_TTL_MS) {
                Log.d(TAG, "查詢快取命中：$query")
                return@withContext result
            }
        }

        val idiomDef = lookupIdiom(query)
        val moedictDef = lookupMoedict(query)
        val wikiDef = lookupWiki(query)
        val result = LookupResult(query, idiomDef, moedictDef, wikiDef)
        if (result.hasAny) cache[query] = result to System.currentTimeMillis()
        result
    }

    /** 清空查詢快取（設定頁「清除快取」動作） */
    fun clearCache() {
        cache.clear()
    }

    // ═════════════════ 源一：內建成語典 ═════════════════

    /**
     * 內建精選成語 60 條（詞→釋義）。
     * 網文高頻＋古典文常見——資料層定位（不入 strings.xml）。
     */
    private val IDIOM_DICT: Map<String, String> = mapOf(
        "孜孜不倦" to "勤勉努力，不知疲倦。",
        "筆耕不輟" to "持續不斷地寫作，從未停筆。",
        "嘔心瀝血" to "形容費盡心思、耗盡心血地創作或經營。",
        "行雲流水" to "形容文章、書法自然流暢，不受拘束。",
        "字裡行間" to "文章的字句與行段之間，指文句隱含的意緒。",
        "躍然紙上" to "形容描繪或刻畫得極為生動逼真。",
        "栩栩如生" to "形容藝術形象極為逼真，宛如活的一樣。",
        "入木三分" to "形容書法筆力剛勁，亦比喻見解深刻。",
        "力透紙背" to "形容書法遒勁有力，亦形容詩文立意深刻。",
        "一氣呵成" to "一口氣完成。形容文章結構緊湊、氣勢連貫。",
        "妙筆生花" to "形容文筆絕佳，才思橫溢。",
        "錦心繡口" to "形容文思優美，詞藻華麗。",
        "擲地有聲" to "形容文章辭句工工麗，鏗鏘有力。",
        "洛陽紙貴" to "形容著作廣受歡迎，風行一時。",
        "汗牛充棟" to "書籍極多，堆滿屋子，搬運時使牛馬都累出汗。",
        "韋編三絕" to "形容讀書勤奮刻苦。",
        "囊螢映雪" to "形容家境貧困而仍刻苦勤學。",
        "懸梁刺股" to "比喻發憤讀書，刻苦自勵。",
        "鑿壁偷光" to "形容家貧而好學。",
        "手不釋卷" to "書不離手，形容勤奮好學。",
        "廢寢忘食" to "專注投入，忘記睡覺與吃飯。",
        "如饑似渴" to "形容要求極為迫切，如饑餓渴求飲水一般。",
        "博覽群書" to "廣泛閱讀各類書籍，形容學識廣博。",
        "融會貫通" to "將各方面的知識或道理融合貫串，得到全面理解。",
        "舉一反三" to "從一件事情類推而知道其他許多事情。",
        "溫故知新" to "溫習舊學，從中獲得新的領悟。",
        "學以致用" to "將所學知識用於實際。",
        "青出於藍" to "弟子勝過老師，後人勝過前人。",
        "出類拔萃" to "才能超出同類之上。",
        "鶴立雞群" to "比喻儀表或才能在人群中格外突出。",
        "鋒芒畢露" to "才華全數顯露，銳氣逼人。",
        "深藏不露" to "有真才實學而不顯露。",
        "虛懷若谷" to "胸懷寬廣如山谷能容，形容非常謙虛。",
        "不恥下問" to "不以向學問地位較低的人請教為恥。",
        "從善如流" to "接受善言勸告，如同流水般自然迅速。",
        "虛左以待" to "空出尊位恭候賢者，形容禮賢下士。",
        "禮賢下士" to "有權位者尊敬賢才，降低身分結交士人。",
        "高山流水" to "比喻知音難遇，亦形容樂曲高妙。",
        "相見恨晚" to "憾恨相識太遲，形容一見如故。",
        "莫逆之交" to "心意相投、友誼深厚的朋友。",
        "君子之交" to "賢者之間的交情，平淡如水，不尚虛華。",
        "管鮑之交" to "比喻交情深厚、彼此知心的朋友。",
        "刎頸之交" to "同生共死、患難與共的朋友。",
        "肝膽相照" to "以真心相見，坦誠無私。",
        "披肝瀝膽" to "比喻開誠相見，竭盡忠誠。",
        "雪中送炭" to "在別人急難時給予及時的幫助。",
        "錦上添花" to "使美好的事物更加美好。",
        "畫龍點睛" to "在關鍵處加上精闢一筆，使全篇生動傳神。",
        "畫蛇添足" to "多此一舉，反而弄巧成拙。",
        "弄巧成拙" to "本想耍弄巧計，結果反而壞事。",
        "南轅北轍" to "行動與目的相反，離目標越來越遠。",
        "緣木求魚" to "方向或方法錯誤，不可能達到目的。",
        "水中撈月" to "比喻徒勞無功，無法實現。",
        "竹籃打水" to "比喻白費力氣，一無所得。",
        "滄海一粟" to "大海中的一粒穀子，比喻極其渺小。",
        "曇花一現" to "美好事物出現的時間極為短暫。",
        "白駒過隙" to "時光飛逝，如白馬跳過縫隙般迅速。",
        "光陰似箭" to "形容時間消逝如飛箭般快速。",
        "歲月如梭" to "形容時間過得很快。",
        "滄海桑田" to "比喻世事變化很大。"
    )

    /** 內建典查詢（離線即時；非成語回 null） */
    fun lookupIdiom(word: String): String? =
        IDIOM_DICT[word.trim()]

    /** 內建典詞數（關於頁資訊） */
    fun idiomCount(): Int = IDIOM_DICT.size

    // ═════════════════ 源二：萌典（教育部辭典） ═════════════════

    /**
     * 萌典 API 查詢：GET https://www.moedict.tw/〈詞〉.json
     * 回應結構：{ "word": ..., "definitions": [ { "def": "釋義…" } ] }
     * 取前 3 條 def 串接（超出的以 … 標示）。
     */
    private suspend fun lookupMoedict(word: String): String? =
        lookupJsonApi(MOEDICT_BASE + word + ".json", word) { payload ->
            runCatching {
                val root = JsonParser.parseString(payload).asJsonObject
                val defs = root.getAsJsonArray("definitions") ?: return@runCatching null
                val parts = defs.take(3)
                    .mapNotNull { el ->
                        el.asJsonObject.get("def")?.asString?.trim()
                    }
                if (parts.isEmpty()) null else parts.joinToString("\n") {
                    "· " + it.replace(Regex("^=\\s*"), "")
                }
            }.getOrNull()
        }

    // ═════════════════ 源三：維基摘要 ═════════════════

    /**
     * 維基 REST 摘要：GET .../page/summary/〈標題〉
     * 回應：{ "title": ..., "extract": "開場摘要…" }
     * 404（無此條目）屬正常——回 null 不算錯誤。
     */
    private suspend fun lookupWiki(word: String): String? =
        lookupJsonApi(WIKI_SUMMARY_BASE + word, word) { payload ->
            runCatching {
                val root = JsonParser.parseString(payload).asJsonObject
                root.get("extract")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            }.getOrNull()
        }

    /**
     * JSON API 共用骨架：三件套護航（瀏覽器級標頭＋單源限流＋
     * SSL 寬容重試）→ GET → parse。失敗回 null（單源失敗不
     * 影響其他源）。
     */
    private suspend fun lookupJsonApi(
        url: String,
        rateKey: String,
        parse: (String) -> String?
    ): String? {
        val request = Request.Builder()
            .url(url)
            .apply { UserAgentPool.browserHeaders().forEach { (k, v) -> header(k, v) } }
            .header("Accept", "application/json")
            .get()
            .build()

        // 單源限流（API host 為限流源——頻繁選詞不轟炸詞庫）
        SourceRateLimiter.awaitPermit(rateKey)
        val response = runCatching {
            apiClient.newCall(request).execute()
        }.recoverCatching {
            if (SslTrustHelper.isSslError(it)) {
                SslTrustHelper.tolerantClient().newCall(request).execute()
            } else throw it
        }.onFailure { e ->
            Log.w(TAG, "詞庫查詢失敗（$rateKey）", e)
        }.getOrNull() ?: return null

        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.d(TAG, "詞庫回應碼 ${resp.code}（$rateKey）")
                return null
            }
            val payload = resp.body?.string() ?: return null
            return parse(payload)
        }
    }

    // ═════════════════ 文案輔助 ═════════════════

    /** 選詞動作列提示文案（閱讀頁選詞後顯示） */
    fun buildLookupHint(ctx: Context, word: String): String =
        ctx.getString(R.string.dict_lookup, word)

    /** 結果拼裝（供對話框一次顯示——含各源標題） */
    fun formatResult(ctx: Context, result: LookupResult): String {
        val sb = StringBuilder()
        result.idiom?.let { sb.append(ctx.getString(R.string.dict_idiom)).append("\n").append(it).append("\n\n") }
        result.moedict?.let { sb.append(ctx.getString(R.string.dict_moedict)).append("\n").append(it).append("\n\n") }
        result.wiki?.let { sb.append(ctx.getString(R.string.dict_wiki)).append("\n").append(it) }
        return sb.toString().trim()
    }
}

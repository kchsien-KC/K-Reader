package com.k.reader.help.tts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import org.json.JSONObject
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 聽書微調助手（Stage 12：閱讀深耕與個人化）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（耳朵和眼睛一樣挑剔）：
 *   Stage 4 的 HTTP 聽書把文字送進了聲帶，但耳朵的需求和眼睛
 *   不同：數學式唸出來是災難、括號註釋是岔路、省略號是噪音；
 *   而且不同書該用不同語速——輕小說趕劇情快些，文史細啃慢些。
 *   本助手補上「朗讀前處理＋逐書微調」層：
 *
 *    1. 逐書語速：rate 0.5x～2.0x（夾擊）逐書記憶（帳冊以書名
 *       為鍵，換源不改鍵）；沒調過的書落回全域語速；
 *    2. 朗讀淨化：sanitizeForSpeech()——朗讀前把「不該被唸出
 *       的東西」剝掉（圓/方括號註釋、刪節號、連續破折、裝飾
 *       星號、章節分隔線）；淨化率即時回報（剝了幾字——讓
 *       使用者知道為什麼唸的比看的短）；
 *    3. 段落停頓：段落邊界注入停頓標記（SSML break 相容：
 *       REST 引擎吃 XML break，EDGE 引擎退回換行分段——換行
 *       即停頓，零引擎依賴）。停頓秒數 0～3s 夾擊，全域一設
 *       逐書亦可覆寫。
 *
 *  規格值：rate 50～200（百分比，預設 100）；停頓 0～3s（預設
 *  0.8s）；淨化輸出單段上限沿用 HttpTtsEngine.MAX_SEGMENT_CHARS
 *  （300 字——分段職責在引擎，本助手只做前置清洗）。
 *
 *  整合點：
 *   · HttpTtsEngine 合成前 → sanitizeForSpeech()（朗讀淨化）
 *   · 聽書服務語速選單 → rateFor(bookName)／setRate()
 *   · 段落分送 → paragraphPauseMs()（REST 吃 SSML break／
 *     EDGE 換行分段）
 *   · 備份層 → tuningFile()（只拷檔不解析）
 */
object TtsTuningHelper {

    private const val TAG = "TtsTuningHelper"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_GLOBAL_RATE = "tts_global_rate"
    private const val KEY_GLOBAL_PAUSE = "tts_global_pause_ms"

    /** 逐書微調帳冊檔名（filesDir 下，書名→{rate, pauseMs}） */
    const val BOOK_FILE = "tts_tuning.json"

    /** 語速界域（百分比） */
    const val RATE_MIN = 50
    const val RATE_MAX = 200
    const val RATE_DEFAULT = 100

    /** 段落停頓界域（毫秒） */
    const val PAUSE_MIN_MS = 0L
    const val PAUSE_MAX_MS = 3_000L
    const val PAUSE_DEFAULT_MS = 800L

    /** SSML break 標記（REST 引擎；EDGE 引擎由呼叫端以換行退化） */
    const val BREAK_MARK = "<break time=\"%dms\"/>"

    /** 帳冊上限（防 JSON 膨脹） */
    const val MAX_TUNING_BOOKS = 300

    /** 逐書微調（null＝未調，落回全域） */
    data class BookTuning(
        val rate: Int?,
        val pauseMs: Long?,
        val updatedAt: Long,
    ) {
        val isEmpty: Boolean get() = rate == null && pauseMs == null
    }

    /** 三層解析結果（出廠兜底——任何欄位有值） */
    data class Resolved(
        val rate: Int,
        val pauseMs: Long,
        val overrides: Int,
    )

    // ── 公開 API：語速 ──────────────────────────────────────────

    /** 逐書語速（百分比；沒調過落回全域→出廠 100） */
    fun rateFor(bookName: String): Int {
        val t = tuningOf(bookName)
        return (t?.rate ?: globalRate()).coerceIn(RATE_MIN, RATE_MAX)
    }

    /** 設定逐書語速（null＝清除此書覆寫） */
    fun setRate(bookName: String, rate: Int?): Boolean {
        val key = bookName.trim()
        if (key.isEmpty()) return false
        return upsert(key, rate, null)
    }

    /** 全域語速 */
    fun globalRate(): Int =
        prefs().getInt(KEY_GLOBAL_RATE, RATE_DEFAULT).coerceIn(RATE_MIN, RATE_MAX)

    /** 設全域語速 */
    fun setGlobalRate(rate: Int) {
        prefs().edit()
            .putInt(KEY_GLOBAL_RATE, rate.coerceIn(RATE_MIN, RATE_MAX))
            .apply()
    }

    // ── 公開 API：段落停頓 ──────────────────────────────────────

    /** 逐書三層解析（語速＋停頓一起看——面板顯示用） */
    fun resolve(bookName: String): Resolved {
        val t = tuningOf(bookName)
        val gRate = globalRate()
        val gPause = globalPauseMs()
        return Resolved(
            rate = (t?.rate ?: gRate).coerceIn(RATE_MIN, RATE_MAX),
            pauseMs = (t?.pauseMs ?: gPause).coerceIn(PAUSE_MIN_MS, PAUSE_MAX_MS),
            overrides = listOfNotNull(t?.rate, t?.pauseMs?.takeIf { it != null }).size,
        )
    }

    /** 設定逐書停頓（null＝清除此欄覆寫） */
    fun setPause(bookName: String, pauseMs: Long?): Boolean {
        val key = bookName.trim()
        if (key.isEmpty()) return false
        return upsert(key, null, pauseMs)
    }

    /** 全域停頓 */
    fun globalPauseMs(): Long =
        prefs().getLong(KEY_GLOBAL_PAUSE, PAUSE_DEFAULT_MS)
            .coerceIn(PAUSE_MIN_MS, PAUSE_MAX_MS)

    /** 設全域停頓 */
    fun setGlobalPause(pauseMs: Long) {
        prefs().edit()
            .putLong(KEY_GLOBAL_PAUSE, pauseMs.coerceIn(PAUSE_MIN_MS, PAUSE_MAX_MS))
            .apply()
    }

    /** 段落停頓標記（REST 吃 SSML；EDGE 由呼叫端退回換行） */
    fun breakMark(pauseMs: Long): String =
        String.format(java.util.Locale.US, BREAK_MARK, pauseMs)

    // ── 公開 API：朗讀淨化 ──────────────────────────────────────

    /**
     * 朗讀朗讀淨化：剝掉「不該被唸出來的東西」。
     * 回傳（淨化後文字, 剝除字數）——淨化率由 UI 回報。
     */
    fun sanitizeForSpeech(raw: String): Pair<String, Int> {
        val before = raw.length
        val out = raw
            .replace(Regex("（[^）]*）"), "，")            // 圓括註釋→停頓氣口
            .replace(Regex("\\([^)]*\\)"), "，")         // 半形圓括
            .replace(Regex("【[^】]*】"), "，")              // 方頭括標記
            .replace(Regex("\\*{2,}"), "，")              // 裝飾星號
            .replace(Regex("—{2,}|──+"), "，")              // 破折／分隔線
            .replace(Regex("[·•]{2,}"), "，")               // 連續間隔號
            .replace(Regex("…+"), "。")                      // 省略號→句點
            .replace(Regex("[\\s\\t]+"), " ")             // 空白壓一
            .replace(Regex(" ?， ?。 ?"), "。")         // 「，。」併發清理
            .replace(Regex("，{2,}"), "，")             // 連續逗點壓一
            .replace(Regex("　{2,}"), "　")            // 全形空白壓一
            .trim()
        return Pair(out, (before - out.length).coerceAtLeast(0))
    }

    // ── 公開 API：帳冊 ──────────────────────────────────────────

    /** 某書微調（無則 null） */
    fun tuningOf(bookName: String): BookTuning? = load()[bookName.trim()]

    /** 清除某書覆寫（還原跟隨全域） */
    fun clear(bookName: String): Boolean {
        val key = bookName.trim()
        val map = load().toMutableMap()
        if (map.remove(key) == null) return false
        persist(map)
        return true
    }

    /** 已微調書數 */
    fun tuningCount(): Int = load().size

    /** 帳冊檔（供備份層只拷檔不解析） */
    fun tuningFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ── 內部 ───────────────────────────────────────────────────

    private var cache: MutableMap<String, BookTuning>? = null

    /** 帳冊合併寫入（rate／pause 任一為 null 保留舊值；雙 null＝刪列） */
    private fun upsert(key: String, rate: Int?, pauseMs: Long?): Boolean {
        val map = load().toMutableMap()
        val old = map[key]
        val merged = BookTuning(
            rate = (rate ?: old?.rate)?.coerceIn(RATE_MIN, RATE_MAX),
            pauseMs = (pauseMs ?: old?.pauseMs)
                ?.coerceIn(PAUSE_MIN_MS, PAUSE_MAX_MS),
            updatedAt = System.currentTimeMillis(),
        )
        if (merged.isEmpty) {
            map.remove(key)
        } else {
            if (map.size >= MAX_TUNING_BOOKS && old == null) {
                Log.w(TAG, "微調帳冊達上限 $MAX_TUNING_BOOKS——拒絕新增")
                return false
            }
            map[key] = merged
        }
        persist(map)
        return true
    }

    private fun load(): MutableMap<String, BookTuning> {
        cache?.let { return it }
        val out = mutableMapOf<String, BookTuning>()
        val file = tuningFile()
        if (file.exists()) {
            try {
                val o = JSONObject(file.readText(Charsets.UTF_8))
                for (key in o.keySet()) {
                    val t = o.optJSONObject(key) ?: continue
                    out[key] = BookTuning(
                        rate = if (t.has("rate")) t.getInt("rate") else null,
                        pauseMs = if (t.has("pauseMs")) t.getLong("pauseMs") else null,
                        updatedAt = t.optLong("updatedAt", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "微調帳冊解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist(map: Map<String, BookTuning>) {
        try {
            val o = JSONObject()
            for ((k, t) in map) {
                o.put(k, JSONObject().apply {
                    t.rate?.let { put("rate", it) }
                    t.pauseMs?.let { put("pauseMs", it) }
                    put("updatedAt", t.updatedAt)
                })
            }
            tuningFile().writeText(o.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "微調帳冊寫入失敗", t)
        }
    }

    private fun prefs(): SharedPreferences =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.ttune_title)
    fun rateLabel(): String = AppContext.ctx.getString(R.string.ttune_rate)
    fun pauseLabel(): String = AppContext.ctx.getString(R.string.ttune_pause)
    fun globalLabel(): String = AppContext.ctx.getString(R.string.ttune_global)
    fun clearLabel(): String = AppContext.ctx.getString(R.string.ttune_clear)
    fun sanitizeLabel(): String = AppContext.ctx.getString(R.string.ttune_sanitize)

    /** 語速顯示：「120%（此書）」／「100%（全域）」 */
    fun rateLine(resolved: Resolved): String =
        AppContext.ctx.getString(
            R.string.ttune_rate_fmt,
            resolved.rate,
            if (resolved.overrides > 0) bookTag() else globalTag(),
        )

    /** 停頓顯示：「0.8 秒」 */
    fun pauseLine(ms: Long): String =
        AppContext.ctx.getString(R.string.ttune_pause_fmt, ms / 1000.0)

    /** 淨化率顯示：「朗讀淨化：剝除 12 字（3%）」 */
    fun sanitizedNotice(stripped: Int, total: Int): String =
        if (total <= 0) AppContext.ctx.getString(R.string.ttune_sanitize_none)
        else AppContext.ctx.getString(
            R.string.ttune_sanitize_fmt,
            stripped,
            stripped * 100 / total,
        )

    fun clearedNotice(): String = AppContext.ctx.getString(R.string.ttune_cleared)

    private fun bookTag(): String = AppContext.ctx.getString(R.string.ttune_tag_book)
    private fun globalTag(): String = AppContext.ctx.getString(R.string.ttune_tag_global)
}

package com.k.reader.help.content

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.storage.RecycleBinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 淨化規則包管理器（十大旗艦功能：淨化規則包）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（正則煉金爐——還小說一頁清白）：
 *   網路小說正文夾帶三害：站點浮水印（首發域名／一秒記住）、
 *   廣告殘段（括號標記／推廣連結）、互動誘導（加入書籤／求票）。
 *   與其在閱讀頁看到再嘆氣，不如在正文落地前統一淨化：
 *
 *    1. 規則形態：正則替換（找到即刪）；三種綁定範圍——
 *       GLOBAL（全域，含內建規則包）／BOOK（依書名綁定）／
 *       SOURCE（依書源綁定）；
 *    2. 內建台灣常見廣告淨化規則包（purify_tw_pack）：十一條
 *       精練正則，簡繁並寫（淨化發生於簡轉繁之前亦不失手），
 *       開箱即用、可整包停用；
 *    3. 正則預編譯快取（purify_regex_precompiled）：pattern →
 *       Regex 的 ConcurrentHashMap，命中零編譯成本——正文逐
 *       章淨化是高頻路徑，編譯一次終身服役；
 *    4. 安全閘：單規則 pattern 長度上限 512（防災難性回溯）；
 *       編譯失敗的規則自動停用並記日誌，不炸閱讀主流程；
 *    5. 刪除不消失：deleteRule 先進回收站（30 天可還原）。
 *
 *  淨化管線位置：正文快取落地後、送入排版之前（與簡轉繁串接
 *  順序不敏感——規則簡繁並寫）。單章淨化屬互斥小工作，同步執行
 *  即可，不另啟協程。
 *
 *  整合點：
 *   · ContentViewModel／BookHelp 取得正文後 → apply(content,
 *     bookName, sourceUrl)
 *   · 設定頁「淨化規則包」（purify_title）→ 規則列表 CRUD
 *   · 台灣規則包開關（purify_tw_pack）→ setTwPackEnabled
 */
object CleanRuleManager {

    private const val TAG = "CleanRuleManager"

    /** 與全域設定共用（規則是使用者設定的一部分） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_RULES = "purify_rules_json"

    /** 單一規則 pattern 長度上限（規格：512——防災難性回溯） */
    const val MAX_PATTERN_LENGTH = 512

    // ═════════════════ 綁定範圍 ═════════════════

    const val SCOPE_GLOBAL = "GLOBAL"
    const val SCOPE_BOOK = "BOOK"
    const val SCOPE_SOURCE = "SOURCE"

    private val gson = Gson()

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 正則預編譯快取（pattern → 編譯成品；purify_regex_precompiled） */
    private val regexCache = ConcurrentHashMap<String, Regex>()

    /** 規則定義（可 Gson 序列化——匯入匯出／回收站 payload 共用） */
    data class PurifyRule(
        var id: String,
        var name: String,
        var scope: String = SCOPE_GLOBAL,
        var scopeValue: String? = null,
        var pattern: String,
        var enabled: Boolean = true,
        var builtin: Boolean = false
    ) {
        companion object {
            fun fromJson(json: String): PurifyRule? = runCatching {
                Gson().fromJson(json, PurifyRule::class.java)
            }.getOrNull()
        }
    }

    // ═════════════════ 內建台灣規則包 ═════════════════

    /**
     * 內建「台灣常見廣告淨化規則包」（purify_tw_pack）：
     * 十一條站點浮水印／廣告殘段正則。簡繁並寫——淨化發生在
     * 簡轉繁之前或之後都能命中。id 固定冪等（重裝不重複）。
     */
    fun builtinTwPack(): List<PurifyRule> = listOf(
        twRule("tw-pack-ad-bracket", "括號廣告標記",
            "（廣告）|【廣告】|\\(广告\\)|【广告】"),
        twRule("tw-pack-ad-label", "廣告引導行",
            "(廣告|广告)[:：][^\\n]{0,24}"),
        twRule("tw-pack-domain-mark", "首發域名浮水印",
            "(請|请)(記住|记住)本書(首發|首发)(域名|網址|网址)[^\\n]{0,48}"),
        twRule("tw-pack-one-second", "一秒記住浮水印",
            "(一秒鐘|一秒钟)(記住|记住)[^\\n]{0,32}"),
        twRule("tw-pack-site-tail", "站點尾部簽名",
            "(筆趣閣|笔趣阁|趣書庫|趣书库)\\s*[a-z0-9.\\-]+\\.(com|net|org|cc|la|so|tw)"),
        twRule("tw-pack-next-page", "分頁誘導句",
            "本章未完[，,](點擊|点击)(下一頁|下一页)(繼續|继续)(閱讀|阅读)"),
        twRule("tw-pack-mobile-url", "手機版閱讀網址",
            "(手機版|手机版)(閱讀|阅读)(網址|网址)[:：]?[a-z0-9.\\-/]{4,64}"),
        twRule("tw-pack-baidu", "搜尋引擎誘導",
            "(百度搜索|一鍵搜索|一键搜索)[^\\n]{0,32}"),
        twRule("tw-pack-bookmark", "加入書籤誘導",
            "(加入書籤|加入书签|收藏本站|收藏本書)[^\\n]{0,24}"),
        twRule("tw-pack-vote", "求票打賞刷屏",
            "(求收藏|求推薦票|求月票|求打賞|求订阅)([，,。!！\\s]{0,3})+"),
        twRule("tw-pack-clean-reading", "清爽閱讀廣告詞",
            "(無彈窗|无弹窗|最快更新|清爽閱讀|清爽阅读)[^\\n]{0,24}")
    )

    private fun twRule(id: String, name: String, pattern: String) = PurifyRule(
        id = id, name = name, scope = SCOPE_GLOBAL,
        scopeValue = null, pattern = pattern,
        enabled = true, builtin = true
    )

    /** 首次啟用／規則清單為空時安裝內建包（冪等——依 id 去重） */
    fun ensureTwPackInstalled(): Boolean {
        val existing = loadRules().associateBy { it.id }
        val merged = loadRules() + builtinTwPack().filter { it.id !in existing }
        return persist(merged)
    }

    /** 台灣規則包整包啟用／停用（僅作用於 builtin 規則） */
    fun setTwPackEnabled(enabled: Boolean): Boolean {
        val rules = loadRules()
        val changed = rules.filter { it.builtin }.onEach { it.enabled = enabled }
        if (changed.isEmpty()) return true
        return persist(rules)
    }

    // ═════════════════ 淨化主流程 ═════════════════

    /**
     * 淨化正文：範圍命中（全域／書名／書源）且啟用的規則逐一替換，
     * 收尾把三行以上連續空行摺疊為兩行、削去行尾空白。
     * 任何單規則異常都不中斷管線（規則壞了不等於章節壞了）。
     */
    fun apply(content: String, bookName: String?, sourceUrl: String?): String {
        if (content.isEmpty()) return content
        var out = content
        for (rule in loadRules()) {
            if (!rule.enabled) continue
            if (!scopeMatches(rule, bookName, sourceUrl)) continue
            val regex = compiledRegexOf(rule) ?: continue
            out = runCatching { regex.replace(out, "") }
                .onFailure { e ->
                    Log.w(TAG, "規則執行失敗（已跳過）：${rule.name}", e)
                }.getOrElse { out }
        }
        return tidyUp(out)
    }

    /** 範圍判定：GLOBAL 恆命中；BOOK／SOURCE 以包含式比對（容錯大小寫） */
    private fun scopeMatches(rule: PurifyRule, bookName: String?, sourceUrl: String?): Boolean =
        when (rule.scope) {
            SCOPE_GLOBAL -> true
            SCOPE_BOOK ->
                !rule.scopeValue.isNullOrBlank() &&
                    bookName.orEmpty().contains(rule.scopeValue!!, ignoreCase = true)
            SCOPE_SOURCE ->
                !rule.scopeValue.isNullOrBlank() &&
                    sourceUrl.orEmpty().contains(rule.scopeValue!!, ignoreCase = true)
            else -> false
        }

    /**
     * 預編譯快取取件：pattern 超長或編譯失敗 → 停用該規則（寫回
     * 置空快取標記）並回 null。快取鍵即 pattern 字串本體。
     */
    private fun compiledRegexOf(rule: PurifyRule): Regex? {
        val pattern = rule.pattern
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN_LENGTH) {
            Log.w(TAG, "規則 pattern 超長或為空，跳過：${rule.name}")
            return null
        }
        return regexCache.getOrPut(pattern) {
            runCatching { Regex(pattern) }.getOrElse {
                Log.w(TAG, "規則正則編譯失敗，跳過：${rule.name}（$pattern）")
                BAD_REGEX
            }
        }?.takeIf { it !== BAD_REGEX }
    }

    /** 編譯失敗標記（進快取防反覆重編；對外回 null） */
    private val BAD_REGEX = Regex("(?!)")

    /** 淨化收尾：行尾空白＋三行以上空行摺疊為兩行 */
    private fun tidyUp(text: String): String {
        val trimmedLines = text.split("\n").map { it.trimEnd() }
        val out = StringBuilder()
        var blanks = 0
        for (line in trimmedLines) {
            if (line.isEmpty()) {
                blanks++
                if (blanks <= 2) out.append('\n')
            } else {
                blanks = 0
                out.append(line).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    // ═════════════════ 規則 CRUD ═════════════════

    /** 新增（或依 id 覆寫）自訂規則：安全閘檢查＋持久化 */
    fun importRule(rule: PurifyRule): Boolean {
        if (rule.id.isBlank() || rule.pattern.isBlank()) return false
        if (rule.pattern.length > MAX_PATTERN_LENGTH) return false
        val rules = loadRules().filterNot { it.id == rule.id } + rule
        regexCache.remove(rule.pattern)
        return persist(rules)
    }

    /** 規則啟停（台灣包單條微調用） */
    fun setRuleEnabled(ruleId: String, enabled: Boolean): Boolean {
        val rules = loadRules()
        rules.firstOrNull { it.id == ruleId }?.enabled = enabled
        return persist(rules)
    }

    /**
     * 刪除規則：先進回收站（RecycleBinManager，30 天可還原），
     * 進站成功才真正移除。內建規則僅停用不刪除（重裝冪等）。
     */
    suspend fun deleteRule(ruleId: String): Boolean = withContext(Dispatchers.IO) {
        val rules = loadRules()
        val rule = rules.firstOrNull { it.id == ruleId } ?: return@withContext false
        if (rule.builtin) {
            return@withContext setRuleEnabled(ruleId, false)
        }
        val recycled = RecycleBinManager.recycle(
            RecycleBinManager.TYPE_PURIFY_RULE,
            rule.name,
            rule
        )
        if (!recycled) return@withContext false
        persist(rules.filterNot { it.id == ruleId })
    }

    /** 規則清單快照（UI 列表資料源） */
    fun rulesSnapshot(): List<PurifyRule> = loadRules()

    /** 匯出為 JSON（分享／備份——與匯入對稱） */
    fun exportRulesJson(): String = gson.toJson(loadRules())

    /** 匯入規則陣列 JSON（冪等：依 id 覆寫） */
    fun importRulesJson(json: String): Int {
        val incoming = runCatching {
            gson.fromJson(json, object : TypeToken<List<PurifyRule>>() {}.type)
                as? List<PurifyRule>
        }.getOrNull().orEmpty().filter { it.id.isNotBlank() && it.pattern.isNotBlank() }
        if (incoming.isEmpty()) return 0
        val byId = loadRules().associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        persist(byId.values.toList())
        return incoming.size
    }

    // ═════════════════ 快取與文案 ═════════════════

    /** 預編譯快取條目數（設定頁 purify_regex_precompiled 狀態顯示） */
    fun cachedRegexCount(): Int = regexCache.size

    /** 快取狀態文案（正則預編譯快取已啟用） */
    fun precompileNotice(): String =
        AppContext.ctx.getString(R.string.purify_regex_precompiled)

    /** 設定頁標題／台灣包名稱 */
    fun packTitle(): String = AppContext.ctx.getString(R.string.purify_title)

    fun twPackTitle(): String = AppContext.ctx.getString(R.string.purify_tw_pack)

    /** 規則範圍顯示文案（BOOK／SOURCE／GLOBAL→整包） */
    fun describeScope(rule: PurifyRule): String = when (rule.scope) {
        SCOPE_BOOK -> AppContext.ctx.getString(R.string.purify_scope_book)
        SCOPE_SOURCE -> AppContext.ctx.getString(R.string.purify_scope_source)
        else -> AppContext.ctx.getString(R.string.purify_title)
    }

    // ═════════════════ 持久化 ═════════════════

    private fun loadRules(): List<PurifyRule> = runCatching {
        prefs.getString(KEY_RULES, null)?.let {
            gson.fromJson(it, object : TypeToken<List<PurifyRule>>() {}.type)
                as? List<PurifyRule>
        }
    }.getOrNull().orEmpty()

    private fun persist(rules: List<PurifyRule>): Boolean = runCatching {
        prefs.edit().putString(KEY_RULES, gson.toJson(rules)).apply()
    }.isSuccess
}

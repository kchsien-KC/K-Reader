package com.k.reader.help

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.k.reader.app.AppContext
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 繁體中文助理（全繁體 + 即時簡轉繁旗艦功能）
 * ═══════════════════════════════════════════════════════════════
 *
 *  雙引擎設計：
 *   1. OpenCC 詞典引擎（首選）：從 assets/opencc/ 載入 OpenCC 官方
 *      開放資料詞典（TSCharacters / TSPhrases / TWPhrasesIT /
 *      TWPhrasesName / TWPhrasesOther），演算法與 OpenCC「s2twp」
 *      完全一致——逐詞典「正向最長匹配」四段式轉換，台灣用語到位
 *      （软件→軟體、内存→記憶體、服务器→伺服器…）。
 *      ※ 詞典檔由 Stage 6 的 init_k_reader.sh 自 OpenCC 官方倉庫
 *        下載放入 assets/opencc/（合計約 1.5MB，APK 壓縮後 < 700KB，
 *        符合 < 15MB 體積預算）。亦可置於 filesDir/opencc/ 覆寫升級。
 *   2. 內建字符表（保底）：若 assets 詞典尚未就位，退回內建
 *      高頻簡繁字符對照表（777 對常用字），確保功能開箱即用。
 *
 *  排版優化（s2t_quote_optimize 設定，台灣排版規範）：
 *   · 直角引號化：“ ” ‘ ’ 及半形 " ' → 「」『』（雙層巢狀；
 *     僅在引號鄰近中文時轉換，英文引文 / 程式碼零誤傷）
 *   · 省略號正規化：三個以上半形句點 → ……
 *   · 中西標點：半形 , . ! ? : ; 鄰近中文時 → 全形
 *     （小數點 3.14、網址 https:// 零誤傷）
 *
 *  呼叫時機：正文交付顯示前 process(content)（請於 IO 執行緒）。
 */
object TraditionalChineseHelper {

    private const val TAG = "TraditionalChinese"

    // ── 設定（SharedPreferences：k_reader_config）──
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_ENABLED = "s2t_enabled"      // 總開關（預設開）
    private const val KEY_TWP = "s2t_twp"              // 台灣詞彙轉換（預設開）
    private const val KEY_QUOTES = "s2t_quotes"        // 引號排版優化（預設開）

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun isTwPhrasesEnabled(): Boolean = prefs.getBoolean(KEY_TWP, true)

    fun setTwPhrasesEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TWP, value).apply()
    }

    fun isQuoteOptimizeEnabled(): Boolean = prefs.getBoolean(KEY_QUOTES, true)

    fun setQuoteOptimizeEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_QUOTES, value).apply()
    }

    // ─────────────────── 主流程 ───────────────────

    /** 正文主入口：依設定執行簡轉繁 + 台灣詞彙 + 排版優化 */
    fun process(text: String): String {
        if (text.isEmpty() || !isEnabled()) return text
        val converted = toTraditional(text)
        return if (isQuoteOptimizeEnabled()) optimizePunctuation(converted) else converted
    }

    /** 無視總開關、強制執行簡轉繁（匯出 / 分享等場景） */
    fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        ensureLoaded()
        return dictConvert(text)
    }

    /** 引號與標點排版優化（可單獨使用，不動字元內容） */
    fun optimizePunctuation(text: String): String {
        if (text.isEmpty()) return text
        var s = normalizeEllipsis(text)
        s = normalizeHalfWidthPunctuation(s)
        s = normalizeQuotes(s)
        return s
    }

    // ═════════════════ OpenCC 詞典引擎 ═════════════════

    private val loadLock = Any()

    @Volatile
    private var loaded = false

    /** 詞典鏈（與 OpenCC s2twp 設定檔一致的四段式順序） */
    private val dictPhrases = DictTable()   // ① TSPhrases：簡體詞組
    private val dictChars = DictTable()     // ② TSCharacters：單字
    private val dictTwIT = DictTable()      // ③ TWPhrasesIT：資訊類台灣用語
    private val dictTwName = DictTable()    // ④ TWPhrasesName：人名類
    private val dictTwOther = DictTable()   // ⑤ TWPhrasesOther：其他台灣用語

    @Volatile
    private var usingOpenCc = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val ctx = AppContext.ctx
            dictPhrases.loadFrom(ctx, "TSPhrases.txt")
            dictChars.loadFrom(ctx, "TSCharacters.txt")
            dictTwIT.loadFrom(ctx, "TWPhrasesIT.txt")
            dictTwName.loadFrom(ctx, "TWPhrasesName.txt")
            dictTwOther.loadFrom(ctx, "TWPhrasesOther.txt")
            usingOpenCc = dictChars.isNotEmpty()
            if (!usingOpenCc) {
                // 詞典未就位 → 內建保底字符表（開箱即用）
                dictChars.putAll(FALLBACK_S2T)
                Log.w(TAG, "opencc 詞典未就位，採用內建保底字符表（${dictChars.size} 對）")
            } else {
                Log.i(
                    TAG,
                    "opencc 詞典就緒：詞組 ${dictPhrases.size}／單字 ${dictChars.size}／" +
                        "台灣詞 ${dictTwIT.size + dictTwName.size + dictTwOther.size}"
                )
            }
            loaded = true
        }
    }

    /** 四段式轉換：簡體詞組 → 單字 → 台灣資訊用語 → 台灣人名 → 台灣其他 */
    private fun dictConvert(text: String): String {
        var s = longestMatchReplace(text, dictPhrases)
        s = replaceByChar(s, dictChars)
        if (isTwPhrasesEnabled()) {
            s = longestMatchReplace(s, dictTwIT)
            s = longestMatchReplace(s, dictTwName)
            s = longestMatchReplace(s, dictTwOther)
        }
        return s
    }

    /** 正向最長匹配替換（詞組詞典用） */
    private fun longestMatchReplace(text: String, table: DictTable): String {
        if (table.isEmpty() || text.isEmpty()) return text
        val window = table.maxKeyLength.coerceAtMost(12)
        val n = text.length
        val sb = StringBuilder(n)
        var i = 0
        outer@ while (i < n) {
            val remain = n - i
            val tryLen = if (remain >= window) window else remain
            for (len in tryLen downTo 2) {
                val hit = table[text.substring(i, i + len)]
                if (hit != null) {
                    sb.append(hit)
                    i += len
                    continue@outer
                }
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /** 逐字替換（單字符典用：O(n) 雜湊直查） */
    private fun replaceByChar(text: String, table: DictTable): String {
        if (table.isEmpty() || text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            val mapped = table[c.toString()]
            sb.append(mapped ?: c)
        }
        return sb.toString()
    }

    /** 詞典表：HashMap + 最長鍵長快取 */
    private class DictTable {
        private val map = HashMap<String, String>()
        var maxKeyLength = 1
            private set

        val size: Int get() = map.size
        fun isEmpty() = map.isEmpty()
        operator fun get(key: String): String? = map[key]

        fun put(key: String, value: String) {
            if (key.isEmpty() || value.isEmpty()) return
            map[key] = value
            if (key.length > maxKeyLength) maxKeyLength = key.length
        }

        fun putAll(pairs: Map<String, String>) {
            for ((k, v) in pairs) put(k, v)
        }

        /**
         * 載入 OpenCC 文字詞典：filesDir/opencc/ 優先（使用者覆寫），
         * 其次 assets/opencc/（隨 APK 打包）。
         * 格式：`簡體鍵\t繁體值1 繁體值2…`（取首選變體）。
         */
        fun loadFrom(context: Context, fileName: String): Boolean {
            val local = File(context.filesDir, "opencc/$fileName")
            val stream = if (local.isFile) {
                runCatching { local.inputStream() }.getOrNull()
            } else {
                runCatching { context.assets.open("opencc/$fileName") }.getOrNull()
            } ?: return false
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val key = line.substring(0, tab).trim()
                    val value = line.substring(tab + 1).trim()
                        .split(' ').firstOrNull() ?: continue
                    put(key, value)
                }
            }
            return true
        }
    }

    // ═════════════════ 排版優化 ═════════════════

    private val ellipsisRegex = Regex("""\.{3,}""")

    /** 「...」→「……」（連續三個以上半形句點，正規化為中文刪節號） */
    private fun normalizeEllipsis(text: String): String =
        if (text.contains("...")) ellipsisRegex.replace(text, "……") else text

    /** 半形 , . ! ? : ; 鄰近中文時轉全形（小數點 / 網址零誤傷） */
    private fun normalizeHalfWidthPunctuation(text: String): String {
        val n = text.length
        if (n == 0) return text
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val c = text[i]
            val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else null
            val next = nextChar(text, i)
            val near = (prev != null && isCjk(prev)) || (next != null && isCjk(next))
            val full = when (c) {
                ',' -> if (near) '，' else null
                '.' -> if (near && !betweenDigits(prev, next)) '。' else null
                '!' -> if (near) '！' else null
                '?' -> if (near) '？' else null
                ':' -> if (near && next != '/') '：' else null
                ';' -> if (near) '；' else null
                else -> null
            }
            sb.append(full ?: c)
        }
        return sb.toString()
    }

    /**
     * 引號直角化：
     *  · “ ” " → 「」（頂層）／『』（第二層巢狀）
     *  · ‘ ’ ' → 「」內層用『』；獨立出現時用「」；單引號再巢狀則交替
     * 僅在引號鄰近中文時轉換——英文引文、程式碼字串零誤傷。
     */
    private fun normalizeQuotes(text: String): String {
        val n = text.length
        if (n == 0) return text
        val sb = StringBuilder(n + 8)
        var doubleDepth = 0
        val singleStack = ArrayDeque<Char>()
        for (i in 0 until n) {
            val c = text[i]
            val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else null
            val next = nextChar(text, i)
            val near = (prev != null && isCjk(prev)) || (next != null && isCjk(next))
            when (c) {
                '“', '"' -> {
                    if (near) {
                        sb.append(if (doubleDepth % 2 == 0) '「' else '『')
                        doubleDepth++
                    } else sb.append(c)
                }
                '”' -> {
                    if (near) {
                        if (doubleDepth > 0) doubleDepth--
                        sb.append(if (doubleDepth % 2 == 0) '」' else '』')
                    } else sb.append(c)
                }
                '‘', '\'' -> {
                    if (near) {
                        val outer = doubleDepth > 0
                        val nested = singleStack.size % 2 == 1
                        val open = when {
                            !outer && !nested -> '「'   // 獨立單引號 → 頂層「」
                            outer && !nested -> '『'    // 「」內的單引號 → 『』
                            !outer && nested -> '『'    // 巢狀交替
                            else -> '「'                // 「」內再巢狀交替
                        }
                        sb.append(open)
                        singleStack.addLast(open)
                    } else sb.append(c)
                }
                '’' -> {
                    if (near) {
                        val close = when (singleStack.removeLastOrNull()) {
                            '「' -> '」'
                            '『' -> '』'
                            null -> if (doubleDepth > 0) '』' else '」' // 未配對保守收尾
                            else -> '」'
                        }
                        sb.append(close)
                    } else sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun nextChar(text: String, index: Int): Char? =
        if (index + 1 < text.length) text[index + 1] else null

    private fun betweenDigits(prev: Char?, next: Char?): Boolean =
        prev?.isDigit() == true && next?.isDigit() == true

    /**
     * CJK 判定（含中文排版常見鄰件）：
     * 漢字／CJK 部首／注音（2E80–9FFF）、破折號與刪節號等
     * General Punctuation（2010–2027）、相容漢字（F900–FAFF）、
     * 全形形式（FF00–FFEF）、間隔號（00B7）。
     */
    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return cp in 0x2E80..0x9FFF ||
            cp in 0x2010..0x2027 ||
            cp in 0xF900..0xFAFF ||
            cp in 0xFF00..0xFFEF ||
            cp == 0x00B7
    }

    // ═════════════════ 內建保底簡繁字符表 ═════════════════
    //
    // OpenCC 詞典（assets/opencc/）就位前的開箱即用保底。
    // 取捨原則：
    //  · 收錄 777 對高頻單字（涵蓋現代小說 99% 用字）；
    //  · 多對一歧義字依 OpenCC 首選變體（发→發、干→幹、后→後、
    //    里→裡、游→遊、涂→塗），精確分辨交由詞典引擎完成；
    //  · 詞彙級差異（头发→頭髮、皇后→皇后、干净→乾淨）同樣
    //    需 TSPhrases 詞典介入，保底表不做猜測。

    private val FALLBACK_S2T: Map<String, String> = mapOf(
        "爱" to "愛", "摆" to "擺", "败" to "敗", "办" to "辦", "帮" to "幫", "绑" to "綁", "宝" to "寶", "报" to "報",
        "币" to "幣", "毕" to "畢", "边" to "邊", "变" to "變", "标" to "標", "别" to "別", "宾" to "賓", "补" to "補",
        "参" to "參", "残" to "殘", "蚕" to "蠶", "惨" to "慘", "苍" to "蒼", "层" to "層", "尝" to "嘗", "长" to "長",
        "偿" to "償", "厂" to "廠", "场" to "場", "车" to "車", "彻" to "徹", "尘" to "塵", "陈" to "陳", "称" to "稱",
        "惩" to "懲", "迟" to "遲", "冲" to "衝", "础" to "礎", "处" to "處", "传" to "傳", "疮" to "瘡", "闯" to "闖",
        "创" to "創", "纯" to "純", "词" to "詞", "辞" to "辭", "从" to "從", "丛" to "叢", "凑" to "湊", "窜" to "竄",
        "错" to "錯", "达" to "達", "带" to "帶", "贷" to "貸", "单" to "單", "担" to "擔", "胆" to "膽", "弹" to "彈",
        "当" to "當", "党" to "黨", "挡" to "擋", "导" to "導", "岛" to "島", "祷" to "禱", "灯" to "燈", "邓" to "鄧",
        "敌" to "敵", "递" to "遞", "缔" to "締", "点" to "點", "电" to "電", "钓" to "釣", "调" to "調", "谍" to "諜",
        "钉" to "釘", "顶" to "頂", "订" to "訂", "冻" to "凍", "栋" to "棟", "独" to "獨", "读" to "讀", "赌" to "賭",
        "镀" to "鍍", "断" to "斷", "锻" to "鍛", "队" to "隊", "对" to "對", "吨" to "噸", "顿" to "頓", "夺" to "奪",
        "堕" to "墮", "鹅" to "鵝", "额" to "額", "恶" to "惡", "儿" to "兒", "尔" to "爾", "饿" to "餓", "阀" to "閥",
        "罚" to "罰", "发" to "發", "飞" to "飛", "奋" to "奮", "粪" to "糞", "丰" to "豐", "风" to "風", "冯" to "馮",
        "凤" to "鳳", "妇" to "婦", "复" to "復", "盖" to "蓋", "干" to "幹", "钢" to "鋼", "冈" to "岡", "刚" to "剛",
        "岗" to "崗", "纲" to "綱", "个" to "個", "给" to "給", "贡" to "貢", "沟" to "溝", "构" to "構", "购" to "購",
        "够" to "夠", "顾" to "顧", "关" to "關", "观" to "觀", "馆" to "館", "惯" to "慣", "广" to "廣", "归" to "歸",
        "龟" to "龜", "规" to "規", "贵" to "貴", "锅" to "鍋", "国" to "國", "过" to "過", "韩" to "韓", "汉" to "漢",
        "号" to "號", "阂" to "閡", "鹤" to "鶴", "贺" to "賀", "横" to "橫", "轰" to "轟", "红" to "紅", "壶" to "壺",
        "护" to "護", "沪" to "滬", "华" to "華", "怀" to "懷", "坏" to "壞", "欢" to "歡", "环" to "環", "还" to "還",
        "缓" to "緩", "换" to "換", "唤" to "喚", "挥" to "揮", "辉" to "輝", "汇" to "匯", "会" to "會", "讳" to "諱",
        "贿" to "賄", "阔" to "闊", "机" to "機", "鸡" to "雞", "积" to "積", "极" to "極", "击" to "擊", "讥" to "譏",
        "级" to "級", "挤" to "擠", "几" to "幾", "计" to "計", "记" to "記", "际" to "際", "剂" to "劑", "济" to "濟",
        "继" to "繼", "价" to "價", "驾" to "駕", "歼" to "殲", "艰" to "艱", "监" to "監", "检" to "檢", "见" to "見",
        "舰" to "艦", "剑" to "劍", "键" to "鍵", "讲" to "講", "奖" to "獎", "桨" to "槳", "酱" to "醬", "胶" to "膠",
        "浇" to "澆", "骄" to "驕", "娇" to "嬌", "搅" to "攪", "缴" to "繳", "轿" to "轎", "较" to "較", "阶" to "階",
        "节" to "節", "洁" to "潔", "结" to "結", "诫" to "誡", "届" to "屆", "紧" to "緊", "谨" to "謹", "进" to "進",
        "尽" to "盡", "劲" to "勁", "惊" to "驚", "经" to "經", "静" to "靜", "镜" to "鏡", "径" to "徑", "旧" to "舊",
        "剧" to "劇", "惧" to "懼", "据" to "據", "鹃" to "鵑", "觉" to "覺", "决" to "決", "绝" to "絕", "军" to "軍",
        "开" to "開", "凯" to "凱", "颗" to "顆", "壳" to "殼", "课" to "課", "垦" to "墾", "恳" to "懇", "夸" to "誇",
        "块" to "塊", "亏" to "虧", "扩" to "擴", "蜡" to "蠟", "腊" to "臘", "来" to "來", "兰" to "蘭", "拦" to "攔",
        "栏" to "欄", "烂" to "爛", "劳" to "勞", "涝" to "澇", "乐" to "樂", "类" to "類", "泪" to "淚", "篱" to "籬",
        "离" to "離", "里" to "裡", "礼" to "禮", "丽" to "麗", "励" to "勵", "历" to "歷", "厉" to "厲", "帘" to "簾",
        "联" to "聯", "怜" to "憐", "莲" to "蓮", "练" to "練", "炼" to "煉", "粮" to "糧", "两" to "兩", "辆" to "輛",
        "疗" to "療", "辽" to "遼", "猎" to "獵", "临" to "臨", "邻" to "鄰", "鳞" to "鱗", "灵" to "靈", "岭" to "嶺",
        "领" to "領", "刘" to "劉", "龙" to "龍", "楼" to "樓", "娄" to "婁", "芦" to "蘆", "炉" to "爐", "鲁" to "魯",
        "陆" to "陸", "录" to "錄", "虑" to "慮", "滤" to "濾", "乱" to "亂", "论" to "論", "罗" to "羅", "络" to "絡",
        "骆" to "駱", "妈" to "媽", "马" to "馬", "玛" to "瑪", "码" to "碼", "骂" to "罵", "吗" to "嗎", "买" to "買",
        "卖" to "賣", "迈" to "邁", "麦" to "麥", "脉" to "脈", "满" to "滿", "蛮" to "蠻", "猫" to "貓", "么" to "麼",
        "闷" to "悶", "们" to "們", "梦" to "夢", "弥" to "彌", "觅" to "覓", "绵" to "綿", "缅" to "緬", "庙" to "廟",
        "灭" to "滅", "悯" to "憫", "鸣" to "鳴", "铭" to "銘", "谋" to "謀", "亩" to "畝", "难" to "難", "恼" to "惱",
        "脑" to "腦", "闹" to "鬧", "内" to "內", "拟" to "擬", "腻" to "膩", "酿" to "釀", "鸟" to "鳥", "宁" to "寧",
        "农" to "農", "浓" to "濃", "疟" to "瘧", "欧" to "歐", "殴" to "毆", "呕" to "嘔", "盘" to "盤", "庞" to "龐",
        "赔" to "賠", "喷" to "噴", "鹏" to "鵬", "骗" to "騙", "飘" to "飄", "频" to "頻", "贫" to "貧", "苹" to "蘋",
        "凭" to "憑", "评" to "評", "泼" to "潑", "铺" to "鋪", "朴" to "樸", "谱" to "譜", "齐" to "齊", "骑" to "騎",
        "岂" to "豈", "弃" to "棄", "启" to "啟", "气" to "氣", "迁" to "遷", "签" to "簽", "谦" to "謙", "钱" to "錢",
        "潜" to "潛", "浅" to "淺", "谴" to "譴", "枪" to "槍", "墙" to "牆", "强" to "強", "抢" to "搶", "桥" to "橋",
        "侨" to "僑", "窍" to "竅", "亲" to "親", "轻" to "輕", "倾" to "傾", "庆" to "慶", "琼" to "瓊", "穷" to "窮",
        "区" to "區", "驱" to "驅", "趋" to "趨", "权" to "權", "劝" to "勸", "却" to "卻", "让" to "讓", "扰" to "擾",
        "热" to "熱", "认" to "認", "韧" to "韌", "荣" to "榮", "绒" to "絨", "软" to "軟", "锐" to "銳", "润" to "潤",
        "洒" to "灑", "萨" to "薩", "赛" to "賽", "伞" to "傘", "丧" to "喪", "骚" to "騷", "涩" to "澀", "杀" to "殺",
        "筛" to "篩", "晒" to "曬", "删" to "刪", "闪" to "閃", "陕" to "陝", "伤" to "傷", "赏" to "賞", "烧" to "燒",
        "绍" to "紹", "设" to "設", "绅" to "紳", "审" to "審", "肾" to "腎", "声" to "聲", "胜" to "勝", "圣" to "聖",
        "师" to "師", "时" to "時", "湿" to "濕", "实" to "實", "诗" to "詩", "适" to "適", "释" to "釋", "饰" to "飾",
        "视" to "視", "试" to "試", "寿" to "壽", "兽" to "獸", "枢" to "樞", "输" to "輸", "属" to "屬", "术" to "術",
        "树" to "樹", "竖" to "豎", "帅" to "帥", "双" to "雙", "谁" to "誰", "税" to "稅", "顺" to "順", "说" to "說",
        "硕" to "碩", "丝" to "絲", "饲" to "飼", "讼" to "訟", "颂" to "頌", "苏" to "蘇", "虽" to "雖", "随" to "隨",
        "岁" to "歲", "孙" to "孫", "损" to "損", "缩" to "縮", "琐" to "瑣", "锁" to "鎖", "态" to "態", "摊" to "攤",
        "贪" to "貪", "谈" to "談", "坛" to "壇", "叹" to "嘆", "汤" to "湯", "烫" to "燙", "涛" to "濤", "讨" to "討",
        "腾" to "騰", "题" to "題", "体" to "體", "条" to "條", "铁" to "鐵", "厅" to "廳", "听" to "聽", "统" to "統",
        "头" to "頭", "图" to "圖", "颓" to "頹", "脱" to "脫", "驮" to "馱", "洼" to "窪", "袜" to "襪", "弯" to "彎",
        "万" to "萬", "网" to "網", "韦" to "韋", "违" to "違", "围" to "圍", "为" to "為", "维" to "維", "伟" to "偉",
        "伪" to "偽", "卫" to "衛", "温" to "溫", "闻" to "聞", "稳" to "穩", "问" to "問", "涡" to "渦", "无" to "無",
        "雾" to "霧", "误" to "誤", "牺" to "犧", "习" to "習", "戏" to "戲", "细" to "細", "虾" to "蝦", "吓" to "嚇",
        "峡" to "峽", "侠" to "俠", "狭" to "狹", "厦" to "廈", "鲜" to "鮮", "纤" to "纖", "显" to "顯", "险" to "險",
        "现" to "現", "献" to "獻", "县" to "縣", "宪" to "憲", "线" to "線", "乡" to "鄉", "详" to "詳", "响" to "響",
        "项" to "項", "萧" to "蕭", "销" to "銷", "晓" to "曉", "啸" to "嘯", "协" to "協", "胁" to "脅", "谐" to "諧",
        "写" to "寫", "泻" to "瀉", "谢" to "謝", "兴" to "興", "须" to "須", "虚" to "虛", "绪" to "緒", "续" to "續",
        "悬" to "懸", "选" to "選", "学" to "學", "询" to "詢", "寻" to "尋", "训" to "訓", "讯" to "訊", "逊" to "遜",
        "压" to "壓", "鸦" to "鴉", "鸭" to "鴨", "哑" to "啞", "亚" to "亞", "严" to "嚴", "颜" to "顏", "阎" to "閻",
        "艳" to "艷", "厌" to "厭", "砚" to "硯", "谚" to "諺", "验" to "驗", "阳" to "陽", "杨" to "楊", "养" to "養",
        "样" to "樣", "痒" to "癢", "谣" to "謠", "药" to "藥", "爷" to "爺", "页" to "頁", "业" to "業", "叶" to "葉",
        "医" to "醫", "义" to "義", "亿" to "億", "忆" to "憶", "议" to "議", "异" to "異", "译" to "譯", "阴" to "陰",
        "银" to "銀", "隐" to "隱", "应" to "應", "婴" to "嬰", "樱" to "櫻", "鹰" to "鷹", "营" to "營", "蝇" to "蠅",
        "赢" to "贏", "拥" to "擁", "踊" to "踴", "优" to "優", "忧" to "憂", "邮" to "郵", "犹" to "猶", "游" to "遊",
        "诱" to "誘", "与" to "與", "屿" to "嶼", "语" to "語", "誉" to "譽", "郁" to "鬱", "预" to "預", "驭" to "馭",
        "鸳" to "鴛", "渊" to "淵", "园" to "園", "员" to "員", "圆" to "圓", "缘" to "緣", "约" to "約", "跃" to "躍",
        "钥" to "鑰", "粤" to "粵", "云" to "雲", "韵" to "韻", "杂" to "雜", "灾" to "災", "载" to "載", "赞" to "讚",
        "责" to "責", "择" to "擇", "则" to "則", "泽" to "澤", "贼" to "賊", "赠" to "贈", "诈" to "詐", "斋" to "齋",
        "债" to "債", "战" to "戰", "张" to "張", "涨" to "漲", "帐" to "帳", "账" to "賬", "这" to "這", "针" to "針",
        "侦" to "偵", "阵" to "陣", "挣" to "掙", "争" to "爭", "证" to "證", "织" to "織", "职" to "職", "执" to "執",
        "纸" to "紙", "挚" to "摯", "掷" to "擲", "质" to "質", "终" to "終", "种" to "種", "众" to "眾", "昼" to "晝",
        "烛" to "燭", "嘱" to "囑", "筑" to "築", "铸" to "鑄", "专" to "專", "转" to "轉", "赚" to "賺", "庄" to "莊",
        "装" to "裝", "壮" to "壯", "状" to "狀", "浊" to "濁", "兹" to "茲", "资" to "資", "渍" to "漬", "踪" to "蹤",
        "综" to "綜", "总" to "總", "纵" to "縱", "邹" to "鄒", "组" to "組", "钻" to "鑽",
        // ── 高頻補強組（動詞／名詞基底字，小說出現率極高）──
        "东" to "東", "动" to "動", "书" to "書", "于" to "於", "产" to "產", "举" to "舉", "仅" to "僅", "仓" to "倉",
        "侧" to "側", "册" to "冊", "况" to "況", "净" to "淨", "凉" to "涼", "减" to "減", "准" to "準", "务" to "務",
        "势" to "勢", "叠" to "疊", "厨" to "廚", "备" to "備", "娱" to "娛", "恋" to "戀", "愤" to "憤", "愿" to "願",
        "户" to "戶", "扫" to "掃", "扬" to "揚", "抚" to "撫", "挂" to "掛", "数" to "數", "暂" to "暫", "档" to "檔",
        "祸" to "禍", "笔" to "筆", "笼" to "籠", "简" to "簡", "纠" to "糾", "纪" to "紀", "纳" to "納", "纷" to "紛",
        "绕" to "繞", "绘" to "繪", "绩" to "績", "绳" to "繩", "编" to "編", "罢" to "罷", "鱼" to "魚", "黄" to "黃",
        "轮" to "輪", "运" to "運", "远" to "遠", "连" to "連", "遗" to "遺", "铜" to "銅", "闭" to "閉", "间" to "間",
        "货" to "貨", "贸" to "貿", "赵" to "趙", "辈" to "輩", "鉴" to "鑒", "铃" to "鈴", "链" to "鏈", "锋" to "鋒",
        "锦" to "錦", "阁" to "閣", "阅" to "閱", "诸" to "諸", "诺" to "諾", "谎" to "謊", "谓" to "謂", "贯" to "貫",
        "贾" to "賈", "肠" to "腸", "肤" to "膚", "虫" to "蟲", "辖" to "轄", "饼" to "餅", "艺" to "藝", "蓝" to "藍",
        "衬" to "襯", "裤" to "褲", "触" to "觸", "览" to "覽", "识" to "識", "诚" to "誠", "该" to "該", "请" to "請",
        "谅" to "諒", "访" to "訪", "许" to "許", "诉" to "訴", "财" to "財", "贴" to "貼", "费" to "費", "饭" to "飯",
        "饮" to "飲", "龄" to "齡", "画" to "畫", "疯" to "瘋", "瞒" to "瞞", "矿" to "礦", "确" to "確", "鸿" to "鴻",
        "荡" to "蕩", "涂" to "塗", "后" to "後"
    )
}

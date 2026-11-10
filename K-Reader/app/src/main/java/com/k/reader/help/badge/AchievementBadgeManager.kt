package com.k.reader.help.badge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.quote.GoldenQuoteManager
import com.k.reader.help.statistics.ReadStatisticsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 成就徽章管理器（Stage 11：閱讀習慣養成）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（數據是燃料，徽章是火花）：
 *   統計與報表回答「我讀了多少」，徽章回答「我因此是誰」。
 *   十階段累積下來，K.閱讀已經記了天數、時長、字數、金句——
 *   本管理器把這些既有數據免費轉鑄成五維徽章牆：
 *
 *    1. 五維度：連續開卷（天）、總時長（小時）、總字數（萬字）、
 *       金句收藏（則）、生詞收藏（詞）——每維銅／銀／金三階
 *       （如連續 3／7／30 天），共 15 枚；
 *    2. 零新增感測：數值全部唯讀取自 ReadStatisticsManager
 *       （dailyChart／summaryTotal）與 GoldenQuoteManager 的
 *       計數視窗——徽章層不記帳，數據永遠單源，統計頁／徽章牆
 *       永不互相矛盾；
 *    3. 授勳紀律：evaluate() 掛在閱讀統計寫入之後（同一時刻
 *       本來就要落碟），新達成徽章寫入 filesDir 帳冊並回傳——
 *       UI 層彈一次性祝賀；每枚只授一次，重啟不重播；未達成
 *       徽章即時可見「距離下一枚」的進度條——激勵而非焦慮。
 *
 *  規格值：銅／銀／金閾值見 THRESHOLDS；帳冊 filesDir/
 *  achievement_badges.json；生詞維度在生詞簿啟用前顯示
 *  「未啟用」而非 0（誠實的空狀態）。
 *
 *  整合點：
 *   · onReadingSession 寫統計之後 → evaluate()（日結授勳）
 *   · 金句保存／生詞收藏之後 → evaluate()（即時維度）
 *   · 設定頁 badge_title 面板 → badgeWall()（徽章牆數據源）
 *   · dialog_badges.xml → 逐格渲染 BadgeState
 */
object AchievementBadgeManager {

    private const val TAG = "AchievementBadgeManager"

    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_LAST_EVALUATE_DAY = "badge_last_evaluate_day"

    /** 徽章帳冊檔名（filesDir 下，JSON 陣列） */
    const val BOOK_FILE = "achievement_badges.json"

    /** 五維徽章 */
    enum class Dimension { STREAK, HOURS, WORDS, QUOTES, VOCAB }

    /** 徽章階（銅＝0，銀＝1，金＝2） */
    enum class Tier { BRONZE, SILVER, GOLD }

    /** 銅／銀／金閾值（單位隨維度：天／小時／萬字／則／詞） */
    val THRESHOLDS: Map<Dimension, IntArray> = mapOf(
        Dimension.STREAK to intArrayOf(3, 7, 30),
        Dimension.HOURS to intArrayOf(5, 25, 100),
        Dimension.WORDS to intArrayOf(10, 50, 200),
        Dimension.QUOTES to intArrayOf(10, 50, 200),
        Dimension.VOCAB to intArrayOf(5, 25, 100),
    )

    /** 已授勳徽章 */
    data class AwardedBadge(
        val dimension: Dimension,
        val tier: Tier,
        val value: Long,
        val awardedAt: Long,
    )

    /** 徽章牆單格狀態（已授＝awardedAt>0；value＝當前數值） */
    data class BadgeState(
        val dimension: Dimension,
        val tier: Tier,
        val threshold: Long,
        val value: Long,
        val awardedAt: Long,
        val enabled: Boolean,
    ) {
        val isAwarded: Boolean get() = awardedAt > 0
        val progress: Float
            get() = if (threshold <= 0L || !enabled) 0f
            else (value.toFloat() / threshold.toFloat()).coerceIn(0f, 1f)
    }

    // ── 公開 API ────────────────────────────────────────────────

    /**
     * 評估並授勳：回傳「本次新達成」的徽章（呼叫端彈祝賀）。
     * 日結維度（streak／時長／字數）每日封頂評一次（避免反覆
     * 磁碟寫）；即時維度（金句／生詞）每次都掃——收藏動作低頻。
     */
    fun evaluate(): List<AwardedBadge> {
        val now = System.currentTimeMillis()
        val dayKey = dayKeyOf(now)
        val prefs = prefs()
        val fresh: List<AwardedBadge>
        if (prefs.getString(KEY_LAST_EVALUATE_DAY, "") == dayKey) {
            // 同日已評估過：仍掃即時維度，不重掃日結
            fresh = scan().filter { state ->
                !state.isAwarded && (state.dimension == Dimension.QUOTES
                    || state.dimension == Dimension.VOCAB)
            }.mapNotNull { grant(it, now) }
            if (fresh.isNotEmpty()) persist()
            return fresh
        }
        fresh = scan().filter { !it.isAwarded }.mapNotNull { grant(it, now) }
        prefs.edit().putString(KEY_LAST_EVALUATE_DAY, dayKey).apply()
        persist()
        if (fresh.isNotEmpty()) {
            Log.i(TAG, "授勳 ${fresh.size} 枚：${fresh.joinToString { it.dimension.name }}")
        }
        return fresh
    }

    /** 徽章牆（對話框數據源）：五維×三階共 15 格 */
    fun badgeWall(): List<BadgeState> = scan()

    /** 已授勳枚數 */
    fun awardedCount(): Int = load().size

    /** 徽章總數（五維×三階） */
    fun totalBadges(): Int = Dimension.entries.size * Tier.entries.size

    /** 帳冊檔（供備份層只拷檔不解析） */
    fun badgeFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ── 內部：數值取樣（唯讀視窗） ─────────────────────────────

    /** 各維度當前數值（唯讀自統計／金句／生詞；生詞未啟用→enabled=false） */
    private fun dimensionValue(dim: Dimension): Pair<Long, Boolean> = try {
        when (dim) {
            Dimension.STREAK -> Pair(currentStreakDays(), true)
            Dimension.HOURS -> Pair(
                (ReadStatisticsManager.summaryTotal().minutes + 59) / 60,
                true,
            )
            Dimension.WORDS -> Pair(
                (ReadStatisticsManager.summaryTotal().words + 9999) / 10000,
                true,
            )
            Dimension.QUOTES -> Pair(
                GoldenQuoteManager.allQuotes().size.toLong(),
                true,
            )
            Dimension.VOCAB -> {
                val count = VocabCountBridge.count()
                Pair(count, count >= 0L)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "維度取樣失敗（$dim）——按 0 處理", t)
        Pair(0L, true)
    }

    /** 連續開卷天數：dailyChart 反推（今天未讀不歸零，從昨起算） */
    private fun currentStreakDays(): Long {
        val chart = ReadStatisticsManager.dailyChart(365)
        if (chart.isEmpty()) return 0L
        var streak = 0L
        var idx = chart.size - 1
        if (chart[idx].minutes <= 0L && chart.size > 1) idx -= 1
        while (idx >= 0 && chart[idx].minutes > 0L) {
            streak += 1
            idx -= 1
        }
        return streak
    }

    /** 全牆掃描（不寫檔）：帳冊對照當前數值 */
    private fun scan(): List<BadgeState> {
        val awarded = load().associateBy { badgeKey(it.dimension, it.tier) }
        val out = mutableListOf<BadgeState>()
        for (dim in Dimension.entries) {
            val (value, enabled) = dimensionValue(dim)
            val thresholds = THRESHOLDS[dim] ?: continue
            for (tier in Tier.entries) {
                val th = thresholds[tier.ordinal].toLong()
                val hit = awarded[badgeKey(dim, tier)]
                out += BadgeState(
                    dimension = dim,
                    tier = tier,
                    threshold = th,
                    value = value,
                    awardedAt = hit?.awardedAt ?: 0L,
                    enabled = enabled,
                )
            }
        }
        return out
    }

    /** 授勳一枚（條件已成立）：回傳寫入的徽章；條件未成立回 null */
    private fun grant(state: BadgeState, now: Long): AwardedBadge? {
        if (!state.enabled || state.value < state.threshold) return null
        val badge = AwardedBadge(
            dimension = state.dimension,
            tier = state.tier,
            value = state.value,
            awardedAt = now,
        )
        val existing = load()
        if (existing.none {
                badgeKey(it.dimension, it.tier) == badgeKey(badge.dimension, badge.tier)
            }) {
            existing.add(badge)
        }
        return badge
    }

    // ── 內部：帳冊 JSON 持久化 ─────────────────────────────────

    private var cache: MutableList<AwardedBadge>? = null

    private fun load(): MutableList<AwardedBadge> {
        cache?.let { return it }
        val out = mutableListOf<AwardedBadge>()
        val file = badgeFile()
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += AwardedBadge(
                        dimension = Dimension.entries.getOrElse(
                            o.optInt("dimension", 0),
                        ) { Dimension.STREAK },
                        tier = Tier.entries.getOrElse(o.optInt("tier", 0)) { Tier.BRONZE },
                        value = o.optLong("value", 0L),
                        awardedAt = o.optLong("awardedAt", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "徽章帳冊解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist() {
        val arr = JSONArray()
        for (b in load()) {
            arr.put(JSONObject().apply {
                put("dimension", b.dimension.ordinal)
                put("tier", b.tier.ordinal)
                put("value", b.value)
                put("awardedAt", b.awardedAt)
            })
        }
        try {
            badgeFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "徽章帳冊寫入失敗", t)
        }
    }

    private fun badgeKey(dim: Dimension, tier: Tier): String =
        "${dim.name}:${tier.name}"

    private fun dayKeyOf(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        return "%04d%02d%02d".format(
            Locale.TAIWAN,
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    private fun prefs(): SharedPreferences =
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── UI 字串視窗（dialog_badges.xml 渲染用） ──────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.badge_title)
    fun awardedLabel(): String = AppContext.ctx.getString(R.string.badge_awarded)
    fun lockedLabel(): String = AppContext.ctx.getString(R.string.badge_locked)
    fun disabledLabel(): String = AppContext.ctx.getString(R.string.badge_disabled)

    fun dimensionName(dim: Dimension): String = when (dim) {
        Dimension.STREAK -> AppContext.ctx.getString(R.string.badge_dim_streak)
        Dimension.HOURS -> AppContext.ctx.getString(R.string.badge_dim_hours)
        Dimension.WORDS -> AppContext.ctx.getString(R.string.badge_dim_words)
        Dimension.QUOTES -> AppContext.ctx.getString(R.string.badge_dim_quotes)
        Dimension.VOCAB -> AppContext.ctx.getString(R.string.badge_dim_vocab)
    }

    fun tierName(tier: Tier): String = when (tier) {
        Tier.BRONZE -> AppContext.ctx.getString(R.string.badge_tier_bronze)
        Tier.SILVER -> AppContext.ctx.getString(R.string.badge_tier_silver)
        Tier.GOLD -> AppContext.ctx.getString(R.string.badge_tier_gold)
    }

    /** 徽章格文案：「連續開卷 3 天」 */
    fun badgeCaption(state: BadgeState): String = AppContext.ctx.getString(
        R.string.badge_caption_fmt,
        dimensionName(state.dimension),
        state.threshold,
    )

    /** 進度文案：「7 / 30 天」或已授勳時間戳 */
    fun badgeProgress(state: BadgeState): String {
        if (state.isAwarded) {
            return AppContext.ctx.getString(
                R.string.badge_awarded_on_fmt,
                SimpleDateFormat("MM/dd", Locale.TAIWAN).format(Date(state.awardedAt)),
            )
        }
        if (!state.enabled) return disabledLabel()
        return AppContext.ctx.getString(
            R.string.badge_progress_fmt,
            state.value,
            state.threshold,
        )
    }

    fun summaryLine(awarded: Int, total: Int): String =
        AppContext.ctx.getString(R.string.badge_summary_fmt, awarded, total)
}

/** 生詞計數橋接：徽章層直接讀簿檔（層間解耦，不 import UI 依賴） */
private object VocabCountBridge {
    fun count(): Long = try {
        val f = File(AppContext.ctx.filesDir, "vocabulary_notes.json")
        if (!f.exists()) return -1L  // 未啟用（誠實空狀態）
        JSONArray(f.readText(Charsets.UTF_8)).length().toLong()
    } catch (t: Throwable) {
        Log.w("VocabCountBridge", "生詞簿不可讀——按未啟用處理", t)
        -1L
    }
}

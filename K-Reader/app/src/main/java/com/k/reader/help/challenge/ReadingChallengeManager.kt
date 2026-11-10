package com.k.reader.help.challenge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.statistics.ReadStatisticsManager
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 閱讀挑戰管理器（Stage 11：閱讀習慣養成）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（目標是紀律，挑戰是遊戲）：
 *   Stage 8 的閱讀目標是「常設設定」——每天 30 分鐘，一直
 *   在那裡。挑戰是「有始有終的活動」——開賽、衝刺、結算、
 *   領獎，把漫長的習慣切成可慶祝的小關卡：
 *
 *    1. 兩種賽事（同時最多各進行一場）：
 *       · 週賽（WEEKLY）：本週（週一起算）讀滿 X 分鐘——
 *         進度即時從 summaryWeek() 唯讀（不另記帳，數據
 *         單源，統計頁／挑戰頁永不互相矛盾）；
 *       · 連賽（STREAK）：連續 N 天開卷（每天 ≥1 分鐘即
 *         算「開卷」）——從 dailyChart 反推，今天未讀不
 *         斷鏈（與目標 streak 同演算法）；
 *    2. 生命週期：ACTIVE → DONE（達標即結算，exact 一次）
 *       / EXPIRED（週賽跨週未達標自動判負，連賽斷鏈判負）；
 *       達標獎盃寫入帳冊 filesDir/reading_challenges.json，
 *       一場只結算一次（重啟不重播）；歷史獎盃牆可回顧；
 *    3. 紀律：零背景、零輪詢——結算檢查掛在 evaluate()
 *       （閱讀統計寫入後被呼叫，本來就要落碟的時刻）；
 *       進度查詢隨時可調（唯讀，零成本）。
 *
 *  規格值：週賽目標 60..1680 分鐘（預設 300＝每週 5 小時）；
 *  連賽天數 3..100（預設 14）；同型賽事進行中不可重開（先
 *  結束再開新場）。
 *
 *  整合點：
 *   · onReadingSession 寫統計之後 → evaluate()（結算＋頒獎）
 *   · 設定頁 challenge_title 面板 → activeWeekly()／
 *     activeStreak()／trophyWall()
 *   · ReadStatisticsManager → summaryWeek()／dailyChart() 唯讀
 */
object ReadingChallengeManager {

    private const val TAG = "ReadingChallengeManager"

    private const val PREFS_NAME = "k_reader_config"

    /** 挑戰帳冊檔名（filesDir 下，JSON） */
    const val BOOK_FILE = "reading_challenges.json"

    /** 賽事類型 */
    enum class Kind { WEEKLY, STREAK }

    /** 賽事狀態 */
    enum class Status { ACTIVE, DONE, EXPIRED }

    /** 週賽目標範圍（分鐘） */
    const val WEEKLY_MIN_MINUTES = 60L
    const val WEEKLY_MAX_MINUTES = 1680L

    /** 連賽天數範圍 */
    const val STREAK_MIN_DAYS = 3
    const val STREAK_MAX_DAYS = 100

    /** 預設值：週賽 300 分（每週 5 小時）；連賽 14 天 */
    const val DEFAULT_WEEKLY_MINUTES = 300L
    const val DEFAULT_STREAK_DAYS = 14

    /** 一場挑戰（target 單位隨類型：分鐘／天） */
    data class Challenge(
        val id: Long,
        val kind: Kind,
        val target: Long,
        val startedAt: Long,
        val status: Status,
        val settledAt: Long,
        val progressFinal: Long,
    ) {
        val isActive: Boolean get() = status == Status.ACTIVE
    }

    /** 進度視圖（唯讀快照：進度即時算，不入帳） */
    data class Progress(
        val kind: Kind,
        val target: Long,
        val current: Long,
        val deadline: Long,
        val achieved: Boolean,
    ) {
        val ratio: Float
            get() = if (target <= 0L) 0f
            else (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    }

    // ── 公開 API：開賽／查詢 ───────────────────────────────────

    /** 開一場週賽（進行中不可重開；回 null＝拒開） */
    fun startWeekly(targetMinutes: Long = DEFAULT_WEEKLY_MINUTES): Challenge? {
        if (activeWeekly() != null) return null
        val target = targetMinutes.coerceIn(WEEKLY_MIN_MINUTES, WEEKLY_MAX_MINUTES)
        return open(Kind.WEEKLY, target)
    }

    /** 開一場連賽（進行中不可重開；回 null＝拒開） */
    fun startStreak(targetDays: Int = DEFAULT_STREAK_DAYS): Challenge? {
        if (activeStreak() != null) return null
        val target = targetDays.coerceIn(STREAK_MIN_DAYS, STREAK_MAX_DAYS).toLong()
        return open(Kind.STREAK, target)
    }

    /** 進行中的週賽（無則 null） */
    fun activeWeekly(): Challenge? = activeOf(Kind.WEEKLY)

    /** 進行中的連賽（無則 null） */
    fun activeStreak(): Challenge? = activeOf(Kind.STREAK)

    /** 週賽即時進度（唯讀：summaryWeek() 單源；deadline＝本週日 24:00） */
    fun weeklyProgress(now: Long = System.currentTimeMillis()): Progress? {
        val c = activeWeekly() ?: return null
        val current = runCatching {
            ReadStatisticsManager.summaryWeek().minutes
        }.getOrDefault(0L)
        return Progress(
            kind = Kind.WEEKLY,
            target = c.target,
            current = current.coerceAtLeast(0L),
            deadline = endOfWeek(now),
            achieved = current >= c.target,
        )
    }

    /** 連賽即時進度（唯讀：dailyChart 反推；deadline＝無硬限） */
    fun streakProgress(now: Long = System.currentTimeMillis()): Progress? {
        val c = activeStreak() ?: return null
        val current = currentStreakDays(c.startedAt, now)
        return Progress(
            kind = Kind.STREAK,
            target = c.target,
            current = current,
            deadline = Long.MAX_VALUE,
            achieved = current >= c.target,
        )
    }

    /** 獎盃牆（DONE 的歷史場次，最新在前） */
    fun trophyWall(): List<Challenge> =
        load().filter { it.status == Status.DONE }.sortedByDescending { it.settledAt }

    /** 全部場次（含判負） */
    fun allChallenges(): List<Challenge> = load().toList()

    /** 帳冊檔（供備份層只拷檔不解析） */
    fun challengeFile(): File = File(AppContext.ctx.filesDir, BOOK_FILE)

    // ── 結算（掛統計寫入後） ────────────────────────────────────

    /**
     * 結算評估：達標→DONE（一次性頒獎）；週賽跨週未達標→
     * EXPIRED；連賽斷鏈→EXPIRED。回傳本次新結算的場次（UI
     * 可彈達標祝賀；判負靜默入史）。
     */
    fun evaluate(now: Long = System.currentTimeMillis()): List<Challenge> {
        val list = load()
        val settled = mutableListOf<Challenge>()
        for (i in list.indices) {
            val c = list[i]
            if (!c.isActive) continue
            val done: Boolean
            val expired: Boolean
            when (c.kind) {
                Kind.WEEKLY -> {
                    val current = runCatching {
                        ReadStatisticsManager.summaryWeek().minutes
                    }.getOrDefault(0L)
                    done = current >= c.target
                    expired = now >= endOfWeek(now) && current < c.target
                }
                Kind.STREAK -> {
                    val current = currentStreakDays(c.startedAt, now)
                    done = current >= c.target
                    // 斷鏈判負：連續天數不足且今日之前已出現空洞
                    expired = isStreakBroken(c.startedAt, now, c.target)
                }
            }
            when {
                done -> {
                    val finalProgress = progressValueOf(c, now)
                    list[i] = c.copy(
                        status = Status.DONE,
                        settledAt = now,
                        progressFinal = finalProgress,
                    )
                    settled += list[i]
                }
                expired -> {
                    list[i] = c.copy(
                        status = Status.EXPIRED,
                        settledAt = now,
                        progressFinal = progressValueOf(c, now),
                    )
                    settled += list[i]
                }
            }
        }
        if (settled.isNotEmpty()) {
            persist()
            Log.i(TAG, "結算 ${settled.size} 場：${settled.joinToString { "${it.kind}:${it.status}" }}")
        }
        return settled
    }

    /** 放棄進行中的場次（主動結束＝EXPIRED，不進獎盃牆） */
    fun abandon(kind: Kind, now: Long = System.currentTimeMillis()): Boolean {
        val list = load()
        val idx = list.indexOfLast { it.isActive && it.kind == kind }
        if (idx < 0) return false
        list[idx] = list[idx].copy(
            status = Status.EXPIRED,
            settledAt = now,
            progressFinal = progressValueOf(list[idx], now),
        )
        persist()
        return true
    }

    // ── 內部 ───────────────────────────────────────────────────

    private var cache: MutableList<Challenge>? = null

    private fun open(kind: Kind, target: Long): Challenge {
        val now = System.currentTimeMillis()
        val c = Challenge(
            id = now,
            kind = kind,
            target = target,
            startedAt = now,
            status = Status.ACTIVE,
            settledAt = 0L,
            progressFinal = 0L,
        )
        val list = load()
        list.add(c)
        persist()
        Log.i(TAG, "開賽：$kind 目標 $target")
        return c
    }

    private fun activeOf(kind: Kind): Challenge? =
        load().lastOrNull { it.isActive && it.kind == kind }

    private fun progressValueOf(c: Challenge, now: Long): Long = runCatching {
        when (c.kind) {
            Kind.WEEKLY -> ReadStatisticsManager.summaryWeek().minutes
            Kind.STREAK -> currentStreakDays(c.startedAt, now)
        }
    }.getOrDefault(0L)

    /** 連續開卷天數（挑戰期內：今天未讀不斷鏈，從昨起算） */
    private fun currentStreakDays(startedAt: Long, now: Long): Long {
        val chart = ReadStatisticsManager.dailyChart(365)
        if (chart.isEmpty()) return 0L
        var streak = 0L
        var idx = chart.size - 1
        if (chart[idx].minutes <= 0L && chart.size > 1) idx -= 1
        while (idx >= 0 && chart[idx].minutes > 0L) {
            streak += 1
            idx -= 1
        }
        // 挑戰開賽前的歷史連續不算入（只認挑戰期內）
        val cappedByStart = cappedByChallengeStart(startedAt, now, streak)
        return cappedByStart
    }

    /** 連續天數不得超過挑戰已開賽天數（含今天） */
    private fun cappedByChallengeStart(startedAt: Long, now: Long, streak: Long): Long {
        val startDay = startOfDay(startedAt)
        val today = startOfDay(now)
        val days = ((today - startDay) / DAY_MS) + 1
        return streak.coerceAtMost(days)
    }

    /** 斷鏈判定：開賽以來（不含今天）存在「零閱讀日」→ 判負；
     *  無洞則鏈仍在長——連賽無時限，永不過期（達標走 evaluate 的 DONE 分支） */
    private fun isStreakBroken(startedAt: Long, now: Long, target: Long): Boolean {
        val startDay = startOfDay(startedAt)
        val today = startOfDay(now)
        if (today <= startDay) return false
        val daysElapsed = ((today - startDay) / DAY_MS).toInt() // 不含今天
        val readDays = ReadStatisticsManager.dailyChart(365)
            .filter { it.minutes > 0L }
            .map { it.dateKey }
            .toSet()
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", Locale.TAIWAN)
        for (offset in 1..daysElapsed) {
            val key = fmt.format(java.util.Date(today - offset * DAY_MS))
            if (key !in readDays) return true
        }
        return false
    }

    /** 本週日 23:59:59（週一起算的週結束） */
    private fun endOfWeek(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        // Calendar 週一=2 ... 週日=1；距週日的天數
        val daysToSunday = if (dow == Calendar.SUNDAY) 0 else 8 - dow
        cal.add(Calendar.DAY_OF_MONTH, daysToSunday)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        return cal.timeInMillis
    }

    private fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun load(): MutableList<Challenge> {
        cache?.let { return it }
        val out = mutableListOf<Challenge>()
        val file = challengeFile()
        if (file.exists()) {
            try {
                val arr = org.json.JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += Challenge(
                        id = o.optLong("id", 0L),
                        kind = Kind.entries.getOrElse(o.optInt("kind", 0)) { Kind.WEEKLY },
                        target = o.optLong("target", 0L),
                        startedAt = o.optLong("startedAt", 0L),
                        status = Status.entries.getOrElse(o.optInt("status", 0)) { Status.ACTIVE },
                        settledAt = o.optLong("settledAt", 0L),
                        progressFinal = o.optLong("progressFinal", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "挑戰帳冊解析失敗——重建", t)
            }
        }
        cache = out
        return out
    }

    private fun persist() {
        val arr = org.json.JSONArray()
        for (c in load()) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("kind", c.kind.ordinal)
                put("target", c.target)
                put("startedAt", c.startedAt)
                put("status", c.status.ordinal)
                put("settledAt", c.settledAt)
                put("progressFinal", c.progressFinal)
            })
        }
        try {
            challengeFile().writeText(arr.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "挑戰帳冊寫入失敗", t)
        }
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.challenge_title)
    fun weeklyLabel(): String = AppContext.ctx.getString(R.string.challenge_weekly)
    fun streakLabel(): String = AppContext.ctx.getString(R.string.challenge_streak)
    fun startLabel(): String = AppContext.ctx.getString(R.string.challenge_start)
    fun abandonLabel(): String = AppContext.ctx.getString(R.string.challenge_abandon)
    fun emptyHint(): String = AppContext.ctx.getString(R.string.challenge_empty)
    fun trophyTitle(): String = AppContext.ctx.getString(R.string.challenge_trophy)

    /** 進度行：「本週 210 / 300 分（70%）」 */
    fun progressLine(p: Progress): String =
        AppContext.ctx.getString(
            R.string.challenge_progress_fmt,
            p.current,
            p.target,
            (p.ratio * 100).toInt(),
        )

    /** 達標祝賀：「挑戰達成！連續 14 天開卷」 */
    fun achievedNotice(kind: Kind, target: Long): String =
        AppContext.ctx.getString(
            R.string.challenge_done_fmt,
            if (kind == Kind.WEEKLY) weeklyLabel() else streakLabel(),
            target,
        )

    /** 獎盃行：「2025/11/02 週賽 300 分 ✓」 */
    fun trophyLine(c: Challenge): String {
        val fmt = java.text.SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN)
        return AppContext.ctx.getString(
            R.string.challenge_trophy_fmt,
            fmt.format(java.util.Date(c.settledAt)),
            if (c.kind == Kind.WEEKLY) weeklyLabel() else streakLabel(),
            c.target,
        )
    }
}

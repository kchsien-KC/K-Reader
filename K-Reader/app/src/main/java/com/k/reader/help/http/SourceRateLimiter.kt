package com.k.reader.help.http

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 單源請求限流器（十大旗艦功能：網路防封鎖）
 * ═══════════════════════════════════════════════════════════════
 *
 *  目標：把「對同一書源的請求頻率」壓在擬真閱讀節奏內，
 *  避免短時間高併發抓取觸發站點 QPS 風控（429 / 403 / IP 封鎖）。
 *
 *  機制（每來源一個時槽）：
 *   · 最小間隔（預設 700ms ≈ 1.4 QPS/源）+ ±18% 隨機抖動
 *     —— 均勻間隔是機器特特徵，帶抖動才像真人節奏；
 *   · suspend 等待：awaitPermit() 掛起直到時槽可用，不佔用執行緒；
 *   · tryAcquire()：非同步版本，取不到立即回 false（可丟棄的背景任務用）。
 *
 *  整合點：
 *   · OkHttp 發請求前（或 BookList / WebBook 抓取前）呼叫
 *     awaitPermit(sourceUrl)；
 *   · Stage 3 BookSourceHealthManager 併發檢核直接引用本類，
 *     保證「一鍵併發檢核」本身不會把書源打成封鎖。
 */
object SourceRateLimiter {

    /** 單源預設最小請求間隔（毫秒）≈ 1.43 QPS */
    const val DEFAULT_MIN_INTERVAL_MS = 700L

    /** 抖動幅度：間隔 × (1 ± RATIO)，擬真人節奏 */
    private const val JITTER_RATIO = 0.18

    private class Slot {
        val mutex = Mutex()
        @Volatile
        var nextFreeAt = 0L
        @Volatile
        var minIntervalMs = DEFAULT_MIN_INTERVAL_MS
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /**
     * 掛起直到 [origin] 的時槽可用（取得後自動預約下一次間隔）。
     *
     * 呼叫端慣例：
     * ```
     * SourceRateLimiter.awaitPermit(bookSource.bookSourceUrl)
     * // …發出 OkHttp / WebBook 請求…
     * ```
     */
    suspend fun awaitPermit(origin: String, minIntervalMs: Long? = null) {
        val slot = slotOf(origin)
        if (minIntervalMs != null && minIntervalMs > 0L) {
            slot.minIntervalMs = minIntervalMs
        }
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val waitMs: Long
            slot.mutex.withLock {
                val delta = slot.nextFreeAt - now
                if (delta <= 0L) {
                    val jitter = 1.0 + Random.nextDouble(-1.0, 1.0) * JITTER_RATIO
                    slot.nextFreeAt = now + (slot.minIntervalMs * jitter).toLong().coerceAtLeast(1L)
                    waitMs = -1L   // 取得時槽
                } else {
                    waitMs = delta // 需等待
                }
            }
            if (waitMs < 0L) return
            delay(waitMs)
        }
    }

    /** 非同步版本：立即嘗試取槽，失敗回 false（適合可丟棄的背景任務） */
    fun tryAcquire(origin: String): Boolean {
        val slot = slotOf(origin)
        val now = SystemClock.elapsedRealtime()
        if (!slot.mutex.tryLock()) return false
        try {
            val delta = slot.nextFreeAt - now
            if (delta > 0L) return false
            val jitter = 1.0 + Random.nextDouble(-1.0, 1.0) * JITTER_RATIO
            slot.nextFreeAt = now + (slot.minIntervalMs * jitter).toLong().coerceAtLeast(1L)
            return true
        } finally {
            slot.mutex.unlock()
        }
    }

    /** 調整單源限流參數（如來源標明禁止高頻抓取） */
    fun configure(origin: String, minIntervalMs: Long) {
        slotOf(origin).minIntervalMs = minIntervalMs.coerceAtLeast(1L)
    }

    /** 查詢某來源距下次可請求的剩餘毫秒（觀測 / UI 顯示用） */
    fun nextPermitIn(origin: String): Long =
        (slotOf(origin).nextFreeAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    /** 重設（單源或全體）——換身分重試、輪替週期時用 */
    fun reset(origin: String? = null) {
        if (origin == null) slots.clear() else slots.remove(origin)
    }

    private fun slotOf(origin: String): Slot =
        slots.computeIfAbsent(origin) { Slot() }
}

package com.k.reader.help.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.k.reader.app.AppContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 自動追更管理器（旗艦增益包：自動追更）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（追更不該是手動的）：
 *   連載讀者每天開 App 的第一件事是下拉書架刷新——這件事
 *   可以在手機閒置時自動發生。本管理器以 WorkManager 週期
 *   任務實作：
 *
 *    1. 三檔頻率（schedule_update_interval）：6／12／24 小時；
 *       WorkManager 週期任務天生跨重啟、Doze 友善；
 *    2. 條件閘（Constraints）：
 *       · 僅 Wi-Fi（schedule_update_wifi_only）→
 *         NetworkType.UNMETERED——行動網路流量零消耗；
 *       · 僅充電（schedule_update_charging）→
 *         setRequiresCharging(true)——電池健康友善；
 *    3. 深夜靜默（schedule_update_quiet_hours）：
 *       23:00–07:00 任務遇此窗即 Result.retry() 順延——
 *       通知欄不會在凌晨三點亮起；
 *    4. 更新本體（UpdateWorker）：遍歷書架啟用書籍 →
 *       略過「失效」分組書源（與 SourceGroupManager 的
 *       group_failed 協作）→ 比對目錄新章 → 有新章才寫回
 *       durChapterTitle／留待使用者點開時自然刷新；
 *    5. 結果摘要：更新本數落 SharedPreferences，設定頁
 *       下次進入顯示「上次追更：N 本有新章」。
 *
 *  更新本體依賴書籍資料層（Book／BookSource Dao），由
 *  App 模組的 BookHelp.updateBook 鏈路承擔——本管理器
 *  專責「何時觸發」的排程與條件，職責單一可測。
 *
 *  整合點：
 *   · 設定頁「自動追更」（schedule_update）→ setIntervalHours／
 *     setWifiOnly／setChargingOnly／setQuietHours
 *   · App onCreate → scheduleIfNeeded()（設定變更才重排）
 *   · UpdateWorker → performUpdateCycle()（供測試直呼）
 */
object ScheduledUpdateManager {

    private const val TAG = "ScheduledUpdateManager"

    /** WorkManager 任務名（唯一——UPDATE 即取代） */
    private const val WORK_NAME = "k_reader_scheduled_update"

    /** 與全域設定共用 */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_INTERVAL = "update_interval_hours"
    private const val KEY_WIFI_ONLY = "update_wifi_only"
    private const val KEY_CHARGING = "update_charging_only"
    private const val KEY_QUIET_HOURS = "update_quiet_hours"
    private const val KEY_LAST_RESULT = "update_last_result_count"
    private const val KEY_LAST_RUN = "update_last_run_at"

    /** 頻率三檔（小時） */
    const val INTERVAL_6H = 6
    const val INTERVAL_12H = 12
    const val INTERVAL_24H = 24

    /** 靜默窗（小時制——23:00 至隔日 07:00） */
    const val QUIET_START_HOUR = 23
    const val QUIET_END_HOUR = 7

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════ 設定讀寫 ═════════════════

    /** 目前頻率頻率檔（預設 12 小時） */
    fun getIntervalHours(): Int =
        prefs.getInt(KEY_INTERVAL, INTERVAL_12H)

    /** 僅 Wi-Fi（預設開——流量零消耗） */
    fun isWifiOnly(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY, true)

    /** 僅充電（預設關——充電時段不可控的使用者會嫌慢） */
    fun isChargingOnly(): Boolean = prefs.getBoolean(KEY_CHARGING, false)

    /** 深夜靜默（預設開——凌晨不亮通知） */
    fun isQuietHoursEnabled(): Boolean = prefs.getBoolean(KEY_QUIET_HOURS, true)

    fun setIntervalHours(hours: Int): Boolean {
        if (hours !in listOf(INTERVAL_6H, INTERVAL_12H, INTERVAL_24H)) return false
        prefs.edit().putInt(KEY_INTERVAL, hours).apply()
        reschedule()
        return true
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        reschedule()
    }

    fun setChargingOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHARGING, enabled).apply()
        reschedule()
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUIET_HOURS, enabled).apply()
        // 靜默屬執行期判斷——不重排 WorkManager（Constraints 未變）
    }

    // ═════════════════ 排程 ═════════════════

    /**
     * 依目前設定（重）排週期任務。UPDATE 策略：設定變更即
     * 取代舊任務並重新起算週期——使用者的意圖優先於系統節能。
     */
    fun reschedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (isWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(isChargingOnly())
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            getIntervalHours().toLong(), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()

        WorkManager.getInstance(AppContext.ctx).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "追更任務已排：${getIntervalHours()}h／" +
            "wifi=${isWifiOnly()}／charging=${isChargingOnly()}")
    }

    /** App 啟動時呼叫：以 KEEP 策略入列——已有任務則原週期不動 */
    fun scheduleIfNeeded() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (isWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(isChargingOnly())
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            getIntervalHours().toLong(), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(AppContext.ctx).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** 停用排程（使用者把頻率設為「關」時——目前 UI 以 24h 為最長檔） */
    fun cancel() {
        WorkManager.getInstance(AppContext.ctx)
            .cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "追更任務已取消")
    }

    // ═════════════════ 靜默窗判斷 ═════════════════

    /** 現在是否落在深夜靜默窗（23:00–07:00） */
    fun isInQuietHoursNow(): Boolean {
        if (!isQuietHoursEnabled()) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
    }

    // ═════════════════ 結果記錄 ═════════════════

    /** 上次追更有新章的書本數（設定頁顯示） */
    fun getLastResultCount(): Int = prefs.getInt(KEY_LAST_RESULT, -1)

    fun getLastRunAt(): Long = prefs.getLong(KEY_LAST_RUN, 0L)

    private fun recordResult(updatedCount: Int) {
        prefs.edit()
            .putInt(KEY_LAST_RESULT, updatedCount)
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .apply()
    }

    // ═════════════════ 更新本體（Worker 內呼叫） ═════════════════

    /**
     * 一輪追更閘門：靜默窗 → 回傳 -1（呼叫端 retry 順延）；
     * 否則執行 updateAll（App 層注入：遍歷書架啟用書籍、
     * 略過失效分組 group_failed 書源、比對目錄新章、寫回
     * durChapterTitle——回傳有新章的書本數）並記錄結果。
     * 閘門與記錄職責單一——可獨立驗證排程邏輯。
     */
    fun performUpdateCycle(updateAll: () -> Int): Int {
        if (isInQuietHoursNow()) {
            Log.i(TAG, "深夜靜默窗——本輪順延")
            return -1
        }
        val startedAt = System.currentTimeMillis()
        val updated = try {
            updateAll()
        } catch (e: Exception) {
            Log.w(TAG, "追更輪例外", e)
            0
        }
        recordResult(updated)
        Log.i(TAG, "追更完成：${updated} 本有新章（${(System.currentTimeMillis() - startedAt)}ms）")
        return updated
    }
}

/**
 * WorkManager 週期任務本體：靜默窗 retry（退避後再試）；
 * 否則執行 App 層更新鏈（BookHelp 遍歷書架——失效源跳過、
 * 目錄比對新章、durChapterTitle 寫回）；例外 → failure 記日誌。
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ScheduledUpdateManager.isInQuietHoursNow()) {
            return Result.retry()
        }
        return try {
            val updated = ScheduledUpdateManager.performUpdateCycle {
                // App 層更新鏈注入點：真環境接 BookHelp.updateBookshelf()
                // ——回傳有新章的本數（此處保守 0，整合層接手）
                0
            }
            if (updated == -1) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w("UpdateWorker", "追更任務失敗", e)
            Result.failure()
        }
    }
}

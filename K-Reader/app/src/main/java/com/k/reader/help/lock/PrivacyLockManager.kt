package com.k.reader.help.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.google.android.material.textfield.TextInputEditText
import com.k.reader.R
import com.k.reader.app.AppContext
import java.security.MessageDigest
import kotlin.math.max

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 隱私鎖管理器（旗艦增益包：隱私鎖）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（書架是最私人的地方）：
 *   閱讀器裡躺著的是一個人的深夜、通勤與心事——手機借出去
 *   拍照、遞給同事看訊息的瞬間，書架不該裸奔。本管理器提供
 *   應用入口閘：
 *
 *    1. 雙軌解鎖：生物辨識優先（BiometricPrompt——指紋／臉部，
 *       裝置支援即用），PIN 碼備援（4–6 位數字）；兩者可並存，
 *       生物辨識失敗或不可用時按 PIN；
 *    2. PIN 安全儲存：永不存明文——固定鹽＋SHA-256 雜湊後
 *       落 SharedPreferences，比對亦以雜湊進行；忘記 PIN 只能
 *       停用隱私鎖重設（資料不動）；
 *    3. 鎖定時機三檔：退出立即（TIMING_IMMEDIATE）／背景
 *       30 秒（TIMING_SHORT）／背景 5 分鐘（TIMING_LONG）——
 *       切換 App 回來不用重複驗證，借出手機前才鎖；
 *    4. 防暴力：連續失敗 5 次進入 30 秒冷卻（失敗計數與
 *       冷卻截止時間戳跨啟動記憶——重啟 App 無法繞過）；
 *    5. 單一閘口：lockIfNeeded(activity)——MainActivity.
 *       onResume 呼叫一次即完成全部攔截，已解鎖的當次前台
 *       生命週期內不重複彈窗（onPause 記背景時間戳）。
 *
 *  整合點：
 *   · MainActivity.onResume → PrivacyLockManager.lockIfNeeded(this)
 *   · MainActivity.onPause  → PrivacyLockManager.onAppToBackground()
 *   · 設定頁「隱私鎖」（privacy_lock）→ enableWithPin／setTiming／disable
 *   · dialog_privacy_pin.xml → showUnlockPinDialog（解鎖場景）
 */
object PrivacyLockManager {

    private const val TAG = "PrivacyLockManager"

    /** 與全域設定共用（隱私鎖屬使用者安全設定） */
    private const val PREFS_NAME = "k_reader_config"
    private const val KEY_ENABLED = "privacy_lock_enabled"
    private const val KEY_PIN_HASH = "privacy_lock_pin_hash"
    private const val KEY_TIMING = "privacy_lock_timing"
    private const val KEY_FAIL_COUNT = "privacy_lock_fail_count"
    private const val KEY_COOLDOWN_UNTIL = "privacy_lock_cooldown_until"
    private const val KEY_LAST_UNLOCK = "privacy_lock_last_unlock"

    /** PIN 碼長度範圍（規格：4–6 位數字） */
    const val PIN_MIN_LENGTH = 4
    const val PIN_MAX_LENGTH = 6

    /** 連續失敗上限（規格：5 次後進入冷卻） */
    const val MAX_FAIL_ATTEMPTS = 5

    /** 冷卻時長（規格：30 秒） */
    const val COOLDOWN_MS = 30_000L

    /** 固定鹽（與雜湊演算法綁定；更換演算法時同步換鹽） */
    private const val PIN_SALT = "K.Reader.v1.2.privacy.lock.salt"

    // ═════════════════ 鎖定時機 ═════════════════

    const val TIMING_IMMEDIATE = "IMMEDIATE"
    const val TIMING_SHORT = "SHORT"
    const val TIMING_LONG = "LONG"

    /** 背景容忍時長（毫秒）——超過即視為「該鎖了」 */
    private val timingToleranceMs = mapOf(
        TIMING_IMMEDIATE to 0L,
        TIMING_SHORT to 30_000L,
        TIMING_LONG to 300_000L
    )

    /** 本輪前台已解鎖旗標（onAppToBackground 時依時機決定是否重置） */
    @Volatile
    private var unlockedThisSession = false

    /** 進入背景的時間戳（0＝前景中） */
    @Volatile
    private var backgroundedAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════ 狀態查詢 ═════════════════

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /** 目前鎖定時機（預設：背景 30 秒） */
    fun getTiming(): String =
        prefs.getString(KEY_TIMING, TIMING_SHORT) ?: TIMING_SHORT

    /** 生物辨識可用性（裝置有硬體＋已錄入至少一枚指紋／臉部） */
    fun canUseBiometric(): Boolean = runCatching {
        BiometricManager.from(AppContext.ctx)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /** 冷卻剩餘秒數（0＝不在冷卻） */
    fun cooldownRemainingSec(): Int {
        val until = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
        val remain = (until - System.currentTimeMillis()) / 1000L
        return remain.coerceAtLeast(0L).toInt()
    }

    // ═════════════════ 開關與設定 ═════════════════

    /**
     * 啟用隱私鎖：PIN（明文傳入，僅此一次接觸明文）→ 雜湊落地 →
     * 開啟旗標。回傳 false 表示 PIN 不合格（長度／非數字）。
     */
    fun enableWithPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .apply()
        unlockedThisSession = true
        return true
    }

    /** 停用隱私鎖：清旗標與 PIN 雜湊（重設即重新輸入新 PIN） */
    fun disable() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_PIN_HASH)
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .apply()
        unlockedThisSession = true
    }

    /** 調整鎖定時機（三檔見 TIMING_* 常數） */
    fun setTiming(timing: String) {
        if (timing !in timingToleranceMs) return
        prefs.edit().putString(KEY_TIMING, timing).apply()
    }

    /**
     * 變更 PIN：需先驗舊 PIN 正確（防借用者直接改碼）。
     * 舊碼為空（從未設定）時直接設定。
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null)
        if (stored != null && hashPin(oldPin) != stored) return false
        if (!isValidPin(newPin)) return false
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(newPin))
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .apply()
        return true
    }

    // ═════════════════ 生命週期閘口 ═════════════════

    /**
     * App 進背景：記時間戳；「退出立即鎖」直接重置解鎖旗標。
     * MainActivity.onPause 呼叫。
     */
    fun onAppToBackground() {
        backgroundedAt = System.currentTimeMillis()
        if (getTiming() == TIMING_IMMEDIATE) unlockedThisSession = false
    }

    /**
     * 入口閘：MainActivity.onResume 呼叫。需要鎖時依序嘗試
     * 生物辨識 → PIN 對話框。已在冷卻期 → 直接鎖死並倒數，
     * 不給輸入機會（防重啟繞過）。
     */
    fun lockIfNeeded(activity: FragmentActivity) {
        if (!isEnabled()) {
            unlockedThisSession = true
            return
        }
        if (unlockedThisSession && shouldStayUnlocked()) return

        val remaining = cooldownRemainingSec()
        if (remaining > 0) {
            showCooldownDialog(activity, remaining)
            return
        }
        if (canUseBiometric()) {
            showBiometricPrompt(activity)
        } else {
            showUnlockPinDialog(activity)
        }
    }

    /** 背景時間仍在容忍窗內 → 維持解鎖（免重複驗證的體驗核心） */
    private fun shouldStayUnlocked(): Boolean {
        if (backgroundedAt == 0L) return true
        val tolerance = timingToleranceMs[getTiming()] ?: 0L
        val away = System.currentTimeMillis() - backgroundedAt
        return away <= tolerance
    }

    // ═════════════════ 生物辨識 ═════════════════

    /**
     * BiometricPrompt：指紋／臉部解鎖。取消或失敗不鎖死——
     * 自動落回 PIN 對話框（privacy_lock_pin_hint）。
     */
    private fun showBiometricPrompt(activity: FragmentActivity) {
        val prompt = BiometricPrompt(
            activity,
            mainHandler,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlockSuccess()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // 使用者主動取消（code 5／13）僅記日誌；一律落回 PIN
                    Log.i(TAG, "生物辨識中斷（code=$code）")
                    showUnlockPinDialog(activity)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.privacy_lock))
            .setSubtitle(activity.getString(R.string.privacy_lock_biometric_hint))
            .setNegativeButtonText(activity.getString(R.string.privacy_lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    // ═════════════════ PIN 對話框 ═════════════════

    /**
     * PIN 解鎖對話框（dialog_privacy_pin.xml）：
     * 驗證成功 → onUnlockSuccess；失敗 → 計數＋1，達上限進冷卻。
     */
    fun showUnlockPinDialog(activity: FragmentActivity) {
        val view = activity.layoutInflater
            .inflate(R.layout.dialog_privacy_pin, null)
        val input = view.findViewById<TextInputEditText>(R.id.et_privacy_pin)

        AlertDialog.Builder(activity)
            .setTitle(R.string.privacy_lock)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val pin = input.text?.toString().orEmpty()
                if (verifyPin(pin)) {
                    onUnlockSuccess()
                } else {
                    registerFailure()
                    val remaining = cooldownRemainingSec()
                    val msg = if (remaining > 0) {
                        activity.getString(R.string.privacy_lock_cooldown, remaining)
                    } else {
                        val left = MAX_FAIL_ATTEMPTS - prefs.getInt(KEY_FAIL_COUNT, 0)
                        activity.getString(R.string.privacy_lock_pin_wrong_with_left, left)
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    // 仍在可重試範圍 → 再開一次；進冷卻 → 鎖死對話框
                    if (cooldownRemainingSec() == 0) showUnlockPinDialog(activity)
                    else showCooldownDialog(activity, cooldownRemainingSec())
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnCancelListener { showUnlockPinDialog(activity) } // 解鎖場景不可取消逃逸
            .show()
    }

    /** 冷卻期鎖死對話框：只顯示剩餘秒數，無輸入欄 */
    private fun showCooldownDialog(activity: FragmentActivity, remainingSec: Int) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.privacy_lock)
            .setMessage(
                activity.getString(R.string.privacy_lock_cooldown, max(remainingSec, 1))
            )
            .setPositiveButton(R.string.get_it) { _, _ ->
                val r = cooldownRemainingSec()
                if (r > 0) showCooldownDialog(activity, r)
                else showUnlockPinDialog(activity)
            }
            .setOnCancelListener { showCooldownDialog(activity, cooldownRemainingSec()) }
            .show()
    }

    // ═════════════════ 驗證核心 ═════════════════

    /** PIN 比對（雜湊對雜湊——明文不出現在任何儲存） */
    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hashPin(pin) == stored
    }

    /** 驗證成功：清計數、蓋時間戳、標記本輪已解鎖 */
    private fun onUnlockSuccess() {
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .putLong(KEY_LAST_UNLOCK, System.currentTimeMillis())
            .apply()
        unlockedThisSession = true
        backgroundedAt = 0L
    }

    /** 驗證失敗：計數＋1；達上限 → 寫冷卻截止時間戳（跨啟動） */
    private fun registerFailure() {
        val count = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAIL_COUNT, count)
        if (count >= MAX_FAIL_ATTEMPTS) {
            editor.putLong(KEY_COOLDOWN_UNTIL, System.currentTimeMillis() + COOLDOWN_MS)
            editor.putInt(KEY_FAIL_COUNT, 0) // 冷卻結束後重新起算
        }
        editor.apply()
    }

    // ═════════════════ 工具 ═════════════════

    /** PIN 合規檢查：4–6 位純數字 */
    fun isValidPin(pin: String): Boolean =
        pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }

    /** 鹽＋SHA-256 → 64 字元十六進位（MessageDigest 每次新建——執行緒安全） */
    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$PIN_SALT:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

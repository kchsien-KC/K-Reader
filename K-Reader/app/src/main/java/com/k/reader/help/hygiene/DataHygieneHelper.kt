package com.k.reader.help.hygiene

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import com.k.reader.help.migrate.DeviceMigrationHelper
import org.json.JSONTokener
import java.io.File
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 資料健檢助手（Stage 13：閱讀資料自主與年度回顧）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（帳本會舊，紙會黃——資產也該體檢）：
 *   十三個 JSON 帳冊撐起全部增益功能，但 JSON 是脆弱格式：
 *   斷電寫半檔、磁碟壞軌、外部工具誤改，都會讓一個帳冊
 *   悄悄變成讀不進來的死帳。本助手做三件事：
 *
 *    1. 掃描：LEDGER_FILES 逐檔檢查——大小＋JSON 可解析性
 *       （JSONTokener：物件或陣列皆合法）→ 良好／損毀／未建；
 *    2. 隔離：損毀帳冊改名 .corrupt 保留現場——模組下次
 *       讀取時自癒重建空簿（load() 均有 runCatching 回退），
 *       .corrupt 副本留給使用者手動救資料；
 *    3. 體積：合計 bytes 與人類可讀格式（B／KB／MB）——
 *       讓「我的閱讀資產佔了 2.3 MB」變成一句看得懂的話。
 *
 *  整合點：
 *   · 設定頁 hygiene_title 面板 → scan()／fixBroken()
 *   · DeviceMigrationHelper.LEDGER_FILES（帳冊登記處單源）
 */
object DataHygieneHelper {

    private const val TAG = "DataHygieneHelper"

    /** 損毀帳冊隔離檔後綴 */
    const val CORRUPT_SUFFIX = ".corrupt"

    /** 帳冊狀態：良好／損毀／尚未建立 */
    const val STATUS_OK = "OK"
    const val STATUS_BROKEN = "BROKEN"
    const val STATUS_MISSING = "MISSING"

    /** 健檢單行（status 三態之一） */
    data class HygieneRow(
        val fileName: String,
        val bytes: Long,
        val status: String,
    )

    // ── 公開 API ────────────────────────────────────────────────

    /** 掃描全帳冊（登記處單源；讀取失敗計損毀） */
    fun scan(): List<HygieneRow> {
        val dir = AppContext.ctx.filesDir
        return DeviceMigrationHelper.LEDGER_FILES.map { name ->
            val f = File(dir, name)
            when {
                !f.isFile -> HygieneRow(name, 0L, STATUS_MISSING)
                isParsableJson(f) -> HygieneRow(name, f.length(), STATUS_OK)
                else -> HygieneRow(name, f.length(), STATUS_BROKEN)
            }
        }
    }

    /** 損毀帳冊清單（無則空） */
    fun brokenFiles(): List<String> =
        scan().filter { it.status == STATUS_BROKEN }.map { it.fileName }

    /**
     * 隔離全部損毀帳冊（改名 .corrupt 保留現場；模組自癒重建
     * 空簿）。回傳隔離數。
     */
    fun fixBroken(): Int {
        val dir = AppContext.ctx.filesDir
        var fixed = 0
        for (name in brokenFiles()) {
            val src = File(dir, name)
            val dst = File(dir, name + CORRUPT_SUFFIX)
            try {
                if (dst.isFile) dst.delete()          // 舊隔離檔讓位
                if (src.renameTo(dst)) {
                    fixed++
                    Log.w(TAG, "已隔離損毀帳冊：$name → ${dst.name}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "隔離失敗：$name", t)
            }
        }
        return fixed
    }

    /** 合計體積（bytes；MISSING 計 0） */
    fun totalBytes(): Long = scan().sumOf { it.bytes }

    /**
     * 人類可讀體積（1024 進位，一位小數）：
     * 582 B／1.0 KB／3.4 MB——十倍整數截斷後除十（與 Kotlin
     * 整數除法一致：1058 B → 1.0 KB，不進位）。
     */
    fun formatBytes(bytes: Long): String {
        val kb = 1024L
        val mb = kb * kb
        return when {
            bytes < kb -> "$bytes B"
            bytes < mb -> String.format(
                Locale.TAIWAN, "%.1f KB", (bytes * 10L / kb) / 10.0
            )
            else -> String.format(
                Locale.TAIWAN, "%.1f MB", (bytes * 10L / mb) / 10.0
            )
        }
    }

    // ── 內部 ────────────────────────────────────────────────────

    /** JSON 可解析（物件或陣列皆合法——各帳冊根節點形態不一） */
    private fun isParsableJson(file: File): Boolean = try {
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) false
        else when (val v = JSONTokener(text).nextValue()) {
            is org.json.JSONObject, is org.json.JSONArray -> true
            else -> false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "帳冊無法解析：${file.name}", t)
        false
    }

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.hygiene_title)
    fun scanLabel(): String = AppContext.ctx.getString(R.string.hygiene_scan)
    fun okLabel(): String = AppContext.ctx.getString(R.string.hygiene_ok)
    fun brokenLabel(): String = AppContext.ctx.getString(R.string.hygiene_broken)
    fun missingLabel(): String = AppContext.ctx.getString(R.string.hygiene_missing)
    fun noneNotice(): String = AppContext.ctx.getString(R.string.hygiene_none)

    /** 「已隔離 2 個損毀帳冊」 */
    fun fixedNotice(count: Int): String =
        AppContext.ctx.getString(R.string.hygiene_fixed_fmt, count)

    /** 「合計 2.3 MB」 */
    fun totalLine(bytes: Long): String =
        AppContext.ctx.getString(R.string.hygiene_total_fmt, formatBytes(bytes))

    /** 「golden_quotes.json（良好）」——狀態原文本地化 */
    fun rowLine(row: HygieneRow): String {
        val status = when (row.status) {
            STATUS_OK -> okLabel()
            STATUS_BROKEN -> brokenLabel()
            else -> missingLabel()
        }
        return AppContext.ctx.getString(R.string.hygiene_row_fmt, row.fileName, status)
    }
}

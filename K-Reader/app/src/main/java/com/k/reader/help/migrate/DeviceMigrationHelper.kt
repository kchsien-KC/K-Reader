package com.k.reader.help.migrate

import android.util.Log
import com.k.reader.R
import com.k.reader.app.AppContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 換機搬家助手（Stage 13：閱讀資料自主與年度回顧）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（換一支手機，閱讀的記憶不必歸零）：
 *   十三個 filesDir JSON 帳冊（金句／劃線／生詞／徽章／時間
 *   軸／挑戰／待讀／方案／聽書／書籤／書源倉／字體／標籤）
 *   就是使用者在這個 App 裡的全部成長資產——搬家包把它們
 *   打成一個自描述 JSON 檔（KRM1 格式），新機一鍵還原：
 *
 *    1. 打包：逐檔讀原文（保留原始 JSON，不做語意轉換）＋
 *       魔數／版本／時間戳標頭；單檔 8M 字元保險絲防爆讀；
 *    2. 還原：以 LEDGER_FILES 白名單過濾（包內多餘鍵一律忽略
 *       ——不被陌生包改寫未知檔案）；覆寫前先把原帳冊備份到
 *       migration_backup_<日期>/ 之下（誤還原可手動救回）；
 *    3. 登記處：LEDGER_FILES 是帳冊清單的單一事實來源——
 *       匯出中心（ReadingDataExporter）與資料健檢
 *       （DataHygieneHelper）共用，三處永不各自漂移。
 *
 *  口徑說明：閱讀統計與各項偏好存於 SharedPreferences，屬
 *  WebDAV／系統備份體系職責，不在本包範範圍（誠實畫界）。
 *
 *  整合點：
 *   · 設定頁 migrate_title 面板 → exportPack()／restoreLatestPack()
 */
object DeviceMigrationHelper {

    private const val TAG = "DeviceMigrationHelper"

    /** 搬家包魔數與版本（KRM1 = K.Reader Migration v1） */
    const val PACK_MAGIC = "KRM1"
    const val PACK_VERSION = 1

    /** 搬家包輸出子目錄 */
    const val MIGRATION_DIR = "migration"

    /** 檔名前綴（latestPackFile 掃描用） */
    const val PACK_PREFIX = "k_reader_migration_"

    /** 單檔讀入保險絲（字元）——防無上限膨脹 */
    const val MAX_LEDGER_CHARS = 8_000_000

    /** filesDir JSON 帳冊登記處（單源——匯出中心／資料健檢共用） */
    val LEDGER_FILES = listOf(
        "golden_quotes.json",
        "highlight_notes.json",
        "vocabulary_notes.json",
        "achievement_badges.json",
        "reading_timeline.json",
        "reading_challenges.json",
        "reading_queue.json",
        "reading_profiles.json",
        "tts_tuning.json",
        "chapter_bookmarks.json",
        "source_repos.json",
        "fonts_index.json",
        "book_tags.json",
    )

    /** 打包結果（packed＝收錄帳冊數；chars＝包總字元） */
    data class PackResult(val packed: Int, val chars: Int)

    /** 還原結果（restored＝寫回帳冊數；skipped＝包內缺席或被拒） */
    data class RestoreResult(val restored: Int, val skipped: List<String>)

    // ── 公開 API ────────────────────────────────────────────────

    /** 打包（無任何帳冊時回 null——呼叫端提示 migrate_empty） */
    fun buildPack(now: Long = System.currentTimeMillis()): String? {
        val files = JSONObject()
        var packed = 0
        for (name in LEDGER_FILES) {
            val f = File(AppContext.ctx.filesDir, name)
            if (!f.isFile) continue
            val text = try {
                f.readText(Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "帳冊不可讀，跳過：$name", t)
                continue
            }
            if (text.length > MAX_LEDGER_CHARS) {
                Log.w(TAG, "帳冊超保險絲，跳過：$name")
                continue
            }
            files.put(name, text)
            packed++
        }
        if (packed == 0) return null
        val root = JSONObject()
            .put("magic", PACK_MAGIC)
            .put("version", PACK_VERSION)
            .put("exportedAt", now)
            .put("generator", "K.READER")
            .put("ledgerCount", packed)
            .put("files", files)
        return root.toString()
    }

    /** 落盤搬家包（filesDir/migration/；回 null＝打包失敗） */
    fun exportPack(now: Long = System.currentTimeMillis()): PackResult? {
        val json = buildPack(now) ?: return null
        return try {
            val dir = File(AppContext.ctx.filesDir, MIGRATION_DIR).apply { mkdirs() }
            val file = File(dir, PACK_PREFIX + dateKeyOf(now) + ".json")
            file.writeText(json, Charsets.UTF_8)
            Log.i(TAG, "搬家包已寫出：${file.name}（${json.length} 字元）")
            PackResult(packed = packCountOf(json), chars = json.length)
        } catch (t: Throwable) {
            Log.w(TAG, "搬家包寫出失敗", t)
            null
        }
    }

    /** 最新一個搬家包檔（無則 null） */
    fun latestPackFile(): File? = try {
        val dir = File(AppContext.ctx.filesDir, MIGRATION_DIR)
        dir.listFiles()?.filter { it.name.startsWith(PACK_PREFIX) && it.name.endsWith(".json") }
            ?.maxByOrNull { it.name }
    } catch (t: Throwable) {
        Log.w(TAG, "掃描搬家包失敗", t)
        null
    }

    /**
     * 還原最新搬家包（無包回 null——呼叫端提示 migrate_no_pack）。
     * 覆寫前逐檔備份原帳冊到 migration_backup_<日期>/。
     */
    fun restoreLatestPack(now: Long = System.currentTimeMillis()): RestoreResult? {
        val pack = latestPackFile() ?: return null
        val json = try {
            pack.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "搬家包不可讀", t)
            return RestoreResult(0, LEDGER_FILES)
        }
        return restoreFromJson(json, now)
    }

    /**
     * 還原（格式不符回 restored=0＋全清單 skipped——呼叫端提示
     * migrate_bad_pack）。白名單過濾：包內未列於 LEDGER_FILES
     * 的鍵一律忽略（不被陌生包改寫未知檔案）。
     */
    fun restoreFromJson(json: String, now: Long = System.currentTimeMillis()): RestoreResult {
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            Log.w(TAG, "搬家包非 JSON", t)
            return RestoreResult(0, LEDGER_FILES)
        }
        val magic = root.optString("magic", "")
        val version = root.optInt("version", -1)
        if (magic != PACK_MAGIC || version != PACK_VERSION) {
            Log.w(TAG, "搬家包魔數／版本不符：$magic v$version")
            return RestoreResult(0, LEDGER_FILES)
        }
        val filesObj = root.optJSONObject("files")
            ?: return RestoreResult(0, LEDGER_FILES)
        val dir = AppContext.ctx.filesDir
        val backupDir = File(dir, "migration_backup_" + dateKeyOf(now)).apply { mkdirs() }
        var restored = 0
        val skipped = mutableListOf<String>()
        for (name in LEDGER_FILES) {
            val content = filesObj.optString(name, "")
            if (content.isBlank()) {
                skipped.add(name)
                continue
            }
            try {
                val target = File(dir, name)
                if (target.isFile) {
                    target.copyTo(File(backupDir, name), overwrite = true)
                }
                target.writeText(content, Charsets.UTF_8)
                restored++
            } catch (t: Throwable) {
                Log.w(TAG, "帳冊寫回失敗：$name", t)
                skipped.add(name)
            }
        }
        Log.i(TAG, "還原完成：$restored 帳冊（備份於 ${backupDir.name}）")
        return RestoreResult(restored, skipped)
    }

    // ── 內部 ────────────────────────────────────────────────────

    /** 包內帳冊數（ledgerCount 欄位；缺欄回 0） */
    private fun packCountOf(json: String): Int = try {
        JSONObject(json).optInt("ledgerCount", 0)
    } catch (t: Throwable) {
        0
    }

    private fun dateKeyOf(now: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.TAIWAN).format(Date(now))

    // ── UI 字串視窗 ────────────────────────────────────────────

    fun panelTitle(): String = AppContext.ctx.getString(R.string.migrate_title)
    fun buildLabel(): String = AppContext.ctx.getString(R.string.migrate_build)
    fun restoreLabel(): String = AppContext.ctx.getString(R.string.migrate_restore)
    fun emptyNotice(): String = AppContext.ctx.getString(R.string.migrate_empty)
    fun noPackNotice(): String = AppContext.ctx.getString(R.string.migrate_no_pack)
    fun badPackNotice(): String = AppContext.ctx.getString(R.string.migrate_bad_pack)
    fun backupNote(): String = AppContext.ctx.getString(R.string.migrate_backup_note)

    /** 「搬家包已建立（12 個帳冊）」 */
    fun builtNotice(result: PackResult): String =
        AppContext.ctx.getString(R.string.migrate_built_fmt, result.packed)

    /** 「已還原 12 個帳冊」 */
    fun restoredNotice(result: RestoreResult): String =
        AppContext.ctx.getString(R.string.migrate_restored_fmt, result.restored)

    /** 「12 個帳冊」 */
    fun filesLine(count: Int): String =
        AppContext.ctx.getString(R.string.migrate_files_fmt, count)
}

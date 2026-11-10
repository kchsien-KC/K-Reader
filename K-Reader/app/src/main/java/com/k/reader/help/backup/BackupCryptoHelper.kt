package com.k.reader.help.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.k.reader.app.AppContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * ═══════════════════════════════════════════════════════════════
 *  K.閱讀 v1.2 — 備份加密引擎（Stage 10：備份加密）
 * ═══════════════════════════════════════════════════════════════
 *
 *  設計理念（雲端放的是密文，而不是你的書櫃）：
 *   WebDAV 備份把整個閱讀生活——書源、進度、筆記、金句——放上
 *   遠端伺服器。伺服器是否可信不該由運氣決定。加密引擎在上傳
 *   前把明文變密文，下載後原樣還原：
 *
 *    1. 演算法：AES-256-GCM（認證加密——密文被竄改會被偵測，
 *       不是「讀出亂碼」而是「拒絕解密」）＋ PBKDF2WithHmacSHA256
 *       金鑰衍生（210,000 迭代——OWASP 2021 建議值，手機端約
 *       一秒內完成，暴力試密碼者每次都要付同樣代價）；
 *    2. 檔案格式：魔術標頭 KRC1＋版本＋16B salt＋12B nonce＋
 *       密文本體。salt 隨檔攜帶——換手機還原時檔案自帶配方，
 *       輸密碼即可解（不依賴本機偏好檔）；AAD 綁定魔術標頭，
 *       標頭被改一個位元整檔作廢；
 *    3. 錯誤分離：AEADBadTagException → 密碼錯誤（bkcrypto_
 *       wrong_password）；魔術不符 → 非加密備份檔（bkcrypto_
 *       invalid_format）——UI 端精準提示，不讓使用者猜；
 *    4. 金鑰落地：衍生金鑰以 Base64 存私有偏好檔（不存密碼
 *       本體；App 私有空間，無 root 不可讀）。clearPassword
 *       一鍵抹除——抹除後既有雲端密文僅剩密碼一途可解。
 *
 *  規格值：金鑰 256 位元；salt 16B；nonce 12B；GCM 標籤 128 位元；
 *  單檔明文上限 100MB。
 *
 *  整合點：
 *   · WebDavHelper.backupToCloud —— 上傳前 encryptFile（掛鉤：
 *     isEncryptionEnabled() 為真時走密文路徑）
 *   · WebDavHelper.restoreFromCloud —— 下載後 decryptFile
 *   · 設定頁 bkcrypto_title 面板 —— setPassword／clearPassword
 */
object BackupCryptoHelper {

    private const val TAG = "BackupCryptoHelper"

    private const val PREFS_NAME = "k_reader_backup_crypto"
    private const val KEY_STORED_KEY = "bkcrypto_stored_key"      // Base64 衍生金鑰
    private const val KEY_ENABLED = "bkcrypto_enabled"

    /** 加密檔魔術標頭（K.Reader Crypto v1） */
    const val MAGIC = "KRC1"

    /** 格式版本 */
    const val VERSION = 0x01

    /** 金鑰位元數（規格：AES-256） */
    const val KEY_BITS = 256

    /** PBKDF2 迭代數（規格：210,000——OWASP 2021 建議值） */
    const val PBKDF2_ITERATIONS = 210_000

    /** salt 位元組數（規格：16B） */
    const val SALT_BYTES = 16

    /** nonce 位元組數（規格：12B——GCM 標準值） */
    const val NONCE_BYTES = 12

    /** GCM 認證標籤位元數（規格：128 位元） */
    const val TAG_BITS = 128

    /** 單檔明文上限（規格：100MB——備份 JSON 遠小於此） */
    const val MAX_PLAIN_BYTES = 100L * 1024L * 1024L

    /** 金鑰驗證常數（解密成功即視為密碼正確） */
    private const val VERIFIER_PLAIN = "K.READER.BACKUP.OK"

    private val random = SecureRandom()

    private val prefs: SharedPreferences by lazy {
        AppContext.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ═════════════════ 結果型別 ═════════════════

    /** 加密結果 */
    sealed class EncryptResult {
        object Ok : EncryptResult()
        object NotConfigured : EncryptResult()   // 未設密碼
        object Failed : EncryptResult()          // IO／演算法失敗
    }

    /** 解密結果 */
    sealed class DecryptResult {
        object Ok : DecryptResult()
        object WrongPassword : DecryptResult()   // AEADBadTagException
        object NeedPassword : DecryptResult()    // 無落地金鑰且未供密碼（bkcrypto_need_password）
        object InvalidFormat : DecryptResult()   // 非 KRC1 檔
        object Failed : DecryptResult()          // IO 等
    }

    /** 記憶體解密結果（帶明文位元組） */
    data class DecryptBytesResult(val result: DecryptResult, val bytes: ByteArray?) {
        val ok: Boolean get() = result == DecryptResult.Ok && bytes != null
    }

    // ═════════════════ 密碼管理 ═════════════════

    /** 是否已啟用加密（已設密碼） */
    fun isEncryptionEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false) && storedKey() != null

    /**
     * 設定（或更新）加密密碼：
     * 長度 ≥ 8 → 生成新 salt → PBKDF2 衍生金鑰 → 落地。
     * 回傳 false＝密碼過短（bkcrypto_password_too_short）。
     */
    fun setPassword(password: String): Boolean {
        if (password.length < 8) return false
        return runCatching {
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val key = deriveKey(password, salt)
            prefs.edit()
                .putString(KEY_STORED_KEY, Base64.encodeToString(key.encoded, Base64.NO_WRAP))
                .putBoolean(KEY_ENABLED, true)
                .apply()
            Log.i(TAG, "備份加密已啟用（AES-256-GCM／PBKDF2 ${PBKDF2_ITERATIONS} 迭代）")
            true
        }.getOrElse { e ->
            Log.w(TAG, "金鑰衍生失敗", e)
            false
        }
    }

    /** 清除金鑰（停用加密；雲端既有密文僅剩密碼可解——bkcrypto_warn_lose） */
    fun clearPassword() {
        prefs.edit()
            .remove(KEY_STORED_KEY)
            .putBoolean(KEY_ENABLED, false)
            .apply()
        Log.i(TAG, "備份加密已停用")
    }

    private fun storedKey(): SecretKeySpec? = runCatching {
        prefs.getString(KEY_STORED_KEY, null)?.let { encoded ->
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size == KEY_BITS / 8) SecretKeySpec(bytes, "AES") else null
        }
    }.getOrNull()

    /** PBKDF2 金鑰衍生（密碼＋salt → 256 位元 AES 金鑰） */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec =
        runCatching {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
            val keyBytes = factory.generateSecret(spec).encoded
            spec.clearPassword()
            SecretKeySpec(keyBytes, "AES")
        }.getOrThrow()

    // ═════════════════ 檔案加密 ═════════════════

    /**
     * 檔案加密（串流——CipherOutputStream 8KB 緩衝，大檔不爆記憶體）：
     * 明文 src → 密文 dst（KRC1 格式）。未設密碼回 NotConfigured。
     */
    fun encryptFile(src: File, dst: File): EncryptResult {
        val key = storedKey() ?: return EncryptResult.NotConfigured
        if (!src.isFile) return EncryptResult.Failed
        if (src.length() > MAX_PLAIN_BYTES) return EncryptResult.Failed
        return runCatching {
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            // salt 亦落地偏好檔：本機後續解密免輸密碼
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce), random)
            cipher.updateAAD(headerAAD())

            dst.parentFile?.mkdirs()
            FileOutputStream(dst).use { fout ->
                fout.write(headerBytes(salt, nonce))
                CipherOutputStream(fout, cipher).use { cout ->
                    FileInputStream(src).use { fin ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = fin.read(buffer)
                            if (read < 0) break
                            cout.write(buffer, 0, read)
                        }
                    }
                }
            }
            EncryptResult.Ok
        }.getOrElse { e ->
            Log.w(TAG, "檔案加密失敗", e)
            runCatching { dst.delete() }.getOrDefault(Unit)
            EncryptResult.Failed
        }
    }

    /**
     * 檔案解密（串流）：密文 src → 明文 dst。
     * 使用落地金鑰；金鑰不符／未設金鑰時改以 [password] 現場衍生
     * （跨裝置還原路徑——salt 隨檔攜帶，輸密碼即可解）。
     */
    @JvmOverloads
    fun decryptFile(src: File, dst: File, password: String? = null): DecryptResult {
        if (!src.isFile) return DecryptResult.Failed
        // 1. 解析標頭
        val header = readHeader(src) ?: return DecryptResult.InvalidFormat
        // 2. 取金鑰：落地金鑰優先 → 現場衍生（跨裝置還原路徑）
        val key = storedKey() ?: password?.let {
            runCatching { deriveKey(it, header.salt) }.getOrNull()
        } ?: return DecryptResult.NeedPassword
        // 3. 串流解密（GCM 標籤驗證失敗 = 密碼錯誤）
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.nonce))
            cipher.updateAAD(headerAAD())

            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".decrypting")
            FileInputStream(src).use { fin ->
                fin.skip(HEADER_SIZE.toLong())
                CipherInputStream(fin, cipher).use { cin ->
                    FileOutputStream(tmp).use { fout ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = cin.read(buffer)
                            if (read < 0) break
                            fout.write(buffer, 0, read)
                        }
                    }
                }
            }
            // 先寫暫存檔再改名——解密中途失敗不污染目標
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            DecryptResult.Ok
        }.getOrElse { e ->
            runCatching { File(dst.parentFile, dst.name + ".decrypting").delete() }
            if (e is javax.crypto.AEADBadTagException) {
                Log.w(TAG, "解密標籤驗證失敗（密碼錯誤）")
                DecryptResult.WrongPassword
            } else {
                Log.w(TAG, "檔案解密失敗", e)
                DecryptResult.Failed
            }
        }
    }

    // ═════════════════ 記憶體加密（WebDav 直掛） ═════════════════

    /** 位元組加密（WebDavHelper 記憶體備份路徑；未設密碼回 null） */
    fun encryptBytes(plain: ByteArray): ByteArray? {
        val key = storedKey() ?: return null
        if (plain.size > MAX_PLAIN_BYTES) return null
        return runCatching {
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce), random)
            cipher.updateAAD(headerAAD())
            val cipherBytes = cipher.doFinal(plain)
            headerBytes(salt, nonce) + cipherBytes
        }.getOrElse { e ->
            Log.w(TAG, "位元組加密失敗", e)
            null
        }
    }

    /** 位元組解密（密碼可選——跨裝置路徑；GCM 標籤即密碼驗證） */
    fun decryptBytes(encrypted: ByteArray, password: String? = null): DecryptBytesResult {
        val header = parseHeader(encrypted)
            ?: return DecryptBytesResult(DecryptResult.InvalidFormat, null)
        val key = storedKey() ?: password?.let {
            runCatching { deriveKey(it, header.salt) }.getOrNull()
        } ?: return DecryptBytesResult(DecryptResult.NeedPassword, null)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, header.nonce))
            cipher.updateAAD(headerAAD())
            val plain = cipher.doFinal(encrypted, HEADER_SIZE, encrypted.size - HEADER_SIZE)
            DecryptBytesResult(DecryptResult.Ok, plain)
        }.getOrElse { e ->
            if (e is javax.crypto.AEADBadTagException) {
                DecryptBytesResult(DecryptResult.WrongPassword, null)
            } else {
                Log.w(TAG, "位元組解密失敗", e)
                DecryptBytesResult(DecryptResult.Failed, null)
            }
        }
    }

    /** 是否為 KRC1 加密檔（魔術前 4 位元組比對） */
    fun isEncryptedFile(file: File): Boolean = runCatching {
        FileInputStream(file).use { fin ->
            val magic = ByteArray(MAGIC.length)
            fin.read(magic) == MAGIC.length && magic.toString(Charsets.ISO_8859_1) == MAGIC
        }
    }.getOrDefault(false)

    // ═════════════════ 檔案格式 ═════════════════

    /** KRC1 標頭：魔術 4B＋版本 1B＋salt 16B＋nonce 12B */
    private const val HEADER_SIZE = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES

    private data class FileHeader(val salt: ByteArray, val nonce: ByteArray)

    /** AAD（附加認證資料）：魔術＋版本——標頭竄改即整檔作廢 */
    private fun headerAAD(): ByteArray =
        (MAGIC.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(VERSION.toByte()))

    private fun headerBytes(salt: ByteArray, nonce: ByteArray): ByteArray =
        MAGIC.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(VERSION.toByte()) + salt + nonce

    private fun readHeader(file: File): FileHeader? = runCatching {
        FileInputStream(file).use { fin ->
            val header = ByteArray(HEADER_SIZE)
            var read = 0
            while (read < HEADER_SIZE) {
                val r = fin.read(header, read, HEADER_SIZE - read)
                if (r < 0) return@use null
                read += r
            }
            parseHeader(header)
        }
    }.getOrNull()

    private fun parseHeader(bytes: ByteArray): FileHeader? {
        if (bytes.size < HEADER_SIZE) return null
        val magic = String(bytes, 0, MAGIC.length, Charsets.ISO_8859_1)
        if (magic != MAGIC) return null
        if (bytes[MAGIC.length].toInt() != VERSION) return null
        val salt = bytes.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_BYTES)
        val nonce = bytes.copyOfRange(MAGIC.length + 1 + SALT_BYTES, HEADER_SIZE)
        return FileHeader(salt, nonce)
    }

    // ═════════════════ 自檢 ═════════════════

    /**
     * 自檢（設定頁觸發）：設密碼後跑一輪加解密往返，
     * 驗證本機 PBKDF2／AES-GCM 管線健康。回傳毫秒（-1＝失敗）。
     */
    fun selfTest(): Long {
        val key = storedKey() ?: return -1L
        val start = System.currentTimeMillis()
        return runCatching {
            val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce), random)
            cipher.updateAAD(headerAAD())
            val sealed = cipher.doFinal(VERIFIER_PLAIN.toByteArray(Charsets.UTF_8))

            val decipher = Cipher.getInstance("AES/GCM/NoPadding")
            decipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            decipher.updateAAD(headerAAD())
            val plain = decipher.doFinal(sealed)
            check(plain.toString(Charsets.UTF_8) == VERIFIER_PLAIN)
            System.currentTimeMillis() - start
        }.getOrElse { e ->
            Log.w(TAG, "加密自檢失敗", e)
            -1L
        }
    }
}

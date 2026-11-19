package com.nongyu.appplus.webvpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nongyu.appplus.net.core.NongyuLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 学校登录账号 / 密码的**加密持久存储**（用户 2026-11-18 明确要求「持久保存 + 下次自动填」）。
 *
 * ## 为什么复用 `net/auth` 的那一套，而不是自己再写一份
 *
 * `MinimalSessionStore` 已经把「DataStore 落盘 + 自管 Keystore AES/GCM」这条路的坑
 * 全部踩过并写在注释里（备份恢复后解密必然失败、`CorruptionException` 是 `IOException`
 * 子类导致崩溃等）。本类**照抄同一套结构与容错**，只换三样东西：
 *
 * | | 会话令牌 | 本类 |
 * | --- | --- | --- |
 * | DataStore 文件 | `nongyu_secure_session` | `nongyu_secure_webvpn` |
 * | Keystore 别名 | `nongyu_session_aead_key` | `nongyu_webvpn_cred_aead_key` |
 * | 存的内容 | App 会话 token | 学校账号 + 密码 |
 *
 * **别名必须分开**：共用一个别名的后果是「登出清会话时把密码一起废掉」，
 * 或者反过来「删密码把用户踢下线」，两种都很难查。
 *
 * ## 三条与令牌存储完全一致的硬约束
 *
 * 1. **惰性打开**：构造只存 context，首次读写才碰 Keystore 与磁盘（不让启动路径碰 IPC）。
 * 2. **读取侧容错**：备份恢复 / 换机后密文在、Key 不在，解密必失败 —— 这是**必然情形**，
 *    必须当作「没有保存过账号」并清空，不能抛。
 * 3. **DataStore 损坏容错**：`CorruptionException` 是 `IOException` 子类，
 *    只用 `ReplaceFileCorruptionHandler` 不够，`catch (IOException)` 也要有。
 *
 * ## 安全边界
 *
 * - 明文只在内存里短暂存在；落盘永远是 base64(IV ‖ ciphertext)；
 * - **绝不打印、绝不进日志**（本类所有日志只写「成功/失败」，不含值）；
 * - 提供 [clear]，让用户能随时删除；删除同时清空整个 prefs。
 *
 * @param context 用 `applicationContext`，防泄漏。
 */
class WebVpnCredentialStore(
    private val context: Context,
    private val logger: NongyuLogger = NongyuLogger.Noop,
) {

    /** 保存下来的账号密码对。密码只应短暂停留在 UI 内存里。 */
    data class Credential(val account: String, val password: String)

    @Volatile
    private var opened = false

    private fun ensureReady(): Boolean {
        if (opened) return true
        synchronized(this) {
            if (opened) return true
            return try {
                obtainKey()
                opened = true
                true
            } catch (e: GeneralSecurityException) {
                logged("WebVPN 账号存储打开失败，按未保存处理", e)
                false
            } catch (e: IOException) {
                logged("WebVPN 账号存储打开失败（IO），按未保存处理", e)
                false
            } catch (e: RuntimeException) {
                logged("WebVPN 账号存储打开失败（运行时），按未保存处理", e)
                false
            }
        }
    }

    /** 读取已保存的账号密码；没有任何有效记录时返回 `null`。 */
    fun read(): Credential? {
        if (!ensureReady()) return null
        return try {
            val (accountEnc, passwordEnc) = runBlocking {
                val prefs = context.webVpnCredDataStore.data.first()
                prefs[stringPreferencesKey(KEY_ACCOUNT)] to
                    prefs[stringPreferencesKey(KEY_PASSWORD)]
            }
            if (accountEnc.isNullOrEmpty() || passwordEnc.isNullOrEmpty()) return null
            Credential(account = decrypt(accountEnc), password = decrypt(passwordEnc))
        } catch (e: GeneralSecurityException) {
            logged("WebVPN 账号解密失败，视为未保存并清空", e)
            clearQuietly()
            null
        } catch (e: IOException) {
            logged("WebVPN 账号读取失败（IO/文件损坏），视为未保存", e)
            clearQuietly()
            null
        } catch (e: RuntimeException) {
            logged("WebVPN 账号解密失败（运行时），视为未保存并清空", e)
            clearQuietly()
            null
        }
    }

    /** 保存账号密码。失败时不抛，只记日志（不影响用户继续在 WebView 里手填）。 */
    fun save(account: String, password: String) {
        if (!ensureReady()) {
            logged("WebVPN 账号存储不可用，未保存", null)
            return
        }
        try {
            val a = encrypt(account)
            val p = encrypt(password)
            runBlocking {
                context.webVpnCredDataStore.edit { prefs ->
                    prefs[stringPreferencesKey(KEY_ACCOUNT)] = a
                    prefs[stringPreferencesKey(KEY_PASSWORD)] = p
                }
            }
            logged("WebVPN 账号已加密保存", null)
        } catch (e: GeneralSecurityException) {
            logged("WebVPN 账号加密失败，未保存", e)
        } catch (e: IOException) {
            logged("WebVPN 账号写入失败（IO），未保存", e)
        } catch (e: RuntimeException) {
            logged("WebVPN 账号加密失败（运行时），未保存", e)
        }
    }

    /**
     * 只保存手机号（短信登录用）。
     *
     * 维护者口径：**短信登录只记手机号**，不记任何短信验证码。
     * 复用同一个存储文件，密码位清空 —— 这样 [read] 返回的 `password` 为空串，
     * UI 据此知道「这是手机号方式，不是账密」。
     */
    fun savePhoneOnly(phone: String) {
        val p = phone.trim()
        if (p.isEmpty()) return
        if (!ensureReady()) {
            logged("WebVPN 账号存储不可用，手机号未保存", null)
            return
        }
        try {
            val enc = encrypt(p)
            runBlocking {
                context.webVpnCredDataStore.edit { prefs ->
                    prefs[stringPreferencesKey(KEY_ACCOUNT)] = enc
                    // 密码位必须清掉：否则会残留上次账密登录的密码
                    prefs.remove(stringPreferencesKey(KEY_PASSWORD))
                }
            }
            logged("WebVPN 手机号已加密保存", null)
        } catch (e: GeneralSecurityException) {
            logged("WebVPN 手机号加密失败，未保存", e)
        } catch (e: IOException) {
            logged("WebVPN 手机号写入失败（IO）", e)
        } catch (e: RuntimeException) {
            logged("WebVPN 手机号加密失败（运行时）", e)
        }
    }

    /** 删除已保存的账号密码。 */
    fun clear() {
        if (!ensureReady()) return
        clearQuietly()
    }

    private fun clearQuietly() {
        try {
            runBlocking {
                context.webVpnCredDataStore.edit { prefs ->
                    prefs.remove(stringPreferencesKey(KEY_ACCOUNT))
                    prefs.remove(stringPreferencesKey(KEY_PASSWORD))
                }
            }
        } catch (e: IOException) {
            logged("清空 WebVPN 账号失败（IO）", e)
        } catch (e: RuntimeException) {
            logged("清空 WebVPN 账号失败", e)
        }
    }

    // ---------------- 加密层 ----------------

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /** 加密并返回 base64(iv ‖ ciphertext)。 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "GCM IV 长度异常：${iv.size}" }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    /** 解密 base64(iv ‖ ciphertext)；失败抛 `GeneralSecurityException`，由调用方兜住。 */
    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        require(raw.size > IV_SIZE) { "密文长度不足以容纳 IV" }
        val iv = raw.copyOfRange(0, IV_SIZE)
        val body = raw.copyOfRange(IV_SIZE, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun logged(message: String, throwable: Throwable?) {
        logger.log(NongyuLogger.Level.INFO, message, throwable)
    }

    companion object {
        /** DataStore 名字（落盘 `files/datastore/nongyu_secure_webvpn.preferences_pb`）。 */
        const val DATASTORE_NAME: String = "nongyu_secure_webvpn"

        /** Keystore 别名。**必须与会话令牌的别名不同**（见类注释）。 */
        const val KEY_ALIAS: String = "nongyu_webvpn_cred_aead_key"

        const val KEY_ACCOUNT: String = "webvpn_account"
        const val KEY_PASSWORD: String = "webvpn_password"

        /** 备份规则需排除的相对路径（相对 `files/` 域）。 */
        val BACKUP_EXCLUDE_PATH: String get() = "datastore/$DATASTORE_NAME.preferences_pb"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
    }
}

/** DataStore 实例必须全局唯一（同名重复创建会抛异常），故放在文件级。 */
private val Context.webVpnCredDataStore: DataStore<Preferences> by preferencesDataStore(
    name = WebVpnCredentialStore.DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

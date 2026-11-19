package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.net.auth.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 断言 WebVPN 账密存储的**隔离性**与备份排除一致（纯 JVM，无需 Android 运行时）。
 *
 * ## 为什么单独一条测试
 *
 * 这里最容易犯的不是「路径写错」，而是**和会话令牌撞在一起**：
 * 同一个 DataStore 文件或同一个 Keystore 别名，会让
 * 「登出清会话」与「删除账密」互相误伤，而且症状极难定位。
 * 这条测试把「必须分开」钉成构建期断言。
 */
class WebVpnCredentialStoreContractTest {

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue("找不到文件：${f.absolutePath}（单测工作目录应为模块目录 app/）", f.exists())
        return f.readText()
    }

    @Test
    fun `WebVPN 账密与会话令牌使用不同的 DataStore 文件与 Keystore 别名`() {
        assertNotEquals(
            "DataStore 文件名不能与会话令牌相同",
            SecurePrefs.DATASTORE_NAME,
            WebVpnCredentialStore.DATASTORE_NAME,
        )
        assertNotEquals(
            "Keystore 别名不能与会话令牌相同（否则登出会废掉密码）",
            SecurePrefs.KEY_ALIAS,
            WebVpnCredentialStore.KEY_ALIAS,
        )
    }

    @Test
    fun `落盘路径由 DATASTORE_NAME 派生且符合 DataStore 约定`() {
        assertEquals(
            "datastore/${WebVpnCredentialStore.DATASTORE_NAME}.preferences_pb",
            WebVpnCredentialStore.BACKUP_EXCLUDE_PATH,
        )
    }

    @Test
    fun `两个备份规则文件都排除了 WebVPN 账密文件`() {
        for (rel in listOf(
            "src/main/res/xml/data_extraction_rules.xml",
            "src/main/res/xml/backup_rules.xml",
        )) {
            val xml = read(rel)
            assertTrue(
                "$rel 必须排除 WebVPN 账密文件（学校账号密码不得进备份/换机直传）",
                xml.contains(WebVpnCredentialStore.BACKUP_EXCLUDE_PATH),
            )
            assertTrue(
                "$rel 的排除 domain 必须是 file（DataStore 落在 files/ 下）",
                Regex(
                    """<exclude\s+domain="file"\s+path="${Regex.escape(WebVpnCredentialStore.BACKUP_EXCLUDE_PATH)}"""",
                ).containsMatchIn(xml),
            )
        }
    }
}

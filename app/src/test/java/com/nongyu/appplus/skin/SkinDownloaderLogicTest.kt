package com.nongyu.appplus.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤包下载决策的纯 JVM 单测（票 #53）。
 *
 * 与 #54 的 SkinFontResolver 同一套分层：只测「要不要下 / 要不要装」的决策，
 * 不测「怎么发 HTTP / 怎么解压 zip」——那些依赖 Android Framework 与 IO。
 * 契约来源：docs/research/皮肤包下载器-服务端对接须知.md
 *
 * 注意：Kotlin 反引号函数名只放 ASCII，中文描述放注释，避免编码问题。
 */
class SkinDownloaderLogicTest {

    // ---------- SkinCatalog.parseMap ----------

    @Test
    fun catalog_hasPackFalse_skips() {
        val c = SkinCatalog.parseMap(mapOf("hasPack" to false))
        assertFalse(c.hasPack)
        assertNull(c.packId)
        assertNull(c.version)
        assertNull(c.downloadUrl)
        assertEquals(
            SkinDownloadDecision.Skip("服务端未发布皮肤包（hasPack=false）"),
            SkinDownloadDecision.of(c),
        )
    }

    @Test
    fun catalog_hasPackTrue_parsesAllFields() {
        val c = SkinCatalog.parseMap(
            mapOf(
                "hasPack" to true,
                "packId" to "default-green",
                "version" to "1.0.0",
                "packSha256" to "abc123",
                "packSize" to 12345L,
                "downloadUrl" to "/skins/default-green/1.0.0/pack",
                "displayName" to "农屿默认绿",
            ),
        )
        assertTrue(c.hasPack)
        assertEquals("default-green", c.packId)
        assertEquals("1.0.0", c.version)
        assertEquals("abc123", c.packSha256)
        assertEquals("/skins/default-green/1.0.0/pack", c.downloadUrl)
        assertEquals(SkinDownloadDecision.Proceed("abc123"), SkinDownloadDecision.of(c))
    }

    @Test
    fun catalog_missingIdOrVersion_skips() {
        val noId = SkinCatalog.parseMap(
            mapOf("hasPack" to true, "version" to "1.0.0", "downloadUrl" to "/x/pack"),
        )
        assertTrue(SkinDownloadDecision.of(noId) is SkinDownloadDecision.Skip)

        val noVersion = SkinCatalog.parseMap(
            mapOf("hasPack" to true, "packId" to "p", "downloadUrl" to "/x/pack"),
        )
        assertTrue(SkinDownloadDecision.of(noVersion) is SkinDownloadDecision.Skip)
    }

    @Test
    fun catalog_missingUrl_buildsFromIdAndVersion() {
        val c = SkinCatalog.parseMap(
            mapOf("hasPack" to true, "packId" to "p1", "version" to "v2", "packSha256" to "deadbeef"),
        )
        assertEquals("/skins/p1/v2/pack", c.downloadUrl)
        assertTrue(SkinDownloadDecision.of(c) is SkinDownloadDecision.Proceed)
    }

    @Test
    fun catalog_unsafeSegments_skips() {
        val bad = SkinCatalog.parseMap(
            mapOf("hasPack" to true, "packId" to "../evil", "version" to "1.0", "downloadUrl" to "/x/pack"),
        )
        assertTrue(SkinDownloadDecision.of(bad) is SkinDownloadDecision.Skip)
    }

    @Test
    fun catalog_missingHasPack_isFalse() {
        assertFalse(SkinCatalog.parseMap(emptyMap()).hasPack)
    }

    // ---------- SkinPackSha256Checker ----------

    @Test
    fun sha_matchesIgnoringCaseAndWhitespace() {
        assertTrue(SkinPackSha256Checker.matches("ABCDEF", "abcdef"))
        assertTrue(SkinPackSha256Checker.matches("  AbCdEf  ", "abcdef"))
    }

    @Test
    fun sha_mismatchOrMissing_rejects() {
        assertFalse(SkinPackSha256Checker.matches("aaa", "bbb"))
        assertFalse(SkinPackSha256Checker.matches(null, "bbb"))
        assertFalse(SkinPackSha256Checker.matches("bbb", null))
        assertFalse(SkinPackSha256Checker.matches("bbb", ""))
    }

    // ---------- SkinInstallGate ----------

    @Test
    fun installGate_onlyWhenVerifiedAndShaMatches() {
        assertTrue(SkinInstallGate.shouldInstall(verificationOk = true, packShaMatches = true))
        assertFalse(SkinInstallGate.shouldInstall(verificationOk = false, packShaMatches = true))
        assertFalse(SkinInstallGate.shouldInstall(verificationOk = true, packShaMatches = false))
        assertFalse(SkinInstallGate.shouldInstall(verificationOk = false, packShaMatches = false))
    }

    // ---------- SkinZipPathSafety ----------

    @Test
    fun zipPathSafety_rejectsTraversal() {
        assertTrue(SkinZipPathSafety.isSafe("manifest.json"))
        assertTrue(SkinZipPathSafety.isSafe("fonts/brand.ttf"))
        assertFalse(SkinZipPathSafety.isSafe("../evil.ttf"))
        assertFalse(SkinZipPathSafety.isSafe("a/../../evil.ttf"))
        assertFalse(SkinZipPathSafety.isSafe("/absolute/evil.ttf"))
        assertFalse(SkinZipPathSafety.isSafe(""))
        assertFalse(SkinZipPathSafety.isSafe("a\\..\\b"))
    }
}

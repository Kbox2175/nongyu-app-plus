package com.nongyu.appplus.net.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校内/校外判定状态的纯逻辑测试（规格 §3.7）。
 *
 * 核心是钉住**三值语义**：`Unknown` 绝不冒充结论 —— 既不挂头也不上报。
 */
class CampusNetworkStateTest {

    @After
    fun tearDown() {
        CampusNetworkState.reset()
    }

    @Test
    fun `初始状态是 Unknown`() {
        CampusNetworkState.reset()
        assertEquals(CampusNetworkState.Presence.Unknown, CampusNetworkState.current)
    }

    @Test
    fun `Unknown 不挂头`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Unknown)
        assertNull("Unknown 必须返回 null（不挂 X-Nongyu-Campus-Network）", CampusNetworkState.headerValue())
    }

    @Test
    fun `Unknown 不上报`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Unknown)
        assertFalse("Unknown 不该上报给后端", CampusNetworkState.shouldReport())
    }

    @Test
    fun `Direct 挂 direct 头并上报`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Direct)
        assertEquals("direct", CampusNetworkState.headerValue())
        assertTrue(CampusNetworkState.shouldReport())
    }

    @Test
    fun `Vpn 挂 vpn 头并上报`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Vpn)
        assertEquals("vpn", CampusNetworkState.headerValue())
        assertTrue(CampusNetworkState.shouldReport())
    }

    @Test
    fun `头的取值与后端常量逐字一致`() {
        // 后端 jiaowu.AccessDirect / AccessVPN / HeaderCampusNetwork
        assertEquals("X-Nongyu-Campus-Network", CampusNetworkState.HEADER_NAME)
        assertEquals("direct", CampusNetworkState.HEADER_VALUE_DIRECT)
        assertEquals("vpn", CampusNetworkState.HEADER_VALUE_VPN)
    }

    @Test
    fun `headerValue 永不返回空串`() {
        // 后端 AccessModeFromHeader 只认 direct/vpn，空串会被当成未识别
        CampusNetworkState.Presence.entries.forEach { p ->
            CampusNetworkState.update(p)
            val v = CampusNetworkState.headerValue()
            assertTrue("$p 的 headerValue 不能是空串", v == null || v.isNotEmpty())
        }
    }

    @Test
    fun `可以来回切换`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Direct)
        assertEquals("direct", CampusNetworkState.headerValue())
        CampusNetworkState.update(CampusNetworkState.Presence.Vpn)
        assertEquals("vpn", CampusNetworkState.headerValue())
        CampusNetworkState.reset()
        assertNull(CampusNetworkState.headerValue())
    }
}

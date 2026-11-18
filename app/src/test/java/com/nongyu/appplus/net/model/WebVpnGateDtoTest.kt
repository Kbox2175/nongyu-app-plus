package com.nongyu.appplus.net.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `cache_miss` 的 `webvpn` 字段（后端 2026-11-18 `9fc5219`）。
 *
 * 这个字段解决的老问题：原先校外形态只回 `session=client_side`，
 * 客户端分不清「还没登录网关」和「已登录票正常」，弹窗没有信号源。
 *
 * 维护者的触发条件是：**只有票缺失或失效才弹**。
 */
class WebVpnGateDtoTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun decode(raw: String): CacheResult =
        json.decodeFromString(CacheGetResponse.serializer(), raw).discriminate()

    @Test
    fun `ready 不弹窗`() {
        val r = decode(
            """{"code":"cache_miss","address":"https://webvpn/x","mode":"vpn",
               "session":"client_side",
               "webvpn":{"status":"ready","expiresAt":"2026-11-19T00:00:00Z"}}""",
        ) as CacheResult.Miss

        assertTrue(r.webvpn!!.isReady())
        assertFalse("ready 不该弹窗", r.needsWebVpnLogin())
    }

    @Test
    fun `missing 要弹窗`() {
        val r = decode(
            """{"code":"cache_miss","mode":"vpn","session":"client_side",
               "webvpn":{"status":"missing","code":"webvpn_ticket_missing"}}""",
        ) as CacheResult.Miss

        assertTrue("missing 必须弹窗", r.needsWebVpnLogin())
        assertEquals("webvpn_ticket_missing", r.webvpn?.code)
    }

    @Test
    fun `expired 要弹窗`() {
        val r = decode(
            """{"code":"cache_miss","mode":"vpn","session":"client_side",
               "webvpn":{"status":"expired","code":"webvpn_ticket_expired"}}""",
        ) as CacheResult.Miss

        assertTrue(r.needsWebVpnLogin())
    }

    @Test
    fun `校内形态没有 webvpn 字段，不弹窗`() {
        val r = decode(
            """{"code":"cache_miss","mode":"direct","session":"ok",
               "cookies":[{"name":"ASPSESSIONID","value":"x"}]}""",
        ) as CacheResult.Miss

        assertNull(r.webvpn)
        assertFalse("校内不该弹 WebVPN 弹窗", r.needsWebVpnLogin())
    }

    @Test
    fun `code 决定弹否，不依赖 status 字段`() {
        // 即便 status 写得奇怪，只要 code 是那两个之一就要弹
        val r = decode(
            """{"code":"cache_miss","webvpn":{"status":"weird","code":"webvpn_ticket_missing"}}""",
        ) as CacheResult.Miss
        assertTrue(r.needsWebVpnLogin())
    }

    @Test
    fun `未知 code 不弹`() {
        val r = decode(
            """{"code":"cache_miss","webvpn":{"status":"ready","code":"something_else"}}""",
        ) as CacheResult.Miss
        assertFalse(r.needsWebVpnLogin())
    }

    @Test
    fun `status 常量与后端逐字一致`() {
        assertEquals("ready", WebVpnGateDto.STATUS_READY)
        assertEquals("missing", WebVpnGateDto.STATUS_MISSING)
        assertEquals("expired", WebVpnGateDto.STATUS_EXPIRED)
    }
}

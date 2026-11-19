package com.nongyu.appplus.webvpn.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关换票的纯逻辑（2026-11-19 实测链路）。
 *
 * 请求形状逐字对齐浏览器抓到的真实 URL：
 * `GET webvpn.sicau.edu.cn/login?cas_login=true&ticket=<st>&userId=<id>`
 */
class WebVpnGatewayExchangeTest {

    @Test
    fun `换票 URL 与实测形状一致`() {
        val url = WebVpnGatewayExchange.loginUrl(st = "ST-abc123", userId = "42")
        assertTrue(url.startsWith("https://webvpn.sicau.edu.cn/login?cas_login=true"))
        assertTrue(url.contains("ticket=ST-abc123"))
        assertTrue(url.contains("userId=42"))
    }

    @Test
    fun `st 里的特殊字符被编码`() {
        val url = WebVpnGatewayExchange.loginUrl(st = "ST:a/b+c", userId = "1")
        // 冒号、斜杠、加号都必须转义，否则会破坏 query 结构
        assertTrue(url.contains("ticket=ST%3Aa%2Fb%2Bc"))
    }

    @Test
    fun `从 Set-Cookie 头里取出网关票`() {
        val headers = listOf(
            "wengine_vpn_ticketwebvpn_sicau_edu_cn=abc123def456; Path=/; Domain=webvpn.sicau.edu.cn; HttpOnly",
        )
        assertEquals(
            "abc123def456",
            WebVpnGatewayExchange.ticketFromSetCookie(headers),
        )
    }

    @Test
    fun `多条 Set-Cookie 里挑出网关票`() {
        val headers = listOf(
            "show_vpn=1; Path=/",
            "wengine_vpn_ticketwebvpn_sicau_edu_cn=deadbeef0011; Path=/; HttpOnly",
            "show_faq=0; Path=/",
        )
        assertEquals(
            "deadbeef0011",
            WebVpnGatewayExchange.ticketFromSetCookie(headers),
        )
    }

    @Test
    fun `没有网关票时返回 null`() {
        assertNull(WebVpnGatewayExchange.ticketFromSetCookie(emptyList()))
        assertNull(
            WebVpnGatewayExchange.ticketFromSetCookie(
                listOf("ASPSESSIONID=abc; Path=/", "show_vpn=1"),
            ),
        )
    }

    @Test
    fun `形态不合法的票被拒绝`() {
        // 太短
        assertNull(
            WebVpnGatewayExchange.ticketFromSetCookie(
                listOf("wengine_vpn_ticketx=short"),
            ),
        )
        // 含 HTML 片段（历史踩坑：302 跳转页被当票）
        assertNull(
            WebVpnGatewayExchange.ticketFromSetCookie(
                listOf("wengine_vpn_ticketx=<a href=x>"),
            ),
        )
    }

    @Test
    fun `识别网关拒绝登录的页面`() {
        assertTrue(
            WebVpnGatewayExchange.looksLikeInvalidTicket(
                "<html>INVALID_TICKET: 票据鉴权失败：非法的ticket</html>",
            ),
        )
        assertFalse(WebVpnGatewayExchange.looksLikeInvalidTicket("<html>welcome</html>"))
        assertFalse(WebVpnGatewayExchange.looksLikeInvalidTicket(null))
    }

    @Test
    fun `urlEncode 保留安全字符`() {
        assertEquals("abc-._~123", WebVpnGatewayExchange.urlEncode("abc-._~123"))
        assertEquals("a%20b", WebVpnGatewayExchange.urlEncode("a b"))
    }
}

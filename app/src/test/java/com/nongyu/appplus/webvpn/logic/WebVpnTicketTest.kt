package com.nongyu.appplus.webvpn.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关票提取与形态校验：与后端 `LooksLikeWebVPNTicket` 逐条对齐。
 *
 * 重点覆盖那条真实的踩坑：`302` 跳转页 HTML（`<a href="...">Found</a>.`）
 * 曾被当成 cookie 值交上去，导致后端存了脏票据。
 */
class WebVpnTicketTest {

    @Test
    fun `从完整 Cookie 头里提取网关票`() {
        val header = "show_vpn=1; wengine_vpn_ticketwebvpn_sicau_edu_cn=1a2b3c4d5e6f7a8b; show_faq=0"
        assertEquals("1a2b3c4d5e6f7a8b", WebVpnTicket.extract(header))
    }

    @Test
    fun `票名带不同后缀也能按前缀命中`() {
        assertEquals(
            "ABCDEF0123456789",
            WebVpnTicket.extract("wengine_vpn_ticket_another_suffix=ABCDEF0123456789; x=1"),
        )
    }

    @Test
    fun `同名多段取最后一个`() {
        assertEquals(
            "second-value-0001",
            WebVpnTicket.extract(
                "wengine_vpn_ticketx=first-value-0001; wengine_vpn_ticketx=second-value-0001",
            ),
        )
    }

    @Test
    fun `没有网关票时返回 null`() {
        assertNull(WebVpnTicket.extract("show_vpn=1; ASPSESSIONID=abc"))
        assertNull(WebVpnTicket.extract(""))
        assertNull(WebVpnTicket.extract(null))
        assertNull(WebVpnTicket.extract("   "))
    }

    @Test
    fun `形态不合法的票被挡在本地`() {
        // 空 / 太短
        assertFalse(WebVpnTicket.looksLikeTicket("1234567"))
        // 含空白
        assertFalse(WebVpnTicket.looksLikeTicket("1a2b 3c4d5e6f7a8b"))
        // 含尖括号 / 引号（HTML 片段）
        assertFalse(WebVpnTicket.looksLikeTicket("<a href=abc>"))
        assertFalse(WebVpnTicket.looksLikeTicket("abc'def1234"))
        // 超长
        assertFalse(WebVpnTicket.looksLikeTicket("a".repeat(513)))
        // null
        assertFalse(WebVpnTicket.looksLikeTicket(null))
    }

    @Test
    fun `302 跳转页 HTML 不会被当票提取`() {
        val html = """<a href="https://webvpn.sicau.edu.cn/login">Found</a>."""
        assertNull(WebVpnTicket.extract(html))
        // 就算被硬塞进 cookie 段，形态校验也要挡住
        assertFalse(WebVpnTicket.looksLikeTicket(html))
    }

    @Test
    fun `合法的 16 位十六进制票通过`() {
        assertTrue(WebVpnTicket.looksLikeTicket("1a2b3c4d5e6f7a8b"))
        assertTrue(WebVpnTicket.looksLikeTicket("0000000000000000"))
        // 注意：游客票同为 16 位，形态校验**不能**区分是否登录成功 —— 这里只验形态
        assertTrue(WebVpnTicket.looksLikeTicket("deadbeefdeadbeef"))
    }

    @Test
    fun `仍在登录页的判定`() {
        assertTrue(WebVpnTicket.isStillOnLoginPage(null))
        assertTrue(WebVpnTicket.isStillOnLoginPage(""))
        assertTrue(WebVpnTicket.isStillOnLoginPage("https://webvpn.sicau.edu.cn/login"))
        assertTrue(
            WebVpnTicket.isStillOnLoginPage(
                "https://webvpn.sicau.edu.cn/login?cas_login=true&ticket=ST:FAKE",
            ),
        )
        assertFalse(WebVpnTicket.isStillOnLoginPage("https://webvpn.sicau.edu.cn/"))
        assertFalse(
            WebVpnTicket.isStillOnLoginPage(
                "https://webvpn.sicau.edu.cn/https/77726476706e69737468656265737421/",
            ),
        )
        // 不是网关域：保守当作还在登录页
        assertTrue(WebVpnTicket.isStillOnLoginPage("https://auth.sicau.edu.cn/wui/"))
    }
}

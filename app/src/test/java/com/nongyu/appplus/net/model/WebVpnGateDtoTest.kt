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

    // ---------- 2026-11-19 修正：must 也认 session 字段 ----------

    @Test
    fun `校外 session=unavailable 且无 cookie 时必须弹窗`() {
        // 真机日志实测的正是这个形状：session=unavailable cookies=0，没有 webvpn 字段
        val r = decode(
            """{"code":"cache_miss","mode":"vpn","session":"unavailable",
               "address":"https://webvpn.sicau.edu.cn/https/xxx/xuesheng/kbbanji.asp"}""",
        ) as CacheResult.Miss

        assertNull("这条路径后端不给 webvpn 字段", r.webvpn)
        assertTrue("必须靠 session+mode 判定出缺票", r.needsWebVpnLogin())
    }

    @Test
    fun `校外 session=ok 且有 cookie 时不弹窗`() {
        val r = decode(
            """{"code":"cache_miss","mode":"vpn","session":"ok",
               "address":"https://webvpn/x",
               "cookies":[{"name":"wengine_vpn_ticketwebvpn_sicau_edu_cn","value":"T"}]}""",
        ) as CacheResult.Miss

        assertFalse("有票就不该弹", r.needsWebVpnLogin())
    }

    @Test
    fun `校内形态 cookie 为空不弹 WebVPN 窗`() {
        // 校内 cookie 空 = 教务会话没换来，该走「重新登录教务」，不是 WebVPN
        val r = decode(
            """{"code":"cache_miss","mode":"direct","session":"unavailable",
               "address":"https://jiaowu.sicau.edu.cn/x"}""",
        ) as CacheResult.Miss

        assertFalse("校内缺的不是网关票", r.needsWebVpnLogin())
    }

    // ---------- 公共取数账号池失败 = 服务端问题 ----------

    @Test
    fun `公共池未配置是服务端问题`() {
        val r = decode(
            """{"code":"fetch_account_unavailable","message":"取数账号未配置"}""",
        ) as CacheResult.Miss
        assertTrue(r.isServerFault())
    }

    @Test
    fun `公共池换票失败是服务端问题`() {
        val r = decode("""{"code":"fetch_account_st_failed"}""") as CacheResult.Miss
        assertTrue(r.isServerFault())
    }

    @Test
    fun `公共池建会话失败是服务端问题`() {
        val r = decode("""{"code":"fetch_account_cas_failed"}""") as CacheResult.Miss
        assertTrue(r.isServerFault())
    }

    @Test
    fun `服务端问题优先于缺票判定`() {
        // 真实场景：公共类型 + 校外形态 + 池没配好 → 后端回 fetch_account_unavailable，
        // 此时 mode/session 可能也像「缺票」。CourseRepository 必须先判 isServerFault，
        // 否则会把运维问题误弹成「请登录 WebVPN」，用户白折腾。
        val r = decode(
            """{"code":"fetch_account_unavailable","mode":"vpn","session":"unavailable"}""",
        ) as CacheResult.Miss

        assertTrue("必须先被识别为服务端问题", r.isServerFault())
        // 记录事实：这两个判定会同时为真，所以调用方的顺序不能反。
        assertTrue("此时缺票判定也会为真 —— 正因如此顺序才关键", r.needsWebVpnLogin())
    }

    @Test
    fun `普通 cache_miss 不是服务端问题`() {
        val r = decode("""{"code":"cache_miss"}""") as CacheResult.Miss
        assertFalse(r.isServerFault())
    }
}

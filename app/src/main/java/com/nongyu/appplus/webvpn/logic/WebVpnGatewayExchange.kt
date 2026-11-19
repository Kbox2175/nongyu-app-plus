package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.webvpn.model.WebVpnGateway

/**
 * 网关换票请求的**构造与解析**（纯逻辑，可纯 JVM 测，不碰网络）。
 *
 * ## 实测到的那一步（2026-11-19，浏览器直接抓）
 *
 * 统一身份认证（auth）换出 `st` 后，**网关**才是下发 `wengine_vpn_ticket` 的地方：
 *
 * ```
 * GET https://webvpn.sicau.edu.cn/login?cas_login=true&ticket=<st>&userId=<id>
 *   → Set-Cookie: wengine_vpn_ticketwebvpn_sicau_edu_cn=<token>
 * ```
 *
 * 实测该 URL 返回「WebVPN资源访问控制系统登录页面」，说明它确实是网关换票入口，
 * 而不是 auth 的页面。**这条链路完全不需要 WebView**：OkHttp 带 CookieJar 走一遍即可。
 *
 * ## 为什么要单独抽一层
 *
 * URL 的 query 顺序、`cas_login` 的字面量、cookie 名、以及「票怎么从 Set-Cookie 里
 * 取出来」全是最容易写错又最难排查的地方。抽成纯函数后可用单测钉死，
 * 并且与 UI 无关 —— 换票失败时能明确区分「URL 构造错」还是「网关拒绝」。
 */
object WebVpnGatewayExchange {

    /**
     * 构造网关换票 URL。
     *
     * @param st 统一身份认证换出的票据（`ST-...`）
     * @param userId 统一身份认证返回的 users[0].id
     */
    fun loginUrl(st: String, userId: String): String =
        "${WebVpnGateway.ORIGIN}/login?cas_login=true" +
            "&ticket=${urlEncode(st)}" +
            "&userId=${urlEncode(userId)}"

    /**
     * 从 `Set-Cookie` *头*（多行）里找出网关票的值。
     *
     * 与 [WebVpnTicket.extract] 的分工：
     * - [WebVpnTicket.extract] 处理的是 `CookieManager.getCookie()` 返回的**整条 cookie 串**
     *   （WebView 场景）；
     * - 本方法处理的是 HTTP 响应里的 `Set-Cookie` 头（纯 OkHttp 场景），
     *   格式是 `name=value; Path=/; Domain=...; HttpOnly`，需要先切掉属性段。
     *
     * @return 票值；没有或形态不合法返回 `null`
     */
    fun ticketFromSetCookie(setCookieHeaders: List<String>): String? {
        for (raw in setCookieHeaders) {
            // 每条 Set-Cookie 只取第一个 `;` 之前的部分：name=value
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            if (!name.startsWith(WebVpnGateway.TICKET_COOKIE_PREFIX, ignoreCase = true)) continue
            val value = pair.substring(eq + 1).trim()
            if (WebVpnTicket.looksLikeTicket(value)) return value
        }
        return null
    }

    /**
     * 判断换票响应是否**已经登录成功**。
     *
     * 网关有三个可辨识的结果：
     * 1. 拿到票（[ticketFromSetCookie] 非空）→ 成功；
     * 2. 票据非法 → 页面出现 `INVALID_TICKET` 文案（旧 RN 实测记录）；
     * 3. 其它 → 未知，交给上层重试。
     */
    fun looksLikeInvalidTicket(html: String?): Boolean =
        html?.contains("INVALID_TICKET", ignoreCase = true) == true

    /** 极简 percent-encoding：只编码会破坏 query 结构的字符。 */
    internal fun urlEncode(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when {
                ch.isLetterOrDigit() || ch in "-._~" -> append(ch)
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        val v = b.toInt() and 0xFF
                        append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
                    }
                }
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

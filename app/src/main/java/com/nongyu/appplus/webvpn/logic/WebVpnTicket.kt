package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.webvpn.model.WebVpnGateway

/**
 * 网关票的提取与形态校验（**纯逻辑，纯 JVM 可测**，不碰 `android.webkit`）。
 *
 * ## 票从哪来
 *
 * 网关票 `wengine_vpn_ticketwebvpn_sicau_edu_cn` 是 HttpOnly Cookie，`document.cookie`
 * 读不到；但 `android.webkit.CookieManager.getCookie(...)` 返回的是**整条 Cookie 头字符串**，
 * 里面包含它。所以 `ui/` 层只负责把那条字符串取出来交给本类，本类负责解析。
 *
 * ## 为什么形态校验要和后端口径一致
 *
 * 后端 `jiaowu/webvpn.go: LooksLikeWebVPNTicket` 会拒绝空串 / 含空白 / 超长 /
 * 含尖括号引号的值 —— 实测踩过「把 302 跳转页 HTML 当成 cookie 值交上来」的坑
 * （`docs/后端已就绪-前端对接须知.md` §3.1）。客户端先按同一口径过滤，
 * 能把明显不对的值挡在本地，不必白跑一次网络请求。
 *
 * ## ⚠️ 形态合法 ≠ 登录成功
 *
 * 匿名访问 `/login` 时网关也会下发一张 16 位**游客票**，长度和形态与已认证票完全一样。
 * 所以本类**不能**用来判断「用户是否已经登录」，只能判断「这个值能不能交给后端」。
 * 「游客票 vs 已认证票」由 `ui/` 层的「离开登录页」信号 + 用户点「我已完成登录」兜底，
 * 最终由后端代取结果裁决。
 */
object WebVpnTicket {

    /** 与后端 `LooksLikeWebVPNTicket` 完全一致的上下界。 */
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 512

    /**
     * 从 `CookieManager.getCookie(...)` 的原始串里提取网关票值。
     *
     * 典型串：`show_vpn=1; wengine_vpn_ticketwebvpn_sicau_edu_cn=1a2b3c4d; show_faq=0`
     *
     * - 按 `;` 切段，取「名字以 [WebVpnGateway.TICKET_COOKIE_PREFIX] 开头」的那一段；
     * - 只取第一个 `=` 之后的值（票值本身不含 `=`，但 Cookie 属性里可能有）；
     * - 有多个同名/同前缀时取**最后一个**（浏览器 Cookie 罐通常新值在后）。
     *
     * @return 票值；没找到或形态不合法时返回 `null`。**永不抛异常**（登录页 Cookie 千奇百怪）。
     */
    fun extract(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        var found: String? = null
        for (segment in cookieHeader.split(';')) {
            val part = segment.trim()
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            if (!name.startsWith(WebVpnGateway.TICKET_COOKIE_PREFIX, ignoreCase = true)) continue
            val value = part.substring(eq + 1).trim()
            if (looksLikeTicket(value)) found = value
        }
        return found
    }

    /**
     * 票值形态校验，逐条对齐后端 `LooksLikeWebVPNTicket`：
     *
     * 1. 长度 8..512；
     * 2. 每个字符都必须是可见 ASCII（0x21..0x7e）—— 排除空格与控制字符；
     * 3. 不得含 `<>"'`（HTML 片段 / 引号注入）。
     *
     * **不用长度判断有效性**：有效票与游客票同为 16 位十六进制，本地判不了。
     */
    fun looksLikeTicket(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim()
        if (v.length < MIN_LENGTH || v.length > MAX_LENGTH) return false
        for (c in v) {
            if (c.code < 0x21 || c.code > 0x7e) return false
        }
        if (v.any { it == '<' || it == '>' || it == '"' || it == '\'' }) return false
        return true
    }

    /**
     * 判断 WebView 当前 URL 是否**仍停在网关登录页**。
     *
     * 网关登录完成后一般落到 `/` 或编码后的资源路径；停在 `/login`（含
     * `/login?cas_login=true&ticket=...` 的 CAS 回调页）说明还没走完。
     * 这条信号用于把「匿名游客票」与「已认证票」分开，不单独作为成功判据。
     *
     * 相对 URL / 空串按「还在登录页」保守处理。
     */
    fun isStillOnLoginPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        if (!lower.contains(WebVpnGateway.COOKIE_HOST)) return true
        // 只看网关域下的 path：/login 开头即登录页（含 /login?...）
        val afterHost = lower.substringAfter(WebVpnGateway.COOKIE_HOST, missingDelimiterValue = "")
        return afterHost.startsWith("/login")
    }
}

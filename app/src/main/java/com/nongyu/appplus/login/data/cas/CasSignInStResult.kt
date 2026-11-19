package com.nongyu.appplus.login.data.cas

/**
 * 「统一身份认证登录后拿到 st」的结果（WebVPN 网关换票专用）。
 *
 * ## 为什么单开一个结果类型
 *
 * [CasLoginResult] 表达的是「换 App 登录用的 `go-admin-tgt`」，字段形状（[CasCredential]）
 * 被登录链路钉死。而换 **WebVPN 网关票**只需要 `st` + `userId`：
 *
 * ```
 * GET webvpn.sicau.edu.cn/login?cas_login=true&ticket=<st>&userId=<id>
 *   → Set-Cookie: wengine_vpn_ticketwebvpn_sicau_edu_cn=<token>
 * ```
 *
 * 两者诉求不同（一个要 tgt、一个要 st），硬塞进同一个结果类型只会让两边都别扭。
 */
sealed interface CasSignInStResult {

    /**
     * 成功。
     *
     * @param st 统一身份认证服务票据，交给网关换票用。**是活凭据，不要打印。**
     * @param userId 统一身份认证返回的用户 id（`users[0].id`）
     * @param callbackUrl 认证服务给的跳转地址（目前不需要，留作排查线索）
     */
    data class Success(
        val st: String,
        val userId: String,
        val callbackUrl: String? = null,
    ) : CasSignInStResult

    /**
     * 失败。
     *
     * @param message 可读信息；空串表示「弱网零提示」（与既有登录口径一致）
     */
    data class Failure(val message: String) : CasSignInStResult
}

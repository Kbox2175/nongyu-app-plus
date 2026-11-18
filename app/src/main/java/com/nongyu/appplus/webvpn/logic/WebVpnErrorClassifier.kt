package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.net.core.ApiErrorCode
import com.nongyu.appplus.webvpn.model.WebVpnTrigger

/**
 * 后端错误码 → 弹窗触发源的映射（**纯逻辑，纯 JVM 可测**）。
 *
 * ## 为什么只认这两个码
 *
 * `docs/specs/网络层.md` §3 与后端 `jiaowu/webvpnhandler.go: writeWebVPNError`
 * 定义得清清楚楚，客户端动作完全不同：
 *
 * | 码 | HTTP | 客户端动作 |
 * | --- | --- | --- |
 * | `webvpn_ticket_missing` | 409 | 去登录网关并 `POST` 交票 |
 * | `webvpn_ticket_expired` | 401 | **重新登录网关**后重交 |
 * | `cache_unavailable` | 503 | 功能未启用，可重试，**不该弹窗骚扰用户** |
 *
 * 所以这里**只**把前两个映射成触发源，其余（含 `cache_unavailable`、签名失败、
 * App 会话失效）一律返回 `null`。把 503 也弹成「请登录 WebVPN」是误报。
 *
 * ## ⚠️ `webvpn_ticket_expired` 是 401，但不是 App 会话失效
 *
 * `SessionInvalidRules.decide` 对 `/school/` + 401 只在 `code == session_expired`
 * 时返回 `KEEP_SESSION_SCHOOL_AUTH`，其余落保守的 `KEEP_SESSION_UNKNOWN` —— 两者都
 * **不清 App 登录态**。本类把它翻成「重新登录网关」，与后端注释「绝不能因此清掉 App 登录态」
 * 一致。
 *
 * ## 为什么把 `net/core/ApiErrorCode` 的常量引到 `webvpn/logic`
 *
 * 方向是 `webvpn → net`（业务域依赖网络契约常量），不是反过来。这样网络层不需要
 * 认识 WebVPN 弹窗，`NetHolder` 只暴露一个可选的「票失效回调」，依赖方向保持干净。
 */
object WebVpnErrorClassifier {

    /**
     * @param code 从错误响应体里解出的业务码（可能为 null / 未知字符串）
     * @param status HTTP 状态码。加这层是防止后端将来把某个码挪到别的状态码上
     *   而客户端仍旧误弹（`invalid_presence` 同码跨 400/401 的教训）。
     * @return 应当触发的 [WebVpnTrigger]；不该弹窗时返回 `null`。
     */
    fun classify(code: String?, status: Int): WebVpnTrigger? = when (code) {
        ApiErrorCode.WEBVPN_TICKET_MISSING ->
            if (status == 409) WebVpnTrigger.TICKET_MISSING else null

        ApiErrorCode.WEBVPN_TICKET_EXPIRED ->
            if (status == 401) WebVpnTrigger.TICKET_EXPIRED else null

        else -> null
    }

    /** 便捷判断：这个码是不是 WebVPN 网关票相关（用于拦截器快速筛掉无关响应）。 */
    fun isTicketErrorCode(code: String?): Boolean =
        code == ApiErrorCode.WEBVPN_TICKET_MISSING ||
            code == ApiErrorCode.WEBVPN_TICKET_EXPIRED
}

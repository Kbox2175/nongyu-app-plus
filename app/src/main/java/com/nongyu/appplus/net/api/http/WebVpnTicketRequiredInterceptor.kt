package com.nongyu.appplus.net.api.http

import com.nongyu.appplus.net.core.NongyuLogger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 网关票失效/缺失的**转发**拦截器。
 *
 * ## 为什么 net 层不认识 webvpn
 *
 * 依赖方向是 `webvpn → net`（业务域依赖网络契约），反过来不行。所以本拦截器
 * **不判断**「哪个码该弹窗」，只做两件最笨的事：
 *
 * 1. 只看 `401` / `409` 响应（后端网关票两个码正好落在这两个状态码上）；
 * 2. 从错误体里抠出 `code`，连同状态码一起交给注入进来的回调。
 *
 * 「`webvpn_ticket_missing` → 弹窗、`cache_unavailable` → 不弹」这种业务判定
 * 住在 `webvpn/logic/WebVpnErrorClassifier`，由 UI 侧在注入回调时接上
 * （`NetHolder.setOnWebVpnTicketRequired`）。这样网络层零 WebVPN 知识，
 * 后端新增错误码时也不用改这里。
 *
 * ## 与 SessionInvalidInterceptor 的关系
 *
 * 两者独立、互不干扰：那个决定「要不要清 App 登录态」，这个只负责「要不要提醒用户
 * 重新登录学校网关」。`webvpn_ticket_expired` 是 401，但 `SessionInvalidRules`
 * 对它落保守的 `KEEP_SESSION_UNKNOWN`，**不会清登录态**（见规格 §5）。
 *
 * ## 绝不消费响应体
 *
 * 用 `peekBody`（与 `SessionInvalidInterceptor` 同一套做法），否则下游拿不到错误体。
 * 回调抛异常一律吞掉并记日志 —— 一次 UI 调度失败不能把这次请求弄崩。
 *
 * @param onTicketRequired 注入的回调：`(code, status)`。为 null 时本拦截器不装配。
 */
class WebVpnTicketRequiredInterceptor(
    private val onTicketRequired: (code: String?, status: Int) -> Unit,
    private val logger: NongyuLogger = NongyuLogger.Noop,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // 只关心这两个状态码，避免为每个 200/404 都去 peek body
        if (response.code != 401 && response.code != 409) return response

        val code = peekErrorCode(response)
        try {
            onTicketRequired(code, response.code)
        } catch (e: RuntimeException) {
            logger.log(NongyuLogger.Level.WARN, "WebVPN 票失效回调异常", e)
        }
        return response
    }

    /** 从错误响应体里抠出 `code`。解析失败返回 `null`（业务侧会保守地不弹窗）。 */
    private fun peekErrorCode(response: Response): String? = try {
        val body = response.peekBody(MAX_PEEK_BYTES).string()
        if (body.isBlank()) {
            null
        } else {
            Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        logger.log(NongyuLogger.Level.DEBUG, "读取错误码失败：${e::class.simpleName}")
        null
    }

    private companion object {
        const val MAX_PEEK_BYTES = 8L * 1024
    }
}

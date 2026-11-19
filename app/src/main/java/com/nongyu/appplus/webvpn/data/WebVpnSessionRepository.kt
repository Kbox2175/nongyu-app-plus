package com.nongyu.appplus.webvpn.data

import android.content.Context
import com.nongyu.appplus.net.NetHolder
import com.nongyu.appplus.net.model.WebVpnSubmitRequest
import com.nongyu.appplus.webvpn.logic.WebVpnTicket
import com.nongyu.appplus.webvpn.model.WebVpnSubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对接后端 WebVPN 网关票层的仓储（`docs/specs/网络层.md` §3）。
 *
 * ## 只做「交票」这一件事
 *
 * 后端 `POST /school/webvpn-session` 收 `{"ticket":"..."}`，按 userID 加密保管，
 * 之后代取教务页面。查询端点只回 `present`/`expiresAt`，**永不回显票值**，
 * 所以这里没有「把票取回来」的方法 —— 票值在我们这一侧**只进不出**。
 *
 * ## 票值安全边界（与后端 `webvpn.go` 注释一致）
 *
 * - 票只在内存参数里流动，不落盘、不进 SharedPreferences、不进埋点；
 * - **绝不打印票值**：失败信息只带 HTTP 状态码，不带请求体；
 * - 交给 Retrofit 前先按 [WebVpnTicket.looksLikeTicket] 做形态校验，
 *   把明显不合法的值挡在本地（后端也会校验，这里省一次网络往返）。
 *
 * 注：`SafeLoggingInterceptor` 的 `SENSITIVE_JSON_KEYS` 已含 `ticket`，
 * 且 `Cookie` 头整体脱敏；本类仍额外做形态校验，两道防线互不替代。
 *
 * ## 装配
 *
 * 经 `NetHolder.webVpn(context)` 拿 `WebVpnApi` —— 与 `cache()` 同构，
 * 复用同一个 Retrofit / OkHttp 连接池（含 NY1 签名与 Bearer 拦截器）。
 * 构造 Repository **不触发**网络装配（`by lazy`），首次 [submit] 才真正建 client，
 * 因此不违反 ADR-0006「冷启动不碰网络」。
 */
class WebVpnSessionRepository(
    private val context: Context,
) {

    /**
     * 把登录网关后拿到的票提交给后端保管。
     *
     * @param ticket `wengine_vpn_ticket*` 的值。**调用方不要把它记到任何日志里。**
     * @return 成功带后端给的 `expiresAt`（RFC3339）；失败带**不含票值**的可读信息。
     */
    suspend fun submit(ticket: String): WebVpnSubmitResult = withContext(Dispatchers.IO) {
        if (!WebVpnTicket.looksLikeTicket(ticket)) {
            return@withContext WebVpnSubmitResult.Failure("网关票形态不合法，请重新登录 WebVPN")
        }

        val result = runCatching {
            val api = NetHolder.webVpn(context.applicationContext)
            val response = api.submit(WebVpnSubmitRequest(ticket = ticket.trim()))
            if (response.isSuccessful) {
                WebVpnSubmitResult.Success(response.body()?.expiresAt)
            } else {
                // 只带状态码，绝不带 errorBody（后端可能回显请求上下文）
                WebVpnSubmitResult.Failure("提交网关票失败：HTTP ${response.code()}")
            }
        }

        result.getOrElse { e ->
            WebVpnSubmitResult.Failure(
                when (e) {
                    is kotlinx.coroutines.CancellationException -> throw e
                    else -> "提交网关票失败：${e::class.simpleName ?: "网络异常"}"
                },
            )
        }
    }
}

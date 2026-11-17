package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.model.WebVpnClearDto
import com.nongyu.appplus.net.model.WebVpnStatusDto
import com.nongyu.appplus.net.model.WebVpnSubmitRequest
import com.nongyu.appplus.net.model.WebVpnSubmitDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * WebVPN 网关票（后端 `internal/school/jiaowu/webvpnhandler.go`，4 条路由）。
 *
 * ## 全部需要 Bearer
 * 身份只来自认证中间件（`requireUserID`），无票即 401。
 *
 * ## ⚠️ 票值只在一个方向流动
 * - **提交**时客户端把 `wengine_vpn_ticket` 交给后端保管；
 * - **查询时后端只回 `present` / `expiresAt`，永不回显票值**。
 *   这是后端的刻意安全边界 —— 不要试图"查回来再用"，也不要把它当取票接口。
 *
 * ## 为什么需要这层
 * 校外形态下后端拿不到网关票（票只能由客户端自己登录网关产生），
 * 所以由客户端提交、后端保管并代取教务页面。
 */
interface WebVpnApi {

    /**
     * `POST /school/webvpn-session` —— 提交网关票。
     *
     * @return `{"present":true,"expiresAt":"RFC3339"}`
     */
    @POST("school/webvpn-session")
    suspend fun submit(@Body body: WebVpnSubmitRequest): Response<WebVpnSubmitDto>

    /**
     * `GET /school/webvpn-session` —— 查询是否已保管。
     *
     * @return `{"present":bool}`；`present=true` 时额外带 `expiresAt`。
     *   **永不回票值。**
     */
    @GET("school/webvpn-session")
    suspend fun status(): Response<WebVpnStatusDto>

    /** `DELETE /school/webvpn-session` —— 清除（登出、或客户端发现票失效时）。 */
    @DELETE("school/webvpn-session")
    suspend fun clear(): Response<WebVpnClearDto>

    /**
     * `GET /school/jiaowu/cache/:dataType/fetch` —— 用已保管的票**校内/校外代取**教务页面。
     *
     * 成功时与 `/school/fetch` 同形：原样透出上游状态码与响应体（不解析）。
     *
     * 失败时给**客户端能据此行动**的错误码（见 [com.nongyu.appplus.net.core.ApiErrorCode]）：
     * `webvpn_ticket_missing`(409) / `webvpn_ticket_expired`(401) / `cache_unavailable`(503)。
     *
     * @return 原始二进制响应体（教务页面是 HTML / GBK，不在这里解码）
     */
    @GET("school/jiaowu/cache/{dataType}/fetch")
    suspend fun fetch(@Path("dataType") dataType: String): Response<ResponseBody>
}

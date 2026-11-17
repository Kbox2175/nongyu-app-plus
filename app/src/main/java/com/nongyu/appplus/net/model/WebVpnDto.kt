package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable

/**
 * WebVPN 网关票 DTO（后端 `internal/school/jiaowu/webvpnhandler.go`）。
 *
 * ## ⚠️ 票值只进不出
 * `POST` 提交票值；`GET` 只回 `present` / `expiresAt`，**永不回票值**。
 * 所以这里没有任何"DTo 带 ticket 字段"的读取侧设计 —— 只能提交，不能查回。
 */

/**
 * `POST /school/webvpn-session` 的请求体。
 *
 * 只收 `ticket` 一个字段：身份永远来自认证中间件，后端**绝不接受请求体里的 userID**
 * （否则任何人都能给别人账户写票）。
 *
 * @param ticket `wengine_vpn_ticket` 的值。**不要打印、不要持久化、不要进埋点。**
 */
@Serializable
data class WebVpnSubmitRequest(
    val ticket: String? = null,
)

/** `POST /school/webvpn-session` 的成功响应：`{"present":true,"expiresAt":"RFC3339"}`。 */
@Serializable
data class WebVpnSubmitDto(
    val present: Boolean? = null,
    /** RFC3339 字符串。 */
    val expiresAt: String? = null,
)

/**
 * `GET /school/webvpn-session` 的响应：`{"present":bool,"expiresAt"?:string}`。
 *
 * `present=false` 时后端**不返回** `expiresAt` 字段（`omitempty`），所以它必须可空。
 */
@Serializable
data class WebVpnStatusDto(
    val present: Boolean? = null,
    /** 仅 `present=true` 时出现。 */
    val expiresAt: String? = null,
)

/** `DELETE /school/webvpn-session` 的响应：`{"cleared":true}`。 */
@Serializable
data class WebVpnClearDto(
    val cleared: Boolean? = null,
)

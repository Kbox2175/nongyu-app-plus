package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.webvpn.logic.WebVpnErrorClassifier
import com.nongyu.appplus.webvpn.model.WebVpnTrigger

/**
 * 把网络层回调（`code`, `status`）翻译成弹窗调度。
 *
 * 这是 `net → UI → webvpn` 的接缝：网络层只把码转发出来（见
 * `net/api/http/WebVpnTicketRequiredInterceptor`），**业务判定**在这里做：
 * [WebVpnErrorClassifier] 决定「这个码该不该弹、弹哪种触发源」，
 * [WebVpnDialogScheduler] 决定「弹不弹得出来」（同意态 / 去重闸门）。
 *
 * 拆出这一层而不是把判定塞进拦截器，是为了让：
 * - 网络层零 WebVPN 知识（依赖方向干净）；
 * - 错误码 → 触发源的映射能纯 JVM 单测（[WebVpnErrorClassifier]）；
 * - 「不同意」的抑制逻辑集中在调度器，不在网络回调里散落。
 *
 * 由 `MainActivity.onCreate` 注入：
 * ```
 * NetHolder.setOnWebVpnTicketRequired(WebVpnErrorDispatcher::dispatch)
 * ```
 * 注入发生在首帧之前，但**只是存一个函数引用**，不装配网络、不发请求（ADR-0006 安全）。
 */
object WebVpnErrorDispatcher {

    /**
     * @param code 错误体里的业务码（可能为 null / 未知）
     * @param status HTTP 状态码
     * @return 实际投递的触发源；不该弹窗或被闸门拦下时返回 `null`
     */
    fun dispatch(code: String?, status: Int): WebVpnTrigger? {
        val trigger = WebVpnErrorClassifier.classify(code, status) ?: return null
        WebVpnDialogScheduler.request(trigger)
        return trigger
    }
}

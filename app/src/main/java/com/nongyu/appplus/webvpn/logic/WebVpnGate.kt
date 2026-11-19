package com.nongyu.appplus.webvpn.logic

import com.nongyu.appplus.webvpn.model.WebVpnConsent
import com.nongyu.appplus.webvpn.model.WebVpnGateDecision
import com.nongyu.appplus.webvpn.model.WebVpnTrigger

/**
 * 「这次调度要不要真的弹窗」的闸门判定（**纯逻辑，纯 JVM 可测**）。
 *
 * ## 为什么要有闸门，而不是每次调度都弹
 *
 * 需求同时要求「登录后生效」「被其他调度后生效」与「有不同意登录的功能」——
 * 如果后端每回一次 `webvpn_ticket_missing` 都无条件弹一次，用户点了「不同意」
 * 之后照样会被反复打扰，等于「不同意」形同虚设。闸门把三件事收在一处：
 *
 * 1. **同意态**：`DECLINED` 抑制一切自动调度；[WebVpnTrigger.MANUAL] 例外（显式操作）。
 * 2. **登录后只弹一次**：[WebVpnTrigger.SIGNED_IN] 每进程只弹一次，避免 `NongyuApp`
 *    重组（横竖屏、主题变更）导致反复弹。
 * 3. **去重**：已有请求在显示时不叠第二层弹窗。
 *
 * 判定顺序是**有意的**：`MANUAL` 优先于同意态，否则用户没法主动恢复会话；
 * 去重优先于同意态写日志，否则一次点击会打两条日志。
 *
 * @param trigger 本次触发源
 * @param consent 当前同意态
 * @param signedInShown 本会话是否已经因 `SIGNED_IN` 弹过
 * @param active 当前是否已有弹窗正在显示
 */
object WebVpnGate {

    fun decide(
        trigger: WebVpnTrigger,
        consent: WebVpnConsent,
        signedInShown: Boolean,
        active: Boolean,
    ): WebVpnGateDecision = when {
        // ① 已有弹窗：无论什么来源都不叠层（包括 MANUAL，用户看到的就是当前这一层）
        active -> WebVpnGateDecision.SKIP_ALREADY_ACTIVE

        // ② 显式手动入口：绕过同意态，永远可弹
        trigger == WebVpnTrigger.MANUAL -> WebVpnGateDecision.SHOW

        // ③ 「不同意」抑制自动调度
        consent == WebVpnConsent.DECLINED -> WebVpnGateDecision.SKIP_DECLINED

        // ④ 登录成功后的自动调度每进程只弹一次
        trigger == WebVpnTrigger.SIGNED_IN && signedInShown ->
            WebVpnGateDecision.SKIP_ALREADY_SHOWN

        // ⑤ 其余（票缺失 / 票过期 / 首次登录成功）正常弹
        else -> WebVpnGateDecision.SHOW
    }

    /** 便捷判断：是否应当把请求投递到 UI。 */
    fun shouldShow(decision: WebVpnGateDecision): Boolean =
        decision == WebVpnGateDecision.SHOW
}

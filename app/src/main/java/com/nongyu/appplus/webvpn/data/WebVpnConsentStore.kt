package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.webvpn.model.WebVpnConsent

/**
 * 「是否同意登录 WebVPN」的会话级偏好存储。
 *
 * ## 为什么是进程内单例、不落盘
 *
 * 见 `docs/specs/WebVPN登录弹窗.md` §3：同意态刻意**不持久化**。
 * 一旦用户点错「不同意」并被永久记住，后端的 `webvpn_ticket_missing` 就再也
 * 无法把弹窗调度起来，课表刷新会永久失效且无从恢复。会话级（进程内）意味着：
 *
 * - 本次进程内：点了「不同意」就不再被自动调度反复打扰；
 * - 下次冷启动：重新问一次，给用户一次改主意的机会；
 * - 设置面板的手动入口**任何时候**都可用（不受本存储约束，由 `WebVpnGate` 保证）。
 *
 * `@Volatile` 保证 OkHttp 拦截器线程写、`NongyuApp` 主线程读之间的可见性
 * （`WebVpnDialogScheduler` 同样可能被后台线程调用）。
 */
object WebVpnConsentStore {

    @Volatile
    private var consent: WebVpnConsent = WebVpnConsent.UNDECIDED

    fun read(): WebVpnConsent = consent

    fun set(value: WebVpnConsent) {
        consent = value
    }

    /** 仅供测试：把状态复位。 */
    internal fun reset() {
        consent = WebVpnConsent.UNDECIDED
    }
}

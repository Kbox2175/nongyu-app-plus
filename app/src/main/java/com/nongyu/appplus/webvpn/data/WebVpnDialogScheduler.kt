package com.nongyu.appplus.webvpn.data

import com.nongyu.appplus.webvpn.logic.WebVpnGate
import com.nongyu.appplus.webvpn.model.WebVpnConsent
import com.nongyu.appplus.webvpn.model.WebVpnGateDecision
import com.nongyu.appplus.webvpn.model.WebVpnRequest
import com.nongyu.appplus.webvpn.model.WebVpnTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WebVPN 弹窗的**全局调度接缝**（需求「被其他调度后生效」的唯一落点）。
 *
 * ## 为什么是单例 + StateFlow
 *
 * 弹窗挂在 `NongyuApp` 根节点（最顶层），但调度方散落在四处：
 * `NongyuApp`（登录后）、OkHttp 拦截器（后端 409/401，后台线程）、设置面板（主线程）、
 * 以及未来任何模块。让这些调度方都持有 `MainShell` 的局部状态是不现实的；
 * 用一个进程内单例 + `StateFlow` 做「当前请求」，根节点 `collectAsState` 即可响应，
 * 且不依赖任何页面内部状态、不引 DI 框架。
 *
 * ## 线程安全
 *
 * OkHttp 拦截器在后台线程调用 [request]，登录后调度在主线程。所有状态读写都放在
 * `synchronized(this)` 里；`MutableStateFlow.value =` 本身线程安全，但闸门判定 +
 * 标记 `signedInShown` 必须是**一次原子操作**，否则并发的两个调度会同时通过闸门。
 *
 * ## 请求生命周期
 *
 * ```
 * request(trigger) ── 闸门 ──▶ currentRequest = Request(nonce++)
 *                                   │
 *                     UI 观察并渲染  │
 *                                   ▼
 *       同意成功 → complete() / 不同意 → decline() / 失败关闭 → dismiss()
 *                                   │
 *                                   ▼
 *                       currentRequest = null（active 变 false）
 * ```
 *
 * `nonce` 自增保证「同触发源连续两次调度」也是两个不等的 [WebVpnRequest]，
 * 否则 `StateFlow` 的相等性判断会把第二次当成没变化，弹窗永远弹不出来。
 */
object WebVpnDialogScheduler {

    private val _currentRequest = MutableStateFlow<WebVpnRequest?>(null)

    /** 当前应当显示的弹窗请求；`null` = 无弹窗。UI 用 `collectAsState` 观察。 */
    val currentRequest: StateFlow<WebVpnRequest?> = _currentRequest.asStateFlow()

    private var nonce: Long = 0L

    /** 登录成功后的自动调度是否已经弹过（每进程一次）。 */
    private var signedInShown: Boolean = false

    /**
     * 投递一次弹窗调度。
     *
     * 可被任意线程调用（OkHttp 拦截器也在用）。闸门判定与状态更新在同一把锁内完成。
     *
     * @return 闸门结论，便于调用方写日志 / 决定是否做后续动作。
     */
    fun request(trigger: WebVpnTrigger): WebVpnGateDecision = synchronized(this) {
        val decision = WebVpnGate.decide(
            trigger = trigger,
            consent = WebVpnConsentStore.read(),
            signedInShown = signedInShown,
            active = _currentRequest.value != null,
        )
        if (decision != WebVpnGateDecision.SHOW) return@synchronized decision

        if (trigger == WebVpnTrigger.SIGNED_IN) signedInShown = true
        _currentRequest.value = WebVpnRequest(trigger = trigger, nonce = ++nonce)
        decision
    }

    /** 用户点「不同意」：记下偏好并关闭弹窗。之后自动调度被抑制，手动入口仍可用。 */
    fun decline() {
        WebVpnConsentStore.set(WebVpnConsent.DECLINED)
        _currentRequest.value = null
    }

    /** 用户同意并成功提交票给后端：记下同意态并关闭弹窗。 */
    fun complete() {
        WebVpnConsentStore.set(WebVpnConsent.AGREED)
        _currentRequest.value = null
    }

    /**
     * 关闭弹窗但**不表达同意**（如提交失败、用户点遮板退出）。
     * 不改同意态：下次同样的自动调度还能再弹出来。
     */
    fun dismiss() {
        _currentRequest.value = null
    }

    /** 当前是否有弹窗正在显示（供闸门 / UI 判断）。 */
    fun isActive(): Boolean = _currentRequest.value != null

    /** 仅供测试：复位全部进程内状态。 */
    internal fun reset() {
        synchronized(this) {
            _currentRequest.value = null
            nonce = 0L
            signedInShown = false
        }
        WebVpnConsentStore.reset()
    }
}

package com.nongyu.appplus.webvpn.model

/**
 * WebVPN 登录弹窗的纯数据模型（ADR-0010）。
 *
 * ## 硬约束（ADR-0008 / `docs/specs/界面层.md` §3）
 * `model/` 与 `logic/` **禁止** import `android.*` 与 `androidx.compose.*`，
 * 这样弹窗闸门、票提取、错误码分流都能在纯 JVM 单测里覆盖，不需要真机。
 *
 * 本文件只放数据，不放任何行为；行为分别在 `logic/`（纯判定）与 `data/`（调度/存储/网络）。
 */

/**
 * 弹窗触发源 —— 「谁把弹窗调度起来的」。
 *
 * 需求原文「这个弹窗在登录后或者被其他调度后生效」拆成四个来源：
 * - [SIGNED_IN]：登录中心 → 主界面（登录成功）后自动调度；
 * - [TICKET_MISSING]：后端代取回 `409 webvpn_ticket_missing`（没交过票）；
 * - [TICKET_EXPIRED]：后端代取回 `401 webvpn_ticket_expired`（票失效，要重新登录网关）；
 * - [MANUAL]：设置面板里用户显式点「登录 / 刷新 WebVPN」。
 *
 * [MANUAL] 是唯一**显式**触发源：即使此前点过「不同意」，也仍然要能弹出来，
 * 否则用户将永远无法主动恢复 WebVPN 会话。其余三个都是自动调度，受同意态约束。
 */
enum class WebVpnTrigger {
    SIGNED_IN,
    TICKET_MISSING,
    TICKET_EXPIRED,
    MANUAL,
    ;

    /** 是否属于「自动调度」（会被「不同意」抑制）。 */
    val automatic: Boolean get() = this != MANUAL
}

/**
 * 一次弹窗请求。
 *
 * @param trigger 触发源，决定副标题文案与闸门行为
 * @param nonce 自增序号：同一个触发源连续两次也能区分成两个独立请求
 *   （`data class` 相等性会让 `StateFlow` 把「同样的值」当成没变化，弹窗就再也弹不出来）。
 */
data class WebVpnRequest(
    val trigger: WebVpnTrigger,
    val nonce: Long,
)

/**
 * 「是否同意登录 WebVPN」的会话级偏好。
 *
 * 刻意**不持久化**：这是一次进程会话内的选择。新一次冷启动会重新问一次，
 * 否则用户一旦点错「不同意」，后端 `webvpn_ticket_missing` 将永远无法恢复课表刷新。
 */
enum class WebVpnConsent {
    /** 还没表达过（初始态）。 */
    UNDECIDED,

    /** 已同意（登录成功或用户点了「同意登录」）。 */
    AGREED,

    /** 已拒绝：本会话内抑制一切自动调度，但 [WebVpnTrigger.MANUAL] 仍可弹出。 */
    DECLINED,
}

/**
 * 弹窗闸门判定结果（`logic/WebVpnGate` 的输出）。
 *
 * 单独定义而不是返回一个 `Boolean`：调用方（调度器）需要知道「为什么没弹」，
 * 才能写日志、决定是否标记 `SIGNED_IN` 已处理。
 */
enum class WebVpnGateDecision {
    /** 应当弹出。 */
    SHOW,

    /** 用户已拒绝，自动调度被抑制。 */
    SKIP_DECLINED,

    /** 登录后的自动调度本会话已弹过一次。 */
    SKIP_ALREADY_SHOWN,

    /** 已有请求正在显示，不重复弹（去重）。 */
    SKIP_ALREADY_ACTIVE,
}

/** 弹窗内 WebView 登录的阶段。 */
enum class WebVpnLoginPhase {
    /** 未开始：只显示说明与「同意登录 / 不同意」双按钮。 */
    IDLE,

    /** WebView 正在加载学校网关登录页。 */
    LOADING,

    /** 已检测到登录态、正在把票提交给后端。 */
    SUBMITTING,

    /** 提交失败（网络/后端错误），保留 WebView 供重试。 */
    FAILED,

    /** 提交成功，即将关闭。 */
    DONE,
}

/** 弹窗对用户可见的状态。 */
data class WebVpnDialogUiState(
    val phase: WebVpnLoginPhase = WebVpnLoginPhase.IDLE,
    /** 失败提示（不为空时显示在卡片里）。票值**永不**出现在这里。 */
    val errorText: String? = null,
    /** 登录页是否已离开 `/login`（用于把「游客票」与「已认证票」区分开）。 */
    val leftLoginPage: Boolean = false,
)

/**
 * 提交网关票给后端的结果。
 *
 * 只回「成没成 + 可读信息」，**永不**携带票值。
 */
sealed interface WebVpnSubmitResult {
    data class Success(val expiresAt: String?) : WebVpnSubmitResult
    data class Failure(val message: String) : WebVpnSubmitResult
}

/**
 * 网关常量（与后端 `nongyu-backend-plus/internal/school/jiaowu/webvpn.go`、
 * 旧 RN 探索文档 `docs/nongyu-rn-app/tech/WebVPN探索.md` 对齐）。
 *
 * 这些是**学校侧**常量，不是我方后端地址，所以放在 `webvpn/model` 而不是 `net/`。
 */
object WebVpnGateway {
    /** 网关根域（`https://` 前缀 + host）。 */
    const val ORIGIN = "https://webvpn.sicau.edu.cn"

    /** 登录页入口：未登录访问会 302 到学校 CAS /wui/ 登录 SPA。 */
    const val LOGIN_URL = "$ORIGIN/login"

    /** 网关票 Cookie 名前缀。实测全名是 `wengine_vpn_ticketwebvpn_sicau_edu_cn`。 */
    const val TICKET_COOKIE_PREFIX = "wengine_vpn_ticket"

    /** CookieManager 取票时使用的域。 */
    const val COOKIE_HOST = "webvpn.sicau.edu.cn"
}

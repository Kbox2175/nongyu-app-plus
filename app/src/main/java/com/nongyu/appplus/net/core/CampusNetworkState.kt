package com.nongyu.appplus.net.core

/**
 * 校内 / 校外的**当前判定状态**（纯逻辑，零 Android 依赖，纯 JVM 可测）。
 *
 * ## 为什么要有这一层
 * 「是否在校内」的判定结果需要被两处用到：
 * 1. [com.nongyu.appplus.net.api.http.CampusNetworkInterceptor] 挂
 *    `X-Nongyu-Campus-Network` 请求头；
 * 2. 上报 `POST /school/presence`。
 *
 * 判定过程本身要碰 Android（`ConnectivityManager` / 网络请求），但**「判定结果是什么、
 * 什么时候该挂头、什么时候该默认不发」这一套决策是纯逻辑**，抽出来才能用单测钉住。
 *
 * ## 三个值，不是一个 boolean
 * 这里刻意用 [Unknown] 而不是把「还没探过」当成「校外」：
 * - [Unknown] → **不发** `X-Nongyu-Campus-Network` 头，让后端用它自己的默认值。
 *   这是维护者拍板的兜底：探不通时不冒充结论。
 * - [Direct] → 发 `direct`
 * - [Vpn] → 发 `vpn`
 *
 * 把 Unknown 和 Vpn 混为一谈会造成「探测失败 = 谎称在校外」，
 * 而校外的地址形态是需要额外条件的（要网关票），误判会连带一串失败。
 *
 * ## 与后端 Mode 的对应
 * 后端 `presence.Mode` 只有 `direct` / `vpn` 两个值（见
 * `internal/school/presence/presence.go`），所以 [Unknown] **只能存在于客户端**，
 * 永远不会被上报出去。
 */
object CampusNetworkState {

    /** 判定结果。 */
    enum class Presence {
        /** 还没探出结论（或探测失败）。**不挂头**。 */
        Unknown,

        /** 校内 / 可直连教务。挂 `direct`。 */
        Direct,

        /** 校外。挂 `vpn`。 */
        Vpn,
    }

    /**
     * 当前判定结果。
     *
     * `@Volatile`：探测在后台线程写，拦截器在 OkHttp 分发线程读，必须可见。
     * 用一个对象持有而不是散落的 `var`，是为了让「谁在改它」只有一个入口。
     */
    @Volatile
    var current: Presence = Presence.Unknown
        private set

    /** 更新判定结果。探测完成 / 网络切换时调用。 */
    fun update(presence: Presence) {
        current = presence
    }

    /** 供测试复位。 */
    fun reset() {
        current = Presence.Unknown
    }

    /**
     * 挂到 `X-Nongyu-Campus-Network` 头上的值；`null` 表示**不要挂这个头**。
     *
     * 这是本对象最关键的决策点：**只有明确知道的才挂**，
     * [Presence.Unknown] 一律返回 `null`（让后端用它自己的默认值）。
     *
     * 后端 `AccessModeFromHeader` 只认 `direct` / `vpn`，其它值会被忽略并回落 `vpn`
     * —— 所以这里绝不返回空串或占位值。
     */
    fun headerValue(): String? = when (current) {
        Presence.Direct -> HEADER_VALUE_DIRECT
        Presence.Vpn -> HEADER_VALUE_VPN
        Presence.Unknown -> null
    }

    /**
     * 是否需要向后端上报。
     *
     * [Presence.Unknown] 不上报 —— 与「不发头」同一口径。
     */
    fun shouldReport(): Boolean = current != Presence.Unknown

    /** 与后端 `jiaowu.AccessDirect` 逐字一致。 */
    const val HEADER_VALUE_DIRECT = "direct"

    /** 与后端 `jiaowu.AccessVPN` 逐字一致。 */
    const val HEADER_VALUE_VPN = "vpn"

    /** 请求头名，与后端 `jiaowu.HeaderCampusNetwork` 逐字一致。 */
    const val HEADER_NAME = "X-Nongyu-Campus-Network"
}

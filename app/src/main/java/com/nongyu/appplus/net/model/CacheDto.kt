package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 回传缓存子域的 DTO（规格 §3.6）。
 *
 * ## ⚠️ GET 端点有两条不同的 200 形状
 * 后端 `cacheGetHandler`：
 * - **命中** → `200` + [CacheEnvelope]
 * - **未命中** → `200` + [CacheMissDto]（`{"code":"cache_miss", ...}`，**不是 404**）
 *
 * 所以「2xx 就取 body」这条常规规则在这里不成立。实现上统一声明成
 * [CacheGetResponse]（字段全可空的并集），再用 [CacheGetResponse.discriminate]
 * 判别，避免调用点各自猜。
 */

/**
 * GET `/school/jiaowu/cache/:dataType` 的**统一响应容器**：命中与未命中字段的并集。
 *
 * 字段全部可空，因为任一形状都只填其中一部分。
 * 不要给任何一个字段写非空类型 —— 后端 `success` 用 `omitempty`、`payload`
 * 可能为 `null`，缺字段是常态。
 *
 * @param payload 命中时的业务数据，**原始 JSON 透传**（数组 / 对象 / null 三种形态都合法）。
 *   用 [JsonElement] 承接而不是 `String`：后端给的是 JSON 值，不是被字符串化的 JSON 文本。
 * @param code 未命中/错误时才有值（如 `cache_miss`）。**它是判别依据**。
 */
@Serializable
data class CacheGetResponse(
    val payload: JsonElement? = null,
    val success: Boolean? = null,
    val schemaVersion: Int? = null,
    val fetchedAt: String? = null,
    val stale: Boolean? = null,
    val code: String? = null,
    val message: String? = null,
    val address: String? = null,
    // ---- 2026-11-16 起：未命中时下发的「直连取数配方」----
    // 客户端**不自己填**地址 / 方法 / Cookie / 解析器名，全部照后端给的值执行。
    val target: String? = null,
    val method: String? = null,
    val cookies: List<IssuedCookie>? = null,
    val parser: String? = null,
    val session: String? = null,
    // ---- 2026-11-16 之后（后端 16ea6b4）：缺入参类型补的两个字段 ----
    /** 缺哪个入参（如「教室名关键词（bj）」）。只有缺入参的 4 类有值。 */
    val needs: String? = null,
    /** 缺入参时仍可用的骨架路径（不带 query），客户端自己拼。 */
    val basePath: String? = null,
    /**
     * 本次下发的是哪种地址形态：`direct`（校内直连）/ `vpn`（校外经网关编码）。
     *
     * 由后端按请求头 `X-Nongyu-Campus-Network` 决定。客户端用它区分「缺网关票」
     * 与「缺教务会话」—— 两者该弹的窗口不同。
     */
    val mode: String? = null,
    // ---- 2026-11-18（后端 9fc5219）：校外形态明确带回网关票状态 ----
    /**
     * WebVPN 网关票的**本地状态**。只在 `mode=vpn`（校外）时出现。
     *
     * ⚠️ 后端诚实边界：`cache_miss` 零出站，所以 `status=ready` 只表示
     * 「本地保管着一张未被判死的票」，**不是**「网关保证放行」。
     * 真正判死仍靠后续请求遇到的 302。
     */
    val webvpn: WebVpnGateDto? = null,
) {
    /** 判别这次 200 到底是命中还是未命中/错误。**只读 [code]，不猜其它字段。 */
    fun discriminate(): CacheResult = when {
        !code.isNullOrEmpty() -> CacheResult.Miss(
            code = code,
            message = message,
            address = address,
            target = target,
            method = method,
            cookies = cookies.orEmpty(),
            parser = parser,
            session = session,
            needs = needs,
            basePath = basePath,
            mode = mode,
            webvpn = webvpn,
        )

        else -> CacheResult.Hit(
            envelope = CacheEnvelope(
                payload = payload,
                success = success,
                schemaVersion = schemaVersion,
                fetchedAt = fetchedAt,
                stale = stale,
            ),
        )
    }
}

/**
 * 后端下发的教务会话 Cookie（`ASPSESSIONID*`）。
 *
 * ⚠️ 这是**本人**的教务会话，只对 `jiaowu.sicau.edu.cn` 有效，
 * 贴到 WebVPN 编码地址上不会登录。不要持久化、不要打印。
 */
@Serializable
data class IssuedCookie(
    val name: String? = null,
    val value: String? = null,
)

/**
 * WebVPN 网关票的**本地状态**（后端 `WebVPNGateResult`，2026-11-18 `9fc5219` 起随
 * `cache_miss` 下发）。
 *
 * ## 为什么需要这个字段
 * 原先校外形态只回 `session=client_side`，客户端**分不清**「还没登录网关」和
 * 「已登录且票正常」—— 两者响应完全一样，导致「何时弹登录窗」没有信号源。
 *
 * ## 三个 status 与两个 code
 *
 * | status | code | 客户端该做什么 |
 * | --- | --- | --- |
 * | `ready` | 无 | **不弹窗**，直接用编码地址取数 |
 * | `missing` | `webvpn_ticket_missing` | 弹窗：去登录网关 |
 * | `expired` | `webvpn_ticket_expired` | 弹窗：重新登录网关 |
 *
 * ## ⚠️ 诚实边界（后端注释原话）
 * `cache_miss` **零出站**，所以 `ready` 只表示「本地保管着一张未被判死的票」，
 * **不是**「网关保证放行」。真正判死靠后续请求遇到的 302。
 * 因此 `ready` 之后取数失败，仍要按「可能票失效」处理一次。
 */
@Serializable
data class WebVpnGateDto(
    val status: String? = null,
    /** `missing` / `expired` 时才有值，与 `/fetch` 端点同码。 */
    val code: String? = null,
    /** 仅 `ready` 时有值（RFC3339）。 */
    val expiresAt: String? = null,
) {
    /** 网关票状态是否为「可用」。 */
    fun isReady(): Boolean = status == STATUS_READY

    /**
     * 是否需要弹窗引导用户登录网关。
     *
     * **只认 [code]**（与 `WebVpnErrorClassifier` 同一口径）：
     * `missing` / `expired` 要弹；`ready` 与未知状态一律不弹。
     */
    fun needsLogin(): Boolean =
        code == com.nongyu.appplus.net.core.ApiErrorCode.WEBVPN_TICKET_MISSING ||
            code == com.nongyu.appplus.net.core.ApiErrorCode.WEBVPN_TICKET_EXPIRED

    companion object {
        /** 与后端 `WebVPNTicketState` 逐字一致。 */
        const val STATUS_READY = "ready"
        const val STATUS_MISSING = "missing"
        const val STATUS_EXPIRED = "expired"
    }
}

/** 判别结果。让调用方用一个 `when` 就能覆盖两种形状，不必自己判空。 */
sealed class CacheResult {
    /** 缓存命中。 */
    data class Hit(val envelope: CacheEnvelope) : CacheResult()

    /**
     * 未命中或子域错误（后端把这些都放在 200 里）。
     *
     * @param code 如 `cache_miss`；其它值按 [com.nongyu.appplus.net.core.ApiErrorCode] 归类。
     */
    /**
     * 未命中或子域错误（后端把这些都放在 200 里）。
     *
     * @param code 如 `cache_miss`；其它值按 [com.nongyu.appplus.net.core.ApiErrorCode] 归类。
     * @param address 直连教务 URL（**不是** WebVPN 编码地址）。校外打不进。
     *   教室/教师这类需要用户输入的 dataType 不下发地址，这里为空。
     * @param method 请求方式，由后端给（当前固定 `GET`）。客户端不自己决定。
     * @param cookies 教务会话 Cookie（`ASPSESSIONID*`），由后端用已保管的 tgt 换出。
     * @param parser 解析器名（如 `jiaowu-course-v1`），标识该用哪套字段规则解析 HTML。
     * @param session `ok` / `unavailable`。`unavailable` 表示后端拿不到教务会话，cookies 为空。
     * @param needs 缺哪个入参（如「教室名关键词（bj）」）。**只有缺入参的 4 类有值**：
     *   教室课表 / 教师课表 / 教师信息 / 教室信息。
     * @param basePath 缺入参时仍可用的**骨架路径**（不带 query），客户端自己拼。
     * @param webvpn 校外形态下的网关票本地状态。`mode=vpn` 时才出现。
     */
    data class Miss(
        val code: String,
        val message: String?,
        val address: String?,
        val target: String? = null,
        val method: String? = null,
        val cookies: List<IssuedCookie> = emptyList(),
        val parser: String? = null,
        val session: String? = null,
        val needs: String? = null,
        val basePath: String? = null,
        /** 地址形态：`direct`（校内）/ `vpn`（校外经网关）。缺票判定要用它。 */
        val mode: String? = null,
        val webvpn: WebVpnGateDto? = null,
    ) : CacheResult() {

        /** 是否「没有可用缓存」这一正常流程分支。 */
        fun isCacheMiss(): Boolean = code == com.nongyu.appplus.net.core.ApiErrorCode.CACHE_MISS

        /**
         * 是否需要弹窗引导用户登录 WebVPN 网关。
         *
         * ## ⚠️ 必须同时覆盖两个字段源（后端有两条下发路径）
         *
         * | 后端情形 | 信号 | 要弹吗 |
         * | --- | --- | --- |
         * | 校外 + 有票 | `session=ok` + `cookies` 非空 | 不弹 |
         * | 校外 + **没票** | `session=unavailable` + `cookies` 空 | **弹** |
         * | 校外 + **票过期** | `webvpn.code=webvpn_ticket_expired` | **弹** |
         * | 课外 + 票缺失 | `webvpn.code=webvpn_ticket_missing` | **弹** |
         * | 校内（direct） | `session=ok` + 教务 cookie | 不弹 |
         *
         * **2026-11-19 修正**：此前只认 `webvpn.code`，漏掉了 `session=unavailable`。
         * 真机实测（日志 `session=unavailable cookies=0`）证明后端在「没票」时走的是
         * `session` 字段而非 `webvpn` 字段，导致客户端拿着空 cookie 去请求网关，
         * 拿回登录页后报「不是课表页」——用户完全不知道是缺票。
         *
         * 判定口径：**票不可用**即弹。`session=unavailable` 与 `cookies` 为空
         * 都表示「没有可用的网关票」，不必区分「从没交过」还是「过期被清」。
         */
        fun needsWebVpnLogin(): Boolean {
            // ① 后端明确给了票状态码（缺失 / 过期）—— 一定是 WebVPN 问题
            if (webvpn?.needsLogin() == true) return true
            // ② 后端明确说票**可用** —— 以它为准，不再用 session/cookies 反推。
            //    这条必须先于 ③：`webvpn.status=ready` 表达的就是「本地有张没被判死的票」，
            //    此时即便 cookies 为空（例如后端改了下发策略），也不该判成缺票。
            if (webvpn?.isReady() == true) return false
            // ③ 校外形态（mode=vpn）下会话不可用或没有 cookie，都等于「没有可用的网关票」
            //    校内形态不在这里判：那说明教务会话没换来，该走「重新登录教务」而非 WebVPN
            if (mode == MODE_VPN) {
                if (session == SESSION_UNAVAILABLE) return true
                if (cookies.isEmpty()) return true
            }
            return false
        }

        /**
         * 是否是**服务端问题**（不是用户能解决的），UI 应提示「服务器暂时不可用」而不是引导登录。
         *
         * 公共类型（通知 / 教室 / 教师那 8 类）由后端的**取数账号池**代取，
         * 池未配置、无可用账号、换票失败都属于运维问题 —— 让用户去登录网关毫无意义。
         */
        fun isServerFault(): Boolean = code in SERVER_FAULT_CODES

        /** 后端是否给出了可执行的取数配方（地址 + 方法都有）。 */
        fun hasRecipe(): Boolean =
            !address.isNullOrBlank() && !method.isNullOrBlank()

        /**
         * 是否「缺入参」——`address` 为空但不是出错，需要用户补一个参数。
         *
         * 后端 16ea6b4 起：教室/教师这类必须用户输入的类型不下发完整地址，
         * 只给 `needs`（缺什么）+ `basePath`（骨架路径）。
         * **判断"是不是错误"不能只看 address 为空** —— 这种情况是正常流程，
         * 应该提示用户补 `needs` 所述的入参，而不是报错。
         */
        fun needsInput(): Boolean =
            !needs.isNullOrBlank() && address.isNullOrBlank()

        /** 教务会话是否可用（后端换票成功）。 */
        fun sessionAvailable(): Boolean = session == "ok" && cookies.isNotEmpty()

        companion object {
            /**
             * 后端 `sessionStateUnavailable`：校外私有类**没票**时的会话语义。
             *
             * 与 `webvpn.status` 的区别：那是「本地票状态」，这是「本次取数所需凭据是否齐备」。
             * 两者语义重叠但字段不同，判定时必须都看。
             */
            const val SESSION_UNAVAILABLE = "unavailable"

            /** 与后端 `jiaowu.AccessVPN` 逐字一致：校外经网关形态。 */
            const val MODE_VPN = "vpn"

            /** 与后端 `jiaowu.AccessDirect` 逐字一致：校内直连形态。 */
            const val MODE_DIRECT = "direct"

            /**
             * 公共取数账号池相关的服务端错误码（后端 `publicCacheMiss`）。
             *
             * 这些码表示**运维侧**没配好（池未配置 / 无可用账号 / 换 ST 失败 / 建会话失败），
             * 用户无论怎么操作都解决不了 —— UI 应提示「服务器暂时不可用，请联系管理员」，
             * **绝不能**引导用户去登录 WebVPN 网关。
             */
            val SERVER_FAULT_CODES = setOf(
                "fetch_account_unavailable",
                "fetch_account_st_failed",
                "fetch_account_cas_failed",
            )
        }
    }
}

/**
 * 缓存命中时的信封（对应后端 `cacheEnvelope`）。
 *
 * - `payload`：`json.RawMessage` 原样透出，**客户端不解析内部字段**。
 * - `success`：后端用 `omitempty`，缺字段时即 `null`。
 * - `schemaVersion`：回传体自带的 schema 版本。
 * - `fetchedAt`：**服务端盖章**的 UTC RFC3339 字符串（不采信客户端时间）。
 * - `stale`：只有 `fallback=1` 时可能为 true（客户端上报直连失败后要旧数据）。
 */
@Serializable
data class CacheEnvelope(
    val payload: JsonElement? = null,
    val success: Boolean? = null,
    val schemaVersion: Int? = null,
    val fetchedAt: String? = null,
    val stale: Boolean? = null,
)

/**
 * POST `/school/jiaowu/cache/:dataType` 的成功响应。
 *
 * 后端 `cacheSubmitHandler` 回的也是 `envelopeOf(snap)`，形状与 [CacheEnvelope] 相同，
 * 这里用 typealias 表达「同一形状」，避免复制一份 DTO 造成两处真源。
 */
typealias CacheSubmitResponse = CacheEnvelope

/** DELETE `/school/jiaowu/cache/:dataType` 的成功响应：`{"invalidated":true}`。 */
@Serializable
data class CacheInvalidateDto(
    val invalidated: Boolean? = null,
)

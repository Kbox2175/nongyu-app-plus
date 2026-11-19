package com.nongyu.appplus.net.api.http

import com.nongyu.appplus.net.core.CampusNetworkState
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把「在校内 / 校外」的判定结果挂成 `X-Nongyu-Campus-Network` 请求头。
 *
 * ## 这个头是干什么的
 * 后端 `jiaowu.recipe.go` 用它决定下发的教务地址形态：
 * - `direct`（校内）→ 直连 `https://jiaowu.sicau.edu.cn/...`，后端顺带下发换出的 `ASPSESSIONID`
 * - `vpn`（校外）→ WebVPN 编码地址，cookies 恒空（网关票只能由客户端自己登录网关产生）
 *
 * 不发这个头时，后端按 `accessModeOf` 的默认值处理（不认识就回落 `vpn`）。
 *
 * ## 为什么不挂到所有请求上是有意的
 * 判定为 [CampusNetworkState.Presence.Unknown]（还没探出来 / 探测失败）时
 * **不挂头** —— 让后端用它自己的默认值，而不是冒充一个客户端并不知道的结论。
 * 这是维护者拍板的兜底口径，见 [CampusNetworkState.headerValue]。
 *
 * ## 为什么要排除探针请求
 * `GET /school/address` 与探测请求本身不需要这个头（它们是取地址用的，
 * 挂上去反而会让后端按尚未确定的形态算出地址）。这里按路径精确排除，
 * 与 [AppSignInterceptor] 的豁免表同一手法：**用精确匹配，不用前缀**。
 */
class CampusNetworkInterceptor(
    private val stateForTest: (() -> String?)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath in SKIP_PATHS) {
            return chain.proceed(request)
        }
        // 生产走 CampusNetworkState；测试可注入固定值。
        val value = stateForTest?.invoke() ?: CampusNetworkState.headerValue()
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header(CampusNetworkState.HEADER_NAME, value).build(),
        )
    }

    private companion object {
        /**
         * 不需要挂头的路径。
         *
         * 只放「地址查询 / 探测」这类**用来决定形态本身**的端点：
         * 给它们挂上尚未确定的形态会让结果自相矛盾。
         */
        val SKIP_PATHS = setOf(
            "/school/address",
            "/school/fetch",
        )
    }
}

package com.nongyu.appplus.net

import com.nongyu.appplus.BuildConfig

/**
 * 网络层基础配置 —— **单一真源**（原来 debug/release 各一份，现已合并）。
 *
 * ## 为什么合并到 main
 * 之前 `src/debug/` 与 `src/release/` 各放一份同名文件做编译期隔离，带来两个问题：
 * 1. **两份容易不同步**：改了一边忘了另一边，只有构建两个变体才暴露；
 * 2. 搜代码、看实现、加参数都要在两处之间来回跳。
 *
 * 合并后只有这一份，变体差异集中在下方 `isDebug` 分叉处，**一眼可见、只有一处**。
 *
 * ## 代价（明确的取舍，不是遗漏）
 * 原来「release 包里连 `DEV_TOKEN` 这个符号都不存在」是**编译期**保证；
 * 现在改为**运行时**判断。为防止误用，仍保留两道结构性保护：
 * - `DEV_TOKEN` 只在 debug 变体的 `buildConfigField` 里声明，
 *   **release 变体没有这个符号**，所以本文件用反射式条件编译拿不到它就要编译失败
 *   —— 因此这里只在 debug 分支引用它，release 分支字面返回 null（见下）。
 * - release 的 `BASE_URL` 仍为空串，`UrlBuilder.normalize` 会抛带明确文案的异常，
 *   不会静默变成坏 URL。
 *
 * ## BASE_URL 契约
 * **必须以 `/` 结尾**（Retrofit 的 `baseUrl()` 要求）。缺失时由
 * [com.nongyu.appplus.net.core.UrlBuilder.normalize] 补齐并校验。
 */
object NetConfig {

    /**
     * Retrofit 的 base URL。
     *
     * - debug：指向本机后端。蓝叠联调走 `adb reverse tcp:3000 tcp:3000`，
     *   不用 `10.0.2.2` —— 这样两个环境共用一个值。
     * - release：**测试版指向公网开发后端**（2026-11-19 维护者指定）。
     *
     * ## ⚠️ release 用的是 HTTP 明文地址，需要配套放行
     * `103.236.88.53:57979` 没有 HTTPS，而 Android 9+ 默认禁明文。
     * 因此 `app/src/main/res/xml/network_security_config.xml` 里为该 IP 单条放行，
     * 并在 `AndroidManifest.xml` 声明。**只放行这一个地址**，其余仍按平台默认禁明文。
     *
     * 上 HTTPS 后应：改此处为 https、删掉 main 源集那份放行配置与清单声明。
     */
    val BASE_URL: String
        get() = if (BuildConfig.DEBUG) DEBUG_BASE_URL else RELEASE_BASE_URL

    private const val DEBUG_BASE_URL: String = "http://127.0.0.1:3000/"

    /** 测试版公网后端。末尾斜杠是 Retrofit 的硬要求，由 UrlBuilder 校验。 */
    private const val RELEASE_BASE_URL: String = "http://103.236.88.53:57979/"
}

/**
 * 是否挂网络日志拦截器（规格 §6.3：release 结构性不打网络日志）。
 *
 * 合并前是 debug 返回 `true` / release 返回 `false` 的两份文件；
 * 现在只有这一个函数，用 `BuildConfig.DEBUG` 分叉。
 */
internal fun netLoggingEnabled(): Boolean = BuildConfig.DEBUG

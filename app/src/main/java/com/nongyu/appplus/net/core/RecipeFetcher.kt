package com.nongyu.appplus.net.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 按后端下发的「取数配方」执行一次请求，拉回原始页面文本。
 *
 * ## 从哪抽出来的
 * 原先是 `timetable/data/CourseRepository.fetchByRecipe` 的私有方法。抽到通行层是因为：
 * 15 类回传数据类型（课表 / 成绩 / 考试 / 学业进度 / 排名 / 培养方案 / 学籍 /
 * 通知 / 教室 / 教师 …）**用的是同一个取数模型** —— 后端给地址与方法，客户端照着执行。
 * 每加一类就复制一遍这个轮子，必然出现 15 份细节各异的实现。
 *
 * ## ⚠️ 为什么不接收 `CacheResult.Miss`
 * `net/core` 是**零依赖的纯逻辑层**（规格 §2.1，`AGENTS.md` 硬约束）。
 * 直接收 `CacheResult.Miss` 会让 `core` 依赖 `net/model`，破坏分层。
 * 所以这里只接收**原始参数**（地址 / 方法 / Cookie 对），由调用方从配方里摊平。
 *
 * ## 客户端在这里不决定任何事
 * 地址、请求方式、Cookie **全部由后端给**，本对象只做「执行」。
 * 这是维护者的硬约束：客户端不得写死教务地址、请求方式、请求字段。
 *
 * ## 为什么用独立的 OkHttpClient
 * **不复用** `NetHolder` 的 client：那个挂了 `AuthInterceptor` 与 `AppSignInterceptor`，
 * 会把农屿的 `Authorization` / `X-Nongyu-*` 一并发给教务，既无意义也可能触发对方风控。
 * 教务会话由配方里的 Cookie 承载。
 *
 * ## 同步 API 与线程要求
 * [fetch] 是**同步阻塞**的。调用方必须已在 IO 线程上，
 * 否则抛 `NetworkOnMainThreadException`（本项目实测踩过：DELETE/GET 都正常，只有这一步炸）。
 */
object RecipeFetcher {

    /** 连接/读超时。教务页面在慢网下可能十几秒。 */
    private const val TIMEOUT_SECONDS = 20L

    /** 教务页多为 GBK；`Content-Type` 未声明字符集时按它解。 */
    private const val DEFAULT_CHARSET = "GBK"

    /**
     * 浏览器请求头 —— **逐字照抄后端实测脚本** `tools/_verify/main.go:115`。
     *
     * 该脚本 2026-11-19 用这组头直连编码地址拿到 200 / 32848 字节 / 含 table 的真实课表。
     * 注意是 **Android Chrome UA**；桌面 Chrome UA 与 okhttp 默认 UA 都会让网关
     * 偶发导向登录页（实测 1856 字节「川农e认证」壳）。**不要改成别的 UA。**
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private const val HTML_ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9"

    private val charsetRegex = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)

    /** 一个 Cookie 对（名字与值都由后端下发）。 */
    data class CookiePair(val name: String, val value: String)

    /**
     * 按配方执行请求并返回响应体文本。
     *
     * @param address 后端下发的地址（必填，空则抛）
     * @param method 请求方式，空则按 `GET`
     * @param cookies 后端下发的 Cookie 列表，可为空
     * @throws IllegalStateException 缺地址 / 不支持的请求方式 / HTTP 非 2xx
     * @throws java.io.IOException 超时、连接失败等
     */
    fun fetch(
        address: String?,
        method: String?,
        cookies: List<CookiePair>,
    ): String {
        val url = address?.takeIf { it.isNotBlank() } ?: error("配方缺少地址")
        val httpMethod = method.orEmpty().ifBlank { "GET" }

        val builder = Request.Builder().url(url)
        cookieHeaderOf(cookies)?.let { builder.header("Cookie", it) }
        // 伪装成浏览器：学校网关/教务对 okhttp 默认 UA 不稳定（实测 2026-11-19）。
        // 三个头逐字对齐后端实测脚本 tools/_verify/main.go:115-117。
        builder.header("User-Agent", BROWSER_UA)
        builder.header("Accept", HTML_ACCEPT)
        builder.header("Accept-Language", ACCEPT_LANGUAGE)

        val request = when (httpMethod.uppercase()) {
            "GET" -> builder.get().build()
            // 目前后端对取数只下发 GET；出现别的方式按它执行，不硬编码。
            else -> error("不支持的请求方式：$httpMethod")
        }

        return client().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("拉取教务页面失败：HTTP ${resp.code}")
            val body = resp.body ?: error("教务响应为空")
            val bytes = body.bytes()
            val charsetName = charsetRegex
                .find(resp.header("Content-Type").orEmpty())
                ?.groupValues?.getOrNull(1)
                ?: DEFAULT_CHARSET
            String(bytes, charsetOf(charsetName))
        }
    }

    /**
     * 把 Cookie 列表拼成 `Cookie` 头。
     *
     * 名字/值都由后端给，客户端**不自己编**。名字为空的项跳过。
     *
     * @return 拼好的头值；没有任何有效 Cookie 时返回 `null`（此时不挂这个头）
     */
    fun cookieHeaderOf(cookies: List<CookiePair>): String? {
        val header = cookies
            .filter { it.name.isNotBlank() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        return header.ifBlank { null }
    }

    /** 解析字符集；名字非法时回落 [DEFAULT_CHARSET]，不抛。 */
    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charset.forName(DEFAULT_CHARSET))

    /**
     * 取数用客户端。惰性创建：只有真的取数时才建连接池。
     *
     * `retryOnConnectionFailure` 保持 OkHttp 默认（true）是**刻意的**：
     * 取数是幂等 GET，与农屿后端的 `false`（防 POST 重发）场景不同。
     */
    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // ⚠️ 不跟随重定向 —— 照抄后端实测脚本 tools/_verify/main.go:125-127。
            //
            // 实机诊断（2026-11-20）：默认 followRedirects=true 时，客户端请求课表编码地址
            // 收到 302 → 自动跟到 /wui/ 登录页 → 拿到 1856 字节「川农e认证」壳，
            // 于是被误判成「不是课表页」。而验证脚本关掉重定向后，同一地址同一票
            // 直接返回 200 / 32848 字节真课表。**这是能否拿到课表的关键差异。**
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 仅测试用：注入客户端（如 MockWebServer 指向的实例）。 */
    private var injectedClient: OkHttpClient? = null

    internal fun setClientForTest(client: OkHttpClient?) {
        injectedClient = client
    }

    internal fun resetForTest() {
        injectedClient = null
    }

    private fun client(): OkHttpClient = injectedClient ?: defaultClient
}

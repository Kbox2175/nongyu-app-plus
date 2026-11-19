package com.nongyu.appplus.timetable.data

import com.nongyu.appplus.net.NetHolder
import com.nongyu.appplus.net.api.get
import com.nongyu.appplus.net.api.invalidate
import com.nongyu.appplus.net.api.submit
import com.nongyu.appplus.net.model.CacheDataType
import com.nongyu.appplus.net.model.CacheResult
import com.nongyu.appplus.net.model.CourseItem
import com.nongyu.appplus.timetable.logic.CourseHtmlParser
import com.nongyu.appplus.timetable.logic.CourseScheduleParser
import com.nongyu.appplus.timetable.model.CourseEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * 课程表真实数据来源：读后端缓存里的 `course` 子域。
 *
 * ## 为什么不是专门接口
 *
 * 后端**没有课表专用端点**。课程数据走通用回传缓存：
 * ```
 * 客户端直连教务 → POST /school/jiaowu/cache/course 回传 → 后端用 schema 校验后存起来
 * 本仓库 App      → GET  /school/jiaowu/cache/course 读取
 * ```
 * 依据：`nongyu-backend-plus/internal/school/jiaowu/datatype.go` 的
 * `DataTypeCourse = "course"`；`schema/course.json` 定义 payload 形状。
 *
 * ## 两种 200 都要处理
 *
 * 后端 `cacheGetHandler`：命中 → `200` + envelope；未命中 → `200` + `{"code":"cache_miss"}`。
 * 这由 [com.nongyu.appplus.net.model.CacheGetResponse.discriminate] 判别，
 * 本类只消费判别结果，不自己猜字段。
 *
 * ## 失败语义
 *
 * 拿不到数据时返回 [Result.failure]，由 UI 决定展示什么。
 * **不 fallback 到 mock**：维护者要求填真实数据，静默回退假数据会让人以为已接通。
 */
class CourseRepository(
    private val context: android.content.Context,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** 本地缓存：课表数据在手机上留一份，日常打开不发网络请求。 */
    private val cache = CourseCache(context)

    /** 读缓存的结果：命中给课程，未命中给**后端下发的取数配方**。 */
    sealed class Loaded {
        /**
         * 命中。
         *
         * @param outcome 解析后的课表
         * @param rawItems 解析前的原始条目，用于写回本地缓存（避免下次再联网）
         */
        data class Hit(
            val outcome: CourseScheduleParser.ParseOutcome,
            val rawItems: List<CourseItem>? = null,
        ) : Loaded()

        /**
         * 缓存未命中，带后端的直连取数配方。
         *
         * @param miss 原始未命中结果，含 address / method / cookies / parser / session。
         *   这些值**全部由后端决定**，客户端不自己填。
         */
        data class Miss(val miss: CacheResult.Miss) : Loaded()
    }

    /**
     * 读课表缓存。
     *
     * ## address 从哪来
     *
     * 后端 `GET /school/jiaowu/cache/:dataType` 未命中时返回的是
     * `200 {"code":"cache_miss","message":"...","address":"https://webvpn..."}`
     * —— **地址就在这个响应里**，不需要另外请求 `/school/address`。
     *
     * ⚠️ **修正（2026-11-18）**：此前这里写「地址已过 WebVPN 改写并带上票据」是**错的**。
     * 后端实际行为按 `X-Nongyu-Campus-Network` 分两种：
     * - 校内（`direct`）：直连教务 URL，**cookies 有值**（后端用保管的 tgt 换出 `ASPSESSIONID`）
     * - 校外（`vpn`）：WebVPN 编码地址，**cookies 恒为空** —— 网关票只能由客户端
     *   自己登录网关产生，后端换不到。票的本地状态在 `webvpn` 字段里（见下）。
     */
    suspend fun loadOrMiss(): Result<Loaded> = runCatching {
        val api = NetHolder.cache(context)
        val resp = api.get(CacheDataType.COURSE)
        if (!resp.isSuccessful) error("读课表缓存失败：HTTP ${resp.code()}")
        val body = resp.body() ?: error("读课表缓存失败：响应体为空")

        when (val result = body.discriminate()) {
            is CacheResult.Miss -> Loaded.Miss(result)

            is CacheResult.Hit -> {
                val payload = result.envelope.payload ?: error("课表缓存内容为空")
                val arr = runCatching { payload.jsonArray }.getOrNull()
                    ?: error("课表缓存格式异常：payload 不是数组")
                val items = arr.mapNotNull { el ->
                    runCatching { json.decodeFromJsonElement(CourseItem.serializer(), el) }.getOrNull()
                }
                Loaded.Hit(CourseScheduleParser.parse(items), rawItems = items)
            }
        }
    }

    /**
     * 读课表 —— **本地优先，本地有就完全不打网络**（维护者 2026-11-17 定的策略）。
     *
     * ## 什么时候才联网
     *
     * 只有两种情况：
     * 1. 本地为空（首次使用 / 缓存被清 / 结构版本不匹配被丢弃）；
     * 2. 用户主动点「更新课表」（走 [refreshFromJiaowu]，与这里无关）。
     *
     * 也就是说，日常打开课表页面**一次网络请求都不发**。理由：课表一学期基本不变，
     * 为了可能过期的数据每次冷启动都打网络，换来的只是「地铁里打不开课表」。
     *
     * ## 联网拿到数据后写回本地
     *
     * 这样只有第一次（或清缓存后首次）需要网络，之后都走本地。
     */
    /**
     * 读课表 —— 三级回落，正常路径**一次网络都不用**。
     *
     * ## 完整链路（维护者 2026-11-17 / 11-19 定的策略）
     *
     * ```
     * ① 本地有 → 直接解析返回（0 次网络）
     * ② 本地空 → GET 后端缓存
     *      命中 → 写本地，返回（1 次网络）
     *    cache_miss → **自动照配方抓取教务 → 回传后端 → 写本地 → 返回**
     *                  （用户不需要手动点「更新课表」）
     * ```
     *
     * ## 为什么 cache_miss 要自动抓
     *
     * 维护者 2026-11-19：「cache_miss 后应该去拿课表并缓存后端呀」。
     * 之前这里遇到 miss 直接报「课表缓存为空，请先更新课表」——
     * 那等于把「后端冷启动」这种正常情况甩给用户手动解决。现在自动完成。
     *
     * ## 为什么不在这里 DELETE
     *
     * miss 已经说明后端没有缓存，DELETE 无意义；且 DELETE 有 60 秒间隔，
     * 会因为别人刚刷新过而导致「首次自动恢复」失败。DELETE 只在用户主动刷新时做。
     */
    suspend fun load(): Result<CourseScheduleParser.ParseOutcome> {
        // ① 本地有 → 直接解析返回，不发任何请求
        val cached = cache.load()
        if (cached.isNotEmpty()) {
            return Result.success(CourseScheduleParser.parse(cached))
        }

        // ②③ 本地空 → 读后端缓存；miss 就自动抓取（抓取内部会回传并写本地）
        return loadOrMiss().mapCatching { loaded ->
            when (loaded) {
                is Loaded.Hit -> {
                    loaded.rawItems?.let { cache.save(it) }
                    loaded.outcome
                }

                is Loaded.Miss -> {
                    // 后端没缓存 → 照配方抓教务、回传后端、写本地
                    android.util.Log.i("CourseRepo", "后端无缓存（${loaded.miss.code}），自动抓取课表")
                    fetchAndSubmit(loaded.miss)
                    // 抓取已写入本地缓存，直接读出来解析，保证与下次冷启动完全一致
                    CourseScheduleParser.parse(cache.load())
                }
            }
        }
    }

    /** 便捷：只关心可落格课程。 */
    suspend fun loadEntries(): Result<List<CourseEntry>> =
        load().map { it.entries }

    /**
     * 把教务解析结果**回传**到后端缓存（`POST /school/jiaowu/cache/course`）。
     *
     * ## 为什么这里要有上传
     *
     * 后端只做「校验 + 存储」，课程的原始来源是教务系统。所以写入缓存的唯一合法路径是：
     * 客户端拿到教务数据 → 解析成 [com.nongyu.appplus.net.model.CourseItem] 数组 → 调本方法回传。
     *
     * @param payloadJson 完整回传体（含 `payload` 数组 + `schemaVersion`），
     *   形状必须符合后端 `schema/course.json`，否则后端会以 schema 校验失败拒收
     */
    suspend fun submit(payloadJson: String): Result<Unit> = runCatching {
        val api = NetHolder.cache(context)
        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val resp = api.submit(CacheDataType.COURSE, body)
        if (!resp.isSuccessful) {
            val errBody = runCatching { resp.errorBody()?.string() }.getOrNull()
            android.util.Log.w("CourseRepo", "回传课表失败 HTTP ${resp.code()} body=$errBody")
            error("回传课表失败：HTTP ${resp.code()} $errBody")
        } else {
            android.util.Log.i("CourseRepo", "回传课表成功：${payloadJson.length} 字节")
        }
    }.onFailure {
        android.util.Log.e("CourseRepo", "回传课表异常", it)
    }.map { }

    /**
     * **更新课表**：照后端下发的「取数配方」执行。
     *
     * ## 客户端在这里不填任何教务细节（维护者硬约束）
     *
     * 地址、请求方式、会话 Cookie、解析器名，**全部由后端下发**：
     * ```json
     * {"code":"cache_miss","target":"jiaowu",
     *  "address":"https://jiaowu.sicau.edu.cn/xuesheng/.../kbbanji.asp?title_id1=4",
     *  "method":"GET","cookies":[{"name":"ASPSESSIONID…","value":"…"}],
     *  "parser":"jiaowu-course-v1","session":"ok"}
     * ```
     * 客户端只做「按 recipe 发请求 → 解析 → 回传」，里面不该出现任何教务路径常量。
     *
     * ## 为什么先 DELETE
     *
     * recipe 只在**未命中**时下发。想刷新已有缓存就得先让它失效。
     * 后端 DELETE 有 60 秒间隔，过频回 `429 refresh_too_soon`（带 `Retry-After`），
     * 这里翻成 [TooFrequentException]，由 UI 用 Toast 提示。
     *
     * ## 链路
     * ```
     * ① DELETE /school/jiaowu/cache/course   让缓存失效（60s 内 429）
     * ② GET    /school/jiaowu/cache/course   → cache_miss + 配方
     * ③ 按配方 GET <address>（带下发 Cookie）→ 教务 HTML
     * ④ 解析 HTML（按 parser 名选规则）
     * ⑤ POST   /school/jiaowu/cache/course   回传解析结果
     * ```
     *
     * @return 成功时带解析出的课程条数
     * @throws TooFrequentException 60 秒内重复更新
     */
    /**
     * 照后端下发的取数配方**抓取并回传**课表，然后写入本地缓存。
     *
     * 这是「拿到数据」的唯一实现，两个入口共用：
     * - [load]：cache_miss 时**自动**调用（首次使用 / 缓存被清后自己恢复）
     * - [refreshFromJiaowu]：用户主动点「更新课表」
     *
     * ## 为什么这里不 DELETE
     *
     * 调用方已经拿到了 [CacheResult.Miss]（后端确认没有缓存），DELETE 无意义，
     * 而且 DELETE 有 60 秒间隔、过频会 429 —— 放在这里会让「首次自动恢复」
     * 因为别人刚刷新过而失败。DELETE 只在 [refreshFromJiaowu] 里做。
     *
     * @param miss 后端下发的取数配方
     * @return 解析出的课程条数
     */
    private suspend fun fetchAndSubmit(miss: CacheResult.Miss): Int {
        // ① 服务端问题：公共取数账号池没配好，用户无法自救 —— 给出可读错误，不弹登录窗。
        if (miss.isServerFault()) {
            error("服务器暂时无法代取教务数据，请联系管理员（${miss.code}）")
        }

        // ② 缺票：投递登录弹窗，并**不再发起注定失败的请求**
        //
        // 判定口径见 CacheResult.Miss.needsWebVpnLogin()：它同时覆盖后端的两条信号路径
        // （session=unavailable 与 webvpn.code=missing/expired）。
        //
        // ⚠️ 2026-11-19 修正：此前这里「投递后继续跑」，理由是「票可能其实还有效」。
        // 真机证明那是错的 —— 没票时请求必然拿回网关登录页，白跑一次网络
        // 还会把「缺票」误报成「不是课表页」。现在改为直接短路返回。
        if (miss.needsWebVpnLogin()) {
            val trigger = when (miss.webvpn?.code) {
                com.nongyu.appplus.net.core.ApiErrorCode.WEBVPN_TICKET_EXPIRED ->
                    com.nongyu.appplus.webvpn.model.WebVpnTrigger.TICKET_EXPIRED

                else -> com.nongyu.appplus.webvpn.model.WebVpnTrigger.TICKET_MISSING
            }
            val decision = com.nongyu.appplus.webvpn.data.WebVpnDialogScheduler.request(trigger)
            android.util.Log.i(
                "CourseRepo",
                "缺票（session=${miss.session} cookies=${miss.cookies.size} " +
                    "webvpn=${miss.webvpn?.status}），弹窗调度=$decision",
            )
            error("需要先登录学校 WebVPN 网关")
        }

        if (!miss.hasRecipe()) {
            error(
                if (miss.sessionAvailable()) "后端未下发课表取数配方"
                else "教务会话不可用，请重新登录教务后重试",
            )
        }

        // ③ 照配方发请求：地址/方法/Cookie 全部用后端给的值
        //
        // ⚠️ 必须切到 IO 线程：Retrofit 的 suspend 方法内部会自己切，
        // 但这里是**裸 OkHttp 同步调用**，留在主线程会抛 NetworkOnMainThreadException
        //（本项目实测踩过：DELETE/GET 都正常，只有这一步炸）。
        val html = withContext(Dispatchers.IO) { fetchByRecipe(miss) }

        // ④ 解析（按 parser 名选规则；目前只有课表一套）
        val parsed = when (miss.parser) {
            PARSER_COURSE, null -> CourseHtmlParser.parse(html)
            else -> error("不支持的解析器：${miss.parser}")
        }
        if (!parsed.looksLikeTimetable) {
            // 把实际拿到的内容留一点线索：教务会话失效时常见的是登录页/跳转页，
            // 只靠一句「不是课表页」根本查不出是 cookie 没带、还是票据过期。
            android.util.Log.w(
                "CourseRepo",
                "非课表页诊断：len=${html.length} session=${miss.session} " +
                    "cookies=${miss.cookies.size} 头部=${html.take(200).replace('\n', ' ')}",
            )
            error("教务返回的不是课表页，可能需要重新登录教务")
        }
        if (parsed.items.isEmpty()) {
            error("教务课表页里没有解析到课程")
        }

        // ⑤ 回传后端，让后端也有这份数据（下次别的设备/清除本地后可直接命中）
        submit(buildPayload(parsed.items)).getOrThrow()

        // ⑥ 写本地缓存 —— 抓到的数据必须落到手机上，下次进页面就不用联网
        cache.save(parsed.items)

        return parsed.items.size
    }

    /** 把解析出的条目拼成后端要求的回传体（形状见 `schema/course.json`）。 */
    private fun buildPayload(items: List<CourseItem>): String = buildJsonObject {
        put("schemaVersion", 1)
        put("success", true)
        putJsonArray("payload") {
            for (item in items) {
                addJsonObject {
                    item.courseName?.let { put("courseName", it) }
                    item.courseId?.let { put("courseId", it) }
                    item.weeks?.let { put("weeks", it) }
                    item.classroom?.let { put("classroom", it) }
                    item.scheduleTime?.let { put("scheduleTime", it) }
                    item.teacher?.let { put("teacher", it) }
                }
            }
        }
    }.toString()

    suspend fun refreshFromJiaowu(): Result<Int> = runCatching {
        val api = NetHolder.cache(context)

        // ① 让缓存失效。过频时后端回 429，翻成专用异常给 UI 弹 Toast。
        val delResp = api.invalidate(CacheDataType.COURSE)
        if (delResp.code() == 429) {
            throw TooFrequentException(
                delResp.headers()["Retry-After"]?.toIntOrNull() ?: 60,
            )
        }
        if (!delResp.isSuccessful) {
            error("使课表缓存失效失败：HTTP ${delResp.code()}")
        }

        // ② 读缓存（必然未命中），拿到后端下发的配方
        val miss = when (val loaded = loadOrMiss().getOrThrow()) {
            is Loaded.Miss -> loaded.miss
            is Loaded.Hit -> error("缓存未按预期失效，请稍后再试")
        }

        // ③ 抓取 + 回传 + 写本地（fetchAndSubmit 内部已完成这三步）
        fetchAndSubmit(miss)
    }.onFailure {
        android.util.Log.e("CourseRepo", "更新课表失败", it)
    }

    /**
     * 按后端下发的配方发请求，拉回教务 HTML。
     *
     * ## 客户端不自己填任何一项
     *
     * 地址、方法、Cookie 全部取自 [CacheResult.Miss]（后端给的）。
     * 本方法只做「执行」，不决定「请求什么」—— 这是维护者的硬约束：
     * 客户端不得写死教务地址、请求方式、请求字段。
     *
     * ## 为什么用独立 OkHttp
     *
     * 不复用 [NetHolder] 的 client：那个挂了 `AuthInterceptor` 与 `AppSignInterceptor`，
     * 会把「我方后端的 Authorization / X-Nongyu-Signature」一并发给教务，
     * 既无意义也可能触发对方风控。教务会话由配方里的 Cookie 承载。
     */
    /**
     * 按后端下发的配方发请求，拉回教务 HTML。
     *
     * ## 执行逻辑已抽到 [RecipeFetcher]
     *
     * 原先本方法自带一份「装 Cookie → 发请求 → 按 charset 解码」的实现。
     * 15 类回传数据用的是同一个取数模型，抽到 `net/core/RecipeFetcher` 后本类只保留
     * 「课表特有的部分」（解析器选择、回传、写本地缓存）。
     *
     * ## 客户端不自己填任何一项
     *
     * 地址、方法、Cookie 全部取自 [CacheResult.Miss]（后端给的）。
     *
     * ## 为什么用独立 OkHttp
     *
     * 不复用 [NetHolder] 的 client：那个挂了 `AuthInterceptor` 与 `AppSignInterceptor`，
     * 会把「我方后端的 Authorization / X-Nongyu-Signature」一并发给教务，
     * 既无意义也可能触发对方风控。教务会话由配方里的 Cookie 承载。
     *
     * ⚠️ 同步阻塞，调用方必须已在 IO 线程（见 `refreshFromJiaowu` 的 `withContext`）。
     */
    private fun fetchByRecipe(recipe: CacheResult.Miss): String =
        com.nongyu.appplus.net.core.RecipeFetcher.fetch(
            address = recipe.address,
            method = recipe.method,
            cookies = recipe.cookies.mapNotNull { c ->
                val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                com.nongyu.appplus.net.core.RecipeFetcher.CookiePair(name, c.value.orEmpty())
            },
        )

    /**
     * 更新过于频繁（后端 60 秒失效间隔，`429 refresh_too_soon`）。
     *
     * 单独成类而不是用通用 Exception：UI 要据此弹「太频繁」的 Toast，
     * 靠匹配文案判断太脆。
     *
     * @param retryAfterSeconds 后端 `Retry-After` 头给的等待秒数
     */
    class TooFrequentException(val retryAfterSeconds: Int) :
        Exception("更新过于频繁，请 ${retryAfterSeconds} 秒后再试")

    private companion object {
        /**
         * 课表页的解析器名（后端 `recipe.go` 的 `pageRecipes` 里与 `DataTypeCourse` 对应）。
         *
         * 注意：这里只有**解析器标识**，没有任何教务地址或请求参数 ——
         * 地址与请求方式由后端下发。
         */
        const val PARSER_COURSE = "jiaowu-course-v1"
    }
}

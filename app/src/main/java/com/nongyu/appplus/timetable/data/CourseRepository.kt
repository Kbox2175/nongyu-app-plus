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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
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

    /** 读缓存的结果：命中给课程，未命中给**后端下发的取数配方**。 */
    sealed class Loaded {
        data class Hit(val outcome: CourseScheduleParser.ParseOutcome) : Loaded()

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
     * 这个地址已经过 WebVPN 改写并带上票据，客户端直接 GET 它就是教务课表页。
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
                Loaded.Hit(CourseScheduleParser.parse(items))
            }
        }
    }

    /**
     * 拉取并映射课程表（只读缓存）。
     *
     * 未命中时失败，提示用户点「更新课表」。
     */
    suspend fun load(): Result<CourseScheduleParser.ParseOutcome> =
        loadOrMiss().mapCatching { loaded ->
            when (loaded) {
                is Loaded.Hit -> loaded.outcome
                is Loaded.Miss -> error("课表缓存为空，请先更新课表")
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

        // ⑤ 回传
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("success", true)
            putJsonArray("payload") {
                for (item in parsed.items) {
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
        }
        submit(payload.toString()).getOrThrow()
        parsed.items.size
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
    private fun fetchByRecipe(recipe: CacheResult.Miss): String {
        val url = recipe.address ?: error("配方缺少地址")
        val method = recipe.method.orEmpty().ifBlank { "GET" }

        // 按配方装 Cookie（名字/值都由后端给，不自己编）
        val cookieHeader = recipe.cookies
            .mapNotNull { c ->
                val n = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val v = c.value.orEmpty()
                "$n=$v"
            }
            .joinToString("; ")

        val builder = Request.Builder().url(url)
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
        // 当前后端只下发 GET；出现别的方式按它执行，不硬编码
        val req = when (method.uppercase()) {
            "GET" -> builder.get().build()
            else -> error("不支持的请求方式：$method")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("拉取教务页面失败：HTTP ${resp.code}")
            val body = resp.body ?: error("教务响应为空")
            // 教务页多为 GBK；按 Content-Type 声明的字符集解码，缺省按 GBK
            val bytes = body.bytes()
            val contentType = resp.header("Content-Type").orEmpty()
            val charsetName = Regex("charset=([A-Za-z0-9_\\-]+)")
                .find(contentType)?.groupValues?.get(1) ?: "GBK"
            return String(bytes, charset(charsetName))
        }
    }

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

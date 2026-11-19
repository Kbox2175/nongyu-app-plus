package com.nongyu.appplus.timetable.data

import com.nongyu.appplus.net.model.CourseItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 课表本地缓存的**纯逻辑部分**（无 Android 依赖，可纯 JVM 单测）。
 *
 * ## 为什么拆出来
 *
 * [CourseCache] 本身要 `Context` 才能拿 `SharedPreferences`，而本仓库单测环境
 * **没有 Robolectric**（`testImplementation` 只有 junit / mockwebserver / coroutines-test）。
 * 直接把序列化与版本判定写在 `CourseCache` 里，就只能靠 instrumented 测试覆盖，
 * 而「缓存损坏怎么办」「版本不匹配怎么办」恰恰是最需要单测的分支。
 *
 * 所以这里只留「字节 → 数据」和「数据 → 字节」的纯变换，
 * `CourseCache` 负责读写 `SharedPreferences`，判定逻辑全部委托给本对象。
 */
object CourseCacheCodec {

    /** 缓存结构版本。**只在一份缓存的结构变了时才 +1**（如 CourseItem 字段改名）。 */
    const val VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * 解码缓存内容。
     *
     * @param raw 存下来的 JSON 文本；`null` 表示从未写过缓存
     * @param storedVersion 存的版本号
     * @return 课程条目；**空列表表示「没有可用数据」**，调用方据此决定是否联网。
     *   以下情况都返回空列表：
     *   1. 从未写过（raw 为 null）
     *   2. 版本不匹配（结构变了，整份丢弃，不写迁移逻辑）
     *   3. 内容损坏（JSON 解析失败）
     */
    fun decode(raw: String?, storedVersion: Int): List<CourseItem> {
        if (raw == null) return emptyList()
        if (storedVersion != VERSION) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(CourseItem.serializer()), raw)
        }.getOrElse { emptyList() }
    }

    /**
     * 编码为可存储的 JSON 文本。
     *
     * @return `null` 表示**不该覆盖已有缓存**（空列表不覆盖：一次失败的拉取
     *   不该把好数据抹掉）
     */
    fun encode(items: List<CourseItem>): String? {
        if (items.isEmpty()) return null
        return runCatching {
            json.encodeToString(ListSerializer(CourseItem.serializer()), items)
        }.getOrNull()
    }
}

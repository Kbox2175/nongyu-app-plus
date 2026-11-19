package com.nongyu.appplus.timetable.data

import android.content.Context
import com.nongyu.appplus.net.model.CourseItem

/**
 * 课表**本地缓存**（SharedPreferences + JSON）。
 *
 * ## 维护者 2026-11-17 定的策略：本地优先，有数据就完全不请求后端
 *
 * ```
 * 读本地 → 有数据 → 直接用（**一次网络都不发**）
 *           └─ 没有 → 联网读后端缓存 → 写回本地
 *
 * 联网时机只有两个：
 *   1. 本地为空（首次使用 / 缓存被清 / 版本不匹配被丢弃）
 *   2. 用户主动点「更新课表」
 * ```
 *
 * 这条比 [com.nongyu.appplus.timetable.data.holiday.HolidayCache] 更彻底：
 * 节假日那边是「本地有就先用」，课表这边是「本地有就**连后台刷新都不做**」。
 * 维护者的理由充分 —— 课表一学期基本不变，为了可能过期的数据每次都打网络，
 * 换来的只是「地铁里打不开课表」。
 *
 * ## 为什么存原始 [CourseItem] 而不是解析后的结果
 *
 * 解析（`CourseScheduleParser`）是**纯函数**，随时可重算；而解析规则会演进
 * （新的 `scheduleTime` 脏格式、周次写法）。存原始数据意味着：解析规则升级后
 * **老缓存不需要作废**，下次读取自然用新规则重新解析。存解析结果则必须写迁移。
 *
 * ## 分层
 *
 * 本类只负责读写 `SharedPreferences`；序列化与版本判定在 [CourseCacheCodec]
 * （纯逻辑、无 Android 依赖、有单测）。这样拆分是因为本仓库单测环境没有
 * Robolectric，写在 `Context` 依赖类里的判定分支无法被纯 JVM 测试覆盖。
 *
 * ## 版本号
 *
 * 见 [CourseCacheCodec.VERSION]。只在一份缓存的结构变了时才 +1；
 * 解析器升级**不需要**动它（因为存的是原始数据）。
 */
class CourseCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读本地课表。
     *
     * @return 原始课程条目；**空列表表示「本地没有可用数据」**
     *   （从未拉取 / 版本不匹配 / 内容损坏），调用方据此决定是否联网。
     */
    fun load(): List<CourseItem> = CourseCacheCodec.decode(
        raw = prefs.getString(KEY_DATA, null),
        storedVersion = prefs.getInt(KEY_VERSION, 0),
    )

    /** 本地是否有可用数据。 */
    fun hasData(): Boolean = load().isNotEmpty()

    /**
     * 写回本地。
     *
     * 空列表**不会**覆盖已有缓存 —— 一次失败的拉取不该把好数据抹掉。
     * 写失败也不抛异常：本次内存里已有数据可用，只是下次进来要重新联网。
     *
     * @param items 解析前的原始条目（与后端缓存里的 payload 同形）
     */
    fun save(items: List<CourseItem>) {
        val encoded = CourseCacheCodec.encode(items) ?: return
        runCatching {
            prefs.edit()
                .putInt(KEY_VERSION, CourseCacheCodec.VERSION)
                .putString(KEY_DATA, encoded)
                .apply()
        }.onFailure {
            android.util.Log.w("CourseCache", "写本地课表失败", it)
        }
    }

    /** 清空缓存（调试用）。 */
    fun clear() {
        prefs.edit().remove(KEY_DATA).remove(KEY_VERSION).apply()
    }

    private companion object {
        const val PREFS_NAME = "course_cache"
        const val KEY_DATA = "items"
        const val KEY_VERSION = "version"
    }
}

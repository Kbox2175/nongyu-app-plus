package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable

/**
 * 教务 `course` 回传体里的一条课程（对应后端 `schema/course.json` 的 `items`）。
 *
 * ## 为什么字段全是可空 String
 *
 * 后端 schema 里 14 个字段**全部声明为 `"type": "string"` 且都非必需**（只有
 * `courseName` 在 schema 的 `required` 里）。旧版 extractor 用 `pickCell` / `?.trim() || ""`
 * 兜底，所以运行时「键恒在、值可能是空串」或「键整个消失」两种都可能出现。
 *
 * 一律收成 `String?` + 默认 `null`：缺键不炸，空串由映射层当空处理。
 *
 * ## 字段来源
 *
 * `nongyu-backend-plus/docs/tech/14类回传JSON字段形状清单.md` §1 逐字段带来源行号。
 * 这里只收课表渲染真正要用的 6 个，其余（credit/hours/labHours/assessmentMethod/
 * enrollmentType/blendedTeaching/campus/weeklyHours）暂不进 DTO：
 * 不用的字段留在里面会诱使上层误以为“已有数据”，实际没人消费。
 * 将来详情卡要展示学分时再加，而那一步的判据是「谁消费谁声明」。
 */
@Serializable
data class CourseItem(
    /** 课程中文名。schema 唯一必需字段，为空整行会被丢弃。 */
    val courseName: String? = null,

    /** 教务课程 id，纯数字串（如 `300513911`）。用于拼复合 id。 */
    val courseId: String? = null,

    /** 教学周，如 `1-16`、`8`。 */
    val weeks: String? = null,

    /** 上课地点，**可能多行**（`\n` 分隔），与多段 scheduleTime 逐行对应。 */
    val classroom: String? = null,

    /** 「星期-节次」，如 `3-3,3-4`、`2-9,2-10(单)`；**可能是不可落格散文**（如「自行在线学习」）。 */
    val scheduleTime: String? = null,

    /** 教师姓名，可能为空串（纯线上课）。 */
    val teacher: String? = null,
)

/**
 * `course` payload 的容器。
 *
 * 后端 schema 的顶层要求 `payload` + `schemaVersion` 两个字段；
 * `success` / `fetchedAt` 可选。这里只声明消费的部分。
 *
 * 注意：该 DTO 用于解析 **GET /school/jiaowu/cache/course 命中时**的 `payload` 字段内部数组，
 * 不是整个响应体 —— 响应体由 [CacheGetResponse] 承接（命中/未命中两种 200 形状）。
 */
@Serializable
data class CoursePayload(
    val payload: List<CourseItem>? = null,
    val schemaVersion: Int? = null,
)

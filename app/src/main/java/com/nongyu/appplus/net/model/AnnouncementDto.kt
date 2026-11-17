package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable

/**
 * 公告域 DTO（后端 `internal/content/announcement/handler.go`，2026-11-17 对账）。
 *
 * ## 响应形状（三种，按端点不同）
 * | 端点 | 形状 |
 * | --- | --- |
 * | `GET /announcements` 列表 | `{"items":[...],"next":"..."}` |
 * | `GET /announcements/:id` 详情 | `{"data":{publicView}}` |
 * | `GET /announcements/latest`（同 handler） | `{"data":null}` 表示**当前没有生效公告** |
 *
 * ⚠️ `data` 为 `null` 是**正常状态**（没有生效公告），不是错误。判断「有无公告」看 `data` 是否为 null，
 * 不要把它当失败。
 */

/** 公告本体（后端 `publicView`）。时间字段是 RFC3339 字符串，可空。 */
@Serializable
data class AnnouncementDto(
    val id: Long? = null,
    val title: String? = null,
    val body: String? = null,
    /** RFC3339；未发布为 `null`。 */
    val publishedAt: String? = null,
    /** 生效起始时间（RFC3339，可空）。 */
    val effectiveFrom: String? = null,
    /** 失效时间（RFC3339，可空）。 */
    val expiresAt: String? = null,
    /** 是否置顶。 */
    val pinned: Boolean? = null,
)

/** `GET /announcements` 列表响应：`{"items":[...],"next":"..."}`。 */
@Serializable
data class AnnouncementListDto(
    val items: List<AnnouncementDto>? = null,
    val next: String? = null,
)

/**
 * 单条公告信封：`{"data":{publicView}}`。
 *
 * ⚠️ `data` 为 `null` 表示**当前没有生效公告**（后端 `c.JSON(200, gin.H{"data": nil})`），
 * 属正常状态，不是错误。调用方用 `data != null` 判断「有没有公告」。
 */
@Serializable
data class AnnouncementEnvelopeDto(
    val data: AnnouncementDto? = null,
)

package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable

/**
 * 站内信 DTO（后端 `internal/message/handler.go`，2026-11-17 对账）。
 *
 * ## 响应形状（每个端点不同，不能共用）
 * | 端点 | 形状 |
 * | --- | --- |
 * | `GET /messages` 列表 | `{"items":[...],"next":"..."}` |
 * | `GET /messages/unread-count` | `{"count":n}` |
 * | `PATCH /messages/:id/read` | `{"data":{messageView}}` |
 * | `POST /messages/read-all` | `{"updated":n}` |
 * | `DELETE /messages/:id` | `{"status":"ok"}` |
 *
 * `POST /messages` 是**内部投递**（服务端写站内信），Android 用户端不用，故不建接口方法。
 */

/** 站内信本体（后端 `messageView`）。时间字段 RFC3339 字符串。 */
@Serializable
data class MessageDto(
    val id: Long? = null,
    val recipientId: Long? = null,
    /** 消息类型标识。 */
    val kind: String? = null,
    /** 来源业务域（`omitempty`）。 */
    val sourceDomain: String? = null,
    /** 来源对象 id（`omitempty`，字符串形态）。 */
    val sourceObjectId: String? = null,
    val title: String? = null,
    val body: String? = null,
    /** 命中次数。 */
    val hits: Int? = null,
    /** 已读时间为 `null` 表示未读。 */
    val readAt: String? = null,
    /** 软删时间（`omitempty`）。 */
    val deletedAt: String? = null,
    val createdAt: String? = null,
    /** 发送者（`omitempty`）。 */
    val senderId: Long? = null,
)

/** `GET /messages` 列表响应：`{"items":[...],"next":"..."}`。 */
@Serializable
data class MessageListDto(
    val items: List<MessageDto>? = null,
    val next: String? = null,
)

/** `GET /messages/unread-count`：`{"count":n}`。 */
@Serializable
data class UnreadCountDto(
    val count: Int? = null,
)

/** `PATCH /messages/:id/read`：`{"data":{messageView}}`。 */
@Serializable
data class MessageEnvelopeDto(
    val data: MessageDto? = null,
)

/** `POST /messages/read-all`：`{"updated":n}`。 */
@Serializable
data class MarkAllReadDto(
    val updated: Int? = null,
)

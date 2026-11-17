package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.model.MarkAllReadDto
import com.nongyu.appplus.net.model.MessageEnvelopeDto
import com.nongyu.appplus.net.model.MessageListDto
import com.nongyu.appplus.net.model.StatusOkDto
import com.nongyu.appplus.net.model.UnreadCountDto
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 站内信（后端 `internal/message/handler.go`，4 条用户侧路由）。
 *
 * ## 全部需要 Bearer
 * 后端 `message.Register` 走 `userIDOf`，身份只来自认证中间件，无票即 401。
 * 所以本接口**没有** `@PublicEndpoint`。
 *
 * ## 响应形状（每端点不同，不能共用）
 * | 端点 | 形状 |
 * | --- | --- |
 * | `GET /messages` | `{"items":[...],"next":"..."}` |
 * | `GET /messages/unread-count` | `{"count":n}` |
 * | `PATCH /messages/:id/read` | `{"data":{messageView}}` |
 * | `POST /messages/read-all` | `{"updated":n}` |
 * | `DELETE /messages/:id` | `{"status":"ok"}` |
 *
 * ## 不接 `POST /messages`
 * 那条是**内部投递**（服务端往用户信箱写站内信），不是用户端操作。
 */
interface MessageApi {

    /** `GET /messages?cursor=&limit=` —— 列表。需 Bearer。 */
    @GET("messages")
    suspend fun list(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<MessageListDto>

    /** `GET /messages/unread-count` —— 未读数。需 Bearer。 */
    @GET("messages/unread-count")
    suspend fun unreadCount(): Response<UnreadCountDto>

    /** `PATCH /messages/:id/read` —— 标记单条已读。需 Bearer。 */
    @PATCH("messages/{id}/read")
    suspend fun markRead(@Path("id") id: Long): Response<MessageEnvelopeDto>

    /** `POST /messages/read-all` —— 全部标记已读。需 Bearer。 */
    @POST("messages/read-all")
    suspend fun markAllRead(): Response<MarkAllReadDto>

    /** `DELETE /messages/:id` —— 删除单条（软删）。需 Bearer。 */
    @DELETE("messages/{id}")
    suspend fun delete(@Path("id") id: Long): Response<StatusOkDto>
}

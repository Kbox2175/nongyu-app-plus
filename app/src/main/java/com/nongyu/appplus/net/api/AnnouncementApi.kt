package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.api.http.PublicEndpoint
import com.nongyu.appplus.net.model.AnnouncementEnvelopeDto
import com.nongyu.appplus.net.model.AnnouncementListDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 公告域（后端 `internal/content/announcement/handler.go`�? �?public 路由；admin 5 条不接）�? *
 * ## 两条都是**匿名可读**
 * 后端 `RegisterPublic` �?`viewerOf` 允许�?null（返回空 `Viewer{}`），
 * 所以无 token 也能读。都�?[PublicEndpoint]�? *
 * ## 鉴权分级�?按可见范围过�?，不�?拒绝访问"
 * 公告�?`audience`（scope / roles / college / grade）；匿名读者只会拿�?`public` 范围的�? * 客户端不需要自己过滤，后端已按 viewer 过滤完毕�? *
 * ## 响应形状
 * - 列表：`{"items":[...],"next":"..."}`
 * - 详情：`{"data":{...}}`�?*`data` �?null 表示当前没有生效公告**（正常状态，不是错误�? */
interface AnnouncementApi {

    /** `GET /announcements?cursor=&limit=` —�?列表，匿名可读�?*/
    @PublicEndpoint
    @GET("announcements")
    suspend fun list(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<AnnouncementListDto>

    /**
     * `GET /announcements/:id` —�?详情，匿名可读�?     *
     * ⚠️ 后端**�?`/announcements/latest` 也挂在这条路由上**（`:id` �?`latest` 时返回最新生效公告）�?     * 不是并列注册的独立路由。所以取"最新公�?应传 `id = "latest"`，用 [latest]�?     */
    @PublicEndpoint
    @GET("announcements/{id}")
    suspend fun detail(@Path("id") id: String): Response<AnnouncementEnvelopeDto>

    /**
     * `GET /announcements/latest` —�?当前最新生效公告，匿名可读�?     *
     * 走的是同一�?`:id` 路由（后端把它做�?`:id` 的特殊取值）�?     * 所以这里把路径参数固定�?`"latest"`�?     * 没有生效公告时返�?`{"data":null}` —�?属正常状态，不是错误�?     */
    @PublicEndpoint
    @GET("announcements/latest")
    suspend fun latest(): Response<AnnouncementEnvelopeDto>
}

package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.model.ReleaseLatestDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 应用发布与更新（后端 `internal/release/`，2026-11-25 新增）。
 *
 * ## App 侧三条只读端点（**需 Bearer**，后端 `RegisterSession`）
 * | 路由 | 用途 |
 * | --- | --- |
 * | `GET /releases/android/latest?applicationId=` | 检查更新（版本信息，不含 APK） |
 * | `GET /releases/android/latest/pack?applicationId=` | 下载最新版本 APK |
 * | `GET /releases/android/:versionCode/pack?applicationId=` | 下载指定版本 APK |
 *
 * ## 使用约束
 * - `applicationId` 必传：用 `BuildConfig.APPLICATION_ID`，**不要写死**包名
 * - 判新旧用 `versionCode`（整数），`versionName` 只作展示
 * - `minSupportedVersionCode` 是**强制更新门槛**：本地低于它必须阻断使用
 * - 下载后可用 [ReleaseLatestDto.sha256] 校验完整性
 *
 * ## 为什么下载走接口而不是 downloadUrl
 * 后端的 `downloadUrl` 指向本服务的相对路径（不是对象存储预签名 URL），
 * 直接调本接口即可复用 NY1 签名与 Bearer，也便于统一处理鉴权失败。
 */
interface ReleaseApi {

    /**
     * `GET /releases/android/latest?applicationId=` —— 检查更新。
     *
     * `hasRelease=false` 表示还没有任何已发布版本（正常状态，不是错误）。
     */
    @GET("releases/android/latest")
    suspend fun latest(@Query("applicationId") applicationId: String): Response<ReleaseLatestDto>

    /**
     * `GET /releases/android/latest/pack?applicationId=` —— 下载最新版本 APK。
     *
     * @Streaming 让 Retrofit 不把整包读进内存（APK 可达数 MB）。
     * 调用方必须自己在 IO 线程读流并关闭。
     */
    @Streaming
    @GET("releases/android/latest/pack")
    suspend fun latestPack(
        @Query("applicationId") applicationId: String,
    ): Response<ResponseBody>

    /**
     * `GET /releases/android/:versionCode/pack?applicationId=` —— 下载指定版本 APK。
     *
     * @param versionCode 必须是整数（后端会校验，非整数回 400）。
     */
    @Streaming
    @GET("releases/android/{versionCode}/pack")
    suspend fun versionPack(
        @Path("versionCode") versionCode: Long,
        @Query("applicationId") applicationId: String,
    ): Response<ResponseBody>
}

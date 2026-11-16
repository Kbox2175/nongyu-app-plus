package com.nongyu.appplus.skin

import android.util.Log
import com.nongyu.appplus.BuildConfig
import com.nongyu.appplus.net.NetConfig
import com.nongyu.appplus.net.api.http.AndroidLogger
import com.nongyu.appplus.net.api.http.AppSignInterceptor
import com.nongyu.appplus.net.api.http.OkHttpFactory
import com.nongyu.appplus.net.api.http.SafeLoggingInterceptor
import com.nongyu.appplus.net.core.UrlBuilder
import com.nongyu.appplus.net.sign.AppRequestSigner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 皮肤包「目录接口 + 包体」的**窄 HTTP 层**（票 #53）。
 *
 * ## 为什么不走 NetHolder / Retrofit
 * - `NetHolder.kt` 正被另一会话改动，本票不碰 net 包任何文件；
 * - `OkHttpFactory.build()` 强制挂 `AuthInterceptor`，而它对非 Retrofit 请求
 *   一律视为「需鉴权」，无 token 时会本地短路 —— 而 `/skins` 下的端点免 Bearer，
 *   挂它反而发不出去。
 * ⇒ 这里用 `OkHttpClient.Builder` 精简构造：**只挂 AppSignInterceptor**（NY1 签名，
 * 复用 `AppRequestSigner.fromBase64` 与 net 包同一把钥匙）与日志拦截器，
 * 超时照抄 [OkHttpFactory] 的常量。不建通用网络层，不引入新依赖。
 *
 * ## 契约
 * `docs/research/皮肤包下载器-服务端对接须知.md` §1–§3：
 * `GET /skins/current` → JSON；`GET {downloadUrl}` → zip 字节 + `X-Nongyu-Pack-Sha256` 头。
 * 三端点免 Bearer 但仍需 NY1 签名（AppSignInterceptor 自动加）。
 *
 * 所有失败都以 [Result.failure] 返回，**不抛异常**（票面「失败静默」）。
 * release 变体 `BASE_URL` 为空时 [UrlBuilder.normalize] 会抛异常，同样被
 * `runCatching` 兜住 → 只记日志、不影响 App。
 */
class SkinCatalogFetcher(
    private val client: OkHttpClient = defaultClient(),
) {

    /** 目录响应原文 + 是否有包。JSON → Map 的转换在 [SkinCatalogJson]（薄适配）。 */
    data class CatalogReply(val body: String)

    /** 包体响应：zip 字节 + 整包 sha256 响应头。 */
    data class PackReply(val bytes: ByteArray, val packSha256Header: String?)

    /** 拉目录。非 2xx / IO / 空 body / release 未配域名都返回 failure。 */
    fun fetchCatalog(): Result<CatalogReply> = runCatching {
        val url = UrlBuilder.normalize(NetConfig.BASE_URL) + CATALOG_PATH
        newCall(url).use { response ->
            if (!response.isSuccessful) throw IOException("目录接口 HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IOException("目录接口空响应")
            CatalogReply(body)
        }
    }

    /** 下包体（相对路径拼 base）。返回字节与 sha256 头，不落盘。 */
    fun fetchPack(relativeOrAbsoluteUrl: String): Result<PackReply> = runCatching {
        val url = if (relativeOrAbsoluteUrl.startsWith("http", ignoreCase = true)) {
            relativeOrAbsoluteUrl
        } else {
            UrlBuilder.normalize(NetConfig.BASE_URL) + relativeOrAbsoluteUrl.trimStart('/')
        }
        newCall(url).use { response ->
            if (!response.isSuccessful) throw IOException("包体下载 HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("包体空响应")
            if (bytes.isEmpty()) throw IOException("包体字节为空")
            PackReply(bytes, response.header(PACK_SHA_HEADER))
        }
    }

    private fun newCall(url: String): Response =
        client.newCall(Request.Builder().url(url).get().build()).execute()

    companion object {
        const val CATALOG_PATH = "skins/current"
        const val PACK_SHA_HEADER = "X-Nongyu-Pack-Sha256"

        /** 精简 client：与 net 包同一把签名器，但不挂 AuthInterceptor。 */
        fun defaultClient(): OkHttpClient {
            val signer = AppRequestSigner.fromBase64(
                BuildConfig.APP_SIGNING_KEY_ID,
                BuildConfig.APP_SIGNING_KEY_B64,
            )
            return OkHttpClient.Builder()
                .connectTimeout(OkHttpFactory.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(OkHttpFactory.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(OkHttpFactory.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(OkHttpFactory.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor(AppSignInterceptor(signer))
                .addInterceptor(
                    SafeLoggingInterceptor(enabled = BuildConfig.DEBUG, logger = AndroidLogger),
                )
                .build()
        }
    }
}

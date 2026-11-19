package com.nongyu.appplus.net.api.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebVPN 票失效转发拦截器的纯 JVM 测试：验证「只有 401/409 的响应会把码交给回调」，
 * 且**不消费响应体**（下游仍能读到 errorBody）。
 *
 * 业务判定（哪个码该弹窗）在 `webvpn/logic/WebVpnErrorClassifierTest` 里测；
 * 这里只测网络层的转发职责与边界。
 */
class WebVpnTicketRequiredInterceptorTest {

    private lateinit var server: MockWebServer
    private val seen = mutableListOf<Pair<String?, Int>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        seen.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            // 必须用具名参数：第二个位置参数是 logger（有默认值），
            // 尾随 lambda 会被解析到 logger 上而不是回调。
            WebVpnTicketRequiredInterceptor(
                onTicketRequired = { code, status -> seen += Pair(code, status) },
            ),
        )
        .build()

    @Test
    fun `409 缺票响应把码交给回调`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"code":"webvpn_ticket_missing","message":"尚未提交"}"""),
        )
        client().newCall(Request.Builder().url(server.url("/school/jiaowu/cache/course/fetch")).build())
            .execute().use { response ->
                assertEquals(409, response.code)
                // 关键：peekBody 不消费流，下游仍能读到错误体
                assertTrue(response.body?.string()?.contains("webvpn_ticket_missing") == true)
            }
        assertEquals(listOf("webvpn_ticket_missing" to 409), seen)
    }

    @Test
    fun `401 票失效响应把码交给回调`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"webvpn_ticket_expired","message":"票失效"}"""),
        )
        client().newCall(Request.Builder().url(server.url("/school/webvpn-session")).build())
            .execute().close()
        assertEquals(listOf("webvpn_ticket_expired" to 401), seen)
    }

    @Test
    fun `200 与 503 不触发回调`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"present":true}"""))
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setBody("""{"code":"cache_unavailable"}"""),
        )
        val c = client()
        c.newCall(Request.Builder().url(server.url("/school/webvpn-session")).build()).execute().close()
        c.newCall(Request.Builder().url(server.url("/school/jiaowu/cache/course/fetch")).build()).execute().close()
        assertTrue("非 401/409 不应转发", seen.isEmpty())
    }

    @Test
    fun `回调抛异常不会弄崩请求`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"x"}"""))
        val c = OkHttpClient.Builder()
            .addInterceptor(
                WebVpnTicketRequiredInterceptor(
                    onTicketRequired = { _, _ -> throw RuntimeException("boom") },
                ),
            )
            .build()
        c.newCall(Request.Builder().url(server.url("/a")).build()).execute().use {
            assertEquals(409, it.code)
        }
    }
}

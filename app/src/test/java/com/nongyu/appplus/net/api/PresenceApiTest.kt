package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.auth.AuthTokenProvider
import com.nongyu.appplus.net.auth.SessionInvalidListener
import com.nongyu.appplus.net.core.NongyuLogger
import com.nongyu.appplus.net.api.http.OkHttpFactory
import com.nongyu.appplus.net.model.NetJson
import com.nongyu.appplus.net.model.PresenceReportRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * `POST/GET /school/presence` 的端到端测试（后端 `internal/school/presence/handler.go`）。
 *
 * 契约要点：
 * - 请求体只有 `mode`，身份来自认证中间件（`school.CtxUserID`），**不接受 body 里的 userID**
 * - 响应键是 `userID`（大写 ID）
 * - `mode` 只接受 `direct` / `vpn`
 * - `GET` 返回 `{"mode","source"}`，`source` 区分 client / backend
 */
class PresenceApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PresenceApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpFactory.build(
            tokenProvider = AuthTokenProvider { "TEST-TOKEN" },
            sessionInvalidListener = SessionInvalidListener { },
            loggingEnabled = false,
            logger = NongyuLogger.Noop,
        )
        api = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .client(client)
            .addConverterFactory(NetJson.converterFactory())
            .build()
            .create(PresenceApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `上报 direct 成功解出 userID 与 mode`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"userID":"u-1","mode":"direct"}"""),
        )
        val body = json.encodeToString(
            PresenceReportRequest.serializer(),
            PresenceReportRequest(mode = "direct"),
        ).toRequestBody("application/json".toMediaType())

        val r = api.report(body)

        assertTrue(r.isSuccessful)
        assertEquals("u-1", r.body()?.userID)
        assertEquals("direct", r.body()?.mode)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/school/presence", req.path)
        assertEquals("Bearer TEST-TOKEN", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"direct\""))
    }

    @Test
    fun `上报 vpn 成功`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"userID":"u-1","mode":"vpn"}"""),
        )
        val body = json.encodeToString(
            PresenceReportRequest.serializer(),
            PresenceReportRequest(mode = "vpn"),
        ).toRequestBody("application/json".toMediaType())

        assertEquals("vpn", api.report(body).body()?.mode)
    }

    @Test
    fun `非法 mode 后端回 400 invalid_presence`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"code":"invalid_presence","message":"非法 mode"}"""),
        )
        val body = json.encodeToString(
            PresenceReportRequest.serializer(),
            PresenceReportRequest(mode = "bogus"),
        ).toRequestBody("application/json".toMediaType())

        val r = api.report(body)

        assertEquals(400, r.code())
        assertTrue(!r.isSuccessful)
    }

    @Test
    fun `判定返回 mode 与 source`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"mode":"vpn","source":"backend"}"""),
        )

        val r = api.decide()

        assertEquals("vpn", r.body()?.mode)
        assertEquals("backend", r.body()?.source)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `source client 表示来自新鲜上报`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"mode":"direct","source":"client"}"""),
        )
        assertEquals("client", api.decide().body()?.source)
    }

    @Test
    fun `请求体只含 mode 一个字段`() {
        val encoded = json.encodeToString(
            PresenceReportRequest.serializer(),
            PresenceReportRequest(mode = "direct"),
        )
        assertEquals("""{"mode":"direct"}""", encoded)
    }
}

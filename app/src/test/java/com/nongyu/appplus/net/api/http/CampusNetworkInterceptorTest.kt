package com.nongyu.appplus.net.api.http

import com.nongyu.appplus.net.auth.AuthTokenProvider
import com.nongyu.appplus.net.auth.SessionInvalidListener
import com.nongyu.appplus.net.core.CampusNetworkState
import com.nongyu.appplus.net.core.NongyuLogger
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `X-Nongyu-Campus-Network` 头的挂载行为（规格 §3.7）。
 *
 * 重点：
 * 1. `Unknown` 时**不挂头**（不冒充结论）；
 * 2. `Direct` / `Vpn` 时挂对应值；
 * 3. `/school/address` 与 `/school/fetch` 是豁免路径（它们本身用来决定形态）。
 */
class CampusNetworkInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        CampusNetworkState.reset()
        server.shutdown()
    }

    private fun fire(path: String): okhttp3.mockwebserver.RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = OkHttpFactory.build(
            tokenProvider = AuthTokenProvider { "T" },
            sessionInvalidListener = SessionInvalidListener { },
            loggingEnabled = false,
            logger = NongyuLogger.Noop,
            campusNetworkEnabled = true,
        )
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
        return server.takeRequest()
    }

    @Test
    fun `Unknown 时不挂头`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Unknown)
        val req = fire("/school/jiaowu/cache/course")
        assertNull(req.getHeader(CampusNetworkState.HEADER_NAME))
    }

    @Test
    fun `Direct 时挂 direct`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Direct)
        val req = fire("/school/jiaowu/cache/course")
        assertEquals("direct", req.getHeader(CampusNetworkState.HEADER_NAME))
    }

    @Test
    fun `Vpn 时挂 vpn`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Vpn)
        val req = fire("/school/jiaowu/cache/course")
        assertEquals("vpn", req.getHeader(CampusNetworkState.HEADER_NAME))
    }

    @Test
    fun `school address 是豁免路径`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Direct)
        val req = fire("/school/address")
        assertNull("取地址的端点不该带形态头", req.getHeader(CampusNetworkState.HEADER_NAME))
    }

    @Test
    fun `school fetch 是豁免路径`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Direct)
        val req = fire("/school/fetch")
        assertNull(req.getHeader(CampusNetworkState.HEADER_NAME))
    }

    @Test
    fun `未开启时完全不挂头`() {
        CampusNetworkState.update(CampusNetworkState.Presence.Direct)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val client = OkHttpFactory.build(
            tokenProvider = AuthTokenProvider { "T" },
            sessionInvalidListener = SessionInvalidListener { },
            loggingEnabled = false,
            logger = NongyuLogger.Noop,
            campusNetworkEnabled = false,
        )
        client.newCall(Request.Builder().url(server.url("/school/jiaowu/cache/course")).build())
            .execute().close()
        assertNull("未开启时行为与改动前一致", server.takeRequest().getHeader(CampusNetworkState.HEADER_NAME))
    }
}

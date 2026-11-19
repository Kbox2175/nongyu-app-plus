package com.nongyu.appplus.net.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.Charset

/**
 * 取数执行器（从 `CourseRepository` 抽出的通用轮子）。
 *
 * 15 类回传数据共用这套模型：后端给地址/方法/Cookie，客户端照着执行。
 * 这里钉住三件事：Cookie 拼装、字符集解码、参数校验。
 */
class RecipeFetcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RecipeFetcher.setClientForTest(
            okhttp3.OkHttpClient.Builder().build(),
        )
    }

    @After
    fun tearDown() {
        RecipeFetcher.resetForTest()
        server.shutdown()
    }

    @Test
    fun `按配方发 GET 并带回 Cookie`() {
        // 明确声明 UTF-8：本用例测的是「Cookie 与基本收发」，不是字符集推断。
        // 字符集推断另有两条专门的用例。
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html>课表</html>"),
        )

        val body = RecipeFetcher.fetch(
            address = server.url("/kbbanji.asp").toString(),
            method = "GET",
            cookies = listOf(RecipeFetcher.CookiePair("ASPSESSIONID", "abc123")),
        )

        assertEquals("<html>课表</html>", body)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("ASPSESSIONID=abc123", req.getHeader("Cookie"))
    }

    @Test
    fun `多个 Cookie 用分号空格拼接`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        RecipeFetcher.fetch(
            address = server.url("/x").toString(),
            method = "GET",
            cookies = listOf(
                RecipeFetcher.CookiePair("A", "1"),
                RecipeFetcher.CookiePair("B", "2"),
            ),
        )

        assertEquals("A=1; B=2", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `无 Cookie 时不挂该头`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        RecipeFetcher.fetch(server.url("/x").toString(), "GET", emptyList())

        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `method 为空时按 GET`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        RecipeFetcher.fetch(server.url("/x").toString(), null, emptyList())
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `按 Content-Type 的 charset 解码 GBK`() {
        val gbk = "课表内容".toByteArray(Charset.forName("GBK"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=GBK")
                .setBody(Buffer().write(gbk)),
        )

        val body = RecipeFetcher.fetch(server.url("/x").toString(), "GET", emptyList())

        assertEquals("课表内容", body)
    }

    @Test
    fun `缺 Content-Type 时按 GBK 兜底`() {
        val gbk = "课表".toByteArray(Charset.forName("GBK"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody(Buffer().write(gbk)),
        )

        assertEquals("课表", RecipeFetcher.fetch(server.url("/x").toString(), "GET", emptyList()))
    }

    @Test
    fun `非 2xx 抛异常`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val e = runCatching {
            RecipeFetcher.fetch(server.url("/x").toString(), "GET", emptyList())
        }.exceptionOrNull()

        assertTrue("应抛异常", e is IllegalStateException)
        assertTrue(e!!.message!!.contains("500"))
    }

    @Test
    fun `缺地址抛异常`() {
        val e = runCatching { RecipeFetcher.fetch(null, "GET", emptyList()) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test
    fun `空地址抛异常`() {
        val e = runCatching { RecipeFetcher.fetch("  ", "GET", emptyList()) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test
    fun `不支持的请求方式抛异常`() {
        val e = runCatching {
            RecipeFetcher.fetch(server.url("/x").toString(), "DELETE", emptyList())
        }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test
    fun `名字为空的 Cookie 被跳过`() {
        assertNull(RecipeFetcher.cookieHeaderOf(listOf(RecipeFetcher.CookiePair("", "v"))))
        assertEquals(
            "A=1",
            RecipeFetcher.cookieHeaderOf(
                listOf(RecipeFetcher.CookiePair("", "x"), RecipeFetcher.CookiePair("A", "1")),
            ),
        )
    }
}

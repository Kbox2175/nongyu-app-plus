package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.auth.AuthTokenProvider
import com.nongyu.appplus.net.auth.SessionInvalidListener
import com.nongyu.appplus.net.api.http.OkHttpFactory
import com.nongyu.appplus.net.core.NongyuLogger
import com.nongyu.appplus.net.model.NetJson
import com.nongyu.appplus.net.model.ReleaseLatestDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * 应用发布与更新（后端 `internal/release/`，2026-11-25 新增）。
 *
 * 重点：`hasRelease=false` 是正常态、强制更新门槛、applicationId 必传、
 * 下载端点用 @Streaming。
 */
class ReleaseApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ReleaseApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val appId = "com.nongyu.appplus"

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
            .create(ReleaseApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `有版本时解出全部字段`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"hasRelease":true,"applicationId":"com.nongyu.appplus",
                   "versionCode":67,"versionName":"0.0.59test","minSdk":26,
                   "minSupportedVersionCode":60,"changelog":"测试版",
                   "size":1722079,"sha256":"abc123",
                   "downloadUrl":"/releases/android/latest/pack",
                   "updatedAt":"2026-11-19T06:45:00Z"}""",
            ),
        )

        val r = api.latest(appId)
        val body = r.body()!!

        assertTrue(body.hasUpdate())
        assertEquals(67L, body.versionCode)
        assertEquals("0.0.59test", body.versionName)
        assertEquals(60L, body.minSupportedVersionCode)
        assertEquals("/releases/android/latest/pack", body.downloadUrl)
        assertEquals("abc123", body.sha256)

        val req = server.takeRequest()
        assertEquals("/releases/android/latest?applicationId=$appId", req.path)
        assertEquals("Bearer TEST-TOKEN", req.getHeader("Authorization"))
    }

    @Test
    fun `没有发布版本是正常态`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hasRelease":false}"""))

        val body = api.latest(appId).body()!!

        assertEquals(false, body.hasRelease)
        assertFalse(body.hasUpdate())
        assertNull(body.versionCode)
    }

    @Test
    fun `强制更新门槛判断`() {
        val floor = json.decodeFromString(
            ReleaseLatestDto.serializer(),
            """{"hasRelease":true,"versionCode":100,"minSupportedVersionCode":90}""",
        )
        assertTrue("本地 80 < 门槛 90 应强制", floor.isMandatoryFor(80))
        assertFalse("本地 95 >= 门槛 90 不强制", floor.isMandatoryFor(95))
    }

    @Test
    fun `新版本判断与强制更新分开`() {
        val dto = json.decodeFromString(
            ReleaseLatestDto.serializer(),
            """{"hasRelease":true,"versionCode":100,"minSupportedVersionCode":50}""",
        )
        assertTrue(dto.isNewerThan(90))
        assertFalse(dto.isMandatoryFor(90))
    }

    @Test
    fun `门槛缺省时不强制`() {
        val dto = json.decodeFromString(
            ReleaseLatestDto.serializer(),
            """{"hasRelease":true,"versionCode":100}""",
        )
        assertFalse("后端没给门槛就不强制", dto.isMandatoryFor(1))
    }

    @Test
    fun `最新包下载路径正确`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody("APK-BYTES"),
        )

        val r = api.latestPack(appId)

        assertTrue(r.isSuccessful)
        assertEquals("APK-BYTES", r.body()?.string())
        assertEquals(
            "/releases/android/latest/pack?applicationId=$appId",
            server.takeRequest().path,
        )
    }

    @Test
    fun `指定版本包路径正确`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("APK-67"),
        )

        val r = api.versionPack(67L, appId)

        assertEquals("APK-67", r.body()?.string())
        assertEquals(
            "/releases/android/67/pack?applicationId=$appId",
            server.takeRequest().path,
        )
    }

    @Test
    fun `applicationId 空串也会原样发送`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"hasRelease":false}"""))
        api.latest("")
        assertTrue(server.takeRequest().path!!.contains("applicationId="))
    }
}

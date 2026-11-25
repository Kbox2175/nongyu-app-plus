package com.nongyu.appplus.net.api

import com.nongyu.appplus.net.auth.AuthTokenProvider
import com.nongyu.appplus.net.auth.SessionInvalidListener
import com.nongyu.appplus.net.api.http.OkHttpFactory
import com.nongyu.appplus.net.core.NongyuLogger
import com.nongyu.appplus.net.model.NetJson
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
 * 教室地图底图 + 删自己的评论（后端 2026-11-25 新增）。
 */
class NewFeaturesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var classroom: ClassroomApi
    private lateinit var posts: PostApi
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
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/").toString())
            .client(client)
            .addConverterFactory(NetJson.converterFactory())
            .build()
        classroom = retrofit.create(ClassroomApi::class.java)
        posts = retrofit.create(PostApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------- 地图 meta ----------

    @Test
    fun `地图 meta 解出标定与四角`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"campus":"yaan","campusLabel":"雅安校区",
                   "width":2000,"height":1500,"coordSystem":"GCJ-02",
                   "uploaded":true,"calibrated":true,
                   "objectKey":"map-v3","hint":"可正常使用",
                   "calibration":{"ar":0.001,"ai":0.0,"tx0":103.0,"ty0":29.0,
                                  "lng0":103.0,"lat0":29.0,"kx":0.0,"ky":0.001},
                   "corners":{"topLeft":{"px":0,"py":0,"lat":29.98,"lng":103.0},
                              "bottomRight":{"px":2000,"py":1500,"lat":29.97,"lng":103.01}}}}""",
            ),
        )

        val body = classroom.mapMeta("yaan").body()?.data!!

        assertTrue(body.canShowMap())
        assertEquals("雅安校区", body.campusLabel)
        assertEquals("GCJ-02", body.coordSystem)
        assertEquals("map-v3", body.objectKey)
        assertEquals("可正常使用", body.hint)
        assertEquals(0.001, body.calibration?.ar ?: 0.0, 0.00001)
        assertEquals(29.98, body.corners?.get("topLeft")?.lat ?: 0.0, 0.001)
        assertEquals(2, body.corners?.size)

        val req = server.takeRequest()
        assertEquals("/classrooms/maps/yaan/meta", req.path)
        assertEquals("Bearer TEST-TOKEN", req.getHeader("Authorization"))
    }

    @Test
    fun `未上传时 hint 直接展示且不能取图`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"campus":"x","uploaded":false,"calibrated":false,
                   "hint":"底图尚未上传，请联系管理员"}}""",
            ),
        )
        val body = classroom.mapMeta("x").body()?.data!!
        assertFalse(body.canShowMap())
        assertNull(body.calibration)
        assertEquals("底图尚未上传，请联系管理员", body.hint)
    }

    @Test
    fun `标定缺省的地图也有 hint`() {
        val dto = json.decodeFromString(
            com.nongyu.appplus.net.model.ClassroomMapMetaDto.serializer(),
            """{"campus":"x","uploaded":true,"calibrated":false,"hint":"尚未标定"}""",
        )
        assertTrue(dto.canShowMap())
        assertNull(dto.calibration)
    }

    // ---------- 地图图片 ----------

    @Test
    fun `地图图片端点返回原始字节且带版本参数`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setHeader("ETag", "\"map-v3\"")
                .setBody("PNG-BYTES"),
        )

        val r = classroom.mapImage("yaan", version = "map-v3")

        assertTrue(r.isSuccessful)
        assertEquals("PNG-BYTES", r.body()?.string())
        assertEquals("\"map-v3\"", r.headers()["ETag"])

        val req = server.takeRequest()
        assertEquals("/classrooms/maps/yaan?v=map-v3", req.path)
    }

    @Test
    fun `地图图片不传版本时无 query`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNG"))
        classroom.mapImage("yaan")
        assertEquals("/classrooms/maps/yaan", server.takeRequest().path)
    }

    // ---------- 删评论 ----------

    @Test
    fun `删除自己的评论返回 status ok`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val r = posts.deleteReply(1L, 9L)

        assertEquals("ok", r.body()?.status)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/posts/1/replies/9", req.path)
        assertEquals("Bearer TEST-TOKEN", req.getHeader("Authorization"))
    }
}

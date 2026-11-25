package com.nongyu.appplus.map.logic

import com.nongyu.appplus.map.model.AnnotationKind
import com.nongyu.appplus.map.model.MapAnnotation
import com.nongyu.appplus.map.model.MapPoint
import com.nongyu.appplus.map.model.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 地图纯逻辑单测（视口换算 / 标注命中 / geo URI）。 */
class MapGeometryTest {

    // ---------- 坐标换算 ----------

    @Test
    fun `imageToScreen 与 screenToImage 互逆`() {
        val vp = MapViewport(offsetX = 50f, offsetY = -20f, scale = 2f)
        val p = MapPoint(30f, 40f)
        val screen = MapGeometry.imageToScreen(p, vp)
        assertEquals(110f, screen.x, 0.001f)
        assertEquals(60f, screen.y, 0.001f)

        val back = MapGeometry.screenToImage(screen, vp)
        assertEquals(p.x, back.x, 0.001f)
        assertEquals(p.y, back.y, 0.001f)
    }

    @Test
    fun `单位视口下两坐标相等`() {
        val p = MapPoint(12f, 34f)
        val screen = MapGeometry.imageToScreen(p, MapViewport())
        assertEquals(12f, screen.x, 0.001f)
        assertEquals(34f, screen.y, 0.001f)
    }

    // ---------- 平移夹取 ----------

    @Test
    fun `图比屏小时居中`() {
        val r = MapGeometry.clampPan(
            MapViewport(), 100f, 100f,
            imageWidth = 400, imageHeight = 300,
            screenWidth = 1000, screenHeight = 1000,
        )
        assertEquals(300f, r.offsetX, 0.001f) // (1000-400)/2
        assertEquals(350f, r.offsetY, 0.001f) // (1000-300)/2
    }

    @Test
    fun `图比屏大时夹在边界内不露空白`() {
        val r = MapGeometry.clampPan(
            MapViewport(), 500f, 500f,
            imageWidth = 2000, imageHeight = 2000,
            screenWidth = 1000, screenHeight = 1000,
        )
        assertTrue("offsetX 不能为正", r.offsetX <= 0f)
        assertTrue("offsetX 不能小于 screen-image", r.offsetX >= -1000f)
        assertTrue("offsetY 不能为正", r.offsetY <= 0f)
    }

    // ---------- 视口约束 ----------

    @Test
    fun `缩放下限为1上限为8`() {
        assertEquals(1f, MapViewport().zoom(0.5f).scale, 0.001f)
        assertEquals(8f, MapViewport().zoom(99f).scale, 0.001f)
        assertEquals(3f, MapViewport().zoom(3f).scale, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `缩放倍率小于1抛异常`() {
        MapViewport(scale = 0.5f)
    }

    @Test
    fun `pan 累加位移`() {
        val r = MapViewport(offsetX = 10f, offsetY = 20f).pan(-5f, 7f)
        assertEquals(5f, r.offsetX, 0.001f)
        assertEquals(27f, r.offsetY, 0.001f)
    }

    // ---------- 命中判定 ----------

    private fun pointAnnotation(id: String, x: Float, y: Float) = MapAnnotation(
        id = id, kind = AnnotationKind.POINT,
        points = listOf(MapPoint(x, y)), campus = "yaan",
    )

    private fun lineAnnotation() = MapAnnotation(
        id = "line", kind = AnnotationKind.LINE,
        points = listOf(MapPoint(0f, 0f), MapPoint(100f, 0f)), campus = "yaan",
    )

    @Test
    fun `点命中在容差内`() {
        assertTrue(MapGeometry.hitTest(pointAnnotation("p", 50f, 50f), MapPoint(52f, 51f), 5f))
        assertFalse(MapGeometry.hitTest(pointAnnotation("p", 50f, 50f), MapPoint(60f, 50f), 5f))
    }

    @Test
    fun `折线命中中点`() {
        assertTrue(MapGeometry.hitTest(lineAnnotation(), MapPoint(50f, 2f), 5f))
        assertFalse(MapGeometry.hitTest(lineAnnotation(), MapPoint(50f, 20f), 5f))
    }

    @Test
    fun `findHit 返回最上层（最后画的）`() {
        val list = listOf(
            pointAnnotation("first", 50f, 50f),
            pointAnnotation("second", 50f, 50f),
        )
        assertEquals("second", MapGeometry.findHit(list, MapPoint(50f, 50f), 5f)?.id)
    }

    @Test
    fun `findHit 未命中返回 null`() {
        assertNull(MapGeometry.findHit(listOf(pointAnnotation("p", 0f, 0f)), MapPoint(500f, 500f), 5f))
    }

    @Test
    fun `点到线段距离`() {
        val d = MapGeometry.distanceToSegment(
            MapPoint(50f, 3f), MapPoint(0f, 0f), MapPoint(100f, 0f),
        )
        assertEquals(3f, d, 0.001f)
    }

    // ---------- 数据模型约束 ----------

    @Test(expected = IllegalArgumentException::class)
    fun `折线少于两点抛异常`() {
        MapAnnotation(
            id = "bad", kind = AnnotationKind.LINE,
            points = listOf(MapPoint(0f, 0f)), campus = "yaan",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空 campus 抛异常`() {
        pointAnnotation("p", 0f, 0f).copy(campus = "")
    }

    // ---------- geo URI ----------

    @Test
    fun `无 label 的 geo URI`() {
        assertEquals("geo:29.98,103.0", MapGeoUri.build(29.98, 103.0))
    }

    @Test
    fun `带 label 的 geo URI 含 q 参数`() {
        val uri = MapGeoUri.build(29.98, 103.0, "一教")
        assertEquals("geo:29.98,103.0?q=29.98,103.0(%E4%B8%80%E6%95%99)", uri)
    }

    @Test
    fun `URL 编码空格为 percent20 而非加号`() {
        val uri = MapGeoUri.build(1.0, 2.0, "A B")
        assertTrue(uri.contains("A%20B"))
        assertFalse(uri.contains("A+B"))
    }

    @Test
    fun `空白 label 等同无 label`() {
        assertEquals("geo:1.0,2.0", MapGeoUri.build(1.0, 2.0, "   "))
    }

    @Test
    fun `unreserved 字符不被编码`() {
        val uri = MapGeoUri.build(1.0, 2.0, "A-1")
        assertTrue("A-1 原样保留", uri.contains("A-1"))
    }
}

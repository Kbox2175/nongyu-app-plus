package com.nongyu.appplus.map.logic

import com.nongyu.appplus.map.model.AnnotationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调用方传入的标注 JSON 解析测试（只读契约）。 */
class MapAnnotationJsonTest {

    @Test
    fun `解析点线字三种标注`() {
        val raw = """[
            {"kind":"POINT","points":[{"x":10,"y":20}],"colorArgb":-15626753},
            {"kind":"LINE","points":[{"x":0,"y":0},{"x":100,"y":100}],"colorArgb":-15626753},
            {"kind":"TEXT","points":[{"x":50,"y":60}],"text":"第一教学楼","colorArgb":-15626753}
        ]"""
        val list = MapAnnotationJson.parse(raw, campus = "yaan")

        assertEquals(3, list.size)
        assertEquals(AnnotationKind.POINT, list[0].kind)
        assertEquals(10f, list[0].anchor.x, 0.001f)
        assertEquals(AnnotationKind.LINE, list[1].kind)
        assertEquals(2, list[1].points.size)
        assertEquals(AnnotationKind.TEXT, list[2].kind)
        assertEquals("第一教学楼", list[2].text)
        assertTrue(list.all { it.campus == "yaan" })
        assertEquals(-15626753, list[0].colorArgb)
    }

    @Test
    fun `kind 大小写不敏感`() {
        val raw = """[{"kind":"point","points":[{"x":1,"y":2}]}]"""
        assertEquals(AnnotationKind.POINT, MapAnnotationJson.parse(raw, "yaan").first().kind)
    }

    @Test
    fun `空或空白 JSON 返回空列表`() {
        assertTrue(MapAnnotationJson.parse(null, "yaan").isEmpty())
        assertTrue(MapAnnotationJson.parse("", "yaan").isEmpty())
        assertTrue(MapAnnotationJson.parse("   ", "yaan").isEmpty())
    }

    @Test
    fun `非法 JSON 返回空列表不抛`() {
        assertTrue(MapAnnotationJson.parse("{not json", "yaan").isEmpty())
    }

    @Test
    fun `非法 kind 的条目被跳过`() {
        val raw = """[
            {"kind":"CIRCLE","points":[{"x":1,"y":2}]},
            {"kind":"POINT","points":[{"x":3,"y":4}]}
        ]"""
        val list = MapAnnotationJson.parse(raw, "yaan")
        assertEquals(1, list.size)
        assertEquals(3f, list.first().anchor.x, 0.001f)
    }

    @Test
    fun `点数不合法的条目被跳过`() {
        val raw = """[
            {"kind":"LINE","points":[{"x":1,"y":2}]},
            {"kind":"POINT","points":[{"x":1,"y":2},{"x":3,"y":4}]},
            {"kind":"TEXT","points":[{"x":5,"y":6}]}
        ]"""
        val list = MapAnnotationJson.parse(raw, "yaan")
        assertEquals(1, list.size)
        assertEquals(AnnotationKind.TEXT, list.first().kind)
    }

    @Test
    fun `缺 colorArgb 用默认色`() {
        val raw = """[{"kind":"POINT","points":[{"x":1,"y":2}]}]"""
        assertEquals(
            com.nongyu.appplus.map.model.MapAnnotation.DEFAULT_COLOR,
            MapAnnotationJson.parse(raw, "yaan").first().colorArgb,
        )
    }

    @Test
    fun `多余字段被忽略`() {
        val raw = """[{"kind":"POINT","points":[{"x":1,"y":2}],"campus":"other","id":"x"}]"""
        val list = MapAnnotationJson.parse(raw, "yaan")
        assertEquals(1, list.size)
        assertEquals("yaan", list.first().campus) // campus 以参数为准，忽略 JSON 里的
    }

    @Test
    fun `坐标缺省的点被过滤`() {
        val raw = """[{"kind":"POINT","points":[{"x":1},{"y":2},{"x":3,"y":4}]}]"""
        val list = MapAnnotationJson.parse(raw, "yaan")
        assertEquals(1, list.size)
        assertEquals(1, list.first().points.size)
        assertEquals(3f, list.first().anchor.x, 0.001f)
    }
}

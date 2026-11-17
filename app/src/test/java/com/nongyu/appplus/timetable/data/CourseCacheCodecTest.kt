package com.nongyu.appplus.timetable.data

import com.nongyu.appplus.net.model.CourseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseCacheCodec] 的单测。
 *
 * 覆盖的是**缓存可用性判定**的每个分支 —— 这些分支决定「要不要联网」，
 * 判错就会变成「有缓存还在每次进页面都打网络」或者「缓存坏了却永远不联网」，
 * 两者都是用户能直接感知的坏行为。
 */
class CourseCacheCodecTest {

    private fun item(
        name: String = "人工智能导论",
        id: String = "300513911",
        weeks: String = "8-11周(2学时)",
        room: String = "10-B308室",
        time: String = "3-1\n3-2",
        teacher: String = "林野",
    ) = CourseItem(
        courseName = name,
        courseId = id,
        weeks = weeks,
        classroom = room,
        scheduleTime = time,
        teacher = teacher,
    )

    // ---- 从未写过缓存 ----

    @Test
    fun `从未写过缓存时返回空列表`() {
        val got = CourseCacheCodec.decode(raw = null, storedVersion = CourseCacheCodec.VERSION)
        assertTrue("null 应视为没有可用数据", got.isEmpty())
    }

    // ---- 版本判定 ----

    @Test
    fun `版本不匹配时整份丢弃`() {
        val encoded = CourseCacheCodec.encode(listOf(item()))!!
        // 模拟「缓存结构已升级」：老版本号读不出内容
        val got = CourseCacheCodec.decode(raw = encoded, storedVersion = CourseCacheCodec.VERSION - 1)
        assertTrue("版本不匹配必须整份丢弃，不能试图兼容", got.isEmpty())
    }

    @Test
    fun `版本号缺失（存的是 0）时视为无效`() {
        val encoded = CourseCacheCodec.encode(listOf(item()))!!
        // SharedPreferences 的 getInt(key, 0) 默认值就是 0
        val got = CourseCacheCodec.decode(raw = encoded, storedVersion = 0)
        assertTrue(got.isEmpty())
    }

    // ---- 内容损坏 ----

    @Test
    fun `内容不是合法 JSON 时返回空列表而不是抛异常`() {
        val got = CourseCacheCodec.decode(
            raw = "{ 这不是 JSON",
            storedVersion = CourseCacheCodec.VERSION,
        )
        assertTrue("坏缓存要静默丢弃，不能崩", got.isEmpty())
    }

    @Test
    fun `内容 JSON 合法但结构不对时返回空列表`() {
        // 是合法 JSON，但顶层不是数组
        val got = CourseCacheCodec.decode(
            raw = """{"foo":"bar"}""",
            storedVersion = CourseCacheCodec.VERSION,
        )
        assertTrue(got.isEmpty())
    }

    // ---- 正常往返 ----

    @Test
    fun `编码再解码能还原全部字段`() {
        val original = listOf(
            item(),
            item(name = "大学生心理健康", id = "300500001", time = "1-1\n1-2"),
        )
        val encoded = CourseCacheCodec.encode(original)
        assertNotNull("非空列表必须能编码", encoded)

        val decoded = CourseCacheCodec.decode(encoded, CourseCacheCodec.VERSION)
        assertEquals(2, decoded.size)
        assertEquals("人工智能导论", decoded[0].courseName)
        assertEquals("10-B308室", decoded[0].classroom)
        // 多行 scheduleTime 必须原样保留（解析层依赖 \n 分段）
        assertEquals("3-1\n3-2", decoded[0].scheduleTime)
        assertEquals("300500001", decoded[1].courseId)
    }

    @Test
    fun `未知字段不会导致解码失败`() {
        // 后端将来给 payload 加字段时，老客户端不该因为看不懂就丢掉整份缓存
        val raw = """
            [{"courseName":"人工智能导论","courseId":"3001","brandNewField":"x"}]
        """.trimIndent()
        val got = CourseCacheCodec.decode(raw, CourseCacheCodec.VERSION)
        assertEquals(1, got.size)
        assertEquals("人工智能导论", got[0].courseName)
    }

    // ---- 写入保护 ----

    @Test
    fun `空列表不覆盖已有缓存`() {
        // 一次失败的拉取拿到空数据，绝不能把好缓存抹掉
        assertNull("空列表应返回 null 表示不写", CourseCacheCodec.encode(emptyList()))
    }
}

package com.nongyu.appplus.timetable.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务课表页 HTML 解析。
 *
 * **用真实夹具测**（`app/src/test/resources/timetable_real.html`，
 * 复制自 `nongyu-backend-plus/testdata/jiaowu/real-2026-11-11/1-课表.html`，脱敏真实页面）。
 * 这样解析器对着真页面回归，而不是对着我编的样例。
 */
class CourseHtmlParserTest {

    private val realHtml: String by lazy {
        CourseHtmlParserTest::class.java.classLoader
            .getResourceAsStream("timetable_real.html")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    @Test
    fun `真实页面能解析出课程`() {
        val r = CourseHtmlParser.parse(realHtml)
        assertTrue("真实课表页应被判为课表页", r.looksLikeTimetable)
        assertTrue("应解析出课程，实际 ${r.items.size} 条", r.items.isNotEmpty())
    }

    @Test
    fun `不把下方课程明细表当成课程格子`() {
        val r = CourseHtmlParser.parse(realHtml)
        val junk = setOf("校区", "课程名称", "编号", "周次", "教室", "上课时间", "学分", "上午", "下午", "晚上", "时间")
        val bad = r.items.map { it.courseName.orEmpty() }.filter { it in junk }
        assertTrue("明细表表头词不该出现在课程名里，实际: $bad", bad.isEmpty())

        // 也不该出现纯数字课程名（如课程编号 300539222）
        val numeric = r.items.map { it.courseName.orEmpty() }.filter { n -> n.isNotEmpty() && n.all { it.isDigit() } }
        assertTrue("不该把课程编号当课程名，实际: $numeric", numeric.isEmpty())
    }

    @Test
    fun `解析出真实课程名与教室`() {
        val r = CourseHtmlParser.parse(realHtml)
        val names = r.items.map { it.courseName.orEmpty() }.toSet()
        // 这几门在真实页面里必然存在
        assertTrue("应含「离散数学」，实际: $names", names.any { it.contains("离散数学") })
        assertTrue("应含「大学物理A」，实际: $names", names.any { it.contains("大学物理") })
        assertTrue("应含「数据结构」，实际: $names", names.any { it.contains("数据结构") })

        val withRoom = r.items.firstOrNull { it.courseName.orEmpty().contains("离散数学") }
        assertFalse("离散数学应解析出教室", withRoom?.classroom.isNullOrBlank())
    }

    @Test
    fun `scheduleTime 是星期-节次格式且星期在 1 到 7`() {
        val r = CourseHtmlParser.parse(realHtml)
        for (item in r.items) {
            val s = item.scheduleTime.orEmpty()
            assertTrue("scheduleTime 不该为空: ${item.courseName}", s.isNotBlank())
            for (line in s.split('\n')) {
                val parts = line.split('-')
                assertEquals("每段应是 星期-节次: $line", 2, parts.size)
                val day = parts[0].toInt()
                val period = parts[1].toInt()
                assertTrue("星期应在 1..7，实际 $day", day in 1..7)
                assertTrue("节次应在 1..12，实际 $period", period in 1..12)
            }
        }
    }

    @Test
    fun `周次被完整保留下来供后续解析`() {
        val r = CourseHtmlParser.parse(realHtml)
        val withWeeks = r.items.filter { !it.weeks.isNullOrBlank() }
        assertTrue("应有课程带周次", withWeeks.isNotEmpty())
        // 真实格式带「周」字与学时括号
        assertTrue(
            "周次应保留原始形态（含周字），实际样例: ${withWeeks.take(3).map { it.weeks }}",
            withWeeks.any { it.weeks!!.contains("周") },
        )
    }

    @Test
    fun `课程条数在合理范围`() {
        val r = CourseHtmlParser.parse(realHtml)
        // 真实页面 19 条左右；给宽区间，只防「扫了明细表」那种数量级错误
        assertTrue("课程条数应在 5..60，实际 ${r.items.size}", r.items.size in 5..60)
    }

    @Test
    fun `非课表页返回 looksLikeTimetable=false`() {
        val r = CourseHtmlParser.parse("<html><body>请登录</body></html>")
        assertFalse(r.looksLikeTimetable)
        assertTrue(r.items.isEmpty())
    }
}

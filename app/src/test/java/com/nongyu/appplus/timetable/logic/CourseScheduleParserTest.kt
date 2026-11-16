package com.nongyu.appplus.timetable.logic

import com.nongyu.appplus.net.model.CourseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务课表 payload → [com.nongyu.appplus.timetable.model.CourseEntry] 的映射。
 *
 * ## 样例全部来自后端文档的真实值
 *
 * `nongyu-backend-plus/docs/tech/14类回传JSON字段形状清单.md` §1 逐字段列了「已知形状」，
 * 本文件把它抄成测试数据。这样文档里那些脏数据（散文 scheduleTime、单双周后缀、
 * 多行地点）一旦解析器退化，测试立刻红，而不是等真机上看出来课表少了几门课。
 */
class CourseScheduleParserTest {

    // ---------- scheduleTime 解析 ----------

    @Test
    fun `基本时段 3-3 逗号 3-4 解析成两段`() {
        val slots = CourseScheduleParser.parseScheduleTime("3-3,3-4")
        assertEquals(2, slots.size)
        // 教务 3 = 周三；本仓 0 基 = 2
        assertEquals(2, slots[0].day)
        assertEquals(3, slots[0].startPeriod)
        assertEquals(2, slots[1].day)
        assertEquals(4, slots[1].startPeriod)
    }

    @Test
    fun `单周后缀被解析成 odd 且不再 even`() {
        val slots = CourseScheduleParser.parseScheduleTime("2-9,2-10(单)")
        assertEquals(2, slots.size)
        assertTrue("带(单)的段应是单周", slots[1].odd)
        assertFalse("带(单)的段不该是双周", slots[1].even)
        // 不带后缀的段仍是全周
        assertTrue(slots[0].odd)
        assertTrue(slots[0].even)
    }

    @Test
    fun `双周全角括号也能解析`() {
        val slots = CourseScheduleParser.parseScheduleTime("1-1（双）")
        assertEquals(1, slots.size)
        assertFalse(slots[0].odd)
        assertTrue(slots[0].even)
    }

    @Test
    fun `多行 scheduleTime 解析成多段`() {
        val slots = CourseScheduleParser.parseScheduleTime("3-3,3-4\n5-1,5-2")
        assertEquals(4, slots.size)
        assertEquals(2, slots[0].day)   // 周三
        assertEquals(4, slots[2].day)   // 周五
    }

    @Test
    fun `长破折号分隔也能解析`() {
        val slots = CourseScheduleParser.parseScheduleTime("3—3")
        assertEquals(1, slots.size)
        assertEquals(2, slots[0].day)
        assertEquals(3, slots[0].startPeriod)
    }

    @Test
    fun `散文型 scheduleTime 返回空`() {
        assertTrue(CourseScheduleParser.parseScheduleTime("自行在线学习").isEmpty())
        assertTrue(CourseScheduleParser.parseScheduleTime("").isEmpty())
        assertTrue(CourseScheduleParser.parseScheduleTime("   ").isEmpty())
    }

    @Test
    fun `任一段不认识就整条放弃`() {
        // 前半对、后半错 → 整条空，避免画出半对半错的格子
        assertTrue(CourseScheduleParser.parseScheduleTime("3-3,自行在线学习").isEmpty())
    }

    @Test
    fun `越界值被拒绝`() {
        assertTrue("星期 8 越界", CourseScheduleParser.parseScheduleTime("8-1").isEmpty())
        assertTrue("节次 0 越界", CourseScheduleParser.parseScheduleTime("1-0").isEmpty())
        assertTrue("节次 11 越界", CourseScheduleParser.parseScheduleTime("1-11").isEmpty())
        assertTrue("星期 0 越界", CourseScheduleParser.parseScheduleTime("0-1").isEmpty())
    }

    // ---------- weeks 解析 ----------

    @Test
    fun `周次区间与单周`() {
        val r = CourseScheduleParser.parseWeeks("1-16")
        assertEquals(1, r?.start)
        assertEquals(16, r?.end)

        val single = CourseScheduleParser.parseWeeks("8")
        assertEquals(8, single?.start)
        assertEquals(8, single?.end)
    }

    @Test
    fun `真实数据里的周次带周字与学时括号也能解析`() {
        // 真实教务 HTML 抽出来就是这几种形态（后端 testdata/jiaowu/real-2026-11-11/1-课表.html）
        val a = CourseScheduleParser.parseWeeks("9-12周(2学时)")
        assertEquals(9, a?.start)
        assertEquals(12, a?.end)

        val b = CourseScheduleParser.parseWeeks("10-11周(连堂4学时)")
        assertEquals(10, b?.start)
        assertEquals(11, b?.end)

        val c = CourseScheduleParser.parseWeeks("1-16周(2学时)")
        assertEquals(1, c?.start)
        assertEquals(16, c?.end)

        // 全角括号同样处理
        val d = CourseScheduleParser.parseWeeks("3-8周（4学时）")
        assertEquals(3, d?.start)
        assertEquals(8, d?.end)
    }

    @Test
    fun `周次括号里的单双周标记要保住`() {
        // 真实值：大学体育Ⅲ 是「1-16周(双周)(2学时)」
        val shuang = CourseScheduleParser.parseWeeksDetail("1-16周(双周)(2学时)")
        assertEquals(1, shuang?.range?.start)
        assertEquals(16, shuang?.range?.end)
        assertFalse("双周课不该标成单周", shuang!!.odd)
        assertTrue("双周课应标成双周", shuang.even)

        // 真实值：MATLAB程序设计实验 是「5-13周(单周)(3学时)」
        val dan = CourseScheduleParser.parseWeeksDetail("5-13周(单周)(3学时)")
        assertTrue("单周课应标成单周", dan!!.odd)
        assertFalse("单周课不该标成双周", dan.even)

        // 未标单双周 → 每周都有
        val both = CourseScheduleParser.parseWeeksDetail("1-16周(2学时)")
        assertTrue(both!!.odd)
        assertTrue(both.even)
    }

    @Test
    fun `休双周标记与时段后缀取交集`() {
        // weeks 标双周 + scheduleTime 无标记 → 仍是双周
        val out = CourseScheduleParser.parse(
            listOf(
                item(
                    name = "大学体育Ⅲ",
                    weeks = "1-16周(双周)(2学时)",
                    schedule = "4-7,4-8",
                ),
            ),
        )
        assertEquals(2, out.entries.size)
        assertTrue("应继承 weeks 的双周标记", out.entries[0].even)
        assertFalse("不该是单周", out.entries[0].odd)
    }

    @Test
    fun `坏周次返回 null`() {
        assertNull(CourseScheduleParser.parseWeeks(""))
        assertNull(CourseScheduleParser.parseWeeks("abc"))
        assertNull("起点大于终点的部分需拒绝", CourseScheduleParser.parseWeeks("16-1"))
    }

    // ---------- 整条映射 ----------

    private fun item(
        name: String = "高等数学",
        id: String = "300513911",
        weeks: String = "1-16",
        room: String = "10-A309",
        schedule: String = "3-3,3-4",
        teacher: String = "黄晖",
    ) = CourseItem(
        courseName = name,
        courseId = id,
        weeks = weeks,
        classroom = room,
        scheduleTime = schedule,
        teacher = teacher,
    )

    @Test
    fun `一条两段的课拆成两条 CourseEntry 且 id 唯一`() {
        val out = CourseScheduleParser.parse(listOf(item()))
        assertEquals(2, out.entries.size)
        assertTrue("两个时段应得到两个不同 id", out.entries[0].id != out.entries[1].id)
        assertTrue("id 应含课程 id 前缀", out.entries[0].id.startsWith("300513911"))
        assertEquals("高等数学", out.entries[0].name)
        assertEquals("10-A309", out.entries[0].room)
        assertEquals("黄晖", out.entries[0].teacher)
        assertEquals(1, out.entries[0].weeks.start)
        assertEquals(16, out.entries[0].weeks.end)
    }

    @Test
    fun `多地点与多时段逐行对应`() {
        val out = CourseScheduleParser.parse(
            listOf(item(schedule = "3-3\n5-1", room = "10-A309\n10-B201")),
        )
        assertEquals(2, out.entries.size)
        assertEquals("10-A309", out.entries[0].room)
        assertEquals("10-B201", out.entries[1].room)
    }

    @Test
    fun `散文型课程进待安排而不是被丢弃`() {
        val out = CourseScheduleParser.parse(
            listOf(item(name = "形势与政策", schedule = "自行在线学习")),
        )
        assertTrue("不该产出可落格的条目", out.entries.isEmpty())
        assertEquals("应保留在待安排列表", 1, out.unplaced.size)
        assertEquals("形势与政策", out.unplaced[0].courseName)
        assertEquals("自行在线学习", out.unplaced[0].scheduleTime)
    }

    @Test
    fun `周次坏掉也进待安排`() {
        val out = CourseScheduleParser.parse(listOf(item(weeks = "???")))
        assertTrue(out.entries.isEmpty())
        assertEquals(1, out.unplaced.size)
    }

    @Test
    fun `课程名为空被跳过`() {
        val out = CourseScheduleParser.parse(listOf(item(name = "  ")))
        assertTrue(out.entries.isEmpty())
        assertTrue("空名属于脏数据，不进待安排", out.unplaced.isEmpty())
    }

    @Test
    fun `courseId 为空时退化成课程名做前缀 保证 id 非空`() {
        val out = CourseScheduleParser.parse(listOf(item(id = "")))
        assertEquals(2, out.entries.size)
        assertTrue("id 不能为空", out.entries[0].id.startsWith("高等数学"))
    }
}

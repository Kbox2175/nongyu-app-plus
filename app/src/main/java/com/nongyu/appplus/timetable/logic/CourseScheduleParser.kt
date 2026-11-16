package com.nongyu.appplus.timetable.logic

import com.nongyu.appplus.net.model.CourseItem
import com.nongyu.appplus.timetable.model.CourseEntry
import com.nongyu.appplus.timetable.model.PERIOD_COUNT
import com.nongyu.appplus.timetable.model.TOTAL_WEEKS
import com.nongyu.appplus.timetable.model.WeekRange

/**
 * 教务 `course` payload → 课表模型 [CourseEntry] 的映射（纯逻辑，不依赖 Android / Compose）。
 *
 * ## 数据来源与权威结构
 *
 * 后端 `schema/course.json` 规定 `payload` 是 `CourseItem[]`，14 个字段**全部是 string**，
 * 其中只有 `courseName` 必需。决定课表布局的是这三个字段：
 *
 * | 字段 | 真实样例 | 含义 |
 * | --- | --- | --- |
 * | `scheduleTime` | `"3-3,3-4"`、`"2-9,2-10(单)"` | **「星期-节次」**，多段逗号分隔、多行 `\n` 分隔 |
 * | `weeks` | `"1-16"`、`"8"` | 教学周区间 |
 * | `classroom` | `"10-A309\n10-A309"` | 上课地点，**多行**与 scheduleTime 逐行对应 |
 *
 * 依据：`nongyu-backend-plus/docs/tech/14类回传JSON字段形状清单.md` §1（逐字段带来源行号）。
 *
 * ## 三个必须处理的真实脏数据
 *
 * 1. **散文型 scheduleTime**：文档明写也可能是「自行在线学习」这类**不可落格**的散文。
 *    维护者拍板：**不丢弃**，进 [ParseOutcome.unplaced]「待安排」列表。
 * 2. **单双周后缀**：`(单)` / `(双)` 要解析成 [CourseEntry.odd] / [CourseEntry.even]。
 * 3. **多时段 / 多地点**：一门课有多行 scheduleTime 时，要**拆成多条** CourseEntry。
 *    因此 id 用复合形式 `"$courseId-$day-$startPeriod"`（维护者拍板 A），
 *    否则同 id 的多条会违反模型 `require(id.isNotBlank())` 之外的唯一性假设。
 *
 * ## 星期索引换算
 *
 * 教务 `scheduleTime` 的星期是 **1 = 周一 … 7 = 周日**（教务惯例），
 * 本仓模型统一 **0 = 周一 … 6 = 周日**，转换只发生在这一层。
 */
object CourseScheduleParser {

    /** 解析结果：能落格的课程 + 无法落格的（待安排）。 */
    data class ParseOutcome(
        val entries: List<CourseEntry>,
        /** 无法落格的课程（散文型 scheduleTime 等），保留原始条目供「待安排」展示。 */
        val unplaced: List<UnplacedCourse>,
    )

    /**
     * 一条无法放进课表格子的课程。
     *
     * @param courseName 课程名
     * @param courseId 教务课程 id
     * @param scheduleTime 原始 scheduleTime 文本（散文），原样保留供展示
     * @param teacher 教师
     * @param weeks 原始周次文本
     * @param reason 为什么落不了格（便于排查，不直接展示给用户）
     */
    data class UnplacedCourse(
        val courseName: String,
        val courseId: String,
        val scheduleTime: String,
        val teacher: String,
        val weeks: String,
        val reason: String,
    )

    /** scheduleTime 里的一段：星期（0 基）+ 起止节次 + 单双周标记。 */
    data class TimeSlot(
        val day: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val odd: Boolean,
        val even: Boolean,
    )

    /**
     * 把整份 payload 映射成课表模型。
     *
     * @param items 教务回传的原始条目
     */
    fun parse(items: List<CourseItem>): ParseOutcome {
        val entries = ArrayList<CourseEntry>(items.size)
        val unplaced = ArrayList<UnplacedCourse>()

        for (item in items) {
            val name = item.courseName.orEmpty().trim()
            if (name.isEmpty()) continue   // schema 要求非空，防御性跳过

            val rawSchedule = item.scheduleTime.orEmpty()
            val slots = parseScheduleTime(rawSchedule)

            // 散文型 / 空 scheduleTime：进「待安排」，不丢弃（维护者拍板 B）
            if (slots.isEmpty()) {
                unplaced += UnplacedCourse(
                    courseName = name,
                    courseId = item.courseId.orEmpty(),
                    scheduleTime = rawSchedule,
                    teacher = item.teacher.orEmpty(),
                    weeks = item.weeks.orEmpty(),
                    reason = if (rawSchedule.isBlank()) "scheduleTime 为空" else "无法解析成星期-节次",
                )
                continue
            }

            val parsedWeeks = parseWeeksDetail(item.weeks.orEmpty())
            if (parsedWeeks == null) {
                unplaced += UnplacedCourse(
                    courseName = name,
                    courseId = item.courseId.orEmpty(),
                    scheduleTime = rawSchedule,
                    teacher = item.teacher.orEmpty(),
                    weeks = item.weeks.orEmpty(),
                    reason = "周次无法解析",
                )
                continue
            }
            val weekRange = parsedWeeks.range

            val rooms = item.classroom.orEmpty().split('\n').map { it.trim() }
            val courseId = item.courseId.orEmpty().ifBlank { name }

            for (slot in slots) {
                // 地点按段序号对应：多行地点与多段时段逐行对齐；
                // 只有一行地点时所有时段共用它（真实数据里常见）。
                val room = when {
                    rooms.size == slots.size -> rooms[slots.indexOf(slot)]
                    rooms.isNotEmpty() -> rooms.first()
                    else -> ""
                }
                entries += CourseEntry(
                    // 复合 id（维护者拍板 A）：课程级 id 不足以区分同一门课的多个时段
                    id = "$courseId-${slot.day}-${slot.startPeriod}",
                    name = name,
                    teacher = item.teacher.orEmpty(),
                    room = room,
                    day = slot.day,
                    startPeriod = slot.startPeriod,
                    endPeriod = slot.endPeriod,
                    weeks = weekRange,
                    // 单双周有两个来源，**取交集**：
                    // - scheduleTime 段尾标记：`2-10(单)`
                    // - weeks 括号标记：`1-16周(双周)(2学时)`（真实数据走这条）
                    // 两者都为 true 才算"每周都有"；任一处指明单/双周就按它缩小。
                    odd = slot.odd && parsedWeeks.odd,
                    even = slot.even && parsedWeeks.even,
                )
            }
        }
        return ParseOutcome(entries, unplaced)
    }

    /**
     * 解析 `scheduleTime`，如 `"3-3,3-4"`、`"2-9,2-10(单)"`、多行 `"3-3,3-4\n5-1,5-2"`。
     *
     * 返回空列表 = 这段文本落不了格（散文或格式不认识）。
     *
     * 规则：
     * - 按换行切行，再按逗号切段（两种分隔符都可能是多时段）；
     * - 每段形如 `星期-节次`，可用 `-` 或 `—`（教务会用全角/长破折号）；
     * - 段尾可带 `(单)` / `(双)` / `（单）`；
     * - 星期 1..7 → 0..6；节次 1..10；
     * - **任一段不认识就整条放弃**（宁可进「待安排」，也不要半对半错地画错格子）。
     */
    fun parseScheduleTime(raw: String): List<TimeSlot> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        val out = ArrayList<TimeSlot>(4)
        // 先按换行、再按中英文逗号切
        val chunks = text.split('\n', '\r', ',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (chunks.isEmpty()) return emptyList()

        for (chunk in chunks) {
            val slot = parseOneSlot(chunk) ?: return emptyList()
            out += slot
        }
        return out
    }

    /** 解析单段，如 `3-3`、`2-10(单)`、`3-1（双）`。失败返回 null。 */
    private fun parseOneSlot(chunk: String): TimeSlot? {
        var s = chunk.trim()
        var odd = true
        var even = true

        // 单双周标记：可能在括号里，也可能裸写在末尾
        val flags = listOf("(单)" to 1, "（单）" to 1, "(双)" to 2, "（双）" to 2)
        for ((marker, kind) in flags) {
            if (s.contains(marker)) {
                if (kind == 1) { odd = true; even = false } else { odd = false; even = true }
                s = s.replace(marker, "")
            }
        }
        s = s.trim()

        // 星期-节次：分隔符可能是 - 或 —（U+2014）或 –（U+2013）
        val parts = s.split('-', '—', '–').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null

        val day1 = parts[0].toIntOrNull() ?: return null      // 1..7
        val period = parts[1].toIntOrNull() ?: return null     // 1..10
        if (day1 !in 1..7) return null
        if (period !in 1..PERIOD_COUNT) return null

        return TimeSlot(
            day = day1 - 1,            // 教务 1 基 → 本仓 0 基
            startPeriod = period,
            endPeriod = period,        // 单段只占一节；连堂由多段相邻表达
            odd = odd,
            even = even,
        )
    }

    /**
     * 解析教学周文本。
     *
     * ## 真实数据的形态比后端文档更脏
     *
     * 字段清单里 `weeks` 的样例是 `1-16` / `8`，但**真实教务 HTML 抽出来是**：
     * - `9-12周(2学时)` —— 带「周」字 + 学时括号
     * - `10-11周(连堂4学时)`
     * - `1-16周(双周)(2学时)` —— **括号里还有单双周标记**
     * - `5-13周(单周)(3学时)`
     *
     * 所以顺序必须是：先提取单双周标记 → 再剥括号 → 再剥「周」字 → 最后取区间。
     * 若先剥括号，`(双周)` 会被一起扔掉，单双周信息就丢了。
     *
     * 解析不了返回 null（调用方把它归入「待安排」）。
     *
     * @return 周次区间；单双周信息通过 [ParsedWeeks] 一并返回
     */
    fun parseWeeks(raw: String): WeekRange? = parseWeeksDetail(raw)?.range

    /** 周次 + 单双周标记。 */
    data class ParsedWeeks(val range: WeekRange, val odd: Boolean, val even: Boolean)

    /**
     * 解析周次并同时给出单双周标记。
     *
     * 未标单双周时 both = true（每周都有）。
     */
    fun parseWeeksDetail(raw: String): ParsedWeeks? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        // ① 先抓单双周标记（在括号剥离之前！）
        var odd = true
        var even = true
        when {
            text.contains("单周") -> { odd = true; even = false }
            text.contains("双周") -> { odd = false; even = true }
        }

        // ② 剥掉括号说明：(2学时) / （连堂4学时） / (双周)
        text = text.replace(Regex("[（(][^）)]*[）)]"), "").trim()
        // ③ 去掉「周」字与空白
        text = text.replace("周", "").replace(" ", "")
        if (text.isEmpty()) return null

        // 只要第一段，避免「1-16,18」这类多段把整体判死
        val first = text.split(',', '，', '\n').firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null

        val parts = first.split('-', '—', '–').map { it.trim() }.filter { it.isNotEmpty() }
        val range = when (parts.size) {
            1 -> {
                val w = parts[0].toIntOrNull() ?: return null
                if (w !in 1..TOTAL_WEEKS) return null
                WeekRange(w, w)
            }
            2 -> {
                val a = parts[0].toIntOrNull() ?: return null
                val b = parts[1].toIntOrNull() ?: return null
                if (a !in 1..TOTAL_WEEKS || b !in a..TOTAL_WEEKS) return null
                WeekRange(a, b)
            }
            else -> return null
        }
        return ParsedWeeks(range, odd, even)
    }
}

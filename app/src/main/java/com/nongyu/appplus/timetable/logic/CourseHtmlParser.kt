package com.nongyu.appplus.timetable.logic

import com.nongyu.appplus.net.model.CourseItem

/**
 * 教务「班级课表」页面 HTML → [CourseItem] 列表（纯逻辑，不依赖 Android / Compose）。
 *
 * ## 为什么手写解析而不是引 HTML 库
 *
 * 项目硬约束是**零新依赖**，而这里只需要从一种固定结构的表格里取格子内容。
 * 引 Jsoup 会为一个页面多加几百 KB 与一条供应链风险，不值当。
 *
 * ## 页面结构（以真实夹具为准）
 *
 * 来源：`nongyu-backend-plus/testdata/jiaowu/real-2026-11-11/1-课表.html`（脱敏真实页）。
 *
 * 页面里有**两块表格**，必须只取第一块：
 *
 * 1. **周课表网格**（要的）：行 = `上午/下午/晚上` + 节次标签（`12节` / `34节` …），
 *    列 = 星期一…星期日。格子内容形如：
 *    ```
 *    课程名：教师<br>雅安校区：10-B201室<br>9-12周(2学时)<br>--------------------<br>&nbsp;
 *    ```
 * 2. **课程明细列表**（不要的）：每门课一行、7 列（校区/课程名称/编号/周次/教室/上课时间/学分）。
 *    它的表头「课程名称」是上方网格的右边界。
 *
 * ## 为什么要以「课程名称」为界
 *
 * 第一版实现没做切割，把明细表也当网格扫，产出上百条垃圾
 * （「晚上」「校区」「雅安」「300539222」…）。实测踩过，所以这里把边界写死并加测试钉住。
 */
object CourseHtmlParser {

    /** 解析结果。 */
    data class Result(
        /** 成功落格的课程。 */
        val items: List<CourseItem>,
        /** 整页是否像课表页（没有任何星期表头时判为 false，提示用户重新登录教务）。 */
        val looksLikeTimetable: Boolean,
    )

    /** 节次标签 → 起止节次。教务把两小节合成一个单元格标签。 */
    private val PERIOD_LABELS: Map<String, Pair<Int, Int>> = mapOf(
        "12节" to (1 to 2),
        "34节" to (3 to 4),
        "56节" to (5 to 6),
        "78节" to (7 to 8),
        "910节" to (9 to 10),
        "1112节" to (11 to 12),
    )

    private val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 明细表才有的词，出现在「课程名」位置说明扫错区域了。 */
    private val JUNK_NAMES = setOf(
        "校区", "课程名称", "编号", "周次", "教室", "上课时间", "学分", "上午", "下午", "晚上", "时间",
    )

    private val TD_REGEX = Regex("<td\\b([^>]*)>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TR_REGEX = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("<[^>]+>")
    private val ROWSPAN2_REGEX = Regex("rowspan\\s*=\\s*2", RegexOption.IGNORE_CASE)
    private val PERIOD_RANGE_REGEX = Regex("(\\d+)-(\\d+)节")
    /** 真实周次：`9-12周(2学时)`、`1-16周(双周)(2学时)`、`5-13周(单周)(3学时)`。 */
    private val WEEKS_REGEX = Regex("\\d+\\s*-\\s*\\d+\\s*周")
    /** 分隔线：格子里的 `--------------------`，后半段是空位。 */
    private const val DIVIDER = "--------------------"

    /**
     * 解析课表页 HTML。
     *
     * @param html 教务返回的整页 HTML（可能是 GBK/UTF-8，解码由调用方负责）
     */
    fun parse(html: String): Result {
        val headerAt = html.indexOf("星期一")
        // 没有星期表头 = 不是课表页（多半是登录失效返回的登录页）
        if (headerAt < 0) return Result(emptyList(), looksLikeTimetable = false)

        // 明细表表头「课程名称」是网格的右边界
        val detailAt = html.indexOf("课程名称", headerAt)
        val grid = if (detailAt > headerAt) html.substring(headerAt, detailAt) else html.substring(headerAt)

        val items = ArrayList<CourseItem>()
        var seq = 0
        var currentPeriod: Pair<Int, Int>? = null

        for (rowMatch in TR_REGEX.findAll(grid)) {
            val row = rowMatch.groupValues[1]
            val cells = TD_REGEX.findAll(row).map { c ->
                val attrs = c.groupValues[1]
                val inner = c.groupValues[2]
                attrs to inner
            }.toList()
            if (cells.isEmpty()) continue

            // 本行是否声明节次标签
            for ((_, inner) in cells) {
                val txt = cellText(inner).replace(" ", "")
                PERIOD_LABELS[txt]?.let { currentPeriod = it }
                PERIOD_RANGE_REGEX.matchEntire(txt)?.let {
                    currentPeriod = it.groupValues[1].toInt() to it.groupValues[2].toInt()
                }
            }

            val period = currentPeriod ?: continue

            // 跳过 rowspan=2 的「上午/下午/晚上」与节次标签格，剩下的按列序对星期
            val dataCells = cells.filter { (attrs, inner) ->
                if (ROWSPAN2_REGEX.containsMatchIn(attrs)) return@filter false
                val txt = cellText(inner).replace(" ", "")
                if (PERIOD_LABELS.containsKey(txt)) return@filter false
                if (PERIOD_RANGE_REGEX.matchEntire(txt) != null) return@filter false
                true
            }
            if (dataCells.isEmpty()) continue

            dataCells.forEachIndexed { idx, (_, inner) ->
                if (idx >= WEEKDAYS.size) return@forEachIndexed
                val parsed = parseCell(cellText(inner)) ?: return@forEachIndexed
                seq++
                val (start, end) = period
                // 教务口径 scheduleTime = 星期-节次，一段一行
                val schedule = (start..end).joinToString("\n") { "${idx + 1}-$it" }
                items += CourseItem(
                    courseName = parsed.name,
                    courseId = "auto$seq",
                    weeks = parsed.weeks,
                    classroom = parsed.classroom,
                    scheduleTime = schedule,
                    teacher = parsed.teacher,
                )
            }
        }
        return Result(items, looksLikeTimetable = true)
    }

    private data class Cell(val name: String, val teacher: String, val classroom: String, val weeks: String)

    /** 解析一个格子；不是真课程返回 null。 */
    private fun parseCell(text: String): Cell? {
        val head = if (text.contains(DIVIDER)) text.substringBefore(DIVIDER) else text
        val lines = head.split('\n').map { it.trim() }.filter { it.isNotEmpty() && it != "\u00A0" }
        if (lines.isEmpty()) return null

        val first = lines.first()
        if (first in JUNK_NAMES) return null

        val sep = when {
            first.contains('：') -> '：'
            first.contains(':') -> ':'
            else -> null
        }
        val (name, teacher) = if (sep != null) {
            val parts = first.split(sep, limit = 2)
            parts[0].trim() to parts.getOrElse(1) { "" }.trim()
        } else {
            first.trim() to ""
        }

        // 课程名合法性：非空、不是纯数字、不含表格词
        if (name.isEmpty() || name.all { it.isDigit() }) return null
        if (name in JUNK_NAMES) return null
        if (name.contains("校区") || name.contains("课程名称") || name.contains("上课时间")) return null

        var classroom = ""
        var weeks = ""
        for (ln in lines.drop(1)) {
            when {
                WEEKS_REGEX.containsMatchIn(ln) -> weeks = ln
                sep != null && (ln.contains(':') || ln.contains('：')) && classroom.isEmpty() -> {
                    val v = ln.split(':', '：', limit = 2).getOrElse(1) { "" }.trim()
                    if (v.isNotEmpty()) classroom = v
                }
                classroom.isEmpty() -> classroom = ln
            }
        }
        return Cell(name, teacher, classroom, weeks)
    }

    /** `<br>` → 换行，去标签，解 HTML 实体。 */
    private fun cellText(inner: String): String {
        var s = BR_REGEX.replace(inner, "\n")
        s = TAG_REGEX.replace(s, "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return s.trim()
    }
}

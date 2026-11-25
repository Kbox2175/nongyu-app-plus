package com.nongyu.appplus.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nongyu.appplus.timetable.data.CourseUserStore
import com.nongyu.appplus.timetable.data.LocationScope
import com.nongyu.appplus.timetable.data.MockCourseCommentRepository
import com.nongyu.appplus.timetable.logic.NOTE_DEBOUNCE_MS
import com.nongyu.appplus.timetable.logic.isPeriodEnded
import com.nongyu.appplus.timetable.logic.mergeAttendance
import com.nongyu.appplus.timetable.logic.noteChanged
import com.nongyu.appplus.timetable.logic.shouldPersistNote
import com.nongyu.appplus.timetable.logic.toggledAttendance
import com.nongyu.appplus.timetable.model.CourseEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 课程详情卡的所有**可修改数据**（地点/教师/周次例外/备注/考勤/评论）。
 *
 * 从 `MainShell` 原样搬出，行为未改。原先这约 90 行状态 + 4 个 `LaunchedEffect`
 * 全部内联在 `MainShell` 里，是「详情卡数据源」，与路由/命中层级无关，故整体抽成
 * 一个 state-holder：`MainShell` 只 `rememberCourseDetailState(...)` 后读字段、调方法。
 *
 * 实测踩过的坑（详见 `docs/specs/界面层.md` §8.5 A）：
 * - 备注不能用 `collectAsState` 直接喂 `TextField`（写入与回读打架，重启丢值）。
 *   这里维持「本地 `mutableState` 做 UI 真源 + 打开卡片读一次 + 改动异步落盘」，
 *   能否落盘的判据住在 `timetable/logic/CourseDetailRules.kt`（`shouldPersistNote`/`noteChanged`）。
 * - 所有随课程切换要复位的状态都用 `remember(courseId)`（不是 `remember { }`），
 *   保证换课的**当帧**就拿到初始值，不会闪现上一门课的备注/考勤。
 *
 * @property deletedIds 已删除课程 id 集合（贯穿切周链路过滤）。它**不随 courseId 复位**
 *   （始终 `collectAsState` 整个 store），课表页也要用它过滤，故暴露在此。
 */
class CourseDetailState(
    val courseId: String?,
    /** 今天（按天覆盖）的地点，无覆盖时为 null。 */
    val locDay: String?,
    /** 通用覆盖地点，无覆盖时为 null。 */
    val locGeneral: String?,
    /** 教师覆盖，无覆盖时为 null。 */
    val teacherOverride: String?,
    val deletedIds: Set<String>,
    /** 合并了「默认按结束时刻判定」与「用户显式点过」之后的考勤表。 */
    val effectiveAttendance: Map<Int, Boolean>,
    private val store: CourseUserStore,
    private val scope: CoroutineScope,
    private val noteTextState: MutableState<String>,
    private val attendanceOverridesState: MutableState<Map<Int, Boolean>>,
    private val commentsState: MutableState<List<String>>,
    private val exceptionIsoDaysState: MutableState<Set<String>>,
) {
    /** 备注 UI 真源（本地态，非 DataStore 回读）。 */
    var noteText by noteTextState
        private set

    /** 用户显式点过的考勤覆盖（period -> 是否已考勤）。 */
    var attendanceOverrides by attendanceOverridesState
        private set

    /** 进程内 mock 评论列表。 */
    var comments by commentsState
        private set

    /** 周次例外天（ISO 字符串，不上课的天）。 */
    var exceptionIsoDays by exceptionIsoDaysState
        private set

    /** 行内改教师：写 store，经 teacherFlow 回读生效。 */
    fun onTeacherChange(value: String) {
        val id = courseId ?: return
        scope.launch { store.setTeacherOverride(id, value) }
    }

    /** 翻转某节考勤：先改本地覆盖（合并优先级见 logic/mergeAttendance），再落盘。 */
    fun onAttendanceToggle(period: Int) {
        val id = courseId ?: return
        attendanceOverrides = toggledAttendance(effectiveAttendance, period)
        val next = attendanceOverrides[period] ?: false
        scope.launch { store.setAttendance(id, period, next) }
    }

    /** 评论增删后重读（mock 后端在进程内，直接 list 即可）。 */
    fun reloadComments() {
        val id = courseId ?: return
        comments = MockCourseCommentRepository.list(id)
    }

    /** 备注 UI 真源更新（落盘由工厂里的防抖 effect 统一处理）。 */
    fun onNoteChange(text: String) {
        noteText = text
    }

    /** 删除这门课：落盘到 deletedIds，切周后不再「复活」。 */
    fun onDelete() {
        val id = courseId ?: return
        scope.launch { store.setDeletedIds(deletedIds + id) }
    }

    /**
     * 地点编辑确认：按天覆盖优先；**未选任何天时退回通用覆盖**，保证至少有值生效。
     */
    fun onLocationConfirm(newLocation: String, days: Set<String>) {
        val id = courseId ?: return
        scope.launch {
            store.setLocationForDays(id, days, newLocation)
            if (days.isEmpty()) {
                store.setLocationOverride(id, LocationScope.Once, newLocation)
            }
        }
    }

    /** 周次编辑确认：先改本地例外天（当帧生效），再落盘。 */
    fun onWeekConfirm(days: Set<String>) {
        val id = courseId ?: return
        exceptionIsoDays = days
        scope.launch { store.setExceptionDays(id, days) }
    }
}

/**
 * [CourseDetailState] 的 Compose 工厂：把原先内联在 `MainShell` 的状态与副作用
 * 原样搬到这里（key 与顺序都未动）。`MainShell` 只调用它、读返回值。
 */
@Composable
fun rememberCourseDetailState(
    store: CourseUserStore,
    scope: CoroutineScope,
    detailCourse: CourseEntry?,
): CourseDetailState {
    val courseId = detailCourse?.id

    // 地点显示值：优先「按天覆盖」（今天），否则「通用覆盖」，否则原始 room。
    val todayIso = remember { LocalDate.now().toString() }
    val locDayFlow = remember(courseId, todayIso) {
        if (courseId != null) store.locationForDay(courseId, todayIso) else null
    }
    val locGeneralFlow = remember(courseId) {
        if (courseId != null) store.locationOverride(courseId, LocationScope.Once) else null
    }
    val locDay by (locDayFlow ?: flowOf(null)).collectAsState(initial = null)
    val locGeneral by (locGeneralFlow ?: flowOf(null)).collectAsState(initial = null)

    // 教师覆盖（行内输入）
    val teacherFlow = remember(courseId) {
        if (courseId != null) store.teacherOverride(courseId) else null
    }
    val teacherOverride by (teacherFlow ?: flowOf(null)).collectAsState(initial = null)

    // 已删除课程 id：贯穿切周链路过滤，防止切周后「复活」。始终收集，不随 courseId 复位。
    val deletedIds by store.deletedIds().collectAsState(initial = emptySet())

    // 周次复杂态：例外天（不上）
    val exceptionIsoDays = remember(courseId) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(courseId) {
        exceptionIsoDays.value = if (courseId != null) {
            store.exceptionDays(courseId).first()
        } else emptySet()
    }

    // 备注三态：UI 真源 / 初始化完成标记 / 已落盘值（用于跳过无意义写入）。
    // 详见 docs/specs/界面层.md §8.5 A：不能用 collectAsState 直接喂 TextField。
    val noteText = remember(courseId) { mutableStateOf("") }
    val noteReady = remember(courseId) { mutableStateOf(false) }
    val notePersisted = remember(courseId) { mutableStateOf("") }
    LaunchedEffect(courseId) {
        if (courseId != null) {
            val loaded = store.noteOnce(courseId)
            noteText.value = loaded
            notePersisted.value = loaded
            noteReady.value = true
        } else {
            noteText.value = ""
            notePersisted.value = ""
            noteReady.value = false
        }
    }
    // 输入停止 NOTE_DEBOUNCE_MS 后统一落盘：既不会每个字符都起一个写协程（竞态），
    // 也不会因为 Flow 回读把输入框内容拉回去。
    LaunchedEffect(noteText.value, courseId, noteReady.value) {
        if (shouldPersistNote(noteReady.value, courseId) && noteChanged(notePersisted.value, noteText.value)) {
            delay(NOTE_DEBOUNCE_MS)
            store.setNote(courseId!!, noteText.value)
            notePersisted.value = noteText.value
        }
    }

    // 考勤：默认按「课程结束时刻 < 现在」判定已考勤；显式记录以用户为准。
    // 合并优先级住在 logic/mergeAttendance（有单测：显式记录必须压过时间判定）。
    val attendanceOverrides = remember(courseId) { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }
    val effectiveAttendance = remember(courseId, attendanceOverrides.value) {
        mergeAttendance(detailCourse, attendanceOverrides.value) { p -> isPeriodEnded(p) }
    }

    // 评论（mock 后端，进程内可增删）
    val comments = remember(courseId) {
        mutableStateOf(
            if (courseId != null) MockCourseCommentRepository.list(courseId) else emptyList(),
        )
    }

    return CourseDetailState(
        courseId = courseId,
        locDay = locDay,
        locGeneral = locGeneral,
        teacherOverride = teacherOverride,
        deletedIds = deletedIds,
        effectiveAttendance = effectiveAttendance,
        store = store,
        scope = scope,
        noteTextState = noteText,
        attendanceOverridesState = attendanceOverrides,
        commentsState = comments,
        exceptionIsoDaysState = exceptionIsoDays,
    )
}

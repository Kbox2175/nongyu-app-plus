package com.nongyu.appplus.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * 时间条（页面级 overlay）的状态。
 *
 * 从 `MainShell` 原样搬出，行为未改。原先这约 30 行状态 + 3 个 `LaunchedEffect`
 * 内联在壳里；时间条本体虽已搬到 `timetable/ui/TimetableRuler`，但它的定位数据、
 * 拖动遮罩、当前时刻刷新仍由 Shell 持有，故整体抽成 state-holder。
 *
 * 实测踩过的坑（详见 `docs/specs/界面层.md` §8.5 B/D）：
 * - 时间文字的清除 effect，key **只盯 [dragging]**。曾同时盯 `focus` 与 `dragging`，
 *   两者在 `onDragEnd` 里连续写，可能出现「focus 已 null 但 dragging 仍 true」的一帧，
 *   导致清除分支不触发、时间文字一直挂在屏幕上。
 * - [periodTopPx]/[periodHeightPx] 必须用 `TimetableGrid` 上报的**实测值**，
 *   手算 padding 公式已连续错两次（漏底栏占位、漏工具条预留）。
 */
class RulerState {
    /** 节次行顶边（px，由 TimetableGrid 实测上报）。 */
    var periodTopPx by mutableFloatStateOf(0f)
        private set

    /** 节次行高度（px，由 TimetableGrid 实测上报）。 */
    var periodHeightPx by mutableFloatStateOf(0f)
        private set

    /** 时间条是否正在拖动（驱动全屏遮罩 [com.nongyu.appplus.design.ModalScrim]）。 */
    var dragging by mutableStateOf(false)
        private set

    /** 拖动中手指所在的时间轴比例（每帧更新）。 */
    var focus by mutableStateOf<Float?>(null)
        private set

    /**
     * 「已停留」的比例：拖动中实时跟随 [focus]，松手后停留 1.6s 再清空。
     * 独立于 [focus]，是为了清除 effect 的 key 能只盯 [dragging]。
     */
    var focusHeld by mutableStateOf<Float?>(null)
        private set

    /** 当前时刻（用于水面高度）。每 30s 刷新一次即可。 */
    var nowMinute by mutableStateOf(LocalTime.now())
        private set

    /** TimetableGrid 上报节次行实测范围。 */
    fun onPeriodColumnBounds(top: Float, height: Float) {
        periodTopPx = top
        periodHeightPx = height
    }

    /** 时间条拖动状态回调。 */
    fun onDragStateChange(isDragging: Boolean) {
        dragging = isDragging
    }

    /** 时间条拖动聚焦比例回调。 */
    fun onFocusChange(value: Float?) {
        focus = value
    }

    /** 拖动中实时跟随手指（由 [rememberRulerState] 的 effect 调用）。 */
    fun followFocus() {
        focusHeld = focus
    }

    /** 松手后 1.6s 清除停留比例（由 [rememberRulerState] 的 effect 调用）。 */
    fun clearFocusHeld() {
        focusHeld = null
    }

    /** 刷新当前时刻（由 [rememberRulerState] 的 ticker effect 调用）。 */
    fun tickNow() {
        nowMinute = LocalTime.now()
    }
}

/**
 * [RulerState] 的 Compose 工厂：把原先内联在 `MainShell` 的三个 effect 原样搬到这里。
 */
@Composable
fun rememberRulerState(): RulerState {
    val state = remember { RulerState() }

    // 当前时刻每 30s 刷新一次（水面高度用，粒度到分钟足够）。
    LaunchedEffect(Unit) {
        while (true) {
            state.tickNow()
            delay(30_000L)
        }
    }
    // 详见 docs/specs/界面层.md §8.5 B：清除 effect 的 key 只盯 dragging。
    LaunchedEffect(state.dragging) {
        if (!state.dragging && state.focusHeld != null) {
            delay(1600)
            state.clearFocusHeld()
        }
    }
    // 拖动中实时跟随手指。
    LaunchedEffect(state.focus) {
        if (state.focus != null) state.followFocus()
    }
    return state
}

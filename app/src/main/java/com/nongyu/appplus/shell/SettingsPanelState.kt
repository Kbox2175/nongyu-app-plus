package com.nongyu.appplus.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nongyu.appplus.settings.ui.settingsTween
import androidx.compose.ui.geometry.Offset

/**
 * 全局设置面板的状态（当前只有「课程表」分区）。
 *
 * 从 `MainShell` 原样搬出，行为未改。原先这约 20 行状态 + 1 个动画 effect 内联在壳里。
 *
 * [progress] 用一个 `Animatable` 同时驱动「面板滑入」与「齿轮旋转」，保证两者严格同步：
 * progress 0→1 滑入、1→0 滑出（反向动画由对称 `settingsTween` 保证）。
 *
 * 课程表设置项（[dayMode]/[showWeekends]/[quietRefresh]）本期只做界面、先放内存，
 * 持久化留给后续票。
 */
class SettingsPanelState {
    /** 面板是否打开。 */
    var open by mutableStateOf(false)

    /** 齿轮中心的屏幕坐标（由课表工具条上报，用于齿轮克隆体飞入标题行）。 */
    var gearCenterPx by mutableStateOf<Offset?>(null)

    /** 面板滑入进度（0 关闭 / 1 全开），同时驱动齿轮旋转。 */
    val progress = Animatable(0f)

    /** 课程表设置：按天视图（false = 按周）。 */
    var dayMode by mutableStateOf(false)

    /** 课程表设置：是否显示周末。 */
    var showWeekends by mutableStateOf(true)

    /** 课程表设置：更新课表时静默（不弹成功 toast）。 */
    var quietRefresh by mutableStateOf(false)
}

/**
 * [SettingsPanelState] 的 Compose 工厂：把原先内联在 `MainShell` 的开关动画 effect
 * 原样搬到这里。
 */
@Composable
fun rememberSettingsPanelState(): SettingsPanelState {
    val state = remember { SettingsPanelState() }
    // 打开/关闭统一走同一个动画：progress 0→1 滑入，1→0 滑出。
    LaunchedEffect(state.open) {
        if (state.open) {
            state.progress.animateTo(1f, settingsTween())
        } else {
            state.progress.animateTo(0f, settingsTween())
        }
    }
    return state
}

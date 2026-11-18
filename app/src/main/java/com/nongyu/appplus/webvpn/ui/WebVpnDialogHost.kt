package com.nongyu.appplus.webvpn.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nongyu.appplus.design.uiScaleFor
import com.nongyu.appplus.webvpn.data.WebVpnDialogScheduler

/**
 * WebVPN 弹窗的**顶层宿主**。
 *
 * ## 为什么挂在根节点最顶层
 *
 * 需求原话「其至于最顶层」。本 Composable 由 `NongyuApp` 放在根 `Box` 的
 * **最后一个子节点**：Compose 同层 `Box` 后声明者绘制在上，所以它盖住
 *
 * - `MainShell`（含设置面板、星云球、时间条遮罩等一切页面内浮层）；
 * - 登录中心，以及登录成功时的圆形揭示转场层。
 *
 * 放在根节点而不是 `MainShell` 内部还有两个好处：登录中心→主界面的转场期间
 * 弹窗不会被卷进转场挖洞；未来新增任何页面内浮层都自动在它之下。
 *
 * ## 为什么不用 `Dialog()`
 *
 * `androidx.compose.ui.window.Dialog()` 会开一个独立 window，但它挂在**调用它的
 * Composition 局部**，且会参与系统的 dialog 层级排序；本项目的层叠约定是
 * 「MainShell 末尾 = 最高」（`docs/specs/界面层.md` §8）。用一个显式的最顶层
 * `Box` 更可控、可预览、可单测调度逻辑。
 *
 * ## 观察点
 *
 * 只观察 [WebVpnDialogScheduler.currentRequest]。非 null 即渲染一个
 * [WebVpnLoginDialog]；弹窗内部通过 `complete()` / `decline()` / `dismiss()`
 * 把请求置 null，本宿主随之移除 —— 没有第二套开关状态。
 */
@Composable
fun WebVpnDialogHost(modifier: Modifier = Modifier) {
    val request by WebVpnDialogScheduler.currentRequest.collectAsState()
    if (request == null) return

    BoxWithConstraints(modifier = modifier) {
        val uiScale = uiScaleFor(maxWidth.value)
        WebVpnLoginDialog(
            request = request!!,
            uiScale = uiScale,
        )
    }
}

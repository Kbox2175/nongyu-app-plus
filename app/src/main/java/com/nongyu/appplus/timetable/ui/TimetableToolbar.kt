package com.nongyu.appplus.timetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.nongyu.appplus.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import androidx.compose.ui.unit.dp
import com.nongyu.appplus.design.NongyuTheme

/**
 * 课表右上角四个按钮（票 #14）。
 *
 * 从左到右：设置 / 更新课表 / 切换日·周 / 添加日程。
 * 前两个是**占位**——点了只给一句轻提示，不接任何真实逻辑（票面验收要求
 * 「两个占位按钮不误触真实逻辑」）。
 *
 * 图标全部**纯几何自绘**（零依赖，不引图标库），与项目既有 `drawNavIcon` 同思路。
 * 四个按钮共用同一个尺寸/圆角/底色，看起来是一组工具条。
 */
@Composable
fun TimetableToolbar(
    uiScale: Float,
    dayMode: Boolean,
    onSettings: () -> Unit,
    onUpdateSchedule: () -> Unit,
    onToggleDayWeek: () -> Unit,
    onAddSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NongyuTheme.colors
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp
    val buttonSize = s(34.dp)
    val corner = s(10.dp)
    val gap = s(8.dp)
    val iconSize = s(16.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = s(12.dp), vertical = s(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(buttonSize, corner, colors.surface.colorCard, uiScale, onSettings) {
            SettingsIcon(iconSize, colors.brand.colorBrand, uiScale)
        }
        ToolbarButton(buttonSize, corner, colors.surface.colorCard, uiScale, onUpdateSchedule) {
            UpdateIcon(iconSize, colors.brand.colorBrand, uiScale)
        }
        ToolbarButton(
            buttonSize,
            corner,
            if (dayMode) colors.brand.colorBrand else colors.surface.colorCard,
            uiScale,
            onToggleDayWeek,
        ) {
            DayWeekIcon(
                iconSize,
                if (dayMode) colors.brand.colorOnBrand else colors.brand.colorBrand,
                uiScale,
                dayMode,
            )
        }
        ToolbarButton(buttonSize, corner, colors.surface.colorCard, uiScale, onAddSchedule) {
            AddIcon(iconSize, colors.brand.colorBrand, uiScale)
        }
    }
}

@Composable
private fun ToolbarButton(
    size: Dp,
    corner: Dp,
    bg: Color,
    uiScale: Float,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** 齿轮（设置）：资源化后的 VectorDrawable（原 8 齿自绘，2026-11-16 迁到 res）。 */
@Composable
private fun SettingsIcon(size: Dp, tint: Color, uiScale: Float) {
    Icon(
        painter = painterResource(R.drawable.ic_toolbar_settings),
        contentDescription = "设置",
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/** 更新课表：资源化后的 VectorDrawable（原下箭头 + 托盘自绘）。 */
@Composable
private fun UpdateIcon(size: Dp, tint: Color, uiScale: Float) {
    Icon(
        painter = painterResource(R.drawable.ic_toolbar_update),
        contentDescription = "更新课表",
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/**
 * 切换日/周：日模式与周模式是两个不同资源（皮肤可分别覆盖）。
 *
 * 原来是一个 Canvas 里按 `dayMode` 走两支；拆成两张矢量图后，
 * 皮肤包可以对「日」和「周」用不同的图。
 */
@Composable
private fun DayWeekIcon(size: Dp, tint: Color, uiScale: Float, dayMode: Boolean) {
    Icon(
        painter = painterResource(
            if (dayMode) R.drawable.ic_toolbar_day else R.drawable.ic_toolbar_week,
        ),
        contentDescription = if (dayMode) "切换到周视图" else "切换到日视图",
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/** 添加日程：资源化后的 VectorDrawable（原加号自绘）。 */
@Composable
private fun AddIcon(size: Dp, tint: Color, uiScale: Float) {
    Icon(
        painter = painterResource(R.drawable.ic_toolbar_add),
        contentDescription = "添加日程",
        tint = tint,
        modifier = Modifier.size(size),
    )
}

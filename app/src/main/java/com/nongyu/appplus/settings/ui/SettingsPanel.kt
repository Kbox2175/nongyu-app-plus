package com.nongyu.appplus.settings.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nongyu.appplus.R
import com.nongyu.appplus.design.NongyuTheme
import com.nongyu.appplus.settings.model.SettingsActionEntry
import com.nongyu.appplus.settings.model.SettingsChoiceEntry
import com.nongyu.appplus.settings.model.SettingsContent
import com.nongyu.appplus.settings.model.SettingsEntry
import com.nongyu.appplus.settings.model.SettingsEntryKey
import com.nongyu.appplus.settings.model.SettingsSection
import com.nongyu.appplus.settings.model.SettingsSwitchEntry

/**
 * 全局设置面板（课表工具条的齿轮入口）。
 *
 * ## 动效（维护者 2026-11-16 定稿）
 *
 * 1. **点齿轮**：齿轮旋转，面板从**右侧滑入**；
 * 2. **停位**：面板**贴屏幕右边缘**，宽占 90%，露出的是左侧 10% 空白
 *    （维护者 2026-11-17 纠正：空位在左，不在右）；
 * 3. **齿轮跟随**：面板滑到「完全包含齿轮」之后，齿轮**克隆一份到顶层**随面板移动，
 *    最终落在面板标题行左端；工具条原按钮留着不动（此时已被面板盖住）；
 * 4. **遮板**：面板之外的区域压暗；**点遮板按反向动画退回**；
 * 5. **关闭**：右上角 X 或点遮板，都走反向动画。
 *
 * ## 为什么齿轮要克隆
 *
 * 齿轮原本在工具条 `Row` 里，位置由布局决定、被父容器裁剪。要它在面板移动中
 * 保持可见并跟着走，只能画在最上层并手动定位——直接移动原按钮会破坏工具条布局。
 *
 * ## 扩展位
 *
 * 面板只消费 [SettingsContent]，不关心分区来自哪个模块。
 * 新增设置改 `settings/logic/SettingsBuilders.kt` 即可，本文件不用动。
 *
 * @param progress 滑入进度 0..1（0=完全在屏幕外，1=到位），由外部动画驱动
 * @param gearCenterPx 齿轮中心相对**屏幕**左上角的像素坐标；null = 降级为不做跟随
 * @param gearRotation 齿轮旋转角度（度）
 */
@Composable
fun SettingsPanel(
    content: SettingsContent,
    uiScale: Float,
    gearSize: Dp,
    gearCenterPx: Offset?,
    gearRotation: Float,
    progress: Float,
    onDismiss: () -> Unit,
    onEntryClick: (SettingsEntryKey) -> Unit,
    onChoiceSelected: (SettingsEntryKey, Int) -> Unit,
) {
    val colors = NongyuTheme.colors
    fun s(dp: Dp): Dp = (dp.value * uiScale).dp

    if (progress <= 0f) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenWPx = constraints.maxWidth.toFloat()

        // 面板占屏幕 90%，**贴右边缘**：留空的是左侧 10%
        // （维护者 2026-11-17 纠正：之前理解反了，空位在左不在右）
        val panelWidthPx = screenWPx * PANEL_FRACTION
        // 露出到位时面板左边缘 x = 屏幕宽 - 面板宽 = 屏幕 10%
        val shownLeftPx = screenWPx - panelWidthPx
        // 收起时整块滑出屏幕右边缘
        val leftPx = shownLeftPx + panelWidthPx * (1f - progress)

        // ---- 遮板：铺满全屏，点在面板之外的区域关闭 ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA * progress))
                .clickable(
                    // 不要涟漪：这是「点空白处关闭」，不是按钮
                    interactionSource = null,
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // ---- 面板本体（压在遮板之上，吃自己的点击） ----
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (leftPx / density.density).dp)
                .width((panelWidthPx / density.density).dp)
                .fillMaxHeight()
                // 只圆左边两角：右边贴着屏幕边缘
                .clip(RoundedCornerShape(topStart = s(20.dp), bottomStart = s(20.dp)))
                .background(colors.surface.colorCard)
                // enabled=false 的 clickable 只吃事件、不触发动作，正是我们要的
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(s(16.dp)),
            ) {
                // 标题行：左端预留齿轮位（克隆体最终落这里），右上角关闭 X
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.size(gearSize))
                    Spacer(modifier = Modifier.width(s(10.dp)))
                    Text(
                        text = "设置",
                        color = colors.surface.colorText,
                        fontSize = (18f * uiScale).sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CloseCross(
                        size = s(22.dp),
                        tint = colors.surface.colorTextSecondary,
                        onClick = onDismiss,
                        uiScale = uiScale,
                    )
                }

                Spacer(modifier = Modifier.height(s(14.dp)))

                content.sections.forEach { section ->
                    SectionBlock(
                        section = section,
                        uiScale = uiScale,
                        s = ::s,
                        onEntryClick = onEntryClick,
                        onChoiceSelected = onChoiceSelected,
                    )
                    Spacer(modifier = Modifier.height(s(14.dp)))
                }

                if (content.isEmpty) {
                    Text(
                        text = "暂无设置项",
                        color = colors.surface.colorTextSecondary,
                        fontSize = (13f * uiScale).sp,
                    )
                }
            }
        }

        // ---- 齿轮克隆体：顶层，从原位置插值飞到面板标题行 ----
        val startCenter = gearCenterPx
        if (startCenter != null && progress > FOLLOW_FROM) {
            val t = ((progress - FOLLOW_FROM) / (1f - FOLLOW_FROM)).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(t)

            // 终点：面板内标题行左端齿轮的中心（相对屏幕）。
            // 面板贴右边，所以起点是面板当前的左边缘 leftPx，不是从 0 算。
            val padPx = s(16.dp).value * density.density
            val gearPx = gearSize.value * density.density
            val endCenterX = leftPx + padPx + gearPx / 2f
            val endCenterY = padPx + gearPx / 2f

            val cx = startCenter.x + (endCenterX - startCenter.x) * eased
            val cy = startCenter.y + (endCenterY - startCenter.y) * eased

            Box(
                modifier = Modifier
                    .offset(
                        x = ((cx - gearPx / 2f) / density.density).dp,
                        y = ((cy - gearPx / 2f) / density.density).dp,
                    )
                    .size(gearSize)
                    .rotate(gearRotation),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_toolbar_settings),
                    contentDescription = null,
                    tint = colors.brand.colorBrand,
                    modifier = Modifier.size(gearSize),
                )
            }
        }
    }
}

/** 面板占屏幕宽度的比例：贴右边缘，左侧留空 10%（维护者 2026-11-17 纠正）。 */
private const val PANEL_FRACTION = 0.90f

/** 遮板不透明度上限。 */
private const val SCRIM_ALPHA = 0.35f

/** 进度超过此值后面板已盖住齿轮原位，克隆体才出现（否则会看到两个齿轮）。 */
private const val FOLLOW_FROM = 0.55f

/** 打开/关闭动画时长（毫秒）。 */
const val SETTINGS_ANIM_MILLIS = 340

/** 齿轮旋转角度（度）。 */
const val SETTINGS_GEAR_DEGREES = 90f

/** 创建面板的开合动画 spec（打开与关闭共用，保证对称）。 */
internal fun settingsTween() = tween<Float>(
    durationMillis = SETTINGS_ANIM_MILLIS,
    easing = FastOutSlowInEasing,
)

@Composable
private fun SectionBlock(
    section: SettingsSection,
    uiScale: Float,
    s: (Dp) -> Dp,
    onEntryClick: (SettingsEntryKey) -> Unit,
    onChoiceSelected: (SettingsEntryKey, Int) -> Unit,
) {
    val colors = NongyuTheme.colors
    Text(
        text = section.title,
        color = colors.surface.colorTextSecondary,
        fontSize = (12f * uiScale).sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = s(2.dp), bottom = s(6.dp)),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(s(12.dp)))
            .background(colors.surface.colorText.copy(alpha = 0.06f)),
    ) {
        section.entries.forEachIndexed { idx, entry ->
            EntryRow(
                entry = entry,
                uiScale = uiScale,
                s = s,
                onClick = { onEntryClick(entry.key) },
                onChoiceSelected = { i -> onChoiceSelected(entry.key, i) },
            )
            if (idx != section.entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = s(12.dp))
                        .height(1.dp)
                        .background(colors.surface.colorCard),
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: SettingsEntry,
    uiScale: Float,
    s: (Dp) -> Dp,
    onClick: () -> Unit,
    onChoiceSelected: (Int) -> Unit,
) {
    val colors = NongyuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = s(12.dp), vertical = s(11.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = if (entry is SettingsActionEntry && entry.emphasis) {
                    colors.brand.colorBrand
                } else {
                    colors.surface.colorText
                },
                fontSize = (14f * uiScale).sp,
            )
            entry.subtitle?.let {
                Text(
                    text = it,
                    color = colors.surface.colorTextSecondary,
                    fontSize = (11f * uiScale).sp,
                    style = TextStyle(lineHeight = (11f * uiScale * 1.35f).sp),
                    modifier = Modifier.padding(top = s(2.dp)),
                )
            }
        }

        when (entry) {
            is SettingsSwitchEntry -> SwitchDot(on = entry.isOn, s = s)
            is SettingsChoiceEntry -> ChoiceChips(
                entry = entry,
                uiScale = uiScale,
                s = s,
                onSelected = onChoiceSelected,
            )
            is SettingsActionEntry -> ChevronRight(
                size = s(14.dp),
                tint = colors.surface.colorTextSecondary,
            )
        }
    }
}

/** 极简开关：一条轨道 + 一个圆点，不引 Material Switch（尺寸与项目尺度不一致）。 */
@Composable
private fun SwitchDot(on: Boolean, s: (Dp) -> Dp) {
    val colors = NongyuTheme.colors
    val trackW = s(38.dp)
    val trackH = s(22.dp)
    Box(
        modifier = Modifier
            .size(width = trackW, height = trackH)
            .clip(RoundedCornerShape(trackH / 2))
            .background(if (on) colors.brand.colorBrand else colors.surface.colorText.copy(alpha = 0.06f)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = s(3.dp))
                .size(trackH - s(6.dp))
                .clip(RoundedCornerShape(trackH / 2))
                .background(
                    if (on) colors.brand.colorOnBrand else colors.surface.colorTextSecondary,
                ),
        )
    }
}

/** 互斥选项：横排小胶囊。 */
@Composable
private fun ChoiceChips(
    entry: SettingsChoiceEntry,
    uiScale: Float,
    s: (Dp) -> Dp,
    onSelected: (Int) -> Unit,
) {
    val colors = NongyuTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(s(6.dp))) {
        entry.options.forEachIndexed { i, label ->
            val active = i == entry.selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(s(8.dp)))
                    .background(if (active) colors.brand.colorBrand else colors.surface.colorText.copy(alpha = 0.06f))
                    .clickable { onSelected(i) }
                    .padding(horizontal = s(9.dp), vertical = s(5.dp)),
            ) {
                Text(
                    text = label,
                    color = if (active) {
                        colors.brand.colorOnBrand
                    } else {
                        colors.surface.colorTextSecondary
                    },
                    fontSize = (11f * uiScale).sp,
                )
            }
        }
    }
}

/** 右上角关闭 X（自绘，零依赖）。 */
@Composable
private fun CloseCross(size: Dp, tint: Color, onClick: () -> Unit, uiScale: Float) {
    Canvas(
        modifier = Modifier
            .size(size)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
            ),
    ) {
        val p = this.size.minDimension
        val inset = p * 0.18f
        val sw = p * 0.11f
        drawLine(tint, Offset(inset, inset), Offset(p - inset, p - inset), sw, StrokeCap.Round)
        drawLine(tint, Offset(p - inset, inset), Offset(inset, p - inset), sw, StrokeCap.Round)
    }
}

/** 指向右的小箭头（动作项）。 */
@Composable
private fun ChevronRight(size: Dp, tint: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * 0.13f
        drawLine(tint, Offset(w * 0.35f, h * 0.2f), Offset(w * 0.68f, h * 0.5f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.68f, h * 0.5f), Offset(w * 0.35f, h * 0.8f), sw, StrokeCap.Round)
    }
}

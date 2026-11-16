package com.nongyu.appplus.design

import android.content.Context
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 纯文字底部提示（不带 App 图标）。
 *
 * ## 为什么要自己造
 *
 * `Toast.makeText(...)` 在 Android 11+ 会给提示左侧带一个**应用小图标**。
 * 维护者明确要求「纯文字提示，前面不要加应用图标」，而 `makeText` 没有任何 API
 * 能去掉它 —— 官方做法是给 Toast 设一个自定义 `view`，系统就不再加图标。
 *
 * ## 样式
 *
 * - 底部居中、圆角深色胶囊，白字；
 * - 宽度由内容决定（`WRAP_CONTENT`），长文案靠 `maxWidth` 限制后自动换行；
 * - 用 [textSizeSp] / [paddingH] / [paddingV] 与调用方传入的 `uiScale` 保持尺度一致。
 *
 * ## 用法
 * ```kotlin
 * showTextToast(context, "课表已更新：19 个时段", uiScale = 1.2f)
 * ```
 * 传 `long = true` 时停留更久（3.5s），用于需要读清楚的失败原因。
 */
fun showTextToast(
    context: Context,
    text: String,
    uiScale: Float = 1f,
    long: Boolean = false,
) {
    if (text.isBlank()) return

    val density = context.resources.displayMetrics.density
    val fontSizeSp = 13f * uiScale
    val padH = (16f * uiScale * density).toInt()
    val padV = (10f * uiScale * density).toInt()

    val tv = TextView(context).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = fontSizeSp
        // 圆角深色胶囊（不用主题色：这是系统级轻提示，保持中性）
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18f * uiScale * density
            setColor(0xE6323232.toInt())
        }
        setPadding(padH, padV, padH, padV)
        // 长文案不撑破屏幕
        maxWidth = (280f * uiScale * density).toInt()
    }

    Toast(context.applicationContext).apply {
        duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        view = tv
        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (96f * uiScale * density).toInt())
    }.show()
}

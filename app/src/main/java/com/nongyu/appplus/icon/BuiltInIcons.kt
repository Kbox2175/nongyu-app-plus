package com.nongyu.appplus.icon

import androidx.annotation.DrawableRes
import com.nongyu.appplus.R
import com.nongyu.appplus.skin.NavIconKey

/**
 * 内置图标：皮肤侧键 → `res/drawable` 里的 VectorDrawable。
 *
 * ## 回落链中的位置
 *
 * ```
 * 皮肤包 iconImages（位图）  →  res 里的 VectorDrawable（本文件）  →  不绘制
 * ```
 *
 * 皮肤包没给某个图标时（[com.nongyu.appplus.skin.ResourcePayload.iconImageOf] 返回 `null`），
 * 渲染层就用这里的矢量图。**两边都是同一套设计**，所以换肤/不换肤的观感一致。
 *
 * ## 为什么把映射放在 icon 包而不是 skin 包
 *
 * `skin` 包是**纯数据契约**（不依赖 `R`，能在纯 JVM 单测里跑）；
 * `icon` 包是**渲染层**，可以依赖 Android 资源。这样分层不破 ADR-0008。
 *
 * ## 2026-11-16 资源化
 *
 * 这些图标原先全是 Kotlin `Canvas` 手绘（`TimetableToolbar` 4 个 + `NavIconGeometry` 4 个）。
 * 迁移到 `res/drawable/` 下的 XML 矢量图后：
 * - 皮肤系统能替换它们（扩展了 [NavIconKey.BuiltIn] 到 9 项）；
 * - 图标不再是代码，可用设计工具直接导出/校对；
 * - **代价**：`iconStrokes` 的线宽调节对导航图标不再生效（维护者已明确「不保」）。
 *
 * ## 不在本表里的图标
 *
 * - 密码眼睛（睁/闭）：维护者明确要求**不进皮肤层**，仍由 `LoginFormBox` 自绘。
 * - 星云球：着色器/渐变图形，不是图标，仍自绘。
 * - 登录页装饰、地点编辑小图标：装饰性图形，未纳入皮肤契约。
 */
object BuiltInIcons {

    /**
     * 取某个图标键对应的矢量资源。
     *
     * @return 资源 id；没有对应矢量图时返回 `null`（调用方应静默不画，而不是崩）。
     */
    @DrawableRes
    fun vectorOf(key: NavIconKey): Int? = when (key) {
        // 底部导航
        NavIconKey.Grid -> R.drawable.ic_nav_grid
        NavIconKey.Calendar -> R.drawable.ic_nav_calendar
        NavIconKey.Compass -> R.drawable.ic_nav_compass
        NavIconKey.Person -> R.drawable.ic_nav_person

        // 课表工具条
        NavIconKey.Settings -> R.drawable.ic_toolbar_settings
        NavIconKey.Update -> R.drawable.ic_toolbar_update
        NavIconKey.Day -> R.drawable.ic_toolbar_day
        NavIconKey.Week -> R.drawable.ic_toolbar_week
        NavIconKey.Add -> R.drawable.ic_toolbar_add

        // 未知键：不画（ADR-0006 Q3=B 静默回落）
        else -> null
    }
}

package com.nongyu.appplus.settings.logic

/**
 * 设置齿轮「交接」判定的**纯逻辑**（无 Android / Compose 依赖，可纯 JVM 单测）。
 *
 * ## 背景（维护者 2026-11-23 连续两轮反馈）
 *
 * 1. 「点击后出现两个齿轮」—— 克隆体与原图标同时可见。
 * 2. 「克隆体先出现，后图标消失」—— 两者存在重叠窗口。
 *
 * 根因是**两个位置各自算自己的显示条件**：
 * 工具条按「面板有没有盖住我」判断，设置面板按「进度到没到某个阈值」判断，
 * 两条判据不可能严格对齐，于是必然出现重叠或空隙。
 *
 * ## 解法
 *
 * 把「是否已接管」收敛成**一个布尔量**，由这里统一算出，
 * 工具条拿它决定「画不画原图标」，设置面板拿它决定「画不画克隆体」——
 * 二者共用同一输入，天然互斥，不可能同时出现，也不会都消失。
 */
object GearHandoff {

    /**
     * 判断设置面板是否已经接管齿轮。
     *
     * ## 判据：面板左边缘是否已越过齿轮**中心**
     *
     * 取中心而不是右边缘，是因为：
     * - 用右边缘：面板要滑过整个齿轮宽度才接管，中间会有一段「原图标已被面板盖掉一半、
     *   克隆体还没出现」的视觉断裂；
     * - 用中心：面板盖住齿轮一半的瞬间完成交接，此时两者重叠区域最大，
     *   切换最不易察觉。
     *
     * @param panelLeftPx 面板当前左边缘的 x（像素，根容器坐标）
     * @param gearCenterX 齿轮中心的 x（像素，同一坐标系）
     * @return `true` = 由克隆体显示，工具条应隐藏原图标
     */
    fun isTakenOver(panelLeftPx: Float, gearCenterX: Float): Boolean =
        panelLeftPx <= gearCenterX

    /**
     * 克隆体相对原位置的**进度** 0..1：0 = 完全停在原位，1 = 已到达面板标题行。
     *
     * 只在接管之后才有意义（接管前恒为 0，即停在原位）。
     * 用「齿轮宽度」做归一化，让过渡在固定的像素距离内完成，
     * 与动画总时长、设备密度都无关。
     *
     * @param panelLeftPx 面板当前左边缘 x
     * @param gearCenterX 齿轮中心 x
     * @param gearWidthPx 齿轮宽度（像素）
     */
    fun travelProgress(
        panelLeftPx: Float,
        gearCenterX: Float,
        gearWidthPx: Float,
    ): Float {
        if (!isTakenOver(panelLeftPx, gearCenterX)) return 0f
        val half = (gearWidthPx / 2f).coerceAtLeast(1f)
        // 从「面板左缘 = 齿轮中心」到「面板左缘 = 齿轮左缘」走完一个半宽
        val gearLeft = gearCenterX - half
        return ((gearCenterX - panelLeftPx) / half).coerceIn(0f, 1f)
    }
}

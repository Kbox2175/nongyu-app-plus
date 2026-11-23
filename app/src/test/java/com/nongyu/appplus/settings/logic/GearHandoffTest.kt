package com.nongyu.appplus.settings.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GearHandoff] 的单测。
 *
 * 这两条判据直接决定「原图标何时消失、克隆体何时出现」：
 * 判错一点就会变成维护者反复反馈的那两种症状 ——
 * 「出现两个齿轮」（判据不一致，两边同时可见）
 * 和「克隆体先出现、原图标后消失」（克隆体提前）。
 */
class GearHandoffTest {

    private val gearCenter = 500f
    private val gearWidth = 32f

    // ---- isTakenOver ----

    @Test
    fun `面板还没够到齿轮时未接管`() {
        // 左缘在齿轮右边很远
        assertFalse(GearHandoff.isTakenOver(panelLeftPx = 800f, gearCenterX = gearCenter))
    }

    @Test
    fun `面板左缘正好到齿轮中心时接管`() {
        // 用中心而不是右缘作为分界：此刻两者重叠面积最大，切换最不易察觉
        assertTrue(GearHandoff.isTakenOver(panelLeftPx = gearCenter, gearCenterX = gearCenter))
    }

    @Test
    fun `面板已越过齿轮时接管`() {
        assertTrue(GearHandoff.isTakenOver(panelLeftPx = 300f, gearCenterX = gearCenter))
    }

    // ---- travelProgress ----

    @Test
    fun `未接管时位移进度恒为 0`() {
        assertEquals(
            0f,
            GearHandoff.travelProgress(panelLeftPx = 800f, gearCenterX = gearCenter, gearWidthPx = gearWidth),
            0.0001f,
        )
    }

    @Test
    fun `刚接管那一刻位移为 0（不会跳变）`() {
        // 这一条是「克隆体瞬移」的关键：接管瞬间必须仍停在原位
        val p = GearHandoff.travelProgress(
            panelLeftPx = gearCenter,
            gearCenterX = gearCenter,
            gearWidthPx = gearWidth,
        )
        assertEquals(0f, p, 0.0001f)
    }

    @Test
    fun `面板滑过半个齿轮宽度后位移走满`() {
        // 半宽 = 16，左缘到 484 时进度应为 1
        val p = GearHandoff.travelProgress(
            panelLeftPx = gearCenter - gearWidth / 2f,
            gearCenterX = gearCenter,
            gearWidthPx = gearWidth,
        )
        assertEquals(1f, p, 0.0001f)
    }

    @Test
    fun `位移进度被夹在 0 到 1 之间`() {
        // 面板远远越过齿轮，不应超过 1
        val p = GearHandoff.travelProgress(
            panelLeftPx = 0f,
            gearCenterX = gearCenter,
            gearWidthPx = gearWidth,
        )
        assertEquals(1f, p, 0.0001f)
    }

    @Test
    fun `齿轮宽度为 0 时不会除零`() {
        val p = GearHandoff.travelProgress(
            panelLeftPx = gearCenter,
            gearCenterX = gearCenter,
            gearWidthPx = 0f,
        )
        assertTrue("不应出现 NaN 或 Infinity", p.isFinite())
    }
}

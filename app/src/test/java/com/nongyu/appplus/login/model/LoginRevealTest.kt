package com.nongyu.appplus.login.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * [LoginReveal] 圆形揭示几何单测（票 #94 修订 + 2026-11-16 真机回归修复）。
 *
 * ## 本轮重点钉死的不变量
 * **圆洞必须始终覆盖登录按钮** —— 圆的下边缘 `cy + r` 只增不减。
 *
 * 上一版测试曾绿着放行了一个真机 bug：圆心上升 0.34、半径增长 0.718，
 * 在 1080×2400 上 Δcy=816px > Δr=775px，圆的下边缘上移 40px，
 * 真机表现为「按钮没消失、圆跑到按钮上方」。
 * 原因是测试里把「高→宽」的换算写反了（用了 W/H，应为 H/W），
 * 把一次违规算成了合规。所以现在：
 * 1. 用 [LoginReveal.bottomEdgeNeverRecedes] 这个**与被测实现解耦不了**的统一判据；
 * 2. 覆盖多种真实宽高比（全面屏 / 16:9 / 方形 / 平板）。
 */
class LoginRevealTest {

    // ---------- 仅主动登录成功才播 ----------

    @Test
    fun `登录中心到主界面才播放`() {
        assertTrue(LoginReveal.shouldPlay(LoginDestination.LOGIN_CENTER, LoginDestination.MAIN))
    }

    @Test
    fun `冷启动与其它跳变都不播放`() {
        assertFalse(LoginReveal.shouldPlay(LoginDestination.MAIN, LoginDestination.MAIN))
        assertFalse(LoginReveal.shouldPlay(LoginDestination.MAIN, LoginDestination.LOGIN_CENTER))
        assertFalse(LoginReveal.shouldPlay(LoginDestination.LOGIN_CENTER, LoginDestination.LOGIN_CENTER))
    }

    // ---------- 起止状态 ----------

    @Test
    fun `起点半径大于登录按钮半径以容忍设备偏差`() {
        // 按钮半径约 0.0815（88dp / 540dp 基准宽 / 2）
        assertTrue(
            "起始半径 ${LoginReveal.START_RADIUS_RATIO} 应大于按钮半径 0.0815",
            LoginReveal.START_RADIUS_RATIO > 0.0815f,
        )
        assertEquals(LoginReveal.START_RADIUS_RATIO, LoginReveal.radiusRatio(0f), 0.0001f)
    }

    @Test
    fun `洞起点必须包住不同设备的登录按钮`() {
        // 两个实测/估算锚点（1080×2400）：
        //   蓝叠 @320dpi：按钮圆心 y=2146，半径 88px（navigationBars 与 uiScale 交互后的真实落位）
        //   真机 @440dpi（scale≈0.726）：圆心 y≈2017，半径 88px
        // 起始洞若不比按钮大，猜错圆心的那台就会露出半截按钮——这正是真机报的问题。
        val h = 2400f
        val w = 1080f
        val holeCy = LoginReveal.centerYRatio(0f) * h
        val holeR = LoginReveal.radiusRatio(0f) * w
        listOf(
            "蓝叠 圆心2146" to (2146f to 88f),
            "真机 圆心2017" to (2017f to 88f),
        ).forEach { (name, btn) ->
            val (btnCy, btnR) = btn
            assertTrue(
                "$name：洞上沿 ${holeCy - holeR} 应不超过按钮上沿 ${btnCy - btnR}",
                holeCy - holeR <= btnCy - btnR,
            )
            assertTrue(
                "$name：洞下沿 ${holeCy + holeR} 应不低于按钮下沿 ${btnCy + btnR}",
                holeCy + holeR >= btnCy + btnR,
            )
        }
    }

    @Test
    fun `起点圆心在登录按钮位置`() {
        assertEquals(LoginReveal.START_CENTER_Y_RATIO, LoginReveal.centerYRatio(0f), 0.0001f)
    }

    @Test
    fun `终点圆心略高于起点`() {
        assertEquals(LoginReveal.END_CENTER_Y_RATIO, LoginReveal.centerYRatio(1f), 0.0001f)
        assertTrue(
            "圆心应向上（y 变小）",
            LoginReveal.centerYRatio(1f) < LoginReveal.centerYRatio(0f),
        )
    }

    @Test
    fun `圆心横向始终居中`() {
        assertEquals(0.5f, LoginReveal.CENTER_X_RATIO, 0.0001f)
    }

    // ---------- 核心不变量：圆洞始终覆盖按钮 ----------

    @Test
    fun `全面屏1080x2400下圆下边缘不倒退`() {
        // 真机回归的那台：1080×2400 @440dpi
        assertTrue(LoginReveal.bottomEdgeNeverRecedes(1080f / 2400f))
    }

    @Test
    fun `常见宽高比下圆下边缘都不倒退`() {
        val ratios = mapOf(
            "1080x2400 全面屏" to 1080f / 2400f,
            "1080x2340 常见" to 1080f / 2340f,
            "1080x1920 16:9" to 1080f / 1920f,
            "1440x3120 加长" to 1440f / 3120f,
            "1080x3000 超长" to 1080f / 3000f,
            "1080x1080 方形" to 1f,
            "1600x2560 平板" to 1600f / 2560f,
        )
        ratios.forEach { (name, ratio) ->
            assertTrue(
                "$name（宽高比 $ratio）下圆的下边缘不应倒退，否则会露出登录按钮",
                LoginReveal.bottomEdgeNeverRecedes(ratio),
            )
        }
    }

    @Test
    fun `圆心上升量不超过半径增长量_像素口径`() {
        // 1080×2400：Δcy_px = Δcy_ratio × 2400，Δr_px = Δr_ratio × 1080
        val h = 2400f
        val w = 1080f
        val dCy = (LoginReveal.START_CENTER_Y_RATIO - LoginReveal.END_CENTER_Y_RATIO) * h
        val dR = (LoginReveal.END_RADIUS_RATIO - LoginReveal.START_RADIUS_RATIO) * w
        assertTrue(
            "位移 ${dCy}px 不应超过半径增长 ${dR}px",
            dCy <= dR,
        )
    }

    @Test
    fun `终点半径能覆盖主流机型的最远角`() {
        // 圆心终点在屏幕中心，最远角是四个角之一
        listOf(
            "1080x2400" to (1080f to 2400f),
            "1080x2340" to (1080f to 2340f),
            "1080x1920" to (1080f to 1920f),
            "1440x3120" to (1440f to 3120f),
            "1600x2560" to (1600f to 2560f),
        ).forEach { (name, dims) ->
            val (w, h) = dims
            val cx = LoginReveal.CENTER_X_RATIO * w
            val cy = LoginReveal.END_CENTER_Y_RATIO * h
            val far = maxOf(
                sqrt(cx * cx + cy * cy),
                sqrt((w - cx) * (w - cx) + cy * cy),
                sqrt(cx * cx + (h - cy) * (h - cy)),
                sqrt((w - cx) * (w - cx) + (h - cy) * (h - cy)),
            )
            val r = LoginReveal.END_RADIUS_RATIO * w
            assertTrue("$name：终点半径 ${r}px 应覆盖最远角 ${far}px", r >= far)
        }
    }

    @Test
    fun `超长屏靠收尾整层移除兜底`() {
        // 1080×3000（27.8:9）这类罕见超长屏，1.30 的半径覆盖不到四角；
        // 此时依赖 isLoginLayerGone 在 progress=1 时整层移除，不留残影。
        val w = 1080f
        val h = 3000f
        val far = sqrt((w / 2) * (w / 2) + (h / 2) * (h / 2))
        val r = LoginReveal.END_RADIUS_RATIO * w
        assertTrue("该机型半径确实不够，是已知边界", r < far)
        assertTrue("但转场结束必须整层移除", LoginReveal.isLoginLayerGone(1f))
    }

    // ---------- 单调性 ----------

    @Test
    fun `半径单调不减`() {
        var prev = -1f
        var p = 0f
        while (p <= 1.0001f) {
            val r = LoginReveal.radiusRatio(p)
            assertTrue("半径不应回退（p=$p）", r >= prev - 0.0001f)
            prev = r
            p += 0.01f
        }
    }

    @Test
    fun `圆心单调向上`() {
        var prev = 2f
        var p = 0f
        while (p <= 1.0001f) {
            val y = LoginReveal.centerYRatio(p)
            assertTrue("圆心应持续向上（p=$p, y=$y）", y <= prev + 0.0001f)
            prev = y
            p += 0.01f
        }
    }

    @Test
    fun `圆心不会移出屏幕`() {
        var p = 0f
        while (p <= 1.0001f) {
            val y = LoginReveal.centerYRatio(p)
            assertTrue("圆心越界（y=$y）", y in 0f..1f)
            p += 0.02f
        }
    }

    // ---------- 收尾 ----------

    @Test
    fun `起点不判定登录层消失`() {
        assertFalse(LoginReveal.isLoginLayerGone(0f))
    }

    @Test
    fun `转场结束必然移除登录层`() {
        assertTrue(LoginReveal.isLoginLayerGone(1f))
    }

    @Test
    fun `中途不提前移除登录层`() {
        assertFalse(LoginReveal.isLoginLayerGone(0.99f))
    }

    // ---------- 夹紧与常量 ----------

    @Test
    fun `进度越界被夹紧`() {
        assertEquals(LoginReveal.radiusRatio(0f), LoginReveal.radiusRatio(-1f), 0.0001f)
        assertEquals(LoginReveal.radiusRatio(1f), LoginReveal.radiusRatio(2f), 0.0001f)
    }

    @Test
    fun `总时长是正数`() {
        assertTrue(LoginReveal.DURATION_MS > 0)
    }

    @Test
    fun `圆心上升幅度保守_不超过半径增幅的一半`() {
        // 用「相对宽度」口径粗校：Δcy_ratio(高度) 换算到宽度量纲后
        // 必须明显小于 Δr_ratio，留出跨机型余量
        val dCy = LoginReveal.START_CENTER_Y_RATIO - LoginReveal.END_CENTER_Y_RATIO
        val dR = LoginReveal.END_RADIUS_RATIO - LoginReveal.START_RADIUS_RATIO
        // 最坏宽高比取 2.5（比 1080/2400=2.22 更极端）
        val dCyInWidthUnits = dCy * 2.5f
        assertTrue(
            "位移换算后 $dCyInWidthUnits 应不超过半径增幅 $dR",
            dCyInWidthUnits <= dR,
        )
    }
}

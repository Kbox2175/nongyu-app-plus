package com.nongyu.appplus.login.model

/**
 * 登录成功转场：**圆形揭示**（iris reveal）的几何编排（票 #94 修订，纯 JVM 可测）。
 *
 * ## 视觉目标
 * 点击登录后**立刻渲染主界面**，登录页整层盖在上方；在登录按钮位置挖一个圆洞，
 * 透过洞看到下面的主界面（登录页背景此时不变），随后圆洞扩大、圆心略微上移，
 * 直到把登录页整层吃掉。
 *
 * ## 硬约束：圆洞必须**始终覆盖**登录按钮
 * 设圆心 `cy`、半径 `r`，圆的下边缘是 `cy + r`。要保证按钮不被露出来，
 * 必须让下边缘**只增不减**：
 * ```
 * Δcy + Δr ≥ 0   ⇔   |Δcy| ≤ Δr      （圆心只上移，故 Δcy < 0）
 * ```
 * 注意这里是**像素**比较，而本类用「相对宽度」表达半径、「相对高度」表达圆心，
 * 两者量纲不同，必须先换算：
 * ```
 * Δr_px = Δr_ratio × 屏宽
 * Δcy_px = Δcy_ratio × 屏高
 * 约束：Δcy_ratio × 屏高 ≤ Δr_ratio × 屏宽
 * ```
 * 在 1080×2400（高宽比 2.22）这类最常见的全面屏上，圆心上移比例必须
 * **≤ 半径增长比例的 0.45 倍**。本类取 0.08 / 0.768 ≈ 0.10，留有充分余量。
 *
 * ## 历史教训（2026-11-16）
 * 上一版取圆心 0.84→0.50（Δ=0.34）、半径 0.082→0.80（Δ=0.718）。
 * 在 1080×2400 上 Δcy_px=816 > Δr_px=775，圆的下边缘上移 40px，
 * 真机上表现为「按钮没消失、圆跑到按钮上方」。
 * 当时的单测之所以绿，是因为换算方向写反了（用了宽/高，应为高/宽）。
 *
 * 第二轮追加修复：起始圆心按公式算成 0.84，但蓝叠实测在 0.894，
 * 洞的起点盖不住按钮下半截（转场最初几帧露出按钮）。现已改为 0.87 + 加大起始半径。
 */
object LoginReveal {

    /** 转场总时长（毫秒）。 */
    const val DURATION_MS = 620

    /**
     * 起始半径（相对屏幕**宽度**）：0.14，比登录按钮本身略大。
     *
     * ## 为什么不再用「按钮半径」0.082
     * 按钮的实际落位取决于 `navigationBarsPadding()` 与 [uiScale] 的交互，
     * **不同设备上不完全一样**：
     * - 蓝叠 1080×2400 @320dpi：实测按钮圆心 y=2146，半径 88px
     * - 真机 1080×2400 @440dpi（scale≈0.726）：估算圆心 y≈2017，半径 88px
     *
     * 圆心比例因此分布在 0.84 ~ 0.894 之间。起始半径若只等于按钮半径，
     * 洞就只能在「猜对圆心」的设备上完整包住按钮，猜错的那台会露出半截。
     * 取 0.15（162px）配 [START_CENTER_Y_RATIO]=0.87，可同时包住上述两端
     * （蓝叠 洞 y=1926..2250 盖住按钮 2058..2234；真机 盖住 1929..2105）。
     *
     * 代价是洞一开始就比按钮大一圈；由于按钮同时在快速淡出，视觉上察觉不到。
     */
    const val START_RADIUS_RATIO = 0.15f

    /**
     * 起始圆心纵向位置（相对屏幕**高度**）：0.87，取跨设备按钮圆心的中值。
     *
     * 按钮底距 148dp、半径 44dp，但 `navigationBarsPadding()` 与 [uiScale]
     * 会把实际位置推到 0.84（真机估算）~ 0.894（蓝叠实测）之间，取中值 0.87。
     * 详见 [START_RADIUS_RATIO] 的跨设备说明。
     */
    const val START_CENTER_Y_RATIO = 0.87f

    /**
     * 结束圆心纵向位置（相对屏幕高度）：升到屏幕垂直中心 0.50。
     *
     * ## 与半径的配对关系（改这个必须同时核 [END_RADIUS_RATIO]）
     * 圆心最终必须落在屏幕中心，圆才能用最小半径覆盖四个角；
     * 同时「位移 ≤ 半径增长」在主流宽高比下仍然成立（见 [bottomEdgeNeverRecedes]）。
     *
     * 2026-11-16 的教训：上一版圆心只升到 0.76、半径 0.85，
     * 下边缘安全了，但**圆根本覆盖不到屏幕上部**（1080×2400 上到上角需 1316px，
     * 而半径只有 918px），转场末段会露出登录页上半屏。
     *
     * 取 0.53（而不是正中的 0.50）是为了给「极窄屏也单调」留余量：
     * Δcy=0.34 配 Δr=1.25 时，单调门槛是宽高比 > 0.272，
     * 覆盖到 1080×3840（0.2812）这类极端长屏。
     */
    const val END_CENTER_Y_RATIO = 0.53f

    /**
     * 结束半径（相对屏幕宽度）：1.30，足以覆盖圆心在屏中时的四个角。
     *
     * 圆心在屏幕中心时，到角距离 = `sqrt((W/2)² + (H/2)²) / W`。
     * 1080×2400 → 1382px，而 1.40×1080 = 1512px，覆盖有余；
     * 1080×3000（27.8:9 的超长屏，罕见）需 1679px，差 167px，
     * 由 [isLoginLayerGone] 在转场结束时整层移除兜底，不会留残影。
     *
     * 另一个作用是放宽单调门槛：[END_CENTER_Y_RATIO] 的位移 0.34
     * 需要 Δr 足够大，才在窄屏上仍保证下边缘不倒退。
     */
    const val END_RADIUS_RATIO = 1.40f

    /** 圆心横向位置：始终在屏幕水平中心（相对宽度 0.5）。 */
    const val CENTER_X_RATIO = 0.5f

    /** 半径缓动：`t²`（先慢后快），让「洞一开始几乎不动，然后迅速吞掉全屏」。 */
    fun radiusRatio(progress: Float): Float {
        val t = progressOf(progress)
        val eased = t * t
        return START_RADIUS_RATIO + (END_RADIUS_RATIO - START_RADIUS_RATIO) * eased
    }

    /**
     * 圆心纵向位置（相对屏幕高度），从 [START_CENTER_Y_RATIO] 升到 [END_CENTER_Y_RATIO]。
     *
     * ## 必须与半径**同阶**缓动（都用 `t²`）
     * 下边缘 `bottom(t) = cy(t) + r(t)·wh` 的导数是：
     * ```
     * d(bottom)/dt = −0.34·k_cy·t^(k_cy−1) + 1.218·wh·k_r·t^(k_r−1)
     * ```
     * 两者同阶（k_cy = k_r = 2）时：
     * `d/dt = 2t·(−0.34 + 1.218·wh)`，只要 `wh > 0.279` 就恒为正 —— 单调不倒退。
     *
     * 曾用 `t³`（想「起点更慢」）反而坏了：t→1 时 `3t²` 的增速超过 `2t`，
     * 末段下边缘会倒退（1080×3000 实测在 p=0.885 倒退）。
     * 真实手机的最窄宽高比约 0.28（1080×3840），仍满足恒正条件。
     */
    fun centerYRatio(progress: Float): Float {
        val t = progressOf(progress)
        val eased = t * t
        return START_CENTER_Y_RATIO + (END_CENTER_Y_RATIO - START_CENTER_Y_RATIO) * eased
    }

    /** 进度夹紧到 0..1。 */
    fun progressOf(raw: Float): Float = raw.coerceIn(0f, 1f)

    /**
     * 登录页（被挖洞那层）是否可以整层移除。
     *
     * 取 `progress >= 1`：转场走完必然移除，由本类自己兜底，
     * 不依赖「当前屏幕的宽高比是否已被半径覆盖」这种机型相关的判断
     * （超长屏如 1080×3000 上 1.30 的半径仍差约 190px，靠这里兜底）。
     */
    fun isLoginLayerGone(progress: Float): Boolean = progressOf(progress) >= 1f

    /**
     * 在给定屏幕宽高比下，圆的下边缘是否**从未倒退**。
     *
     * 这是本类的核心不变量，抽成函数是为了让 UI 与测试共用同一判据。
     *
     * @param widthOverHeight 屏宽 / 屏高（如 1080/2400 ≈ 0.45）
     */
    fun bottomEdgeNeverRecedes(widthOverHeight: Float, steps: Int = 200): Boolean {
        if (widthOverHeight <= 0f || steps <= 0) return false
        var prevBottom = START_CENTER_Y_RATIO + START_RADIUS_RATIO * widthOverHeight
        for (i in 1..steps) {
            val p = i.toFloat() / steps
            // 统一换算到「相对高度」量纲再相加
            val bottom = centerYRatio(p) + radiusRatio(p) * widthOverHeight
            if (bottom < prevBottom - 1e-4f) return false
            prevBottom = bottom
        }
        return true
    }

    /**
     * 是否应播放转场：只有「登录中心 → 主界面」这一跳。
     *
     * @param fromLoginCenter 转场前所在去向
     * @param toMain 转场后所在去向
     */
    fun shouldPlay(fromLoginCenter: LoginDestination, toMain: LoginDestination): Boolean =
        fromLoginCenter == LoginDestination.LOGIN_CENTER && toMain == LoginDestination.MAIN
}

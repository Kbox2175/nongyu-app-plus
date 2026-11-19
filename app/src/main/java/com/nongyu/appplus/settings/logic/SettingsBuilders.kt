package com.nongyu.appplus.settings.logic

import com.nongyu.appplus.settings.model.SettingsActionEntry
import com.nongyu.appplus.settings.model.SettingsChoiceEntry
import com.nongyu.appplus.settings.model.SettingsContent
import com.nongyu.appplus.settings.model.SettingsEntryKey
import com.nongyu.appplus.settings.model.SettingsSection
import com.nongyu.appplus.settings.model.SettingsSectionKey
import com.nongyu.appplus.settings.model.SettingsSwitchEntry

/**
 * 课程表设置分区的**构建逻辑**（纯逻辑，无 Android / Compose 依赖，可纯 JVM 单测）。
 *
 * ## 为什么单独一层而不是直接在 Composable 里拼
 *
 * 「哪些设置项存在、当前值是什么」是**业务规则**：比如「本周是第几周」要按学期算，
 * 「日/周视图」当前态来自课表页。这些规则放在 Composable 里就无法单测
 * （`@Composable` 在纯 JVM 测试里调用即编译失败）。
 *
 * ## 扩展位（维护者的「预留」要求）
 *
 * 以后其他模块要加设置，就在 [SettingsBuilders.all] 里注册一个新的 builder，
 * **面板本身一行都不用改**。
 */
object TimetableSettings {

    /** 课程表设置项用到的全部 key（渲染层与测试都从这里取，避免字符串散落）。 */
    object Keys {
        val DayWeekView = SettingsEntryKey("timetable.dayWeekView")
        val ShowWeekends = SettingsEntryKey("timetable.showWeekends")
        val UpdateSchedule = SettingsEntryKey("timetable.updateSchedule")
        val QuietRefresh = SettingsEntryKey("timetable.quietRefresh")

        /**
         * 「登录 / 刷新学校 WebVPN」动作项（WebVPN 弹窗的**显式手动调度入口**）。
         *
         * 放在课程表分区里是因为 WebVPN 票的用途就是课表代取；渲染层按 key 分发，
         * 面板本身不认识 WebVPN。见 `docs/specs/WebVPN登录弹窗.md` §3。
         */
        val WebVpnLogin = SettingsEntryKey("timetable.webVpnLogin")
    }

    /**
     * 生成课程表设置分区。
     *
     * @param dayMode 当前是否日视图（来自课表页，不在本层持有）
     * @param showWeekends 是否显示周末
     * @param quietRefresh 是否静默刷新（不弹提示）
     */
    fun build(
        dayMode: Boolean,
        showWeekends: Boolean,
        quietRefresh: Boolean,
    ): SettingsSection = SettingsSection(
        key = SettingsSectionKey.Timetable,
        title = "课程表",
        entries = listOf(
            // 日/周视图：与工具条那个按钮是同一个状态，这里给一个显式入口
            SettingsChoiceEntry(
                key = Keys.DayWeekView,
                title = "默认视图",
                subtitle = "打开课表时先显示哪一种",
                options = listOf("周视图", "日视图"),
                selectedIndex = if (dayMode) 1 else 0,
            ),
            SettingsSwitchEntry(
                key = Keys.ShowWeekends,
                title = "显示周末",
                subtitle = "关闭后只显示工作日",
                isOn = showWeekends,
            ),
            SettingsSwitchEntry(
                key = Keys.QuietRefresh,
                title = "静默刷新",
                subtitle = "更新课表成功后不弹提示",
                isOn = quietRefresh,
            ),
            SettingsActionEntry(
                key = Keys.UpdateSchedule,
                title = "更新课表",
                subtitle = "从教务重新拉取（60 秒内只能一次）",
            ),
            SettingsActionEntry(
                key = Keys.WebVpnLogin,
                title = "登录 / 刷新 WebVPN",
                subtitle = "校外访问教务需要连接学校网关",
            ),
        ),
    )
}

/**
 * 全部分区的注册表。
 *
 * 面板按这里的顺序渲染；新增模块的设置只要往列表里加一项。
 */
object SettingsBuilders {

    /**
     * 构建整个设置页内容。
     *
     * @param dayMode / @param showWeekends / @param quietRefresh 课程表分区的当前值
     */
    fun buildContent(
        dayMode: Boolean,
        showWeekends: Boolean,
        quietRefresh: Boolean,
    ): SettingsContent = SettingsContent(
        sections = listOf(
            TimetableSettings.build(
                dayMode = dayMode,
                showWeekends = showWeekends,
                quietRefresh = quietRefresh,
            ),
        ),
    )
}

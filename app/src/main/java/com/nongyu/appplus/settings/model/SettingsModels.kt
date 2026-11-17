package com.nongyu.appplus.settings.model

/**
 * 全局设置的数据模型（**纯数据**，不依赖 Android / Compose）。
 *
 * ## 为什么设置要「分区」而不是一长串开关
 *
 * 维护者明确：**全局设置与课程表设置共用一个包、算一个功能**，
 * 当前只落地「课程表」这一块，以后其他模块的设置也会挂进来。
 *
 * 所以模型一开始就按「分区（Section）→ 条目（Entry）」两层组织：
 * 新增一个模块的设置 = 往 [SettingsSection] 里加一个分区，
 * **不需要改面板布局**，面板按分区顺序渲染即可。
 *
 * ## 条目用密封接口而不是通用 (title, onClick)
 *
 * 三种交互形态的语义差别很大（开关有状态、跳转没有、单选有当前值），
 * 合成一个「标题 + 点击回调」会让渲染层靠猜。分开后编译器保证每种都处理。
 */

/** 设置分区的稳定标识（字符串而非枚举：与 `NavIconKey` 同一套降级思路）。 */
data class SettingsSectionKey(val name: String) {
    override fun toString() = name

    companion object {
        /** 课程表设置（本期唯一落地的分区）。 */
        val Timetable = SettingsSectionKey("timetable")
    }
}

/** 设置条目的稳定标识。用于皮肤/埋点/后续持久化按 key 读取。 */
data class SettingsEntryKey(val name: String) {
    override fun toString() = name
}

/** 一个设置条目。 */
sealed interface SettingsEntry {
    val key: SettingsEntryKey

    /** 主标题（一行内尽量放下）。 */
    val title: String

    /** 副标题/说明，可为空。 */
    val subtitle: String?
}

/**
 * 开关项：值由外部持有（本层不存状态）。
 *
 * @param isOn 当前是否开启
 */
data class SettingsSwitchEntry(
    override val key: SettingsEntryKey,
    override val title: String,
    override val subtitle: String? = null,
    val isOn: Boolean,
) : SettingsEntry

/**
 * 动作项：点了立刻执行（如「更新课表」「退出登录」）。
 *
 * @param emphasis 是否用强调色（如破坏性操作或主操作）
 */
data class SettingsActionEntry(
    override val key: SettingsEntryKey,
    override val title: String,
    override val subtitle: String? = null,
    val emphasis: Boolean = false,
) : SettingsEntry

/**
 * 单选项：一组互斥选项中的一个（如「视图：周 / 日」）。
 *
 * @param options 选项显示名
 * @param selectedIndex 当前选中项下标
 */
data class SettingsChoiceEntry(
    override val key: SettingsEntryKey,
    override val title: String,
    override val subtitle: String? = null,
    val options: List<String>,
    val selectedIndex: Int,
) : SettingsEntry

/**
 * 一个设置分区：标题 + 若干条目。
 *
 * 分区本身不带图标：图标在面板顶部（齿轮），分区只负责分组。
 */
data class SettingsSection(
    val key: SettingsSectionKey,
    val title: String,
    val entries: List<SettingsEntry>,
)

/**
 * 整个设置页的内容。
 *
 * 面板只消费这个对象，**不关心分区来自哪个模块** —— 这就是「预留扩展」的落点。
 */
data class SettingsContent(
    val sections: List<SettingsSection>,
) {
    val isEmpty: Boolean get() = sections.all { it.entries.isEmpty() }

    companion object {
        val Empty = SettingsContent(sections = emptyList())
    }
}

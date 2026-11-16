package com.nongyu.appplus.skin

/**
 * 字体的**选择决策**（票 #54）。纯 JVM 可测，刻意不 import `android.*` /
 * `androidx.compose.*`：把「选哪个字体」与「怎么把字节变成 FontFamily」分开。
 *
 * 真正构造 `FontFamily` 的动作在 `design/SkinFontFamilyFactory`（依赖 Android Framework），
 * 本文件只输出一个可被单测穷举的决策值。这与 T3/T5 处理 `org.json` 的同一套分层思路。
 *
 * ## 为什么需要它
 *
 * `SkinFont.FromPack` 只带包内相对文件名；渲染层要加载字体必须拿到**绝对路径**。
 * 路径由启动路径的 `FileSkinSource` 拼好塞进 `FromPack.absolutePath`，
 * 本对象负责在「有路径 / 没路径 / 文件名非法 / 系统默认」之间做**不抛异常**的判定
 * （ADR-0006 静默回落原则）。
 */
enum class FontChoice {
    /** 用系统默认字体（无包、损坏、路径缺失时的最终回落）。 */
    SystemDefault,

    /** 用包内字体文件，路径见 [FontDecision.absolutePath]。 */
    FromFile,
}

/** 一次字体选择的结果。[absolutePath] 仅在 [FontChoice.FromFile] 时非空。 */
data class FontDecision(val choice: FontChoice, val absolutePath: String?)

object SkinFontResolver {

    /**
     * 决定渲染层该用哪个字体。**永不抛异常**。
     *
     * @param font 资源段里的字体标识。
     * @param packDir 当前皮肤包目录（仅当 [font] 自己没带绝对路径时才用它拼）。
     */
    fun resolve(font: SkinFont, packDir: String? = null): FontDecision = when (font) {
        SkinFont.SystemDefault -> FontDecision(FontChoice.SystemDefault, null)

        is SkinFont.FromPack -> {
            // 1) 启动路径已拼好绝对路径 → 直接采用（推荐路径，渲染层无需知道包目录）。
            val ready = font.absolutePath?.trim()?.takeIf { it.isNotEmpty() }
            if (ready != null) {
                return FontDecision(FontChoice.FromFile, ready)
            }

            // 2) 没带绝对路径 → 用调用方给的包目录拼。文件名/目录任一无效都回落。
            val name = font.fileName.trim()
            val dir = packDir?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }
            if (name.isEmpty() || dir == null) {
                FontDecision(FontChoice.SystemDefault, null)
            } else {
                FontDecision(FontChoice.FromFile, "$dir/$name")
            }
        }
    }
}

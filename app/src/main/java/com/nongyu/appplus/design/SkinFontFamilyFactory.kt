package com.nongyu.appplus.design

import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import com.nongyu.appplus.skin.FontChoice
import com.nongyu.appplus.skin.SkinFont
import com.nongyu.appplus.skin.SkinFontResolver
import java.io.File

/**
 * 把皮肤字体标识转成 Compose [FontFamily]（票 #54）。
 *
 * ## 分层
 *
 * - 「选哪个字体」的纯决策在 `skin.SkinFontResolver`（纯 JVM 可测、不碰 Android）。
 * - 本文件只做**平台动作**：`Typeface.createFromFile` → `FontFamily(typeface)`。
 *   依赖 Android Framework，故不进纯 JVM 单测覆盖，靠「失败必回落」保证安全。
 *
 * ## 失败回落（ADR-0006）
 *
 * 字体文件不存在、损坏、解码失败时一律返回 [FontFamily.Default]，
 * **绝不抛异常、绝不显示方块字**。无包时 [SkinFont.SystemDefault] 直接走默认，
 * 因此视觉与接字体之前完全一致。
 */
object SkinFontFamilyFactory {

    private const val TAG = "NongyuSkinFont"

    /**
     * @param font 资源段里的字体标识。
     * @param packDir 当前皮肤包目录（仅当 [font] 自带绝对路径缺失时才用）。
     */
    fun create(font: SkinFont, packDir: String? = null): FontFamily {
        val decision = SkinFontResolver.resolve(font, packDir)
        if (decision.choice != FontChoice.FromFile) {
            return FontFamily.Default
        }

        val path = decision.absolutePath ?: return FontFamily.Default
        return try {
            val file = File(path)
            if (!file.isFile) {
                Log.w(TAG, "字体文件不存在，回落系统默认: $path")
                return FontFamily.Default
            }
            // createFromFile 在文件损坏时会抛 RuntimeException（或返回不可用的 Typeface），
            // 用 runCatching 一并兜住；任何失败都是「静默回落 + 记日志」。
            val typeface = runCatching { Typeface.createFromFile(file) }.getOrElse {
                Log.w(TAG, "字体解码失败，回落系统默认: $path", it)
                return FontFamily.Default
            }
            if (typeface == null) {
                Log.w(TAG, "字体解码结果为空，回落系统默认: $path")
                FontFamily.Default
            } else {
                FontFamily(typeface)
            }
        } catch (t: Throwable) {
            // 兜底：不让任何字体相关异常影响启动或渲染。
            Log.w(TAG, "字体加载异常，回落系统默认: $path", t)
            FontFamily.Default
        }
    }
}

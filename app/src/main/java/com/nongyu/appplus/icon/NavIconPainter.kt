package com.nongyu.appplus.icon

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File

/**
 * 图标位图覆盖的**解码与绘制**（ADR-0009）。
 *
 * ## 与既有几何绘制的关系
 * 皮肤包可以给某个图标下发一张位图（`payload.iconImages`）。有就用图，没有就照旧
 * 走 [drawNavIcon] 的几何绘制 —— **逐图标回落**，不是整包切换（ADR-0005 Q5=A）。
 *
 * ## 为什么带缓存
 * `drawIconOverride` 在每一帧都会被调；每帧 `BitmapFactory.decodeFile` 会把主线程打死。
 * 这里按「路径 + mtime + 长度」做一层进程内缓存：皮肤包安装是原子 rename，
 * 文件内容换了 mtime/长度必变，因此这条 key 足以识别「同一张图」。
 *
 * ## 失败一律回落
 * 文件不存在 / 解码失败 / 尺寸为 0 → 返回 `false`，调用方接着画几何图标。
 * **绝不抛异常**（ADR-0006 静默回落原则）。
 *
 * @return `true` = 已用位图画完；`false` = 没有可用位图，调用方应回落几何绘制。
 */
object NavIconPainter {

    private class Entry(val bitmap: androidx.compose.ui.graphics.ImageBitmap, val stamp: String)

    private val cache = LinkedHashMap<String, Entry>()
    private const val MAX_CACHE = 8

    /** 清空缓存（测试与皮肤包切换后可调）。 */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 尝试用位图绘制图标。**必须在 [DrawScope] 里调用。**
     *
     * @param absolutePath 包内位图的**绝对路径**；`null` 直接返回 `false`（无覆盖）。
     * @param tint 染色。位图按单色图标设计，用 `SrcIn` 染成主题色，
     *   这样包内图不必关心配色（与几何图标走同一套 tint 语义）。
     */
    fun DrawScope.drawIconOverride(
        absolutePath: String?,
        tint: Color,
    ): Boolean {
        val image = load(absolutePath) ?: return false
        val canvasSize = size.minDimension
        if (canvasSize <= 0f) return false

        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        if (iw <= 0f || ih <= 0f) return false

        // 等比缩放到画布，居中（不拉伸变形）
        val k = canvasSize / maxOf(iw, ih)
        val w = iw * k
        val h = ih * k
        val dx = (size.width - w) / 2f
        val dy = (size.height - h) / 2f

        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(dx.toInt(), dy.toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
            colorFilter = ColorFilter.tint(tint),
            filterQuality = DefaultFilterQuality,
            // alpha 交给调用方画的图本身，这里不额外压
        )
        return true
    }

    /** 取（并缓存）位图；任何失败返回 `null`。 */
    private fun load(path: String?): androidx.compose.ui.graphics.ImageBitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null

        val stamp = "${file.lastModified()}:${file.length()}"
        synchronized(cache) {
            cache[path]?.let { if (it.stamp == stamp) return it.bitmap }
        }

        val bitmap = try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (t: Throwable) {
            // 坏图/不支持的格式：回落几何绘制，不崩。
            null
        } ?: return null

        synchronized(cache) {
            if (cache.size >= MAX_CACHE) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[path] = Entry(bitmap, stamp)
        }
        return bitmap
    }
}

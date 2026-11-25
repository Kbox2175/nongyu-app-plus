package com.nongyu.appplus.map.logic

import com.nongyu.appplus.map.model.AnnotationKind
import com.nongyu.appplus.map.model.MapAnnotation
import com.nongyu.appplus.map.model.MapPoint
import com.nongyu.appplus.map.model.MapViewport
import kotlin.math.abs

/**
 * 地图纯逻辑（零 Android / Compose 依赖，纯 JVM 可测）。
 *
 * 屏幕坐标 ↔ 图像坐标的换算、标注命中判定、视口约束都在这里，
 * `map/ui` 只负责把结果画出来。
 */
object MapGeometry {

    /**
     * 图像坐标 → 屏幕坐标。
     *
     * 变换顺序：先缩放，再平移。与 [screenToImage] 互为逆运算。
     */
    fun imageToScreen(point: MapPoint, viewport: MapViewport): MapPoint = MapPoint(
        x = point.x * viewport.scale + viewport.offsetX,
        y = point.y * viewport.scale + viewport.offsetY,
    )

    /** 屏幕坐标 → 图像坐标。 */
    fun screenToImage(point: MapPoint, viewport: MapViewport): MapPoint {
        require(viewport.scale > 0f) { "缩放倍率必须为正" }
        return MapPoint(
            x = (point.x - viewport.offsetX) / viewport.scale,
            y = (point.y - viewport.offsetY) / viewport.scale,
        )
    }

    /**
     * 视口平移：把图像夹在屏幕内，避免拖出空白。
     *
     * @param viewport 当前视口
     * @param dx 屏幕位移 x
     * @param dy 屏幕位移 y
     * @param imageWidth 图像原始宽
     * @param imageHeight 图像原始高
     * @param screenWidth 屏幕宽
     * @param screenHeight 屏幕高
     *
     * 规则：图像比屏幕小的方向居中；比屏幕大的方向夹在 `[screen - image*scale, 0]`。
     */
    fun clampPan(
        viewport: MapViewport,
        dx: Float,
        dy: Float,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): MapViewport {
        val newX = viewport.offsetX + dx
        val newY = viewport.offsetY + dy
        val scaledW = imageWidth * viewport.scale
        val scaledH = imageHeight * viewport.scale

        val clampedX = if (scaledW <= screenWidth) {
            (screenWidth - scaledW) / 2f
        } else {
            newX.coerceIn(screenWidth - scaledW, 0f)
        }
        val clampedY = if (scaledH <= screenHeight) {
            (screenHeight - scaledH) / 2f
        } else {
            newY.coerceIn(screenHeight - scaledH, 0f)
        }
        return viewport.copy(offsetX = clampedX, offsetY = clampedY)
    }

    /** 点到线段的距离（用于折线命中判定）。 */
    fun distanceToSegment(p: MapPoint, a: MapPoint, b: MapPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) {
            return kotlin.math.sqrt((p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y))
        }
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        t = t.coerceIn(0f, 1f)
        val closest = MapPoint(a.x + t * dx, a.y + t * dy)
        return kotlin.math.sqrt((p.x - closest.x) * (p.x - closest.x) + (p.y - closest.y) * (p.y - closest.y))
    }

    /**
     * 判断某个图像坐标点是否命中标注。
     *
     * 容差按图像坐标给出（调用方先把屏幕点击换算成图像坐标，并按缩放调整容差）。
     *
     * @param tolerance 命中半径（图像像素）
     */
    fun hitTest(annotation: MapAnnotation, point: MapPoint, tolerance: Float): Boolean =
        when (annotation.kind) {
            AnnotationKind.POINT, AnnotationKind.TEXT -> {
                val a = annotation.anchor
                abs(point.x - a.x) <= tolerance && abs(point.y - a.y) <= tolerance
            }

            AnnotationKind.LINE -> annotation.points.zipWithNext().any { (a, b) ->
                distanceToSegment(point, a, b) <= tolerance
            }
        }

    /** 在标注列表里找命中的最后一个（后画的在上层，优先被选中）。 */
    fun findHit(
        annotations: List<MapAnnotation>,
        point: MapPoint,
        tolerance: Float,
    ): MapAnnotation? = annotations.asReversed().firstOrNull { hitTest(it, point, tolerance) }
}

/**
 * 外部地图软件跳转 URI 构造（纯字符串逻辑，零 Android 依赖）。
 *
 * 使用标准 `geo:` URI，通过 `ACTION_VIEW` 让系统选择已安装的地图 App，
 * 不依赖高德 SDK 或 key。
 */
object MapGeoUri {

    /**
     * 构造 `geo:` URI。
     *
     * @param lat 纬度
     * @param lng 经度
     * @param label 地点名（会被 URL 编码）
     * @return 形如 `geo:29.98,103.0?q=29.98,103.0( label )`
     *
     * 说明：`q=` 后带同名坐标是给不认 `geo:` 主坐标的 App 的兼容写法；
     * label 用 `java.net.URLEncoder` 风格的百分号编码（这里手写，保持零依赖）。
     */
    fun build(lat: Double, lng: Double, label: String? = null): String {
        val base = "geo:$lat,$lng"
        return if (label.isNullOrBlank()) {
            base
        } else {
            "$base?q=$lat,$lng(${encode(label)})"
        }
    }

    /** 最小百分号编码：只编码非 unreserved 字符，空格转 `%20`（不是 `+`）。 */
    private fun encode(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val isUnreserved = (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c in setOf('-'.code, '_'.code, '.'.code, '~'.code)
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append(HEX[c shr 4])
                sb.append(HEX[c and 0x0F])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

package com.nongyu.appplus.map.model

/**
 * 地图标注数据模型（纯数据，零 Android / Compose 依赖，纯 JVM 可测）。
 *
 * ## 坐标约定
 * 所有坐标都是**底图像素坐标**（与缩放、平移无关的原始图像坐标系）。
 * 屏幕坐标 ↔ 图像坐标的换算由 `map/logic` 的视口逻辑负责。
 *
 * ## 标注种类
 * - [Point]：一个点（如教室位置）
 * - [Text]：一段文字
 * - [Line]：多段折线（如从校门到教学楼的路径）
 */

/** 标注类型。 */
enum class AnnotationKind {
    POINT,
    TEXT,
    LINE,
}

/**
 * 一个图像坐标点。
 *
 * @param x 图像像素 x
 * @param y 图像像素 y
 */
data class MapPoint(
    val x: Float,
    val y: Float,
)

/**
 * 一条标注（点 / 文字 / 线共用）。
 *
 * @param id 稳定 id（新建时生成，编辑时不变）
 * @param kind 类型
 * @param points 几何点集：POINT/TEXT 用第一个点，LINE 用全部点（至少 2 个）
 * @param text 文字内容（仅 [AnnotationKind.TEXT] 有意义）
 * @param colorArgb ARGB 颜色整数（用 `android.graphics.Color` 或 Compose `Color.toArgb()` 产生，
 *   本类只存整数，不依赖任何 Android 类）
 * @param campus 所属校区（与后端 `:campus` 对应，DataStore 按它分区）
 */
data class MapAnnotation(
    val id: String,
    val kind: AnnotationKind,
    val points: List<MapPoint>,
    val text: String = "",
    val colorArgb: Int = DEFAULT_COLOR,
    val campus: String,
) {
    init {
        require(id.isNotBlank()) { "标注 id 不能为空" }
        require(campus.isNotBlank()) { "campus 不能为空" }
        require(points.isNotEmpty()) { "标注至少需要一个点" }
        when (kind) {
            AnnotationKind.POINT, AnnotationKind.TEXT ->
                require(points.size == 1) { "${kind} 只能有一个点，实际 ${points.size}" }

            AnnotationKind.LINE ->
                require(points.size >= 2) { "折线至少需要两个点，实际 ${points.size}" }
        }
    }

    /** 标注的锚点（第一个点）。 */
    val anchor: MapPoint get() = points.first()

    companion object {
        /** 默认标注色：不透明的 Material Blue 500。 */
        const val DEFAULT_COLOR: Int = (0xFF shl 24) or 0x2196F3
    }
}

/**
 * 视口状态：图像在屏幕上的平移与缩放。
 *
 * @param offsetX 屏幕平移 x（像素）
 * @param offsetY 屏幕平移 y（像素）
 * @param scale 缩放倍率（>=1）
 */
data class MapViewport(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
) {
    init {
        require(scale >= 1f) { "缩放倍率不能小于 1，实际 $scale" }
    }

    /** 在现基础上平移。 */
    fun pan(dx: Float, dy: Float): MapViewport = copy(
        offsetX = offsetX + dx,
        offsetY = offsetY + dy,
    )

    /** 更新缩放并夹在 [minScale, maxScale] 内。 */
    fun zoom(newScale: Float, minScale: Float = 1f, maxScale: Float = 8f): MapViewport = copy(
        scale = newScale.coerceIn(minScale, maxScale),
    )
}

/**
 * 地图缓存条目（纯数据）。
 *
 * @param campus 校区
 * @param objectKey 后端 ETag / objectKey，用于让换图后 URL 自然失效
 * @param localPath 本地文件路径（相对 filesDir）
 * @param width 图像宽
 * @param height 图像高
 * @param contentType MIME 类型
 */
data class CachedMapImage(
    val campus: String,
    val objectKey: String,
    val localPath: String,
    val width: Int,
    val height: Int,
    val contentType: String,
)

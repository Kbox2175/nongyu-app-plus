package com.nongyu.appplus.map.logic

import com.nongyu.appplus.map.model.AnnotationKind
import com.nongyu.appplus.map.model.MapAnnotation
import com.nongyu.appplus.map.model.MapPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 标注 JSON 契约（由**调用方**传入，用户在地图组件里只读、不可编辑）。
 *
 * ## JSON 形状（数组，每个元素一条标注）
 * ```json
 * [
 *   {"kind":"POINT","points":[{"x":120,"y":340}],"colorArgb":-15626753,"text":""},
 *   {"kind":"LINE","points":[{"x":10,"y":20},{"x":80,"y":90}],"colorArgb":-15626753},
 *   {"kind":"TEXT","points":[{"x":50,"y":60}],"text":"第一教学楼","colorArgb":-15626753}
 * ]
 * ```
 *
 * - `kind`：`POINT` / `LINE` / `TEXT`（大小写不敏感）
 * - `points`：图像像素坐标；`POINT`/`TEXT` 一点，`LINE` 至少两点
 * - `colorArgb`：ARGB 整数（Compose `Color.toArgb()` / Android `Color.argb()` 的返回值）
 * - `text`：仅 `TEXT` 有意义，可缺省
 *
 * ## 容错
 * 解析失败、字段缺失、非法 kind、点数不对的条目一律**跳过**，不抛异常、
 * 不让整张地图因为一条坏数据而白屏。这与网络层「解析失败保守处理」的口径一致。
 */
object MapAnnotationJson {

    @Serializable
    private data class PointJson(
        val x: Float? = null,
        val y: Float? = null,
    )

    @Serializable
    private data class AnnotationJson(
        val kind: String? = null,
        val points: List<PointJson>? = null,
        val text: String? = null,
        val colorArgb: Long? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析调用方传入的 JSON。
     *
     * @param raw JSON 字符串；空/空白返回空列表
     * @param campus 归属校区。JSON 里没有 campus 字段，由调用方参数补齐，
     *   这样同一份标注 JSON 可用于不同校区。
     */
    fun parse(raw: String?, campus: String): List<MapAnnotation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AnnotationJson>>(raw)
                .mapNotNull { item -> toAnnotation(item, campus) }
        }.getOrDefault(emptyList())
    }

    private fun toAnnotation(item: AnnotationJson, campus: String): MapAnnotation? {
        val kind = runCatching { AnnotationKind.valueOf(item.kind?.trim()?.uppercase().orEmpty()) }
            .getOrNull() ?: return null

        val points = item.points.orEmpty().mapNotNull { p ->
            val x = p.x ?: return@mapNotNull null
            val y = p.y ?: return@mapNotNull null
            MapPoint(x, y)
        }

        // 颜色：支持负数（Java int 的 ARGB 常为负），用 Long 承接后转 Int。
        val color = (item.colorArgb ?: MapAnnotation.DEFAULT_COLOR.toLong()).toInt()

        return runCatching {
            MapAnnotation(
                id = "${campus}_${kind.name}_${points.hashCode()}",
                kind = kind,
                points = points,
                text = item.text.orEmpty(),
                colorArgb = color,
                campus = campus,
            )
        }.getOrNull() // 点数不合法时 MapAnnotation 的 init 会抛，这里跳过该条
    }
}

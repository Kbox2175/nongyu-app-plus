package com.nongyu.appplus.map.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import com.nongyu.appplus.map.logic.MapGeoUri
import com.nongyu.appplus.map.model.AnnotationKind
import com.nongyu.appplus.map.model.MapAnnotation
import com.nongyu.appplus.map.model.MapPoint
import com.nongyu.appplus.map.model.MapViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 地图组件：显示底图 + 拖动 + 标注编辑 + 跳转外部地图。
 *
 * ## 状态提升到调用方
 * [viewport] 与 [annotations] 都是外部传入，组件内部只发回调（无私有状态），
 * 便于纯逻辑层单测视口换算、也便于调用方持久化标注。
 *
 * ## 交互
 * - 单指拖动 → [onPan]（调用方用 MapGeometry.clampPan 夹住边界）
 * - 单击空白处 → [onTapEmpty]（调用方按当前工具加标注）
 * - 单击已有标注 → [onAnnotationClick]（调用方选中编辑/删除）
 */
@Composable
fun MapCanvasView(
    image: ImageBitmap?,
    viewport: MapViewport,
    annotations: List<MapAnnotation>,
    selectedId: String?,
    modifier: Modifier = Modifier,
    onPan: (dx: Float, dy: Float) -> Unit,
    onTap: (imagePoint: MapPoint) -> Unit,
) {
    val measurer = rememberTextMeasurer()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onPan(dragAmount.x, dragAmount.y)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // 屏幕坐标 → 图像坐标（与 MapGeometry.screenToImage 同式，
                        // 这里内联以避免在 ui 层依赖逻辑层对象实例；公式唯一真源仍在 logic）。
                        val p = MapPoint(
                            x = (offset.x - viewport.offsetX) / viewport.scale,
                            y = (offset.y - viewport.offsetY) / viewport.scale,
                        )
                        onTap(p)
                    }
                },
        ) {
            val img = image
            if (img != null) {
                // 用 scale + translate 保证图片与标注共用「先缩放后平移」的同一变换。
                // 与 MapGeometry.imageToScreen 完全一致。
                withTransform({
                    scale(scaleX = viewport.scale, scaleY = viewport.scale)
                    translate(
                        left = viewport.offsetX / viewport.scale,
                        top = viewport.offsetY / viewport.scale,
                    )
                }) {
                    drawImage(image = img, topLeft = Offset.Zero)
                }
            }
            annotations.forEach { annotation ->
                drawAnnotation(
                    annotation = annotation,
                    viewport = viewport,
                    selected = annotation.id == selectedId,
                    measurer = measurer,
                )
            }
        }
    }
}

/** 画一条标注（坐标从图像系换算到屏幕系）。 */
private fun DrawScope.drawAnnotation(
    annotation: MapAnnotation,
    viewport: MapViewport,
    selected: Boolean,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val color = Color(annotation.colorArgb)
    val screenPoints = annotation.points.map { p ->
        Offset(
            x = p.x * viewport.scale + viewport.offsetX,
            y = p.y * viewport.scale + viewport.offsetY,
        )
    }
    when (annotation.kind) {
        AnnotationKind.POINT -> {
            drawCircle(
                color = color,
                radius = if (selected) 14f else 10f,
                center = screenPoints.first(),
            )
            if (selected) drawCircle(color = Color.White, radius = 4f, center = screenPoints.first())
        }

        AnnotationKind.LINE -> {
            for (i in 0 until screenPoints.size - 1) {
                drawLine(
                    color = color,
                    start = screenPoints[i],
                    end = screenPoints[i + 1],
                    strokeWidth = if (selected) 6f else 4f,
                )
            }
        }

        AnnotationKind.TEXT -> {
            val measured = measurer.measure(
                text = annotation.text,
                style = TextStyle(color = color, fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
            )
            drawText(
                textMeasurer = measurer,
                text = annotation.text,
                topLeft = screenPoints.first(),
                style = TextStyle(
                    color = color,
                    fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
                    background = if (selected) Color(0x33FFFFFF) else Color.Unspecified,
                ),
            )
        }
    }
}

/** 把本地图片文件解码为 ImageBitmap（IO 线程，调用方用 produceState/LaunchedEffect 包）。 */
suspend fun decodeMapImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 跳转外部地图软件（geo: URI，系统让用户选 App）。
 *
 * @param context Activity 或 Application context 均可；用 Application 时需加 FLAG_ACTIVITY_NEW_TASK
 */
fun openInExternalMap(
    context: Context,
    latitude: Double,
    longitude: Double,
    label: String?,
): Boolean {
    val uri = Uri.parse(MapGeoUri.build(latitude, longitude, label))
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

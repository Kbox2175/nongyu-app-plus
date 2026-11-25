package com.nongyu.appplus.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nongyu.appplus.map.data.MapLoadResult
import com.nongyu.appplus.map.data.MapRepository
import com.nongyu.appplus.map.logic.MapGeometry
import com.nongyu.appplus.map.model.AnnotationKind
import com.nongyu.appplus.map.model.MapAnnotation
import com.nongyu.appplus.map.model.MapPoint
import com.nongyu.appplus.map.model.MapViewport
import java.util.UUID

/**
 * 带工具栏的地图编辑器（共用组件入口）。
 *
 * ## 功能
 * 1. 自动加载：本地缓存优先，缺失走后端（[MapRepository]）
 * 2. 显示 + 拖动 + 点/线/文字标注（带颜色）
 * 3. 跳转外部地图软件（geo: URI）
 *
 * @param campus 校区标识（对应后端 `:campus`）
 * @param initialAnnotations 初始标注（调用方从 [com.nongyu.appplus.map.data.MapAnnotationStore] 读）
 * @param onAnnotationsChange 标注变化时回调（调用方持久化）
 * @param labelForExternalMap 跳转外部地图时的地点名
 * @param externalLat / externalLng 跳转坐标；为空时隐藏跳转按钮
 */
@Composable
fun MapEditor(
    campus: String,
    modifier: Modifier = Modifier,
    initialAnnotations: List<MapAnnotation> = emptyList(),
    onAnnotationsChange: (List<MapAnnotation>) -> Unit = {},
    labelForExternalMap: String? = null,
    externalLat: Double? = null,
    externalLng: Double? = null,
) {
    val context = LocalContext.current
    val repository = remember(campus) { MapRepository(context) }

    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var meta by remember { mutableStateOf<com.nongyu.appplus.net.model.ClassroomMapMetaDto?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf(MapViewport()) }
    var annotations by remember(initialAnnotations) { mutableStateOf(initialAnnotations) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tool by remember { mutableStateOf<Tool>(Tool.Point) }
    var color by remember { mutableStateOf(MapAnnotation.DEFAULT_COLOR) }
    var lineDraft by remember { mutableStateOf<List<MapPoint>>(emptyList()) }
    var textInput by remember { mutableStateOf("") }

    // 加载地图（缓存优先）
    val loadResult by produceState<MapLoadResult?>(initialValue = null, campus) {
        value = repository.load(campus)
    }
    LaunchedEffect(loadResult) {
        when (val r = loadResult) {
            is MapLoadResult.Success -> {
                image = decodeMapImage(r.imageFile.absolutePath)
                meta = r.meta
                hint = null
            }

            is MapLoadResult.NoImage -> hint = r.hint.ifBlank { "该校区暂无地图" }
            is MapLoadResult.Error -> hint = "地图加载失败：${r.cause.message ?: "未知错误"}"
            null -> Unit
        }
    }

    fun commit(list: List<MapAnnotation>) {
        annotations = list
        onAnnotationsChange(list)
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        MapCanvasView(
            image = image,
            viewport = viewport,
            annotations = annotations + lineDraftAnnotation(campus, lineDraft, color),
            selectedId = selectedId,
            modifier = Modifier.fillMaxSize(),
            onPan = { dx, dy ->
                val w = image?.width ?: 1
                val h = image?.height ?: 1
                viewport = MapGeometry.clampPan(
                    viewport, dx, dy, w, h,
                    screenWidth = 1080, screenHeight = 1920, // 由调用方按实际屏幕覆盖更好
                )
            },
            onTap = { p ->
                when (tool) {
                    is Tool.Point -> {
                        val a = MapAnnotation(
                            id = UUID.randomUUID().toString(),
                            kind = AnnotationKind.POINT,
                            points = listOf(p),
                            colorArgb = color,
                            campus = campus,
                        )
                        commit(annotations + a)
                        selectedId = a.id
                    }

                    is Tool.Text -> {
                        if (textInput.isNotBlank()) {
                            val a = MapAnnotation(
                                id = UUID.randomUUID().toString(),
                                kind = AnnotationKind.TEXT,
                                points = listOf(p),
                                text = textInput,
                                colorArgb = color,
                                campus = campus,
                            )
                            commit(annotations + a)
                            selectedId = a.id
                            textInput = ""
                        }
                    }

                    is Tool.Line -> {
                        lineDraft = lineDraft + p
                    }

                    is Tool.Select -> {
                        selectedId = MapGeometry.findHit(
                            annotations, p, tolerance = 20f / viewport.scale,
                        )?.id
                    }
                }
            },
        )

        // 顶部工具栏
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.White.copy(alpha = 0.9f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton("点", tool is Tool.Point) { tool = Tool.Point; lineDraft = emptyList() }
                ToolButton("线", tool is Tool.Line) { tool = Tool.Line; lineDraft = emptyList() }
                ToolButton("字", tool is Tool.Text) { tool = Tool.Text; lineDraft = emptyList() }
                ToolButton("选", tool is Tool.Select) { tool = Tool.Select; lineDraft = emptyList() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "蓝" to (0xFF shl 24 or 0x2196F3),
                    "红" to (0xFF shl 24 or 0xF44336),
                    "绿" to (0xFF shl 24 or 0x4CAF50),
                    "黑" to (0xFF shl 24 or 0x000000),
                ).forEach { (name, argb) ->
                    ColorButton(name, argb, color == argb) { color = argb }
                }
            }
            if (tool is Tool.Text) {
                androidx.compose.material3.TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    singleLine = true,
                    placeholder = { Text("输入文字") },
                )
            }
            if (tool is Tool.Line && lineDraft.size >= 2) {
                Text("完成折线", color = Color(0xFF2563EB),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val a = MapAnnotation(
                            id = UUID.randomUUID().toString(),
                            kind = AnnotationKind.LINE,
                            points = lineDraft,
                            colorArgb = color,
                            campus = campus,
                        )
                        commit(annotations + a)
                        lineDraft = emptyList()
                        selectedId = a.id
                    })
            }
            if (selectedId != null) {
                Text("删除选中", color = Color(0xFFDC2626),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        commit(annotations.filterNot { it.id == selectedId })
                        selectedId = null
                    })
            }
            if (externalLat != null && externalLng != null) {
                Text("外部地图", color = Color(0xFF2563EB),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        openInExternalMap(context, externalLat, externalLng, labelForExternalMap)
                    })
            }
        }

        hint?.let {
            Text(
                it,
                color = Color(0xFF666666),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 线草稿临时标注（不持久化）。 */
private fun lineDraftAnnotation(
    campus: String,
    points: List<MapPoint>,
    color: Int,
): List<MapAnnotation> = if (points.size < 2) {
    emptyList()
} else {
    listOf(
        MapAnnotation(
            id = "__draft__", kind = AnnotationKind.LINE,
            points = points, colorArgb = color, campus = campus,
        ),
    )
}

@Composable
private fun ToolButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.White else Color(0xFF374151),
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) Color(0xFF2563EB) else Color(0xFFE5E7EB))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ColorButton(label: String, argb: Int, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .then(if (active) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

private sealed class Tool {
    object Point : Tool()
    object Line : Tool()
    object Text : Tool()
    object Select : Tool()
}

package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable

/**
 * 教室地图底图（后端 `internal/classroom/maphandler.go`，2026-11-25 新增）。
 *
 * ## 两个端点
 * - `GET /classrooms/maps/:campus/meta` → [ClassroomMapMetaDto]（`{"data":{...}}`）
 * - `GET /classrooms/maps/:campus` → **原始图片字节**（不是 JSON）
 *
 * ## 像素坐标 ≠ 经纬度
 * 底图是静态图片（高德 GCJ-02 坐标系）。后端给出 `calibration`（放射变换参数）
 * 与 `corners`（四角像素 ↔ 经纬度的对应点），客户端**自己**做像素↔经纬度换算。
 * 地图要经管理端上传，`uploaded=false` 时不能取图。
 *
 * 两条都需 Bearer。
 */

/**
 * 地图元数据（后端 `mapView`，信封 `{"data":{...}}`）。
 *
 * `hint` 后端用一句话说明当前校区能做什么（未上传 / 未标定 / 可用），
 * 直接展示给用户即可，不必自己拼条件。
 */
@Serializable
data class ClassroomMapMetaDto(
    /** 校区标识（如 `yaan`）。 */
    val campus: String? = null,
    val campusLabel: String? = null,
    /** 底图像素宽（`uploaded=false` 时可能为 0）。 */
    val width: Int? = null,
    val height: Int? = null,
    /** 底图坐标系标识，固定 `GCJ-02`（高德级）。 */
    val coordSystem: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    /** 内容指纹，用于构造带版本的缓存 URL（`?v=<objectKey>`）。 */
    val objectKey: String? = null,
    /** 底图是否已上传。false 时不应请求图片端点。 */
    val uploaded: Boolean? = null,
    /** 是否已完成标定。false 时**不能**做像素↔经纬度换算。 */
    val calibrated: Boolean? = null,
    /** 标定参数；未标定时为 null。 */
    val calibration: ClassroomMapCalibrationDto? = null,
    /** 四角对应点：key 是角名（如 `topLeft`），value 含像素与经纬度。 */
    val corners: Map<String, ClassroomCornerDto>? = null,
    val calibrationNote: String? = null,
    /** 后端给的当前状态说明，可直接展示。 */
    val hint: String? = null,
) {
    /** 底图是否可用（已上传）。 */
    fun canShowMap(): Boolean = uploaded == true
}

/**
 * 标定参数（后端 `calibrationView`）：放射变换系数。
 *
 * 像素 → 经纬度大致是 `lng = ar*x + ai*y + tx0`、`lat = kx*x + ky*y + ty0`
 * 这类线性映射（`lng0`/`lat0` 是参考原点）。**公式细节以后端 spec 为准**，
 * 客户端只透传这些系数，不在 DTO 层做运算。
 */
@Serializable
data class ClassroomMapCalibrationDto(
    val ar: Double? = null,
    val ai: Double? = null,
    val tx0: Double? = null,
    val ty0: Double? = null,
    val lng0: Double? = null,
    val lat0: Double? = null,
    val kx: Double? = null,
    val ky: Double? = null,
)

/** 一个角点：底图像素坐标 ↔ 经纬度。 */
@Serializable
data class ClassroomCornerDto(
    /** 像素 x。 */
    val px: Double? = null,
    /** 像素 y。 */
    val py: Double? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

/** `GET /classrooms/maps/:campus/meta` 的信封：`{"data":{...}}`。 */
@Serializable
data class ClassroomMapMetaEnvelopeDto(
    val data: ClassroomMapMetaDto? = null,
)

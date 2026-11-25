package com.nongyu.appplus.net.model

import kotlinx.serialization.Serializable

/**
 * 应用发布与更新（后端 `internal/release/`，2026-11-25 新增）。
 *
 * ## App 侧三条只读端点（需 Bearer，后端 `RegisterSession`）
 * - `GET /releases/android/latest?applicationId=...` → [ReleaseLatestDto]
 * - `GET /releases/android/latest/pack?applicationId=...` → 原始 APK 字节
 * - `GET /releases/android/:versionCode/pack?applicationId=...` → 原始 APK 字节
 *
 * ## `applicationId` 必传
 * 后端按 Android 包名区分应用。不传会得到空结果/404。
 * 客户端用 `BuildConfig.APPLICATION_ID` 填，不要写死字符串。
 *
 * ## `minSupportedVersionCode` 是硬门槛
 * 低于它的版本必须**阻断使用**（强制更新），不是可选升级。
 *
 * ## `downloadUrl` 指向本服务
 * 后端只回相对路径（如 `/releases/android/latest/pack`），
 * 不含 host、也不是对象存储的预签名 URL。客户端可自行拼 host 或直接调 pack 端点。
 */

/**
 * `GET /releases/android/latest` 的响应（后端 `latestView`）。
 *
 * ⚠️ `hasRelease=false` 表示「该应用还没有任何已发布版本」，是**正常状态不是错误**，
 * 此时其它字段全部缺省（后端 `omitempty`）。
 */
@Serializable
data class ReleaseLatestDto(
    /** false = 还没有任何已发布版本，属正常状态。 */
    val hasRelease: Boolean? = null,
    val applicationId: String? = null,
    val versionCode: Long? = null,
    /** 人类可读版本号（如 `0.0.59test`），只作展示，判新旧用 versionCode。 */
    val versionName: String? = null,
    /** 安装包要求的最低 Android API，`0` 表示未设。 */
    val minSdk: Int? = null,
    /**
     * 支持版本下限。**本地 versionCode 低于它必须阻断使用**（强制更新）。
     */
    val minSupportedVersionCode: Long? = null,
    /** 更新说明，可空。 */
    val changelog: String? = null,
    /** APK 字节数。 */
    val size: Long? = null,
    /** 整包 SHA-256（小写 hex），下载后可校验完整性。 */
    val sha256: String? = null,
    /** 指向本服务的下载路径（相对路径，不是预签名 URL）。 */
    val downloadUrl: String? = null,
    /** RFC3339 字符串。 */
    val updatedAt: String? = null,
) {
    /** 是否有可用版本。 */
    fun hasUpdate(): Boolean = hasRelease == true && versionCode != null

    /**
     * 是否需要**强制**更新（本地版本低于服务端支持下限）。
     *
     * @param localVersionCode 本地 `BuildConfig.VERSION_CODE`
     */
    fun isMandatoryFor(localVersionCode: Int): Boolean {
        val floor = minSupportedVersionCode ?: return false
        return localVersionCode.toLong() < floor
    }

    /**
     * 是否有新版本（用于提示更新，与强制更新区分）。
     */
    fun isNewerThan(localVersionCode: Int): Boolean {
        val remote = versionCode ?: return false
        return remote > localVersionCode.toLong()
    }
}

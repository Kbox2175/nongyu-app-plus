package com.nongyu.appplus.map.data

import android.content.Context
import com.nongyu.appplus.map.model.CachedMapImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 地图底图的本地文件缓存（filesDir，换机不迁移）。
 *
 * ## 失效规则
 * 后端给 `objectKey`（同时是 ETag）。本地文件名带 objectKey：
 * `map_<campus>_<objectKey>.img`。换图后 objectKey 变了 → 本地文件自然找不到 → 重新下载。
 * 不需要主动清理旧文件（数量很少），但提供 [clearAll] 供设置页手动清。
 *
 * ## IO 线程
 * 所有读写都 `withContext(Dispatchers.IO)`，调用方无需关心线程。
 */
class MapImageCache(private val context: Context) {

    private val dir: File
        get() = File(context.applicationContext.filesDir, "campus_maps").apply { mkdirs() }

    /** 取本地缓存；不存在返回 null。 */
    suspend fun get(campus: String, objectKey: String): File? = withContext(Dispatchers.IO) {
        val f = fileFor(campus, objectKey)
        f.takeIf { it.exists() && it.length() > 0 }
    }

    /** 写入本地缓存，返回文件。 */
    suspend fun put(
        campus: String,
        objectKey: String,
        bytes: ByteArray,
    ): File = withContext(Dispatchers.IO) {
        val f = fileFor(campus, objectKey)
        f.writeBytes(bytes)
        f
    }

    /** 清掉某校区所有缓存文件。 */
    suspend fun clearCampus(campus: String) = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("map_${safe(campus)}_") }
            ?.forEach { it.delete() }
            ?: Unit
    }

    /** 清掉全部缓存。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() } ?: Unit
    }

    private fun fileFor(campus: String, objectKey: String): File =
        File(dir, "map_${safe(campus)}_${safe(objectKey)}.img")

    /** 文件名安全化：只保留字母数字与下划线，避免路径穿越。 */
    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    companion object {
        /** 由缓存文件构造纯数据条目（供 UI 层读取尺寸等元信息）。 */
        fun entryOf(
            campus: String,
            objectKey: String,
            file: File,
            width: Int,
            height: Int,
            contentType: String,
        ) = CachedMapImage(campus, objectKey, file.absolutePath, width, height, contentType)
    }
}

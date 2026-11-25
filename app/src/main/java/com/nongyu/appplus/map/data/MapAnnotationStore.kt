package com.nongyu.appplus.map.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nongyu.appplus.map.model.AnnotationKind
import com.nongyu.appplus.map.model.MapAnnotation
import com.nongyu.appplus.map.model.MapPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 标注持久层：DataStore 存 JSON，按 campus 分区。
 *
 * 容错沿用 `CourseUserStore` 已真机验证过的三件套：
 * `ReplaceFileCorruptionHandler` + `catch { IOException -> emptyPreferences }` + `edit{}`。
 *
 * 明文存储即可（标注不是敏感信息），不套 Keystore。
 */
private val Context.mapAnnotationStore: DataStore<Preferences> by preferencesDataStore(
    name = "map_annotations",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** JSON 序列化用的 DTO（扁平结构，不含逻辑）。 */
@Serializable
private data class StoredAnnotation(
    val id: String,
    val kind: String,
    val x: List<Float>,
    val y: List<Float>,
    val text: String,
    val colorArgb: Int,
)

@Serializable
private data class StoredList(val items: List<StoredAnnotation>)

/** `ignoreUnknownKeys` 与仓内 NetJson 一致，便于将来加字段不炸旧数据。 */
private val mapJson = Json { ignoreUnknownKeys = true }

class MapAnnotationStore(private val context: Context) {

    /** 读某个校区的全部标注。 */
    fun annotations(campus: String): Flow<List<MapAnnotation>> =
        context.mapAnnotationStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs -> decode(prefs[stringKey(campus)]) }

    /** 一次性读取（供编辑页进入时取快照）。 */
    suspend fun load(campus: String): List<MapAnnotation> =
        annotations(campus).first()

    /** 覆盖某个校区的全部标注。 */
    suspend fun save(campus: String, annotations: List<MapAnnotation>) {
        val stored = StoredList(
            items = annotations.filter { it.campus == campus }.map { a ->
                StoredAnnotation(
                    id = a.id,
                    kind = a.kind.name,
                    x = a.points.map { it.x },
                    y = a.points.map { it.y },
                    text = a.text,
                    colorArgb = a.colorArgb,
                )
            },
        )
        context.mapAnnotationStore.edit { prefs ->
            prefs[stringKey(campus)] = mapJson.encodeToString(stored)
        }
    }

    /** 清空某校区标注。 */
    suspend fun clear(campus: String) {
        context.mapAnnotationStore.edit { prefs -> prefs.remove(stringKey(campus)) }
    }

    private fun decode(raw: String?): List<MapAnnotation> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val stored = mapJson.decodeFromString<StoredList>(raw)
            stored.items.mapNotNull { item ->
                val kind = runCatching { AnnotationKind.valueOf(item.kind) }.getOrNull()
                    ?: return@mapNotNull null
                val points = item.x.indices.map { i ->
                    MapPoint(item.x[i], item.y.getOrNull(i) ?: 0f)
                }
                runCatching {
                    MapAnnotation(
                        id = item.id,
                        kind = kind,
                        points = points,
                        text = item.text,
                        colorArgb = item.colorArgb,
                        campus = "", // campus 由读取时的 key 决定，这里填占位
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun stringKey(campus: String) = stringPreferencesKey("anno_$campus")
}

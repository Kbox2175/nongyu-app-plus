package com.nongyu.appplus.skin

import org.json.JSONObject

/**
 * 目录接口 JSON → [SkinCatalog] 的**薄适配**（票 #53）。
 *
 * 与 `SkinManifestParser.parseJson` 同一套分层：`org.json` 在纯 JVM 单测里是 stub，
 * 所以判定逻辑全在 [SkinCatalog.parseMap]（吃 Map，可全测），这里只做取值转换，
 * 几乎无分支、**永不抛异常**（解析失败返回 hasPack=false，等价于「没有包」）。
 */
object SkinCatalogJson {

    /** 解析目录响应；任何异常都退化成「无包」，不阻断调用方。 */
    fun parse(json: String): SkinCatalog = try {
        val obj = JSONObject(json)
        val map = HashMap<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when (key) {
                "manifest" -> obj.optJSONObject(key)?.let { toMap(it) }
                else -> obj.opt(key)
            }
        }
        SkinCatalog.parseMap(map)
    } catch (t: Throwable) {
        // 目录解析失败 = 这次没有可用的包；由调用方记日志后结束（失败静默）。
        SkinCatalog.parseMap(mapOf("hasPack" to false))
    }

    private fun toMap(obj: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        for (key in obj.keys()) {
            val v = obj.opt(key)
            out[key] = if (v is JSONObject) toMap(v) else v
        }
        return out
    }

    /** 取出 manifest 原文，供 `SkinManifestParser.parseJson` 直接消费。 */
    fun manifestJsonOf(catalog: SkinCatalog): String? =
        catalog.manifestRaw?.let { JSONObject(it as Map<*, *>).toString() }
}

package com.nongyu.appplus.skin

/**
 * 皮肤包「目录接口」的响应模型与**纯逻辑解析**（票 #53）。
 *
 * 契约来源：`docs/research/皮肤包下载器-服务端对接须知.md` §2。
 * 与 `SkinManifestParser` / `ColorPayloadParser` 同一套分层：
 * - [parseMap] 只吃 `Map`，纯 JVM 可全量单测；
 * - `org.json` 到 Map 的转换放在薄适配 `SkinCatalogJson`（依赖 Android Framework 的部分）。
 *
 * ## 为什么单独建模而不直接复用 SkinPackManifest
 * 目录接口除了 `manifest` 原文，还带 `hasPack` / `version` / `packSha256` /
 * `downloadUrl` 这些**传输层**字段；它们不属于包内清单，混进 `SkinPackManifest`
 * 会破坏「清单 = 包内 manifest.json 原文」这个既有契约。
 */
data class SkinCatalog(
    val hasPack: Boolean,
    val packId: String?,
    val version: String?,
    val packSha256: String?,
    val packSize: Long?,
    val displayName: String?,
    val downloadUrl: String?,
    val manifestRaw: Map<String, Any?>?,
) {
    companion object {

        /** 契约 §2：downloadUrl 缺失时按 `{base}/skins/{packId}/{version}/pack` 构造的**相对路径**。 */
        fun buildPackPath(packId: String, version: String): String =
            "/skins/$packId/$version/pack"

        /** 纯逻辑解析：从已取出的键值对构造目录响应，永不抛异常。 */
        fun parseMap(raw: Map<String, Any?>): SkinCatalog {
            val hasPack = raw["hasPack"] as? Boolean ?: false
            val packId = (raw["packId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val version = (raw["version"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val sha = (raw["packSha256"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val size = (raw["packSize"] as? Number)?.toLong()
            val name = (raw["displayName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val url = (raw["downloadUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (hasPack && packId != null && version != null) {
                    buildPackPath(packId, version)
                } else {
                    null
                }
            @Suppress("UNCHECKED_CAST")
            val manifest = raw["manifest"] as? Map<String, Any?>
            return SkinCatalog(
                hasPack = hasPack,
                packId = packId,
                version = version,
                packSha256 = sha,
                packSize = size,
                displayName = name,
                downloadUrl = url,
                manifestRaw = manifest,
            )
        }
    }
}

/** 下载决策：要么继续（带整包 sha256 期望值），要么跳过并给出原因。 */
sealed interface SkinDownloadDecision {
    data class Proceed(val expectedPackSha256: String?) : SkinDownloadDecision
    data class Skip(val reason: String) : SkinDownloadDecision

    companion object {
        /** 纯判定：目录响应是否值得发第二次请求。 */
        fun of(catalog: SkinCatalog): SkinDownloadDecision {
            if (!catalog.hasPack) return Skip("服务端未发布皮肤包（hasPack=false）")
            val id = catalog.packId
            val ver = catalog.version
            if (id == null || ver == null) return Skip("目录缺 packId / version")
            if (!SkinStoreLayout.isSafeSegment(id) || !SkinStoreLayout.isSafeSegment(ver)) {
                return Skip("packId / version 含非法字符")
            }
            if (catalog.downloadUrl == null) return Skip("目录缺 downloadUrl 且无法构造")
            return Proceed(catalog.packSha256)
        }
    }
}

/** 整包 sha256 头比对（契约 §3：`X-Nongyu-Pack-Sha256`）。大小写不敏感、去首尾空白。 */
object SkinPackSha256Checker {
    fun matches(header: String?, expected: String?): Boolean {
        if (header == null || expected == null) return false
        val h = header.trim()
        val e = expected.trim()
        if (h.isEmpty() || e.isEmpty()) return false
        return h.equals(e, ignoreCase = true)
    }
}

/** 「校验结论 OK 且整包 sha 通过」才允许 install —— 票面交付 1 的最后一道闸。 */
object SkinInstallGate {
    fun shouldInstall(verificationOk: Boolean, packShaMatches: Boolean): Boolean =
        verificationOk && packShaMatches
}

/** zip 条目路径安全判定（客户端二次防御，服务端已挡过一次）。 */
object SkinZipPathSafety {
    fun isSafe(entryName: String): Boolean {
        if (entryName.isEmpty()) return false
        // 统一分隔符，挡 Windows 反斜杠形式的穿越
        val n = entryName.replace('\\', '/')
        if (n.startsWith("/")) return false
        if (n.contains("..")) return false
        if (n.startsWith("./")) return false
        // 逐段再查一次，挡 a/../b 这类被 contains("..") 已覆盖但语义更明确的情形
        return n.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
}

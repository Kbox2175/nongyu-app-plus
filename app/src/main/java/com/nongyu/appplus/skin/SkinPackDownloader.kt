package com.nongyu.appplus.skin

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 皮肤包下载器：**首帧之后**拉目录 → 下载 zip → 校验 → 落盘（票 #53）。
 *
 * ## 硬约束（ADR-0006 / ADR-0003）
 * 1. **首帧之前绝不碰网络**：本类只能由 [schedule] 在首帧回调里触发（见 `SkinUpdateScheduler`），
 *    启动路径上的 `FileSkinSource.resolve()` 完全不经过这里。
 * 2. **失败静默**：拉目录 / 下载 / 解压 / 校验任一失败都只记日志，**不调 install**，
 *    当前生效的包不受影响。
 * 3. **完整性在应用侧判**：先比对 `X-Nongyu-Pack-Sha256`（省一次解包），
 *    再走 `SkinPackIntegrity.verify`（逐文件 sha256 + 白名单 + 签名），
 *    只有 `verification.ok` 才进 `SkinStore.install`。
 *
 * ## 分层
 * - 决策（要不要下 / 要不要装 / 路径安全）全在 [SkinDownloadDecision] /
 *   [SkinPackSha256Checker] / [SkinInstallGate] / [SkinZipPathSafety] —— 纯 JVM 可测；
 * - 本类是 IO 编排壳：网络在 [SkinCatalogFetcher]，磁盘在 [SkinStore]。
 */
class SkinPackDownloader(
    private val store: SkinStore,
    private val fetcher: SkinCatalogFetcher,
    private val appVersionCode: Int,
    /** 内置验签器。**ADR-0005 尚未定算法与公钥**，生产沿用 NO_OP（放行并记 WARN）。 */
    private val verifier: PackSignatureVerifier = NO_OP_VERIFIER,
    private val tempRoot: File,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    /** 一次下载的结果，供日志与测试断言。 */
    sealed interface Outcome {
        data class Installed(val packId: String, val version: String) : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** 完整链路。**永不抛异常**（失败一律转成 [Outcome.Failed] / [Outcome.Skipped]）。 */
    fun run(): Outcome = try {
        downloadAndInstall()
    } catch (t: Throwable) {
        // 兜底：任何未预期异常都不能影响 App 可用性。
        log("皮肤下载器异常，已忽略：${t.message}")
        Outcome.Failed("未预期异常：${t.message}")
    }

    private fun downloadAndInstall(): Outcome {
        // ① 拉目录
        val reply = fetcher.fetchCatalog().getOrElse {
            log("拉取皮肤目录失败：${it.message}")
            return Outcome.Failed("拉目录失败：${it.message}")
        }
        val catalog = SkinCatalogJson.parse(reply.body)

        // ② 决策：要不要继续（hasPack / 字段安全 / 有下载地址）
        val decision = SkinDownloadDecision.of(catalog)
        if (decision is SkinDownloadDecision.Skip) {
            log("皮肤目录：${decision.reason}")
            return Outcome.Skipped(decision.reason)
        }
        decision as SkinDownloadDecision.Proceed
        val packId = catalog.packId!!
        val version = catalog.version!!
        val downloadUrl = catalog.downloadUrl!!
        log("发现皮肤包 $packId/$version，开始下载：$downloadUrl")

        // ③ 下包体
        val pack = fetcher.fetchPack(downloadUrl).getOrElse {
            log("下载皮肤包失败：${it.message}")
            return Outcome.Failed("下载失败：${it.message}")
        }

        // ④ 整包 sha256 先比对（省一次解包）
        val shaOk = SkinPackSha256Checker.matches(pack.packSha256Header, catalog.packSha256)
        if (!shaOk) {
            log("整包 sha256 不匹配，丢弃：头=${pack.packSha256Header} 期望=${catalog.packSha256}")
        }

        // ⑤ 解压到临时目录
        val work = File(tempRoot, "skin-download-$packId-$version")
        work.deleteRecursively()
        if (!work.mkdirs() && !work.isDirectory) {
            log("无法创建临时目录：$work")
            return Outcome.Failed("临时目录不可用")
        }
        try {
            val unzipOk = unzip(pack.bytes, work)
            if (!unzipOk) {
                log("解压失败（含非法条目），丢弃本次下载")
                return Outcome.Failed("解压失败")
            }

            // ⑥ 清单：优先用目录返回的 manifest 原文，缺失则读包内 manifest.json
            val manifestJson = SkinCatalogJson.manifestJsonOf(catalog)
                ?: File(work, MANIFEST_FILE).takeIf { it.isFile }?.readText()
            val manifest = manifestJson?.let { SkinManifestParser.parseJson(it) }
            if (manifest == null) {
                log("皮肤包清单不可用（目录与包内都没有合法 manifest）")
                return Outcome.Failed("清单不可用")
            }

            // ⑦ 完整性校验（逐文件 sha256 + 白名单 + 签名）
            val actualBytes = mutableMapOf<String, ByteArray>()
            for (entry in manifest.entries) {
                FilePackByteSource(work).read(entry.path)?.let { actualBytes[entry.path] = it }
            }
            val verification = SkinPackIntegrity.verify(
                SkinPackVerifyInput(
                    manifest = manifest,
                    actualBytes = actualBytes,
                    appVersionCode = appVersionCode,
                ),
                verifier,
            )
            if (!verification.ok) {
                log("皮肤包校验未通过（${verification.origin}）：${verification.reason}")
                return Outcome.Skipped("校验未通过：${verification.origin}")
            }
            for (w in verification.warnings) log("皮肤包警告：$w")

            // ⑧ 最后一道闸：校验 OK 且整包 sha 匹配才 install
            if (!SkinInstallGate.shouldInstall(verification.ok, shaOk)) {
                log("整包 sha256 未通过，不安装")
                return Outcome.Skipped("整包 sha256 未通过")
            }

            return when (val installed = store.install(work, packId, version)) {
                is InstallOutcome.Installed -> {
                    log("皮肤包已安装：$packId/$version（下次启动生效）")
                    Outcome.Installed(packId, version)
                }
                is InstallOutcome.Unchanged -> {
                    log("皮肤包未变更：${installed.reason}")
                    Outcome.Skipped("未变更：${installed.reason}")
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /** 解压 zip 到 [targetDir]，逐条目做路径安全检查。任一条目非法则整体失败。 */
    private fun unzip(bytes: ByteArray, targetDir: File): Boolean {
        val root = targetDir.canonicalFile
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (!SkinZipPathSafety.isSafe(name)) {
                    log("zip 条目路径非法，拒绝解压：$name")
                    return false
                }
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) {
                    log("zip 条目越界，拒绝解压：$name")
                    return false
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
            }
        }
        // 诊断：把解压出来的文件树打进日志，便于定位「装好了但内容缺失」。
        val tree = root.walkTopDown().filter { it.isFile }
            .joinToString { "${it.relativeTo(root).path}(${it.length()})" }
        log("解压完成，共 ${root.walkTopDown().count { it.isFile }} 个文件：$tree")
        return true
    }

    companion object {
        private const val TAG = "NongyuSkinDownload"
        private const val MANIFEST_FILE = "manifest.json"

        /**
         * 内置验签器占位。
         *
         * ADR-0005 要求「整包签名」，但**算法与公钥属部署期决策，尚未定**
         * （见 `FileSkinSource.kt` 同款注释）。这里保持与启动路径一致的行为：
         * 放行并记 WARN，避免出现「启动时放行、下载时拒绝」的不一致。
         * 公钥一旦确定，把这里换成真实实现即可，调用方无需改动。
         */
        val NO_OP_VERIFIER = PackSignatureVerifier { _, _ ->
            Log.w(TAG, "整包签名未验证（ADR-0005 未定算法/公钥），本次按放行处理")
            true
        }
    }
}

package com.nongyu.appplus.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * **皮肤包字节一律从文件读**（票 #98 设备验收发现的真缺陷）。
 *
 * ## 缺陷是什么
 * `SkinPackLoader.load` 在 API 30+ 且传了 `Resources` 时会走
 * `ResourcesLoaderPackByteSource`，它用 `resources.assets.open(path)` 读文件。
 * 但 `ResourcesLoaderBinding.attach` 用的是
 * `ResourcesProvider.loadFromDirectory(packDir, null)` —— **第二个参数是
 * AssetsProvider，传的是 null**，也就是根本没有把包目录接到 assets 上。
 *
 * ⇒ 结果：解压成功、install 成功，但启动路径在 API 30+ **永远读不到
 * `icons/grid.png` / `fonts/x.ttf`**，报 `IntegrityFailed（清单声明的文件缺失）`，
 * 皮肤包在 API 30+ 设备上**整体失效**。
 *
 * ## 本文件的断言
 * 不依赖 Android Framework（纯 JVM 可跑）：直接验证
 * [SkinPackLoader.assemble] 在拿到**文件字节源**时能通过校验，
 * 并在注释里锁住「两条路径都必须能拿到字节」这条契约。
 * 真正的设备验证由 APK 实装完成（见票 #98）。
 */
class SkinPackByteSourceContractTest {

    private fun sha(b: ByteArray) = SkinPackIntegrity.sha256Hex(b)

    private fun builtIn() = ResourcePayload.empty(PayloadMeta.fallback(PayloadOrigin.Absent, "无"))

    @Test
    fun assemble_withFileByteSource_verifiesNestedFiles() {
        val base = Files.createTempDirectory("skin-bytes").toFile().apply { deleteOnExit() }
        val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        File(base, "icons").mkdirs()
        File(base, "icons/grid.png").writeBytes(icon)
        File(base, "manifest.json").writeText("{}")

        val manifest = SkinPackManifest(
            packId = "p",
            schemaVersion = 1,
            minAppVersion = 1,
            displayName = "d",
            iconStrokes = emptyMap(),
            fontFileName = null,
            entries = listOf(SkinPackEntry("icons/grid.png", sha(icon))),
            signature = "sig",
            iconImages = mapOf(NavIconKey.Grid to "icons/grid.png"),
        )

        // 关键：FilePackByteSource 能读到嵌套文件 —— 这是「文件路径」契约
        val src = FilePackByteSource(base)
        assertEquals("FilePackByteSource 必须能读嵌套文件", icon.toList(), src.read("icons/grid.png")?.toList())

        val loaded = SkinPackLoader.assemble(
            source = src,
            manifest = manifest,
            appVersionCode = 1,
            builtIn = builtIn(),
            verifier = { _, _ -> true },
        )
        assertTrue(
            "文件字节源装配后应带出 iconImages，实际: ${loaded.iconImages}",
            loaded.iconImages.isNotEmpty(),
        )
    }

    /**
     * 反向锁住缺陷：**如果**用 `resources.assets.open()` 那种读法（这里用一个
     * 模拟「assets 里没有该文件」的 byte source 表达），装配必然失败。
     * 这条测试的作用是让「不能依赖 assets 读包文件」这件事有回归保护。
     */
    @Test
    fun assemble_withUnreadableByteSource_failsIntegrity() {
        val manifest = SkinPackManifest(
            packId = "p",
            schemaVersion = 1,
            minAppVersion = 1,
            displayName = "d",
            iconStrokes = emptyMap(),
            fontFileName = null,
            entries = listOf(SkinPackEntry("icons/grid.png", sha(byteArrayOf(1)))),
            signature = "sig",
        )
        // 模拟 assets.open 抛 FileNotFoundException -> read 返回 null
        val brokenSource = PackByteSource { null }

        val loaded = SkinPackLoader.assemble(
            source = brokenSource,
            manifest = manifest,
            appVersionCode = 1,
            builtIn = builtIn(),
            verifier = { _, _ -> true },
        )
        assertEquals(
            "字节源读不到时必须是 IntegrityFailed（而不是静默成功）",
            PayloadOrigin.IntegrityFailed,
            loaded.meta.origin,
        )
    }
}

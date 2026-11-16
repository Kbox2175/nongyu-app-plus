package com.nongyu.appplus.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `SkinStore.install` 对**嵌套子目录**的复制行为（票 #98 设备验收发现的缺口）。
 *
 * ## 为什么单独一个文件
 * `SkinStoreTest` 里的 `makePack` 只造扁平的 `f.txt`，所以「带子目录的包」
 * （`icons/grid.png`、`fonts/x.ttf`）**从未被测过**。设备上因此出现：
 * 解压出 2 个文件（`manifest.json` + `icons/grid.png`）→ `install` 报成功 →
 * 重启后启动路径却报 `清单声明的文件缺失：icons/grid.png`。
 */
class SkinStoreNestedDirTest {

    private fun newBase(): File =
        Files.createTempDirectory("skin-store-nested").toFile().apply { deleteOnExit() }

    private fun store(base: File) = SkinStore(baseDir = base)

    @Test
    fun install_copiesNestedSubdirectories() {
        val base = newBase()
        val incoming = File(base, "incoming").apply { mkdirs() }
        File(incoming, "manifest.json").writeText("{}")
        File(incoming, "icons").mkdirs()
        File(incoming, "icons/grid.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        File(incoming, "fonts").mkdirs()
        File(incoming, "fonts/brand.ttf").writeBytes(ByteArray(64) { it.toByte() })

        val outcome = store(base).install(incoming, "p", "1.0.0")
        assertTrue("安装应成功，实际: $outcome", outcome is InstallOutcome.Installed)

        val dir = store(base).currentPackDir()
        assertTrue("currentPackDir 不应为 null", dir != null)
        assertTrue(
            "manifest.json 应存在",
            File(dir, "manifest.json").isFile,
        )
        assertTrue(
            "嵌套文件 icons/grid.png 必须被复制（设备上正是这里缺）",
            File(dir, "icons/grid.png").isFile,
        )
        assertTrue(
            "嵌套文件 fonts/brand.ttf 必须被复制",
            File(dir, "fonts/brand.ttf").isFile,
        )
        assertEquals(
            "内容应逐字节一致",
            4L,
            File(dir, "icons/grid.png").length(),
        )
    }

    /**
     * 真实场景：安装后用 **FilePackByteSource** 按清单路径读字节。
     * 这是启动路径做的事——如果 install 丢了子目录，这里必然读不到。
     */
    @Test
    fun installedPack_isReadableByFilePackByteSource() {
        val base = newBase()
        val incoming = File(base, "incoming").apply { mkdirs() }
        File(incoming, "icons").mkdirs()
        val iconBytes = byteArrayOf(1, 2, 3, 4, 5)
        File(incoming, "icons/grid.png").writeBytes(iconBytes)

        store(base).install(incoming, "p", "1.0.0")
        val dir = store(base).currentPackDir()!!

        val read = FilePackByteSource(dir).read("icons/grid.png")
        assertTrue("启动路径按清单路径读字节时必须能读到", read != null)
        assertEquals("读到的字节应与原文件一致", iconBytes.toList(), read!!.toList())
    }
}

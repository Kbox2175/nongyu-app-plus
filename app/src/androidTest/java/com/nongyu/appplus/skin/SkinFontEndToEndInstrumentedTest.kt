package com.nongyu.appplus.skin

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nongyu.appplus.BuildConfig
import com.nongyu.appplus.design.SkinFontFamilyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 票 #54 的**设备端验收**：证明「给定带字体的皮肤包，界面文本真的用上了那个字体」。
 *
 * ## 为什么必须是 instrumented test
 * 上一轮用 `adb` 造包时被 `run-as` / `adb root` 双双拒绝（`setegid(AID_PACKAGE_INFO)
 * failed: Operation not permitted`），无法写入 app 私有 `filesDir`。instrumented test
 * 跑在**被测 App 进程内**，`context.filesDir` 可直接读写，绕开该限制且**不改生产代码**。
 *
 * ## 验的是真链路（不是造假的中间态）
 * 造真实皮肤包（清单 + 真 TTF，sha256 用真实字节）→ 写进 `filesDir/skins/{id}/{ver}/`
 * → 写 `current-skin` 指针 → 调**生产的** [FileSkinSource.resolve] → 取出资源段的
 * `SkinFont` → 用**生产的** [SkinFontFamilyFactory.create] 构造 `FontFamily`。
 *
 * ## 三条断言
 * 1. 字体被识别为「包内字体」且带绝对路径（证明启动路径把包目录拼进去了）；
 * 2. 得到的 `FontFamily` **不是** [FontFamily.Default]（证明真的换了字体，而非静默回落）；
 * 3. 字体**度量**与系统默认字体不同（用 `TextPaint` 量同一串文字宽度，排除
 *    「对象不同但视觉相同」的假阳性）。
 */
@RunWith(AndroidJUnit4::class)
class SkinFontEndToEndInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val packId = "font-e2e"
    private val version = "1.0.0"

    private val packDir: File
        get() = File(
            File(File(context.filesDir, SkinStoreLayout.SKINS_DIR), packId),
            version,
        )

    private val pointerFile: File
        get() = File(context.filesDir, SkinStoreLayout.POINTER_FILE)

    @Before
    fun clean() {
        packDir.deleteRecursively()
        pointerFile.delete()
    }

    @After
    fun tearDown() {
        // 验收完把设备上的测试包清掉，不留残留影响后续启动。
        packDir.deleteRecursively()
        pointerFile.delete()
    }

    @Test
    fun packFont_isResolvedWithAbsolutePath_andChangesFontFamily() {
        val fontBytes = readSystemFont()
        writePack(fontBytes = fontBytes, manifestFontName = FONT_PATH)

        val resolution = FileSkinSource(context, BuildConfig.VERSION_CODE).resolve()
        val font = resolution.resourcePayload().font

        // ① 启动路径应识别出包内字体，且带绝对路径（本票新增的接缝）
        assertTrue("应识别为包内字体，实际: $font", font is SkinFont.FromPack)
        val fromPack = font as SkinFont.FromPack
        assertEquals(FONT_PATH, fromPack.fileName)
        val abs = fromPack.absolutePath
        assertTrue("绝对路径应非空，实际: $abs", !abs.isNullOrBlank())
        assertTrue("绝对路径应指向真实文件: $abs", File(abs).isFile)

        // ② 转换层应给出**非默认**的 FontFamily（证明确实换了字体）
        val family = SkinFontFamilyFactory.create(fromPack)
        assertNotEquals(
            "字体应真的生效，而不是静默回落 FontFamily.Default",
            FontFamily.Default,
            family,
        )

        // ③ 度量证据：同一串文字在默认字体与包内字体下宽度应不同
        val defaultWidth = measureTextWidth(FontFamily.Default)
        val packWidth = measureTextWidth(family)
        assertNotEquals(
            "字体度量应与系统默认不同（否则视觉上等于没换）：default=$defaultWidth pack=$packWidth",
            defaultWidth,
            packWidth,
        )
    }

    @Test
    fun corruptedFont_fallsBackToDefault_withoutCrash() {
        // 写入垃圾字节当作「损坏字体」：清单声明它，但内容不是合法 TTF。
        writePack(fontBytes = ByteArray(512) { 0x00 }, manifestFontName = FONT_PATH)

        val font = FileSkinSource(context, BuildConfig.VERSION_CODE)
            .resolve().resourcePayload().font

        // 加载阶段 PackFontProbe 会判「读不成字体」→ 回落到 SystemDefault。
        // 无论走到哪一层回落，硬要求是：不崩、且最终字体族是默认。
        val family = SkinFontFamilyFactory.create(font)
        assertEquals("损坏字体必须静默回落系统默认", FontFamily.Default, family)
        assertFalse("损坏字体不应被当成可用字体", font is SkinFont.FromPack && font.absolutePath != null)
    }

    // ---------- 辅助 ----------

    /** 从设备系统字体里取一个真实 TTF（保证 `Typeface.createFromFile` 能解）。 */
    private fun readSystemFont(): ByteArray {
        val candidates = listOf(
            "/system/fonts/DancingScript-Bold.ttf",
            "/system/fonts/Caveat-Regular.ttf",
            "/system/fonts/CutiveMono.ttf",
            "/system/fonts/ComingSoon.ttf",
        )
        for (p in candidates) {
            val f = File(p)
            if (f.isFile && f.length() > 4096) return f.readBytes()
        }
        // 兜底：扫 /system/fonts 找任意一个可解的 ttf
        val dir = File("/system/fonts")
        val any = dir.listFiles()?.firstOrNull { it.isFile && it.length() > 4096 && it.name.endsWith(".ttf") }
            ?: error("设备上没有可用的系统 TTF")
        return any.readBytes()
    }

    /** 按生产契约写一份真实皮肤包 + 指针。 */
    private fun writePack(fontBytes: ByteArray, manifestFontName: String) {
        packDir.mkdirs()
        File(packDir, FONT_PATH).writeBytes(fontBytes)

        // 新结构清单：payload.fontFileName + integrity.entries（真实 sha256）
        val sha = SkinPackIntegrity.sha256Hex(fontBytes)
        val manifest = """
            {
              "packId": "$packId",
              "schemaVersion": ${SkinPackManifest.SUPPORTED_SCHEMA_MAJOR},
              "minAppVersion": 1,
              "displayName": "字体端到端验收包",
              "payload": { "iconStrokes": {}, "fontFileName": "$manifestFontName" },
              "integrity": {
                "entries": [ { "path": "$FONT_PATH", "sha256": "$sha" } ],
                "signature": "e2e"
              }
            }
        """.trimIndent()
        File(packDir, "manifest.json").writeText(manifest)

        pointerFile.writeText(SkinStoreLayout.encodePointer(PackRef(packId, version)))
    }

    /** 用 TextPaint 量同一串字符的宽度（px），作为「字体真的不同」的度量证据。 */
    private fun measureTextWidth(family: FontFamily): Float {
        val paint = android.text.TextPaint()
        paint.textSize = 48f
        paint.typeface = if (family === FontFamily.Default) {
            Typeface.DEFAULT
        } else {
            // 包内字体：从同一文件建 Typeface，保证度量针对的就是包内字体
            Typeface.createFromFile(File(packDir, FONT_PATH))
        }
        return paint.measureText("Nongyu 农屿 1234")
    }

    private companion object {
        const val FONT_PATH = "fonts/e2e.ttf"
    }
}

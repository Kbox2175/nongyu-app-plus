package com.nongyu.appplus.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 字体选择决策的纯 JVM 单测（票 #54）。
 *
 * 刻意只测「选哪个字体」的决策，不测「怎么把字节变成 FontFamily」——
 * 后者依赖 Android Framework（android.graphics.Typeface / androidx.compose.ui.text.font），
 * 在 :app:testDebugUnitTest 里不可用。这与 T3/T5 处理 org.json 的同一套分层思路。
 */
class SkinFontResolverTest {

    @Test
    fun `SystemDefault 始终回落到系统默认，不依赖包目录`() {
        val d = SkinFontResolver.resolve(SkinFont.SystemDefault, packDir = null)
        assertEquals(FontChoice.SystemDefault, d.choice)
        assertNull(d.absolutePath)
    }

    @Test
    fun `FromPack 有包目录时给出绝对路径`() {
        val d = SkinFontResolver.resolve(
            SkinFont.FromPack("fonts/brand.ttf"),
            packDir = "/data/user/0/com.nongyu.appplus/files/skins/p1/v1",
        )
        assertEquals(FontChoice.FromFile, d.choice)
        assertEquals(
            "/data/user/0/com.nongyu.appplus/files/skins/p1/v1/fonts/brand.ttf",
            d.absolutePath,
        )
    }

    @Test
    fun `FromPack 缺包目录时安全回落系统默认，不抛异常`() {
        // 只有文件名、拿不到包目录 = 无法定位字体文件。ADR-0006 静默回落原则。
        val d = SkinFontResolver.resolve(SkinFont.FromPack("fonts/brand.ttf"), packDir = null)
        assertEquals(FontChoice.SystemDefault, d.choice)
        assertNull(d.absolutePath)
    }

    @Test
    fun `FromPack 带了自己的 absolutePath 时优先用它，忽略 packDir`() {
        // FileSkinSource 已在启动路径把绝对路径拼好塞进 FromPack；
        // 渲染层不必再知道包目录，这是「FromPack 带绝对路径」方案的落点。
        val d = SkinFontResolver.resolve(
            SkinFont.FromPack("fonts/brand.ttf", absolutePath = "/abs/brand.ttf"),
            packDir = null,
        )
        assertEquals(FontChoice.FromFile, d.choice)
        assertEquals("/abs/brand.ttf", d.absolutePath)
    }

    @Test
    fun `FromPack 的 fileName 为空时回落系统默认`() {
        val d = SkinFontResolver.resolve(SkinFont.FromPack(""), packDir = "/tmp/pack")
        assertEquals(FontChoice.SystemDefault, d.choice)
        assertNull(d.absolutePath)
    }

    @Test
    fun `包目录末尾斜杠不会产生双斜杠`() {
        val d = SkinFontResolver.resolve(SkinFont.FromPack("a.ttf"), packDir = "/tmp/pack/")
        assertEquals("/tmp/pack/a.ttf", d.absolutePath)
    }

    @Test
    fun `包目录为空串等同无包目录`() {
        val d = SkinFontResolver.resolve(SkinFont.FromPack("a.ttf"), packDir = "")
        assertEquals(FontChoice.SystemDefault, d.choice)
        assertNull(d.absolutePath)
    }
}

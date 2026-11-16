package com.nongyu.appplus.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「皮肤可覆盖图标（位图）」的纯 JVM 单测。
 *
 * 决策来源 ADR-0009（本票新建）：图标从「只有绘制参数」扩展为「可选位图覆盖」。
 * 与既有 `SkinPackTest` 同一套分层：只测解析 / 剔除 / 回落这些**判定**，
 * 不测 `BitmapFactory` 解码（依赖 Android Framework）。
 *
 * 硬约束（沿用 ADR-0005 Q5=A）：**逐资源回落** —— 单个图标非法或缺失只该项用内置默认。
 */
class IconImageOverrideTest {

    // ---------- 清单解析：payload.iconImages ----------

    @Test
    fun manifest_parsesIconImages() {
        val raw = rawOf(iconImages = mapOf("grid" to "icons/grid.png"))
        val m = SkinManifestParser.parseMap(raw)
        assertEquals(mapOf(NavIconKey.Grid to "icons/grid.png"), m?.iconImages)
    }

    @Test
    fun manifest_withoutIconImages_isEmpty() {
        val m = SkinManifestParser.parseMap(rawOf(iconImages = null))
        assertTrue("未声明时应为空表而不是 null", m?.iconImages?.isEmpty() == true)
    }

    @Test
    fun manifest_iconImagePath_mustBeSafeAndDeclaredInEntries() {
        // 声明了但没进 integrity.entries → 整包拒绝（防止夹带未校验文件）
        val undeclared = SkinManifestParser.parseMap(
            rawOf(iconImages = mapOf("grid" to "icons/grid.png"), declareIconPathsIn = false),
        )
        assertNull("图片未在 integrity.entries 声明时必须整包拒绝", undeclared)

        // 目录穿越 → 整包拒绝
        val traversal = SkinManifestParser.parseMap(
            rawOf(iconImages = mapOf("grid" to "../evil.png")),
        )
        assertNull("路径穿越必须整包拒绝", traversal)

        // 非白名单扩展名 → 整包拒绝
        val badExt = SkinManifestParser.parseMap(
            rawOf(iconImages = mapOf("grid" to "icons/grid.svg")),
        )
        assertNull("svg 不在白名单，应整包拒绝", badExt)
    }

    @Test
    fun manifest_unknownIconKey_isDroppedNotFatal() {
        // 未知图标名属「同主版本未知字段」→ 忽略（ADR-0003 第②层），不影响其他项
        val m = SkinManifestParser.parseMap(
            rawOf(iconImages = mapOf("grid" to "icons/grid.png", "not-an-icon" to "icons/x.png")),
        )
        // "not-an-icon" 不在 NavIconKey.BuiltIn 里 → 被忽略；grid 保留
        assertEquals(mapOf(NavIconKey.Grid to "icons/grid.png"), m?.iconImages)
    }

    // ---------- 装配：逐资源回落 ----------

    @Test
    fun plan_carriesIconImagesThrough() {
        val manifest = manifestOf(iconImages = mapOf("grid" to "icons/grid.png"))
        val planned = SkinPackPlanner.plan(
            manifest = manifest,
            verification = PackVerification(ok = true, origin = PayloadOrigin.Loaded),
            fontReadable = false,
            builtIn = builtIn(),
        )
        val expected = mapOf(NavIconKey.Grid to "icons/grid.png")
        assertEquals(expected, planned.iconImages)
        assertEquals(expected, planned.toPayload().iconImages)
    }

    @Test
    fun payload_iconImageOf_fallsBackPerIcon() {
        val payload = ResourcePayload(
            iconStrokes = emptyMap(),
            font = SkinFont.SystemDefault,
            iconImages = mapOf(NavIconKey.Grid to "/abs/grid.png"),
            meta = PayloadMeta.loaded(version = "1"),
        )
        assertEquals("/abs/grid.png", payload.iconImageOf(NavIconKey.Grid))
        assertNull("缺失项应返回 null，由渲染层回落内置几何", payload.iconImageOf(NavIconKey.Compass))
    }

    @Test
    fun emptyPayload_hasNoIconImages() {
        val empty = ResourcePayload.empty(PayloadMeta.fallback(PayloadOrigin.Absent, "无"))
        assertTrue(empty.iconImages.isEmpty())
        assertNull(empty.iconImageOf(NavIconKey.Grid))
    }

    // ---------- 辅助 ----------

    private fun builtIn() = ResourcePayload.empty(PayloadMeta.fallback(PayloadOrigin.Absent, "无"))

    private fun manifestOf(iconImages: Map<String, String>) = SkinPackManifest(
        packId = "p",
        schemaVersion = 1,
        minAppVersion = 1,
        displayName = "d",
        iconStrokes = emptyMap(),
        fontFileName = null,
        entries = listOf(SkinPackEntry("icons/grid.png", "a".repeat(64))),
        signature = "sig",
        iconImages = iconImages.map { (name, path) -> NavIconKey(name) to path }.toMap(),
    )

    /**
     * 造一份最小合法清单；[iconImages] 为 null 时不声明该段。
     *
     * [declareIconPathsIn] 决定把哪些**图标路径**写进 integrity.entries：
     * 传 `false` 即可造出「清单声明了图片但没在 entries 里登记」的非法包。
     */
    private fun rawOf(
        iconImages: Map<String, String>?,
        declareIconPathsIn: Boolean = true,
    ): ManifestRaw {
        val paths = buildList {
            add("icons/declared.png")
            if (declareIconPathsIn && iconImages != null) addAll(iconImages.values)
        }.distinct()
        return ManifestRaw(
            packId = "p",
            schemaVersion = 1,
            minAppVersion = 1,
            displayName = "d",
            signature = "sig",
            entries = paths.map { SkinPackEntry(it, "a".repeat(64)) },
            iconStrokes = emptyMap(),
            fontFileName = null,
            iconImages = iconImages,
            format = ManifestFormat.NEW,
        )
    }
}

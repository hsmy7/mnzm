package com.xianxia.sect.core.render

import com.xianxia.sect.core.nativebridge.NativeBridge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * SceneUvTablesMirrorGuardTest — 场景 UV 常量表跨语言镜像守卫（R3.2/B10）。
 *
 * 守护目标：`app/src/main/cpp/scene/scene_uv_tables.h`（build-atlas.mjs
 * --codegen 生成的仓库内生成物）与 Kotlin [SpriteAtlasDef]（同一生成器产出）
 * **逐位一致**——五张 UV 表（瓦片/建筑+固定结构/作物/云/道路）、占地尺寸表、
 * 双端共享渲染常量，以及 R3.3/B11 的叠加层视觉常量（网格线/预览框/选中/拆除
 * 高亮的颜色、不透明度、线宽 30 项）。任一侧漂移（改 LAYOUT 后漏提交 C++ 头 /
 * 手改生成物）即失败并指引重跑 `node scripts/build-atlas.mjs --codegen`。
 *
 * 双向等价链：LAYOUT（唯一权威）→ 同一生成器 → Kotlin 常量（本测试读取）
 * 与 C++ 头（本测试解析）；R3.2 后 Kotlin 渲染新路径不再每帧传 UV 数组，
 * C++ 消费生成表——本守卫锁定"两份生成物同值"这一渲染像素等价的前提
 * （顶点流级等价另由 C++ SceneEquivalenceTest 锁定）。
 *
 * UV 解析为表达式求值（`136.0f / 4096.0f`）：图集尺寸为 2 的幂，除法在
 * 二进制浮点下精确，双端/测试三侧逐位一致；比较用 [Float.toRawBits]
 * （严格位相等，-0.0/NaN 语义不放宽）。
 */
class SceneUvTablesMirrorGuardTest {

    /** 从测试工作目录向上定位仓库根（含 android/app 的最近父目录）。 */
    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "android/app/src/main/cpp/scene/scene_uv_tables.h").exists() ||
                File(dir, "app/src/main/cpp/scene/scene_uv_tables.h").exists()
            ) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun generatedHeader(): File {
        val root = repoRoot()
        assumeTrue("仓库根定位失败（工作目录: ${System.getProperty("user.dir")}）", root != null)
        // assumeTrue 已在 null 时跳过测试——此处按 StaticDataSingleSourceGuardTest
        // 先例以 elvis 兜底返回（不可达路径），避免 `!!`
        val base = root ?: return File("scene_uv_tables.h")
        val direct = File(base, "app/src/main/cpp/scene/scene_uv_tables.h")
        if (direct.exists()) return direct
        return File(base, "android/app/src/main/cpp/scene/scene_uv_tables.h")
    }

    /**
     * 手写头 `scene_draw.h`（非生成物）。
     *
     * 少数跨端常量（如浮字 spawn 端口的 `kFloatSpawnStride`）落在生成核心而非
     * 生成表内——它们同样是双端契约，须同样纳入镜像守卫。
     */
    private fun sceneDrawHeader(): File {
        val root = repoRoot()
        assumeTrue("仓库根定位失败（工作目录: ${System.getProperty("user.dir")}）", root != null)
        val base = root ?: return File("scene_draw.h")
        val direct = File(base, "app/src/main/cpp/scene/scene_draw.h")
        if (direct.exists()) return direct
        return File(base, "android/app/src/main/cpp/scene/scene_draw.h")
    }

    // ── 生成头解析 ─────────────────────────────────────────────

    /** 提取 `inline constexpr float <name>[] = { ... };` 花括号内文本 */
    private fun floatArrayBlock(header: String, name: String): String {
        val marker = "inline constexpr float $name[] = {"
        val start = header.indexOf(marker)
        assertTrue("scene_uv_tables.h 缺少 $name（生成物被手改/损坏？重跑 build-atlas.mjs --codegen）", start >= 0)
        val bodyStart = start + marker.length
        val end = header.indexOf("};", bodyStart)
        assertTrue("scene_uv_tables.h 的 $name 未正常闭合", end > bodyStart)
        return header.substring(bodyStart, end)
    }

    /** 提取 `inline constexpr int <name> = <value>;` 标量 */
    private fun intScalar(header: String, name: String): Int {
        val regex = Regex("inline constexpr int $name = (-?\\d+);")
        val m = regex.find(header)
        assertTrue("scene_uv_tables.h 缺少 $name", m != null)
        return m!!.groupValues[1].toInt()
    }

    /** 提取 `inline constexpr float <name> = <literal>;` 标量（浮点字面量） */
    private fun floatScalar(header: String, name: String): Float {
        val regex = Regex("inline constexpr float $name = (-?[\\d.]+f);")
        val m = regex.find(header)
        assertTrue("scene_uv_tables.h 缺少 $name", m != null)
        return m!!.groupValues[1].removeSuffix("f").toFloat()
    }

    /**
     * 解析 UV 表条目为 Float 列表：每项为 `a.0f / 4096.0f` 除法表达式
     * （生成器保证形态；2 的幂除法精确 ⇒ 求值结果与 Kotlin 运行时逐位一致）。
     */
    private fun parseUvTable(header: String, name: String): List<Float> =
        floatArrayBlock(header, name).split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                val parts = token.split("/").map { it.trim().removeSuffix("f").trim() }
                assertEquals("UV 表 $name 条目须为 `a / b` 除法表达式: $token", 2, parts.size)
                parts[0].toFloat() / parts[1].toFloat()
            }

    /** 解析整型数组表 */
    private fun parseIntTable(header: String, name: String): List<Int> {
        val marker = "inline constexpr int $name[] = {"
        val start = header.indexOf(marker)
        assertTrue("scene_uv_tables.h 缺少 $name（生成物被手改/损坏？）", start >= 0)
        val bodyStart = start + marker.length
        val end = header.indexOf("};", bodyStart)
        assertTrue("scene_uv_tables.h 的 $name 未正常闭合", end > bodyStart)
        return header.substring(bodyStart, end)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
    }

    private fun assertBitsEqual(what: String, expected: Float, actual: Float) {
        assertEquals(
            "$what 逐位不一致（Kotlin=${expected.toRawBits().toString(16)} " +
                "C++=${actual.toRawBits().toString(16)}）——scene_uv_tables.h 与 " +
                "SpriteAtlasDef 漂移，重跑 node scripts/build-atlas.mjs --codegen",
            expected.toRawBits(),
            actual.toRawBits()
        )
    }

    private fun assertTableBitsEqual(what: String, expected: FloatArray, actual: List<Float>) {
        assertEquals("$what 长度不一致（Kotlin=${expected.size} C++=${actual.size}）", expected.size, actual.size)
        for (i in expected.indices) {
            assertBitsEqual("$what[$i]", expected[i], actual[i])
        }
    }

    // ── 五张 UV 表 ─────────────────────────────────────────────

    @Test
    fun `tile uv table mirrors SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual("kTileUv", SpriteAtlasDef.TILE_UV_MAP, parseUvTable(header, "kTileUv"))
    }

    @Test
    fun `building uv table mirrors SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual(
            "kBuildingUv", SpriteAtlasDef.BUILDING_UV_MAP, parseUvTable(header, "kBuildingUv")
        )
    }

    @Test
    fun `crop cloud road uv tables mirror SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual("kCropUv", SpriteAtlasDef.CROP_UV_MAP, parseUvTable(header, "kCropUv"))
        assertTableBitsEqual("kCloudUv", SpriteAtlasDef.CLOUD_UV_MAP, parseUvTable(header, "kCloudUv"))
        assertTableBitsEqual("kRoadUv", SpriteAtlasDef.ROAD_UV_MAP, parseUvTable(header, "kRoadUv"))
    }

    // ── 占地表 ─────────────────────────────────────────────────

    @Test
    fun `footprint tables mirror SpriteAtlasDef`() {
        val header = generatedHeader().readText()
        val w = parseIntTable(header, "kFootprintW")
        val h = parseIntTable(header, "kFootprintH")
        assertEquals(
            "kFootprintW/H 行数与 FOOTPRINT_BY_NAME_INDEX 不一致",
            SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size,
            w.size
        )
        assertEquals(h.size, w.size)
        for (i in w.indices) {
            val (kw, kh) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[i]
            assertEquals("kFootprintW[$i] 与 Kotlin 占地宽不一致", kw, w[i])
            assertEquals("kFootprintH[$i] 与 Kotlin 占地深不一致", kh, h[i])
        }
    }

    // ── 双端共享渲染常量 ───────────────────────────────────────

    @Test
    fun `shared render constants mirror SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertBitsEqual(
            "kDecorQualityThreshold",
            SpriteAtlasDef.DECOR_QUALITY_THRESHOLD,
            floatScalar(header, "kDecorQualityThreshold")
        )
        assertBitsEqual(
            "kShadowOffsetTiles",
            SpriteAtlasDef.SHADOW_OFFSET_TILES,
            floatScalar(header, "kShadowOffsetTiles")
        )
        assertBitsEqual(
            "kShadowAlpha",
            SpriteAtlasDef.SHADOW_ALPHA,
            floatScalar(header, "kShadowAlpha")
        )
        assertBitsEqual(
            "kTopdownYScale",
            SpriteAtlasDef.TOPDOWN_Y_SCALE,
            floatScalar(header, "kTopdownYScale")
        )
        assertEquals(
            "kStructureNameBase 与 Kotlin 建筑数不一致",
            SpriteAtlasDef.BUILDING_NAMES.size,
            intScalar(header, "kStructureNameBase")
        )
    }

    // ── 叠加层视觉常量（R3.3/B11）────────────────────────────────

    /**
     * C++ `scene::kXxx` ↔ Kotlin [SpriteAtlasDef] 叠加层常量配对表。
     *
     * 二者同由 build-atlas.mjs 的 LAYOUT.overlay 生成；本表**逐条点名**消费侧引用名，
     * 使"新增 overlay 常量但漏登记消费侧"成为可检失败（数量断言 + 逐位断言）。
     * VulkanRenderBackend 旧路径（逐 rect 回滚臂）与 C++ 新路径（几何生成）都引用
     * 这组值 ⇒ 两路同值由构造保证（本守卫锁定生成物一侧不漂移）。
     */
    private val overlayConstants: List<Pair<String, Float>> = listOf(
        "kGoldR" to SpriteAtlasDef.GOLD_R,
        "kGoldG" to SpriteAtlasDef.GOLD_G,
        "kGoldB" to SpriteAtlasDef.GOLD_B,
        "kHighlightFillAlpha" to SpriteAtlasDef.HIGHLIGHT_FILL_ALPHA,
        "kHighlightEdgeAlpha" to SpriteAtlasDef.HIGHLIGHT_EDGE_ALPHA,
        "kDemolishGreenR" to SpriteAtlasDef.DEMOLISH_GREEN_R,
        "kDemolishGreenG" to SpriteAtlasDef.DEMOLISH_GREEN_G,
        "kDemolishGreenB" to SpriteAtlasDef.DEMOLISH_GREEN_B,
        "kDemolishRedR" to SpriteAtlasDef.DEMOLISH_RED_R,
        "kDemolishRedG" to SpriteAtlasDef.DEMOLISH_RED_G,
        "kDemolishRedB" to SpriteAtlasDef.DEMOLISH_RED_B,
        "kDemolishFillAlpha" to SpriteAtlasDef.DEMOLISH_FILL_ALPHA,
        "kDemolishEdgeAlpha" to SpriteAtlasDef.DEMOLISH_EDGE_ALPHA,
        "kPreviewGreenR" to SpriteAtlasDef.PREVIEW_GREEN_R,
        "kPreviewGreenG" to SpriteAtlasDef.PREVIEW_GREEN_G,
        "kPreviewGreenB" to SpriteAtlasDef.PREVIEW_GREEN_B,
        "kPreviewRedR" to SpriteAtlasDef.PREVIEW_RED_R,
        "kPreviewRedG" to SpriteAtlasDef.PREVIEW_RED_G,
        "kPreviewRedB" to SpriteAtlasDef.PREVIEW_RED_B,
        "kPreviewBoxFillAlpha" to SpriteAtlasDef.PREVIEW_BOX_FILL_ALPHA,
        "kPreviewBoxEdgeAlpha" to SpriteAtlasDef.PREVIEW_BOX_EDGE_ALPHA,
        "kGridR" to SpriteAtlasDef.GRID_R,
        "kGridG" to SpriteAtlasDef.GRID_G,
        "kGridB" to SpriteAtlasDef.GRID_B,
        "kGridAlpha" to SpriteAtlasDef.GRID_ALPHA,
        "kHighlightLineWidthTiles" to SpriteAtlasDef.HIGHLIGHT_LINE_WIDTH_TILES,
        "kHighlightLineMinPx" to SpriteAtlasDef.HIGHLIGHT_LINE_MIN_PX,
        "kGridLineWidthMinWorld" to SpriteAtlasDef.GRID_LINE_WIDTH_MIN_WORLD,
        "kGridLineWidthPx" to SpriteAtlasDef.GRID_LINE_WIDTH_PX,
        "kOverlayMinScale" to SpriteAtlasDef.OVERLAY_MIN_SCALE
    )

    @Test
    fun `overlay visual constants mirror SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertEquals(
            "生成头的叠加层常量条目数与 Kotlin 配对表不一致（新增/删除常量须双端同步，" +
                "并重跑 node scripts/build-atlas.mjs --codegen）",
            overlaySectionConstantCount(header),
            overlayConstants.size
        )
        for ((name, expected) in overlayConstants) {
            assertBitsEqual(name, expected, floatScalar(header, name))
        }
    }

    /** 生成头「世界叠加层」段的 `inline constexpr float` 条目数 */
    private fun overlaySectionConstantCount(header: String): Int {
        val start = header.indexOf("// ── 世界叠加层（overlay）视觉常量")
        assertTrue("scene_uv_tables.h 缺少叠加层常量段（生成物被手改/损坏？）", start >= 0)
        val end = header.indexOf("// ── 瓦片分类", start)
        assertTrue("叠加层常量段未正常闭合（缺少后续「瓦片分类」段）", end > start)
        return header.substring(start, end).lines().count { it.startsWith("inline constexpr float") }
    }

    // ── Tier1 文本资产（R3.8/B13）────────────────────────────────

    /**
     * Tier1 文本资产 UV 表逐位镜像：C++ `scene::kFloatUv` ↔ Kotlin
     * [SpriteAtlasDef.FLOAT_UV]。
     *
     * 这是「浮字批按资产索引直取 UV」这一零每帧 JNI 设计的地基——两侧任一侧
     * 漂移（改 LAYOUT 后漏跑 codegen / 手改生成物）即红。
     * 索引序 = 词条段（0..FLOAT_WORD_COUNT-1）→ 单字形段（FLOAT_GLYPH_BASE_INDEX..）。
     */
    @Test
    fun `tier1 float uv table mirrors SpriteAtlasDef bit-exactly`() {
        val header = generatedHeader().readText()
        assertTableBitsEqual("kFloatUv", SpriteAtlasDef.FLOAT_UV, parseUvTable(header, "kFloatUv"))
    }

    /**
     * Tier1 索引/几何常量双端一致 + 冻结清单内部自洽。
     *
     * 除逐位比对外，本用例显式锁定"冻结清单"这一语义：词条表 + 字形表拼接序
     * 与资产索引序严格对应（`FLOAT_ASSET_COUNT == WORD_COUNT + GLYPH_COUNT`，
     * 且 `FLOAT_GLYPH_BASE_INDEX == WORD_COUNT`）——新增词条只改 LAYOUT 也能被
     * 本守卫捕获（两侧索引偏移即失败）。
     */
    @Test
    fun `tier1 asset counts and indices mirror C++ constants`() {
        val header = generatedHeader().readText()
        assertEquals("kFloatWordCount 与 Kotlin 不一致", SpriteAtlasDef.FLOAT_WORD_COUNT, intScalar(header, "kFloatWordCount"))
        assertEquals("kFloatGlyphCount 与 Kotlin 不一致", SpriteAtlasDef.FLOAT_GLYPH_COUNT, intScalar(header, "kFloatGlyphCount"))
        assertEquals("kFloatAssetCount 与 Kotlin 不一致", SpriteAtlasDef.FLOAT_ASSET_COUNT, intScalar(header, "kFloatAssetCount"))
        assertEquals(
            "kFloatGlyphBaseIndex 与 Kotlin 不一致（字形段起始索引 = 词条数）",
            SpriteAtlasDef.FLOAT_GLYPH_BASE_INDEX,
            intScalar(header, "kFloatGlyphBaseIndex")
        )
        assertEquals(
            "kFloatCellW 与 Kotlin 不一致", SpriteAtlasDef.FLOAT_CELL_W, intScalar(header, "kFloatCellW")
        )
        assertEquals(
            "kFloatCellH 与 Kotlin 不一致", SpriteAtlasDef.FLOAT_CELL_H, intScalar(header, "kFloatCellH")
        )
        assertBitsEqual(
            "kFloatGlyphScale", SpriteAtlasDef.FLOAT_GLYPH_SCALE, floatScalar(header, "kFloatGlyphScale")
        )
        // 冻结清单内部自洽（两端同一份 LAYOUT 派生，此处防生成器逻辑漂移）
        assertEquals(
            "FLOAT_ASSET_COUNT ≠ FLOAT_WORD_COUNT + FLOAT_GLYPH_COUNT",
            SpriteAtlasDef.FLOAT_WORD_COUNT + SpriteAtlasDef.FLOAT_GLYPH_COUNT,
            SpriteAtlasDef.FLOAT_ASSET_COUNT
        )
        assertEquals(
            "FLOAT_GLYPH_BASE_INDEX ≠ FLOAT_WORD_COUNT（字形段索引基准漂移）",
            SpriteAtlasDef.FLOAT_WORD_COUNT,
            SpriteAtlasDef.FLOAT_GLYPH_BASE_INDEX
        )
        assertEquals(
            "FLOAT_UV 长度 ≠ FLOAT_ASSET_COUNT × 4（每资产 4 分量 u0,v0,u1,v1）",
            SpriteAtlasDef.FLOAT_ASSET_COUNT * 4,
            SpriteAtlasDef.FLOAT_UV.size
        )
    }

    /**
     * Tier1 冻结词表/字形表双端文本一致 + 与索引序对应。
     *
     * 批次要求「词表须在批次内显式冻结并列清单」——本用例把生成头中的 C++
     * 字符串字面量数组与 Kotlin 清单逐条对照，使"改了一侧漏改另一侧"成为
     * 可检失败；同时锁定运行期不依赖文本（仅按索引取 UV）这一前提不被动摇。
     */
    @Test
    fun `tier1 frozen word and glyph text lists mirror C++ literals`() {
        val header = generatedHeader().readText()
        val cppWords = parseCStringArray(header, "kFloatWordText")
        val cppGlyphs = parseCStringArray(header, "kFloatGlyphText")
        assertEquals(
            "C++ kFloatWordText 长度与 Kotlin FLOAT_WORD_TEXT 不一致",
            SpriteAtlasDef.FLOAT_WORD_TEXT.size, cppWords.size
        )
        assertEquals(
            "C++ kFloatGlyphText 长度与 Kotlin FLOAT_GLYPH_TEXT 不一致",
            SpriteAtlasDef.FLOAT_GLYPH_TEXT.size, cppGlyphs.size
        )
        for (i in cppWords.indices) {
            assertEquals("kFloatWordText[$i] 与 Kotlin 冻结词表不一致", SpriteAtlasDef.FLOAT_WORD_TEXT[i], cppWords[i])
        }
        for (i in cppGlyphs.indices) {
            assertEquals("kFloatGlyphText[$i] 与 Kotlin 冻结字形表不一致", SpriteAtlasDef.FLOAT_GLYPH_TEXT[i], cppGlyphs[i])
        }
        // 冻结清单不得有重复条目（重复 = 资产索引歧义）
        assertEquals(
            "FLOAT_WORD_TEXT 含重复条目", SpriteAtlasDef.FLOAT_WORD_TEXT.size,
            SpriteAtlasDef.FLOAT_WORD_TEXT.toSet().size
        )
        assertEquals(
            "FLOAT_GLYPH_TEXT 含重复条目", SpriteAtlasDef.FLOAT_GLYPH_TEXT.size,
            SpriteAtlasDef.FLOAT_GLYPH_TEXT.toSet().size
        )
        // 批次点名要求的最小词集必须在冻结清单内（漏删检测）
        for (required in listOf("会心", "格挡", "闪避", "连击", "暴击")) {
            assertTrue(
                "Tier1 冻结词表缺少批次点名词条「$required」",
                SpriteAtlasDef.FLOAT_WORD_TEXT.contains(required)
            )
        }
        // 数字 0-9 必须完整（伤害数字是浮字主用途）
        for (d in 0..9) {
            assertTrue("Tier1 冻结字形表缺少数字 '$d'", SpriteAtlasDef.FLOAT_GLYPH_TEXT.contains(d.toString()))
        }
    }

    /** 解析 `inline constexpr const char* <name>[] = { "a", "b" };` 的字符串列表 */
    private fun parseCStringArray(header: String, name: String): List<String> {
        val marker = "inline constexpr const char* $name[] = {"
        val start = header.indexOf(marker)
        assertTrue("scene_uv_tables.h 缺少 $name（生成物被手改/损坏？）", start >= 0)
        val bodyStart = start + marker.length
        val end = header.indexOf("};", bodyStart)
        assertTrue("scene_uv_tables.h 的 $name 未正常闭合", end > bodyStart)
        val body = header.substring(bodyStart, end)
        // 逗号分隔的 "..." 字面量（生成器用 JSON.stringify 产出纯 ASCII 引号）
        return Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    // ── Tier1 样式档（R3.8/B13）─────────────────────────────────

    /** C++ `kFloatStyle*` ↔ Kotlin [SpriteAtlasDef] 样式档索引配对表 */
    private val floatStyleConstants: List<Pair<String, Int>> = listOf(
        "kFloatStyleNormal" to SpriteAtlasDef.FLOAT_STYLE_NORMAL,
        "kFloatStyleCrit" to SpriteAtlasDef.FLOAT_STYLE_CRIT,
        "kFloatStyleHeal" to SpriteAtlasDef.FLOAT_STYLE_HEAL,
        "kFloatStyleWarn" to SpriteAtlasDef.FLOAT_STYLE_WARN
    )

    @Test
    fun `tier1 float style indices mirror SpriteAtlasDef`() {
        val header = generatedHeader().readText()
        assertEquals(
            "kFloatStyleCount 与 Kotlin FLOAT_STYLE_COUNT 不一致",
            SpriteAtlasDef.FLOAT_STYLE_COUNT, intScalar(header, "kFloatStyleCount")
        )
        assertEquals(
            "样式档索引条目数与 Kotlin 配对表不一致（新增/删除档位须双端同步）",
            floatStyleConstants.size, SpriteAtlasDef.FLOAT_STYLE_COUNT
        )
        val seen = mutableSetOf<Int>()
        for ((cppName, expected) in floatStyleConstants) {
            val actual = intScalar(header, cppName)
            assertEquals("$cppName 与 Kotlin 不一致", expected, actual)
            assertTrue("$cppName 档位索引重复", seen.add(actual))
        }
    }

    // ── 浮字 spawn 端口步长（R3.8-③/B13）─────────────────────────

    /**
     * `sceneSpawnFloatingText` 的**扁平标量步长**双端镜像。
     *
     * 端口契约：`jfloatArray` 长度必须是 `FLOAT_SPAWN_STRIDE` 的整数倍，
     * 每 10 个标量描述一条浮字：
     * `[worldX, worldY, assetIndex, charCount, styleIndex, nowSeconds,
     *   scale, riseScale, bounce, reserved]`
     *
     * 双端任一侧改动步长而另一侧未同步 ⇒ 参数错位（静默错渲染），
     * 故在此逐值锁定。
     */
    @Test
    fun `float spawn stride mirrors NativeBridge constant`() {
        val impl = sceneDrawHeader().readText()
        assertEquals(
            "C++ kFloatSpawnStride 与 Kotlin FLOAT_SPAWN_STRIDE 不一致" +
                "（步长错位会导致 spawn 参数静默错读）",
            NativeBridge.FLOAT_SPAWN_STRIDE, intScalar(impl, "kFloatSpawnStride")
        )
        assertTrue("spawn 步长必须为正", NativeBridge.FLOAT_SPAWN_STRIDE > 0)
    }
}

package com.xianxia.sect.core.render

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 生成物一致性守卫（资源管线 codegen）。
 *
 * 解析 `build-atlas.mjs --atlas-def-only` 生成的 SpriteAtlasDef.kt 文本，
 * 断言所有布局数值与期望全等。期望值复制自原手工版 SpriteAtlasDef.kt——
 * 修改 build-atlas.mjs 的 LAYOUT 源数据后本测试仍通过（生成物与源数据天然一致），
 * 其守卫价值在于：任何人手工修改生成物、或 LAYOUT 与 C++/消费侧期望漂移时，
 * 本测试变红并提示同步。
 */
class SpriteAtlasDefGeneratedTest {

    private val generatedFile =
        File("build/generated/sprite/com/xianxia/sect/core/render/SpriteAtlasDef.kt")

    private fun source(): String {
        assertTrue(
            "生成文件不存在: ${generatedFile.absolutePath}——请运行 ./gradlew :core:engine:generateSpriteAtlasDef",
            generatedFile.exists()
        )
        return generatedFile.readText()
    }

    @Test
    fun `const 常量与期望数值全等`() {
        val src = source()
        assertEquals("ATLAS_W", "4096", extractConst(src, "ATLAS_W"))
        assertEquals("ATLAS_H", "4096", extractConst(src, "ATLAS_H"))
        assertEquals("TILE_SIZE", "128", extractConst(src, "TILE_SIZE"))
        assertEquals("BUILDING_SIZE", "512", extractConst(src, "BUILDING_SIZE"))
        // gutter 版布局（管线改版）：建筑节距 = 512 槽位 + 8 gutter
        assertEquals("BUILDING_PITCH", "520", extractConst(src, "BUILDING_PITCH"))
        assertEquals("BUILDING_GRID_ORIGIN_Y", "512", extractConst(src, "BUILDING_GRID_ORIGIN_Y"))
    }

    @Test
    fun `TileType 11 个枚举值及 rect 与期望全等`() {
        val expected = listOf(
            Triple("GROUND", 0, intArrayOf(0, 0, 128, 128)),
            Triple("GRASS1", 1, intArrayOf(136, 0, 128, 128)),
            Triple("GRASS2", 2, intArrayOf(272, 0, 128, 128)),
            Triple("GRASS3", 3, intArrayOf(408, 0, 128, 128)),
            Triple("GRASS4", 4, intArrayOf(544, 0, 128, 128)),
            Triple("STONE1", 5, intArrayOf(2048, 0, 128, 128)),
            Triple("STONE2", 6, intArrayOf(2184, 0, 128, 128)),
            Triple("STONE3", 7, intArrayOf(2320, 0, 128, 128)),
            Triple("TREE1", 8, intArrayOf(800, 0, 256, 256)),
            Triple("TREE2", 9, intArrayOf(1064, 0, 256, 256)),
            Triple("TILE_BUILDING", 10, intArrayOf(0, 0, 128, 128)),
        )
        val actual = parseTileTypes(source())
        assertEquals(
            "TileType 枚举值数量与期望不一致——修改 LAYOUT.tiles 后需同步本测试期望",
            expected.size, actual.size
        )
        for (i in expected.indices) {
            assertEquals("TileType[$i] 名称", expected[i].first, actual[i].first)
            assertEquals("TileType[${expected[i].first}] index", expected[i].second, actual[i].second)
            assertEquals(
                "TileType[${expected[i].first}] rect",
                expected[i].third.toList(), actual[i].third.toList()
            )
        }
    }

    @Test
    fun `瓦片分类常量 - 装饰区间连续且显示尺寸与绘制层与 TileType 一一对应`() {
        // 渲染器（Vulkan C++ + Canvas）以「装饰区间 + 显示尺寸 + 绘制层」驱动装饰叠加层——
        // 区间不连续或尺寸/层表错位会导致装饰漏画/错位/层序颠倒
        //（生成源：LAYOUT.tiles kind/sprite/layer）
        val tiles = parseTileTypes(source())
        assertEquals(
            "装饰区间下界应为 GRASS1（草/石/树连续排列在 GROUND 与 TILE_BUILDING 之间）",
            tiles.first { it.first == "GRASS1" }.second, SpriteAtlasDef.DECOR_TILE_MIN_INDEX
        )
        assertEquals(
            "装饰区间上界应为 TREE2",
            tiles.first { it.first == "TREE2" }.second, SpriteAtlasDef.DECOR_TILE_MAX_INDEX
        )
        // 区间内每一格都是装饰瓦片，区间外（GROUND/TILE_BUILDING）不是
        for (idx in SpriteAtlasDef.DECOR_TILE_MIN_INDEX..SpriteAtlasDef.DECOR_TILE_MAX_INDEX) {
            assertTrue("index=$idx 应判定为装饰瓦片", SpriteAtlasDef.isDecorTile(idx))
        }
        assertFalse("GROUND 不应判定为装饰瓦片", SpriteAtlasDef.isDecorTile(0))
        assertFalse("TILE_BUILDING 不应判定为装饰瓦片",
            SpriteAtlasDef.isDecorTile(SpriteAtlasDef.TileType.TILE_BUILDING.index))
        // 显示尺寸（格，小数格）与绘制层：立体素材按屏上不变形取值（草/石 ~1 格宽、
        // 树 2 格宽且更高），树为立体层（与建筑同序归并绘制）
        val expectedSprite = mapOf(
            "GROUND" to (1.0f to 1.0f),
            "GRASS1" to (1.0f to 1.157f),
            "GRASS2" to (1.0f to 1.064f),
            "GRASS3" to (1.0f to 1.157f),
            "GRASS4" to (1.0f to 1.42f),
            "STONE1" to (1.0f to 0.959f),
            "STONE2" to (1.0f to 0.909f),
            "STONE3" to (1.0f to 1.105f),
            "TREE1" to (2.0f to 3.291f),
            "TREE2" to (2.0f to 2.791f),
            "TILE_BUILDING" to (1.0f to 1.0f),
        )
        for (tile in tiles) {
            val expected = expectedSprite[tile.first]
                ?: throw AssertionError("新增瓦片 ${tile.first} 未在本测试期望表登记（LAYOUT.tiles 变更需同步）")
            assertEquals(
                "瓦片 ${tile.first} 显示宽与期望不一致（LAYOUT.tiles sprite[0]）",
                expected.first, SpriteAtlasDef.tileSpriteWidth(tile.second)
            )
            assertEquals(
                "瓦片 ${tile.first} 显示高与期望不一致（LAYOUT.tiles sprite[1]）",
                expected.second, SpriteAtlasDef.tileSpriteHeight(tile.second)
            )
            val expectedObjectLayer = tile.first.startsWith("TREE")
            assertEquals(
                "瓦片 ${tile.first} 绘制层判定与期望不一致（LAYOUT.tiles layer：树应走立体层）",
                expectedObjectLayer, SpriteAtlasDef.isObjectDecorTile(tile.second)
            )
        }
        assertEquals("越界索引应回退 1 格宽", 1f, SpriteAtlasDef.tileSpriteWidth(999))
        assertEquals("越界索引应回退 1 格高", 1f, SpriteAtlasDef.tileSpriteHeight(999))
        assertFalse("越界索引不应判定为立体层", SpriteAtlasDef.isObjectDecorTile(999))
        // 实体障碍：石/树/建筑占位（autotile 跳过），草与地面不是
        for (tile in tiles) {
            val expectedSolid = tile.first.startsWith("STONE") || tile.first.startsWith("TREE") ||
                tile.first == "TILE_BUILDING"
            assertEquals(
                "瓦片 ${tile.first} 实体障碍判定与期望不一致（LAYOUT.tiles solid）",
                expectedSolid, SpriteAtlasDef.isSolidTile(tile.second)
            )
        }
    }

    @Test
    fun `装饰越界余量与显示尺寸表自洽`() {
        // 越界余量必须与显示尺寸表自洽（渲染遍历/可见性范围按此扩展——不一致会裁掉树冠）
        val maxW = SpriteAtlasDef.TILE_SPRITE_W
            .slice(SpriteAtlasDef.DECOR_TILE_MIN_INDEX..SpriteAtlasDef.DECOR_TILE_MAX_INDEX).max()
        val maxH = SpriteAtlasDef.TILE_SPRITE_H
            .slice(SpriteAtlasDef.DECOR_TILE_MIN_INDEX..SpriteAtlasDef.DECOR_TILE_MAX_INDEX).max()
        assertEquals("DECOR_MAX_SPRITE_W 与尺寸表不一致", maxW, SpriteAtlasDef.DECOR_MAX_SPRITE_W)
        assertEquals("DECOR_MAX_SPRITE_H 与尺寸表不一致", maxH, SpriteAtlasDef.DECOR_MAX_SPRITE_H)
        assertEquals(
            "DECOR_MARGIN_COLS 应为 ceil((maxW−1)/2)",
            kotlin.math.ceil(((maxW - 1f) / 2f).toDouble()).toInt(),
            SpriteAtlasDef.DECOR_MARGIN_COLS
        )
        assertEquals(
            "DECOR_MARGIN_ROWS 应为 ceil(maxH−1)",
            kotlin.math.ceil((maxH - 1f).toDouble()).toInt(),
            SpriteAtlasDef.DECOR_MARGIN_ROWS
        )
        // 树（最高装饰）必须走立体层，否则 chunk 缝/遮挡语义失效
        assertTrue(
            "树瓦片必须判定为立体层（LAYOUT.tiles layer=object）",
            SpriteAtlasDef.isObjectDecorTile(SpriteAtlasDef.TileType.TREE1.index) &&
                SpriteAtlasDef.isObjectDecorTile(SpriteAtlasDef.TileType.TREE2.index)
        )
    }

    @Test
    fun `STRUCTURES 固定结构与期望全等`() {
        val expected = listOf(
            StructureDef(
                "宗门门楼", "sect_gate", intArrayOf(3072, 512, 768, 256), 6, 2, 6, 2
            ),
        )
        val actual = parseStructures(source())
        assertEquals(
            "STRUCTURES 数量与期望不一致——修改 LAYOUT.structures 后需同步本测试期望",
            expected.size, actual.size
        )
        for (i in expected.indices) {
            assertEquals("STRUCTURES[$i] 名称", expected[i].name, actual[i].name)
            assertEquals("STRUCTURES[${expected[i].name}] key", expected[i].key, actual[i].key)
            assertEquals(
                "STRUCTURES[${expected[i].name}] rect",
                expected[i].rect.toList(), actual[i].rect.toList()
            )
            assertEquals(
                "STRUCTURES[${expected[i].name}] footprint",
                listOf(expected[i].fpW, expected[i].fpH),
                listOf(actual[i].fpW, actual[i].fpH)
            )
            assertEquals(
                "STRUCTURES[${expected[i].name}] sprite",
                listOf(expected[i].sw, expected[i].sh),
                listOf(actual[i].sw, actual[i].sh)
            )
        }
    }

    @Test
    fun `GROUND_VARIANT_INDICES 与期望全等`() {
        val src = source()
        assertTrue("生成物缺少 GROUND_VARIANT_INDICES", src.contains("GROUND_VARIANT_INDICES"))
        assertEquals(
            "GROUND_VARIANT_INDICES 与期望不一致",
            listOf(0),
            parseGroundVariantIndices(src)
        )
    }

    @Test
    fun `BUILDING_NAMES 19 个名称与期望全等`() {
        val expected = listOf(
            "灵矿场", "灵植阁", "灵田", "炼丹炉", "锻造坊",
            "仓库", "藏经阁", "问道塔", "青云塔", "天枢殿",
            "执法堂", "任务阁", "巡视楼", "监牢",
            "单人住所", "中级单人住所", "多人住所", "血炼池", "中级多人住所",
        )
        val actual = parseBuildingNames(source())
        assertEquals(
            "BUILDING_NAMES 数量与期望不一致——修改 LAYOUT.buildingNames 后需同步本测试期望",
            expected.size, actual.size
        )
        assertEquals("BUILDING_NAMES 顺序/名称与期望不一致", expected, actual)
    }

    @Test
    fun `FOOTPRINT_BY_NAME_INDEX 19 对占地尺寸与期望全等`() {
        // 占地 = 建筑底座：宽 = 精灵宽（左右不压盖）、深 = 底座进深
        //（塔/亭 2 格、池 3 格、院落/山丘/殿保持现状）
        val expected = listOf(
            intArrayOf(4, 4), intArrayOf(4, 3), intArrayOf(1, 1), intArrayOf(4, 2), intArrayOf(5, 3),
            intArrayOf(6, 4), intArrayOf(6, 3), intArrayOf(4, 2), intArrayOf(4, 2), intArrayOf(18, 13),
            intArrayOf(6, 3), intArrayOf(4, 3), intArrayOf(4, 2), intArrayOf(4, 4), intArrayOf(4, 4),
            intArrayOf(6, 6), intArrayOf(6, 4), intArrayOf(4, 3), intArrayOf(6, 5),
        )
        val actual = parseFootprints(source())
        assertEquals(
            "FOOTPRINT 数量与期望不一致——修改 LAYOUT.footprints 后需同步本测试期望与 C++ 侧",
            expected.size, actual.size
        )
        for (i in expected.indices) {
            assertEquals("FOOTPRINT[$i]", expected[i].toList(), actual[i].toList())
        }
    }

    @Test
    fun `CropStage 3 个枚举值及 rect 与期望全等`() {
        val expected = listOf(
            Pair("SEEDLING", intArrayOf(1384, 0, 128, 128)),
            Pair("GROWING", intArrayOf(1520, 0, 128, 128)),
            Pair("MATURE", intArrayOf(1656, 0, 128, 128)),
        )
        val actual = parseCropStages(source())
        assertEquals(
            "CropStage 枚举值数量与期望不一致——修改 LAYOUT.crops 后需同步本测试期望",
            expected.size, actual.size
        )
        for (i in expected.indices) {
            assertEquals("CropStage[${expected[i].first}] 名称", expected[i].first, actual[i].first)
            assertEquals("CropStage[${expected[i].first}] rect", expected[i].second.toList(), actual[i].second.toList())
        }
    }

    @Test
    fun `CLOUD_RECTS 5 个云层及 rect 与期望全等`() {
        val expected = listOf(
            Pair("cloud_1", intArrayOf(0, 2816, 968, 240)),
            Pair("cloud_2", intArrayOf(968, 2816, 904, 376)),
            // cloud_3 落 x≥3088 空带：原 (1872,2816) 与浮空岛左右环精灵实体重叠
            Pair("cloud_3", intArrayOf(3088, 2816, 976, 192)),
            Pair("cloud_4", intArrayOf(0, 3240, 1048, 216)),
            Pair("cloud_5", intArrayOf(1048, 3240, 944, 400)),
        )
        val actual = parseCloudRects(source())
        assertEquals(
            "CLOUD_RECTS 数量与期望不一致——修改 LAYOUT.clouds 后需同步本测试期望",
            expected.size, actual.size
        )
        for (i in expected.indices) {
            assertEquals("CLOUD_RECTS[${expected[i].first}] 名称", expected[i].first, actual[i].first)
            assertEquals(
                "CLOUD_RECTS[${expected[i].first}] rect",
                expected[i].second.toList(),
                actual[i].second.toList()
            )
        }
        // 图集边界守卫：云层 rect 必须在 2048×2048 图集内（防 LAYOUT 手改越界）
        val insideAtlas = actual.all { (_, r) ->
            r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= SpriteAtlasDef.ATLAS_W && r[1] + r[3] <= SpriteAtlasDef.ATLAS_H
        }
        assertTrue("云层 rect 超出图集范围", insideAtlas)
    }

    @Test
    fun `天枢殿使用专属 1024x1024 高清槽位`() {
        // 天枢殿显示 18×15 格（≈576×480 世界像素），3x 放大时
        // 512 槽位仍 ~3.8x 上采样——buildingRectOverrides 分配 1024×1024 专属槽位。
        // gutter 版布局：移至 (3008,1032)（与门楼 y 间距 8px）
        val idx = SpriteAtlasDef.BUILDING_NAME_INDEX["天枢殿"]
            ?: throw AssertionError("天枢殿未在图集 BUILDING_NAMES 中注册")
        assertEquals(
            "天枢殿槽位 rect 与期望不一致——修改 buildingRectOverrides 后需同步本测试期望",
            SpriteRect(3008, 1032, 1024, 1024),
            SpriteAtlasDef.buildingRect(idx)
        )
        // 其他建筑仍走行公式 512×512 槽位（节距 520 含 gutter）
        assertEquals(SpriteRect(0, 512, 512, 512), SpriteAtlasDef.buildingRect(0))
        assertEquals(SpriteRect(520, 512, 512, 512), SpriteAtlasDef.buildingRect(1))
    }

    @Test
    fun `生成物不含死代码命令类`() {
        val src = source()
        assertFalse("死代码类 FrameDrawCommand 不应出现在生成物中", src.contains("FrameDrawCommand"))
        assertFalse("死代码类 BuildingDrawCmd 不应出现在生成物中", src.contains("BuildingDrawCmd"))
        assertFalse("死代码类 PreviewDrawCmd 不应出现在生成物中", src.contains("PreviewDrawCmd"))
    }

    private fun extractConst(src: String, name: String): String {
        val regex = Regex("""const val $name = (\d+)""")
        return regex.find(src)?.groupValues?.get(1)
            ?: throw AssertionError("生成物中未找到 const val $name")
    }

    private fun parseTileTypes(src: String): List<Triple<String, Int, IntArray>> {
        // 行尾可带逗号（非末条）或分号（末条后接 companion object），或不带（末条无成员）
        val regex = Regex(
            """^\s{8}(\w+)\((\d+), SpriteRect\(""" +
                """(\d+), (\d+), (\d+), (\d+)\)\)[,;]?$""",
            RegexOption.MULTILINE
        )
        return regex.findAll(src).map { m ->
            Triple(
                m.groupValues[1],
                m.groupValues[2].toInt(),
                intArrayOf(
                    m.groupValues[3].toInt(), m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(), m.groupValues[6].toInt()
                )
            )
        }.toList()
    }

    private fun parseBuildingNames(src: String): List<String> {
        val decl = src.indexOf("BUILDING_NAMES = listOf(")
        val end = src.indexOf(")", decl)
        val regex = Regex(""""([^"]+)"""")
        return regex.findAll(src.substring(decl, end)).map { it.groupValues[1] }.toList()
    }

    private fun parseFootprints(src: String): List<IntArray> {
        val decl = src.indexOf("FOOTPRINT_BY_NAME_INDEX")
        val end = src.indexOf(")", decl)
        val regex = Regex("""(\d+) to (\d+)""")
        return regex.findAll(src.substring(decl, end)).map { m ->
            intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }.toList()
    }

    private fun parseCropStages(src: String): List<Pair<String, IntArray>> {
        val regex = Regex("""^\s{8}(\w+)\(SpriteRect\((\d+), (\d+), (\d+), (\d+)\)\)[,;]?$""", RegexOption.MULTILINE)
        return regex.findAll(src).map { m ->
            Pair(
                m.groupValues[1],
                intArrayOf(
                    m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt()
                )
            )
        }.toList()
    }

    private fun parseCloudRects(src: String): List<Pair<String, IntArray>> {
        // 限定在 CLOUD_RECTS 块内解析——ROAD_RECTS 同为 "name" to SpriteRect(...) 格式，
        // 不限定会把道路条目误计入云层。
        val decl = src.indexOf("val CLOUD_RECTS")
        if (decl < 0) throw AssertionError("生成物中未找到 CLOUD_RECTS")
        // 以 ROAD_RECTS 声明为界截取 CLOUD_RECTS 块（CLOUD_RECTS 在 ROAD_RECTS 之前声明）
        val end = src.indexOf("val ROAD_RECTS", decl).let { if (it < 0) src.length else it }
        val block = src.substring(decl, end)
        val regex = Regex(""""(\w+)" to SpriteRect\((\d+), (\d+), (\d+), (\d+)\)[,]?$""", RegexOption.MULTILINE)
        return regex.findAll(block).map { m ->
            Pair(
                m.groupValues[1],
                intArrayOf(
                    m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt()
                )
            )
        }.toList()
    }

    private fun parseRoadRects(src: String): List<Pair<String, IntArray>> {
        // 限定在 ROAD_RECTS 块内解析（该块在文件尾部，以 ROAD_UV_MAP 声明为界）——
        // "name" to SpriteRect(...) 格式与 CLOUD_RECTS 相同，不限定会把云层条目误计入。
        val decl = src.indexOf("val ROAD_RECTS")
        if (decl < 0) throw AssertionError("生成物中未找到 ROAD_RECTS")
        val end = src.indexOf("val ROAD_UV_MAP", decl).let { if (it < 0) src.length else it }
        val block = src.substring(decl, end)
        val regex = Regex(""""(\w+)" to SpriteRect\((\d+), (\d+), (\d+), (\d+)\)[,]?$""", RegexOption.MULTILINE)
        return regex.findAll(block).map { m ->
            Pair(
                m.groupValues[1],
                intArrayOf(
                    m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt()
                )
            )
        }.toList()
    }

    private fun parseStructures(src: String): List<StructureDef> {
        val regex = Regex(
            """^\s{8}StructureDef\("([^"]+)", "([^"]+)", SpriteRect\(""" +
                """(\d+), (\d+), (\d+), (\d+)\), (\d+), (\d+), (\d+), (\d+)\)[,]?$""",
            RegexOption.MULTILINE
        )
        return regex.findAll(src).map { m ->
            StructureDef(
                name = m.groupValues[1],
                key = m.groupValues[2],
                rect = intArrayOf(
                    m.groupValues[3].toInt(), m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(), m.groupValues[6].toInt()
                ),
                fpW = m.groupValues[7].toInt(),
                fpH = m.groupValues[8].toInt(),
                sw = m.groupValues[9].toInt(),
                sh = m.groupValues[10].toInt()
            )
        }.toList()
    }

    private fun parseGroundVariantIndices(src: String): List<Int> {
        val regex = Regex("""GROUND_VARIANT_INDICES = intArrayOf\(([^)]*)\)""")
        val body = regex.find(src)?.groupValues?.get(1)
            ?: throw AssertionError("生成物中未找到 GROUND_VARIANT_INDICES")
        return body.split(",").map { it.trim().toInt() }
    }

    @Test
    fun `道路精灵名与合成器 SPRITE_KEYS 序一致（计划 v2 阶段 6 三端映射锚点）`() {
        // RoadCompositorBridge.SPRITE_KEYS 下标 = C++ RoadSprite 枚举序
        //（GTest road_compositor_test.SpriteEnumOrderIsContractAnchor 守护）
        // = ROAD_RECTS 声明序（roadUVMap 索引同序）——顺序漂移即三端错位。
        val atlasKeys = SpriteAtlasDef.ROAD_RECTS.map { it.first }
        assertEquals(atlasKeys, RoadCompositorBridge.SPRITE_KEYS.toList())
        // 枚举数与单格最大操作数（分别等于 C++ kRoadSpriteCount=3 / kMaxRoadDrawOpsPerTile=6，
        // 由 GTest road_compositor_test.SpriteEnumOrderIsContractAnchor 守护）
        assertEquals(3, RoadCompositorBridge.SPRITE_KEYS.size)
        assertEquals(6, RoadCompositorBridge.MAX_OPS_PER_TILE)
    }

    @Test
    fun `道路槽位与瓦片同显示分辨率 - 防深度降采样缩放跳变回归`() {
        // 渲染不变量：道路主体显示为 1×1 格（48px），
        // 图集槽位必须与瓦片/作物同比例（128 槽位 ↔ 48 显示 = 2.67:1×）。曾用 768×768
        //（16:1× 深度降采样）：缩放范围 [0.3, 3.0] 内 mip 跨越 3+ 级，道路观感在
        // "只有几条模糊线框" ↔ "满格石板细节"间剧烈跳变（视觉上似闪烁）；与瓦片
        // 同比例后道路与地面行为一致。槽位再放大即回归本缺陷。
        val roadRects = parseRoadRects(source())
        fun rectOf(name: String) = roadRects.firstOrNull { it.first == name }
            ?: throw AssertionError("ROAD_RECTS 缺少 $name——修改 LAYOUT.roads 后需同步本测试")
        val body = rectOf("road_body")
        val edgeV = rectOf("road_edge_v")
        val edgeH = rectOf("road_edge_h")

        // 主体：与瓦片标准槽位（GROUND 128×128）全等
        val tileRect = SpriteAtlasDef.TileType.GROUND.rect
        assertEquals("road_body 槽位宽应等于瓦片标准 ${tileRect.w}（48px 显示格同比例）",
            tileRect.w, body.second[2])
        assertEquals("road_body 槽位高应等于瓦片标准 ${tileRect.h}（48px 显示格同比例）",
            tileRect.h, body.second[3])

        // 边缘条：8×24 显示片段 → 32×96（4:1×，保持源图 1:3 纵横比）；超限即回归
        assertEquals("road_edge_v 槽位应为 32×96（8×24 显示片段 4:1×，原 192×576 属 24:1× 深度降采样）",
            listOf(32, 96), edgeV.second.toList().takeLast(2))
        assertEquals("road_edge_h 槽位应为 96×32（8×24 显示片段 4:1×，原 576×192 属 24:1× 深度降采样）",
            listOf(96, 32), edgeH.second.toList().takeLast(2))
    }

    @Test
    fun `地图精灵槽位与显示尺寸同比例 - 深度降采样防回归（道路与灵田类问题全地图排查）`() {
        // 渲染不变量（缩放跳变防回归的推广）：地图精灵的图集槽位边长不得超过
        // 最大显示尺寸 × 4——警戒线依据：原道路 16:1×、原灵田 10.7:1× 超出后产生
        // "模糊线框 ↔ 满格细节"缩放跳变；现状瓦片/道路/灵田 2.67:1×、4×3 建筑
        // 3.56:1×（内容低频 + 放大 3× 即近原生，无跳变观感）、天枢殿 1.19:1× 均在线内。
        // 超限即：缩放时 mip 跨越 3+ 级，观感"模糊线框 ↔ 满格细节"跳变（视觉上似闪烁）。
        // 云层除外：按原生宽度 ×(0.5~1.5) 随机比例显示，恒 ≤1.5:1×（CloudLayerAnimator）。
        val displayCell = GameConfig.SectMap.TILE_SIZE
        val maxRatio = 4.0f

        fun assertSlot(name: String, slot: IntArray, dispW: Int, dispH: Int) {
            assertTrue(
                "$name 槽位 ${slot[2]}×${slot[3]} 超过显示 ${dispW}×${dispH} 的 $maxRatio 倍" +
                    "（深度降采样缩放跳变隐患）——应在 LAYOUT 按显示尺寸收敛槽位",
                slot[2] <= (dispW * maxRatio).toInt() && slot[3] <= (dispH * maxRatio).toInt()
            )
        }

        // 瓦片/树/石（按 LAYOUT.tiles 显示尺寸——小数格，锚点 = 格底边居中）
        for (tile in SpriteAtlasDef.TileType.values()) {
            if (tile == SpriteAtlasDef.TileType.TILE_BUILDING) continue // 占位（与 GROUND 重叠）
            val displayW = (SpriteAtlasDef.tileSpriteWidth(tile.index) * displayCell).toInt()
            val displayH = (SpriteAtlasDef.tileSpriteHeight(tile.index) * displayCell).toInt()
            assertSlot(tile.name, tile.rect.toArray(), displayW, displayH)
        }
        // 灵田作物三阶段（1×1 格显示）
        for (crop in SpriteAtlasDef.CropStage.values()) {
            assertSlot(crop.name, crop.rect.toArray(), displayCell, displayCell)
        }
        // 建筑：槽位比按**精灵显示尺寸**判定（需要配置尺寸），
        // 由 app 模块 SpriteSizingFidelityTest 覆盖（本测试只锁 codegen 自洽数据）
        // 固定结构（门楼：按 spriteSize 显示——精灵高于占地）
        for (s in SpriteAtlasDef.STRUCTURES) {
            assertSlot(
                s.key, s.rect.toArray(),
                s.spriteW * displayCell, s.spriteH * displayCell
            )
        }
        // 道路（与道路专项守卫互补：此规则只卡上限，边界几何由专项守卫锁死）
        for ((name, r) in SpriteAtlasDef.ROAD_RECTS) {
            assertSlot(name, r.toArray(), displayCell, displayCell)
        }
    }

    private fun SpriteRect.toArray() = intArrayOf(x, y, w, h)

    @Test
    fun `图集布局 gutter 守卫 - 全部槽位在图集内且两两间距不低于 8px`() {
        // 渲染不变量（渲染纹理降采样管线统一改版 §2.1）：per-sprite 独立 mip
        // + pad 环依赖槽位间距——任一相邻槽位间距 ≥8px（mip0 尺度，左右 pad 各 4px
        // 恰好铺满 gutter），mip ≥1 级采样边界才不会混入相邻精灵内容。
        // 云层豁免间距校验（远背景半透明、mip 均值即可，无 gutter 强制），但**必须**
        // 在图集边界内。TILE_BUILDING 为 GROUND 同 rect 占位（drawable=null），豁免。
        val entries = collectLayoutEntries()
        val gutterExempt = setOf("TILE_BUILDING", *SpriteAtlasDef.CLOUD_RECTS.map { it.first }.toTypedArray())

        // 校验一：全部槽位在 4096² 图集内
        for ((name, r) in entries) {
            assertTrue(
                "$name rect($r) 越出图集边界（ATLAS ${SpriteAtlasDef.ATLAS_W}×${SpriteAtlasDef.ATLAS_H}）" +
                    "——LAYOUT 重排后须整体在界内",
                r[0] >= 0 && r[1] >= 0 &&
                    r[0] + r[2] <= SpriteAtlasDef.ATLAS_W && r[1] + r[3] <= SpriteAtlasDef.ATLAS_H
            )
        }

        // 校验二：两两间距 ≥8（水平或垂直双向间隙均须 ≥8；仅校验投影相交的对，
        // 即水平区间或垂直区间有重叠的对——对角无关对不采光）
        val minGutter = 8
        val solid = entries.filter { it.first !in gutterExempt }
        for (i in solid.indices) {
            for (j in i + 1 until solid.size) {
                val (n1, r1) = solid[i]
                val (n2, r2) = solid[j]
                val xOverlap = r1[0] < r2[0] + r2[2] && r2[0] < r1[0] + r1[2]
                val yOverlap = r1[1] < r2[1] + r2[3] && r2[1] < r1[1] + r1[3]
                if (!xOverlap && !yOverlap) continue // 对角无关对
                val gapX = if (xOverlap) 0 else maxOf(r2[0] - (r1[0] + r1[2]), r1[0] - (r2[0] + r2[2]))
                val gapY = if (yOverlap) 0 else maxOf(r2[1] - (r1[1] + r1[3]), r1[1] - (r2[1] + r2[3]))
                val gap = maxOf(gapX, gapY)
                assertTrue(
                    "$n1 与 $n2 间距 $gap < $minGutter px——per-sprite mip 的 pad 环" +
                        "（左右各 4px）需 gutter 铺满，去 build-atlas.mjs LAYOUT 调整槽位坐标",
                    gap >= minGutter
                )
            }
        }
    }

    @Test
    fun `图集布局实体矩形互不重叠 - 云层与瓦片、建筑、岛边缘一律无例外`() {
        // 渲染不变量：任一槽位的**实体矩形**（不含 pad 环）都不得与任何其他槽位重叠——
        // 重叠即两张精灵在同一像素区互相覆盖（合成顺序决定谁赢，被压者的 pad 环/
        // 半透明边缘会混入对方的颜色，mip 各级都会带出脏边）。
        // 云层曾经豁免 gutter 校验，导致 cloud_3 (1872,2816,976,192) 压住浮空岛左右环
        // 三张精灵（x 2560..2931 / y 2624..3022）却无人报警——本守卫对云层同样生效，
        // 只豁免"间距"（半透明远背景不需要 gutter），不豁免"重叠"。
        val entries = collectLayoutEntries().filter { it.first != "TILE_BUILDING" }
        val overlaps = mutableListOf<String>()
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                val (n1, r1) = entries[i]
                val (n2, r2) = entries[j]
                val xOverlap = r1[0] < r2[0] + r2[2] && r2[0] < r1[0] + r1[2]
                val yOverlap = r1[1] < r2[1] + r2[3] && r2[1] < r1[1] + r1[3]
                if (xOverlap && yOverlap) {
                    overlaps += "$n1(${r1.toList()}) ∩ $n2(${r2.toList()})"
                }
            }
        }
        assertTrue(
            "图集存在实体矩形重叠（合成顺序决定谁被覆盖，另一半的 pad 环/半透明边会混入对方颜色）:\n" +
                overlaps.joinToString("\n") + "\n去 build-atlas.mjs LAYOUT 把槽位移到空带",
            overlaps.isEmpty()
        )
    }

    /** 汇集全部布局 rect（瓦片/建筑含 override/作物/结构/云层/道路/浮空岛边缘）供 gutter 守卫遍历。 */
    private fun collectLayoutEntries(): List<Pair<String, IntArray>> {
        val entries = mutableListOf<Pair<String, IntArray>>()
        for (tile in SpriteAtlasDef.TileType.values()) entries += tile.name to tile.rect.toArray()
        for (i in SpriteAtlasDef.BUILDING_NAMES.indices) {
            entries += SpriteAtlasDef.BUILDING_NAMES[i] to SpriteAtlasDef.buildingRect(i).toArray()
        }
        for (crop in SpriteAtlasDef.CropStage.values()) entries += crop.name to crop.rect.toArray()
        for (s in SpriteAtlasDef.STRUCTURES) entries += s.key to s.rect.toArray()
        for ((name, r) in SpriteAtlasDef.CLOUD_RECTS) entries += name to r.toArray()
        for ((name, r) in SpriteAtlasDef.ROAD_RECTS) entries += name to r.toArray()
        return entries
    }

    /** STRUCTURES 解析载体（期待值口径：rect 为绝对像素矩形，sw/sh 为精灵显示尺寸） */
    private data class StructureDef(
        val name: String,
        val key: String,
        val rect: IntArray,
        val fpW: Int,
        val fpH: Int,
        val sw: Int,
        val sh: Int,
    )
}

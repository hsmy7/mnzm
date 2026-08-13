package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 生成物一致性守卫（2026-08-13 资源管线 codegen）。
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
        assertEquals("ATLAS_W", "2048", extractConst(src, "ATLAS_W"))
        assertEquals("ATLAS_H", "2048", extractConst(src, "ATLAS_H"))
        assertEquals("TILE_SIZE", "64", extractConst(src, "TILE_SIZE"))
        assertEquals("BUILDING_SIZE", "128", extractConst(src, "BUILDING_SIZE"))
    }

    @Test
    fun `TileType 8 个枚举值及 rect 与期望全等`() {
        val expected = listOf(
            Triple("GROUND", 0, intArrayOf(0, 0, 64, 64)),
            Triple("GRASS_SMALL", 1, intArrayOf(64, 0, 64, 64)),
            Triple("GRASS_MEDIUM", 2, intArrayOf(128, 0, 64, 64)),
            Triple("GRASS_LARGE", 3, intArrayOf(192, 0, 64, 64)),
            Triple("TREE1", 4, intArrayOf(256, 0, 128, 128)),
            Triple("TREE2", 5, intArrayOf(384, 0, 128, 128)),
            Triple("TILE_BUILDING", 6, intArrayOf(0, 0, 64, 64)),
            Triple("GROUND_V2", 7, intArrayOf(512, 0, 64, 64)),
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
        val expected = listOf(
            intArrayOf(4, 4), intArrayOf(4, 3), intArrayOf(1, 1), intArrayOf(4, 3), intArrayOf(5, 3),
            intArrayOf(6, 4), intArrayOf(6, 3), intArrayOf(4, 3), intArrayOf(4, 3), intArrayOf(6, 3),
            intArrayOf(6, 3), intArrayOf(4, 3), intArrayOf(4, 3), intArrayOf(4, 4), intArrayOf(4, 4),
            intArrayOf(6, 6), intArrayOf(6, 4), intArrayOf(4, 4), intArrayOf(6, 5),
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
            Pair("SEEDLING", intArrayOf(832, 0, 64, 64)),
            Pair("GROWING", intArrayOf(896, 0, 64, 64)),
            Pair("MATURE", intArrayOf(960, 0, 64, 64)),
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
    fun `FloorTileType 5 个枚举值及 rect 与期望全等`() {
        val expected = listOf(
            Array5("TILE_2x2", "floor_tile_2x2", 2, 2, intArrayOf(0, 640, 128, 128)),
            Array5("TILE_2x3", "floor_tile_2x3", 2, 3, intArrayOf(0, 768, 128, 192)),
            Array5("TILE_3x2", "floor_tile_3x2", 3, 2, intArrayOf(0, 960, 192, 128)),
            Array5("TILE_3x3", "floor_tile_3x3", 3, 3, intArrayOf(192, 960, 192, 192)),
            Array5("SPIRIT_MINE_GROUND", "spirit_mine_ground", 4, 4, intArrayOf(0, 1152, 256, 256)),
        )
        val actual = parseFloorTiles(source())
        assertEquals(
            "FloorTileType 枚举值数量与期望不一致——修改 LAYOUT.floors 后需同步本测试期望",
            expected.size, actual.size
        )
        for (i in expected.indices) {
            assertEquals("FloorTileType[$i] 名称", expected[i].name, actual[i].name)
            assertEquals("FloorTileType[${expected[i].name}] key", expected[i].key, actual[i].key)
            assertEquals("FloorTileType[${expected[i].name}] gridW", expected[i].gridW, actual[i].gridW)
            assertEquals("FloorTileType[${expected[i].name}] gridH", expected[i].gridH, actual[i].gridH)
            assertEquals("FloorTileType[${expected[i].name}] rect", expected[i].rect.toList(), actual[i].rect.toList())
        }
    }

    @Test
    fun `生成物不含死代码命令类且保留复杂度抑制注解`() {
        val src = source()
        assertFalse("死代码类 FrameDrawCommand 不应出现在生成物中", src.contains("FrameDrawCommand"))
        assertFalse("死代码类 BuildingDrawCmd 不应出现在生成物中", src.contains("BuildingDrawCmd"))
        assertFalse("死代码类 PreviewDrawCmd 不应出现在生成物中", src.contains("PreviewDrawCmd"))
        assertTrue(
            "floorTileIndex 必须带 @Suppress(CyclomaticComplexMethod)——detekt baseline 路径失配会新增违规",
            src.contains("@Suppress(\"CyclomaticComplexMethod\")")
        )
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

    private fun parseFloorTiles(src: String): List<Array5> {
        val regex = Regex(
            """^\s{8}(\w+)\("([^"]+)", (\d+), (\d+), SpriteRect\(""" +
                """(\d+), (\d+), (\d+), (\d+)\)\)[,;]?$""",
            RegexOption.MULTILINE
        )
        return regex.findAll(src).map { m ->
            Array5(
                m.groupValues[1],
                m.groupValues[2],
                m.groupValues[3].toInt(),
                m.groupValues[4].toInt(),
                intArrayOf(
                    m.groupValues[5].toInt(), m.groupValues[6].toInt(),
                    m.groupValues[7].toInt(), m.groupValues[8].toInt()
                )
            )
        }.toList()
    }

    private data class Array5(
        val name: String,
        val key: String,
        val gridW: Int,
        val gridH: Int,
        val rect: IntArray,
    )
}

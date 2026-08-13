package com.xianxia.sect

import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.render.SpriteRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C++/Kotlin 图集布局同步守卫测试（2026-08-10 新增）。
 *
 * Kotlin 侧 `SpriteAtlasDef.kt` 是图集布局的唯一权威（SpriteAtlasDef.TileType/BUILDING_NAMES/
 * SpriteAtlasDef.FloorTileType 定义像素位置），C++ 侧 `TextureAtlas.h` 的 MAP_SPRITES 必须逐项一致——
 * 若有人只改一侧，本测试失败并提示同步位置。
 *
 * 守卫三要素：
 * 1. 以 SpriteAtlasDef（权威）为锚，遍历所有条目比对 C++ rect
 * 2. 反向遍历 C++ 条目，确保无孤儿（C++ 新增条目必须在 Kotlin 有映射）
 * 3. 故意排除项显式声明：TILE_BUILDING 与 GROUND 同 rect，C++ 无显式条目（Kotlin 兼容保留）
 */
class AtlasLayoutSyncTest {

    /** C++ MAP_SPRITES 条目（名称 + 像素矩形） */
    private data class CppEntry(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

    // 瓦片名称映射（C++ 名称 → Kotlin TileType）
    private val tileNameMap: Map<String, SpriteAtlasDef.TileType> = mapOf(
        "ground_tile" to SpriteAtlasDef.TileType.GROUND,
        "grass_small" to SpriteAtlasDef.TileType.GRASS_SMALL,
        "grass_medium" to SpriteAtlasDef.TileType.GRASS_MEDIUM,
        "grass_large" to SpriteAtlasDef.TileType.GRASS_LARGE,
        "tree1" to SpriteAtlasDef.TileType.TREE1,
        "tree2" to SpriteAtlasDef.TileType.TREE2,
        "ground_tile_v2" to SpriteAtlasDef.TileType.GROUND_V2
    )

    // 地砖名称映射（C++ 名称 → Kotlin FloorTileType）
    private val floorNameMap: Map<String, SpriteAtlasDef.FloorTileType> = mapOf(
        "floor_tile_2x2" to SpriteAtlasDef.FloorTileType.TILE_2x2,
        "floor_tile_2x3" to SpriteAtlasDef.FloorTileType.TILE_2x3,
        "floor_tile_3x2" to SpriteAtlasDef.FloorTileType.TILE_3x2,
        "floor_tile_3x3" to SpriteAtlasDef.FloorTileType.TILE_3x3,
        "spirit_mine_ground" to SpriteAtlasDef.FloorTileType.SPIRIT_MINE_GROUND
    )

    // 作物阶段名称映射（C++ 名称 → Kotlin CropStage，WP6）
    private val cropNameMap: Map<String, SpriteAtlasDef.CropStage> = mapOf(
        "crop_seedling" to SpriteAtlasDef.CropStage.SEEDLING,
        "crop_growing" to SpriteAtlasDef.CropStage.GROWING,
        "crop_mature" to SpriteAtlasDef.CropStage.MATURE
    )

    @Test
    fun `MAP_SPRITES 建筑与 BUILDING_NAMES 双向一致`() {
        val cpp = parseMapSprites()
        val cppByName = cpp.associateBy { it.name }

        // 正向：每个 Kotlin 建筑在 C++ 中存在且 rect 一致
        for (idx in SpriteAtlasDef.BUILDING_NAMES.indices) {
            val name = SpriteAtlasDef.BUILDING_NAMES[idx]
            val expected = SpriteAtlasDef.buildingRect(idx)
            val entry = cppByName[name]
                ?: throw AssertionError(
                    "建筑 '${name}' 未在 TextureAtlas.h MAP_SPRITES 中注册——" +
                        "新增/改名建筑必须同步 SpriteAtlasDef.BUILDING_NAMES 与 C++ MAP_SPRITES"
                )
            assertEquals(
                "建筑 '$name' 图集位置 C++=(${entry.x},${entry.y},${entry.w},${entry.h}) " +
                    "≠ Kotlin=(${expected.x},${expected.y},${expected.w},${expected.h})——" +
                    "修改图集布局必须两端同步",
                expected,
                cppEntryToRect(entry)
            )
        }

        // 反向：C++ 建筑条目（非瓦片/非地砖/非作物）必须是 BUILDING_NAMES 中的成员（无孤儿）
        val kotlinBuildingNames = SpriteAtlasDef.BUILDING_NAMES.toSet()
        val orphanBuildings = cpp
            .filter { it.name !in tileNameMap && it.name !in floorNameMap && it.name !in cropNameMap }
            .filter { it.name !in kotlinBuildingNames }
        assertTrue(
            "TextureAtlas.h MAP_SPRITES 存在孤儿建筑条目: ${orphanBuildings.map { it.name }}——" +
                "C++ 新增条目必须在 SpriteAtlasDef 中同步注册",
            orphanBuildings.isEmpty()
        )
    }

    @Test
    fun `MAP_SPRITES 瓦片与 TileType rect 一致`() {
        val cppByName = parseMapSprites().associateBy { it.name }

        for ((cppName, tile) in tileNameMap) {
            val entry = cppByName[cppName]
                ?: throw AssertionError("瓦片 '$cppName' 未在 TextureAtlas.h MAP_SPRITES 中注册")
            assertEquals(
                "瓦片 '$cppName' (${tile.name}) 图集位置 C++=(${entry.x},${entry.y},${entry.w},${entry.h}) " +
                    "≠ Kotlin=(${tile.rect.x},${tile.rect.y},${tile.rect.w},${tile.rect.h})",
                tile.rect,
                cppEntryToRect(entry)
            )
        }
    }

    @Test
    fun `MAP_SPRITES 地砖与 FloorTileType rect 一致`() {
        val cppByName = parseMapSprites().associateBy { it.name }

        for ((cppName, floor) in floorNameMap) {
            val entry = cppByName[cppName]
                ?: throw AssertionError("地砖 '$cppName' 未在 TextureAtlas.h MAP_SPRITES 中注册")
            assertEquals(
                "地砖 '$cppName' 图集位置 C++=(${entry.x},${entry.y},${entry.w},${entry.h}) " +
                    "≠ Kotlin=(${floor.pixelRect.x},${floor.pixelRect.y},${floor.pixelRect.w},${floor.pixelRect.h})",
                floor.pixelRect,
                cppEntryToRect(entry)
            )
        }
    }

    @Test
    fun `MAP_SPRITES 作物与 CropStage rect 一致`() {
        val cppByName = parseMapSprites().associateBy { it.name }

        for ((cppName, stage) in cropNameMap) {
            val entry = cppByName[cppName]
                ?: throw AssertionError("作物 '$cppName' 未在 TextureAtlas.h MAP_SPRITES 中注册——" +
                    "新增作物阶段必须同步 SpriteAtlasDef.CropStage 与 C++ MAP_SPRITES")
            assertEquals(
                "作物 '$cppName' (${stage.name}) 图集位置 C++=(${entry.x},${entry.y},${entry.w},${entry.h}) " +
                    "≠ Kotlin=(${stage.rect.x},${stage.rect.y},${stage.rect.w},${stage.rect.h})——" +
                    "修改图集布局必须两端同步",
                stage.rect,
                cppEntryToRect(entry)
            )
        }

        // 反向覆盖：Kotlin 全部作物阶段都应在 C++ 有映射
        val uncoveredCrops = SpriteAtlasDef.CropStage.values()
            .filter { it !in cropNameMap.values }
        assertTrue(
            "SpriteAtlasDef.CropStage 存在未在 C++ MAP_SPRITES 覆盖的阶段: $uncoveredCrops——" +
                "新增作物阶段必须同步 TextureAtlas.h",
            uncoveredCrops.isEmpty()
        )
    }

    @Test
    fun `MAP_SPRITES 无孤儿条目且 TileType 全部覆盖`() {
        val cpp = parseMapSprites()
        val coveredNames = tileNameMap.keys + floorNameMap.keys + cropNameMap.keys + SpriteAtlasDef.BUILDING_NAMES
        val orphans = cpp.filter { it.name !in coveredNames }
        assertTrue(
            "TextureAtlas.h MAP_SPRITES 存在无法映射的孤儿条目: ${orphans.map { it.name }}——" +
                "每条 C++ 图集条目都必须能在 SpriteAtlasDef 中找到对应（瓦片/建筑/地砖/作物）",
            orphans.isEmpty()
        )

        // 反向覆盖：Kotlin 全部瓦片类型（TILE_BUILDING 除外——与 GROUND 同 rect (0,0,64,64)，
        // C++ 无显式条目，Kotlin 兼容保留）都应在 C++ 有映射
        val intentionallyExcluded = setOf(SpriteAtlasDef.TileType.TILE_BUILDING)
        val uncovered = SpriteAtlasDef.TileType.values().filter {
            it !in intentionallyExcluded && it !in tileNameMap.values
        }
        assertTrue(
            "SpriteAtlasDef.TileType 存在未在 C++ MAP_SPRITES 覆盖的类型: $uncovered——" +
                "新增瓦片类型必须同步 TextureAtlas.h（TILE_BUILDING 除外，见上方注释）",
            uncovered.isEmpty()
        )
    }

    private fun cppEntryToRect(entry: CppEntry) =
        SpriteRect(entry.x, entry.y, entry.w, entry.h)

    /**
     * 解析 TextureAtlas.h 的 MAP_SPRITES 数组条目。
     * 格式：`{ "name", x, y, w, h },`，单行格式正则提取可靠。
     */
    private fun parseMapSprites(): List<CppEntry> {
        // 2026-08-13 起 TextureAtlas.h 为 build-atlas.mjs codegen 产物（不再手工维护于 src/main/cpp）
        val headerFile = File("build/generated/sprite/TextureAtlas.h")
        assertTrue(
            "TextureAtlas.h 不存在：${headerFile.absolutePath}——请先运行 codegen（generateSpriteCode 任务）",
            headerFile.exists()
        )
        val source = headerFile.readText()

        // 只解析 MAP_SPRITES 数组区间内的条目（排除类内其他 SpriteDef 数组）
        val arrayMatch = Regex(
            """MAP_SPRITES\[\]\s*=\s*\{(.*?)\n\};""",
            RegexOption.DOT_MATCHES_ALL
        ).find(source)
            ?: throw AssertionError("TextureAtlas.h 中未找到 MAP_SPRITES[] 数组")

        val entryRegex = Regex("""\{\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\}""")
        return entryRegex.findAll(arrayMatch.groupValues[1]).map { m ->
            CppEntry(
                name = m.groupValues[1],
                x = m.groupValues[2].toInt(),
                y = m.groupValues[3].toInt(),
                w = m.groupValues[4].toInt(),
                h = m.groupValues[5].toInt()
            )
        }.toList()
    }
}

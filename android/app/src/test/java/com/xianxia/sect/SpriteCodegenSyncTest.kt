package com.xianxia.sect

import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 生成物同步守卫（资源管线 codegen）。
 *
 * 解析 `build-atlas.mjs --codegen` 生成的 SpriteRegistryData.kt 与 TextureAtlas.h 文本，
 * 断言结构与关键条目与期望全等（期望值复制自原手工版）。
 * 守卫价值：手工修改生成物、LAYOUT/注册源数据与消费侧漂移、R 引用规则破坏时变红。
 */
class SpriteCodegenSyncTest {

    private val registryFile =
        File("build/generated/sprite/com/xianxia/sect/SpriteRegistryData.kt")
    private val headerFile =
        File("build/generated/sprite/TextureAtlas.h")

    private fun registrySource(): String {
        assertTrue(
            "生成文件不存在: ${registryFile.absolutePath}——请运行 ./gradlew generateSpriteCode",
            registryFile.exists()
        )
        return registryFile.readText()
    }

    private fun headerSource(): String {
        assertTrue(
            "生成文件不存在: ${headerFile.absolutePath}——请运行 ./gradlew generateSpriteCode",
            headerFile.exists()
        )
        return headerFile.readText()
    }

    @Test
    fun `SpriteRegistryData 结构 - package 与注册入口签名与期望一致`() {
        val src = registrySource()
        assertTrue("必须位于 com.xianxia.sect 包", src.startsWith("package com.xianxia.sect"))
        assertTrue(
            "registerAllSprites() 签名必须是 internal fun（XianxiaApplication 无感调用）",
            src.contains("internal fun registerAllSprites()")
        )
        assertTrue(
            "ALLEQUIPMENTRESIDS 必须派生自 EQUIPMENT 分类（values 顺序 = 注册顺序，语义等价手工版）",
            src.contains("internal val ALLEQUIPMENTRESIDS: List<Int> = SPRITES_EQUIPMENT.values.toList()")
        )
    }

    @Test
    fun `SpriteRegistryData 结构 - 14 个分类全部注册且名称配对`() {
        val expectedCategories = listOf(
            "EQUIPMENT", "MANUAL", "PILL", "SPIRIT_STONE", "MATERIAL", "STORAGE_BAG",
            "SECT_ICON", "ITEM", "UI", "BEAST", "CAVE", "HEAVENLY_TRIAL", "BACKGROUND", "PORTRAIT",
        )
        val src = registrySource()
        for (category in expectedCategories) {
            assertTrue(
                "缺少分类注册行: SpriteCategory.$category —— resource-registry.json 分类变更后必须同步本测试期望",
                src.contains("SpriteResRegistry.register(SpriteCategory.$category, SPRITES_$category)")
            )
        }
        val registerCalls = Regex("""SpriteResRegistry\.register\(SpriteCategory\.(\w+)""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "register 调用数量与期望不一致（多注册/漏注册都会破坏预加载分类语义）",
            expectedCategories.size, registerCalls.size
        )
        assertEquals("register 分类顺序与期望不一致", expectedCategories, registerCalls)
    }

    @Test
    fun `SpriteRegistryData 数据 - 装备映射与期望一致`() {
        val src = registrySource()
        assertTrue(
            "精铁剑必须映射 R.drawable.jing_tie_jian（app 模块有副本 → 用 app R）",
            src.contains("\"精铁剑\" to R.drawable.jing_tie_jian")
        )
        assertTrue(
            "功法必须映射 manual_ 前缀键（manualSpriteRes(rarity) 查询协议；1=凡阶→manual_fan_ling，凡/灵品共用单图）",
            src.contains("\"manual_1\" to R.drawable.manual_fan_ling")
        )
        assertTrue(
            "丹药必须映射 pill_ 前缀键（pillSpriteRes(rarity) 查询协议；1=凡阶→pill_fan）",
            src.contains("\"pill_1\" to R.drawable.pill_fan")
        )
        assertTrue(
            "灵石必须映射 spirit_stone_ 前缀键（spiritStoneSpriteRes(grade) 查询协议）",
            src.contains("\"spirit_stone_low\" to R.drawable.spirit_stone_low")
        )
        assertTrue(
            "储物袋必须映射 bag_ 前缀键（storageBagSpriteRes(rarity) 查询协议；1=凡阶→bag_fan）",
            src.contains("\"bag_1\" to R.drawable.bag_fan")
        )
        assertTrue(
            "宗门图标必须映射 sect_icon_ 前缀键（sectIconRes(level) 查询协议；0=小→sect_icon_small）",
            src.contains("\"sect_icon_0\" to R.drawable.sect_icon_small")
        )
    }

    @Test
    fun `TextureAtlas 头 - 常量与期望数值全等`() {
        val src = headerSource()
        for (line in listOf(
            "#define TILE_SIZE 128",
            "#define TREE_SIZE 256",
            "#define ATLAS_W 4096",
            "#define ATLAS_H 4096",
            "#define BUILDING_W 512",
            "#define BUILDING_H 512",
        )) {
            assertTrue("TextureAtlas.h 缺少常量行: $line", src.contains(line))
        }
    }

    @Test
    fun `TextureAtlas 头 - 双端共享渲染常量与 SpriteAtlasDef 全等`() {
        val src = headerSource()
        // 生成头文本数值 ↔ 编译产物 SpriteAtlasDef 常量逐项全等
        //（单一数据源为 SpriteAtlasDef.LAYOUT，头文件常量由 codegen 生成）
        for ((define, expected) in listOf(
            "DECOR_QUALITY_THRESHOLD" to SpriteAtlasDef.DECOR_QUALITY_THRESHOLD.toString(),
            "SHADOW_OFFSET_TILES" to SpriteAtlasDef.SHADOW_OFFSET_TILES.toString(),
            "SHADOW_ALPHA" to SpriteAtlasDef.SHADOW_ALPHA.toString(),
            "TOPDOWN_Y_SCALE" to SpriteAtlasDef.TOPDOWN_Y_SCALE.toString(),
            "SPIRIT_MINE_NAME_INDEX" to SpriteAtlasDef.SPIRIT_MINE_NAME_INDEX.toString(),
            "SPIRIT_FIELD_NAME_INDEX" to SpriteAtlasDef.SPIRIT_FIELD_NAME_INDEX.toString(),
            "TILE_GROUND" to SpriteAtlasDef.TILE_GROUND_INDEX.toString(),
            "TILE_BUILDING" to SpriteAtlasDef.TILE_BUILDING_INDEX.toString(),
            "DECOR_TILE_MIN" to SpriteAtlasDef.DECOR_TILE_MIN_INDEX.toString(),
            "DECOR_TILE_MAX" to SpriteAtlasDef.DECOR_TILE_MAX_INDEX.toString(),
            "DECOR_MAX_SPRITE_W" to SpriteAtlasDef.DECOR_MAX_SPRITE_W.toString(),
            "DECOR_MAX_SPRITE_H" to SpriteAtlasDef.DECOR_MAX_SPRITE_H.toString(),
            "DECOR_MARGIN_COLS" to SpriteAtlasDef.DECOR_MARGIN_COLS.toString(),
            "DECOR_MARGIN_ROWS" to SpriteAtlasDef.DECOR_MARGIN_ROWS.toString(),
        )) {
            val match = Regex("""#define $define ([0-9.]+)f?""").find(src)
            assertTrue("TextureAtlas.h 缺少双端共享常量: $define", match != null)
            assertEquals(
                "TextureAtlas.h $define 与 SpriteAtlasDef 不一致——LAYOUT 修改后需重新 codegen",
                expected, match?.groupValues?.get(1)
            )
        }
        // 显示尺寸表 + 绘制层表逐项全等（渲染器按瓦片值直取，错位即装饰尺寸/层序错乱）
        assertEquals(
            "TILE_SPRITE_W 与 SpriteAtlasDef.TILE_SPRITE_W 不一致",
            SpriteAtlasDef.TILE_SPRITE_W.toList(),
            arrayBody(src, "TILE_SPRITE_W").split(",").map { it.trim().removeSuffix("f").toFloat() }
        )
        assertEquals(
            "TILE_SPRITE_H 与 SpriteAtlasDef.TILE_SPRITE_H 不一致",
            SpriteAtlasDef.TILE_SPRITE_H.toList(),
            arrayBody(src, "TILE_SPRITE_H").split(",").map { it.trim().removeSuffix("f").toFloat() }
        )
        assertEquals(
            "TILE_OBJECT_LAYER 与 SpriteAtlasDef.TILE_OBJECT_LAYER 不一致",
            SpriteAtlasDef.TILE_OBJECT_LAYER.toList(),
            arrayBody(src, "TILE_OBJECT_LAYER").split(",").map { it.trim().toInt() }
        )
    }

    /** 提取 TextureAtlas.h 数组字面量内容（缺失即断言失败——ThrowsCount 收敛） */
    private fun arrayBody(src: String, name: String): String {
        val match = Regex("""$name\[\] = \{([^}]*)\}""").find(src)
        assertTrue("TextureAtlas.h 缺少数组: $name", match != null)
        return match?.groupValues?.get(1) ?: ""
    }

    @Test
    fun `TextureAtlas 头 - MAP_SPRITES 41 条与期望全等`() {
        val src = headerSource()
        val spriteRegex = Regex("""\{ "([^"]+)",\s+(\d+),\s+(\d+),\s+(\d+),\s+(\d+)\s*\},\s*""")
        val sprites = spriteRegex.findAll(src).map { m ->
            SpriteEntry(
                m.groupValues[1],
                m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(),
            )
        }.toList()
        assertEquals(
            "MAP_SPRITES 条目数与期望不一致（10 瓦片 + 3 作物 + 19 建筑 + 1 结构 + 5 云层 + 3 道路 = 41）",
            41, sprites.size
        )
        // 抽查关键条目（数据与 Kotlin LAYOUT 同源，见 build-atlas.mjs；gutter 版布局）
        assertContains(sprites, SpriteEntry("ground_tile", 0, 0, 128, 128))
        assertContains(sprites, SpriteEntry("grass1", 136, 0, 128, 128))
        assertContains(sprites, SpriteEntry("grass4", 544, 0, 128, 128))
        assertContains(sprites, SpriteEntry("stone1", 2048, 0, 128, 128))
        assertContains(sprites, SpriteEntry("stone3", 2320, 0, 128, 128))
        assertContains(sprites, SpriteEntry("tree1", 800, 0, 256, 256))
        assertContains(sprites, SpriteEntry("crop_mature", 1656, 0, 128, 128))
        assertContains(sprites, SpriteEntry("灵矿场", 0, 512, 512, 512))
        assertContains(sprites, SpriteEntry("中级多人住所", 1560, 2072, 512, 512))
        assertContains(sprites, SpriteEntry("天枢殿", 3008, 1032, 1024, 1024))  // 专属 1024×1024 高清槽位
        assertContains(sprites, SpriteEntry("sect_gate", 3072, 512, 768, 256))
        assertContains(sprites, SpriteEntry("cloud_1", 0, 2816, 968, 240))
        assertContains(sprites, SpriteEntry("cloud_5", 1048, 3240, 944, 400))
        assertContains(sprites, SpriteEntry("road_body", 2048, 2624, 128, 128))
        // 浮空岛崖壁**不在图集内**（单张最大 1180×3552 超出 4096² 容量）——
        // 改走独立纹理，故此处不再断言 ie_* 条目（见 feature/game IslandCliffTextureSet）。
        // 瓦片 index == 精灵索引（渲染器以瓦片值直取 UV 表）：前 10 条按 TileType 序排列
        val tileOrder = listOf(
            "ground_tile", "grass1", "grass2", "grass3", "grass4",
            "stone1", "stone2", "stone3", "tree1", "tree2",
        )
        assertEquals(
            "MAP_SPRITES 前 10 条必须按 TileType 序（瓦片值 = 精灵索引）",
            tileOrder, sprites.take(tileOrder.size).map { it.name }
        )
        assertTrue(
            "MAP_SPRITE_COUNT 计算式必须存在（C++ 侧依赖）",
            src.contains("MAP_SPRITE_COUNT") && src.contains("sizeof(MAP_SPRITES)")
        )
    }

    private fun assertContains(sprites: List<SpriteEntry>, expected: SpriteEntry) {
        assertTrue(
            "MAP_SPRITES 缺少 ${expected}——LAYOUT.mapSprites 变更后必须同步本测试期望",
            sprites.contains(expected)
        )
    }

    private data class SpriteEntry(
        val name: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )
}

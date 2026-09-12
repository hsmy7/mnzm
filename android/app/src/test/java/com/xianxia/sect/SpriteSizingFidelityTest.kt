package com.xianxia.sect

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.ui.game.building.registerDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 显示尺寸保真守卫（构建期 `build-atlas.mjs` validateDisplaySizing 的测试期孪生）。
 *
 * 锁定的不变量（用户需求：建筑/装饰在画面上不得被压扁或拉宽）：
 * 1. **屏上纵横比 == 素材纵横比**——立体素材（立绘建筑 / 草石树）按
 *    `显示宽 ÷ (0.75 × 显示高) == 素材宽 ÷ 素材高`（0.75 = TOPDOWN_Y_SCALE：
 *    地面做透视压缩、竖向物体不压缩）；俯视贴地素材（灵田）按
 *    `显示宽 ÷ 显示高 == 素材宽 ÷ 素材高`（与地格平铺对齐）。
 *    容差 7% = 整数格取整残差上界（现役最坏 6.7%）。
 * 2. **精灵宽 == 占地宽**（相邻建筑精灵不左右压盖）、**精灵高 ≥ 占地深**（精灵盖住底座）。
 * 3. **图集槽位边长 ≤ 显示尺寸 × 4**（深度降采样缩放跳变防线——原守卫按占地判定，
 *    高精灵越出占地后改为按实际显示矩形判定）。
 *
 * 素材尺寸取自 `atlas-manifest.json` 的 srcW/srcH（图集构建期由 sharp 读取写入）——
 * 换图未同步尺寸、或有人手改配置/LAYOUT 使比例失真，本守卫即变红。
 */
class SpriteSizingFidelityTest {

    private companion object {
        /** 素材纵横比容差（整数格取整残差上界；现役最坏 6.7%）。 */
        const val TOLERANCE = 0.07

        /** 图集槽位 ≤ 显示尺寸 × 4（缩放 mip 跨越防回归）。 */
        const val MAX_SLOT_RATIO = 4

        /** 俯视贴地类建筑（世界纵横比 = 素材纵横比，与地格平铺对齐）。 */
        val GRID_ALIGNED_BUILDINGS = setOf("灵田")
    }

    @Test
    fun `建筑显示尺寸与素材纵横比一致 - 屏上不变形且精灵覆盖占地`() {
        val sprites = manifestSprites()
        val configs = configBuildings()
        BuildingFeatureRegistry.registerDefaults()
        val offenders = mutableListOf<String>()

        for (feature in BuildingFeatureRegistry.all) {
            val spriteName = feature.effectiveSpriteName()
            val sprite = spriteOf(sprites, spriteName)
            val srcW = srcWidthOf(sprite)
            val srcH = srcHeightOf(sprite)
            val cfg = configOf(configs, feature.displayName)

            val nameIndex = buildingIndex(spriteName)
            val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[nameIndex]

            if (cfg.spriteWidth != fpW) {
                offenders += "${feature.displayName}: 精灵宽 ${cfg.spriteWidth} ≠ 占地宽 $fpW（左右会互相压盖）"
            }
            if (cfg.spriteHeight < fpH) {
                offenders += "${feature.displayName}: 精灵高 ${cfg.spriteHeight} < 占地深 $fpH（盖不住底座）"
            }
            val displayH = if (spriteName in GRID_ALIGNED_BUILDINGS) {
                cfg.spriteHeight.toDouble()
            } else {
                (cfg.spriteHeight * SpriteAtlasDef.TOPDOWN_Y_SCALE).toDouble()
            }
            val err = aspectError(cfg.spriteWidth.toDouble(), displayH, srcW.toDouble(), srcH.toDouble())
            if (err > TOLERANCE) {
                offenders += "${feature.displayName}: 屏上纵横比与素材不符（偏差 ${pct(err)}）——" +
                    "素材 ${srcW}×${srcH}，当前精灵 ${cfg.spriteWidth}×${cfg.spriteHeight}"
            }
            // 槽位深度降采样防线：按实际显示矩形判定
            assertSlotWithinRatio(
                featured = feature.displayName,
                slotW = SpriteAtlasDef.buildingRect(nameIndex).w,
                slotH = SpriteAtlasDef.buildingRect(nameIndex).h,
                dispW = cfg.spriteWidth * SpriteAtlasDef.TILE_SIZE,
                dispH = (cfg.spriteHeight * SpriteAtlasDef.TILE_SIZE)
            )
        }

        assertEquals(
            "以下建筑显示尺寸保真校验失败（改 LAYOUT / config/buildings.json）：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `装饰显示尺寸与素材纵横比一致 - 屏上不变形`() {
        val sprites = manifestSprites()
        val offenders = mutableListOf<String>()
        for (tile in SpriteAtlasDef.TileType.values()) {
            if (!SpriteAtlasDef.isDecorTile(tile.index)) continue
            val sprite = spriteOf(sprites, tile.name)
            val srcW = srcWidthOf(sprite)
            val srcH = srcHeightOf(sprite)
            val err = aspectError(
                SpriteAtlasDef.tileSpriteWidth(tile.index).toDouble(),
                (SpriteAtlasDef.tileSpriteHeight(tile.index) * SpriteAtlasDef.TOPDOWN_Y_SCALE).toDouble(),
                srcW.toDouble(), srcH.toDouble()
            )
            if (err > TOLERANCE) {
                offenders += "${tile.name}: 屏上纵横比与素材不符（偏差 ${pct(err)}）——" +
                    "素材 ${srcW}×${srcH}，当前 " +
                    "${SpriteAtlasDef.tileSpriteWidth(tile.index)}×${SpriteAtlasDef.tileSpriteHeight(tile.index)}"
            }
        }
        assertEquals(
            "以下装饰显示尺寸保真校验失败（改 LAYOUT.tiles[].sprite）：\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `图集清单每个精灵都记录素材尺寸`() {
        val sprites = manifestSprites()
        // TILE_BUILDING 为占位（drawable=null，与 GROUND 同槽位，不参与渲染）——豁免
        val missing = sprites.values
            .filter { it.drawable != null && (it.srcW == null || it.srcH == null) }
            .map { it.name }
        assertEquals(
            "manifest 精灵缺少 srcW/srcH（图集构建期由 sharp 写入）——请重跑 node scripts/build-atlas.mjs：$missing",
            emptyList<String>(),
            missing
        )
    }

    /** 槽位边长不得超过显示边长的 [MAX_SLOT_RATIO] 倍（深度降采样缩放跳变防线）。 */
    private fun assertSlotWithinRatio(featured: String, slotW: Int, slotH: Int, dispW: Int, dispH: Int) {
        assertTrue(
            "$featured 槽位 ${slotW}×${slotH} 超过显示 ${dispW}×${dispH} 的 ${MAX_SLOT_RATIO} 倍" +
                "（深度降采样缩放跳变隐患）——应在 LAYOUT 按显示尺寸收敛槽位",
            slotW <= dispW * MAX_SLOT_RATIO && slotH <= dispH * MAX_SLOT_RATIO
        )
    }

    // ── 查找辅助（缺失即断言失败——测试方法内 ThrowsCount 收敛） ──

    private fun spriteOf(sprites: Map<String, ManifestSprite>, name: String): ManifestSprite {
        val sprite = sprites[name]
        assertTrue("图集清单缺少精灵: $name", sprite != null)
        return sprite ?: ManifestSprite(name, null, null, null)
    }

    private fun srcWidthOf(sprite: ManifestSprite): Int {
        val srcW = sprite.srcW
        assertTrue("manifest 缺少 srcW: ${sprite.name}——请重跑 node scripts/build-atlas.mjs", srcW != null)
        return srcW ?: 0
    }

    private fun srcHeightOf(sprite: ManifestSprite): Int {
        val srcH = sprite.srcH
        assertTrue("manifest 缺少 srcH: ${sprite.name}——请重跑 node scripts/build-atlas.mjs", srcH != null)
        return srcH ?: 0
    }

    private fun buildingIndex(spriteName: String): Int {
        val nameIndex = SpriteAtlasDef.BUILDING_NAME_INDEX[spriteName]
        assertTrue("图集缺少建筑精灵: $spriteName", nameIndex != null)
        return nameIndex ?: 0
    }

    private fun configOf(configs: Map<String, ConfiguredSprite>, displayName: String): ConfiguredSprite {
        val cfg = configs[displayName]
        assertTrue("配置缺少建筑: $displayName", cfg != null)
        return cfg ?: ConfiguredSprite(0, 0)
    }

    private fun pct(err: Double): String = "${"%.1f".format(err * 100)}% > ${(TOLERANCE * 100).toInt()}%"

    /** 屏上纵横比与素材纵横比的相对误差（0 = 完全不变形）。 */
    private fun aspectError(displayW: Double, displayH: Double, srcW: Double, srcH: Double): Double {
        val display = displayW / displayH
        val art = srcW / srcH
        return Math.abs(display - art) / art
    }

    /** manifest 精灵条目（名称 → 素材像素尺寸 + 槽位矩形）。 */
    private data class ManifestSprite(
        val name: String,
        val drawable: String?,
        val srcW: Int?,
        val srcH: Int?
    )

    private fun manifestSprites(): Map<String, ManifestSprite> {
        val file = File("src/main/assets/atlas/atlas-manifest.json")
        assertTrue("atlas-manifest.json 不存在: ${file.absolutePath}", file.exists())
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val sprites = root["sprites"] as? kotlinx.serialization.json.JsonArray
            ?: error("manifest 缺少 sprites 数组")
        return sprites.associate { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: error("manifest 精灵缺少 name")
            name to ManifestSprite(
                name = name,
                drawable = obj["drawable"]?.jsonPrimitive?.contentOrNull,
                srcW = obj["srcW"]?.jsonPrimitive?.content?.toIntOrNull(),
                srcH = obj["srcH"]?.jsonPrimitive?.content?.toIntOrNull()
            )
        }
    }

    /** assets 建筑配置（displayName → sprite 尺寸）。 */
    private data class ConfiguredSprite(val spriteWidth: Int, val spriteHeight: Int)

    private fun configBuildings(): Map<String, ConfiguredSprite> {
        val file = File("src/main/assets/config/buildings.json")
        assertTrue("buildings.json 不存在: ${file.absolutePath}", file.exists())
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val buildings = root["buildings"] as? JsonObject ?: error("buildings 字段缺失或结构异常")
        return buildings.values.associate { element ->
            val obj = element.jsonObject
            val displayName = obj["displayName"]?.jsonPrimitive?.content
                ?: error("建筑配置缺少 displayName")
            val spriteWidth = obj["spriteWidth"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: error("建筑配置缺少 spriteWidth: $displayName")
            val spriteHeight = obj["spriteHeight"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: error("建筑配置缺少 spriteHeight: $displayName")
            displayName to ConfiguredSprite(spriteWidth, spriteHeight)
        }
    }
}

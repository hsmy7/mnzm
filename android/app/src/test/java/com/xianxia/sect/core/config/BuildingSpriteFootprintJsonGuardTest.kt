package com.xianxia.sect.core.config

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.ui.game.building.registerDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 真实 assets `config/buildings.json` 结构守卫（与 engine 兜底配置守卫同口径）：
 * ① 精灵宽 == 占地宽（左右不压盖）；② 精灵高 ≥ 占地深（盖住底座）；
 * ③ 占地与图集 `FOOTPRINT_BY_NAME_INDEX` 逐栋一致（渲染器按图集取占地）。
 *
 * 背景：旧守卫强制 `sprite == grid`（1:1 贴地），与带高度的立绘素材冲突——
 * 巡视楼 569×1024 只能显示出约 31% 的高度。现行口径按素材纵横比取显示尺寸
 * （屏上不变形），素材比例由 `SpriteSizingFidelityTest` 单独守卫，
 * 本测试锁配置的结构关系（运营改 JSON 时不会把精灵改成比占地窄/矮）。
 */
class BuildingSpriteFootprintJsonGuardTest {

    @Test
    fun `assets buildingsJson 精灵宽等于占地宽且精灵高不低于占地深`() {
        val file = File("src/main/assets/config/buildings.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val buildingsElement = root["buildings"] ?: error("buildings 字段缺失")
        val buildings = buildingsElement as? JsonObject ?: error("buildings 字段结构异常")

        assertEquals("建筑数量", 19, buildings.size)

        BuildingFeatureRegistry.registerDefaults()
        val offenders = buildings.entries.mapNotNull { (id, element) ->
            val obj = element as? JsonObject
            fun intOf(key: String): Int? = obj?.get(key)?.jsonPrimitive?.content?.toIntOrNull()
            val displayName = obj?.get("displayName")?.jsonPrimitive?.content ?: id
            val gridW = intOf("gridWidth")
            val gridH = intOf("gridHeight")
            val spriteW = intOf("spriteWidth")
            val spriteH = intOf("spriteHeight")
            when {
                gridW == null || gridH == null || spriteW == null || spriteH == null ->
                    "$id: 字段缺失或非整数"
                spriteW != gridW ->
                    "$id($displayName): 精灵宽 ${spriteW} ≠ 占地宽 ${gridW}（相邻建筑精灵会左右压盖）"
                spriteH < gridH ->
                    "$id($displayName): 精灵高 ${spriteH} < 占地深 ${gridH}（盖不住底座）"
                else -> null
            }
        }

        assertEquals(
            "以下建筑精灵/占地关系不合法（宽必须相等、高不得低于占地深）:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `assets buildingsJson 占地与图集 FOOTPRINT_BY_NAME_INDEX 逐栋一致`() {
        val file = File("src/main/assets/config/buildings.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val buildings = root["buildings"] as? JsonObject ?: error("buildings 字段结构异常")
        BuildingFeatureRegistry.registerDefaults()

        val mismatches = mutableListOf<String>()
        for (element in buildings.values) {
            val obj = element.jsonObject
            val displayName = obj["displayName"]?.jsonPrimitive?.content ?: continue
            val spriteName = BuildingFeatureRegistry.findByDisplayName(displayName)
                ?.effectiveSpriteName() ?: displayName
            val nameIndex = SpriteAtlasDef.BUILDING_NAME_INDEX[spriteName]
                ?: throw AssertionError("建筑 '$displayName' 未在图集 BUILDING_NAMES 注册")
            val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[nameIndex]
            val gridW = obj["gridWidth"]?.jsonPrimitive?.content?.toIntOrNull()
            val gridH = obj["gridHeight"]?.jsonPrimitive?.content?.toIntOrNull()
            if (gridW != fpW || gridH != fpH) {
                mismatches += "$displayName: JSON 占地 ${gridW}×${gridH} ≠ 图集 $fpW×$fpH"
            }
        }
        assertEquals(
            "配置占地与图集占地不一致——修改占地须同步 LAYOUT.footprints" +
                "（并重新生成 SpriteAtlasDef.kt / footprint_table.h）:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches
        )
    }
}

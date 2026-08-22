package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建筑精灵图守卫测试（2026-08 初级多人住所精灵图不显示根因修复）。
 *
 * 锁住不变量：运行时图集组装器 [SectAtlasAssembler] 的"图集名 → drawable"映射
 * 必须经 [com.xianxia.sect.core.engine.domain.building.BuildingFeature.effectiveSpriteName]
 * 解析（显示名可带分级前缀，图集精灵名保持历史名称）。若有人改回按 displayName 建映射、
 * 或新增/重命名建筑时未同步 spriteName，本测试立即变红。
 */
class SectAtlasBuildingDrawableGuardTest {

    @Test
    fun `图集每个建筑名都能映射到非零 drawable`() {
        BuildingFeatureRegistry.registerDefaults()
        val map = SectAtlasAssembler.buildingAtlasDrawableMap()
        for (name in SpriteAtlasDef.BUILDING_NAMES) {
            val resId = map[name]
            assertTrue(
                "图集建筑 '$name' 无 drawable——检查 BuildingFeature 的 spriteName/effectiveSpriteName" +
                    "（住所类显示名带「初级」前缀，精灵名必须保持图集历史名称）",
                resId != null && resId != 0
            )
        }
    }

    @Test
    fun `每个注册建筑经 effectiveSpriteName 都能命中图集且无重复`() {
        BuildingFeatureRegistry.registerDefaults()
        val map = SectAtlasAssembler.buildingAtlasDrawableMap()
        for (feature in BuildingFeatureRegistry.all) {
            val name = feature.effectiveSpriteName()
            assertTrue(
                "'${feature.displayName}' 的精灵名 '$name' 不在图集 BUILDING_NAMES 中——" +
                    "新增建筑必须同步 LAYOUT.buildingNames 并设置 spriteName",
                name in SpriteAtlasDef.BUILDING_NAME_INDEX
            )
            assertTrue("'${feature.displayName}' 精灵名 '$name' drawable 缺失", (map[name] ?: 0) != 0)
        }
        // 全量双射：19 建筑 ↔ 19 图集名，无缺漏无重复
        assertEquals(
            "映射键数应与图集建筑数一致（存在未覆盖/重复的精灵名）",
            SpriteAtlasDef.BUILDING_NAMES.size,
            map.size
        )
    }

    @Test
    fun `住所类建筑图集槽位有 drawable_回归锚点`() {
        BuildingFeatureRegistry.registerDefaults()
        val map = SectAtlasAssembler.buildingAtlasDrawableMap()
        assertTrue("多人住所槽位 drawable 缺失（初级多人住所不显示回归）", (map["多人住所"] ?: 0) != 0)
        assertTrue("单人住所槽位 drawable 缺失（初级单人住所不显示回归）", (map["单人住所"] ?: 0) != 0)
    }
}

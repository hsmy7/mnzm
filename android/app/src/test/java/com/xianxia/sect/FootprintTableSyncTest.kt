package com.xianxia.sect

import com.xianxia.sect.core.engine.domain.building.BuildingFeature
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kotlin 侧占地表一致性守卫。
 *
 * **口径变更（b02 发现 5 清理，2026-09-20）**：原用例 1（footprint_table.h ↔
 * FOOTPRINT_BY_NAME_INDEX 逐项一致）已随 `generateFootprintHeader` 死管道退役——
 * C++ 消费面自 B10 起改用 `scene/scene_uv_tables.h`（build-atlas.mjs 单跳生成），
 * 其"生成器幂等 + C++ 消费表同步"守卫职责由
 * `SceneUvTablesMirrorGuardTest.footprint tables mirror SpriteAtlasDef`（:core:engine）
 * 逐项接替。本文件保留的是 **Kotlin 侧两表一致性**（名称表 ↔ 占地表 ↔ 注册表），
 * 与 footprint_table.h 无关。
 */
class FootprintTableSyncTest {

    @Test
    fun `BUILDING_NAMES 与足迹表数量一致`() {
        assertEquals(
            "BUILDING_NAMES 数量(${SpriteAtlasDef.BUILDING_NAMES.size}) 与 " +
                "FOOTPRINT_BY_NAME_INDEX 数量(${SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size}) 不一致——" +
                "新增建筑必须同时添加名称与占地尺寸",
            SpriteAtlasDef.BUILDING_NAMES.size,
            SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size
        )
    }

    @Test
    fun `BuildingFeatureRegistry 占地与 FOOTPRINT_BY_NAME_INDEX 逐项一致`() {
        // 索引精灵包围盒（BuildingSpatialIndex）按注册表取占地、
        // 渲染器按 FOOTPRINT_BY_NAME_INDEX 取占地，两表不一致会使精灵命中区整体偏移
        //（症状：部分区域点击无效）。registerDefaults 在测试环境不执行。
        BuildingFeatureRegistry.registerDefaults()

        val features = BuildingFeatureRegistry.all
        assertEquals(
            "注册表建筑数(${features.size}) 与图集名称数(${SpriteAtlasDef.BUILDING_NAMES.size}) 不一致——" +
                "新增建筑必须同时注册 BuildingFeature 与图集名称",
            SpriteAtlasDef.BUILDING_NAMES.size, features.size
        )

        for (feature: BuildingFeature in features) {
            // 精灵名经 effectiveSpriteName 解析（显示名可带分级前缀，图集精灵名保持历史名称）
            val nameIdx = SpriteAtlasDef.BUILDING_NAME_INDEX[feature.effectiveSpriteName()]
                ?: throw AssertionError("建筑 '${feature.displayName}' 未在图集 BUILDING_NAMES 中注册")
            val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[nameIdx]
            assertEquals(
                "'${feature.displayName}' gridWidth=${feature.gridWidth} ≠ 图集占地宽=$fpW——" +
                    "修改注册表占地必须同步 SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX",
                feature.gridWidth, fpW
            )
            assertEquals(
                "'${feature.displayName}' gridHeight=${feature.gridHeight} ≠ 图集占地高=$fpH——" +
                    "同步维护点同上",
                feature.gridHeight, fpH
            )
        }
    }
}

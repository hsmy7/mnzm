package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 初始灵矿场占地尺寸守卫测试（CLAUDE.md 9.5 守卫测试三要素）。
 *
 * 锚点：`spirit_mine` 配置。灵矿场占地尺寸在四处独立维护，
 * 任一改动（如调整占地为 6×6）都会导致新档首次会话"渲染尺寸 ≠ 点击尺寸"，
 * 症状为矿场部分区域点击无效（2026-08-06 修复 #2 的根因）。
 */
class InitialMineSizeGuardTest {

    @Before
    fun setUp() {
        // XianxiaApplication.onCreate 在测试环境不执行，手动注册默认特征
        BuildingFeatureRegistry.registerDefaults()
    }

    @Test
    fun `灵矿场占地尺寸守卫 - 配置与初始创建保持一致`() {
        val def = BuildingFeatureRegistry.findByKey("spirit_mine")
        assertEquals("spirit_mine 注册表项应存在", true, def != null)

        assertEquals(
            "灵矿场 gridWidth 必须为 4。若调整，请同步更新以下三处维护点：\n" +
                "1. GameEngineCoordination.kt createNewGameInternal / restartGameInternal " +
                "的 initialMine width/height\n" +
                "2. BuildingConfigService.createDefaultConfig() 的 mining gridWidth/gridHeight\n" +
                "3. SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[0]（渲染占地）",
            4, def?.gridWidth
        )
        assertEquals(
            "灵矿场 gridHeight 必须为 4。同步维护点同上（三处）。",
            4, def?.gridHeight
        )
    }
}

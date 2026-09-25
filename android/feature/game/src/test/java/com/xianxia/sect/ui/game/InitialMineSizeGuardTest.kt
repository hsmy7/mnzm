package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 初始灵矿场占地尺寸守卫测试（根 AGENTS.md §9.5 守卫测试三要素）。
 *
 * 锚点：`spirit_mine` 注册表项。灵矿场占地尺寸在多处独立维护，
 * 任一改动（如调整占地为 6×6）都会导致新档首次会话"渲染尺寸 ≠ 点击尺寸"，
 * 症状为矿场部分区域点击无效。
 *
 * 本测试只持有注册表这一头（feature/game 不读 :core:engine 的开局实现）；
 * 开局两臂（createNewGame / restartGameInternal 有名臂）实际建出的 4×4 由
 * `core/engine` 的 `GameEngineCoordinationTest`「初始灵矿场为 4x4」两条用例持有，
 * 两侧合起来构成完整闭环。
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
            "灵矿场 gridWidth 必须为 4。若调整，请同步更新以下维护点：\n" +
                "1. GameEngineLoadDataOps.kt createNewGame 的 initialMine width/height\n" +
                "2. GameEngineLoadDataOps.kt restartGameInternal 有名臂的 initialMine width/height\n" +
                "3. GameEngineLoadDataOps.kt restartGameInternal else 臂（sectName.isBlank()）——" +
                "当前不建任何建筑，一旦开始建矿场必须同口径，否则「重置后开局不一致」\n" +
                "4. BuildingFeatureBoot.kt 的 BuildingFeature(\"spirit_mine\", gridWidth/gridHeight)" +
                "（即本测试读取的注册表锚点）\n" +
                "5. SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[0]（渲染占地，图集 codegen 产物，" +
                "改占地须同步 FootprintTableSyncTest）\n" +
                "6. GameEngineCoordinationTest 的两条「初始灵矿场为 4x4」用例",
            4, def?.gridWidth
        )
        assertEquals(
            "灵矿场 gridHeight 必须为 4。同步维护点同上（六处）。",
            4, def?.gridHeight
        )
    }
}

package com.xianxia.sect.ui.game

import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.building.registerDefaults
import com.xianxia.sect.ui.game.main.BuildingEntrySpec
import com.xianxia.sect.ui.game.main.buildingEntrySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 建筑选中交互重设计相关逻辑单测：
 * - [buildingDialogType]：建筑显示名 → 详情对话框类型映射（"进入"按钮分发）
 * - [buildingEntrySpec]：建筑显示名 → "进入"按钮图标/文本映射（灵田/炼丹炉/锻造坊专属）
 */
class MainGameScreenSelectionTest {

    @Before
    fun registerFeatures() {
        BuildingFeatureRegistry.registerDefaults()
    }

    // ============================================================
    // buildingDialogType — 详情对话框分发映射
    // ============================================================

    @Test
    fun `buildingDialogType - herb_garden 灵植阁 返回 HerbGarden`() {
        assertEquals(DialogType.HerbGarden, buildingDialogType("灵植阁", "inst_1"))
    }

    @Test
    fun `buildingDialogType - spirit_field 灵田 返回 Planting`() {
        assertEquals(DialogType.Planting, buildingDialogType("灵田", "inst_1"))
    }

    @Test
    fun `buildingDialogType - alchemy 炼丹炉 返回 Alchemy 携带实例ID`() {
        assertEquals(DialogType.Alchemy("inst_alchemy"), buildingDialogType("炼丹炉", "inst_alchemy"))
    }

    @Test
    fun `buildingDialogType - forge 锻造坊 返回 Forge 携带实例ID`() {
        assertEquals(DialogType.Forge("inst_forge"), buildingDialogType("锻造坊", "inst_forge"))
    }

    @Test
    fun `buildingDialogType - spirit_mine 灵矿场 返回 SpiritMine 携带实例ID`() {
        assertEquals(DialogType.SpiritMine("inst_mine"), buildingDialogType("灵矿场", "inst_mine"))
    }

    @Test
    fun `buildingDialogType - 住所返回 Residence 携带实例ID`() {
        assertEquals(DialogType.Residence("inst_r"), buildingDialogType("初级单人住所", "inst_r"))
        assertEquals(DialogType.Residence("inst_r2"), buildingDialogType("中级多人住所", "inst_r2"))
    }

    @Test
    fun `buildingDialogType - 未注册显示名返回 null`() {
        assertNull(buildingDialogType("不存在建筑", "inst_x"))
    }

    // ============================================================
    // buildingEntrySpec — "进入"按钮图标/文本映射
    // ============================================================

    @Test
    fun `buildingEntrySpec - 灵田 使用种植图标与种植文本`() {
        assertEquals(BuildingEntrySpec("ui_planting", "种植"), buildingEntrySpec("灵田"))
    }

    @Test
    fun `buildingEntrySpec - 炼丹炉 使用炼丹图标与炼丹文本`() {
        assertEquals(BuildingEntrySpec("ui_alchemy", "炼丹"), buildingEntrySpec("炼丹炉"))
    }

    @Test
    fun `buildingEntrySpec - 锻造坊 使用锻造图标与锻造文本`() {
        assertEquals(BuildingEntrySpec("ui_forge", "锻造"), buildingEntrySpec("锻造坊"))
    }

    @Test
    fun `buildingEntrySpec - 其它建筑使用通用进入图标与进入文本`() {
        assertEquals(BuildingEntrySpec("ui_enter", "进入"), buildingEntrySpec("灵矿场"))
        assertEquals(BuildingEntrySpec("ui_enter", "进入"), buildingEntrySpec("藏经阁"))
    }

    @Test
    fun `buildingEntrySpec - 未注册显示名回退通用进入`() {
        assertEquals(BuildingEntrySpec("ui_enter", "进入"), buildingEntrySpec("未知建筑"))
    }
}

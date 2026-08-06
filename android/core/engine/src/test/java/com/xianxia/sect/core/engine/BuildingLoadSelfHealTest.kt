package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.config.BuildingConfigModel
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WorldSect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 建筑读档自愈纯函数测试（D-13 孤儿归属归一化 + D-11 activeSectId 净化）。
 *
 * 覆盖：
 * - 孤儿（sectId 无对应宗门）归入本宗 ""；现存宗门/本宗建筑不动
 * - worldMapSects 为空时跳过归一化（世界重生前防误伤）
 * - 幂等（连续两次归一化结果一致）
 * - 灵矿场槽位 sectId 与建筑同步
 * - activeSectId 净化：空/不存在/失守宗门归空，占领/玩家宗门保留
 * - 守卫：归一化后孤儿与玩家建筑重叠 → 溢出迁移拆除低价者
 */
class BuildingLoadSelfHealTest {

    private val homeSect = WorldSect(id = "player_sect", isPlayerSect = true)
    private val aiSect = WorldSect(id = "sect_1", isPlayerSect = false)
    private val conqueredSect = WorldSect(id = "sect_2", isPlayerSect = false, isPlayerOccupied = true)
    private val lostSect = WorldSect(id = "sect_3", isPlayerSect = false)
    private val worldSects = listOf(homeSect, aiSect, conqueredSect, lostSect)

    private fun b(displayName: String, sectId: String, instanceId: String) =
        GridBuildingData(displayName = displayName, sectId = sectId, instanceId = instanceId)

    // ================================================================
    // normalizeOrphanBuildingSectIds — D-13
    // ================================================================

    @Test
    fun `normalizeOrphanBuildingSectIds_orphanSectId_归入本宗`() {
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects)
        assertEquals("", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_existingAiSect_不动`() {
        val buildings = listOf(b("炼丹炉", "sect_1", "id_1"), b("灵矿场", "", "id_2"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects)
        assertEquals("sect_1", result.buildings[0].sectId)
        assertEquals("", result.buildings[1].sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_conqueredSect_不动`() {
        // 玩家占领宗门的建筑有真实归属，不得并入本宗
        val buildings = listOf(b("仓库", "sect_2", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects)
        assertEquals("sect_2", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_emptyWorldSects_跳过不归一化`() {
        // 世界重生（boot Step 5）之前 worldMapSects 为空——此时归一化会误伤，
        // 跳过等下次读档收敛
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), emptyList())
        assertEquals("sect_dead", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_blankSectId_不动`() {
        val buildings = listOf(b("灵矿场", "", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects)
        assertEquals("", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_idempotent_两次归一化结果一致`() {
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"), b("炼丹炉", "sect_1", "id_2"))
        val first = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects)
        val second = normalizeOrphanBuildingSectIds(first.buildings, first.spiritMineSlots, worldSects)
        assertEquals(first.buildings, second.buildings)
        assertEquals("", second.buildings[0].sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_mineSlot_orphanSectId同步归空`() {
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"))
        val slots = listOf(SpiritMineSlot(index = 0, sectId = "sect_dead", buildingInstanceId = "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, slots, worldSects)
        assertEquals("", result.spiritMineSlots.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_mineSlot_existingSect不动`() {
        val slots = listOf(SpiritMineSlot(index = 0, sectId = "sect_1"))
        val result = normalizeOrphanBuildingSectIds(emptyList(), slots, worldSects)
        assertEquals("sect_1", result.spiritMineSlots.single().sectId)
    }

    // ================================================================
    // purifyStaleActiveSectId — D-11
    // ================================================================

    @Test
    fun `purifyStaleActiveSectId_blank_原样返回`() {
        assertEquals("", purifyStaleActiveSectId("", worldSects))
    }

    @Test
    fun `purifyStaleActiveSectId_nonExistentSect_归空`() {
        assertEquals("", purifyStaleActiveSectId("sect_dead", worldSects))
    }

    @Test
    fun `purifyStaleActiveSectId_lostSect_归空`() {
        // 宗门存在但玩家已失守（非玩家持有）→ 残留 id 归空
        assertEquals("", purifyStaleActiveSectId("sect_3", worldSects))
    }

    @Test
    fun `purifyStaleActiveSectId_occupiedSect_保留`() {
        assertEquals("sect_2", purifyStaleActiveSectId("sect_2", worldSects))
    }

    @Test
    fun `purifyStaleActiveSectId_playerSect_保留`() {
        assertEquals("player_sect", purifyStaleActiveSectId("player_sect", worldSects))
    }

    @Test
    fun `purifyStaleActiveSectId_emptyWorldSects_归空`() {
        // 世界损坏待重生——任何残留 id 必无效
        assertEquals("", purifyStaleActiveSectId("sect_1", emptyList()))
    }

    // ================================================================
    // 守卫：归一化 → 溢出迁移顺序（孤儿并入本宗后重叠由迁移拆除）
    // ================================================================

    @Test
    fun `normalizeThenOverflowMigration_orphanOverlapsPlayerBuilding_lowerCostDemolished`() {
        val buildingConfigService = mock<BuildingConfigService>()
        whenever(buildingConfigService.getBuildingConfigByDisplayName("灵矿场")).thenReturn(
            BuildingConfigModel(id = "mining", displayName = "灵矿场",
                buildingType = "MINING", cost = 1500, gridWidth = 4, gridHeight = 4)
        )
        whenever(buildingConfigService.getBuildingConfigByDisplayName("炼丹炉")).thenReturn(
            BuildingConfigModel(id = "alchemy", displayName = "炼丹炉",
                buildingType = "ALCHEMY", cost = 4000, gridWidth = 4, gridHeight = 4)
        )

        // 孤儿 灵矿场(2,2,4×4, sectId=sect_dead) 与玩家 炼丹炉(2,2,4×4, sectId="") 同坐标
        val buildings = listOf(
            GridBuildingData(displayName = "灵矿场", gridX = 2, gridY = 2,
                width = 4, height = 4, instanceId = "orphan_mine", sectId = "sect_dead"),
            GridBuildingData(displayName = "炼丹炉", gridX = 2, gridY = 2,
                width = 4, height = 4, instanceId = "player_alchemy", sectId = "")
        )

        // 第一步：归一化 → 孤儿归入本宗 ""（与炼丹炉同网格）
        val norm = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects)
        assertEquals("", norm.buildings[0].sectId)

        // 第二步：溢出迁移（按归一化后的 sectId 分组）→ 造价低的灵矿场被拆除
        val gd = GameData(placedBuildings = norm.buildings)
        val result = computeBuildingOverflowMigration(norm.buildings, gd, buildingConfigService)
        assertEquals(listOf("player_alchemy"), result.kept.map { it.instanceId })
        assertEquals(listOf("orphan_mine"), result.demolished.map { it.instanceId })
        assertEquals(1500L, result.totalRefund)
    }
}

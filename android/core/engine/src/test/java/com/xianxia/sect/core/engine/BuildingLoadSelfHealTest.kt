package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.config.BuildingConfigModel
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 建筑读档自愈纯函数测试（孤儿归属归一化 + activeSectId 净化）。
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
    // normalizeResidenceDisplayNames — 住所显示名分级前缀迁移
    // ================================================================

    @Test
    fun `normalizeResidenceDisplayNames_旧名单人多人_改写为初级前缀`() {
        val buildings = listOf(
            b("单人住所", "", "s1"),
            b("多人住所", "", "m1"),
            b("炼丹炉", "", "a1"),
            b("中级单人住所", "", "su1")
        )
        val (renamed, counters) = normalizeResidenceDisplayNames(buildings, emptyMap())
        assertEquals("初级单人住所", renamed[0].displayName)
        assertEquals("初级多人住所", renamed[1].displayName)
        assertEquals("非住所建筑不动", "炼丹炉", renamed[2].displayName)
        assertEquals("中级住所不动", "中级单人住所", renamed[3].displayName)
        assertEquals("无计数时计数器原样", emptyMap<String, Long>(), counters)
    }

    @Test
    fun `normalizeResidenceDisplayNames_引导计数key同步迁移且数值保留`() {
        val counters = mapOf(
            GuideCounterKeys.buildingBuiltKey("单人住所") to 5L,
            GuideCounterKeys.buildingBuiltKey("多人住所") to 3L,
            "miningOutput" to 100L
        )
        val (_, renamed) = normalizeResidenceDisplayNames(emptyList(), counters)
        assertEquals("旧单人住所计数迁到新 key", 5L,
            renamed[GuideCounterKeys.buildingBuiltKey("初级单人住所")])
        assertEquals("旧多人住所计数迁到新 key", 3L,
            renamed[GuideCounterKeys.buildingBuiltKey("初级多人住所")])
        assertEquals("无关计数保留", 100L, renamed["miningOutput"])
        assertEquals("旧 key 不再残留", null, renamed[GuideCounterKeys.buildingBuiltKey("单人住所")])
    }

    @Test
    fun `normalizeResidenceDisplayNames_幂等_新名不动`() {
        val buildings = listOf(
            b("初级单人住所", "", "s1"),
            b("初级多人住所", "", "m1")
        )
        val counters = mapOf(GuideCounterKeys.buildingBuiltKey("初级单人住所") to 5L)
        val (renamed, renamedCounters) = normalizeResidenceDisplayNames(buildings, counters)
        assertEquals("已迁移建筑不动", buildings, renamed)
        assertEquals("已迁移计数不动", counters, renamedCounters)
    }

    @Test
    fun `normalizeResidenceDisplayNames_旧名与新名混合_仅迁移旧名`() {
        val buildings = listOf(
            b("单人住所", "", "old1"),
            b("初级单人住所", "", "new1")
        )
        val (renamed, _) = normalizeResidenceDisplayNames(buildings, emptyMap())
        assertEquals("旧名改写", "初级单人住所", renamed[0].displayName)
        assertEquals("新名不动", "初级单人住所", renamed[1].displayName)
        // 混合后两座同名（旧档与新档共存窗口），均为新名即可
        assertTrue(renamed.all { it.displayName == "初级单人住所" })
    }

    // ================================================================
    // normalizeOrphanBuildingSectIds — 孤儿归属归一化
    // ================================================================

    @Test
    fun `normalizeOrphanBuildingSectIds_orphanSectId_归入本宗`() {
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        assertEquals("", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_existingAiSect_不动`() {
        val buildings = listOf(b("炼丹炉", "sect_1", "id_1"), b("灵矿场", "", "id_2"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        assertEquals("sect_1", result.buildings[0].sectId)
        assertEquals("", result.buildings[1].sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_conqueredSect_不动`() {
        // 玩家占领宗门的建筑有真实归属，不得并入本宗
        val buildings = listOf(b("仓库", "sect_2", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        assertEquals("sect_2", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_emptyWorldSects_跳过不归一化`() {
        // 世界重生（boot Step 5）之前 worldMapSects 为空——此时归一化会误伤，
        // 跳过等下次读档收敛
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), emptyList(), emptySet())
        assertEquals("sect_dead", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_blankSectId_不动`() {
        val buildings = listOf(b("灵矿场", "", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        assertEquals("", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_idempotent_两次归一化结果一致`() {
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"), b("炼丹炉", "sect_1", "id_2"))
        val first = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        val second = normalizeOrphanBuildingSectIds(first.buildings, first.spiritMineSlots, worldSects, emptySet())
        assertEquals(first.buildings, second.buildings)
        assertEquals("", second.buildings[0].sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_mineSlot_orphanSectId同步归空`() {
        val buildings = listOf(b("灵矿场", "sect_dead", "id_1"))
        val slots = listOf(SpiritMineSlot(index = 0, sectId = "sect_dead", buildingInstanceId = "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, slots, worldSects, emptySet())
        assertEquals("", result.spiritMineSlots.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_mineSlot_existingSect不动`() {
        val slots = listOf(SpiritMineSlot(index = 0, sectId = "sect_1"))
        val result = normalizeOrphanBuildingSectIds(emptyList(), slots, worldSects, emptySet())
        assertEquals("sect_1", result.spiritMineSlots.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_rosterBulkDiverged_跳过不误归主宗`() {
        // 问题1 选项1 守卫：roster 非空但建筑引用多个（≥2）不在 roster 的宗门 id →
        // 判定严重失配（世界重生/重型数据分叉异常态），跳过归一化、保留原 sectId；
        // 否则"占领宗门内建建筑"会被误归 ""（主宗），显示到主宗地图。
        val buildings = listOf(
            b("仓库", "sect_x", "id_1"),
            b("灵矿场", "sect_y", "id_2")
        )
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        assertEquals("严重失配需保留占用宗门归属", "sect_x", result.buildings[0].sectId)
        assertEquals("严重失配需保留占用宗门归属", "sect_y", result.buildings[1].sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_rosterBulkDiverged_mineSlots同步保留`() {
        // 失配（≥2 个引用宗门缺失）同样作用于矿场槽位（与建筑同源 stamp），槽位 sectId 不被清空。
        val buildings = listOf(
            b("灵矿场", "sect_x", "id_1"),
            b("仓库", "sect_y", "id_2")
        )
        val slots = listOf(SpiritMineSlot(index = 0, sectId = "sect_x", buildingInstanceId = "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, slots, worldSects, emptySet())
        assertEquals("严重失配时矿场槽位归属保留", "sect_x", result.spiritMineSlots.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_playerOwnedSectMissingFromRoster_保留归属`() {
        // 问题1 选项2：玩家持有（占领）宗门缺失于 roster 时，建筑归属须保留（不误归主宗）——
        // playerOwnedSectIds 为独立权威（sectDetails.isOwned）。此场景是单个被占宗门缺失
        //（≥2 阈值守卫不适用；这是"只剩一个被占宗门"的常见报障场景）。
        val buildings = listOf(b("仓库", "sect_x", "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, setOf("sect_x"))
        assertEquals("玩家持有宗门缺失于 roster 仍保留归属", "sect_x", result.buildings.single().sectId)
    }

    @Test
    fun `normalizeOrphanBuildingSectIds_playerOwnedSect_mineSlot同步保留`() {
        val buildings = listOf(b("灵矿场", "sect_x", "id_1"))
        val slots = listOf(SpiritMineSlot(index = 0, sectId = "sect_x", buildingInstanceId = "id_1"))
        val result = normalizeOrphanBuildingSectIds(buildings, slots, worldSects, setOf("sect_x"))
        assertEquals("sect_x", result.spiritMineSlots.single().sectId)
    }

    @Test
    fun `derivePlayerOwnedSectIds_sectDetailsIsOwned和worldMapSectsOccupied合并`() {
        val details = mapOf("sect_a" to SectDetail(sectId = "sect_a", isOwned = true))
        val sects = listOf(
            WorldSect(id = "sect_b", isPlayerOccupied = true),
            WorldSect(id = "sect_c")
        )
        val derived = derivePlayerOwnedSectIds(details, sects)
        assertEquals("isOwned 与 isPlayerOccupied 合并去重", setOf("sect_a", "sect_b"), derived)
    }

    @Test
    fun `backfillPlayerOwnedSectDetails_为当前占领宗门补标isOwned_幂等`() {
        val details = mapOf("sect_a" to SectDetail(sectId = "sect_a", isOwned = true))
        val sects = listOf(
            WorldSect(id = "sect_a", isPlayerOccupied = true),
            WorldSect(id = "sect_b", isPlayerOccupied = true)
        )
        val backfilled = backfillPlayerOwnedSectDetails(details, sects)
        assertTrue("已持有宗门不动", backfilled["sect_a"]?.isOwned == true)
        assertTrue("现状占领宗门补标 isOwned", backfilled["sect_b"]?.isOwned == true)
        assertEquals("幂等：重复回填结果一致", backfilled, backfillPlayerOwnedSectDetails(backfilled, sects))
    }

    @Test
    fun `purifyStaleActiveSectId_playerOwnedSectMissingFromRoster_保留`() {
        // 预存问题3：玩家持有（占领）宗门缺失于 roster 时净化保留 activeSectId，不锁回主宗视角。
        assertEquals("sect_x", purifyStaleActiveSectId("sect_x", worldSects, setOf("sect_x")))
    }

    // ================================================================
    // purifyStaleActiveSectId — activeSectId 净化
    // ================================================================

    @Test
    fun `purifyStaleActiveSectId_blank_原样返回`() {
        assertEquals("", purifyStaleActiveSectId("", worldSects, emptySet()))
    }

    @Test
    fun `purifyStaleActiveSectId_nonExistentSect_归空`() {
        assertEquals("", purifyStaleActiveSectId("sect_dead", worldSects, emptySet()))
    }

    @Test
    fun `purifyStaleActiveSectId_lostSect_归空`() {
        // 宗门存在但玩家已失守（非玩家持有）→ 残留 id 归空
        assertEquals("", purifyStaleActiveSectId("sect_3", worldSects, emptySet()))
    }

    @Test
    fun `purifyStaleActiveSectId_occupiedSect_保留`() {
        assertEquals("sect_2", purifyStaleActiveSectId("sect_2", worldSects, emptySet()))
    }

    @Test
    fun `purifyStaleActiveSectId_playerSect_保留`() {
        assertEquals("player_sect", purifyStaleActiveSectId("player_sect", worldSects, emptySet()))
    }

    @Test
    fun `purifyStaleActiveSectId_emptyWorldSects_归空`() {
        // 世界损坏待重生——任何残留 id 必无效
        assertEquals("", purifyStaleActiveSectId("sect_1", emptyList(), emptySet()))
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
        val norm = normalizeOrphanBuildingSectIds(buildings, emptyList(), worldSects, emptySet())
        assertEquals("", norm.buildings[0].sectId)

        // 第二步：溢出迁移（按归一化后的 sectId 分组）→ 造价低的灵矿场被拆除
        val gd = GameData(placedBuildings = norm.buildings)
        val result = computeBuildingOverflowMigration(norm.buildings, gd, buildingConfigService)
        assertEquals(listOf("player_alchemy"), result.kept.map { it.instanceId })
        assertEquals(listOf("orphan_mine"), result.demolished.map { it.instanceId })
        assertEquals(1500L, result.totalRefund)
    }

    // ================================================================
    // filterLegacyTianshuHalls — 旧档天枢殿识别（占地尺寸与当前配置不符）
    // ================================================================

    @Test
    fun `filterLegacyTianshuHalls_旧尺寸天枢殿被识别`() {
        val buildings = listOf(
            GridBuildingData(displayName = "天枢殿", gridX = 5, gridY = 5,
                width = 6, height = 3, instanceId = "legacy_tianshu"),
            GridBuildingData(displayName = "炼丹炉", gridX = 0, gridY = 0,
                width = 4, height = 3, instanceId = "alchemy")
        )
        val legacy = filterLegacyTianshuHalls(buildings) { 18 to 13 }
        assertEquals("旧尺寸天枢殿（6×3 ≠ 当前 18×13）应被识别", listOf("legacy_tianshu"), legacy.map { it.instanceId })
    }

    @Test
    fun `filterLegacyTianshuHalls_当前尺寸天枢殿不识别`() {
        val buildings = listOf(
            GridBuildingData(displayName = "天枢殿", gridX = 5, gridY = 5,
                width = 18, height = 13, instanceId = "new_tianshu")
        )
        val legacy = filterLegacyTianshuHalls(buildings) { 18 to 13 }
        assertTrue("当前尺寸天枢殿（18×13）不应被识别为旧档遗留", legacy.isEmpty())
    }

    @Test
    fun `filterLegacyTianshuHalls_无天枢殿返回空`() {
        val buildings = listOf(
            GridBuildingData(displayName = "灵田", gridX = 0, gridY = 0, width = 1, height = 1, instanceId = "field")
        )
        val legacy = filterLegacyTianshuHalls(buildings) { 18 to 13 }
        assertTrue("无天枢殿时返回空列表", legacy.isEmpty())
    }
}

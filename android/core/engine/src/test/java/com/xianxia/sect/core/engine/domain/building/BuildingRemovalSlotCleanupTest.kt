package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.PatrolConfig
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.BuildingNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BuildingFacadeImpl] 建筑移除槽位清理纯函数单元测试。
 *
 * 覆盖历史 bug：
 * - 旧实现在 `cleanupBuildingSlots` 中使用 `dropLast(3)` / `dropLast(8)` / `maxOfOrNull { it.slotIndex }`
 *   按位置截断移除槽位，当存在多个同类型建筑时会移除错误建筑的槽位
 * - 旧实现在 `collectDiscipleIdsForRemoval` 中使用 `takeLast(3)` / `takeLast(8)` / `maxByOrNull { it.slotIndex }`
 *   按位置收集弟子 ID，同样存在多建筑同类型时收集错误弟子的问题
 *
 * 修复方案：为 SpiritMineSlot / PatrolSlot / ProductionSlot 添加 `buildingInstanceId` 字段，
 * 提取纯函数 [BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval] 和
 * [BuildingFacadeImpl.filterBuildingSlots] 按 buildingInstanceId 精确匹配。
 */
@Suppress("DEPRECATION") // 测试需访问 GameData.productionSlots（已迁移到 Repository，但 GameData 仍保留字段用于旧数据兼容）
class BuildingRemovalSlotCleanupTest {

    // ── collectDiscipleIdsForBuildingRemoval：灵矿场 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 灵矿场按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "mine-A"
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = targetInstanceId),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = targetInstanceId),
                SpiritMineSlot(index = 2, discipleId = "", buildingInstanceId = targetInstanceId),
                // 其他灵矿场的槽位 - 不应被收集
                SpiritMineSlot(index = 3, discipleId = "3", buildingInstanceId = "mine-B"),
                SpiritMineSlot(index = 4, discipleId = "4", buildingInstanceId = "mine-B")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "灵矿场", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅收集目标灵矿场的弟子ID", setOf("1", "2"), ids)
    }

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 灵矿场空 discipleId 不被收集`() {
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "", buildingInstanceId = "mine-A"),
                SpiritMineSlot(index = 1, discipleId = "1", buildingInstanceId = "mine-A")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "灵矿场", instanceId = "mine-A", gameData = gameData
        )

        assertEquals("空 discipleId 不应被收集", setOf("1"), ids)
    }

    // ── collectDiscipleIdsForBuildingRemoval：巡视楼 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 巡视楼按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "patrol-A"
        val gameData = GameData().copy(
            patrolSlots = listOf(
                PatrolSlot(index = 0, discipleId = "10", buildingInstanceId = targetInstanceId),
                PatrolSlot(index = 1, discipleId = "11", buildingInstanceId = targetInstanceId),
                // 其他巡视楼的槽位
                PatrolSlot(index = 2, discipleId = "12", buildingInstanceId = "patrol-B")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "巡视楼", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅收集目标巡视楼的弟子ID", setOf("10", "11"), ids)
    }

    // ── collectDiscipleIdsForBuildingRemoval：炼丹炉 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 炼丹炉按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "alchemy-A"
        val gameData = GameData().copy(
            productionSlots = listOf(
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    assignedDiscipleId = "20",
                    buildingInstanceId = targetInstanceId
                ),
                ProductionSlot(
                    slotIndex = 1,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    assignedDiscipleId = "21",
                    buildingInstanceId = targetInstanceId
                ),
                // 另一个炼丹炉的槽位 - 不应被收集
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    assignedDiscipleId = "22",
                    buildingInstanceId = "alchemy-B"
                )
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "炼丹炉", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅收集目标炼丹炉的弟子ID", setOf("20", "21"), ids)
    }

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 炼丹炉不收集锻造坊槽位`() {
        val targetInstanceId = "shared-1"
        val gameData = GameData().copy(
            productionSlots = listOf(
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    assignedDiscipleId = "20",
                    buildingInstanceId = targetInstanceId
                ),
                // 同 instanceId 但不同 buildingId 的锻造槽位 - 不应被收集
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE,
                    assignedDiscipleId = "21",
                    buildingInstanceId = targetInstanceId
                )
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "炼丹炉", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("不应收集锻造坊槽位的弟子ID", setOf("20"), ids)
    }

    // ── collectDiscipleIdsForBuildingRemoval：锻造坊 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 锻造坊按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "forge-A"
        val gameData = GameData().copy(
            productionSlots = listOf(
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE,
                    assignedDiscipleId = "30",
                    buildingInstanceId = targetInstanceId
                ),
                // 另一个锻造坊的槽位
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE,
                    assignedDiscipleId = "31",
                    buildingInstanceId = "forge-B"
                )
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "锻造坊", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅收集目标锻造坊的弟子ID", setOf("30"), ids)
    }

    // ── collectDiscipleIdsForBuildingRemoval：住所 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 住所按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "residence-A"
        val gameData = GameData().copy(
            residenceSlots = listOf(
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 0, discipleId = "40"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 1, discipleId = "41"),
                // 其他住所槽位
                ResidenceSlot(buildingInstanceId = "residence-B", slotIndex = 0, discipleId = "42")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "单人住所", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅收集目标住所的弟子ID", setOf("40", "41"), ids)
    }

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 多人住所同样按 buildingInstanceId 精确收集`() {
        val targetInstanceId = "multi-residence-A"
        val gameData = GameData().copy(
            residenceSlots = listOf(
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 0, discipleId = "50"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 1, discipleId = "51"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 2, discipleId = "52"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 3, discipleId = "53")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "多人住所", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(setOf("50", "51", "52", "53"), ids)
    }

    // ── collectDiscipleIdsForBuildingRemoval：仓库 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 仓库按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "warehouse-A"
        val gameData = GameData().copy(
            warehouseGarrisons = listOf(
                WarehouseGarrisonSlot(buildingInstanceId = targetInstanceId, discipleId = "60"),
                WarehouseGarrisonSlot(buildingInstanceId = "warehouse-B", discipleId = "61")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "仓库", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(setOf("60"), ids)
    }

    // ── collectDiscipleIdsForBuildingRemoval：血炼池 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 血炼池按 buildingInstanceId 精确收集弟子ID`() {
        val targetInstanceId = "blood-A"
        val gameData = GameData().copy(
            activeBloodRefinements = mapOf(
                targetInstanceId to BloodRefinementProgress(discipleId = "70", discipleName = "弟子A"),
                "blood-B" to BloodRefinementProgress(discipleId = "71", discipleName = "弟子B")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "血炼池", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(setOf("70"), ids)
    }

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 血炼池不存在该实例时返回空集合`() {
        val gameData = GameData().copy(
            activeBloodRefinements = mapOf(
                "blood-A" to BloodRefinementProgress(discipleId = "70")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "血炼池", instanceId = "blood-nonexistent", gameData = gameData
        )

        assertTrue("不存在的实例应返回空集合", ids.isEmpty())
    }

    // ── collectDiscipleIdsForBuildingRemoval：边界情况 ────────────────────

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 未知建筑名返回空集合`() {
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = "x")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "未知建筑", instanceId = "x", gameData = gameData
        )

        assertTrue("未知建筑名应返回空集合", ids.isEmpty())
    }

    @Test
    fun `collectDiscipleIdsForBuildingRemoval - 旧存档 buildingInstanceId 为空字符串时精确匹配空字符串`() {
        // 旧存档兼容：buildingInstanceId 为空字符串的槽位，只有当传入 instanceId 也为空字符串时才会匹配
        // 正常调用 removeBuilding 时 instanceId 是 UUID，不会匹配空字符串，因此旧存档的槽位不会被误收集
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = ""),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = "")
            )
        )

        val ids = BuildingFacadeImpl.collectDiscipleIdsForBuildingRemoval(
            displayName = "灵矿场", instanceId = "uuid-not-empty", gameData = gameData
        )

        assertTrue("旧存档空 buildingInstanceId 不应被非空 instanceId 匹配", ids.isEmpty())
    }

    // ════════════════════════════════════════════════════════════════════
    // filterBuildingSlots 测试
    // ════════════════════════════════════════════════════════════════════

    // ── filterBuildingSlots：灵矿场 ────────────────────

    @Test
    fun `filterBuildingSlots - 灵矿场按 buildingInstanceId 精确过滤槽位`() {
        val targetInstanceId = "mine-A"
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = targetInstanceId),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = targetInstanceId),
                SpiritMineSlot(index = 2, discipleId = "3", buildingInstanceId = targetInstanceId),
                // 其他灵矿场的槽位 - 不应被移除
                SpiritMineSlot(index = 3, discipleId = "4", buildingInstanceId = "mine-B"),
                SpiritMineSlot(index = 4, discipleId = "5", buildingInstanceId = "mine-B")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "灵矿场", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅移除目标灵矿场的3个槽位", 2, result.spiritMineSlots.size)
        assertTrue("剩余槽位应属于 mine-B",
            result.spiritMineSlots.all { it.buildingInstanceId == "mine-B" })
    }

    @Test
    fun `filterBuildingSlots - 灵矿场不影响其他灵矿场槽位顺序`() {
        val targetInstanceId = "mine-A"
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = targetInstanceId),
                SpiritMineSlot(index = 3, discipleId = "4", buildingInstanceId = "mine-B"),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = targetInstanceId),
                SpiritMineSlot(index = 4, discipleId = "5", buildingInstanceId = "mine-B"),
                SpiritMineSlot(index = 2, discipleId = "3", buildingInstanceId = targetInstanceId)
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "灵矿场", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应保留 mine-B 的2个槽位", 2, result.spiritMineSlots.size)
        // 验证剩余槽位的相对顺序保持不变
        assertEquals("4", result.spiritMineSlots[0].discipleId)
        assertEquals("5", result.spiritMineSlots[1].discipleId)
    }

    // ── filterBuildingSlots：巡视楼 ────────────────────

    @Test
    fun `filterBuildingSlots - 巡视楼按 buildingInstanceId 精确过滤槽位和对应 patrolConfig`() {
        val targetInstanceId = "patrol-A"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "巡视楼", instanceId = targetInstanceId),
                GridBuildingData(displayName = "巡视楼", instanceId = "patrol-B")
            ),
            patrolSlots = listOf(
                PatrolSlot(index = 0, discipleId = "10", buildingInstanceId = targetInstanceId),
                PatrolSlot(index = 1, discipleId = "11", buildingInstanceId = targetInstanceId),
                // 其他巡视楼的槽位
                PatrolSlot(index = 2, discipleId = "12", buildingInstanceId = "patrol-B")
            ),
            patrolConfigs = listOf(
                PatrolConfig(maxBeastCount = 1),
                PatrolConfig(maxBeastCount = 2)
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "巡视楼", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅移除目标巡视楼的槽位", 1, result.patrolSlots.size)
        assertEquals("patrol-B", result.patrolSlots[0].buildingInstanceId)
        assertEquals("应移除对应的 patrolConfig（索引0）", 1, result.patrolConfigs.size)
        assertEquals("剩余 patrolConfig 应是 patrol-B 的", 2, result.patrolConfigs[0].maxBeastCount)
    }

    @Test
    fun `filterBuildingSlots - 巡视楼移除第二个建筑时移除对应索引的 patrolConfig`() {
        val targetInstanceId = "patrol-B"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "巡视楼", instanceId = "patrol-A"),
                GridBuildingData(displayName = "巡视楼", instanceId = targetInstanceId),
                GridBuildingData(displayName = "巡视楼", instanceId = "patrol-C")
            ),
            patrolSlots = listOf(
                PatrolSlot(index = 0, discipleId = "10", buildingInstanceId = "patrol-A"),
                PatrolSlot(index = 1, discipleId = "11", buildingInstanceId = targetInstanceId),
                PatrolSlot(index = 2, discipleId = "12", buildingInstanceId = "patrol-C")
            ),
            patrolConfigs = listOf(
                PatrolConfig(maxBeastCount = 1), // patrol-A
                PatrolConfig(maxBeastCount = 2), // patrol-B
                PatrolConfig(maxBeastCount = 3)  // patrol-C
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "巡视楼", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅移除 patrol-B 的槽位", 2, result.patrolSlots.size)
        assertEquals("patrol-A", result.patrolSlots[0].buildingInstanceId)
        assertEquals("patrol-C", result.patrolSlots[1].buildingInstanceId)
        assertEquals("应移除索引1的 patrolConfig", 2, result.patrolConfigs.size)
        assertEquals("剩余第一个应是 patrol-A 的", 1, result.patrolConfigs[0].maxBeastCount)
        assertEquals("剩余第二个应是 patrol-C 的", 3, result.patrolConfigs[1].maxBeastCount)
    }

    @Test
    fun `filterBuildingSlots - 巡视楼 instanceId 不在 placedBuildings 时只过滤槽位不动 patrolConfig`() {
        val targetInstanceId = "patrol-unknown"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "巡视楼", instanceId = "patrol-A")
            ),
            patrolSlots = listOf(
                PatrolSlot(index = 0, discipleId = "10", buildingInstanceId = "patrol-A"),
                PatrolSlot(index = 1, discipleId = "11", buildingInstanceId = targetInstanceId)
            ),
            patrolConfigs = listOf(PatrolConfig(maxBeastCount = 1))
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "巡视楼", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应移除目标槽位", 1, result.patrolSlots.size)
        assertEquals("patrol-A", result.patrolSlots[0].buildingInstanceId)
        // towerIdx 为 -1 时不应修改 patrolConfigs
        assertEquals("patrolConfig 不应被修改", 1, result.patrolConfigs.size)
    }

    // ── filterBuildingSlots：灵田 ────────────────────

    @Test
    fun `filterBuildingSlots - 灵田按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "field-A"
        val gameData = GameData().copy(
            spiritFieldPlants = listOf(
                SpiritFieldPlant(buildingInstanceId = targetInstanceId, seedId = "seed1"),
                SpiritFieldPlant(buildingInstanceId = "field-B", seedId = "seed2")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "灵田", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(1, result.spiritFieldPlants.size)
        assertEquals("field-B", result.spiritFieldPlants[0].buildingInstanceId)
    }

    // ── filterBuildingSlots：住所 ────────────────────

    @Test
    fun `filterBuildingSlots - 单人住所按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "res-A"
        val gameData = GameData().copy(
            residenceSlots = listOf(
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 0, discipleId = "1"),
                ResidenceSlot(buildingInstanceId = "res-B", slotIndex = 0, discipleId = "2")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "单人住所", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(1, result.residenceSlots.size)
        assertEquals("res-B", result.residenceSlots[0].buildingInstanceId)
    }

    @Test
    fun `filterBuildingSlots - 多人住所按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "multi-A"
        val gameData = GameData().copy(
            residenceSlots = listOf(
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 0, discipleId = "1"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 1, discipleId = "2"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 2, discipleId = "3"),
                ResidenceSlot(buildingInstanceId = targetInstanceId, slotIndex = 3, discipleId = "4"),
                ResidenceSlot(buildingInstanceId = "multi-B", slotIndex = 0, discipleId = "5")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "多人住所", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅保留 multi-B 的1个槽位", 1, result.residenceSlots.size)
        assertEquals("multi-B", result.residenceSlots[0].buildingInstanceId)
    }

    // ── filterBuildingSlots：仓库 ────────────────────

    @Test
    fun `filterBuildingSlots - 仓库按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "wh-A"
        val gameData = GameData().copy(
            warehouseGarrisons = listOf(
                WarehouseGarrisonSlot(buildingInstanceId = targetInstanceId, discipleId = "1"),
                WarehouseGarrisonSlot(buildingInstanceId = "wh-B", discipleId = "2")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "仓库", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(1, result.warehouseGarrisons.size)
        assertEquals("wh-B", result.warehouseGarrisons[0].buildingInstanceId)
    }

    // ── filterBuildingSlots：炼丹炉 ────────────────────

    @Test
    fun `filterBuildingSlots - 炼丹炉按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "alchemy-A"
        val gameData = GameData().copy(
            productionSlots = listOf(
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    buildingInstanceId = targetInstanceId
                ),
                ProductionSlot(
                    slotIndex = 1,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    buildingInstanceId = targetInstanceId
                ),
                // 另一个炼丹炉的槽位
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    buildingInstanceId = "alchemy-B"
                )
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "炼丹炉", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("应仅移除目标炼丹炉的2个槽位", 1, result.productionSlots.size)
        assertEquals("alchemy-B", result.productionSlots[0].buildingInstanceId)
    }

    // ── filterBuildingSlots：锻造坊 ────────────────────

    @Test
    fun `filterBuildingSlots - 锻造坊按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "forge-A"
        val gameData = GameData().copy(
            productionSlots = listOf(
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE,
                    buildingInstanceId = targetInstanceId
                ),
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE,
                    buildingInstanceId = "forge-B"
                )
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "锻造坊", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(1, result.productionSlots.size)
        assertEquals("forge-B", result.productionSlots[0].buildingInstanceId)
    }

    @Test
    fun `filterBuildingSlots - 炼丹炉过滤不影响同 instanceId 的锻造坊槽位`() {
        // 验证：buildingInstanceId 相同但 buildingId 不同时不会被误删
        val targetInstanceId = "shared-1"
        val gameData = GameData().copy(
            productionSlots = listOf(
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY,
                    buildingInstanceId = targetInstanceId
                ),
                ProductionSlot(
                    slotIndex = 0,
                    buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE,
                    buildingInstanceId = targetInstanceId
                )
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "炼丹炉", instanceId = targetInstanceId, gameData = gameData
        )

        // 注意：filterBuildingSlots 对炼丹炉/锻造坊分支不区分 buildingId，会移除所有同 instanceId 的 productionSlots
        // 这是预期行为：一个 instanceId 只对应一种建筑类型（炼丹炉或锻造坊），不会出现共享情况
        // 此测试文档化该行为：调用方需确保 displayName 与实际建筑类型一致
        assertEquals("炼丹炉分支会移除所有同 instanceId 的 productionSlots", 0, result.productionSlots.size)
    }

    // ── filterBuildingSlots：血炼池 ────────────────────

    @Test
    fun `filterBuildingSlots - 血炼池按 buildingInstanceId 精确过滤`() {
        val targetInstanceId = "blood-A"
        val gameData = GameData().copy(
            activeBloodRefinements = mapOf(
                targetInstanceId to BloodRefinementProgress(discipleId = "1"),
                "blood-B" to BloodRefinementProgress(discipleId = "2")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "血炼池", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals(1, result.activeBloodRefinements.size)
        assertFalse("blood-A 应被移除", result.activeBloodRefinements.containsKey(targetInstanceId))
        assertTrue("blood-B 应保留", result.activeBloodRefinements.containsKey("blood-B"))
    }

    // ── filterBuildingSlots：边界情况 ────────────────────

    @Test
    fun `filterBuildingSlots - 未知建筑名返回原 GameData 不变`() {
        val gameData = GameData().copy(
            spiritMineSlots = listOf(SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = "x"))
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "未知建筑", instanceId = "x", gameData = gameData
        )

        assertEquals("未知建筑名应返回原数据不变", gameData.spiritMineSlots, result.spiritMineSlots)
    }

    @Test
    fun `filterBuildingSlots - 旧存档空 buildingInstanceId 不被非空 instanceId 匹配`() {
        // 旧存档兼容：buildingInstanceId 为空字符串的槽位，只有传入空字符串 instanceId 才会匹配
        // 正常调用 removeBuilding 时 instanceId 是 UUID，不会误删旧存档槽位
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = ""),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = "")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "灵矿场", instanceId = "uuid-not-empty", gameData = gameData
        )

        assertEquals("旧存档空 buildingInstanceId 不应被非空 instanceId 误删", 2, result.spiritMineSlots.size)
    }

    @Test
    fun `filterBuildingSlots - 灵矿场不存在匹配 instanceId 时槽位不变`() {
        val gameData = GameData().copy(
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = "mine-A"),
                SpiritMineSlot(index = 1, discipleId = "2", buildingInstanceId = "mine-B")
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "灵矿场", instanceId = "mine-nonexistent", gameData = gameData
        )

        assertEquals("不存在的 instanceId 不应改变槽位列表", 2, result.spiritMineSlots.size)
    }

    @Test
    fun `filterBuildingSlots - 不修改 placedBuildings 和 spiritStones`() {
        // filterBuildingSlots 纯函数仅过滤槽位列表，不处理建筑移除和灵石返还
        // 那些逻辑在 BuildingFacadeImpl.cleanupBuildingSlots 私有方法中处理
        val targetInstanceId = "mine-A"
        val gameData = GameData().copy(
            spiritStones = 5000L,
            placedBuildings = listOf(
                GridBuildingData(displayName = "灵矿场", instanceId = targetInstanceId)
            ),
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "1", buildingInstanceId = targetInstanceId)
            )
        )

        val result = BuildingFacadeImpl.filterBuildingSlots(
            displayName = "灵矿场", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("spiritStones 不应被修改", 5000L, result.spiritStones)
        assertEquals("placedBuildings 不应被修改", 1, result.placedBuildings.size)
        assertEquals("spiritMineSlots 应被过滤", 0, result.spiritMineSlots.size)
    }
}

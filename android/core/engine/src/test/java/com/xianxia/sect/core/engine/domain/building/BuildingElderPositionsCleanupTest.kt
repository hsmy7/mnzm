package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.production.BuildingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * [SlotGroup.ElderPositions] 长老职务清理纯函数单元测试。
 *
 * ElderSlots 为按建筑类型的全局单槽（无 buildingInstanceId），
 * 语义：仅当该类型最后一座建筑被拆除时才清空对应职务字段，长老弟子回归空闲。
 */
class BuildingElderPositionsCleanupTest {

    companion object {
        private fun collectDiscipleIdsForTest(
            displayName: String, instanceId: String, gameData: GameData
        ): Set<String> {
            val feature = BuildingFeatureRegistry.findByDisplayName(displayName) ?: return emptySet()
            return feature.slotGroups.flatMap { it.collectDiscipleIds(gameData, instanceId, feature) }.toSet()
        }

        private fun filterBuildingSlotsForTest(
            displayName: String, instanceId: String, gameData: GameData
        ): GameData {
            val feature = BuildingFeatureRegistry.findByDisplayName(displayName) ?: return gameData
            var gd = gameData
            for (group in feature.slotGroups) {
                gd = group.filterFromGameData(gd, instanceId, feature)
            }
            return gd
        }

        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            if (BuildingFeatureRegistry.findByDisplayName("炼丹炉") == null) {
                listOf(
                    BuildingFeature("alchemy", "炼丹炉", BuildingType.ALCHEMY,
                        listOf(SlotGroup.ProductionSlotGroup(), SlotGroup.ElderPositions.ALCHEMY)),
                    BuildingFeature("spirit_mine", "灵矿场", BuildingType.MINING,
                        listOf(SlotGroup.SpiritMine(), SlotGroup.ElderPositions.SPIRIT_MINE)),
                    BuildingFeature("wen_dao_peak", "问道塔", BuildingType.WEN_DAO_PEAK,
                        listOf(SlotGroup.ElderPositions.WEN_DAO_PEAK)),
                ).forEach { BuildingFeatureRegistry.register(it) }
            }
        }
    }

    @Test
    fun `ElderPositions - 最后一座炼丹炉拆除时清空炼丹长老与亲传弟子`() {
        val targetInstanceId = "alchemy-A"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "炼丹炉", instanceId = targetInstanceId)
            ),
            elderSlots = ElderSlots(
                alchemyElder = "1",
                alchemyDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "2"))
            )
        )

        val result = filterBuildingSlotsForTest(
            displayName = "炼丹炉", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("最后一座炼丹炉拆除应清空 alchemyElder", "", result.elderSlots.alchemyElder)
        assertTrue("亲传弟子槽应清空", result.elderSlots.alchemyDisciples.none { it.isActive })
    }

    @Test
    fun `ElderPositions - 同类型第二座存在时保留长老职位`() {
        val targetInstanceId = "alchemy-A"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "炼丹炉", instanceId = targetInstanceId),
                GridBuildingData(displayName = "炼丹炉", instanceId = "alchemy-B")
            ),
            elderSlots = ElderSlots(alchemyElder = "1")
        )

        val result = filterBuildingSlotsForTest(
            displayName = "炼丹炉", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("仍有其他炼丹炉，长老应保留", "1", result.elderSlots.alchemyElder)
    }

    @Test
    fun `ElderPositions - collectDiscipleIds 收集长老与亲传弟子`() {
        val gameData = GameData().copy(
            elderSlots = ElderSlots(
                alchemyElder = "1",
                alchemyDisciples = listOf(
                    DirectDiscipleSlot(index = 0, discipleId = "2"),
                    DirectDiscipleSlot(index = 1, discipleId = "")
                )
            )
        )

        val ids = collectDiscipleIdsForTest(
            displayName = "炼丹炉", instanceId = "alchemy-A", gameData = gameData
        )

        assertEquals("应收集长老与活跃亲传弟子", setOf("1", "2"), ids)
    }

    @Test
    fun `ElderPositions - 灵矿场拆除清空采矿执事弟子`() {
        val targetInstanceId = "mine-A"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "灵矿场", instanceId = targetInstanceId)
            ),
            elderSlots = ElderSlots(
                spiritMineDeaconDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "1"))
            )
        )

        val result = filterBuildingSlotsForTest(
            displayName = "灵矿场", instanceId = targetInstanceId, gameData = gameData
        )

        assertTrue("采矿执事弟子应清空", result.elderSlots.spiritMineDeaconDisciples.none { it.isActive })
    }

    @Test
    fun `ElderPositions - 问道塔拆除清空外门长老与传道长老`() {
        val targetInstanceId = "wdp-A"
        val gameData = GameData().copy(
            placedBuildings = listOf(
                GridBuildingData(displayName = "问道塔", instanceId = targetInstanceId)
            ),
            elderSlots = ElderSlots(
                outerElder = "1",
                preachingElder = "2",
                preachingMasters = listOf(DirectDiscipleSlot(index = 0, discipleId = "3"))
            )
        )

        val result = filterBuildingSlotsForTest(
            displayName = "问道塔", instanceId = targetInstanceId, gameData = gameData
        )

        assertEquals("", result.elderSlots.outerElder)
        assertEquals("", result.elderSlots.preachingElder)
        assertTrue(result.elderSlots.preachingMasters.none { it.isActive })
    }
}

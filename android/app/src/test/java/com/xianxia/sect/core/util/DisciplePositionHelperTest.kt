package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DisciplePositionHelperTest {

    // ==================== 辅助方法 ====================

    private fun buildGameData(
        elderSlots: ElderSlots = ElderSlots(),
        spiritMineSlots: List<SpiritMineSlot> = emptyList()
    ): GameData = GameData().copy(
        elderSlots = elderSlots,
        spiritMineSlots = spiritMineSlots
    )

    private fun discipleSlot(discipleId: String) = DirectDiscipleSlot(
        discipleId = discipleId,
        discipleName = "弟子$discipleId"
    )

    // ==================== getDisciplePosition ====================

    @Test
    fun getDisciplePosition_viceSectMaster_returns副掌门() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(viceSectMaster = "d1")
        )
        assertEquals("副掌门", DisciplePositionHelper.getDisciplePosition("d1", gameData))
    }

    @Test
    fun getDisciplePosition_herbGardenElder_returns灵植阁长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(herbGardenElder = "d2")
        )
        assertEquals("灵植阁长老", DisciplePositionHelper.getDisciplePosition("d2", gameData))
    }

    @Test
    fun getDisciplePosition_alchemyElder_returns炼丹炉长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(alchemyElder = "d3")
        )
        assertEquals("炼丹炉长老", DisciplePositionHelper.getDisciplePosition("d3", gameData))
    }

    @Test
    fun getDisciplePosition_forgeElder_returns锻造坊长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(forgeElder = "d4")
        )
        assertEquals("锻造坊长老", DisciplePositionHelper.getDisciplePosition("d4", gameData))
    }

    @Test
    fun getDisciplePosition_outerElder_returns外门执事() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(outerElder = "d5")
        )
        assertEquals("外门执事", DisciplePositionHelper.getDisciplePosition("d5", gameData))
    }

    @Test
    fun getDisciplePosition_preachingElder_returns问道塔传道长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(preachingElder = "d6")
        )
        assertEquals("问道塔传道长老", DisciplePositionHelper.getDisciplePosition("d6", gameData))
    }

    @Test
    fun getDisciplePosition_lawEnforcementElder_returns执法长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(lawEnforcementElder = "d7")
        )
        assertEquals("执法长老", DisciplePositionHelper.getDisciplePosition("d7", gameData))
    }

    @Test
    fun getDisciplePosition_innerElder_returns内门执事() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(innerElder = "d8")
        )
        assertEquals("内门执事", DisciplePositionHelper.getDisciplePosition("d8", gameData))
    }

    @Test
    fun getDisciplePosition_qingyunPreachingElder_returns青云塔传道长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(qingyunPreachingElder = "d9")
        )
        assertEquals("青云塔传道长老", DisciplePositionHelper.getDisciplePosition("d9", gameData))
    }

    @Test
    fun getDisciplePosition_recruitingElder_returns纳徒长老() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(recruitingElder = "d18")
        )
        assertEquals("纳徒长老", DisciplePositionHelper.getDisciplePosition("d18", gameData))
    }

    @Test
    fun getDisciplePosition_preachingMaster_returns问道塔传道师() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(preachingMasters = listOf(discipleSlot("d10")))
        )
        assertEquals("问道塔传道师", DisciplePositionHelper.getDisciplePosition("d10", gameData))
    }

    @Test
    fun getDisciplePosition_qingyunPreachingMaster_returns青云塔传道师() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(qingyunPreachingMasters = listOf(discipleSlot("d11")))
        )
        assertEquals("青云塔传道师", DisciplePositionHelper.getDisciplePosition("d11", gameData))
    }

    @Test
    fun getDisciplePosition_herbGardenDisciple_returns灵植阁亲传弟子() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(herbGardenDisciples = listOf(discipleSlot("d12")))
        )
        assertEquals("灵植阁亲传弟子", DisciplePositionHelper.getDisciplePosition("d12", gameData))
    }

    @Test
    fun getDisciplePosition_alchemyDisciple_returns炼丹炉亲传弟子() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(alchemyDisciples = listOf(discipleSlot("d13")))
        )
        assertEquals("炼丹炉亲传弟子", DisciplePositionHelper.getDisciplePosition("d13", gameData))
    }

    @Test
    fun getDisciplePosition_forgeDisciple_returns锻造坊亲传弟子() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(forgeDisciples = listOf(discipleSlot("d14")))
        )
        assertEquals("锻造坊亲传弟子", DisciplePositionHelper.getDisciplePosition("d14", gameData))
    }

    @Test
    fun getDisciplePosition_lawEnforcementDisciple_returns执法弟子() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(lawEnforcementDisciples = listOf(discipleSlot("d15")))
        )
        assertEquals("执法弟子", DisciplePositionHelper.getDisciplePosition("d15", gameData))
    }

    @Test
    fun getDisciplePosition_spiritMineDisciple_returns采矿弟子() {
        val gameData = buildGameData(
            spiritMineSlots = listOf(SpiritMineSlot(discipleId = "d16", discipleName = "弟子d16"))
        )
        assertEquals("采矿弟子", DisciplePositionHelper.getDisciplePosition("d16", gameData))
    }

    @Test
    fun getDisciplePosition_spiritMineDeacon_returns灵矿执事() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(spiritMineDeaconDisciples = listOf(discipleSlot("d17")))
        )
        assertEquals("灵矿执事", DisciplePositionHelper.getDisciplePosition("d17", gameData))
    }

    @Test
    fun getDisciplePosition_unknownDisciple_returnsNull() {
        val gameData = buildGameData()
        assertNull(DisciplePositionHelper.getDisciplePosition("unknown", gameData))
    }

    // ==================== hasDisciplePosition ====================

    @Test
    fun hasDisciplePosition_withPosition_returnsTrue() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(viceSectMaster = "d1")
        )
        assertTrue(DisciplePositionHelper.hasDisciplePosition("d1", gameData))
    }

    @Test
    fun hasDisciplePosition_withoutPosition_returnsFalse() {
        val gameData = buildGameData()
        assertFalse(DisciplePositionHelper.hasDisciplePosition("unknown", gameData))
    }

    // ==================== isReserveDisciple ====================

    @Test
    fun isReserveDisciple_lawEnforcementReserve_returnsTrue() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(lawEnforcementReserveDisciples = listOf(discipleSlot("r1")))
        )
        assertTrue(DisciplePositionHelper.isReserveDisciple("r1", gameData))
    }

    @Test
    fun isReserveDisciple_herbGardenReserve_returnsTrue() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(herbGardenReserveDisciples = listOf(discipleSlot("r2")))
        )
        assertTrue(DisciplePositionHelper.isReserveDisciple("r2", gameData))
    }

    @Test
    fun isReserveDisciple_alchemyReserve_returnsTrue() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(alchemyReserveDisciples = listOf(discipleSlot("r3")))
        )
        assertTrue(DisciplePositionHelper.isReserveDisciple("r3", gameData))
    }

    @Test
    fun isReserveDisciple_forgeReserve_returnsTrue() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(forgeReserveDisciples = listOf(discipleSlot("r4")))
        )
        assertTrue(DisciplePositionHelper.isReserveDisciple("r4", gameData))
    }

    @Test
    fun isReserveDisciple_nonReserve_returnsFalse() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(viceSectMaster = "d1")
        )
        assertFalse(DisciplePositionHelper.isReserveDisciple("d1", gameData))
    }

    // ==================== isPositionWorkStatus ====================

    @Test
    fun isPositionWorkStatus_viceSectMaster_returnsTrue() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(viceSectMaster = "d1")
        )
        assertTrue(DisciplePositionHelper.isPositionWorkStatus("d1", gameData))
    }

    @Test
    fun isPositionWorkStatus_herbGardenElder_returnsFalse() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(herbGardenElder = "d2")
        )
        assertFalse(DisciplePositionHelper.isPositionWorkStatus("d2", gameData))
    }

    // ==================== getWorkStatusPositionIds ====================

    @Test
    fun getWorkStatusPositionIds_returnsListOfDiscipleIdsInWorkStatus() {
        val gameData = buildGameData(
            elderSlots = ElderSlots(
                viceSectMaster = "d1",
                preachingElder = "d6",
                qingyunPreachingElder = "d9",
                preachingMasters = listOf(discipleSlot("d10")),
                qingyunPreachingMasters = listOf(discipleSlot("d11")),
                lawEnforcementDisciples = listOf(discipleSlot("d15")),
                spiritMineDeaconDisciples = listOf(discipleSlot("d17"))
            )
        )
        val ids = DisciplePositionHelper.getWorkStatusPositionIds(gameData)
        assertTrue(ids.contains("d1"))
        assertTrue(ids.contains("d6"))
        assertTrue(ids.contains("d9"))
        assertTrue(ids.contains("d10"))
        assertTrue(ids.contains("d11"))
        assertTrue(ids.contains("d15"))
        assertTrue(ids.contains("d17"))
    }
}

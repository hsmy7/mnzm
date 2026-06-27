package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * 灵矿月度结算测试 — 验证灵矿产出从域路由移至月度事件后的行为。
 */
class SpiritMineMonthlySettlementTest {

    private lateinit var stateStore: GameStateStore
    private lateinit var settlement: CultivationSettlement

    @Before
    fun setUp() {
        stateStore = mock(GameStateStore::class.java)
        settlement = CultivationSettlement(
            stateStore = stateStore,
            inventorySystem = mock(com.xianxia.sect.core.engine.system.InventorySystem::class.java),
            inventoryConfig = mock(com.xianxia.sect.core.config.InventoryConfig::class.java),
            battleSystem = mock(com.xianxia.sect.core.engine.domain.battle.BattleSystem::class.java),
            productionCoordinator = mock(com.xianxia.sect.core.engine.domain.production.ProductionCoordinator::class.java),
            productionSlotRepository = mock(com.xianxia.sect.core.repository.ProductionSlotRepository::class.java),
            discipleService = mock(com.xianxia.sect.core.engine.domain.disciple.DiscipleService::class.java),
            cultivationCore = mock(CultivationCore::class.java),
            breakthroughHandler = mock(DiscipleBreakthroughHandler::class.java),
            scopeProvider = mock(com.xianxia.sect.core.util.CoroutineScopeProvider::class.java)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Fingerprint
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `computeSpiritMineFingerprint_empty vs miner`() {
        val tables = DiscipleTables()
        // 添加一个弟子用于有矿工的场景
        val disciple = Disciple(id = "1", realm = 3, isAlive = true)
        val skills = disciple.skills.copy(mining = 50)
        val d = disciple.copy(skills = skills)
        tables.insert(d)

        val emptySlots = emptyList<SpiritMineSlot>()
        val minerSlots = listOf(SpiritMineSlot(buildingInstanceId = "mine_1", discipleId = "1"))

        val fpEmpty = invokeComputeFingerprint(
            GameData(spiritMineSlots = emptySlots), tables
        )
        val fpMiner = invokeComputeFingerprint(
            GameData(spiritMineSlots = minerSlots), tables
        )

        assertNotEquals("空槽位指纹应与有矿工不同", fpEmpty, fpMiner)
    }

    @Test
    fun `computeSpiritMineFingerprint_policy on vs off`() {
        val tables = DiscipleTables()

        val boostOn = GameData(
            spiritMineSlots = emptyList(),
            sectPolicies = SectPolicies(spiritMineBoost = true)
        )
        val boostOff = GameData(
            spiritMineSlots = emptyList(),
            sectPolicies = SectPolicies(spiritMineBoost = false)
        )

        val fpOn = invokeComputeFingerprint(boostOn, tables)
        val fpOff = invokeComputeFingerprint(boostOff, tables)

        assertNotEquals("政策不同应有不同指纹", fpOn, fpOff)
    }

    // ═══════════════════════════════════════════════════════════════
    // Daily rate
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `calculateSpiritMineDailyRate_no miner returns zero`() {
        val tables = DiscipleTables()
        val data = GameData(spiritMineSlots = emptyList())

        val rate = invokeCalculateDailyRate(data, tables)

        assertEquals(0L, rate)
    }

    @Test
    fun `calculateSpiritMineDailyRate_one miner positive`() {
        val tables = DiscipleTables()
        // 添加一个弟子
        val disciple = Disciple(id = "1", realm = 3, isAlive = true)
        val skills = disciple.skills.copy(mining = 0)
        val d = disciple.copy(skills = skills)
        tables.insert(d)

        val data = GameData(spiritMineSlots = listOf(
            SpiritMineSlot(buildingInstanceId = "mine_1", discipleId = "1")
        ))

        val rate = invokeCalculateDailyRate(data, tables)

        assertTrue("至少一个矿工时日产出应大于零, got=$rate", rate > 0)
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase snapshot
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `recordPhaseSnapshot does not throw`() {
        val tables = DiscipleTables()
        val disciple = Disciple(id = "1", realm = 3, isAlive = true)
        val skills = disciple.skills.copy(mining = 0)
        val d = disciple.copy(skills = skills)
        tables.insert(d)

        val data = GameData(
            spiritMineSlots = listOf(
                SpiritMineSlot(buildingInstanceId = "mine_1", discipleId = "1")
            ),
            gamePhase = 1
        )

        val state = mock(MutableGameState::class.java)
        org.mockito.Mockito.`when`(state.gameData).thenReturn(data)
        org.mockito.Mockito.`when`(state.discipleTables).thenReturn(tables)

        // 应不抛异常
        settlement.recordPhaseSnapshot(state)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper: invoke private methods via reflection
    // ═══════════════════════════════════════════════════════════════

    private fun invokeComputeFingerprint(
        data: GameData, tables: DiscipleTables
    ): Int {
        val method = CultivationSettlement::class.java
            .getDeclaredMethod("computeSpiritMineFingerprint",
                GameData::class.java, DiscipleTables::class.java)
        method.isAccessible = true
        return method.invoke(settlement, data, tables) as Int
    }

    private fun invokeCalculateDailyRate(
        data: GameData, tables: DiscipleTables
    ): Long {
        val method = CultivationSettlement::class.java
            .getDeclaredMethod("calculateSpiritMineDailyRate",
                GameData::class.java, DiscipleTables::class.java)
        method.isAccessible = true
        return method.invoke(settlement, data, tables) as Long
    }
}

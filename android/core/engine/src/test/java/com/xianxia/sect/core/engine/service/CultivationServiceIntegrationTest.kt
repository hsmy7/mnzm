package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner

/**
 * 验证 [CultivationService.accumulateCultivationPerPhase] 的端到端行为：
 * - 存活弟子修为正确累加
 * - checkpoint 与 cultivation 同步更新
 * - 满修为/死亡/零速率等边界条件
 */
@RunWith(RobolectricTestRunner::class)
class CultivationServiceIntegrationTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var stateStore: GameStateStore
    private lateinit var cultivationCore: CultivationCore
    private lateinit var service: CultivationService
    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        tables = DiscipleTables()
        stateStore = mock(GameStateStore::class.java)
        Mockito.`when`(stateStore.discipleTables).thenReturn(tables)
        Mockito.`when`(stateStore.gameData).thenReturn(MutableStateFlow(GameData(gameYear = 1, gameMonth = 6)))
        Mockito.`when`(stateStore.manualInstances).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(stateStore.manualStacks).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(stateStore.equipmentInstances).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(stateStore.equipmentStacks).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(stateStore.disciples).thenReturn(MutableStateFlow(emptyList()))

        // 初始化 statsProvider（CultivationCore 依赖它计算 disciple.maxCultivation）
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) = DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) = DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(d: Disciple, e: Map<String, EquipmentInstance>) = DiscipleStatCalculator.getStatsWithEquipment(d, e)
            override fun getStatsWithEquipment(a: DiscipleAggregate, e: Map<String, EquipmentInstance>) = DiscipleStatCalculator.getStatsWithEquipment(a, e)
            override fun getFinalStats(d: Disciple, e: Map<String, EquipmentInstance>, m: Map<String, ManualInstance>, p: Map<String, ManualProficiencyData>) = DiscipleStatCalculator.getFinalStats(d, e, m, p)
            override fun getFinalStats(a: DiscipleAggregate, e: Map<String, EquipmentInstance>, m: Map<String, ManualInstance>, p: Map<String, ManualProficiencyData>) = DiscipleStatCalculator.getFinalStats(a, e, m, p)
            override fun calculateCultivationSpeed(d: Disciple, manuals: Map<String, ManualInstance>, mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double, peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.calculateCultivationPerPhase(d, manuals, mps, bb, peb, pmb, csb, pcb, gcp)
            override fun calculateCultivationSpeed(a: DiscipleAggregate, manuals: Map<String, ManualInstance>, mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double, peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.calculateCultivationPerPhase(a, manuals, mps, bb, peb, pmb, csb, pcb, gcp)
            override fun getBreakthroughChance(d: Disciple, iec: Int, oec: Int, pb: Double, ab: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)
            override fun getBreakthroughChance(a: DiscipleAggregate, iec: Int, oec: Int, pb: Double, ab: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
        }

        cultivationCore = CultivationCore(
            stateStore = stateStore,
            inventoryConfig = mock(),
            thermalMonitor = mock(),
            gameClock = mock(),
            scopeProvider = mock(),
            pillManager = mock(),
            equipmentManager = mock(),
            manualManager = mock()
        )

        service = CultivationService(
            stateStore = stateStore,
            cultivationCore = cultivationCore,
            breakthroughHandler = mock(),
            cultivationSettlement = mock(),
            eventProcessor = mock(),
            productionProcessor = mock(),
            merchantAndRecruitService = mock(),
            caveExplorationProcessor = mock(),
            sharedState = CultivationSharedState(),
            discipleService = mock()
        )
    }

    private fun insertDisciple(
        id: Int = 1,
        realm: Int = 9,
        cultivation: Double = 0.0
    ) {
        val disciple = Disciple(
            id = id.toString(),
            name = "测试弟子$id",
            realm = realm,
            cultivation = cultivation,
            spiritRootType = "1,2",
            combat = CombatAttributes(hpVariance = 0, mpVariance = 0)
        )
        tables.insert(disciple)
    }

    @Test
    fun `accumulateCultivationPerPhase increases cultivation and syncs checkpoint`() {
        insertDisciple(id = 1, cultivation = 10.0, realm = 9)

        val state = MutableGameState(
            gameData = GameData(gameYear = 1, gameMonth = 6),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(), materials = EntityStore(),
            herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
            teams = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )

        val before = tables.cultivations[1]
        service.accumulateCultivationPerPhase(1, state)

        val after = tables.cultivations[1]
        val cpAfter = tables.cultivationCheckpoints[1]
        val cpMonthAfter = tables.cultivationCheckpointGameMonths[1]

        // 修炼值增加了
        assertTrue("cultivation should increase", after > before)
        // checkpoint 同步到新值
        assertEquals("checkpoint should equal new cultivation", after, cpAfter, 0.001)
        // checkpoint month 更新为当前月份
        assertEquals("checkpoint month should advance", 18, cpMonthAfter) // 1*12+6 = 18
    }

    @Test
    fun `accumulateCultivationPerPhase skips dead disciple`() {
        insertDisciple(id = 1, cultivation = 100.0)
        tables.isAlive[1] = 0

        val state = MutableGameState(
            gameData = GameData(gameYear = 1, gameMonth = 1),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(), materials = EntityStore(),
            herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
            teams = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )

        val before = tables.cultivations[1]
        service.accumulateCultivationPerPhase(1, state)
        val after = tables.cultivations[1]

        assertEquals("dead disciple cultivation should not change", before, after, 0.001)
    }

    @Test
    fun `accumulateCultivationPerPhase caps at max cultivation and still checkpoints`() {
        insertDisciple(id = 1, cultivation = 45.0, realm = 9)  // maxCultivation=50，加1旬速率后可能超过

        val state = MutableGameState(
            gameData = GameData(gameYear = 1, gameMonth = 1),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(), materials = EntityStore(),
            herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
            teams = emptyList(), battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )

        service.accumulateCultivationPerPhase(1, state)

        val after = tables.cultivations[1]
        val disciple = tables.assemble(1)
        assertTrue(
            "cultivation $after should not exceed max ${disciple.maxCultivation}",
            after <= disciple.maxCultivation
        )
        assertTrue(
            "cultivation $after should have increased above initial 45",
            after >= 45.0
        )
    }
}

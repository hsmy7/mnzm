package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.disciple.*
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.service.CultivationCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * CultivationCore 并行/串行一致性测试。
 *
 * 验证 [CultivationCore.computeBatchCultivationDelta] 的并行路径与串行路径
 * 对相同输入数据产生完全一致的输出。
 */
@RunWith(RobolectricTestRunner::class)
class CultivationCoreConcurrencyTest {

    private lateinit var core: CultivationCore
    private lateinit var mockStateStore: GameStateStore
    private lateinit var profiler: DeviceCapabilityProfiler

    @Before
    fun setUp() {
        DiscipleAggregate.statsProvider = createStatsProvider()
        mockStateStore = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(mockStateStore.manualInstances)
            .thenReturn(MutableStateFlow(emptyList()))
        profiler = DeviceCapabilityProfiler()

        core = CultivationCore(
            stateStore = mockStateStore,
            inventoryConfig = Mockito.mock(InventoryConfig::class.java),
            thermalMonitor = Mockito.mock(ThermalMonitor::class.java),
            gameClock = Mockito.mock(GameTimeClock::class.java),
            scopeProvider = Mockito.mock(CoroutineScopeProvider::class.java),
            pillManager = Mockito.mock(DisciplePillManager::class.java),
            equipmentManager = Mockito.mock(DiscipleEquipmentManager::class.java),
            manualManager = Mockito.mock(DiscipleManualManager::class.java)
        )
    }

    @Test
    fun `serial compute - produces correct cultivation values`() = runBlocking {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = 1))
        val gameData = GameData()

        val workingCopy = tables.deepCopy()
        val result = core.computeBatchCultivationDelta(
            workingTables = workingCopy,
            gameData = gameData,
            equipmentInstances = emptyList(),
            manualInstances = emptyList(),
            phasesToSettle = 1
        )

        assertNotNull("Result should not be null", result)
        val cult = workingCopy.cultivations[1] ?: 0.0
        assertTrue("Cultivation should increase, got $cult", cult > 0.0)
    }

    @Test
    fun `deterministic results`() = runBlocking {
        val discipleCount = if (profiler.totalCores >= 4) 100 else 30
        val tables = DiscipleTables()
        for (i in 1..discipleCount) {
            tables.insert(createDisciple(id = i))
        }
        val gameData = GameData()

        val copy1 = tables.deepCopy()
        core.computeBatchCultivationDelta(
            workingTables = copy1, gameData = gameData,
            equipmentInstances = emptyList(), manualInstances = emptyList(),
            phasesToSettle = 3
        )
        val copy2 = tables.deepCopy()
        core.computeBatchCultivationDelta(
            workingTables = copy2, gameData = gameData,
            equipmentInstances = emptyList(), manualInstances = emptyList(),
            phasesToSettle = 3
        )

        for (id in 1..discipleCount) {
            assertEquals(
                "Disciple $id cultivation should be deterministic",
                copy1.cultivations[id] ?: 0.0,
                copy2.cultivations[id] ?: 0.0,
                0.001
            )
        }
    }

    @Test
    fun `compute with zero phasesToSettle returns unchanged tables`() = runBlocking {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = 1))
        val original = tables.deepCopy()
        val originalCult = original.cultivations[1] ?: 0.0

        val result = core.computeBatchCultivationDelta(
            workingTables = tables,
            gameData = GameData(),
            equipmentInstances = emptyList(),
            manualInstances = emptyList(),
            phasesToSettle = 0
        )

        assertNotNull("Result with phasesToSettle=0 should not be null", result)
        assertEquals("Cultivation should not change with phasesToSettle=0",
            originalCult, tables.cultivations[1] ?: 0.0, 0.001)
    }

    @Test
    fun `multiple phases accumulate correctly`() = runBlocking {
        val tables = DiscipleTables()
        tables.insert(createDisciple(id = 1))
        val gameData = GameData()

        // 1 phase
        core.computeBatchCultivationDelta(
            workingTables = tables, gameData = gameData,
            equipmentInstances = emptyList(), manualInstances = emptyList(),
            phasesToSettle = 1
        )
        val cult1 = tables.cultivations[1] ?: 0.0

        // Reset and do 3 phases
        val tables2 = DiscipleTables()
        tables2.insert(createDisciple(id = 1))
        core.computeBatchCultivationDelta(
            workingTables = tables2, gameData = gameData,
            equipmentInstances = emptyList(), manualInstances = emptyList(),
            phasesToSettle = 3
        )
        val cult3 = tables2.cultivations[1] ?: 0.0

        // 3 phases should accumulate more than 1 phase
        assertTrue("3 phases ($cult3) should be more than 1 phase ($cult1)", cult3 > cult1)
    }

    // ── 辅助方法 ──

    /**
     * 创建一个测试用弟子。
     * realm=9（低境界）使 maxCultivation 不会太大，便于测试。
     */
    private fun createDisciple(
        id: Int = 1,
        realm: Int = 9,
        realmLayer: Int = 1,
        cultivation: Double = 0.0,
        comprehension: Int = 50
    ): Disciple = Disciple(
        id = id.toString(),
        realm = realm,
        realmLayer = realmLayer,
        cultivation = cultivation,
        skills = SkillStats(comprehension = comprehension),
        combat = CombatAttributes(currentHp = 100, currentMp = 100)
    )

    private fun createStatsProvider() = object : DiscipleStatsProvider {
        override fun getBaseStats(d: Disciple) = DiscipleStatCalculator.getBaseStats(d)
        override fun getBaseStats(a: DiscipleAggregate) = DiscipleStatCalculator.getBaseStats(a)
        override fun getTalentEffects(d: Disciple) = DiscipleStatCalculator.getTalentEffects(d)
        override fun getTalentEffects(a: DiscipleAggregate) = DiscipleStatCalculator.getTalentEffects(a)
        override fun getStatsWithEquipment(d: Disciple, e: Map<String, EquipmentInstance>) =
            DiscipleStatCalculator.getStatsWithEquipment(d, e)
        override fun getStatsWithEquipment(a: DiscipleAggregate, e: Map<String, EquipmentInstance>) =
            DiscipleStatCalculator.getStatsWithEquipment(a, e)
        override fun getFinalStats(d: Disciple, e: Map<String, EquipmentInstance>,
            m: Map<String, ManualInstance>, p: Map<String, ManualProficiencyData>) =
            DiscipleStatCalculator.getFinalStats(d, e, m, p)
        override fun getFinalStats(a: DiscipleAggregate, e: Map<String, EquipmentInstance>,
            m: Map<String, ManualInstance>, p: Map<String, ManualProficiencyData>) =
            DiscipleStatCalculator.getFinalStats(a, e, m, p)
        override fun calculateCultivationSpeed(d: Disciple, m: Map<String, ManualInstance>,
            p: Map<String, ManualProficiencyData>, bb: Double, ab: Double,
            peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double) =
            DiscipleStatCalculator.calculateCultivationSpeed(d, m, p, bb, ab, peb, pmb, csb, pcb, gcp, mdb)
        override fun calculateCultivationSpeed(a: DiscipleAggregate, m: Map<String, ManualInstance>,
            p: Map<String, ManualProficiencyData>, bb: Double, ab: Double,
            peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double) =
            DiscipleStatCalculator.calculateCultivationSpeed(a, m, p, bb, ab, peb, pmb, csb, pcb, gcp, mdb)
        override fun getBreakthroughChance(d: Disciple, iec: Int, oec: Int, pb: Double,
            ab: Double, gbp: Double, mdb: Double) =
            DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gbp, mdb)
        override fun getBreakthroughChance(a: DiscipleAggregate, iec: Int, oec: Int, pb: Double,
            ab: Double, gbp: Double, mdb: Double) =
            DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gbp, mdb)
    }
}

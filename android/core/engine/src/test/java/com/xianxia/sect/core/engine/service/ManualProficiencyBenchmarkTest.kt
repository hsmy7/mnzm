package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * 功法熟练度批量版性能基准测试（P-1，2026-08-02）。
 *
 * 修复前：每旬热点循环对每个弟子执行 `manualProficiencies.toMutableMap()`（O(D) 全量
 * Map 拷贝）+ `GameData.copy()`（O(D) 深度拷贝）——O(D²)/旬，D=300 时 ~90K 次 Map 插入。
 * 修复后：单弟子只计算条目（O(P)）累积到 pending，循环后单次 Map 构建 + 单次 copy
 * （O(D)）。
 *
 * 断言：批量版耗时 ≤ 单弟子版 50%（Robolectric 噪声较大，阈值保守化——结构性退化
 * （批量版误回全量拷贝）时比值必然接近 1.0）。
 */
@RunWith(RobolectricTestRunner::class)
class ManualProficiencyBenchmarkTest {

    private lateinit var core: CultivationCore

    private val DISCIPLE_COUNT = 300
    private val MANUALS_PER_DISCIPLE = 5

    @Before
    fun setUp() {
        // 与 CultivationCoreTest 一致：注入 stats provider 使 disciple 计算属性可用
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) =
                DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) =
                DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) =
                DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(
                disciple: Disciple, equipments: Map<String, com.xianxia.sect.core.model.EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(disciple, equipments)
            override fun getStatsWithEquipment(
                aggregate: DiscipleAggregate, equipments: Map<String, com.xianxia.sect.core.model.EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(aggregate, equipments)
            override fun getFinalStats(
                disciple: Disciple, equipments: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(disciple, equipments, manuals, manualProficiencies)
            override fun getFinalStats(
                aggregate: DiscipleAggregate, equipments: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>
            ) = DiscipleStatCalculator.getFinalStats(aggregate, equipments, manuals, manualProficiencies)
            override fun calculateCultivationSpeed(
                disciple: Disciple, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                disciple, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun calculateCultivationSpeed(
                aggregate: DiscipleAggregate, manuals: Map<String, ManualInstance>,
                manualProficiencies: Map<String, ManualProficiencyData>, buildingBonus: Double,
                additionalBonus: Double, preachingElderBonus: Double, preachingMastersBonus: Double,
                cultivationSubsidyBonus: Double, parentCultivationBonus: Double,
                griefCultivationSpeedPenalty: Double, masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                aggregate, manuals, manualProficiencies, buildingBonus,
                preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus,
                parentCultivationBonus, griefCultivationSpeedPenalty
            )
            override fun getBreakthroughChance(
                disciple: Disciple, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                disciple, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus
            )
            override fun getBreakthroughChance(
                aggregate: DiscipleAggregate, innerElderComprehension: Int,
                outerElderComprehension: Int, pillBonus: Double,
                adBonus: Double, griefBreakthroughPenalty: Double,
                masterDiscipleBonus: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(
                aggregate, innerElderComprehension, outerElderComprehension,
                pillBonus, adBonus, griefBreakthroughPenalty, masterDiscipleBonus
            )
        }

        val mockStateStore = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(mockStateStore.manualInstances)
            .thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(mockStateStore.disciples)
            .thenReturn(MutableStateFlow(emptyList()))

        val realHpMpRecoveryService = HpMpRecoveryService()
        core = CultivationCore(
            stateStore = mockStateStore,
            inventoryConfig = Mockito.mock(InventoryConfig::class.java),
            thermalMonitor = Mockito.mock(ThermalMonitor::class.java),
            gameClock = Mockito.mock(GameTimeClock::class.java),
            scopeProvider = Mockito.mock(CoroutineScopeProvider::class.java),
            pillManager = Mockito.mock(DisciplePillManager::class.java),
            equipmentManager = Mockito.mock(DiscipleEquipmentManager::class.java),
            manualManager = Mockito.mock(DiscipleManualManager::class.java),
            hpMpRecoveryService = realHpMpRecoveryService,
            autoPillService = AutoPillService(Mockito.mock(DisciplePillManager::class.java), Mockito.mock()),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(mockStateStore),
            battleSettlementService = BattleSettlementService(realHpMpRecoveryService)
        )
    }

    /** 构建 300 弟子 × 5 功法的测试状态（每次调用独立副本） */
    private fun buildState(): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        val manualInstances = (1..MANUALS_PER_DISCIPLE).map { i ->
            ManualInstance(id = "m$i", name = "功法$i")
        }
        for (i in 1..DISCIPLE_COUNT) {
            tables.insert(Disciple(id = i.toString(), name = "弟子$i", realm = 5, realmLayer = 1))
            tables.manualIds[i] = (1..MANUALS_PER_DISCIPLE).map { "m$it" }
            tables.comprehensions[i] = 80
        }
        tables.writeAllowed = false
        tables.changedIdTracker.consumeChangedIds()
        return MutableGameState(
            gameData = GameData(),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(manualInstances),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    private fun measure(iterations: Int, block: () -> Unit): Long {
        repeat(3) { block() }  // warmup
        val times = (1..iterations).map {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun `批量版耗时不超过单弟子版 50%`() {
        val ids = buildState().discipleTables.ids.toList()

        val singleState = buildState()
        val singleTime = measure(5) {
            for (id in ids) {
                core.processManualProficiencySingle(singleState, id, singleState.manualInstances.items.associateBy { it.id })
            }
        }

        val batchState = buildState()
        val batchTime = measure(5) {
            val pending = mutableMapOf<String, List<ManualProficiencyData>?>()
            val libraryIds = batchState.gameData.librarySlots.mapTo(HashSet()) { it.discipleId }
            for (id in ids) {
                core.processManualProficiencySingle(
                    batchState, id,
                    batchState.manualInstances.items.associateBy { it.id },
                    pending, libraryIds
                )
            }
            core.commitManualProficiencies(batchState, pending)
        }

        val ratio = batchTime.toDouble() / singleTime
        assertTrue(
            "批量版(${batchTime / 1000}μs) 应显著快于单弟子版(${singleTime / 1000}μs)，" +
                "实际比值 $ratio > 0.50——批量路径可能误用全量 Map 拷贝，请检查" +
                "processManualProficiencySingle 的 pendingProficiencies 分支",
            ratio <= 0.50
        )
    }

    /** 批量版与单弟子版最终状态逐 key 等价（行为不变性回归） */
    @Test
    fun `批量版与单弟子版最终 manualProficiencies 逐 key 等价`() {
        val ids = buildState().discipleTables.ids.toList()

        // 单弟子版
        val stateSingle = buildState()
        val manualMapSingle = stateSingle.manualInstances.items.associateBy { it.id }
        for (id in ids) {
            core.processManualProficiencySingle(stateSingle, id, manualMapSingle)
        }

        // 批量版
        val stateBatch = buildState()
        val manualMapBatch = stateBatch.manualInstances.items.associateBy { it.id }
        val pending = mutableMapOf<String, List<ManualProficiencyData>?>()
        val libraryIds = stateBatch.gameData.librarySlots.mapTo(HashSet()) { it.discipleId }
        for (id in ids) {
            core.processManualProficiencySingle(stateBatch, id, manualMapBatch, pending, libraryIds)
        }
        core.commitManualProficiencies(stateBatch, pending)

        assertTrue(
            "批量版与单弟子版 manualProficiencies 不一致：\n" +
                "single=${stateSingle.gameData.manualProficiencies}\n" +
                "batch=${stateBatch.gameData.manualProficiencies}",
            stateSingle.gameData.manualProficiencies == stateBatch.gameData.manualProficiencies
        )
    }
}

package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * realtimeCultivation 批量发射验证（P-6，2026-08-02）。
 *
 * 修复前：accumulateCultivationPerPhase 每弟子一次 `(prevMap ?: emptyMap()) + (key to projection)`
 * 发射（O(D) Map 重建 × D 弟子 = O(D²) + D 次 StateFlow 发射）。
 * 修复后：批量模式累积到 pending，flush 单次合并发射（O(D) + 1 次发射）。
 *
 * 断言：① 批量版最终 realtimeCultivation 与逐弟子版逐 key 等价（行为不变）；
 * ② 批量版发射 1 次（vs 逐弟子版 D 次）——消费方只读最终值，无中间值依赖。
 */
@RunWith(RobolectricTestRunner::class)
class RealtimeCultivationBatchTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var stateStore: FakeAtomicStateStore

    @Before
    fun setUp() {
        // Fake 提供真实语义：discipleTables 持久实例（insertDisciple 直写、跨事务保留），
        // 各 flow 默认空列表 + gameData 同步——等价 mock 时代的逐条 stub
        stateStore = FakeAtomicStateStore().also {
            it.setGameData(GameData(gameYear = 1, gameMonth = 6))
        }
        tables = stateStore.discipleTables
        DiscipleAggregate.statsProvider = object : DiscipleStatsProvider {
            override fun getBaseStats(disciple: Disciple) = DiscipleStatCalculator.getBaseStats(disciple)
            override fun getBaseStats(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getBaseStats(aggregate)
            override fun getTalentEffects(disciple: Disciple) = DiscipleStatCalculator.getTalentEffects(disciple)
            override fun getTalentEffects(aggregate: DiscipleAggregate) = DiscipleStatCalculator.getTalentEffects(aggregate)
            override fun getStatsWithEquipment(d: Disciple, e: Map<String, EquipmentInstance>) = DiscipleStatCalculator.getStatsWithEquipment(d, e)
            override fun getStatsWithEquipment(a: DiscipleAggregate, e: Map<String, EquipmentInstance>) = DiscipleStatCalculator.getStatsWithEquipment(a, e)
            override fun getFinalStats(
                d: Disciple, e: Map<String, EquipmentInstance>, m: Map<String, ManualInstance>,
                p: Map<String, ManualProficiencyData>, bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(d, e, m, p, bloodRefinementPct)
            override fun getFinalStats(
                a: DiscipleAggregate, e: Map<String, EquipmentInstance>, m: Map<String, ManualInstance>,
                p: Map<String, ManualProficiencyData>, bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(a, e, m, p, bloodRefinementPct)
            override fun calculateCultivationSpeed(d: Disciple, manuals: Map<String, ManualInstance>, mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double, peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.calculateCultivationPerPhase(d, manuals, mps, bb, peb, pmb, csb, pcb, gcp)
            override fun calculateCultivationSpeed(a: DiscipleAggregate, manuals: Map<String, ManualInstance>, mps: Map<String, ManualProficiencyData>, bb: Double, ab: Double, peb: Double, pmb: Double, csb: Double, pcb: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.calculateCultivationPerPhase(a, manuals, mps, bb, peb, pmb, csb, pcb, gcp)
            override fun getBreakthroughChance(d: Disciple, iec: Int, oec: Int, pb: Double, ab: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)
            override fun getBreakthroughChance(a: DiscipleAggregate, iec: Int, oec: Int, pb: Double, ab: Double, gcp: Double, mdb: Double) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
        }
    }

    private fun buildService(sharedState: CultivationSharedState): CultivationService {
        // stateStore 为字段 Fake（setUp 创建），服务直读其持久表与默认空 flow

        val cultivationCore = CultivationCore(
            hpMpRecoveryService = HpMpRecoveryService(),
            autoPillService = AutoPillService(mockSmart(), mockSmart()),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(stateStore),
            battleSettlementService = BattleSettlementService(HpMpRecoveryService())
        )
        return CultivationService(
            stateStore = stateStore,
            cultivationCore = cultivationCore,
            breakthroughHandler = mockSmart(),
            cultivationSettlement = mockSmart(),
            eventProcessor = mockSmart(),
            productionProcessor = mockSmart(),
            recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(),
            caveExplorationProcessor = mockSmart(),
            sharedState = sharedState,
            discipleService = mockSmart()
        )
    }

    private fun insertDisciple(id: Int) {
        tables.insert(
            Disciple(
                id = id.toString(), name = "弟子$id", realm = 9,
                cultivation = 10.0, spiritRootType = "1,2",
                combat = CombatAttributes(hpVariance = 0, mpVariance = 0)
            )
        )
    }

    private fun buildState(): MutableGameState = MutableGameState(
        gameData = GameData(gameYear = 1, gameMonth = 6),
        discipleTables = tables,
        equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
        manualStacks = EntityStore(), manualInstances = EntityStore(),
        pills = EntityStore(), materials = EntityStore(),
        herbs = EntityStore(), seeds = EntityStore(), storageBags = EntityStore(),
        teams = emptyList(), battleLogs = emptyList(),
        isPaused = false, isLoading = false, isSaving = false
    )

    /** 订阅 highFrequencyData 发射计数（跳过初始值） */
    private fun countEmissions(shared: CultivationSharedState): Pair<AtomicInteger, Job> {
        val counter = AtomicInteger(0)
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            shared.highFrequencyData
                .drop(1)
                .onEach { counter.incrementAndGet() }
                .collect()
        }
        return counter to job
    }

    @Test
    fun `批量版发射 1 次且最终 map 与逐弟子版逐 key 等价`() {
        insertDisciple(1)
        insertDisciple(2)
        insertDisciple(3)
        val state = buildState()

        // ── 逐弟子版（修复前行为）──
        val sharedOld = CultivationSharedState()
        val serviceOld = buildService(sharedOld)
        val (countOld, jobOld) = countEmissions(sharedOld)
        for (id in 1..3) {
            serviceOld.accumulateCultivationPerPhase(id, state)
        }
        jobOld.cancel()
        val mapOld = sharedOld.highFrequencyData.value.realtimeCultivation
        assertTrue(
            "逐弟子版应每弟子发射一次（当前 ${countOld.get()}），构造或实现有误",
            countOld.get() >= 3
        )

        // ── 批量版（P-6 修复后行为）──
        val sharedBatch = CultivationSharedState()
        val serviceBatch = buildService(sharedBatch)
        val (countBatch, jobBatch) = countEmissions(sharedBatch)
        val pending = mutableMapOf<String, Double>()
        for (id in 1..3) {
            serviceBatch.accumulateCultivationPerPhase(id, state, pending)
        }
        assertEquals("批量累积阶段不应发射", 0, countBatch.get())
        serviceBatch.flushRealtimeCultivation(pending)
        jobBatch.cancel()
        val mapBatch = sharedBatch.highFrequencyData.value.realtimeCultivation

        assertEquals("批量版应单次发射（当前 ${countBatch.get()}）", 1, countBatch.get())
        assertEquals("批量版与逐弟子版最终 map 不一致", mapOld, mapBatch)
    }

    @Test
    fun `无投影变化的批量 flush 不发射`() {
        insertDisciple(1)
        val state = buildState()

        val shared = CultivationSharedState()
        val service = buildService(shared)
        val (counter, job) = countEmissions(shared)
        // 空 pending flush：无变化，不应发射
        service.flushRealtimeCultivation(mutableMapOf())
        job.cancel()
        assertEquals("空 pending flush 不应发射", 0, counter.get())
    }
}

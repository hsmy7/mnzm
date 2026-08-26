package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.PillEffectApplier
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.AutoPillService
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationEventProcessor
import com.xianxia.sect.core.engine.service.CultivationRateCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.CultivationSettlement
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.engine.service.DiscipleBreakthroughHandler
import com.xianxia.sect.core.engine.service.EquipmentNurtureService
import com.xianxia.sect.core.engine.service.HpMpRecoveryService
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.service.PolicyCostResult
import com.xianxia.sect.core.engine.service.RelativeGiftHandler
import com.xianxia.sect.core.engine.service.YearSettlementExecutor
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.NativeRngChannel
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffAuthoritativeTickTest — AUTHORITATIVE 过渡期 tick 全管线跨语言对拍
 * （计划 v2 阶段 2d / T2.4 验收核心）。
 *
 * 守护目标：同一初始状态推进 100 旬（含多次月界 + 一次年界），
 * "C++ 核心结算 + Kotlin 残留执行器 + 委托式 RNG + 增量镜像"管线终态 ==
 * 纯 Kotlin 全量引擎（现生产行为）终态，逐字段逐位一致。
 *
 * 管线对应（生产 GameEngineCoreAuthoritativeOps.processAuthoritativeTick）：
 * nativeCoreSettlePhase → applyDirty → executeResidual → 边界月/年编排 +
 * 回导。本测试经 DiffRngBridge 桌面通道驱动同一协议。
 *
 * 场景：年 1 月 10 起（3 次月变 + 跨年年变）、年俸配置生效、年报计数非零、
 * 政策全开但灵石充足（政策扣除路径活跃）。RNG：场景内每旬结算零抽取
 *（无丹药/突破触发），序列统一性由 NativeBackedRngTest + DiffRngTest 守护。
 *
 * 前置：桌面 JNI 已构建并注入 -Dgamecore.jni.path；未注入时跳过。
 */
class DiffAuthoritativeTickTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private companion object {
        const val TICKS = 100
        const val SEED = 2026_10_24L

        /** realm=9 年俸额 */
        const val SALARY_REALM9 = 500L

        /** 弟子数 */
        const val DISCIPLE_COUNT = 3

        /** 初始灵石（充足，政策不降级） */
        const val INITIAL_STONES = 50_000L
    }

    /** 生产委托通道的桌面测试实现（经 DiffRngBridge 符号驱动同一 C++ 引擎） */
    private object DesktopRngChannel : NativeRngChannel {
        override fun nextInt(partitionId: Int): Int =
            DiffRngBridge.nativeCoreRngNextInt(partitionId)
        override fun snapshot(partitionId: Int): Long =
            DiffRngBridge.nativeCoreRngSnapshotPartition(partitionId)
        override fun restore(partitionId: Int, state: Long) =
            DiffRngBridge.nativeCoreRngRestorePartition(partitionId, state)
        override fun initSystemSeed(seed: Long) =
            DiffRngBridge.nativeCoreRngInitSeed(seed)
    }

    @After
    fun tearDown() {
        // 还原默认模式引擎——共享桌面单例，防止污染同 JVM 的既有对拍用例
        if (DiffRngBridge.isAvailable()) {
            DiffRngBridge.nativeCoreInitMode(false)
        }
    }

    // ── 场景构建 ────────────────────────────────────────────────────

    private fun buildSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 10, gamePhase = 0,
            spiritStones = INITIAL_STONES
        ).apply {
            rngStates = initialRngStates()
            lastRecruitYear = 1
            merchantLastRefreshChanceGrantYear = 1
            // 年报可观察输入
            annualTotalIncome = 1200L
            annualAlchemyCount = 5
            // 年俸配置（realm9）
            yearlySalary = mapOf(9 to SALARY_REALM9.toInt())
            yearlySalaryEnabled = mapOf(9 to true)
        }
        return NativeGameState(
            gameData = gameData,
            disciples = listOf(
                settlerDisciple("31", "甲"),
                settlerDisciple("32", "乙"),
                settlerDisciple("33", "丙")
            )
        )
    }

    private fun settlerDisciple(id: String, name: String) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1,
        cultivation = 10.0, spiritRootType = "metal",
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    private fun initialRngStates(): MutableMap<Int, Long> {
        val states = mutableMapOf<Int, Long>()
        RngPartition.values().forEach { partition ->
            val rng = DeterministicRng.fromSeed(SEED + partition.id)
            repeat(3) { rng.nextInt() }
            states[partition.id] = rng.snapshot()
        }
        return states
    }

    // ── 共享装配（与 DiffYearSettlementTest 同构；真实组件 + 定向惰性依赖） ──

    private fun installStatsProvider() {
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
                d: Disciple, e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(d, e)
            override fun getStatsWithEquipment(
                a: DiscipleAggregate, e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(a, e)
            override fun getFinalStats(
                d: Disciple,
                e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                m: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                p: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                b: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(d, e, m, p, b)
            override fun getFinalStats(
                a: DiscipleAggregate,
                e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                m: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                p: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                b: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(a, e, m, p, b)
            override fun calculateCultivationSpeed(
                d: Disciple,
                manuals: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                mps: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(d, manuals, mps, bb, peb, pmb, csb, pcb, gcp)
            override fun calculateCultivationSpeed(
                a: DiscipleAggregate,
                manuals: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                mps: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(a, manuals, mps, bb, peb, pmb, csb, pcb, gcp)
            override fun getBreakthroughChance(
                d: Disciple, iec: Int, oec: Int, pb: Double,
                ab: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)
            override fun getBreakthroughChance(
                a: DiscipleAggregate, iec: Int, oec: Int, pb: Double,
                ab: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
        }
    }

    private class UnconfinedScopeProvider : CoroutineScopeProvider {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val ioScope = CoroutineScope(Dispatchers.Unconfined)
    }

    @Suppress("LongMethod")  // 测试装配：与 DiffYearSettlementTest 同构的完整服务装配
    private fun buildHarness(
        store: FakeGameStateStore,
        rngStates: Map<Int, Long>,
        delegating: Boolean
    ): Triple<CultivationService, GameRngManager, HarnessExecutors> {
        installStatsProvider()
        val gameRng = GameRngManager().also {
            if (delegating) {
                it.attachNativeChannel(DesktopRngChannel)
            } else {
                it.restoreStates(rngStates)
            }
        }
        val core = CultivationCore(
            hpMpRecoveryService = HpMpRecoveryService(),
            autoPillService = AutoPillService(
                DisciplePillManager(PillEffectApplier()), mockSmart()
            ),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(store)
        )
        val handler = DiscipleBreakthroughHandler(
            stateStore = store, cultivationCore = core,
            scopeProvider = mockSmart(), relativeGiftHandler = RelativeGiftHandler(gameRng),
            rngManager = gameRng, analyticsTracker = mockSmart()
        )
        val scopeProvider = UnconfinedScopeProvider()
        val wallet = SpiritStoneWallet(store, SpiritStoneLedger(), EventBus(scopeProvider))
        val configProvider = GameConfigProvider(ConfigLoader({ null }))
        val settlement = CultivationSettlement(
            stateStore = store, scopeProvider = scopeProvider,
            spiritStoneWallet = wallet, lawEnforcementProcessor = mockSmart(),
            gameConfigProvider = configProvider
        )
        val eventProcessor = CultivationEventProcessor(
            stateStore = store, spiritStoneWallet = wallet,
            inventorySystem = mockSmart(), inventoryConfig = mockSmart(),
            scopeProvider = scopeProvider, discipleService = mockSmart(),
            cultivationCore = core, breakthroughHandler = handler,
            cultivationSettlement = settlement, battleSystem = mockSmart(),
            recruitService = mockSmart(), merchantAndRecruitService = mockSmart(),
            caveExplorationProcessor = mockSmart(), discipleLifecycleProcessor = mockSmart(),
            diplomacyEventProcessor = mockSmart(), diplomacyService = mockSmart(),
            equipmentManager = mockSmart(), manualManager = mockSmart(),
            autoBuyService = mockSmart(), vassalService = mockSmart(),
            disciplePurchaseService = mockSmart(),
            aiSectBeastAttackProcessor = mockSmart<AISectBeastAttackProcessor>(),
            lawEnforcementProcessor = mockSmart<LawEnforcementProcessor>(),
            rngManager = gameRng, secretRealmService = mockSmart(),
            secretRealmAIProcessor = mockSmart(), deathHandler = mockSmart()
        )
        val service = CultivationService(
            stateStore = store, cultivationCore = core, breakthroughHandler = handler,
            cultivationSettlement = settlement, eventProcessor = eventProcessor,
            productionProcessor = mockSmart(), recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(), caveExplorationProcessor = mockSmart(),
            sharedState = CultivationSharedState(), discipleService = mockSmart()
        )
        val monthExecutor = MonthSettlementExecutor(
            cultivationService = service,
            aiSectBeastAttackProcessor = mockSmart<AISectBeastAttackProcessor>(),
            systemManager = SystemManager(setOf(PartnerSystem(gameRng)))
        )
        return Triple(
            service,
            gameRng,
            HarnessExecutors(
                PhaseSettlementExecutor(service),
                YearSettlementExecutor(service),
                monthExecutor
            )
        )
    }

    private class HarnessExecutors(
        val phase: PhaseSettlementExecutor,
        val year: YearSettlementExecutor,
        val month: MonthSettlementExecutor
    )

    // ── 验收测试 ────────────────────────────────────────────────────

    @Test
    @Suppress("LongMethod")  // 逐旬互锁全管线：单函数承载对拍主流程（与 T2.2/T2.3 对拍同构）
    fun `authoritative pipeline matches legacy kotlin engine over 100 phases`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInitMode(true)

        val snapshot = buildSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        // ── Side B：纯 Kotlin 全量引擎（现行生产行为基准） ──
        val storeB = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        val (_, rngB, exB) = buildHarness(storeB, snapshot.gameData.rngStates, delegating = false)
        val timeB = TimeSystem(storeB)

        // ── Side A：AUTHORITATIVE 管线（C++ 核心 + Kotlin 残留 + 委托 RNG） ──
        assertTrue("导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        // 镜像必须预播种完整初始状态（生产侧 = 已读档的 GameStateStore）——
        // dirty 只推变更字段，裸默认 store 会让镜像停留在默认时间线，
        // 每旬回导把错误时间写回 C++ 造成两侧同步漂移
        val storeA = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        val syncA = StateSyncService(storeA) { DiffRngBridge.nativeCoreApplyReverseDirty(it) }
        val (_, _, exA) = buildHarness(storeA, snapshot.gameData.rngStates, delegating = true)

        // 逐旬互锁：两侧各推进一旬后比较，首次分歧即报旬号与字段路径
        runTest {
            repeat(TICKS) { tick ->
                // Side B 一旬（现行生产路径）
                var yearChangedB = false
                var monthChangedB = false
                storeB.update {
                    val prevYear = gameData.gameYear
                    val prevMonth = gameData.gameMonth
                    timeB.onPhaseTick(this, phasesToSettle = 1)
                    exB.phase.execute(this)
                    yearChangedB = gameData.gameYear != prevYear
                    monthChangedB = gameData.gameMonth != prevMonth
                }
                runBoundary(exB, storeB, yearChangedB, monthChangedB)
                // Side A 一旬（AUTHORITATIVE 管线）
                val flags = DiffRngBridge.nativeCoreSettlePhase()
                val dirty = DiffRngBridge.nativeCoreExportDirty().decodeToString()
                val applyResult = syncA.applyDirty(dirty)
                assertEquals("镜像失败", false, applyResult == null)
                // ②' 重置反向捕获窗口（生产同构：② 变更由 C++ 产生、无需回导）
                storeA.resetReverseAccumulator()
                storeA.update { exA.phase.executeResidual(this) }
                if (flags != 0) {
                    val yearChangedA = (flags and GameCoreBridge.FLAG_YEAR_CHANGED) != 0
                    val monthChangedA = (flags and GameCoreBridge.FLAG_MONTH_CHANGED) != 0
                    runBoundary(exA, storeA, yearChangedA, monthChangedA)
                }
                // ⑤ 反向增量回导（阶段 3：取代每旬全量 importToNative——残留/边界
                // 效果经 applyReverseDirty 增量写回 C++ 真相源；生产侧失败降级全量，
                // 本测试断言增量通道成功）
                assertTrue("反向增量回导失败", syncA.applyDirtyToNative())
                // 逐旬对拍（每 5 旬一次全量结构）
                if (tick % 5 == 4) {
                    val actualEl = json.parseToJsonElement(
                        DiffRngBridge.nativeCoreExportState().decodeToString()
                    )
                    val expectedEl = json.encodeToJsonElement(
                        NativeGameState.serializer(),
                        NativeGameState(
                            gameData = storeB.gameDataValue.copy(
                                rngStates = rngB.exportStates().toMutableMap()
                            ),
                            disciples = storeB.disciplesValue
                        )
                    )
                    try {
                        assertNodeMatches(expectedEl, actualEl, "$")
                    } catch (e: AssertionError) {
                        throw AssertionError("第 $tick 旬全量结构分歧: ${e.message}", e)
                    }
                }
            }
        }

        // ── 终态对拍：C++ 导出（真相源）vs Kotlin 全量引擎 ──
        val actual = json.parseToJsonElement(
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        val expectedEl = json.encodeToJsonElement(NativeGameState.serializer(), NativeGameState(
            gameData = storeB.gameDataValue.copy(
                rngStates = rngB.exportStates().toMutableMap()
            ),
            disciples = storeB.disciplesValue
        ))
        assertNodeMatches(expectedEl, actual, "$")

        // 显式不变量：100 旬自（1,10）起 = 33 个整月 + 1 旬（每 3 旬一月）：
        // 10→11→12→(2,1)→…→(4,7)，跨 3 个年界（含 3 次年俸与 3 份年报）。
        // 精确数值由上方逐旬全量结构对拍覆盖（C++ 导出面 vs Kotlin 全量）。
        val gdA = storeA.gameDataValue
        assertEquals(4, gdA.gameYear)
        assertEquals(7, gdA.gameMonth)
        assertEquals("年俸扣减总额不符",
            INITIAL_STONES - SALARY_REALM9 * DISCIPLE_COUNT * 3,
            gdA.spiritStones)
        assertEquals("年报应恰 3 份", 3, gdA.yearlyReports.size)
        storeA.disciplesValue.forEach { d ->
            assertTrue("弟子 ${d.id} 未收到年俸", d.equipment.storageBagSpiritStones >= SALARY_REALM9)
        }
    }

    /** 年先于月变的边界编排（两侧共用同一顺序契约） */
    private suspend fun runBoundary(
        ex: HarnessExecutors,
        store: FakeGameStateStore,
        yearChanged: Boolean,
        monthChanged: Boolean
    ) {
        if (yearChanged) {
            val gd = store.gameDataValue
            ex.year.execute(gd.gameYear, gd.gameMonth == 1)
        }
        if (monthChanged) {
            var policy: PolicyCostResult = PolicyCostResult.AllPaid
            store.update { policy = ex.month.execute(this) }
            assertTrue(policy == PolicyCostResult.AllPaid)
        }
    }

    // ── JSON 结构对拍（与 DiffYearSettlementTest 同构） ─────────────

    private fun assertNodeMatches(expected: JsonElement, actual: JsonElement, path: String) {
        when {
            actual is JsonObject && expected is JsonObject ->
                compareObjects(expected, actual, path)
            actual is JsonArray && expected is JsonArray ->
                compareArrays(expected, actual, path)
            else -> assertPrimitiveEquals(expected, actual, path)
        }
    }

    private fun compareObjects(expected: JsonObject, actual: JsonObject, path: String) {
        for ((k, a) in actual) {
            if (k == "timestamp") continue
            val e = expected[k]
            assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失", e != null)
            assertNodeMatches(e!!, a, "$path.$k")
        }
    }

    private fun compareArrays(expected: JsonArray, actual: JsonArray, path: String) {
        assertEquals("$path size", expected.size, actual.size)
        actual.forEachIndexed { i, a -> assertNodeMatches(expected[i], a, "$path[$i]") }
    }

    private fun assertPrimitiveEquals(expected: JsonElement, actual: JsonElement, path: String) {
        require(actual is JsonPrimitive && expected is JsonPrimitive) {
            "$path 结构不匹配"
        }
        val eq = when {
            actual === JsonNull || expected === JsonNull -> actual == expected
            actual.booleanOrNull != null || expected.booleanOrNull != null ->
                actual.booleanOrNull == expected.booleanOrNull
            else -> {
                val eD = expected.doubleOrNull
                val aD = actual.doubleOrNull
                if (eD != null && aD != null) {
                    java.lang.Double.doubleToLongBits(eD) == java.lang.Double.doubleToLongBits(aD)
                } else {
                    actual.content == expected.content
                }
            }
        }
        assertTrue("$path 期望=$expected 实际=$actual", eq)
    }
}

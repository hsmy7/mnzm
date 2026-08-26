package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.AutoPillService
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationRateCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.CultivationSettlement
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.engine.service.DiscipleBreakthroughHandler
import com.xianxia.sect.core.engine.service.EquipmentNurtureService
import com.xianxia.sect.core.engine.service.HpMpRecoveryService
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.domain.disciple.PillEffectApplier
import com.xianxia.sect.core.engine.service.RelativeGiftHandler
import com.xianxia.sect.core.engine.service.CultivationEventProcessor
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
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
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.partnerId
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffMonthSettlementTest — 月变结算跨语言差分对拍（T2.2 验收核心）。
 *
 * 守护目标：C++ `gamecore::system::runMonthSettlement`（注册于 onMonthChange，
 * 经 nativeCoreAdvancePhases 跨月界触发）与 Kotlin `GameEngineCore` 同构组合管线
 * （TimeSystem 推进至边界 → PhaseSettlementExecutor → MonthSettlementExecutor）
 * 的月变八步语义**逐位一致**。
 *
 * 场景覆盖（对照 t2-2-semantics.md §3 九条规避约束）：
 * ① 灵矿 lastSettledMonth 无条件推进（矿空 rate=0 仍推进——差分保护语义）
 * ② 伴侣配对 SYSTEM 流：两男两女适格（age≥18 / 无道侣 / 无血亲 /
 *    bannedRootCounts 空），males 外层 × females 内层每组合恰 1 次 nextDouble，
 *    以 RNG 分区终态锁定抽取次数与顺序（0.006 概率下预期全不命中 → partnerId 全空）
 * ③ 政策忠诚：仁政爱徒 loyalty delta=+1（50→51 coerceIn(0,100)）+
 *    S1 政策月费 100×4 弟子经真实钱包扣除
 *
 * 规避清单落实：政策仅开仁政爱徒（自动排班六开关全关）/ 弟子 morality≥阈值
 * （偷盗钩子零触发）/ consentRequired=false / worldLevels·worldMapSects 空 /
 * spiritFieldPlants 空 / activeBloodRefinements 空 / 无秘境·巡逻·任务 /
 * 非 12 月 / timestamp 对拍排除。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffMonthSettlementTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 推进 3 旬：(1,1,上旬) → (1,2,上旬)，恰好跨一个月界。
     * 刻意避开 month % 3 == 0 的任务自动刷新月（missionRefresh 属任务批次未下沉）。
     */
    private companion object {
        const val PHASES = 3
        const val SEED = 20260901L

        /** 仁政爱徒月费（PolicyConfig：100 × 全体弟子数） */
        const val BENEVOLENT_MONTHLY_COST_PER_DISCIPLE = 100L

        /** 初始忠诚缺省（DiscipleTables.loyalties getOrDefault 50） */
        const val BASE_LOYALTY = 50

        /** 仁政爱徒月度忠诚增量（kBenevolentLoyaltyPerMonth） */
        const val BENEVOLENT_LOYALTY_DELTA = 1

        /** 弟子数（2 男 2 女） */
        const val DISCIPLE_COUNT = 4
    }

    // ── 场景构建 ────────────────────────────────────────────────────

    /** 两男两女适格弟子（配对流断言核心）+ 仁政爱徒开启 + 灵石充足 */
    private fun buildSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 场景③：仁政爱徒（S1 按弟子数计费 100×N + S2 忠诚 +1）
            sectPolicies = sectPolicies.copy(benevolentGovernance = true)
            // 场景②前提：自动配对模式（提案分支不在协议）
            daoCompanionConsentRequired = false
        }
        return NativeGameState(
            gameData = gameData,
            disciples = listOf(
                pairingDisciple("11", "甲一", "male"),
                pairingDisciple("12", "甲二", "male"),
                pairingDisciple("13", "乙一", "female"),
                pairingDisciple("14", "乙二", "female")
            )
        )
    }

    /** 配对适格弟子：成年 / 无道侣 / 无血亲 / 低修为（不触发突破）/ 满血哨兵 */
    private fun pairingDisciple(id: String, name: String, gender: String) =
        Disciple(
            id = id, name = name, realm = 9, realmLayer = 1,
            cultivation = 10.0, spiritRootType = "metal",
            age = 20, gender = gender,
            combat = CombatAttributes(currentHp = -1, currentMp = -1)
        )

    /** 初始 RNG 分区状态：seed+partitionId 播种后各抽取 3 次（非平凡状态） */
    private fun initialRngStates(seed: Long): MutableMap<Int, Long> {
        val states = mutableMapOf<Int, Long>()
        RngPartition.values().forEach { partition ->
            val rng = DeterministicRng.fromSeed(seed + partition.id)
            repeat(3) { rng.nextInt() }
            states[partition.id] = rng.snapshot()
        }
        return states
    }

    // ── Kotlin 基准侧（与 DiffPhaseSettlementTest 装配同构） ──────────

    private fun buildService(
        store: FakeGameStateStore,
        rngStates: Map<Int, Long>
    ): Pair<CultivationService, GameRngManager> {
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
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(d, e, m, p, bloodRefinementPct)
            override fun getFinalStats(
                a: DiscipleAggregate,
                e: Map<String, com.xianxia.sect.core.model.EquipmentInstance>,
                m: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                p: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(a, e, m, p, bloodRefinementPct)
            override fun calculateCultivationSpeed(
                d: Disciple,
                manuals: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                mps: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                d, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )
            override fun calculateCultivationSpeed(
                a: DiscipleAggregate,
                manuals: Map<String, com.xianxia.sect.core.model.ManualInstance>,
                mps: Map<String, com.xianxia.sect.core.model.ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                a, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )
            override fun getBreakthroughChance(
                d: Disciple, iec: Int, oec: Int, pb: Double,
                ab: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(d, iec, oec, pb, ab, gcp, mdb)
            override fun getBreakthroughChance(
                a: DiscipleAggregate, iec: Int, oec: Int, pb: Double,
                ab: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.getBreakthroughChance(a, iec, oec, pb, ab, gcp, mdb)
        }
        val core = CultivationCore(
            hpMpRecoveryService = HpMpRecoveryService(),
            autoPillService = AutoPillService(
                DisciplePillManager(PillEffectApplier()),
                mockSmart()
            ),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(store)
        )
        val gameRng = GameRngManager().also { it.restoreStates(rngStates) }
        val handler = DiscipleBreakthroughHandler(
            stateStore = store,
            cultivationCore = core,
            scopeProvider = mockSmart(),
            relativeGiftHandler = RelativeGiftHandler(gameRng),
            rngManager = gameRng,
            analyticsTracker = mockSmart()
        )
        val scopeProvider = UnconfinedCoroutineScopeProvider()
        val wallet = SpiritStoneWallet(
            store, SpiritStoneLedger(), EventBus(scopeProvider)
        )
        val configProvider = GameConfigProvider(ConfigLoader({ null }))
        val settlement = CultivationSettlement(
            stateStore = store,
            scopeProvider = scopeProvider,
            spiritStoneWallet = wallet,
            lawEnforcementProcessor = mockSmart(),
            gameConfigProvider = configProvider
        )
        val eventProcessor = buildEventProcessor(
            store, core, handler, settlement, gameRng, scopeProvider
        )
        return CultivationService(
            stateStore = store,
            cultivationCore = core,
            breakthroughHandler = handler,
            cultivationSettlement = settlement,
            eventProcessor = eventProcessor,
            productionProcessor = mockSmart(),
            recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(),
            caveExplorationProcessor = mockSmart(),
            sharedState = CultivationSharedState(),
            discipleService = mockSmart()
        ) to gameRng
    }

    /** 真实 CultivationEventProcessor + 定向惰性依赖（论证见 t2-2-report.md §A） */
    private fun buildEventProcessor(
        store: FakeGameStateStore,
        core: CultivationCore,
        handler: DiscipleBreakthroughHandler,
        settlement: CultivationSettlement,
        gameRng: GameRngManager,
        scopeProvider: CoroutineScopeProvider
    ): CultivationEventProcessor {
        val wallet = SpiritStoneWallet(
            store, SpiritStoneLedger(), EventBus(scopeProvider)
        )
        return CultivationEventProcessor(
            stateStore = store,
            spiritStoneWallet = wallet,
            inventorySystem = mockSmart(),
            inventoryConfig = mockSmart(),
            scopeProvider = scopeProvider,
            discipleService = mockSmart(),
            cultivationCore = core,
            breakthroughHandler = handler,
            cultivationSettlement = settlement,
            battleSystem = mockSmart(),
            recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(),
            caveExplorationProcessor = mockSmart(),
            discipleLifecycleProcessor = mockSmart(),
            diplomacyEventProcessor = mockSmart(),
            diplomacyService = mockSmart(),
            equipmentManager = mockSmart(),
            manualManager = mockSmart(),
            autoBuyService = mockSmart(),
            vassalService = mockSmart(),
            disciplePurchaseService = mockSmart(),
            aiSectBeastAttackProcessor = mockSmart<AISectBeastAttackProcessor>(),
            lawEnforcementProcessor = mockSmart<LawEnforcementProcessor>(),
            rngManager = gameRng,
            secretRealmService = mockSmart(),
            secretRealmAIProcessor = mockSmart(),
            deathHandler = mockSmart()
        )
    }

    /** 月变编排器（SystemManager 仅装 PartnerSystem——缺席 ≡ 场景恒零，见报告 §A） */
    private fun buildMonthExecutor(
        service: CultivationService,
        gameRng: GameRngManager
    ): MonthSettlementExecutor = MonthSettlementExecutor(
        cultivationService = service,
        aiSectBeastAttackProcessor = mockSmart<AISectBeastAttackProcessor>(),
        systemManager = SystemManager(setOf(PartnerSystem(gameRng)))
    )

    private class UnconfinedCoroutineScopeProvider : CoroutineScopeProvider {
        override val scope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        override val ioScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    }

    // ── 验收测试 ───────────────────────────────────────────────────

    @Test
    fun `month settlement matches Kotlin bit-for-bit across one boundary`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        val expected = advanceKotlinSide(snapshot)

        // ── C++ 被测侧 ──
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        assertExplicitAssertions(actual)
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    /** Kotlin 组合管线：3 旬旬结算 + 1 次月变（跨界检测同生产 tick 序） */
    private fun advanceKotlinSide(snapshot: NativeGameState): NativeGameState {
        val store = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        val serviceAndRng = buildService(store, snapshot.gameData.rngStates)
        val service = serviceAndRng.first
        val gameRng = serviceAndRng.second
        val phaseExecutor = PhaseSettlementExecutor(service)
        val monthExecutor = buildMonthExecutor(service, gameRng)
        val timeSystem = TimeSystem(store)
        store.update {
            repeat(PHASES) {
                val prevMonth = gameData.gameMonth
                timeSystem.onPhaseTick(this, phasesToSettle = 1)
                phaseExecutor.execute(this)
                if (gameData.gameMonth != prevMonth) {
                    monthExecutor.execute(this)
                }
            }
        }
        store.gameDataValue = store.gameDataValue.copy(
            rngStates = gameRng.exportStates().toMutableMap()
        )
        return NativeGameState(
            gameData = store.gameDataValue,
            disciples = store.disciplesValue
        )
    }

    /** 场景显式断言（可读性优先，全量结构对拍兜底） */
    private fun assertExplicitAssertions(actual: NativeGameState) {
        val actualGd = actual.gameData
        // ① 灵矿 lastSettledMonth 无条件推进到当前绝对月（1 年 × 12 + 2 月 = 14；
        //    矿空 rate=0 不入账但推进——差分回档保护语义，双端同构）
        assertEquals(
            "灵矿 lastSettledMonth 未无条件推进",
            (1 * 12 + 2).toLong(),
            actualGd.spiritMineLastSettledMonth.toLong()
        )
        // ③ 政策忠诚：仁政爱徒 +1（50 → 51，coerceIn(0,100)）；四弟子全部生效
        for (d in actual.disciples) {
            assertEquals(
                "弟子 ${d.id} 忠诚未按仁政爱徒 +1",
                (BASE_LOYALTY + BENEVOLENT_LOYALTY_DELTA),
                d.skills.loyalty
            )
        }
        // ③ S1 政策月费经真实钱包扣除：100 × 4 弟子
        assertEquals(
            "仁政爱徒月费未正确扣除",
            10000L - BENEVOLENT_MONTHLY_COST_PER_DISCIPLE * DISCIPLE_COUNT,
            actualGd.spiritStones
        )
        // ② 伴侣配对：0.006 概率下预期无命中（partnerId 保持 null）；
        //    SYSTEM 分区终态已含 4 组合各一次 nextDouble 的状态推进（全量对拍兜底）
        for (d in actual.disciples) {
            assertEquals("弟子 ${d.id} 意外配对", null, d.social.partnerId)
        }
    }

    // ── JSON 结构对拍（C++ 导出键集为权威覆盖面；与 T2.1 同构） ──────

    private fun assertCppSurfaceMatches(expected: JsonElement, actual: JsonElement) {
        assertNodeMatches(expected, actual, "$")
    }

    private fun assertNodeMatches(expected: JsonElement, actual: JsonElement, path: String) {
        when {
            actual is JsonObject && expected is JsonObject ->
                compareObjects(expected, actual, path)
            actual is JsonArray && expected is JsonArray ->
                compareArrays(expected, actual, path)
            else -> assertPrimitiveEquals(expected, actual, path)
        }
    }

    private fun compareObjects(
        expected: JsonObject,
        actual: JsonObject,
        path: String
    ) {
        for ((k, a) in actual) {
            if (k == "timestamp") continue   // 现实墙钟：Clock 注入边界
            val e = expected[k]
            assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失（协议漂移）", e != null)
            assertNodeMatches(e!!, a, "$path.$k")
        }
    }

    private fun compareArrays(
        expected: JsonArray,
        actual: JsonArray,
        path: String
    ) {
        assertEquals("$path size", expected.size, actual.size)
        actual.forEachIndexed { i, a ->
            assertNodeMatches(expected[i], a, "$path[$i]")
        }
    }

    /** 数字统一 IEEE754 double 位比较（C++ 导出整值 double 规范化为整数形式） */
    private fun assertPrimitiveEquals(
        expected: JsonElement,
        actual: JsonElement,
        path: String
    ) {
        require(actual is JsonPrimitive && expected is JsonPrimitive) {
            "$path 结构不匹配：期望=$expected 实际=$actual"
        }
        assertTrue("$path 期望=$expected 实际=$actual", primitivesEqual(expected, actual))
    }

    private fun primitivesEqual(expected: JsonPrimitive, actual: JsonPrimitive): Boolean =
        when {
            actual === JsonNull || expected === JsonNull -> actual == expected
            actual.booleanOrNull != null || expected.booleanOrNull != null ->
                actual.booleanOrNull == expected.booleanOrNull
            else -> numericOrStringEquals(expected, actual)
        }

    private fun numericOrStringEquals(
        expected: JsonPrimitive,
        actual: JsonPrimitive
    ): Boolean {
        val eD = expected.doubleOrNull
        val aD = actual.doubleOrNull
        return if (eD != null && aD != null) {
            java.lang.Double.doubleToLongBits(eD) ==
                java.lang.Double.doubleToLongBits(aD)
        } else {
            actual.content == expected.content
        }
    }
}

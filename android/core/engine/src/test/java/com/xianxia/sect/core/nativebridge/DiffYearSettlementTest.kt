package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.PillEffectApplier
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.AutoPillService
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationRateCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.CultivationSettlement
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.engine.service.DiscipleBreakthroughHandler
import com.xianxia.sect.core.engine.service.DiscipleLifecycleProcessor
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.service.EquipmentNurtureService
import com.xianxia.sect.core.engine.service.HpMpRecoveryService
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.engine.service.MerchantAndRecruitService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
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
import com.xianxia.sect.core.engine.service.CultivationEventProcessor
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.ManualDatabase.ManualTemplate
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * DiffYearSettlementTest — 年变结算跨语言差分对拍（T2.3 验收核心）。
 *
 * 守护目标：C++ `gamecore::system::runYearSettlement`（注册于 onYearChange，
 * 经 nativeCoreAdvancePhases 跨年界触发）与 Kotlin `GameEngineCore` 同构组合
 * 管线（旬结算 → 跨年检测 → YearSettlementExecutor → MonthSettlementExecutor）
 * 的年变语义**逐位一致**。
 *
 * 场景覆盖：
 * ① 年报快照：yearlyReports 追加 YearlyReport(year-1, annual* 快照值) +
 *    annual* 十二项清零（含 annualTheftCount）
 * ② 年俸发放：yearlySalary[9]=500 enabled × 2 弟子 → spiritStones -1000、
 *    每人袋 +500、paidCount+1、loyalty 50→51（非开源节流）
 * ③ RNG 审计（批 Y-4b 行为基线更新）：T2-③ 商人收购每年消费 SYSTEM 分区
 *    （数量/品阶/选池/库存/grade/价格）——SYSTEM 终态由全量对拍逐位守护；
 *    BREAKTHROUGH/EXPLORATION 保持播种预抽后初值
 *
 * 规避清单落实（t2-3-semantics.md §4）：worldMapSects 空（驻军轮换恒等 +
 * gameOverCheck 不判定）/ lastRecruitYear=1 差值判据不满足 / recruitList 空 /
 * vassalContracts·scoutInfo·autoBuyEntries 空 / frugality=false /
 * morality≥阈值（执法堂沿用 T2.2 规避）/ timestamp 对拍排除。
 *
 * 批 Y-4b（T2-③）：Kotlin 臂换装真实 MerchantAndRecruitService（收购流
 * 对拍主体——C++ runYearSettlement 每年执行收购）+ ManualDatabase 从静态
 * 数据中性源快照（/templates/manual_db_sample.json，与 C++ manual_db.h
 * 同源同序）注入真实功法表；年变后 flushYearlyOpsQueue forceDrain 触发
 * T2 组执行（对齐生产引擎 tick drain）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffYearSettlementTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** 推进 3 旬：(1,12,下旬)→(2,1,上旬) 跨年，再 2 旬进入 1 月中下旬 */
    private companion object {
        const val PHASES = 3
        const val SEED = 20261001L

        /** realm=9 年俸额 */
        const val SALARY_REALM9 = 500L

        /** 初始忠诚缺省 */
        const val BASE_LOYALTY = 50

        /** 弟子数 */
        const val DISCIPLE_COUNT = 2
    }

    // ── 场景构建 ────────────────────────────────────────────────────

    /**
     * 批 Y-4b（T2-③ 商人收购换装前置）：从静态数据中性源快照
     *（/templates/manual_db_sample.json——与 C++ manual_db.h 同源同序，
     * 由 scripts/gen-manual-db.mjs 生成）注入真实功法表——收购/交易池的
     * 功法条目（name/rarity/price/type）与 C++ 侧逐条对齐（池大小/池序/
     * 价格双端一致，nextInt(pool.size) 与选中条目可对拍）。
     * 其余字段（stats/skill*）收购/交易生成不消费，默认值即可。
     */
    @Before
    fun initManualDatabaseFromSnapshot() {
        val resource = javaClass.getResourceAsStream("/templates/manual_db_sample.json")
            ?: return
        val root = json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
        val templates = mutableMapOf<String, ManualDatabase.ManualTemplate>()
        root.getValue("entries").jsonArray.forEach { e ->
            val o = e.jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            templates[id] = ManualDatabase.ManualTemplate(
                id = id,
                name = o.getValue("name").jsonPrimitive.content,
                type = when (o.getValue("type").jsonPrimitive.content) {
                    "ATTACK" -> ManualType.ATTACK
                    "DEFENSE" -> ManualType.DEFENSE
                    "MIND" -> ManualType.MIND
                    else -> ManualType.SUPPORT
                },
                rarity = o.getValue("rarity").jsonPrimitive.content.toInt(),
                description = o["description"]?.jsonPrimitive?.content ?: "",
                price = o["price"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            )
        }
        ManualDatabase.resetForTest()
        ManualDatabase.initializeWithManuals(templates)
    }

    /** 恢复未初始化态——防污染其他条件初始化 ManualDatabase 的测试类。 */
    @org.junit.After
    fun resetManualDatabase() {
        ManualDatabase.resetForTest()
    }

    private fun buildSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 12, gamePhase = 2,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            // 场景②：年俸配置（realm9 启用 500；字段为只读 Map 接口，整体替换赋值）
            yearlySalary = mapOf(9 to SALARY_REALM9.toInt())
            yearlySalaryEnabled = mapOf(9 to true)
            // 规避清单：招募刷新差值判据不满足（year-last ≥ 3 才刷新）
            lastRecruitYear = 1
            // 批 Y-1 规避：商人刷新机会首次授予（T1-⑥——Kotlin 臂 mock 的
            // merchantAndRecruitService 零行为，C++ 侧 lastGrant==0 会授予 →
            // 置 lastGrant=当前年使 C++ 侧差值不满足 → 双端零效果）
            merchantLastRefreshChanceGrantYear = 1
            // 年报快照可观察输入：旧年计数非零
            annualTotalIncome = 3000L
            annualAlchemyCount = 2
        }
        return NativeGameState(
            gameData = gameData,
            disciples = listOf(
                salaryDisciple("21", "岁一"),
                salaryDisciple("22", "岁二")
            )
        )
    }

    /** 年俸适格弟子：realm9 名非空、满血哨兵、低修为不触发突破 */
    private fun salaryDisciple(id: String, name: String) = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1,
        cultivation = 10.0, spiritRootType = "metal",
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    private fun initialRngStates(seed: Long): MutableMap<Int, Long> {
        val states = mutableMapOf<Int, Long>()
        RngPartition.values().forEach { partition ->
            val rng = DeterministicRng.fromSeed(seed + partition.id)
            repeat(3) { rng.nextInt() }
            states[partition.id] = rng.snapshot()
        }
        return states
    }

    // ── Kotlin 基准侧（与 Phase/Month diff 测试装配同构） ─────────────

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
            // 批 Y-4b（T2-③）：真实商人服务——C++ runYearSettlement 每年执行
            // 收购（SYSTEM 分区），Kotlin 臂必须真实执行（mock 零行为失配）
            merchantAndRecruitService = MerchantAndRecruitService(store, gameRng),
            caveExplorationProcessor = mockSmart(),
            sharedState = CultivationSharedState(),
            discipleService = mockSmart()
        ) to gameRng
    }

    /** 真实 CultivationEventProcessor + 定向惰性依赖（论证见 t2-3-report §A） */
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
        // 批 Y-3（T1-③ 下沉）：换装真实 DiscipleLifecycleProcessor——C++
        // runYearSettlement 已执行死亡链（老化 age+1/死亡处理），Kotlin 臂必须
        // 真实老化（mock 零行为 → age 失配）；场景弟子 age 低不死亡 → 槽位/
        // 哀悼/DAO 平台效应零触发（discipleSlotCleanup/productionCoordinator/
        // inventorySystem/deathHandler mock 无害）
        val lifecycle = DiscipleLifecycleProcessor(
            stateStore = store,
            scopeProvider = scopeProvider,
            productionCoordinator = mockSmart(),
            eventBus = EventBus(scopeProvider),
            discipleSlotCleanup = mockSmart(),
            lawEnforcementProcessor = mockSmart(),
            discipleStatusService = mockSmart(),
            ioDispatcher = IoDispatcher(),
            inventorySystem = mockSmart(),
            deathHandler = mockSmart()
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
            // 批 Y-4b（T2-③）：真实商人服务（收购流对拍主体——T2 #12 由
            // flushYearlyOpsQueue 触发；功法表经 @Before 快照注入与 C++ 对齐）
            merchantAndRecruitService = MerchantAndRecruitService(store, gameRng),
            caveExplorationProcessor = mockSmart(),
            discipleLifecycleProcessor = lifecycle,
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
            deathHandler = mockSmart(),
            gameConfigProvider = GameConfigProvider(ConfigLoader({ null }))
        )
    }

    /** 月变编排器（SystemManager 仅装 PartnerSystem——缺席 ≡ 场景恒零） */
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
    fun `year settlement matches Kotlin bit-for-bit across one boundary`() {
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

    /**
     * Kotlin 组合管线：旬结算 → （跨年）年变 → （跨月）月变，
     * 与 C++ SettlementEngine.advanceOnePhase 钩子序同构。
     */
    private fun advanceKotlinSide(snapshot: NativeGameState): NativeGameState {
        val store = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        val serviceAndRng = buildService(store, snapshot.gameData.rngStates)
        val service = serviceAndRng.first
        val gameRng = serviceAndRng.second
        val phaseExecutor = PhaseSettlementExecutor(service)
        val yearExecutor = YearSettlementExecutor(service)
        val monthExecutor = buildMonthExecutor(service, gameRng)
        val timeSystem = TimeSystem(store)
        runTest {
            repeat(PHASES) {
                var yearChanged = false
                var monthChanged = false
                store.update {
                    val prevYear = gameData.gameYear
                    val prevMonth = gameData.gameMonth
                    timeSystem.onPhaseTick(this, phasesToSettle = 1)
                    phaseExecutor.execute(this)
                    yearChanged = gameData.gameYear != prevYear
                    monthChanged = gameData.gameMonth != prevMonth
                }
                // 年变先于月变（对齐生产 processMonthYearChange 与 C++
                // settlement.h 钩子序）；年变执行器自开事务，在 update 事务外调用
                if (yearChanged) {
                    val gd = store.gameDataValue
                    yearExecutor.execute(
                        gameYear = gd.gameYear,
                        isJanuary = gd.gameMonth == 1
                    )
                    // 批 Y-4b（T2-③）：年变 T2 延迟组（收购/交易/AI 招募）经
                    // flushYearlyOpsQueue forceDrain 全量执行——对齐生产引擎
                    // tick drain 语义；收购（真实 MerchantAndRecruitService）
                    // 在独立事务消费 SYSTEM 分区，C++ 侧同序执行
                    service.flushYearlyOpsQueue()
                }
                if (monthChanged) {
                    store.update { monthExecutor.execute(this) }
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

    /** 场景显式断言（全量结构对拍兜底） */
    private fun assertExplicitAssertions(actual: NativeGameState) {
        val actualGd = actual.gameData

        // 时间推进到位：(2, 1, 上旬)
        assertEquals(2, actualGd.gameYear)
        assertEquals(1, actualGd.gameMonth)

        // ① 年报快照：归属 year1，annual* 输入值被捕获
        assertTrue("年报未生成", actualGd.yearlyReports.isNotEmpty())
        val report = actualGd.yearlyReports.last()
        assertEquals(1, report.year)
        assertEquals(3000L, report.totalIncome)
        assertEquals(2, report.alchemyCompleted)

        // ① annual* 清零
        assertEquals(0L, actualGd.annualTotalIncome)
        assertEquals(0, actualGd.annualAlchemyCount)

        // ② 年俸发放：spiritStones -Σ原额(1000)；每人袋 +500/paid+1/loyalty 51
        assertEquals(
            "年俸扣减额不符",
            10000L - SALARY_REALM9 * DISCIPLE_COUNT,
            actualGd.spiritStones
        )
        for (d in actual.disciples) {
            assertEquals(
                "弟子 ${d.id} 年俸袋入不符",
                SALARY_REALM9,
                d.equipment.storageBagSpiritStones
            )
            assertEquals(
                "弟子 ${d.id} paidCount 不符",
                1,
                d.skills.salaryPaidCount
            )
            assertEquals(
                "弟子 ${d.id} 忠诚未 +1",
                BASE_LOYALTY + 1,
                d.skills.loyalty
            )
        }

        // ③ RNG 审计（批 Y-4b 行为基线更新）：T2-③ 商人收购每年消费 SYSTEM
        // 分区（数量/品阶/选池/库存/grade/价格）——SYSTEM 终态由全量对拍
        //（rngStates 结构）与 Kotlin 臂逐位一致守护；BREAKTHROUGH/EXPLORATION
        // 保持播种预抽后初值（年变不消费）
        for (p in listOf(RngPartition.BREAKTHROUGH, RngPartition.EXPLORATION)) {
            val fresh = DeterministicRng.fromSeed(SEED + p.id)
            repeat(3) { fresh.nextInt() }   // 复刻 initialRngStates 预抽序列
            assertEquals(
                "分区 ${p.name} 在年变路径被意外消耗",
                fresh.snapshot(),
                actualGd.rngStates[p.id]
            )
        }
    }

    // ── JSON 结构对拍（C++ 导出键集为权威覆盖面；与 T2.1/T2.2 同构） ──

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
            if (isMirrorGeneratedField(path, k)) continue
            val e = expected[k]
            assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失（协议漂移）", e != null)
            assertNodeMatches(e!!, a, "$path.$k")
        }
    }

    /**
     * diff 面排除的镜像生成/边界字段：
     * - timestamp：现实墙钟（Clock 注入边界）
     * - merchantAcquisitionItems 的 id/itemId（批 Y-4b）：Kotlin UUID vs
     *   C++ 确定性自增（gc-trade-N），语义等价仅保证唯一——收购内容其余
     *   字段（name/type/rarity/price/quantity/grade/年份，price 经 S-22
     *   清偿已收敛 SYSTEM 分区）与 RNG 终态逐位对拍
     */
    private fun isMirrorGeneratedField(path: String, k: String): Boolean = when {
        k == "timestamp" -> true
        (k == "id" || k == "itemId") && path.contains("merchantAcquisitionItems") -> true
        else -> false
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

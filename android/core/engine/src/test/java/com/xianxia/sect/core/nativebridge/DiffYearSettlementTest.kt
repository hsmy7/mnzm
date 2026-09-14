package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.PillEffectApplier
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.domain.favor.FavorEventProcessor
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.engine.service.AutoPillService
import com.xianxia.sect.core.engine.service.CaveExplorationProcessor
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationRateCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.CultivationSettlement
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.engine.service.DiplomacyEventProcessor
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
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
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
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment
import com.xianxia.sect.core.engine.domain.disciple.getTalentEffects

/**
 * DiffYearSettlementTest — 年变结算跨语言差分对拍（验收核心）。
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
 * ③ RNG 审计：商人收购每年消费 SYSTEM 分区
 *    （数量/品阶/选池/库存/grade/价格）——SYSTEM 终态由全量对拍逐位守护；
 *    BREAKTHROUGH/EXPLORATION 保持播种预抽后初值
 *
 * 规避清单落实（t2-3-semantics.md §4）：worldMapSects 空（驻军轮换恒等 +
 * gameOverCheck 不判定）/ lastRecruitYear=1 差值判据不满足 / recruitList 空 /
 * vassalContracts·scoutInfo·autoBuyEntries 空 / frugality=false /
 * morality≥阈值（执法堂沿用月变对拍规避）/ timestamp 对拍排除。
 *
 * 商人收购：Kotlin 臂换装真实 MerchantAndRecruitService（收购流
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
     * 商人收购换装前置：从静态数据中性源快照
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
        // AI 招募换装：AI 独立分区 RNG 播种——对拍场景
        // mapSeed=0（GameData 默认）→ C++ importStateJson 从 mapSeed 播种
        // fromSeed(0 + AI_SECT.id(6)×31337)，Kotlin 臂同源 initForSlot(0)
        AISectDiscipleManager.initForSlot(0L)
    }

    /** 恢复未初始化态——防污染其他条件初始化 ManualDatabase 的测试类。 */
    @org.junit.After
    fun resetManualDatabase() {
        ManualDatabase.resetForTest()
        // 摘除本类注入的 AI 随机源（AISectDiscipleManager 为进程级 object，
        // 残留引用会让后续测试类解析到已废弃的 manager）
        AISectDiscipleManager.resetManagerForTest()
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
            // 场景规避：商人刷新机会首次授予（Kotlin 臂 mock 的
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
        // AI 弟子域随机源归一（阶段 1②）：把夹具的 manager 交给
        // AISectDiscipleManager（R5：禁止自建随机源）——AI 招募/换装必须与本夹具的
        // gameRng 同源，否则 Kotlin 臂与 C++ 臂的 AI 流分叉（对拍恒红）。
        // **顺序关键**：initForSlot 必须在 restoreStates **之后**——restoreStates 会用
        // 快照里的 AI_SECT 键覆盖分区，先播种会被抹掉（实测：分区停在 188022
        // 而非 `0 + 6×31337`，导致两侧招募条数不同）
        AISectDiscipleManager.initialize(gameRng)
        AISectDiscipleManager.initForSlot(0L)
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
        // AI 招募：真实 AI 宗门处理器——构造环断环：
        // CaveExplorationProcessor 直接依赖 eventProcessor，而 eventProcessor
        // 经 Provider<CaveExplorationProcessor> 延迟解析——先构造 eventProcessor
        //（Provider 指向晚绑定的 caveProc），再构造 caveProc 回填
        lateinit var caveProc: CaveExplorationProcessor
        val eventProcessor = buildEventProcessor(
            store, core, handler, settlement, gameRng, scopeProvider
        ) { caveProc }
        caveProc = buildCaveExplorationProcessor(store, wallet, eventProcessor)
        return CultivationService(
            stateStore = store,
            cultivationCore = core,
            breakthroughHandler = handler,
            cultivationSettlement = settlement,
            eventProcessor = eventProcessor,
            productionProcessor = mockSmart(),
            recruitService = mockSmart(),
            // 商人收购：真实商人服务——C++ runYearSettlement 每年执行
            // 收购（SYSTEM 分区），Kotlin 臂必须真实执行（mock 零行为失配）
            merchantAndRecruitService = MerchantAndRecruitService(store, gameRng),
            // 真实 AI 宗门处理器（招募路由对拍主体）
            caveExplorationProcessor = javax.inject.Provider { caveProc },
            sharedState = CultivationSharedState(),
        ) to gameRng
    }

    /**
     * 真实 AI 宗门处理器装配（招募路由对拍主体）——
     * processSectDisciplesYearlyRecruitment 走 state 参数 + 静态依赖
     *（AISectDiscipleManager/RecruitService），其余构造依赖惰性 mock。
     */
    private fun buildCaveExplorationProcessor(
        store: FakeGameStateStore,
        wallet: SpiritStoneWallet,
        eventProcessor: CultivationEventProcessor
    ): CaveExplorationProcessor = CaveExplorationProcessor(
        stateStore = store,
        inventorySystem = mockSmart(),
        battleSystem = mockSmart(),
        eventProcessor = eventProcessor,
        analyticsTracker = mockSmart(),
        spiritStoneWallet = wallet,
        deathHandler = mockSmart(),
        aiSectBattleProcessor = mockSmart()
    )

    /** 真实 CultivationEventProcessor + 定向惰性依赖（论证见 t2-3-report §A） */
    private fun buildEventProcessor(
        store: FakeGameStateStore,
        core: CultivationCore,
        handler: DiscipleBreakthroughHandler,
        settlement: CultivationSettlement,
        gameRng: GameRngManager,
        scopeProvider: CoroutineScopeProvider,
        caveExplorationProvider: () -> CaveExplorationProcessor
    ): CultivationEventProcessor {
        val wallet = SpiritStoneWallet(
            store, SpiritStoneLedger(), EventBus(scopeProvider)
        )
        // 死亡链下沉：换装真实 DiscipleLifecycleProcessor——C++
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
            // 商人收购：真实商人服务（收购流对拍主体——由
            // flushYearlyOpsQueue 触发；功法表经 @Before 快照注入与 C++ 对齐）
            merchantAndRecruitService = MerchantAndRecruitService(store, gameRng),
            // AI 招募：真实 AI 宗门处理器（Provider 延迟解析——
            // caveProc 在 buildService 中构造后回填，processSectDisciplesYearlyRecruitment
            // 走 state 参数，其余依赖惰性）
            caveExplorationProcessor = javax.inject.Provider { caveExplorationProvider() },
            discipleLifecycleProcessor = lifecycle,
            // 批收尾（外交簇换装真实）：年变 T2 #13 交易刷新（refreshAllSectTrades
            // ——局部种子 sectId.hashCode()+year，零分区 RNG）+ #15/#16/#19 联盟到期/
            // 低好感解散/好感衰减 + 附庸纳贡与月变附庸脱离真实执行——
            // C++ runYearSettlement 已下沉同路径（Y-4a），Kotlin 臂必须真实执行
            // 才能对拍；场景 sectDetails/alliances/sectRelations/vassalContracts
            // 全空 → 真实服务全部纯早退零写入零 RNG（场景不变 = 零回归）
            diplomacyEventProcessor = DiplomacyEventProcessor(
                store,
                FavorEventProcessor(store, scopeProvider)
            ),
            diplomacyService = DiplomacyService(
                stateStore = store,
                inventorySystem = mockSmart(),
                favorService = mockSmart(),
                spiritStoneWallet = wallet,
                rngManager = gameRng
            ),
            equipmentManager = mockSmart(),
            manualManager = mockSmart(),
            autoBuyService = mockSmart(),
            vassalService = VassalService(store, wallet, gameRng),
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

    /**
     * AI 招募对拍场景快照：AI 宗门 ai-1（中型 level 1——
     * 装备 2/功法 3）+ 1 名现有弟子 + lastAiSectRecruitYear=0；
     * gameYear=3 跨年到 4 → 差值 4-0>=3 触发招募（1..5 名炼气新弟子）。
     * 规避清单：lastRecruitYear/merchantLastRefreshChanceGrantYear 置 3
     *（招募/商人刷新差值不满足）；recruitList 空；sectDetails/联盟/附庸/秘境
     * 空（其余年变步骤零效果）；mapSeed=0（AI RNG = fromSeed(0+6*31337)，
     * 与 @Before initForSlot(0) 同源）。
     */
    private fun buildAiSectSnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 3, gameMonth = 12, gamePhase = 2,
            spiritStones = 10000L
        ).apply {
            // 键 6（AI_SECT）已在协议面**退役**（阶段 1②：AI 流权威态由宿主侧
            // `aiRng_` 承载，随 9 号通道键落盘）——快照里带 6 号会让 C++ 导入时
            // 按该值覆写 aiRng_，与 Kotlin 侧 `mapSeed + 6×31337` 播种态分叉。
            // 键 9（AI_SECT_MIRROR）**必须一并摘除**：C++ importStateInternal
            // 的"存档续接"语义是"快照带非 0 键 9 → 以其覆盖 mapSeed 重播态"
            //（game_core.cpp importStateInternal）——initialRngStates 盲扫写入的
            // 9 号值是 fromSeed(SEED+9) 预抽 3 次的无关状态，续接后 AI 流首抽
            // 即分叉（实测首名新招募弟子名字即不同）。本场景刻意走 mapSeed
            // 播种路径（Kotlin 臂 initForSlot(0) 同源 fromSeed(0+6×31337)），
            // 故两侧 AI 流种子只经 mapSeed 公式对齐，快照不带任何 AI 通道键。
            rngStates = (initialRngStates(SEED) -
                RngPartition.AI_SECT.id -
                RngPartition.AI_SECT_MIRROR.id).toMutableMap()
            lastRecruitYear = 3          // 招募刷新差值 4-3<3 不刷新
            merchantLastRefreshChanceGrantYear = 3   // 商人刷新机会差值 <30 不授予
        }
        val aiDisciple = Disciple(
            id = "ai-a1", name = "青云长老", surname = "青",
            gender = "male", realm = 7, realmLayer = 1,
            cultivation = 100.0, spiritRootType = "metal",
            age = 100, isAlive = true,
            combat = CombatAttributes(baseHp = 500, currentHp = -1, currentMp = -1)
        )
        return NativeGameState(
            gameData = gameData,
            aiSectDisciples = mapOf(
                "ai-1" to listOf(aiDisciple)
            ),
            disciples = emptyList()
        )
    }

    /** 玩家宗门（aiSect 场景的 worldMapSects 构造辅助）。 */
    private fun buildAiSectWorld(): List<WorldSect> {
        return listOf(
            WorldSect(
                id = "ai-1", name = "青云宗", level = 1,
                x = 100f, y = 100f, isPlayerSect = false
            )
        )
    }

    /**
     * 批收尾（外交簇换装对拍）场景快照：玩家宗门 p1 + AI 宗门 ai-1/ai-2，
     * 触发年变 T2 外交三路径真实执行：
     * - #13 交易刷新（refreshAllSectTrades）：sectDetails{ai-1} 空交易 →
     *   差值/空列表判据满足 → 局部种子（sectId.hashCode()+year）生成 20 条
     * - #15 联盟到期（checkAllianceExpiry）：A1{player,ai-1} startYear=1 →
     *   year7 差值 6 >= 5 解散
     * - #16 联盟好感过低（checkAllianceFavorDrop）：A2{player,ai-2}
     *   startYear=6 未到期 + 关系 favor 75 < 80 → 解散
     * - #19 好感衰减（processFavorDecay）：rel(ai-1) favor 85 且距上次
     *   交互 2 年 → 84 + noGiftYears+1；rel(ai-2) favor 75 不衰减
     * 规避清单：lastRecruitYear/merchantLastRefreshChanceGrantYear/
     * lastAiSectRecruitYear 置 6（差值判据不满足）；弟子/招募/附庸/秘境空。
     * 玩家宗门 id 用 "player" 哨兵（联盟 sectIds 同哨兵，C++/Kotlin 双侧一致）。
     */
    private fun buildDiplomacySnapshot(): NativeGameState {
        val gameData = GameData(
            gameYear = 6, gameMonth = 12, gamePhase = 2,
            spiritStones = 10000L
        ).apply {
            rngStates = initialRngStates(SEED)
            lastRecruitYear = 6
            merchantLastRefreshChanceGrantYear = 6
            lastAiSectRecruitYear = 6
            // 月变步骤 4e（关卡刷新生成）规避：玩家宗门在场会触发 C++ 侧
            // 关卡生成（Kotlin 臂 SystemManager 未装 WorldLevelSystem 零生成
            // 失配）——lastRefreshMonth 置远未来哨兵使 shouldRefresh 恒 false
            worldLevelLastRefreshMonth = 1000
            worldMapSects = listOf(
                WorldSect(
                    id = "player", name = "青云宗", level = 1,
                    x = 0f, y = 0f, isPlayerSect = true, allianceId = "a1"
                ),
                WorldSect(
                    id = "ai-1", name = "太一宗", level = 1,
                    x = 100f, y = 100f, isPlayerSect = false, allianceId = "a1"
                ),
                WorldSect(
                    id = "ai-2", name = "玄冥宗", level = 1,
                    x = 200f, y = 200f, isPlayerSect = false, allianceId = "a2"
                )
            )
            // ai-1 空交易详情 → 交易刷新差值/空列表判据满足触发刷新
            sectDetails = mapOf("ai-1" to SectDetail(sectId = "ai-1"))
            alliances = listOf(
                Alliance(id = "a1", sectIds = listOf("player", "ai-1"), startYear = 1),
                Alliance(id = "a2", sectIds = listOf("player", "ai-2"), startYear = 6)
            )
            // sectId1/sectId2 按字典序（"ai-*" < "player"）
            sectRelations = listOf(
                SectRelation(
                    sectId1 = "ai-1", sectId2 = "player",
                    favor = 85, lastInteractionYear = 5, noGiftYears = 0, acquainted = true
                ),
                SectRelation(
                    sectId1 = "ai-2", sectId2 = "player",
                    favor = 75, lastInteractionYear = 6, noGiftYears = 0, acquainted = true
                )
            )
        }
        return NativeGameState(gameData = gameData, disciples = emptyList())
    }

    /**
     * 批收尾（外交簇换装对拍）：年变 T2 #13 交易刷新 / #15 联盟到期 /
     * #16 联盟好感过低解散 / #19 好感衰减——真实 DiplomacyService/
     * DiplomacyEventProcessor/FavorEventProcessor vs C++ runYearSettlement
     * 同路径逐位对拍（交易刷新首次跨语言整链：局部种子商品生成全字段）。
     */
    @Test
    fun `diplomacy yearly ops match Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildDiplomacySnapshot()
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

        // 显式断言：外交三路径产生写效果
        val actualGd = actual.gameData
        assertEquals(7, actualGd.gameYear)
        // 交易刷新：ai-1 空交易 → 刷新生成
        val trade = actualGd.sectDetails["ai-1"]
        assertTrue(
            "sectDetails[ai-1] 应被刷新（原空列表 → 生成交易商品），实际 ${trade?.tradeItems?.size}",
            trade != null && trade.tradeItems.isNotEmpty()
        )
        assertEquals("ai-1 交易刷新年应写回 7", 7, trade!!.tradeLastRefreshYear)
        // 联盟到期（a1）+ 低好感解散（a2）→ 联盟全清
        assertTrue("过期联盟 a1 与低好感联盟 a2 均应被解散", actualGd.alliances.isEmpty())
        for (sect in actualGd.worldMapSects) {
            assertEquals("宗门 ${sect.id} 联盟字段应清零", "", sect.allianceId)
        }
        // 好感衰减：rel(ai-1) favor 85→84 + noGiftYears+1；rel(ai-2) favor 75 不衰减
        val relAi1 = actualGd.sectRelations.find {
            (it.sectId1 == "ai-1" && it.sectId2 == "player") ||
                (it.sectId1 == "player" && it.sectId2 == "ai-1")
        }
        assertTrue("rel(ai-1) 应存在", relAi1 != null)
        assertEquals("rel(ai-1) favor 应衰减 85→84", 84, relAi1!!.favor)
        assertEquals("rel(ai-1) noGiftYears 应 +1", 1, relAi1.noGiftYears)
        val relAi2 = actualGd.sectRelations.find {
            (it.sectId1 == "ai-2" && it.sectId2 == "player") ||
                (it.sectId1 == "player" && it.sectId2 == "ai-2")
        }
        assertTrue("rel(ai-2) 应存在", relAi2 != null)
        assertEquals("rel(ai-2) favor 75 不应衰减", 75, relAi2!!.favor)

        // 全量结构对拍（sectDetails.tradeItems 的 id/itemId 镜像生成字段排除）
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    @Test
    fun `ai sect yearly recruitment matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildAiSectSnapshot()
        // Kotlin 臂场景快照需含 worldMapSects（招募路由遍历 aiSectDisciples
        // 时 find sect——无 worldMapSects 则 continue 不生成）
        val kotlinSnapshot = snapshot.copy(
            gameData = snapshot.gameData.copy(
                worldMapSects = buildAiSectWorld()
            )
        )
        // C++ 臂同输入（GameState.worldMapSects 在 gameData 内）
        val encoded = json.encodeToString(NativeGameState.serializer(), kotlinSnapshot)

        val expected = advanceKotlinSide(kotlinSnapshot)

        // ── C++ 被测侧 ──
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 显式断言：招募触发 + 路由 + 年份推进
        val actualGd = actual.gameData
        assertEquals(4, actualGd.gameYear)
        assertEquals(4, actualGd.lastAiSectRecruitYear)
        val sectDisciples = actual.aiSectDisciples?.get("ai-1").orEmpty()
        assertTrue(
            "AI 宗门应新增弟子（原 1 + 新增 1..5），实际 ${sectDisciples.size}",
            sectDisciples.size in 2..6
        )
        for (d in sectDisciples.drop(1)) {
            assertEquals("新招募弟子应炼气一层", 9, d.realm)
        }
        // 全量结构对拍（aiSectDisciples 弟子全字段逐位一致——id 镜像排除）
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

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
            it.gameDataValue = snapshot.gameData.apply {
                // AI 招募：GameData.aiSectDisciples @Transient 内存字段
                //（不入 gameData JSON）——从 NativeGameState 顶层快照回填，
                // Kotlin 臂招募路由才能读到 AI 宗门弟子池
                aiSectDisciples = snapshot.aiSectDisciples ?: emptyMap()
            }
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
                    // 商人收购：年变 T2 延迟组（收购/交易/AI 招募）经
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
            // AI 招募：AI 弟子池为 GameData @Transient 内存字段
            //（不入 gameData JSON）——经 NativeGameState 顶层承载回填；
            // 空表返回 null（与 C++ 空表不导出键的协议对称——可空语义）
            aiSectDisciples = store.gameDataValue.aiSectDisciples.takeIf { it.isNotEmpty() },
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

        // ③ RNG 审计：商人收购每年消费 SYSTEM
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

    // ── JSON 结构对拍（C++ 导出键集为权威覆盖面；与每旬/月变对拍同构） ──

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
            // rngStates 段按**双侧共有键**比较：阶段 1② 归一后 AI 流的权威态在
            // C++ `aiRng_`（随 9 号键落盘），Kotlin 侧 6 号（AI_SECT）不再与 C++
            // 同源；两侧键集本就不同，协议语义差异只在共有键上成立
            // （写成 if/else 而非 `continue`——循环体内已有 1 处 continue，
            // detekt LoopWithTooManyJumpStatements 阈值为 1）
            if (k == "rngStates") {
                val expectedRng = e?.jsonObject ?: JsonObject(emptyMap())
                val actualRng = a.jsonObject
                for ((pid, av) in actualRng) {
                    val ev = expectedRng[pid] ?: continue
                    assertNodeMatches(ev, av, "$path.$k.$pid")
                }
            } else {
                assertNodeMatches(e!!, a, "$path.$k")
            }
        }
    }

    /**
     * diff 面排除的镜像生成/边界字段：
     * - timestamp：现实墙钟（Clock 注入边界）
     * - merchantAcquisitionItems 的 id/itemId：Kotlin UUID vs
     *   C++ 确定性自增（gc-trade-N），语义等价仅保证唯一——收购内容其余
     *   字段（name/type/rarity/price/quantity/grade/年份，price 经 S-22
     *   已收敛 SYSTEM 分区）与 RNG 终态逐位对拍
     * - aiSectDisciples[*].id：AI 弟子 id Kotlin UUID vs C++
     *   确定性自增（gc-ai-d-N），语义等价仅保证唯一——弟子全字段（名字/
     *   性别/灵根/属性/技能/装备/功法）双端逐位对拍
     * - sectDetails[*].tradeItems[].id/itemId：宗门交易商品 id
     *   Kotlin UUID vs C++ 确定性自增，语义等价仅保证唯一——商品其余字段
     *   （name/type/rarity/price/quantity/grade/obtainedYear/obtainedMonth）
     *   与 RNG 终态逐位对拍
     */
    private fun isMirrorGeneratedField(path: String, k: String): Boolean = when {
        k == "timestamp" -> true
        (k == "id" || k == "itemId") && path.contains("merchantAcquisitionItems") -> true
        k == "id" && path.contains("aiSectDisciples") -> true   // AI 招募
        (k == "id" || k == "itemId") && path.contains("sectDetails") &&
            path.contains("tradeItems") -> true                  // 批收尾
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

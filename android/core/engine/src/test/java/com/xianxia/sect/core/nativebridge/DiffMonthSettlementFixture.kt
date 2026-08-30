package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
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
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.service.ProductionProcessor
import com.xianxia.sect.core.engine.domain.disciple.PillEffectApplier
import com.xianxia.sect.core.engine.service.RelativeGiftHandler
import com.xianxia.sect.core.engine.service.CultivationEventProcessor
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmAIProcessor
import com.xianxia.sect.core.engine.service.DisciplePurchaseService
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.ExplorationTickSystem
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.engine.system.ChildBirthSystem
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.exploration.WorldLevelManager
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.engine.domain.diplomacy.VassalService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.service.AutoBuyService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet

/**
 * DiffMonthSettlementFixture — DiffMonthSettlementTest / DiffWorldLevelRefreshTest
 * 共享的 Kotlin 臂装配（月变八步对拍基准：真实 CultivationService +
 * 定向真实服务 + mock 惰性依赖）。
 *
 * 从 DiffMonthSettlementTest 提取（LargeClass 拆分，2026-08-30 批 13-2b）：
 * 语义零变更——仅实例方法改 internal 顶层函数，测试类调用点对应对接。
 * 装配细节（真实/定向 mock 论证）见原测试类 KDoc 与 t2-2-report.md §A。
 */

/** 月变对拍推进旬数（(1,1,上旬) → (1,2,上旬)，跨一个月界） */
internal const val DIFF_MONTH_PHASES = 3

/** 批 13-1：真实 AISectBeastAttackProcessor（precomputeTargets 对拍主体） */
internal fun buildBeastAttackProcessor(
    store: FakeGameStateStore,
    gameRng: GameRngManager
): AISectBeastAttackProcessor = AISectBeastAttackProcessor(
    stateStore = store,
    battleSystem = mockSmart(),
    rngManager = gameRng,
    encounterBattleService = mockSmart()
)

/** 批 13-2a：真实 LawEnforcementProcessor（教化之道偷盗判定钩子对拍主体；
 *  lifecycle 用 mock——捕获思过/叛逃清理在钩子场景中不触达或恒等） */
internal fun buildLawEnforcement(
    store: FakeGameStateStore,
    gameRng: GameRngManager
): LawEnforcementProcessor = LawEnforcementProcessor(
    stateStore = store,
    rngManager = gameRng,
    discipleLifecycleProcessor = mockSmart(),
    lootCalculator = LootCalculator(gameRng)
)

/** 批 13-3：真实 ProductionProcessor（月变步骤 6 自动排班对拍主体） */
internal fun buildProductionProcessor(
    store: FakeGameStateStore,
    gameRng: GameRngManager,
    scopeProvider: CoroutineScopeProvider
): ProductionProcessor = ProductionProcessor(
    stateStore = store,
    inventorySystem = mockSmart(),
    productionCoordinator = mockSmart(),
    productionSlotRepository = mockSmart(),
    formulaService = mockSmart(),
    rngManager = gameRng,
    scopeProvider = scopeProvider,
    ioDispatcher = mockSmart(),
    inventoryConfig = com.xianxia.sect.core.config.InventoryConfig()
)

/** 月变对拍服务装配（真实 CultivationService + 定向真实依赖 + mock 惰性面） */
internal fun buildMonthDiffService(
    store: FakeGameStateStore,
    rngStates: Map<Int, Long>
): Triple<CultivationService, GameRngManager, AISectBeastAttackProcessor> {
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
    // 批 13-1：真实 AISectBeastAttackProcessor（precomputeTargets 对拍主体；
    // battleSystem/encounterBattleService 用 mock——对拍场景 targets 空
    // 或未触发子事件 9 战斗，processRemainingTargets 纯早退不调用）
    val aiBeastAttackProcessor = buildBeastAttackProcessor(store, gameRng)
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
    // 批 13-2a：教化之道偷盗判定钩子对拍主体——CultivationSettlement
    // 换装真实 LawEnforcementProcessor（与 eventProcessor 同实例；
    // lifecycle 用 mock——捕获思过/叛逃清理在场景中不触达或恒等）
    val lawEnforcement = buildLawEnforcement(store, gameRng)
    val settlement = CultivationSettlement(
        stateStore = store,
        scopeProvider = scopeProvider,
        spiritStoneWallet = wallet,
        lawEnforcementProcessor = lawEnforcement,
        gameConfigProvider = configProvider
    )
    val eventProcessor = buildMonthDiffEventProcessor(
        store, core, handler, settlement, gameRng, scopeProvider,
        aiBeastAttackProcessor
    )
    // 批 13-3：真实 ProductionProcessor（月变步骤 6 自动排班对拍主体——
    // 灵矿分配路径不依赖 BuildingFeature 注册表/repo 回滚面；生产/住所
    // 面由 GTest 黄金序列守护；其余依赖 mock 惰性）
    val productionProcessor = buildProductionProcessor(store, gameRng, scopeProvider)
    return Triple(
        CultivationService(
            stateStore = store,
            cultivationCore = core,
            breakthroughHandler = handler,
            cultivationSettlement = settlement,
            eventProcessor = eventProcessor,
            productionProcessor = productionProcessor,
            recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(),
            caveExplorationProcessor = mockSmart(),
            sharedState = CultivationSharedState(),
            discipleService = mockSmart()
        ),
        gameRng,
        aiBeastAttackProcessor
    )
}

/** 真实 CultivationEventProcessor + 定向惰性依赖（论证见 t2-2-report.md §A） */
@Suppress("LongMethod")  // 测试装配：按 27 个构造参数逐个传参，行数随依赖面自然增长
internal fun buildMonthDiffEventProcessor(
    store: FakeGameStateStore,
    core: CultivationCore,
    handler: DiscipleBreakthroughHandler,
    settlement: CultivationSettlement,
    gameRng: GameRngManager,
    scopeProvider: CoroutineScopeProvider,
    aiBeastAttackProcessor: AISectBeastAttackProcessor
): CultivationEventProcessor {
    val wallet = SpiritStoneWallet(
        store, SpiritStoneLedger(), EventBus(scopeProvider)
    )
    val inventoryConfig = com.xianxia.sect.core.config.InventoryConfig()
    val configProvider = GameConfigProvider(ConfigLoader({ null }))
    // 批 11-3：真实库存系统 + 自动购买（12 月场景对拍主体）——溢出转邮件
    // NoOp（diff 面不可见，与 C++ 草稿丢弃侧等价）
    val inventorySystem = InventorySystem(
        stateStore = store,
        inventoryConfig = inventoryConfig,
        spiritStoneWallet = wallet,
        gameConfigProvider = configProvider,
        overflowMailHandler = com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
    )
    return CultivationEventProcessor(
        stateStore = store,
        spiritStoneWallet = wallet,
        inventorySystem = inventorySystem,
        inventoryConfig = inventoryConfig,
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
        autoBuyService = AutoBuyService(
            stateStore = store,
            inventorySystem = inventorySystem,
            inventoryConfig = inventoryConfig,
            merchantAndRecruitService = mockSmart(),
            spiritStoneWallet = wallet
        ),
        // 批 10-4：真实附庸服务（脱离流对拍主体——玩家宗门 + 至交附属
        // 场景下恰抽 1 次 SYSTEM 且必不脱离）
        vassalService = VassalService(
            stateStore = store,
            spiritStoneWallet = wallet,
            rngManager = gameRng
        ),
        // 批 12-1：真实弟子智能购买（购买流对拍主体——场景⑪ playerListedItems
        // 非空 + 仓库有货 + 弟子有灵石）
        disciplePurchaseService = DisciplePurchaseService(
            stateStore = store,
            inventorySystem = inventorySystem,
            inventoryConfig = inventoryConfig,
            rngManager = gameRng
        ),
        aiSectBeastAttackProcessor = aiBeastAttackProcessor,
        // 批 10-2：真实执法堂处理器（叛逃流对拍主体）——lifecycle 用 mock：
        // 逃脱路径的 11 槽清理在场景中恒等（叛逃候选无任何槽位引用）
        lawEnforcementProcessor = buildLawEnforcement(store, gameRng),
        rngManager = gameRng,
        secretRealmService = mockSmart(),
        // 批 11-2：真实秘境 AI 派遣处理器（纯数据变换零 RNG——秘境存在 +
        // 有存活 AI 弟子 → 逐月派遣队伍，幂等去重）
        secretRealmAIProcessor = SecretRealmAIProcessor(),
        deathHandler = mockSmart(),
        // 批 12（S-10/S-13）：真实配置 provider（GameConfigNativeBridge
        // register 时 isLoaded=false 安全跳过）
        gameConfigProvider = configProvider
    )
}

/**
 * 月变编排器（批 13-1：步骤 3 换装真实 AISectBeastAttackProcessor；
 * 批 13-2b：步骤 4e 换装真实 ExplorationTickSystem——世界关卡刷新/清理/
 * 妖兽移动对拍主体，WorldLevelManager/LevelGenerator 真实（EXPLORATION
 * 分区消费与 C++ 逐位对齐），巡视/攻击检测 mock（纯早退零效果）；
 * 现有场景 worldLevelLastRefreshMonth 预置当前月 → 不刷新零生成）
 */
internal fun buildMonthDiffExecutor(
    service: CultivationService,
    gameRng: GameRngManager,
    aiBeastAttackProcessor: AISectBeastAttackProcessor,
    store: FakeGameStateStore
): MonthSettlementExecutor {
    val explorationSystem = ExplorationTickSystem(
        explorationService = ExplorationService(
            stateStore = store,
            battleSystem = mockSmart(),
            inventorySystem = mockSmart(),
            cultivationService = mockSmart(),
            spiritStoneWallet = mockSmart(),
            worldLevelManager = WorldLevelManager(
                rngManager = gameRng,
                levelGenerator = LevelGenerator(gameRng)
            ),
            beastAttackDetector = mockSmart(),
            patrolBattleSystem = mockSmart(),
            lootCalculator = mockSmart(),
            rngManager = gameRng,
            encounterBattleService = mockSmart(),
            deathHandler = mockSmart()
        )
    )
    return MonthSettlementExecutor(
        cultivationService = service,
        aiSectBeastAttackProcessor = aiBeastAttackProcessor,
        systemManager = SystemManager(
            setOf(
                PartnerSystem(gameRng),
                explorationSystem,
                // 批 13-4c：真实 ChildBirthSystem（月变步骤 4d 生育对拍主体）
                ChildBirthSystem(
                    stateStore = store,
                    discipleFactory = DiscipleFactory(),
                    rngManager = gameRng
                )
            )
        )
    )
}

/** Kotlin 组合管线：N 旬旬结算 + 月变（跨界检测同生产 tick 序） */
internal fun advanceKotlinMonthSide(
    snapshot: NativeGameState,
    phases: Int = DIFF_MONTH_PHASES
): NativeGameState {
    val store = FakeGameStateStore().also {
        it.gameDataValue = snapshot.gameData
        it.disciplesValue = snapshot.disciples
        // 批 12-1：仓库库存灌入（弟子购买 hasWarehouseStock 依赖）
        it.equipmentStacksValue = snapshot.equipmentStacks
        it.equipmentInstancesValue = snapshot.equipmentInstances
        it.manualStacksValue = snapshot.manualStacks
        it.manualInstancesValue = snapshot.manualInstances
        it.pillsValue = snapshot.pills
        it.materialsValue = snapshot.materials
        it.herbsValue = snapshot.herbs
        it.seedsValue = snapshot.seeds
        it.storageBagsValue = snapshot.storageBags
    }
    val (service, gameRng, aiBeastAttackProcessor) =
        buildMonthDiffService(store, snapshot.gameData.rngStates)
    val phaseExecutor = PhaseSettlementExecutor(service)
    val monthExecutor = buildMonthDiffExecutor(
        service, gameRng, aiBeastAttackProcessor, store
    )
    val timeSystem = TimeSystem(store)
    store.update {
        repeat(phases) {
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
        // 批 10-4：AI 弟子池经顶层字段承载（与 C++ 导出键对齐）
        aiSectDisciples = store.gameDataValue.aiSectDisciples,
        disciples = store.disciplesValue,
        // 批 11-4：库存集合参与对拍（FakeGameStateStore 嵌套事务修复后
        // Kotlin 臂的库存写入保留于 store——含 12 月 autoBuy 入库）
        equipmentStacks = store.equipmentStacksValue,
        equipmentInstances = store.equipmentInstancesValue,
        manualStacks = store.manualStacksValue,
        manualInstances = store.manualInstancesValue,
        pills = store.pillsValue,
        materials = store.materialsValue,
        herbs = store.herbsValue,
        seeds = store.seedsValue,
        storageBags = store.storageBagsValue
    )
}

internal class UnconfinedCoroutineScopeProvider : CoroutineScopeProvider {
    override val scope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    override val ioScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
}

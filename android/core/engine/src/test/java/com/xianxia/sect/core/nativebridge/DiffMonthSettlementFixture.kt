package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationSubSystems
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
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment
import com.xianxia.sect.core.engine.domain.disciple.getTalentEffects

/**
 * DiffMonthSettlementFixture — DiffMonthSettlementTest / DiffWorldLevelRefreshTest
 * 共享的 Kotlin 臂装配（月变八步对拍基准：真实 CultivationService +
 * 定向真实服务 + mock 惰性依赖）。
 *
 * 装配函数为 internal 顶层函数，测试类调用点对应对接。
 * 装配细节（真实/定向 mock 论证）见 t2-2-report.md §A。
 */

/** 月变对拍推进旬数（(1,1,上旬) → (1,2,上旬)，跨一个月界） */
internal const val DIFF_MONTH_PHASES = 3

/**
 * 任务域 RNG（历史口径，W4-C 已收敛）：
 * 原 `initMissionDomainRng` 将 EnemyGenerator 的 ENEMY_GEN 顶层管理器指向本臂
 * gameRng；W4-C 随机源收敛后 ENEMY_GEN 分区改为**形参必传**（生产链
 * MissionSystem ← GameEngineMissionOps 透传引擎自身 `GameRngManager`），
 * 可变全局已摘除，本函数随之删除。
 *
 * **`MissionSystem` 同理**：其 MISSION 分区消费早已改为**形参必传**
 * （调用方各自透传自己持有的 `GameRngManager`），无可变全局状态可注入——
 * 双引擎同进程时后构造者覆写前者会让一侧的月变消费另一侧的分区。
 */

/** 真实 AISectBeastAttackProcessor（precomputeTargets 对拍主体） */
internal fun buildBeastAttackProcessor(
    gameRng: GameRngManager
): AISectBeastAttackProcessor = AISectBeastAttackProcessor(
    battleSystem = mockSmart(),
    rngManager = gameRng,
)

/** 真实 LawEnforcementProcessor（教化之道偷盗判定钩子对拍主体；
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

/** 真实 ProductionProcessor（月变步骤 6 自动排班对拍主体） */
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

/** S4 内存 ProductionSlotDataPort（fake repo 后端——月结生产结算对拍用）。
 *  单线程 Unconfined 语义（无并发）；update 未命中为 no-op（DAO update-where-id 语义）。 */
internal class InMemoryProductionSlotDataPort : com.xianxia.sect.core.repository.ProductionSlotDataPort {
    private val slots = mutableListOf<com.xianxia.sect.core.model.production.ProductionSlot>()

    override fun getAllSync(): List<com.xianxia.sect.core.model.production.ProductionSlot> =
        slots.toList()

    override suspend fun insertAll(toAdd: List<com.xianxia.sect.core.model.production.ProductionSlot>) {
        slots.addAll(toAdd)
    }

    override suspend fun update(slot: com.xianxia.sect.core.model.production.ProductionSlot) {
        val i = slots.indexOfFirst { it.id == slot.id }
        if (i >= 0) slots[i] = slot
    }

    override suspend fun updateAll(toUpdate: List<com.xianxia.sect.core.model.production.ProductionSlot>) {
        toUpdate.forEach { s ->
            val i = slots.indexOfFirst { it.id == s.id }
            if (i >= 0) slots[i] = s
        }
    }

    override suspend fun insert(slot: com.xianxia.sect.core.model.production.ProductionSlot) {
        slots.add(slot)
    }

    override suspend fun deleteById(id: String) {
        slots.removeAll { it.id == id }
    }

    override suspend fun deleteBySlot(slotId: Int) {
        slots.clear()
    }

    override suspend fun deleteBySlotAndBuildingType(
        slotId: Int,
        buildingType: com.xianxia.sect.core.model.production.BuildingType
    ) {
        slots.removeAll { it.buildingType == buildingType }
    }

    /** 场景预置（对拍 snapshot builder 调用） */
    fun seed(toAdd: List<com.xianxia.sect.core.model.production.ProductionSlot>) {
        slots.clear()
        slots.addAll(toAdd)
    }
}

/** S4 生产域真实链装配：真实 InventorySystem + FormulaService + Coordinator +
 *  Repository（InMemory port）→ ProductionProcessor（生产对拍主体）。 */
internal fun buildProductionDiffChain(
    store: FakeGameStateStore,
    gameRng: GameRngManager,
    scopeProvider: CoroutineScopeProvider
): Triple<ProductionProcessor, InMemoryProductionSlotDataPort,
        com.xianxia.sect.core.repository.ProductionSlotRepository> {
    val inventoryConfig = com.xianxia.sect.core.config.InventoryConfig()
    val productionInventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
        stateStore = store,
        inventoryConfig = inventoryConfig,
        overflowMailHandler = com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
    )
    val productionSlotPort = InMemoryProductionSlotDataPort()
    val productionSlotRepository = com.xianxia.sect.core.repository.ProductionSlotRepository(
        productionSlotPort,
        com.xianxia.sect.core.config.BuildingConfigService(
            object : com.xianxia.sect.core.platform.AssetSource {
                override fun open(assetPath: String): java.io.InputStream? = null
            }
        ),
        scopeProvider
    )
    val ioDispatcher = com.xianxia.sect.core.engine.di.IoDispatcher(
        kotlinx.coroutines.Dispatchers.Unconfined
    )
    val productionCoordinator =
        com.xianxia.sect.core.engine.domain.production.ProductionCoordinator(
            productionSlotRepository,
            com.xianxia.sect.core.transaction.ProductionTransactionManager(
                productionSlotRepository, gameRng, ioDispatcher
            )
        )
    val formulaService = com.xianxia.sect.core.engine.service.FormulaService(
        store, productionSlotRepository
    )
    val processor = ProductionProcessor(
        stateStore = store,
        inventorySystem = productionInventorySystem,
        productionCoordinator = productionCoordinator,
        productionSlotRepository = productionSlotRepository,
        formulaService = formulaService,
        rngManager = gameRng,
        scopeProvider = scopeProvider,
        ioDispatcher = ioDispatcher,
        inventoryConfig = inventoryConfig
    )
    return Triple(processor, productionSlotPort, productionSlotRepository)
}

/** S4 生产域对拍装配载体（store/repo 需在场景预置与月结后对齐两处访问） */
internal class MonthDiffHarness(
    val store: FakeGameStateStore,
    val service: CultivationService,
    val gameRng: GameRngManager,
    val aiBeastAttackProcessor: AISectBeastAttackProcessor,
    val productionSlotPort: InMemoryProductionSlotDataPort,
    val productionSlotRepository: com.xianxia.sect.core.repository.ProductionSlotRepository
)

/** 月变对拍服务装配（真实 CultivationService + 定向真实依赖 + mock 惰性面；
 *  S4 生产域全真实链——真实 InventorySystem/FormulaService/ProductionCoordinator/
 *  Repository(InMemory port)） */
internal fun buildMonthDiffHarness(
    store: FakeGameStateStore,
    rngStates: Map<Int, Long>
): MonthDiffHarness {
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
    // W4-C 随机源收敛：EnemyGenerator 的 ENEMY_GEN 分区已改形参必传
    //（原 initMissionDomainRng 全局注入随顶层可变 enemyGenRngManager 一并摘除；
    // 生产链 MissionSystem ← GameEngineMissionOps 透传引擎自身的 gameRngManager，
    // harness 侧无需再注入）
    // 真实 AISectBeastAttackProcessor（precomputeTargets 对拍主体；
    // battleSystem/encounterBattleService 用 mock——对拍场景 targets 空
    // 或未触发子事件 9 战斗，processRemainingTargets 纯早退不调用）
    val aiBeastAttackProcessor = buildBeastAttackProcessor(gameRng)
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
    // 教化之道偷盗判定钩子对拍主体——CultivationSettlement
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
    // 生产域全真实链（既有场景槽位/repo 全空 → 完成结算与自动
    // 排班纯早退零抽取；生产场景槽位预置后真实触发）
    val (productionProcessor, productionSlotPort, productionSlotRepository) =
        buildProductionDiffChain(store, gameRng, scopeProvider)
    val service = CultivationService(
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
    )
    return MonthDiffHarness(store, service, gameRng, aiBeastAttackProcessor,
        productionSlotPort, productionSlotRepository)
}

/** 兼容既有 Triple 消费点（DiffMonthSettlementTest / DiffWorldLevelRefreshTest） */
internal fun buildMonthDiffService(
    store: FakeGameStateStore,
    rngStates: Map<Int, Long>
): Triple<CultivationService, GameRngManager, AISectBeastAttackProcessor> {
    val harness = buildMonthDiffHarness(store, rngStates)
    return Triple(harness.service, harness.gameRng, harness.aiBeastAttackProcessor)
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
    // 真实库存系统 + 自动购买（12 月场景对拍主体）——溢出转邮件
    // NoOp（diff 面不可见，与 C++ 草稿丢弃侧等价）
    val inventorySystem = InventorySystem(
        stateStore = store,
        inventoryConfig = inventoryConfig,
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
        // 真实战斗系统（任务完成 COMBAT_REQUIRED/COMBAT_RANDOM 场景
        // 消费——战斗组装+执行双端对拍；既有场景无任务不触达，零行为变化）
        battleSystem = com.xianxia.sect.core.engine.domain.battle.BattleSystem(gameRng),
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
            inventorySystem = inventorySystem,            merchantAndRecruitService = mockSmart(),
            spiritStoneWallet = wallet
        ),
        // 真实附庸服务（脱离流对拍主体——玩家宗门 + 至交附属
        // 场景下恰抽 1 次 SYSTEM 且必不脱离）
        vassalService = VassalService(
            stateStore = store,
            spiritStoneWallet = wallet,
            rngManager = gameRng
        ),
        // 真实弟子智能购买（购买流对拍主体——场景⑪ playerListedItems
        // 非空 + 仓库有货 + 弟子有灵石）
        disciplePurchaseService = DisciplePurchaseService(
            stateStore = store,
            rngManager = gameRng
        ),
        aiSectBeastAttackProcessor = aiBeastAttackProcessor,
        // 真实执法堂处理器（叛逃流对拍主体）——lifecycle 用 mock：
        // 逃脱路径的 11 槽清理在场景中恒等（叛逃候选无任何槽位引用）
        lawEnforcementProcessor = buildLawEnforcement(store, gameRng),
        rngManager = gameRng,
        secretRealmService = mockSmart(),
        // 真实秘境 AI 派遣处理器（纯数据变换零 RNG——秘境存在 +
        // 有存活 AI 弟子 → 逐月派遣队伍，幂等去重）
        secretRealmAIProcessor = SecretRealmAIProcessor(),
        deathHandler = mockSmart(),
        // 真实配置 provider（GameConfigNativeBridge
        // register 时 isLoaded=false 安全跳过）
        gameConfigProvider = configProvider
    )
}

/**
 * 月变编排器（步骤 3 换装真实 AISectBeastAttackProcessor；
 * 步骤 4e 换装真实 ExplorationTickSystem——世界关卡刷新/清理/
 * 妖兽移动对拍主体，WorldLevelManager/LevelGenerator 真实（EXPLORATION
 * 分区消费与 C++ 逐位对齐），巡视/攻击检测 mock（纯早退零效果）；
 * 现有场景 worldLevelLastRefreshMonth 预置当前月 → 不刷新零生成）
 */
internal fun buildMonthDiffExecutor(
    service: CultivationService,
    gameRng: GameRngManager,
    aiBeastAttackProcessor: AISectBeastAttackProcessor,
    store: FakeGameStateStore,
    scopeProvider: CoroutineScopeProvider = UnconfinedCoroutineScopeProvider()
): MonthSettlementExecutor {
    val explorationSystem = ExplorationTickSystem(
        explorationService = ExplorationService(
            stateStore = store,
            battleSystem = mockSmart(),
            inventorySystem = mockSmart(),
            cultivationService = mockSmart(),
            spiritStoneWallet = mockSmart(),
            rngManager = gameRng,
            subSystems = ExplorationSubSystems(
                worldLevelManager = WorldLevelManager(
                    rngManager = gameRng,
                    levelGenerator = LevelGenerator(gameRng)
                ),
                beastAttackDetector = mockSmart(),
                patrolBattleSystem = mockSmart(),
                lootCalculator = mockSmart(),
                encounterBattleService = mockSmart(),
                deathHandler = mockSmart()
            )
        )
    )
    return MonthSettlementExecutor(
        cultivationService = service,
        aiSectBeastAttackProcessor = aiBeastAttackProcessor,
        systemManager = SystemManager(
            setOf(
                // 真实 AlchemySystem/ForgeSystem（炼丹/锻造完成结算 +
                // 自动排班对拍主体——槽位预置场景真实触发；既有场景槽位/repo
                // 全空 → 完成结算与续炼启动纯早退零抽取）
                com.xianxia.sect.core.engine.system.building.AlchemySystem(
                    service, scopeProvider
                ),
                com.xianxia.sect.core.engine.system.building.ForgeSystem(
                    service, scopeProvider
                ),
                PartnerSystem(gameRng),
                explorationSystem,
                // 真实 ChildBirthSystem（月变步骤 4d 生育对拍主体）
                ChildBirthSystem(
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
    return advanceKotlinMonthSide(
        buildMonthDiffHarness(store, snapshot.gameData.rngStates), phases
    )
}

/**
 * Kotlin 组合管线（harness 版——生产场景先经 port.seed 预置 repo 槽位）。
 * 月结后以 repo 为真源写回镜像：完成结算的槽位重置走 Room 通道，
 * keepDisciple 分支镜像不写为 B5 既有口径；repo 对齐与生产管线
 * settleMonthNative 的窗口对齐互为同构（Kotlin 编排内 repo 写先于该对齐）。
 */
internal fun advanceKotlinMonthSide(
    harness: MonthDiffHarness,
    phases: Int = DIFF_MONTH_PHASES
): NativeGameState {
    val store = harness.store
    val service = harness.service
    val gameRng = harness.gameRng
    val aiBeastAttackProcessor = harness.aiBeastAttackProcessor
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
                // S4：repo 为真源写回镜像（生产结算槽位重置走 Room 通道后的
                // 窗口对齐——与生产管线 settleMonthNative 前置对齐互为逆操作）
                val repoSlots = harness.productionSlotPort.getAllSync()
                if (repoSlots.isNotEmpty()) {
                    gameData = gameData.copy(productionSlots = repoSlots)
                }
            }
        }
    }
    store.gameDataValue = store.gameDataValue.copy(
        rngStates = gameRng.exportStates().toMutableMap()
    )
    return NativeGameState(
        gameData = store.gameDataValue,
        // AI 弟子池经顶层字段承载（与 C++ 导出键对齐）
        aiSectDisciples = store.gameDataValue.aiSectDisciples,
        disciples = store.disciplesValue,
        // 库存集合参与对拍（FakeGameStateStore 嵌套事务下
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

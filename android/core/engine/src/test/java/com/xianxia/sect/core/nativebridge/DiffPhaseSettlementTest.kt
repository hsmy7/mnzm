package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.engine.service.AutoPillService
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationRateCalculator
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.engine.service.DiscipleBreakthroughHandler
import com.xianxia.sect.core.engine.service.EquipmentNurtureService
import com.xianxia.sect.core.engine.service.HpMpRecoveryService
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.domain.disciple.PillEffectApplier
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.RelativeGiftHandler
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.config.GameConfigProvider
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.engine.service.CultivationEventProcessor
import com.xianxia.sect.core.engine.service.CultivationSettlement
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
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
import com.xianxia.sect.core.engine.domain.disciple.calculateCultivationPerPhase
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getBreakthroughChance
import com.xianxia.sect.core.engine.domain.disciple.getFinalStats
import com.xianxia.sect.core.engine.domain.disciple.getStatsWithEquipment
import com.xianxia.sect.core.engine.domain.disciple.getTalentEffects

/**
 * DiffPhaseSettlementTest — 每旬弟子结算跨语言差分对拍（验收核心）。
 *
 * 守护目标：C++ `gamecore::system::runPhaseSettlement`（注册于 onPhaseSettle，
 * 经 nativeCoreAdvancePhases 触发）与 Kotlin [PhaseSettlementExecutor] +
 * [TimeSystem] 的六步结算语义**逐位一致**（含 RNG 抽取序列）。
 *
 * 流程：同一导入状态 → Kotlin 侧真实服务编排推进 N 旬 / C++ 侧
 * nativeCoreAdvancePhases(N) → 双侧导出 → JSON 结构对拍。
 *
 * 对拍方向：以 **C++ 导出的键集为权威覆盖面**（C++ 快照协议只含已迁移字段；
 * Kotlin 侧未迁移字段双端均无人写入，不在守卫范围）——C++ 持有的任何
 * 字段被改坏都会在此失败；数字统一 IEEE754 double 位比较；现实墙钟字段
 * timestamp 排除（Clock 注入边界）。
 *
 * 场景边界（未随本批下沉的跨系统钩子不触发，见 t2-1-report.md）：
 * - 自动仓库装备/学习开关全关（processAutoFromWarehouse 纯早退）；
 * - 弟子无任何亲属关系（亲属赠送零 SYSTEM RNG）；
 * - 丹药不含道德减益（无偷盗判定钩子）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffPhaseSettlementTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * 对拍推进旬数：3 旬恰好跨一个月界（(1,1,0)→(1,2,0)）。
     * 刻意避开 month%3==0 的任务自动刷新月（missionRefresh 不在 diff 面内）——
     * 月界选择约束见 t2-2-report.md 场景规避清单。
     */
    private companion object {
        const val PHASES = 3
        const val SEED = 424242L

        /** 自动突破丹场景种子（首抽经前置校验锁定在翻转区间内） */
        const val AUTO_PILL_SEED = 99L

        /** 元婴一层三灵根基础突破概率（表(6,3)） */
        const val BASE_CHANCE_REALM6_ROOTS3 = 0.12

        /** 突破丹概率加成（仓库破境丹 effects.breakthroughChance） */
        const val PILL_BONUS = 0.50
    }

    // ── 场景构建（双侧同源） ────────────────────────────────────────

    /**
     * 构建对拍场景：
     * - 弟子1 炼气1层 cult=50 单灵根 低血量 —— 恢复 + 累积
     * - 弟子2 筑基1层 cult=300 双灵根 藏经阁 + 功法 m1 + 武器 w1 —— 熟练度/孕养/多乘区
     * - 弟子3 炼气1层 cult=98 满 满血 —— 每旬突破候选（BREAKTHROUGH RNG 序列）
     *   储物袋带 cultivationAdd 丹药 ×2 —— 自动服药路径
     */
    private fun buildSnapshot(): NativeGameState {
        val manuals = listOf(
            ManualInstance(
                id = "m1", name = "青云心法", rarity = 2,
                stats = mapOf("hp" to 20, "cultivationSpeedPercent" to 10)
            )
        )
        val equipments = listOf(
            EquipmentInstance(
                id = "w1", name = "青锋剑", rarity = 2,
                slot = com.xianxia.sect.core.model.EquipmentSlot.WEAPON,
                physicalAttack = 10, hp = 15, nurtureLevel = 0,
                nurtureProgress = 55.0
            )
        )
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000
        ).apply {
            rngStates = initialRngStates(SEED)
            librarySlots = listOf(LibrarySlot(index = 0, discipleId = "2"))
        }
        return NativeGameState(
            gameData = gameData,
            disciples = listOf(
                Disciple(
                    id = "1", name = "青一", realm = 9, realmLayer = 1,
                    cultivation = 50.0, spiritRootType = "metal",
                    combat = CombatAttributes(currentHp = 30, currentMp = 20)
                ),
                Disciple(
                    id = "2", name = "青二", realm = 8, realmLayer = 1,
                    cultivation = 300.0, spiritRootType = "fire,water",
                    manualIds = listOf("m1"),
                    equipment = EquipmentSet(weaponId = "w1"),
                    combat = CombatAttributes(currentHp = -1, currentMp = -1)
                ),
                Disciple(
                    id = "3", name = "青三", realm = 9, realmLayer = 1,
                    cultivation = 490.0, spiritRootType = "metal",
                    combat = CombatAttributes(currentHp = -1, currentMp = -1),
                    equipment = EquipmentSet(
                        storageBagItems = listOf(cultivationPill())
                    )
                )
            ),
            manualInstances = manuals,
            equipmentInstances = equipments
        )
    }

    /** 直接修为丹（INSTANT_CULTIVATION 规则；无道德减益 → 不触发偷盗钩子） */
    private fun cultivationPill() = StorageBagItem(
        itemId = "pill-1", itemType = "pill", name = "聚气丹", rarity = 2,
        quantity = 2, obtainedYear = 1, obtainedMonth = 1,
        effect = ItemEffect(
            pillType = "cultivationAdd", cultivationAdd = 50, minRealm = 9
        )
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

    // ── Kotlin 基准侧 ──────────────────────────────────────────────

    /**
     * 执法处理器构造（偷盗钩子场景用真实实现，其余场景 mock）。
     * 必须与基准侧共用同一 [gameRng]（SYSTEM 抽取落同一分区真相）。
     */
    private fun buildLawProcessor(
        store: FakeGameStateStore,
        gameRng: GameRngManager,
        real: Boolean
    ): LawEnforcementProcessor =
        if (real) {
            com.xianxia.sect.core.engine.service.LawEnforcementProcessor(
                stateStore = store,
                rngManager = gameRng,
                discipleLifecycleProcessor =
                    mockSmart<com.xianxia.sect.core.engine.service.DiscipleLifecycleProcessor>(),
                lootCalculator = com.xianxia.sect.core.exploration.LootCalculator(gameRng)
            )
        } else {
            mockSmart()
        }

    /** 构造真实服务的 CultivationService（mock 仅为本路径不触达的依赖）；返回服务与其 RNG 管理器 */
    private fun buildService(
        store: FakeGameStateStore,
        rngStates: Map<Int, Long>,
        realLawEnforcement: Boolean = false
    ): Pair<CultivationService, GameRngManager> {
        // 晚绑定属性计算器（生产由 App 启动装配；纯 JUnit 需手动绑定——
        // 突破失败折算/长老悟性等路径经 disciple.maxHp/getBaseStats 消费它）
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
                d: Disciple, e: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(d, e)
            override fun getStatsWithEquipment(
                a: DiscipleAggregate, e: Map<String, EquipmentInstance>
            ) = DiscipleStatCalculator.getStatsWithEquipment(a, e)
            override fun getFinalStats(
                d: Disciple,
                e: Map<String, EquipmentInstance>,
                m: Map<String, ManualInstance>,
                p: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(d, e, m, p, bloodRefinementPct)
            override fun getFinalStats(
                a: DiscipleAggregate,
                e: Map<String, EquipmentInstance>,
                m: Map<String, ManualInstance>,
                p: Map<String, ManualProficiencyData>,
                bloodRefinementPct: BloodRefinementPctTotal?
            ) = DiscipleStatCalculator.getFinalStats(a, e, m, p, bloodRefinementPct)
            override fun calculateCultivationSpeed(
                d: Disciple,
                manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>,
                bb: Double, ab: Double, peb: Double, pmb: Double,
                csb: Double, pcb: Double, gcp: Double, mdb: Double
            ) = DiscipleStatCalculator.calculateCultivationPerPhase(
                d, manuals, mps, bb, peb, pmb, csb, pcb, gcp
            )
            override fun calculateCultivationSpeed(
                a: DiscipleAggregate,
                manuals: Map<String, ManualInstance>,
                mps: Map<String, ManualProficiencyData>,
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
        // RNG 与 C++ 同源：restore 到导入快照的分区状态（C++ importStateJson
        // 的等价步骤）；突破/亲属赠送/伴侣配对共用同一管理器（生产装配同构）
        val gameRng = GameRngManager().also { it.restoreStates(rngStates) }
        val lawProcessor = buildLawProcessor(store, gameRng, realLawEnforcement)
        val core = CultivationCore(
            hpMpRecoveryService = HpMpRecoveryService(),
            autoPillService = AutoPillService(
                DisciplePillManager(PillEffectApplier()),
                lawProcessor
            ),
            equipmentNurtureService = EquipmentNurtureService(),
            manualProficiencyService = ManualProficiencyService(),
            cultivationRateCalculator = CultivationRateCalculator(store)
        )
        val handler = DiscipleBreakthroughHandler(
            stateStore = store,
            cultivationCore = core,
            scopeProvider = mockSmart(),
            relativeGiftHandler = RelativeGiftHandler(gameRng),
            rngManager = gameRng,
            analyticsTracker = mockSmart()
        )
        // ── 月变路径真实依赖（跨月界时 Kotlin 基准侧需跑同构月变）──
        // 钱包/账本/事件总线为纯 ledger（无 Android 依赖，普通 JUnit 可真构造）
        val scopeProvider = UnconfinedCoroutineScopeProvider()
        val wallet = SpiritStoneWallet(
            store, SpiritStoneLedger(), EventBus(scopeProvider)
        )
        // 配置加载缺省兜底（assetReader 恒 null → GameConfigData 默认值，
        // 与 C++ kSpiritMineBaseOutputPerMiner 等编译期常量同源）
        val configProvider = GameConfigProvider(ConfigLoader({ null }))
        val settlement = CultivationSettlement(
            stateStore = store,
            scopeProvider = scopeProvider,
            spiritStoneWallet = wallet,
            lawEnforcementProcessor = mockSmart(),   // 惰性实例：S2 教化之道
            // 偷盗钩子场景规避后零触达（等价性论证见 t2-2-report.md §A）
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
        ) to gameRng
    }

    /**
     * 真实 CultivationEventProcessor + 定向惰性依赖：
     * processMonthlyEvents 为扩展函数（静态分发不可 mock）——必须走真实实现；
     * 十六子事件中未下沉十三件的依赖字段以惰性实例注入，其 NPE 由
     * safelyRunInState 捕获继续 ≡ 场景规避（逐条论证见 t2-2-report.md §A）。
     */
    private fun buildEventProcessor(
        store: FakeGameStateStore,
        core: CultivationCore,
        handler: DiscipleBreakthroughHandler,
        settlement: CultivationSettlement,
        gameRng: GameRngManager,
        scopeProvider: CoroutineScopeProvider
    ): CultivationEventProcessor {
        val aiProcessor = mockSmart<AISectBeastAttackProcessor>()
        val lawEnforcement = mockSmart<LawEnforcementProcessor>()
        return CultivationEventProcessor(
            stateStore = store,
            spiritStoneWallet = SpiritStoneWallet(
                store, SpiritStoneLedger(), EventBus(scopeProvider)
            ),
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
            equipmentManager = com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager(),
            manualManager = com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager(),
            autoBuyService = mockSmart(),
            vassalService = mockSmart(),
            disciplePurchaseService = mockSmart(),
            aiSectBeastAttackProcessor = aiProcessor,
            lawEnforcementProcessor = lawEnforcement,
            rngManager = gameRng,
            secretRealmService = mockSmart(),
            secretRealmAIProcessor = mockSmart(),
            deathHandler = mockSmart(),
            gameConfigProvider = GameConfigProvider(ConfigLoader({ null }))
        )
    }

    /** 月变编排器（SystemManager 仅装 PartnerSystem——其余六系统在场景输入下
     *  恒零输出，缺席 ≡ 恒零；Partner 为配对 RNG 断言核心必须真实） */
    private fun buildMonthExecutor(
        service: CultivationService,
        gameRng: GameRngManager
    ): MonthSettlementExecutor = MonthSettlementExecutor(
        cultivationService = service,
        aiSectBeastAttackProcessor = mockSmart(),
        systemManager = SystemManager(setOf(PartnerSystem(gameRng)))
    )

    /** Unconfined 协程域替身（async launch 在当前协程直接执行，无后台线程） */
    private class UnconfinedCoroutineScopeProvider : CoroutineScopeProvider {
        override val scope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        override val ioScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    }

    // ── 验收测试 ───────────────────────────────────────────────────

    @Test
    fun `phase settlement matches Kotlin bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val encoded = json.encodeToString(NativeGameState.serializer(), buildSnapshot())

        // Kotlin 基准侧：真实 TimeSystem + PhaseSettlementExecutor 逐旬推进
        val snapshot = json.decodeFromString(NativeGameState.serializer(), encoded)
        val store = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
            it.equipmentInstancesValue = snapshot.equipmentInstances
            it.manualInstancesValue = snapshot.manualInstances
        }
        val serviceAndRng = buildService(store, snapshot.gameData.rngStates)
        val service = serviceAndRng.first
        val gameRng = serviceAndRng.second
        val executor = PhaseSettlementExecutor(service)
        val monthExecutor = buildMonthExecutor(service, gameRng)
        val timeSystem = TimeSystem(store)
        store.update {
            repeat(PHASES) {
                // 组合管线同构 C++ SettlementEngine.advanceOnePhase：
                // 时间推进 → 旬结算钩子 → （跨界时）月变钩子
                val prevMonth = gameData.gameMonth
                timeSystem.onPhaseTick(this, phasesToSettle = 1)
                executor.execute(this)
                if (gameData.gameMonth != prevMonth) {
                    monthExecutor.execute(this)
                }
            }
        }
        // 生产语义对齐：存档流程经 GameRngManager.exportStates 回写 rngStates
        // （C++ 侧由 exportStateJson 的 syncRngStates 等价完成）
        store.gameDataValue = store.gameDataValue.copy(
            rngStates = gameRng.exportStates().toMutableMap()
        )
        val expected = NativeGameState(
            gameData = store.gameDataValue,
            disciples = store.disciplesValue,
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

        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        // C++ 被测侧：逐旬推进（每旬经 export 校验 RNG 分区同步，最终全量断言）
        var actual: NativeGameState? = null
        repeat(PHASES) {
            DiffRngBridge.nativeCoreAdvancePhases(1)
            actual = json.decodeFromString(
                NativeGameState.serializer(),
                DiffRngBridge.nativeCoreExportState().decodeToString()
            )
        }

        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual!!))
    }

    /**
     * 自动突破丹场景（审查 Critical 补救）：修为已满 + 仓库含目标境界突破丹 +
     * breakthroughAutoPillRootCounts 命中 → 概率加成参与成败判定。
     *
     * 区分度构造：元婴一层三灵根基础概率 = 表(6,3) = 0.12；突破丹加成 +[PILL_BONUS] →
     * 合成 [BASE_CHANCE_REALM6_ROOTS3 + PILL_BONUS]。固定种子首抽落在
     * [0.12, 0.62) 时"无丹必败、有丹必成"——若 C++ 丢弃 pillBonus 则该旬成败
     * 必与 Kotlin 分叉（RNG 终态亦分叉）。
     */
    @Test
    fun `auto breakthrough pill bonus participates in chance bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val snapshot = buildAutoPillSnapshot(AUTO_PILL_SEED)
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        // Kotlin 基准侧（真实 handler：attemptAutoPill → getBreakthroughChance(pillBonus)）
        val store = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
            it.pillsValue = snapshot.pills
        }
        val serviceAndRng = buildService(store, snapshot.gameData.rngStates)
        val executor = PhaseSettlementExecutor(serviceAndRng.first)
        val monthExecutor = buildMonthExecutor(serviceAndRng.first, serviceAndRng.second)
        val timeSystem = TimeSystem(store)
        store.update {
            repeat(PHASES) {
                // 组合管线：时间推进 → 旬结算 → （跨界时）月变（同 C++ 钩子序）
                val prevMonth = gameData.gameMonth
                timeSystem.onPhaseTick(this, phasesToSettle = 1)
                executor.execute(this)
                if (gameData.gameMonth != prevMonth) {
                    monthExecutor.execute(this)
                }
            }
        }
        store.gameDataValue = store.gameDataValue.copy(
            rngStates = serviceAndRng.second.exportStates().toMutableMap()
        )
        val expected = NativeGameState(
            gameData = store.gameDataValue,
            disciples = store.disciplesValue,
            pills = store.pillsValue
        )

        // C++ 被测侧
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(PHASES)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )

        // 成败分支一致性：第一旬即尝试（后续旬修为从零累积不再候选）
        val kDisciple = expected.disciples.first()
        val cDisciple = actual.disciples.first()
        assertEquals(
            "双端成败分支不一致",
            kDisciple.realmLayer == 2, cDisciple.realmLayer == 2
        )
        assertTrue(
            "场景未观察到突破尝试（layer 应已变化或 RNG 已消耗）",
            kDisciple.realmLayer == 2 || kDisciple.combat.breakthroughFailCount > 0
        )
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    /** 构建自动突破丹对拍场景（含种子区分度前置校验） */
    private fun buildAutoPillSnapshot(seed: Long): NativeGameState {
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000
        ).apply {
            rngStates = initialRngStates(seed)
            // 自动突破丹资格：三灵根命中 rootCounts（focused 关闭免 statusData 依赖）
            breakthroughAutoPillRootCounts = setOf(3)
        }
        assertSeedFlipsOutcome(seed)

        val warehousePill = com.xianxia.sect.core.model.Pill(
            id = "bp-1", name = "破境丹", rarity = 4, pillType = "breakthrough",
            effects = com.xianxia.sect.core.model.PillEffect(
                breakthroughChance = PILL_BONUS, targetRealm = 6
            )
        )
        return NativeGameState(
            gameData = gameData,
            disciples = listOf(autoPillDisciple()),
            pills = listOf(warehousePill)
        )
    }

    /**
     * 场景有效性前置：探测本种子 BREAKTHROUGH 分区下一次抽取值，
     * 必须落在"无丹败 / 有丹成"的翻转区间 [基础概率, 基础概率+丹加成)。
     */
    private fun assertSeedFlipsOutcome(seed: Long) {
        val probe = DeterministicRng.fromSeed(seed + RngPartition.BREAKTHROUGH.id)
        repeat(3) { probe.nextInt() }   // 复刻 initialRngStates 的预抽序列
        val firstDraw = probe.nextDouble()
        val upperBound = BASE_CHANCE_REALM6_ROOTS3 + PILL_BONUS
        assertTrue(
            "种子 $seed 首抽 $firstDraw 未落入 " +
                "[${BASE_CHANCE_REALM6_ROOTS3}, $upperBound)，场景失去区分度",
            firstDraw >= BASE_CHANCE_REALM6_ROOTS3 && firstDraw < upperBound
        )
    }

    /** 元婴一层三灵根满修为满血弟子（自动突破丹资格命中 rootCounts=[3]） */
    private fun autoPillDisciple() = Disciple(
        id = "7", name = "元七", realm = 6, realmLayer = 1,
        cultivation = 29250.0,  // maxCult(6,1)
        spiritRootType = "fire,water,wind",
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    // ── 自动装备/亲属赠送/偷盗钩子场景 ─────────────────────────
    // 覆盖上文场景边界规避的三条路径：自动装备开启 / 突破后亲属赠送
    // （SYSTEM RNG）/ 丹药写回偷盗判定钩子（SYSTEM RNG）。

    /** 探测指定分区在 3 次预热抽取后的首个 nextDouble 值 */
    private fun probeFirstDouble(seed: Long, partition: RngPartition): Double {
        val probe = DeterministicRng.fromSeed(seed + partition.id)
        repeat(3) { probe.nextInt() }
        return probe.nextDouble()
    }

    private fun findSeed(
        partition: RngPartition,
        predicate: (Double) -> Boolean
    ): Long {
        for (s in 1L..100_000L) {
            if (predicate(probeFirstDouble(s, partition))) return s
        }
        error("未找到满足条件的种子（分区 ${partition.id}）")
    }

    private fun herb(itemId: String, rarity: Int, quantity: Int) = StorageBagItem(
        itemId = itemId, itemType = "herb", name = "灵草$itemId",
        rarity = rarity, quantity = quantity, obtainedYear = 1, obtainedMonth = 1
    )

    /** 双端同构推进：Kotlin 基准（TimeSystem + execute + 月变）vs C++ advancePhases */
    private fun runDiffPhases(
        snapshot: NativeGameState,
        realLawEnforcement: Boolean = false,
        phases: Int = PHASES
    ): Pair<NativeGameState, NativeGameState> {
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)
        val store = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
            it.equipmentInstancesValue = snapshot.equipmentInstances
            it.manualInstancesValue = snapshot.manualInstances
            it.equipmentStacksValue = snapshot.equipmentStacks
            it.manualStacksValue = snapshot.manualStacks
            it.pillsValue = snapshot.pills
        }
        val serviceAndRng = buildService(store, snapshot.gameData.rngStates, realLawEnforcement)
        val executor = PhaseSettlementExecutor(serviceAndRng.first)
        val monthExecutor = buildMonthExecutor(serviceAndRng.first, serviceAndRng.second)
        val timeSystem = TimeSystem(store)
        store.update {
            repeat(phases) {
                val prevMonth = gameData.gameMonth
                timeSystem.onPhaseTick(this, phasesToSettle = 1)
                executor.execute(this)
                if (gameData.gameMonth != prevMonth) {
                    monthExecutor.execute(this)
                }
            }
        }
        store.gameDataValue = store.gameDataValue.copy(
            rngStates = serviceAndRng.second.exportStates().toMutableMap()
        )
        val expected = NativeGameState(
            gameData = store.gameDataValue,
            disciples = store.disciplesValue,
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
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            encoded.encodeToByteArray()))
        DiffRngBridge.nativeCoreAdvancePhases(phases)
        val actual = json.decodeFromString(
            NativeGameState.serializer(),
            DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        return expected to actual
    }

    /**
     * 自动装备（S3）：袋内 equipment_instance 直接装配（完整实例保真，
     * 不走模板重建——桌面环境无模板 DB 依赖）。
     */
    @Test
    fun `auto equip from bag instance matches bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val instance = EquipmentInstance(
            id = "i1", name = "青云剑", rarity = 4,
            slot = com.xianxia.sect.core.model.EquipmentSlot.WEAPON,
            physicalAttack = 100, minRealm = 9,
            nurtureLevel = 2, nurtureProgress = 30.0,
            ownerId = "1", isEquipped = false
        )
        val bagItem = StorageBagItem(
            itemId = "i1", itemType = "equipment_instance", name = "青云剑",
            rarity = 4, quantity = 1, obtainedYear = 1, obtainedMonth = 1,
            equipmentInstance = instance
        )
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000
        ).apply {
            rngStates = initialRngStates(SEED)
            autoEquipFromWarehouseRootCounts = setOf(1)   // 单灵根命中
        }
        val snapshot = NativeGameState(
            gameData = gameData,
            disciples = listOf(
                Disciple(
                    id = "1", name = "青一", realm = 9, realmLayer = 1,
                    cultivation = 50.0, spiritRootType = "metal",
                    combat = CombatAttributes(currentHp = -1, currentMp = -1),
                    equipment = EquipmentSet(storageBagItems = listOf(bagItem))
                )
            )
        )
        val (expected, actual) = runDiffPhases(snapshot)
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    /**
     * 亲属赠送（S1）：突破成功（层变即可）触发道侣赠送（SYSTEM RNG，
     * 概率 0.45）。种子双探测：BREAKTHROUGH 首抽 < 0.90（突破成功）+
     * SYSTEM 首抽 < 0.45（赠送触发）。
     */
    @Test
    fun `relative gifts after breakthrough matches bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        // 种子双探测：BREAKTHROUGH 首抽 < 0.90（炼气一层突破成功）+
        // SYSTEM 首抽 < 0.45（道侣赠送概率 0.45）
        val jointSeed = generateSequence(1L) { it + 1 }
            .first { s ->
                probeFirstDouble(s, RngPartition.BREAKTHROUGH) < 0.90 &&
                    probeFirstDouble(s, RngPartition.SYSTEM) < 0.45
            }

        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000
        ).apply { rngStates = initialRngStates(jointSeed) }
        val snapshot = NativeGameState(
            gameData = gameData,
            disciples = listOf(
                Disciple(   // 突破候选：炼气一层修为满、满血
                    id = "1", name = "青一", realm = 9, realmLayer = 1,
                    cultivation = 490.0, spiritRootType = "metal",
                    combat = CombatAttributes(currentHp = -1, currentMp = -1),
                    social = com.xianxia.sect.core.model.SocialData(partnerId = "2")
                ),
                Disciple(   // 道侣：袋内 ≥2 条目（Kotlin MIN_BAG_ITEMS_TO_KEEP=1 按条目数守卫）
                    id = "2", name = "青二", realm = 9, realmLayer = 1,
                    cultivation = 0.0, spiritRootType = "metal",
                    combat = CombatAttributes(currentHp = -1, currentMp = -1),
                    equipment = EquipmentSet(storageBagItems = listOf(
                        herb("h-1", 4, 2), herb("h-2", 1, 1)
                    ))
                )
            )
        )
        val (expected, actual) = runDiffPhases(snapshot)
        // 场景有效性前置：突破确实发生（层数 1→2）
        assertEquals(2, expected.disciples.first().realmLayer)
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    /**
     * 偷盗判定钩子（S2）：道德减益丹服用后道德 < 30 → 即时偷盗判定
     * （SYSTEM RNG 全链：尝试→捕获→金额→偷后叛逃）。种子探测 SYSTEM 首抽
     * < 0.30（道德归零后偷盗概率 30×0.01）；忠诚 35 → 平均忠诚 < 50 门控
     * 通过、偷后叛逃概率 clamp 0（不触发清理路径）。
     */
    @Test
    fun `pill morality debuff triggers theft chain bit-for-bit`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()

        val seed = findSeed(RngPartition.SYSTEM) { it < 0.30 }
        val gameData = GameData(
            gameYear = 1, gameMonth = 1, gamePhase = 0,
            spiritStones = 10000
        ).apply { rngStates = initialRngStates(seed) }
        val debuffPill = StorageBagItem(
            itemId = "pill-1", itemType = "pill", name = "迷心丹",
            rarity = 2, quantity = 1, obtainedYear = 1, obtainedMonth = 1,
            effect = ItemEffect(
                pillType = "intel", intelligenceAdd = 5,
                moralityAdd = -100, minRealm = 9
            )
        )
        val snapshot = NativeGameState(
            gameData = gameData,
            disciples = listOf(
                Disciple(
                    id = "1", name = "青一", realm = 9, realmLayer = 1,
                    cultivation = 10.0, spiritRootType = "metal",
                    combat = CombatAttributes(currentHp = -1, currentMp = -1),
                    skills = com.xianxia.sect.core.model.SkillStats(loyalty = 35),
                    equipment = EquipmentSet(storageBagItems = listOf(debuffPill))
                )
            )
        )
        val (expected, actual) = runDiffPhases(snapshot, realLawEnforcement = true, phases = 2)
        // 场景有效性前置：偷盗判定确已发生（标记先于抽取，未遂同计数）。
        // 注：不跨月界（2 旬）——月度重置（theftJudgementsThisMonth 归零）
        // 在 Kotlin 基准侧位于被 mock 的 processTheftIfNeeded（口径差
        // 登记族），月度重置语义由 C++ month_settlement_test 黄金用例守护。
        assertEquals(1, expected.gameData.theftJudgementsThisMonth)
        assertCppSurfaceMatches(json.encodeToJsonElement(expected),
                                json.encodeToJsonElement(actual))
    }

    // ── JSON 结构对拍（C++ 导出键集为权威覆盖面） ────────────────────

    /** 递归对拍入口：actual=C++ 导出（权威面），expected=Kotlin 导出 */
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

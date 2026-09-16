package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.ConfigLoader
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
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
import com.xianxia.sect.core.engine.service.DiscipleLifecycleProcessor
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.service.EquipmentNurtureService
import com.xianxia.sect.core.engine.service.HpMpRecoveryService
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.service.ManualProficiencyService
import com.xianxia.sect.core.engine.service.MerchantAndRecruitService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.MonthSettlementResidualExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.service.PolicyCostResult
import com.xianxia.sect.core.engine.service.RelativeGiftHandler
import com.xianxia.sect.core.engine.service.YearSettlementExecutor
import com.xianxia.sect.core.engine.service.YearSettlementResidualExecutor
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.engine.system.PartnerSystem
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.advancePhaseBaseline
import com.xianxia.sect.core.engine.parseMonthSettlementEnvelope
import com.xianxia.sect.core.engine.parseYearSettlementEnvelope
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatsProvider
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.nativebridge.NativeEngineFlag as NativeEngineFlagX
import com.xianxia.sect.core.state.ReverseChannelPolicy
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.NativeRngChannel
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.SectTerrainBridge
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
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
 * DiffAuthoritativeTickTest — AUTHORITATIVE 过渡期 tick 全管线跨语言对拍
 * （验收核心）。
 *
 * 守护目标：同一初始状态推进 100 旬（含多次月界 + 一次年界），
 * "C++ 完整结算（含月/年边界结算 + 自动装备/丹药/突破下沉）+ 委托式 RNG +
 * 增量镜像"管线终态 == 纯 Kotlin 全量引擎（对拍基准）终态，逐字段逐位一致。
 *
 * 管线对应（生产 GameEngineCoreAuthoritativeOps.processAuthoritativeTick）：
 * nativeCoreSettlePhase（每旬完整七步）→ applyDirty → 边界 C++ 月/年结算
 * （nativeCoreSettleYear/Month + Kotlin 残留执行器——W4-D/D3 起 harness 与
 * 生产同口径，不再把 Kotlin 月/年完整编排纳入 AUTHORITATIVE 侧；该编排仅存
 * 于对拍基准侧 B = 生产 flag-OFF 回退臂语义）+ 回导。
 * 本测试经 DiffRngBridge 桌面通道驱动同一协议。
 *
 * 场景：年 1 月 10 起（3 次月变 + 跨年年变）、年俸配置生效、年报计数非零、
 * 政策全开但灵石充足（政策扣除路径活跃）、mapSeed 非零 + boot 地形回填
 * （生产同款——terrainTiles/mapGenVersion 参与全状态对拍）。RNG：序列统一性
 * 由 NativeBackedRngTest + DiffRngTest 守护。
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

        /** 手动招募对拍场景的招募候选 id（buildRecruitSnapshot） */
        const val RECRUIT_ID = "r1"

        /** 场景 mapSeed（非零 = 真实存档前置——boot 地形回填/C++ AI 流播种同款输入） */
        const val MAP_SEED = 987_654_321
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
        // 摘除本类注入的 AI 随机源（AISectDiscipleManager 为进程级 object，
        // 残留引用会让后续测试类解析到已废弃的 manager）
        AISectDiscipleManager.resetManagerForTest()
        // 恢复未初始化态——防污染其他条件初始化 ManualDatabase 的测试类
        ManualDatabase.resetForTest()
    }

    /**
     * 功法注册表注入（对齐 DiffYearSettlementTest 同款装配）——
     * 年变 T2 #12 商人收购刷新（C++ 与 Kotlin 基准臂同源执行）按
     * EquipmentDatabase/ManualDatabase/ItemDatabase/HerbDatabase 构建收购池；
     * ManualDatabase 需显式初始化（其余注册表为 codegen 静态态），样本与
     * C++ 注册表的对位由 ManualRegistryGuardTest 守护。
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
        // AI 流播种与 C++ importStateInternal 的 mapSeed 播种同源（生产读档
        // 同款：initForSlot(mapSeed)）
        AISectDiscipleManager.initForSlot(MAP_SEED.toLong())
    }

    // ── 场景构建 ────────────────────────────────────────────────────

    private fun buildSnapshot(): NativeGameState {
        val gameData = backfillTerrainOnBoot(
            GameData(
                gameYear = 1, gameMonth = 10, gamePhase = 0,
                spiritStones = INITIAL_STONES
            ).apply {
                mapSeed = MAP_SEED
                rngStates = initialRngStates()
                // 招募刷新差值门置满（对齐 DiffYearSettlementTest 规避清单同款）：
                // 本场景 worldMapSects 为空（无玩家宗门）⇒ 刷新落入"空世界兜底
                // 分支"（nextInt(7) 兜底 + 无容量约束的自动招募）——生产 boot 后
                // worldMapSects 恒非空，该分支不可达；空世界下双臂（C++ vs Kotlin
                // 回退臂）自动招募/净化输出差异已在 D3 对齐时实测（4 vs 1），登记
                // 为空世界观测、不在本 harness 展开。年变招募刷新的跨语言对拍由
                // 读档自愈 native 臂（RECRUIT_REFRESH_TX）+ 后续带宗门场景承担
                lastRecruitYear = 4
                merchantLastRefreshChanceGrantYear = 1
                // 年报可观察输入
                annualTotalIncome = 1200L
                annualAlchemyCount = 5
                // 年俸配置（realm9）
                yearlySalary = mapOf(9 to SALARY_REALM9.toInt())
                yearlySalaryEnabled = mapOf(9 to true)
            }
        )
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

    /** 可招募候选（标准：16 岁单灵根炼气一层，资质缺省 50 触发入宗散列补算） */
    private fun recruitDisciple(id: String): Disciple = Disciple(
        id = id, name = "候选招募", age = 16, realm = 9, realmLayer = 1,
        cultivation = 1.0, spiritRootType = "metal",
        combat = CombatAttributes(currentHp = -1, currentMp = -1)
    )

    /** 手动招募对拍初始状态（3 宗门弟子 + 1 招募候选；时间与主场景同相位） */
    private fun buildRecruitSnapshot(): NativeGameState {
        val gameData = backfillTerrainOnBoot(
            GameData(
                gameYear = 1, gameMonth = 10, gamePhase = 0,
                spiritStones = INITIAL_STONES
            ).apply {
                mapSeed = MAP_SEED
                rngStates = initialRngStates()
                lastRecruitYear = 1
                recruitList = listOf(recruitDisciple(RECRUIT_ID))
            }
        )
        return NativeGameState(
            gameData = gameData,
            disciples = listOf(
                settlerDisciple("31", "甲"),
                settlerDisciple("32", "乙"),
                settlerDisciple("33", "丙")
            )
        )
    }

    /**
     * 地图冻结（WS-5b）boot 回填——生产同款（GameEngineSaveOps.
     * ensureSectTerrainBackfilled 的场景面等价）：无段 + 种子非零 ⇒ 按种子生成
     * flat 瓦片段 + 落 GameData 权威数据 + 戳生成器版本（幂等；有段恒优先）。
     * 生成真源 = SectTerrainBridge（native 优先 + Kotlin 降级，双实现位级一致
     * ——桌面 JVM 走 Kotlin 生成器，DiffSectTerrainTest 全数组逐位对拍已证）。
     * 写时机 = 导入 C++ 之前（生产同序：boot 回填先于引擎 AUTHORITATIVE 初始化）
     * ⇒ C++ importStateInternal 归一化族按"有段恒优先"采用同段，terrainTiles/
     * mapGenVersion 由此进入全状态对拍面（两侧键存在性 + 内容逐位对拍生效）。
     */
    private fun backfillTerrainOnBoot(gd: GameData): GameData {
        if (gd.terrainTiles.isNotEmpty() || gd.mapSeed == 0) return gd
        val tiles = SectTerrainBridge.generateFlatTileData(
            worldWidthCells = GameConfig.SectMap.WORLD_WIDTH_CELLS,
            worldHeightCells = GameConfig.SectMap.WORLD_HEIGHT_CELLS,
            worldSeed = gd.mapSeed,
            borderTreeRing = GameConfig.SectMap.BORDER_TREE_RING
        )
        return gd.copy(
            terrainTiles = tiles.toList(),
            mapGenVersion = GameConfig.SectMap.MAP_GEN_VERSION
        )
    }

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
        // AI 弟子域随机源归一（阶段 1②）：把夹具的 manager 交给
        // AISectDiscipleManager（R5：禁止自建随机源）——AI 弟子生成/装备补全
        // 必须与本夹具的 gameRng 同源，否则 Kotlin 臂与 C++ 臂的 AI 流分叉。
        // **顺序关键**：initForSlot 必须在 restoreStates **之后**——否则快照里的
        // AI_SECT 键会把播种抹掉（实测：分区停在快照值而非 `0 + 6×31337`）
        AISectDiscipleManager.initialize(gameRng)
        AISectDiscipleManager.initForSlot(MAP_SEED.toLong())
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
        // 死亡链下沉（对齐 DiffYearSettlementTest 同款装配）：换装真实
        // DiscipleLifecycleProcessor——C++ runYearSettlement 已执行年变死亡链
        // （老化 age+1/死亡处理），Kotlin 基准臂必须真实老化（mock 零行为 →
        // age 失配，D3 harness 对齐生产时实测暴露：第 9 旬 disciples[0].age
        // 16 vs 17）。场景弟子 age 低不死亡 → 槽位/哀悼/DAO 平台效应零触发
        //（discipleSlotCleanup/productionCoordinator/inventorySystem/deathHandler
        // mock 无害）；discipleStatusService mock（syncAllDiscipleStatuses 为
        // 派生态同步，本场景无状态迁移面）
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
        val eventProcessor = CultivationEventProcessor(
            stateStore = store, spiritStoneWallet = wallet,
            inventorySystem = mockSmart(), inventoryConfig = mockSmart(),
            scopeProvider = scopeProvider ,
            discipleService = mockSmart(),
            cultivationCore = core, breakthroughHandler = handler,
            cultivationSettlement = settlement, battleSystem = mockSmart(),
            // 招募：真实服务（年变 T1 #5 刷新差值判据 3 年到期 ⇒ 真实刷新 +
            // lastRecruitYear 落账——C++ runYearSettlement 同序执行，mock 零行为
            // → lastRecruitYear 失配，D3 对齐时实测暴露：第 84 旬 1 vs 4）
            recruitService = com.xianxia.sect.core.engine.service.RecruitService(
                store, com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory(), gameRng
            ),
            // 商人收购：真实商人服务（年变 T1 商人刷新机会 + T2 #12 收购刷新
            // 由 flushYearlyOpsQueue 触发——C++ runYearSettlement 同序执行，
            // mock 零行为 → merchantAcquisitionItems 失配，D3 对齐时实测暴露）
            merchantAndRecruitService = MerchantAndRecruitService(store, gameRng),
            // AI 宗门处理器：Provider 必须返回 mock 实例（mock Provider 的
            // get() 恒 null → 年变 T2 #4 sectDisciplesAging NPE 中断队列 drain
            // ⇒ #12 收购刷新永不执行；mock 实例 = 场景空 AI 池下的零效果等价）
            caveExplorationProcessor = javax.inject.Provider {
                mockSmart<com.xianxia.sect.core.engine.service.CaveExplorationProcessor>()
            },
            discipleLifecycleProcessor = lifecycle,
            diplomacyEventProcessor = mockSmart(), diplomacyService = mockSmart(),
            equipmentManager = mockSmart(), manualManager = mockSmart(),
            autoBuyService = mockSmart(), vassalService = mockSmart(),
            disciplePurchaseService = mockSmart(),
            aiSectBeastAttackProcessor = mockSmart<AISectBeastAttackProcessor>(),
            lawEnforcementProcessor = mockSmart<LawEnforcementProcessor>(),
            rngManager = gameRng, secretRealmService = mockSmart(),
            secretRealmAIProcessor = mockSmart(), deathHandler = mockSmart(),
            gameConfigProvider = configProvider
        )
        val service = CultivationService(
            stateStore = store, cultivationCore = core, breakthroughHandler = handler,
            cultivationSettlement = settlement, eventProcessor = eventProcessor,
            productionProcessor = mockSmart(), recruitService = mockSmart(),
            merchantAndRecruitService = mockSmart(), caveExplorationProcessor = mockSmart(),
            sharedState = CultivationSharedState()
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
                monthExecutor,
                MonthSettlementResidualExecutor(eventProcessor),
                YearSettlementResidualExecutor(eventProcessor),
                service
            )
        )
    }

    private class HarnessExecutors(
        val phase: PhaseSettlementExecutor,
        val year: YearSettlementExecutor,
        val month: MonthSettlementExecutor,
        val monthResidual: MonthSettlementResidualExecutor,
        val yearResidual: YearSettlementResidualExecutor,
        val service: CultivationService
    )

    // ── 验收测试 ────────────────────────────────────────────────────

    @Test
    @Suppress("LongMethod")  // 逐旬互锁全管线：单函数承载对拍主流程（与月变/年变对拍同构）
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
                // Side B 一旬（flag-OFF 基准臂语义；w3-13 起捕获侧检测
                // AUTHORITATIVE 门控——基准臂写入即真相，不触发关闭域检测计数）
                var yearChangedB = false
                var monthChangedB = false
                NativeEngineFlagX.withMode(NativeEngineFlagX.Mode.OFF) {
                    storeB.update {
                        val prevYear = gameData.gameYear
                        val prevMonth = gameData.gameMonth
                        advancePhaseBaseline(1)
                        exB.phase.execute(this)
                        yearChangedB = gameData.gameYear != prevYear
                        monthChangedB = gameData.gameMonth != prevMonth
                    }
                    runBoundary(exB, storeB, yearChangedB, monthChangedB)
                }
                // Side A 一旬（AUTHORITATIVE 管线：每旬完整七步
                // 在 nativeCoreSettlePhase 内执行，原 Kotlin executeResidual 删除）
                NativeEngineFlagX.withMode(NativeEngineFlagX.Mode.AUTHORITATIVE) {
                    val flags = DiffRngBridge.nativeCoreSettlePhase()
                    val dirty = DiffRngBridge.nativeCoreExportDirty().decodeToString()
                    val applyResult = syncA.applyDirty(dirty)
                    assertEquals("镜像失败", false, applyResult == null)
                    // ★ 镜像写入经 updateMirror 不参与反向捕获——
                    //   玩家操作捕获不会被镜像清空，保留至 ⑤ 与边界变更一并回导
                    if (flags != 0) {
                        runNativeBoundary(storeA, syncA, exA, flags)
                    }
                    // ⑤ 反向增量回导（取代每旬全量 importToNative。w3-13 弟子通道
                    // 关闭 + 残留执行器转非捕获事务后，AUTHORITATIVE 稳态窗口恒空
                    // = 零发送；生产语义同）
                    assertTrue("反向增量回导失败", syncA.applyDirtyToNative())
                }
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

    /**
     * w3-13 阶段 A 观察窗（先禁用后删除，ADR reverse-channel-elimination §4 安全下线）：
     * 反向通道**停发**状态下重跑同场景 100 旬 AUTHORITATIVE 全管线——C++ 结算、
     * 前向镜像与 Kotlin 残留执行器照常，每旬步骤⑤照常消费捕获窗口，但信封
     * **不发送** C++（版本号/锚点/变化检测缓存不推进）。
     *
     * 闸门（任一命中 ⇒ 按 ADR 重置流程、回滚对应域并回到下沉批，**不得删除通道**）：
     * ① 零信封发送（传输体积 = 0 可观测）；② 关闭域写入检测零命中（禁用不得
     * 掩盖漏域写者）；③ 终态全量结构对拍仍逐位一致 + 时间线/年俸/年报不变量保持
     * （结算周期与镜像链路在停发态无退化——窗口消费、锚点停推、缓存停更路径全被踩过）。
     *
     * 口径说明：本用例证明的是"观察窗仪器 + AUTHORITATIVE 结算周期在停发态无退化"；
     * 生产 AUTHORITATIVE 下是否残留依赖通道的稳态写者，以
     * `ReverseChannelPolicyGuardTest` 审计红线与 ui-read-surface §4.4 滚动表为准
     * （删通道的前置 = 全部传输单元关闭，见 w3 README §4）。
     */
    @Test
    @Suppress("LongMethod")  // 观察窗对拍主流程：与上方 100 旬用例同构（差异仅停发与三闸门断言）
    fun `authoritative pipeline runs clean with reverse transport disabled`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInitMode(true)

        val snapshot = buildSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        // ── Side B：纯 Kotlin 全量引擎（对拍基准，与主用例同款装配） ──
        val storeB = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        val (_, rngB, exB) = buildHarness(storeB, snapshot.gameData.rngStates, delegating = false)

        // ── Side A：AUTHORITATIVE 管线 + 反向通道停发（观察窗） ──
        assertTrue("导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        val storeA = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        var sentEnvelopes = 0
        val syncA = StateSyncService(storeA) { sentEnvelopes++; true }
        val (_, _, exA) = buildHarness(storeA, snapshot.gameData.rngStates, delegating = true)
        val closedWriteBaseline = ReverseChannelPolicy.closedWriteCountSnapshot()
        ReverseChannelPolicy.setReverseTransportEnabled(false)
        try {
            runTest {
                repeat(TICKS) { tick ->
                    // Side B 一旬（flag-OFF 基准臂语义——同主用例，检测门控不计数）
                    var yearChangedB = false
                    var monthChangedB = false
                    NativeEngineFlagX.withMode(NativeEngineFlagX.Mode.OFF) {
                        storeB.update {
                            val prevYear = gameData.gameYear
                            val prevMonth = gameData.gameMonth
                            advancePhaseBaseline(1)
                            exB.phase.execute(this)
                            yearChangedB = gameData.gameYear != prevYear
                            monthChangedB = gameData.gameMonth != prevMonth
                        }
                        runBoundary(exB, storeB, yearChangedB, monthChangedB)
                    }
                    // Side A 一旬（结算 + 前向镜像 + 边界编排同款；步骤⑤照常调用
                    // applyDirtyToNative——观察窗语义 = 消费窗口 + 构建检测 + 不发送）
                    NativeEngineFlagX.withMode(NativeEngineFlagX.Mode.AUTHORITATIVE) {
                        val flags = DiffRngBridge.nativeCoreSettlePhase()
                        val dirty = DiffRngBridge.nativeCoreExportDirty().decodeToString()
                        val applyResult = syncA.applyDirty(dirty)
                        assertEquals("镜像失败", false, applyResult == null)
                        if (flags != 0) {
                            runNativeBoundary(storeA, syncA, exA, flags)
                        }
                        assertTrue(
                            "观察窗下回导调用必须照常成功（消费窗口不发送，不得触发全量降级）",
                            syncA.applyDirtyToNative()
                        )
                    }
                    // 逐旬对拍（每 5 旬一次全量结构，与主用例同款）
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
                            throw AssertionError("观察窗第 $tick 旬全量结构分歧: ${e.message}", e)
                        }
                    }
                }
            }
            // 终态对拍 + 时间线/经济不变量（与主用例同款）
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
            val gdA = storeA.gameDataValue
            assertEquals(4, gdA.gameYear)
            assertEquals(7, gdA.gameMonth)
            assertEquals(
                "年俸扣减总额不符",
                INITIAL_STONES - SALARY_REALM9 * DISCIPLE_COUNT * 3,
                gdA.spiritStones
            )
            assertEquals("年报应恰 3 份", 3, gdA.yearlyReports.size)
            // 闸门①：零信封发送（传输体积 = 0 可观测）
            assertEquals("观察窗不得发送任何信封", 0, sentEnvelopes)
            // 闸门②：关闭域写入检测零命中（禁用不得掩盖漏域写者）
            assertEquals(
                "关闭域写入检测零命中 records=" +
                    ReverseChannelPolicy.closedWriteRecordsSnapshot().take(5),
                closedWriteBaseline, ReverseChannelPolicy.closedWriteCountSnapshot()
            )
        } finally {
            ReverseChannelPolicy.setReverseTransportEnabled(true)
        }
    }

    /**
     * 手动招募下沉对拍：C++ nativeCoreManualRecruitFromList（AUTHORITATIVE
     * 单真相源）执行一次手动招募 → 前向增量镜像 → 再推进一旬（含边界编排 + 反向回导）
     * 后，C++ 导出真相源与 Kotlin 基准（DiscipleFacadeImpl 同语义）逐字段一致。
     *
     * 守卫目标：手动招募与自动招募同侧（C++ 权威），Kotlin 侧不再修改镜像——
     * 防"C++ 结算重写 recruitList → 前向镜像覆盖手动招募"回归（自动招募正常而
     * 手动招募失效的根因域）。
     */
    @Test
    @Suppress("LongMethod")  // 双臂对拍流程：单函数承载（与主场景同构）
    fun `native manual recruit matches legacy kotlin over one phase`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInitMode(true)

        val snapshot = buildRecruitSnapshot()
        val encoded = json.encodeToString(NativeGameState.serializer(), snapshot)

        // ── Side B：纯 Kotlin 基准（DiscipleFacadeImpl.recruitDiscipleFromList 同语义） ──
        val storeB = FakeGameStateStore().also {
            it.gameDataValue = snapshot.gameData
            it.disciplesValue = snapshot.disciples
        }
        val (_, rngB, exB) = buildHarness(storeB, snapshot.gameData.rngStates, delegating = false)
        runTest {
            // Side B 全段 = flag-OFF 基准臂语义（w3-13 捕获侧检测门控不计数）
            NativeEngineFlagX.withMode(NativeEngineFlagX.Mode.OFF) {
            storeB.update {
                val recruit = gameData.recruitList.toList().find { it.id == RECRUIT_ID }
                val currentMonth = gameData.gameYear * 12 + gameData.gameMonth
                val recruited = requireNotNull(recruit).copy(
                    usage = recruit.usage.copy(recruitedMonth = currentMonth)
                )
                val newId = discipleTables.allocateAndInsert(recruited)
                if (newId.isNotEmpty()) {
                    val intId = newId.toIntOrNull()
                    if (intId != null) {
                        val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
                        discipleTables.lifeEvents[intId] = events + "${recruit.age}岁：加入宗门"
                    }
                }
                gameData = gameData.copy(
                    recruitList = gameData.recruitList.filter {
                        it.id != RECRUIT_ID && !RecruitIntegrity.isSamePerson(it, recruited)
                    },
                    recruitCountThisMonth = gameData.recruitCountThisMonth + 1,
                    annualNewDisciples = gameData.annualNewDisciples + 1
                )
            }
            // Side B 推进一旬（与 Side A 同步）
            var yearChangedB = false
            var monthChangedB = false
            storeB.update {
                val prevYear = gameData.gameYear
                val prevMonth = gameData.gameMonth
                advancePhaseBaseline(1)
                exB.phase.execute(this)
                yearChangedB = gameData.gameYear != prevYear
                monthChangedB = gameData.gameMonth != prevMonth
            }
            runBoundary(exB, storeB, yearChangedB, monthChangedB)
            }

            // ── Side A：AUTHORITATIVE 管线（C++ 核心 + 委托 RNG） ──
            NativeEngineFlagX.withMode(NativeEngineFlagX.Mode.AUTHORITATIVE) {
            assertTrue("导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
            val storeA = FakeGameStateStore().also {
                it.gameDataValue = snapshot.gameData
                it.disciplesValue = snapshot.disciples
            }
            val syncA = StateSyncService(storeA) { DiffRngBridge.nativeCoreApplyReverseDirty(it) }
            val (_, _, exA) = buildHarness(storeA, snapshot.gameData.rngStates, delegating = true)
            // native 手动招募（C++ 权威直接入宗）
            val envelope = json.parseToJsonElement(
                DiffRngBridge.nativeCoreManualRecruitFromList(RECRUIT_ID).decodeToString()
            ).jsonObject
            assertTrue(
                "native 手动招募失败: $envelope",
                envelope["ok"]?.jsonPrimitive?.booleanOrNull == true
            )
            // 镜像（生产 tick ③ 前向增量）
            val dirty = DiffRngBridge.nativeCoreExportDirty().decodeToString()
            assertTrue("镜像失败", syncA.applyDirty(dirty) != null)
            // Side A 推进一旬（与 Side B 同步）——验证招募后旬结算仍同步
            val flags = DiffRngBridge.nativeCoreSettlePhase()
            val dirty2 = DiffRngBridge.nativeCoreExportDirty().decodeToString()
            assertTrue("镜像失败", syncA.applyDirty(dirty2) != null)
            if (flags != 0) {
                runNativeBoundary(storeA, syncA, exA, flags)
            }
            assertTrue("反向增量回导失败", syncA.applyDirtyToNative())

            // ── 对拍：C++ 导出（真相源）vs Kotlin 基准 ──
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
            // 显式不变量：招募入宗 id = max(31/32/33)+1 = 34
            assertTrue("新弟子 34 未入宗", storeA.disciplesValue.any { it.id == "34" })
            assertTrue("招募列表未清空", storeA.gameDataValue.recruitList.isEmpty())
            assertEquals(1, storeA.gameDataValue.recruitCountThisMonth)
            assertEquals(1, storeA.gameDataValue.annualNewDisciples)
            }
        }
    }

    /** 年先于月变的边界编排（对拍基准侧 B = 生产 flag-OFF 回退臂同序契约） */
    private suspend fun runBoundary(
        ex: HarnessExecutors,
        store: FakeGameStateStore,
        yearChanged: Boolean,
        monthChanged: Boolean
    ) {        if (yearChanged) {
            val gd = store.gameDataValue
            ex.year.execute(gd.gameYear, gd.gameMonth == 1)
            // 年变 T2 延迟组（收购/交易/AI 招募等 11 项）forceDrain 全量执行
            //——对齐生产引擎 tick drain 语义与 C++ T2 内联执行（对齐
            // DiffYearSettlementTest 同款；mock 缺席 ≡ 场景恒零的步骤除外）
            ex.service.flushYearlyOpsQueue()
        }
        if (monthChanged) {
            var policy: PolicyCostResult = PolicyCostResult.AllPaid
            store.update { policy = ex.month.execute(this) }
            assertTrue(policy == PolicyCostResult.AllPaid)
        }
    }

    /**
     * AUTHORITATIVE 边界编排（Side A，生产同款——年先于月）：
     * nativeCoreSettleYear/Month（C++ 完整月/年结算）→ 前向增量镜像 →
     * Kotlin 残留执行器（信封草稿应用，单事务）。与生产 settleYearNative/
     * settleMonthNative 的差异仅平台面：月结前槽位窗口对齐与 AI 热控批量推送
     * （Room/ThermalMonitor 依赖，本夹具无此面——生产组件在此为 mock no-op）、
     * 事务外平台效应（生产槽 DAO 清理/DeathEvent/建筑没收 handler）不装配。
     */
    private fun runNativeBoundary(
        store: FakeGameStateStore,
        sync: StateSyncService,
        ex: HarnessExecutors,
        settleFlags: Int
    ) {
        val yearChanged = (settleFlags and GameCoreBridge.FLAG_YEAR_CHANGED) != 0
        val monthChanged = (settleFlags and GameCoreBridge.FLAG_MONTH_CHANGED) != 0
        if (yearChanged) {
            val env = parseYearSettlementEnvelope(
                DiffRngBridge.nativeCoreSettleYear().decodeToString()
            )
            val dirty = DiffRngBridge.nativeCoreExportDirty().decodeToString()
            assertTrue("年变镜像失败", sync.applyDirty(dirty) != null)
            // 非捕获事务（生产 GameEngineCoreYearOps 同款——C++ 事实的 Kotlin 投影）
            store.updateMirror { ex.yearResidual.execute(this, env) }
        }
        if (monthChanged) {
            val env = parseMonthSettlementEnvelope(
                DiffRngBridge.nativeCoreSettleMonth().decodeToString()
            )
            val dirty = DiffRngBridge.nativeCoreExportDirty().decodeToString()
            assertTrue("月变镜像失败", sync.applyDirty(dirty) != null)
            // 非捕获事务（生产 GameEngineCoreMonthOps 同款）
            store.updateMirror { ex.monthResidual.execute(this, env) }
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

    /**
     * 结构对拍**跳过的字段**（合并到单次判定——避免循环体内出现第二个 `continue`，
     * detekt `LoopWithTooManyJumpStatements` 阈值为 1）：
     *
     * - `timestamp`：运行时戳（时钟注入边界）
     * - `deathYear`：C++ 侧 P1-7 已纳入弟子协议而 Kotlin `Disciple` 镜像字段未落
     *   ——结构对拍容忍协议超集（Kotlin 镜像落地后可回收）
     * - `availableMissions[*].id`：**镜像生成字段**——Kotlin `Mission.id` 为
     *   `UUID.randomUUID()`（展示/引用用），C++ `createMission` 为确定性自增
     *   （`gc-mission-N`）。语义等价仅保证唯一，不参与 RNG 终态对拍
     *   （与 `DiffYearSettlementTest` 的 `sectDetails.tradeItems[].id` 同口径）。
     *   其余字段（template/name/difficulty/duration/rewards/enemyType/
     *   createdYear/createdMonth/triggerChance）逐位对拍。
     */
    private fun isSkippedDiffField(key: String, path: String): Boolean =
        key == "timestamp" || key == "deathYear" ||
            (key == "id" && path.contains("availableMissions")) ||
            // 商人收购 id/itemId：Kotlin UUID vs C++ 确定性自增（gc-trade-N），
            // 语义等价仅保证唯一（DiffYearSettlementTest 同口径）；收购内容
            // 其余字段（name/rarity/price/quantity/grade/年份）仍逐位对拍
            ((key == "id" || key == "itemId") && path.contains("merchantAcquisitionItems"))

    private fun compareObjects(expected: JsonObject, actual: JsonObject, path: String) {
        for ((k, a) in actual) {
            if (isSkippedDiffField(k, path)) continue
            val e = expected[k]
            // rngStates 段按**双侧共有键**比较：阶段 1② 归一后 AI 流的权威态在
            // C++ `aiRng_`（随 9 号键落盘），Kotlin 侧 6 号（AI_SECT）不再与 C++
            // 同源；两侧键集本就不同（Kotlin 无 9、C++ 无 Kotlin 的 6 号语义），
            // 协议语义差异只在共有键上成立
            if (k == "rngStates") {
                assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失", e != null)
                val expectedRng = e?.jsonObject ?: JsonObject(emptyMap())
                val actualRng = a.jsonObject
                for ((pid, av) in actualRng) {
                    val ev = expectedRng[pid] ?: continue
                    assertNodeMatches(ev, av, "$path.$k.$pid")
                }
            } else {
                assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失", e != null)
                assertNodeMatches(e!!, a, "$path.$k")
            }
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

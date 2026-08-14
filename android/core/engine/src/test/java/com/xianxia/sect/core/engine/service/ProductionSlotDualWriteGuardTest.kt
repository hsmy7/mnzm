package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.BattleTeam
import com.xianxia.sect.core.model.BattleTeamSlot
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 生产槽双存储（镜像 GameData.productionSlots vs Room ProductionSlotRepository）
 * 双写守卫测试——覆盖三处修复防线：
 *
 * 1. [ProductionCoordinator.clearDiscipleFromRepository]：真实 Repository 清理
 *    该弟子全部槽位且不影响他人（双写统一工具的正确性）
 * 2. [ProductionProcessor.processAutoAssign]：镜像残留生产槽的 IDLE 弟子
 *    不被自动排班重复分配（玩家反馈"弟子被自动任命其他工作槽位"防线）
 * 3. [ProductionProcessor.processAutoAlchemySlot]：Repository 有弟子但镜像已无
 *    （玩家已释放）→ 清 Repository 残留且不自动重启（"被自动任命回原槽"防线）
 *
 * 4. **全槽位互斥化（2026-08-10）**：processAutoAssign 候选过滤走
 *    [buildOccupiedSlotDiscipleIds]（status==IDLE 第一层 + 全槽位占用集合
 *    第二层防御）——逐槽位验证各工作槽位（纳徒长老/巡逻/藏经阁/仓库驻守/
 *    宗门驻守/战斗队伍/活跃任务/秘境/洞穴/探索队伍/血炼）占用的弟子即使
 *    存储 status=IDLE（未同步窗口）也不被捕获制造双槽位；对照组验证健康
 *    空闲弟子仍可被正常分配。
 *
 * 必须 Robolectric：候选判定依赖 spiritRootTypes 组件表（底层
 * android.util.SparseArray）——纯 JVM 环境 mockable android.jar 的
 * SparseArray 是 stub（put 无操作/get 恒 null），灵根写入读回全失效，
 * 删除本注解会让占用类测试退化为假阳性（弟子根本不进候选，断言空通过
 * 而非防线拦截）。见 ElderSlotsStatusCoverageTest 同款环境要求。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class ProductionSlotDualWriteGuardTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        store.update {
            gameData = gameData.copy(
                sectPolicies = gameData.sectPolicies.copy(
                    autoPlantRootCounts = listOf(2)
                )
            )
        }
    }

    // ── 测试 1：clearDiscipleFromRepository 真实 Repository 清理 ──

    @Test
    fun `clearDiscipleFromRepository - 真实 Repository 中该弟子全部槽位被清空且他人槽位保留`() = runTest {
        val repository = newRepository()
        repository.restoreSlots(
            listOf(
                ProductionSlot.createIdle(
                    slotIndex = 0, buildingType = BuildingType.FORGE,
                    buildingId = BuildingNames.FORGE
                ).copy(
                    assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A",
                    status = ProductionSlotStatus.WORKING
                ),
                ProductionSlot.createIdle(
                    slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY
                ).copy(
                    assignedDiscipleId = DISCIPLE_B, assignedDiscipleName = "弟子B",
                    status = ProductionSlotStatus.WORKING
                )
            ),
            slotId = 1
        )
        val coordinator = ProductionCoordinator(
            repository = repository,
            transactionManager = mock<ProductionTransactionManager>()
        )

        coordinator.clearDiscipleFromRepository(DISCIPLE_A)

        val forgeSlot = repository.getSlotsByType(BuildingType.FORGE).first()
        assertNull("A 的锻造槽位关联应清空", forgeSlot.assignedDiscipleId)
        assertEquals("A 的锻造槽位名字应清空", "", forgeSlot.assignedDiscipleName)
        val alchemySlot = repository.getSlotsByType(BuildingType.ALCHEMY).first()
        assertEquals("B 的炼丹槽位应保留", DISCIPLE_B, alchemySlot.assignedDiscipleId)
    }

    // ── 测试 2：processAutoAssign 候选防线（防双槽） ──

    @Test
    fun `processAutoAssign - 镜像残留生产槽的 IDLE 弟子不被重复分配`() = runTest {
        val processor = newProcessorWithHerbSlot()
        // 弟子 A：IDLE + 存活 + 双灵根（满足 autoPlantRootCounts=[2] 的灵植候选条件）
        writeIdleDualRootDisciple(id = 1, name = "弟子A", planting = 50)
        // 镜像分叉残留：A 仍在炼丹槽（历史只清镜像入口的产物），灵田槽空闲待分配
        store.update {
            gameData = gameData.copy(
                productionSlots = listOf(
                    ProductionSlot(
                        slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                        buildingId = BuildingNames.ALCHEMY,
                        status = ProductionSlotStatus.IDLE,
                        assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A"
                    ),
                    emptyHerbSlot()
                )
            )
        }

        store.update { processor.processAutoAssign(this) }

        val herbSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.HERB_GARDEN }
        assertNull("镜像残留炼丹槽的弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
        // 防线不减伤正常路径：未被占用的空闲弟子仍可被分配（对照组）
        val alchemySlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.ALCHEMY }
        assertEquals("A 的炼丹槽残留应保留（防线只拦截重复分配）", DISCIPLE_A, alchemySlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 镜像残留灵矿槽的 IDLE 弟子不被重复分配`() = runTest {
        val processor = newProcessorWithHerbSlot()
        writeIdleDualRootDisciple(id = 1, name = "弟子A", planting = 50)
        // 灵矿槽残留 + 灵田空槽
        store.update {
            gameData = gameData.copy(
                spiritMineSlots = listOf(
                    com.xianxia.sect.core.model.SpiritMineSlot(discipleId = DISCIPLE_A, discipleName = "弟子A")
                ),
                productionSlots = listOf(emptyHerbSlot())
            )
        }

        store.update { processor.processAutoAssign(this) }

        val herbSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.HERB_GARDEN }
        assertNull("镜像残留灵矿槽的弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    // ── 测试 2b：全槽位互斥化——各工作槽位占用的 IDLE 弟子不被自动排班 ──

    @Test
    fun `processAutoAssign - 纳徒长老占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(elderSlots = data.elderSlots.copy(recruitingElder = DISCIPLE_A))
        }
        assertNull("纳徒长老占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 巡逻槽占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(patrolSlots = listOf(PatrolSlot(discipleId = DISCIPLE_A)))
        }
        assertNull("巡逻槽占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 藏经阁槽占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(librarySlots = listOf(LibrarySlot(discipleId = DISCIPLE_A)))
        }
        assertNull("藏经阁槽占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 仓库驻守槽占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(warehouseGarrisons = listOf(WarehouseGarrisonSlot(discipleId = DISCIPLE_A)))
        }
        assertNull("仓库驻守槽占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 宗门驻守槽占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(
                worldMapSects = listOf(
                    WorldSect(
                        id = "player", name = "玩家宗门", isPlayerSect = true,
                        garrisonSlots = listOf(GarrisonSlot(discipleId = DISCIPLE_A))
                    )
                )
            )
        }
        assertNull("宗门驻守槽占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 战斗队伍占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(
                battleTeams = listOf(
                    BattleTeam(slots = listOf(BattleTeamSlot(discipleId = DISCIPLE_A)))
                )
            )
        }
        assertNull("战斗队伍占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 活跃任务占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(
                activeMissions = listOf(
                    ActiveMission(
                        missionId = "m1",
                        template = MissionTemplate.PATROL_TERRITORY,
                        difficulty = MissionDifficulty.NORMAL,
                        discipleIds = listOf(DISCIPLE_A),
                        rewards = MissionRewardConfig()
                    )
                )
            )
        }
        assertNull("活跃任务占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 秘境队伍占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(
                secretRealmState = SecretRealmState(id = "sr"),
                secretRealmSession = SecretRealmExplorationSession(
                    members = listOf(SecretRealmMemberState(discipleId = DISCIPLE_A))
                )
            )
        }
        assertNull("秘境队伍占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 洞穴探索队伍占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(
                caveExplorationTeams = listOf(
                    CaveExplorationTeam(
                        memberIds = listOf(DISCIPLE_A),
                        status = CaveExplorationStatus.EXPLORING
                    )
                )
            )
        }
        assertNull("洞穴探索占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 血炼占用的 IDLE 弟子不被排班`() = runTest {
        val processor = newProcessorWithHerbSlot()
        val herbSlot = runOccupiedScenario(processor) { data ->
            data.copy(
                activeBloodRefinements = mapOf("br1" to BloodRefinementProgress(discipleId = DISCIPLE_A))
            )
        }
        assertNull("血炼占用弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
    }

    @Test
    fun `processAutoAssign - 健康空闲弟子仍可被分配（对照组）`() = runTest {
        val processor = newProcessorWithHerbSlot()
        writeIdleDualRootDisciple(id = 1, name = "弟子A", planting = 50)
        store.update {
            discipleTables.addId(2)
            discipleTables.names[2] = "弟子B"
            discipleTables.statuses[2] = DiscipleStatus.IDLE
            discipleTables.isAlive[2] = 1
            discipleTables.realms[2] = 9
            discipleTables.realmLayers[2] = 1
            discipleTables.spiritRootTypes[2] = "金,火"
            discipleTables.spiritPlantings[2] = 40
            gameData = gameData.copy(productionSlots = listOf(emptyHerbSlot()))
        }

        store.update { processor.processAutoAssign(this) }

        val herbSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.HERB_GARDEN }
        assertTrue(
            "互斥化不得拦截健康空闲弟子：灵田空槽应被 A 或 B 填充（实际 ${herbSlot?.assignedDiscipleId}）",
            herbSlot?.assignedDiscipleId in setOf(DISCIPLE_A, DISCIPLE_B)
        )
    }

    // ── 测试 3：processAutoAlchemySlot 镜像一致性检查（清残留 + 不重启） ──

    @Test
    fun `processAutoAlchemy - Repository 有弟子但镜像无时清 Repository 残留且不重启`() = runTest {
        val repository = newRepository()
        repository.restoreSlots(
            listOf(
                ProductionSlot.createIdle(
                    slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                    buildingId = BuildingNames.ALCHEMY
                ).copy(
                    assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A",
                    autoRestartEnabled = true
                )
            ),
            slotId = 1
        )
        // 镜像 productionSlots 为空（玩家已通过只清镜像的入口释放了 A——分叉场景）
        assertTrue("镜像生产槽应为空（分叉前提）", store.latestGameData.productionSlots.isEmpty())
        val processor = newProcessor(repository)

        processor.processAutoAlchemy()

        val slot = repository.getSlotsByType(BuildingType.ALCHEMY).first()
        assertNull("分叉残留的弟子关联应从 Repository 清除", slot.assignedDiscipleId)
        assertEquals("不应自动重启（保持 IDLE 且无配方）", ProductionSlotStatus.IDLE, slot.status)
        assertNull("不应自动重启（recipeId 应为空）", slot.recipeId)
    }

    // ── fixture ──

    /**
     * 写入存储 status=IDLE 的双灵根健康弟子（满足 autoPlantRootCounts=[2] +
     * autoPlantThreshold=1 的灵植候选条件）。
     *
     * status=IDLE 是有意为之：模拟"分配后尚未 syncAllDiscipleStatuses"的
     * 陈旧状态窗口（防线第二层必须拦截的场景）。
     */
    private fun writeIdleDualRootDisciple(id: Int, name: String, planting: Int) {
        store.update {
            discipleTables.addId(id)
            discipleTables.names[id] = name
            discipleTables.statuses[id] = DiscipleStatus.IDLE
            discipleTables.isAlive[id] = 1
            discipleTables.realms[id] = 9
            discipleTables.realmLayers[id] = 1
            discipleTables.spiritRootTypes[id] = "金,木"
            discipleTables.spiritPlantings[id] = planting
        }
    }

    private fun emptyHerbSlot() = ProductionSlot(
        slotIndex = 0, buildingType = BuildingType.HERB_GARDEN,
        buildingId = BuildingNames.HERB_GARDEN,
        status = ProductionSlotStatus.IDLE
    )

    /**
     * 运行一个占用场景：弟子 A 被 [occupant] 指定的工作槽位持有（存储 status
     * 仍为 IDLE）+ 一个空灵田槽 → 执行 processAutoAssign → 返回灵田槽结果。
     */
    private fun runOccupiedScenario(
        processor: ProductionProcessor,
        occupant: (GameData) -> GameData
    ): ProductionSlot? {
        writeIdleDualRootDisciple(id = 1, name = "弟子A", planting = 50)
        store.update {
            gameData = occupant(gameData).copy(productionSlots = listOf(emptyHerbSlot()))
        }

        store.update { processor.processAutoAssign(this) }

        return store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.HERB_GARDEN }
    }

    /**
     * 构造带灵田空槽内存态的 ProductionProcessor（真实 Repository + restoreSlots
     * 预置灵田槽）——自动排班命中时 repo 回写必须真实成功，否则
     * writeBatchAssignmentToRepo 会触发 rollbackMirrorBatchAssignment 清镜像，
     * 对照组断言读到空（mock repo 的 updateSlotByBuildingId 无内存态可写）。
     */
    private suspend fun newProcessorWithHerbSlot(): ProductionProcessor {
        val repository = newRepository()
        repository.restoreSlots(listOf(emptyHerbSlot()), slotId = 1)
        return newProcessor(repository)
    }

    private fun newRepository(): ProductionSlotRepository {
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        return ProductionSlotRepository(
            dao = mock<ProductionSlotDataPort>(),
            configService = mock<BuildingConfigService>(),
            scopeProvider = scopeProvider
        )
    }

    /** 构造 ProductionProcessor：stateStore 用 Fake，其余依赖 mock */
    private fun newProcessor(repository: ProductionSlotRepository = newRepository()): ProductionProcessor {
        val scopeProvider = mock<CoroutineScopeProvider>()
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        return ProductionProcessor(
            stateStore = store,
            inventorySystem = mock(),
            productionCoordinator = mock(),
            productionSlotRepository = repository,
            formulaService = mock<FormulaService>(),
            rngManager = mock<GameRngManager>(),
            scopeProvider = scopeProvider,
            // Unconfined：repo 清理 launch(ioDispatcher) 在断言前同步执行
            // （真实 Dispatchers.IO 在 runTest 虚拟时间外异步，断言会读到旧值）
            ioDispatcher = IoDispatcher(dispatcher = Dispatchers.Unconfined),
            inventoryConfig = mock<InventoryConfig>()
        )
    }

    companion object {
        private const val DISCIPLE_A = "1"
        private const val DISCIPLE_B = "2"
    }
}

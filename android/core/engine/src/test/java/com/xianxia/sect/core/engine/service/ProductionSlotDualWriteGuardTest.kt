package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.model.DiscipleStatus
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
 */
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
    fun `processAutoAssign - 镜像残留生产槽的 IDLE 弟子不被重复分配`() {
        // 弟子 A：IDLE + 存活 + 双灵根（满足 autoPlantRootCounts=[2] 的灵植候选条件）
        store.update {
            discipleTables.addId(1)
            discipleTables.names[1] = "弟子A"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = 1
            discipleTables.realms[1] = 9
            discipleTables.realmLayers[1] = 1
            discipleTables.spiritRootTypes[1] = "金,木"
            discipleTables.spiritPlantings[1] = 50
        }
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
                    ProductionSlot(
                        slotIndex = 0, buildingType = BuildingType.HERB_GARDEN,
                        buildingId = BuildingNames.HERB_GARDEN,
                        status = ProductionSlotStatus.IDLE
                    )
                )
            )
        }
        val processor = newProcessor()

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
    fun `processAutoAssign - 镜像残留灵矿槽的 IDLE 弟子不被重复分配`() {
        store.update {
            discipleTables.addId(1)
            discipleTables.names[1] = "弟子A"
            discipleTables.statuses[1] = DiscipleStatus.IDLE
            discipleTables.isAlive[1] = 1
            discipleTables.realms[1] = 9
            discipleTables.realmLayers[1] = 1
            discipleTables.spiritRootTypes[1] = "金,木"
        }
        // 灵矿槽残留 + 灵田空槽
        store.update {
            gameData = gameData.copy(
                spiritMineSlots = listOf(
                    com.xianxia.sect.core.model.SpiritMineSlot(discipleId = DISCIPLE_A, discipleName = "弟子A")
                ),
                productionSlots = listOf(
                    ProductionSlot(
                        slotIndex = 0, buildingType = BuildingType.HERB_GARDEN,
                        buildingId = BuildingNames.HERB_GARDEN,
                        status = ProductionSlotStatus.IDLE
                    )
                )
            )
        }
        val processor = newProcessor()

        store.update { processor.processAutoAssign(this) }

        val herbSlot = store.latestGameData.productionSlots
            .find { it.buildingType == BuildingType.HERB_GARDEN }
        assertNull("镜像残留灵矿槽的弟子不得被重复分配到灵田槽", herbSlot?.assignedDiscipleId)
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

package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.domain.building.registerTestFeatures
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 一键批量拆除（BuildingFacadeImpl.removeBuildings）单元测试。
 *
 * 覆盖：批量事务/灵石返还/单次状态同步、血炼 REFINING 释放、Gate 释放、
 * 长老职位清理（最后一座）、监牢 REFLECTING 释放、任务阁清理、
 * Repository 生产槽位删除、未知实例跳过、空列表幂等。
 */
@Suppress("DEPRECATION") // 测试需访问 GameData.productionSlots 镜像
@RunWith(RobolectricTestRunner::class)
class BuildingBatchRemovalTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var gameEngineCore: GameEngineCore
    private lateinit var repository: ProductionSlotRepository
    private lateinit var productionCoordinator: ProductionCoordinator
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var discipleStatusService: DiscipleStatusService
    private lateinit var facade: BuildingFacadeImpl

    companion object {
        @BeforeClass
        @JvmStatic
        fun initRegistry() {
            BuildingFeatureRegistry.registerTestFeatures()
        }
    }

    @Before
    fun setUp() {
        tables = DiscipleTables()
        state = createMutableState(tables)
        // mockSmart + 显式 stub：update/gameDataSnapshot 被 stub 路由到外部 state
        // （断言基于 state），换 Fake 会写内部状态破坏断言语义，故保留 stub 语义
        mockStore = mockSmart(GameStateStore::class.java)
        Mockito.doAnswer { state.gameData }.`when`(mockStore).gameDataSnapshot
        Mockito.doAnswer { inv ->
            val block = inv.getArgument<MutableGameState.() -> Unit>(0)
            block(state)
            null
        }.`when`(mockStore).update(any())

        gameEngineCore = mockSmart(GameEngineCore::class.java)
        // 真实 repository（Robolectric 下 final 类 mock 拦截不可靠，且该类
        // 属性 slots 与函数 getSlots 同名存在 JVM 重载，mock 易匹配错签名）
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        repository = ProductionSlotRepository(
            dao = mockSmart(ProductionSlotDataPort::class.java),
            configService = mockSmart(BuildingConfigService::class.java),
            scopeProvider = scopeProvider
        )
        productionCoordinator = mockSmart(ProductionCoordinator::class.java)
        Mockito.doReturn(repository).`when`(productionCoordinator).repository

        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        discipleStatusService = mockSmart(DiscipleStatusService::class.java)
        val wallet = SpiritStoneWallet(
            stateStore = mockStore,
            ledger = SpiritStoneLedger(),
            eventBus = mockSmart(EventBus::class.java)
        )
        facade = BuildingFacadeImpl(
            buildingService = mockSmart(BuildingService::class.java),
            stateStore = mockStore,
            gameEngineCore = gameEngineCore,
            productionCoordinator = productionCoordinator,
            inventorySystem = mockSmart(InventorySystem::class.java),
            spiritStoneWallet = wallet,
            assignmentGate = gate,
            discipleStatusService = discipleStatusService,
            ioDispatcher = IoDispatcher()
        )
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private fun createMutableState(tables: DiscipleTables) = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
        equipmentStacks = EntityStore(emptyList()),
        equipmentInstances = EntityStore(emptyList()),
        manualStacks = EntityStore(emptyList()),
        manualInstances = EntityStore(emptyList()),
        pills = EntityStore(emptyList()),
        materials = EntityStore(emptyList()),
        herbs = EntityStore(emptyList()),
        seeds = EntityStore(emptyList()),
        storageBags = EntityStore(emptyList()),
                battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun insertDisciple(
        id: Int, status: DiscipleStatus = DiscipleStatus.IDLE,
        statusData: Map<String, String> = emptyMap()
    ) {
        val disciple = Disciple(
            id = id.toString(),
            name = "弟子$id",
            realm = 1,
            realmLayer = 1,
            age = 20,
            lifespan = 80,
            skills = SkillStats(comprehension = 100),
            statusData = statusData,
            combat = CombatAttributes(currentHp = 100, currentMp = 100)
        )
        tables.insert(disciple)
        tables.isAlive[id] = 1
        tables.statuses[id] = status
    }

    private fun building(key: String, name: String, instanceId: String) = GridBuildingData(
        buildingId = key, displayName = name, gridX = 0, gridY = 0,
        width = 2, height = 2, instanceId = instanceId
    )

    private fun stubLaunchInScope(scope: TestScope) {
        whenever(gameEngineCore.launchInScope(any())).thenAnswer { inv ->
            val block = inv.getArgument<suspend CoroutineScope.() -> Unit>(0)
            scope.launch { block(scope) }
            null
        }
    }

    // ── 批量事务与灵石 ────────────────────────────────────────

    @Test
    fun `批量拆除 - 多座建筑单事务移除并返还灵石总和`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            spiritStones = 0,
            placedBuildings = listOf(
                building("alchemy", "炼丹炉", "a1"),
                building("alchemy", "炼丹炉", "a2"),
                building("warehouse", "仓库", "w1")
            )
        )
        facade.removeBuildings(mapOf("a1" to 3000L, "a2" to 3000L, "w1" to 10000L))
        advanceUntilIdle()

        assertEquals(16000L, state.gameData.spiritStones)
        assertTrue(state.gameData.placedBuildings.isEmpty())
        verify(discipleStatusService, times(1)).syncAllDiscipleStatuses()
    }

    @Test
    fun `批量拆除 - 空 map 幂等早退不触发状态同步`() = runTest {
        stubLaunchInScope(this)
        facade.removeBuildings(emptyMap())
        advanceUntilIdle()
        verify(discipleStatusService, never()).syncAllDiscipleStatuses()
    }

    @Test
    fun `批量拆除 - 未知实例跳过不删不返款`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            spiritStones = 0,
            placedBuildings = listOf(building("alchemy", "炼丹炉", "a1"))
        )
        facade.removeBuildings(mapOf("ghost" to 999L))
        advanceUntilIdle()
        assertEquals(0L, state.gameData.spiritStones)
        assertEquals(1, state.gameData.placedBuildings.size)
    }

    // ── Repository 生产槽位 ───────────────────────────────────

    @Test
    fun `批量拆除 - repository 中目标建筑生产槽位全部删除`() = runTest {
        stubLaunchInScope(this)
        repository.loadSlots(
            listOf(
                ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                    buildingId = "alchemy").copy(id = "s1", buildingInstanceId = "a1"),
                ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                    buildingId = "alchemy").copy(id = "s2", buildingInstanceId = "a2"),
                ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.FORGE,
                    buildingId = "forge").copy(id = "s3", buildingInstanceId = "other")
            )
        )
        state.gameData = GameData(
            placedBuildings = listOf(
                building("alchemy", "炼丹炉", "a1"),
                building("alchemy", "炼丹炉", "a2")
            )
        )
        facade.removeBuildings(mapOf("a1" to 1L, "a2" to 1L))
        advanceUntilIdle()

        val remaining = repository.getSlots().map { it.id }
        assertEquals("目标建筑槽位应全部删除", listOf("s3"), remaining)
    }

    // ── Gate 释放 ─────────────────────────────────────────────

    @Test
    fun `批量拆除 - 生产弟子与采矿弟子 Gate 注册被释放`() = runTest {
        stubLaunchInScope(this)
        gate.confirmAssign("1", SlotRef(SlotCategory.PRODUCTION_SLOT, "alchemy:0", "p1"))
        gate.confirmAssign("2", SlotRef(SlotCategory.SPIRIT_MINE, "miner:0", "m1"))
        state.gameData = GameData(
            placedBuildings = listOf(
                building("alchemy", "炼丹炉", "a1"),
                building("spirit_mine", "灵矿场", "sm1")
            ),
            productionSlots = listOf(
                ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                    buildingId = "alchemy")
                    .copy(buildingInstanceId = "a1", assignedDiscipleId = "1")
            ),
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "2", buildingInstanceId = "sm1")
            )
        )
        facade.removeBuildings(mapOf("a1" to 1L, "sm1" to 1L))
        advanceUntilIdle()

        assertFalse(gate.isAssigned("1"))
        assertFalse(gate.isAssigned("2"))
    }

    // ── 血炼 REFINING 释放 ────────────────────────────────────

    @Test
    fun `批量拆除 - 血炼池拆除释放卡 REFINING 弟子并清 statusData`() = runTest {
        stubLaunchInScope(this)
        insertDisciple(5, DiscipleStatus.REFINING, mapOf("buildingId" to "bp1"))
        state.gameData = GameData(
            placedBuildings = listOf(building("blood_refining_pool", "血炼池", "bp1")),
            activeBloodRefinements = mapOf(
                "bp1" to BloodRefinementProgress(
                    discipleId = "5", discipleName = "弟子5",
                    materialId = "mat", materialName = "兽血",
                    startYear = 1, startMonth = 1, durationMonths = 6,
                    selectedStat = "hp", bonusPercent = 10.0
                )
            )
        )
        facade.removeBuildings(mapOf("bp1" to 1L))
        advanceUntilIdle()

        assertEquals(DiscipleStatus.IDLE, tables.statuses[5])
        assertTrue(tables.statusData[5]?.containsKey("buildingId") != true)
        assertTrue(state.gameData.activeBloodRefinements.isEmpty())
    }

    // ── 监牢 REFLECTING 释放 ──────────────────────────────────

    @Test
    fun `批量拆除 - 监牢拆除释放思过弟子`() = runTest {
        stubLaunchInScope(this)
        insertDisciple(
            7, DiscipleStatus.REFLECTING,
            mapOf("reflectionStartYear" to "1", "reflectionEndYear" to "2")
        )
        state.gameData = GameData(
            placedBuildings = listOf(building("reflection_cliff", "监牢", "rc1"))
        )
        facade.removeBuildings(mapOf("rc1" to 1L))
        advanceUntilIdle()

        assertEquals(DiscipleStatus.IDLE, tables.statuses[7])
        assertTrue(tables.statusData[7]?.containsKey("reflectionStartYear") != true)
    }

    // ── 任务阁 ────────────────────────────────────────────────

    @Test
    fun `批量拆除 - 任务阁清空活跃任务并释放 ON_MISSION 弟子`() = runTest {
        stubLaunchInScope(this)
        insertDisciple(9, DiscipleStatus.ON_MISSION)
        state.gameData = GameData(
            placedBuildings = listOf(building("mission_hall", "任务阁", "mh1")),
            activeMissions = listOf(
                ActiveMission(
                    missionId = "mission-1",
                    template = MissionTemplate.CLEAR_BANDITS,
                    difficulty = MissionDifficulty.SIMPLE,
                    discipleIds = listOf("9"),
                    rewards = MissionRewardConfig()
                )
            )
        )
        facade.removeBuildings(mapOf("mh1" to 1L))
        advanceUntilIdle()

        assertTrue(state.gameData.activeMissions.isEmpty())
        assertEquals(DiscipleStatus.IDLE, tables.statuses[9])
    }

    // ── 长老职位（ElderPositions） ────────────────────────────

    @Test
    fun `批量拆除 - 仅最后一座炼丹炉拆除时清空长老职位`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            placedBuildings = listOf(
                building("alchemy", "炼丹炉", "a1"),
                building("alchemy", "炼丹炉", "a2")
            ),
            elderSlots = ElderSlots(
                alchemyElder = "1",
                alchemyDisciples = listOf(DirectDiscipleSlot(index = 0, discipleId = "2"))
            )
        )
        // 拆第一座：仍有一座炼丹炉 → 长老保留
        facade.removeBuildings(mapOf("a1" to 1L))
        advanceUntilIdle()
        assertEquals("1", state.gameData.elderSlots.alchemyElder)
        assertTrue(state.gameData.elderSlots.alchemyDisciples.any { it.isActive })

        // 拆最后一座 → 长老职位清空
        facade.removeBuildings(mapOf("a2" to 1L))
        advanceUntilIdle()
        assertEquals("", state.gameData.elderSlots.alchemyElder)
        assertTrue(state.gameData.elderSlots.alchemyDisciples.none { it.isActive })
    }

    @Test
    fun `批量拆除 - 同批多座同类型一起拆时最后一座触发长老清空`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            placedBuildings = listOf(
                building("alchemy", "炼丹炉", "a1"),
                building("alchemy", "炼丹炉", "a2")
            ),
            elderSlots = ElderSlots(alchemyElder = "1")
        )
        facade.removeBuildings(mapOf("a1" to 1L, "a2" to 1L))
        advanceUntilIdle()
        assertEquals("", state.gameData.elderSlots.alchemyElder)
    }

    @Test
    fun `批量拆除 - 问道塔拆除清空外门长老与传道长老职位`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            placedBuildings = listOf(building("wen_dao_peak", "问道塔", "wdp1")),
            elderSlots = ElderSlots(
                outerElder = "1",
                preachingElder = "2",
                preachingMasters = listOf(DirectDiscipleSlot(index = 0, discipleId = "3"))
            )
        )
        facade.removeBuildings(mapOf("wdp1" to 1L))
        advanceUntilIdle()
        assertEquals("", state.gameData.elderSlots.outerElder)
        assertEquals("", state.gameData.elderSlots.preachingElder)
        assertTrue(state.gameData.elderSlots.preachingMasters.none { it.isActive })
    }

    // ── 没收宗门（2026-08-06：占领宗门被夺回时无返还拆除）────────────────

    private fun buildingWithSect(key: String, name: String, instanceId: String, sectId: String) =
        building(key, name, instanceId).copy(sectId = sectId)

    @Test
    fun `没收宗门 - 指定宗门建筑全部拆除且无灵石返还，本宗建筑保留`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            spiritStones = 0,
            placedBuildings = listOf(
                buildingWithSect("spirit_mine", "灵矿场", "sm-x", "ai-x"),
                buildingWithSect("alchemy", "炼丹炉", "a-x", "ai-x"),
                buildingWithSect("forge", "锻造坊", "f-home", "")
            )
        )
        facade.seizeBuildingsOfSect("ai-x")
        advanceUntilIdle()

        assertEquals("没收不应返还灵石", 0L, state.gameData.spiritStones)
        assertEquals("本宗建筑应保留", listOf("f-home"),
            state.gameData.placedBuildings.map { it.instanceId })
        verify(discipleStatusService, times(1)).syncAllDiscipleStatuses()
    }

    @Test
    fun `没收宗门 - 关联槽位与弟子 Gate 完整清理`() = runTest {
        stubLaunchInScope(this)
        gate.confirmAssign("1", SlotRef(SlotCategory.PRODUCTION_SLOT, "alchemy:0", "p1"))
        gate.confirmAssign("2", SlotRef(SlotCategory.SPIRIT_MINE, "miner:0", "m1"))
        state.gameData = GameData(
            placedBuildings = listOf(
                buildingWithSect("alchemy", "炼丹炉", "a-x", "ai-x"),
                buildingWithSect("spirit_mine", "灵矿场", "sm-x", "ai-x")
            ),
            productionSlots = listOf(
                ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY,
                    buildingId = "alchemy")
                    .copy(buildingInstanceId = "a-x", assignedDiscipleId = "1")
            ),
            spiritMineSlots = listOf(
                SpiritMineSlot(index = 0, discipleId = "2", buildingInstanceId = "sm-x")
            )
        )
        facade.seizeBuildingsOfSect("ai-x")
        advanceUntilIdle()

        assertTrue("生产槽位应清空", state.gameData.productionSlots.isEmpty())
        assertTrue("矿场槽位应清空", state.gameData.spiritMineSlots.isEmpty())
        assertFalse("生产弟子 Gate 应释放", gate.isAssigned("1"))
        assertFalse("采矿弟子 Gate 应释放", gate.isAssigned("2"))
    }

    @Test
    fun `没收宗门 - 空宗门与空串 sectId 幂等早退`() = runTest {
        stubLaunchInScope(this)
        state.gameData = GameData(
            spiritStones = 0,
            placedBuildings = listOf(buildingWithSect("alchemy", "炼丹炉", "a1", ""))
        )
        facade.seizeBuildingsOfSect("")   // 本宗不可没收
        facade.seizeBuildingsOfSect("no-such-sect")
        advanceUntilIdle()

        assertEquals("本宗建筑不受影响", 1, state.gameData.placedBuildings.size)
        assertEquals(0L, state.gameData.spiritStones)
        verify(discipleStatusService, never()).syncAllDiscipleStatuses()
    }
}

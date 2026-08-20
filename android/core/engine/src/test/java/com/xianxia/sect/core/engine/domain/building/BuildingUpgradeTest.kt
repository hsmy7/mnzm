package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.SectLevel
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
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.guide.GuideCounterKeys
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 建筑升级（BuildingFacadeImpl.upgradeBuilding / upgradeBuildings）单元测试。
 *
 * 覆盖：单座升级原地变换/灵石扣除/槽位保留、失败原子性、批量可负担数、
 * 空间不足跳过、相邻扩地互斥、等级门槛整批判定、空列表幂等。
 *
 * 测试注册表造价：单人住所 12000 → 中级单人住所 50000（差价 38000）；
 * 多人住所 24000 → 中级多人住所 80000（差价 56000）。
 */
@Suppress("DEPRECATION") // 测试需访问 GameData.productionSlots 镜像
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class BuildingUpgradeTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var tables: DiscipleTables
    private lateinit var state: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var gameEngineCore: GameEngineCore
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
        mockStore = mockSmart(GameStateStore::class.java)
        Mockito.doAnswer { state.gameData }.`when`(mockStore).gameDataSnapshot
        Mockito.doAnswer { inv ->
            val block = inv.getArgument<MutableGameState.() -> Unit>(0)
            block(state)
            null
        }.`when`(mockStore).update(any())

        gameEngineCore = mockSmart(GameEngineCore::class.java)
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        whenever(scopeProvider.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val repository = ProductionSlotRepository(
            dao = mockSmart(ProductionSlotDataPort::class.java),
            configService = mockSmart(BuildingConfigService::class.java),
            scopeProvider = scopeProvider
        )
        val productionCoordinator = mockSmart(ProductionCoordinator::class.java)
        Mockito.doReturn(repository).`when`(productionCoordinator).repository

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
            assignmentGate = DiscipleAssignmentGate(DiscipleAssignmentRegistry()),
            discipleStatusService = mockSmart(DiscipleStatusService::class.java),
            ioDispatcher = IoDispatcher()
        )
    }

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

    // ── 辅助构造 ──────────────────────────────────────────────

    private fun singleResidence(
        instanceId: String,
        gridX: Int = 20,
        gridY: Int = 20,
        sectId: String = "main"
    ) = GridBuildingData(
        buildingId = "single_residence", displayName = "单人住所",
        gridX = gridX, gridY = gridY, width = 4, height = 4,
        instanceId = instanceId, sectId = sectId
    )

    private fun multiResidence(
        instanceId: String,
        gridX: Int = 20,
        gridY: Int = 20,
        sectId: String = "main"
    ) = GridBuildingData(
        buildingId = "multi_residence", displayName = "多人住所",
        gridX = gridX, gridY = gridY, width = 6, height = 4,
        instanceId = instanceId, sectId = sectId
    )

    private fun setupState(
        stones: Long,
        level: Int = SectLevel.MEDIUM,
        buildings: List<GridBuildingData>,
        residenceSlots: List<ResidenceSlot> = emptyList()
    ) {
        state.gameData = GameData(
            spiritStones = stones,
            worldMapSects = listOf(WorldSect(id = "main", level = level, isPlayerSect = true)),
            activeSectId = "main",
            placedBuildings = buildings,
            residenceSlots = residenceSlots
        )
    }

    private fun upgraded(building: GridBuildingData): GridBuildingData =
        state.gameData.placedBuildings.find { it.instanceId == building.instanceId }
            ?: error("升级后建筑丢失：${building.instanceId}")

    // ── 单座升级 ──────────────────────────────────────────────

    @Test
    fun `单座升级 - 成功原地变换并扣除差价`() = runTest {
        val b = singleResidence("s1")
        setupState(stones = 100_000, buildings = listOf(b))
        val result = facade.upgradeBuilding("s1")
        assertTrue("应成功，实际：$result", result is UpgradeResult.Success)
        val after = upgraded(b)
        assertEquals("buildingId 应变为中级", "single_residence_upgraded", after.buildingId)
        assertEquals("displayName 应变为中级单人住所", "中级单人住所", after.displayName)
        assertEquals("占地宽度 4→6", 6, after.width)
        assertEquals("占地高度 4→6", 6, after.height)
        assertEquals("instanceId 不变", "s1", after.instanceId)
        assertEquals("gridX 不变", 20, after.gridX)
        assertEquals("gridY 不变", 20, after.gridY)
        assertEquals("sectId 不变", "main", after.sectId)
        assertEquals("灵石扣除差价 38000", 100_000 - 38_000, state.gameData.spiritStones)
    }

    @Test
    fun `单座升级 - 住所槽位与弟子入住关系保留`() = runTest {
        val b = singleResidence("s1")
        val slot = ResidenceSlot(buildingInstanceId = "s1", slotIndex = 0, discipleId = "d1")
        setupState(stones = 100_000, buildings = listOf(b), residenceSlots = listOf(slot))
        facade.upgradeBuilding("s1")
        assertEquals(
            "住所槽位应按原 buildingInstanceId 保留",
            listOf(slot),
            state.gameData.residenceSlots
        )
    }

    @Test
    fun `单座升级 - 灵石不足时失败且状态零变更`() = runTest {
        val b = singleResidence("s1")
        setupState(stones = 37_999, buildings = listOf(b))
        val result = facade.upgradeBuilding("s1")
        val failure = result as UpgradeResult.Failure
        assertTrue("应含灵石不足原因，实际：${failure.reasons}", failure.reasons.any { it.contains("灵石不足") })
        assertEquals("状态零变更", "single_residence", state.gameData.placedBuildings.single().buildingId)
        assertEquals("灵石零变更", 37_999L, state.gameData.spiritStones)
    }

    @Test
    fun `单座升级 - 宗门等级不足时失败`() = runTest {
        val b = singleResidence("s1")
        setupState(stones = 100_000, level = SectLevel.SMALL, buildings = listOf(b))
        val result = facade.upgradeBuilding("s1")
        val failure = result as UpgradeResult.Failure
        assertTrue("应含宗门等级原因，实际：${failure.reasons}", failure.reasons.any { it.contains("宗门等级") })
        assertEquals("状态零变更", "single_residence", state.gameData.placedBuildings.single().buildingId)
    }

    @Test
    fun `单座升级 - 空间不足时失败`() = runTest {
        val target = singleResidence("s1", gridX = 20, gridY = 20)
        val blocker = singleResidence("s2", gridX = 24, gridY = 20)
        setupState(stones = 100_000, buildings = listOf(target, blocker))
        val result = facade.upgradeBuilding("s1")
        val failure = result as UpgradeResult.Failure
        assertTrue("应含空间不足原因，实际：${failure.reasons}", failure.reasons.any { it.contains("空间不足") })
    }

    @Test
    fun `单座升级 - 不可升级建筑返回失败`() = runTest {
        val mine = GridBuildingData(
            buildingId = "spirit_mine", displayName = "灵矿场",
            gridX = 20, gridY = 20, width = 4, height = 4,
            instanceId = "mine1", sectId = "main"
        )
        setupState(stones = 100_000, buildings = listOf(mine))
        val result = facade.upgradeBuilding("mine1")
        assertTrue("不可升级建筑应失败", result is UpgradeResult.Failure)
    }

    @Test
    fun `单座升级 - 多座同类型建筑时精确升级指定实例`() = runTest {
        // 回归（真机反馈）：住所弹窗升级曾升级到同类型的第一座（按 gridX 稳定序），
        // 而非弹窗对应的实例——单座升级必须精确命中 instanceId
        val s1 = singleResidence("s1", gridX = 10, gridY = 10)
        val s2 = singleResidence("s2", gridX = 20, gridY = 20)
        setupState(stones = 100_000, buildings = listOf(s1, s2))
        val result = facade.upgradeBuilding("s2")
        assertTrue("应成功，实际：$result", result is UpgradeResult.Success)
        assertEquals("s1 应保持初级", "single_residence", upgraded(s1).buildingId)
        assertEquals("s2 应精确升级为中级", "single_residence_upgraded", upgraded(s2).buildingId)
    }

    @Test
    fun `单座升级 - 指定实例空间不足时即使他座可行也失败`() = runTest {
        // s2 的升级占地（14,10 扩 6×6）被仓库（16,10 6×4）阻挡；他座 s1 自身可升级——
        // 单座升级应校验"指定实例"的空间而非"任一实例"
        val s1 = singleResidence("s1", gridX = 10, gridY = 10)
        val s2 = singleResidence("s2", gridX = 14, gridY = 10)
        val warehouse = GridBuildingData(
            buildingId = "warehouse", displayName = "仓库",
            gridX = 16, gridY = 10, width = 6, height = 4,
            instanceId = "w1", sectId = "main"
        )
        setupState(stones = 100_000, buildings = listOf(s1, s2, warehouse))
        val result = facade.upgradeBuilding("s2")
        val failure = result as UpgradeResult.Failure
        assertTrue("应因空间不足失败，实际：${failure.reasons}", failure.reasons.any { it.contains("空间不足") })
        assertEquals("s1 保持初级", "single_residence", upgraded(s1).buildingId)
        assertEquals("s2 保持初级", "single_residence", upgraded(s2).buildingId)
    }

    // ── 批量升级（一键升级）────────────────────────────────────

    @Test
    fun `批量升级 - 灵石只够N座时恰升级N座`() = runTest {
        val buildings = listOf(
            singleResidence("s1", gridX = 10, gridY = 10),
            singleResidence("s2", gridX = 20, gridY = 10),
            singleResidence("s3", gridX = 30, gridY = 10)
        )
        // 差价 38000，持有 76000 → 只够 2 座
        setupState(stones = 76_000, buildings = buildings)
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        assertTrue("应成功，实际：$result", result is UpgradeResult.Success)
        assertEquals("应升级 2 座", 2, (result as UpgradeResult.Success).upgradedCount)
        val upgradedIds = state.gameData.placedBuildings
            .filter { it.buildingId == "single_residence_upgraded" }.map { it.instanceId }.toSet()
        assertEquals("升级实例为稳定序前两座", setOf("s1", "s2"), upgradedIds)
        assertEquals("灵石剩余 76000-2×38000", 0L, state.gameData.spiritStones)
    }

    @Test
    fun `批量升级 - 灵石不足一座时失败并告知`() = runTest {
        val b = singleResidence("s1")
        setupState(stones = 37_999, buildings = listOf(b))
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        val failure = result as UpgradeResult.Failure
        assertTrue("应含灵石不足原因，实际：${failure.reasons}", failure.reasons.any { it.contains("灵石不足") })
        assertEquals("状态零变更", "single_residence", state.gameData.placedBuildings.single().buildingId)
    }

    @Test
    fun `批量升级 - 空间不足实例被跳过并计入跳过数`() = runTest {
        // s1(20,20) 扩 6×6 与紧贴的 s2(24,20) 重叠 → s1 被挡；s2 扩 6×6 与 s1 原占地
        // 边界相接不重叠 → s2 可升
        val s1 = singleResidence("s1", gridX = 20, gridY = 20)
        val s2 = singleResidence("s2", gridX = 24, gridY = 20)
        setupState(stones = 100_000, buildings = listOf(s1, s2))
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        val success = result as UpgradeResult.Success
        assertEquals("s1 空间不足被跳过，s2 升级", 1, success.upgradedCount)
        assertEquals("跳过 1 座", 1, success.spaceBlockedCount)
        assertEquals("s1 保持初级", "single_residence", upgraded(s1).buildingId)
        assertEquals("s2 升级为中级", "single_residence_upgraded", upgraded(s2).buildingId)
    }

    @Test
    fun `批量升级 - 相邻扩地互斥时先判定者被挡后判定者可升`() = runTest {
        // 两座相邻 4×4：s1(10,10) 扩 6×6 与 s2(14,10) 重叠被挡；
        // s2 扩 6×6 与 s1 原占地边界相接不重叠 → 可升（稳定序 gridX 升序判定）
        val s1 = singleResidence("s1", gridX = 10, gridY = 10)
        val s2 = singleResidence("s2", gridX = 14, gridY = 10)
        setupState(stones = 100_000, buildings = listOf(s1, s2))
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        val success = result as UpgradeResult.Success
        assertEquals("只升第二座", 1, success.upgradedCount)
        assertEquals("第一座被跳过", 1, success.spaceBlockedCount)
        assertEquals("s1 保持初级", "single_residence", upgraded(s1).buildingId)
        assertEquals("s2 已升级", "single_residence_upgraded", upgraded(s2).buildingId)
    }

    @Test
    fun `批量升级 - 宗门等级不足整批判定失败零变更`() = runTest {
        val buildings = listOf(
            singleResidence("s1", gridX = 10, gridY = 10),
            singleResidence("s2", gridX = 20, gridY = 10)
        )
        setupState(stones = 100_000, level = SectLevel.SMALL, buildings = buildings)
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        val failure = result as UpgradeResult.Failure
        assertTrue("应含宗门等级原因，实际：${failure.reasons}", failure.reasons.any { it.contains("宗门等级") })
        assertTrue("全部保持初级", state.gameData.placedBuildings.all { it.buildingId == "single_residence" })
        assertEquals("灵石零变更", 100_000L, state.gameData.spiritStones)
    }

    @Test
    fun `批量升级 - 无候选实例时失败`() = runTest {
        setupState(stones = 100_000, buildings = emptyList())
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        assertTrue("无候选应失败", result is UpgradeResult.Failure)
    }

    @Test
    fun `批量升级 - maxCount为0时失败`() = runTest {
        val b = singleResidence("s1")
        setupState(stones = 100_000, buildings = listOf(b))
        val result = facade.upgradeBuildings("main", "single_residence", 0)
        assertTrue("maxCount=0 应失败", result is UpgradeResult.Failure)
    }

    @Test
    fun `批量升级 - 未知源建筑key失败`() = runTest {
        setupState(stones = 100_000, buildings = emptyList())
        val result = facade.upgradeBuildings("main", "unknown_building", 1)
        assertTrue("未知源建筑应失败", result is UpgradeResult.Failure)
    }

    @Test
    fun `批量升级 - 他宗门实例不参与本宗门批量升级`() = runTest {
        val main = singleResidence("s1", gridX = 10, gridY = 10, sectId = "main")
        val other = singleResidence("s2", gridX = 20, gridY = 10, sectId = "sect_b")
        setupState(stones = 100_000, buildings = listOf(main, other))
        val result = facade.upgradeBuildings("main", "single_residence", Int.MAX_VALUE)
        assertTrue("应成功，实际：$result", result is UpgradeResult.Success)
        assertEquals("仅本宗门 1 座升级", 1, (result as UpgradeResult.Success).upgradedCount)
        assertEquals("他宗门实例保持初级", "single_residence", upgraded(other).buildingId)
    }

    // ── 建造累计计数（引导累计计算）────────────────────────────────

    @Test
    fun `建造 - 成功放置后累计建造计数+1`() = runTest {
        setupState(stones = 100_000, buildings = emptyList())
        val b = GridBuildingData(
            buildingId = "alchemy", displayName = "炼丹炉",
            gridX = 20, gridY = 20, width = 4, height = 3,
            instanceId = "n1", sectId = "main"
        )
        facade.placeBuilding(b)
        assertEquals(
            "累计建造计数应 +1",
            1L, state.gameData.guideCounters[GuideCounterKeys.buildingBuiltKey("炼丹炉")]
        )
        assertEquals("建筑已放置", 1, state.gameData.placedBuildings.size)
    }

    @Test
    fun `建造 - 重复放置累计叠加`() = runTest {
        setupState(stones = 100_000, buildings = emptyList())
        facade.placeBuilding(
            GridBuildingData(buildingId = "alchemy", displayName = "炼丹炉",
                gridX = 20, gridY = 20, width = 4, height = 3, instanceId = "n1", sectId = "main")
        )
        facade.placeBuilding(
            GridBuildingData(buildingId = "alchemy", displayName = "炼丹炉",
                gridX = 30, gridY = 20, width = 4, height = 3, instanceId = "n2", sectId = "main")
        )
        assertEquals(
            "累计建造计数应叠加为 2",
            2L, state.gameData.guideCounters[GuideCounterKeys.buildingBuiltKey("炼丹炉")]
        )
    }

    @Test
    fun `升级 - 不改变建造累计计数（升级是原地变换非新建）`() = runTest {
        val b = singleResidence("s1")
        setupState(stones = 100_000, buildings = listOf(b))
        facade.upgradeBuilding("s1")
        assertEquals(
            "升级不应新增建造计数",
            null, state.gameData.guideCounters[GuideCounterKeys.buildingBuiltKey("中级单人住所")]
        )
    }
}

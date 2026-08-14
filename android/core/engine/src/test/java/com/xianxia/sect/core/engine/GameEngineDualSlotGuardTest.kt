package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.service.SecretRealmService
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.MissionDifficulty
import com.xianxia.sect.core.model.MissionRewardConfig
import com.xianxia.sect.core.model.MissionTemplate
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner



/**
 * 跨系统双槽位守卫测试：验证所有分配入口在"弟子已在其他槽位"时
 * 旧槽位被清空、新槽位唯一、gate 注册唯一。
 *
 * 决策留痕：GameEngineBattleOps.occupySectRewards 战后自动填 garrisonSlots
 * （:271/:292）不在本守卫范围——它是战争结算产物（幸存者自动入驻被占领宗），
 * 非玩家任命入口，战斗流程本身约束弟子"在战斗"，不会与驻守长期共存。
 * 若未来出现"战驻双槽位"报告，需在此补充覆盖。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineDualSlotGuardTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var discipleFacade: DiscipleFacade
    private lateinit var mockCore: GameEngineCore
    private lateinit var mockPC: ProductionCoordinator

    companion object {
        private const val DISCIPLE_A = "1"
        private const val DISCIPLE_B = "2"
        private const val DISCIPLE_C = "3"
        private const val DISCIPLE_D = "4"
        private const val SECT_ID = "sect_player"
    }

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()

        // 创建 6 个测试弟子（事务内初始化 DiscipleTables，tables 实例被 store 持久化）
        store.update {
            discipleTables.writeAllowed = true
            for (i in 1..6) {
                val id = i
                discipleTables.addId(id)
                discipleTables.names[id] = "弟子${i}"
                discipleTables.statuses[id] = DiscipleStatus.IDLE
                discipleTables.isAlive[id] = 1
                discipleTables.realms[id] = 9
                discipleTables.realmLayers[id] = 1
                discipleTables.portraitRes[id] = "portrait_$id"
            }
            discipleTables.writeAllowed = false
        }

        // 创建测试槽位：巡逻 2 槽 + 玩家宗门驻守 1 槽 + 秘境存在 + 灵石/材料种子
        store.update {
            gameData = gameData.copy(
                patrolSlots = listOf(PatrolSlot(index = 0), PatrolSlot(index = 1)),
                worldMapSects = listOf(
                    WorldSect(
                        id = SECT_ID, isPlayerSect = true,
                        garrisonSlots = listOf(GarrisonSlot(index = 0))
                    )
                ),
                secretRealmState = SecretRealmState(id = "sr1"),
                spiritStones = 1000L
            )
            materials.add(Material(id = "m1", name = "兽血", rarity = 2, quantity = 5))
        }

        setupEngine()
    }

    /** mock GameEngine（8 构造参数），仅 stateStore + assignmentGate 为真实实现 */
    private fun setupEngine() {
        discipleFacade = mock()
        mockCore = mock<GameEngineCore>()
        // launchInScope 同步执行（非 suspend 入口依赖）；返回 mock Job 满足返回类型
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        // scopeForStateIn 供 clearDiscipleFromProductionRepository 传 scope；
        // mock 默认返回 null 会与 verify(any()) 不匹配（any() 不含 null）
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val mockBattleFacade = mock<BattleFacade>()
        whenever(mockBattleFacade.assignmentGate).thenReturn(gate)
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(MutableStateFlow(emptyList()))
        val mockCultivationFacade = mock<CultivationFacade>()
        whenever(mockCultivationFacade.cultivationService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleService).thenReturn(mock())
        whenever(mockCultivationFacade.discipleFacade).thenReturn(discipleFacade)
        whenever(mockCultivationFacade.productionFacade).thenReturn(mockProductionFacade)
        mockPC = mock<ProductionCoordinator>()
        // getSlots() 函数与属性 slots 的 JVM getter 同名，Mockito 无法安全 stub（见
        // BootSequenceControllerTest T15 注释）；heal 侧对 null 有 ?: emptyList() 兜底，
        // 此处保持裸 mock（getSlots 返回 null 走兜底）
        whenever(mockPC.repository).thenReturn(mock())
        whenever(mockCultivationFacade.productionCoordinator).thenReturn(mockPC)
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        val mockEconomyFacade = mock<EconomyFacade>()
        whenever(mockEconomyFacade.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(mockEconomyFacade.mailService).thenReturn(mock())
        // 秘境服务：真实实例（构造依赖全部 mock），stub 到 explorationFacade 链
        val mockRngManager = mock<GameRngManager>()
        whenever(mockRngManager.getRng(RngPartition.SECRET_REALM)).thenReturn(mock())
        val realSecretRealmService = SecretRealmService(
            rngManager = mockRngManager,
            battleSystem = mock(),
            inventorySystem = mock(),
            spiritStoneWallet = mock(),
            overflowMailSender = mock(),
            assignmentGate = mock()
        )
        val mockExplorationFacade = mock<ExplorationFacade>()
        whenever(mockExplorationFacade.secretRealmService).thenReturn(realSecretRealmService)

        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(),
            explorationFacade = mockExplorationFacade,
            cultivationFacade = mockCultivationFacade,
            economyFacade = mockEconomyFacade,
            battleFacade = mockBattleFacade
        )
    }

    /** 把弟子放入巡逻槽位 0（模拟"已在岗"）并登记 gate */
    private fun placeInPatrol(discipleId: String) {
        store.update {
            val slots = gameData.patrolSlots.toMutableList()
            slots[0] = PatrolSlot(index = 0, discipleId = discipleId, discipleName = "弟子$discipleId")
            gameData = gameData.copy(patrolSlots = slots)
        }
        gate.confirmAssign(
            discipleId, SlotRef(SlotCategory.PATROL_SLOT, "patrol", "patrol_0")
        )
    }

    private fun patrolDiscipleAt(index: Int): String =
        store.latestGameData.patrolSlots[index].discipleId

    // ── 世界地图驻守 ──

    @Test
    fun `已在巡逻槽位的弟子分配驻守后旧槽位被清空且 gate 唯一`() = runTest {
        placeInPatrol(DISCIPLE_A)

        engine.assignGarrisonDisciple(SECT_ID, slotIndex = 0, discipleId = DISCIPLE_A)

        assertEquals("巡逻槽位应清空", "", patrolDiscipleAt(0))
        val garrison = store.latestGameData.worldMapSects
            .find { it.id == SECT_ID }!!.garrisonSlots[0]
        assertEquals("驻守槽位应为 A", DISCIPLE_A, garrison.discipleId)
        val assignment = gate.getAssignment(DISCIPLE_A)
        assertEquals("gate 应唯一登记驻守", SlotCategory.GARRISON_SLOT, assignment?.slotRef?.category)
    }

    @Test
    fun `驻守分配顶替旧驻守时旧驻守 gate 释放且状态回归`() = runTest {
        // B 已在驻守槽
        store.update {
            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { sect ->
                    if (sect.id == SECT_ID) {
                        sect.copy(garrisonSlots = listOf(
                            GarrisonSlot(index = 0, discipleId = DISCIPLE_B, discipleName = "弟子B")
                        ))
                    } else sect
                }
            )
        }
        gate.confirmAssign(DISCIPLE_B, SlotRef(SlotCategory.GARRISON_SLOT, "$SECT_ID:0", "garrison_${SECT_ID}_0"))

        engine.assignGarrisonDisciple(SECT_ID, slotIndex = 0, discipleId = DISCIPLE_A)

        assertFalse("旧驻守 B 的 gate 应释放", gate.isAssigned(DISCIPLE_B))
        verify(discipleFacade).syncSingleDiscipleStatus(DISCIPLE_B)
    }

    // ── 任务派遣 ──

    @Test
    fun `已在巡逻槽位的弟子派遣任务后旧槽位被清空`() = runTest {
        placeInPatrol(DISCIPLE_A)
        val disciples = store.discipleTables.assembleAll()
        val mission = Mission(
            template = MissionTemplate.PATROL_TERRITORY,
            name = "护送任务",
            description = "",
            difficulty = MissionDifficulty.SIMPLE,
            duration = 3,
            rewards = MissionRewardConfig()
        )

        engine.startMission(mission, disciples)

        assertEquals("巡逻槽位应清空", "", patrolDiscipleAt(0))
        assertTrue("任务应已开始", store.latestGameData.activeMissions.isNotEmpty())
        assertFalse("gate 应释放（任务不登记 gate）", gate.isAssigned(DISCIPLE_A))
    }

    // ── 秘境出发 ──

    @Test
    fun `已在巡逻槽位的弟子出发秘境后旧槽位被清空且成员 gate 注册`() = runTest {
        placeInPatrol(DISCIPLE_A)
        val memberIds = listOf(DISCIPLE_A, DISCIPLE_B, DISCIPLE_C, DISCIPLE_D)

        val result = engine.startSecretRealmExploration(memberIds)

        assertTrue("秘境出发应成功", result.isSuccess)
        assertEquals("巡逻槽位应清空", "", patrolDiscipleAt(0))
        assertTrue("秘境会话应激活", store.latestGameData.secretRealmSession.isActive)
        for (id in memberIds) {
            val assignment = gate.getAssignment(id)
            assertEquals("成员 $id 应登记秘境", SlotCategory.EXPLORATION_TEAM, assignment?.slotRef?.category)
        }
    }

    @Test
    fun `秘境出发校验失败时旧槽位不被清理`() = runTest {
        placeInPatrol(DISCIPLE_A)
        // 秘境已消失（exists=false）→ startSession 返回 Failure，不应清理任何岗位
        store.update { gameData = gameData.copy(secretRealmState = SecretRealmState()) }
        val memberIds = listOf(DISCIPLE_A, DISCIPLE_B, DISCIPLE_C, DISCIPLE_D)

        val result = engine.startSecretRealmExploration(memberIds)

        assertTrue("秘境已消失应返回 Failure", result.isFailure)
        assertEquals("失败路径不得清理巡逻槽位", DISCIPLE_A, patrolDiscipleAt(0))
        assertTrue("失败路径 gate 应保留", gate.isAssigned(DISCIPLE_A))
    }

    // ── 血炼 ──

    private fun bloodProgressFor(discipleId: String) = BloodRefinementProgress(
        discipleId = discipleId,
        discipleName = "弟子$discipleId",
        materialId = "m1",
        materialName = "兽血",
        durationMonths = 3,
        selectedStat = "hp",
        bonusPercent = 5.0
    )

    @Test
    fun `已在巡逻槽位的弟子启动血炼后旧槽位被清空且血炼池唯一`() = runTest {
        placeInPatrol(DISCIPLE_A)

        val result = engine.startBloodRefinementAtomic(
            materialName = "兽血", materialRarity = 2, materialCount = 1,
            buildingInstanceId = "blood_b1", requiredSpiritStones = 100L,
            progress = bloodProgressFor(DISCIPLE_A)
        )

        assertTrue("血炼应成功", result is BloodRefinementStartResult.Success)
        assertEquals("巡逻槽位应清空", "", patrolDiscipleAt(0))
        val refinements = store.latestGameData.activeBloodRefinements
        assertEquals("血炼池应只有 A 的 1 条", 1, refinements.size)
        assertEquals("血炼池应为 blood_b1", DISCIPLE_A, refinements["blood_b1"]?.discipleId)
    }

    @Test
    fun `血炼事务失败时旧槽位不被清理`() = runTest {
        placeInPatrol(DISCIPLE_A)
        // 灵石不足 → checkStones 抛错 → 事务整体回滚
        store.update { gameData = gameData.copy(spiritStones = 0L) }

        val result = engine.startBloodRefinementAtomic(
            materialName = "兽血", materialRarity = 2, materialCount = 1,
            buildingInstanceId = "blood_b1", requiredSpiritStones = 100L,
            progress = bloodProgressFor(DISCIPLE_A)
        )

        assertTrue("灵石不足应返回 Error", result is BloodRefinementStartResult.Error)
        assertEquals("失败回滚后巡逻槽位应保留 A", DISCIPLE_A, patrolDiscipleAt(0))
        assertTrue("失败回滚后血炼池应为空", store.latestGameData.activeBloodRefinements.isEmpty())
    }

    // ── 仓库驻守 ──

    @Test
    fun `已在巡逻槽位的弟子驻守仓库后旧槽位被清空且 gate 注册仓库`() = runTest {
        placeInPatrol(DISCIPLE_A)

        engine.assignWarehouseGarrisonAtomic(
            buildingInstanceId = "wh1", discipleId = DISCIPLE_A,
            discipleName = "弟子A", sectId = SECT_ID
        )

        assertEquals("巡逻槽位应清空", "", patrolDiscipleAt(0))
        val warehouse = store.latestGameData.warehouseGarrisons
        assertEquals("仓库驻守应为 A", DISCIPLE_A, warehouse.find { it.buildingInstanceId == "wh1" }?.discipleId)
        val assignment = gate.getAssignment(DISCIPLE_A)
        assertEquals("gate 应唯一登记仓库", SlotCategory.WAREHOUSE_GARRISON, assignment?.slotRef?.category)
    }

    @Test
    fun `仓库驻守顶替旧驻守时旧驻守 gate 被释放`() = runTest {
        store.update {
            gameData = gameData.copy(
                warehouseGarrisons = listOf(
                    WarehouseGarrisonSlot("wh1", DISCIPLE_B, "弟子B", SECT_ID)
                )
            )
        }
        gate.confirmAssign(DISCIPLE_B, SlotRef(SlotCategory.WAREHOUSE_GARRISON, "wh1", "warehouse_wh1"))

        engine.assignWarehouseGarrisonAtomic(
            buildingInstanceId = "wh1", discipleId = DISCIPLE_A,
            discipleName = "弟子A", sectId = SECT_ID
        )

        assertFalse("旧驻守 B 的 gate 应释放", gate.isAssigned(DISCIPLE_B))
        verify(discipleFacade).syncSingleDiscipleStatus(DISCIPLE_B)
    }

    // ── 读档自愈 ──

    @Test
    fun `双槽位旧档自愈后仅保留赢家槽位且 gate 重建唯一`() = runTest {
        // A 同时出现在长老（副宗主）与巡逻槽位
        store.update {
            gameData = gameData.copy(
                elderSlots = ElderSlots(viceSectMaster = DISCIPLE_A),
                patrolSlots = listOf(
                    PatrolSlot(index = 0, discipleId = DISCIPLE_A, discipleName = "弟子A"),
                    PatrolSlot(index = 1)
                )
            )
        }

        engine.healDuplicateSlotAssignments()

        val data = store.latestGameData
        assertEquals("长老槽位应保留 A（赢家）", DISCIPLE_A, data.elderSlots.viceSectMaster)
        assertEquals("巡逻槽位应清空", "", data.patrolSlots[0].discipleId)
        val assignment = gate.getAssignment(DISCIPLE_A)
        assertNotNull("gate 应重建登记", assignment)
        assertEquals("gate 应为赢家槽位", SlotCategory.ELDER_POSITION, assignment!!.slotRef.category)
        assertEquals("gate 应为副宗主", "viceSectMaster", assignment.slotRef.slotType)
    }

    @Test
    fun `双槽位旧档自愈后住所保留`() = runTest {
        // A 在长老 + 巡逻 + 住所（住所与工作共存是有意设计）
        store.update {
            gameData = gameData.copy(
                elderSlots = ElderSlots(viceSectMaster = DISCIPLE_A),
                patrolSlots = listOf(
                    PatrolSlot(index = 0, discipleId = DISCIPLE_A, discipleName = "弟子A"),
                    PatrolSlot(index = 1)
                ),
                residenceSlots = listOf(
                    ResidenceSlot(
                        buildingInstanceId = "res_b1", slotIndex = 0,
                        discipleId = DISCIPLE_A, discipleName = "弟子A"
                    )
                )
            )
        }

        engine.healDuplicateSlotAssignments()

        val data = store.latestGameData
        assertEquals("住所应保留（共存设计）", DISCIPLE_A, data.residenceSlots[0].discipleId)
        assertEquals("长老槽位应保留 A（赢家）", DISCIPLE_A, data.elderSlots.viceSectMaster)
        assertEquals("巡逻槽位应清空", "", data.patrolSlots[0].discipleId)
    }

    @Test
    fun `秘境成员加岗位槽并存旧档自愈后岗位被清空`() = runTest {
        // A 在秘境队伍 + 巡逻槽（原 Bug 场景）
        store.update {
            gameData = gameData.copy(
                patrolSlots = listOf(
                    PatrolSlot(index = 0, discipleId = DISCIPLE_A, discipleName = "弟子A"),
                    PatrolSlot(index = 1)
                ),
                secretRealmSession = com.xianxia.sect.core.model.SecretRealmExplorationSession(
                    secretRealmId = "sr1",
                    members = listOf(
                        com.xianxia.sect.core.model.SecretRealmMemberState(
                            discipleId = DISCIPLE_A, name = "弟子A", portraitRes = "p",
                            realm = 9, realmName = "炼气1层", maxHp = 100
                        )
                    )
                )
            )
        }

        engine.healDuplicateSlotAssignments()

        assertEquals("岗位槽位应清空", "", store.latestGameData.patrolSlots[0].discipleId)
        assertEquals("秘境成员应保留", 1, store.latestGameData.secretRealmSession.members.size)
    }

    // ── 长老漏网（recruitingElder 清理） ──

    @Test
    fun `纳徒长老弟子调任巡逻时旧槽位被清空`() = runTest {
        // A 在纳徒长老（regression：clearElderSlots 曾漏 recruitingElder）
        store.update {
            gameData = gameData.copy(
                elderSlots = ElderSlots(recruitingElder = DISCIPLE_A)
            )
        }
        gate.confirmAssign(
            DISCIPLE_A, SlotRef(SlotCategory.ELDER_POSITION, "recruitingElder", "elder_recruitingElder")
        )

        engine.assignPatrolAtomic(DISCIPLE_A, globalIndex = 0)

        assertEquals("纳徒长老槽位应清空", "", store.latestGameData.elderSlots.recruitingElder)
        assertEquals("巡逻槽位应为 A", DISCIPLE_A, patrolDiscipleAt(0))
        val assignment = gate.getAssignment(DISCIPLE_A)
        assertEquals("gate 应唯一登记巡逻", SlotCategory.PATROL_SLOT, assignment?.slotRef?.category)
    }

    // ── 双存储双写（镜像 + Room 生产槽 Repository） ──

    @Test
    fun `释放所有槽位后同步清 Room 生产槽 Repository`() = runTest {
        placeInPatrol(DISCIPLE_A)

        engine.releaseDiscipleFromAllSlotsAtomic(DISCIPLE_A)

        assertEquals("巡逻槽位应清空", "", patrolDiscipleAt(0))
        // 双存储同步：事务内只清镜像，事务后必须清 Room 生产槽 Repository，
        // 否则 repo 残留占用经月度自动重启复活（双槽分叉根因）
        verify(mockPC).clearDiscipleInRepository(any(), eq(DISCIPLE_A))
    }

    @Test
    fun `自愈生产槽双槽后同步清 Room 生产槽 Repository`() = runTest {
        // A 同时出现在巡逻槽位与生产槽（历史分叉存档）。
        // 生产槽双份一致占用（镜像 + Room Repository 真源都有 A），
        // 自愈扫描发现双槽后镜像清空 + Repository 同步清理。
        val realRepo = ProductionSlotRepository(
            dao = mock<com.xianxia.sect.core.repository.ProductionSlotDataPort>(),
            configService = mock<com.xianxia.sect.core.config.BuildingConfigService>(),
            scopeProvider = mock<com.xianxia.sect.core.util.CoroutineScopeProvider>().also {
                whenever(it.scope).thenReturn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            }
        )
        realRepo.restoreSlots(
            listOf(
                com.xianxia.sect.core.model.production.ProductionSlot.createIdle(
                    slotIndex = 0,
                    buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                    buildingId = "alchemy"
                ).copy(
                    assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A",
                    status = com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                )
            ),
            slotId = 1
        )
        whenever(mockPC.repository).thenReturn(realRepo)
        // 让 mock 上的 suspend 清理真实执行到 realRepo：端到端验证
        // "自愈调用清理 → Repository 真源确实清空"（纯 mock 会静默不做事）
        val realCoordinator = ProductionCoordinator(
            repository = realRepo,
            transactionManager = mock<com.xianxia.sect.core.transaction.ProductionTransactionManager>()
        )
        whenever(mockPC.clearDiscipleFromRepository(any())).thenAnswer { invocation ->
            runBlocking {
                realCoordinator.clearDiscipleFromRepository(invocation.getArgument<String>(0))
            }
            null
        }
        store.update {
            gameData = gameData.copy(
                patrolSlots = listOf(
                    PatrolSlot(index = 0, discipleId = DISCIPLE_A, discipleName = "弟子A"),
                    PatrolSlot(index = 1)
                ),
                productionSlots = listOf(
                    com.xianxia.sect.core.model.production.ProductionSlot(
                        slotIndex = 0,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                        buildingId = "alchemy",
                        status = com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE,
                        assignedDiscipleId = DISCIPLE_A, assignedDiscipleName = "弟子A"
                    )
                )
            )
        }

        engine.healDuplicateSlotAssignments()

        // 赢家为巡逻槽位 → 镜像生产槽被清空
        val prodSlots = store.latestGameData.productionSlots
        assertTrue("镜像生产槽应被清空", prodSlots.none { it.assignedDiscipleId == DISCIPLE_A })
        // 双存储同步：自愈清镜像后必须同步清 Room 生产槽 Repository
        verify(mockPC).clearDiscipleFromRepository(eq(DISCIPLE_A))
        // Repository 真源同步清空（真实 repo 上的 updateSlot 执行）
        assertTrue(
            "Repository 生产槽应同步清空",
            realRepo.getSlots().none { it.assignedDiscipleId == DISCIPLE_A }
        )
    }
}

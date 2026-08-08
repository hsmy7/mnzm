package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExplorationTeam
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L1b 等价性安全网：死亡/哀悼流水线列直写重构前的回归夹具。
 *
 * 断言重构后的产出状态（griefEndYears 列 / lifeEvents 文本 / 死亡标记 / 解绑），
 * 第 2 步（L1b 列直写）后本测试仍绿 = 等价性成立。
 *
 * 现状行为约定（重构必须保持）：
 * - 哀悼期 = currentYear + 1，取 max（已更长则保留）
 * - 事件只对"新进入哀悼"者生成（originalList 中已哀悼者无新事件，即使 griefEndYear 被刷新）
 * - `deduceRelationship` 第 4 分支"子女"实际不可达（`==` 对称与第 3 分支相同条件）：
 *   死者父母（grieving 是死者子女的父/母）收到的是"亲属"文本——预存缺陷，等价性测试按现状断言
 * - 事件文本中 grievingAge = 老化前年龄（applyAgedDeath 在 applyAliveUpdates 之前执行）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeathPipelineEquivalenceTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mutableState: MutableGameState
    private lateinit var mockStore: GameStateStore
    private lateinit var processor: DiscipleLifecycleProcessor
    private lateinit var gameDataFlow: MutableStateFlow<GameData>
    private lateinit var disciplesFlow: MutableStateFlow<List<Disciple>>

    @Before
    fun setUp() {
        tables = DiscipleTables()
        mutableState = createMutableState(tables)
        gameDataFlow = MutableStateFlow(GameData(gameYear = 10))
        disciplesFlow = MutableStateFlow(emptyList())

        // 创建 delegate mock 但不做任何 property stub。
        // 所有属性在匿名类中直接 override 以避免 Mockito property 状态污染。
        val delegate = mock(GameStateStore::class.java)

        mockStore = object : GameStateStore by delegate {
            override val discipleTables: DiscipleTables get() = tables
            override val gameData: StateFlow<GameData> get() = gameDataFlow
            override val disciples: StateFlow<List<Disciple>> get() = disciplesFlow
            override val equipmentStacks: StateFlow<List<EquipmentStack>>
                get() = MutableStateFlow(emptyList())
            override val equipmentInstances: StateFlow<List<EquipmentInstance>>
                get() = MutableStateFlow(emptyList())
            override val manualStacks: StateFlow<List<ManualStack>>
                get() = MutableStateFlow(emptyList())
            override val manualInstances: StateFlow<List<ManualInstance>>
                get() = MutableStateFlow(emptyList())
            override val pills: StateFlow<List<Pill>>
                get() = MutableStateFlow(emptyList())
            override val materials: StateFlow<List<Material>>
                get() = MutableStateFlow(emptyList())
            override val herbs: StateFlow<List<Herb>>
                get() = MutableStateFlow(emptyList())
            override val seeds: StateFlow<List<Seed>>
                get() = MutableStateFlow(emptyList())
            override val storageBags: StateFlow<List<StorageBag>>
                get() = MutableStateFlow(emptyList())
            override val teams: StateFlow<List<ExplorationTeam>>
                get() = MutableStateFlow(emptyList())
            override val battleLogs: StateFlow<List<BattleLog>>
                get() = MutableStateFlow(emptyList())

            override fun update(
                block: MutableGameState.() -> Unit
            ) {
                block.invoke(mutableState)
            }

            override fun <R> updateAndReturn(
                block: MutableGameState.() -> R
            ): R {
                return block.invoke(mutableState)
            }
        }

        processor = DiscipleLifecycleProcessor(
            stateStore = mockStore,
            scopeProvider = mock(CoroutineScopeProvider::class.java),
            productionCoordinator = mock(
                com.xianxia.sect.core.engine.domain.production.ProductionCoordinator::class.java
            ),
            eventBus = mock(EventBusPort::class.java),
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            lawEnforcementProcessor = object : javax.inject.Provider<LawEnforcementProcessor> {
                override fun get(): LawEnforcementProcessor = mock(LawEnforcementProcessor::class.java)
            },
            discipleStatusService = mock(DiscipleStatusService::class.java),
            ioDispatcher = IoDispatcher(),
            inventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
                stateStore = mockStore,
                inventoryConfig = InventoryConfig(),
                spiritStoneWallet = mock(com.xianxia.sect.core.wallet.SpiritStoneWallet::class.java),
                gameConfigProvider = mock(com.xianxia.sect.core.engine.config.GameConfigProvider::class.java)
            )
        )
    }

    // ==================== 辅助 ====================

    private fun insertDisciple(
        id: Int,
        name: String = "弟子$id",
        realm: Int = 9,
        realmLayer: Int = 3,
        age: Int = 20,
        lifespan: Int = 80,
        status: DiscipleStatus = DiscipleStatus.IDLE,
        statusData: Map<String, String> = emptyMap(),
        social: SocialData = SocialData(),
        skills: SkillStats = SkillStats(),
        skipTablesIsAlive: Boolean = false
    ) {
        val disciple = Disciple(
            id = id.toString(),
            name = name,
            realm = realm,
            realmLayer = realmLayer,
            age = age,
            lifespan = lifespan,
            status = status,
            statusData = statusData,
            social = social,
            skills = skills
        )
        tables.insert(disciple)
        if (!skipTablesIsAlive) {
            tables.isAlive[id] = 1
        }
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
        teams = emptyList(),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    // ==================== 用例 ====================

    @Test
    fun `aged death - grief end years and bereavement events for all relative types`() = runTest {
        // 死者 id=1：配偶 2 / 子女 3,4（死者是 3,4 的父/母）/ 父母 5（死者是 5 的子女）/ 手足 6（共同 parentId "99"）
        insertDisciple(
            1, name = "寿终老人", age = 79, lifespan = 80,
            social = SocialData(partnerId = "2", parentId1 = "5", parentId2 = "99")
        )
        insertDisciple(2, name = "未亡人", age = 50, social = SocialData(partnerId = "1"))
        insertDisciple(3, name = "大孝子", age = 30, social = SocialData(parentId1 = "1"))
        insertDisciple(4, name = "小孝女", age = 28, social = SocialData(parentId2 = "1"))
        // lifespan=200：本测试聚焦单死者（K=1）等价性；99 岁默认 lifespan=80 会判死，
        // 触发 K=2 多死者覆盖 bug（后死者 replaceAll 用死前列表复活先死者），
        // 属预存缺陷，重构（computeGriefEndYearMap 全批处理）时另行修复验证
        insertDisciple(5, name = "老翁", age = 99, lifespan = 200)
        insertDisciple(6, name = "手足", age = 40, social = SocialData(parentId1 = "99"))
        insertDisciple(7, name = "路人", age = 20)
        insertDisciple(8, name = "已哀悼者", age = 35, social = SocialData(griefEndYear = 12))
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        // ── 死亡标记 ──
        assertFalse("死者应从表移除", tables.ids.contains(1))
        assertEquals("死亡年份", 10, tables.deathYears[1])
        assertEquals("年度死亡计数", 1, mutableState.gameData.annualDeceasedDisciples)
        assertEquals("死者自身无哀悼条目（remove 清列）", -1, tables.griefEndYears.getOrDefault(1, -1))

        // ── griefEndYears：哀悼期 = 11；无关者哨兵 -1；已哀悼者保持 12 ──
        assertEquals("道侣哀悼", 11, tables.griefEndYears.getOrDefault(2, -1))
        assertEquals("子女哀悼（死者是父/母）", 11, tables.griefEndYears.getOrDefault(3, -1))
        assertEquals("子女哀悼2", 11, tables.griefEndYears.getOrDefault(4, -1))
        assertEquals("死者父母哀悼", 11, tables.griefEndYears.getOrDefault(5, -1))
        assertEquals("手足哀悼", 11, tables.griefEndYears.getOrDefault(6, -1))
        assertEquals("无关者无哀悼", -1, tables.griefEndYears.getOrDefault(7, -1))
        assertEquals("已哀悼者保持原值", 12, tables.griefEndYears.getOrDefault(8, -1))

        // ── 生命事件（grievingAge = 老化前年龄；死者父母收到"亲属"——第 4 分支不可达的现状行为）──
        val expected2 = "50岁：因道侣寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected3 = "30岁：因父/母寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected4 = "28岁：因父/母寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected5 = "99岁：因亲属寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected6 = "40岁：因亲属寿终老人离世陷入悲痛，修炼速度降低50%"
        assertEquals(listOf(expected2), tables.lifeEvents.getOrDefault(2, emptyList()))
        assertEquals(listOf(expected3), tables.lifeEvents.getOrDefault(3, emptyList()))
        assertEquals(listOf(expected4), tables.lifeEvents.getOrDefault(4, emptyList()))
        assertEquals(listOf(expected5), tables.lifeEvents.getOrDefault(5, emptyList()))
        assertEquals(listOf(expected6), tables.lifeEvents.getOrDefault(6, emptyList()))
        assertEquals("无关者无事件", emptyList<String>(), tables.lifeEvents.getOrDefault(7, emptyList()))
        assertEquals("已哀悼者无新事件", emptyList<String>(), tables.lifeEvents.getOrDefault(8, emptyList()))

        // ── 解绑 ──
        assertNull("道侣关系解绑", tables.partnerIds.getOrNull(2))
    }

    @Test
    fun `aged death - existing longer grief kept and no new event`() = runTest {
        insertDisciple(1, name = "寿终老人", age = 79, lifespan = 80)
        insertDisciple(9, name = "丧亲者", age = 40, social = SocialData(parentId1 = "1", griefEndYear = 15))
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        assertEquals("更长哀悼期保持（max 语义）", 15, tables.griefEndYears.getOrDefault(9, -1))
        assertEquals("已哀悼者无新事件", emptyList<String>(), tables.lifeEvents.getOrDefault(9, emptyList()))
    }

    @Test
    fun `aged death - dead disciples do not grieve`() = runTest {
        insertDisciple(1, name = "寿终老人", age = 79, lifespan = 80)
        insertDisciple(10, name = "亡者之灵", age = 60, social = SocialData(parentId1 = "1"))
        tables.isAlive[10] = 0 // 已死弟子不哀悼
        disciplesFlow.value = tables.assembleAll()

        processor.processDiscipleAging(currentYear = 10)

        assertEquals("已死弟子无哀悼", -1, tables.griefEndYears.getOrDefault(10, -1))
        assertEquals("已死弟子无事件", emptyList<String>(), tables.lifeEvents.getOrDefault(10, emptyList()))
    }
}

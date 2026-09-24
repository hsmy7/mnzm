package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 死亡/哀悼流水线等价性安全网（回归夹具）。
 *
 * 断言当前产出状态（griefEndYears 列 / lifeEvents 文本 / 死亡标记 / 解绑），
 * 本测试持续为绿 = 列直写等价性成立。
 *
 * 现状行为约定（重构必须保持）：
 * - 死亡入口 = handleDiscipleDeath 非战斗链（写死亡标记/哀悼传播/解绑，行保留）
 * - 哀悼期 = 死亡当年 + 1，取 max（已更长则保留）
 * - 事件只对"新进入哀悼"者生成（originalList 中已哀悼者无新事件，即使 griefEndYear 被刷新）
 * - `deduceRelationship` 第 4 分支"子女"实际不可达（`==` 对称与第 3 分支相同条件）：
 *   死者父母（grieving 是死者子女的父/母）收到的是"亲属"文本——预存缺陷，等价性测试按现状断言
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
class DeathPipelineEquivalenceTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var processor: DiscipleLifecycleProcessor

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables
        store.setGameData(GameData(gameYear = 10))

        processor = DiscipleLifecycleProcessor(
            stateStore = mockStore,
            scopeProvider = mockSmart(CoroutineScopeProvider::class.java),
            productionCoordinator = mockSmart(
                com.xianxia.sect.core.engine.domain.production.ProductionCoordinator::class.java
            ),
            eventBus = mockSmart(EventBusPort::class.java),
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            discipleStatusService = mockSmart(DiscipleStatusService::class.java),
            ioDispatcher = IoDispatcher(),
            inventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
                stateStore = mockStore,
                inventoryConfig = InventoryConfig(),
            ),
            // 统一死亡入口：真实实例（markDead 写 isAlive=0 + status=DEAD + deathYear）
            deathHandler = DiscipleDeathHandler()
        )
    }

    // ==================== 辅助 ====================

    private fun insertDisciple(
        id: Int,
        name: String = "弟子$id",
        realm: Int = 9,
        realmLayer: Int = 3,
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

    // ==================== 用例 ====================

    @Test
    fun `handleDiscipleDeath - grief end years and bereavement events for all relative types`() = runTest {
        // 死者 id=1：配偶 2 / 子女 3,4（死者是 3,4 的父/母）/ 父母 5（死者是 5 的子女）/ 手足 6（共同 parentId "99"）
        insertDisciple(
            1, name = "寿终老人",
            social = SocialData(partnerId = "2", parentId1 = "5", parentId2 = "99")
        )
        insertDisciple(2, name = "未亡人", social = SocialData(partnerId = "1"))
        insertDisciple(3, name = "大孝子", social = SocialData(parentId1 = "1"))
        insertDisciple(4, name = "小孝女", social = SocialData(parentId2 = "1"))
        insertDisciple(5, name = "老翁")
        insertDisciple(6, name = "手足", social = SocialData(parentId1 = "99"))
        insertDisciple(7, name = "路人")
        insertDisciple(8, name = "已哀悼者", social = SocialData(griefEndYear = 12))

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = false)

        // ── 死亡标记（行保留，isAlive=0 + deathYear）──
        assertEquals("死者标记死亡", 0, tables.isAlive[1])
        assertEquals("死亡状态", DiscipleStatus.DEAD, tables.statuses[1])
        assertEquals("死亡年份", 10, tables.deathYears[1])
        assertEquals("年度死亡计数", 1, mockStore.gameData.value.annualDeceasedDisciples)
        assertEquals("死者自身无哀悼条目", -1, tables.griefEndYears.getOrDefault(1, -1))

        // ── griefEndYears：哀悼期 = 11；无关者哨兵 -1；已哀悼者保持 12 ──
        assertEquals("道侣哀悼", 11, tables.griefEndYears.getOrDefault(2, -1))
        assertEquals("子女哀悼（死者是父/母）", 11, tables.griefEndYears.getOrDefault(3, -1))
        assertEquals("子女哀悼2", 11, tables.griefEndYears.getOrDefault(4, -1))
        assertEquals("死者父母哀悼", 11, tables.griefEndYears.getOrDefault(5, -1))
        assertEquals("手足哀悼", 11, tables.griefEndYears.getOrDefault(6, -1))
        assertEquals("无关者无哀悼", -1, tables.griefEndYears.getOrDefault(7, -1))
        assertEquals("已哀悼者保持原值", 12, tables.griefEndYears.getOrDefault(8, -1))

        // ── 生命事件（死者父母收到"亲属"——第 4 分支不可达的现状行为）──
        val expected2 = "因道侣寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected3 = "因父/母寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected4 = "因父/母寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected5 = "因亲属寿终老人离世陷入悲痛，修炼速度降低50%"
        val expected6 = "因亲属寿终老人离世陷入悲痛，修炼速度降低50%"
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
    fun `handleDiscipleDeath - existing longer grief kept and no new event`() = runTest {
        insertDisciple(1, name = "寿终老人")
        insertDisciple(9, name = "丧亲者", social = SocialData(parentId1 = "1", griefEndYear = 15))

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = false)

        assertEquals("更长哀悼期保持（max 语义）", 15, tables.griefEndYears.getOrDefault(9, -1))
        assertEquals("已哀悼者无新事件", emptyList<String>(), tables.lifeEvents.getOrDefault(9, emptyList()))
    }

    @Test
    fun `handleDiscipleDeath - dead disciples do not grieve`() = runTest {
        insertDisciple(1, name = "寿终老人")
        insertDisciple(10, name = "亡者之灵", social = SocialData(parentId1 = "1"))
        tables.isAlive[10] = 0 // 已死弟子不哀悼

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = false)

        assertEquals("已死弟子无哀悼", -1, tables.griefEndYears.getOrDefault(10, -1))
        assertEquals("已死弟子无事件", emptyList<String>(), tables.lifeEvents.getOrDefault(10, emptyList()))
    }
}

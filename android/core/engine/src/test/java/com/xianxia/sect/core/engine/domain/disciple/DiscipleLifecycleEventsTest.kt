package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.mockSmart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 验证 [DiscipleLifecycleManager.initializeLifeEvents] 的合成历史事件生成逻辑。
 *
 * 该功能为旧存档首次查看弟子详情时生成加入宗门/拜师的合成日志，
 * 仅当尚无日志时写入。使用 delegate mock 模式（同 DiscipleServiceCrudTest）注入 GameStateStore。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DiscipleLifecycleEventsTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var lifecycleManager: DiscipleLifecycleManager

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables

        // ProductionSlotRepository 是 final 类：mock 拦截依赖类加载时机（顺序敏感 flaky），
        // 用真实实例 + mockSmart 端口（getSlots() 真实返回空列表，语义与 mock 时代一致）
        val productionRepo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val slotManager = DiscipleSlotManager(
            stateStore = mockStore,
            productionSlotRepository = productionRepo,
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            discipleStatusServiceProvider = javax.inject.Provider { mockSmart() },
            ioDispatcher = IoDispatcher()
        )
        lifecycleManager = DiscipleLifecycleManager(
            stateStore = mockStore,
            slotManager = slotManager,
            productionSlotRepository = mockSmart(),
        )
    }

    // ==================== 辅助方法 ====================

    private fun insertDisciple(id: Int, name: String = "弟子$id") {
        tables.insert(Disciple(id = id.toString(), name = name))
    }

    private fun setGameTime(year: Int, month: Int) {
        mockStore.update { gameData = gameData.copy(gameYear = year, gameMonth = month) }
    }

    // ═══════════════════════════════════════════════════════════════
    // 无招募时间/无师父 — 不写入任何事件
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initializeLifeEvents - no recruit and no master writes nothing`() {
        insertDisciple(1)

        lifecycleManager.initializeLifeEvents("1")

        val events = tables.lifeEvents.getOrNull(1)
        assertTrue("no events should be written, but got: $events", events == null || events.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 加入宗门事件 — recruitedMonth 早于当前月才生成
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initializeLifeEvents - generates joined sect event`() {
        insertDisciple(1)
        setGameTime(year = 2, month = 1) // currentAbsoluteMonth = 25
        tables.recruitedMonths[1] = 13   // 第1年1月加入 → 早于当月

        lifecycleManager.initializeLifeEvents("1")

        val events = tables.lifeEvents.getOrNull(1)
        assertNotNull("events should be generated", events)
        assertTrue("joined event missing: $events", events!!.contains("加入宗门"))
    }

    @Test
    fun `initializeLifeEvents - joined sect event when recruited this month is skipped`() {
        insertDisciple(1)
        setGameTime(year = 1, month = 1) // currentAbsoluteMonth = 13
        tables.recruitedMonths[1] = 13   // 当月加入 → currentAbsoluteMonth > recruitedMonth 不成立

        lifecycleManager.initializeLifeEvents("1")

        val events = tables.lifeEvents.getOrNull(1)
        assertTrue("no joined event when recruited in current month: $events", events == null || events.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 拜师事件 — 师父名从名字表解析
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initializeLifeEvents - generates master event with master name`() {
        insertDisciple(1)
        insertDisciple(2, name = "玄机真人")
        tables.masterIds[1] = "2"

        lifecycleManager.initializeLifeEvents("1")

        val events = tables.lifeEvents.getOrNull(1)
        assertNotNull("events should be generated", events)
        assertTrue("master event missing: $events", events!!.contains("拜玄机真人为师"))
    }

    // ═══════════════════════════════════════════════════════════════
    // 幂等 — 已有日志时不覆盖、不重复生成
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initializeLifeEvents - does not overwrite existing events`() {
        insertDisciple(1)
        tables.lifeEvents[1] = listOf("加入宗门")

        lifecycleManager.initializeLifeEvents("1")

        val events = tables.lifeEvents.getOrNull(1)
        assertEquals("existing events preserved", listOf("加入宗门"), events)
    }

    // ═══════════════════════════════════════════════════════════════
    // 无效输入 — 非数字 id / 不存在的弟子
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initializeLifeEvents - invalid id ignored`() {
        insertDisciple(1)

        lifecycleManager.initializeLifeEvents("abc") // 非数字
        lifecycleManager.initializeLifeEvents("999") // 不存在

        val events = tables.lifeEvents.getOrNull(1)
        assertTrue("no events should be written, but got: $events", events == null || events.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // 综合 — 全部事件按序生成（加入宗门 → 拜师）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `initializeLifeEvents - generates join and master events in order`() {
        insertDisciple(1)
        insertDisciple(2, name = "玄机真人")
        setGameTime(year = 2, month = 1)
        tables.recruitedMonths[1] = 13
        tables.masterIds[1] = "2"

        lifecycleManager.initializeLifeEvents("1")

        val events = tables.lifeEvents.getOrNull(1)
        assertEquals(
            listOf("加入宗门", "拜玄机真人为师"),
            events
        )
    }
}

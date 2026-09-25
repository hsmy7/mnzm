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
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 死亡流水线等价性安全网（回归夹具）。
 *
 * 断言当前产出状态（死亡标记 / 年度死亡计数 / 宗门死亡事件），
 * 本测试持续为绿 = 列直写等价性成立。
 *
 * 现状行为约定（重构必须保持）：
 * - 死亡入口 = handleDiscipleDeath 非战斗链（写死亡标记，行保留）
 * - 战斗链入口（isOutsideSect = true）只钳重伤，不写死亡三元组、不计年报
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
            skills = skills
        )
        tables.insert(disciple)
        if (!skipTablesIsAlive) {
            tables.isAlive[id] = 1
        }
    }

    // ==================== 用例 ====================

    @Test
    fun `handleDiscipleDeath - death markers and sect death event`() = runTest {
        insertDisciple(1, name = "寿终老人")
        insertDisciple(5, name = "在世弟子")

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = false)

        // ── 死亡标记（行保留，isAlive=0 + status + deathYear）──
        assertEquals("死者标记死亡", 0, tables.isAlive[1])
        assertEquals("死亡状态", DiscipleStatus.DEAD, tables.statuses[1])
        assertEquals("死亡年份", 10, tables.deathYears[1])
        assertEquals("年度死亡计数", 1, mockStore.gameData.value.annualDeceasedDisciples)
        assertEquals("无关在世弟子不受影响", 1, tables.isAlive[5])

        // ── 宗门死亡事件（非战斗链必记一条 SECT/DEATH）──
        val deathEvents = mockStore.gameData.value.gameEventRecords
            .filter { it.eventType == GameEventType.DEATH }
        assertEquals("宗门死亡事件条数", 1, deathEvents.size)
        assertEquals("宗门死亡事件主角", "1", deathEvents.first().relatedEntityId)
    }

    @Test
    fun `handleDiscipleDeath - combat path only injures and never marks death`() = runTest {
        insertDisciple(1, name = "出战弟子")

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = true)

        // G07：战斗败北只钳重伤，不写死亡三元组、不计年报
        assertEquals("出战弟子保持存活", 1, tables.isAlive[1])
        assertEquals("重伤气血为 1", 1, tables.currentHps[1])
        assertEquals("年报死亡计数不动", 0, mockStore.gameData.value.annualDeceasedDisciples)
    }
}

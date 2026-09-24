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
 * 死亡流水线等价性安全网（回归夹具）。
 *
 * 断言当前产出状态（死亡标记 / 年度死亡计数 / 师徒解绑 / 宗门死亡事件），
 * 本测试持续为绿 = 列直写等价性成立。
 *
 * 现状行为约定（重构必须保持）：
 * - 死亡入口 = handleDiscipleDeath 非战斗链（写死亡标记/师徒解绑，行保留）
 * - 师徒解绑单向：只清空「师父指向死者」的徒弟行，死者自身的 masterId 不动
 * - 无关弟子的师徒绑定不受死亡事件影响
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
    fun `handleDiscipleDeath - death markers and master-apprentice unbinding`() = runTest {
        // 死者 id=1：师父 9 / 徒弟 2,3（死者是他们师父）/ 同门 4（师父是 9，与死者无关）
        insertDisciple(1, name = "寿终老人", social = SocialData(masterId = "9"))
        insertDisciple(2, name = "大徒弟", social = SocialData(masterId = "1"))
        insertDisciple(3, name = "小徒弟", social = SocialData(masterId = "1"))
        insertDisciple(4, name = "旁支", social = SocialData(masterId = "9"))
        insertDisciple(5, name = "路人")
        insertDisciple(9, name = "师父")

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = false)

        // ── 死亡标记（行保留，isAlive=0 + status + deathYear）──
        assertEquals("死者标记死亡", 0, tables.isAlive[1])
        assertEquals("死亡状态", DiscipleStatus.DEAD, tables.statuses[1])
        assertEquals("死亡年份", 10, tables.deathYears[1])
        assertEquals("年度死亡计数", 1, mockStore.gameData.value.annualDeceasedDisciples)

        // ── 宗门死亡事件（非战斗链必记一条 SECT/DEATH）──
        val deathEvents = mockStore.gameData.value.gameEventRecords
            .filter { it.eventType == GameEventType.DEATH }
        assertEquals("宗门死亡事件条数", 1, deathEvents.size)
        assertEquals("宗门死亡事件主角", "1", deathEvents.first().relatedEntityId)

        // ── 解绑：指向死者的徒弟全部清空，其余师徒绑定保持 ──
        assertNull("徒弟2与死者师徒关系解绑", tables.masterIds.getOrNull(2))
        assertNull("徒弟3与死者师徒关系解绑", tables.masterIds.getOrNull(3))
        assertEquals("无关弟子绑定不动", "9", tables.masterIds.getOrNull(4))
        assertEquals("死者自身绑定保留（行保留语义）", "9", tables.masterIds.getOrNull(1))
    }

    @Test
    fun `handleDiscipleDeath - combat path only injures and never unbinds`() = runTest {
        insertDisciple(1, name = "出战弟子", social = SocialData(masterId = "9"))
        insertDisciple(2, name = "徒弟", social = SocialData(masterId = "1"))

        processor.handleDiscipleDeath(tables.assemble(1), isOutsideSect = true)

        // G07：战斗败北只钳重伤，不写死亡三元组、不解绑、不计年报
        assertEquals("出战弟子保持存活", 1, tables.isAlive[1])
        assertEquals("重伤气血为 1", 1, tables.currentHps[1])
        assertEquals("年报死亡计数不动", 0, mockStore.gameData.value.annualDeceasedDisciples)
        assertEquals("师徒绑定不解绑", "1", tables.masterIds.getOrNull(2))
    }
}

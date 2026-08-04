package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * 叛逃判定范围测试：弟子随时可叛逃（除战斗/任务/血炼/思过中）。
 *
 * 根因回归：叛逃候选曾仅限 IDLE 状态，导致"卸任/更换 → IDLE → 月结叛逃永久删除"，
 * 且叛逃不写消息栏事件。本测试使用真实 update 执行的 delegate mock store，
 * 验证工作状态弟子参与判定、免疫状态不参与、以及叛逃/捕获事件写入。
 */
@RunWith(RobolectricTestRunner::class)
class LawEnforcementDesertionScopeTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    @Test
    fun `叛逃范围 - 工作状态弟子参与判定并被删除`() {
        val id1 = 1; val id2 = 2
        val tables = makeDesertionTables(id1, DiscipleStatus.PATROLLING, loyalty = 0).also {
            addIdleDisciple(it, id2, "弟子$id2", loyalty = 30)
        }
        val gd = GameData(spiritStones = 1_000_000L, gameYear = 10, gameMonth = 6)
        val (mockStore, state) = makeExecutingStore(gd, tables)
        val rng = GameRngManager()
        rng.initSystemSeed(14L)
        val proc = LawEnforcementProcessor(mockStore, rng,
            Mockito.mock(DiscipleLifecycleProcessor::class.java), LootCalculator(GameRngManager()))
        proc.processLawEnforcementMonthly()
        // 平均忠诚 (0+30)/2=15 < 50 门控通过；巡逻中弟子忠诚0应参与判定并被删除
        assertFalse("巡逻中弟子(忠诚0)应被叛逃移除", tables.ids.contains(id1))
        assertTrue("空闲弟子(忠诚30)不受影响", tables.ids.contains(id2))
        assertEquals(1, state.gameData.annualDesertedDisciples)
        // 消息栏事件：脱离宗门
        val desertionEvent = state.gameData.gameEventRecords.lastOrNull { it.eventType == GameEventType.DESERTION }
        assertNotNull("应写入脱离宗门事件", desertionEvent)
        assertTrue("事件摘要应含弟子名", desertionEvent!!.summary.contains("弟子1"))
    }

    @Test
    fun `叛逃范围 - 任务中弟子不参与判定`() {
        assertImmuneToDesertion(DiscipleStatus.ON_MISSION)
    }

    @Test
    fun `叛逃范围 - 思过中弟子不参与判定`() {
        assertImmuneToDesertion(DiscipleStatus.REFLECTING)
    }

    @Test
    fun `叛逃范围 - 血炼中弟子不参与判定`() {
        assertImmuneToDesertion(DiscipleStatus.REFINING)
    }

    @Test
    fun `叛逃范围 - 队伍中弟子不参与判定`() {
        assertImmuneToDesertion(DiscipleStatus.IN_TEAM)
    }

    @Test
    fun `叛逃捕获 - 写入捕获消息栏事件`() {
        val id1 = 1; val id2 = 2
        val tables = makeDesertionTables(id1, DiscipleStatus.IDLE, loyalty = 0).also {
            addIdleDisciple(it, id2, "执法长老", loyalty = 30)
            it.intelligences[id2] = 1000 // 长老智力极高 → captureRate 达上限 1.0，必捕获
        }
        val gd = GameData(spiritStones = 1_000_000L, gameYear = 10, gameMonth = 6).apply {
            elderSlots = elderSlots.copy(lawEnforcementElder = id2.toString())
        }
        val elder = tables.assemble(id2)
        assertNotNull("长老应可组装", elder)
        val (mockStore, state) = makeExecutingStore(gd, tables, disciples = listOf(elder!!))
        val rng = GameRngManager()
        rng.initSystemSeed(14L)
        val proc = LawEnforcementProcessor(mockStore, rng,
            Mockito.mock(DiscipleLifecycleProcessor::class.java), LootCalculator(GameRngManager()))
        proc.processLawEnforcementMonthly()
        // captureRate=1.0 → 叛逃弟子必被捕获进思过，而非删除
        assertTrue("被捕弟子应仍存在", tables.ids.contains(id1))
        assertEquals("被捕后状态思过", DiscipleStatus.REFLECTING, tables.statuses[id1])
        val caughtEvent = state.gameData.gameEventRecords.lastOrNull { it.eventType == GameEventType.DESERTION_CAUGHT }
        assertNotNull("应写入捕获事件", caughtEvent)
    }

    private fun assertImmuneToDesertion(status: DiscipleStatus) {
        val id1 = 1; val id2 = 2
        val tables = makeDesertionTables(id1, status, loyalty = 0).also {
            addIdleDisciple(it, id2, "弟子$id2", loyalty = 30)
        }
        val gd = GameData(spiritStones = 1_000_000L, gameYear = 10, gameMonth = 6)
        val (mockStore, state) = makeExecutingStore(gd, tables)
        val proc = LawEnforcementProcessor(mockStore, GameRngManager(),
            Mockito.mock(DiscipleLifecycleProcessor::class.java), LootCalculator(GameRngManager()))
        proc.processLawEnforcementMonthly()
        assertTrue("$status 弟子不应参与叛逃判定", tables.ids.contains(id1))
        assertEquals(0, state.gameData.annualDesertedDisciples)
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    /**
     * 构造执行真实 update 的 delegate mock store（同 DiscipleReflectionReleaseTest 模式）。
     * update/updateAndReturn 在 [makeState] 创建的 MutableGameState 上真实执行，
     * 使叛逃删除/事件写入可被断言。
     */
    private fun makeExecutingStore(gd: GameData, tables: DiscipleTables,
                                   disciples: List<Disciple> = emptyList()): Pair<GameStateStore, MutableGameState> {
        val state = makeState(gd, tables)
        val delegate = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(delegate.discipleTables).thenReturn(tables)
        Mockito.`when`(delegate.gameData).thenReturn(MutableStateFlow(gd))
        Mockito.`when`(delegate.disciples).thenReturn(MutableStateFlow(disciples))
        Mockito.`when`(delegate.equipmentStacks).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.equipmentInstances).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.manualStacks).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.manualInstances).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.pills).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.materials).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.herbs).thenReturn(MutableStateFlow(emptyList()))
        Mockito.`when`(delegate.seeds).thenReturn(MutableStateFlow(emptyList()))
        val store = object : GameStateStore by delegate {
            override val discipleTables: DiscipleTables
                get() = tables

            override fun update(
                block: MutableGameState.() -> Unit
            ) {
                block.invoke(state)
            }

            override fun <R> updateAndReturn(
                block: MutableGameState.() -> R
            ): R {
                return block.invoke(state)
            }
        }
        return store to state
    }

    /** 构造叛逃候选弟子（insert 全列写入，确保组件表完整、remove/insert 一致性检查通过）。 */
    private fun makeDesertionTables(id: Int, status: DiscipleStatus, loyalty: Int = 0): DiscipleTables {
        val t = DiscipleTables()
        t.insert(Disciple(id = id.toString(), name = "弟子$id", age = 30))
        t.isAlive[id] = 1; t.statuses[id] = status
        t.moralities[id] = 0; t.loyalties[id] = loyalty
        t.recruitedMonths[id] = 24
        t.intelligences[id] = 100; t.baseSpeeds[id] = 100
        t.realms[id] = 5; t.realmLayers[id] = 1
        return t
    }

    /** 追加一个组件完整的空闲弟子。 */
    private fun addIdleDisciple(t: DiscipleTables, id: Int, name: String, loyalty: Int) {
        t.insert(Disciple(id = id.toString(), name = name, age = 30))
        t.isAlive[id] = 1; t.statuses[id] = DiscipleStatus.IDLE
        t.loyalties[id] = loyalty; t.recruitedMonths[id] = 24
    }

    private fun makeState(gd: GameData, tables: DiscipleTables,
                          materials: EntityStore<Material> = EntityStore()): MutableGameState {
        return MutableGameState(gd, tables,
            EntityStore(), EntityStore(), EntityStore(), EntityStore(),
            EntityStore(), materials, EntityStore(), EntityStore(), EntityStore(),
            emptyList(), emptyList(), false, false, false)
    }
}

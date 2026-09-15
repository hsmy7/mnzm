package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.di.ApplicationScopeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GameStateStoreReverseDirtyTest — 反向增量通道事务级捕获。
 *
 * 覆盖：弟子列写捕获 / gameData 引用捕获 / 集合引用捕获（upsert+removed）/
 * 多事务窗口累积 / consume 清空 / reset 清窗 / remove 记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreReverseDirtyTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var stateStore: GameStateStoreImpl

    @Before
    fun setUp() {
        // 弟子通道已随 w3-13 关闭（handover §2.76 续批）；本类守护**捕获机械**
        // 语义（弟子列写 → changed id → 快照），经覆盖钩子恢复传输前提。
        ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.DISCIPLE)
        stateStore = GameStateStoreImpl(
            applicationScopeProvider = ApplicationScopeProvider(),
            repository = testGameStateRepository()
        )
        stateStore.unsafeAllowMainThreadUpdateForTest = true
    }

    @After
    fun tearDown() {
        ReverseChannelPolicy.resetSwitches()
    }

    private fun makeDisciple(id: Int): Disciple = Disciple(
        id = id.toString(),
        name = "弟子$id",
        realm = 9,
        cultivation = 10.0,
        skills = SkillStats(loyalty = 50)
    )

    @Test
    fun `disciple column write captured as changed id`() {
        stateStore.update { discipleTables.insert(makeDisciple(1)) }
        stateStore.resetReverseAccumulator()
        stateStore.update { discipleTables.cultivations[1] = 99.0 }

        val snap = stateStore.consumeReverseDirty()
        assertTrue(snap != null)
        assertTrue("弟子 1 应被捕获", snap!!.discipleIds.contains(1))
        assertFalse("纯弟子列写不应触发 gameData 通道", snap.gameDataChanged)
        assertTrue(snap.collections.isEmpty())
        // 消费后清空
        assertNull(stateStore.consumeReverseDirty())
    }

    @Test
    fun `gameData change captured`() {
        stateStore.update { gameData = gameData.copy(spiritStones = 777) }
        val snap = stateStore.consumeReverseDirty()
        assertTrue(snap != null)
        assertTrue(snap!!.gameDataChanged)
        assertTrue(snap.discipleIds.isEmpty())
    }

    @Test
    fun `collection change captured with upsert and removed ids`() {
        stateStore.update { pills.add(Pill(id = "p1", name = "丹", quantity = 1)) }
        stateStore.resetReverseAccumulator()

        // 事务 1：新增 p2；事务 2：移除 p1（窗口内累积）
        stateStore.update { pills.add(Pill(id = "p2", name = "丹2", quantity = 1)) }
        stateStore.update { pills.remove("p1") }

        val snap = stateStore.consumeReverseDirty()
        assertTrue(snap != null)
        val pills = snap!!.collections["pills"]
        assertTrue("pills 集合应被捕获", pills != null)
        // 最近一次捕获：upsert 当前全量（含 p2），removed 含 p1
        assertEquals(listOf("p2"), pills!!.upserts.map { it.id })
        assertEquals(setOf("p1"), pills.removedIds)
    }

    @Test
    fun `multiple transactions accumulate into one snapshot`() {
        stateStore.update { discipleTables.insert(makeDisciple(1)) }
        stateStore.resetReverseAccumulator()
        stateStore.update { discipleTables.cultivations[1] = 11.0 }
        stateStore.update { discipleTables.cultivations[1] = 12.0 }
        stateStore.update { gameData = gameData.copy(spiritStones = 100) }

        val snap = stateStore.consumeReverseDirty()
        assertTrue(snap != null)
        assertTrue(snap!!.discipleIds.contains(1))
        assertTrue(snap.gameDataChanged)
    }

    @Test
    fun `remove disciple recorded in window`() {
        stateStore.update { discipleTables.insert(makeDisciple(1)) }
        stateStore.resetReverseAccumulator()
        stateStore.update { discipleTables.remove(1) }

        val snap = stateStore.consumeReverseDirty()
        assertTrue(snap != null)
        // remove 记录 changedId——消费方（StateSyncService）核对表内无此 id 后进 removed
        assertTrue(snap!!.discipleIds.contains(1))
    }

    @Test
    fun `reset clears the window`() {
        stateStore.update { gameData = gameData.copy(spiritStones = 1) }
        stateStore.resetReverseAccumulator()
        assertNull(stateStore.consumeReverseDirty())
    }

    @Test
    fun `closed collection is not captured and its write is detected`() {
        // 逐域关闭（batch-21）：关闭集合不构造捕获载荷（省 O(n) 差集与实体序列化）；
        // 关闭后仍有 Kotlin 写者 = 回导缺口，必须可从诊断面观测
        ReverseChannelPolicy.overrideClosedUnitsForTest(
            listOf(
                ReverseChannelPolicy.ClosedUnit(
                    domain = ReverseChannelPolicy.Domain.INVENTORY,
                    kind = ReverseChannelPolicy.Kind.COLLECTION,
                    name = "pills",
                )
            )
        )
        try {
            stateStore.update { pills.add(Pill(id = "p1", name = "丹", quantity = 1)) }
            assertNull("关闭集合的写入不得进入捕获快照", stateStore.consumeReverseDirty())
            assertTrue(
                "关闭集合被写入必须被检测",
                ReverseChannelPolicy.closedWriteRecordsSnapshot().any { it.contains("pills") }
            )
        } finally {
            ReverseChannelPolicy.resetSwitches()
        }
    }

    @Test
    fun `reopened domain restores capture`() {
        ReverseChannelPolicy.overrideClosedUnitsForTest(
            listOf(
                ReverseChannelPolicy.ClosedUnit(
                    domain = ReverseChannelPolicy.Domain.INVENTORY,
                    kind = ReverseChannelPolicy.Kind.COLLECTION,
                    name = "pills",
                )
            )
        )
        try {
            ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.INVENTORY)
            stateStore.update { pills.add(Pill(id = "p1", name = "丹", quantity = 1)) }
            val snap = stateStore.consumeReverseDirty()
            assertTrue("回滚该域后集合捕获必须恢复", snap?.collections?.containsKey("pills") == true)
        } finally {
            ReverseChannelPolicy.resetSwitches()
        }
    }
}

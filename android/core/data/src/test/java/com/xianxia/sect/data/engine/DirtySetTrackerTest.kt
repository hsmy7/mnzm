package com.xianxia.sect.data.engine

import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.state.SaveDirtySet
import com.xianxia.sect.core.state.SaveDirtyTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DirtySetTracker 单测（SS5）：三种脏集形状表达、move 语义、代序号结算
 * （飞行期再变更不清除）、失败回并、溢出与全量标记、路径判定越界回退。
 */
class DirtySetTrackerTest {

    // ── 三种脏集形状（验收①） ──

    @Test
    fun `新增_修改_删除三种形状可表达`() {
        val tracker = DirtySetTracker()
        // 新增/修改：行级 upsert id
        tracker.recordEntityUpserts(SaveDirtyTables.MATERIALS, listOf("m1", "m2"))
        // 删除：不馈送 id，由增量保存的「已落盘 ↔ 快照」id 对账承载
        tracker.recordTableRewrite(SaveDirtyTables.RECIPES)
        // heavy：key 粒度
        tracker.recordHeavyKeys(listOf(GameHeavyData.KEY_EXPLORED_SECTS))

        val delta = requireNotNull(tracker.captureDeltaForSave())
        assertEquals(setOf("m1", "m2"), delta.upsertIds[SaveDirtyTables.MATERIALS])
        assertEquals(setOf(SaveDirtyTables.RECIPES), delta.rewriteTables)
        assertEquals(setOf(GameHeavyData.KEY_EXPLORED_SECTS), delta.heavyKeys)
    }

    @Test
    fun `空馈送交出空脏集`() {
        val tracker = DirtySetTracker()
        assertEquals(SaveDirtySet.EMPTY, tracker.captureDeltaForSave())
    }

    // ── move 语义 + 代序号结算 ──

    @Test
    fun `捕获为move语义_再次捕获只含新馈送`() {
        val tracker = DirtySetTracker()
        tracker.recordEntityUpserts(SaveDirtyTables.PILLS, listOf("p1"))
        val first = requireNotNull(tracker.captureDeltaForSave())
        assertEquals(setOf("p1"), first.upsertIds[SaveDirtyTables.PILLS])

        tracker.recordEntityUpserts(SaveDirtyTables.PILLS, listOf("p2"))
        val second = requireNotNull(tracker.captureDeltaForSave())
        assertEquals(setOf("p2"), second.upsertIds[SaveDirtyTables.PILLS])
    }

    @Test
    fun `成功结算只清除捕获后未再变更的条目`() {
        val tracker = DirtySetTracker()
        tracker.recordEntityUpserts(SaveDirtyTables.MATERIALS, listOf("m1", "m2"))
        val delta = requireNotNull(tracker.captureDeltaForSave())
        // 飞行期间 m2 再次变更（代序号前进）
        tracker.recordEntityUpserts(SaveDirtyTables.MATERIALS, listOf("m2"))

        tracker.settleSaveResult(delta, success = true, writtenViaFull = false)

        assertTrue(tracker.hasPendingChanges())
        val next = requireNotNull(tracker.captureDeltaForSave())
        assertEquals(setOf("m2"), next.upsertIds[SaveDirtyTables.MATERIALS])
    }

    @Test
    fun `失败结算整组回并且幂等`() {
        val tracker = DirtySetTracker()
        tracker.recordHeavyKeys(listOf(GameHeavyData.KEY_SECT_DETAILS))
        val delta = requireNotNull(tracker.captureDeltaForSave())

        tracker.settleSaveResult(delta, success = false, writtenViaFull = false)
        tracker.settleSaveResult(delta, success = false, writtenViaFull = false)

        val merged = requireNotNull(tracker.captureDeltaForSave())
        assertEquals(setOf(GameHeavyData.KEY_SECT_DETAILS), merged.heavyKeys)
    }

    @Test
    fun `成功结算建立基线_此后不再要求全量`() {
        val tracker = DirtySetTracker()
        assertTrue(tracker.isFullWriteRequired())

        val delta = requireNotNull(tracker.captureDeltaForSave())
        tracker.settleSaveResult(delta, success = true, writtenViaFull = false)

        assertFalse(tracker.isFullWriteRequired())
    }

    // ── 全量标记与复位 ──

    @Test
    fun `状态整体替换清空累积并要求全量`() {
        val tracker = DirtySetTracker()
        tracker.recordEntityUpserts(SaveDirtyTables.SEEDS, listOf("s1"))
        tracker.settleSaveResult(SaveDirtySet.EMPTY, success = true, writtenViaFull = false)
        assertFalse(tracker.isFullWriteRequired())

        tracker.resetForStateReplacement()
        assertTrue(tracker.isFullWriteRequired())
        assertFalse(tracker.hasPendingChanges())
    }

    @Test
    fun `溢出后要求全量_全量成功后复位`() {
        val tracker = DirtySetTracker()
        val oversized = (0 until DirtySetTracker.MAX_DIRTY_IDS_PER_TABLE + 1).map(Int::toString)
        tracker.recordEntityUpserts(SaveDirtyTables.MATERIALS, oversized)
        assertTrue(tracker.isFullWriteRequired())

        tracker.settleSaveResult(null, success = true, writtenViaFull = true)
        assertFalse(tracker.isFullWriteRequired())
    }

    // ── 路径判定（验收⑤） ──

    @Test
    fun `路径判定_无基线走全量`() {
        val decision = resolveSavePath(
            dirtySet = SaveDirtySet.EMPTY,
            hasBaseline = false,
            snapshotIds = emptyMap()
        )
        assertFalse(decision.incremental)
        assertEquals(FullSaveReason.NO_BASELINE, decision.fullSaveReason)
    }

    @Test
    fun `路径判定_脏集缺失走全量`() {
        val decision = resolveSavePath(
            dirtySet = null,
            hasBaseline = true,
            snapshotIds = emptyMap()
        )
        assertFalse(decision.incremental)
        assertEquals(FullSaveReason.NO_DIRTY_SET, decision.fullSaveReason)
    }

    @Test
    fun `路径判定_脏集越界回退全量`() {
        val decision = resolveSavePath(
            dirtySet = SaveDirtySet(upsertIds = mapOf(SaveDirtyTables.PILLS to setOf("ghost-id"))),
            hasBaseline = true,
            snapshotIds = mapOf(SaveDirtyTables.PILLS to setOf("p1", "p2"))
        )
        assertFalse(decision.incremental)
        assertEquals(FullSaveReason.DIRTY_OUT_OF_SNAPSHOT, decision.fullSaveReason)
    }

    @Test
    fun `路径判定_表缺失视为越界`() {
        val decision = resolveSavePath(
            dirtySet = SaveDirtySet(upsertIds = mapOf("unknownTable" to setOf("x"))),
            hasBaseline = true,
            snapshotIds = mapOf(SaveDirtyTables.PILLS to setOf("p1"))
        )
        assertEquals(FullSaveReason.DIRTY_OUT_OF_SNAPSHOT, decision.fullSaveReason)
    }

    @Test
    fun `路径判定_有效脏集走增量`() {
        val dirty = SaveDirtySet(
            upsertIds = mapOf(SaveDirtyTables.PILLS to setOf("p1")),
            rewriteTables = setOf(SaveDirtyTables.RECIPES),
            heavyKeys = setOf(GameHeavyData.KEY_SCOUT_INFO)
        )
        val decision = resolveSavePath(
            dirtySet = dirty,
            hasBaseline = true,
            snapshotIds = mapOf(SaveDirtyTables.PILLS to setOf("p1", "p2"))
        )
        assertTrue(decision.incremental)
        assertNull(decision.fullSaveReason)
        assertEquals(dirty, decision.dirtySet)
    }
}

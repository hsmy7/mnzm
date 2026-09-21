package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 上传落地记账单测（MMKV 记账语义；SR-0 Q6 进程被杀场景的账本面依据）。
 */
class UploadLedgerTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var ledger: UploadLedger

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        ledger = UploadLedger(store)
    }

    @Test
    fun `初始态 - 全零且不脏`() {
        assertEquals(0L, ledger.lastLocalSaveId(1))
        assertEquals(0L, ledger.lastConfirmedCloudId(1))
        assertEquals(0L, ledger.pendingSaveId(1))
        assertFalse(ledger.isLocalDirty(1))
    }

    @Test
    fun `Q1 - 入队保存记录 L 递增且 C 不变`() {
        val first = ledger.recordLocalSave(1)
        val second = ledger.recordLocalSave(1)
        val third = ledger.recordLocalSave(1)
        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(3L, third)
        assertEquals(3L, ledger.lastLocalSaveId(1))
        assertEquals(0L, ledger.lastConfirmedCloudId(1))
        assertEquals(3L, ledger.pendingSaveId(1))
        assertTrue(ledger.isLocalDirty(1))
    }

    @Test
    fun `Q5 - 上传确认推进 C 且清待传`() {
        val id = ledger.recordLocalSave(1)
        ledger.recordCloudConfirmed(1, id)
        assertEquals(id, ledger.lastConfirmedCloudId(1))
        assertEquals(0L, ledger.pendingSaveId(1))
        assertFalse(ledger.isLocalDirty(1))
    }

    @Test
    fun `确认单调不回退 - 旧序号重复确认无副作用（幂等）`() {
        val id4 = ledger.recordLocalSave(1)
        ledger.recordCloudConfirmed(1, id4)
        val id5 = ledger.recordLocalSave(1)
        ledger.recordCloudConfirmed(1, id5)
        // 丢失的旧确认迟到（Q6 幂等重传回执）：C 不得回退
        ledger.recordCloudConfirmed(1, id4)
        assertEquals(id5, ledger.lastConfirmedCloudId(1))
        // 待传指针已随 id5 确认清零，旧确认不得使其复活
        assertEquals(0L, ledger.pendingSaveId(1))
        assertFalse(ledger.isLocalDirty(1))
    }

    @Test
    fun `Q6 - 待传指针持久化在 MMKV（模拟进程被杀后新实例可见）`() {
        val id = ledger.recordLocalSave(1)
        // 进程在上传成功与确认写入之间被杀：C 停留旧值、待传指针已落 MMKV
        val revivedLedger = UploadLedger(store) // 模拟重启后从同一 MMKV 重建
        assertEquals(id, revivedLedger.pendingSaveId(1))
        assertTrue(revivedLedger.isLocalDirty(1))
        assertEquals(0L, revivedLedger.lastConfirmedCloudId(1))
    }

    @Test
    fun `槽位隔离 - 各 slot 记账互不影响（多档共享冷却下的独立账本）`() {
        val slot1Id = ledger.recordLocalSave(1)
        val slot2Id = ledger.recordLocalSave(2)
        assertEquals(slot1Id, ledger.lastLocalSaveId(1))
        assertEquals(slot2Id, ledger.lastLocalSaveId(2))
        ledger.recordCloudConfirmed(2, slot2Id)
        assertTrue(ledger.isLocalDirty(1))
        assertFalse(ledger.isLocalDirty(2))
    }

    @Test
    fun `U10 - normalizeIfNeeded 修复 L小于C 并返回 true（合法态返回 false 无副作用）`() {
        // 构造非法态：确认先于本地序号写入的中断窗
        store.putLong("cloud_upload_ledger_slot1_last_local", 2L)
        store.putLong("cloud_upload_ledger_slot1_last_confirmed", 5L)
        assertTrue(ledger.normalizeIfNeeded(1))
        // 自愈方向 = C 回落到 L（确认写入先于本地序号写入的中断窗，C 是前滚的脏值）
        assertEquals(2L, ledger.lastLocalSaveId(1))
        assertEquals(2L, ledger.lastConfirmedCloudId(1))
        // 合法态：无副作用
        assertFalse(ledger.normalizeIfNeeded(1))
        assertFalse(ledger.normalizeIfNeeded(2))
    }

    @Test
    fun `resetSlot - 清空指定槽位且不影响他槽`() {
        ledger.recordLocalSave(1)
        ledger.recordLocalSave(2)
        ledger.resetSlot(1)
        assertEquals(0L, ledger.lastLocalSaveId(1))
        assertEquals(0L, ledger.pendingSaveId(1))
        assertEquals(1L, ledger.lastLocalSaveId(2))
    }
}

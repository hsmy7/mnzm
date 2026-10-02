package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 上传落地记账单测（MMKV 记账语义；Q6 进程被杀场景的账本面依据）。
 * 云端单档语义下记账为单键（无槽位维度）。
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
        assertEquals(0L, ledger.lastLocalSaveId())
        assertEquals(0L, ledger.lastConfirmedCloudId())
        assertEquals(0L, ledger.pendingSaveId())
        assertFalse(ledger.isLocalDirty())
    }

    @Test
    fun `Q1 - 入队保存记录 L 递增且 C 不变`() {
        val first = ledger.recordLocalSave()
        val second = ledger.recordLocalSave()
        val third = ledger.recordLocalSave()
        assertEquals(1L, first)
        assertEquals(2L, second)
        assertEquals(3L, third)
        assertEquals(3L, ledger.lastLocalSaveId())
        assertEquals(0L, ledger.lastConfirmedCloudId())
        assertEquals(3L, ledger.pendingSaveId())
        assertTrue(ledger.isLocalDirty())
    }

    @Test
    fun `Q5 - 上传确认推进 C 且清待传`() {
        val id = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id)
        assertEquals(id, ledger.lastConfirmedCloudId())
        assertEquals(0L, ledger.pendingSaveId())
        assertFalse(ledger.isLocalDirty())
    }

    @Test
    fun `确认单调不回退 - 旧序号重复确认无副作用（幂等）`() {
        val id4 = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id4)
        val id5 = ledger.recordLocalSave()
        ledger.recordCloudConfirmed(id5)
        // 丢失的旧确认迟到（Q6 幂等重传回执）：C 不得回退
        ledger.recordCloudConfirmed(id4)
        assertEquals(id5, ledger.lastConfirmedCloudId())
        // 待传指针已随 id5 确认清零，旧确认不得使其复活
        assertEquals(0L, ledger.pendingSaveId())
        assertFalse(ledger.isLocalDirty())
    }

    @Test
    fun `Q6 - 待传指针持久化在 MMKV（模拟进程被杀后新实例可见）`() {
        val id = ledger.recordLocalSave()
        // 进程在上传成功与确认写入之间被杀：C 停留旧值、待传指针已落 MMKV
        val revivedLedger = UploadLedger(store) // 模拟重启后从同一 MMKV 重建
        assertEquals(id, revivedLedger.pendingSaveId())
        assertTrue(revivedLedger.isLocalDirty())
        assertEquals(0L, revivedLedger.lastConfirmedCloudId())
    }

    @Test
    fun `U10 - normalizeIfNeeded 修复 L小于C 并返回 true（合法态返回 false 无副作用）`() {
        // 构造非法态：确认先于本地序号写入的中断窗
        store.putLong("cloud_upload_ledger_last_local", 2L)
        store.putLong("cloud_upload_ledger_last_confirmed", 5L)
        assertTrue(ledger.normalizeIfNeeded())
        // 自愈方向 = C 回落到 L（确认写入先于本地序号写入的中断窗，C 是前滚的脏值）
        assertEquals(2L, ledger.lastLocalSaveId())
        assertEquals(2L, ledger.lastConfirmedCloudId())
        // 合法态：无副作用
        assertFalse(ledger.normalizeIfNeeded())
    }

    @Test
    fun `reset - 清空全部记账键`() {
        ledger.recordLocalSave()
        ledger.recordLocalSave()
        ledger.reset()
        assertEquals(0L, ledger.lastLocalSaveId())
        assertEquals(0L, ledger.lastConfirmedCloudId())
        assertEquals(0L, ledger.pendingSaveId())
        assertFalse(ledger.isLocalDirty())
    }
}

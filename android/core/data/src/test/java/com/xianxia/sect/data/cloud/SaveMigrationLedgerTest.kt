package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 迁移完成度记账单测（SR-6，方案 §4「迁移完成度记账（MMKV per-slot 上云确认标记）」）。
 *
 * 关注点：态的 roundtrip、**槽位隔离**（一槽已迁不得溢出到邻槽）、失败封闭回落、
 * 删档清理，以及运维侧要按 key 核账时依赖的命名规则。
 */
class SaveMigrationLedgerTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var ledger: SaveMigrationLedger

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        ledger = SaveMigrationLedger(store)
    }

    @Test
    fun `初始态 - 未写入即 NONE 且不算已迁移`() {
        assertEquals(MigrationSlotState.NONE, ledger.state(1))
        assertFalse(ledger.state(1).migrated)
        assertFalse(ledger.state(1).settled)
    }

    @Test
    fun `四态 roundtrip - QUEUED 不算收口，UPLOADED 与 CLOUD_PREFERRED 才算`() {
        ledger.markQueued(1)
        assertEquals(MigrationSlotState.QUEUED, ledger.state(1))
        // 入队 ≠ 上云：进程在确认前被杀时，该槽必须仍被矩阵列为未完成
        assertFalse(ledger.state(1).settled)
        assertFalse(ledger.state(1).migrated)

        ledger.markUploaded(1)
        assertEquals(MigrationSlotState.UPLOADED, ledger.state(1))
        assertTrue(ledger.state(1).settled)
        assertTrue(ledger.state(1).migrated)

        ledger.markCloudPreferred(1)
        assertEquals(MigrationSlotState.CLOUD_PREFERRED, ledger.state(1))
        assertTrue(ledger.state(1).migrated)
    }

    @Test
    fun `槽位隔离 - 标记 slot2 不影响 slot1 与 slot6`() {
        ledger.markUploaded(2)
        assertEquals(MigrationSlotState.UPLOADED, ledger.state(2))
        assertEquals(MigrationSlotState.NONE, ledger.state(1))
        assertEquals(MigrationSlotState.NONE, ledger.state(6))
    }

    @Test
    fun `失败封闭 - 未写入与非法值一律回落 NONE（手改存储不得伪造已迁移）`() {
        assertEquals(MigrationSlotState.NONE, SaveMigrationLedger.fromStored(null))
        assertEquals(MigrationSlotState.NONE, SaveMigrationLedger.fromStored("UPLOADED_"))
        assertEquals(MigrationSlotState.NONE, SaveMigrationLedger.fromStored(""))
        assertEquals(MigrationSlotState.NONE, SaveMigrationLedger.fromStored("lowercase"))
        assertEquals(MigrationSlotState.UPLOADED, SaveMigrationLedger.fromStored("UPLOADED"))
    }

    @Test
    fun `删档清理 - clearSlot 回到 NONE 且不影响邻槽`() {
        ledger.markUploaded(3)
        ledger.markUploaded(4)
        ledger.clearSlot(3)
        assertEquals(MigrationSlotState.NONE, ledger.state(3))
        assertEquals(MigrationSlotState.UPLOADED, ledger.state(4))
    }

    @Test
    fun `key 命名规则 - 运维按前缀核账（改前缀即改存储契约，须显式改此断言）`() {
        ledger.markUploaded(5)
        assertTrue(store.contains("cloud_migration_slot5_state"))
        assertEquals("UPLOADED", store.getString("cloud_migration_slot5_state", null))
    }
}

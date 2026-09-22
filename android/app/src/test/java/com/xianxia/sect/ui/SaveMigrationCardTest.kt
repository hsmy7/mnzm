package com.xianxia.sect.ui

import com.xianxia.sect.ui.game.saveload.MigrationPhase
import com.xianxia.sect.ui.game.saveload.MigrationSlotRow
import com.xianxia.sect.ui.game.saveload.MigrationUiState
import com.xianxia.sect.ui.game.saveload.SlotMigrationStatus
import com.xianxia.sect.ui.model.SaveSelectMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移引导卡的可见性与文案守卫（SR-6 C8）。
 *
 * 两条各管一头：
 * - **可见性**：新游戏模式不得冒出迁移卡（与 SR-3 云槽位卡 `visibleCloudSlots` 同纪律）；
 *   引导完成（全员收口 + 云存档已启用）后卡片必须消失，否则"引导"变常驻噪音；
 * - **文案全覆盖**：每个槽位状态与每个阶段都必须有非空玩家文案——空串意味着某条状态
 *   进了 UI 却没有任何解释（"算了但没人看"的 UI 版）。
 */
class SaveMigrationCardTest {

    private fun migration(
        phase: MigrationPhase = MigrationPhase.READY,
        statuses: List<SlotMigrationStatus> = listOf(SlotMigrationStatus.AWAITING_UPLOAD),
        canEnable: Boolean = false,
        legacyArchive: Boolean = false,
        cloudOnly: Int = 0
    ) = MigrationUiState(
        phase = phase,
        rows = statuses.mapIndexed { index, status ->
            MigrationSlotRow(slot = index + 1, label = "青云宗 · 第12年3月", status = status)
        },
        pendingTotal = statuses.count { it == SlotMigrationStatus.AWAITING_UPLOAD },
        migratedTotal = statuses.count { it == SlotMigrationStatus.MIGRATED },
        legacyArchivePresent = legacyArchive,
        cloudOnlyTotal = cloudOnly,
        canEnableCloudSave = canEnable
    )

    @Test
    fun `读档模式有可迁槽时卡片可见，新游戏模式一律不可见`() {
        val state = migration()
        assertTrue(migrationCardVisible(SaveSelectMode.LOAD_SAVE, state))
        assertFalse(migrationCardVisible(SaveSelectMode.NEW_GAME, state))
    }

    @Test
    fun `空态不可见；服务不可达即使无行也要可见（原因必须让玩家看见）`() {
        assertFalse(migrationCardVisible(SaveSelectMode.LOAD_SAVE, MigrationUiState()))
        val unavailable = MigrationUiState(
            phase = MigrationPhase.SERVICE_UNAVAILABLE,
            notice = "云存档服务不可达：未连接"
        )
        assertTrue(migrationCardVisible(SaveSelectMode.LOAD_SAVE, unavailable))
    }

    @Test
    fun `全员收口但未启用云存档时仍可见 - 启用位是引导的最后一步`() {
        val state = migration(
            phase = MigrationPhase.DONE,
            statuses = listOf(SlotMigrationStatus.MIGRATED),
            canEnable = true
        )
        assertTrue(state.visible)
    }

    @Test
    fun `启用云存档后引导完成，卡片消失`() {
        val state = migration(
            phase = MigrationPhase.DONE,
            statuses = listOf(SlotMigrationStatus.MIGRATED),
            canEnable = false
        )
        assertFalse(state.visible)
    }

    @Test
    fun `收口后仍有存量单档或云端独有槽时不得消失（引导未完成）`() {
        val withLegacy = migration(
            statuses = listOf(SlotMigrationStatus.MIGRATED),
            legacyArchive = true
        ).copy(canEnableCloudSave = false)
        val withCloudOnly = migration(
            statuses = listOf(SlotMigrationStatus.MIGRATED),
            cloudOnly = 2
        ).copy(canEnableCloudSave = false)
        assertTrue("存量单档未取回，引导不得判完成", withLegacy.visible)
        assertTrue("云端独有槽未下载，引导不得判完成", withCloudOnly.visible)
    }

    @Test
    fun `每个槽位状态与阶段都有玩家文案，不允许空解释`() {
        SlotMigrationStatus.values().forEach { status ->
            assertTrue("状态 $status 缺文案", migrationRowText(status).isNotBlank())
        }
        MigrationPhase.values().filter { it != MigrationPhase.IDLE }.forEach { phase ->
            assertTrue("阶段 $phase 缺文案", migrationPhaseText(phase).isNotBlank())
        }
    }

    @Test
    fun `损坏槽文案必须同时点明未上云与未覆盖，避免玩家误以为已安全`() {
        val text = migrationRowText(SlotMigrationStatus.BLOCKED_CORRUPT)
        assertTrue(text.contains("未上云"))
        assertTrue(text.contains("未覆盖"))
    }
}

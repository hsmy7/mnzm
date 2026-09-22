package com.xianxia.sect.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存量迁移矩阵单测（SR-6 方案门：「迁移矩阵单测」= 方案 §4 SR-6 验收第 1 条）。
 *
 * 覆盖 = 方案四格（本地有×云无｜本地有×云有｜本地无×云有｜双无）× 记账幂等态 ×
 * 损坏槽 × 续传态，外加 CLOUD_ONLY 升档门槛（SR-7 前置门）。
 *
 * IN2：全部输入只有"有没有档 / 保存序号 / 记账态"，**零时钟**——`CloudSaveEntry`
 * 的 `modifiedTimeMs` 在所有用例里恒为 [NO_TIME]，正是"矩阵不读它"的可执行注记。
 */
class SaveMigrationPlannerTest {

    private fun cloud(slot: Int, saveId: Long?) = CloudSaveEntry(
        slot = slot,
        archiveName = "slot_$slot",
        saveId = saveId,
        sizeBytes = 1_024L,
        modifiedTimeMs = NO_TIME,
        summary = null
    )

    private fun input(
        slot: Int = 1,
        localHasSave: Boolean = true,
        localLoadError: Boolean = false,
        cloudEntry: CloudSaveEntry? = null,
        state: MigrationSlotState = MigrationSlotState.NONE,
        l: Long = 0L,
        c: Long = 0L,
        pending: Long = 0L
    ) = SlotMigrationInput(
        slot = slot,
        localHasSave = localHasSave,
        localLoadError = localLoadError,
        cloud = cloudEntry,
        migrationState = state,
        ledger = SlotLedgerSnapshot(l, c, pending)
    )

    private fun decide(input: SlotMigrationInput) = SaveMigrationPlanner.decide(input)

    // ── 方案四格 ──

    @Test
    fun `M1 双无 - 新游戏槽无需任何动作`() {
        assertEquals(SlotMigrationAction.NoAction, decide(input(localHasSave = false)))
    }

    @Test
    fun `M2 本地有 x 云无 - 引导逐槽上传（MIGRATE）`() {
        assertEquals(
            SlotMigrationAction.UploadLocal(UploadReason.MIGRATE),
            decide(input(cloudEntry = null))
        )
    }

    @Test
    fun `M3 本地无 x 云有 - 直接云档（下载落缓存）`() {
        assertEquals(
            SlotMigrationAction.UseCloud,
            decide(input(localHasSave = false, cloudEntry = cloud(1, saveId = 7L)))
        )
    }

    @Test
    fun `M4 本地有 x 云有 且双端各有新进度 - 玩家二选一`() {
        assertEquals(
            SlotMigrationAction.ResolveConflict(CloudConflictReason.BOTH_ADVANCED),
            decide(input(cloudEntry = cloud(1, saveId = 5L), l = 4L, c = 3L))
        )
    }

    // ── F12：仲裁兜底在迁移语境下会静默覆盖，矩阵必须前置拦截 ──

    @Test
    fun `M5 云档无 saveId（历史档）- 无从判定，交玩家而非按 U11 放行覆盖`() {
        assertEquals(
            SlotMigrationAction.ResolveConflict(CloudConflictReason.UNVERIFIABLE_CLOUD_STATE),
            decide(input(cloudEntry = cloud(1, saveId = null)))
        )
    }

    @Test
    fun `M6 LEGACY 存量首次（C=0）即使云有 saveId 也判玩家裁决 - 本机对这个云档一无所知`() {
        // SaveArbiter.arbitrate(0, 0, 9) = LOCAL_BEHIND → 若直接照抄会下载覆盖本机存量档
        assertEquals(
            SlotMigrationAction.ResolveConflict(CloudConflictReason.UNVERIFIABLE_CLOUD_STATE),
            decide(input(cloudEntry = cloud(1, saveId = 9L), l = 0L, c = 0L))
        )
    }

    // ── 规则 6 的 SaveArbiter 四出口（本机已有确认基线 C>0）──

    @Test
    fun `M7 云落后 W 小于等于 C - 本机档更新，上云收敛`() {
        assertEquals(
            SlotMigrationAction.UploadLocal(UploadReason.MIGRATE),
            decide(input(cloudEntry = cloud(1, saveId = 3L), l = 4L, c = 3L))
        )
    }

    @Test
    fun `M8 本机净且云更新 LOCAL_BEHIND - 用云档`() {
        assertEquals(
            SlotMigrationAction.UseCloud,
            decide(input(cloudEntry = cloud(1, saveId = 4L), l = 3L, c = 3L))
        )
    }

    @Test
    fun `M9 两端一致 IN_SYNC - 无动作`() {
        assertEquals(
            SlotMigrationAction.NoAction,
            decide(input(cloudEntry = cloud(1, saveId = 3L), l = 3L, c = 3L))
        )
    }

    // ── 幂等与损坏槽 ──

    @Test
    fun `M10 已 UPLOADED 且云有档 - 冷启动不重复骚扰`() {
        assertEquals(
            SlotMigrationAction.NoAction,
            decide(input(cloudEntry = cloud(1, saveId = 3L), state = MigrationSlotState.UPLOADED))
        )
    }

    @Test
    fun `M11 已 CLOUD_PREFERRED 但本地有档 - 玩家裁决过就不再问`() {
        assertEquals(
            SlotMigrationAction.NoAction,
            decide(input(state = MigrationSlotState.CLOUD_PREFERRED))
        )
    }

    @Test
    fun `M12 QUEUED 不算收口 - 入队不等于上云`() {
        assertEquals(
            SlotMigrationAction.UploadLocal(UploadReason.MIGRATE),
            decide(input(state = MigrationSlotState.QUEUED))
        )
    }

    @Test
    fun `M13 损坏槽一律阻断，云有有档也不例外`() {
        assertEquals(
            SlotMigrationAction.BlockedByLoadError,
            decide(input(localLoadError = true, localHasSave = false))
        )
        assertEquals(
            SlotMigrationAction.BlockedByLoadError,
            decide(input(localLoadError = true, cloudEntry = cloud(1, saveId = 3L)))
        )
    }

    @Test
    fun `M14 续传优先于已裁决 - 未走完的动作不能被幂等态抹掉（勘察 F1）`() {
        assertEquals(
            SlotMigrationAction.UploadLocal(UploadReason.RESUME_PENDING),
            decide(
                input(
                    cloudEntry = cloud(1, saveId = 2L),
                    state = MigrationSlotState.UPLOADED,
                    l = 3L,
                    c = 2L,
                    pending = 3L
                )
            )
        )
    }

    // ── 计划聚合面 ──

    @Test
    fun `M15 计划按槽归集各动作，存量单档单列不进逐槽判定`() {
        val plan = SaveMigrationPlanner.plan(
            inputs = listOf(
                input(slot = 1, cloudEntry = null),                          // UploadLocal
                input(slot = 2, localHasSave = false, cloudEntry = cloud(2, 5L)), // UseCloud
                input(slot = 3, localLoadError = true, localHasSave = false),  // Blocked
                input(slot = 4, localHasSave = false),                         // 双无
                input(slot = 5, cloudEntry = cloud(5, 5L), l = 4L, c = 3L)     // Conflict
            ),
            legacyArchive = cloud(0, saveId = null)
        )
        assertEquals(listOf(1), plan.uploadSlots)
        assertEquals(listOf(2), plan.cloudSlots)
        assertEquals(listOf(3), plan.blockedSlots)
        assertEquals(listOf(5), plan.conflictSlots)
        // 可动作数 = 上云1 + 用云1 + 阻断1 + 冲突1 + 存量单档1
        assertEquals(5, plan.actionableCount)
        assertTrue(plan.requiresPlayerDecision)
    }

    @Test
    fun `M16 无存量单档且全空槽 - 计划不需要任何决策`() {
        val plan = SaveMigrationPlanner.plan(listOf(input(slot = 1, localHasSave = false)))
        assertEquals(0, plan.actionableCount)
        assertFalse(plan.requiresPlayerDecision)
    }

    // ── CLOUD_ONLY 升档门槛（方案 SR-6 硬红线 + SR-7 前置门）──

    @Test
    fun `P1 全部本地有档槽已上云 - 门槛开`() {
        assertTrue(
            SaveMigrationPlanner.canPromoteToCloudOnly(
                listOf(
                    input(slot = 1, state = MigrationSlotState.UPLOADED, l = 2L, c = 2L),
                    input(slot = 2, state = MigrationSlotState.CLOUD_PREFERRED),
                    input(slot = 3, localHasSave = false)
                )
            )
        )
    }

    @Test
    fun `P2 仍有未迁槽 - 门槛关`() {
        assertFalse(
            SaveMigrationPlanner.canPromoteToCloudOnly(
                listOf(
                    input(slot = 1, state = MigrationSlotState.UPLOADED),
                    input(slot = 2)
                )
            )
        )
    }

    @Test
    fun `P3 QUEUED 不算已迁移 - 确认未到账就不开门槛`() {
        assertFalse(
            SaveMigrationPlanner.canPromoteToCloudOnly(
                listOf(input(slot = 1, state = MigrationSlotState.QUEUED))
            )
        )
    }

    @Test
    fun `P4 有损坏槽 - 门槛关（既不在云上也无法读出）`() {
        assertFalse(
            SaveMigrationPlanner.canPromoteToCloudOnly(
                listOf(
                    input(slot = 1, state = MigrationSlotState.UPLOADED),
                    input(slot = 2, localHasSave = false, localLoadError = true)
                )
            )
        )
    }

    @Test
    fun `P5 有未确认待传 - 门槛关（即使态已 UPLOADED）`() {
        assertFalse(
            SaveMigrationPlanner.canPromoteToCloudOnly(
                listOf(input(slot = 1, state = MigrationSlotState.UPLOADED, l = 4L, c = 3L, pending = 4L))
            )
        )
    }

    @Test
    fun `P6 全新设备无存量 - 三条件真空成立，迁移不成为升档前置`() {
        assertTrue(
            SaveMigrationPlanner.canPromoteToCloudOnly(
                listOf(input(slot = 1, localHasSave = false), input(slot = 2, localHasSave = false))
            )
        )
    }

    private companion object {
        /** 矩阵不读时钟：所有用例的 mtime 恒为此值，判定结果与之无关（IN2 可执行注记） */
        const val NO_TIME = 0L
    }
}

package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.DiscipleStatus
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫测试：新增 [DiscipleStatus] 枚举值时，确保推导系统的 3 处同步更新。
 *
 * ## 新增弟子状态的必改清单（推导系统部分）
 *
 * 当你在 [DiscipleStatus] 添加了新的枚举值且该状态由槽位推导（非受保护状态），
 * 必须同步更新以下 3 处：
 *
 * 1. **[DiscipleStatusService.SlotFlags]** — 添加 `val newStatus: Boolean = false`
 * 2. **[DiscipleStatusService.deriveDiscipleStatus]** — 在 when 链中添加 `slotFlags.newStatus -> DiscipleStatus.NEW_STATUS`
 * 3. **[DiscipleStatusService.buildSlotFlagsFor]** — 从游戏数据中设置该标志
 *    （若该状态在批量同步中也有处理，还需更新 [DiscipleStatusService.syncAllDiscipleStatuses] 的槽位收集函数）
 *
 * 受保护状态（[REFLECTING], [ON_MISSION], [REFINING]）和 [DEAD] 不在此列——
 * 它们不通过推导系统设置。
 */
class StatusDerivationCoverageTest {

    /**
     * 非推导状态：不通过 SlotFlags/deriveDiscipleStatus 设置。
     * - DEAD：通过 markDead 设置，非槽位推导
     * - REFLECTING：受保护状态，直接写入
     * - ON_MISSION：受保护状态，直接写入
     * - REFINING：受保护状态，直接写入
     */
    private val NON_DERIVED_STATUSES = setOf(
        DiscipleStatus.DEAD,
        DiscipleStatus.REFLECTING,
        DiscipleStatus.ON_MISSION,
        DiscipleStatus.REFINING
    )

    /**
     * 每个可推导状态 → 对应的 [SlotFlags] 字段名。
     * 枚举值名转换为驼峰 field 名（首字母小写）。
     */
    private val statusToSlotFlag: Map<DiscipleStatus, String> = mapOf(
        DiscipleStatus.IDLE to "",  // IDLE 是 else 分支，无对应 flag
        DiscipleStatus.GARRISONING to "inGarrison",
        DiscipleStatus.WAREHOUSE_GARRISON to "inWarehouseGarrison",
        DiscipleStatus.IN_TEAM to "inTeam",
        DiscipleStatus.SECRET_REALM to "inSecretRealm",
        DiscipleStatus.LAW_ENFORCING to "lawEnforcing",
        DiscipleStatus.PREACHING to "preaching",
        DiscipleStatus.DEACONING to "deaconing",
        DiscipleStatus.MANAGING to "managing",
        DiscipleStatus.STUDYING to "studying",
        DiscipleStatus.MINING to "mining",
        DiscipleStatus.PATROLLING to "patrolling",
        DiscipleStatus.ALCHEMY to "alchemy",
        DiscipleStatus.FORGE to "forge",
        DiscipleStatus.SPIRIT_PLANTING to "spiritPlanting"
    )

    @Test
    fun `all derived DiscipleStatus values have a corresponding SlotFlags field`() {
        val slotFlagsFields = DiscipleStatusService.SlotFlags::class.memberProperties
            .map { it.name }
            .toSet()

        val derivedStatuses = DiscipleStatus.values()
            .filter { it !in NON_DERIVED_STATUSES }
            .filter { it != DiscipleStatus.IDLE }  // IDLE = else 分支

        val missing = derivedStatuses.filter { status ->
            val expectedField = statusToSlotFlag[status]
            expectedField != null && expectedField !in slotFlagsFields
        }

        val missingField = missing.firstOrNull()
        val step1 = "  1. 在 SlotFlags 中添加 val " +
            "${missingField?.name?.lowercase()}: Boolean = false"
        val step2 = "  2. 在 deriveDiscipleStatus 的 when 链中添加" +
            " slotFlags.${missingField?.name?.lowercase()}" +
            " -> DiscipleStatus.${missingField?.name}"
        val step3 = "  3. 在 buildSlotFlagsFor 中设置该标志"
        assertTrue(
            """|以下 DiscipleStatus 没有对应的 SlotFlags 字段：
               |$missing
               |
               |请按以下步骤操作：
               |$step1
               |$step2
               |$step3
               |
               |注意：新增状态可能也需要更新 syncAllDiscipleStatuses 的
               |槽位收集函数（buildLawEnforcerIds / buildInTeamIds 等）
               |和 DiscipleStatusCoverageTest。""".trimMargin(),
            missing.isEmpty()
        )
    }

    @Test
    fun `all SlotFlags fields have a corresponding branch in deriveDiscipleStatus`() {
        val slotFlagsFields = DiscipleStatusService.SlotFlags::class.memberProperties
            .map { it.name }
            .toSet()

        // 这些是 deriveDiscipleStatus 的 when 分支中检查的 flag（不含 else=IDLE）
        val coveredFlags = statusToSlotFlag.values.filter { it.isNotEmpty() }.toSet()

        val uncovered = slotFlagsFields - coveredFlags

        assertTrue(
            """|SlotFlags 中存在以下字段，但 deriveDiscipleStatus 的 when 链中没有对应分支：
               |$uncovered
               |
               |请检查：
               |  1. 是否需要在 deriveDiscipleStatus 中添加分支？
               |  2. 如果该字段不用于推导（辅助用途），在 statusToSlotFlag 映射表中添加排除""".trimMargin(),
            uncovered.isEmpty()
        )
    }

    @Test
    fun `deriveDiscipleStatus handles all SlotFlags fields`() {
        val slotFlagsFields = DiscipleStatusService.SlotFlags::class.memberProperties
            .map { it.name }
            .toSet()

        // statusToSlotFlag 中列出的所有 flag 必须在 SlotFlags 中存在
        val referencedFlags = statusToSlotFlag.values.filter { it.isNotEmpty() }.toSet()
        val missingFields = referencedFlags - slotFlagsFields

        assertTrue(
            """|statusToSlotFlag 引用了以下字段，但 SlotFlags 中不存在：
               |$missingFields
               |
               |请在 SlotFlags 中添加对应的字段声明""".trimMargin(),
            missingFields.isEmpty()
        )
    }

    @Test
    fun `all DiscipleStatus values are documented in statusToSlotFlag`() {
        val documented = statusToSlotFlag.keys + NON_DERIVED_STATUSES
        val all = DiscipleStatus.values().toSet()
        val undocumented = all - documented

        assertTrue(
            """|以下 DiscipleStatus 既不在 NON_DERIVED_STATUSES 中，也不在 statusToSlotFlag 中：
               |$undocumented
               |
               |请将新状态加入 statusToSlotFlag（如果是可推导状态）或
               |NON_DERIVED_STATUSES（如果是受保护/特殊状态）。""".trimMargin(),
            undocumented.isEmpty()
        )
    }
}

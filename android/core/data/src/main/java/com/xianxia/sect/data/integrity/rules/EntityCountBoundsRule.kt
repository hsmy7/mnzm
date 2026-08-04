package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.data.model.SaveData

/**
 * 检查各实体列表数量是否在合理范围内。
 *
 * 两级阈值（2026-08-04 T7 修复"伪修复"）：
 * - **警告阈值**：超出仅记录（不修改数据，保留既有语义）
 * - **硬上限**：超出真正截断（战斗日志按时间保留最新 / 堆叠截断并清理弟子悬空引用）；
 *   弟子数量超硬上限判定 [RuleOutcome.Corrupted]（截断弟子需跨实体引用手术，不可半修复）
 */
object EntityCountBoundsRule : SaveValidationRule {
    override val id = "entity_count_bounds"
    override val order = 19

    /** 弟子数量警告阈值 */
    private const val DISCIPLE_WARN_THRESHOLD = 10000

    /** 装备堆叠数量警告阈值 */
    private const val EQUIPMENT_STACK_WARN_THRESHOLD = 5000

    /** 战斗日志数量警告阈值 */
    private const val BATTLE_LOG_WARN_THRESHOLD = 2000

    /** 弟子数量硬上限（超限=不可安全修复，判损坏） */
    private const val DISCIPLE_HARD_CAP = 100000

    /** 装备堆叠硬上限 */
    private const val EQUIPMENT_STACK_HARD_CAP = 50000

    /** 功法堆叠硬上限 */
    private const val MANUAL_STACK_HARD_CAP = 50000

    /** 战斗日志硬上限（与 DataArchiver 保留最新语义一致） */
    private const val BATTLE_LOG_HARD_CAP = 5000

    @Suppress("ReturnCount") // 守卫风格：硬上限判损坏早退 + 截断分支，多 return 为守卫
    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        if (data.disciples.size > DISCIPLE_HARD_CAP) {
            return RuleOutcome.Corrupted(
                listOf("弟子数量 ${data.disciples.size} 超过硬上限 $DISCIPLE_HARD_CAP，判定存档损坏")
            )
        }

        val details = mutableListOf<String>()
        var dataChanged = false

        // ── 战斗日志：超硬上限按时间戳保留最新 N 条（与 DataArchiver.getRetainedBattleLogs 语义一致）──
        var battleLogs = data.battleLogs
        if (battleLogs.size > BATTLE_LOG_HARD_CAP) {
            battleLogs = battleLogs.sortedByDescending { it.timestamp }.take(BATTLE_LOG_HARD_CAP)
            details.add("战斗日志 ${data.battleLogs.size} 条超过硬上限 $BATTLE_LOG_HARD_CAP，已按时间保留最新 $BATTLE_LOG_HARD_CAP 条")
            dataChanged = true
        }

        // ── 装备/功法堆叠：超硬上限截断 + 清理弟子对已截断堆叠的悬空引用 ──
        var equipmentStacks = data.equipmentStacks
        var manualStacks = data.manualStacks
        if (equipmentStacks.size > EQUIPMENT_STACK_HARD_CAP) {
            equipmentStacks = equipmentStacks.take(EQUIPMENT_STACK_HARD_CAP)
            details.add("装备堆叠 ${data.equipmentStacks.size} 个超过硬上限 $EQUIPMENT_STACK_HARD_CAP，已截断")
            dataChanged = true
        }
        if (manualStacks.size > MANUAL_STACK_HARD_CAP) {
            manualStacks = manualStacks.take(MANUAL_STACK_HARD_CAP)
            details.add("功法堆叠 ${data.manualStacks.size} 个超过硬上限 $MANUAL_STACK_HARD_CAP，已截断")
            dataChanged = true
        }

        // ── 警告阈值（仅记录，不修改数据）──
        val discipleCount = data.disciples.size
        if (discipleCount > DISCIPLE_WARN_THRESHOLD) {
            details.add("⚠️ 弟子数量 $discipleCount 超过警告阈值 $DISCIPLE_WARN_THRESHOLD")
        }
        val equipmentStackCount = data.equipmentStacks.size
        if (equipmentStackCount > EQUIPMENT_STACK_WARN_THRESHOLD) {
            details.add("⚠️ 装备堆叠数量 $equipmentStackCount 超过警告阈值 $EQUIPMENT_STACK_WARN_THRESHOLD")
        }
        val battleLogCount = data.battleLogs.size
        if (battleLogCount > BATTLE_LOG_WARN_THRESHOLD) {
            details.add("⚠️ 战斗日志数量 $battleLogCount 超过警告阈值 $BATTLE_LOG_WARN_THRESHOLD")
        }

        if (!dataChanged) {
            return if (details.isNotEmpty()) RuleOutcome.Repaired(data, details) else RuleOutcome.Passed
        }

        // ── 截断后清理弟子悬空引用（仅清理指向"被移除堆叠"的引用，equipmentInstances 不受影响）──
        val removedEquipmentIds = data.equipmentStacks.map { it.id }.toHashSet() -
            equipmentStacks.map { it.id }.toHashSet()
        val removedManualIds = data.manualStacks.map { it.id }.toHashSet() -
            manualStacks.map { it.id }.toHashSet()
        val fixedDisciples = data.disciples.map { d ->
            clearDanglingStackRefs(d, removedEquipmentIds, removedManualIds, details)
        }

        return RuleOutcome.Repaired(
            data.copy(
                battleLogs = battleLogs,
                equipmentStacks = equipmentStacks,
                manualStacks = manualStacks,
                disciples = fixedDisciples
            ),
            details
        )
    }

    /** 清理弟子对已截断堆叠的悬空引用（装备四槽 + manualIds） */
    private fun clearDanglingStackRefs(
        d: Disciple,
        removedEquipmentIds: Set<String>,
        removedManualIds: Set<String>,
        details: MutableList<String>
    ): Disciple {
        val eq = d.equipment
        val removedEquipped = eq.equippedItemIds.filter { it in removedEquipmentIds }
        val removedManuals = d.manualIds.filter { it in removedManualIds }
        if (removedEquipped.isEmpty() && removedManuals.isEmpty()) return d

        val newEq = EquipmentSet(
            weaponId = eq.weaponId.takeUnless { it in removedEquipmentIds }.orEmpty(),
            armorId = eq.armorId.takeUnless { it in removedEquipmentIds }.orEmpty(),
            bootsId = eq.bootsId.takeUnless { it in removedEquipmentIds }.orEmpty(),
            accessoryId = eq.accessoryId.takeUnless { it in removedEquipmentIds }.orEmpty(),
            weaponNurture = eq.weaponNurture,
            armorNurture = eq.armorNurture,
            bootsNurture = eq.bootsNurture,
            accessoryNurture = eq.accessoryNurture,
            storageBagItems = eq.storageBagItems,
            storageBagSpiritStones = eq.storageBagSpiritStones,
            spiritStones = eq.spiritStones
        )
        details.add(
            "弟子[${d.name.ifBlank { "ID=${d.id}" }}] 存在指向已截断堆叠的悬空引用" +
                "（装备 ${removedEquipped.size} 件 / 功法 ${removedManuals.size} 本），已清除"
        )
        return d.copy(equipment = newEq, manualIds = d.manualIds - removedManualIds.toSet())
    }
}

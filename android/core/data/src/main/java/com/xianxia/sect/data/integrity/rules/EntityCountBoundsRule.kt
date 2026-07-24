package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查各实体列表数量是否在合理范围内。
 *
 * 对超出阈值的数量发出警告，不自动修改数据，
 * 因为数据量的概念是业务层阈值而非数据损坏。
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

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val warnings = mutableListOf<String>()

        val discipleCount = data.disciples.size
        if (discipleCount > DISCIPLE_WARN_THRESHOLD) {
            warnings.add("⚠️ 弟子数量 $discipleCount 超过警告阈值 $DISCIPLE_WARN_THRESHOLD")
        }

        val equipmentStackCount = data.equipmentStacks.size
        if (equipmentStackCount > EQUIPMENT_STACK_WARN_THRESHOLD) {
            warnings.add("⚠️ 装备堆叠数量 $equipmentStackCount 超过警告阈值 $EQUIPMENT_STACK_WARN_THRESHOLD")
        }

        val battleLogCount = data.battleLogs.size
        if (battleLogCount > BATTLE_LOG_WARN_THRESHOLD) {
            warnings.add("⚠️ 战斗日志数量 $battleLogCount 超过警告阈值 $BATTLE_LOG_WARN_THRESHOLD")
        }

        return if (warnings.isNotEmpty()) {
            RuleOutcome.Repaired(data, warnings)
        } else {
            RuleOutcome.Passed
        }
    }
}

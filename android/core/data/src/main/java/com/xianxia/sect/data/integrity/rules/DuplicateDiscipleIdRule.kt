package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查 [disciples] 列表中是否存在重复的 [Disciple.id]。
 * 保留第一个出现，移除后续重复项。
 *
 * 重复 ID 会导致 ComponentTable 中的索引覆盖、存档/读档后部分弟子数据丢失。
 */
object DuplicateDiscipleIdRule : SaveValidationRule {
    override val id = "duplicate_disciple_id"
    override val order = 9

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val seen = mutableSetOf<String>()
        val repairs = mutableListOf<String>()
        val deduped = data.disciples.filter { d ->
            if (d.id in seen) {
                repairs.add("重复弟子 ID=${d.id}（name=${d.name}），已移除重复项")
                false
            } else {
                seen.add(d.id)
                true
            }
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = deduped), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

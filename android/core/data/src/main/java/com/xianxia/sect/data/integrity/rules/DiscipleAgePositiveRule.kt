package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查所有存活弟子的 age 是否 >= 0。
 * age < 0 的弟子设为默认值 16（初始入门年龄）。
 */
object DiscipleAgePositiveRule : SaveValidationRule {
    override val id = "disciple_age_positive"
    override val order = 3

    private const val DEFAULT_AGE = 16

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            if (d.age < 0) {
                repairs.add("弟子[${d.name.ifBlank { "ID=${d.id}" }}] age=${d.age} 为负，已设为 $DEFAULT_AGE")
                d.copy(age = DEFAULT_AGE)
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

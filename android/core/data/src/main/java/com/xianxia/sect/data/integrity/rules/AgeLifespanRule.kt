package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查存活弟子的年龄是否超过寿命上限。
 * 超过则截断至 [lifespan]。
 */
object AgeLifespanRule : SaveValidationRule {
    override val id = "age_lifespan"
    override val order = 7

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            if (d.isAlive && d.age > d.lifespan) {
                repairs.add(
                    "弟子[${d.name.ifBlank { "ID=${d.id}" }}] " +
                        "age=${d.age} 超过 lifespan=${d.lifespan}，已截断"
                )
                d.copy(age = d.lifespan)
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

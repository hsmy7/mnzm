package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.domain.disciple.computeMaxAge
import com.xianxia.sect.data.model.SaveData

/**
 * 检查存活弟子的年龄是否超过寿元上限。
 *
 * 截断目标为 [computeMaxAge]（引擎死亡判定同一口径）而非 [Disciple.lifespan]——
 * age ∈ (lifespan, computeMaxAge] 是"延年"词条/天赋加成的合法延寿区间，
 * 按 lifespan 截断会造成年龄回滚死循环（引擎允许活过 lifespan → 存档截断回 lifespan
 * → 永远到不了寿元上限 → 弟子永生，2026-08-10 修复）。仅当 age 超过特质加成的
 * 真实上限（硬顶 20000）时才判定为损坏数据并截断。
 */
object AgeLifespanRule : SaveValidationRule {
    override val id = "age_lifespan"
    override val order = 7

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            if (d.isAlive) {
                val maxAge = d.computeMaxAge()
                if (d.age > maxAge) {
                    repairs.add(
                        "弟子[${d.name.ifBlank { "ID=${d.id}" }}] " +
                            "age=${d.age} 超过寿元上限 maxAge=$maxAge，已截断"
                    )
                    d.copy(age = maxAge)
                } else d
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

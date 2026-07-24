package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检测弟子 manualIds 和 talentIds 中的空/空白字符串引用。
 *
 * 当前对 manualId/talentId 做基础的空字符串和空白校验。
 * 完整的手册注册表和天赋注册表校验需要访问 [com.xianxia.sect.core.registry.ManualRegistry]
 * 和 [com.xianxia.sect.core.registry.TalentRegistry]（位于 :core:engine 模块），
 * 本规则仅做数据层内的基础一致性检查。
 *
 * 完整跨模块校验需在后续迭代中将注册表接口移至 :core:domain 模块或通过 RuleContext 注入。
 */
object ItemRefConsistencyRule : SaveValidationRule {
    override val id = "item_ref_consistency"
    override val order = 18

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()

        val fixedDisciples = data.disciples.map { d ->
            val name = d.name.ifBlank { "ID=${d.id}" }
            var modified = false

            // ── 清理 manualIds 中的空/空白字符串 ──
            val cleanedManualIds = d.manualIds.filterNot { id ->
                val blank = id.isBlank()
                if (blank) {
                    repairs.add("弟子[$name] manualId 为空字符串，已移除")
                    modified = true
                }
                blank
            }

            // ── 清理 talentIds 中的空/空白字符串 ──
            val cleanedTalentIds = d.talentIds.filterNot { id ->
                val blank = id.isBlank()
                if (blank) {
                    repairs.add("弟子[$name] talentId 为空字符串，已移除")
                    modified = true
                }
                blank
            }

            if (modified) d.copy(manualIds = cleanedManualIds, talentIds = cleanedTalentIds) else d
        }

        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = fixedDisciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

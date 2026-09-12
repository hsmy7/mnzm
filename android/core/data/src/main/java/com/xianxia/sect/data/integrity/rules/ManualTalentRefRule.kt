package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.data.model.SaveData

/**
 * manualIds / talentIds 悬空引用清理规则（T7，2026-08-04）。
 *
 * [ItemRefConsistencyRule]（order=18）仅清理空/空白字符串引用，
 * 本规则（order=22，在其后）处理**非空但悬空**的引用：
 * - manualIds：存档作用域校验——合法集合 = manualStacks + manualInstances 的 id 并集
 *   （游戏运行期 manualMap 即按此键查找）
 * - talentIds：注册表校验——[TalentDatabase.getTalentDataById] 查无此键即悬空
 */
object ManualTalentRefRule : SaveValidationRule {
    override val id = "manual_talent_ref"
    override val order = 22

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        // 存档作用域内合法 manualId 集合（游戏运行期 manualMap 即按此键查找）
        val validManualIds = buildSet {
            data.manualStacks.forEach { add(it.id) }
            data.manualInstances.forEach { add(it.id) }
        }

        val repairs = mutableListOf<String>()
        val fixedDisciples = data.disciples.map { d ->
            var modified = false
            var manualIds = d.manualIds
            var talentIds = d.talentIds
            val name = d.name.ifBlank { "ID=${d.id}" }

            val keptManuals = manualIds.filter { it in validManualIds }
            if (keptManuals.size != manualIds.size) {
                repairs.add("弟子[$name] ${manualIds.size - keptManuals.size} 个 manualId 悬空引用，已移除")
                manualIds = keptManuals
                modified = true
            }

            val keptTalents = talentIds.filter { TalentDatabase.getTalentDataById(it) != null }
            if (keptTalents.size != talentIds.size) {
                repairs.add("弟子[$name] ${talentIds.size - keptTalents.size} 个 talentId 悬空引用，已移除")
                talentIds = keptTalents
                modified = true
            }

            if (modified) d.copy(manualIds = manualIds, talentIds = talentIds) else d
        }

        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = fixedDisciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

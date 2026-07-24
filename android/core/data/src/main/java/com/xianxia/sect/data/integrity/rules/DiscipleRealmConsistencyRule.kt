package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.model.SaveData

/**
 * 检查弟子的 realm 和 realmLayer 是否在合法范围内。
 *
 * - realm 应在 [0, 9] 范围（0=仙人, 1~9=凡人境界）
 * - realmLayer 应在 [1, maxLayers] 范围
 * - realm <= 0（仙人）时 realmLayer 设为 1
 */
object DiscipleRealmConsistencyRule : SaveValidationRule {
    override val id = "disciple_realm_consistency"
    override val order = 13

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            val name = d.name.ifBlank { "ID=${d.id}" }
            val fixedRealm = d.realm.coerceIn(0, 9)

            val maxLayers = if (fixedRealm > 0) {
                GameConfig.Realm.get(fixedRealm)?.maxLayers ?: 9
            } else 1

            val fixedLayer = d.realmLayer.coerceIn(1, maxLayers)

            if (fixedRealm != d.realm || fixedLayer != d.realmLayer) {
                repairs.add(
                    "弟子[$name] realm=${d.realm} layer=${d.realmLayer} 超出范围，" +
                        "已修正为 realm=$fixedRealm layer=$fixedLayer"
                )
                d.copy(realm = fixedRealm, realmLayer = fixedLayer)
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

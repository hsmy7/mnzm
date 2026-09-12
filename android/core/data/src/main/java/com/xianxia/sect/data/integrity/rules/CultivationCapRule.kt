package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.model.SaveData

/**
 * 检查所有弟子的 cultivation 是否超过境界上限。
 * 超过则截断至对应境界和层数的最大修为值。
 *
 * 仙人境界（realm <= 0）使用绝对上限钳制（2026-08-04 T7）：
 * 原实现不限制（Double.MAX_VALUE），恶意云档可携带 realm=0 + 巨大 cultivation 穿透。
 */
object CultivationCapRule : SaveValidationRule {
    override val id = "cultivation_cap"
    override val order = 5

    /**
     * 仙人境界（realm<=0）修为绝对上限。
     * 渡劫 9 层最大值约 3.6e6，1e9 提供约 270 倍余量，正常游戏无法触及。
     */
    private const val IMMORTAL_REALM_CULTIVATION_CAP = 1e9

    /** realmLayer 合法最小层数（钳制 crafted 存档的负层数） */
    private const val MIN_REALM_LAYER = 1

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            val maxCult = if (d.realm <= 0) {
                IMMORTAL_REALM_CULTIVATION_CAP
            } else {
                // 对抗性审查（2026-08-05）：realmLayer 未钳制会被 crafted 存档
                // 放大 cap（Int.MAX_VALUE → 4.65e10）或注入负 cap（负层数 → 负修为）
                val safeLayer = d.realmLayer.coerceIn(
                    MIN_REALM_LAYER,
                    GameConfig.Realm.get(d.realm).maxLayers
                )
                computeMaxCultivation(d.realm, safeLayer)
            }
            if (d.cultivation > maxCult) {
                repairs.add(
                    "弟子[${d.name.ifBlank { "ID=${d.id}" }}] " +
                        "cultivation=${d.cultivation} 超过境界上限=$maxCult，已截断"
                )
                d.copy(cultivation = maxCult)
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}

/**
 * 计算给定境界和层数的修为上限。
 *
 * 与 [com.xianxia.sect.core.model.Disciple.maxCultivation] 保持一致。
 * realm <= 0（仙人境界）返回 [Double.MAX_VALUE]（不限制）。
 *
 * @param realm 境界（0=仙人, 1~9）
 * @param realmLayer 层数（1~maxLayers）
 */
internal fun computeMaxCultivation(realm: Int, realmLayer: Int): Double {
    if (realm <= 0) return Double.MAX_VALUE
    val cfg = GameConfig.Realm.get(realm)
    val nextCfg = GameConfig.Realm.get(realm - 1)
    val base = cfg.cultivationBase.toDouble()
    val nextBase = nextCfg.cultivationBase.toDouble()
    val maxLayers = cfg.maxLayers
    return base + (realmLayer - 1).toDouble() * (nextBase - base) / maxLayers
}

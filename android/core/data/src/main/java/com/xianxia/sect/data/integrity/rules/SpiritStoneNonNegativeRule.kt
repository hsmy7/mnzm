package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查 [spiritStones]、[midGradeSpiritStones]、[highGradeSpiritStones] 是否为负。
 * 负值截断为 0。
 *
 * 负灵石会导致 Wallet 审计账本异常和经济系统计算错误。
 */
object SpiritStoneNonNegativeRule : SaveValidationRule {
    override val id = "spirit_stone_non_negative"
    override val order = 12

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val repairs = mutableListOf<String>()

        val fixedLow = gd.spiritStones.coerceAtLeast(0)
        val fixedMid = gd.midGradeSpiritStones.coerceAtLeast(0)
        val fixedHigh = gd.highGradeSpiritStones.coerceAtLeast(0)

        if (fixedLow != gd.spiritStones) {
            repairs.add("spiritStones=${gd.spiritStones} 为负，已修正为 0")
        }
        if (fixedMid != gd.midGradeSpiritStones) {
            repairs.add("midGradeSpiritStones=${gd.midGradeSpiritStones} 为负，已修正为 0")
        }
        if (fixedHigh != gd.highGradeSpiritStones) {
            repairs.add("highGradeSpiritStones=${gd.highGradeSpiritStones} 为负，已修正为 0")
        }

        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(
                data.copy(
                    gameData = gd.copy(
                        spiritStones = fixedLow,
                        midGradeSpiritStones = fixedMid,
                        highGradeSpiritStones = fixedHigh
                    )
                ),
                repairs
            )
        } else {
            RuleOutcome.Passed
        }
    }
}

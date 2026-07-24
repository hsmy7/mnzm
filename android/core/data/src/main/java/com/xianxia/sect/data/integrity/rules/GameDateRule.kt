package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查 [SaveData.gameData.gameYear] 和 [gameMonth] 是否在合法范围内。
 * year >= 1, month in [1, 12]。
 */
object GameDateRule : SaveValidationRule {
    override val id = "game_date"
    override val order = 2

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val clampedYear = gd.gameYear.coerceAtLeast(1)
        val clampedMonth = gd.gameMonth.coerceIn(1, 12)
        return if (clampedYear != gd.gameYear || clampedMonth != gd.gameMonth) {
            RuleOutcome.Repaired(
                data.copy(
                    gameData = gd.copy(gameYear = clampedYear, gameMonth = clampedMonth)
                ),
                listOf(
                    "游戏时间越界：year=${gd.gameYear} month=${gd.gameMonth}，" +
                        "已修正为 year=$clampedYear month=$clampedMonth"
                )
            )
        } else {
            RuleOutcome.Passed
        }
    }
}

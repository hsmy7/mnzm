package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.data.model.SaveData

/**
 * 检查玉符（氪金货币）字段是否非法：
 * - [jadeSymbols] 为负 → 钳 0；持有上限 [Int.MAX_VALUE] - [GameConfig.Jade.DAILY_CAP]
 *   （防止手改 Int.MAX_VALUE 后首次发放 Int 溢出回绕为负）
 * - [jadeSymbolsToday] 为负 → 钳 0；超 [GameConfig.Jade.DAILY_CAP] → 钳至上限
 *   （防"今日计数 999"冻结/绕限组合攻击）
 * - [jadeDayAnchorMs] 为负 → 钳 0
 * - [jadeAccumMs] 为负 → 钳 0；≥ [GameConfig.Jade.INTERVAL_MS] → 钳至
 *   `INTERVAL_MS - 1`（上限须严格小于发放阈值，否则"恰等于 20 分钟"的存档
 *   读档首帧即免费 +1 玉符，可配合改档循环无限刷——对抗性审查 F1）
 *
 * 负值/超限会导致玉符结算异常（发放金额错误、周期跳动、免刷漏洞）。
 */
object JadeSymbolNonNegativeRule : SaveValidationRule {
    override val id = "jade_symbol_non_negative"
    override val order = 23

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val repairs = mutableListOf<String>()

        val fixedSymbols = gd.jadeSymbols.coerceIn(0, Int.MAX_VALUE - GameConfig.Jade.DAILY_CAP)
        val fixedToday = gd.jadeSymbolsToday.coerceIn(0, GameConfig.Jade.DAILY_CAP)
        val fixedAccum = gd.jadeAccumMs
            .coerceAtLeast(0)
            .coerceAtMost(GameConfig.Jade.INTERVAL_MS - 1)
        val fixedAnchor = gd.jadeDayAnchorMs.coerceAtLeast(0)

        if (fixedSymbols != gd.jadeSymbols) {
            repairs.add("jadeSymbols=${gd.jadeSymbols} 越界，已修正为 $fixedSymbols")
        }
        if (fixedToday != gd.jadeSymbolsToday) {
            repairs.add("jadeSymbolsToday=${gd.jadeSymbolsToday} 越界，已修正为 $fixedToday")
        }
        if (fixedAccum != gd.jadeAccumMs) {
            repairs.add("jadeAccumMs=${gd.jadeAccumMs} 越界，已修正为 $fixedAccum")
        }
        if (fixedAnchor != gd.jadeDayAnchorMs) {
            repairs.add("jadeDayAnchorMs=${gd.jadeDayAnchorMs} 为负，已修正为 0")
        }

        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(
                data.copy(
                    gameData = gd.copy(
                        jadeSymbols = fixedSymbols,
                        jadeSymbolsToday = fixedToday,
                        jadeAccumMs = fixedAccum,
                        jadeDayAnchorMs = fixedAnchor
                    )
                ),
                repairs
            )
        } else {
            RuleOutcome.Passed
        }
    }
}

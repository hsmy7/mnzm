package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查 [SaveData.gameData.gamePhase] 是否在合法范围 [0, 2]。
 * 旧存档可能使用 gameDay (1~30)，需要映射为 phase (0~2)。
 */
object GamePhaseRangeRule : SaveValidationRule {
    override val id = "game_phase_range"
    override val order = 4

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val phase = gd.gamePhase
        return if (phase !in 0..2) {
            val fixedPhase = if (phase > 0) (phase - 1) / 10 else 0
            val clamped = fixedPhase.coerceIn(0, 2)
            RuleOutcome.Repaired(
                data.copy(gameData = gd.copy(gamePhase = clamped)),
                listOf("gamePhase=$phase 超出范围[0,2]，已修正为 $clamped")
            )
        } else {
            RuleOutcome.Passed
        }
    }
}

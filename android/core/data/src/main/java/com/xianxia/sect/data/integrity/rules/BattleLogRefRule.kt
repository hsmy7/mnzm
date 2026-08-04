package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.data.model.SaveData

/**
 * 战斗日志结构校验规则（T7，2026-08-04）。
 *
 * battleLogs 是历史记录（UI 仅按成员 name/portraitRes 渲染，teamId 不序列化），
 * 不存在可校验的当前存档外键——可执行合约 = 条目结构合法性 + 空条目清理。
 * 数量截断已由 [EntityCountBoundsRule]（order=19）完成，本规则（order=21）做条目级校验。
 *
 * 判定为非法的条目：
 * - 日期非法（year < 1 或 month 不在 1..12）
 * - 回合数非法（turns 为负或超过上限）
 * - 伤亡数非法（teamCasualties 为负或超过上限）
 * - 全空条目（attackerName/defenderName/details 全空白且成员/敌人列表为空）
 */
object BattleLogRefRule : SaveValidationRule {
    override val id = "battle_log_ref"
    override val order = 21

    /** 单条日志回合数上限 */
    private const val MAX_BATTLE_TURNS = 100_000

    /** 单条日志队伍伤亡数上限 */
    private const val MAX_TEAM_CASUALTIES = 100

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val kept = data.battleLogs.filter { it.isStructurallyValid() }
        if (kept.size == data.battleLogs.size) return RuleOutcome.Passed

        val removedCount = data.battleLogs.size - kept.size
        return RuleOutcome.Repaired(
            data.copy(battleLogs = kept),
            listOf("清理 $removedCount 条结构非法战斗日志（负回合/负伤亡/非法日期/空条目）")
        )
    }

    /** 条目结构合法性判定 */
    private fun BattleLog.isStructurallyValid(): Boolean {
        if (year < 1 || month !in 1..12) return false
        if (turns < 0 || turns > MAX_BATTLE_TURNS) return false
        if (teamCasualties < 0 || teamCasualties > MAX_TEAM_CASUALTIES) return false
        val blankStub = attackerName.isBlank() && defenderName.isBlank() && details.isBlank() &&
            teamMembers.isEmpty() && enemies.isEmpty()
        return !blankStub
    }
}

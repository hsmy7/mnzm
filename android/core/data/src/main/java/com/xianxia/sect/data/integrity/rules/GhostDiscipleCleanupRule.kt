package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 清理幽灵弟子（name.isBlank() 的弟子记录）。
 *
 * 同时将清理的弟子 ID 写入 [context.removedDiscipleIds]，
 * 供 [GhostRefCleanupRule] 清理挂在幽灵弟子下的引用。
 *
 * 必须排在 [GhostRefCleanupRule] 之前（order=10 < 11）。
 */
object GhostDiscipleCleanupRule : SaveValidationRule {
    override val id = "ghost_disciple_cleanup"
    override val order = 10

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val ghostDiscipleIds = data.disciples
            .filter { it.name.isBlank() }
            .map { it.id }
            .toSet()

        if (ghostDiscipleIds.isEmpty()) return RuleOutcome.Skipped("无幽灵弟子")

        val details = data.disciples
            .filter { it.id in ghostDiscipleIds }
            .map { ghost ->
                // 向 context 写入，供 GhostRefCleanupRule 使用
                context.removedDiscipleIds.add(ghost.id)
                "幽灵弟子 id=${ghost.id}（name=空, age=${ghost.age}, realm=${ghost.realm}）已从存档中清理"
            }

        val cleanedDisciples = data.disciples.filter { it.id !in ghostDiscipleIds }
        return RuleOutcome.Repaired(data.copy(disciples = cleanedDisciples), details)
    }
}

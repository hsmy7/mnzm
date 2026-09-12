package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.data.model.SaveData

/**
 * 净化招募列表（[SaveData.gameData.recruitList]）中的异常条目：
 * 损坏数据 / 同 id 重复 / 同内容双胞胎 / 已入宗门残留。
 *
 * 历史版本 Bug（4.0.49 前幽灵弟子、招募残留等）产生的脏数据会
 * 随存档保留，玩家侧表现为"无肖像且无法招募的幽灵弟子"与
 * "两个完全相同的弟子"。本规则在读档/存档校验时自动修复。
 *
 * 必须排在 [GhostDiscipleCleanupRule]（order=10）之后，
 * 使用已清理过的正式弟子表做跨表残留判定。
 *
 * 规则必须零抛异常——抛异常会被框架转为 Corrupted，阻断读档。
 */
object RecruitListCleanupRule : SaveValidationRule {
    override val id = "recruit_list_cleanup"
    override val order = 20

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val report = RecruitIntegrity.sanitizeRecruitList(
            recruits = data.gameData.recruitList,
            sectDisciples = data.disciples
        )
        if (report.removedCount == 0) return RuleOutcome.Passed

        val details = report.details.map { "招募列表净化: $it" }
        val repaired = data.copy(
            gameData = data.gameData.copy(recruitList = report.cleaned)
        )
        return RuleOutcome.Repaired(repaired, details)
    }
}

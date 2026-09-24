package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 招募链下线：招募列表（[SaveData.gameData.recruitList]）恒空规则。
 *
 * 无论当前 recruitList 是否为空，一律清空并返回 [RuleOutcome.Repaired]
 * （恒空语义以恒定 Repaired 触发落盘），使 recruitList 在读档/存档校验
 * 边界始终保持空表；字段本身保留以兼容三端存档链。
 *
 * 规则必须零抛异常——抛异常会被框架转为 Corrupted，阻断读档。
 */
object RecruitListCleanupRule : SaveValidationRule {
    override val id = "recruit_list_cleanup"
    override val order = 20

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome =
        RuleOutcome.Repaired(
            data.copy(gameData = data.gameData.copy(recruitList = emptyList())),
            listOf("招募链已下线，招募列表清空")
        )
}

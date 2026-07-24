package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查 [SaveData.gameData.sectName] 是否为空或空白。
 * 若为空则设为默认值"青云宗"。
 */
object SectNameRule : SaveValidationRule {
    override val id = "sect_name"
    override val order = 1

    private const val DEFAULT_SECT_NAME = "青云宗"

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        return if (gd.sectName.isBlank()) {
            RuleOutcome.Repaired(
                data.copy(gameData = gd.copy(sectName = DEFAULT_SECT_NAME)),
                listOf("sectName 为空，已设为默认值\"$DEFAULT_SECT_NAME\"")
            )
        } else {
            RuleOutcome.Passed
        }
    }
}

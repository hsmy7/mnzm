package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.data.model.SaveData

/**
 * 数值消毒规则（T11，2026-08-04）。
 *
 * 恶意云档可携带 NaN/Infinity/负值修炼值穿透既有校验：
 * - [CultivationCapRule] 对 NaN 比较恒 false 不截断 → NaN 传播
 * - Infinity → toLong() 饱和 Long.MAX
 * - 负修炼值只封上限不封下限
 *
 * 本规则（order=0，最先执行）对进入数值运算的 Double 字段做
 * `isFinite() && >= 0` 钳制，非有限值或负值重置为 0.0。
 * 必须先于 [CultivationCapRule]（order=5）运行，防止 NaN 穿透 cap 规则。
 *
 * 未变化的字段保持原对象引用（引用相等），保证无变化时不误报 Repaired。
 */
object NumericSanitizeRule : SaveValidationRule {
    override val id = "numeric_sanitize"
    override val order = 0

    /** 非有限值/负值重置的安全默认值 */
    private const val SANITIZED_DEFAULT = 0.0

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val fixedDisciples = data.disciples.map { sanitizeDisciple(it, repairs) }

        // ── gameData 域：sectCultivation + 招募列表 + AI 宗门弟子 ──
        var sectCultivation = data.gameData.sectCultivation
        if (!sectCultivation.isFinite() || sectCultivation < 0.0) {
            repairs.add("宗门修为 sectCultivation=$sectCultivation 非有限或为负，已重置为 0")
            sectCultivation = SANITIZED_DEFAULT
        }
        // 对抗性审查（2026-08-05）：招募列表/AI 宗门弟子必须**全字段**消毒——
        // 原实现只查 cultivation，NaN checkpoint/pill 经招募全字段拷贝进入组件表
        var fixedRecruit: List<Disciple>? = null
        if (data.gameData.recruitList.any { it.hasInvalidNumericFields() }) {
            fixedRecruit = data.gameData.recruitList.map { sanitizeDisciple(it, repairs, "招募列表 ") }
        }
        var fixedAiSects: Map<String, List<Disciple>>? = null
        if (data.gameData.aiSectDisciples.values.any { list ->
                list.any { it.hasInvalidNumericFields() }
            }) {
            fixedAiSects = data.gameData.aiSectDisciples.mapValues { (sect, list) ->
                list.map { sanitizeDisciple(it, repairs, "AI宗门[$sect] ") }
            }
        }

        val gd = data.gameData
        val gdChanged = sectCultivation != gd.sectCultivation || fixedRecruit != null || fixedAiSects != null
        val fixedGd = if (gdChanged) {
            gd.copy(
                sectCultivation = sectCultivation,
                recruitList = fixedRecruit ?: gd.recruitList,
                aiSectDisciples = fixedAiSects ?: gd.aiSectDisciples
            )
        } else {
            gd
        }

        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(
                data.copy(gameData = fixedGd, disciples = fixedDisciples),
                repairs
            )
        } else {
            RuleOutcome.Passed
        }
    }

    /** 弟子三维修炼值 + 丹药 5 项 Double 加成消毒（scope 用于区分招募/AI 宗门来源） */
    private fun sanitizeDisciple(d: Disciple, repairs: MutableList<String>, scope: String = ""): Disciple {
        val label = "${scope}弟子[${d.name.ifBlank { "ID=${d.id}" }}]"
        var modified = false

        var cultivation = d.cultivation
        var checkpoint = d.cultivationCheckpoint
        var speedBonus = d.cultivationSpeedBonus
        if (cultivation.isInvalid()) {
            repairs.add("$label.cultivation=$cultivation 非有限或为负，已重置")
            cultivation = SANITIZED_DEFAULT
            modified = true
        }
        if (checkpoint.isInvalid()) {
            repairs.add("$label.cultivationCheckpoint=$checkpoint 非有限或为负，已重置")
            checkpoint = SANITIZED_DEFAULT
            modified = true
        }
        if (speedBonus.isInvalid()) {
            repairs.add("$label.cultivationSpeedBonus=$speedBonus 非有限或为负，已重置")
            speedBonus = SANITIZED_DEFAULT
            modified = true
        }

        var pill = d.pillEffects
        val pillFields = listOf(
            pill.pillCritRateBonus,
            pill.pillCritEffectBonus,
            pill.pillCultivationSpeedBonus,
            pill.pillSkillExpSpeedBonus,
            pill.pillNurtureSpeedBonus
        )
        if (pillFields.any { it.isInvalid() }) {
            repairs.add("$label.pillEffects 含非有限或负值加成，已重置")
            pill = pill.copy(
                pillCritRateBonus = pill.pillCritRateBonus.sanitize(),
                pillCritEffectBonus = pill.pillCritEffectBonus.sanitize(),
                pillCultivationSpeedBonus = pill.pillCultivationSpeedBonus.sanitize(),
                pillSkillExpSpeedBonus = pill.pillSkillExpSpeedBonus.sanitize(),
                pillNurtureSpeedBonus = pill.pillNurtureSpeedBonus.sanitize()
            )
            modified = true
        }

        return if (modified) {
            d.copy(
                cultivation = cultivation,
                cultivationCheckpoint = checkpoint,
                cultivationSpeedBonus = speedBonus,
                pillEffects = pill
            )
        } else {
            d
        }
    }

    /** 非有限或负值判定 */
    private fun Double.isInvalid(): Boolean = !isFinite() || this < 0.0

    /** 全数值字段合法性判定（与 [sanitizeDisciple] 覆盖范围一致） */
    private fun Disciple.hasInvalidNumericFields(): Boolean {
        val pill = pillEffects
        val pillFields = listOf(
            pill.pillCritRateBonus,
            pill.pillCritEffectBonus,
            pill.pillCultivationSpeedBonus,
            pill.pillSkillExpSpeedBonus,
            pill.pillNurtureSpeedBonus
        )
        return cultivation.isInvalid() || cultivationCheckpoint.isInvalid() ||
            cultivationSpeedBonus.isInvalid() || pillFields.any { it.isInvalid() }
    }

    /** 消毒为安全默认值 */
    private fun Double.sanitize(): Double = if (isInvalid()) SANITIZED_DEFAULT else this
}

package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.PillEffects
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.artifactRefining
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.comprehension
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.mining
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.pillCritEffectBonus
import com.xianxia.sect.core.model.pillCritRateBonus
import com.xianxia.sect.core.model.pillCultivationSpeedBonus
import com.xianxia.sect.core.model.pillEffectDuration
import com.xianxia.sect.core.model.pillHpBonus
import com.xianxia.sect.core.model.pillMagicAttackBonus
import com.xianxia.sect.core.model.pillMagicDefenseBonus
import com.xianxia.sect.core.model.pillMpBonus
import com.xianxia.sect.core.model.pillNurtureSpeedBonus
import com.xianxia.sect.core.model.pillPhysicalAttackBonus
import com.xianxia.sect.core.model.pillPhysicalDefenseBonus
import com.xianxia.sect.core.model.pillRefining
import com.xianxia.sect.core.model.pillSkillExpSpeedBonus
import com.xianxia.sect.core.model.pillSpeedBonus
import com.xianxia.sect.core.model.spiritPlanting
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton



/**
 * 统一的丹药效果应用层。
 *
 * 收敛 [DisciplePillManager.processAutoUsePills]（储物袋自动服用）
 * 和 [DiscipleFacadeImpl] 内联效果应用（手动/奖励服用）
 * 两套逻辑到单一入口，消除手动服用与自动服用的逻辑分叉。
 *
 * 使用方式：
 * - 自动服用路径：DisciplePillManager 内部调用
 * - 手动/奖励路径：调用方组装 Disciple → 调用 → 写回组件表
 */
@GameService("PillEffectApplier")
@Singleton
class PillEffectApplier @Inject constructor() {

    /**
     * 将丹药效果应用到弟子对象（不可变，通过 copy 返回新对象）。
     * 调用方负责：
     * 1. 调用前通过 [DisciplePillManager.canUsePill] 检查资格
     * 2. 调用后扣除丹药库存
     * 3. 将返回的新 Disciple 写回状态层
     */
    fun applyToDisciple(
        disciple: Disciple,
        pillItem: StorageBagItem
    ): Disciple {
        val effect = pillItem.effect ?: return disciple
        val rule = DisciplePillManager.classify(effect)

        var updated = disciple
        updated = applyCultivationEffect(updated, effect)
        updated = applySustainedBonus(updated, effect)
        updated = applyLifeExtend(updated, effect)
        updated = applyPermanentBaseAttr(updated, effect)
        updated = applyUsageTracking(updated, effect, rule)
        updated = applyBattleAttrAndTemp(updated, effect, rule)
        updated = applyHealAndRecover(updated, effect)
        updated = applyClearAll(updated, effect)
        return updated
    }

    // ── 私有辅助函数 ──────────────────────────────────────────────────

    /** 直接修为 / 功法经验加成（可重复服用） */
    private fun applyCultivationEffect(
        disciple: Disciple, effect: ItemEffect
    ): Disciple {
        var updated = disciple
        if (effect.cultivationAdd > 0) {
            updated = updated.copy(
                cultivation = (updated.cultivation + effect.cultivationAdd)
                    .coerceIn(0.0, updated.maxCultivation)
            )
        }
        if (effect.skillExpAdd > 0) {
            updated = updated.copy(
                manualMasteries = updated.manualMasteries.mapValues { (_, v) ->
                    (v + effect.skillExpAdd).coerceAtMost(10000)
                }
            )
        }
        return updated
    }

    /** 持续修炼速度加成（以旬为单位） */
    private fun applySustainedBonus(
        disciple: Disciple, effect: ItemEffect
    ): Disciple {
        if (effect.cultivationSpeedPercent <= 0) return disciple
        return disciple.copy(
            cultivationSpeedBonus = effect.cultivationSpeedPercent,
            cultivationSpeedDuration = if (effect.duration > 0)
                effect.duration else disciple.cultivationSpeedDuration
        )
    }

    /** 延寿效果 */
    private fun applyLifeExtend(
        disciple: Disciple, effect: ItemEffect
    ): Disciple {
        if (effect.extendLife <= 0) return disciple
        var updated = disciple.copy(
            lifespan = disciple.lifespan + effect.extendLife
        )
        if (effect.pillType.isNotEmpty() &&
            effect.pillType !in updated.usage.usedExtendLifePillTypes
        ) {
            updated = updated.copy(
                usage = updated.usage.copy(
                    usedExtendLifePillTypes =
                        updated.usage.usedExtendLifePillTypes + effect.pillType
                )
            )
        }
        return updated
    }

    /** 永久基础属性加成 */
    private fun applyPermanentBaseAttr(
        disciple: Disciple, effect: ItemEffect
    ): Disciple {
        if (!DisciplePillManager.hasAnyBaseAttrAdd(effect)) return disciple
        return disciple.copy(
            skills = disciple.skills.copy(
                intelligence = (disciple.skills.intelligence + effect.intelligenceAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                charm = (disciple.skills.charm + effect.charmAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                loyalty = (disciple.skills.loyalty + effect.loyaltyAdd)
                    .coerceIn(0, GameConfig.Disciple.MAX_LOYALTY),
                comprehension = (disciple.skills.comprehension + effect.comprehensionAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                artifactRefining = (disciple.skills.artifactRefining +
                    effect.artifactRefiningAdd).coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                pillRefining = (disciple.skills.pillRefining + effect.pillRefiningAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                spiritPlanting = (disciple.skills.spiritPlanting + effect.spiritPlantingAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                teaching = (disciple.skills.teaching + effect.teachingAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                morality = (disciple.skills.morality + effect.moralityAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX),
                mining = (disciple.skills.mining + effect.miningAdd)
                    .coerceIn(0, GameConfig.Disciple.SKILL_MAX)
            )
        )
    }

    /** 更新使用记录（永久属性丹的去重 key） */
    private fun applyUsageTracking(
        disciple: Disciple, effect: ItemEffect, rule: PillRule
    ): Disciple {
        if (rule != PillRule.PERMANENT_BASE_ATTR) return disciple
        return disciple.copy(
            usage = disciple.usage.copy(
                usedPermanentPillKeys = disciple.usage.usedPermanentPillKeys +
                    DisciplePillManager.buildUsedKeys(effect, effect.tier)
            )
        )
    }

    /** 临时 / 持续战斗属性与速度加成 */
    private fun applyBattleAttrAndTemp(
        disciple: Disciple, effect: ItemEffect, rule: PillRule
    ): Disciple {
        if (!DisciplePillManager.hasAnyBattleAttrAdd(effect) &&
            effect.cultivationSpeedPercent <= 0 &&
            effect.skillExpSpeedPercent <= 0 &&
            effect.nurtureSpeedPercent <= 0
        ) return disciple

        val isStackingRule = rule == PillRule.SUSTAINED_SPEED ||
            rule == PillRule.TEMPORARY_BATTLE

        return disciple.copy(
            pillEffects = disciple.pillEffects.copy(
                pillPhysicalAttackBonus = effect.physicalAttackAdd,
                pillMagicAttackBonus = effect.magicAttackAdd,
                pillPhysicalDefenseBonus = effect.physicalDefenseAdd,
                pillMagicDefenseBonus = effect.magicDefenseAdd,
                pillHpBonus = effect.hpAdd,
                pillMpBonus = effect.mpAdd,
                pillSpeedBonus = effect.speedAdd,
                pillCritRateBonus = effect.critRateAdd,
                pillCritEffectBonus = effect.critEffectAdd,
                pillCultivationSpeedBonus = effect.cultivationSpeedPercent,
                pillSkillExpSpeedBonus = effect.skillExpSpeedPercent,
                pillNurtureSpeedBonus = effect.nurtureSpeedPercent,
                pillEffectDuration = if (effect.duration > 0)
                    maxOf(disciple.pillEffects.pillEffectDuration, effect.duration)
                else disciple.pillEffects.pillEffectDuration,
                activePillTypes = if (isStackingRule)
                    disciple.pillEffects.activePillTypes + effect.pillType
                else disciple.pillEffects.activePillTypes
            )
        )
    }

    /** 治疗 HP / 恢复 MP */
    private fun applyHealAndRecover(
        disciple: Disciple, effect: ItemEffect
    ): Disciple {
        var updated = disciple

        if (effect.healMaxHpPercent > 0) {
            val maxHp = updated.maxHp
            val rawCurrentHp = updated.combat.currentHp
            val currentHp = if (rawCurrentHp < 0) maxHp else rawCurrentHp
            val healAmount = (maxHp * effect.healMaxHpPercent).toInt()
                .coerceAtLeast(1)
            val newHp = (currentHp + healAmount).coerceAtMost(maxHp)
            updated = updated.copy(combat = updated.combat.copy(currentHp = newHp))
        }

        if (effect.mpRecoverMaxMpPercent > 0) {
            val maxMp = updated.maxMp
            val rawCurrentMp = updated.combat.currentMp
            val currentMp = if (rawCurrentMp < 0) maxMp else rawCurrentMp
            val recoverAmount = (maxMp * effect.mpRecoverMaxMpPercent).toInt()
                .coerceAtLeast(1)
            val newMp = (currentMp + recoverAmount).coerceAtMost(maxMp)
            updated = updated.copy(combat = updated.combat.copy(currentMp = newMp))
        }

        return updated
    }

    /** 清除所有丹药临时效果 */
    private fun applyClearAll(
        disciple: Disciple, effect: ItemEffect
    ): Disciple {
        if (!effect.clearAll) return disciple
        return disciple.copy(pillEffects = PillEffects())
    }
}

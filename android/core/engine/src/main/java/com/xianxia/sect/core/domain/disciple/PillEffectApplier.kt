package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一的丹药效果应用层。
 *
 * 收敛 [DisciplePillManager.applyPillEffect]（储物袋自动服用）
 * 和 [DiscipleFacadeImpl.applyPillEffectsToDisciple]（手动/奖励服用）
 * 两套逻辑到单一入口，消除手动服用与自动服用的逻辑分叉。
 *
 * 使用方式：
 * - 自动服用路径：DisciplePillManager 内部调用
 * - 手动/奖励路径：DiscipleFacadeImpl 组装 Disciple → 调用 → 写回组件表
 */
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
        var updated = disciple
        val rule = DisciplePillManager.classify(effect)

        // ── 直接修为/功法/孕养（可重复） ──
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

        // ── 持续速度加成（以旬为单位） ──
        if (effect.cultivationSpeedPercent > 0) {
            updated = updated.copy(
                cultivationSpeedBonus = effect.cultivationSpeedPercent,
                cultivationSpeedDuration = if (effect.duration > 0)
                    effect.duration else updated.cultivationSpeedDuration
            )
        }

        // ── 延寿 ──
        if (effect.extendLife > 0) {
            updated = updated.copy(lifespan = updated.lifespan + effect.extendLife)
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
        }

        // ── 永久基础属性 ──
        if (DisciplePillManager.hasAnyBaseAttrAdd(effect)) {
            updated = updated.copy(
                skills = updated.skills.copy(
                    intelligence = (updated.skills.intelligence + effect.intelligenceAdd)
                        .coerceAtLeast(0),
                    charm = (updated.skills.charm + effect.charmAdd).coerceAtLeast(0),
                    loyalty = (updated.skills.loyalty + effect.loyaltyAdd).coerceAtLeast(0),
                    comprehension = (updated.skills.comprehension + effect.comprehensionAdd)
                        .coerceAtLeast(0),
                    artifactRefining = (updated.skills.artifactRefining +
                        effect.artifactRefiningAdd).coerceAtLeast(0),
                    pillRefining = (updated.skills.pillRefining + effect.pillRefiningAdd)
                        .coerceAtLeast(0),
                    spiritPlanting = (updated.skills.spiritPlanting + effect.spiritPlantingAdd)
                        .coerceAtLeast(0),
                    teaching = (updated.skills.teaching + effect.teachingAdd).coerceAtLeast(0),
                    morality = (updated.skills.morality + effect.moralityAdd).coerceAtLeast(0),
                    mining = (updated.skills.mining + effect.miningAdd).coerceAtLeast(0)
                )
            )
        }

        // ── 更新使用记录 ──
        when (rule) {
            PillRule.PERMANENT_BASE_ATTR -> {
                updated = updated.copy(
                    usage = updated.usage.copy(
                        usedPermanentPillKeys = updated.usage.usedPermanentPillKeys +
                            DisciplePillManager.buildUsedKeys(effect, effect.tier)
                    )
                )
            }
            PillRule.PERMANENT_LIFE -> {
                // 延寿记录已在 extendLife 块中处理
            }
            else -> {}
        }

        // ── 临时/持续战斗属性和速度加成 ──
        if (DisciplePillManager.hasAnyBattleAttrAdd(effect) ||
            effect.cultivationSpeedPercent > 0 ||
            effect.skillExpSpeedPercent > 0 ||
            effect.nurtureSpeedPercent > 0
        ) {
            updated = updated.copy(
                pillEffects = updated.pillEffects.copy(
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
                        maxOf(updated.pillEffects.pillEffectDuration, effect.duration)
                    else updated.pillEffects.pillEffectDuration,
                    activePillTypes = if (rule == PillRule.SUSTAINED_SPEED ||
                        rule == PillRule.TEMPORARY_BATTLE
                    )
                        updated.pillEffects.activePillTypes + effect.pillType
                    else updated.pillEffects.activePillTypes
                )
            )
        }

        // ── 治疗 ──
        if (effect.healMaxHpPercent > 0) {
            val maxHp = updated.maxHp
            val rawCurrentHp = updated.combat.currentHp
            val currentHp = if (rawCurrentHp < 0) maxHp else rawCurrentHp
            val healAmount = (maxHp * effect.healMaxHpPercent).toInt().coerceAtLeast(1)
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

        if (effect.clearAll) {
            updated = updated.copy(pillEffects = PillEffects())
        }

        return updated
    }
}

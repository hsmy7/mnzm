package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.StorageBagUtils

object DisciplePillManager {

    data class PillUseResult(
        val disciple: Disciple,
        val events: List<String>
    )

    fun processAutoUsePills(
        disciple: Disciple,
        gameYear: Int,
        gameMonth: Int,
        gameDay: Int,
        instantMessage: Boolean = false
    ): PillUseResult {
        val events = mutableListOf<String>()

        if (disciple.equipment.storageBagItems.isEmpty()) {
            return PillUseResult(disciple, events)
        }

        var updatedDisciple = disciple

        val pillItems = disciple.equipment.storageBagItems
            .filter { it.itemType == "pill" }
            .sortedByDescending { it.rarity }

        if (pillItems.isEmpty()) {
            return PillUseResult(updatedDisciple, events)
        }

        for (pillItem in pillItems) {
            val check = canUsePill(updatedDisciple, pillItem)
            if (!check.canUse) continue

            updatedDisciple = applyPillEffect(updatedDisciple, pillItem)

            updatedDisciple = updatedDisciple.copyWith(
                storageBagItems = StorageBagUtils.decreaseItemQuantity(
                    updatedDisciple.equipment.storageBagItems,
                    pillItem.itemId
                )
            )

            val messagePrefix = if (instantMessage) "立即" else "自动"
            events.add("${updatedDisciple.name} ${messagePrefix}使用了丹药 ${pillItem.name}")
        }

        return PillUseResult(updatedDisciple, events)
    }

    data class PillUseCheck(
        val canUse: Boolean,
        val reason: String = ""
    )

    fun canUsePill(disciple: Disciple, pillItem: StorageBagItem): PillUseCheck {
        val effect = pillItem.effect ?: return PillUseCheck(false, "无效果数据")

        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, effect.minRealm)) {
            return PillUseCheck(false, "境界不足")
        }

        if (effect.cannotStack && effect.pillCategory.isNotEmpty()) {
            if (disciple.pillEffects.activePillCategory == effect.pillCategory) {
                return PillUseCheck(false, "同类型丹药生效中")
            }
        }

        if (effect.pillCategory == PillCategory.FUNCTIONAL.name && effect.pillType.isNotEmpty()) {
            if (disciple.usage.usedFunctionalPillTypes.contains(effect.pillType)) {
                return PillUseCheck(false, "已使用过同类功能丹药")
            }
        }

        if (effect.extendLife > 0 && effect.pillType.isNotEmpty()) {
            if (disciple.usage.usedExtendLifePillIds.contains(effect.pillType)) {
                return PillUseCheck(false, "已使用过同类延寿丹药")
            }
        }

        return PillUseCheck(true)
    }

    private fun applyPillEffect(disciple: Disciple, pillItem: StorageBagItem): Disciple {
        val effect = pillItem.effect ?: return disciple
        var updated = disciple

        if (effect.cultivationAdd > 0) {
            updated = updated.copy(cultivation = (updated.cultivation + effect.cultivationAdd).coerceAtLeast(0.0))
        }

        if (effect.skillExpAdd > 0) {
            val updatedMasteries = updated.manualMasteries.mapValues { (_, v) ->
                (v + effect.skillExpAdd).coerceAtMost(10000)
            }
            updated = updated.copy(manualMasteries = updatedMasteries)
        }

        if (effect.cultivationSpeedPercent > 0) {
            updated = updated.copy(
                cultivationSpeedBonus = effect.cultivationSpeedPercent,
                cultivationSpeedDuration = if (effect.duration > 0) effect.duration * 30 else updated.cultivationSpeedDuration
            )
        }

        if (effect.extendLife > 0) {
            updated = updated.copy(lifespan = updated.lifespan + effect.extendLife)
            if (effect.pillType.isNotEmpty() && !updated.usage.usedExtendLifePillIds.contains(effect.pillType)) {
                updated = updated.copy(
                    usage = updated.usage.copy(
                        usedExtendLifePillIds = updated.usage.usedExtendLifePillIds + effect.pillType
                    )
                )
            }
        }

        if (effect.intelligenceAdd > 0 || effect.charmAdd > 0 || effect.loyaltyAdd > 0 ||
            effect.comprehensionAdd > 0 || effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 ||
            effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 || effect.moralityAdd > 0
        ) {
            updated = updated.copy(
                skills = updated.skills.copy(
                    intelligence = (updated.skills.intelligence + effect.intelligenceAdd).coerceIn(1, 100),
                    charm = (updated.skills.charm + effect.charmAdd).coerceIn(1, 100),
                    loyalty = (updated.skills.loyalty + effect.loyaltyAdd).coerceIn(1, 100),
                    comprehension = (updated.skills.comprehension + effect.comprehensionAdd).coerceIn(1, 100),
                    artifactRefining = (updated.skills.artifactRefining + effect.artifactRefiningAdd).coerceIn(1, 100),
                    pillRefining = (updated.skills.pillRefining + effect.pillRefiningAdd).coerceIn(1, 100),
                    spiritPlanting = (updated.skills.spiritPlanting + effect.spiritPlantingAdd).coerceIn(1, 100),
                    teaching = (updated.skills.teaching + effect.teachingAdd).coerceIn(1, 100),
                    morality = (updated.skills.morality + effect.moralityAdd).coerceIn(1, 100)
                )
            )
        }

        if (effect.pillCategory == PillCategory.FUNCTIONAL.name && effect.pillType.isNotEmpty()) {
            updated = updated.copy(
                usage = updated.usage.copy(
                    usedFunctionalPillTypes = updated.usage.usedFunctionalPillTypes + effect.pillType
                )
            )
        }

        if (effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 ||
            effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 ||
            effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 ||
            effect.critRateAdd > 0 || effect.critEffectAdd > 0 ||
            effect.cultivationSpeedPercent > 0 || effect.skillExpSpeedPercent > 0 || effect.nurtureSpeedPercent > 0
        ) {
            val newEffects = updated.pillEffects.copy(
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
                pillEffectDuration = if (effect.duration > 0) effect.duration * 30 else updated.pillEffects.pillEffectDuration,
                activePillCategory = if (effect.cannotStack) effect.pillCategory else updated.pillEffects.activePillCategory
            )
            updated = updated.copy(pillEffects = newEffects)
        }

        if (effect.healMaxHpPercent > 0) {
            val maxHp = updated.maxHp
            val rawCurrentHp = updated.combat.currentHp
            val currentHp = if (rawCurrentHp < 0) maxHp else rawCurrentHp
            val healAmount = (maxHp * effect.healMaxHpPercent).toInt().coerceAtLeast(1)
            val newHp = (currentHp + healAmount).coerceAtMost(maxHp)
            updated = updated.copyWith(currentHp = newHp)
        }

        if (effect.mpRecoverMaxMpPercent > 0) {
            val maxMp = updated.maxMp
            val rawCurrentMp = updated.combat.currentMp
            val currentMp = if (rawCurrentMp < 0) maxMp else rawCurrentMp
            val recoverAmount = (maxMp * effect.mpRecoverMaxMpPercent).toInt().coerceAtLeast(1)
            val newMp = (currentMp + recoverAmount).coerceAtMost(maxMp)
            updated = updated.copyWith(currentMp = newMp)
        }

        if (effect.revive && !disciple.isAlive) {
            updated = updated.copyWith(isAlive = true, currentHp = -1)
        }

        if (effect.clearAll) {
            updated = updated.copy(pillEffects = PillEffects())
        }

        return updated
    }

    fun pillToItemEffect(pill: Pill): ItemEffect {
        return ItemEffect(
            cultivationSpeedPercent = pill.effects.cultivationSpeedPercent,
            skillExpSpeedPercent = pill.effects.skillExpSpeedPercent,
            nurtureSpeedPercent = pill.effects.nurtureSpeedPercent,
            breakthroughChance = pill.effects.breakthroughChance,
            targetRealm = pill.effects.targetRealm,
            cultivationAdd = pill.effects.cultivationAdd,
            skillExpAdd = pill.effects.skillExpAdd,
            nurtureAdd = pill.effects.nurtureAdd,
            healMaxHpPercent = pill.effects.healMaxHpPercent,
            mpRecoverMaxMpPercent = pill.effects.mpRecoverMaxMpPercent,
            hpAdd = pill.effects.hpAdd,
            mpAdd = pill.effects.mpAdd,
            extendLife = pill.effects.extendLife,
            physicalAttackAdd = pill.effects.physicalAttackAdd,
            magicAttackAdd = pill.effects.magicAttackAdd,
            physicalDefenseAdd = pill.effects.physicalDefenseAdd,
            magicDefenseAdd = pill.effects.magicDefenseAdd,
            speedAdd = pill.effects.speedAdd,
            critRateAdd = pill.effects.critRateAdd,
            critEffectAdd = pill.effects.critEffectAdd,
            intelligenceAdd = pill.effects.intelligenceAdd,
            charmAdd = pill.effects.charmAdd,
            loyaltyAdd = pill.effects.loyaltyAdd,
            comprehensionAdd = pill.effects.comprehensionAdd,
            artifactRefiningAdd = pill.effects.artifactRefiningAdd,
            pillRefiningAdd = pill.effects.pillRefiningAdd,
            spiritPlantingAdd = pill.effects.spiritPlantingAdd,
            teachingAdd = pill.effects.teachingAdd,
            moralityAdd = pill.effects.moralityAdd,
            revive = pill.effects.revive,
            clearAll = pill.effects.clearAll,
            isAscension = pill.effects.isAscension,
            duration = pill.effects.duration,
            cannotStack = pill.effects.cannotStack,
            minRealm = pill.minRealm,
            pillCategory = pill.category.name,
            pillType = pill.pillType
        )
    }
}

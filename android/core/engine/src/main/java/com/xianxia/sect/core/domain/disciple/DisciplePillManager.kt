package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.StorageBagUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 丹药分类规则。
 * priority 决定自动服用时的排序优先级（值越大越优先）。
 */
enum class PillRule(val priority: Int) {
    /** 永久基础属性丹：按 tier+effectField 终身限一次 */
    PERMANENT_BASE_ATTR(4),
    /** 延寿丹：按 pillType 终身限一次 */
    PERMANENT_LIFE(4),
    /** 永久战斗属性丹（预留，当前没有） */
    PERMANENT_BATTLE(4),
    /** 直接修为/功法/孕养丹：可重复服用 */
    INSTANT_CULTIVATION(3),
    /** 持续增益丹：按 pillType 不可叠加 */
    SUSTAINED_SPEED(2),
    /** 临时战斗属性丹：按 pillType 不可叠加 */
    TEMPORARY_BATTLE(1),
    /** 突破丹：可重复，失败可再吃 */
    BREAKTHROUGH(0)
}

@GameService("DisciplePillManager")
@Singleton
class DisciplePillManager @Inject constructor(
    private val pillEffectApplier: PillEffectApplier
) {

    data class PillUseResult(
        val disciple: Disciple,
        val events: List<String>
    )

    // ── 自动服用（主入口）──────────────────────────────────────────

    fun processAutoUsePills(
        disciple: Disciple,
        gameYear: Int,
        gameMonth: Int,
        gamePhase: Int,
        instantMessage: Boolean = false
    ): PillUseResult {
        val events = mutableListOf<String>()

        if (disciple.equipment.storageBagItems.isEmpty()) {
            return PillUseResult(disciple, events)
        }

        var updatedDisciple = disciple

        // 按规则优先级 > 品阶排序：永久 → 直接修为 → 持续/临时 → 突破
        val pillItems = disciple.equipment.storageBagItems
            .filter { it.itemType == "pill" && it.effect != null }
            .sortedWith(
                compareByDescending<StorageBagItem> {
                    val effect = checkNotNull(it.effect) {
                        "Effect was null for pill ${it.name}"
                    }
                    classify(effect).priority
                }
                    .thenByDescending { it.rarity }
            )

        if (pillItems.isEmpty()) {
            return PillUseResult(updatedDisciple, events)
        }

        for (pillItem in pillItems) {
            val check = canUsePill(updatedDisciple, pillItem)
            if (!check.canUse) continue

            updatedDisciple = pillEffectApplier.applyToDisciple(
                updatedDisciple, pillItem
            )

            updatedDisciple = updatedDisciple.copy(
                equipment = updatedDisciple.equipment.copy(
                    storageBagItems = StorageBagUtils.decreaseItemQuantity(
                        updatedDisciple.equipment.storageBagItems,
                        pillItem.itemId
                    )
                )
            )

            val messagePrefix = if (instantMessage) "立即" else "自动"
            events.add("${updatedDisciple.name} ${messagePrefix}使用了丹药 ${pillItem.name}")
        }

        return PillUseResult(updatedDisciple, events)
    }

    // ── 服用资格检查 ──────────────────────────────────────────────

    data class PillUseCheck(
        val canUse: Boolean,
        val reason: String = ""
    )

    fun canUsePill(disciple: Disciple, pillItem: StorageBagItem): PillUseCheck {
        val effect = pillItem.effect ?: return PillUseCheck(false, "无效果数据")

        if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, effect.minRealm)) {
            return PillUseCheck(false, "境界不足")
        }

        return when (classify(effect)) {
            PillRule.PERMANENT_BASE_ATTR -> {
                val usedKeys = buildUsedKeys(effect, effect.tier)
                if (usedKeys.any { it in disciple.usage.usedPermanentPillKeys })
                    PillUseCheck(false, "已服用过同类属性丹药")
                else PillUseCheck(true)
            }
            PillRule.PERMANENT_LIFE -> {
                if (effect.pillType in disciple.usage.usedExtendLifePillTypes)
                    PillUseCheck(false, "已服用过同类延寿丹药")
                else PillUseCheck(true)
            }
            PillRule.SUSTAINED_SPEED, PillRule.TEMPORARY_BATTLE -> {
                if (effect.pillType in disciple.pillEffects.activePillTypes)
                    PillUseCheck(false, "同类型丹药效果生效中")
                else PillUseCheck(true)
            }
            PillRule.INSTANT_CULTIVATION,
            PillRule.BREAKTHROUGH,
            PillRule.PERMANENT_BATTLE -> PillUseCheck(true)
        }
    }

    // ── Pill → ItemEffect 转换 ──────────────────────────────────────

    fun pillToItemEffect(pill: Pill): ItemEffect {
        return ItemEffect(
            tier = pill.rarity,  // rarity 直接映射为品阶
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
            miningAdd = pill.effects.miningAdd,
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

    // ── 公开辅助函数 ──────────────────────────────────────────────

    companion object {
        /**
         * 根据丹药效果分类到对应规则。
         */
        fun classify(effect: ItemEffect): PillRule = when (effect.pillType) {
            "extendLife" -> PillRule.PERMANENT_LIFE
            "cultivationAdd", "skillExpAdd", "nurtureAdd" ->
                PillRule.INSTANT_CULTIVATION
            "cultivationSpeed", "skillExpSpeed", "nurtureSpeed" ->
                PillRule.SUSTAINED_SPEED
            "breakthrough" -> PillRule.BREAKTHROUGH
            else -> {
                if (hasAnyBaseAttrAdd(effect)) PillRule.PERMANENT_BASE_ATTR
                else if (hasAnyBattleAttrAdd(effect)) PillRule.TEMPORARY_BATTLE
                else throw IllegalStateException(
                    "未定义规则的丹药：pillType=${effect.pillType}, " +
                        "pillCategory=${effect.pillCategory}"
                )
            }
        }

        /**
         * 是否有任何基础属性（非战斗）加成。
         */
        fun hasAnyBaseAttrAdd(effect: ItemEffect): Boolean {
            return effect.intelligenceAdd > 0 || effect.charmAdd > 0 ||
                effect.loyaltyAdd > 0 || effect.comprehensionAdd > 0 ||
                effect.artifactRefiningAdd > 0 || effect.pillRefiningAdd > 0 ||
                effect.spiritPlantingAdd > 0 || effect.teachingAdd > 0 ||
                effect.moralityAdd > 0 || effect.miningAdd > 0
        }

        /**
         * 是否有任何战斗属性加成（含速度、暴击等）。
         */
        fun hasAnyBattleAttrAdd(effect: ItemEffect): Boolean {
            return effect.physicalAttackAdd > 0 || effect.magicAttackAdd > 0 ||
                effect.physicalDefenseAdd > 0 || effect.magicDefenseAdd > 0 ||
                effect.hpAdd > 0 || effect.mpAdd > 0 || effect.speedAdd > 0 ||
                effect.critRateAdd > 0 || effect.critEffectAdd > 0
        }

        /**
         * 根据 ItemEffect 中所有非零基础属性字段生成去重 key 集合。
         * key 格式："tier#fieldName"，如 "1#intelligence"。
         */
        fun buildUsedKeys(effect: ItemEffect, tier: Int): Set<String> {
            val fields = mutableListOf<String>()
            if (effect.intelligenceAdd > 0) fields += "intelligence"
            if (effect.charmAdd > 0) fields += "charm"
            if (effect.loyaltyAdd > 0) fields += "loyalty"
            if (effect.comprehensionAdd > 0) fields += "comprehension"
            if (effect.artifactRefiningAdd > 0) fields += "artifactRefining"
            if (effect.pillRefiningAdd > 0) fields += "pillRefining"
            if (effect.spiritPlantingAdd > 0) fields += "spiritPlanting"
            if (effect.teachingAdd > 0) fields += "teaching"
            if (effect.moralityAdd > 0) fields += "morality"
            if (effect.miningAdd > 0) fields += "mining"
            return fields.map { "$tier#$it" }.toSet()
        }
    }
}

package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.storageBagItems
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
        val disciple: Disciple
    )

    // ── 自动服用（主入口）──────────────────────────────────────────

    /**
     * 自动服用储物袋丹药（非突破/非战斗临时丹）。
     *
     * @param nurtureEffect 孕养度丹（nurtureAdd）效果回调——
     *                      由调用方把 N 点孕养度均分到已装备装备实例
     *                      （PillEffectApplier 无装备实例访问权，故经回调注入）
     */
    @Suppress("ReturnCount")  // 空袋/无可服丹药/成功 三出口
    fun processAutoUsePills(
        disciple: Disciple,
        nurtureEffect: ((Int) -> Unit)? = null
    ): PillUseResult {
        if (disciple.equipment.storageBagItems.isEmpty()) {
            return PillUseResult(disciple)
        }

        var updatedDisciple = disciple

        // 按规则优先级 > 品阶排序：永久 → 直接修为 → 持续/临时
        // 突破丹由 DiscipleBreakthroughHandler 内联处理，不在此处消费；
        // 战斗临时丹不自动服用（保留手动/战前结算）
        val pillItems = disciple.equipment.storageBagItems
            .filter { it.itemType == "pill" && it.effect != null }
            .filterNot {
                val effect = checkNotNull(it.effect) { "Effect null: ${it.name}" }
                val rule = classify(effect)
                rule == PillRule.BREAKTHROUGH || rule == PillRule.TEMPORARY_BATTLE
            }
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
            return PillUseResult(updatedDisciple)
        }

        for (pillItem in pillItems) {
            updatedDisciple = tryConsumePill(updatedDisciple, pillItem, nurtureEffect)
                ?: continue
        }

        return PillUseResult(updatedDisciple)
    }

    /**
     * 单颗丹药尝试服用（资格/浪费门槛判定；跳过返回 null）。
     * 满修为/全功法满级时不浪费修为丹/功法经验丹
     */
    @Suppress("ReturnCount")  // 资格拒绝/效果缺失/浪费跳过/成功 四出口
    private fun tryConsumePill(
        disciple: Disciple,
        pillItem: StorageBagItem,
        nurtureEffect: ((Int) -> Unit)?
    ): Disciple? {
        val check = canUsePill(disciple, pillItem)
        if (!check.canUse) return null

        val effect = pillItem.effect ?: return null
        if (isWastefulUse(disciple, effect)) return null

        var updated = disciple
        // 孕养度丹效果（装备实例均分由调用方执行）
        if (effect.nurtureAdd > 0) {
            nurtureEffect?.invoke(effect.nurtureAdd)
        }

        updated = pillEffectApplier.applyToDisciple(updated, pillItem)

        return updated.copy(
            equipment = updated.equipment.copy(
                storageBagItems = StorageBagUtils.decreaseItemQuantity(
                    updated.equipment.storageBagItems,
                    pillItem.itemId
                )
            )
        )
    }

    /**
     * 满修为/全功法满级时服用即浪费。
     */
    @Suppress("ReturnCount")  // 修为满/功法满/不浪费 三出口
    private fun isWastefulUse(disciple: Disciple, effect: ItemEffect): Boolean {
        if (effect.cultivationAdd > 0 &&
            disciple.cultivation >= disciple.maxCultivation
        ) {
            return true
        }
        if (effect.skillExpAdd > 0) {
            val allMaxed = disciple.manualMasteries.isNotEmpty() &&
                disciple.manualMasteries.all { it.value >= MANUAL_MASTERY_CAP }
            if (allMaxed) return true
        }
        return false
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

        // 治疗/回蓝丹按需服用——满血/满蓝不自动服用
        // （口径 = applyHealAndRecover 同源 getBaseStats maxHp/maxMp）
        if (isHealGatingBlocked(disciple, effect)) {
            return PillUseCheck(false, "当前状态已满")
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

    /**
     * 治疗/回蓝丹满状态拦截（canUsePill 内部判定）。
     * 口径 = applyHealAndRecover 同源 getBaseStats maxHp/maxMp（与 C++
     * pill_system::healGatingBlocked 逐位一致）。
     */
    @Suppress("ReturnCount")  // 无治疗效果/满血/满蓝/可通过 四出口
    private fun isHealGatingBlocked(disciple: Disciple, effect: ItemEffect): Boolean {
        if (effect.healMaxHpPercent <= 0.0 && effect.mpRecoverMaxMpPercent <= 0.0) {
            return false
        }
        val maxHp = disciple.maxHp
        val maxMp = disciple.maxMp
        if (effect.healMaxHpPercent > 0.0) {
            val curHp = if (disciple.combat.currentHp < 0) maxHp else disciple.combat.currentHp
            if (curHp >= maxHp) return true
        }
        if (effect.mpRecoverMaxMpPercent > 0.0) {
            val curMp = if (disciple.combat.currentMp < 0) maxMp else disciple.combat.currentMp
            if (curMp >= maxMp) return true
        }
        return false
    }

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

        private const val TAG = "DisciplePillManager"

        /** 功法熟练度上限（ManualProficiencySystem.MAX_PROFICIENCY 场景门槛 10000） */
        private const val MANUAL_MASTERY_CAP = 10000

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
                else if (hasAnyHealingEffect(effect)) PillRule.INSTANT_CULTIVATION
                else {
                    DomainLog.w(TAG, "未分类丹药，默认降级为可重复服用: " +
                        "pillType=${effect.pillType}, " +
                        "pillCategory=${effect.pillCategory}")
                    PillRule.INSTANT_CULTIVATION
                }
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
         * 是否有治疗/复活/清除效果（含回血、回蓝）。
         */
        fun hasAnyHealingEffect(effect: ItemEffect): Boolean {
            return effect.healMaxHpPercent > 0.0 ||
                effect.mpRecoverMaxMpPercent > 0.0 ||
                effect.revive || effect.clearAll
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

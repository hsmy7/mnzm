package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.PillRule
import com.xianxia.sect.core.model.ItemEffect
import com.xianxia.sect.core.model.secretRealmMemberIds
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动丹药服务。
 *
 * 职责：
 * - 实时轨自动服用储物袋中的非突破丹药
 * - 丹药指纹检测与查重
 * - 丹药消费结果写回组件表
 */
@Singleton
@GameService("AutoPillService")
class AutoPillService @Inject constructor(
    private val pillManager: DisciplePillManager,
    private val lawEnforcementProcessor: LawEnforcementProcessor
) {

    /**
     * 实时轨专用：自动服用储物袋中的非突破丹药。
     *
     * 突破丹由 [DiscipleBreakthroughHandler.performBreakthrough]
     * 内联处理，不在此方法中消费。
     *
     * 采用每弟子储物袋丹药指纹检测：直接读取
     * [DiscipleTables.storageBagItems] 组件列判断是否有丹药，
     * 无丹药弟子跳过 [DiscipleTables.assemble]，避免分配开销。
     *
     * @param state 可变游戏状态
     */
    fun processRealtimeAutoPills(
        state: MutableGameState
    ) {
        val tables = state.discipleTables
        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        // 远古秘境：探索中弟子不自动服用丹药（不可突破、不可恢复状态）
        val secretRealmMemberIds = state.gameData.secretRealmMemberIds()
        for (id in tables.ids) {
            // 死亡弟子/远古秘境探索中弟子/无可用丹药弟子跳过
            if (tables.isAlive[id] != 1 || id in secretRealmMemberIds || !hasUsablePills(id, tables)) {
                continue
            }

            val disciple = tables.assemble(id)
            val result = pillManager.processAutoUsePills(
                disciple,
                // 孕养度丹效果——装备实例均分（余数给第一件）
                nurtureEffect = { amount -> applyNurtureAddToEquipped(state, id, amount) }
            )
            if (result.disciple != disciple) {
                writePillResultToTables(id, result.disciple, tables, state)
                // Checkpoint：丹药可能改变修炼速率（持续加速/瞬间增长），同步检查点
                tables.checkpointDisciple(id, currentMonth)
            }
        }
    }

    /**
     * 孕养度丹效果：N 点均分到已装备装备实例
     * （向下取整，余数给第一件；满级装备跳过，该件增益不累积）。
     * 无装备实例时零效果（丹药照常扣除，与 C++ auto_gear 同源语义）。
     */
    private fun applyNurtureAddToEquipped(
        state: MutableGameState,
        discipleId: Int,
        amount: Int
    ) {
        if (amount <= 0) return
        val disciple = state.discipleTables.assemble(discipleId)
        val equippedIds = listOfNotNull(
            disciple.equipment.weaponId.takeIf { it.isNotEmpty() },
            disciple.equipment.armorId.takeIf { it.isNotEmpty() },
            disciple.equipment.bootsId.takeIf { it.isNotEmpty() },
            disciple.equipment.accessoryId.takeIf { it.isNotEmpty() }
        )
        if (equippedIds.isEmpty()) return
        val per = amount / equippedIds.size
        val remainder = amount % equippedIds.size
        equippedIds.forEachIndexed { index, eqId ->
            val gain = per + if (index == 0) remainder else 0
            if (gain <= 0) return@forEachIndexed
            state.equipmentInstances.update(eqId) { eq ->
                EquipmentNurtureSystem.updateNurtureExp(eq, gain.toDouble()).equipment
            }
        }
    }

    // ── processRealtimeAutoPills 辅助方法 ──────────────────────

    /**
     * 指纹检测：储物袋中是否有可服用的非突破丹药。
     *
     * 排除规则：
     * - 突破丹：由 [DiscipleBreakthroughHandler] 内联处理
     * - 战斗临时丹：不自动服用
     * - 满血哨兵治疗丹（保守指纹）：currentHp < 0（-1 哨兵）
     *   视为满血排除——完整满血判定需 getBaseStats maxHp（列不可达），
     *   由 canUsePill 兜底精确判定（性能近似、行为等价，与 C++ 指纹
     *   结构一致——C++ 侧因已物化弟子可做完整判定）
     * - 已服用过的永久属性丹：通过 [DiscipleTables.usedPermanentPillKeys] 查重
     * - 已服用过的延寿丹：通过 [DiscipleTables.usedExtendLifePillTypes] 查重
     *
     * @return true 表示有至少一颗可自动服用的丹药
     */
    private fun hasUsablePills(
        id: Int,
        tables: DiscipleTables
    ): Boolean {
        val items = tables.storageBagItems.getOrNull(id) ?: return false
        val usedPermanentKeys =
            tables.usedPermanentPillKeys.getOrNull(id).orEmpty()
        val usedExtendLifeTypes =
            tables.usedExtendLifePillTypes.getOrNull(id).orEmpty()

        return items.any { item ->
            if (item.itemType != "pill") return@any false
            val effect = item.effect ?: return@any false
            when (DisciplePillManager.classify(effect)) {
                PillRule.BREAKTHROUGH -> return@any false
                PillRule.TEMPORARY_BATTLE -> return@any false
                PillRule.PERMANENT_BASE_ATTR -> {
                    val keys = DisciplePillManager.buildUsedKeys(
                        effect, effect.tier
                    )
                    keys.none { it in usedPermanentKeys }
                }
                PillRule.PERMANENT_LIFE ->
                    effect.pillType !in usedExtendLifeTypes
                else -> {
                    // C1：满血/满蓝治疗丹指纹排除（与 canUsePill 同源口径）
                    if (isHealPillBlockedByFingerprint(id, tables, effect)) {
                        return@any false
                    }
                    true
                }
            }
        }
    }

    /**
     * 保守指纹：满血哨兵（currentHp < 0 = -1）才排除治疗丹——
     * 完整满血判定需 getBaseStats maxHp（列不可达），由 canUsePill 兜底精确判定
     * （性能近似、行为等价，与 C++ 指纹结构一致——C++ 侧因已物化弟子可做完整判定）。
     */
    @Suppress("ReturnCount")  // 满血/满蓝/可通过 三出口
    private fun isHealPillBlockedByFingerprint(
        id: Int,
        tables: DiscipleTables,
        effect: ItemEffect
    ): Boolean {
        if (effect.healMaxHpPercent > 0.0 &&
            (tables.currentHps.getOrNull(id) ?: 0) < 0
        ) {
            return true
        }
        if (effect.mpRecoverMaxMpPercent > 0.0 &&
            (tables.currentMps.getOrNull(id) ?: 0) < 0
        ) {
            return true
        }
        return false
    }

    /**
     * 将丹药消费结果写回 [DiscipleTables]。
     *
     * 覆盖 [PillEffectApplier.applyToDisciple] 可能修改的全部字段，
     * 包括储物袋、修为、技能、PillEffects、使用追踪、HP/MP。
     */
    private fun writePillResultToTables(
        id: Int,
        d: com.xianxia.sect.core.model.Disciple,
        tables: DiscipleTables,
        state: MutableGameState
    ) {
        tables.storageBagItems[id] = d.equipment.storageBagItems
        tables.cultivations[id] = d.cultivation
        tables.manualMasteries[id] = d.manualMasteries
        // 丹药修炼速度加成统一收敛于 pillEffects 体系——
        // 清零旧 cultivationSpeedBonus 组件列（旧档残留数据自愈），
        // 防止旧档残留加成继续影响速率
        tables.cultivationSpeedBonuses[id] = 0.0
        tables.cultivationSpeedDurations[id] = 0
        tables.lifespans[id] = d.lifespan
        // 技能字段（永久属性丹）
        tables.intelligences[id] = d.skills.intelligence
        tables.charms[id] = d.skills.charm
        tables.loyalties[id] = d.skills.loyalty
        tables.comprehensions[id] = d.skills.comprehension
        tables.artifactRefinings[id] = d.skills.artifactRefining
        tables.pillRefinings[id] = d.skills.pillRefining
        tables.spiritPlantings[id] = d.skills.spiritPlanting
        tables.teachings[id] = d.skills.teaching
        tables.moralities[id] = d.skills.morality
        // 道德变化后即时触发偷盗判定（事务内版本）
        if (d.skills.morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD) {
            lawEnforcementProcessor.processSingleDiscipleTheft(id, state)
        }
        tables.minings[id] = d.skills.mining
        // PillEffects 字段
        tables.pillPhysicalAttackBonuses[id] =
            d.pillEffects.pillPhysicalAttackBonus
        tables.pillMagicAttackBonuses[id] =
            d.pillEffects.pillMagicAttackBonus
        tables.pillPhysicalDefenseBonuses[id] =
            d.pillEffects.pillPhysicalDefenseBonus
        tables.pillMagicDefenseBonuses[id] =
            d.pillEffects.pillMagicDefenseBonus
        tables.pillHpBonuses[id] = d.pillEffects.pillHpBonus
        tables.pillMpBonuses[id] = d.pillEffects.pillMpBonus
        tables.pillSpeedBonuses[id] = d.pillEffects.pillSpeedBonus
        tables.pillCritRateBonuses[id] =
            d.pillEffects.pillCritRateBonus
        tables.pillCritEffectBonuses[id] =
            d.pillEffects.pillCritEffectBonus
        tables.pillCultivationSpeedBonuses[id] =
            d.pillEffects.pillCultivationSpeedBonus
        tables.pillSkillExpSpeedBonuses[id] =
            d.pillEffects.pillSkillExpSpeedBonus
        tables.pillNurtureSpeedBonuses[id] =
            d.pillEffects.pillNurtureSpeedBonus
        tables.pillEffectDurations[id] =
            d.pillEffects.pillEffectDuration
        tables.activePillTypes[id] = d.pillEffects.activePillTypes
        // 使用追踪
        tables.usedPermanentPillKeys[id] =
            d.usage.usedPermanentPillKeys
        tables.usedExtendLifePillTypes[id] =
            d.usage.usedExtendLifePillTypes
        // HP/MP（治疗丹）
        tables.currentHps[id] = d.combat.currentHp
        tables.currentMps[id] = d.combat.currentMp
    }
}

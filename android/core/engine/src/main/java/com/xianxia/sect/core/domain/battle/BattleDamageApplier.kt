package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.util.BattleCalculator

/**
 * 共享伤害应用层（2026-08-04 双引擎收敛）。
 *
 * BattleSystem（主战斗）与 AISectAttackManager（宗门战）共用：
 * 护盾吸收（含护盾余量写回）、伤害分摊、伤害链接的"计算 + 更新目标"编排。
 * 纯计算在 [BattleCalculator]，本类仅做"应用到 Combatant"的状态转换，
 * 保证两引擎受击语义一致（此前 AI 引擎直接扣血、无护盾/分摊/链接）。
 *
 * 阵营语义与 BattleSystem 一致：team = DEFENDER 阵营，beasts = ATTACKER 阵营。
 */
object BattleDamageApplier {

    /**
     * 对目标应用一次伤害：护盾吸收 → 扣血 → 护盾余量写回。
     *
     * @param target 目标当前状态（调用方从列表中取出的最新实例）
     * @param damage 结算后的原始伤害（未扣除护盾）
     * @return 更新后的目标（hp 与护盾 buff 值）
     */
    fun applyDamageToTarget(target: Combatant, damage: Int): Combatant {
        val shieldResult = BattleCalculator.calculateShieldAbsorption(target, damage)
        val updated = target.copy(hp = maxOf(0, target.hp - shieldResult.remainingDamage))
        val shieldBuff = shieldResult.shieldBuff ?: return updated
        // 护盾余量按比例写回（剩余护盾值 / 当前 maxHp）。
        // 对抗性审查修复：按 value 匹配被消耗的护盾而非 remainingDuration——
        // 同剩余时长的多个护盾（如龟甲术 0.15 + 水盾 0.3 均 3 回合）此前会被一并
        // 覆写为同一余量值，护盾总量提前耗尽
        return updated.copy(
            buffs = updated.buffs.map { b ->
                if (b.type == BuffType.SHIELD && b.value == shieldBuff.value) {
                    b.copy(value = shieldResult.remainingShield.toDouble() / updated.maxHp.coerceAtLeast(1))
                } else b
            }
        )
    }

    /**
     * 伤害分摊：将伤害按分担规则重分配给分担者（含各自的护盾吸收与余量写回）。
     *
     * @param target 被攻击目标（快照或当前均可，仅用于 id/side/damage）
     * @param team DEFENDER 阵营列表
     * @param beasts ATTACKER 阵营列表
     * @return 需要更新的分担者 id → 更新后的 Combatant
     */
    fun applySharedDamage(
        target: Combatant,
        damage: Int,
        team: List<Combatant>,
        beasts: List<Combatant>
    ): Map<String, Combatant> {
        val shareDmg = BattleCalculator.calculateDamageShare(
            target.id, target.side, damage, team, beasts
        )
        if (shareDmg.isEmpty()) return emptyMap()
        val updated = mutableMapOf<String, Combatant>()
        for ((sharerId, shareDamage) in shareDmg) {
            val sharer = team.firstOrNull { it.id == sharerId }
                ?: beasts.firstOrNull { it.id == sharerId } ?: continue
            updated[sharerId] = applyDamageToTarget(sharer, shareDamage)
        }
        return updated
    }

    /**
     * 伤害链接：将链接伤害分配给被链接的敌人（仅一个）。
     *
     * @param attacker 攻击方（用于定位其敌人阵营）
     * @param team DEFENDER 阵营列表
     * @param beasts ATTACKER 阵营列表
     * @return 需要更新的链接者 id → 更新后的 Combatant
     */
    fun applyLinkedDamage(
        attacker: Combatant,
        target: Combatant,
        damage: Int,
        team: List<Combatant>,
        beasts: List<Combatant>
    ): Map<String, Combatant> {
        val linkedDmg = BattleCalculator.calculateLinkedDamage(
            attacker, target, damage, beasts, team
        )
        if (linkedDmg.isEmpty()) return emptyMap()
        val updated = mutableMapOf<String, Combatant>()
        for ((linkedId, linkDmg) in linkedDmg) {
            val linked = team.firstOrNull { it.id == linkedId }
                ?: beasts.firstOrNull { it.id == linkedId } ?: continue
            updated[linkedId] = applyDamageToTarget(linked, linkDmg)
        }
        return updated
    }
}

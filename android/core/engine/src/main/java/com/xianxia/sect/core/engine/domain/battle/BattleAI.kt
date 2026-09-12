@file:Suppress("TooManyFunctions") // 提取的私有辅助函数集中于本文件，文件级函数数为拆分的固有代价
package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.util.DeterministicRng

/**
 * 统一战斗 AI —— 所有单位（玩家弟子、AI 弟子、妖兽、任务敌人、洞府、
 * 天道试炼及未来新增敌人）在自动战斗中共享同一套决策逻辑。
 *
 * 架构：8 层级联优先级 + 概率衰减（崩溃星铁 / AFK 系列同款模式）
 *
 * 目标选择基于 FDG 2014 威胁排序公式：
 *   threat = attack × (maxHp − currentHp)
 *
 * 智能治愈基于 IEEE CIG 2016 启发式：
 *   仅在治愈能实质性延长盟友存活时才使用，避免浪费行动机会。
 */
object BattleAI {

    // === 可调阈值 ===

    /** HP 低于此比例触发保命机制 */
    const val SELF_PRESERVE_HP = 0.25

    /** 敌人 HP 低于此比例触发斩杀判断 */
    const val EXECUTE_HP = 0.30

    /** 盟友 HP 低于此比例触发支援 */
    const val SUPPORT_ALLY_HP = 0.40

    /** MP 低于此比例进入省蓝模式 */
    const val MP_SAVE = 0.30

    /** 存活敌人数 ≥ 此值才考虑 AOE */
    const val AOE_MIN_TARGETS = 3

    // 各层概率（0.0 ~ 1.0）
    const val PROB_SELF_PRESERVE = 0.90
    const val PROB_EXECUTE = 0.85
    const val PROB_SUPPORT_ALLY = 0.80
    const val PROB_TEAM_BUFF = 0.60
    const val PROB_CONTROL = 0.60
    const val PROB_AOE = 0.70

    // 目标选择概率
    const val PROB_TARGET_LOW_HP = 0.70
    const val PROB_TARGET_HIGH_THREAT = 0.50
    const val PROB_TARGET_LOW_DEF = 0.40

    // === 行动类型 ===

    enum class AIActionType {
        SKILL_ATTACK_SINGLE,
        SKILL_ATTACK_AOE,
        SKILL_HEAL_SELF,
        SKILL_HEAL_ALLY,
        SKILL_HEAL_TEAM,
        SKILL_BUFF_SELF,
        SKILL_BUFF_ALLY,
        SKILL_BUFF_TEAM,
        NORMAL_ATTACK,
        NONE
    }

    data class AIAction(
        val skill: CombatSkill?,
        val target: Combatant?,
        val actionType: AIActionType
    ) {
        companion object {
            fun none() = AIAction(null, null, AIActionType.NONE)
        }
    }

    // === 公开 API ===

    /**
     * 主决策入口。所有单位（不分阵营）调用此方法获取下一步行动。
     *
     * @param unit    当前行动单位
     * @param allies  所有存活盟友（含 unit 自身）
     * @param enemies 所有存活敌人
     * @param rng  确定性 RNG（保证存档/读档后随机序列一致）
     */
    fun decideAction(
        unit: Combatant,
        allies: List<Combatant>,
        enemies: List<Combatant>,
        rng: DeterministicRng,
        playerDamageModifier: Double = 1.0
    ): AIAction {
        // 快速退出：已死亡或被控（两检查点间无 RNG 消费/副作用，合并守卫等价）
        if (unit.isDead || unit.hasControlEffect) return AIAction.none()

        val aliveAllies = allies.filter { !it.isDead }
        val aliveEnemies = enemies.filter { !it.isDead }
        if (aliveEnemies.isEmpty()) return AIAction.none()

        // ---- Tier 1: 被控检查 ----
        val isSilenced = unit.buffs.any {
            it.type == BuffType.SILENCE
        }

        // 筛选可用技能
        val usableSkills = if (isSilenced) emptyList()
        else unit.skills.filter {
            it.currentCooldown <= 0 && unit.mp >= it.mpCost
        }

        val attackSkills = usableSkills.filter {
            it.damageMultiplier > 0
        }
        val supportSkills = usableSkills.filter {
            it.damageMultiplier <= 0
        }

        // ---- Tier 2-3: 紧急行动（保命/斩杀）----
        tryEmergencyActions(
            unit = unit,
            supportSkills = supportSkills,
            attackSkills = attackSkills,
            aliveEnemies = aliveEnemies,
            rng = rng,
            playerDamageModifier = playerDamageModifier
        )?.let { return it }

        // ---- Tier 4-7: 机会行动（支援/团队Buff/控制/AOE）----
        tryOpportunityActions(
            unit = unit,
            aliveAllies = aliveAllies,
            aliveEnemies = aliveEnemies,
            usableSkills = usableSkills,
            supportSkills = supportSkills,
            attackSkills = attackSkills,
            rng = rng
        )?.let { return it }

        // ---- Tier 8-10: 攻击决策 ----
        return decideAttackAction(
            unit, aliveEnemies, attackSkills, rng
        )
    }

    /**
     * Tier 2-3 紧急行动：保命 (HP < 25%) + 斩杀 (敌 HP < 30%)。
     * RNG 消费顺序与次数与拆分前完全一致（逐层短路返回）。
     */
    @Suppress("ReturnCount")
    private fun tryEmergencyActions(
        unit: Combatant,
        supportSkills: List<CombatSkill>,
        attackSkills: List<CombatSkill>,
        aliveEnemies: List<Combatant>,
        rng: DeterministicRng,
        playerDamageModifier: Double
    ): AIAction? {
        // ---- Tier 2: 保命 (HP < 25%) ----
        if (unit.hpPercent < SELF_PRESERVE_HP &&
            rng.nextDouble() < PROB_SELF_PRESERVE
        ) {
            val selfAction = findSelfPreservation(unit, supportSkills)
            if (selfAction != null) return selfAction
        }

        // ---- Tier 3: 斩杀 (敌 HP < 30%) ----
        if (rng.nextDouble() < PROB_EXECUTE && attackSkills.isNotEmpty()) {
            val executeAction = findExecuteTarget(
                unit, aliveEnemies, attackSkills, playerDamageModifier
            )
            if (executeAction != null) return executeAction
        }
        return null
    }

    /**
     * Tier 4-7 机会行动：支援盟友/团队Buff/控制/AOE。
     * RNG 消费顺序与次数与拆分前完全一致（逐层短路返回）。
     */
    @Suppress("ReturnCount")
    private fun tryOpportunityActions(
        unit: Combatant,
        aliveAllies: List<Combatant>,
        aliveEnemies: List<Combatant>,
        usableSkills: List<CombatSkill>,
        supportSkills: List<CombatSkill>,
        attackSkills: List<CombatSkill>,
        rng: DeterministicRng
    ): AIAction? {
        // ---- Tier 4: 支援盟友 (盟友 HP < 40%) ----
        if (rng.nextDouble() < PROB_SUPPORT_ALLY &&
            supportSkills.isNotEmpty()
        ) {
            val supportAction = findAllySupport(
                unit, aliveAllies, supportSkills
            )
            if (supportAction != null) return supportAction
        }

        // ---- Tier 5: 团队 Buff (无紧急需求) ----
        if (rng.nextDouble() < PROB_TEAM_BUFF &&
            supportSkills.isNotEmpty()
        ) {
            val buffAction = findBuffOpportunity(
                unit, aliveAllies, supportSkills
            )
            if (buffAction != null) return buffAction
        }

        // ---- Tier 6: 控制 (高威胁未受控敌人) ----
        if (rng.nextDouble() < PROB_CONTROL) {
            val ccAction = findControlAction(
                unit, aliveEnemies, usableSkills
            )
            if (ccAction != null) return ccAction
        }

        // ---- Tier 7: AOE (3+ 敌人) ----
        if (aliveEnemies.size >= AOE_MIN_TARGETS &&
            rng.nextDouble() < PROB_AOE &&
            attackSkills.isNotEmpty()
        ) {
            val aoeSkills = attackSkills.filter { it.isAoe }
            if (aoeSkills.isNotEmpty()) {
                val bestAoe = aoeSkills.maxByOrNull {
                    it.damageMultiplier
                } ?: return AIAction.none()
                return AIAction(
                    bestAoe, null,
                    AIActionType.SKILL_ATTACK_AOE
                )
            }
        }
        return null
    }

    /**
     * 选择攻击目标（威胁排序）。
     */
    @Suppress("UnusedParameter") // attacker: 决策签名完整性：当前目标选择不依赖攻击者属性，保留形参表达调用语义
    fun selectAttackTarget(
        attacker: Combatant,
        enemies: List<Combatant>,
        skill: CombatSkill?,
        rng: DeterministicRng
    ): Combatant? {
        val alive = enemies.filter { !it.isDead }
        // 空/单目标快速退出（无 RNG 消费，合并守卫等价）
        if (alive.size <= 1) return alive.firstOrNull()

        // 低血量优先
        if (rng.nextDouble() < PROB_TARGET_LOW_HP) {
            return alive.minByOrNull { it.hp }
        }

        // 高威胁优先
        if (rng.nextDouble() < PROB_TARGET_HIGH_THREAT) {
            return alive.maxByOrNull {
                it.effectivePhysicalAttack +
                    it.effectiveMagicAttack
            }
        }

        // 低防御优先（根据技能伤害类型）
        if (rng.nextDouble() < PROB_TARGET_LOW_DEF) {
            return when (skill?.damageType) {
                DamageType.PHYSICAL ->
                    alive.minByOrNull {
                        it.effectivePhysicalDefense
                    }
                DamageType.MAGIC ->
                    alive.minByOrNull {
                        it.effectiveMagicDefense
                    }
                null ->
                    alive.minByOrNull {
                        it.effectivePhysicalDefense +
                            it.effectiveMagicDefense
                    }
            }
        }

        // 兜底：第一个存活
        return alive.first()
    }

    /**
     * 选择支援目标。
     */
    fun selectSupportTarget(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill
    ): Combatant? {
        val candidates = allies.filter {
            !it.isDead && it.id != caster.id
        }
        if (candidates.isEmpty()) return null

        return when {
            skill.healPercent > 0 || skill.healFixed > 0 ||
                skill.healType == HealType.HP ->
                candidates.minByOrNull { it.hpPercent }
            skill.shieldPercent > 0 ->
                candidates.minByOrNull { it.hpPercent }
            else ->
                candidates.minByOrNull {
                    it.effectivePhysicalDefense +
                        it.effectiveMagicDefense
                }
        }
    }

    /**
     * 确定性伤害估算（无随机数，用于 AI 斩杀判断）。
     * 委托到 [BattleCalculator.estimateDamage]。
     */
    fun estimateDamage(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill,
        damageModifier: Double = 1.0
    ): Int {
        return com.xianxia.sect.core.util.BattleCalculator
            .estimateDamage(attacker, defender, skill, damageModifier = damageModifier)
    }

    // === 私有辅助方法 ===

    private fun findSelfPreservation(
        unit: Combatant,
        supportSkills: List<CombatSkill>
    ): AIAction? {
        if (supportSkills.isEmpty()) return null

        // 优先级：护盾 > 治愈 > 减伤 > 加速
        supportSkills.firstOrNull(::isSelfShieldSkill)?.let {
            return AIAction(it, unit, AIActionType.SKILL_BUFF_SELF)
        }
        supportSkills.firstOrNull(::isSelfHealSkill)?.let {
            return AIAction(it, unit, AIActionType.SKILL_HEAL_SELF)
        }
        supportSkills.firstOrNull(::isSelfMitigationOrSpeedSkill)?.let {
            return AIAction(it, unit, AIActionType.SKILL_BUFF_SELF)
        }
        return null
    }

    private fun findExecuteTarget(
        attacker: Combatant,
        enemies: List<Combatant>,
        attackSkills: List<CombatSkill>,
        playerDamageModifier: Double
    ): AIAction? {
        val lowHp = enemies.filter {
            !it.isDead && it.hpPercent < EXECUTE_HP
        }
        if (lowHp.isEmpty()) return null

        // 按威胁降序：优先斩杀高威胁残血目标
        val sorted = lowHp.sortedByDescending {
            it.effectivePhysicalAttack +
                it.effectiveMagicAttack
        }
        // 非 AoE 单体技能中倍率最高者（循环不变量，提升出循环；无可用技能即无可斩杀）
        val bestSkill = attackSkills
            .filter { !it.isAoe }
            .maxByOrNull { it.damageMultiplier } ?: return null
        for (target in sorted) {
            if (estimateDamage(attacker, target, bestSkill, playerDamageModifier)
                >= target.hp
            ) {
                return AIAction(
                    bestSkill, target,
                    AIActionType.SKILL_ATTACK_SINGLE
                )
            }
        }
        return null
    }

    private fun findAllySupport(
        unit: Combatant,
        allies: List<Combatant>,
        supportSkills: List<CombatSkill>
    ): AIAction? {
        val woundedAllies = allies.filter {
            !it.isDead && it.id != unit.id &&
                it.hpPercent < SUPPORT_ALLY_HP
        }
        if (woundedAllies.isEmpty()) return null

        // 优先治愈最低血量盟友
        val healSkills = supportSkills.filter {
            it.healPercent > 0 || it.healFixed > 0
        }
        if (healSkills.isNotEmpty()) {
            return buildHealAction(woundedAllies, healSkills)
        }

        // 其次：给最低血量盟友上防御 Buff
        val defBuffSkills = supportSkills.filter {
            it.shieldPercent > 0 ||
                it.buffType == BuffType.PHYSICAL_DEFENSE_BOOST ||
                it.buffType == BuffType.MAGIC_DEFENSE_BOOST ||
                it.buffs.any { b ->
                    b.first == BuffType.PHYSICAL_DEFENSE_BOOST ||
                        b.first == BuffType.MAGIC_DEFENSE_BOOST
                }
        }
        if (defBuffSkills.isNotEmpty()) {
            return buildDefBuffAction(woundedAllies, defBuffSkills)
        }

        return null
    }

    /** 治愈臂：最低血量盟友 + 最优治疗技能；无可用目标返回 null */
    private fun buildHealAction(
        woundedAllies: List<Combatant>,
        healSkills: List<CombatSkill>
    ): AIAction? {
        val target = woundedAllies.minByOrNull {
            it.hpPercent
        } ?: return null
        val bestHeal = healSkills.maxByOrNull {
            it.healPercent + it.healFixed.toDouble() / 100
        } ?: return null
        return if (bestHeal.targetScope == "team" ||
            bestHeal.isAoe
        )
            AIAction(
                bestHeal, null,
                AIActionType.SKILL_HEAL_TEAM
            )
        else AIAction(
            bestHeal, target,
            AIActionType.SKILL_HEAL_ALLY
        )
    }

    /** 防御 Buff 臂：最低血量盟友 + 首个防御 Buff 技能 */
    private fun buildDefBuffAction(
        woundedAllies: List<Combatant>,
        defBuffSkills: List<CombatSkill>
    ): AIAction? {
        val target = woundedAllies.minByOrNull {
            it.hpPercent
        } ?: return null
        val bestBuff = defBuffSkills.first()
        return if (bestBuff.targetScope == "team" ||
            bestBuff.isAoe
        )
            AIAction(
                bestBuff, null,
                AIActionType.SKILL_BUFF_TEAM
            )
        else AIAction(
            bestBuff, target,
            AIActionType.SKILL_BUFF_ALLY
        )
    }

    private fun findBuffOpportunity(
        unit: Combatant,
        allies: List<Combatant>,
        supportSkills: List<CombatSkill>
    ): AIAction? {
        // 团队范围 Buff
        val teamBuffs = supportSkills.filter {
            it.targetScope == "team" || it.isAoe
        }
        if (teamBuffs.isNotEmpty()) {
            val buff = teamBuffs.first()
            return AIAction(
                buff, null,
                AIActionType.SKILL_BUFF_TEAM
            )
        }

        // 自身 Buff（非治疗类）
        val selfBuffs = supportSkills.filter {
            it.targetScope == "self" &&
                it.healPercent <= 0 && it.healFixed <= 0
        }
        if (selfBuffs.isNotEmpty()) {
            return AIAction(
                selfBuffs.first(), unit,
                AIActionType.SKILL_BUFF_SELF
            )
        }

        // 单体队友 Buff
        val allyBuffs = supportSkills.filter {
            it.targetScope == "ally"
        }
        if (allyBuffs.isNotEmpty()) {
            val target = selectSupportTarget(
                unit, allies, allyBuffs.first()
            ) ?: return null
            return AIAction(
                allyBuffs.first(), target,
                AIActionType.SKILL_BUFF_ALLY
            )
        }

        return null
    }

    @Suppress("UnusedParameter") // unit: 决策签名完整性：控制技选取当前不依赖自身单位状态
    private fun findControlAction(
        unit: Combatant,
        enemies: List<Combatant>,
        skills: List<CombatSkill>
    ): AIAction? {
        val ccSkills = skills.filter { skill ->
            skill.buffType in listOf(
                BuffType.STUN, BuffType.FREEZE,
                BuffType.SILENCE, BuffType.TAUNT
            ) ||
                skill.buffs.any { b ->
                    b.first in listOf(
                        BuffType.STUN, BuffType.FREEZE,
                        BuffType.SILENCE, BuffType.TAUNT
                    )
                }
        }
        if (ccSkills.isEmpty()) return null

        // 目标：最高威胁未受控敌人
        val uncontrolled = enemies.filter { e ->
            !e.isDead && !e.hasControlEffect
        }
        if (uncontrolled.isEmpty()) return null

        val target = uncontrolled.maxByOrNull {
            it.effectivePhysicalAttack +
                it.effectiveMagicAttack
        } ?: return null

        val bestCC = ccSkills.first()
        return AIAction(
            bestCC, target,
            AIActionType.SKILL_ATTACK_SINGLE
        )
    }

    private fun decideAttackAction(
        unit: Combatant,
        enemies: List<Combatant>,
        attackSkills: List<CombatSkill>,
        rng: DeterministicRng
    ): AIAction {
        // 省蓝模式
        if (unit.mpPercent < MP_SAVE && attackSkills.isNotEmpty()) {
            val cheap = attackSkills.minByOrNull {
                it.mpCost
            }
            if (cheap != null && unit.mp >= cheap.mpCost) {
                return selectAttackTarget(unit, enemies, cheap, rng)
                    ?.let { target ->
                        AIAction(
                            cheap, target,
                            AIActionType.SKILL_ATTACK_SINGLE
                        )
                    } ?: AIAction.none()
            }
        }

        // 最优单体攻击
        return attackSkills.maxByOrNull {
            it.damageMultiplier /
                it.mpCost.coerceAtLeast(1).toDouble()
        }?.let { best ->
            selectAttackTarget(unit, enemies, best, rng)?.let { target ->
                AIAction(
                    best, target,
                    AIActionType.SKILL_ATTACK_SINGLE
                )
            }
        } ?: run {
            // 兜底普通攻击（最优技能/目标缺失时）
            val target = selectAttackTarget(
                unit, enemies, null, rng
            )
            if (target != null)
                AIAction(null, target, AIActionType.NORMAL_ATTACK)
            else AIAction.none()
        }
    }
}

/** 护盾保命技判定：自身/AOE 范围的护盾技 */
private fun isSelfShieldSkill(skill: CombatSkill): Boolean =
    skill.shieldPercent > 0 &&
        (skill.targetScope == "self" || skill.isAoe)

/** 治愈保命技判定：自身/AOE 范围的治疗技 */
private fun isSelfHealSkill(skill: CombatSkill): Boolean =
    (skill.healPercent > 0 || skill.healFixed > 0) &&
        (skill.targetScope == "self" || skill.isAoe)

/** 减伤/加速保命技判定：自身/AOE 范围的减伤或加速技 */
private fun isSelfMitigationOrSpeedSkill(skill: CombatSkill): Boolean =
    (skill.targetScope == "self" || skill.isAoe) && (
        skill.buffType == BuffType.DAMAGE_REDUCTION ||
            skill.buffs.any {
                it.first == BuffType.DAMAGE_REDUCTION
            } ||
            skill.buffType == BuffType.SPEED_BOOST ||
            skill.buffs.any {
                it.first == BuffType.SPEED_BOOST
            }
        )

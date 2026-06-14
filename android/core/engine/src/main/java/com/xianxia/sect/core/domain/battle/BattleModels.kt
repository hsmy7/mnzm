package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill

/**
 * 战斗数据模型（从 BattleSystem.kt 提取）
 *
 * 包含战斗系统的所有数据类定义，独立于战斗逻辑。
 */
data class Battle(
    val team: List<Combatant>,
    val beasts: List<Combatant>,
    val turn: Int = 0,
    val isFinished: Boolean = false,
    val winner: BattleWinner? = null,
    val maxTurns: Int = GameConfig.Battle.MAX_TURNS
)

data class CombatBuff(
    val type: BuffType,
    val value: Double,
    var remainingDuration: Int,
    val sourceRealm: Int = 9
)

data class Combatant(
    val id: String,
    val name: String,
    val side: CombatantSide = CombatantSide.DEFENDER,
    val hp: Int,
    val maxHp: Int,
    val mp: Int,
    val maxMp: Int,
    val physicalAttack: Int,
    val magicAttack: Int,
    val physicalDefense: Int,
    val magicDefense: Int,
    val speed: Int,
    val critRate: Double,
    val skills: List<CombatSkill>,
    val buffs: List<CombatBuff> = emptyList(),
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val element: String = "",
    val weaponName: String? = null,
    val armorName: String? = null,
    val bootsName: String? = null,
    val accessoryName: String? = null,
    val portraitRes: String = "",
    val isBeast: Boolean = false
) {
    val isDead: Boolean get() = hp <= 0
    val hpPercent: Double get() = if (maxHp > 0) hp.toDouble() / maxHp else 0.0
    val mpPercent: Double get() = if (maxMp > 0) mp.toDouble() / maxMp else 0.0
    val hasControlEffect: Boolean get() = buffs.any { it.type == BuffType.STUN || it.type == BuffType.FREEZE }

    val effectivePhysicalAttack: Int get() {
        val boost = buffs.filter { it.type == BuffType.PHYSICAL_ATTACK_BOOST }.sumOf { it.value }
        val reduce = buffs.filter { it.type == BuffType.PHYSICAL_ATTACK_REDUCE }.sumOf { it.value }
        return (physicalAttack * (1 + boost - reduce)).toInt().coerceAtLeast(0)
    }

    val effectiveMagicAttack: Int get() {
        val boost = buffs.filter { it.type == BuffType.MAGIC_ATTACK_BOOST }.sumOf { it.value }
        val reduce = buffs.filter { it.type == BuffType.MAGIC_ATTACK_REDUCE }.sumOf { it.value }
        return (magicAttack * (1 + boost - reduce)).toInt().coerceAtLeast(0)
    }

    val effectivePhysicalDefense: Int get() {
        val boost = buffs.filter { it.type == BuffType.PHYSICAL_DEFENSE_BOOST }.sumOf { it.value }
        val reduce = buffs.filter { it.type == BuffType.PHYSICAL_DEFENSE_REDUCE }.sumOf { it.value }
        return (physicalDefense * (1 + boost - reduce)).toInt().coerceAtLeast(0)
    }

    val effectiveMagicDefense: Int get() {
        val boost = buffs.filter { it.type == BuffType.MAGIC_DEFENSE_BOOST }.sumOf { it.value }
        val reduce = buffs.filter { it.type == BuffType.MAGIC_DEFENSE_REDUCE }.sumOf { it.value }
        return (magicDefense * (1 + boost - reduce)).toInt().coerceAtLeast(0)
    }

    val effectiveCritRate: Double get() {
        val boost = buffs.filter { it.type == BuffType.CRIT_RATE_BOOST }.sumOf { it.value }
        val reduce = buffs.filter { it.type == BuffType.CRIT_RATE_REDUCE }.sumOf { it.value }
        return (critRate + boost - reduce).coerceAtLeast(0.0)
    }

    val effectiveSpeed: Int get() {
        val boost = buffs.filter { it.type == BuffType.SPEED_BOOST }.sumOf { it.value }
        val reduce = buffs.filter { it.type == BuffType.SPEED_REDUCE }.sumOf { it.value }
        return (speed * (1 + boost - reduce)).toInt().coerceAtLeast(0)
    }

    val effectiveMaxHp: Int get() {
        val boost = buffs.filter { it.type == BuffType.HP_BOOST }.sumOf { it.value }
        return (maxHp * (1 + boost)).toInt()
    }

    val effectiveMaxMp: Int get() {
        val boost = buffs.filter { it.type == BuffType.MP_BOOST }.sumOf { it.value }
        return (maxMp * (1 + boost)).toInt()
    }
}

enum class BattleWinner {
    TEAM, BEASTS, DRAW
}

data class AttackResult(
    val attacker: Combatant,
    val target: Combatant,
    val damage: Int,
    val isCrit: Boolean,
    val isPhysical: Boolean,
    val isDodged: Boolean = false,
    val isInstantKill: Boolean = false,
    val skillName: String? = null,
    val hits: Int = 1,
    val isSupport: Boolean = false,
    val message: String = "",
    val healPercent: Double = 0.0,
    val healType: HealType = HealType.HP,
    val healAmount: Int = 0,
    val healedIds: List<String> = emptyList(),
    val newBuffs: List<CombatBuff> = emptyList(),
    val teamBuffs: Map<String, List<CombatBuff>> = emptyMap(),
    val turnAdvancePercent: Double = 0.0
)

data class BattleSystemResult(
    val battle: Battle,
    val victory: Boolean,
    val rewards: Map<String, Int>,
    val log: BattleLogData = BattleLogData(),
    val timedOut: Boolean = false,
    val durationMs: Long = 0,
    val turnCount: Int = 0
)

data class BattleLogData(
    val rounds: List<BattleRoundData> = emptyList(),
    val teamMembers: List<BattleMemberData> = emptyList(),
    val enemies: List<BattleEnemyData> = emptyList()
)

data class BattleRoundData(
    val roundNumber: Int = 0,
    val actions: List<BattleActionData> = emptyList()
)

data class BattleActionData(
    val type: String = "",
    val attacker: String = "",
    val attackerType: String = "",
    val target: String = "",
    val damage: Int = 0,
    val damageType: String = "",
    val isCrit: Boolean = false,
    val isKill: Boolean = false,
    val isInstantKill: Boolean = false,
    val message: String = "",
    val skillName: String? = null
)

data class BattleMemberData(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val hp: Int = 0,
    val maxHp: Int = 0,
    val mp: Int = 0,
    val maxMp: Int = 0,
    var isAlive: Boolean = true,
    val portraitRes: String = ""
)

data class BattleEnemyData(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val hp: Int = 0,
    val maxHp: Int = 0,
    var isAlive: Boolean = true,
    val portraitRes: String = ""
)

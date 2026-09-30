package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.InnateDamageType

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
    val sourceRealm: Int = 9,
    /** 施放者的小层境界（1~9，0 表示未知/未记录，按初层 1 回退） */
    val sourceRealmLayer: Int = 0
)

/**
 * 战斗单位（属性单列口径，装备重构 B1 方案 §15）：
 * - 攻防各一列 [attack]/[defense]，物理/法术之分由三条通道承载：
 *   普攻 [innateDamageType]、技能 damageType、类型增伤/减伤分桶
 *   （[physicalDamageBonus]/[magicDamageBonus] + [physicalDamageReduction]/
 *   [magicDamageReduction]，默认 0.0 ⇒ 未配置时与旧公式逐位一致，S19）；
 * - 物法命名的攻防 Buff（PHYSICAL_ATTACK_BOOST 等八类）结算位置迁移为
 *   类型增伤/减伤语义（枚举名不变，见 BattleCalculator.buildDamageZones）。
 */
data class Combatant(
    val id: String,
    val name: String,
    val side: CombatantSide = CombatantSide.DEFENDER,
    val hp: Int,
    val maxHp: Int,
    val mp: Int,
    val maxMp: Int,
    val attack: Int,
    val defense: Int,
    /** 普攻伤害类型（弟子按固有属性、妖兽/敌人按种类固定；技能另按 skill.damageType） */
    val innateDamageType: DamageType = DamageType.PHYSICAL,
    /** 物理伤害加成（类型增伤桶，攻方；装备/套装等来源，B1 默认 0.0） */
    val physicalDamageBonus: Double = 0.0,
    /** 法术伤害加成（类型增伤桶，攻方；同上） */
    val magicDamageBonus: Double = 0.0,
    /** 物理伤害减免（类型减伤桶，守方；妖兽类型抗性等来源，B1 默认 0.0） */
    val physicalDamageReduction: Double = 0.0,
    /** 法术伤害减免（类型减伤桶，守方；同上） */
    val magicDamageReduction: Double = 0.0,
    val speed: Int,
    val critRate: Double,
    /** 暴击伤害加成（B3 接线 D3：暴击时 `critMult = 1 + kCritBaseMultiplier + critDamageBonus`） */
    val critDamageBonus: Double = 0.0,
    val skills: List<CombatSkill>,
    val buffs: List<CombatBuff> = emptyList(),
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val element: String = "",
    // 六部位装备展示名（0.2-12：四具名字段六部位化；C++ 侧不入战斗状态，仅展示）
    val headName: String? = null,
    val bodyName: String? = null,
    val handsName: String? = null,
    val feetName: String? = null,
    val weaponName: String? = null,
    val legsName: String? = null,
    val portraitRes: String = "",
    val isBeast: Boolean = false
) {
    val isDead: Boolean get() = hp <= 0
    val hpPercent: Double get() = if (maxHp > 0) hp.toDouble() / maxHp else 0.0
    val mpPercent: Double get() = if (maxMp > 0) mp.toDouble() / maxMp else 0.0
    val hasControlEffect: Boolean get() = buffs.any { it.type == BuffType.STUN || it.type == BuffType.FREEZE }

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

/**
 * 弟子普攻伤害类型解析（B1 §15.3 单点）：
 * 显式 `combat.innateDamageType` 优先；空串/非法值（存量旧弟子）按模板 id →
 * 角色 `InnateDamageType.derive`（模板缺失按首灵根 金/土→物理、水/木/火→法术）兜底。
 */
fun Disciple.resolvedInnateDamageType(): DamageType =
    try {
        DamageType.valueOf(combat.innateDamageType)
    } catch (_: IllegalArgumentException) {
        DamageType.valueOf(InnateDamageType.derive(templateId, spiritRootType))
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

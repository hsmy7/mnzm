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
 * 战斗单位（属性单列口径，装备重构 B1 方案 §15；五行属性伤害系统扩 6 类型通道）：
 * - 攻防各一列 [attack]/[defense]，伤害类型由两条通道承载：
 *   普攻恒 [DamageType.PHYSICAL]、技能按 skill.damageType（功法自带元素，6 值）；
 * - 类型增伤/减伤各 **6 路**（物理 + 金木水火土，[physicalDamageBonus]..
 *   [earthDamageReduction]，默认 0.0 ⇒ 未配置时与基准公式逐位一致，S19）。
 *   五行加成路为灵根 gate 后的生效值（构造期折算）；物法 buff 八类结算语义不变
 *   （枚举名不变，见 BattleCalculator.buildDamageZones；法术桶为退役段）。
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
    /** 普攻伤害类型（**角色配置驱动**：弟子按角色模板、妖兽/敌人按种类配置；
     *  当前全部角色设定为物理——这是内容设定而非架构恒等式，未来法术/五行普攻
     *  角色改配数据即可。技能另按 skill.damageType） */
    val innateDamageType: DamageType = DamageType.PHYSICAL,
    /** 物理伤害加成（类型增伤桶，攻方；不受灵根 gate） */
    val physicalDamageBonus: Double = 0.0,
    /** 金伤害加成（类型增伤桶，攻方；gate 后值） */
    val metalDamageBonus: Double = 0.0,
    /** 木伤害加成（类型增伤桶，攻方；gate 后值） */
    val woodDamageBonus: Double = 0.0,
    /** 水伤害加成（类型增伤桶，攻方；gate 后值） */
    val waterDamageBonus: Double = 0.0,
    /** 火伤害加成（类型增伤桶，攻方；gate 后值） */
    val fireDamageBonus: Double = 0.0,
    /** 土伤害加成（类型增伤桶，攻方；gate 后值） */
    val earthDamageBonus: Double = 0.0,
    /** 物理伤害减免（类型减伤桶，守方；妖兽类型抗性等来源） */
    val physicalDamageReduction: Double = 0.0,
    /** 金伤害减免（类型减伤桶，守方） */
    val metalDamageReduction: Double = 0.0,
    /** 木伤害减免（类型减伤桶，守方） */
    val woodDamageReduction: Double = 0.0,
    /** 水伤害减免（类型减伤桶，守方） */
    val waterDamageReduction: Double = 0.0,
    /** 火伤害减免（类型减伤桶，守方） */
    val fireDamageReduction: Double = 0.0,
    /** 土伤害减免（类型减伤桶，守方） */
    val earthDamageReduction: Double = 0.0,
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

    /** 按伤害类型取进攻方类型增伤桶（6 路选桶；退役段返回 0.0） */
    fun typeDamageBonusOf(type: DamageType): Double = when (type) {
        DamageType.PHYSICAL -> physicalDamageBonus
        DamageType.METAL -> metalDamageBonus
        DamageType.WOOD -> woodDamageBonus
        DamageType.WATER -> waterDamageBonus
        DamageType.FIRE -> fireDamageBonus
        DamageType.EARTH -> earthDamageBonus
        DamageType.MAGIC -> 0.0
    }

    /** 按伤害类型取防守方类型减伤桶（6 路选桶；退役段返回 0.0） */
    fun typeDamageReductionOf(type: DamageType): Double = when (type) {
        DamageType.PHYSICAL -> physicalDamageReduction
        DamageType.METAL -> metalDamageReduction
        DamageType.WOOD -> woodDamageReduction
        DamageType.WATER -> waterDamageReduction
        DamageType.FIRE -> fireDamageReduction
        DamageType.EARTH -> earthDamageReduction
        DamageType.MAGIC -> 0.0
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

/**
 * 弟子普攻伤害类型解析（B1 §15.3 单点；五行属性伤害系统后口径）：
 * 显式 `combat.innateDamageType` 优先（**角色配置驱动**——合法 `DamageType` name 直读，
 * 含旧档 MAGIC 时代残留值：显式配置什么普攻就是什么类型）；空串/非法值（真正无法解析的
 * 脏值）按模板 id → 角色 `InnateDamageType.derive` 兜底（模板缺失/无显式配置 → 物理，
 * 当前全部角色设定物理）。
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
    /** 本次攻击伤害类型（普攻恒 PHYSICAL、技能按功法元素） */
    val damageType: DamageType = DamageType.PHYSICAL,
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
) {
    /** 兼容视图：物理类型判定（= [damageType] 是否 [DamageType.PHYSICAL]） */
    val isPhysical: Boolean get() = damageType.isPhysical
}

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

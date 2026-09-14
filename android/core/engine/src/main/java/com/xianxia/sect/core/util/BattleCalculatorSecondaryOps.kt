package com.xianxia.sect.core.util

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.util.BattleCalculator.DamageResult
import com.xianxia.sect.core.util.BattleCalculator.CombatantStats
import com.xianxia.sect.core.util.BattleCalculator.RealmGapFactors
import com.xianxia.sect.core.util.BattleCalculator.ShieldResult
// ── BattleCalculator 次级计算域（自 BattleCalculator 拆出，行为零变更） ──

fun BattleCalculator.calculateDodgeChance(
    attacker: CombatantStats,
    defender: CombatantStats,
    modifier: Double = 0.5
): Double {
    val speedDiff = attacker.speed - defender.speed
    val totalSpeed = (attacker.speed + defender.speed).coerceAtLeast(1)
    return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, GameConfig.Battle.MAX_DODGE_CHANCE)
}

/**
 * 跨境界压制因子（独立乘算，不进乘区，不会被同一乘区加算稀释）。
 *
 * realm 数值越小境界越高（0=仙人，9=炼气），realmLayer 1~9（1=初层）。
 * 小层差距沿用 [checkInstantKill] 的归一化公式：
 *   layerGap = (defenderRealm - attackerRealm) × LAYERS_PER_REALM + (attackerLayer - defenderLayer)
 * layerGap > 0 表示攻击方境界更高：增伤因子 = 每层加成 × layerGap（不封顶）
 * layerGap < 0 表示防守方境界更高：减伤 = min(1.0, 每层减伤 × (-layerGap))（封顶 100%）
 * 大境界差 = defenderRealm - attackerRealm，> 0 表示攻击方高 N 个大境界：
 *   大境界增伤因子 = 每大境界加成 × 大境界差（仅增伤方向，反向无对称减伤）。
 * 三因子各自独立乘算，可同时生效（大境界加成与小层加成叠加）。
 *
 * 注意：直伤路径上高 2 个大境界及以上时 [checkInstantKill] 必杀优先，
 * 大境界加成仅在"高 1 个大境界"及 DoT 路径完整生效；因子仍按大境界差累加计算（DoT 全档生效）。
 *
 * @param attackerRealm 攻击方境界（数值越小境界越高）
 * @param attackerLayer 攻击方小层（1~9，0/越界按初层 1 回退）
 * @param defenderRealm 防守方境界
 * @param defenderLayer 防守方小层
 */
fun BattleCalculator.calculateRealmGapFactors(
    attackerRealm: Int,
    attackerLayer: Int,
    defenderRealm: Int,
    defenderLayer: Int,
    damageBonusPerLayer: Double = GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_LAYER,
    damageReductionPerLayer: Double = GameConfig.Battle.RealmGap.DAMAGE_REDUCTION_PER_LAYER,
    damageBonusPerMajorRealm: Double = GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_MAJOR_REALM
): RealmGapFactors {
    // 存档篡改防御：realm 无合法域校验（Room 列无 CHECK 约束），钳制到 [0,9] 防止
    // 负值/超大值导致增伤因子无上限爆炸（口径与 GameConfig.Realm 0~9 对齐）；
    // 与 safeLayer 同级的 realm 防御
    val attackerRealmSafe = safeRealm(attackerRealm)
    val defenderRealmSafe = safeRealm(defenderRealm)
    // Long 中间运算防存档篡改后 Int 溢出回绕
    val majorGap = defenderRealmSafe.toLong() - attackerRealmSafe.toLong()
    val layerGap = majorGap * LAYERS_PER_REALM + (safeLayer(attackerLayer) - safeLayer(defenderLayer))
    val damageAmplification = if (layerGap > 0L) damageBonusPerLayer * layerGap else 0.0
    // 防守方境界更高：每高 1 小层 +30% 减伤（封顶 100%）
    val damageReduction = if (layerGap < 0L) minOf(1.0, damageReductionPerLayer * (-layerGap)) else 0.0
    // 大境界加成仅增伤方向：攻击方每高 1 大境界 +100%，反向无对称减伤。
    // 配置为负值时钳制为 0（负因子 × 减伤超额会"负负得正"反转伤害语义）
    val majorRealmAmplification = if (majorGap > 0L) maxOf(0.0, damageBonusPerMajorRealm * majorGap) else 0.0
    return RealmGapFactors(
        damageAmplification = damageAmplification,
        damageReduction = damageReduction,
        majorRealmDamageAmplification = majorRealmAmplification
    )
}

/** 小层境界安全钳制（1~9）：0/越界（未知、存档篡改）回退合法层数 */
internal fun BattleCalculator.safeLayer(layer: Int): Int = layer.coerceIn(1, LAYERS_PER_REALM)

/** 大境界安全钳制（0~9，0=仙人，9=炼气）：负值/越界（存档篡改）回退合法域，与 [GameConfig.Realm] 口径一致 */
internal fun BattleCalculator.safeRealm(realm: Int): Int = realm.coerceIn(0, GameConfig.Realm.MAX_REALM_INDEX)

fun BattleCalculator.generateBattleMessage(
    attackerName: String,
    targetName: String,
    result: DamageResult
): String {
    if (result.isDodged) {
        return "$targetName 闪避了 $attackerName 的攻击！"
    }

    val damageType = if (result.isPhysical) "物理" else "法术"
    val skillPrefix = result.skillName?.let { "使用[$it] " } ?: ""
    var message = "$attackerName ${skillPrefix}对 $targetName 造成 ${result.damage} 点${damageType}伤害"

    if (result.isCrit) message += "（暴击！）"
    if (result.hits > 1) message += "（${result.hits}连击）"

    return message
}

/**
 * Calculate shield absorption. Returns (absorbed, remaining damage).
 * Shield absorbs damage before HP deduction. Multiple shields take the max value.
 */
fun BattleCalculator.calculateShieldAbsorption(defender: Combatant, incomingDamage: Int): ShieldResult {
    val shieldBuff = defender.buffs
        .filter { it.type == BuffType.SHIELD && it.remainingDuration > 0 }
        .maxByOrNull { it.value }
        ?: return ShieldResult(0, incomingDamage)

    // 护盾 value 语义为最大生命比例（0~1），篡改负值会让
    // absorbed 为负 → 伤害放大；+Infinity → 无限护盾；NaN.toInt()=0 已天然安全。
    // 钳制到 [0,1] 防御存档篡改
    val safeValue = shieldBuff.value.coerceIn(0.0, 1.0)
    val shieldValue = (defender.maxHp * safeValue).toInt().coerceAtLeast(0)
    val absorbed = minOf(shieldValue, incomingDamage)
    val remaining = incomingDamage - absorbed
    val newShieldValue = (shieldValue - absorbed).coerceAtLeast(0)

    return ShieldResult(
        absorbed = absorbed,
        remainingDamage = remaining,
        shieldBuff = shieldBuff,
        remainingShield = newShieldValue
    )
}

/**
 * Apply damage share redistribution.
 * Returns a map of (combatantId -> extraDamageToTake) from sharing.
 */
fun BattleCalculator.calculateDamageShare(
    targetId: String,
    targetSide: CombatantSide,
    incomingDamage: Int,
    team: List<Combatant>,
    beasts: List<Combatant>
): Map<String, Int> {
    val extraDamage = mutableMapOf<String, Int>()
    val allies = if (targetSide == CombatantSide.DEFENDER) team else beasts

    for (ally in allies) {
        // 自身/死亡/无有效分担 buff 的队友跳过
        val shareBuff = if (ally.id == targetId || ally.isDead) null
        else ally.buffs.find {
            it.type == BuffType.DAMAGE_SHARE && it.remainingDuration > 0
        }
        if (shareBuff == null) continue
        val shareDamage = (incomingDamage * shareBuff.value).toInt()
        extraDamage[ally.id] = (extraDamage[ally.id] ?: 0) + shareDamage
    }

    return extraDamage
}

/**
 * Calculate linked damage. Returns additional damage to apply to the linked enemy.
 */
fun BattleCalculator.calculateLinkedDamage(
    attacker: Combatant,
    target: Combatant,
    damage: Int,
    beasts: List<Combatant>,
    team: List<Combatant>
): Map<String, Int> {
    val linkedDamage = mutableMapOf<String, Int>()
    val enemies = if (attacker.side == CombatantSide.DEFENDER) beasts else team

    // Only one enemy can be linked at a time：取首个（存活、非目标、
    // 持有效链接 buff）敌人
    val linkedEnemy = enemies.firstOrNull { enemy ->
        enemy.id != target.id && !enemy.isDead &&
            enemy.buffs.any {
                it.type == BuffType.DAMAGE_LINK && it.remainingDuration > 0
            }
    } ?: return linkedDamage
    val linkBuff = linkedEnemy.buffs.first {
        it.type == BuffType.DAMAGE_LINK && it.remainingDuration > 0
    }
    val linkDamage = (damage * linkBuff.value).toInt().coerceAtLeast(1)
    linkedDamage[linkedEnemy.id] = linkDamage

    return linkedDamage
}

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

object BattleSystem {
    
    fun createBattle(
        disciples: List<Disciple>,
        equipmentMap: Map<String, Equipment>,
        manualMap: Map<String, Manual>,
        beastLevel: Int,
        beastCount: Int? = null,
        beastType: String? = null,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>> = emptyMap()
    ): Battle {
        val combatants = disciples.map { disciple ->
            val discipleProficiencies = manualProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies)
            val skills = disciple.manualIds.mapNotNull { manualId ->
                val manual = manualMap[manualId] ?: return@mapNotNull null
                val proficiencyData = discipleProficiencies[manualId]
                val masteryLevel = proficiencyData?.masteryLevel ?: 0
                val baseSkill = manual.skill ?: return@mapNotNull null
                val adjustedMultiplier = ManualProficiencySystem.calculateSkillDamageMultiplier(
                    baseSkill.damageMultiplier,
                    masteryLevel
                )
                CombatSkill(
                    name = baseSkill.name,
                    skillType = baseSkill.skillType,
                    damageType = baseSkill.damageType,
                    damageMultiplier = adjustedMultiplier,
                    mpCost = baseSkill.mpCost,
                    cooldown = baseSkill.cooldown,
                    hits = baseSkill.hits,
                    healPercent = baseSkill.healPercent,
                    healType = baseSkill.healType
                )
            }
            
            Combatant(
                id = disciple.id,
                name = disciple.name,
                type = CombatantType.DISCIPLE,
                hp = stats.maxHp,
                maxHp = stats.maxHp,
                mp = stats.maxMp,
                maxMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                skills = skills,
                realm = disciple.realm,
                realmName = GameConfig.Realm.getName(disciple.realm),
                realmLayer = disciple.realmLayer
            )
        }
        
        val teamAvgRealm = GameUtils.calculateTeamAverageRealm(
            disciples,
            realmExtractor = { it.realm },
            layerExtractor = { it.realmLayer }
        )
        
        val actualBeastCount = beastCount ?: Random.nextInt(GameConfig.Battle.MIN_BEAST_COUNT, GameConfig.Battle.MAX_BEAST_COUNT + 1)
        
        val beasts = (1..actualBeastCount).map { index ->
            createBeast(teamAvgRealm, index, beastType)
        }
        
        return Battle(
            team = combatants,
            beasts = beasts,
            turn = 0,
            isFinished = false,
            winner = null
        )
    }
    
    private fun createBeast(teamAvgRealm: Double, index: Int, beastType: String? = null): Combatant {
        val realmIndex = ceil(teamAvgRealm).toInt().coerceIn(0, 9)

        val realm = GameConfig.Beast.getRealm(realmIndex)

        val type = if (beastType != null) {
            GameConfig.Beast.TYPES.find { it.name == beastType } ?: GameConfig.Beast.getType(0)
        } else {
            GameConfig.Beast.getType(Random.nextInt(GameConfig.Beast.TYPES.size))
        }

        // 计算层数加成（参照弟子：layerBonus = 1.0 + (realmLayer - 1) * 0.1）
        val realmLayer = ((teamAvgRealm - realmIndex) * 10).toInt().coerceIn(1, 9)
        val layerBonus = 1.0 + (realmLayer - 1) * 0.1

        // 炼虚及以上（realmIndex <= 4）属性为弟子200%，浮动±30%
        // 化神及以下（realmIndex >= 5）属性为弟子150%，浮动±30%
        val realmMultiplier = if (realmIndex <= 4) 2.0 else 1.5
        val variance = 0.7 + Random.nextDouble() * 0.6
        val isPhysicalAttacker = Random.nextDouble() < 0.5

        val physicalAttack: Int
        val magicAttack: Int
        if (isPhysicalAttacker) {
            physicalAttack = (realm.baseAtk * type.atkMod * variance * realmMultiplier * layerBonus).toInt()
            magicAttack = (realm.baseAtk * type.atkMod * 0.3 * variance * realmMultiplier * layerBonus).toInt()
        } else {
            physicalAttack = (realm.baseAtk * type.atkMod * 0.3 * variance * realmMultiplier * layerBonus).toInt()
            magicAttack = (realm.baseAtk * type.atkMod * variance * realmMultiplier * layerBonus).toInt()
        }

        val hp = (realm.baseHp * type.hpMod * variance * realmMultiplier * layerBonus).toInt()
        val defense = (realm.baseDef * type.defMod * variance * realmMultiplier * layerBonus).toInt()
        val speed = (realm.baseSpeed * type.speedMod * variance * realmMultiplier * layerBonus).toInt()
        
        return Combatant(
            id = "beast_$index",
            name = "${type.prefix}${type.name}",
            type = CombatantType.BEAST,
            hp = hp,
            maxHp = hp,
            mp = (realm.baseHp * 0.2).toInt(),
            maxMp = (realm.baseHp * 0.2).toInt(),
            physicalAttack = physicalAttack,
            magicAttack = magicAttack,
            physicalDefense = defense,
            magicDefense = (defense * 0.8).toInt(),
            speed = speed,
            critRate = 0.05 + realmIndex * 0.01,
            skills = emptyList(),
            realm = realmIndex,
            realmName = GameConfig.Realm.getName(realmIndex),
            realmLayer = realmLayer
        )
    }
    
    fun executeBattle(battle: Battle): BattleSystemResult {
        var currentBattle = battle
        val rounds = mutableListOf<BattleRoundData>()
        val teamMembers = battle.team.map { combatant ->
            BattleMemberData(
                id = combatant.id,
                name = combatant.name,
                realm = combatant.realm,
                realmName = combatant.realmName,
                hp = combatant.hp,
                maxHp = combatant.maxHp,
                isAlive = true
            )
        }.toMutableList()
        val enemies = battle.beasts.map { combatant ->
            BattleEnemyData(
                name = combatant.name,
                realm = combatant.realm,
                realmName = combatant.realmName,
                realmLayer = combatant.realmLayer
            )
        }
        
        while (!currentBattle.isFinished && currentBattle.turn < GameConfig.Battle.MAX_TURNS) {
            val turnResult = executeTurnWithLog(currentBattle)
            currentBattle = turnResult.first
            if (turnResult.second.actions.isNotEmpty()) {
                rounds.add(turnResult.second)
            }
            
            teamMembers.forEachIndexed { index, member ->
                val combatant = currentBattle.team.find { it.id == member.id }
                if (combatant != null) {
                    teamMembers[index] = member.copy(
                        isAlive = !combatant.isDead,
                        hp = combatant.hp
                    )
                }
            }
        }
        
        val aliveTeam = currentBattle.team.count { !it.isDead }
        val aliveBeasts = currentBattle.beasts.count { !it.isDead }
        
        val winner = when {
            aliveTeam == 0 -> BattleWinner.BEASTS
            aliveBeasts == 0 -> BattleWinner.TEAM
            else -> BattleWinner.DRAW
        }
        
        val finalBattle = currentBattle.copy(
            isFinished = true,
            winner = winner
        )
        
        return BattleSystemResult(
            battle = finalBattle,
            victory = winner == BattleWinner.TEAM,
            rewards = if (winner == BattleWinner.TEAM) generateRewards(battle.beasts.size) else emptyMap(),
            log = BattleLogData(
                rounds = rounds,
                teamMembers = teamMembers,
                enemies = enemies
            )
        )
    }
    
    private fun executeTurn(battle: Battle): Battle {
        return executeTurnWithLog(battle).first
    }
    
    private fun executeTurnWithLog(battle: Battle): Pair<Battle, BattleRoundData> {
        val allCombatants = (battle.team + battle.beasts)
            .filter { !it.isDead }
            .sortedByDescending { it.speed }
        
        var team = battle.team.toMutableList()
        var beasts = battle.beasts.toMutableList()
        val actions = mutableListOf<BattleActionData>()
        
        allCombatants.forEach { combatant ->
            if (combatant.isDead) return@forEach
            
            val targets = if (combatant.type == CombatantType.DISCIPLE) {
                beasts.filter { !it.isDead }
            } else {
                team.filter { !it.isDead }
            }
            
            if (targets.isEmpty()) {
                return Pair(battle.copy(team = team, beasts = beasts, isFinished = true), BattleRoundData(battle.turn, actions))
            }
            
            val target = targets.random()
            
            var currentCombatant = if (combatant.type == CombatantType.DISCIPLE) {
                team.find { it.id == combatant.id } ?: combatant
            } else {
                beasts.find { it.id == combatant.id } ?: combatant
            }
            
            val availableSkill = currentCombatant.skills.firstOrNull { 
                it.currentCooldown == 0 && currentCombatant.mp >= it.mpCost 
            }
            
            val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
            
            val result = if (availableSkill != null) {
                if (isSupportSkill && currentCombatant.type == CombatantType.DISCIPLE) {
                    executeSupportSkill(currentCombatant, team, availableSkill)
                } else {
                    executeSkill(currentCombatant, target, availableSkill)
                }
            } else {
                executeAttack(currentCombatant, target)
            }
            
            var message: String
            var isKill = false
            
            if (result.isSupport) {
                message = result.message
            } else if (result.isDodged) {
                message = "${target.name} 闪避了 ${currentCombatant.name} 的攻击！"
            } else {
                val damageType = if (result.isPhysical) "物理" else "法术"
                val skillPrefix = result.skillName?.let { "使用[$it] " } ?: ""
                message = "${currentCombatant.name} ${skillPrefix}对 ${target.name} 造成 ${result.damage} 点${damageType}伤害"
                if (result.isCrit) message += "（暴击！）"
                if (result.hits > 1) message += "（${result.hits}连击）"
                
                isKill = target.hp - result.damage <= 0
                if (isKill) message += "，${target.name} 被击败！"
            }
            
            actions.add(BattleActionData(
                type = when {
                    isSupportSkill -> "support"
                    availableSkill != null -> "skill"
                    else -> "attack"
                },
                attacker = currentCombatant.name,
                attackerType = if (currentCombatant.type == CombatantType.DISCIPLE) "disciple" else "beast",
                target = if (isSupportSkill) "team" else target.name,
                damage = result.damage,
                damageType = if (result.isSupport) "support" else if (result.isDodged) "闪避" else if (result.isPhysical) "物理" else "法术",
                isCrit = result.isCrit,
                isKill = isKill,
                message = message,
                skillName = result.skillName
            ))
            
            if (!result.isSupport) {
                val targetIndex = if (target.type == CombatantType.DISCIPLE) {
                    team.indexOfFirst { it.id == target.id }
                } else {
                    beasts.indexOfFirst { it.id == target.id }
                }
                
                if (!result.isDodged) {
                    if (target.type == CombatantType.DISCIPLE && targetIndex >= 0) {
                        team[targetIndex] = result.target.copy(
                            hp = maxOf(0, result.target.hp - result.damage)
                        )
                    } else if (targetIndex >= 0) {
                        beasts[targetIndex] = result.target.copy(
                            hp = maxOf(0, result.target.hp - result.damage)
                        )
                    }
                }
            }
            
            if (availableSkill != null) {
                val combatantIndex = if (currentCombatant.type == CombatantType.DISCIPLE) {
                    team.indexOfFirst { it.id == currentCombatant.id }
                } else {
                    beasts.indexOfFirst { it.id == currentCombatant.id }
                }
                
                if (combatantIndex >= 0) {
                    val updatedSkills = currentCombatant.skills.map { skill ->
                        if (skill.name == availableSkill.name) {
                            skill.copy(currentCooldown = skill.cooldown)
                        } else {
                            skill.copy(currentCooldown = maxOf(0, skill.currentCooldown - 1))
                        }
                    }
                    
                    val newBuffs = if (isSupportSkill && result.newBuffs.isNotEmpty()) {
                        currentCombatant.buffs.filter { it.remainingDuration > 0 } + result.newBuffs
                    } else {
                        currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
                    }
                    
                    val updatedCombatant = currentCombatant.copy(
                        mp = currentCombatant.mp - availableSkill.mpCost,
                        skills = updatedSkills,
                        buffs = newBuffs
                    )
                    
                    if (currentCombatant.type == CombatantType.DISCIPLE) {
                        team[combatantIndex] = updatedCombatant
                    } else {
                        beasts[combatantIndex] = updatedCombatant
                    }
                }
                
                if (isSupportSkill && result.healedIds.isNotEmpty()) {
                    result.healedIds.forEach { healedId ->
                        val healedIndex = team.indexOfFirst { it.id == healedId }
                        if (healedIndex >= 0) {
                            val healed = team[healedIndex]
                            if (result.healType == HealType.MP) {
                                team[healedIndex] = healed.copy(
                                    mp = minOf(healed.mp + result.healAmount, healed.maxMp)
                                )
                            } else {
                                team[healedIndex] = healed.copy(
                                    hp = minOf(healed.hp + result.healAmount, healed.maxHp)
                                )
                            }
                        }
                    }
                }
            } else {
                val combatantIndex = if (currentCombatant.type == CombatantType.DISCIPLE) {
                    team.indexOfFirst { it.id == currentCombatant.id }
                } else {
                    beasts.indexOfFirst { it.id == currentCombatant.id }
                }
                
                if (combatantIndex >= 0) {
                    val newBuffs = currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
                    val updatedCombatant = currentCombatant.copy(buffs = newBuffs)
                    
                    if (currentCombatant.type == CombatantType.DISCIPLE) {
                        team[combatantIndex] = updatedCombatant
                    } else {
                        beasts[combatantIndex] = updatedCombatant
                    }
                }
            }
        }
        
        return Pair(
            battle.copy(
                team = team,
                beasts = beasts,
                turn = battle.turn + 1
            ),
            BattleRoundData(battle.turn + 1, actions)
        )
    }
    
    private fun executeAttack(attacker: Combatant, defender: Combatant): AttackResult {
        val speedDiff = attacker.speed - defender.speed
        val dodgeChance = (speedDiff.toDouble() / (attacker.speed + defender.speed).coerceAtLeast(1) * 0.5).coerceIn(0.0, 0.5)
        
        if (Random.nextDouble() < dodgeChance) {
            return AttackResult(
                attacker = attacker,
                target = defender,
                damage = 0,
                isCrit = false,
                isPhysical = true,
                isDodged = true
            )
        }
        
        val isPhysical = attacker.physicalAttack >= attacker.magicAttack
        
        val attack = if (isPhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (isPhysical) defender.physicalDefense else defender.magicDefense
        
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier: Double = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        
        val baseDamage = (attack * critMultiplier - defense).coerceAtLeast(1.0)
        val variance = Random.nextDouble(0.9, 1.1)
        val finalDamage = (baseDamage * variance).toInt().coerceAtMost(Int.MAX_VALUE / 2)
        
        return AttackResult(
            attacker = attacker,
            target = defender,
            damage = finalDamage,
            isCrit = isCrit,
            isPhysical = isPhysical,
            isDodged = false
        )
    }
    
    private fun executeSkill(attacker: Combatant, defender: Combatant, skill: CombatSkill): AttackResult {
        val speedDiff = attacker.speed - defender.speed
        val dodgeChance = (speedDiff.toDouble() / (attacker.speed + defender.speed).coerceAtLeast(1) * 0.3).coerceIn(0.0, 0.3)
        
        if (Random.nextDouble() < dodgeChance) {
            return AttackResult(
                attacker = attacker,
                target = defender,
                damage = 0,
                isCrit = false,
                isPhysical = skill.damageType == DamageType.PHYSICAL,
                isDodged = true,
                skillName = skill.name,
                hits = skill.hits
            )
        }
        
        val isPhysical = skill.damageType == DamageType.PHYSICAL
        
        val baseAttack = if (isPhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (isPhysical) defender.physicalDefense else defender.magicDefense
        
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier: Double = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0
        
        val totalDamage = (baseAttack * skill.damageMultiplier * critMultiplier - defense).coerceAtLeast(1.0)
        val variance = Random.nextDouble(0.9, 1.1)
        val finalDamage = (totalDamage * variance).toInt().coerceAtMost(Int.MAX_VALUE / 2)
        
        return AttackResult(
            attacker = attacker,
            target = defender,
            damage = finalDamage,
            isCrit = isCrit,
            isPhysical = isPhysical,
            isDodged = false,
            skillName = skill.name,
            hits = skill.hits
        )
    }
    
    private fun executeSupportSkill(
        caster: Combatant, 
        team: List<Combatant>, 
        skill: CombatSkill
    ): AttackResult {
        val aliveTeamMembers = team.filter { !it.isDead }
        val healPercent = skill.healPercent
        val healType = skill.healType
        
        val healAmount = if (healType == HealType.MP) {
            (caster.maxMp * healPercent).toInt()
        } else {
            (caster.maxHp * healPercent).toInt()
        }
        
        val message = "${caster.name} 使用[${skill.name}] 为全队恢复${healType.displayName}${healAmount}点"
        
        return AttackResult(
            attacker = caster,
            target = caster,
            damage = 0,
            isCrit = false,
            isPhysical = false,
            isDodged = false,
            skillName = skill.name,
            hits = 1,
            isSupport = true,
            message = message,
            healPercent = healPercent,
            healType = healType,
            healAmount = healAmount,
            healedIds = aliveTeamMembers.map { it.id },
            newBuffs = emptyList()
        )
    }
    
    private fun generateRewards(beastCount: Int): Map<String, Int> {
        val rewards = mutableMapOf<String, Int>()
        rewards["spiritStones"] = Random.nextInt(50, 150) * beastCount
        return rewards
    }
}

data class Battle(
    val team: List<Combatant>,
    val beasts: List<Combatant>,
    val turn: Int = 0,
    val isFinished: Boolean = false,
    val winner: BattleWinner? = null
)

data class CombatBuff(
    val type: BuffType,
    val value: Double,
    var remainingDuration: Int
)

enum class BuffType {
    HP_BOOST, MP_BOOST, ATTACK_BOOST, DEFENSE_BOOST, SPEED_BOOST;
    
    val displayName: String get() = when (this) {
        HP_BOOST -> "生命加成"
        MP_BOOST -> "灵力加成"
        ATTACK_BOOST -> "攻击加成"
        DEFENSE_BOOST -> "防御加成"
        SPEED_BOOST -> "速度加成"
    }
}

data class Combatant(
    val id: String,
    val name: String,
    val type: CombatantType,
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
    val realmLayer: Int = 0
) {
    val isDead: Boolean get() = hp <= 0
    val hpPercent: Double get() = if (maxHp > 0) hp.toDouble() / maxHp else 0.0
    val mpPercent: Double get() = if (maxMp > 0) mp.toDouble() / maxMp else 0.0
    
    val effectiveMaxHp: Int get() {
        val hpBoost = buffs.filter { it.type == BuffType.HP_BOOST }.sumOf { it.value }
        return (maxHp * (1 + hpBoost)).toInt()
    }
    
    val effectiveMaxMp: Int get() {
        val mpBoost = buffs.filter { it.type == BuffType.MP_BOOST }.sumOf { it.value }
        return (maxMp * (1 + mpBoost)).toInt()
    }
}

enum class CombatantType {
    DISCIPLE, BEAST
}

enum class DamageType {
    PHYSICAL, MAGIC
}

enum class SkillType {
    ATTACK, SUPPORT;
    
    val displayName: String get() = when (this) {
        ATTACK -> "攻击"
        SUPPORT -> "辅助"
    }
}

enum class HealType {
    HP, MP;
    
    val displayName: String get() = when (this) {
        HP -> "生命值"
        MP -> "灵力"
    }
}

data class CombatSkill(
    val name: String,
    val skillType: SkillType = SkillType.ATTACK,
    val damageType: DamageType,
    val damageMultiplier: Double,
    val mpCost: Int,
    val cooldown: Int,
    val hits: Int = 1,
    val healPercent: Double = 0.0,
    val healType: HealType = HealType.HP,
    var currentCooldown: Int = 0
)

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
    val skillName: String? = null,
    val hits: Int = 1,
    val isSupport: Boolean = false,
    val message: String = "",
    val healPercent: Double = 0.0,
    val healType: HealType = HealType.HP,
    val healAmount: Int = 0,
    val healedIds: List<String> = emptyList(),
    val newBuffs: List<CombatBuff> = emptyList()
)

data class BattleSystemResult(
    val battle: Battle,
    val victory: Boolean,
    val rewards: Map<String, Int>,
    val log: BattleLogData = BattleLogData()
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
    var isAlive: Boolean = true
)

data class BattleEnemyData(
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0
)

data class BattleEnemy(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val hp: Int = 100,
    val maxHp: Int = 100,
    val attack: Int = 20,
    val defense: Int = 10,
    val speed: Int = 10,
    var isAlive: Boolean = true,
    var remainingHp: Int = 100
) {
    init {
        remainingHp = hp
    }
}



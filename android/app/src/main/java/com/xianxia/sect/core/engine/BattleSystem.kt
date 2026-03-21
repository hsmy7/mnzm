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
                baseSkill.copy(
                    damageMultiplier = adjustedMultiplier
                ).toCombatSkill(manualName = manual.name)
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
            .sortedByDescending { it.effectiveSpeed }
        
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
                val buffs = availableSkill?.buffs ?: emptyList()
                message = BattleDescriptionGenerator.generateSupportSkillDescription(
                    caster = currentCombatant,
                    skill = availableSkill!!,
                    healAmount = result.healAmount,
                    healType = result.healType,
                    buffs = buffs
                )
            } else if (availableSkill != null) {
                isKill = target.hp - result.damage <= 0
                message = BattleDescriptionGenerator.generateSkillDescription(
                    attacker = currentCombatant,
                    target = target,
                    skill = availableSkill,
                    result = result,
                    isKill = isKill
                )
            } else {
                isKill = target.hp - result.damage <= 0
                message = BattleDescriptionGenerator.generateAttackDescription(
                    attacker = currentCombatant,
                    target = target,
                    result = result,
                    isKill = isKill
                )
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
                        currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 } + result.newBuffs
                    } else {
                        currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 }
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
                
                if (isSupportSkill) {
                    if (result.healedIds.isNotEmpty()) {
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
                    
                    if (result.teamBuffs.isNotEmpty()) {
                        result.teamBuffs.forEach { (memberId, buffs) ->
                            val memberIndex = team.indexOfFirst { it.id == memberId }
                            if (memberIndex >= 0) {
                                val member = team[memberIndex]
                                val existingBuffs = member.buffs.filter { it.remainingDuration > 0 }
                                team[memberIndex] = member.copy(
                                    buffs = existingBuffs + buffs
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
                    val newBuffs = currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 }
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
        val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
        val dodgeChance = (speedDiff.toDouble() / (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1) * 0.5).coerceIn(0.0, 0.5)
        
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
        
        val attack = if (isPhysical) attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack
        val defense = if (isPhysical) defender.effectivePhysicalDefense else defender.effectiveMagicDefense
        
        val isCrit = Random.nextDouble() < attacker.effectiveCritRate
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
        val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
        val dodgeChance = (speedDiff.toDouble() / (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1) * 0.3).coerceIn(0.0, 0.3)
        
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
        
        val baseAttack = if (isPhysical) attacker.effectivePhysicalAttack else attacker.effectiveMagicAttack
        val defense = if (isPhysical) defender.effectivePhysicalDefense else defender.effectiveMagicDefense
        
        val isCrit = Random.nextDouble() < attacker.effectiveCritRate
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
        
        var healAmount = 0
        val teamBuffs = mutableMapOf<String, List<CombatBuff>>()
        
        if (healPercent > 0) {
            healAmount = if (healType == HealType.MP) {
                (caster.maxMp * healPercent).toInt()
            } else {
                (caster.maxHp * healPercent).toInt()
            }
        }
        
        if (skill.buffType != null && skill.buffDuration > 0) {
            val buff = CombatBuff(
                type = skill.buffType,
                value = skill.buffValue,
                remainingDuration = skill.buffDuration
            )
            for (member in aliveTeamMembers) {
                teamBuffs[member.id] = listOf(buff)
            }
        }
        
        for ((buffType, buffValue, buffDuration) in skill.buffs) {
            val buff = CombatBuff(
                type = buffType,
                value = buffValue,
                remainingDuration = buffDuration
            )
            for (member in aliveTeamMembers) {
                val existing = teamBuffs[member.id] ?: emptyList()
                teamBuffs[member.id] = existing + buff
            }
        }
        
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
            message = "",
            healPercent = healPercent,
            healType = healType,
            healAmount = healAmount,
            healedIds = if (healAmount > 0) aliveTeamMembers.map { it.id } else emptyList(),
            newBuffs = emptyList(),
            teamBuffs = teamBuffs
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
    HP_BOOST, MP_BOOST, SPEED_BOOST,
    PHYSICAL_ATTACK_BOOST, MAGIC_ATTACK_BOOST, PHYSICAL_DEFENSE_BOOST, MAGIC_DEFENSE_BOOST,
    CRIT_RATE_BOOST;
    
    val displayName: String get() = when (this) {
        HP_BOOST -> "生命加成"
        MP_BOOST -> "灵力加成"
        SPEED_BOOST -> "速度加成"
        PHYSICAL_ATTACK_BOOST -> "物攻加成"
        MAGIC_ATTACK_BOOST -> "法攻加成"
        PHYSICAL_DEFENSE_BOOST -> "物防加成"
        MAGIC_DEFENSE_BOOST -> "法防加成"
        CRIT_RATE_BOOST -> "暴击加成"
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
    
    val effectivePhysicalAttack: Int get() {
        val attackBoost = buffs.filter { it.type == BuffType.PHYSICAL_ATTACK_BOOST }.sumOf { it.value }
        return (physicalAttack * (1 + attackBoost)).toInt()
    }
    
    val effectiveMagicAttack: Int get() {
        val attackBoost = buffs.filter { it.type == BuffType.MAGIC_ATTACK_BOOST }.sumOf { it.value }
        return (magicAttack * (1 + attackBoost)).toInt()
    }
    
    val effectivePhysicalDefense: Int get() {
        val defenseBoost = buffs.filter { it.type == BuffType.PHYSICAL_DEFENSE_BOOST }.sumOf { it.value }
        return (physicalDefense * (1 + defenseBoost)).toInt()
    }
    
    val effectiveMagicDefense: Int get() {
        val defenseBoost = buffs.filter { it.type == BuffType.MAGIC_DEFENSE_BOOST }.sumOf { it.value }
        return (magicDefense * (1 + defenseBoost)).toInt()
    }
    
    val effectiveCritRate: Double get() {
        val critBoost = buffs.filter { it.type == BuffType.CRIT_RATE_BOOST }.sumOf { it.value }
        return critRate + critBoost
    }
    
    val effectiveSpeed: Int get() {
        val speedBoost = buffs.filter { it.type == BuffType.SPEED_BOOST }.sumOf { it.value }
        return (speed * (1 + speedBoost)).toInt()
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
    val buffType: BuffType? = null,
    val buffValue: Double = 0.0,
    val buffDuration: Int = 0,
    val buffs: List<Triple<BuffType, Double, Int>> = emptyList(),
    var currentCooldown: Int = 0,
    val skillDescription: String = "",
    val manualName: String = ""
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
    val newBuffs: List<CombatBuff> = emptyList(),
    val teamBuffs: Map<String, List<CombatBuff>> = emptyMap()
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

object BattleDescriptionGenerator {
    
    private val physicalAttackVerbs = listOf(
        "挥剑斩向", "猛力攻击", "一剑刺向", "奋力劈向", "凌厉攻击",
        "剑光闪烁，攻向", "剑气纵横，斩向", "一记重击砸向", "凌空一击攻向", "蓄力一击轰向"
    )
    
    private val magicAttackVerbs = listOf(
        "凝聚灵力攻向", "施法轰向", "灵力涌动，攻向", "法力凝聚，轰向", "一记法术攻向",
        "灵光闪烁，攻向", "法力流转，轰向", "凝聚真元攻向", "施放法术轰向", "灵力爆发攻向"
    )
    
    private val beastPhysicalAttackVerbs = listOf(
        "猛扑向", "利爪抓向", "獠牙咬向", "尾巴横扫", "猛烈撞击",
        "咆哮着扑向", "凶狠攻击", "利爪撕裂", "獠牙撕咬", "野蛮冲撞"
    )
    
    private val beastMagicAttackVerbs = listOf(
        "凝聚妖力攻向", "喷吐妖气轰向", "妖力涌动，攻向", "释放妖术轰向", "一记妖法攻向",
        "妖光闪烁，攻向", "妖气弥漫，轰向", "凝聚妖元攻向", "施放妖术轰向", "妖力爆发攻向"
    )
    
    private val skillCastPhrases = listOf(
        "运转", "施展", "催动", "发动", "运转"
    )
    
    private val critDescriptions = listOf(
        "致命一击！", "击中要害！", "暴击命中！", "一击破防！", "势大力沉！"
    )
    
    private val dodgeDescriptions = listOf(
        "身形一闪，躲过了攻击", "侧身闪避，堪堪躲过", "身法灵动，避开了攻击",
        "脚下生风，闪身躲过", "反应敏捷，躲开了攻击"
    )
    
    private val killDescriptions = listOf(
        "轰杀", "击杀", "斩杀", "击溃", "击倒"
    )
    
    fun generateAttackDescription(
        attacker: Combatant,
        target: Combatant,
        result: AttackResult,
        isKill: Boolean
    ): String {
        val sb = StringBuilder()
        
        if (result.isDodged) {
            val dodgeDesc = dodgeDescriptions.random()
            return "${target.name}${dodgeDesc}${attacker.name}的攻击！"
        }
        
        val isPhysical = result.isPhysical
        val isBeast = attacker.type == CombatantType.BEAST
        
        val attackVerb = if (isBeast) {
            if (isPhysical) beastPhysicalAttackVerbs.random() else beastMagicAttackVerbs.random()
        } else {
            if (isPhysical) physicalAttackVerbs.random() else magicAttackVerbs.random()
        }
        
        val damageType = if (isPhysical) "物理" else "法术"
        
        sb.append("${attacker.name}${attackVerb}${target.name}")
        
        if (result.isCrit) {
            sb.append("，${critDescriptions.random()}")
        }
        
        sb.append("，造成${result.damage}点${damageType}伤害")
        
        if (result.hits > 1) {
            sb.append("（${result.hits}连击）")
        }
        
        if (isKill) {
            val killDesc = killDescriptions.random()
            sb.append("，${killDesc}了${target.name}！")
        }
        
        return sb.toString()
    }
    
    fun generateSkillDescription(
        attacker: Combatant,
        target: Combatant,
        skill: CombatSkill,
        result: AttackResult,
        isKill: Boolean
    ): String {
        val sb = StringBuilder()
        
        if (result.isDodged) {
            val dodgeDesc = dodgeDescriptions.random()
            return "${target.name}${dodgeDesc}${attacker.name}的[${skill.name}]！"
        }
        
        val castPhrase = skillCastPhrases.random()
        if (skill.manualName.isNotEmpty()) {
            sb.append("${attacker.name}${castPhrase}【${skill.manualName}】，使出[${skill.name}]")
        } else {
            sb.append("${attacker.name}使出[${skill.name}]")
        }
        
        if (skill.skillDescription.isNotEmpty()) {
            sb.append("（${skill.skillDescription}）")
        }
        
        sb.append("攻向${target.name}")
        
        if (result.isCrit) {
            sb.append("，${critDescriptions.random()}")
        }
        
        val damageType = if (result.isPhysical) "物理" else "法术"
        sb.append("，造成${result.damage}点${damageType}伤害")
        
        if (skill.damageMultiplier > 1.0) {
            sb.append("（威力${(skill.damageMultiplier * 100).toInt()}%）")
        }
        
        if (result.hits > 1) {
            sb.append("（${result.hits}连击）")
        }
        
        if (isKill) {
            val killDesc = killDescriptions.random()
            sb.append("，${killDesc}了${target.name}！")
        }
        
        return sb.toString()
    }
    
    fun generateSupportSkillDescription(
        caster: Combatant,
        skill: CombatSkill,
        healAmount: Int,
        healType: HealType,
        buffs: List<Triple<BuffType, Double, Int>>
    ): String {
        val sb = StringBuilder()
        
        val castPhrase = skillCastPhrases.random()
        if (skill.manualName.isNotEmpty()) {
            sb.append("${caster.name}${castPhrase}【${skill.manualName}】，使出[${skill.name}]")
        } else {
            sb.append("${caster.name}使出[${skill.name}]")
        }
        
        if (skill.skillDescription.isNotEmpty()) {
            sb.append("（${skill.skillDescription}）")
        }
        
        val effects = mutableListOf<String>()
        
        if (healAmount > 0) {
            val healTypeName = if (healType == HealType.HP) "生命值" else "灵力"
            effects.add("为全队恢复${healTypeName}${healAmount}点")
        }
        
        buffs.forEach { (buffType, buffValue, duration) ->
            val percentValue = (buffValue * 100).toInt()
            effects.add("全队获得${buffType.displayName}${percentValue}%持续${duration}回合")
        }
        
        if (effects.isNotEmpty()) {
            sb.append("，${effects.joinToString("，")}")
        }
        
        return sb.toString()
    }
}



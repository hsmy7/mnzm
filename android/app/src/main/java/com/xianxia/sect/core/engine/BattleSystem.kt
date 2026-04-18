@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.BattleCalculator
import com.xianxia.sect.core.util.GameUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class BattleSystem @Inject constructor() {

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
            convertDiscipleToCombatant(disciple, equipmentMap, manualMap, manualProficiencies, CombatantSide.DEFENDER)
        }

        val beastRealm = GameUtils.calculateBeastRealm(
            disciples,
            realmExtractor = { it.realm },
            layerExtractor = { it.realmLayer }
        )

        val actualBeastCount = beastCount ?: Random.nextInt(GameConfig.Battle.MIN_BEAST_COUNT, GameConfig.Battle.MAX_BEAST_COUNT + 1)

        val beasts = (1..actualBeastCount).map { index ->
            createBeast(beastRealm, index, beastType)
        }

        return Battle(
            team = combatants,
            beasts = beasts,
            turn = 0,
            isFinished = false,
            winner = null
        )
    }

    fun convertDiscipleToCombatant(
        disciple: Disciple,
        equipmentMap: Map<String, Equipment>,
        manualMap: Map<String, Manual>,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>>,
        side: CombatantSide = CombatantSide.DEFENDER
    ): Combatant {
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

        val effectiveHp = if (disciple.currentHp < 0) stats.maxHp else disciple.currentHp.coerceAtMost(stats.maxHp)
        val effectiveMp = if (disciple.currentMp < 0) stats.maxMp else disciple.currentMp.coerceAtMost(stats.maxMp)

        val spiritRootTypes = disciple.spiritRoot.types
        val primaryElement = spiritRootTypes.firstOrNull()?.trim() ?: "metal"

        return Combatant(
            id = disciple.id,
            name = disciple.name,
            side = side,
            hp = effectiveHp,
            maxHp = stats.maxHp,
            mp = effectiveMp,
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
            realmLayer = disciple.realmLayer,
            element = primaryElement
        )
    }

    private fun createBeast(beastRealm: Int, index: Int, beastType: String? = null): Combatant {
        val realmIndex = beastRealm.coerceIn(0, 9)

        val realmConfig = GameConfig.Realm.get(realmIndex)
        val realmMultiplier = realmConfig.multiplier

        val type = if (beastType != null) {
            GameConfig.Beast.TYPES.find { it.name == beastType } ?: GameConfig.Beast.getType(0)
        } else {
            GameConfig.Beast.getType(Random.nextInt(GameConfig.Beast.TYPES.size))
        }

        val realmLayer = Random.nextInt(1, 10)
        val layerBonus = 1.0 + (realmLayer - 1) * 0.1

        val beastBaseHp = 300
        val beastBaseMp = 150
        val beastBaseAtk = 35
        val beastBaseDef = 26
        val beastBaseSpeed = 24

        val baseHp = (beastBaseHp * realmMultiplier * layerBonus).toInt()
        val baseMp = (beastBaseMp * realmMultiplier * layerBonus).toInt()
        val baseAtk = (beastBaseAtk * realmMultiplier * layerBonus).toInt()
        val baseDef = (beastBaseDef * realmMultiplier * layerBonus).toInt()
        val baseSpeed = (beastBaseSpeed * realmMultiplier * layerBonus).toInt()

        val beastAdvantage = 0.06
        val hpVariance = -0.2 + Random.nextDouble() * 0.4
        val physicalAttackVariance = -0.2 + Random.nextDouble() * 0.4
        val magicAttackVariance = -0.2 + Random.nextDouble() * 0.4
        val physicalDefenseVariance = -0.2 + Random.nextDouble() * 0.4
        val magicDefenseVariance = -0.2 + Random.nextDouble() * 0.4
        val speedVariance = -0.2 + Random.nextDouble() * 0.4

        val hp = (baseHp * (1.0 + (type.hpMod - 1.0) + hpVariance + beastAdvantage)).toInt()
        val mp = (baseMp * (1.0 + (type.hpMod - 1.0) + hpVariance + beastAdvantage)).toInt()
        val physicalAttack = (baseAtk * (1.0 + (type.atkMod - 1.0) + physicalAttackVariance + beastAdvantage)).toInt()
        val magicAttack = (baseAtk * (1.0 + (type.atkMod - 1.0) + magicAttackVariance + beastAdvantage)).toInt()
        val physicalDefense = (baseDef * (1.0 + (type.defMod - 1.0) + physicalDefenseVariance + beastAdvantage)).toInt()
        val magicDefense = (baseDef * (1.0 + (type.defMod - 1.0) + magicDefenseVariance + beastAdvantage)).toInt()
        val speed = (baseSpeed * (1.0 + (type.speedMod - 1.0) + speedVariance + beastAdvantage)).toInt()

        val beastSkills = type.skills.map { skillConfig ->
            CombatSkill(
                name = skillConfig.name,
                skillType = skillConfig.skillType,
                damageType = skillConfig.damageType,
                damageMultiplier = skillConfig.damageMultiplier,
                mpCost = skillConfig.mpCost,
                cooldown = skillConfig.cooldown,
                hits = skillConfig.hits,
                buffType = skillConfig.buffType,
                buffValue = skillConfig.buffValue,
                buffDuration = skillConfig.buffDuration,
                isAoe = skillConfig.isAoe,
                targetScope = skillConfig.targetScope
            )
        }

        return Combatant(
            id = "beast_$index",
            name = "${type.prefix}${type.name}",
            side = CombatantSide.ATTACKER,
            hp = hp,
            maxHp = hp,
            mp = mp,
            maxMp = mp,
            physicalAttack = physicalAttack,
            magicAttack = magicAttack,
            physicalDefense = physicalDefense,
            magicDefense = magicDefense,
            speed = speed,
            critRate = 0.05 + realmIndex * 0.01,
            skills = beastSkills,
            realm = realmIndex,
            realmName = GameConfig.Realm.getName(realmIndex),
            realmLayer = realmLayer,
            element = type.element
        )
    }

    fun executeBattle(battle: Battle): BattleSystemResult {
        return executeBattleWithTimeout(battle, GameConfig.Battle.MAX_BATTLE_DURATION_MS)
    }

    fun executeBattleWithTimeout(battle: Battle, timeoutMs: Long = GameConfig.Battle.MAX_BATTLE_DURATION_MS): BattleSystemResult {
        val startTime = System.currentTimeMillis()
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
                mp = combatant.mp,
                maxMp = combatant.maxMp,
                isAlive = true
            )
        }.toMutableList()
        val enemies = battle.beasts.map { combatant ->
            BattleEnemyData(
                id = combatant.id,
                name = combatant.name,
                realm = combatant.realm,
                realmName = combatant.realmName,
                realmLayer = combatant.realmLayer,
                hp = combatant.hp,
                maxHp = combatant.maxHp,
                isAlive = true
            )
        }.toMutableList()

        var timedOut = false

        while (!currentBattle.isFinished && currentBattle.turn < GameConfig.Battle.MAX_TURNS) {
            val elapsed = System.currentTimeMillis() - startTime

            if (elapsed > timeoutMs) {
                timedOut = true
                break
            }

            if (elapsed > GameConfig.Battle.BATTLE_TIMEOUT_WARNING_MS && currentBattle.turn % 5 == 0) {
                android.util.Log.w("BattleSystem", "Battle taking long: ${elapsed}ms, turn ${currentBattle.turn}/${GameConfig.Battle.MAX_TURNS}")
            }

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
                        hp = combatant.hp,
                        mp = combatant.mp
                    )
                } else {
                    teamMembers[index] = member.copy(
                        isAlive = false,
                        hp = 0,
                        mp = 0
                    )
                }
            }

            enemies.forEachIndexed { index, enemy ->
                val beast = currentBattle.beasts.find { it.id == enemy.id }
                if (beast != null) {
                    enemies[index] = enemy.copy(
                        isAlive = !beast.isDead,
                        hp = beast.hp
                    )
                } else {
                    enemies[index] = enemy.copy(
                        isAlive = false,
                        hp = 0
                    )
                }
            }
        }

        val aliveTeam = currentBattle.team.count { !it.isDead }
        val aliveBeasts = currentBattle.beasts.count { !it.isDead }

        val winner = when {
            timedOut -> {
                android.util.Log.w("BattleSystem", "Battle timed out after ${System.currentTimeMillis() - startTime}ms")
                if (aliveTeam > aliveBeasts) BattleWinner.TEAM else if (aliveBeasts > aliveTeam) BattleWinner.BEASTS else BattleWinner.DRAW
            }
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
            ),
            timedOut = timedOut,
            durationMs = System.currentTimeMillis() - startTime,
            turnCount = currentBattle.turn
        )
    }

    private fun executeTurnWithLog(battle: Battle): Pair<Battle, BattleRoundData> {
        val allCombatants = (battle.team + battle.beasts)
            .filter { !it.isDead }
            .sortedByDescending { it.effectiveSpeed }

        var team = battle.team.toMutableList()
        var beasts = battle.beasts.toMutableList()
        val teamIndexMap = team.withIndex().associate { it.value.id to it.index }
        val beastsIndexMap = beasts.withIndex().associate { it.value.id to it.index }
        val actions = mutableListOf<BattleActionData>()

        for (combatant in allCombatants) {
            if (combatant.isDead) continue

            val isTeamMember = combatant.side == CombatantSide.DEFENDER
            val allies = if (isTeamMember) team else beasts
            val enemies = if (isTeamMember) beasts else team
            val alliesIndexMap = if (isTeamMember) teamIndexMap else beastsIndexMap
            val enemiesIndexMap = if (isTeamMember) beastsIndexMap else teamIndexMap

            val aliveEnemies = enemies.filter { !it.isDead }
            if (aliveEnemies.isEmpty()) {
                return Pair(battle.copy(team = team, beasts = beasts, isFinished = true), BattleRoundData(battle.turn, actions))
            }

            val currentCombatant = allies.find { it.id == combatant.id } ?: combatant

            if (currentCombatant.hasControlEffect) {
                val stunBuff = currentCombatant.buffs.find { it.type == BuffType.STUN || it.type == BuffType.FREEZE }
                if (stunBuff != null) {
                    actions.add(BattleActionData(
                        type = "control",
                        attacker = currentCombatant.name,
                        attackerType = if (isTeamMember) "disciple" else "beast",
                        target = currentCombatant.name,
                        damage = 0,
                        damageType = if (stunBuff.type == BuffType.STUN) "眩晕" else "冰冻",
                        message = "${currentCombatant.name}因${stunBuff.type.displayName}无法行动！"
                    ))
                    updateCombatantBuffs(currentCombatant, allies, alliesIndexMap)
                    continue
                }
            }

            val silenceBuff = currentCombatant.buffs.find { it.type == BuffType.SILENCE && it.remainingDuration > 0 }
            val availableSkill = selectSkill(currentCombatant, aliveEnemies, allies, silenceBuff != null)

            val isSupportSkill = availableSkill?.skillType == SkillType.SUPPORT
            val isAoeSkill = availableSkill?.isAoe == true && !isSupportSkill

            val results = if (availableSkill != null) {
                if (isSupportSkill) {
                    listOf(executeSupportSkill(currentCombatant, allies.filter { !it.isDead }, availableSkill))
                } else if (isAoeSkill) {
                    aliveEnemies.map { target -> executeSkill(currentCombatant, target, availableSkill) }
                } else {
                    val target = selectTarget(currentCombatant, aliveEnemies)
                    listOf(executeSkill(currentCombatant, target, availableSkill))
                }
            } else {
                val target = selectTarget(currentCombatant, aliveEnemies)
                listOf(executeAttack(currentCombatant, target))
            }

            val result = results.first()
            var isKill = false
            var totalDamage = 0

            val message: String = if (result.isSupport && availableSkill != null) {
                val buffs = availableSkill.buffs
                BattleDescriptionGenerator.generateSupportSkillDescription(
                    caster = currentCombatant,
                    skill = availableSkill,
                    healAmount = result.healAmount,
                    healType = result.healType,
                    buffs = buffs
                )
            } else if (availableSkill != null) {
                totalDamage = results.sumOf { it.damage }
                isKill = results.any { r -> r.target.hp - r.damage <= 0 }
                if (isAoeSkill) {
                    BattleDescriptionGenerator.generateAoeSkillDescription(
                        attacker = currentCombatant,
                        skill = availableSkill,
                        results = results,
                        isKill = isKill
                    )
                } else {
                    val singleTarget = result.target
                    isKill = singleTarget.hp - result.damage <= 0
                    BattleDescriptionGenerator.generateSkillDescription(
                        attacker = currentCombatant,
                        target = singleTarget,
                        skill = availableSkill,
                        result = result,
                        isKill = isKill
                    )
                }
            } else {
                isKill = result.target.hp - result.damage <= 0
                BattleDescriptionGenerator.generateAttackDescription(
                    attacker = currentCombatant,
                    target = result.target,
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
                attackerType = if (isTeamMember) "disciple" else "beast",
                target = if (isSupportSkill) "team" else if (isAoeSkill) "全体敌人" else result.target.name,
                damage = if (isAoeSkill) totalDamage else result.damage,
                damageType = if (result.isSupport) "support" else if (result.isDodged) "闪避" else if (result.isPhysical) "物理" else "法术",
                isCrit = results.any { it.isCrit },
                isKill = isKill,
                message = message,
                skillName = result.skillName
            ))

            if (!result.isSupport) {
                results.forEach { r ->
                    if (r.isDodged) return@forEach
                    val targetIndex = enemiesIndexMap[r.target.id] ?: return@forEach
                    val newHp = maxOf(0, r.target.hp - r.damage)
                    if (isTeamMember) {
                        beasts[targetIndex] = beasts[targetIndex].copy(hp = newHp)
                    } else {
                        team[targetIndex] = team[targetIndex].copy(hp = newHp)
                    }

                    if (availableSkill?.buffType != null && availableSkill.buffDuration > 0 && !isAoeSkill) {
                        val debuff = CombatBuff(
                            type = availableSkill.buffType,
                            value = availableSkill.buffValue,
                            remainingDuration = availableSkill.buffDuration
                        )
                        if (isTeamMember && targetIndex < beasts.size) {
                            beasts[targetIndex] = beasts[targetIndex].copy(
                                buffs = beasts[targetIndex].buffs + debuff
                            )
                        } else if (targetIndex < team.size) {
                            team[targetIndex] = team[targetIndex].copy(
                                buffs = team[targetIndex].buffs + debuff
                            )
                        }
                    }
                }

                if (isAoeSkill && availableSkill?.buffType != null && availableSkill.buffDuration > 0) {
                    val debuff = CombatBuff(
                        type = availableSkill.buffType,
                        value = availableSkill.buffValue,
                        remainingDuration = availableSkill.buffDuration
                    )
                    aliveEnemies.filter { !it.isDead }.forEach { enemy ->
                        val idx = enemiesIndexMap[enemy.id] ?: return@forEach
                        if (isTeamMember && idx < beasts.size) {
                            beasts[idx] = beasts[idx].copy(buffs = beasts[idx].buffs + debuff)
                        } else if (idx < team.size) {
                            team[idx] = team[idx].copy(buffs = team[idx].buffs + debuff)
                        }
                    }
                }

                team = team.filter { !it.isDead }.toMutableList()
                beasts = beasts.filter { !it.isDead }.toMutableList()
            }

            if (availableSkill != null) {
                val combatantIndex = alliesIndexMap[currentCombatant.id] ?: -1
                if (combatantIndex >= 0) {
                    val updatedSkills = currentCombatant.skills.map { skill ->
                        if (skill.name == availableSkill.name) {
                            skill.copy(currentCooldown = skill.cooldown)
                        } else {
                            skill.copy(currentCooldown = maxOf(0, skill.currentCooldown - 1))
                        }
                    }

                    val existingBuffs = currentCombatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 }

                    val updatedCombatant = currentCombatant.copy(
                        mp = currentCombatant.mp - availableSkill.mpCost,
                        skills = updatedSkills,
                        buffs = existingBuffs
                    )

                    if (isTeamMember) {
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
                            val memberIndex = allies.indexOfFirst { it.id == memberId }
                            if (memberIndex >= 0) {
                                val member = allies[memberIndex]
                                val existingBuffs = member.buffs.filter { it.remainingDuration > 0 }
                                val updated = member.copy(buffs = existingBuffs + buffs)
                                if (isTeamMember) {
                                    team[memberIndex] = updated
                                } else {
                                    beasts[memberIndex] = updated
                                }
                            }
                        }
                    }
                }
            } else {
                updateCombatantBuffs(currentCombatant, allies, alliesIndexMap)
            }
        }

        processDotEffects(team, beasts, actions)

        return Pair(
            battle.copy(
                team = team,
                beasts = beasts,
                turn = battle.turn + 1
            ),
            BattleRoundData(battle.turn + 1, actions)
        )
    }

    private fun updateCombatantBuffs(combatant: Combatant, list: List<Combatant>, indexMap: Map<String, Int>) {
        val idx = indexMap[combatant.id] ?: return
        if (idx >= list.size) return
        val newBuffs = combatant.buffs.map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }.filter { it.remainingDuration > 0 }
        val updated = combatant.copy(buffs = newBuffs)
        if (list === list) {
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Combatant>)[idx] = updated
        }
    }

    private fun processDotEffects(team: MutableList<Combatant>, beasts: MutableList<Combatant>, actions: MutableList<BattleActionData>) {
        val allCombatants = (team + beasts).filter { !it.isDead }
        for (combatant in allCombatants) {
            val poisonBuffs = combatant.buffs.filter { it.type == BuffType.POISON && it.remainingDuration > 0 }
            val burnBuffs = combatant.buffs.filter { it.type == BuffType.BURN && it.remainingDuration > 0 }

            var dotDamage = 0
            poisonBuffs.forEach { buff -> dotDamage += (combatant.maxHp * buff.value).toInt() }
            burnBuffs.forEach { buff -> dotDamage += (combatant.maxHp * buff.value).toInt() }

            if (dotDamage > 0) {
                val newHp = maxOf(0, combatant.hp - dotDamage)
                val isTeamMember = combatant.side == CombatantSide.DEFENDER
                if (isTeamMember) {
                    val idx = team.indexOfFirst { it.id == combatant.id }
                    if (idx >= 0) team[idx] = team[idx].copy(hp = newHp)
                } else {
                    val idx = beasts.indexOfFirst { it.id == combatant.id }
                    if (idx >= 0) beasts[idx] = beasts[idx].copy(hp = newHp)
                }
                actions.add(BattleActionData(
                    type = "dot",
                    attacker = "",
                    attackerType = "",
                    target = combatant.name,
                    damage = dotDamage,
                    damageType = "持续伤害",
                    isKill = newHp <= 0,
                    message = "${combatant.name}受到${dotDamage}点持续伤害"
                ))
            }
        }
    }

    private fun executeAttack(attacker: Combatant, defender: Combatant): AttackResult {
        val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
        val dodgeChance = (speedDiff.toDouble() / (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1) * 0.5).coerceIn(0.0, GameConfig.Battle.MAX_DODGE_CHANCE)

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
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0

        val baseDamage = attack.toDouble()
        val reduction = defense.toDouble() / (defense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val elementMultiplier = calculateElementMultiplier(attacker.element, defender.element)

        val finalDamage = (baseDamage * (1.0 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier).toInt()
        val variance = Random.nextDouble(GameConfig.Battle.DAMAGE_VARIANCE_MIN, GameConfig.Battle.DAMAGE_VARIANCE_MAX)
        val damageWithVariance = (finalDamage * variance).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE).coerceAtMost(Int.MAX_VALUE / 2)

        return AttackResult(
            attacker = attacker,
            target = defender,
            damage = damageWithVariance,
            isCrit = isCrit,
            isPhysical = isPhysical,
            isDodged = false
        )
    }

    private fun executeSkill(attacker: Combatant, defender: Combatant, skill: CombatSkill): AttackResult {
        val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
        val dodgeChance = (speedDiff.toDouble() / (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1) * 0.3).coerceIn(0.0, GameConfig.Battle.MAX_SKILL_DODGE_CHANCE)

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
        val critMultiplier = if (isCrit) GameConfig.Battle.CRIT_MULTIPLIER else 1.0

        val reduction = defense.toDouble() / (defense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val elementMultiplier = calculateElementMultiplier(attacker.element, defender.element)

        val totalDamage = (baseAttack * skill.damageMultiplier * (1.0 - reduction) * critMultiplier * realmGapMultiplier * elementMultiplier).toInt()
        val variance = Random.nextDouble(GameConfig.Battle.DAMAGE_VARIANCE_MIN, GameConfig.Battle.DAMAGE_VARIANCE_MAX)
        val finalDamage = (totalDamage * variance).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE).coerceAtMost(Int.MAX_VALUE / 2)

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
        allies: List<Combatant>,
        skill: CombatSkill
    ): AttackResult {
        val targets = if (skill.targetScope == "team") allies else listOf(caster)
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
            for (member in targets) {
                teamBuffs[member.id] = listOf(buff)
            }
        }

        for ((buffType, buffValue, buffDuration) in skill.buffs) {
            val buff = CombatBuff(
                type = buffType,
                value = buffValue,
                remainingDuration = buffDuration
            )
            for (member in targets) {
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
            healedIds = if (healAmount > 0) targets.map { it.id } else emptyList(),
            newBuffs = emptyList(),
            teamBuffs = teamBuffs
        )
    }

    fun calculateRealmGapMultiplier(attackerRealm: Int, defenderRealm: Int): Double {
        val gap = attackerRealm - defenderRealm
        val absGap = kotlin.math.abs(gap).coerceAtMost(GameConfig.Battle.RealmGap.MAX_REALM_GAP)
        if (absGap == 0) return 1.0

        val ratio = if (gap > 0) {
            1.0 + absGap * GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_REALM
        } else {
            1.0 - absGap * GameConfig.Battle.RealmGap.DAMAGE_PENALTY_PER_REALM
        }

        return ratio.coerceIn(GameConfig.Battle.RealmGap.MIN_DAMAGE_RATIO, GameConfig.Battle.RealmGap.MAX_DAMAGE_RATIO)
    }

    fun calculateElementMultiplier(attackerElement: String, defenderElement: String): Double {
        if (attackerElement.isEmpty() || defenderElement.isEmpty()) return 1.0
        val advantage = GameConfig.Battle.Element.ADVANTAGES[attackerElement]
        return when {
            advantage == defenderElement -> GameConfig.Battle.Element.ADVANTAGE_MULTIPLIER
            GameConfig.Battle.Element.ADVANTAGES[defenderElement] == attackerElement -> GameConfig.Battle.Element.DISADVANTAGE_MULTIPLIER
            else -> 1.0
        }
    }

    private fun selectSkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean
    ): CombatSkill? {
        if (isSilenced) return null
        if (combatant.skills.isEmpty()) return null

        val availableSkills = combatant.skills.filter {
            it.currentCooldown == 0 && combatant.mp >= it.mpCost
        }
        if (availableSkills.isEmpty()) return null

        val supportSkills = availableSkills.filter { it.skillType == SkillType.SUPPORT }
        val attackSkills = availableSkills.filter { it.skillType == SkillType.ATTACK }

        val lowHpAllies = allies.filter { it.hpPercent < 0.3 }
        if (lowHpAllies.isNotEmpty() && supportSkills.isNotEmpty() && Random.nextDouble() < 0.8) {
            return supportSkills.first()
        }

        val controlSkills = attackSkills.filter { skill ->
            skill.buffType != null && skill.buffDuration > 0 && skill.buffType.isDebuff &&
                skill.buffType in setOf(BuffType.STUN, BuffType.FREEZE, BuffType.SILENCE, BuffType.TAUNT)
        }
        val uncontrolledEnemies = enemies.filter { enemy -> !enemy.hasControlEffect }
        if (uncontrolledEnemies.isNotEmpty() && controlSkills.isNotEmpty() && Random.nextDouble() < 0.6) {
            return controlSkills.first()
        }

        val aoeSkills = attackSkills.filter { it.isAoe }
        if (enemies.size >= 3 && aoeSkills.isNotEmpty() && Random.nextDouble() < 0.7) {
            return aoeSkills.maxByOrNull { it.damageMultiplier }
        }

        if (combatant.mpPercent < 0.3 && attackSkills.isNotEmpty()) {
            val cheapSkill = attackSkills.minByOrNull { it.mpCost }
            if (cheapSkill != null && combatant.mp >= cheapSkill.mpCost * 2) {
                return cheapSkill
            }
            return null
        }

        if (attackSkills.isNotEmpty()) {
            return attackSkills.maxByOrNull { it.damageMultiplier / it.mpCost.coerceAtLeast(1) }
        }

        return availableSkills.firstOrNull()
    }

    private fun selectTarget(attacker: Combatant, targets: List<Combatant>): Combatant {
        val lowHpTargets = targets.filter { it.hpPercent < 0.3 }
        if (lowHpTargets.isNotEmpty() && Random.nextDouble() < 0.7) {
            return lowHpTargets.random()
        }

        val highThreatTargets = targets.filter { target ->
            target.skills.isNotEmpty() && target.effectivePhysicalAttack > attacker.effectivePhysicalDefense
        }
        if (highThreatTargets.isNotEmpty() && Random.nextDouble() < 0.5) {
            return highThreatTargets.random()
        }

        val lowDefenseTargets = targets.filter { target ->
            val avgDefense = (target.effectivePhysicalDefense + target.effectiveMagicDefense) / 2.0
            avgDefense < attacker.effectivePhysicalAttack * 0.5
        }
        if (lowDefenseTargets.isNotEmpty() && Random.nextDouble() < 0.4) {
            return lowDefenseTargets.random()
        }

        val elementAdvantageTargets = targets.filter { target ->
            calculateElementMultiplier(attacker.element, target.element) > 1.0
        }
        if (elementAdvantageTargets.isNotEmpty() && Random.nextDouble() < 0.3) {
            return elementAdvantageTargets.random()
        }

        return targets.random()
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
    val element: String = ""
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

@Deprecated("Use CombatantSide instead", replaceWith = ReplaceWith("CombatantSide"))
enum class CombatantType {
    DISCIPLE, BEAST
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
    val manualName: String = "",
    val isAoe: Boolean = false,
    val targetScope: String = "self"
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
    var isAlive: Boolean = true
)

data class BattleEnemyData(
    val id: String = "",
    val name: String = "",
    val realm: Int = 9,
    val realmName: String = "",
    val realmLayer: Int = 0,
    val hp: Int = 0,
    val maxHp: Int = 0,
    var isAlive: Boolean = true
)

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
        val isBeast = attacker.side == CombatantSide.ATTACKER

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

    fun generateAoeSkillDescription(
        attacker: Combatant,
        skill: CombatSkill,
        results: List<AttackResult>,
        isKill: Boolean
    ): String {
        val sb = StringBuilder()

        val castPhrase = skillCastPhrases.random()
        if (skill.manualName.isNotEmpty()) {
            sb.append("${attacker.name}${castPhrase}【${skill.manualName}】，使出[${skill.name}]")
        } else {
            sb.append("${attacker.name}使出[${skill.name}]")
        }

        if (skill.skillDescription.isNotEmpty()) {
            sb.append("（${skill.skillDescription}）")
        }

        val damageType = if (results.first().isPhysical) "物理" else "法术"
        val totalDamage = results.sumOf { it.damage }
        val hitCount = results.count { !it.isDodged }
        val dodgeCount = results.count { it.isDodged }

        sb.append("，对全体敌人造成${totalDamage}点${damageType}伤害")

        if (skill.damageMultiplier > 1.0) {
            sb.append("（威力${(skill.damageMultiplier * 100).toInt()}%）")
        }

        if (skill.hits > 1) {
            sb.append("（${skill.hits}连击×${hitCount}目标）")
        }

        if (dodgeCount > 0) {
            sb.append("，${dodgeCount}个目标闪避")
        }

        if (isKill) {
            val killDesc = killDescriptions.random()
            sb.append("，${killDesc}了敌人！")
        }

        return sb.toString()
    }
}

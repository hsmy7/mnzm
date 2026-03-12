package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.GameUtils
import kotlin.math.roundToInt
import kotlin.random.Random

object SectWarBattleSystem {
    
    private const val MAX_ROUNDS = 25
    
    /**
     * 执行10v10战斗
     */
    fun executeBattle(
        attackers: List<Disciple>,
        defenders: List<DefenseDisciple>,
        equipmentMap: Map<String, Equipment>,
        manuals: Map<String, Manual>,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>> = emptyMap()
    ): WarBattleResult {
        // 转换进攻方为战斗单位
        val attackerUnits = attackers.map { disciple ->
            val discipleProficiencies = manualProficiencies[disciple.id] ?: emptyMap()
            val stats = disciple.getFinalStats(equipmentMap, manuals, discipleProficiencies)
            WarCombatant(
                id = disciple.id,
                name = disciple.name,
                type = WarCombatantType.PLAYER,
                maxHp = stats.maxHp,
                currentHp = stats.maxHp,
                maxMp = stats.maxMp,
                currentMp = stats.maxMp,
                physicalAttack = stats.physicalAttack,
                magicAttack = stats.magicAttack,
                physicalDefense = stats.physicalDefense,
                magicDefense = stats.magicDefense,
                speed = stats.speed,
                critRate = stats.critRate,
                realm = disciple.realm,
                realmName = GameConfig.Realm.getName(disciple.realm)
            )
        }
        
        // 转换防守方为战斗单位
        val defenderUnits = defenders.map { defense ->
            WarCombatant(
                id = defense.id,
                name = defense.name,
                type = WarCombatantType.AI,
                maxHp = defense.maxHp,
                currentHp = defense.maxHp,
                maxMp = defense.maxMp,
                currentMp = defense.maxMp,
                physicalAttack = defense.physicalAttack,
                magicAttack = defense.magicAttack,
                physicalDefense = defense.physicalDefense,
                magicDefense = defense.magicDefense,
                speed = defense.speed,
                critRate = defense.critRate,
                realm = defense.realm,
                realmName = defense.realmName
            )
        }
        
        // 执行战斗回合
        val rounds = mutableListOf<BattleRoundLog>()
        var currentAttackers = attackerUnits.toMutableList()
        var currentDefenders = defenderUnits.toMutableList()
        
        for (round in 1..MAX_ROUNDS) {
            val roundLog = executeRound(currentAttackers, currentDefenders, round)
            rounds.add(roundLog)
            
            // 检查胜负
            val attackersAlive = currentAttackers.count { !it.isDead }
            val defendersAlive = currentDefenders.count { !it.isDead }
            
            if (attackersAlive == 0 || defendersAlive == 0) {
                break
            }
        }
        
        // 判定结果
        val attackersAlive = currentAttackers.count { !it.isDead }
        val defendersAlive = currentDefenders.count { !it.isDead }
        val totalAttackerHp = currentAttackers.sumOf { it.currentHp }
        val totalDefenderHp = currentDefenders.sumOf { it.currentHp }
        
        val victory = when {
            attackersAlive > 0 && defendersAlive == 0 -> true
            attackersAlive == 0 && defendersAlive > 0 -> false
            totalAttackerHp > totalDefenderHp -> true
            else -> false
        }
        
        val timeout = rounds.size >= MAX_ROUNDS && attackersAlive > 0 && defendersAlive > 0
        
        // 计算伤亡
        val playerCasualties = calculateCasualties(attackers, currentAttackers)
        val aiCasualties = calculateAICasualties(defenders, currentDefenders)
        
        // MVP
        val mvp = findMVP(currentAttackers, attackers)
        
        return WarBattleResult(
            victory = victory,
            attackerLosses = attackers.size - currentAttackers.count { !it.isDead },
            defenderLosses = defenders.size - currentDefenders.count { !it.isDead },
            rounds = rounds,
            playerCasualties = playerCasualties,
            aiCasualties = aiCasualties,
            log = rounds.map { "Round ${it.round}: ${it.actions.size} actions" },
            timeout = timeout,
            mvp = mvp
        )
    }
    
    /**
     * 执行单个回合
     */
    private fun executeRound(
        attackers: MutableList<WarCombatant>,
        defenders: MutableList<WarCombatant>,
        roundNum: Int
    ): BattleRoundLog {
        val actions = mutableListOf<BattleAction>()
        
        // 按速度排序所有存活单位
        val allUnits = (attackers + defenders)
            .filter { !it.isDead }
            .sortedByDescending { it.speed }
        
        for (unit in allUnits) {
            if (unit.isDead) continue
            
            // 选择目标
            val targets = if (unit.type == WarCombatantType.PLAYER) {
                defenders.filter { !it.isDead }
            } else {
                attackers.filter { !it.isDead }
            }
            
            if (targets.isEmpty()) continue
            
            val target = targets.random()
            val action = executeAttack(unit, target)
            actions.add(action)
            
            // 更新目标状态
            if (unit.type == WarCombatantType.PLAYER) {
                val index = defenders.indexOfFirst { it.id == target.id }
                if (index >= 0) {
                    defenders[index] = target
                }
            } else {
                val index = attackers.indexOfFirst { it.id == target.id }
                if (index >= 0) {
                    attackers[index] = target
                }
            }
        }
        
        return BattleRoundLog(round = roundNum, actions = actions)
    }
    
    /**
     * 执行攻击
     */
    private fun executeAttack(attacker: WarCombatant, target: WarCombatant): BattleAction {
        val isPhysical = Random.nextDouble() < 0.5
        
        val attack = if (isPhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (isPhysical) target.physicalDefense else target.magicDefense
        
        val isCrit = Random.nextDouble() < attacker.critRate
        val critMultiplier = if (isCrit) 2.0 else 1.0
        
        val baseDamage = (attack * critMultiplier - defense).coerceAtLeast(1.0)
        val variance = Random.nextDouble(0.9, 1.1)
        val finalDamage = (baseDamage * variance).toInt().coerceAtMost(Int.MAX_VALUE / 2)
        
        target.currentHp = (target.currentHp - finalDamage).coerceAtLeast(0)
        val isKill = target.currentHp <= 0
        
        val damageType = if (isPhysical) "物理" else "法术"
        var message = "${attacker.name} 对 ${target.name} 造成 ${finalDamage} 点${damageType}伤害"
        if (isCrit) message += "（暴击！）"
        if (isKill) message += "，${target.name} 被击败！"
        
        return BattleAction(
            attacker = attacker.name,
            attackerType = if (attacker.type == WarCombatantType.PLAYER) 
                WarCombatantType.PLAYER 
            else 
                WarCombatantType.AI,
            target = target.name,
            damage = finalDamage,
            isCrit = isCrit,
            isKill = isKill,
            message = message
        )
    }
    
    /**
     * 计算玩家伤亡
     */
    private fun calculateCasualties(
        disciples: List<Disciple>,
        units: List<WarCombatant>
    ): List<Casualty> {
        val casualties = mutableListOf<Casualty>()
        
        disciples.forEach { disciple ->
            val unit = units.find { it.id == disciple.id }
            if (unit != null) {
                val hpPercent = unit.currentHp.toDouble() / unit.maxHp
                val injuryType = when {
                    unit.isDead -> InjuryType.DEAD
                    hpPercent < 0.2 -> InjuryType.SEVERE
                    hpPercent < 0.5 -> InjuryType.MODERATE
                    hpPercent < 0.8 -> InjuryType.LIGHT
                    else -> InjuryType.NONE
                }
                
                if (injuryType != InjuryType.NONE) {
                    val recoveryMonths = when (injuryType) {
                        InjuryType.LIGHT -> Random.nextInt(1, 3)
                        InjuryType.MODERATE -> Random.nextInt(3, 6)
                        InjuryType.SEVERE -> Random.nextInt(6, 12)
                        else -> 0
                    }
                    
                    casualties.add(Casualty(
                        discipleId = disciple.id,
                        name = disciple.name,
                        injuryType = injuryType,
                        recoveryMonths = recoveryMonths
                    ))
                }
            }
        }
        
        return casualties
    }
    
    /**
     * 计算AI伤亡
     */
    private fun calculateAICasualties(
        defenders: List<DefenseDisciple>,
        units: List<WarCombatant>
    ): List<Casualty> {
        val casualties = mutableListOf<Casualty>()
        
        defenders.forEach { defender ->
            val unit = units.find { it.id == defender.id }
            if (unit != null && unit.isDead) {
                casualties.add(Casualty(
                    discipleId = defender.id,
                    name = defender.name,
                    injuryType = InjuryType.DEAD,
                    recoveryMonths = 0
                ))
            }
        }
        
        return casualties
    }
    
    /**
     * 寻找MVP
     */
    private fun findMVP(units: List<WarCombatant>, disciples: List<Disciple>): String? {
        val aliveUnits = units.filter { !it.isDead }
        if (aliveUnits.isEmpty()) return null
        
        // 选择造成伤害最高或存活的弟子
        val mvpUnit = aliveUnits.maxByOrNull { it.maxHp - it.currentHp }
        return mvpUnit?.name
    }
    
    /**
     * 生成AI防守队伍
     */
    fun generateAIDefenseTeam(sect: WorldSect): List<DefenseDisciple> {
        val team = mutableListOf<DefenseDisciple>()
        val sectDisciples = sect.disciples
        
        // 从高境界开始选取
        var remaining = 10
        for (realm in 0..9) {
            if (remaining <= 0) break
            val count = sectDisciples[realm] ?: 0
            val takeCount = minOf(count, remaining)
            
            repeat(takeCount) {
                team.add(createDefenseDisciple(sect, realm))
            }
            remaining -= takeCount
        }
        
        // 不足10人则填充
        while (team.size < 10) {
            val highestRealm = (0..9).firstOrNull { (sectDisciples[it] ?: 0) > 0 } ?: 9
            team.add(createDefenseDisciple(sect, highestRealm))
        }
        
        return team.take(10)
    }
    
    /**
     * 创建AI防守弟子
     */
    private fun createDefenseDisciple(sect: WorldSect, realm: Int): DefenseDisciple {
        val realmConfig = GameConfig.Realm.get(realm)
        val layer = Random.nextInt(1, 10)
        val layerBonus = 1.0 + (layer - 1) * 0.1
        
        return DefenseDisciple(
            id = "defense_${sect.id}_${Random.nextInt(10000)}",
            name = generateDefenseName(sect.name),
            realm = realm,
            realmLayer = layer,
            realmName = realmConfig.name,
            maxHp = (100 * realmConfig.multiplier * layerBonus).toInt(),
            maxMp = (50 * realmConfig.multiplier * layerBonus).toInt(),
            physicalAttack = (10 * realmConfig.multiplier * layerBonus).toInt(),
            magicAttack = (5 * realmConfig.multiplier * layerBonus).toInt(),
            physicalDefense = (5 * realmConfig.multiplier * layerBonus).toInt(),
            magicDefense = (3 * realmConfig.multiplier * layerBonus).toInt(),
            speed = (10 * realmConfig.multiplier * layerBonus).toInt(),
            critRate = 0.05
        )
    }
    
    /**
     * 生成防守弟子名称
     */
    private fun generateDefenseName(sectName: String): String {
        return GameUtils.generateRandomName(GameUtils.NameStyle.XIANXIA)
    }
}

/**
 * 战斗单位
 */
data class WarCombatant(
    val id: String,
    val name: String,
    val type: WarCombatantType,
    val maxHp: Int,
    var currentHp: Int,
    val maxMp: Int,
    var currentMp: Int,
    val physicalAttack: Int,
    val magicAttack: Int,
    val physicalDefense: Int,
    val magicDefense: Int,
    val speed: Int,
    val critRate: Double,
    val realm: Int,
    val realmName: String
) {
    val isDead: Boolean get() = currentHp <= 0
}

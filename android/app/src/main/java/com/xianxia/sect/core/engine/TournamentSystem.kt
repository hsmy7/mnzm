package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.TournamentReward
import kotlin.random.Random

object TournamentSystem {
    
    const val INTERVAL_YEARS = 3
    const val MAX_PARTICIPANTS = 32
    const val MIN_PARTICIPANTS = 2
    const val MAX_ROUNDS = 25
    
    data class TournamentParticipant(
        val discipleId: String,
        val name: String,
        val realm: Int,
        val power: Int
    )
    
    data class TournamentGroup(
        val realm: Int,
        val enabled: Boolean = true,
        val participants: List<TournamentParticipant> = emptyList(),
        val champion: TournamentParticipant? = null,
        val runnerUp: TournamentParticipant? = null,
        val semiFinalists: List<TournamentParticipant> = emptyList()
    )
    
    data class Tournament(
        val year: Int,
        val status: String = "进行中",
        val groups: Map<Int, TournamentGroup> = emptyMap()
    )
    
    data class Combatant(
        val name: String,
        var hp: Int,
        val maxHp: Int,
        var mp: Int,
        val maxMp: Int,
        val physicalAttack: Int,
        val magicAttack: Int,
        val physicalDefense: Int,
        val magicDefense: Int,
        val speed: Int,
        val critRate: Double = 0.05
    )
    
    fun initRewards(): Map<Int, TournamentReward> {
        val rewards = mutableMapOf<Int, TournamentReward>()
        for (realm in 0..9) {
            rewards[realm] = TournamentReward(
                enabled = true,
                championSpiritStones = getChampionReward(realm),
                runnerUpSpiritStones = getRunnerUpReward(realm)
            )
        }
        return rewards
    }
    
    private fun getChampionReward(realm: Int): Int {
        return when (realm) {
            9 -> 100
            8 -> 300
            7 -> 600
            6 -> 1000
            5 -> 2000
            4 -> 4000
            3 -> 8000
            2 -> 15000
            1 -> 30000
            0 -> 50000
            else -> 100
        }
    }
    
    private fun getRunnerUpReward(realm: Int): Int {
        return (getChampionReward(realm) * 0.5).toInt()
    }
    
    fun shouldHoldTournament(currentYear: Int, lastTournamentYear: Int): Boolean {
        return (currentYear - lastTournamentYear) >= INTERVAL_YEARS
    }
    
    fun getEnabledRealms(rewardsConfig: Map<Int, TournamentReward>): List<Int> {
        return rewardsConfig.filter { it.value.enabled }.keys.toList()
    }
    
    fun getDisciplePower(disciple: Disciple): Int {
        val stats = disciple.getBaseStats()
        return stats.physicalAttack + stats.magicAttack +
               stats.physicalDefense + stats.magicDefense +
               stats.speed + stats.maxHp / 10
    }
    
    fun selectParticipants(disciples: List<Disciple>, realm: Int): List<TournamentParticipant> {
        val candidates = disciples
            .filter { it.realm == realm && it.isAlive }
            .map { d ->
                TournamentParticipant(
                    discipleId = d.id,
                    name = d.name,
                    realm = d.realm,
                    power = getDisciplePower(d)
                )
            }
            .sortedByDescending { it.power }
            .take(MAX_PARTICIPANTS)
        
        return candidates
    }
    
    fun createTournament(
        year: Int,
        disciples: List<Disciple>,
        rewardsConfig: Map<Int, TournamentReward>
    ): Tournament {
        val groups = mutableMapOf<Int, TournamentGroup>()
        val enabledRealms = getEnabledRealms(rewardsConfig)
        
        enabledRealms.forEach { realm ->
            val participants = selectParticipants(disciples, realm)
            
            if (participants.size >= MIN_PARTICIPANTS) {
                groups[realm] = TournamentGroup(
                    realm = realm,
                    enabled = true,
                    participants = participants
                )
            }
        }
        
        return Tournament(year = year, groups = groups)
    }
    
    fun createCombatant(disciple: Disciple): Combatant {
        val stats = disciple.getBaseStats()
        return Combatant(
            name = disciple.name,
            hp = stats.maxHp,
            maxHp = stats.maxHp,
            mp = stats.maxMp,
            maxMp = stats.maxMp,
            physicalAttack = stats.physicalAttack,
            magicAttack = stats.magicAttack,
            physicalDefense = stats.physicalDefense,
            magicDefense = stats.magicDefense,
            speed = stats.speed,
            critRate = stats.critRate
        )
    }
    
    fun executeAttack(attacker: Combatant, target: Combatant) {
        val usePhysical = Random.nextBoolean()
        val attack = if (usePhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (usePhysical) target.physicalDefense else target.magicDefense
        
        var damage = (attack - defense / 2).coerceAtLeast(1)
        
        if (Random.nextDouble() < attacker.critRate) {
            damage = (damage * GameConfig.Battle.CRIT_MULTIPLIER).toInt()
        }
        
        val variance = Random.nextDouble(0.9, 1.1)
        damage = (damage * variance).toInt().coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE / 2)
        
        target.hp -= damage
    }
    
    fun simulateMatch(
        player1: TournamentParticipant,
        player2: TournamentParticipant,
        disciples: List<Disciple>
    ): TournamentParticipant {
        val d1 = disciples.find { it.id == player1.discipleId }
        val d2 = disciples.find { it.id == player2.discipleId }
        
        if (d1 == null) return player2
        if (d2 == null) return player1
        
        val combatant1 = createCombatant(d1)
        val combatant2 = createCombatant(d2)
        
        var turns = 0
        while (turns < MAX_ROUNDS && combatant1.hp > 0 && combatant2.hp > 0) {
            turns++
            
            val (first, second) = if (combatant1.speed >= combatant2.speed) {
                Pair(combatant1, combatant2)
            } else {
                Pair(combatant2, combatant1)
            }
            
            executeAttack(first, second)
            if (second.hp <= 0) break
            
            executeAttack(second, first)
        }
        
        return if (combatant1.hp > 0) player1 else player2
    }
    
    fun runGroupTournament(
        group: TournamentGroup,
        disciples: List<Disciple>
    ): TournamentGroup {
        var participants = group.participants.toMutableList()
        val results = mutableListOf<TournamentParticipant>()
        var semiFinalists = mutableListOf<TournamentParticipant>()
        
        while (participants.size > 1) {
            val nextRound = mutableListOf<TournamentParticipant>()
            
            for (i in participants.indices step 2) {
                if (i + 1 >= participants.size) {
                    nextRound.add(participants[i])
                    continue
                }
                
                if (participants.size == 4) {
                    semiFinalists = participants.toMutableList()
                }
                
                val winner = simulateMatch(participants[i], participants[i + 1], disciples)
                nextRound.add(winner)
            }
            
            participants = nextRound
        }
        
        val champion = participants.firstOrNull()
        val runnerUp = if (semiFinalists.size == 4 && champion != null) {
            val championIndex = semiFinalists.indexOf(champion)
            val opponentIndex = if (championIndex % 2 == 0) championIndex + 1 else championIndex - 1
            semiFinalists.getOrNull(opponentIndex)
        } else null
        
        return group.copy(
            champion = champion,
            runnerUp = runnerUp,
            semiFinalists = semiFinalists.filter { it.discipleId != champion?.discipleId }
        )
    }
    
    fun runTournament(
        tournament: Tournament,
        disciples: List<Disciple>,
        rewardsConfig: Map<Int, TournamentReward>
    ): Map<Int, String> {
        val champions = mutableMapOf<Int, String>()
        
        tournament.groups.forEach { (realm, group) ->
            if (!group.enabled || group.participants.size < MIN_PARTICIPANTS) {
                return@forEach
            }
            
            val result = runGroupTournament(group, disciples)
            
            val rewards = rewardsConfig[realm]
            if (rewards != null) {
                result.champion?.let { champions[realm] = it.name }
            }
        }
        
        return champions
    }
    
    fun generateChampionMessage(champions: Map<Int, String>): String {
        if (champions.isEmpty()) {
            return "宗门大比结束，各境界参赛人数不足"
        }
        
        val messages = champions.map { (realm, name) ->
            val realmName = GameConfig.Realm.getName(realm)
            "$realmName：$name"
        }
        
        return "宗门大比结束，各境界冠军：${messages.joinToString("，")}"
    }
}

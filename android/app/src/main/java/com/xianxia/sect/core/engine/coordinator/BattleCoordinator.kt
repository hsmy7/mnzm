@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.coordinator

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.Battle
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.ListenerManager
import com.xianxia.sect.core.util.PerformanceMonitor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BattleCoordinator @Inject constructor(
    private val performanceMonitor: PerformanceMonitor,
    private val battleSystem: BattleSystem
) {
    companion object {
        private const val TAG = "BattleCoordinator"
        private const val MAX_BATTLE_DURATION_MS = GameConfig.Battle.MAX_BATTLE_DURATION_MS
        private const val BATTLE_TIMEOUT_WARNING_MS = GameConfig.Battle.BATTLE_TIMEOUT_WARNING_MS
    }
    
    data class BattleContext(
        val battleType: BattleType,
        val teamSize: Int,
        val averageTeamRealm: Double,
        val beastCount: Int
    )
    
    data class BattleResult(
        val battle: Battle,
        val victory: Boolean,
        val rewards: Map<String, Int>,
        val durationMs: Long,
        val turnCount: Int,
        val timedOut: Boolean = false
    )
    
    enum class BattleType {
        EXPLORATION,
        CAVE,
        DUNGEON,
        PVP,
        AI_SECT_ATTACK
    }
    
    interface BattleEventListener {
        fun onBattleStart(context: BattleContext)
        fun onBattleEnd(context: BattleContext, result: BattleResult)
        fun onBattleTimeout(context: BattleContext, partialResult: BattleResult?)
    }
    
    private val listeners = ListenerManager<BattleEventListener>(TAG)
    
    fun addListener(listener: BattleEventListener) = listeners.add(listener)
    
    fun removeListener(listener: BattleEventListener) = listeners.remove(listener)
    
    fun executeBattleWithTimeout(
        disciples: List<Disciple>,
        equipmentMap: Map<String, Equipment>,
        manualMap: Map<String, Manual>,
        beastLevel: Int,
        beastCount: Int? = null,
        beastType: String? = null,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>> = emptyMap(),
        battleType: BattleType = BattleType.EXPLORATION,
        timeoutMs: Long = MAX_BATTLE_DURATION_MS
    ): BattleResult {
        val startTime = System.currentTimeMillis()
        
        val context = BattleContext(
            battleType = battleType,
            teamSize = disciples.size,
            averageTeamRealm = GameUtils.calculateTeamAverageRealm(
                disciples,
                realmExtractor = { it.realm },
                layerExtractor = { it.realmLayer }
            ),
            beastCount = beastCount ?: 1
        )
        
        notifyBattleStart(context)
        
        return try {
            performanceMonitor.measureOperation("battle_${battleType.name.lowercase()}") {
                val battle = battleSystem.createBattle(
                    disciples = disciples,
                    equipmentMap = equipmentMap,
                    manualMap = manualMap,
                    beastLevel = beastLevel,
                    beastCount = beastCount,
                    beastType = beastType,
                    manualProficiencies = manualProficiencies
                )
                
                val result = battleSystem.executeBattleWithTimeout(battle, timeoutMs)
                
                BattleResult(
                    battle = result.battle,
                    victory = result.victory,
                    rewards = result.rewards,
                    durationMs = result.durationMs,
                    turnCount = result.turnCount,
                    timedOut = result.timedOut
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battle execution failed", e)
            val durationMs = System.currentTimeMillis() - startTime
            BattleResult(
                battle = Battle(team = emptyList(), beasts = emptyList()),
                victory = false,
                rewards = emptyMap(),
                durationMs = durationMs,
                turnCount = 0,
                timedOut = false
            )
        }.also { result ->
            notifyBattleEnd(context, result)
        }
    }
    
    fun quickBattle(
        disciples: List<Disciple>,
        equipmentMap: Map<String, Equipment>,
        manualMap: Map<String, Manual>,
        beastLevel: Int,
        beastCount: Int? = null,
        manualProficiencies: Map<String, Map<String, ManualProficiencyData>> = emptyMap()
    ): BattleResult {
        return executeBattleWithTimeout(
            disciples = disciples,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = beastLevel,
            beastCount = beastCount,
            manualProficiencies = manualProficiencies,
            battleType = BattleType.EXPLORATION,
            timeoutMs = MAX_BATTLE_DURATION_MS / 2
        )
    }
    
    fun validateBattleFeasibility(
        disciples: List<Disciple>,
        beastLevel: Int,
        beastCount: Int
    ): BattleFeasibility {
        if (disciples.isEmpty()) {
            return BattleFeasibility.NOT_ENOUGH_DISCIPLES
        }
        
        val aliveDisciples = disciples.filter { it.isAlive }
        if (aliveDisciples.isEmpty()) {
            return BattleFeasibility.ALL_DISCIPLES_DEAD
        }
        
        if (aliveDisciples.size > GameConfig.Battle.MAX_TEAM_SIZE) {
            return BattleFeasibility.TEAM_TOO_LARGE
        }
        
        val teamAvgRealm = GameUtils.calculateTeamAverageRealm(
            aliveDisciples,
            realmExtractor = { it.realm },
            layerExtractor = { it.realmLayer }
        )
        val difficultyGap = beastLevel - teamAvgRealm
        
        return when {
            difficultyGap > 3 -> BattleFeasibility.TOO_DIFFICULT
            difficultyGap < -3 -> BattleFeasibility.TOO_EASY
            else -> BattleFeasibility.FEASIBLE
        }
    }
    
    enum class BattleFeasibility {
        FEASIBLE,
        NOT_ENOUGH_DISCIPLES,
        ALL_DISCIPLES_DEAD,
        TEAM_TOO_LARGE,
        TOO_DIFFICULT,
        TOO_EASY
    }
    
    private fun notifyBattleStart(context: BattleContext) = 
        listeners.notify { it.onBattleStart(context) }
    
    private fun notifyBattleEnd(context: BattleContext, result: BattleResult) = 
        listeners.notify { it.onBattleEnd(context, result) }
    
    fun cleanup() {
        listeners.clear()
    }
}

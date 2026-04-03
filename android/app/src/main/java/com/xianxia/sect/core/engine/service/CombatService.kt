package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.engine.BattleSystemResult
import com.xianxia.sect.core.data.TalentDatabase
import kotlin.random.Random

/**
 * 战斗服务 - 负责战斗系统管理
 *
 * 职责域：
 * - 委托给 BattleSystem 执行战斗
 * - 战斗日志管理
 * - 战斗结果处理
 * - 战斗奖励计算
 */
class CombatService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _manuals: MutableStateFlow<List<Manual>>,
    private val _battleLogs: MutableStateFlow<List<BattleLog>>,
    private val battleSystem: BattleSystem,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "CombatService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get battle logs StateFlow
     */
    fun getBattleLogs(): StateFlow<List<BattleLog>> = _battleLogs

    // ==================== 战斗执行 ====================

    /**
     * Execute dungeon exploration battle
     */
    fun executeExplorationBattle(
        teamMembers: List<Disciple>,
        dungeon: Dungeon
    ): BattleSystemResult {
        val data = _gameData.value

        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = battleSystem.createBattle(
            disciples = teamMembers,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = dungeon.realm,
            manualProficiencies = allProficiencies
        )

        val result = battleSystem.executeBattle(battle)

        // Record battle log (convert BattleLogData to BattleLog)
        val battleLog = BattleLog(
            year = data.gameYear,
            month = data.gameMonth,
            type = com.xianxia.sect.core.model.BattleType.PVE,
            attackerName = teamMembers.joinToString(", ") { it.name },
            defenderName = dungeon.name,
            result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
            dungeonName = dungeon.name
        )
        _battleLogs.value = listOf(battleLog) + _battleLogs.value.take(49)

        return result
    }

    /**
     * Execute cave exploration battle against AI team
     */
    fun executeCaveAIBattle(
        playerDisciples: List<Disciple>,
        aiTeam: AICaveTeam
    ): BattleSystemResult {
        val data = _gameData.value

        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = CaveExplorationSystem.createAIBattle(
            playerDisciples = playerDisciples,
            playerEquipmentMap = equipmentMap,
            playerManualMap = manualMap,
            playerManualProficiencies = allProficiencies,
            aiTeam = aiTeam
        )

        return battleSystem.executeBattle(battle)
    }

    /**
     * Execute cave guardian battle
     */
    fun executeCaveGuardianBattle(
        playerDisciples: List<Disciple>,
        cave: CultivatorCave
    ): BattleSystemResult {
        val data = _gameData.value

        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = CaveExplorationSystem.createGuardianBattle(
            playerDisciples = playerDisciples,
            playerEquipmentMap = equipmentMap,
            playerManualMap = manualMap,
            playerManualProficiencies = allProficiencies,
            cave = cave
        )

        return battleSystem.executeBattle(battle)
    }

    // ==================== 战斗结果处理 ====================

    /**
     * Process battle victory - apply bonuses to survivors
     */
    fun applyBattleVictoryBonuses(memberIds: List<String>, addSoulPower: Boolean) {
        memberIds.forEach { memberId ->
            val discipleIndex = _disciples.value.indexOfFirst { it.id == memberId }
            if (discipleIndex < 0) return@forEach

            val disciple = _disciples.value[discipleIndex]
            if (!disciple.isAlive) return@forEach

            // Add combat experience (battles won count)
            val newBattlesWon = disciple.battlesWon + 1

            // Add soul power if requested
            val newSoulPower = if (addSoulPower) disciple.soulPower + 1 else disciple.soulPower

            // Small chance to increase stats from battle experience
            val newHp = if (Random.nextDouble() < 0.1) disciple.baseHp + 1 else disciple.baseHp
            val newAttack = if (Random.nextDouble() < 0.1) disciple.basePhysicalAttack + 1 else disciple.basePhysicalAttack
            val newDefense = if (Random.nextDouble() < 0.1) disciple.basePhysicalDefense + 1 else disciple.basePhysicalDefense

            val updatedDisciple = disciple.copyWith(
                battlesWon = newBattlesWon,
                soulPower = newSoulPower,
                baseHp = newHp,
                basePhysicalAttack = newAttack,
                basePhysicalDefense = newDefense
            )

            _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = updatedDisciple }
        }
    }

    /**
     * Process battle casualties - update disciples status and handle deaths
     */
    fun processBattleCasualties(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>
    ) {
        deadMemberIds.forEach { memberId ->
            val discipleIndex = _disciples.value.indexOfFirst { it.id == memberId }
            if (discipleIndex < 0) return@forEach

            val disciple = _disciples.value[discipleIndex]
            val updatedDisciple = disciple.copy(isAlive = false, status = DiscipleStatus.IDLE)
            _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = updatedDisciple }

            addEvent("${disciple.name} 在战斗中阵亡", EventType.DANGER)
        }

        // 清理阵亡弟子的所有槽位
        if (deadMemberIds.isNotEmpty()) {
            synchronized(transactionMutex) {
                val data = _gameData.value

                // 清理执法堂槽位
                val updatedElderSlots = data.elderSlots.let { slots ->
                    var updated = slots

                    // 清理执法长老
                    if (updated.lawEnforcementElder in deadMemberIds) {
                        updated = updated.copy(lawEnforcementElder = null)
                    }

                    // 清理执法弟子槽位
                    updated = updated.copy(
                        lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        }
                    )

                    // 清理其他长老职位
                    if (updated.viceSectMaster in deadMemberIds) updated = updated.copy(viceSectMaster = null)
                    if (updated.innerElder in deadMemberIds) updated = updated.copy(innerElder = null)
                    if (updated.outerElder in deadMemberIds) updated = updated.copy(outerElder = null)
                    if (updated.preachingElder in deadMemberIds) updated = updated.copy(preachingElder = null)
                    if (updated.herbGardenElder in deadMemberIds) updated = updated.copy(herbGardenElder = null)
                    if (updated.alchemyElder in deadMemberIds) updated = updated.copy(alchemyElder = null)
                    if (updated.forgeElder in deadMemberIds) updated = updated.copy(forgeElder = null)
                    if (updated.qingyunPreachingElder in deadMemberIds) updated = updated.copy(qingyunPreachingElder = null)

                    // 清理传功、灵药园、炼丹、锻造等弟子槽位
                    updated = updated.copy(
                        preachingMasters = updated.preachingMasters.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        qingyunPreachingMasters = updated.qingyunPreachingMasters.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        herbGardenDisciples = updated.herbGardenDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        }
                    )

                    updated
                }

                // 清理锻造槽位
                val updatedForgeSlots = data.forgeSlots.filter { slot ->
                    slot.discipleId == null || slot.discipleId !in deadMemberIds
                }

                // 清理灵矿槽位
                val updatedSpiritMineSlots = data.spiritMineSlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = null, discipleName = "") else slot
                }

                // 清理藏经阁槽位
                val updatedLibrarySlots = data.librarySlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = null, discipleName = "") else slot
                }

                _gameData.value = data.copy(
                    elderSlots = updatedElderSlots,
                    forgeSlots = updatedForgeSlots,
                    spiritMineSlots = updatedSpiritMineSlots,
                    librarySlots = updatedLibrarySlots
                )
            }
        }

        // Update HP for survivors
        survivorHpMap.forEach { (memberId, hp) ->
            val discipleIndex = _disciples.value.indexOfFirst { it.id == memberId }
            if (discipleIndex >= 0 && !deadMemberIds.contains(memberId)) {
                val disciple = _disciples.value[discipleIndex]
                val updatedStatusData = disciple.statusData + ("currentHp" to hp.toString())
                val updatedDisciple = disciple.copy(status = DiscipleStatus.IDLE, statusData = updatedStatusData)
                _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = updatedDisciple }
            }
        }
    }

    /**
     * Clear temporary pill effects after battle
     */
    fun clearBattlePillEffects(teamMembers: List<Disciple>) {
        // Pills may have temporary buffs that should be cleared after battle
        // This would remove any temporary stat modifications from consumable pills
    }

    // ==================== 统计查询 ====================

    /**
     * Get total battles fought
     */
    fun getTotalBattlesCount(): Int {
        return _battleLogs.value.size
    }

    /**
     * Get recent battle results (last N)
     */
    fun getRecentBattles(count: Int = 10): List<BattleLog> {
        return _battleLogs.value.take(count)
    }

    /**
     * Get win rate for last N battles
     */
    fun getWinRate(lastNBattles: Int = 50): Double {
        val recentBattles = _battleLogs.value.take(lastNBattles)
        if (recentBattles.isEmpty()) return 0.0

        val wins = recentBattles.count { it.result == BattleResult.WIN }
        return wins.toDouble() / recentBattles.size
    }
}

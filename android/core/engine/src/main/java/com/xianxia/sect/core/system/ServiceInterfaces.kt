package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.engine.domain.battle.BattleSystemResult
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.flow.StateFlow

/**
 * 服务层接口契约。每个接口声明对应服务的领域特定方法，
 * 不扩展 GameSystem（由具体类同时实现两者）。
 * GameEngine 通过接口依赖而非具体类。
 */

interface SaveSystem {
    fun getStateSnapshotSync(): GameStateSnapshot
    suspend fun getStateSnapshot(): GameStateSnapshot
    suspend fun loadFromSave(
        loadedGameData: GameData,
        disciples: List<Disciple>,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: List<EquipmentInstance>,
        manualStacks: List<ManualStack>,
        manualInstances: List<ManualInstance>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>,
        battleLogs: List<BattleLog>,
        teams: List<ExplorationTeam>
    )
    fun validateState(): List<String>
    fun getStateStatistics(): Map<String, Any>
    fun isGameStarted(): Boolean
    fun getFormattedGameTime(): String
}

interface FormulaSystem {
    fun calculateSuccessRateBonus(disciple: Disciple?, buildingId: String): Double
    fun calculateWorkDurationWithAllDisciples(baseDuration: Int, buildingId: String): Int
    fun calculateElderAndDisciplesBonus(buildingType: String): FormulaService.ElderBonusData
}

interface CombatSystem {
    fun getBattleLogs(): StateFlow<List<BattleLog>>
    fun executeCaveAIBattle(playerDisciples: List<Disciple>, aiTeam: AICaveTeam): BattleSystemResult
    fun executeCaveGuardianBattle(playerDisciples: List<Disciple>, cave: CultivatorCave): BattleSystemResult
    suspend fun processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>, survivorMpMap: Map<String, Int> = emptyMap(), isOutsideSect: Boolean = true)
    fun getTotalBattlesCount(): Int
    fun getRecentBattles(count: Int = 10): List<BattleLog>
    fun getWinRate(lastNBattles: Int = 50): Double
}

interface ExplorationSystem {
    fun getTeams(): StateFlow<List<ExplorationTeam>>
    fun recallDiscipleFromTeam(teamId: String, discipleId: String): Boolean
    fun completeExploration(teamId: String, success: Boolean, survivorIds: List<String>)
}

interface RedeemCodeSystem {
    suspend fun redeemCode(code: String, usedCodes: List<String>, currentYear: Int, currentMonth: Int): RedeemResult
}

interface BuildingSystem {
    fun getBuildingSlots(): List<BuildingSlot>
    fun getAlchemySlots(): List<AlchemySlot>
    fun getPlantSlots(): List<PlantSlotData>
    suspend fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String)
    suspend fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int)
    fun getBuildingSlotsForBuilding(buildingId: String): List<BuildingSlot>
    suspend fun startAlchemy(slotIndex: Int, recipeId: String): Boolean
    suspend fun startForging(slotIndex: Int, recipeId: String): Boolean
    suspend fun autoHarvestCompletedAlchemySlots(): List<AlchemyResult>
    fun autoHarvestForgeSlot(slot: ProductionSlot)
    fun clearPlantSlot(slotIndex: Int)
}

interface DiplomacySystem {
    fun giftSpiritStones(sectId: String, tier: Int): DiplomacyService.GiftResult
    fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String>
    fun dissolveAlliance(sectId: String): Pair<Boolean, String>
    fun getRejectProbability(sectLevel: Int, rarity: Int): Int
    fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int>
    fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double
    fun getEnvoyRealmRequirement(sectLevel: Int): Int
    fun getAllianceCost(sectLevel: Int): Long
    fun generateSectTradeItems(year: Int, sectId: String? = null): List<MerchantItem>
    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem>
    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1)
    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1)
}

interface DiscipleManagementSystem {
    fun getDisciples(): StateFlow<List<Disciple>>
    fun addDisciple(disciple: Disciple)
    fun removeDisciple(discipleId: String): Boolean
    fun getDiscipleById(discipleId: String): Disciple?
    fun updateDisciple(disciple: Disciple)
    fun getDiscipleStatus(discipleId: String): DiscipleStatus
    fun syncAllDiscipleStatuses()
    suspend fun resetAllDisciplesStatus()
    fun recruitDisciple(): Disciple
    suspend fun expelDisciple(discipleId: String): Boolean
    suspend fun equipEquipment(discipleId: String, equipmentId: String): Boolean
    suspend fun unequipEquipment(discipleId: String, equipmentId: String): Boolean
    suspend fun clearDiscipleFromAllSlots(discipleId: String)
    suspend fun autoFillLawEnforcementSlots(): Int
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean
    fun getAliveDisciplesCount(): Int
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple>
    fun getIdleDisciples(): List<Disciple>
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate?
    fun getAllDiscipleAggregates(): List<DiscipleAggregate>
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean)
}

interface CultivationSystem {
    fun getHighFrequencyData(): StateFlow<HighFrequencyData>
    fun resetHighFrequencyData()
    fun updateRealtimeCultivation(currentTimeMillis: Long, state: MutableGameState? = null)
    suspend fun advancePhase(state: MutableGameState? = null)
    suspend fun advanceMonth(state: MutableGameState? = null)
    suspend fun advanceYear(state: MutableGameState? = null)
    suspend fun processCaveLifecycle(year: Int, month: Int)
}

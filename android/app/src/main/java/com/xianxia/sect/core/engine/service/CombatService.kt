@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.engine.BattleSystemResult
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
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
        synchronized(transactionMutex) {
            val data = _gameData.value
            val equipmentMap = _equipment.value.associateBy { it.id }
            val manualMap = _manuals.value.associateBy { it.id }
            var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
            val discipleUpdates = mutableMapOf<Int, Disciple>()

            memberIds.forEach { memberId ->
                val discipleIndex = _disciples.value.indexOfFirst { it.id == memberId }
                if (discipleIndex < 0) return@forEach

                val disciple = _disciples.value[discipleIndex]
                if (!disciple.isAlive) return@forEach

                val newSoulPower = if (addSoulPower) disciple.soulPower + 1 else disciple.soulPower

                val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
                val winGrowthAttr = if (talentEffects["winBattleRandomAttrPlus"] != null) {
                    listOf("maxHp", "maxMp", "physicalAttack", "magicAttack", "physicalDefense", "magicDefense", "speed").random()
                } else null

                var updatedDisciple = disciple.copyWith(
                    soulPower = newSoulPower
                )

                if (winGrowthAttr != null) {
                    val currentGrowth = updatedDisciple.statusData["winGrowth.$winGrowthAttr"]?.toIntOrNull() ?: 0
                    val newStatusData = updatedDisciple.statusData.toMutableMap().apply {
                        put("winGrowth.$winGrowthAttr", (currentGrowth + 1).toString())
                    }
                    updatedDisciple = when (winGrowthAttr) {
                        "maxHp" -> updatedDisciple.copyWith(baseHp = updatedDisciple.baseHp + 1, statusData = newStatusData)
                        "maxMp" -> updatedDisciple.copyWith(baseMp = updatedDisciple.baseMp + 1, statusData = newStatusData)
                        "physicalAttack" -> updatedDisciple.copyWith(basePhysicalAttack = updatedDisciple.basePhysicalAttack + 1, statusData = newStatusData)
                        "magicAttack" -> updatedDisciple.copyWith(baseMagicAttack = updatedDisciple.baseMagicAttack + 1, statusData = newStatusData)
                        "physicalDefense" -> updatedDisciple.copyWith(basePhysicalDefense = updatedDisciple.basePhysicalDefense + 1, statusData = newStatusData)
                        "magicDefense" -> updatedDisciple.copyWith(baseMagicDefense = updatedDisciple.baseMagicDefense + 1, statusData = newStatusData)
                        "speed" -> updatedDisciple.copyWith(baseSpeed = updatedDisciple.baseSpeed + 1, statusData = newStatusData)
                        else -> updatedDisciple
                    }
                }

                disciple.manualIds.forEach { manualId ->
                    val manual = manualMap[manualId] ?: return@forEach
                    val proficiencyList = updatedManualProficiencies.getOrPut(memberId) { mutableListOf<ManualProficiencyData>() } as MutableList<ManualProficiencyData>
                    val existingIndex = proficiencyList.indexOfFirst { it.manualId == manualId }
                    val existing = if (existingIndex >= 0) proficiencyList[existingIndex] else null
                    val maxProf = ManualProficiencySystem.getMaxProficiency(manual.rarity)
                    val gain03pct = maxProf * 0.03
                    val newProficiency = if (existing != null) {
                        (existing.proficiency + gain03pct).coerceAtMost(maxProf)
                    } else {
                        gain03pct.coerceAtMost(maxProf)
                    }
                    val newMasteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProficiency, manual.rarity).level
                    val updated = ManualProficiencyData(
                        manualId = manualId,
                        manualName = manual.name,
                        proficiency = newProficiency,
                        maxProficiency = maxProf.toInt(),
                        level = newMasteryLevel,
                        masteryLevel = newMasteryLevel
                    )
                    if (existingIndex >= 0) {
                        proficiencyList[existingIndex] = updated
                    } else {
                        proficiencyList.add(updated)
                    }
                }

                var eqSet = updatedDisciple.equipment

                updatedDisciple.weaponId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.weaponNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(weaponNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }
                updatedDisciple.armorId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.armorNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(armorNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }
                updatedDisciple.bootsId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.bootsNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(bootsNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }
                updatedDisciple.accessoryId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.accessoryNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(accessoryNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }

                discipleUpdates[discipleIndex] = updatedDisciple.copy(equipment = eqSet)
            }

            if (discipleUpdates.isNotEmpty()) {
                _disciples.value = _disciples.value.toMutableList().also { list ->
                    discipleUpdates.forEach { (index, d) -> list[index] = d }
                }
            }

            if (updatedManualProficiencies != data.manualProficiencies) {
                _gameData.value = data.copy(manualProficiencies = updatedManualProficiencies)
            }
        }
    }

    /**
     * Process battle casualties - update disciples status and handle deaths
     */
    fun processBattleCasualties(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int> = emptyMap()
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

                    if (updated.lawEnforcementElder in deadMemberIds) {
                        updated = updated.copy(lawEnforcementElder = "")
                    }

                    updated = updated.copy(
                        lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        }
                    )

                    if (updated.viceSectMaster in deadMemberIds) updated = updated.copy(viceSectMaster = "")
                    if (updated.innerElder in deadMemberIds) updated = updated.copy(innerElder = "")
                    if (updated.outerElder in deadMemberIds) updated = updated.copy(outerElder = "")
                    if (updated.preachingElder in deadMemberIds) updated = updated.copy(preachingElder = "")
                    if (updated.herbGardenElder in deadMemberIds) updated = updated.copy(herbGardenElder = "")
                    if (updated.alchemyElder in deadMemberIds) updated = updated.copy(alchemyElder = "")
                    if (updated.forgeElder in deadMemberIds) updated = updated.copy(forgeElder = "")
                    if (updated.qingyunPreachingElder in deadMemberIds) updated = updated.copy(qingyunPreachingElder = "")

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
                        herbGardenReserveDisciples = updated.herbGardenReserveDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        alchemyReserveDisciples = updated.alchemyReserveDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                            if (slot.discipleId in deadMemberIds) DirectDiscipleSlot(index = slot.index) else slot
                        },
                        forgeReserveDisciples = updated.forgeReserveDisciples.mapNotNull { slot ->
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
                    slot.discipleId?.isEmpty() == true || slot.discipleId !in deadMemberIds
                }

                val updatedSpiritMineSlots = data.spiritMineSlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
                }

                val updatedLibrarySlots = data.librarySlots.map { slot ->
                    if (slot.discipleId in deadMemberIds) slot.copy(discipleId = "", discipleName = "") else slot
                }

                _gameData.value = data.copy(
                    elderSlots = updatedElderSlots,
                    forgeSlots = updatedForgeSlots,
                    spiritMineSlots = updatedSpiritMineSlots,
                    librarySlots = updatedLibrarySlots
                )
            }
        }

        // Update HP/MP for survivors
        survivorHpMap.forEach { (memberId, hp) ->
            val discipleIndex = _disciples.value.indexOfFirst { it.id == memberId }
            if (discipleIndex >= 0 && !deadMemberIds.contains(memberId)) {
                val disciple = _disciples.value[discipleIndex]
                val mp = survivorMpMap[memberId] ?: disciple.currentMp
                val updatedStatus = if (disciple.status == DiscipleStatus.IN_TEAM) DiscipleStatus.IDLE else disciple.status
                val updatedDisciple = disciple.copyWith(
                    status = updatedStatus,
                    currentHp = hp,
                    currentMp = mp
                )
                _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = updatedDisciple }
            }
        }
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

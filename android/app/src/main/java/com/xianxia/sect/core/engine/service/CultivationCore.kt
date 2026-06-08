package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.di.ApplicationScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

@Singleton
class CultivationCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val thermalMonitor: ThermalMonitor,
    private val gameClock: GameTimeClock,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    private val scope get() = applicationScopeProvider.scope

    // State accessors - same pattern as CultivationService
    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }
    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }
    private var currentEquipmentStacks: List<EquipmentStack>
        get() = stateStore.currentTransactionMutableState()?.equipmentStacks ?: stateStore.equipmentStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentStacks = value; return }
            scope.launch { stateStore.update { equipmentStacks = value } }
        }
    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch { stateStore.update { equipmentInstances = value } }
        }
    private var currentManualStacks: List<ManualStack>
        get() = stateStore.currentTransactionMutableState()?.manualStacks ?: stateStore.manualStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualStacks = value; return }
            scope.launch { stateStore.update { manualStacks = value } }
        }
    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch { stateStore.update { manualInstances = value } }
        }

    val phaseMultiplier: Int get() = 10

    fun calculateDiscipleCultivationPerSecond(disciple: Disciple, data: GameData): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)
        val allDisciples = currentDisciples.associateBy { it.id }
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(disciple, data, allDisciples, "outer")
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(disciple, data, allDisciples, "inner")

        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
        }

        val manualInstanceMap = currentManualInstances.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

        val parent1 = disciple.social.parentId1?.let { allDisciples[it] }
        val parent2 = disciple.social.parentId2?.let { allDisciples[it] }
        val parentCultivationBonus = DiscipleStatCalculator.calculateParentCultivationBonus(parent1, parent2)

        val griefPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_CULTIVATION_SPEED_PENALTY
        } else {
            0.0
        }

        return DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty
        ).coerceIn(1.0, 1000.0)
    }

    private fun calculatePreachingBonuses(
        disciple: Disciple,
        data: GameData,
        allDisciples: Map<String, Disciple>,
        targetDiscipleType: String
    ): Pair<Double, Double> {
        val elderSlots = data.elderSlots
        val preachingElder = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingElder.let { id -> if (id.isNotEmpty()) allDisciples[id] else null }
            "inner" -> elderSlots.qingyunPreachingElder.let { id -> if (id.isNotEmpty()) allDisciples[id] else null }
            else -> null
        }
        val preachingMasters = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingMasters.mapNotNull { slot -> slot.discipleId?.let { allDisciples[it] } }
            "inner" -> elderSlots.qingyunPreachingMasters.mapNotNull { slot -> slot.discipleId?.let { allDisciples[it] } }
            else -> emptyList()
        }
        return DiscipleStatCalculator.calculatePreachingBonus(
            disciple = disciple,
            targetDiscipleType = targetDiscipleType,
            preachingElder = preachingElder,
            preachingMasters = preachingMasters
        )
    }

    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        val slot = data.residenceSlots.firstOrNull { it.discipleId == disciple.id } ?: return 1.0
        val building = data.placedBuildings.firstOrNull { it.instanceId == slot.buildingInstanceId } ?: return 1.0
        return when (building.displayName) {
            "中级单人住所" -> 1.40
            "单人住所" -> 1.20
            "多人住所" -> 1.10
            else -> 1.0
        }
    }

    fun isDiscipleFullHpMp(disciple: Disciple): Boolean {
        val hp = if (disciple.combat.currentHp < 0) disciple.maxHp else disciple.combat.currentHp
        val mp = if (disciple.combat.currentMp < 0) disciple.maxMp else disciple.combat.currentMp
        return hp >= disciple.maxHp && mp >= disciple.maxMp
    }

    fun getLifespanGainForRealm(realm: Int): Int {
        return when (realm) {
            8 -> 50
            7 -> 100
            6 -> 200
            5 -> 400
            4 -> 800
            3 -> 1500
            2 -> 3000
            1 -> 5000
            0 -> 10000
            else -> 0
        }
    }

    fun recoverHpMpForAllDisciples(state: MutableGameState) {
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()

        state.disciples = state.disciples.map { d ->
            if (!d.isAlive) return@map d
            val curHp = d.combat.currentHp
            val curMp = d.combat.currentMp
            if (curHp < 0 && curMp < 0) return@map d

            val proficiencyMap = allProficiencies[d.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, proficiencyMap)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp

            val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
            val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

            if (newHp != curHp || newMp != curMp) {
                d.copyWith(currentHp = newHp, currentMp = newMp)
            } else {
                d
            }
        }
    }

    fun recoverHpMpForBattleParticipants(discipleIds: List<String>) {
        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
        val allProficiencies = currentGameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()

        currentDisciples = currentDisciples.map { d ->
            if (d.id !in discipleIds || !d.isAlive) return@map d
            var curHp = d.combat.currentHp
            var curMp = d.combat.currentMp
            if (curHp >= d.maxHp && curMp >= d.maxMp) return@map d
            val discipleProficiencies = allProficiencies[d.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, discipleProficiencies)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp
            curHp = if (curHp < 0) curHp else (curHp + (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxHp)
            curMp = if (curMp < 0) curMp else (curMp + (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxMp)
            if (curHp != d.combat.currentHp || curMp != d.combat.currentMp) d.copyWith(currentHp = curHp, currentMp = curMp) else d
        }
    }

    fun processDiscipleTick(
        disciple: Disciple,
        year: Int, month: Int, phase: Int,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        proficienciesMap: Map<String, List<ManualProficiencyData>>,
        multiplier: Double, decay: Int,
        equipmentStacksList: List<EquipmentStack>,
        manualStacksList: List<ManualStack>,
        maxEquipStack: Int, maxManualStack: Int,
        acc: PhaseTickAccumulator,
        phaseSecondsValue: Double,
        focusedDiscipleId: String?,
        cachedCultivationRates: Map<String, Double>,
        highFrequencyData: HighFrequencyData,
        autoEquipDirty: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>,
        autoLearnDirty: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>
    ): Disciple {
        var d = disciple

        if (d.cultivationSpeedDuration > 0) {
            val newDuration = d.cultivationSpeedDuration - decay
            if (newDuration <= 0) {
                d = d.copy(cultivationSpeedBonus = 0.0, cultivationSpeedDuration = 0)
            } else {
                d = d.copy(cultivationSpeedDuration = newDuration)
            }
        }

        if (d.pillEffects.pillEffectDuration > 0) {
            val newDuration = d.pillEffects.pillEffectDuration - decay
            if (newDuration <= 0) {
                d = d.copy(pillEffects = PillEffects())
            } else {
                d = d.copy(pillEffects = d.pillEffects.copy(pillEffectDuration = newDuration))
            }
        }

        val discipleProficiencies = proficienciesMap.getOrDefault(d.id, emptyList())
            .associateBy { it.manualId }
        val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, discipleProficiencies)
        val maxHp = finalStats.maxHp
        val maxMp = finalStats.maxMp
        val curHp = d.combat.currentHp
        val curMp = d.combat.currentMp

        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)

        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp || newMp != curMp) {
            d = d.copyWith(currentHp = newHp, currentMp = newMp)
        }

        if (d.id != focusedDiscipleId) {
            val cultivationRate = cachedCultivationRates[d.id] ?: 0.0
            if (cultivationRate > 0 && d.cultivation < d.maxCultivation) {
                val gain = cultivationRate * phaseSecondsValue
                d = d.copy(cultivation = (d.cultivation + gain).coerceAtMost(d.maxCultivation))
            }
        }

        val pillResult = DisciplePillManager.processAutoUsePills(
            disciple = d, gameYear = year, gameMonth = month, gamePhase = phase
        )
        if (pillResult.disciple != d) {
            d = pillResult.disciple
        }

        val hasEquipmentInBag = d.equipment.storageBagItems.any { it.itemType == "equipment" }
        val needEquipCheck = hasEquipmentInBag || d.id in autoEquipDirty
        val equipResult = if (needEquipCheck) {
            DiscipleEquipmentManager.processAutoEquip(
                disciple = d, equipmentStacks = equipmentStacksList,
                equipmentInstances = equipmentMap,
                gameYear = year, gameMonth = month, gamePhase = phase,
                maxStack = maxEquipStack
            )
        } else null
        if (equipResult != null && equipResult.newInstances.isNotEmpty()) {
            d = equipResult.disciple
            acc.equipInstancesToAdd.addAll(equipResult.newInstances)
            equipResult.replacedInstances.forEach { acc.equipInstanceIdsToRemove.add(it.id) }
            equipResult.stackUpdates.forEach { update ->
                if (update.isDeletion) {
                    acc.equipStackDeletions.add(update.stackId)
                } else {
                    acc.equipStackQuantityDeltas.merge(update.stackId, update.newQuantity) { _, new -> new }
                }
            }
            equipResult.replacedEquipmentStacks.forEach { replacedStack ->
                acc.equipStackAdditions.add(replacedStack)
            }
        }

        val hasManualInBag = d.equipment.storageBagItems.any { it.itemType == "manual" }
        val needLearnCheck = hasManualInBag || d.id in autoLearnDirty
        val manualResult = if (needLearnCheck) {
            DiscipleManualManager.processAutoLearn(
                disciple = d, manualStacks = manualStacksList,
                manualInstances = manualMap,
                gameYear = year, gameMonth = month, gamePhase = phase,
                maxStack = maxManualStack
            )
        } else null
        if (manualResult != null && manualResult.newInstance != null) {
            d = manualResult.disciple
            manualResult.newInstance?.let { acc.manualInstancesToAdd.add(it) }
            manualResult.replacedInstance?.let { replaced ->
                acc.manualInstanceIdsToRemove.add(replaced.id)
                acc.profRemovals.getOrPut(d.id) { mutableSetOf() }.add(replaced.id)
            }
            manualResult.stackUpdate?.let { update ->
                if (update.isDeletion) {
                    acc.manualStackDeletions.add(update.stackId)
                } else {
                    acc.manualStackQuantityDeltas.merge(update.stackId, update.newQuantity) { _, new -> new }
                }
            }
            manualResult.replacedManualStack?.let { replacedStack ->
                acc.manualStackAdditions.add(replacedStack)
            }
        }

        autoEquipDirty.remove(d.id)
        autoLearnDirty.remove(d.id)
        return d
    }

    fun applyAccumulator(acc: PhaseTickAccumulator, maxEquipStack: Int, maxManualStack: Int) {
        if (acc.equipInstancesToAdd.isNotEmpty() || acc.equipInstanceIdsToRemove.isNotEmpty()) {
            currentEquipmentInstances = currentEquipmentInstances
                .filter { it.id !in acc.equipInstanceIdsToRemove } + acc.equipInstancesToAdd
        }
        if (acc.equipStackDeletions.isNotEmpty()) {
            currentEquipmentStacks = currentEquipmentStacks.filter { it.id !in acc.equipStackDeletions }
        }
        acc.equipStackQuantityDeltas.forEach { (stackId, newQty) ->
            currentEquipmentStacks = currentEquipmentStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxEquipStack)) else it
            }
        }
        acc.equipStackAdditions.forEach { stack ->
            val existing = currentEquipmentStacks.find { it.id == stack.id }
            currentEquipmentStacks = if (existing != null) {
                currentEquipmentStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxEquipStack)) else it
                }
            } else {
                currentEquipmentStacks + stack
            }
        }

        if (acc.manualInstancesToAdd.isNotEmpty() || acc.manualInstanceIdsToRemove.isNotEmpty()) {
            currentManualInstances = currentManualInstances
                .filter { it.id !in acc.manualInstanceIdsToRemove } + acc.manualInstancesToAdd
        }
        if (acc.profRemovals.isNotEmpty()) {
            val updatedProficiencies = currentGameData.manualProficiencies.toMutableMap()
            acc.profRemovals.forEach { (discipleId, manualIds) ->
                updatedProficiencies[discipleId]?.let { profList ->
                    val filtered = profList.filter { it.manualId !in manualIds }
                    if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                    else updatedProficiencies[discipleId] = filtered
                }
            }
            currentGameData = currentGameData.copy(manualProficiencies = updatedProficiencies)
        }
        if (acc.manualStackDeletions.isNotEmpty()) {
            currentManualStacks = currentManualStacks.filter { it.id !in acc.manualStackDeletions }
        }
        acc.manualStackQuantityDeltas.forEach { (stackId, newQty) ->
            currentManualStacks = currentManualStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxManualStack)) else it
            }
        }
        acc.manualStackAdditions.forEach { stack ->
            val existing = currentManualStacks.find { it.id == stack.id }
            currentManualStacks = if (existing != null) {
                currentManualStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxManualStack)) else it
                }
            } else {
                currentManualStacks + stack
            }
        }
    }

    fun updateMonthlyCultivation(state: MutableGameState, highFrequencyData: HighFrequencyData): HighFrequencyData {
        val data = state.gameData
        val livingDisciples = state.disciples.filter { it.isAlive }
        if (livingDisciples.isEmpty()) return highFrequencyData

        val monthSeconds = gameClock.msPerPhase * 3 / 1000.0
        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        val focusedGains = highFrequencyData.cultivationUpdates
        val focusedProfGains = highFrequencyData.proficiencyUpdates
        val focusedNurtureGains = highFrequencyData.nurtureUpdates
        val updatedDisciples = state.disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple

            val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
            val monthlyGain = cultivationPerSecond * monthSeconds
            val alreadyGained = focusedGains[disciple.id] ?: 0.0
            val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
            var d = disciple.copy(cultivation = (disciple.cultivation + netGain).coerceIn(0.0, disciple.maxCultivation))

            val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
            val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
            val proficiencyGainPerSecond = ManualProficiencySystem.calculateProficiencyGainPerSecond(disciple.comprehension, libraryBonus)
            val proficiencyGain = proficiencyGainPerSecond * monthSeconds
            val profAlreadyGained = focusedProfGains[d.id] ?: emptyMap()
            d.manualIds.forEach { manualId ->
                manualInstanceMap[manualId]?.let { manual ->
                    val profList = updatedManualProficiencies.getOrDefault(d.id, emptyList()).toMutableList()
                    val profIndex = profList.indexOfFirst { it.manualId == manualId }
                    val alreadyGainedProf = profAlreadyGained[manualId] ?: 0.0
                    val netProfGain = (proficiencyGain - alreadyGainedProf).coerceAtLeast(0.0)
                    val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
                    if (profIndex >= 0) {
                        val cp = profList[profIndex]
                        val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                        val newProf = (cp.proficiency + netProfGain).coerceAtMost(fixedMaxProf.toDouble())
                        profList[profIndex] = cp.copy(
                            proficiency = newProf,
                            maxProficiency = fixedMaxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                        )
                    } else {
                        val initProf = netProfGain.coerceAtMost(maxProf.toDouble())
                        profList.add(ManualProficiencyData(
                            manualId = manualId,
                            manualName = manual.name,
                            proficiency = initProf,
                            maxProficiency = maxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                        ))
                    }
                    updatedManualProficiencies[d.id] = profList
                }
            }

            val monthlyNurtureGain = 5.0 * monthSeconds
            val nurtureAlreadyGained = focusedNurtureGains[d.id] ?: emptyMap()

            fun processNurture(eqId: String?) {
                eqId?.let { id ->
                    equipmentInstanceMap[id]?.let { eq ->
                        val already = nurtureAlreadyGained[id] ?: 0.0
                        val netGain = (monthlyNurtureGain - already).coerceAtLeast(0.0)
                        equipmentInstanceUpdates[id] = EquipmentNurtureSystem.updateNurtureExp(eq, netGain).equipment
                    }
                }
            }
            processNurture(d.equipment.weaponId)
            processNurture(d.equipment.armorId)
            processNurture(d.equipment.bootsId)
            processNurture(d.equipment.accessoryId)
            d
        }

        state.disciples = updatedDisciples
        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        return highFrequencyData.copy(
            lastUpdateTime = System.currentTimeMillis(),
            totalDisciples = livingDisciples.size,
            cultivationUpdates = emptyMap(),
            proficiencyUpdates = emptyMap(),
            nurtureUpdates = emptyMap()
        )
    }

    fun updateFocusedDisciple(discipleId: String, state: MutableGameState, highFrequencyData: HighFrequencyData, breakthroughHandler: DiscipleBreakthroughHandler): HighFrequencyData {
        val data = state.gameData
        val allDisciples = state.disciples
        val disciple = allDisciples.find { it.id == discipleId && it.isAlive } ?: return highFrequencyData

        if (disciple.cultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)) {
            breakthroughHandler.processRealtimeBreakthroughs(listOf(disciple), data)
        }

        val postBreakthroughDisciples = state.disciples
        val currentDisciple = postBreakthroughDisciples.find { it.id == discipleId } ?: return highFrequencyData

        val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
        val perPhaseSeconds = gameClock.msPerPhase / 1000.0
        val gained = cultivationPerSecond * perPhaseSeconds

        state.disciples = postBreakthroughDisciples.map { d ->
            if (d.id == discipleId) d.copy(cultivation = (d.cultivation + gained).coerceIn(0.0, d.maxCultivation))
            else d
        }

        val accumGains = highFrequencyData.cultivationUpdates.toMutableMap()
        accumGains[discipleId] = (accumGains[discipleId] ?: 0.0) + gained

        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        val inLibrary = data.librarySlots.any { it.discipleId == discipleId }
        val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val proficiencyGainPerTick = ManualProficiencySystem.calculateProficiencyGainPerSecond(currentDisciple.comprehension, libraryBonus) * perPhaseSeconds

        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val profUpdatesMap = highFrequencyData.proficiencyUpdates.toMutableMap()
        val discipleProfUpdates = profUpdatesMap.getOrDefault(discipleId, emptyMap()).toMutableMap()
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()

        currentDisciple.manualIds.forEach { manualId ->
            manualInstanceMap[manualId]?.let { manual ->
                val profList = updatedManualProficiencies.getOrDefault(discipleId, emptyList()).toMutableList()
                val profIndex = profList.indexOfFirst { it.manualId == manualId }
                if (profIndex >= 0) {
                    val cp = profList[profIndex]
                    val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                    val newProf = (cp.proficiency + proficiencyGainPerTick).coerceAtMost(fixedMaxProf.toDouble())
                    profList[profIndex] = cp.copy(
                        proficiency = newProf,
                        maxProficiency = fixedMaxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                    )
                } else {
                    val initProf = proficiencyGainPerTick.coerceAtMost(maxProf.toDouble())
                    profList.add(ManualProficiencyData(
                        manualId = manualId,
                        manualName = manual.name,
                        proficiency = initProf,
                        maxProficiency = maxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                    ))
                }
                updatedManualProficiencies[discipleId] = profList
                discipleProfUpdates[manualId] = (discipleProfUpdates[manualId] ?: 0.0) + proficiencyGainPerTick
            }
        }
        profUpdatesMap[discipleId] = discipleProfUpdates
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        val nurtureGainPerTick = 5.0 * perPhaseSeconds
        val nurtureUpdatesMap = highFrequencyData.nurtureUpdates.toMutableMap()
        val discipleNurtureUpdates = nurtureUpdatesMap.getOrDefault(discipleId, emptyMap()).toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        listOfNotNull(
            currentDisciple.equipment.weaponId,
            currentDisciple.equipment.armorId,
            currentDisciple.equipment.bootsId,
            currentDisciple.equipment.accessoryId
        ).forEach { eqId ->
            equipmentInstanceMap[eqId]?.let { eq ->
                val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGainPerTick)
                equipmentInstanceUpdates[eqId] = result.equipment
                discipleNurtureUpdates[eqId] = (discipleNurtureUpdates[eqId] ?: 0.0) + nurtureGainPerTick
            }
        }
        nurtureUpdatesMap[discipleId] = discipleNurtureUpdates
        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }

        return highFrequencyData.copy(
            lastCultivationTime = System.currentTimeMillis(),
            cultivationPerSecond = cultivationPerSecond,
            totalDisciples = allDisciples.count { it.isAlive },
            cultivationUpdates = accumGains,
            proficiencyUpdates = profUpdatesMap,
            nurtureUpdates = nurtureUpdatesMap
        )
    }

    companion object {
        private const val TAG = "CultivationCore"
    }
}

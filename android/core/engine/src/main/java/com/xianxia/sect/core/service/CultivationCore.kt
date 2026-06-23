package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
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
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("CultivationCore")
class CultivationCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val thermalMonitor: ThermalMonitor,
    private val gameClock: GameTimeClock,
    private val scopeProvider: CoroutineScopeProvider,
    private val pillManager: DisciplePillManager,
    private val equipmentManager: DiscipleEquipmentManager,
    private val manualManager: DiscipleManualManager
) {

    val phaseMultiplier: Int get() = 10

    /**
     * 计算弟子每旬修炼速度（1x 速度基准）。
     * calculateCultivationSpeed 已直接返回每旬值，无需再换算。
     */
    fun calculateDiscipleCultivationPerPhase(disciple: Disciple, data: GameData, tables: DiscipleTables): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(disciple, data, tables, "outer")
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(disciple, data, tables, "inner")

        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
        }

        val manualInstanceMap = stateStore.manualInstances.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

        val parent1 = disciple.social.parentId1?.let { pid ->
            val pidInt = pid.toIntOrNull() ?: return@let null
            if (tables.names.contains(pidInt)) tables.assemble(pidInt) else null
        }
        val parent2 = disciple.social.parentId2?.let { pid ->
            val pidInt = pid.toIntOrNull() ?: return@let null
            if (tables.names.contains(pidInt)) tables.assemble(pidInt) else null
        }
        val parentCultivationBonus = DiscipleStatCalculator.calculateParentCultivationBonus(parent1, parent2)

        val griefPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_CULTIVATION_SPEED_PENALTY
        } else {
            0.0
        }

        val perSecond = DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty
        ).coerceAtLeast(1.0)
        // calculateCultivationSpeed 已直接返回每旬值，无需再换算
        return perSecond
    }

    private fun calculatePreachingBonuses(
        disciple: Disciple,
        data: GameData,
        tables: DiscipleTables,
        targetDiscipleType: String
    ): Pair<Double, Double> {
        val elderSlots = data.elderSlots
        val preachingElder = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingElder.let { id -> if (id.isNotEmpty()) id.toIntOrNull()?.let { tables.assemble(it) } else null }
            "inner" -> elderSlots.qingyunPreachingElder.let { id -> if (id.isNotEmpty()) id.toIntOrNull()?.let { tables.assemble(it) } else null }
            else -> null
        }
        val preachingMasters = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingMasters.mapNotNull { slot -> slot.discipleId?.toIntOrNull()?.let { tables.assemble(it) } }
            "inner" -> elderSlots.qingyunPreachingMasters.mapNotNull { slot -> slot.discipleId?.toIntOrNull()?.let { tables.assemble(it) } }
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

    fun isDiscipleFullHpMp(id: Int, tables: DiscipleTables): Boolean {
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        val hp = if (curHp < 0) tables.baseHps[id] else curHp
        val mp = if (curMp < 0) tables.baseMps[id] else curMp
        return hp >= tables.baseHps[id] && mp >= tables.baseMps[id]
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
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()

        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            val curHp = tables.currentHps[id]
            val curMp = tables.currentMps[id]
            if (curHp < 0 && curMp < 0) continue

            val disciple = tables.assemble(id)
            val proficiencyMap = allProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(disciple, equipmentMap, manualMap, proficiencyMap)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp

            val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
            val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

            if (newHp != curHp) tables.currentHps[id] = newHp
            if (newMp != curMp) tables.currentMps[id] = newMp
        }
    }

    /**
     * 月度 HP/MP 恢复（月结制专用）。
     * 每旬 multiplier=10，每月 3 旬 → 月度 multiplier=30。
     * @param focusedPhaseCount 本月焦点域已处理的旬数，用于扣除已应用的恢复
     */
    fun recoverMonthlyHpMp(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) {
        val curHp = tables.currentHps[id]
        val curMp = tables.currentMps[id]
        if (curHp < 0 && curMp < 0) return

        // 扣除已在焦点域旬结算中应用的部分，避免双计
        val monthlyMultiplier = (30.0 - focusedPhaseCount * 10).coerceAtLeast(0.0)
        if (monthlyMultiplier <= 0.0) return

        val maxHp = tables.baseHps[id]
        val maxMp = tables.baseMps[id]

        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * monthlyMultiplier)
            .toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * monthlyMultiplier)
            .toInt().coerceAtLeast(1)
        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp) tables.currentHps[id] = newHp
        if (newMp != curMp) tables.currentMps[id] = newMp
    }

    /**
     * 月度持续效果衰减（月结制专用）。
     * 修炼速度加成和丹药效果每旬衰减 10，每月衰减 30。
     * @param focusedPhaseCount 本月焦点域已处理的旬数，用于扣除已应用的衰减
     */
    fun applyMonthlyDurationDecay(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) {
        // 扣除已在焦点域旬结算中应用的部分，避免双计
        // 每月 3 旬，duration 以旬为单位
        val monthlyDecay = (3 - focusedPhaseCount).coerceAtLeast(0)
        if (monthlyDecay <= 0) return

        // 修炼速度加成衰减
        val speedDuration = tables.cultivationSpeedDurations[id]
        if (speedDuration > 0) {
            val newDuration = speedDuration - monthlyDecay
            if (newDuration <= 0) {
                tables.cultivationSpeedBonuses[id] = 0.0
                tables.cultivationSpeedDurations[id] = 0
            } else {
                tables.cultivationSpeedDurations[id] = newDuration
            }
        }

        // 丹药效果衰减
        val pillDuration = tables.pillEffectDurations[id]
        if (pillDuration > 0) {
            val newDuration = pillDuration - monthlyDecay
            if (newDuration <= 0) {
                tables.pillHpBonuses[id] = 0
                tables.pillMpBonuses[id] = 0
                tables.pillPhysicalAttackBonuses[id] = 0
                tables.pillMagicAttackBonuses[id] = 0
                tables.pillPhysicalDefenseBonuses[id] = 0
                tables.pillMagicDefenseBonuses[id] = 0
                tables.pillSpeedBonuses[id] = 0
                tables.pillCritRateBonuses[id] = 0.0
                tables.pillCritEffectBonuses[id] = 0.0
                tables.pillCultivationSpeedBonuses[id] = 0.0
                tables.pillSkillExpSpeedBonuses[id] = 0.0
                tables.pillNurtureSpeedBonuses[id] = 0.0
                tables.activePillCategories[id] = ""
                tables.activePillTypes[id] = emptySet()
                tables.pillEffectDurations[id] = 0
            } else {
                tables.pillEffectDurations[id] = newDuration
            }
        }
    }

    fun recoverHpMpForBattleParticipants(state: MutableGameState, discipleIds: List<String>) {
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()
        val idSet = discipleIds.toSet()

        for (id in tables.ids) {
            val strId = id.toString()
            if (strId !in idSet || tables.isAlive[id] != 1) continue

            val disciple = tables.assemble(id)
            var curHp = tables.currentHps[id]
            var curMp = tables.currentMps[id]
            if (curHp >= disciple.maxHp && curMp >= disciple.maxMp) continue

            val discipleProficiencies = allProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(disciple, equipmentMap, manualMap, discipleProficiencies)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp
            curHp = if (curHp < 0) curHp else (curHp + (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxHp)
            curMp = if (curMp < 0) curMp else (curMp + (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxMp)
            if (curHp != tables.currentHps[id]) tables.currentHps[id] = curHp
            if (curMp != tables.currentMps[id]) tables.currentMps[id] = curMp
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
                d = d.copy(pillEffects = d.pillEffects.copy(
                    pillEffectDuration = newDuration
                ))
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
            d = d.copy(combat = d.combat.copy(currentHp = newHp, currentMp = newMp))
        }

        val pillResult = pillManager.processAutoUsePills(
            disciple = d, gameYear = year, gameMonth = month, gamePhase = phase
        )
        if (pillResult.disciple != d) {
            d = pillResult.disciple
        }

        val hasEquipmentInBag = d.equipment.storageBagItems.any { it.itemType == "equipment" }
        val needEquipCheck = hasEquipmentInBag || d.id in autoEquipDirty
        val equipResult = if (needEquipCheck) {
            equipmentManager.processAutoEquip(
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
            manualManager.processAutoLearn(
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

    fun applyAccumulator(acc: PhaseTickAccumulator, state: MutableGameState, maxEquipStack: Int, maxManualStack: Int) {
        if (acc.equipInstancesToAdd.isNotEmpty() || acc.equipInstanceIdsToRemove.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances
                .filter { it.id !in acc.equipInstanceIdsToRemove } + acc.equipInstancesToAdd
        }
        if (acc.equipStackDeletions.isNotEmpty()) {
            state.equipmentStacks = state.equipmentStacks.filter { it.id !in acc.equipStackDeletions }
        }
        acc.equipStackQuantityDeltas.forEach { (stackId, newQty) ->
            state.equipmentStacks = state.equipmentStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxEquipStack)) else it
            }
        }
        acc.equipStackAdditions.forEach { stack ->
            val existing = state.equipmentStacks.find { it.id == stack.id }
            state.equipmentStacks = if (existing != null) {
                state.equipmentStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxEquipStack)) else it
                }
            } else {
                state.equipmentStacks + stack
            }
        }

        if (acc.manualInstancesToAdd.isNotEmpty() || acc.manualInstanceIdsToRemove.isNotEmpty()) {
            state.manualInstances = state.manualInstances
                .filter { it.id !in acc.manualInstanceIdsToRemove } + acc.manualInstancesToAdd
        }
        if (acc.profRemovals.isNotEmpty()) {
            val updatedProficiencies = state.gameData.manualProficiencies.toMutableMap()
            acc.profRemovals.forEach { (discipleId, manualIds) ->
                updatedProficiencies[discipleId]?.let { profList ->
                    val filtered = profList.filter { it.manualId !in manualIds }
                    if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                    else updatedProficiencies[discipleId] = filtered
                }
            }
            state.gameData = state.gameData.copy(manualProficiencies = updatedProficiencies)
        }
        if (acc.manualStackDeletions.isNotEmpty()) {
            state.manualStacks = state.manualStacks.filter { it.id !in acc.manualStackDeletions }
        }
        acc.manualStackQuantityDeltas.forEach { (stackId, newQty) ->
            state.manualStacks = state.manualStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxManualStack)) else it
            }
        }
        acc.manualStackAdditions.forEach { stack ->
            val existing = state.manualStacks.find { it.id == stack.id }
            state.manualStacks = if (existing != null) {
                state.manualStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxManualStack)) else it
                }
            } else {
                state.manualStacks + stack
            }
        }
    }

    fun updateMonthlyCultivation(state: MutableGameState, highFrequencyData: HighFrequencyData): HighFrequencyData {
        val data = state.gameData
        val tables = state.discipleTables
        val livingIds = tables.ids.filter { tables.isAlive[it] == 1 }
        if (livingIds.isEmpty()) return highFrequencyData

        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        val focusedGains = highFrequencyData.cultivationUpdates
        val focusedProfGains = highFrequencyData.proficiencyUpdates
        val focusedNurtureGains = highFrequencyData.nurtureUpdates

        for (id in livingIds) {
            val disciple = tables.assemble(id)
            val cultivationPerPhase = calculateDiscipleCultivationPerPhase(disciple, data, tables)
            val monthlyGain = cultivationPerPhase * 3  // 3旬/月
            val alreadyGained = focusedGains[disciple.id] ?: 0.0
            val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
            tables.cultivations[id] = (disciple.cultivation + netGain).coerceIn(0.0, disciple.maxCultivation)

            val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
            val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
            val proficiencyGainPerPhase = ManualProficiencySystem.calculateProficiencyGainPerPhase(disciple.comprehension, libraryBonus)
            val proficiencyGain = proficiencyGainPerPhase * 3  // 3旬/月
            val profAlreadyGained = focusedProfGains[disciple.id] ?: emptyMap()
            disciple.manualIds.forEach { manualId ->
                manualInstanceMap[manualId]?.let { manual ->
                    val profList = updatedManualProficiencies.getOrDefault(disciple.id, emptyList()).toMutableList()
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
                    updatedManualProficiencies[disciple.id] = profList
                }
            }

            // 温养每旬基础值：5.0/s × MS_PER_PHASE_1X/1000 → × 3旬/月
            val monthlyNurtureGain = 5.0 * com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X / 1000.0 * 3
            val nurtureAlreadyGained = focusedNurtureGains[disciple.id] ?: emptyMap()

            fun processNurture(eqId: String?) {
                eqId?.let { eid ->
                    equipmentInstanceMap[eid]?.let { eq ->
                        val already = nurtureAlreadyGained[eid] ?: 0.0
                        val netGain = (monthlyNurtureGain - already).coerceAtLeast(0.0)
                        equipmentInstanceUpdates[eid] = EquipmentNurtureSystem.updateNurtureExp(eq, netGain).equipment
                    }
                }
            }
            processNurture(disciple.equipment.weaponId)
            processNurture(disciple.equipment.armorId)
            processNurture(disciple.equipment.bootsId)
            processNurture(disciple.equipment.accessoryId)
        }

        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        return highFrequencyData.copy(
            lastUpdateTime = System.currentTimeMillis(),
            totalDisciples = livingIds.size,
            cultivationUpdates = emptyMap(),
            proficiencyUpdates = emptyMap(),
            nurtureUpdates = emptyMap()
        )
    }

    fun updateFocusedDisciple(discipleId: String, state: MutableGameState, highFrequencyData: HighFrequencyData, breakthroughHandler: DiscipleBreakthroughHandler): HighFrequencyData {
        val data = state.gameData
        val tables = state.discipleTables
        val idInt = discipleId.toIntOrNull() ?: return highFrequencyData
        if (!tables.names.contains(idInt) || tables.isAlive[idInt] != 1) return highFrequencyData

        val disciple = tables.assemble(idInt)

        if (disciple.cultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)) {
            breakthroughHandler.processRealtimeBreakthroughs(listOf(disciple), data, state)
        }

        val currentDisciple = tables.assemble(idInt)

        val cultivationPerPhase = calculateDiscipleCultivationPerPhase(disciple, data, tables)

        tables.cultivations.update(idInt) { (it + cultivationPerPhase).coerceIn(0.0, currentDisciple.maxCultivation) }

        val accumGains = highFrequencyData.cultivationUpdates.toMutableMap()
        accumGains[discipleId] = (accumGains[discipleId] ?: 0.0) + cultivationPerPhase

        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        val inLibrary = data.librarySlots.any { it.discipleId == discipleId }
        val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val proficiencyGainPerTick = ManualProficiencySystem.calculateProficiencyGainPerPhase(currentDisciple.comprehension, libraryBonus)

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
        // 温养每旬基础值 = 5.0/s × 每旬秒数
        val nurtureGainPerTick = 5.0 * com.xianxia.sect.core.engine.system.GameTimeClock.MS_PER_PHASE_1X / 1000.0
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
            cultivationPerPhase = cultivationPerPhase,
            totalDisciples = tables.ids.count { tables.isAlive[it] == 1 },
            cultivationUpdates = accumGains,
            proficiencyUpdates = profUpdatesMap,
            nurtureUpdates = nurtureUpdatesMap
        )
    }

    companion object {
        private const val TAG = "CultivationCore"
    }
}

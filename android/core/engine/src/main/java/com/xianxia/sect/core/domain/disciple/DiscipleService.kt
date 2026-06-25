package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.mergeStackable
import com.xianxia.sect.core.util.addEquipmentInstanceToDiscipleBag
import com.xianxia.sect.core.util.equipmentBagStackIds
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.DomainLog
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@GameService("DiscipleService")
@Singleton
class DiscipleService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
private val scopeProvider: CoroutineScopeProvider,
    private val inventoryConfig: InventoryConfig,
    private val discipleFactory: DiscipleFactory
) {
    private val scope get() = scopeProvider.scope

    private val currentDiscipleTables: DiscipleTables
        get() = stateStore.discipleTables

    companion object {
        private const val TAG = "DiscipleService"
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get disciples StateFlow
     */
    fun getDisciples(): StateFlow<List<Disciple>> = stateStore.disciples

    // ==================== 弟子 CRUD ====================

    /**
     * Add new disciple
     */
    fun addDisciple(disciple: Disciple) {
        stateStore.discipleTables.insert(disciple)
    }

    /**
     * Remove disciple by ID
     */
    fun removeDisciple(discipleId: String): DomainResult<Unit> {
        val id = discipleId.toIntOrNull()
            ?: return DomainResult.Failure(AppError.Domain.Disciple.NotFound(discipleId))
        val tables = stateStore.discipleTables
        return if (tables.ids.contains(id)) {
            tables.remove(id)
            DomainResult.Success(Unit)
        } else {
            DomainResult.Failure(AppError.Domain.Disciple.NotFound(discipleId))
        }
    }

    /**
     * Get disciple by ID
     */
    fun getDiscipleById(discipleId: String): Disciple? {
        val id = discipleId.toIntOrNull() ?: return null
        val tables = stateStore.discipleTables
        return if (tables.ids.contains(id)) tables.assemble(id) else null
    }

    /**
     * Update disciple
     */
    fun updateDisciple(disciple: Disciple) {
        val id = disciple.id.toIntOrNull() ?: return
        val tables = stateStore.discipleTables
        if (tables.ids.contains(id)) {
            tables.remove(id)
            tables.insert(disciple)
        }
    }

    // ==================== 弟子状态管理 ====================

    /**
     * Get disciple status based on current assignments
     */
    fun getDiscipleStatus(discipleId: String): DiscipleStatus {
        val data = stateStore.gameData.value
        val id = discipleId.toIntOrNull() ?: return DiscipleStatus.IDLE
        val tables = stateStore.discipleTables
        if (!tables.ids.contains(id)) return DiscipleStatus.IDLE

        val isAlive = tables.isAlive[id] == 1
        if (!isAlive) return DiscipleStatus.DEAD
        val status = tables.statuses[id]
        if (status == DiscipleStatus.REFLECTING) return DiscipleStatus.REFLECTING
        if (status == DiscipleStatus.ON_MISSION) return DiscipleStatus.ON_MISSION

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val inGarrison = playerSect?.garrisonSlots?.any { it.discipleId == discipleId } == true
        if (inGarrison) return DiscipleStatus.GARRISONING

        val inBattleTeam = data.battleTeams.any { team ->
            team.slots.any { it.discipleId == discipleId }
        }
        if (inBattleTeam) return DiscipleStatus.IN_TEAM

        if (_isInExploration(discipleId)) return DiscipleStatus.IN_TEAM

        if (_isInCaveExploration(discipleId)) return DiscipleStatus.IN_TEAM

        val elderSlots = data.elderSlots
        if (elderSlots.lawEnforcementElder == discipleId ||
            elderSlots.lawEnforcementDisciples.any { it.discipleId == discipleId } ||
            elderSlots.lawEnforcementReserveDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.LAW_ENFORCING
        }
        if (elderSlots.preachingElder == discipleId ||
            elderSlots.preachingMasters.any { it.discipleId == discipleId }) {
            return DiscipleStatus.PREACHING
        }
        if (elderSlots.qingyunPreachingElder == discipleId ||
            elderSlots.qingyunPreachingMasters.any { it.discipleId == discipleId }) {
            return DiscipleStatus.PREACHING
        }

        if (elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.DEACONING
        }

        if (elderSlots.viceSectMaster == discipleId ||
            elderSlots.outerElder == discipleId ||
            elderSlots.innerElder == discipleId ||
            elderSlots.forgeElder == discipleId ||
            elderSlots.alchemyElder == discipleId ||
            elderSlots.herbGardenElder == discipleId ||
            elderSlots.herbGardenDisciples.any { it.discipleId == discipleId } ||
            elderSlots.alchemyDisciples.any { it.discipleId == discipleId } ||
            elderSlots.forgeDisciples.any { it.discipleId == discipleId } ||
            elderSlots.herbGardenReserveDisciples.any { it.discipleId == discipleId } ||
            elderSlots.alchemyReserveDisciples.any { it.discipleId == discipleId } ||
            elderSlots.forgeReserveDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MANAGING
        }

        if (data.librarySlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.STUDYING
        }

        val discipleType = tables.discipleTypes[id]
        if (data.spiritMineSlots.any { it.discipleId == discipleId } && discipleType == "outer") {
            return DiscipleStatus.MINING
        }

        return DiscipleStatus.IDLE
    }

    /**
     * Sync all disciples' status based on their assignments
     */
    fun syncAllDiscipleStatuses() {
        var data = stateStore.gameData.value
        val tables = stateStore.discipleTables
        val elderSlots = data.elderSlots

        val lawEnforcerIds = mutableSetOf<String>()
        elderSlots.lawEnforcementElder?.let { lawEnforcerIds.add(it) }
        elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }.forEach { lawEnforcerIds.add(it) }
        elderSlots.lawEnforcementReserveDisciples.mapNotNull { it.discipleId }.forEach { lawEnforcerIds.add(it) }

        val preachingIds = mutableSetOf<String>()
        elderSlots.preachingElder?.let { preachingIds.add(it) }
        elderSlots.qingyunPreachingElder?.let { preachingIds.add(it) }
        elderSlots.preachingMasters.mapNotNull { it.discipleId }.forEach { preachingIds.add(it) }
        elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }.forEach { preachingIds.add(it) }

        val deaconingIds = mutableSetOf<String>()
        elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }.forEach { deaconingIds.add(it) }

        val managingIds = mutableSetOf<String>()
        elderSlots.viceSectMaster?.let { managingIds.add(it) }
        elderSlots.outerElder?.let { managingIds.add(it) }
        elderSlots.innerElder?.let { managingIds.add(it) }
        elderSlots.forgeElder?.let { managingIds.add(it) }
        elderSlots.alchemyElder?.let { managingIds.add(it) }
        elderSlots.herbGardenElder?.let { managingIds.add(it) }
        elderSlots.herbGardenDisciples.forEach { if (it.discipleId.isNotEmpty()) managingIds.add(it.discipleId) }
        elderSlots.alchemyDisciples.forEach { if (it.discipleId.isNotEmpty()) managingIds.add(it.discipleId) }
        elderSlots.forgeDisciples.forEach { if (it.discipleId.isNotEmpty()) managingIds.add(it.discipleId) }
        elderSlots.herbGardenReserveDisciples.forEach { if (it.discipleId.isNotEmpty()) managingIds.add(it.discipleId) }
        elderSlots.alchemyReserveDisciples.forEach { if (it.discipleId.isNotEmpty()) managingIds.add(it.discipleId) }
        elderSlots.forgeReserveDisciples.forEach { if (it.discipleId.isNotEmpty()) managingIds.add(it.discipleId) }

        val studyingIds = data.librarySlots.mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }.toMutableSet()

        val miningIds = data.spiritMineSlots
            .mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }
            .filter { id -> tables.ids.contains(id.toInt()) && tables.discipleTypes[id.toInt()] == "outer" }
            .toMutableSet()

        val hasInvalidMiningSlots = data.spiritMineSlots.any { slot ->
            slot.discipleId.isNotEmpty() && (!tables.ids.contains(slot.discipleId.toInt()) || tables.discipleTypes[slot.discipleId.toInt()] != "outer")
        }
        if (hasInvalidMiningSlots) {
            val fixedSlots = data.spiritMineSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() && (!tables.ids.contains(slot.discipleId.toInt()) || tables.discipleTypes[slot.discipleId.toInt()] != "outer")) {
                    slot.copy(discipleId = "", discipleName = "")
                } else slot
            }
            data = data.copy(spiritMineSlots = fixedSlots)
            scope.launch { stateStore.update { gameData = data } }
        }

        val garrisonIds = mutableSetOf<String>()
        data.worldMapSects.find { it.isPlayerSect }?.garrisonSlots
            ?.filter { it.discipleId.isNotEmpty() }
            ?.forEach { garrisonIds.add(it.discipleId) }

        val inTeamIds = mutableSetOf<String>()
        data.battleTeams.flatMap { it.slots }
            .filter { it.discipleId.isNotEmpty() }
            .forEach { inTeamIds.add(it.discipleId) }

        stateStore.teams.value.filter { it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING || it.status == ExplorationStatus.SCOUTING || it.status == ExplorationStatus.DANGER }
            .forEach { team -> inTeamIds.addAll(team.memberIds) }
        data.caveExplorationTeams.filter { it.status == CaveExplorationStatus.TRAVELING || it.status == CaveExplorationStatus.EXPLORING }
            .forEach { team -> inTeamIds.addAll(team.memberIds) }

        val patrollingIds = data.patrolSlots
            .filter { it.discipleId.isNotEmpty() }
            .map { it.discipleId }
            .toSet()

        for (id in tables.ids) {
            val isAlive = tables.isAlive[id] == 1
            val status = tables.statuses[id]
            if (!isAlive) continue
            if (status == DiscipleStatus.REFLECTING) continue
            if (status == DiscipleStatus.ON_MISSION) continue

            val discipleId = id.toString()
            val newStatus = when {
                garrisonIds.contains(discipleId) -> DiscipleStatus.GARRISONING
                inTeamIds.contains(discipleId) -> DiscipleStatus.IN_TEAM
                lawEnforcerIds.contains(discipleId) -> DiscipleStatus.LAW_ENFORCING
                preachingIds.contains(discipleId) -> DiscipleStatus.PREACHING
                deaconingIds.contains(discipleId) -> DiscipleStatus.DEACONING
                managingIds.contains(discipleId) -> DiscipleStatus.MANAGING
                studyingIds.contains(discipleId) -> DiscipleStatus.STUDYING
                miningIds.contains(discipleId) -> DiscipleStatus.MINING
                patrollingIds.contains(discipleId) -> DiscipleStatus.PATROLLING
                else -> DiscipleStatus.IDLE
            }

            if (status != newStatus) {
                tables.statuses[id] = newStatus
            }
        }
    }

    /**
     * Reset all disciples to IDLE status
     * Used when resetting game state or disbanding all teams
     */
    suspend fun resetAllDisciplesStatus() {
        val data = stateStore.gameData.value
        val tables = stateStore.discipleTables

        val reflectingIds = mutableSetOf<String>()
        for (id in tables.ids) {
            if (tables.statuses[id] == DiscipleStatus.REFLECTING) {
                reflectingIds.add(id.toString())
            }
        }

        val clearedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in reflectingIds)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedLibrarySlots = data.librarySlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in reflectingIds)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedElderSlots = clearAllDisciplesFromElderSlots(data.elderSlots, reflectingIds)

        val clearedGarrisonSects = data.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect.copy(
                    garrisonSlots = sect.garrisonSlots.map { slot ->
                        if (slot.discipleId.isNotEmpty() && slot.discipleId !in reflectingIds)
                            GarrisonSlot(index = slot.index)
                        else slot
                    }
                )
            } else sect
        }

        val clearedCaveTeams = data.caveExplorationTeams.map { team ->
            if (team.memberIds.any { it !in reflectingIds }) {
                team.copy(
                    memberIds = emptyList(),
                    memberNames = emptyList(),
                    status = CaveExplorationStatus.COMPLETED
                )
            } else team
        }

        val clearedActiveMissions = data.activeMissions.filter { mission ->
            mission.discipleIds.all { it in reflectingIds }
        }

        val teamsSnapshot = stateStore.teams.value
        val updatedTeams = teamsSnapshot.map { team ->
            if (team.memberIds.any { it !in reflectingIds }) {
                team.copy(
                    memberIds = emptyList(),
                    memberNames = emptyList(),
                    status = ExplorationStatus.COMPLETED
                )
            } else team
        }

        stateStore.update {
            gameData = data.copy(
                spiritMineSlots = clearedSpiritMineSlots,
                librarySlots = clearedLibrarySlots,
                elderSlots = clearedElderSlots,
                worldMapSects = clearedGarrisonSects,
                caveExplorationTeams = clearedCaveTeams,
                activeMissions = clearedActiveMissions
            )
            teams = updatedTeams
        }

        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId != null && slot.assignedDiscipleId !in reflectingIds && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }

        for (id in tables.ids) {
            val isAlive = tables.isAlive[id] == 1
            val status = tables.statuses[id]
            if (!isAlive) continue
            if (status == DiscipleStatus.REFLECTING) continue
            if (status == DiscipleStatus.IDLE) continue
            tables.statuses[id] = DiscipleStatus.IDLE
            tables.statusData[id] = emptyMap()
        }

        autoFillLawEnforcementSlots()
    }

    private fun clearAllDisciplesFromElderSlots(slots: ElderSlots, reflectingIds: Set<String>): ElderSlots {
        var updated = slots

        if (updated.viceSectMaster.isNotEmpty() && updated.viceSectMaster !in reflectingIds)
            updated = updated.copy(viceSectMaster = "")
        if (updated.herbGardenElder.isNotEmpty() && updated.herbGardenElder !in reflectingIds)
            updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder.isNotEmpty() && updated.alchemyElder !in reflectingIds)
            updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder.isNotEmpty() && updated.forgeElder !in reflectingIds)
            updated = updated.copy(forgeElder = "")
        if (updated.outerElder.isNotEmpty() && updated.outerElder !in reflectingIds)
            updated = updated.copy(outerElder = "")
        if (updated.preachingElder.isNotEmpty() && updated.preachingElder !in reflectingIds)
            updated = updated.copy(preachingElder = "")
        if (updated.lawEnforcementElder.isNotEmpty() && updated.lawEnforcementElder !in reflectingIds)
            updated = updated.copy(lawEnforcementElder = "")
        if (updated.innerElder.isNotEmpty() && updated.innerElder !in reflectingIds)
            updated = updated.copy(innerElder = "")
        if (updated.qingyunPreachingElder.isNotEmpty() && updated.qingyunPreachingElder !in reflectingIds)
            updated = updated.copy(qingyunPreachingElder = "")

        updated = updated.copy(
            preachingMasters = updated.preachingMasters.filter { it.discipleId in reflectingIds },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.filter { it.discipleId in reflectingIds },
            lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.filter { it.discipleId in reflectingIds },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.filter { it.discipleId in reflectingIds },
            herbGardenDisciples = updated.herbGardenDisciples.filter { it.discipleId in reflectingIds },
            alchemyDisciples = updated.alchemyDisciples.filter { it.discipleId in reflectingIds },
            forgeDisciples = updated.forgeDisciples.filter { it.discipleId in reflectingIds },
            herbGardenReserveDisciples = updated.herbGardenReserveDisciples.filter { it.discipleId in reflectingIds },
            alchemyReserveDisciples = updated.alchemyReserveDisciples.filter { it.discipleId in reflectingIds },
            forgeReserveDisciples = updated.forgeReserveDisciples.filter { it.discipleId in reflectingIds },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.filter { it.discipleId in reflectingIds }
        )

        return updated
    }

    // ==================== 弟子培养 ====================

    /**
     * Recruit new disciple
     */
    fun recruitDisciple(): Disciple {
        val id =
            ((stateStore.discipleTables.ids.maxOrNull() ?: 0) + 1).toString()
        val gender = if (Random.nextBoolean()) "male" else "female"

        val existingNames = (stateStore.discipleTables.assembleAll()
            + stateStore.gameData.value.recruitList)
            .map { it.name }.toSet()
        val nameResult = NameService.generateName(
            gender, NameService.NameStyle.FULL, existingNames
        )

        val disciple = discipleFactory.create(
            DiscipleFactory.DiscipleSeed(
                id = id,
                gender = gender,
                nameResult = nameResult,
                spiritRootType = SpiritRootGenerator.generate(),
                age = Random.nextInt(16, 30),
                realmLayer = 1,
                social = com.xianxia.sect.core.model.SocialData(),
                nextInt = { from, until -> Random.nextInt(from, until) }
            )
        )

        // Set recruitment time
        val data = stateStore.gameData.value
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        disciple.usage.recruitedMonth = currentMonthValue

        addDisciple(disciple)

        return disciple
    }

    /**
     * Expel disciple from sect
     */
    suspend fun expelDisciple(discipleId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = AppError.Domain.Disciple.NotFound(discipleId)
        stateStore.update {
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) {
                error = AppError.Domain.Disciple.NotFound(discipleId)
                return@update
            }

            val isAlive = discipleTables.isAlive[id] == 1
            if (!isAlive) {
                error = AppError.Domain.Disciple.NotAlive(discipleId)
                return@update
            }

            clearDiscipleFromAllSlots(discipleId)

            val returnEquipIds = mutableListOf<String>()
            discipleTables.weaponIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.armorIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.bootsIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.accessoryIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.storageBagItems[id].filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { returnEquipIds.add(it.itemId) }

            val bagStackIds = discipleTables.ids
                .filter { it != id }
                .flatMap { discipleTables.storageBagItems[it] }
                .filter { it.itemType == "equipment_stack" }
                .map { it.itemId }
                .toSet()

            returnEquipIds.forEach { eid ->
                val eq = equipmentInstances.get(eid) ?: return@forEach
                val stack = eq.toStack()
                val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                equipmentStacks = equipmentStacks.mergeStackable(
                    item = stack,
                    matchPredicate = { it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot && it.id !in bagStackIds },
                    maxStack = maxStack
                )
                equipmentInstances.remove(eid)
            }

            discipleTables.storageBagItems[id].filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }.forEach { bagItem ->
                val m = manualInstances.get(bagItem.itemId)
                if (m != null) {
                    val stack = m.toStack()
                    val maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                    manualStacks = manualStacks.mergeStackable(
                        item = stack,
                        matchPredicate = { it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type },
                        maxStack = maxStack
                    )
                    manualInstances.remove(bagItem.itemId)
                }
            }

            discipleTables.manualIds[id].forEach { manualId ->
                val m = manualInstances.get(manualId)
                if (m != null) {
                    val stack = m.toStack()
                    val maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                    manualStacks = manualStacks.mergeStackable(
                        item = stack,
                        matchPredicate = { it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type },
                        maxStack = maxStack
                    )
                    manualInstances.remove(manualId)
                }
            }

            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(discipleId)
            if (updatedProficiencies != gameData.manualProficiencies) {
                gameData = gameData.copy(manualProficiencies = updatedProficiencies)
            }

            discipleTables.remove(id)

            error = null
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    /**
     * 拜师：徒弟 [discipleId] 向师父 [masterId] 拜师，建立永久师徒关系。
     * 仅一方死亡方可解绑（见 DiscipleLifecycleProcessor.handleDiscipleDeath）。
     * - 师父最多 5 名徒弟
     * - 弟子最多 1 名师父
     */
    suspend fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = null
        stateStore.update {
            val did = discipleId.toIntOrNull()
            val mid = masterId.toIntOrNull()
            if (did == null || !discipleTables.ids.contains(did)) {
                error = AppError.Domain.Disciple.NotFound(discipleId)
                return@update
            }
            if (mid == null || !discipleTables.ids.contains(mid)) {
                error = AppError.Domain.Disciple.NotFound(masterId)
                return@update
            }
            if (did == mid) {
                error = AppError.Domain.Disciple.SlotInvalid("不能拜自己为师")
                return@update
            }
            if (discipleTables.isAlive[did] != 1) {
                error = AppError.Domain.Disciple.NotAlive(discipleId)
                return@update
            }
            if (discipleTables.isAlive[mid] != 1) {
                error = AppError.Domain.Disciple.NotAlive(masterId)
                return@update
            }
            // 该弟子已有师父
            if (discipleTables.masterIds.getOrNull(did) != null) {
                error = AppError.Domain.Disciple.SlotInvalid("弟子已有师父，师徒关系不可更改")
                return@update
            }
            // 师父徒弟数 < 5（仅统计存活徒弟）
            val apprenticeCount = discipleTables.ids.count { otherId ->
                otherId != did &&
                discipleTables.isAlive[otherId] == 1 &&
                discipleTables.masterIds.getOrNull(otherId) == masterId
            }
            if (apprenticeCount >= DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER) {
                error = AppError.Domain.Disciple.SlotInvalid(
                    "师父徒弟已满（最多${DiscipleStatCalculator.MAX_APPRENTICES_PER_MASTER}名）")
                return@update
            }
            // 通过校验，建立师徒关系
            discipleTables.masterIds[did] = masterId
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    // ==================== 装备管理 ====================

    /**
     * Equip equipment to disciple
     * 设计意图：装备是独占物品，不可共用。一件装备只能给一名弟子穿戴。
     * 装备新装备时，旧装备自动卸下并放入弟子储物袋。
     */
    suspend fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = AppError.Domain.Disciple.NotFound(discipleId)
        stateStore.update {
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) {
                error = AppError.Domain.Disciple.NotFound(discipleId); return@update
            }

            val equipmentStack = equipmentStacks.get(equipmentId)
            val equipmentInstance = equipmentInstances.get(equipmentId)

            if (equipmentStack == null && equipmentInstance == null) {
                error = AppError.Domain.Disciple.NotFound(discipleId); return@update
            }

            val discipleRealm = discipleTables.realms[id]

            if (equipmentInstance != null) {
                if (equipmentInstance.isEquipped) {
                    if (equipmentInstance.ownerId == discipleId) {
                        error = AppError.Domain.Disciple.AlreadyEquipped(
                            slot = equipmentInstance.slot.name
                        ); return@update
                    }
                    error = AppError.Domain.Disciple.AlreadyEquipped(
                        slot = equipmentInstance.slot.name
                    ); return@update
                }
                if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentInstance.minRealm)) {
                    error = AppError.Domain.Disciple.RealmTooLow(
                        discipleId = discipleId,
                        need = "境界${equipmentInstance.minRealm}"
                    ); return@update
                }
            } else if (equipmentStack != null) {
                if (!GameConfig.Realm.meetsRealmRequirement(discipleRealm, equipmentStack.minRealm)) {
                    error = AppError.Domain.Disciple.RealmTooLow(
                        discipleId = discipleId,
                        need = "境界${equipmentStack.minRealm}"
                    ); return@update
                }
            }

            val slot = equipmentInstance?.slot ?: equipmentStack?.slot ?: run {
                error = AppError.Domain.Disciple.SlotInvalid("无法确定装备槽位"); return@update
            }
            val equipName = equipmentStack?.name ?: equipmentInstance?.name ?: ""

            val oldEquipId = when (slot) {
                EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
                EquipmentSlot.ARMOR -> discipleTables.armorIds[id]
                EquipmentSlot.BOOTS -> discipleTables.bootsIds[id]
                EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id]
                else -> ""
            }
            if (oldEquipId.isNotEmpty()) {
                val unequipped = unequipEquipmentLogic(discipleId, oldEquipId)
                if (!unequipped) {
                    DomainLog.w(TAG, "equipEquipment: failed to unequip $oldEquipId, aborting equip")
                    error = AppError.Domain.Disciple.SlotInvalid("卸下旧装备失败 $oldEquipId")
                    return@update
                }
            }

            val stack = equipmentStacks.get(equipmentId)
            val instance = equipmentInstances.get(equipmentId)

            if (stack != null) {
                val equippedId = UUID.randomUUID().toString()
                val equippedItem = stack.toInstance(id = equippedId, ownerId = discipleId, isEquipped = true)
                if (stack.quantity > 1) {
                    equipmentStacks.update(equipmentId) { it.copy(quantity = it.quantity - 1) }
                } else {
                    equipmentStacks.remove(equipmentId)
                }
                equipmentInstances = equipmentInstances + equippedItem
                when (slot) {
                    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = equippedId
                    EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = equippedId
                    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = equippedId
                    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = equippedId
                    else -> {}
                }
            } else if (instance != null) {
                when (slot) {
                    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = equipmentId
                    EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = equipmentId
                    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = equipmentId
                    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = equipmentId
                    else -> {}
                }
                equipmentInstances.update(equipmentId) { it.copy(isEquipped = true, ownerId = discipleId) }
            }

            error = null
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    /**
     * Unequip equipment from disciple
     * 设计意图：装备是独占物品，卸下后放入弟子储物袋，而非归还宗门仓库。
     *
     * 验证和卸下操作全部在 stateStore.update 事务内原子执行，返回实际操作结果。
     */
    suspend fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> {
        var error: AppError.Domain.Disciple? = AppError.Domain.Disciple.NotFound(discipleId)
        stateStore.update {
            val id = discipleId.toIntOrNull()
            if (id == null || !discipleTables.ids.contains(id)) {
                error = AppError.Domain.Disciple.NotFound(discipleId); return@update
            }
            val isEquipped = discipleTables.weaponIds[id] == equipmentId ||
                discipleTables.armorIds[id] == equipmentId ||
                discipleTables.bootsIds[id] == equipmentId ||
                discipleTables.accessoryIds[id] == equipmentId
            if (!isEquipped) {
                error = AppError.Domain.Disciple.SlotInvalid("装备未穿戴在弟子身上")
                return@update
            }

            val unequipped = unequipEquipmentLogic(discipleId, equipmentId)
            if (unequipped) error = null
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    private fun MutableGameState.unequipEquipmentLogic(discipleId: String, equipmentId: String): Boolean {
        val id = discipleId.toIntOrNull() ?: return false
        if (!discipleTables.ids.contains(id)) return false

        val weaponId = discipleTables.weaponIds[id]
        val armorId = discipleTables.armorIds[id]
        val bootsId = discipleTables.bootsIds[id]
        val accessoryId = discipleTables.accessoryIds[id]

        val changed = when {
            weaponId == equipmentId -> { discipleTables.weaponIds[id] = ""; true }
            armorId == equipmentId -> { discipleTables.armorIds[id] = ""; true }
            bootsId == equipmentId -> { discipleTables.bootsIds[id] = ""; true }
            accessoryId == equipmentId -> { discipleTables.accessoryIds[id] = ""; true }
            else -> false
        }

        if (changed) {
            val eq = equipmentInstances.get(equipmentId)

            if (eq != null) {
                val updatedDisciple = discipleTables.assemble(id)
                val bagStackIds = updatedDisciple.equipmentBagStackIds()
                val result = addEquipmentInstanceToDiscipleBag(
                    disciple = updatedDisciple,
                    instance = eq,
                    bagStackIds = bagStackIds,
                    gameYear = gameData.gameYear,
                    gameMonth = gameData.gameMonth,
                    gamePhase = gameData.gamePhase,
                    maxStackSize = inventoryConfig.getMaxStackSize("equipment_stack")
                )
                // Write back the updated fields from result.updatedDisciple
                discipleTables.storageBagItems[id] = result.updatedDisciple.equipment.storageBagItems
                discipleTables.storageBagSpiritStones[id] = result.updatedDisciple.equipment.storageBagSpiritStones
                discipleTables.discipleSpiritStones[id] = result.updatedDisciple.equipment.spiritStones
            } else {
                DomainLog.w(TAG, "unequipEquipmentLogic: equipment instance $equipmentId not found for disciple $discipleId, clearing slot only")
            }

            return true
        }
        return false
    }

    // ==================== 辅助方法 ====================

    /**
     * Clear disciple from all slots and assignments
     */
    suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        stateStore.update { gameData = DiscipleSlotCleanup.clearAllSlots(gameData, discipleId) }

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }

        autoFillLawEnforcementSlots()
    }

    /**
     * 执法堂自动补位
     *
     * 当执法弟子槽位出现空缺时，从储备弟子池中按优先级自动选取候选人补位。
     *
     * 补位规则：
     * - 仅对 lawEnforcementDisciples 的 8 个槽位（index 0-7）进行补位，不自动填补执法长老
     * - 候选人排序优先级：智力降序 → 境界升序
     * - 按槽位 index 从小到大依次填补
     * - 一个储备弟子只能填补一个槽位
     * - 仅选取存活且存在于 disciples 列表中的储备弟子
     *
     * @return 成功补位的数量
     */
    suspend fun autoFillLawEnforcementSlots(): Int {
        val data = stateStore.gameData.value
        val elderSlots = data.elderSlots
        val activeSlots = elderSlots.lawEnforcementDisciples
        val reserveSlots = elderSlots.lawEnforcementReserveDisciples
        val tables = stateStore.discipleTables

        // 收集空缺槽位（仅处理前 8 个槽位）
        val emptySlotIndices = (0 until 8).filter { index ->
            index >= activeSlots.size || activeSlots[index].discipleId.isEmpty()
        }

        if (emptySlotIndices.isEmpty()) return 0

        val candidates = reserveSlots
            .mapNotNull { slot ->
                val discipleId = slot.discipleId.ifEmpty { return@mapNotNull null }
                val id = discipleId.toIntOrNull() ?: return@mapNotNull null
                if (tables.ids.contains(id) && tables.isAlive[id] == 1) {
                    Triple(discipleId, slot.discipleName, tables.assemble(id))
                } else null
            }
            // 排序：智力降序 → 境界升序（realm 越小境界越高）
            .sortedWith(compareByDescending<Triple<String, String, Disciple>> { it.third.skills.intelligence }
                .thenBy { it.third.realm })

        if (candidates.isEmpty()) return 0

        var fillCount = 0
        var updatedActiveSlots = activeSlots.toMutableList()
        var updatedReserveSlots = reserveSlots.toMutableList()
        val usedReserveIds = mutableSetOf<String>()

        // 确保活跃槽位列表至少有 8 个元素
        while (updatedActiveSlots.size < 8) {
            updatedActiveSlots.add(DirectDiscipleSlot(index = updatedActiveSlots.size))
        }

        for (slotIndex in emptySlotIndices) {
            if (fillCount >= candidates.size) break

            // 找到第一个未被使用的最优候选人
            val candidate = candidates.find { (discipleId, _, _) -> discipleId !in usedReserveIds } ?: break
            val (discipleId, discipleName, disciple) = candidate
            usedReserveIds.add(discipleId)

            // 构建新的活跃槽位
            updatedActiveSlots[slotIndex] = DirectDiscipleSlot(
                index = slotIndex,
                discipleId = discipleId,
                discipleName = discipleName,
                discipleRealm = disciple.realmName,
                discipleSpiritRootColor = disciple.spiritRoot.countColor
            )

            // 从储备池中移除该弟子
            updatedReserveSlots = updatedReserveSlots.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            }.toMutableList()

            fillCount++
        }

        if (fillCount > 0) {
            stateStore.update { gameData = gameData.copy(
                elderSlots = gameData.elderSlots.copy(
                    lawEnforcementDisciples = updatedActiveSlots.toList(),
                    lawEnforcementReserveDisciples = updatedReserveSlots.toList()
                )
            ) }

            syncAllDiscipleStatuses()
        }

        return fillCount
    }

    /**
     * Check if disciple is in exploration team
     */
    private fun _isInExploration(discipleId: String): Boolean {
        return stateStore.teams.value.any { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == ExplorationStatus.TRAVELING || team.status == ExplorationStatus.EXPLORING || team.status == ExplorationStatus.SCOUTING || team.status == ExplorationStatus.DANGER)
        }
    }

    /**
     * Check if disciple is in cave exploration team
     */
    private fun _isInCaveExploration(discipleId: String): Boolean {
        val data = stateStore.gameData.value
        return data.caveExplorationTeams.any { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == CaveExplorationStatus.TRAVELING || team.status == CaveExplorationStatus.EXPLORING)
        }
    }

    /**
     * Check if disciple is assigned to spirit mine
     */
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean {
        val data = stateStore.gameData.value
        val inMinerSlots = data.spiritMineSlots.any { it.discipleId == discipleId }
        val inDeaconSlots = data.elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }
        return inMinerSlots || inDeaconSlots
    }

    /**
     * Get alive disciples count
     */
    fun getAliveDisciplesCount(): Int {
        val tables = stateStore.discipleTables
        var count = 0
        for (id in tables.ids) {
            if (tables.isAlive[id] == 1) count++
        }
        return count
    }

    /**
     * Get disciples by status
     */
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> {
        val tables = stateStore.discipleTables
        return tables.ids.filter { tables.isAlive[it] == 1 && tables.statuses[it] == status }
            .map { tables.assemble(it) }
    }

    /**
     * Get idle disciples
     */
    fun getIdleDisciples(): List<Disciple> {
        return getDisciplesByStatus(DiscipleStatus.IDLE)
    }

    // ==================== DiscipleAggregate 查询接口（渐进式迁移支持）====================

    /**
     * 获取单个弟子的聚合数据
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 单表实体转换而来。
     *
     * @param discipleId 弟子 ID
     * @return 完整的 DiscipleAggregate 实例，如果弟子不存在则返回 null
     */
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? {
        val disciple = getDiscipleById(discipleId) ?: return null
        return disciple.toAggregate()
    }

    /**
     * 获取所有弟子的聚合数据列表
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 列表批量转换而来。
     *
     * @return 所有弟子的 DiscipleAggregate 列表
     */
    fun getAllDiscipleAggregates(): List<DiscipleAggregate> {
        return stateStore.discipleTables.assembleAll().map { it.toAggregate() }
    }

    /**
     * Update yearly salary enabled/disabled for a realm
     */
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        val data = stateStore.gameData.value
        val newEnabled = data.yearlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        scope.launch { stateStore.update { gameData = data.copy(yearlySalaryEnabled = newEnabled) } }
    }
}

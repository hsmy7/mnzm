package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
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
    private val currentDiscipleTables: DiscipleTables
        get() = stateStore.discipleTables

    companion object {
        private const val TAG = "DiscipleService"
        private val explorationStatuses = setOf(
            ExplorationStatus.TRAVELING, ExplorationStatus.EXPLORING,
            ExplorationStatus.SCOUTING, ExplorationStatus.DANGER
        )
        private val caveExplorationStatuses = setOf(
            CaveExplorationStatus.TRAVELING, CaveExplorationStatus.EXPLORING
        )
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

    // ==================== 弟子日志 ====================

    /**
     * 为指定弟子追加一条日志事件。
     * 事件格式："xx岁：事件描述"。
     */
    fun addLifeEvent(discipleId: String, event: String) {
        val id = discipleId.toIntOrNull() ?: return
        val tables = currentDiscipleTables
        if (!tables.ids.contains(id)) return
        val currentEvents = tables.lifeEvents.getOrDefault(id, emptyList())
        tables.lifeEvents[id] = currentEvents + event
    }

    /**
     * 获取指定弟子的全部日志事件，按添加顺序排列。
     */
    fun getLifeEvents(discipleId: String): List<String> {
        val id = discipleId.toIntOrNull() ?: return emptyList()
        return currentDiscipleTables.lifeEvents.getOrDefault(id, emptyList())
    }

    /**
     * 根据弟子当前状态生成合成历史事件（仅当尚无日志时）。
     * 用于加载旧存档后首次查看日志。
     */
    fun initializeLifeEvents(discipleId: String) {
        val id = discipleId.toIntOrNull() ?: return
        val tables = currentDiscipleTables
        if (!tables.ids.contains(id)) return
        if (tables.lifeEvents.getOrNull(id)?.isNotEmpty() == true) return

        val events = mutableListOf<String>()
        val age = tables.ages[id]
        val data = stateStore.gameData.value
        val currentAbsoluteMonth = data.gameYear * 12 + data.gameMonth
        val recruitedMonth = tables.recruitedMonths.getOrDefault(id, 0)

        // 加入宗门
        if (recruitedMonth > 0 && currentAbsoluteMonth > recruitedMonth) {
            val monthsSince = currentAbsoluteMonth - recruitedMonth
            val recruitedAge = (age - monthsSince / 12).coerceAtLeast(1)
            events.add("${recruitedAge}岁：加入宗门")
        }

        // 拜师
        val masterId = tables.masterIds.getOrNull(id)
        if (masterId != null) {
            val masterIdInt = masterId.toIntOrNull()
            val masterName = if (masterIdInt != null) tables.names.getOrNull(masterIdInt) ?: "未知" else "未知"
            events.add("${age}岁：拜${masterName}为师")
        }

        // 道侣
        val partnerId = tables.partnerIds.getOrNull(id)
        if (partnerId != null) {
            val partnerIdInt = partnerId.toIntOrNull()
            val partnerName = if (partnerIdInt != null) tables.names.getOrNull(partnerIdInt) ?: "未知" else "未知"
            events.add("${age}岁：与${partnerName}结为道侣")
        }

        if (events.isNotEmpty()) {
            tables.lifeEvents[id] = events
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
        if (status == DiscipleStatus.REFINING) return DiscipleStatus.REFINING

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
            elderSlots.lawEnforcementDisciples.any { it.discipleId == discipleId }) {
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
            elderSlots.forgeDisciples.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MANAGING
        }

        if (data.librarySlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.STUDYING
        }

        val discipleType = tables.discipleTypes[id]
        if (data.spiritMineSlots.any { it.discipleId == discipleId } && discipleType == TYPE_OUTER) {
            return DiscipleStatus.MINING
        }

        return DiscipleStatus.IDLE
    }

    /**
     * Sync all disciples' status based on their assignments
     */
    suspend fun syncAllDiscipleStatuses() {
        val data = stateStore.gameData.value
        val tables = stateStore.discipleTables

        val lawEnforcerIds = buildLawEnforcerIds(data.elderSlots)
        val preachingIds = buildPreachingIds(data.elderSlots)
        val deaconingIds = buildDeaconingIds(data.elderSlots)
        val managingIds = buildManagingIds(data.elderSlots)
        val studyingIds = buildStudyingIds(data)
        val miningIds = buildMiningIds(data, tables)
        val garrisonIds = buildGarrisonIds(data)
        val inTeamIds = buildInTeamIds(data)
        val patrollingIds = buildPatrollingIds(data)

        fixInvalidMiningSlots(data, tables)

        stateStore.update {
            for (id in discipleTables.ids) {
                val isAlive = discipleTables.isAlive[id] == 1
                val status = discipleTables.statuses[id]
                if (!isAlive) continue
                if (status == DiscipleStatus.REFLECTING) continue
                if (status == DiscipleStatus.ON_MISSION) continue
                if (status == DiscipleStatus.REFINING) continue

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
                    discipleTables.statuses[id] = newStatus
                }
            }
        }
    }

    // ── syncAllDiscipleStatuses 拆出的槽位收集函数 ──────────────────────

    private fun buildLawEnforcerIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.lawEnforcementElder?.let { ids.add(it) }
        elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        return ids
    }

    private fun buildPreachingIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.preachingElder?.let { ids.add(it) }
        elderSlots.qingyunPreachingElder?.let { ids.add(it) }
        elderSlots.preachingMasters.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId }.forEach { ids.add(it) }
        return ids
    }

    private fun buildDeaconingIds(elderSlots: ElderSlots): Set<String> =
        elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }.toSet()

    private fun buildManagingIds(elderSlots: ElderSlots): Set<String> {
        val ids = mutableSetOf<String>()
        elderSlots.viceSectMaster?.let { ids.add(it) }
        elderSlots.outerElder?.let { ids.add(it) }
        elderSlots.innerElder?.let { ids.add(it) }
        elderSlots.forgeElder?.let { ids.add(it) }
        elderSlots.alchemyElder?.let { ids.add(it) }
        elderSlots.herbGardenElder?.let { ids.add(it) }
        elderSlots.herbGardenDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        elderSlots.alchemyDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        elderSlots.forgeDisciples.forEach { if (it.discipleId.isNotEmpty()) ids.add(it.discipleId) }
        return ids
    }

    private fun buildStudyingIds(data: GameData): Set<String> =
        data.librarySlots.mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }.toSet()

    private fun buildMiningIds(data: GameData, tables: DiscipleTables): Set<String> =
        data.spiritMineSlots
            .mapNotNull { it.discipleId.takeIf { id -> id.isNotEmpty() } }
            .filter { id -> tables.ids.contains(id.toInt()) && tables.discipleTypes[id.toInt()] == TYPE_OUTER }
            .toSet()

    private suspend fun fixInvalidMiningSlots(data: GameData, tables: DiscipleTables) {
        val hasInvalid = data.spiritMineSlots.any { slot ->
            slot.discipleId.isNotEmpty() &&
                (!tables.ids.contains(slot.discipleId.toInt()) || tables.discipleTypes[slot.discipleId.toInt()] != TYPE_OUTER)
        }
        if (hasInvalid) {
            val fixed = data.spiritMineSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() &&
                    (!tables.ids.contains(slot.discipleId.toInt()) || tables.discipleTypes[slot.discipleId.toInt()] != TYPE_OUTER)
                ) slot.copy(discipleId = "", discipleName = "") else slot
            }
            stateStore.update { gameData = gameData.copy(spiritMineSlots = fixed) }
        }
    }

    private fun buildGarrisonIds(data: GameData): Set<String> {
        val ids = mutableSetOf<String>()
        data.worldMapSects.find { it.isPlayerSect }?.garrisonSlots
            ?.filter { it.discipleId.isNotEmpty() }
            ?.forEach { ids.add(it.discipleId) }
        data.warehouseGarrisons.filter { it.discipleId.isNotEmpty() }.forEach { ids.add(it.discipleId) }
        return ids
    }

    private fun buildInTeamIds(data: GameData): MutableSet<String> {
        val ids = mutableSetOf<String>()
        data.battleTeams.flatMap { it.slots }
            .filter { it.discipleId.isNotEmpty() }
            .forEach { ids.add(it.discipleId) }
        // 探索/洞窟队伍成员
        ids.addAll(stateStore.teams.value
            .filter { it.status in explorationStatuses }
            .flatMap { it.memberIds })
        ids.addAll(data.caveExplorationTeams
            .filter { it.status in caveExplorationStatuses }
            .flatMap { it.memberIds })
        return ids
    }

    private fun buildPatrollingIds(data: GameData): Set<String> =
        data.patrolSlots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()

    /**
     * Reset all disciples to IDLE status
     * Used when resetting game state or disbanding all teams
     */
    suspend fun resetAllDisciplesStatus() {
        val data = stateStore.gameData.value
        val tables = stateStore.discipleTables

        val protectedIds = mutableSetOf<String>()
        for (id in tables.ids) {
            val status = tables.statuses[id]
            if (status == DiscipleStatus.REFLECTING || status == DiscipleStatus.REFINING) {
                protectedIds.add(id.toString())
            }
        }

        val clearedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in protectedIds)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedLibrarySlots = data.librarySlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in protectedIds)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedElderSlots = clearAllDisciplesFromElderSlots(data.elderSlots, protectedIds)

        val clearedGarrisonSects = data.worldMapSects.map { sect ->
            if (sect.isPlayerSect) {
                sect.copy(
                    garrisonSlots = sect.garrisonSlots.map { slot ->
                        if (slot.discipleId.isNotEmpty() && slot.discipleId !in protectedIds)
                            GarrisonSlot(index = slot.index)
                        else slot
                    }
                )
            } else sect
        }

        val clearedCaveTeams = data.caveExplorationTeams.map { team ->
            if (team.memberIds.any { it !in protectedIds }) {
                team.copy(
                    memberIds = emptyList(),
                    memberNames = emptyList(),
                    status = CaveExplorationStatus.COMPLETED
                )
            } else team
        }

        val clearedActiveMissions = data.activeMissions.filter { mission ->
            mission.discipleIds.all { it in protectedIds }
        }

        val teamsSnapshot = stateStore.teams.value
        val updatedTeams = teamsSnapshot.map { team ->
            if (team.memberIds.any { it !in protectedIds }) {
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
            if (slot.assignedDiscipleId != null && slot.assignedDiscipleId !in protectedIds && !slot.isWorking) {
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
            if (status == DiscipleStatus.REFINING) continue
            if (status == DiscipleStatus.IDLE) continue
            tables.statuses[id] = DiscipleStatus.IDLE
            tables.statusData[id] = emptyMap()
        }
    }

    private fun clearAllDisciplesFromElderSlots(slots: ElderSlots, protectedIds: Set<String>): ElderSlots {
        var updated = slots

        if (updated.viceSectMaster.isNotEmpty() && updated.viceSectMaster !in protectedIds)
            updated = updated.copy(viceSectMaster = "")
        if (updated.herbGardenElder.isNotEmpty() && updated.herbGardenElder !in protectedIds)
            updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder.isNotEmpty() && updated.alchemyElder !in protectedIds)
            updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder.isNotEmpty() && updated.forgeElder !in protectedIds)
            updated = updated.copy(forgeElder = "")
        if (updated.outerElder.isNotEmpty() && updated.outerElder !in protectedIds)
            updated = updated.copy(outerElder = "")
        if (updated.preachingElder.isNotEmpty() && updated.preachingElder !in protectedIds)
            updated = updated.copy(preachingElder = "")
        if (updated.lawEnforcementElder.isNotEmpty() && updated.lawEnforcementElder !in protectedIds)
            updated = updated.copy(lawEnforcementElder = "")
        if (updated.innerElder.isNotEmpty() && updated.innerElder !in protectedIds)
            updated = updated.copy(innerElder = "")
        if (updated.qingyunPreachingElder.isNotEmpty() && updated.qingyunPreachingElder !in protectedIds)
            updated = updated.copy(qingyunPreachingElder = "")

        updated = updated.copy(
            preachingMasters = updated.preachingMasters.filter { it.discipleId in protectedIds },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.filter { it.discipleId in protectedIds },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.filter { it.discipleId in protectedIds },
            herbGardenDisciples = updated.herbGardenDisciples.filter { it.discipleId in protectedIds },
            alchemyDisciples = updated.alchemyDisciples.filter { it.discipleId in protectedIds },
            forgeDisciples = updated.forgeDisciples.filter { it.discipleId in protectedIds },
            spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.filter { it.discipleId in protectedIds }
        )

        return updated
    }

    // ==================== 弟子培养 ====================

    /**
     * Recruit new disciple
     * @param realm 境界，默认 9（炼气期），0 为仙人
     */
    fun recruitDisciple(realm: Int = 9): Disciple {
        val id = stateStore.discipleTables.allocateNextId().toString()
        val gender = if (Random.nextBoolean()) GENDER_MALE else GENDER_FEMALE

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
                realm = realm,
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

        // 记录加入宗门日志
        addLifeEvent(disciple.id, "${disciple.age}岁：加入宗门")

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

            if (discipleTables.statuses[id] == DiscipleStatus.REFINING) {
                error = AppError.Domain.Disciple.SlotInvalid("弟子正在血炼中，无法驱逐")
                return@update
            }

            clearDiscipleFromAllSlots(discipleId)

            val returnEquipIds = mutableListOf<String>()
            discipleTables.weaponIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.armorIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.bootsIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.accessoryIds[id].takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            discipleTables.storageBagItems[id].filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }.forEach { returnEquipIds.add(it.itemId) }

            val bagStackIds = discipleTables.ids
                .filter { it != id }
                .flatMap { discipleTables.storageBagItems[it] }
                .filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK }
                .map { it.itemId }
                .toSet()

            returnEquipIds.forEach { eid ->
                val eq = equipmentInstances.get(eid) ?: return@forEach
                val stack = eq.toStack()
                val maxStack = inventoryConfig.getMaxStackSize(ITEM_TYPE_EQUIPMENT_STACK)
                equipmentStacks = equipmentStacks.mergeStackable(
                    item = stack,
                    matchPredicate = { it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot && it.id !in bagStackIds },
                    maxStack = maxStack
                )
                equipmentInstances.remove(eid)
            }

            discipleTables.storageBagItems[id].filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }.forEach { bagItem ->
                val m = manualInstances.get(bagItem.itemId)
                if (m != null) {
                    val stack = m.toStack()
                    val maxStack = inventoryConfig.getMaxStackSize(ITEM_TYPE_MANUAL_STACK)
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
                    val maxStack = inventoryConfig.getMaxStackSize(ITEM_TYPE_MANUAL_STACK)
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

            // 记录拜师日志（徒弟视角）
            val masterName = discipleTables.names[mid] ?: "未知"
            val discipleName = discipleTables.names[did] ?: "未知"
            val discipleAge = discipleTables.ages[did]
            val masterAge = discipleTables.ages[mid]
            val currentEvents = discipleTables.lifeEvents.getOrDefault(did, emptyList())
            discipleTables.lifeEvents[did] = currentEvents + "${discipleAge}岁：拜${masterName}为师"
            // 记录收徒日志（师父视角）
            val masterEvents = discipleTables.lifeEvents.getOrDefault(mid, emptyList())
            discipleTables.lifeEvents[mid] = masterEvents + "${masterAge}岁：收${discipleName}为徒"
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

            // 记录装备日志
            val equipAge = discipleTables.ages[id]
            val equipEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
            if (oldEquipId.isNotEmpty()) {
                val oldName = equipmentInstances.get(oldEquipId)?.name ?: "旧装备"
                discipleTables.lifeEvents[id] = equipEvents +
                    "${equipAge}岁：将${oldName}替换为${equipName}"
            } else {
                discipleTables.lifeEvents[id] = equipEvents +
                    "${equipAge}岁：装备了${equipName}"
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
                    maxStackSize = inventoryConfig.getMaxStackSize(ITEM_TYPE_EQUIPMENT_STACK)
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

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId(BUILDING_FORGE)
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId(BUILDING_FORGE, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
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
    suspend fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        val data = stateStore.gameData.value
        val newEnabled = data.yearlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        stateStore.update { gameData = gameData.copy(yearlySalaryEnabled = newEnabled) }
    }
}

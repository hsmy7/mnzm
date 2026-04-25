package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.addEquipmentInstanceToDiscipleBag
import com.xianxia.sect.core.state.equipmentBagStackIds
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.StateAccessorFactory
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.NameService
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@SystemPriority(order = 220)
@Singleton
class DiscipleService @Inject constructor(
    private val stateStore: GameStateStore,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventService: EventService,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val inventoryConfig: InventoryConfig
) : GameSystem {
    override val systemName: String = "DiscipleService"
    private val scope get() = applicationScopeProvider.scope

    override fun initialize() {
        Log.d(TAG, "DiscipleService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "DiscipleService released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    private val state = StateAccessorFactory(stateStore, scope, null)

    private var currentGameData: GameData
        get() = state.gameDataFromUnified().current
        set(value) { state.gameDataFromUnified().current = value }

    private var currentDisciples: List<Disciple>
        get() = state.disciplesFromUnified().current
        set(value) { state.disciplesFromUnified().current = value }

    private var currentTeams: List<ExplorationTeam>
        get() = state.teamsFromUnified().current
        set(value) { state.teamsFromUnified().current = value }

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
        currentDisciples = currentDisciples + disciple
    }

    /**
     * Remove disciple by ID
     */
    fun removeDisciple(discipleId: String): Boolean {
        val current = currentDisciples
        val filtered = current.filter { it.id != discipleId }
        if (filtered.size < current.size) {
            currentDisciples = filtered
            return true
        }
        return false
    }

    /**
     * Get disciple by ID
     */
    fun getDiscipleById(discipleId: String): Disciple? {
        return currentDisciples.find { it.id == discipleId }
    }

    /**
     * Update disciple
     */
    fun updateDisciple(disciple: Disciple) {
        currentDisciples = currentDisciples.map {
            if (it.id == disciple.id) disciple else it
        }
    }

    // ==================== 弟子状态管理 ====================

    /**
     * Get disciple status based on current assignments
     */
    fun getDiscipleStatus(discipleId: String): DiscipleStatus {
        val data = currentGameData
        val disciple = currentDisciples.find { it.id == discipleId } ?: return DiscipleStatus.IDLE

        if (!disciple.isAlive) return DiscipleStatus.DEAD
        if (disciple.status == DiscipleStatus.REFLECTING) return DiscipleStatus.REFLECTING
        if (disciple.status == DiscipleStatus.ON_MISSION) return DiscipleStatus.ON_MISSION

        val battleTeam = data.battleTeam
        if (battleTeam != null && battleTeam.status != "idle") {
            if (battleTeam.slots.any { it.discipleId == discipleId }) {
                return DiscipleStatus.IN_TEAM
            }
        }

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
            elderSlots.herbGardenElder == discipleId) {
            return DiscipleStatus.MANAGING
        }

        if (data.librarySlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.STUDYING
        }

        if (data.spiritMineSlots.any { it.discipleId == discipleId } && disciple.discipleType == "outer") {
            return DiscipleStatus.MINING
        }

        return DiscipleStatus.IDLE
    }

    /**
     * Sync all disciples' status based on their assignments
     */
    fun syncAllDiscipleStatuses() {
        var data = currentGameData
        val disciples = currentDisciples
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

        val studyingIds = data.librarySlots.mapNotNull { it.discipleId }.toMutableSet()

        val allDisciplesMap = disciples.associateBy { it.id }
        val miningIds = data.spiritMineSlots
            .mapNotNull { it.discipleId }
            .filter { id -> allDisciplesMap[id]?.discipleType == "outer" }
            .toMutableSet()

        val hasInvalidMiningSlots = data.spiritMineSlots.any { slot ->
            slot.discipleId.isNotEmpty() && allDisciplesMap[slot.discipleId]?.discipleType != "outer"
        }
        if (hasInvalidMiningSlots) {
            val fixedSlots = data.spiritMineSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() && allDisciplesMap[slot.discipleId]?.discipleType != "outer") {
                    slot.copy(discipleId = "", discipleName = "")
                } else slot
            }
            data = data.copy(spiritMineSlots = fixedSlots)
            currentGameData = data
        }

        val inTeamIds = mutableSetOf<String>()
        val battleTeam = data.battleTeam
        if (battleTeam != null && battleTeam.status != "idle") {
            battleTeam.slots.mapNotNull { it.discipleId }.forEach { inTeamIds.add(it) }
        }

        currentTeams.filter { it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING }
            .forEach { team -> inTeamIds.addAll(team.memberIds) }
        data.caveExplorationTeams.filter { it.status == CaveExplorationStatus.TRAVELING || it.status == CaveExplorationStatus.EXPLORING }
            .forEach { team -> inTeamIds.addAll(team.memberIds) }

        currentDisciples = disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple
            if (disciple.status == DiscipleStatus.REFLECTING) return@map disciple
            if (disciple.status == DiscipleStatus.ON_MISSION) return@map disciple

            val newStatus = when {
                inTeamIds.contains(disciple.id) -> DiscipleStatus.IN_TEAM
                lawEnforcerIds.contains(disciple.id) -> DiscipleStatus.LAW_ENFORCING
                preachingIds.contains(disciple.id) -> DiscipleStatus.PREACHING
                deaconingIds.contains(disciple.id) -> DiscipleStatus.DEACONING
                managingIds.contains(disciple.id) -> DiscipleStatus.MANAGING
                studyingIds.contains(disciple.id) -> DiscipleStatus.STUDYING
                miningIds.contains(disciple.id) -> DiscipleStatus.MINING
                else -> DiscipleStatus.IDLE
            }

            if (disciple.status != newStatus) {
                disciple.copy(status = newStatus)
            } else {
                disciple
            }
        }
    }

    /**
     * Reset all disciples to IDLE status
     * Used when resetting game state or disbanding all teams
     */
    suspend fun resetAllDisciplesStatus() {
        val data = currentGameData

        val reflectingIds = currentDisciples
            .filter { it.status == DiscipleStatus.REFLECTING }
            .map { it.id }
            .toSet()

        val clearedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in reflectingIds)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedLibrarySlots = data.librarySlots.map {
            if (it.discipleId.isNotEmpty() && it.discipleId !in reflectingIds)
                it.copy(discipleId = "", discipleName = "") else it
        }

        val clearedElderSlots = clearAllDisciplesFromElderSlots(data.elderSlots, reflectingIds)

        val clearedBattleTeam = data.battleTeam?.let { team ->
            if (team.status != "idle") {
                team.copy(
                    slots = team.slots.map { slot ->
                        if (slot.discipleId.isNotEmpty() && slot.discipleId !in reflectingIds)
                            slot.copy(discipleId = "", discipleName = "", discipleRealm = "", isAlive = true)
                        else slot
                    },
                    status = "idle",
                    targetSectId = "",
                    originSectId = "",
                    route = emptyList(),
                    currentRouteIndex = 0,
                    moveProgress = 0f,
                    isOccupying = false,
                    occupiedSectId = "",
                    isReturning = false
                )
            } else team
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

        currentGameData = data.copy(
            spiritMineSlots = clearedSpiritMineSlots,
            librarySlots = clearedLibrarySlots,
            elderSlots = clearedElderSlots,
            battleTeam = clearedBattleTeam,
            caveExplorationTeams = clearedCaveTeams,
            activeMissions = clearedActiveMissions
        )

        currentTeams = currentTeams.map { team ->
            if (team.memberIds.any { it !in reflectingIds }) {
                team.copy(
                    memberIds = emptyList(),
                    memberNames = emptyList(),
                    status = ExplorationStatus.COMPLETED
                )
            } else team
        }

        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId != null && slot.assignedDiscipleId !in reflectingIds && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }

        currentDisciples = currentDisciples.map { disciple ->
            when {
                !disciple.isAlive -> disciple
                disciple.status == DiscipleStatus.REFLECTING -> disciple
                disciple.status == DiscipleStatus.IDLE -> disciple
                else -> disciple.copy(status = DiscipleStatus.IDLE, statusData = emptyMap())
            }
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
        val id = UUID.randomUUID().toString()
        val gender = if (Random.nextBoolean()) "male" else "female"

        val existingNames = (currentDisciples + currentGameData.recruitList).map { it.name }.toSet()
        val nameResult = NameService.generateName(gender, NameService.NameStyle.FULL, existingNames)

        val allSpiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val rootCount = when (Random.nextInt(100)) {
            in 0..4 -> 1
            in 5..24 -> 2
            in 25..54 -> 3
            in 55..84 -> 4
            else -> 5
        }
        val spiritRootType = allSpiritRootTypes.shuffled().take(rootCount).joinToString(",")

        val hpVariance = Random.nextInt(-50, 51)
        val mpVariance = Random.nextInt(-50, 51)
        val physicalAttackVariance = Random.nextInt(-50, 51)
        val magicAttackVariance = Random.nextInt(-50, 51)
        val physicalDefenseVariance = Random.nextInt(-50, 51)
        val magicDefenseVariance = Random.nextInt(-50, 51)
        val speedVariance = Random.nextInt(-50, 51)

        val comprehension = when (rootCount) {
            1 -> Random.nextInt(80, 101)
            2 -> Random.nextInt(60, 101)
            3 -> Random.nextInt(40, 101)
            4 -> Random.nextInt(20, 101)
            else -> Random.nextInt(1, 101)
        }

        val disciple = Disciple(
            id = id,
            name = nameResult.fullName,
            surname = nameResult.surname,
            gender = gender,
            age = Random.nextInt(16, 30),
            realm = 9,
            realmLayer = 1,
            spiritRootType = spiritRootType,
            status = DiscipleStatus.IDLE,
            discipleType = "outer",
            talentIds = TalentDatabase.generateTalentsForDisciple().map { it.id },
            combat = com.xianxia.sect.core.model.CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            social = com.xianxia.sect.core.model.SocialData(),
            skills = com.xianxia.sect.core.model.SkillStats(
                intelligence = Random.nextInt(1, 101),
                charm = Random.nextInt(1, 101),
                loyalty = Random.nextInt(1, 101),
                comprehension = comprehension,
                morality = Random.nextInt(1, 101),
                artifactRefining = Random.nextInt(1, 101),
                pillRefining = Random.nextInt(1, 101),
                spiritPlanting = Random.nextInt(1, 101),
                teaching = Random.nextInt(1, 101)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed

            // 计算寿命天赋加成（如"寿元绵长"/"寿元亏损"）
            val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val baseLifespan = GameConfig.Realm.get(realm).maxAge
            lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
        }

        // Set recruitment time
        val data = currentGameData
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        disciple.usage.recruitedMonth = currentMonthValue

        addDisciple(disciple)
        eventService.addGameEvent("新弟子 ${disciple.name} 加入宗门", EventType.SUCCESS)

        return disciple
    }

    /**
     * Expel disciple from sect
     */
    suspend fun expelDisciple(discipleId: String): Boolean {
        var result = false
        stateStore.update {
            val disciple = disciples.find { it.id == discipleId }
            if (disciple == null) {
                result = false
                return@update
            }

            if (!disciple.isAlive) {
                eventService.addGameEvent("${disciple.name} 已死亡，无法逐出", EventType.WARNING)
                result = false
                return@update
            }

            clearDiscipleFromAllSlots(discipleId)

            val returnEquipIds = mutableListOf<String>()
            disciple.equipment.weaponId.takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            disciple.equipment.armorId.takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            disciple.equipment.bootsId.takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            disciple.equipment.accessoryId.takeIf { it.isNotEmpty() }?.let { returnEquipIds.add(it) }
            disciple.equipment.storageBagItems.filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { returnEquipIds.add(it.itemId) }

            val bagStackIds = disciples.filter { it.id != discipleId }
                .flatMap { it.equipment.storageBagItems }
                .filter { it.itemType == "equipment_stack" }
                .map { it.itemId }
                .toSet()

            returnEquipIds.forEach { eid ->
                val eq = equipmentInstances.find { it.id == eid } ?: return@forEach
                val stack = eq.toStack()
                val existingStack = equipmentStacks.find {
                    it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot && it.id !in bagStackIds
                }
                if (existingStack != null) {
                    val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                    val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxStack)
                    equipmentStacks = equipmentStacks.map { s ->
                        if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                    }
                } else {
                    equipmentStacks = equipmentStacks + stack
                }
                equipmentInstances = equipmentInstances.filter { it.id != eid }
            }

            disciple.equipment.storageBagItems.filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }.forEach { bagItem ->
                val m = manualInstances.find { it.id == bagItem.itemId }
                if (m != null) {
                    val stack = m.toStack()
                    val existingStack = manualStacks.find {
                        it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type
                    }
                    if (existingStack != null) {
                        val maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                        val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxStack)
                        manualStacks = manualStacks.map { s ->
                            if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                        }
                    } else {
                        manualStacks = manualStacks + stack
                    }
                    manualInstances = manualInstances.filter { it.id != bagItem.itemId }
                }
            }

            disciple.manualIds.forEach { manualId ->
                val m = manualInstances.find { it.id == manualId }
                if (m != null) {
                    val stack = m.toStack()
                    val existingStack = manualStacks.find {
                        it.name == stack.name && it.rarity == stack.rarity && it.type == stack.type
                    }
                    if (existingStack != null) {
                        val maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                        val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(maxStack)
                        manualStacks = manualStacks.map { s ->
                            if (s.id == existingStack.id) s.copy(quantity = newQty) else s
                        }
                    } else {
                        manualStacks = manualStacks + stack
                    }
                    manualInstances = manualInstances.filter { it.id != manualId }
                }
            }

            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(discipleId)
            if (updatedProficiencies != gameData.manualProficiencies) {
                gameData = gameData.copy(manualProficiencies = updatedProficiencies)
            }

            disciples = disciples.filter { it.id != discipleId }

            eventService.addGameEvent("已将 ${disciple.name} 逐出宗门", EventType.INFO)
            result = true
        }
        return result
    }

    // ==================== 装备管理 ====================

    /**
     * Equip equipment to disciple
     * 设计意图：装备是独占物品，不可共用。一件装备只能给一名弟子穿戴。
     * 装备新装备时，旧装备自动卸下并放入弟子储物袋。
     */
    suspend fun equipEquipment(discipleId: String, equipmentId: String): Boolean {
        var result = false
        stateStore.update {
            val idx = disciples.indexOfFirst { it.id == discipleId }
            if (idx < 0) { result = false; return@update }
            val disciple = disciples[idx]

            val equipmentStack = equipmentStacks.find { it.id == equipmentId }
            val equipmentInstance = equipmentInstances.find { it.id == equipmentId }

            if (equipmentStack == null && equipmentInstance == null) { result = false; return@update }

            if (equipmentInstance != null) {
                if (equipmentInstance.isEquipped) {
                    if (equipmentInstance.ownerId == discipleId) { result = false; return@update }
                    eventService.addGameEvent("${equipmentInstance.name} 已被其他弟子装备，无法重复穿戴", EventType.WARNING)
                    result = false; return@update
                }
                if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, equipmentInstance.minRealm)) {
                    eventService.addGameEvent("${disciple.name} 境界不足，无法装备 ${equipmentInstance.name}", EventType.WARNING)
                    result = false; return@update
                }
            } else if (equipmentStack != null) {
                if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, equipmentStack.minRealm)) {
                    eventService.addGameEvent("${disciple.name} 境界不足，无法装备 ${equipmentStack.name}", EventType.WARNING)
                    result = false; return@update
                }
            }

            val slot = equipmentInstance?.slot ?: equipmentStack?.slot ?: run { result = false; return@update }
            val equipName = equipmentStack?.name ?: equipmentInstance?.name ?: ""

            val oldEquipId = when (slot) {
                EquipmentSlot.WEAPON -> disciple.equipment.weaponId
                EquipmentSlot.ARMOR -> disciple.equipment.armorId
                EquipmentSlot.BOOTS -> disciple.equipment.bootsId
                EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
                else -> ""
            }
            if (oldEquipId.isNotEmpty()) {
                val unequipped = unequipEquipmentLogic(discipleId, oldEquipId)
                if (!unequipped) {
                    Log.w(TAG, "equipEquipment: failed to unequip $oldEquipId, aborting equip")
                    result = false; return@update
                }
            }

            val currentDisciple = disciples[idx]
            val stack = equipmentStacks.find { it.id == equipmentId }
            val instance = equipmentInstances.find { it.id == equipmentId }

            if (stack != null) {
                val equippedId = UUID.randomUUID().toString()
                val equippedItem = stack.toInstance(id = equippedId, ownerId = discipleId, isEquipped = true)
                if (stack.quantity > 1) {
                    equipmentStacks = equipmentStacks.map {
                        if (it.id == equipmentId) it.copy(quantity = it.quantity - 1) else it
                    }
                } else {
                    equipmentStacks = equipmentStacks.filter { it.id != equipmentId }
                }
                equipmentInstances = equipmentInstances + equippedItem
                val updatedDisciple = when (slot) {
                    EquipmentSlot.WEAPON -> currentDisciple.copyWith(weaponId = equippedId)
                    EquipmentSlot.ARMOR -> currentDisciple.copyWith(armorId = equippedId)
                    EquipmentSlot.BOOTS -> currentDisciple.copyWith(bootsId = equippedId)
                    EquipmentSlot.ACCESSORY -> currentDisciple.copyWith(accessoryId = equippedId)
                    else -> currentDisciple
                }
                disciples = disciples.toMutableList().also { it[idx] = updatedDisciple }
            } else if (instance != null) {
                val updatedDisciple = when (slot) {
                    EquipmentSlot.WEAPON -> currentDisciple.copyWith(weaponId = equipmentId)
                    EquipmentSlot.ARMOR -> currentDisciple.copyWith(armorId = equipmentId)
                    EquipmentSlot.BOOTS -> currentDisciple.copyWith(bootsId = equipmentId)
                    EquipmentSlot.ACCESSORY -> currentDisciple.copyWith(accessoryId = equipmentId)
                    else -> currentDisciple
                }
                disciples = disciples.toMutableList().also { it[idx] = updatedDisciple }
                equipmentInstances = equipmentInstances.map {
                    if (it.id == equipmentId) it.copy(isEquipped = true, ownerId = discipleId) else it
                }
            }

            eventService.addGameEvent("${disciple.name} 装备了 $equipName", EventType.INFO)
            result = true
        }
        return result
    }

    /**
     * Unequip equipment from disciple
     * 设计意图：装备是独占物品，卸下后放入弟子储物袋，而非归还宗门仓库。
     *
     * 验证和卸下操作全部在 stateStore.update 事务内原子执行，返回实际操作结果。
     */
    suspend fun unequipEquipment(discipleId: String, equipmentId: String): Boolean {
        var result = false
        stateStore.update {
            val disciple = disciples.find { it.id == discipleId }
            if (disciple == null) { result = false; return@update }
            val isEquipped = disciple.equipment.weaponId == equipmentId ||
                disciple.equipment.armorId == equipmentId ||
                disciple.equipment.bootsId == equipmentId ||
                disciple.equipment.accessoryId == equipmentId
            if (!isEquipped) { result = false; return@update }

            result = unequipEquipmentLogic(discipleId, equipmentId)
        }
        return result
    }

    private fun MutableGameState.unequipEquipmentLogic(discipleId: String, equipmentId: String): Boolean {
        val discipleIndex = disciples.indexOfFirst { it.id == discipleId }
        if (discipleIndex < 0) return false

        val disciple = disciples[discipleIndex]
        val updatedDisciple = when {
            disciple.equipment.weaponId == equipmentId -> disciple.copyWith(weaponId = "")
            disciple.equipment.armorId == equipmentId -> disciple.copyWith(armorId = "")
            disciple.equipment.bootsId == equipmentId -> disciple.copyWith(bootsId = "")
            disciple.equipment.accessoryId == equipmentId -> disciple.copyWith(accessoryId = "")
            else -> disciple
        }

        if (updatedDisciple != disciple) {
            val eq = equipmentInstances.find { it.id == equipmentId }

            if (eq != null) {
                val bagStackIds = updatedDisciple.equipmentBagStackIds()
                val result = addEquipmentInstanceToDiscipleBag(
                    disciple = updatedDisciple,
                    instance = eq,
                    bagStackIds = bagStackIds,
                    gameYear = gameData.gameYear,
                    gameMonth = gameData.gameMonth,
                    gameDay = gameData.gameDay,
                    maxStackSize = inventoryConfig.getMaxStackSize("equipment_stack")
                )
                disciples = disciples.toMutableList().also { it[discipleIndex] = result.updatedDisciple }
            } else {
                Log.w(TAG, "unequipEquipmentLogic: equipment instance $equipmentId not found for disciple $discipleId, clearing slot only")
                disciples = disciples.toMutableList().also { it[discipleIndex] = updatedDisciple }
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
        val data = currentGameData

        val updatedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedLibrarySlots = data.librarySlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedElderSlots = clearDiscipleFromElderSlots(data.elderSlots, discipleId)

        currentGameData = data.copy(
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots
        )

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
     * Clear disciple from elder slots
     */
    private fun clearDiscipleFromElderSlots(slots: ElderSlots, discipleId: String): ElderSlots {
        var updated = slots

        if (updated.viceSectMaster == discipleId) updated = updated.copy(viceSectMaster = "")
        if (updated.herbGardenElder == discipleId) updated = updated.copy(herbGardenElder = "")
        if (updated.alchemyElder == discipleId) updated = updated.copy(alchemyElder = "")
        if (updated.forgeElder == discipleId) updated = updated.copy(forgeElder = "")
        if (updated.outerElder == discipleId) updated = updated.copy(outerElder = "")
        if (updated.preachingElder == discipleId) updated = updated.copy(preachingElder = "")
        if (updated.lawEnforcementElder == discipleId) updated = updated.copy(lawEnforcementElder = "")
        if (updated.innerElder == discipleId) updated = updated.copy(innerElder = "")
        if (updated.qingyunPreachingElder == discipleId) updated = updated.copy(qingyunPreachingElder = "")

        // Clear from position slots
        updated = updated.copy(
            preachingMasters = updated.preachingMasters.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            qingyunPreachingMasters = updated.qingyunPreachingMasters.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            herbGardenDisciples = updated.herbGardenDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            herbGardenReserveDisciples = updated.herbGardenReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            alchemyReserveDisciples = updated.alchemyReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            },
            forgeReserveDisciples = updated.forgeReserveDisciples.mapNotNull { slot ->
                if (slot.discipleId == discipleId) null else slot
            }
        )

        return updated
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
        val data = currentGameData
        val elderSlots = data.elderSlots
        val activeSlots = elderSlots.lawEnforcementDisciples
        val reserveSlots = elderSlots.lawEnforcementReserveDisciples

        // 收集空缺槽位（仅处理前 8 个槽位）
        val emptySlotIndices = (0 until 8).filter { index ->
            index >= activeSlots.size || activeSlots[index].discipleId.isEmpty()
        }

        if (emptySlotIndices.isEmpty()) return 0

        val candidates = reserveSlots
            .mapNotNull { slot ->
                val discipleId = slot.discipleId.ifEmpty { return@mapNotNull null }
                val disciple = currentDisciples.find { it.id == discipleId }
                if (disciple != null && disciple.isAlive) {
                    Triple(discipleId, slot.discipleName, disciple)
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

            eventService.addGameEvent("执法堂自动补位：$discipleName 接任第${slotIndex + 1}号执法弟子", EventType.INFO)
            fillCount++
        }

        if (fillCount > 0) {
            currentGameData = currentGameData.copy(
                elderSlots = currentGameData.elderSlots.copy(
                    lawEnforcementDisciples = updatedActiveSlots.toList(),
                    lawEnforcementReserveDisciples = updatedReserveSlots.toList()
                )
            )

            syncAllDiscipleStatuses()
        }

        return fillCount
    }

    /**
     * Check if disciple is in exploration team
     */
    private fun _isInExploration(discipleId: String): Boolean {
        return currentTeams.any { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == ExplorationStatus.TRAVELING || team.status == ExplorationStatus.EXPLORING)
        }
    }

    /**
     * Check if disciple is in cave exploration team
     */
    private fun _isInCaveExploration(discipleId: String): Boolean {
        val data = currentGameData
        return data.caveExplorationTeams.any { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == CaveExplorationStatus.TRAVELING || team.status == CaveExplorationStatus.EXPLORING)
        }
    }

    /**
     * Check if disciple is assigned to spirit mine
     */
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean {
        val data = currentGameData
        val inMinerSlots = data.spiritMineSlots.any { it.discipleId == discipleId }
        val inDeaconSlots = data.elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }
        return inMinerSlots || inDeaconSlots
    }

    /**
     * Get alive disciples count
     */
    fun getAliveDisciplesCount(): Int {
        return currentDisciples.count { it.isAlive }
    }

    /**
     * Get disciples by status
     */
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> {
        return currentDisciples.filter { it.status == status && it.isAlive }
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
        return currentDisciples.map { it.toAggregate() }
    }

    /**
     * Update monthly salary enabled/disabled for a realm
     */
    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        val data = currentGameData
        val newEnabled = data.monthlySalaryEnabled.toMutableMap()
        newEnabled[realm] = enabled
        currentGameData = data.copy(monthlySalaryEnabled = newEnabled)
    }
}

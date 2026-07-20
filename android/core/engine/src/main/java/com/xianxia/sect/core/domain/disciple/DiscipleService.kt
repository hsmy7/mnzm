package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
@GameService("DiscipleService")
@Singleton
class DiscipleService @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: DiscipleFactory,
    private val rngManager: GameRngManager,
    // 子服务（已提取的职责模块，用于未来深度重构）
    private val discipleEquipmentService: DiscipleEquipmentService,
    private val discipleLifecycleManager: DiscipleLifecycleManager,
    private val discipleMasterApprenticeService: DiscipleMasterApprenticeService,
    private val discipleSlotManager: DiscipleSlotManager
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
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
    fun addDisciple(disciple: Disciple) = discipleLifecycleManager.addDisciple(disciple)

    /**
     * Remove disciple by ID
     */
    fun removeDisciple(discipleId: String): DomainResult<Unit> = discipleLifecycleManager.removeDisciple(discipleId)

    /**
     * Get disciple by ID
     */
    fun getDiscipleById(discipleId: String): Disciple? = discipleLifecycleManager.getDiscipleById(discipleId)

    /**
     * Update disciple
     */
    fun updateDisciple(disciple: Disciple) = discipleLifecycleManager.updateDisciple(disciple)

    // ==================== 弟子日志 ====================

    /**
     * 为指定弟子追加一条日志事件。
     * 事件格式："xx岁：事件描述"。
     */
    fun addLifeEvent(discipleId: String, event: String) = discipleLifecycleManager.addLifeEvent(discipleId, event)

    fun getLifeEvents(discipleId: String): List<String> = discipleLifecycleManager.getLifeEvents(discipleId)

    fun initializeLifeEvents(discipleId: String) = discipleLifecycleManager.initializeLifeEvents(discipleId)

    fun getDiscipleStatus(discipleId: String): DiscipleStatus = discipleLifecycleManager.getDiscipleStatus(discipleId)

    /**
     * Sync all disciples' status based on their assignments
     */
    fun syncAllDiscipleStatuses() {
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
            .filter { id -> tables.ids.contains(id.toInt()) }
            .toSet()

    private fun fixInvalidMiningSlots(data: GameData, tables: DiscipleTables) {
        val hasInvalid = data.spiritMineSlots.any { slot ->
            slot.discipleId.isNotEmpty() &&
                !tables.ids.contains(slot.discipleId.toInt())
        }
        if (hasInvalid) {
            val fixed = data.spiritMineSlots.map { slot ->
                if (slot.discipleId.isNotEmpty() &&
                    !tables.ids.contains(slot.discipleId.toInt())
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
        val protectedIds = stateStore.updateAndReturn {
            val ids = mutableSetOf<String>()
            for (id in discipleTables.ids) {
                val status = discipleTables.statuses[id]
                if (status == DiscipleStatus.REFLECTING || status == DiscipleStatus.REFINING) {
                    ids.add(id.toString())
                }
            }

            val clearedSpiritMineSlots = gameData.spiritMineSlots.map {
                if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                    it.copy(discipleId = "", discipleName = "") else it
            }

            val clearedLibrarySlots = gameData.librarySlots.map {
                if (it.discipleId.isNotEmpty() && it.discipleId !in ids)
                    it.copy(discipleId = "", discipleName = "") else it
            }

            val clearedElderSlots = clearAllDisciplesFromElderSlots(gameData.elderSlots, ids)

            val clearedGarrisonSects = gameData.worldMapSects.map { sect ->
                if (sect.isPlayerSect) {
                    sect.copy(
                        garrisonSlots = sect.garrisonSlots.map { slot ->
                            if (slot.discipleId.isNotEmpty() && slot.discipleId !in ids)
                                GarrisonSlot(index = slot.index)
                            else slot
                        }
                    )
                } else sect
            }

            val clearedCaveTeams = gameData.caveExplorationTeams.map { team ->
                if (team.memberIds.any { it !in ids }) {
                    team.copy(
                        memberIds = emptyList(),
                        memberNames = emptyList(),
                        status = CaveExplorationStatus.COMPLETED
                    )
                } else team
            }

            val clearedActiveMissions = gameData.activeMissions.filter { mission ->
                mission.discipleIds.all { it in ids }
            }

            val updatedTeams = teams.map { team ->
                if (team.memberIds.any { it !in ids }) {
                    team.copy(
                        memberIds = emptyList(),
                        memberNames = emptyList(),
                        status = ExplorationStatus.COMPLETED
                    )
                } else team
            }

            gameData = gameData.copy(
                spiritMineSlots = clearedSpiritMineSlots,
                librarySlots = clearedLibrarySlots,
                elderSlots = clearedElderSlots,
                worldMapSects = clearedGarrisonSects,
                caveExplorationTeams = clearedCaveTeams,
                activeMissions = clearedActiveMissions
            )
            teams = updatedTeams

            for (id in discipleTables.ids) {
                val isAlive = discipleTables.isAlive[id] == 1
                val status = discipleTables.statuses[id]
                if (!isAlive) continue
                if (status == DiscipleStatus.REFLECTING) continue
                if (status == DiscipleStatus.REFINING) continue
                if (status == DiscipleStatus.IDLE) continue
                discipleTables.statuses[id] = DiscipleStatus.IDLE
                discipleTables.statusData[id] = emptyMap()
            }

            ids
        }

        discipleLifecycleManager.clearProductionSlots(protectedIds)
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
     *
     * 安全操作（name生成、factory创建）优先执行，不涉及 DiscipleTables。
     * ID 分配 + 组件表写入使用 [allocateAndInsert] 在最后一步原子完成，
     * 消灭 allocateNextId → insert 之间的悬空窗口。
     */
    fun recruitDisciple(realm: Int = 9): Disciple {
        val gender = if (rng.nextDouble() < 0.5) GENDER_MALE else GENDER_FEMALE

        val existingNames = (stateStore.discipleTables.assembleAll()
            + stateStore.gameData.value.recruitList)
            .map { it.name }.toSet()
        val nameResult = NameService.generateName(
            gender, NameService.NameStyle.FULL, existingNames
        )

        val rawDisciple = discipleFactory.create(
            DiscipleFactory.DiscipleSeed(
                id = "PENDING",  // 占位 ID，allocateAndInsert 会覆盖
                gender = gender,
                nameResult = nameResult,
                spiritRootType = SpiritRootGenerator.generate(rng.asKotlinRandom()),
                age = 16 + rng.nextInt(14),
                realm = realm,
                realmLayer = 1,
                social = com.xianxia.sect.core.model.SocialData(),
                nextInt = { from, until -> from + rng.nextInt(until - from) }
            )
        )

        // Set recruitment time
        val data = stateStore.gameData.value
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        rawDisciple.usage.recruitedMonth = currentMonthValue

        // 最后一步：原子分配 ID + 写入组件表 + 加入宗门日志（消灭悬空窗口）
        val realId = stateStore.updateAndReturn {
            val id = discipleTables.allocateAndInsert(rawDisciple)
            val intId = id.toIntOrNull()
            if (intId != null) {
                val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
                discipleTables.lifeEvents[intId] = events + "${rawDisciple.age}岁：加入宗门"
            }
            id
        }

        return rawDisciple.copy(id = realId)
    }

    /**
     * Expel disciple from sect
     */
    fun expelDisciple(discipleId: String): DomainResult<Unit> {
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

            // 仅清除装备/功法所有权，不返还仓库
            val expelEquipIds = mutableListOf<String>()
            discipleTables.weaponIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.armorIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.bootsIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.accessoryIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.storageBagItems[id].filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }.map { it.itemId }.forEach { expelEquipIds.add(it) }
            val expelManualIds = discipleTables.storageBagItems[id].filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }.map { it.itemId }.toSet() + discipleTables.manualIds[id].toSet()

            equipmentInstances = equipmentInstances.filter { it.id !in expelEquipIds }
            manualInstances = manualInstances.filter { it.id !in expelManualIds }

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
    fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit> = discipleMasterApprenticeService.apprenticeToMaster(discipleId, masterId)

    // ==================== 装备管理 ====================

    /**
     * Equip equipment to disciple
     * 设计意图：装备是独占物品，不可共用。一件装备只能给一名弟子穿戴。
     * 装备新装备时，旧装备自动卸下并放入弟子储物袋。
     */
    fun equipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> = discipleEquipmentService.equipEquipment(discipleId, equipmentId)

    /**
     * Unequip equipment from disciple
     * 设计意图：装备是独占物品，卸下后放入弟子储物袋，而非归还宗门仓库。
     *
     * 验证和卸下操作全部在 stateStore.update 事务内原子执行，返回实际操作结果。
     */
    fun unequipEquipment(discipleId: String, equipmentId: String): DomainResult<Unit> = discipleEquipmentService.unequipEquipment(discipleId, equipmentId)

    // ==================== 辅助方法 ====================

    /**
     * Clear disciple from all slots and assignments
     */
    fun clearDiscipleFromAllSlots(discipleId: String) = discipleSlotManager.clearDiscipleFromAllSlots(discipleId)

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
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean = discipleSlotManager.isDiscipleAssignedToSpiritMine(discipleId)

    /**
     * Get alive disciples count
     */
    fun getAliveDisciplesCount(): Int = discipleLifecycleManager.getAliveDisciplesCount()

    /**
     * Get disciples by status
     */
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> = discipleLifecycleManager.getDisciplesByStatus(status)

    /**
     * Get idle disciples
     */
    fun getIdleDisciples(): List<Disciple> = discipleLifecycleManager.getIdleDisciples()

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
    fun getDiscipleAggregate(discipleId: String): DiscipleAggregate? = discipleLifecycleManager.getDiscipleAggregate(discipleId)

    /**
     * 获取所有弟子的聚合数据列表
     *
     * 此方法为 [DiscipleAggregate] 多表架构的迁移桥梁。
     * 内部实现：从现有 [Disciple] 列表批量转换而来。
     *
     * @return 所有弟子的 DiscipleAggregate 列表
     */
    fun getAllDiscipleAggregates(): List<DiscipleAggregate> = discipleLifecycleManager.getAllDiscipleAggregates()

    /**
     * Update yearly salary enabled/disabled for a realm
     */
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) = discipleLifecycleManager.updateYearlySalaryEnabled(realm, enabled)
}

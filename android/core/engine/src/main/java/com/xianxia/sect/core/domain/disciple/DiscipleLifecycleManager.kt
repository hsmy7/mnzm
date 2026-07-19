package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.SpiritRootGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子生命周期管理服务。
 *
 * ## 职责
 * 1. **弟子 CRUD** — 新增/移除/更新/查询弟子
 * 2. **弟子日志** — 记录和查询生命周期事件
 * 3. **招募与逐出** — 招募新弟子、驱逐弟子出宗门
 * 4. **状态查询** — 查询弟子当前状态、按状态筛选
 * 5. **聚合查询** — [DiscipleAggregate] 多表迁移支持
 */
@Singleton
class DiscipleLifecycleManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: DiscipleFactory,
    private val rngManager: GameRngManager,
    private val slotManager: DiscipleSlotManager,
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
    private val currentDiscipleTables: DiscipleTables
        get() = stateStore.discipleTables

    companion object {
        private const val TAG = "DiscipleLifecycleManager"
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
        stateStore.update { discipleTables.insert(disciple) }
    }

    /**
     * Remove disciple by ID
     */
    fun removeDisciple(discipleId: String): DomainResult<Unit> {
        val id = discipleId.toIntOrNull()
            ?: return DomainResult.Failure(AppError.Domain.Disciple.NotFound(discipleId))
        return stateStore.updateAndReturn {
            if (discipleTables.ids.contains(id)) {
                discipleTables.remove(id)
                DomainResult.Success(Unit)
            } else {
                DomainResult.Failure(AppError.Domain.Disciple.NotFound(discipleId))
            }
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
        stateStore.update {
            if (discipleTables.ids.contains(id)) {
                discipleTables.remove(id)
                discipleTables.insert(disciple)
            }
        }
    }

    // ==================== 弟子日志 ====================

    /**
     * 为指定弟子追加一条日志事件。
     * 事件格式："xx岁：事件描述"。
     */
    fun addLifeEvent(discipleId: String, event: String) {
        val id = discipleId.toIntOrNull() ?: return
        stateStore.update {
            if (!discipleTables.ids.contains(id)) return@update
            val currentEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
            discipleTables.lifeEvents[id] = currentEvents + event
        }
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
        stateStore.update {
            if (!discipleTables.ids.contains(id)) return@update
            if (discipleTables.lifeEvents.getOrNull(id)?.isNotEmpty() == true) return@update

            val events = mutableListOf<String>()
            val age = discipleTables.ages[id]
            val currentAbsoluteMonth = gameData.gameYear * 12 + gameData.gameMonth
            val recruitedMonth = discipleTables.recruitedMonths.getOrDefault(id, 0)

            // 加入宗门
            if (recruitedMonth > 0 && currentAbsoluteMonth > recruitedMonth) {
                val monthsSince = currentAbsoluteMonth - recruitedMonth
                val recruitedAge = (age - monthsSince / 12).coerceAtLeast(1)
                events.add("${recruitedAge}岁：加入宗门")
            }

            // 拜师
            val masterId = discipleTables.masterIds.getOrNull(id)
            if (masterId != null) {
                val masterIdInt = masterId.toIntOrNull()
                val masterName = if (masterIdInt != null) discipleTables.names.getOrNull(masterIdInt) ?: "未知" else "未知"
                events.add("${age}岁：拜${masterName}为师")
            }

            // 道侣
            val partnerId = discipleTables.partnerIds.getOrNull(id)
            if (partnerId != null) {
                val partnerIdInt = partnerId.toIntOrNull()
                val partnerName = if (partnerIdInt != null) discipleTables.names.getOrNull(partnerIdInt) ?: "未知" else "未知"
                events.add("${age}岁：与${partnerName}结为道侣")
            }

            if (events.isNotEmpty()) {
                discipleTables.lifeEvents[id] = events
            }
        }
    }

    // ==================== 弟子状态查询 ====================

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

        if (data.spiritMineSlots.any { it.discipleId == discipleId }) {
            return DiscipleStatus.MINING
        }

        return DiscipleStatus.IDLE
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

    // ==================== 招募与逐出 ====================

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
                spiritRootType = SpiritRootGenerator.generate(),
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

            slotManager.clearDiscipleFromAllSlots(discipleId)

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

    // ==================== 杂项 ====================

    /**
     * Update yearly salary enabled/disabled for a realm
     */
    fun updateYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        stateStore.update {
            val newEnabled = gameData.yearlySalaryEnabled.toMutableMap()
            newEnabled[realm] = enabled
            gameData = gameData.copy(yearlySalaryEnabled = newEnabled)
        }
    }

    // ==================== 辅助方法 ====================

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
}

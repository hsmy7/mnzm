package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.guide.GuideCounterKeys
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
    private val discipleSlotManager: DiscipleSlotManager,
    private val discipleStatusService: DiscipleStatusService
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
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
     * 根据所有槽位分配同步所有存活弟子的状态。
     * 委托给 [DiscipleStatusService]。
     */
    fun syncAllDiscipleStatuses() = discipleStatusService.syncAllDiscipleStatuses()

    /**
     * 重置所有弟子为 IDLE 状态。
     * 委托给 [DiscipleStatusService]。
     */
    suspend fun resetAllDisciplesStatus() = discipleStatusService.resetAllDisciplesStatus()

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
            // 引导系统：累计招募弟子
            val prevCount = gameData.guideCounters[GuideCounterKeys.DISCIPLES_RECRUITED] ?: 0L
            gameData = gameData.copy(
                guideCounters = gameData.guideCounters + (GuideCounterKeys.DISCIPLES_RECRUITED to prevCount + 1),
                annualNewDisciples = gameData.annualNewDisciples + 1
            )
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

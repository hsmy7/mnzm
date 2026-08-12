package com.xianxia.sect.core.engine.domain.disciple

import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.CaveExplorationStatus
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.partnerId
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton



/**
 * 弟子生命周期管理服务。
 *
 * ## 职责
 * 1. **弟子 CRUD** — 新增/移除/更新/查询弟子
 * 2. **弟子日志** — 记录和查询生命周期事件
 * 3. **状态查询** — 查询弟子当前状态、按状态筛选
 * 4. **聚合查询** — [DiscipleAggregate] 多表迁移支持
 * 5. **槽位清理** — 清除弟子全部槽位分配（逐出/死亡路径委托）
 */
@Singleton
class DiscipleLifecycleManager @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: DiscipleFactory,
    private val rngManager: GameRngManager,
    private val slotManager: DiscipleSlotManager,
    private val productionSlotRepository: ProductionSlotRepository,
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
    private val currentDiscipleTables: DiscipleTables
        get() = stateStore.discipleTables

    companion object {
        private const val TAG = "DiscipleLifecycleManager"
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
        val status = tables.statuses[id]

        return DiscipleStatusService.deriveDiscipleStatus(
            isAlive = isAlive,
            currentStatus = status,
            slotFlags = DiscipleStatusService.buildSlotFlagsFor(
                discipleId = discipleId,
                data = data
            )
        )
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
     * Check if disciple is in cave exploration team
     */
    private fun _isInCaveExploration(discipleId: String): Boolean {
        val data = stateStore.gameData.value
        return data.caveExplorationTeams.any { team ->
            team.memberIds.contains(discipleId) &&
            (team.status == CaveExplorationStatus.TRAVELING || team.status == CaveExplorationStatus.EXPLORING)
        }
    }

    // ── 槽位管理委托（供 DiscipleService 统一入口） ────────────────
    fun clearDiscipleFromAllSlots(discipleId: String) = slotManager.clearDiscipleFromAllSlots(discipleId)
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean = slotManager.isDiscipleAssignedToSpiritMine(discipleId)

    suspend fun clearProductionSlots(protectedIds: Set<String>) {
        val allSlots = productionSlotRepository.getSlots()
        for (slot in allSlots) {
            if (slot.assignedDiscipleId != null && slot.assignedDiscipleId !in protectedIds && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId(slot.buildingId, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }
}

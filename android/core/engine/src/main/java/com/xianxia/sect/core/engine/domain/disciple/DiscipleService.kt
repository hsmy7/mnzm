package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.engine.system.materializeBagItemsToWarehouse


@GameService("DiscipleService")
@Singleton
class DiscipleService @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: DiscipleFactory,
    private val rngManager: GameRngManager,
    // 子服务（已提取的职责模块，用于未来深度重构）
    private val discipleEquipmentService: DiscipleEquipmentService,
    internal val discipleLifecycleManager: DiscipleLifecycleManager,
    private val discipleMasterApprenticeService: DiscipleMasterApprenticeService,
    private val discipleSlotManager: DiscipleSlotManager,
    private val discipleStatusService: DiscipleStatusService,
    // 放宽为 internal 供 DiscipleLifecycleNativeTx 逐出臂袋物品物化读取（三重防护惯例）
    internal val inventorySystem: InventorySystem
) {
    /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

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

    /**
     * 获取指定弟子的全部日志事件，按添加顺序排列。
     */
    fun getLifeEvents(discipleId: String): List<String> = discipleLifecycleManager.getLifeEvents(discipleId)

    /**
     * 根据弟子当前状态生成合成历史事件（仅当尚无日志时）。
     * 用于加载旧存档后首次查看日志。
     */
    fun initializeLifeEvents(discipleId: String) = discipleLifecycleManager.initializeLifeEvents(discipleId)

    /**
     * Get disciple status based on current assignments
     */
    fun getDiscipleStatus(discipleId: String): DiscipleStatus = discipleLifecycleManager.getDiscipleStatus(discipleId)

    /**
     * 根据所有槽位分配同步所有存活弟子的状态。
     * 委托给 [DiscipleStatusService]。
     */
    fun syncAllDiscipleStatuses() = discipleStatusService.syncAllDiscipleStatuses()

    /**
     * 根据单个弟子的槽位分配推导其状态并写入。
     * O(1) 推导，避免全量 O(n) 扫描。
     * 委托给 [DiscipleStatusService]。
     */
    fun syncSingleDiscipleStatus(discipleId: String) = discipleStatusService.syncSingleDiscipleStatus(discipleId)

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
        // 名字随机源分区化（batch-14b 拍板落地，AISectDiscipleManager/RecruitService
        // 同款先例）——原默认 Random.Default 非确定性、不入 rngStates，同 mapSeed
        // 新档初始弟子名字不可复现；传 SYSTEM 分区适配器后与性别/灵根/年龄/factory
        // 同流（与 C++ name_service.h generateName 分区语义同源），
        // 名字序列存档可重放。活跃调用方仅新档创建播种（createNewGame/restartGame）。
        val nameResult = NameService.generateName(
            gender, NameService.NameStyle.FULL, existingNames, rng.asKotlinRandom()
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
                nextInt = { from, until -> from + rng.nextInt(until - from) },
                random = rng.asKotlinRandom()
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

            // 逐出前袋物品物化回仓库（玩家保留，溢出自动转邮件）——
            // 独立存储后袋条目持有数据且随弟子删除，不物化即物品消失
            val expelBagItems = discipleTables.storageBagItems[id]
            if (expelBagItems.isNotEmpty()) {
                inventorySystem.withTrackingSource("disciple_expel") {
                    inventorySystem.materializeBagItemsToWarehouse(expelBagItems)
                }
            }

            // 仅清除穿着的装备/功法所有权，不返还仓库（实例销毁）
            val expelEquipIds = mutableListOf<String>()
            discipleTables.weaponIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.armorIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.bootsIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            discipleTables.accessoryIds[id].takeIf { it.isNotEmpty() }?.let { expelEquipIds.add(it) }
            val expelManualIds = discipleTables.manualIds[id].toSet()

            equipmentInstances = equipmentInstances.filter { it.id !in expelEquipIds }
            manualInstances = manualInstances.filter { it.id !in expelManualIds }

            // 弟子强化派生 map 统一收口（审计 P2-7/P3-4：原仅清
            // manualProficiencies，漏血炼三 map——逐出弟子血炼加成随之残留）
            eraseDiscipleDerivedMaps(discipleId)

            discipleTables.remove(id)

            // 年报脱离弟子计数
            gameData = gameData.copy(
                annualDesertedDisciples = gameData.annualDesertedDisciples + 1
            )

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
    fun apprenticeToMaster(discipleId: String,
        masterId: String): DomainResult<Unit> = discipleMasterApprenticeService.apprenticeToMaster(discipleId, masterId)

    // ==================== 装备管理 ====================

    /**
     * Equip equipment to disciple
     * 设计意图：装备是独占物品，不可共用。一件装备只能给一名弟子穿戴。
     * 装备新装备时，旧装备自动卸下并放入弟子储物袋。
     */
    fun equipEquipment(discipleId: String,
        equipmentId: String): DomainResult<Unit> = discipleEquipmentService.equipEquipment(discipleId, equipmentId)

    /**
     * Unequip equipment from disciple
     * 设计意图：装备是独占物品，卸下后放入弟子储物袋，而非归还宗门仓库。
     *
     * 验证和卸下操作全部在 stateStore.update 事务内原子执行，返回实际操作结果。
     */
    fun unequipEquipment(discipleId: String,
        equipmentId: String): DomainResult<Unit> = discipleEquipmentService.unequipEquipment(discipleId, equipmentId)

    // ==================== 辅助方法 ====================

    /**
     * Clear disciple from all slots and assignments
     */
    fun clearDiscipleFromAllSlots(discipleId: String) = discipleSlotManager.clearDiscipleFromAllSlots(discipleId)

    /**
     * Check if disciple is assigned to spirit mine
     */
    fun isDiscipleAssignedToSpiritMine(discipleId: String): Boolean = discipleSlotManager
        .isDiscipleAssignedToSpiritMine(discipleId)

    /**
     * Get alive disciples count
     */
}

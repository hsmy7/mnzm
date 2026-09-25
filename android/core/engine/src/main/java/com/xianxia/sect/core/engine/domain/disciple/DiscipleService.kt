package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.annotation.GameService
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.recruitedMonth
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition


@GameService("DiscipleService")
@Singleton
class DiscipleService @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: DiscipleFactory,
    private val rngManager: GameRngManager,
    // 子服务（已提取的职责模块，用于未来深度重构）
    private val discipleEquipmentService: DiscipleEquipmentService,
    internal val discipleLifecycleManager: DiscipleLifecycleManager,
    private val discipleSlotManager: DiscipleSlotManager,
    private val discipleStatusService: DiscipleStatusService,
    // 放宽为 internal 供同域扩展按需读取（stateStore 同款三重防护惯例）
    internal val inventorySystem: InventorySystem
) {
    /** 属性 roll 随机流走 SYSTEM 分区（与伴侣配对同类系统级随机） */
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

    companion object {
        /** 模板姓名首字即姓氏（[CharacterTemplateDb] 全部模板均为单字姓） */
        private const val SURNAME_PREFIX_LENGTH = 1
        /** 弟子日志：入门事件 */
        private const val LIFE_EVENT_JOINED_SECT = "加入宗门"
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
     * 事件格式：动作描述文本。
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

    // ==================== 模板弟子构造 ====================

    /**
     * 角色模板实例化：把一具具名角色模板落为一名在册弟子（弟子构造的唯一生产入口）。
     *
     * 身份字段（姓名 / 性别 / 灵根 / 立绘键 / 模板 id / 初始境界）全部取自
     * [CharacterTemplateDb]，**不经** [NameService.generateName] 与灵根随机生成；
     * 六维方差、悟性与技能仍由 [DiscipleFactory] 的既有确定性 roll 链产生。
     *
     * 可预期的业务失败以 sealed [DomainResult.Failure] 返回（不抛异常）：
     * - [AppError.Domain.Disciple.TemplateUnknown]：[templateId] 不在模板表中
     * - [AppError.Domain.Disciple.TemplateAlreadyOwned]：名册已有同模板弟子（限持 1）
     *
     * ID 分配 + 组件表写入 + 入门日志 + 引导计数 + 年度新增弟子计数在**同一次**
     * [GameStateStore.updateAndReturn] 事务内原子完成。
     *
     * @param templateId 角色模板 id
     * @return 成功时携带已入库的弟子（id 为实际分配值）
     */
    fun instantiateTemplate(templateId: String): DomainResult<Disciple> {
        val template = CharacterTemplateDb.byId(templateId)
            ?: return DomainResult.Failure(AppError.Domain.Disciple.TemplateUnknown(templateId))
        ownsTemplate(templateId)?.let { ownerId ->
            return DomainResult.Failure(
                AppError.Domain.Disciple.TemplateAlreadyOwned(templateId, ownerId)
            )
        }

        val rawDisciple = discipleFactory.create(
            DiscipleFactory.DiscipleSeed(
                id = "PENDING",  // 占位 ID，allocateAndInsert 会覆盖
                gender = template.gender,
                nameResult = NameService.NameResult(
                    surname = template.name.take(SURNAME_PREFIX_LENGTH), fullName = template.name
                ),
                spiritRootType = template.spiritRootType,
                realm = CharacterTemplateDb.STARTUP_REALM,
                realmLayer = CharacterTemplateDb.STARTUP_REALM_LAYER,
                nextInt = { from, until -> from + rng.nextInt(until - from) },
                templateId = template.id,
                portraitResOverride = template.portraitKey
            )
        )

        // 入门时间戳（当前游戏月序号）
        val data = stateStore.gameData.value
        rawDisciple.usage.recruitedMonth = data.gameYear * 12 + data.gameMonth

        val realId = stateStore.updateAndReturn { insertTemplateDisciple(rawDisciple) }
        return DomainResult.Success(rawDisciple.copy(id = realId))
    }

    /**
     * 限持判定：名册中已存在该模板的弟子实例时返回其 id，否则 null。
     * 只读 `templateIds` 列，不装配 Disciple 整对象。
     */
    private fun ownsTemplate(templateId: String): String? {
        val column = stateStore.discipleTables.templateIds
        val ownerId = column.ids().firstOrNull { column.getOrDefault(it, "") == templateId }
        return ownerId?.toString()
    }

    /**
     * 模板弟子落库（事务内）：原子分配 ID + 写组件表 + 「加入宗门」日志 +
     * 引导计数器（键名沿用 `disciplesRecruited`：入门即计数，改键会使旧档引导倒退）
     * + 年度新增弟子。
     */
    private fun MutableGameState.insertTemplateDisciple(disciple: Disciple): String {
        val id = discipleTables.allocateAndInsert(disciple)
        val intId = id.toIntOrNull()
        if (intId != null) {
            val events = discipleTables.lifeEvents.getOrDefault(intId, emptyList())
            discipleTables.lifeEvents[intId] = events + LIFE_EVENT_JOINED_SECT
        }
        val prevCount = gameData.guideCounters[GuideCounterKeys.DISCIPLES_RECRUITED] ?: 0L
        gameData = gameData.copy(
            guideCounters = gameData.guideCounters + (GuideCounterKeys.DISCIPLES_RECRUITED to prevCount + 1),
            annualNewDisciples = gameData.annualNewDisciples + 1
        )
        return id
    }

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
}

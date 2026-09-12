package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.ZoneCalculator
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatusService
import com.xianxia.sect.core.util.GameRngManager
import javax.inject.Inject
import javax.inject.Singleton






/**
 * 自动排班互斥决策（详见 buildOccupiedSlotDiscipleIds）：
 * 候选过滤走"status==IDLE（存储权威）+ 全槽位占用集合（第二层防御）"，
 * **不**注册 DiscipleAssignmentGate（confirmAssign 不会调用
 * gate.filterAvailableDisciples——注册会造成 gate 陈旧条目，validateAutoSlot
 * 清槽路径不 release；gate 一致性由读档 rebuildFromGameData 兜底，
 * UI 全走 status 过滤）。
 */
@Singleton
@GameService("ProductionProcessor")
class ProductionProcessor @Inject constructor(
    internal val stateStore: GameStateStore,
    internal val inventorySystem: InventorySystem,
    internal val productionCoordinator: ProductionCoordinator,
    internal val productionSlotRepository: ProductionSlotRepository,
    internal val formulaService: FormulaService,
    internal val rngManager: GameRngManager,
    internal val scopeProvider: CoroutineScopeProvider,
    internal val ioDispatcher: IoDispatcher,
    internal val inventoryConfig: com.xianxia.sect.core.config.InventoryConfig,
) {

    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "ProductionProcessor"
        /** 丹药品阶 roll 阈值（与月变路径 ProductionProcessor 保持一致） */
        internal const val PILL_GRADE_HIGH_THRESHOLD = 0.06
        internal const val PILL_GRADE_MEDIUM_THRESHOLD = 0.40
        internal const val SINGLE_RESIDENCE_SLOTS = 1
        internal const val MULTI_RESIDENCE_SLOTS = 4

        /** 灵田收获附带种子数量上限：0..HARVEST_SEED_MAX_GAIN 各 20%（均匀分布，nextInt(n+1)） */
        internal const val HARVEST_SEED_MAX_GAIN = 4
    }

    /** 灵田收获整轮共享的灵草/种子合并仓库（service 包内数据载体，命名遵守守卫后缀约定） */
    // ── 建筑生产 ──────────────────────────────────────────────────────

    internal class HarvestStoreContext(
        val herbs: StackableItemStore<Herb>,
        val seeds: StackableItemStore<Seed>
    )

    /**
     * 灵植成熟速度乘区（Herb Garden Maturity Zone）。
     *
     * 公式：有效生长时间 = ceil(baseGrowTime / ((1 + elderZone) × (1 + auraZone) × (1 + policyZone)))
     */
    data class HerbGardenMaturityZones(
        val elderZone: Double = 0.0,   // 灵植长老乘区
        val auraZone: Double = 0.0,    // 光环弟子乘区
        val policyZone: Double = 0.0,  // 灵药培育政策乘区
    ) {
        /** 计算总加速倍率（纯加成值，如 0.155 = 15.5%） */
        fun totalMultiplier(): Double =
            ZoneCalculator.calculate(1.0, elderZone, auraZone, policyZone) - 1.0
    }

    /**
     * 灵田收获全局加成上下文：与地块无关的加成只计算一次（性能优化——
     * 每块地重算 O(d)+O(b) 会在地块多时持锁阻塞 UI 线程）。
     *
     * @param auraByField 建筑 instanceId → 是否处于灵植阁光环内
     *        （null 表示光环值为 0，无需构建索引）
     */
    internal class HarvestMaturityContext(
        val elderZone: Double,
        val auraZone: Double,
        val policyZone: Double,
        private val auraByField: Map<String, Boolean>?
    ) {
        /** 单地块总加速倍率（O(1)，光环判定走预构建索引） */
        fun bonusFor(buildingInstanceId: String): Double {
            val aura = if (auraByField?.get(buildingInstanceId) == true) auraZone else 0.0
            return ZoneCalculator.calculate(1.0, elderZone, aura, policyZone) - 1.0
        }
    }

    /** 构建整轮收获的全局加成上下文（无长老且无光环弟子时跳过弟子表组装） */
}

/**
 * 重置守卫（顶层 internal 供测试调用真身）：
 * 结算时刻的异步槽位重置，仅当缓存槽仍处于"本次结算的炼制"才允许重置。
 *
 * 身份判别 = 状态 WORKING + completionMonth/recipeId 与结算快照一致。
 * 其余（IDLE 已取消/收获、COMPLETED 已手动收集、WORKING 但身份不同的新炼制）
 * 一律不动——防结算快照重建覆盖窗口内的玩家操作或排班启动的新炼制。
 *
 * 注意：completionMonth/recipeId 属可被存档篡改的字段——
 * 篡改使身份判别失效时，最坏情况是"重置被跳过 + 槽位保持 WORKING"，下月由
 * isSlotCompleteDynamic 重算再次结算。该字段对结算判定本身无影响（结算判定
 * 完全动态重算），守卫只承担防乱序覆盖职责，不承担防篡改职责。
 */
internal fun shouldResetSlotForCompletion(
    current: ProductionSlot,
    settled: ProductionSlot
): Boolean = current.status == ProductionSlotStatus.WORKING &&
    current.completionMonth == settled.completionMonth &&
    current.recipeId == settled.recipeId

/**
 * 全槽位占用弟子 ID 收集（月度自动排班互斥防线）。
 *
 * 扫描全部工作槽位：长老全槽位（含纳徒长老 recruitingElder）、生产镜像槽、
 * 灵矿/藏经阁/仓库驻守/巡视/玩家宗门驻守、战斗队伍、活跃任务、远古秘境
 * 探索成员（secretRealmState.exists 时）、洞穴探索队伍（仅活跃状态，
 * 与 [DiscipleStatusService.buildInTeamIds] 同状态条件）、血炼进度。
 *
 * 调用方 [ProductionProcessor.processAutoAssign] 以 status==IDLE 为第一层
 * 过滤（存储权威），本函数为第二层防御——覆盖同一事务内"分配后尚未
 * syncAllDiscipleStatuses"的陈旧状态窗口与推导缺口（如纳徒长老被推导为
 * IDLE），杜绝占用弟子被当作空闲捕获制造双槽位。
 *
 * @param data 当前游戏数据（含全部槽位字段）
 */
internal fun buildOccupiedSlotDiscipleIds(data: GameData): Set<String> = buildSet {
    addAll(collectElderSlotDiscipleIds(data.elderSlots))
    data.spiritMineSlots.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.librarySlots.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.warehouseGarrisons.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.patrolSlots.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.worldMapSects.find { it.isPlayerSect }?.garrisonSlots
        ?.filter { it.discipleId.isNotEmpty() }?.forEach { add(it.discipleId) }
    data.battleTeams.flatMap { it.slots }
        .filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.activeMissions.forEach { addAll(it.discipleIds) }
    if (data.secretRealmState.exists) {
        data.secretRealmSession.members.filter { !it.isDead }.forEach { add(it.discipleId) }
    }
    data.caveExplorationTeams.filter { it.status in DiscipleStatusService.caveExplorationStatuses }
        .forEach { addAll(it.memberIds) }
    data.activeBloodRefinements.values.filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
    data.productionSlots
        .mapNotNull { it.assignedDiscipleId?.takeIf { id -> id.isNotEmpty() } }
        .forEach { add(it) }
}

/**
 * 长老槽位全部占用弟子 ID 收集（10 个单槽字段 + 7 个亲传弟子列表字段）。
 * 显式清单 + 守卫测试（ElderSlotsStatusCoverageTest 反射双向校验）保证新增
 * 字段不遗漏——遗漏会导致该槽位弟子被自动排班当作空闲调动（双槽位根因）。
 */
internal fun collectElderSlotDiscipleIds(elderSlots: ElderSlots): Set<String> = buildSet {
    listOf(
        elderSlots.viceSectMaster, elderSlots.herbGardenElder, elderSlots.alchemyElder,
        elderSlots.forgeElder, elderSlots.outerElder, elderSlots.preachingElder,
        elderSlots.lawEnforcementElder, elderSlots.innerElder,
        elderSlots.qingyunPreachingElder, elderSlots.recruitingElder
    ).filter { it.isNotEmpty() }.forEach { add(it) }
    listOf(
        elderSlots.preachingMasters, elderSlots.lawEnforcementDisciples,
        elderSlots.qingyunPreachingMasters, elderSlots.herbGardenDisciples,
        elderSlots.alchemyDisciples, elderSlots.forgeDisciples,
        elderSlots.spiritMineDeaconDisciples
    ).flatten().filter { it.discipleId.isNotEmpty() }.forEach { add(it.discipleId) }
}

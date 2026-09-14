package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.nextEventSequenceId
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.exploration.LootCalculator
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats
import com.xianxia.sect.core.engine.domain.disciple.getPositionEffectBonus







/**
 * 执法/偷窃处理器 — 处理叛逃/偷窃检测及处罚。
 *
 * ## 职责
 * - 叛逃检测：月度检查忠诚度低于阈值的弟子，依概率触发捕获/逃脱
 * - **偷窃检测：道德变化即触发（反应式），月度兜底** — 不再纯百分比
 * - 偷窃金额基于弟子境界/身法/智力，非纯百分比
 * - 偷窃扩展到仓库物品（材料/丹药/装备/功法），等概率抽取
 * - 执法堂捕获率 + 仓库守卫纯智力判定
 * - 捕获处理：面壁反省
 * - 逃脱处理：清理装备/功法 + 移除
 *
 * @param stateStore 游戏状态存储
 * @param rngManager 确定性 RNG 管理器（SYSTEM 分区）
 * @param discipleLifecycleProcessor 弟子生命周期处理器
 * @param lootCalculator 掠夺计算器（复用物品扣除）
 */
@GameService("LawEnforcementProcessor")
@Singleton
class LawEnforcementProcessor @Inject constructor(
    internal val stateStore: GameStateStore,
    internal val rngManager: GameRngManager,
    internal val discipleLifecycleProcessor: DiscipleLifecycleProcessor,
    internal val lootCalculator: LootCalculator
) {
    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "LawEnforcementProc"

        /** 叛逃免疫状态：战斗/任务/血炼/思过/秘境中的弟子不参与叛逃判定（其余状态均随时可叛逃）。
         * 注意：WAREHOUSE_GARRISON 不在免疫集合——仓库驻守弟子可叛逃（与 GARRISONING 现状一致）。 */
        private val DESERTION_IMMUNE_STATUSES = setOf(
            DiscipleStatus.ON_MISSION,   // 任务中
            DiscipleStatus.REFLECTING,   // 思过中
            DiscipleStatus.REFINING,     // 血炼中
            DiscipleStatus.IN_TEAM,      // 队伍中（探索/战斗中）
            DiscipleStatus.SECRET_REALM  // 远古秘境中
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 公开 API
    // ══════════════════════════════════════════════════════════════════

    /**
     * 单弟子偷盗判定入口 —— 由道德变更点（5处）调用。
     *
     * 即时运行完整偷盗流程：条件检查 → 标记判定 → 偷盗概率 → 执法堂判定 → 仓库守卫判定 → 执行。
     * 三层限制：弟子年上限（每弟子每年1次）、月度上限（每月3名）、年度成功上限（年3次）。
     */
    fun processSingleDiscipleTheft(discipleId: Int) {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        if (!canDiscipleAttemptTheft(discipleId, tables, currentData)) return
        val currentMonth = currentData.gameYear * 12 + currentData.gameMonth
        // 标记判定：递增本月判定计数 + 标记弟子年判定（在事务内完成，避免写保护冲突）
        stateStore.update {
            gameData = gameData.copy(theftJudgementsThisMonth = gameData.theftJudgementsThisMonth + 1)
            discipleTables.lastTheftJudgementYears[discipleId] = gameData.gameYear
        }
        executeFullTheftCheck(discipleId, tables, currentMonth, currentData)
    }

    /**
     * 事务内版本 —— 供已在 [stateStore.update] 块内调用的钩子使用。
     * 直接修改 [state]，避免 ReentrantLock 重入写覆盖。
     */
    fun processSingleDiscipleTheft(discipleId: Int, state: MutableGameState) {
        if (state.gameData.spiritStones <= 0) return
        if (!canDiscipleAttemptTheft(discipleId, state.discipleTables, state.gameData)) return
        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        // 标记判定：弟子年上限 + 月度上限（直接修改 transactions 内 state）
        state.discipleTables.lastTheftJudgementYears[discipleId] = state.gameData.gameYear
        state.gameData = state.gameData.copy(theftJudgementsThisMonth = state.gameData.theftJudgementsThisMonth + 1)
        executeFullTheftCheckInTransaction(discipleId, state, currentMonth)
    }

    /** 前置条件检查。三条规则：弟子年上限 → 月度上限 → 年度成功上限 */
    private fun canDiscipleAttemptTheft(discipleId: Int, tables: DiscipleTables, data: GameData): Boolean {
        return passesTheftEligibilityGate(discipleId, tables, data) &&
            passesTheftRateLimits(discipleId, tables, data)
    }

    /** 资格门控：从众门控 + 存活/IDLE + 新弟子保护期 */
    private fun passesTheftEligibilityGate(discipleId: Int, tables: DiscipleTables, data: GameData): Boolean {
        // 从众门控：平均忠诚 ≥ 阈值时不偷盗
        if (!isAverageLoyaltyLowEnough(tables)) return false
        if (tables.isAlive.getOrDefault(discipleId, 0) != 1) return false
        if (tables.statuses.getOrDefault(discipleId, DiscipleStatus.IDLE) != DiscipleStatus.IDLE) return false
        val currentMonth = data.gameYear * 12 + data.gameMonth
        return (currentMonth - tables.recruitedMonths.getOrDefault(discipleId, 0)) >=
            GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
    }

    /** 频次上限：弟子年一次 → 每月至多 3 名 → 年度成功上限 */
    private fun passesTheftRateLimits(discipleId: Int, tables: DiscipleTables, data: GameData): Boolean {
        // 规则1：每个弟子每年最多判定一次
        if (tables.lastTheftJudgementYears.getOrDefault(discipleId, 0) == data.gameYear) return false
        // 规则2：每月最多判定3名弟子
        if (data.theftJudgementsThisMonth >= GameConfig.LawEnforcementConfig
            .MAX_THEFT_JUDGEMENTS_PER_MONTH) return false
        // 规则3：年度成功偷盗已达上限 → 全年停止判定
        return data.annualTheftCount < GameConfig.LawEnforcementConfig.MAX_THEFT_PER_YEAR
    }

    /** 计算捕获率：基于执法长老/弟子智力 + 政策加成。返回 [0.0, 1.0]。 */
    fun calculateCaptureRate(): Double {
        val data = stateStore.gameData.value
        val elderSlots = data.elderSlots
        val allDisciples = stateStore.disciples.value.associateBy { it.id }
        var captureRate = GameConfig.LawEnforcementConfig.BASE_CAPTURE_RATE
        elderSlots.lawEnforcementElder?.let { elderId ->
            if (elderId.isNotEmpty()) {
                allDisciples[elderId]?.let { elder ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(elder).intelligence - GameConfig
                        .LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    // 体质/词条的职务加成：作为乘算因子作用于长老职能效果
                    val posBonus = DiscipleStatCalculator.getPositionEffectBonus(elder, ElderSlotType.LAW_ENFORCEMENT)
                    captureRate += intelligenceAboveBase * GameConfig.LawEnforcementConfig
                        .ELDER_BONUS_PER_POINT * (1.0 + posBonus)
                }
            }
        }
        elderSlots.lawEnforcementDisciples.forEach { slot ->
            if (slot.discipleId.isNotEmpty()) {
                allDisciples[slot.discipleId]?.let { disciple ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(disciple).intelligence - GameConfig
                        .LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += (intelligenceAboveBase / GameConfig.LawEnforcementConfig
                        .DISCIPLE_INTELLIGENCE_STEP) * GameConfig.LawEnforcementConfig.DISCIPLE_BONUS_PER_STEP
                }
            }
        }
        if (data.sectPolicies.enhancedSecurity) {
            captureRate += GameConfig.PolicyConfig.ENHANCED_SECURITY_EFFECT
        }
        if (data.sectPolicies.rewardPunish) {
            captureRate += GameConfig.PolicyConfig.REWARD_PUNISH_EFFECT
        }
        return captureRate.coerceIn(0.0, 1.0)
    }

    /** 月度叛逃检测。 */
    fun processLawEnforcementMonthly() {
        val data = stateStore.gameData.value
        val tables = stateStore.discipleTables
        // 从众门控：平均忠诚 ≥ 阈值时不叛逃
        if (!isAverageLoyaltyLowEnough(tables)) return
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val threshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        val atRiskIds = findAtRiskDiscipleIds(currentMonthValue, threshold, protectionMonths, tables)
        for (id in atRiskIds) {
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= calcDesertionProbability(threshold,
                tables.loyalties.getOrDefault(id, 0))) continue
            enforceDiscipleDesertion(id, data.gameYear, captureRate, threshold, tables)
        }
    }

    /**
     * 月度偷盗兜底。
     *
     * 从道德 < 阈值且本年未判定的弟子中，选至多 [MAX_THEFT_JUDGEMENTS_PER_MONTH] 名
     * 进行完整偷盗判定。三层限制在 [canDiscipleAttemptTheft] 中统一检查。
     */
    fun processTheftMonthly() {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        // 从众门控：平均忠诚 ≥ 阈值时不偷盗
        if (!isAverageLoyaltyLowEnough(tables)) return
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val currentMonth = currentData.gameYear * 12 + currentData.gameMonth
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        val candidates = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold &&
                (currentMonth - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths &&
                tables.lastTheftJudgementYears.getOrDefault(id, 0) != currentData.gameYear
        }
        // 每月最多判定3名弟子（实际判定入口另有 caps 二次保证）
        for (id in candidates.take(GameConfig.LawEnforcementConfig.MAX_THEFT_JUDGEMENTS_PER_MONTH)) {
            processSingleDiscipleTheft(id)
        }
    }

    /**
     * 检查是否需要月度偷盗处理。
     * 每月初重置 [theftJudgementsThisMonth] 计数器。
     */
    fun processTheftIfNeeded() {
        // 月度判定计数器归零
        stateStore.update { gameData = gameData.copy(theftJudgementsThisMonth = 0) }
        val gd = stateStore.gameData.value
        if (gd.spiritStones <= 0) return
        if (gd.annualTheftCount >= GameConfig.LawEnforcementConfig.MAX_THEFT_PER_YEAR) return
        val tables = stateStore.discipleTables
        // 从众门控：平均忠诚 ≥ 阈值时不偷盗
        if (!isAverageLoyaltyLowEnough(tables)) return
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val currentMonth = gd.gameYear * 12 + gd.gameMonth
        val hasCandidate = tables.ids.any { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold &&
                tables.lastTheftJudgementYears.getOrDefault(id, 0) != gd.gameYear
        }
        if (!hasCandidate) return
        processTheftMonthly()
    }

    // ══════════════════════════════════════════════════════════════════
    // 核心偷盗流程
    // ══════════════════════════════════════════════════════════════════

    /**
     * 计算偷盗灵石金额（新公式）。
     *
     * 公式：
     *   baseAmount = THEFT_REALM_BASE_AMOUNTS[realmLevel]
     *   speedBonus = max(0, speed - 50) * 0.005
     *   intelBonus = max(0, intel - 50) * 0.003
     *   rawAmount = baseAmount × (1 + speedBonus + intelBonus) × 随机波动(±20%)
     *   stolenAmount = min(rawAmount, spiritStones × 10%), 下限 100
     */
    @Suppress("UnusedParameter") // currentData: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
    internal fun calcTheftAmount(disciple: Disciple, totalSpiritStones: Long): Long {
        if (totalSpiritStones <= 0) return 0L
        val cfg = GameConfig.LawEnforcementConfig
        val realmLevel = disciple.realm.coerceIn(1, 9)
        val baseAmount = cfg.THEFT_REALM_BASE_AMOUNTS[realmLevel] ?: 500L
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val speedBonus = (stats.speed - cfg.THEFT_SPEED_BASE).coerceAtLeast(0) * cfg.THEFT_SPEED_BONUS_PER_POINT
        val intelBonus = (stats.intelligence - cfg.THEFT_INTELLIGENCE_BASE).coerceAtLeast(0) * cfg
            .THEFT_INTELLIGENCE_BONUS_PER_POINT
        val rawAmount = baseAmount * (1.0 + speedBonus + intelBonus)
        val randomFactor = 0.8 + rngManager.getRng(RngPartition.SYSTEM).nextDouble() * 0.4
        val maxAmount = (totalSpiritStones * cfg.THEFT_MAX_RATIO_OF_TOTAL).toLong()
        return (rawAmount * randomFactor).toLong()
            .coerceIn(cfg.THEFT_MIN_AMOUNT, maxAmount)
    }

    /**
     * 计算弟子可偷物品（非事务版 —— 从 StateFlow 读取仓库）。
     */
    @Suppress("UnusedParameter") // currentData: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
    internal fun computeTheftItems(
        disciple: Disciple, gd: GameData, currentData: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>
    ): List<LootedItemEntry> = performWeightedItemSelection(disciple, gd, warehouses, garrisons,
        TheftInventorySnapshot(
            materials = stateStore.materials.value,
            pills = stateStore.pills.value,
            herbs = stateStore.herbs.value,
            seeds = stateStore.seeds.value,
            equipmentStacks = stateStore.equipmentStacks.value,
            manualStacks = stateStore.manualStacks.value
        )
    )

    /**
     * 计算弟子可偷物品（事务内版 —— 从 [state] 的 EntityStore 读取仓库）。
     */
    @Suppress("UnusedParameter") // currentData: 语义形参：签名与非事务版对称（调用点可读性优先），当前策略不消费
    internal fun computeTheftItems(
        disciple: Disciple, gd: GameData, currentData: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
        state: MutableGameState
    ): List<LootedItemEntry> = performWeightedItemSelection(disciple, gd, warehouses, garrisons,
        TheftInventorySnapshot(
            materials = state.materials.items,
            pills = state.pills.items,
            herbs = state.herbs.items,
            seeds = state.seeds.items,
            equipmentStacks = state.equipmentStacks.items,
            manualStacks = state.manualStacks.items
        )
    )

    /**
     * 加权物品选择（共享逻辑）。
     *
     * 1. 计算偷盗能力 = 境界基准 + 身法加成 + 智力加成
     * 2. 守卫减益：每个活跃守卫减 2 物品单位
     * 3. 所有仓库物品等概率抽取（无稀有度偏好）
     * 4. 均匀随机抽取
     */
    private fun performWeightedItemSelection(
        disciple: Disciple, gd: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
        inventory: TheftInventorySnapshot
    ): List<LootedItemEntry> {
        if (gd.spiritStones <= 0L) return emptyList()

        val cfg = GameConfig.LawEnforcementConfig
        val realmLevel = disciple.realm.coerceIn(1, 9)
        val baseAmount = cfg.THEFT_REALM_BASE_AMOUNTS[realmLevel] ?: 500L
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val speedUnits = ((stats.speed - cfg.THEFT_SPEED_BASE).coerceAtLeast(0) * cfg.THEFT_SPEED_BONUS_PER_POINT * cfg
            .THEFT_ITEM_UNIT_SPEED_FACTOR).toInt()
        val intelUnits = ((stats.intelligence - cfg.THEFT_INTELLIGENCE_BASE).coerceAtLeast(0) * cfg
            .THEFT_INTELLIGENCE_BONUS_PER_POINT * cfg.THEFT_ITEM_UNIT_INTEL_FACTOR).toInt()

        val activeGuardCount = warehouses.count { w ->
            garrisons.any { it.buildingInstanceId == w.instanceId && it.isActive }
        }
        val capacity = (baseAmount / cfg.THEFT_ITEM_BASE_DIVISOR).toInt() + speedUnits + intelUnits
        val finalCount = (capacity - activeGuardCount * cfg.THEFT_ITEM_GUARD_REDUCTION).coerceAtLeast(1)

        // 构建加权物品池
        data class Entry(val type: String, val id: String, val name: String, val rarity: Int)
        val entries = mutableListOf<Entry>()
        fun add(items: List<*>, type: String, nameFn: (Any) -> String, idFn: (Any) -> String, rarityFn: (Any) -> Int,
            qtyFn: (Any) -> Int) {
            items.forEach { item ->
                if (item != null) {
                    repeat(qtyFn(item).coerceAtLeast(0)) { entries.add(Entry(type, idFn(item), nameFn(item),
                        rarityFn(item))) }
                }
            }
        }
        add(inventory.materials, "material", { (it as Material).name }, { (it as Material).id },
            { (it as Material).rarity }, { (it as Material).quantity })
        add(inventory.pills, "pill", { (it as Pill).name }, { (it as Pill).id }, { (it as Pill).rarity },
            { (it as Pill).quantity })
        add(inventory.herbs, "herb", { (it as Herb).name }, { (it as Herb).id }, { (it as Herb).rarity },
            { (it as Herb).quantity })
        add(inventory.seeds, "seed", { (it as Seed).name }, { (it as Seed).id }, { (it as Seed).rarity },
            { (it as Seed).quantity })
        add(inventory.equipmentStacks, "equipment", { (it as EquipmentStack).name }, { (it as EquipmentStack).id },
            { (it as EquipmentStack).rarity }, { (it as EquipmentStack).quantity })
        add(inventory.manualStacks, "manual", { (it as ManualStack).name }, { (it as ManualStack).id },
            { (it as ManualStack).rarity }, { (it as ManualStack).quantity })

        if (entries.isEmpty()) return emptyList()

        /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
        val rng = rngManager.getRng(RngPartition.SYSTEM)
        val pool = entries.toMutableList()
        val picked = mutableListOf<Entry>()
        repeat(finalCount.coerceAtMost(pool.size)) {
            if (pool.isEmpty()) return@repeat
            picked.add(pool.removeAt(rng.nextInt(pool.size)))
        }
        return picked.groupBy { it.id to it.type }.map { (_, list) -> val f = list.first(); LootedItemEntry(f.id,
            f.name, f.type, f.rarity, list.size) }
    }

    /** 偷盗目标仓库快照（performWeightedItemSelection 参数分组）：六类可偷物品列表 */
    private data class TheftInventorySnapshot(
        val materials: List<Material>,
        val pills: List<Pill>,
        val herbs: List<Herb>,
        val seeds: List<Seed>,
        val equipmentStacks: List<EquipmentStack>,
        val manualStacks: List<ManualStack>
    )

    /** 偷盗物品的临时记录。 */
    data class LootedItemEntry(
        val id: String, val name: String,
        val type: String, val rarity: Int, val count: Int
    )

    // ══════════════════════════════════════════════════════════════════
    // 叛逃 / 面壁 （原逻辑保持不动）
    // ══════════════════════════════════════════════════════════════════

    private fun findAtRiskDiscipleIds(currentMonthValue: Int, threshold: Int, protectionMonths: Int,
        tables: DiscipleTables): List<Int> {
        return tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) !in DESERTION_IMMUNE_STATUSES &&
                tables.loyalties.getOrDefault(id, 0) < threshold &&
                (currentMonthValue - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths
        }
    }

    private fun calcDesertionProbability(threshold: Int, loyal: Int): Double {
        return ((threshold - loyal) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0,
            GameConfig.LawEnforcementConfig.MAX_PROB)
    }

    /**
     * 从众门控：计算所有活弟子的平均忠诚度，判断是否低于阈值。
     * 平均忠诚 ≥ [HERD_LOYALTY_THRESHOLD] 时，宗门风气好，不愿叛逃/偷盗。
     * 只能在 [stateStore.update] 块内或 [MutableGameState] 上下文使用，
     * 因为 [tables] 必须从事务内获取。
     */
    private fun isAverageLoyaltyLowEnough(tables: DiscipleTables): Boolean {
        val aliveIds = tables.ids.filter { tables.isAlive[it] == 1 }
        if (aliveIds.isEmpty()) return false
        val sum = aliveIds.sumOf { tables.loyalties.getOrDefault(it, 0) }
        val average = sum / aliveIds.size
        return average < GameConfig.LawEnforcementConfig.HERD_LOYALTY_THRESHOLD
    }

    private fun enforceDiscipleDesertion(id: Int, currentYear: Int, captureRate: Double, threshold: Int,
        tables: DiscipleTables) {
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate) {
            captureDiscipleForReflection(id, currentYear)
        } else {
            escapeDiscipleWithCleanup(id, threshold, tables)
        }
    }

    private fun escapeDiscipleWithCleanup(id: Int, threshold: Int, tables: DiscipleTables) {
        if (tables.loyalties.getOrDefault(id, 0) >= threshold) return
        val snapshot = tables.assemble(id) ?: return
        desertDiscipleCleanup(id, threshold, snapshot)
    }

    /** 事务内添加事件记录 —— 直接修改 [state]，不含独立的 stateStore.update。 */
    internal fun addEventRecord(state: MutableGameState, category: String, type: String, summary: String,
        relatedEntityId: String, relatedEntityName: String) {
        val records = state.gameData.gameEventRecords.toMutableList()
        // 追加序号分配（与 MutableGameState.recordGameEvent 一致）
        val nextSeq = nextEventSequenceId(records)
        records.add(GameEventRecord(
            timestamp = System.currentTimeMillis(),
            year = state.gameData.gameYear,
            month = state.gameData.gameMonth,
            phase = state.gameData.gamePhase,
            category = category,
            eventType = type,
            summary = summary,
            relatedEntityId = relatedEntityId,
            relatedEntityName = relatedEntityName,
            sequenceId = nextSeq
        ))
        state.gameData = state.gameData.copy(gameEventRecords = if (records.size > 200) records
            .takeLast(200) else records)
    }
}

/** 偷盗被捕：置思过状态（起止年写入）；返回 false 表示弟子 id 非法（调用方按未处理短路）。 */
internal fun markTheftCaught(
    disciple: Disciple,
    tables: DiscipleTables,
    currentData: GameData
): Boolean {
    val cid = disciple.id.toIntOrNull() ?: return false
    if (tables.ids.contains(cid) && tables.isAlive[cid] == 1) {
        tables.statuses[cid] = DiscipleStatus.REFLECTING
        tables.statusData[cid] = (tables.statusData.getOrNull(cid) ?: emptyMap()) + mapOf(
            "reflectionStartYear" to currentData.gameYear.toString(),
            "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS)
                .toString()
        )
    }
    return true
}

package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_EQUIPMENT_INSTANCE
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_EQUIPMENT_STACK
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_INSTANCE
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_STACK
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.exploration.LootCalculator
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * 执法/偷窃处理器 — 处理叛逃/偷窃检测及处罚。
 *
 * ## 职责
 * - 叛逃检测：月度检查忠诚度低于阈值的弟子，依概率触发捕获/逃脱
 * - **偷窃检测：道德变化即触发（反应式），月度兜底** — 不再纯百分比
 * - 偷窃金额基于弟子境界/身法/智力，非纯百分比
 * - 偷窃扩展到仓库物品（材料/丹药/装备/功法），等概率抽取
 * - 隐匿 vs 感知判定层（Sigmoid），战力对抗保留
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
    private val stateStore: GameStateStore,
    private val rngManager: GameRngManager,
    private val discipleLifecycleProcessor: DiscipleLifecycleProcessor,
    private val lootCalculator: LootCalculator
) {
    companion object {
        private const val TAG = "LawEnforcementProc"
    }

    // ══════════════════════════════════════════════════════════════════
    // 公开 API
    // ══════════════════════════════════════════════════════════════════

    /**
     * 单弟子偷盗判定入口 —— 由道德变更点（5处）调用。
     *
     * 即时运行完整偷盗流程：条件检查 → 概率 → 隐匿 → 守卫对抗 → 执行。
     * 保留 lastTheftMonth 冷却（≥12月），防止同一弟子频繁触发。
     */
    fun processSingleDiscipleTheft(discipleId: Int) {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        if (!canDiscipleAttemptTheft(discipleId, tables, currentData)) return
        val currentMonth = currentData.gameYear * 12 + currentData.gameMonth
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
        executeFullTheftCheckInTransaction(discipleId, state, currentMonth)
    }

    /** 前置条件检查。 */
    private fun canDiscipleAttemptTheft(discipleId: Int, tables: DiscipleTables, data: GameData): Boolean {
        if (tables.isAlive.getOrDefault(discipleId, 0) != 1) return false
        if (tables.statuses.getOrDefault(discipleId, DiscipleStatus.IDLE) != DiscipleStatus.IDLE) return false
        val currentMonth = data.gameYear * 12 + data.gameMonth
        if ((currentMonth - tables.recruitedMonths.getOrDefault(discipleId, 0)) <
            GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS) return false
        if (data.annualTheftCount >= GameConfig.LawEnforcementConfig.MAX_THEFT_PER_YEAR) return false
        return true
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
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(elder).intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += intelligenceAboveBase * GameConfig.LawEnforcementConfig.ELDER_BONUS_PER_POINT
                }
            }
        }
        elderSlots.lawEnforcementDisciples.forEach { slot ->
            if (slot.discipleId.isNotEmpty()) {
                allDisciples[slot.discipleId]?.let { disciple ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(disciple).intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += (intelligenceAboveBase / GameConfig.LawEnforcementConfig.DISCIPLE_INTELLIGENCE_STEP) * GameConfig.LawEnforcementConfig.DISCIPLE_BONUS_PER_STEP
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

    /** 月度叛逃检测（原逻辑保持不动）。 */
    fun processLawEnforcementMonthly() {
        val data = stateStore.gameData.value
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth
        val tables = stateStore.discipleTables
        val threshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        val atRiskIds = findAtRiskDiscipleIds(currentMonthValue, threshold, protectionMonths, tables)
        for (id in atRiskIds) {
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= calcDesertionProbability(threshold, tables.loyalties.getOrDefault(id, 0))) continue
            enforceDiscipleDesertion(id, data.gameYear, captureRate, threshold, tables)
        }
    }

    /**
     * 月度偷盗兜底（弱化版）。
     *
     * 仅在以下情况触发：
     * - 道德 < 阈值且 lastTheftMonth >= 12月前（未被反应式钩子捕获的漏网之鱼）
     * - 概率减半（正常偷盗概率 / 2），避免与反应式触发重复
     */
    fun processTheftMonthly() {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val currentMonth = currentData.gameYear * 12 + currentData.gameMonth
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        if (currentData.annualTheftCount >= GameConfig.LawEnforcementConfig.MAX_THEFT_PER_YEAR) return
        val unmatchedIds = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold &&
                (currentMonth - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths
        }
        for (id in unmatchedIds) {
            executeFullTheftCheck(id, tables, currentMonth, currentData)
        }
    }

    /**
     * 检查是否需要月度偷盗处理（弱化版兜底）。
     * 仅在年上限未满且存在道德<30且空闲的弟子时执行。
     */
    fun processTheftIfNeeded() {
        val gd = stateStore.gameData.value
        if (gd.spiritStones <= 0) return
        if (gd.annualTheftCount >= GameConfig.LawEnforcementConfig.MAX_THEFT_PER_YEAR) return
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val currentMonth = gd.gameYear * 12 + gd.gameMonth
        val hasCandidate = tables.ids.any { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold
        }
        if (!hasCandidate) return
        processTheftMonthly()
    }

    // ══════════════════════════════════════════════════════════════════
    // 核心偷盗流程
    // ══════════════════════════════════════════════════════════════════

    /**
     * 对单个弟子执行完整偷盗检查。
     * 流程：偷盗概率 → 隐匿判定 → 守卫对抗 → 执行偷窃（灵石+物品）。
     */
    private fun executeFullTheftCheck(id: Int, tables: DiscipleTables, currentMonth: Int, currentData: GameData) {
        val disciple = tables.assemble(id) ?: return
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val captureRate = calculateCaptureRate()
        val warehouses = currentData.placedBuildings.filter { it.displayName == "仓库" }
        val garrisons = currentData.warehouseGarrisons

        // Step 1: 偷盗概率判定
        val theftProb = ((moralThreshold - stats.morality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT)
            .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
        val effectiveTheftProb = if (currentData.sectPolicies.curfew) {
            theftProb * (1.0 - GameConfig.PolicyConfig.CURFEW_EVENT_REDUCTION)
        } else theftProb
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= effectiveTheftProb) return

        // Step 2: 隐匿判定 + 守卫对抗
        val detected = tryStealthDetection(disciple, warehouses, garrisons)
        val caught = if (detected) {
            tryGuardCatch(disciple, warehouses, garrisons, captureRate)
        } else {
            false // 未被发现 → 偷窃直接成功
        }

        if (caught) {
            // 被捕 → 面壁
            stateStore.update {
                val cid = disciple.id.toIntOrNull() ?: return@update
                if (discipleTables.ids.contains(cid) && discipleTables.isAlive[cid] == 1) {
                    discipleTables.statuses[cid] = DiscipleStatus.REFLECTING
                    val existingData = discipleTables.statusData[cid]
                    discipleTables.statusData[cid] = existingData + mapOf(
                        "reflectionStartYear" to currentData.gameYear.toString(),
                        "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
                    )
                }
                addEventRecord(this, "SECT", "theft_caught", "${disciple.name}偷盗被捕", disciple.id, disciple.name)
            }
            return
        }

        // Step 3: 偷窃成功 → 执行（灵石 + 物品）
        executeSuccessfulTheft(disciple, id, tables, currentData, warehouses, garrisons)

        // Step 4: 偷盗后叛逃判定（仅看忠诚）
        val desertionProb = ((loyalThreshold - stats.loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT)
            .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < desertionProb) {
            processTheftDesertionCleanup(setOf(id), tables, loyalThreshold)
        }
    }

    /**
     * 事务内完整偷盗检查 —— 直接修改 [state]，不调用 stateStore.update。
     */
    private fun executeFullTheftCheckInTransaction(id: Int, state: MutableGameState, currentMonth: Int) {
        val tables = state.discipleTables
        val disciple = tables.assemble(id) ?: return
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val currentData = state.gameData
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val captureRate = calculateCaptureRate()
        val warehouses = currentData.placedBuildings.filter { it.displayName == "仓库" }
        val garrisons = currentData.warehouseGarrisons

        // Step 1: 偷盗概率判定
        val theftProb = ((moralThreshold - stats.morality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT)
            .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
        val effectiveTheftProb = if (currentData.sectPolicies.curfew) {
            theftProb * (1.0 - GameConfig.PolicyConfig.CURFEW_EVENT_REDUCTION)
        } else theftProb
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= effectiveTheftProb) return

        // Step 2: 隐匿判定 + 守卫对抗（传入 state 从事务内读取守卫数据）
        val detected = tryStealthDetection(disciple, warehouses, garrisons, state)
        val caught = if (detected) {
            tryGuardCatch(disciple, warehouses, garrisons, captureRate)
        } else false

        if (caught) {
            val cid = disciple.id.toIntOrNull() ?: return
            if (tables.ids.contains(cid) && tables.isAlive[cid] == 1) {
                tables.statuses[cid] = DiscipleStatus.REFLECTING
                val existingData = tables.statusData[cid]
                tables.statusData[cid] = existingData + mapOf(
                    "reflectionStartYear" to currentData.gameYear.toString(),
                    "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
                )
            }
            state.recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_CAUGHT,
                "${disciple.name}偷盗被捕", disciple.id, disciple.name)
            return
        }

        // Step 3: 偷窃成功
        executeSuccessfulTheftInTransaction(disciple, id, tables, currentData, warehouses, garrisons, state)

        // Step 4: 偷盗后叛逃判定
        val desertionProb = ((loyalThreshold - stats.loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT)
            .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < desertionProb) {
            processTheftDesertionCleanupInTransaction(setOf(id), tables, loyalThreshold, state)
        }
    }

    /**
     * 事务内成功偷窃 —— 直接修改 [state]。
     */
    private fun executeSuccessfulTheftInTransaction(
        disciple: Disciple, id: Int, tables: DiscipleTables,
        currentData: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
        state: MutableGameState
    ) {
        val stolenAmount = calcTheftAmount(disciple, currentData.spiritStones)
        if (stolenAmount <= 0L && currentData.spiritStones <= 0) return

        // 扣除宗门灵石
        if (stolenAmount > 0L) {
            state.gameData = state.gameData.copy(spiritStones = (state.gameData.spiritStones - stolenAmount).coerceAtLeast(0))
        }

        // 物品偷窃（事务内版本：从 state 的 EntityStore 读取仓库，而非 stateStore StateFlow）
        val stolenItems = buildList {
            addAll(computeTheftItems(disciple, currentData, currentData, warehouses, garrisons, state))
        }

        // 通过 LootCalculator 扣除仓库物品
        if (stolenItems.isNotEmpty()) {
            val lootData = LootCalculator.BeastLootData(
                stolenItems = stolenItems.map { LootCalculator.LootedItem(it.id, it.name, it.type, it.rarity, it.count) }
            )
            lootCalculator.applyLoot(state, lootData)
        }

        // 更新弟子储物袋（无容量上限）
        val existing = tables.assembleAll().firstOrNull { it.id == disciple.id } ?: return
        val itemEntries = stolenItems.map { item ->
            StorageBagItem(
                itemId = item.id, itemType = item.type, name = item.name,
                rarity = item.rarity, quantity = item.count,
                obtainedYear = state.gameData.gameYear, obtainedMonth = state.gameData.gameMonth
            )
        }
        tables.update(existing.copy(
            equipment = existing.equipment.copy(
                storageBagSpiritStones = existing.equipment.storageBagSpiritStones + stolenAmount,
                storageBagItems = existing.equipment.storageBagItems + itemEntries
            )
        ))

        // 事件记录
        val itemSummary = if (stolenItems.isNotEmpty()) "（含${stolenItems.size}种物品）" else ""
        state.recordGameEvent(GameEventCategory.SECT, GameEventType.WAREHOUSE_THEFT,
            "宗门仓库被盗，损失${stolenAmount}灵石${itemSummary}")
        // 年度偷盗计数递增
        state.gameData = state.gameData.copy(annualTheftCount = state.gameData.annualTheftCount + 1)
    }

    /**
     * 事务内偷盗后叛逃清理 —— 直接修改 [state]。
     */
    private fun processTheftDesertionCleanupInTransaction(thiefIds: Set<Int>, tables: DiscipleTables, loyalThreshold: Int, state: MutableGameState) {
        if (thiefIds.isEmpty()) return
        for (thiefId in thiefIds) {
            if (tables.loyalties.getOrDefault(thiefId, 0) >= loyalThreshold) continue
            val snapshot = tables.assemble(thiefId) ?: continue
            val equipIds = mutableListOf<String>()
            snapshot.equipment.weaponId?.let { equipIds.add(it) }
            snapshot.equipment.armorId?.let { equipIds.add(it) }
            snapshot.equipment.bootsId?.let { equipIds.add(it) }
            snapshot.equipment.accessoryId?.let { equipIds.add(it) }
            val manualIds = snapshot.manualIds.toSet()
            discipleLifecycleProcessor.clearDiscipleFromAllSlots(thiefId.toString())
            state.equipmentInstances.setItems(state.equipmentInstances.all().filter { it.id !in equipIds })
            state.manualInstances.setItems(state.manualInstances.all().filter { it.id !in manualIds })
            tables.remove(thiefId)
            state.gameData = state.gameData.copy(
                annualDesertedDisciples = state.gameData.annualDesertedDisciples + 1
            )
            state.recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_DESERTION,
                "${snapshot.name}偷盗后叛逃", thiefId.toString(), snapshot.name)
        }
    }
    private fun executeSuccessfulTheft(
        disciple: Disciple, id: Int, tables: DiscipleTables,
        currentData: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>
    ) {
        // 灵石偷窃（新公式）
        val stolenAmount = calcTheftAmount(disciple, currentData.spiritStones)
        if (stolenAmount <= 0L && currentData.spiritStones <= 0) return

        stateStore.update {
            // 扣除宗门灵石
            if (stolenAmount > 0L) {
                gameData = gameData.copy(spiritStones = (gameData.spiritStones - stolenAmount).coerceAtLeast(0))
            }

            // 物品偷窃
            val stolenItems = buildList {
                addAll(computeTheftItems(disciple, currentData, currentData, warehouses, garrisons))
            }

            // 通过 LootCalculator 扣除仓库物品
            if (stolenItems.isNotEmpty()) {
                val lootData = LootCalculator.BeastLootData(
                    stolenItems = stolenItems.map { (id, name, type, rarity, count) ->
                        LootCalculator.LootedItem(id, name, type, rarity, count)
                    }
                )
                lootCalculator.applyLoot(this, lootData)
            }

            // 更新弟子储物袋
            val existing = discipleTables.assembleAll().firstOrNull { it.id == disciple.id } ?: return@update
            val itemEntries = stolenItems.map { item ->
                StorageBagItem(
                    itemId = item.id, itemType = item.type, name = item.name,
                    rarity = item.rarity, quantity = item.count,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth
                )
            }
            discipleTables.update(existing.copy(
                equipment = existing.equipment.copy(
                    storageBagSpiritStones = existing.equipment.storageBagSpiritStones + stolenAmount,
                    storageBagItems = existing.equipment.storageBagItems + itemEntries
                )
            ))

            // 事件记录（含物品详情）
            val itemSummary = if (stolenItems.isNotEmpty()) {
                "（含${stolenItems.size}种物品）"
            } else ""
            addEventRecord(this, "SECT", "warehouse_theft",
                "宗门仓库被盗，损失${stolenAmount}灵石${itemSummary}", "", "")
            // 年度偷盗计数递增
            gameData = gameData.copy(annualTheftCount = gameData.annualTheftCount + 1)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 新公式：偷盗金额计算
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
    private fun calcTheftAmount(disciple: Disciple, totalSpiritStones: Long): Long {
        if (totalSpiritStones <= 0) return 0L
        val cfg = GameConfig.LawEnforcementConfig
        val realmLevel = disciple.realm.coerceIn(1, 9)
        val baseAmount = cfg.THEFT_REALM_BASE_AMOUNTS[realmLevel] ?: 500L
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val speedBonus = (stats.speed - cfg.THEFT_SPEED_BASE).coerceAtLeast(0) * cfg.THEFT_SPEED_BONUS_PER_POINT
        val intelBonus = (stats.intelligence - cfg.THEFT_INTELLIGENCE_BASE).coerceAtLeast(0) * cfg.THEFT_INTELLIGENCE_BONUS_PER_POINT
        val rawAmount = baseAmount * (1.0 + speedBonus + intelBonus)
        val randomFactor = 0.8 + rngManager.getRng(RngPartition.SYSTEM).nextDouble() * 0.4
        val maxAmount = (totalSpiritStones * cfg.THEFT_MAX_RATIO_OF_TOTAL).toLong()
        return (rawAmount * randomFactor).toLong()
            .coerceIn(cfg.THEFT_MIN_AMOUNT, maxAmount)
    }

    /**
     * 计算弟子可偷物品（非事务版 —— 从 StateFlow 读取仓库）。
     */
    private fun computeTheftItems(
        disciple: Disciple, gd: GameData, currentData: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>
    ): List<LootedItemEntry> = performWeightedItemSelection(disciple, gd, warehouses, garrisons,
        stateStore.materials.value,
        stateStore.pills.value,
        stateStore.herbs.value,
        stateStore.seeds.value,
        stateStore.equipmentStacks.value,
        stateStore.manualStacks.value
    )

    /**
     * 计算弟子可偷物品（事务内版 —— 从 [state] 的 EntityStore 读取仓库）。
     */
    private fun computeTheftItems(
        disciple: Disciple, gd: GameData, currentData: GameData,
        warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
        state: MutableGameState
    ): List<LootedItemEntry> = performWeightedItemSelection(disciple, gd, warehouses, garrisons,
        state.materials.items,
        state.pills.items,
        state.herbs.items,
        state.seeds.items,
        state.equipmentStacks.items,
        state.manualStacks.items
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
        materials: List<Material>, pills: List<Pill>,
        herbs: List<Herb>, seeds: List<Seed>,
        equipmentStacks: List<EquipmentStack>, manualStacks: List<ManualStack>
    ): List<LootedItemEntry> {
        if (gd.spiritStones <= 0L) return emptyList()

        val cfg = GameConfig.LawEnforcementConfig
        val realmLevel = disciple.realm.coerceIn(1, 9)
        val baseAmount = cfg.THEFT_REALM_BASE_AMOUNTS[realmLevel] ?: 500L
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val speedUnits = ((stats.speed - cfg.THEFT_SPEED_BASE).coerceAtLeast(0) * cfg.THEFT_SPEED_BONUS_PER_POINT * cfg.THEFT_ITEM_UNIT_SPEED_FACTOR).toInt()
        val intelUnits = ((stats.intelligence - cfg.THEFT_INTELLIGENCE_BASE).coerceAtLeast(0) * cfg.THEFT_INTELLIGENCE_BONUS_PER_POINT * cfg.THEFT_ITEM_UNIT_INTEL_FACTOR).toInt()

        val activeGuardCount = warehouses.count { w ->
            garrisons.any { it.buildingInstanceId == w.instanceId && it.isActive }
        }
        val capacity = (baseAmount / cfg.THEFT_ITEM_BASE_DIVISOR).toInt() + speedUnits + intelUnits
        val finalCount = (capacity - activeGuardCount * cfg.THEFT_ITEM_GUARD_REDUCTION).coerceAtLeast(1)

        // 构建加权物品池
        data class Entry(val type: String, val id: String, val name: String, val rarity: Int)
        val entries = mutableListOf<Entry>()
        fun add(items: List<*>, type: String, nameFn: (Any) -> String, idFn: (Any) -> String, rarityFn: (Any) -> Int, qtyFn: (Any) -> Int) {
            items.forEach { item ->
                if (item != null) {
                    repeat(qtyFn(item).coerceAtLeast(0)) { entries.add(Entry(type, idFn(item), nameFn(item), rarityFn(item))) }
                }
            }
        }
        add(materials, "material", { (it as Material).name }, { (it as Material).id }, { (it as Material).rarity }, { (it as Material).quantity })
        add(pills, "pill", { (it as Pill).name }, { (it as Pill).id }, { (it as Pill).rarity }, { (it as Pill).quantity })
        add(herbs, "herb", { (it as Herb).name }, { (it as Herb).id }, { (it as Herb).rarity }, { (it as Herb).quantity })
        add(seeds, "seed", { (it as Seed).name }, { (it as Seed).id }, { (it as Seed).rarity }, { (it as Seed).quantity })
        add(equipmentStacks, "equipment", { (it as EquipmentStack).name }, { (it as EquipmentStack).id }, { (it as EquipmentStack).rarity }, { (it as EquipmentStack).quantity })
        add(manualStacks, "manual", { (it as ManualStack).name }, { (it as ManualStack).id }, { (it as ManualStack).rarity }, { (it as ManualStack).quantity })

        if (entries.isEmpty()) return emptyList()

        val rng = rngManager.getRng(RngPartition.SYSTEM)
        val pool = entries.toMutableList()
        val picked = mutableListOf<Entry>()
        repeat(finalCount.coerceAtMost(pool.size)) {
            if (pool.isEmpty()) return@repeat
            picked.add(pool.removeAt(rng.nextInt(pool.size)))
        }
        return picked.groupBy { it.id to it.type }.map { (_, list) -> val f = list.first(); LootedItemEntry(f.id, f.name, f.type, f.rarity, list.size) }
    }

    /** 偷盗物品的临时记录。 */
    data class LootedItemEntry(
        val id: String, val name: String,
        val type: String, val rarity: Int, val count: Int
    )

    // ══════════════════════════════════════════════════════════════════
    // 隐匿 vs 感知判定（新增层）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 隐匿判定：盗贼隐匿 vs 守卫感知。
     *
     * 使用 Sigmoid 函数：
     *   P(被发现) = 1 / (1 + e^(盗贼隐匿 - 守卫感知))
     *
     * @return true = 被发现（进入战力对抗），false = 未被发现（偷窃成功）
     */
    private fun tryStealthDetection(
        disciple: Disciple,
        warehouses: List<GridBuildingData>,
        garrisons: List<WarehouseGarrisonSlot>,
        state: MutableGameState? = null
    ): Boolean {
        if (warehouses.isEmpty()) return false
        val warehouse = warehouses[rngManager.getRng(RngPartition.SYSTEM).nextInt(warehouses.size)]
        val garrison = garrisons.find { it.buildingInstanceId == warehouse.instanceId && it.isActive } ?: return false
        // 事务内路径走 state.discipleTables 而非 stateStore，确保读到事务内最新数据
        val guardDisciple = if (state != null) {
            val gid = garrison.discipleId.toIntOrNull() ?: return false
            state.discipleTables.assemble(gid) ?: return false
        } else {
            stateStore.disciples.value.find { it.id == garrison.discipleId } ?: return false
        }

        val thiefStealth = computeStealth(disciple)
        val guardPerception = computePerception(guardDisciple)
        val diff = thiefStealth - guardPerception
        val clampedDiff = diff.coerceIn(-20.0, 20.0)
        val detectionProb = 1.0 / (1.0 + exp(clampedDiff))
        return rngManager.getRng(RngPartition.SYSTEM).nextDouble() < detectionProb
    }

    /** 计算盗贼隐匿值。 */
    private fun computeStealth(disciple: Disciple): Double {
        val realmLevel = disciple.realm.coerceIn(1, 9)
        val cfg = GameConfig.LawEnforcementConfig
        val stats = DiscipleStatCalculator.getBaseStats(disciple)
        val realmBonus = realmLevel * cfg.THEFT_REALM_PERCEPTION_BONUS * cfg.THEFT_STEALTH_REALM_FACTOR
        val speedBonus = (stats.speed - cfg.THEFT_SPEED_BASE).coerceAtLeast(0) * cfg.THEFT_STEALTH_SPEED_FACTOR
        val intelBonus = (stats.intelligence - cfg.THEFT_INTELLIGENCE_BASE).coerceAtLeast(0) * cfg.THEFT_STEALTH_INTEL_FACTOR
        return realmBonus + speedBonus + intelBonus
    }

    /** 计算守卫感知值。 */
    private fun computePerception(guard: Disciple): Double {
        val realmLevel = guard.realm.coerceIn(1, 9)
        val cfg = GameConfig.LawEnforcementConfig
        val stats = DiscipleStatCalculator.getBaseStats(guard)
        val realmBonus = realmLevel * cfg.THEFT_REALM_PERCEPTION_BONUS
        val intelBonus = (stats.intelligence - cfg.THEFT_INTELLIGENCE_BASE).coerceAtLeast(0) * cfg.THEFT_PERCEPTION_INTEL_FACTOR
        return realmBonus + intelBonus
    }

    /**
     * 守卫对战判定（保留旧逻辑，去掉隐匿层）。
     *
     * 只有被隐匿判定发现的盗贼才会进入此方法。
     * 无守卫时直接返回 false（抓不到）。
     */
    private fun tryGuardCatch(disciple: Disciple, warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>, captureRate: Double): Boolean {
        if (warehouses.isEmpty()) return rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate
        val warehouse = warehouses[rngManager.getRng(RngPartition.SYSTEM).nextInt(warehouses.size)]
        val garrison = garrisons.find { it.buildingInstanceId == warehouse.instanceId && it.isActive } ?: return false
        val guardDisciple = stateStore.disciples.value.find { it.id == garrison.discipleId } ?: return false
        val thiefStats = DiscipleStatCalculator.getBaseStats(disciple)
        val guardStats = DiscipleStatCalculator.getBaseStats(guardDisciple)
        val thiefPower = thiefStats.physicalAttack + thiefStats.magicAttack + thiefStats.physicalDefense + thiefStats.magicDefense + thiefStats.speed
        val guardPower = guardStats.physicalAttack + guardStats.magicAttack + guardStats.physicalDefense + guardStats.magicDefense + guardStats.speed
        val thiefWinProb = (thiefPower.toDouble() / (thiefPower + guardPower).coerceAtLeast(1)).coerceIn(0.1, 0.9)
        return rngManager.getRng(RngPartition.SYSTEM).nextDouble() >= thiefWinProb
    }

    // ══════════════════════════════════════════════════════════════════
    // 叛逃 / 面壁 （原逻辑保持不动）
    // ══════════════════════════════════════════════════════════════════

    private fun findAtRiskDiscipleIds(currentMonthValue: Int, threshold: Int, protectionMonths: Int, tables: DiscipleTables): List<Int> {
        return tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.loyalties.getOrDefault(id, 0) < threshold &&
                (currentMonthValue - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths
        }
    }

    private fun calcDesertionProbability(threshold: Int, loyal: Int): Double {
        return ((threshold - loyal) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
    }

    private fun enforceDiscipleDesertion(id: Int, currentYear: Int, captureRate: Double, threshold: Int, tables: DiscipleTables) {
        if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate) {
            captureDiscipleForReflection(id, currentYear)
        } else {
            escapeDiscipleWithCleanup(id, threshold, tables)
        }
    }

    private fun captureDiscipleForReflection(id: Int, currentYear: Int) {
        val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
        stateStore.update {
            val d = discipleTables.assemble(id) ?: run {
                DomainLog.w(TAG, "captureDiscipleForReflection: disciple $id already removed, skipping")
                return@update
            }
            discipleTables.remove(id)
            discipleTables.insert(d.copy(status = DiscipleStatus.REFLECTING,
                statusData = d.statusData + mapOf("reflectionStartYear" to currentYear.toString(), "reflectionEndYear" to endYear.toString())))
            val prev = gameData.guideCounters[GuideCounterKeys.DISCIPLE_IMPRISONED] ?: 0L
            gameData = gameData.copy(
                guideCounters = gameData.guideCounters + (GuideCounterKeys.DISCIPLE_IMPRISONED to prev + 1)
            )
        }
    }

    private fun escapeDiscipleWithCleanup(id: Int, threshold: Int, tables: DiscipleTables) {
        if (tables.loyalties.getOrDefault(id, 0) >= threshold) return
        val snapshot = tables.assemble(id) ?: return
        desertDiscipleCleanup(id, threshold, snapshot)
    }

    private fun processTheftDesertionCleanup(thiefIds: Set<Int>, tables: DiscipleTables, loyalThreshold: Int) {
        if (thiefIds.isEmpty()) return
        val theftDesertCleanup = mutableMapOf<Int, Triple<List<String>, Set<String>, String>>()
        for (thiefId in thiefIds) {
            if (tables.loyalties.getOrDefault(thiefId, 0) >= loyalThreshold) continue
            val snapshot = tables.assemble(thiefId) ?: continue
            val equipIds = mutableListOf<String>()
            snapshot.equipment.weaponId?.let { equipIds.add(it) }
            snapshot.equipment.armorId?.let { equipIds.add(it) }
            snapshot.equipment.bootsId?.let { equipIds.add(it) }
            snapshot.equipment.accessoryId?.let { equipIds.add(it) }
            val manualIds = snapshot.manualIds.toSet()
            theftDesertCleanup[thiefId] = Triple(equipIds, manualIds, snapshot.name)
            discipleLifecycleProcessor.clearDiscipleFromAllSlots(thiefId.toString())
        }
        stateStore.update {
            for ((thiefId, cleanup) in theftDesertCleanup) {
                if (discipleTables.loyalties.getOrDefault(thiefId, 0) >= loyalThreshold) continue
                val (equipIds, manualIds, thiefName) = cleanup
                equipmentInstances = equipmentInstances.filter { it.id !in equipIds }
                manualInstances = manualInstances.filter { it.id !in manualIds }
                val mutableProf = gameData.manualProficiencies.toMutableMap()
                mutableProf.remove(thiefId.toString())
                gameData = gameData.copy(manualProficiencies = mutableProf)
                discipleTables.remove(thiefId)
                addEventRecord(this, "SECT", "theft_desertion", "${thiefName}偷盗后叛逃", thiefId.toString(), thiefName)
                gameData = gameData.copy(
                    annualDesertedDisciples = gameData.annualDesertedDisciples + 1
                )
            }
        }
    }

    private fun desertDiscipleCleanup(id: Int, threshold: Int, snapshot: Disciple) {
        val desertEquipIds = mutableListOf<String>()
        snapshot.equipment.weaponId?.let { desertEquipIds.add(it) }
        snapshot.equipment.armorId?.let { desertEquipIds.add(it) }
        snapshot.equipment.bootsId?.let { desertEquipIds.add(it) }
        snapshot.equipment.accessoryId?.let { desertEquipIds.add(it) }
        snapshot.equipment.storageBagItems.filter { it.itemType == ITEM_TYPE_EQUIPMENT_STACK || it.itemType == ITEM_TYPE_EQUIPMENT_INSTANCE }.map { it.itemId }.forEach { desertEquipIds.add(it) }
        val desertManualIds = snapshot.manualIds.toSet() + snapshot.equipment.storageBagItems.filter { it.itemType == ITEM_TYPE_MANUAL_STACK || it.itemType == ITEM_TYPE_MANUAL_INSTANCE }.map { it.itemId }
        val desertProfId = id.toString()
        discipleLifecycleProcessor.clearDiscipleFromAllSlots(id.toString())
        stateStore.update {
            if (discipleTables.loyalties.getOrDefault(id, 0) < threshold) {
                equipmentInstances = equipmentInstances.filter { it.id !in desertEquipIds }
                manualInstances = manualInstances.filter { it.id !in desertManualIds }
                val mutableProf = gameData.manualProficiencies.toMutableMap()
                mutableProf.remove(desertProfId)
                gameData = gameData.copy(manualProficiencies = mutableProf)
                discipleTables.remove(id)
                gameData = gameData.copy(
                    annualDesertedDisciples = gameData.annualDesertedDisciples + 1
                )
            }
        }
    }

    private fun recordGameEventSect(category: String, type: String, summary: String, relatedEntityId: String, relatedEntityName: String) {
        val data = stateStore.gameData.value
        val records = data.gameEventRecords.toMutableList()
        records.add(GameEventRecord(
            timestamp = System.currentTimeMillis(),
            year = data.gameYear,
            month = data.gameMonth,
            phase = data.gamePhase,
            category = category,
            eventType = type,
            summary = summary,
            relatedEntityId = relatedEntityId,
            relatedEntityName = relatedEntityName
        ))
        val trimmed = if (records.size > 200) records.takeLast(200) else records
        stateStore.update { gameData = gameData.copy(gameEventRecords = trimmed) }
    }

    /** 事务内添加事件记录 —— 直接修改 [state]，不含独立的 stateStore.update。 */
    private fun addEventRecord(state: MutableGameState, category: String, type: String, summary: String, relatedEntityId: String, relatedEntityName: String) {
        val records = state.gameData.gameEventRecords.toMutableList()
        records.add(GameEventRecord(
            timestamp = System.currentTimeMillis(),
            year = state.gameData.gameYear,
            month = state.gameData.gameMonth,
            phase = state.gameData.gamePhase,
            category = category,
            eventType = type,
            summary = summary,
            relatedEntityId = relatedEntityId,
            relatedEntityName = relatedEntityName
        ))
        state.gameData = state.gameData.copy(gameEventRecords = if (records.size > 200) records.takeLast(200) else records)
    }
}

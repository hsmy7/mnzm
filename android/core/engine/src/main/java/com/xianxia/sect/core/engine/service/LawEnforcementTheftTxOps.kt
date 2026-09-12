package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.core.model.loyalty
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.exploration.LootCalculator
import com.xianxia.sect.core.model.BagStackedData
import com.xianxia.sect.core.engine.domain.disciple.getBaseStats

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── 盗窃事务域（自 LawEnforcementProcessor 拆出，行为零变更） ──────────────────

private val TAG = LawEnforcementProcessor.TAG
/**
 * 对单个弟子执行完整偷盗检查。
 * 流程：偷盗概率 → 执法堂判定 → 仓库守卫判定 → 执行偷窃（灵石+物品）。
 * 判定标记（lastTheftJudgementYears / theftJudgementsThisMonth）已在调用方完成。
 */
@Suppress("UnusedParameter") // currentMonth: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
internal fun LawEnforcementProcessor.executeFullTheftCheck(
    id: Int,
    tables: DiscipleTables,
    currentMonth: Int,
    currentData: GameData
) {
    val disciple = tables.assemble(id) ?: return
    val stats = DiscipleStatCalculator.getBaseStats(disciple)
    val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
    val captureRate = calculateCaptureRate()
    val warehouses = currentData.placedBuildings.filter { it.displayName == "仓库" }
    val garrisons = currentData.warehouseGarrisons

    // Step 1: 偷盗概率判定
    if (!shouldAttemptTheft(morality = stats.morality, currentData = currentData)) return

    // Step 2: 执法堂判定 — 直接以抓捕率判定
    if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate) {
        handleLawEnforcementCapture(disciple = disciple, currentData = currentData)
        return
    }

    // Step 3: 仓库驻守判定 — 纯智力比拼
    if (handleWarehouseGarrisonCheck(
            disciple = disciple,
            thiefIntel = stats.intelligence,
            warehouses = warehouses,
            garrisons = garrisons,
            currentData = currentData
        )
    ) {
        return
    }

    // Step 3: 偷窃成功 → 执行（灵石 + 物品）
    executeSuccessfulTheft(disciple, id, tables, currentData, warehouses, garrisons)

    // Step 4: 偷盗后叛逃判定（仅看忠诚）
    if (shouldDesertAfterTheft(loyalty = stats.loyalty)) {
        processTheftDesertionCleanup(setOf(id), tables, loyalThreshold)
    }
}

/** 偷盗概率判定：道德差 × 每点概率，宵禁减免；true=尝试偷盗 */

internal fun LawEnforcementProcessor.shouldAttemptTheft(morality: Int, currentData: GameData): Boolean {
    val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
    val theftProb = ((moralThreshold - morality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT)
        .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
    val effectiveTheftProb = if (currentData.sectPolicies.curfew) {
        theftProb * (1.0 - GameConfig.PolicyConfig.CURFEW_EVENT_REDUCTION)
    } else theftProb
    return rngManager.getRng(RngPartition.SYSTEM).nextDouble() < effectiveTheftProb
}

/** 执法堂抓捕处理：面壁思过 + 事件记录 */

internal fun LawEnforcementProcessor.handleLawEnforcementCapture(disciple: Disciple, currentData: GameData) {
    stateStore.update {
        val cid = disciple.id.toIntOrNull() ?: return@update
        if (discipleTables.ids.contains(cid) && discipleTables.isAlive[cid] == 1) {
            discipleTables.statuses[cid] = DiscipleStatus.REFLECTING
            discipleTables.statusData[cid] = (discipleTables.statusData.getOrNull(cid) ?: emptyMap()) + mapOf(
                "reflectionStartYear" to currentData.gameYear.toString(),
                "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS)
                    .toString()
            )
        }
        addEventRecord(this, "SECT", "theft_caught", "${disciple.name}偷盗被捕", disciple.id, disciple.name)
    }
}

/** 仓库守卫判定：守卫智力 ≥ 小偷智力 → 抓捕；true=已被捕 */

@Suppress("ReturnCount")
internal fun LawEnforcementProcessor.handleWarehouseGarrisonCheck(
    disciple: Disciple,
    thiefIntel: Int,
    warehouses: List<GridBuildingData>,
    garrisons: List<WarehouseGarrisonSlot>,
    currentData: GameData
): Boolean {
    if (warehouses.isEmpty()) return false
    val wh = warehouses[rngManager.getRng(RngPartition.SYSTEM).nextInt(warehouses.size)]
    val garrison = garrisons.find { it.buildingInstanceId == wh.instanceId && it.isActive }
    if (garrison == null) return false
    val guardDisciple = stateStore.disciples.value.find { it.id == garrison.discipleId }
    if (guardDisciple == null) return false
    val guardIntel = DiscipleStatCalculator.getBaseStats(guardDisciple).intelligence
    if (thiefIntel > guardIntel) return false
    stateStore.update {
        val cid = disciple.id.toIntOrNull() ?: return@update
        if (discipleTables.ids.contains(cid) && discipleTables.isAlive[cid] == 1) {
            discipleTables.statuses[cid] = DiscipleStatus.REFLECTING
            discipleTables.statusData[cid] = (discipleTables.statusData.getOrNull(cid) ?: emptyMap()) + mapOf(
                "reflectionStartYear" to currentData.gameYear.toString(),
                "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS)
                    .toString()
            )
        }
        addEventRecord(this, "SECT", "theft_caught",
            "${disciple.name}偷盗被捕", disciple.id, disciple.name)
    }
    return true
}

/** 偷盗后叛逃判定：仅看忠诚，低于阈值按概率叛逃 */

@Suppress("UnusedParameter") // currentMonth: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
internal fun LawEnforcementProcessor.shouldDesertAfterTheft(loyalty: Int): Boolean {
    val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
    val desertionProb = ((loyalThreshold - loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT)
        .coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
    return rngManager.getRng(RngPartition.SYSTEM).nextDouble() < desertionProb
}

/**
 * 事务内完整偷盗检查 —— 直接修改 [state]，不调用 stateStore.update。
 * 标记判定已在 [processSingleDiscipleTheft(id, state)] 中完成。
 */

@Suppress("UnusedParameter") // currentMonth: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
internal fun LawEnforcementProcessor.executeFullTheftCheckInTransaction(
    id: Int,
    state: MutableGameState,
    currentMonth: Int
) {
    val tables = state.discipleTables
    val disciple = tables.assemble(id) ?: return
    // 标记判定已在 processSingleDiscipleTheft 事务入口完成，此处不重复
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

    // Step 2: 执法堂判定 — 直接以抓捕率判定
    if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < captureRate) {
        markCaughtAndRecord(disciple, tables, currentData, state)
        return
    }

    // Step 3: 仓库驻守判定 — 纯智力比拼（从事务内 state 读取守卫）
    if (resolveWarehouseGarrisonInterception(disciple, tables, currentData, warehouses, garrisons, state)) {
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

/** 被捕处置：标记被捕并记录事件（标记失败仅跳过事件） */

internal fun LawEnforcementProcessor.markCaughtAndRecord(
    disciple: Disciple,
    tables: DiscipleTables,
    currentData: GameData,
    state: MutableGameState
) {
    if (!markTheftCaught(disciple, tables, currentData)) return
    state.recordGameEvent(GameEventCategory.SECT, GameEventType.THEFT_CAUGHT,
        "${disciple.name}偷盗被捕", disciple.id, disciple.name)
}

/**
 * 仓库驻守拦截：仓库存在时抽选目标仓库
 * （恰一次 SYSTEM nextInt——RNG 消费序确定），驻守活跃且智力 ≥ 窃贼
 * 则按被捕处置。
 *
 * @return true = 已按被捕处置，主流程终止
 */

internal fun LawEnforcementProcessor.resolveWarehouseGarrisonInterception(
    disciple: Disciple,
    tables: DiscipleTables,
    currentData: GameData,
    warehouses: List<GridBuildingData>,
    garrisons: List<WarehouseGarrisonSlot>,
    state: MutableGameState
): Boolean {
    if (warehouses.isEmpty()) return false
    val wh = warehouses[rngManager.getRng(RngPartition.SYSTEM).nextInt(warehouses.size)]
    val garrison = garrisons.find { it.buildingInstanceId == wh.instanceId && it.isActive }
    val gid = garrison?.discipleId?.toIntOrNull() ?: return false
    val thiefIntelligence = DiscipleStatCalculator.getBaseStats(disciple).intelligence
    if (thiefIntelligence > state.discipleTables.intelligences.getOrDefault(gid, 0)) return false
    markCaughtAndRecord(disciple, tables, currentData, state)
    return true
}

/**
 * 事务内成功偷窃 —— 直接修改 [state]。
 */

internal fun LawEnforcementProcessor.executeSuccessfulTheftInTransaction(
    disciple: Disciple, id: Int, tables: DiscipleTables,
    currentData: GameData,
    warehouses: List<GridBuildingData>, garrisons: List<WarehouseGarrisonSlot>,
    state: MutableGameState
) {
    val stolenAmount = calcTheftAmount(disciple, currentData.spiritStones)
    if (stolenAmount <= 0L && currentData.spiritStones <= 0) return

    // 扣除宗门灵石
    if (stolenAmount > 0L) {
        state.gameData = state.gameData.copy(spiritStones = (state.gameData.spiritStones - stolenAmount)
            .coerceAtLeast(0))
    }

    // 物品偷窃（事务内版本：从 state 的 EntityStore 读取仓库，而非 stateStore StateFlow）
    val stolenItems = buildList {
        addAll(computeTheftItems(disciple, currentData, currentData, warehouses, garrisons, state))
    }

    // 通过 LootCalculator 扣除仓库物品
    if (stolenItems.isNotEmpty()) {
        val lootData = LootCalculator.BeastLootData(
            stolenItems = stolenItems.map { LootCalculator.LootedItem(it.id, it.name, it.type, it.rarity,
                it.count) }
        )
        lootCalculator.applyLoot(state, lootData)
    }

    // 更新弟子储物袋（容量无上限；条目自带 stackedData 标记已物化）
    val existing = tables.assembleAll().firstOrNull { it.id == disciple.id } ?: return
    val itemEntries = stolenItems.map { item ->
        StorageBagItem(
            itemId = item.id, itemType = item.type, name = item.name,
            rarity = item.rarity, quantity = item.count,
            obtainedYear = state.gameData.gameYear, obtainedMonth = state.gameData.gameMonth,
            stackedData = BagStackedData()
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

@Suppress("UnusedParameter") // tables: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
internal fun LawEnforcementProcessor.processTheftDesertionCleanupInTransaction(
    thiefIds: Set<Int>, tables: DiscipleTables,
    loyalThreshold: Int, state: MutableGameState) {
    if (thiefIds.isEmpty()) return
    for (thiefId in thiefIds) {
        // 忠诚达标的窃贼不叛逃；快照组装失败（幽灵行）跳过
        val loyal = tables.loyalties.getOrDefault(thiefId, 0)
        val snapshot = if (loyal >= loyalThreshold) null else tables.assemble(thiefId)
        if (snapshot == null) continue
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

@Suppress("UnusedParameter") // tables: 语义形参：签名表达 API 决策域，当前策略不消费
internal fun LawEnforcementProcessor.executeSuccessfulTheft(
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
                stolenItems = stolenItems.map { item ->
                    LootCalculator.LootedItem(item.id, item.name, item.type, item.rarity, item.count)
                }
            )
            lootCalculator.applyLoot(this, lootData)
        }

        // 更新弟子储物袋（容量无上限；条目自带 stackedData 标记已物化）
        val existing = discipleTables.assembleAll().firstOrNull { it.id == disciple.id } ?: return@update
        val itemEntries = stolenItems.map { item ->
            StorageBagItem(
                itemId = item.id, itemType = item.type, name = item.name,
                rarity = item.rarity, quantity = item.count,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                stackedData = BagStackedData()
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


internal fun LawEnforcementProcessor.processTheftDesertionCleanup(
    thiefIds: Set<Int>,
    tables: DiscipleTables,
    loyalThreshold: Int
) {
    if (thiefIds.isEmpty()) return
    val theftDesertCleanup = mutableMapOf<Int, Triple<List<String>, Set<String>, String>>()
    for (thiefId in thiefIds) {
        // 忠诚达标的窃贼不叛逃；快照组装失败（幽灵行）跳过
        val loyal = tables.loyalties.getOrDefault(thiefId, 0)
        val snapshot = if (loyal >= loyalThreshold) null else tables.assemble(thiefId)
        if (snapshot == null) continue
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

internal fun LawEnforcementProcessor.desertDiscipleCleanup(id: Int, threshold: Int, snapshot: Disciple) {
    val desertEquipIds = mutableListOf<String>()
    snapshot.equipment.weaponId?.let { desertEquipIds.add(it) }
    snapshot.equipment.armorId?.let { desertEquipIds.add(it) }
    snapshot.equipment.bootsId?.let { desertEquipIds.add(it) }
    snapshot.equipment.accessoryId?.let { desertEquipIds.add(it) }
    // 储物袋独立存储后袋条目持有数据且随弟子删除（叛逃带走），
    // 不收集袋条目 itemId——itemId 可能撞仓库堆叠 id，收集会误删玩家仓库物品
    val desertManualIds = snapshot.manualIds.toSet()
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
            // 消息栏事件：脱离宗门必须对玩家可见（否则弟子"无故消失"）
            recordGameEvent(GameEventCategory.SECT, GameEventType.DESERTION,
                "${snapshot.name}脱离宗门", id.toString(), snapshot.name)
        }
    }
}



internal fun LawEnforcementProcessor.captureDiscipleForReflection(id: Int, currentYear: Int) {
    val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
    stateStore.update {
        val d = discipleTables.assemble(id) ?: run {
            DomainLog.w(TAG, "captureDiscipleForReflection: disciple $id already removed, skipping")
            return@update
        }
        discipleTables.remove(id)
        discipleTables.insert(d.copy(status = DiscipleStatus.REFLECTING,
            statusData = d.statusData + mapOf("reflectionStartYear" to currentYear.toString(),
                "reflectionEndYear" to endYear.toString())))
        val prev = gameData.guideCounters[GuideCounterKeys.DISCIPLE_IMPRISONED] ?: 0L
        gameData = gameData.copy(
            guideCounters = gameData.guideCounters + (GuideCounterKeys.DISCIPLE_IMPRISONED to prev + 1)
        )
        recordGameEvent(GameEventCategory.SECT, GameEventType.DESERTION_CAUGHT,
            "${d.name}企图叛逃，被执法堂捕获思过", id.toString(), d.name)
    }
}

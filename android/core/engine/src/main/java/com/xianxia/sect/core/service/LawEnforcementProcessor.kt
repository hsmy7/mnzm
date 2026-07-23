package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执法/偷窃处理器 — 从 [CultivationEventProcessor] 拆分。
 * 处理执法月度事件、偷窃检测及处罚。
 *
 * ## 职责
 * - 叛逃检测：月度检查忠诚度低于阈值的弟子，依概率触发捕获/逃脱
 * - 偷窃检测：月度检查道德低于阈值的弟子，依概率触发偷窃 + 守卫对战
 * - 捕获处理：捕获后的弟子送面壁反省
 * - 逃脱处理：逃脱弟子清理装备/功法 + 移除
 *
 * @param stateStore 游戏状态存储
 * @param rngManager 确定性 RNG 管理器（BATTLE 分区）
 * @param discipleLifecycleProcessor 弟子生命周期处理器（槽位清理/死亡处理）
 */
@GameService("LawEnforcementProcessor")
@Singleton
class LawEnforcementProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val rngManager: GameRngManager,
    private val discipleLifecycleProcessor: DiscipleLifecycleProcessor
) {
    companion object {
        private const val TAG = "LawEnforcementProc"
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
        // 赏善罚恶：执法效率+30%
        if (data.sectPolicies.rewardPunish) {
            captureRate += GameConfig.PolicyConfig.REWARD_PUNISH_EFFECT
        }
        return captureRate.coerceIn(0.0, 1.0)
    }

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

    fun processTheftMonthly() {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return
        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentData.gameYear * 12 + currentData.gameMonth
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val protectionMonths = GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        val atRiskIds = tables.ids.filter { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
                tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
                tables.moralities.getOrDefault(id, 0) < moralThreshold &&
                tables.loyalties.getOrDefault(id, 0) < loyalThreshold &&
                (currentMonthValue - tables.recruitedMonths.getOrDefault(id, 0)) >= protectionMonths &&
                (currentMonthValue - tables.lastTheftMonths.getOrDefault(id, 0)) >= 12
        }
        val thiefIds = mutableSetOf<Int>()
        val warehouses = currentData.placedBuildings.filter { it.displayName == "仓库" }
        val garrisons = currentData.warehouseGarrisons
        for (id in atRiskIds) {
            val disciple = tables.assemble(id) ?: continue
            val stats = DiscipleStatCalculator.getBaseStats(disciple)
            val effectiveMorality = stats.morality
            val theftProb = ((moralThreshold - effectiveMorality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            // 宵禁：治安事件概率-30%
            val effectiveTheftProb = if (currentData.sectPolicies.curfew) theftProb * (1.0 - GameConfig.PolicyConfig.CURFEW_EVENT_REDUCTION) else theftProb
            if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < effectiveTheftProb) {
                val caught = tryGuardCatch(disciple, warehouses, garrisons, captureRate)
                if (caught) {
                    stateStore.update {
                        val cid = disciple.id.toIntOrNull() ?: run {
                            DomainLog.w(TAG, "processTheftMonthly: caught but disciple.id not int, skipping")
                            return@update
                        }
                        if (discipleTables.ids.contains(cid) && discipleTables.isAlive[cid] == 1) {
                            discipleTables.statuses[cid] = DiscipleStatus.REFLECTING
                            val existingData = discipleTables.statusData[cid]
                            discipleTables.statusData[cid] = existingData + mapOf(
                                "reflectionStartYear" to currentData.gameYear.toString(),
                                "reflectionEndYear" to (currentData.gameYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS).toString()
                            )
                        }
                        recordGameEventSect("SECT", "theft_caught", "${disciple.name}偷盗被捕", disciple.id, disciple.name)
                    }
                } else {
                    val stolenAmount = executeTheftStolen(disciple, currentMonthValue, tables)
                    if (stolenAmount <= 0L) break
                    val desertionProb = ((loyalThreshold - stats.loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
                    if (rngManager.getRng(RngPartition.SYSTEM).nextDouble() < desertionProb) thiefIds.add(id)
                    stateStore.update { recordGameEventSect("SECT", "warehouse_theft", "宗门仓库被盗，损失了 $stolenAmount 灵石", "", "") }
                }
            }
        }
        processTheftDesertionCleanup(thiefIds, tables, loyalThreshold)
    }

    fun processTheftIfNeeded() {
        if (stateStore.gameData.value.spiritStones <= 0) return
        val tables = stateStore.discipleTables
        val moralThreshold = GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        val loyalThreshold = GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        val hasLowMoralityDisciple = tables.ids.any { id ->
            tables.isAlive.getOrDefault(id, 0) == 1 &&
            tables.statuses.getOrDefault(id, DiscipleStatus.IDLE) == DiscipleStatus.IDLE &&
            tables.moralities.getOrDefault(id, 0) < moralThreshold &&
            tables.loyalties.getOrDefault(id, 0) < loyalThreshold
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly()
    }

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
            // 引导系统：累计弟子入狱
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
                recordGameEventSect("SECT", "theft_desertion", "${thiefName}偷盗后叛逃", thiefId.toString(), thiefName)
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

    private fun executeTheftStolen(disciple: Disciple, currentMonthValue: Int, tables: DiscipleTables): Long {
        val currentData = stateStore.gameData.value
        if (currentData.spiritStones <= 0) return 0L
        val stolenAmount = (currentData.spiritStones * (GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO + (GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO - GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO) * rngManager.getRng(RngPartition.SYSTEM).nextDouble())).toLong().coerceAtLeast(1)
        stateStore.update {
            gameData = gameData.copy(spiritStones = (gameData.spiritStones - stolenAmount).coerceAtLeast(0))
            discipleTables.assembleAll().firstOrNull { it.id == disciple.id }?.let { d ->
                discipleTables.update(d.copy(equipment = d.equipment.copy(storageBagSpiritStones = d.equipment.storageBagSpiritStones + stolenAmount), usage = d.usage.copy(lastTheftMonth = currentMonthValue)))
            }
        }
        return stolenAmount
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
}

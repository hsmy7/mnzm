package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

@Singleton
@GameService("DiscipleBreakthroughHandler")
class DiscipleBreakthroughHandler @Inject constructor(
    private val stateStore: GameStateStore,
    private val cultivationCore: CultivationCore,
    private val scopeProvider: CoroutineScopeProvider,
    private val relativeGiftHandler: RelativeGiftHandler,
    private val rngManager: GameRngManager
) {
    private val scope get() = scopeProvider.scope

    fun performBreakthrough(
        disciple: Disciple,
        state: MutableGameState,
        data: GameData
    ): Disciple {
        val tables = state.discipleTables
        var d = disciple.copy(
            cultivation = tables.cultivations.getOrDefault(disciple.id.toInt(), disciple.cultivation)
        )
        var shouldContinue = true
        var breakthroughCount = 0
        var failCount = 0

        while (shouldContinue && d.realm > 0) {
            if (d.cultivation < d.maxCultivation) break
            if (!cultivationCore.isDiscipleFullHpMp(d)) break

            val pillTargetRealm = if (d.realmLayer >= GameConfig.Realm.get(d.realm).maxLayers) {
                d.realm - 1
            } else d.realm
            val pillBonus = attemptAutoPill(d, pillTargetRealm, state, data)
            d = pillBonus.second
                ?: d // 储物袋丹药修改可能改变了 d，保留原值

            val success = tryBreakthrough(d, pillBonus.first, state)
            if (success) {
                breakthroughCount++
                d = applyBreakthroughSuccess(d)
            } else {
                failCount++
                d = applyBreakthroughFailure(d)
                shouldContinue = false
            }
        }

        // Checkpoint：突破后弟子境界/层数可能变化，修炼速率改变，同步检查点
        val currentMonth = data.gameYear * 12 + data.gameMonth
        d.id.toIntOrNull()?.let { tables.checkpointDisciple(it, currentMonth) }

        d = clearAdBonus(d)
        writeBreakthroughCounts(d.id, tables, breakthroughCount, failCount)
        d = updateCompletionEstimate(d, data)

        return d
    }

    /** 自动服用突破丹药：检测配置 → 仓库优先 → 储物袋兜底，返回 (加成值, 修改后的弟子) */
    private fun attemptAutoPill(d: Disciple, pillTargetRealm: Int, state: MutableGameState, data: GameData): Pair<Double, Disciple?> {
        val autoFocused = data.breakthroughAutoPillFocused
        val autoRootCounts = data.breakthroughAutoPillRootCounts
        if (!autoFocused && autoRootCounts.isEmpty()) return Pair(0.0, null)

        val qualifies = (autoFocused && d.statusData["followed"] == "true") ||
            d.spiritRootType.split(",").size in autoRootCounts
        if (!qualifies) return Pair(0.0, null)

        val warehousePill = state.pills.all()
            .filter { it.pillType == "breakthrough" && it.effects.targetRealm == pillTargetRealm }
            .maxByOrNull { it.effects.breakthroughChance }
        if (warehousePill != null) {
            state.pills = state.pills - listOf(warehousePill)
            return Pair(warehousePill.effects.breakthroughChance, null)
        }

        // 储物袋丹药兜底
        val bestPill = d.equipment.storageBagItems
            .filter { it.itemType == ITEM_TYPE_PILL && it.effect?.pillType == "breakthrough" && it.effect?.targetRealm == pillTargetRealm }
            .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
        return if (bestPill != null) {
            Pair(bestPill.effect?.breakthroughChance ?: 0.0, d.copy(equipment = d.equipment.copy(
                storageBagItems = d.equipment.storageBagItems - bestPill
            )))
        } else Pair(0.0, null)
    }

    private fun applyBreakthroughSuccess(d: Disciple): Disciple {
        var disciple = d.copy(cultivation = 0.0)
        val oldRealm = disciple.realm
        if (disciple.realmLayer < GameConfig.Realm.get(disciple.realm).maxLayers) {
            disciple = disciple.copy(realmLayer = disciple.realmLayer + 1)
        } else {
            disciple = disciple.copy(realm = disciple.realm - 1, realmLayer = 1)
        }
        if (disciple.realm != oldRealm) {
            var lifespanGain = cultivationCore.getLifespanGainForRealm(disciple.realm)
            val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(disciple.talentIds)["lifespan"] ?: 0.0
            if (lifespanTalentBonus != 0.0) {
                lifespanGain += (cultivationCore.getLifespanGainForRealm(disciple.realm) * lifespanTalentBonus).toInt()
            }
            disciple = disciple.copy(lifespan = disciple.lifespan + lifespanGain)
        }
        return disciple
    }

    private fun applyBreakthroughFailure(d: Disciple): Disciple {
        val curHp = if (d.combat.currentHp < 0) d.maxHp else d.combat.currentHp
        val curMp = if (d.combat.currentMp < 0) d.maxMp else d.combat.currentMp
        return d.copy(
            cultivation = 0.0,
            combat = d.combat.copy(
                currentHp = (curHp * FAILURE_HP_MP_RATIO).toInt().coerceAtLeast(1),
                currentMp = (curMp * FAILURE_HP_MP_RATIO).toInt().coerceAtLeast(1)
            )
        )
    }

    private fun clearAdBonus(d: Disciple): Disciple {
        val cleaned = (d.statusData ?: emptyMap()).toMutableMap().apply {
            remove("adBreakthroughBonus")
        }
        return d.copy(statusData = cleaned)
    }

    private fun writeBreakthroughCounts(discipleId: String, tables: DiscipleTables, successCount: Int, failCount: Int) {
        val idInt = discipleId.toIntOrNull() ?: return
        if (successCount > 0) {
            tables.breakthroughCounts[idInt] =
                (tables.breakthroughCounts[idInt] ?: 0) + successCount
        }
        if (failCount > 0) {
            tables.breakthroughFailCounts[idInt] =
                (tables.breakthroughFailCounts[idInt] ?: 0) + failCount
        }
    }

    private fun updateCompletionEstimate(d: Disciple, data: GameData): Disciple {
        val currentMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
            data.gameYear, data.gameMonth
        )
        val tables = stateStore.discipleTables
        val rate = cultivationCore.calculateDiscipleCultivationPerPhase(d, data, tables)
        val remaining = if (d.cultivation < d.maxCultivation) d.maxCultivation - d.cultivation else 0.0
        val monthsToNext = com.xianxia.sect.core.engine.LazyEvaluationDispatcher
            .estimateMonthsToNextBreakthrough(remaining, rate)
        return d.copy(
            cultivationCompletionMonth = currentMonth + monthsToNext,
            cultivationCompletionPhase = 1
        )
    }

    /**
     * 实时突破处理 — 委托给 [performBreakthrough]，再批量写回组件表。
     */
    fun processRealtimeBreakthroughs(
        livingDisciples: List<Disciple>, data: GameData, state: MutableGameState
    ) {
        val candidates = livingDisciples.filter { disciple ->
            disciple.realm > 0 && disciple.cultivation >= disciple.maxCultivation &&
                cultivationCore.isDiscipleFullHpMp(disciple)
        }
        if (candidates.isEmpty()) return

        val tables = state.discipleTables
        val candidateIds = candidates.map { it.id }.toSet()
        val allDiscipleIds = tables.ids.filter { tables.isAlive[it] == 1 }
        val updatedDisciples = allDiscipleIds.map { id ->
            val disciple = tables.assemble(id)
            if (disciple.id !in candidateIds) return@map disciple
            if (disciple.cultivation < disciple.maxCultivation || disciple.realm <= 0) return@map disciple
            performBreakthrough(disciple, state, data)
        }

        // 精准字段写回，不再全量 clear+insert
        updatedDisciples.forEach { d ->
            val id = d.id.toIntOrNull() ?: return@forEach
            tables.cultivations[id] = d.cultivation
            tables.realms[id] = d.realm
            tables.realmLayers[id] = d.realmLayer
            tables.lifespans[id] = d.lifespan
            tables.currentHps[id] = d.combat.currentHp
            tables.currentMps[id] = d.combat.currentMp
            // 突破后的装备存储物品变更
            tables.storageBagItems[id] = d.equipment.storageBagItems
            // statusData 变更（清除广告加成）
            tables.statusData[id] = d.statusData
            // 修炼完成时间
            tables.cultivationCompletionMonths[id] = d.cultivationCompletionMonth
            tables.cultivationCompletionPhases[id] = d.cultivationCompletionPhase
        }

        // 亲属智能赠送：突破（realm 或 realmLayer 变化）后触发
        for (candidate in candidates) {
            val id = candidate.id.toIntOrNull() ?: continue
            val newRealm = tables.realms[id]
            val newLayer = tables.realmLayers[id]
            if (candidate.realm != newRealm || candidate.realmLayer != newLayer) {
                relativeGiftHandler.processGiftsForBreakthrough(id, tables, state)
            }

            // 记录突破日志（仅大境界变化）
            if (candidate.realm != newRealm) {
                val discipleAge = tables.ages[id]
                val newRealmName = GameConfig.Realm.getName(newRealm)
                val event = "${discipleAge}岁：突破至${newRealmName}"
                val currentEvents = tables.lifeEvents.getOrDefault(id, emptyList())
                tables.lifeEvents[id] = currentEvents + event
            }
        }
    }

    fun tryBreakthrough(disciple: Disciple, pillBonus: Double = 0.0, state: MutableGameState? = null): Boolean {
        val data = state?.gameData ?: stateStore.gameData.value
        val tables = state?.discipleTables ?: stateStore.discipleTables
        val elderSlots = data.elderSlots

        val innerElderId = elderSlots.innerElder
        val innerElderComprehension = if (innerElderId.isNotEmpty() && disciple.discipleType == TYPE_INNER) {
            val elderId = innerElderId.toIntOrNull()
            if (elderId != null && tables.isAlive[elderId] == 1
                && disciple.realm >= tables.realms[elderId]) {
                tables.comprehensions[elderId]
            } else { 0 }
        } else { 0 }

        val outerElderId = data.elderSlots.outerElder
        val outerElderComprehension = if (disciple.discipleType == TYPE_OUTER && outerElderId.isNotEmpty()) {
            val oid = outerElderId.toIntOrNull()
            if (oid != null && tables.isAlive[oid] == 1
                && disciple.realm >= tables.realms[oid]) {
                tables.comprehensions[oid]
            } else { 0 }
        } else { 0 }

        val adBonus = disciple.statusData?.get("adBreakthroughBonus")?.toDoubleOrNull() ?: 0.0

        val griefBreakthroughPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_BREAKTHROUGH_CHANCE_PENALTY
        } else {
            0.0
        }

        // 师徒加成：徒弟有师父且师父存活时，按大境界差提供突破率加成
        val masterDiscipleBonus = disciple.social.masterId?.let { mid ->
            val midInt = mid.toIntOrNull() ?: return@let 0.0
            if (tables.ids.contains(midInt) && tables.isAlive[midInt] == 1) {
                val masterRealm = tables.realms[midInt]
                DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(disciple.realm, masterRealm)
            } else 0.0
        } ?: 0.0

        val chance = DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            innerElderComprehension = innerElderComprehension,
            outerElderComprehension = outerElderComprehension,
            pillBonus = pillBonus,
            adBonus = adBonus,
            griefBreakthroughPenalty = griefBreakthroughPenalty,
            masterDiscipleBonus = masterDiscipleBonus
        )
        return rngManager.getRng(RngPartition.BREAKTHROUGH).nextDouble() < chance
    }

    companion object {
        private const val TAG = "DiscipleBreakthroughHandler"
        private const val FAILURE_HP_MP_RATIO = 0.1
    }
}

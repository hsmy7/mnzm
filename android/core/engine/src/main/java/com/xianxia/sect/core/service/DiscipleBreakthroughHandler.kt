package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlin.random.Random

@Singleton
@GameService("DiscipleBreakthroughHandler")
class DiscipleBreakthroughHandler @Inject constructor(
    private val stateStore: GameStateStore,
    private val cultivationCore: CultivationCore,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.scope

    /**
     * 统一的突破处理方法（单个弟子）。
     *
     * 处理突破循环、自动用丹、概率判定、境界变化、HP/MP惩罚、
     * 突破计数写入。返回修改后的弟子对象，由调用方负责写回状态。
     *
     * @param disciple 待突破的弟子
     * @param state 可变游戏状态（用于读取 tables 和操作丹药库存）
     * @param data 游戏数据快照
     * @return 突破处理后的弟子对象
     */
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

            // 满状态检查：HP/MP 均满才可突破
            if (!cultivationCore.isDiscipleFullHpMp(d)) break

            val isMajorBreakthrough = d.realmLayer >= GameConfig.Realm.get(d.realm).maxLayers
            val pillTargetRealm = if (isMajorBreakthrough) d.realm - 1 else d.realm
            var pillBonus = 0.0

            // 自动服用突破丹药：检测配置 → 仓库优先 → 储物袋兜底
            val autoFocused = data.breakthroughAutoPillFocused
            val autoRootCounts = data.breakthroughAutoPillRootCounts
            if (autoFocused || autoRootCounts.isNotEmpty()) {
                val qualifies = (autoFocused && d.statusData["followed"] == "true") ||
                    d.spiritRootType.split(",").size in autoRootCounts
                if (qualifies) {
                    val warehousePill = state.pills.all()
                        .filter { it.pillType == "breakthrough" && it.effects.targetRealm == pillTargetRealm }
                        .maxByOrNull { it.effects.breakthroughChance }
                    if (warehousePill != null) {
                        state.pills = state.pills - listOf(warehousePill)
                        pillBonus = warehousePill.effects.breakthroughChance
                    }
                }
            }

            // 储物袋丹药兜底
            if (pillBonus == 0.0) {
                val bestPill = d.equipment.storageBagItems
                    .filter { it.itemType == "pill" && it.effect?.pillType == "breakthrough" && it.effect?.targetRealm == pillTargetRealm }
                    .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
                if (bestPill != null) {
                    d = d.copy(equipment = d.equipment.copy(
                        storageBagItems = d.equipment.storageBagItems - bestPill
                    ))
                    pillBonus = bestPill.effect?.breakthroughChance ?: 0.0
                }
            }

            val success = tryBreakthrough(d, pillBonus, state)
            if (success) {
                breakthroughCount++
                d = d.copy(cultivation = 0.0)
                val oldRealm = d.realm
                if (d.realmLayer < GameConfig.Realm.get(d.realm).maxLayers) {
                    d = d.copy(realmLayer = d.realmLayer + 1)
                } else {
                    d = d.copy(realm = d.realm - 1, realmLayer = 1)
                }
                if (d.realm != oldRealm) {
                    var lifespanGain = cultivationCore.getLifespanGainForRealm(d.realm)
                    val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(d.talentIds)["lifespan"] ?: 0.0
                    if (lifespanTalentBonus != 0.0) {
                        lifespanGain += (cultivationCore.getLifespanGainForRealm(d.realm) * lifespanTalentBonus).toInt()
                    }
                    d = d.copy(lifespan = d.lifespan + lifespanGain)
                }
            } else {
                failCount++
                d = d.copy(cultivation = 0.0)
                shouldContinue = false
                val curHp = if (d.combat.currentHp < 0) d.maxHp else d.combat.currentHp
                val curMp = if (d.combat.currentMp < 0) d.maxMp else d.combat.currentMp
                d = d.copy(combat = d.combat.copy(
                    currentHp = (curHp * 0.1).toInt().coerceAtLeast(1),
                    currentMp = (curMp * 0.1).toInt().coerceAtLeast(1)
                ))
            }
        }

        // 清除广告加成
        val cleanedStatusData = (d.statusData ?: emptyMap()).toMutableMap().apply {
            remove("adBreakthroughBonus")
        }
        d = d.copy(statusData = cleanedStatusData)

        // 写入突破成功/失败计数
        val idInt = d.id.toIntOrNull()
        if (idInt != null) {
            if (breakthroughCount > 0) {
                tables.breakthroughCounts[idInt] =
                    (tables.breakthroughCounts[idInt] ?: 0) + breakthroughCount
            }
            if (failCount > 0) {
                tables.breakthroughFailCounts[idInt] =
                    (tables.breakthroughFailCounts[idInt] ?: 0) + failCount
            }
        }

        // 更新修炼完成时间预估
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
            data.gameYear, data.gameMonth
        )
        val cultivationRate = cultivationCore.calculateDiscipleCultivationPerPhase(d, data, tables)
        val remainingCultivation = if (d.cultivation < d.maxCultivation) d.maxCultivation - d.cultivation else 0.0
        val monthsToNext = com.xianxia.sect.core.engine.LazyEvaluationDispatcher
            .estimateMonthsToNextBreakthrough(remainingCultivation, cultivationRate)
        d = d.copy(
            cultivationCompletionMonth = currentAbsoluteMonth + monthsToNext,
            cultivationCompletionPhase = 1
        )

        return d
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
    }

    fun tryBreakthrough(disciple: Disciple, pillBonus: Double = 0.0, state: MutableGameState? = null): Boolean {
        val data = state?.gameData ?: stateStore.gameData.value
        val tables = state?.discipleTables ?: stateStore.discipleTables
        val elderSlots = data.elderSlots

        val innerElderId = elderSlots.innerElder
        val innerElderComprehension = if (innerElderId.isNotEmpty() && disciple.discipleType == "inner") {
            val elderId = innerElderId.toIntOrNull()
            if (elderId != null && tables.isAlive[elderId] == 1
                && disciple.realm >= tables.realms[elderId]) {
                tables.comprehensions[elderId]
            } else { 0 }
        } else { 0 }

        val outerElderId = data.elderSlots.outerElder
        val outerElderComprehensionBonus = if (disciple.discipleType == "outer" && outerElderId.isNotEmpty()) {
            val oid = outerElderId.toIntOrNull()
            if (oid != null && tables.isAlive[oid] == 1
                && disciple.realm >= tables.realms[oid]) {
                val comp = tables.comprehensions[oid]
                if (comp >= GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) {
                    ((comp - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) / GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR) * 0.01
                } else { 0.0 }
            } else { 0.0 }
        } else { 0.0 }

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
            outerElderComprehensionBonus = outerElderComprehensionBonus,
            pillBonus = pillBonus,
            adBonus = adBonus,
            griefBreakthroughPenalty = griefBreakthroughPenalty,
            masterDiscipleBonus = masterDiscipleBonus
        )
        return Random.nextDouble() < chance
    }

    companion object {
        private const val TAG = "DiscipleBreakthroughHandler"
    }
}

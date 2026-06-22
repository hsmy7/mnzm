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
     * 实时突破处理。
     * 直接操作事务内状态 [state]，不使用异步协程，避免覆盖事务变更。
     */
    fun processRealtimeBreakthroughs(
        livingDisciples: List<Disciple>, data: GameData, state: MutableGameState
    ) {
        val candidates = livingDisciples.filter { disciple ->
            disciple.realm > 0 && disciple.cultivation >= disciple.maxCultivation && cultivationCore.isDiscipleFullHpMp(disciple)
        }

        if (candidates.isEmpty()) return

        val tables = state.discipleTables
        val candidateIds = candidates.map { it.id }.toSet()
        val allDiscipleIds = tables.ids.filter { tables.isAlive[it] == 1 }
        val updatedDisciples = allDiscipleIds.map { id ->
            val disciple = tables.assemble(id)
            if (disciple.id !in candidateIds) return@map disciple
            if (disciple.cultivation < disciple.maxCultivation || disciple.realm <= 0) return@map disciple

            var newCultivation = disciple.cultivation
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer
            var newCurrentHp = disciple.combat.currentHp
            var newCurrentMp = disciple.combat.currentMp
            var newStorageItems = disciple.equipment.storageBagItems
            var shouldContinue = true

            while (shouldContinue && newRealm > 0) {
                val currentMaxCultivation = if (newRealm == 0) Double.MAX_VALUE else {
                    val base = GameConfig.Realm.get(newRealm).cultivationBase
                    val nextBase = GameConfig.Realm.get(newRealm - 1).cultivationBase
                    val maxLayers = GameConfig.Realm.get(newRealm).maxLayers
                    base + (newRealmLayer - 1) * (nextBase - base).toDouble() / maxLayers
                }
                if (newCultivation < currentMaxCultivation) break

                val isMajorBreakthrough = newRealmLayer >= GameConfig.Realm.get(newRealm).maxLayers

                val pillTargetRealm = if (isMajorBreakthrough) newRealm - 1 else newRealm
                var pillBonus = 0.0

                val autoPill = qualifiesForSectAuto(
                    disciple, data.breakthroughAutoPillFocused, data.breakthroughAutoPillRootCounts
                ) { false }
                if (autoPill) {
                    val warehousePill = state.pills.all()
                        .filter { it.pillType == "breakthrough" && it.effects.targetRealm == pillTargetRealm }
                        .maxByOrNull { it.effects.breakthroughChance }
                    if (warehousePill != null) {
                        val pillIndex = state.pills.all().indexOf(warehousePill)
                        if (pillIndex >= 0) {
                            val updatedPills = state.pills.all().toMutableList()
                            updatedPills.removeAt(pillIndex)
                            state.pills.setItems(updatedPills)
                            pillBonus = warehousePill.effects.breakthroughChance
                        }
                    }
                }

                if (pillBonus == 0.0) {
                    val bestPill = newStorageItems
                        .filter { it.itemType == "pill" && it.effect?.pillType == "breakthrough" && it.effect?.targetRealm == pillTargetRealm }
                        .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
                    if (bestPill != null) {
                        newStorageItems = newStorageItems - bestPill
                        pillBonus = bestPill.effect?.breakthroughChance ?: 0.0
                    }
                }

                val success = tryBreakthrough(disciple, pillBonus, state)
                if (success) {
                    newCultivation = 0.0
                    if (newRealmLayer < GameConfig.Realm.get(newRealm).maxLayers) {
                        newRealmLayer++
                    } else {
                        newRealm--
                        newRealmLayer = 1
                    }
                } else {
                    newCultivation = 0.0
                    shouldContinue = false
                    val curHp = if (newCurrentHp < 0) disciple.maxHp else newCurrentHp
                    val curMp = if (newCurrentMp < 0) disciple.maxMp else newCurrentMp
                    newCurrentHp = (curHp * 0.1).toInt().coerceAtLeast(1)
                    newCurrentMp = (curMp * 0.1).toInt().coerceAtLeast(1)
                }
            }

            val cleanedStatusData = (disciple.statusData ?: emptyMap()).toMutableMap().apply {
                remove("adBreakthroughBonus")
            }

            val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                data.gameYear, data.gameMonth
            )
            val cultivationRate = cultivationCore.calculateDiscipleCultivationPerPhase(disciple, data, tables)
            val remainingCultivation = if (newCultivation < disciple.maxCultivation) disciple.maxCultivation - newCultivation else 0.0
            val monthsToNext = com.xianxia.sect.core.engine.LazyEvaluationDispatcher
                .estimateMonthsToNextBreakthrough(remainingCultivation, cultivationRate)

            disciple.copy(
                cultivation = newCultivation,
                realm = newRealm,
                realmLayer = newRealmLayer,
                lifespan = disciple.lifespan,
                combat = disciple.combat.copy(
                    currentHp = newCurrentHp,
                    currentMp = newCurrentMp
                ),
                equipment = disciple.equipment.copy(
                    storageBagItems = newStorageItems
                ),
                statusData = cleanedStatusData,
                cultivationCompletionMonth = currentAbsoluteMonth + monthsToNext,
                cultivationCompletionPhase = 1
            )
        }

        // 直接写回事务内状态
        tables.clear()
        updatedDisciples.forEach { tables.insert(it) }
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

        val chance = DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            innerElderComprehension = innerElderComprehension,
            outerElderComprehensionBonus = outerElderComprehensionBonus,
            pillBonus = pillBonus,
            adBonus = adBonus,
            griefBreakthroughPenalty = griefBreakthroughPenalty
        )
        return Random.nextDouble() < chance
    }

    private fun qualifiesForSectAuto(disciple: Disciple, focused: Boolean, rootCounts: Set<Int>, legacyCheck: (Disciple) -> Boolean): Boolean {
        if (focused || rootCounts.isNotEmpty()) {
            if (focused && disciple.statusData["followed"] == "true") return true
            val rootCount = disciple.spiritRootType.split(",").size
            return rootCount in rootCounts
        }
        return false
    }

    fun processMonthlyBreakthroughs(state: MutableGameState) {
        val data = state.gameData
        val livingDisciples = state.discipleTables.assembleAll().filter { it.isAlive }
        processRealtimeBreakthroughs(livingDisciples, data, state)
    }

    companion object {
        private const val TAG = "DiscipleBreakthroughHandler"
    }
}

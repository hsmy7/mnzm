package com.xianxia.sect.core.domain.favor

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.DiplomaticEventConfig
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 好感度相关事件处理器。
 *
 * 从 [com.xianxia.sect.core.engine.service.DiplomacyEventProcessor] 中提取的
 * 好感度专用事件逻辑：月度外交事件、好感度衰减、联盟好感度检查。
 */
@Singleton
class FavorEventProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val sharedState: CultivationSharedState
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "FavorEventProcessor"
    }

    // ═══════════ 外交月度事件 ═══════════

    /**
     * 月度外交事件处理（带每月上限的封装版本）。
     */
    fun processMonthlyFavorEventsCapped(year: Int, month: Int) {
        val currentAbsoluteMonth =
            com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        if (currentAbsoluteMonth != sharedState.diplomacyEventsMonth) {
            sharedState.diplomacyEventsMonth = currentAbsoluteMonth
            sharedState.diplomacyEventsThisMonth = 0
        }
        if (sharedState.diplomacyEventsThisMonth >= 2) return
        sharedState.diplomacyEventsThisMonth++
        processMonthlyFavorEvents(year, month)
    }

    /**
     * 月度外交事件处理：对所有关系以 1% 概率触发好感度变化事件。
     */
    fun processMonthlyFavorEvents(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id
        val updatedRelations = data.sectRelations.toMutableList()
        var relationsChanged = false

        val allEvents = DiplomaticEventConfig.Events.ALL_EVENTS

        for (relation in data.sectRelations) {
            if (kotlin.random.Random.nextDouble() >= DiplomaticEventConfig.MONTHLY_TRIGGER_CHANCE) continue

            val involvesPlayer = relation.sectId1 == playerSectId || relation.sectId2 == playerSectId
            val sect1 = data.worldMapSects.find { it.id == relation.sectId1 }
            val sect2 = data.worldMapSects.find { it.id == relation.sectId2 }
            if (sect1 == null || sect2 == null) continue

            val isSameAlignment = sect1.isRighteous == sect2.isRighteous
            val isAllied = sect1.allianceId.isNotEmpty() && sect1.allianceId == sect2.allianceId

            val eligibleEvents = allEvents.filter { event ->
                when {
                    event.requiresPlayer && !involvesPlayer -> false
                    event.requiresSameAlignment && !isSameAlignment -> false
                    event.requiresOpposingAlignment && isSameAlignment -> false
                    event.requiresAlliance && !isAllied -> false
                    else -> true
                }
            }

            if (eligibleEvents.isEmpty()) continue

            val eventDef = eligibleEvents.random()
            val favorChange = eventDef.favorChange
            val newFavor = (relation.favor + favorChange)
                .coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR)

            val index = updatedRelations.indexOfFirst {
                it.sectId1 == relation.sectId1 && it.sectId2 == relation.sectId2
            }
            if (index >= 0) {
                updatedRelations[index] = updatedRelations[index].copy(favor = newFavor)
                relationsChanged = true
            }
        }

        if (relationsChanged) {
            val newList = updatedRelations.toList()
            stateStore.update {
                gameData = gameData.copy(sectRelations = newList)
            }
        }
    }

    // ═══════════ 好感度衰减 ═══════════

    /**
     * 好感度自然衰减处理。
     * 仅处理玩家相关的关系：好感度超过阈值且超过设定年数未送礼时衰减。
     */
    fun processFavorDecay(currentYear: Int) {
        val data = stateStore.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return

        val updatedRelations = data.sectRelations.map { relation ->
            val involvesPlayer =
                relation.sectId1 == playerSect.id || relation.sectId2 == playerSect.id
            if (!involvesPlayer) return@map relation

            if (!FavorDomain.shouldDecay(relation, currentYear)) return@map relation

            val newFavor = FavorDomain.calculateDecayedFavor(relation)
            relation.copy(
                favor = newFavor,
                noGiftYears = relation.noGiftYears + 1
            )
        }

        val hasChanges = updatedRelations.zip(data.sectRelations).any { (a, b) -> a != b }
        if (hasChanges) {
            stateStore.update {
                gameData = gameData.copy(sectRelations = updatedRelations)
            }
        }
    }

    // ═══════════ 联盟好感度检查 ═══════════

    /**
     * 检查好感度过低导致联盟自动解散。
     * 当玩家与盟友的好感度低于 MIN_ALLIANCE_FAVOR 时自动解除盟约。
     */
    fun checkAllianceFavorDrop() {
        val data = stateStore.gameData.value
        val dissolvedAlliances = mutableListOf<com.xianxia.sect.core.model.Alliance>()
        val playerSect = data.worldMapSects.find { it.isPlayerSect }

        data.alliances.forEach { alliance ->
            if (!alliance.sectIds.contains("player")) return@forEach

            val sectId = alliance.sectIds.find { it != "player" }
            if (sectId != null && playerSect != null) {
                val sect = data.worldMapSects.find { it.id == sectId }
                val favor = FavorDomain.findFavor(
                    data.sectRelations, playerSect.id, sectId
                )
                if (favor < GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR) {
                    dissolvedAlliances.add(alliance)
                }
            }
        }

        if (dissolvedAlliances.isNotEmpty()) {
            val updatedAlliances = data.alliances.filter { it !in dissolvedAlliances }
            val updatedSects = data.worldMapSects.map { sect ->
                if (dissolvedAlliances.any { it.sectIds.contains(sect.id) }) {
                    sect.copy(allianceId = "", allianceStartYear = 0)
                } else sect
            }
            stateStore.update {
                gameData = gameData.copy(
                    alliances = updatedAlliances,
                    worldMapSects = updatedSects
                )
            }
        }
    }
}

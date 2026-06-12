package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.DiplomaticEventConfig
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiplomacyEventProcessor @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val sharedState: CultivationSharedState
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "DiplomacyEventProc"
    }

    // ── 状态访问器 ──────────────────────────────────────────────────────

    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    // ── 外交月度事件 ──────────────────────────────────────────────────

    fun processDiplomacyMonthlyEventsCapped(year: Int, month: Int) {
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        if (currentAbsoluteMonth != sharedState.diplomacyEventsMonth) {
            sharedState.diplomacyEventsMonth = currentAbsoluteMonth
            sharedState.diplomacyEventsThisMonth = 0
        }
        if (sharedState.diplomacyEventsThisMonth >= 2) return
        sharedState.diplomacyEventsThisMonth++
        processDiplomacyMonthlyEvents(year, month)
    }

    fun processDiplomacyMonthlyEvents(year: Int, month: Int) {
        val data = currentGameData
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
            val newFavor = (relation.favor + favorChange).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR)

            val index = updatedRelations.indexOfFirst { it.sectId1 == relation.sectId1 && it.sectId2 == relation.sectId2 }
            if (index >= 0) {
                updatedRelations[index] = updatedRelations[index].copy(favor = newFavor)
                relationsChanged = true
            }
        }

        if (relationsChanged) {
            currentGameData = data.copy(sectRelations = updatedRelations.toList())
        }
    }

    fun processFavorDecay(currentYear: Int) {
        val data = currentGameData
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return

        val updatedRelations = data.sectRelations.map { relation ->
            val involvesPlayer = relation.sectId1 == playerSect.id || relation.sectId2 == playerSect.id
            if (!involvesPlayer) return@map relation

            if (relation.favor <= GameConfig.Diplomacy.FAVOR_DECAY_THRESHOLD) return@map relation

            val yearsSinceGift = currentYear - relation.lastInteractionYear
            if (yearsSinceGift < GameConfig.Diplomacy.FAVOR_DECAY_NO_GIFT_YEARS) return@map relation

            val newFavor = (relation.favor - GameConfig.Diplomacy.FAVOR_DECAY_AMOUNT).coerceAtLeast(GameConfig.Diplomacy.FAVOR_DECAY_THRESHOLD)
            relation.copy(favor = newFavor, noGiftYears = relation.noGiftYears + 1)
        }

        val hasChanges = updatedRelations.zip(data.sectRelations).any { (a, b) -> a != b }
        if (hasChanges) {
            currentGameData = data.copy(sectRelations = updatedRelations)
        }
    }

    fun checkAllianceExpiry(year: Int) {
        val data = currentGameData
        val expiredAlliances = data.alliances.filter { year - it.startYear >= GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }

        if (expiredAlliances.isEmpty()) return

        val updatedAlliances = data.alliances.filter { year - it.startYear < GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }
        val updatedSects = data.worldMapSects.map { sect ->
            if (expiredAlliances.any { it.sectIds.contains(sect.id) }) {
                sect.copy(allianceId = "", allianceStartYear = 0)
            } else sect
        }

        currentGameData = data.copy(
            alliances = updatedAlliances,
            worldMapSects = updatedSects
        )
    }

    fun checkAllianceFavorDrop() {
        val data = currentGameData
        val dissolvedAlliances = mutableListOf<Alliance>()
        val playerSect = data.worldMapSects.find { it.isPlayerSect }

        data.alliances.forEach { alliance ->
            if (!alliance.sectIds.contains("player")) return@forEach

            val sectId = alliance.sectIds.find { it != "player" }
            if (sectId != null && playerSect != null) {
                val sect = data.worldMapSects.find { it.id == sectId }
                val relation = data.sectRelations.find {
                    (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                    (it.sectId1 == sectId && it.sectId2 == playerSect.id)
                }
                val favor = relation?.favor ?: 0
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
            currentGameData = data.copy(
                alliances = updatedAlliances,
                worldMapSects = updatedSects
            )
        }
    }

    fun processAIAlliances(year: Int) {
        // AI宗门自动结盟逻辑尚未实现，保留为扩展点。
    }

    fun processCrossSectPartnerMatching(year: Int, month: Int) {
        // 跨宗门联姻系统尚未实现，保留为扩展点。
    }
}

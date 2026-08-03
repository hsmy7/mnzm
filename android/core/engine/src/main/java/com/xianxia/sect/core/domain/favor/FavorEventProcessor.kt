package com.xianxia.sect.core.domain.favor

import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.engine.service.CultivationSharedState
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
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

    // ═══════════ 好感度衰减 ═══════════

    /**
     * 好感度自然衰减处理。
     * 仅处理玩家相关的关系：好感度超过阈值且超过设定年数未送礼时衰减。
     */
    fun processFavorDecay(currentYear: Int) {
        stateStore.update {
            val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return@update

            val updatedRelations = gameData.sectRelations.map { relation ->
                // 跳过未相识的关系
                if (!relation.acquainted) return@map relation

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

            val hasChanges = updatedRelations.zip(gameData.sectRelations).any { (a, b) -> a != b }
            if (hasChanges) {
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
        stateStore.update {
            val dissolvedAlliances = mutableListOf<com.xianxia.sect.core.model.Alliance>()
            val playerSect = gameData.worldMapSects.find { it.isPlayerSect }

            gameData.alliances.forEach { alliance ->
                if (!alliance.sectIds.contains("player")) return@forEach

                val sectId = alliance.sectIds.find { it != "player" }
                if (sectId != null && playerSect != null) {
                    val sect = gameData.worldMapSects.find { it.id == sectId }
                    val favor = FavorDomain.findFavor(
                        gameData.sectRelations, playerSect.id, sectId
                    )
                    if (favor < com.xianxia.sect.core.config.FavorConfig.MIN_ALLIANCE_FAVOR) {
                        dissolvedAlliances.add(alliance)
                    }
                }
            }

            if (dissolvedAlliances.isNotEmpty()) {
                val updatedAlliances = gameData.alliances.filter { it !in dissolvedAlliances }
                val updatedSects = gameData.worldMapSects.map { sect ->
                    if (dissolvedAlliances.any { it.sectIds.contains(sect.id) }) {
                        sect.copy(allianceId = "", allianceStartYear = 0)
                    } else sect
                }
                gameData = gameData.copy(
                    alliances = updatedAlliances,
                    worldMapSects = updatedSects
                )
            }
        }
    }
}

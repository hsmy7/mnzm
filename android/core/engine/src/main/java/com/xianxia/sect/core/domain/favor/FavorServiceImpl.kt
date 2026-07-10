package com.xianxia.sect.core.domain.favor

import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 好感度系统业务接口实现。
 *
 * 注入 GameStateStore 访问游戏状态，委托 [FavorDomain] 做所有纯计算。
 * 所有好感度修改通过 scope.launch + stateStore.update 原子写入。
 */
@Singleton
class FavorServiceImpl @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider
) : FavorService {

    private val scope get() = scopeProvider.scope

    override fun getFavor(sectId: String): Int {
        val data = stateStore.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return 0
        return FavorDomain.findFavor(data.sectRelations, playerSect.id, sectId)
    }

    override fun getFavorLevel(sectId: String): SectRelationLevel {
        return FavorDomain.getLevel(getFavor(sectId))
    }

    override fun getTradePriceMultiplier(sectId: String): Double {
        val data = stateStore.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return 1.0
        return FavorDomain.calculateTradePriceMultiplier(
            data.sectRelations,
            data.alliances,
            sectId,
            playerSect.id
        )
    }

    override fun getRejectProbability(sectLevel: Int, rarity: Int): Int {
        return FavorDomain.calculateRejectProbability(sectLevel, rarity)
    }

    override fun updateFavor(sectId: String, newFavor: Int, year: Int) {
        scope.launch {
            stateStore.update {
                val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return@update
                val updated = FavorDomain.updateFavor(
                    gameData.sectRelations,
                    playerSect.id,
                    sectId,
                    newFavor,
                    year
                )
                gameData = gameData.copy(sectRelations = updated)
            }
        }
    }

    override fun modifyFavor(sectId: String, delta: Int) {
        scope.launch {
            stateStore.update {
                val playerSect = gameData.worldMapSects.find { it.isPlayerSect } ?: return@update
                val updated = FavorDomain.modifyFavor(
                    gameData.sectRelations,
                    playerSect.id,
                    sectId,
                    delta
                )
                gameData = gameData.copy(sectRelations = updated)
            }
        }
    }
}

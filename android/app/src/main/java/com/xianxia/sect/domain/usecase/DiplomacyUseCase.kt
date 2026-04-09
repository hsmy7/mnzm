@file:Suppress("DEPRECATION")

package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiplomacyUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class GiftSpiritStonesParams(
        val sectId: String,
        val tier: Int,
        val currentSpiritStones: Long
    )
    
    sealed class GiftResult {
        data class Success(val favorIncrease: Int, val newFavor: Int) : GiftResult()
        data class Error(val message: String) : GiftResult()
    }
    
    data class AllianceParams(
        val sectId: String,
        val envoyDiscipleId: String,
        val disciples: List<Disciple>,
        val currentSpiritStones: Long
    )
    
    sealed class AllianceResult {
        data class Success(val allianceYears: Int) : AllianceResult()
        data class Error(val message: String) : AllianceResult()
    }
    
    fun giftSpiritStones(params: GiftSpiritStonesParams): GiftResult {
        val cost = getSpiritStoneCost(params.tier)
        if (params.currentSpiritStones < cost) {
            return GiftResult.Error("灵石不足，需要${cost}灵石")
        }
        
        val result = gameEngine.giftSpiritStones(params.sectId, params.tier)
        return GiftResult.Success(favorIncrease = result.favorChange, newFavor = result.newFavor)
    }
    
    fun giftItem(
        sectId: String,
        itemId: String,
        itemType: String,
        quantity: Int
    ): GiftResult {
        val result = gameEngine.giftItem(sectId, itemId, itemType, quantity)
        return GiftResult.Success(favorIncrease = result.favorChange, newFavor = result.newFavor)
    }
    
    fun requestAlliance(params: AllianceParams): AllianceResult {
        val disciple = params.disciples.find { it.id == params.envoyDiscipleId }
            ?: return AllianceResult.Error("使者弟子不存在")
        
        val gameData = gameEngine.gameData.value
        val targetSect = gameData.worldMapSects.find { it.id == params.sectId }
            ?: return AllianceResult.Error("目标宗门不存在")
        
        val requiredRealm = gameEngine.getEnvoyRealmRequirement(targetSect.level)
        if (disciple.realm > requiredRealm) {
            return AllianceResult.Error("使者境界不足，需要${requiredRealm}境界以上")
        }
        
        val allianceCost = gameEngine.getAllianceCost(targetSect.level)
        if (params.currentSpiritStones < allianceCost) {
            return AllianceResult.Error("灵石不足，需要${allianceCost}灵石")
        }
        
        val conditions = gameEngine.checkAllianceConditions(params.sectId, params.envoyDiscipleId)
        if (!conditions.first) {
            return AllianceResult.Error(conditions.second)
        }
        
        gameEngine.requestAlliance(params.sectId, params.envoyDiscipleId)
        return AllianceResult.Success(allianceYears = 10)
    }
    
    fun dissolveAlliance(sectId: String): AllianceResult {
        if (!gameEngine.isAlly(sectId)) {
            return AllianceResult.Error("该宗门不是你的盟友")
        }
        
        gameEngine.dissolveAlliance(sectId)
        return AllianceResult.Success(allianceYears = 0)
    }
    
    private fun getSpiritStoneCost(tier: Int): Long {
        return when (tier) {
            1 -> 10_000L
            2 -> 50_000L
            3 -> 100_000L
            else -> 10_000L
        }
    }
}

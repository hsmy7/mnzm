package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * ## DiplomacyUseCase - 外交用例
 *
 * ### [H-09] 过度工程化评估
 *
 * **总体评价**: 核心方法有价值，部分简单查询为纯代理
 *
 * **保留的方法** (有业务逻辑):
 * - `giftSpiritStones()`: 灵石成本检查 + 结果封装
 * - `giftItem()`: 结果封装
 * - `requestAlliance()`: 复杂的条件验证 (使者、境界、灵石、联盟条件)
 * - `dissolveAlliance()`: 盟友检查
 *
 * **@Deprecated 的方法** (纯代理):
 * - `isAlly()`: 直接转发
 * - `getAllianceCost()`: 直接转发
 * - `getAllianceRemainingYears()`: 直接转发
 * - `getPlayerAllies()`: 简单的 map 操作
 */
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

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.isAlly() directly.",
        ReplaceWith("gameEngine.isAlly(sectId)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun isAlly(sectId: String): Boolean = gameEngine.isAlly(sectId)

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.getAllianceCost() directly.",
        ReplaceWith("gameEngine.getAllianceCost(sectLevel)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun getAllianceCost(sectLevel: Int): Long = gameEngine.getAllianceCost(sectLevel)

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.getAllianceRemainingYears() directly.",
        ReplaceWith("gameEngine.getAllianceRemainingYears(sectId)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun getAllianceRemainingYears(sectId: String): Int = gameEngine.getAllianceRemainingYears(sectId)

    // H-09: 简单 map 操作
    @Deprecated(
        "Simple map operation. Use gameEngine.getPlayerAllies() directly.",
        ReplaceWith("gameEngine.getPlayerAllies()", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun getPlayerAllies(): List<String> = gameEngine.getPlayerAllies()
    
    private fun getSpiritStoneCost(tier: Int): Long {
        return when (tier) {
            1 -> 10_000L
            2 -> 50_000L
            3 -> 100_000L
            else -> 10_000L
        }
    }
}

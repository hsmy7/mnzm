package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.model.MerchantItem

interface DiplomacyFacade {
    fun giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean = false): DiplomacyService.GiftResult
    suspend fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String>
    fun dissolveAlliance(sectId: String): Pair<Boolean, String>
    fun getRejectProbability(sectLevel: Int, rarity: Int): Int
    fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int>
    fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double
    fun getEnvoyRealmRequirement(sectLevel: Int): Int
    fun getAllianceCost(sectLevel: Int): Long
    fun generateSectTradeItems(year: Int): List<MerchantItem>
    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem>
    fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1)
    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1)
    fun isAlly(sectId: String): Boolean
    fun getAllianceRemainingYears(sectId: String): Int
    fun getPlayerAllies(): List<String>
}

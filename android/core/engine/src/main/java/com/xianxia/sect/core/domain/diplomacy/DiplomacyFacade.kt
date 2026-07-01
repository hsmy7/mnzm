package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.model.MerchantItem

interface DiplomacyFacade {
    fun giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean = false): DiplomacyService.GiftResult
    suspend fun requestAllianceSimple(sectId: String): Boolean
    suspend fun dissolveAllianceSimple(sectId: String): Boolean
    fun isAlly(sectId: String): Boolean
    fun getPlayerAllies(): List<String>
    fun generateSectTradeItems(year: Int): List<MerchantItem>
    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem>
    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1)
}

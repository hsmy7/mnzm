package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.domain.favor.GiftResult
import com.xianxia.sect.core.model.MerchantItem


@Suppress("TooManyFunctions") // 外交域门面契约：关系/联盟/战书端口协议面
interface DiplomacyFacade {
    suspend fun giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean = false): GiftResult
    suspend fun requestAllianceSimple(sectId: String): Boolean
    suspend fun dissolveAllianceSimple(sectId: String): Boolean
    fun isAlly(sectId: String): Boolean
    fun getPlayerAllies(): List<String>
    suspend fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem>
    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1)

    // ═══ 附属宗门 ═══
    suspend fun requestVassalContract(sectId: String): Boolean
    suspend fun dissolveVassalContract(sectId: String): Boolean
    fun isPlayerVassal(sectId: String): Boolean
    fun getPlayerVassals(): List<String>
}

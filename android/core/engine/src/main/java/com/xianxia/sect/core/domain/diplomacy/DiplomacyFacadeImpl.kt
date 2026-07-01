package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiplomacyFacadeImpl @Inject constructor(
    private val diplomacyService: DiplomacyService,
    private val stateStore: GameStateStore
) : DiplomacyFacade {

    override fun giftSpiritStones(sectId: String, tier: Int, bypassYearLimit: Boolean): DiplomacyService.GiftResult =
        diplomacyService.giftSpiritStones(sectId, tier, bypassYearLimit)

    override suspend fun requestAllianceSimple(sectId: String): Boolean =
        diplomacyService.requestAllianceSimple(sectId)

    override suspend fun dissolveAllianceSimple(sectId: String): Boolean =
        diplomacyService.dissolveAllianceSimple(sectId)

    override fun isAlly(sectId: String): Boolean {
        val data = stateStore.gameData.value
        return data.alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
    }

    override fun getPlayerAllies(): List<String> {
        val data = stateStore.gameData.value
        val playerAlliance = data.alliances.find { it.sectIds.contains("player") } ?: return emptyList()
        return playerAlliance.sectIds.filter { it != "player" }
    }

    override fun generateSectTradeItems(year: Int): List<MerchantItem> =
        diplomacyService.generateSectTradeItems(year)

    override fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> =
        diplomacyService.getOrRefreshSectTradeItems(sectId)

    override suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int) =
        diplomacyService.buyFromSectTradeSync(sectId, itemId, quantity)
}

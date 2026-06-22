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

    override fun giftSpiritStones(sectId: String, tier: Int): DiplomacyService.GiftResult =
        diplomacyService.giftSpiritStones(sectId, tier)

    override suspend fun requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> =
        diplomacyService.requestAlliance(sectId, envoyDiscipleId)

    override fun dissolveAlliance(sectId: String): Pair<Boolean, String> =
        diplomacyService.dissolveAlliance(sectId)

    override fun getRejectProbability(sectLevel: Int, rarity: Int): Int =
        diplomacyService.getRejectProbability(sectLevel, rarity)

    override fun checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> =
        diplomacyService.checkAllianceConditions(sectId, envoyDiscipleId)

    override fun calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double =
        diplomacyService.calculatePersuasionSuccessRate(favorability, intelligence, charm)

    override fun getEnvoyRealmRequirement(sectLevel: Int): Int =
        diplomacyService.getEnvoyRealmRequirement(sectLevel)

    override fun getAllianceCost(sectLevel: Int): Long =
        diplomacyService.getAllianceCost(sectLevel)

    override fun generateSectTradeItems(year: Int): List<MerchantItem> =
        diplomacyService.generateSectTradeItems(year)

    override fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> =
        diplomacyService.getOrRefreshSectTradeItems(sectId)

    override fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int) =
        diplomacyService.buyFromSectTrade(sectId, itemId, quantity)

    override suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int) =
        diplomacyService.buyFromSectTradeSync(sectId, itemId, quantity)

    override fun isAlly(sectId: String): Boolean {
        val data = stateStore.gameData.value
        return data.alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
    }

    override fun getAllianceRemainingYears(sectId: String): Int {
        val data = stateStore.gameData.value
        val alliance = data.alliances.find { it.sectIds.contains("player") && it.sectIds.contains(sectId) } ?: return 0
        val sect = data.worldMapSects.find { it.id == sectId }
        val startYear = if (sect?.allianceStartYear != null && sect.allianceStartYear > 0) {
            sect.allianceStartYear
        } else {
            alliance.startYear
        }
        val elapsed = data.gameYear - startYear
        return (GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS - elapsed).coerceAtLeast(0)
    }

    override fun getPlayerAllies(): List<String> {
        val data = stateStore.gameData.value
        val playerAlliance = data.alliances.find { it.sectIds.contains("player") } ?: return emptyList()
        return playerAlliance.sectIds.filter { it != "player" }
    }
}

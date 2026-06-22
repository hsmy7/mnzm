package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.DomainLog

fun GameEngine.generateSectTradeItems(year: Int): List<MerchantItem> = diplomacyFacade.generateSectTradeItems(year)
fun GameEngine.getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> = diplomacyFacade.getOrRefreshSectTradeItems(sectId)
fun GameEngine.buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) = diplomacyFacade.buyFromSectTrade(sectId, itemId, quantity)
suspend fun GameEngine.buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) = diplomacyFacade.buyFromSectTradeSync(sectId, itemId, quantity)
fun GameEngine.giftSpiritStones(sectId: String, tier: Int): GiftResult = diplomacyFacade.giftSpiritStones(sectId, tier)
suspend fun GameEngine.requestAlliance(sectId: String, envoyDiscipleId: String): Pair<Boolean, String> = diplomacyFacade.requestAlliance(sectId, envoyDiscipleId)
fun GameEngine.dissolveAlliance(sectId: String): Pair<Boolean, String> = diplomacyFacade.dissolveAlliance(sectId)
fun GameEngine.getRejectProbability(sectLevel: Int, rarity: Int): Int = diplomacyFacade.getRejectProbability(sectLevel, rarity)
fun GameEngine.checkAllianceConditions(sectId: String, envoyDiscipleId: String): Triple<Boolean, String, Int> = diplomacyFacade.checkAllianceConditions(sectId, envoyDiscipleId)
fun GameEngine.calculatePersuasionSuccessRate(favorability: Int, intelligence: Int, charm: Int): Double = diplomacyFacade.calculatePersuasionSuccessRate(favorability, intelligence, charm)
fun GameEngine.getEnvoyRealmRequirement(sectLevel: Int): Int = diplomacyFacade.getEnvoyRealmRequirement(sectLevel)
fun GameEngine.getAllianceCost(sectLevel: Int): Long = diplomacyFacade.getAllianceCost(sectLevel)
fun GameEngine.isAlly(sectId: String): Boolean = diplomacyFacade.isAlly(sectId)
fun GameEngine.getAllianceRemainingYears(sectId: String): Int = diplomacyFacade.getAllianceRemainingYears(sectId)
fun GameEngine.getPlayerAllies(): List<String> = diplomacyFacade.getPlayerAllies()
fun GameEngine.interactWithSect(sectId: String, action: String) { DomainLog.w("GameEngine", "interactWithSect 尚未实现: sectId=$sectId, action=$action") }

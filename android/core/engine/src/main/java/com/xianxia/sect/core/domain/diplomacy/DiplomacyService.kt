package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.domain.favor.FavorService
import com.xianxia.sect.core.event.BattleCompletedEvent
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.config.GiftConfig
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import java.util.UUID

/**
 * 外交服务 — 管理宗门之间的联盟、交易。
 *
 * 送礼相关逻辑已移至 [com.xianxia.sect.core.domain.favor.GiftService]，
 * 好感度相关方法和查询委托 [FavorDomain] 和 [FavorService]。
 */
@Singleton
class DiplomacyService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val eventBus: EventBusPort,
    private val favorService: FavorService,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    private val discipleTables: DiscipleTables
        get() = stateStore.discipleTables

    companion object {
        private const val TAG = "DiplomacyService"
    }

    // ==================== 联盟系统 ====================

    /**
     * 简化版结盟请求（聊天流使用）
     * 无灵石费用、无需envoyDiscipleId、无需游说弟子
     * 仅检查好感度 + 已有盟约 + 概率随机判定
     */
    suspend fun requestAllianceSimple(sectId: String): Boolean {
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
            ?: return false

        if (sect.isPlayerSect) return false
        if (sect.allianceId.isNotEmpty()) return false

        // 检查玩家是否已有盟约
        val existingAlliance = data.alliances.any { it.sectIds.contains("player") }
        if (existingAlliance) return false

        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val favor = if (playerSect != null) {
            FavorDomain.findFavor(data.sectRelations, playerSect.id, sectId)
        } else 0

        // 按好感度计算成功概率
        val successChance = FavorDomain.calculateAllianceSuccessChance(favor)
        val success = Random.nextDouble() < successChance

        if (success) {
            stateStore.update {
                val alliance = Alliance(
                    id = UUID.randomUUID().toString(),
                    sectIds = listOf("player", sectId),
                    startYear = gameData.gameYear,
                    initiatorId = "player"
                )
                gameData = gameData.copy(
                    alliances = gameData.alliances + alliance,
                    worldMapSects = gameData.worldMapSects.map { s ->
                        when {
                            s.id == sectId -> s.copy(allianceId = alliance.id, allianceStartYear = gameData.gameYear)
                            s.isPlayerSect -> s.copy(allianceId = alliance.id, allianceStartYear = gameData.gameYear)
                            else -> s
                        }
                    }
                )
            }
        }

        return success
    }

    /**
     * 简化版解除结盟（聊天流使用）
     * 无灵石惩罚
     */
    suspend fun dissolveAllianceSimple(sectId: String): Boolean {
        var success = false
        stateStore.update {
            val sect = gameData.worldMapSects.find { it.id == sectId } ?: return@update
            if (sect.allianceId.isEmpty()) return@update
            val alliance = gameData.alliances.find { it.id == sect.allianceId } ?: return@update

            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { s ->
                    if (alliance.sectIds.contains(s.id)) s.copy(allianceId = "", allianceStartYear = 0)
                    else s
                },
                alliances = gameData.alliances.filter { it.id != alliance.id }
            )
            success = true
        }
        return success
    }

    // ==================== 宗门交易系统 ====================

    private data class SectTradeValidation(
        val sect: WorldSect,
        val item: MerchantItem,
        val actualQuantity: Int,
        val totalPrice: Long,
        val updatedSectDetails: Map<String, SectDetail>
    )

    private val SECT_TRADE_RARITY_PROBABILITIES = mapOf(
        6 to 0.003,
        5 to 0.027,
        4 to 0.05,
        3 to 0.12,
        2 to 0.40,
        1 to 0.40
    )

    fun generateSectTradeItems(year: Int, sectId: String? = null): List<MerchantItem> {
        val items = mutableListOf<MerchantItem>()
        val random = if (sectId != null) {
            Random(sectId.hashCode().toLong() + year)
        } else {
            Random.Default
        }

        val itemCount = 20
        val generatedNames = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = itemCount * 3

        while (items.size < itemCount && attempts < maxAttempts) {
            attempts++
            val type = listOf("equipment", "manual", "pill", "material", "herb", "seed", "spiritStone").random(random)
            val rarity = selectRarityByMerchantProbabilities(random)

            fun calcStock(t: String, r: Int): Int {
                val isConsumable = t in listOf("herb", "seed", "material")
                return if (isConsumable) {
                    when (r) {
                        6 -> random.nextInt(3, 8)
                        5 -> random.nextInt(3, 8)
                        4 -> random.nextInt(5, 11)
                        3 -> random.nextInt(5, 13)
                        2 -> random.nextInt(5, 16)
                        else -> random.nextInt(7, 16)
                    }
                } else {
                    when (r) {
                        6 -> random.nextInt(1, 4)
                        5 -> random.nextInt(1, 4)
                        4 -> random.nextInt(1, 6)
                        3 -> random.nextInt(1, 6)
                        2 -> random.nextInt(1, 6)
                        else -> random.nextInt(1, 6)
                    }
                }
            }

            val item = when (type) {
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(rarity, rarity)
                    val template = EquipmentDatabase.getTemplateByName(equipment.name)
                    val basePrice = (template?.price ?: GameConfig.Rarity.get(rarity).basePrice).toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = equipment.name,
                        type = "equipment",
                        itemId = equipment.id,
                        rarity = equipment.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(rarity, rarity)
                    val template = ManualDatabase.getByName(manual.name)
                    val basePrice = (template?.price ?: GameConfig.Rarity.get(rarity).basePrice).toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = manual.name,
                        type = "manual",
                        itemId = manual.id,
                        rarity = manual.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "pill" -> {
                    val pillTemplates = ItemDatabase.getPillsByRarity(rarity)
                    if (pillTemplates.isEmpty()) continue
                    val template = pillTemplates.random(random)
                    val pill = ItemDatabase.createPillFromTemplate(template)
                    val basePrice = template.price.toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = pill.name,
                        type = "pill",
                        itemId = pill.id,
                        rarity = pill.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1,
                        grade = pill.grade.displayName
                    )
                }
                "material" -> {
                    val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
                    if (materials.isEmpty()) continue
                    val material = materials.random(random)
                    val basePrice = material.price.toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = material.name,
                        type = "material",
                        itemId = material.id,
                        rarity = material.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "herb" -> {
                    val herbs = HerbDatabase.getByRarity(rarity)
                    if (herbs.isEmpty()) continue
                    val herb = herbs.random(random)
                    val basePrice = herb.price.toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = herb.name,
                        type = "herb",
                        itemId = herb.id,
                        rarity = herb.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "seed" -> {
                    val seeds = HerbDatabase.getSeedsByRarity(rarity)
                    if (seeds.isEmpty()) continue
                    val seed = seeds.random(random)
                    val basePrice = seed.price.toLong()
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = seed.name,
                        type = "seed",
                        itemId = seed.id,
                        rarity = seed.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "spiritStone" -> {
                    val isHigh = rarity >= 4
                    val name = if (isHigh) "上品灵石" else "中品灵石"
                    val itemRarity = if (isHigh) 4 else 3
                    val basePrice = if (isHigh) {
                        SpiritStoneExchange.RATIO * SpiritStoneExchange.RATIO
                    } else {
                        SpiritStoneExchange.RATIO
                    }
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        type = "spiritStone",
                        itemId = UUID.randomUUID().toString(),
                        rarity = itemRarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity).coerceAtMost(3),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                else -> continue
            }

            if (!generatedNames.contains(item.name)) {
                generatedNames.add(item.name)
                items.add(item)
            }
        }

        items.sortByDescending { it.rarity }
        return items
    }

    suspend fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> {
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return emptyList()
        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)

        val currentYear = data.gameYear
        val shouldRefresh = currentYear - sectDetail.tradeLastRefreshYear >= 3 || sectDetail.tradeItems.isEmpty()

        if (shouldRefresh) {
            val newItems = generateSectTradeItems(currentYear, sectId)
            stateStore.modifyState {
                val updatedSectDetails = gameData.sectDetails.toMutableMap()
                updatedSectDetails[sectId] = (gameData.sectDetails[sectId] ?: SectDetail(sectId = sectId)).copy(
                    tradeItems = newItems,
                    tradeLastRefreshYear = currentYear
                )
                gameData = gameData.copy(sectDetails = updatedSectDetails)
            }
            return newItems
        }

        return sectDetail.tradeItems
    }

    private fun validateSectTrade(data: GameData, sectId: String, itemId: String, quantity: Int): SectTradeValidation? {
        val sect = data.worldMapSects.find { it.id == sectId } ?: return null
        val sectDetail = data.sectDetails[sectId]
        val tradeItems = sectDetail?.tradeItems ?: emptyList()
        val item = tradeItems.find { it.id == itemId } ?: return null

        val relation = favorService.getFavor(sectId)
        val relationLevel = FavorDomain.getLevel(relation)
        if (relationLevel !in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY, SectRelationLevel.INTIMATE)) {
            return null
        }

        val maxAllowedRarity = relationLevel.maxAllowedRarity
        if (item.rarity > maxAllowedRarity) {
            return null
        }

        val actualQuantity = minOf(quantity, item.quantity)
        val priceMultiplier = favorService.getTradePriceMultiplier(sectId)
        val totalPrice = (item.price * priceMultiplier).toLong() * actualQuantity

        if (data.spiritStones < totalPrice) {
            return null
        }

        val capacityOk = when (item.type.lowercase()) {
            "equipment" -> inventorySystem.canAddItems(actualQuantity)
            "manual" -> {
                val t = ManualDatabase.getByName(item.name)
                inventorySystem.canAddManual(item.name, item.rarity, t?.type ?: ManualType.SUPPORT)
            }
            "pill" -> {
                val t = PillRecipeDatabase.getRecipeByName(item.name)
                val grade = item.grade?.let { gn -> PillGrade.entries.find { it.displayName == gn } } ?: PillGrade.MEDIUM
                inventorySystem.canAddPill(item.name, item.rarity, t?.category ?: PillCategory.FUNCTIONAL, grade)
            }
            "material" -> {
                val t = BeastMaterialDatabase.getMaterialByName(item.name)
                val cat = t?.category?.let { try { MaterialCategory.valueOf(it) } catch (e: IllegalArgumentException) { MaterialCategory.BEAST_HIDE } } ?: MaterialCategory.BEAST_HIDE
                inventorySystem.canAddMaterial(item.name, item.rarity, cat)
            }
            "herb" -> {
                val t = HerbDatabase.getHerbByName(item.name)
                inventorySystem.canAddHerb(item.name, item.rarity, t?.category ?: "spirit")
            }
            "seed" -> {
                val t = HerbDatabase.getSeedByName(item.name)
                inventorySystem.canAddSeed(item.name, item.rarity, t?.growTime ?: 12)
            }
            "spiritstone" -> true
            else -> false
        }
        if (!capacityOk) {
            return null
        }

        val updatedTradeItems = if (item.quantity > actualQuantity) {
            tradeItems.map {
                if (it.id == itemId) it.copy(quantity = it.quantity - actualQuantity)
                else it
            }
        } else {
            tradeItems.filter { it.id != itemId }
        }

        val updatedSectDetails = data.sectDetails.toMutableMap()
        if (sectDetail != null) {
            updatedSectDetails[sectId] = sectDetail.copy(tradeItems = updatedTradeItems)
        }

        return SectTradeValidation(sect, item, actualQuantity, totalPrice, updatedSectDetails)
    }

    @Deprecated(
        "Use buyFromSectTradeSync() — scope.launch 在 swapFromShadow 期间存在竞态风险",
        ReplaceWith("buyFromSectTradeSync(sectId, itemId, quantity)")
    )
    suspend fun buyFromSectTrade(sectId: String, itemId: String, quantity: Int = 1) {
        stateStore.update {
            val v = validateSectTrade(gameData, sectId, itemId, quantity) ?: return@update
            val deductResult = spiritStoneWallet.deduct(this, v.totalPrice, SpiritStoneGrade.LOW, SpiritStoneReason.Purchase, SpiritStoneSource.MerchantTrade)
            if (deductResult !is DeductResult.Success) {
                return@update
            }
            gameData = gameData.copy(
                sectDetails = v.updatedSectDetails
            )
            addSectTradeItemToMutableState(v.item, v.actualQuantity)
        }
    }

    suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) {
        stateStore.update {
            val v = validateSectTrade(gameData, sectId, itemId, quantity) ?: return@update
            val deductResult = spiritStoneWallet.deduct(this, v.totalPrice, SpiritStoneGrade.LOW, SpiritStoneReason.Purchase, SpiritStoneSource.MerchantTrade)
            if (deductResult !is DeductResult.Success) {
                return@update
            }
            gameData = gameData.copy(
                sectDetails = v.updatedSectDetails
            )
            addSectTradeItemToMutableState(v.item, v.actualQuantity)
        }
    }

    private fun MutableGameState.addSectTradeItemToMutableState(item: MerchantItem, actualQuantity: Int) {
        when (item.type.lowercase()) {
            "equipment" -> {
                val eq = MerchantItemConverter.toEquipment(item).copy(quantity = actualQuantity)
                val existing = equipmentStacks.find { it.name == eq.name && it.rarity == eq.rarity && it.slot == eq.slot }
                if (existing != null) {
                    val newQty = (existing.quantity + eq.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
                    equipmentStacks = equipmentStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    equipmentStacks = equipmentStacks + eq
                }
            }
            "manual" -> {
                val m = MerchantItemConverter.toManual(item).copy(quantity = actualQuantity)
                val existing = manualStacks.find { it.name == m.name && it.rarity == m.rarity && it.type == m.type }
                if (existing != null) {
                    val newQty = (existing.quantity + m.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("manual_stack"))
                    manualStacks = manualStacks.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    manualStacks = manualStacks + m
                }
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(item).copy(quantity = actualQuantity)
                val existing = pills.find { it.name == p.name && it.rarity == p.rarity && it.category == p.category && it.grade == p.grade }
                if (existing != null) {
                    val newQty = (existing.quantity + p.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                    pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    pills = pills + p
                }
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(item).copy(quantity = actualQuantity)
                val existing = materials.find { it.name == m.name && it.rarity == m.rarity && it.category == m.category }
                if (existing != null) {
                    val newQty = (existing.quantity + m.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                    materials = materials.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    materials = materials + m
                }
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(item).copy(quantity = actualQuantity)
                val existing = herbs.find { it.name == h.name && it.rarity == h.rarity && it.category == h.category }
                if (existing != null) {
                    val newQty = (existing.quantity + h.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                    herbs = herbs.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    herbs = herbs + h
                }
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(item).copy(quantity = actualQuantity)
                val existing = seeds.find { it.name == s.name && it.rarity == s.rarity && it.growTime == s.growTime }
                if (existing != null) {
                    val newQty = (existing.quantity + s.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                    seeds = seeds.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                } else {
                    seeds = seeds + s
                }
            }
            "spiritstone" -> {
                val grade = SpiritStoneGrade.fromDisplayName(item.name) ?: return
                spiritStoneWallet.add(this, actualQuantity.toLong(), grade, SpiritStoneSource.MerchantTrade)
            }
        }
    }

    private fun selectRarityByMerchantProbabilities(random: Random): Int {
        val rand = random.nextDouble()
        var cumulative = 0.0
        for ((rarity, prob) in SECT_TRADE_RARITY_PROBABILITIES.entries.sortedByDescending { it.key }) {
            cumulative += prob
            if (rand < cumulative) return rarity
        }
        return 1
    }
}

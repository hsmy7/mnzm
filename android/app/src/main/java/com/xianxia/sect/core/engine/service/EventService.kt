package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.system.AddResult
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SectRelationLevel
import android.util.Log

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.math.roundToInt

@SystemPriority(order = 260)
@Singleton
class EventService @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig
) : GameSystem {
    override val systemName: String = "EventService"
    private val scope get() = applicationScopeProvider.scope

    override fun initialize() {
        Log.d(TAG, "EventService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "EventService released")
    }

    override suspend fun clearForSlot(slotId: Int) {
        clearEvents()
    }
    companion object {
        private const val TAG = "EventService"
        private const val MAX_EVENTS = 100
    }

    fun getEvents(): StateFlow<List<GameEvent>> = stateStore.events

    fun addGameEvent(message: String, type: EventType = EventType.INFO) {
        val ts = stateStore.currentTransactionMutableState()
        val data = if (ts != null) ts.gameData else stateStore.gameData.value
        val event = GameEvent(
            year = data.gameYear,
            month = data.gameMonth,
            message = message,
            type = type
        )
        if (ts != null) {
            ts.events = listOf(event) + ts.events.take(MAX_EVENTS - 1)
        } else {
            scope.launch { stateStore.update { events = listOf(event) + events.take(MAX_EVENTS - 1) } }
        }
    }

    fun clearEvents() {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.events = emptyList()
        } else {
            scope.launch { stateStore.update { events = emptyList() } }
        }
    }

    fun getRecentEvents(count: Int = 20): List<GameEvent> {
        return stateStore.events.value.take(count)
    }

    // ==================== 商人系统 ====================

    /**
     * Generate random trade items for sect merchant
     */
    fun generateSectTradeItems(year: Int, sectId: String? = null): List<MerchantItem> {
        val items = mutableListOf<MerchantItem>()
        // Use sectId as differentiation factor for deterministic per-sect randomness,
        // falling back to Random.Default when sectId is unavailable
        val random = if (sectId != null) {
            Random(sectId.hashCode().toLong() + year)
        } else {
            Random.Default
        }

        val itemCount = 20

        val centuryCount = year / 100
        val adjustment = centuryCount * 3

        val baseProbabilities = listOf(75.0, 60.0, 22.6, 7.0, 2.8, 0.6)
        val adjustedProbabilities = baseProbabilities.mapIndexed { index, prob ->
            when {
                index < 3 -> maxOf(1.0, prob - adjustment)
                else -> minOf(100.0, prob + adjustment)
            }
        }

        val generatedNames = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = itemCount * 3

        while (items.size < itemCount && attempts < maxAttempts) {
            attempts++
            val type = listOf("equipment", "manual", "pill", "material", "herb", "seed").random(random)
            val rarity = generateRarityByProbability(adjustedProbabilities, random)

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
                    val basePrice = ((template?.price ?: GameConfig.Rarity.get(rarity).basePrice) * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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
                    val basePrice = ((template?.price ?: GameConfig.Rarity.get(rarity).basePrice) * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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
                    val basePrice = (template.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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
                    val basePrice = (material.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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
                    val basePrice = (herb.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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
                    val basePrice = (seed.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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

    /**
     * Get or refresh sect trade items
     */
    fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> {
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return emptyList()
        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)

        val currentYear = data.gameYear
        val shouldRefresh = currentYear - sectDetail.tradeLastRefreshYear >= 3 || sectDetail.tradeItems.isEmpty()

        if (shouldRefresh) {
            val newItems = generateSectTradeItems(currentYear, sectId)
            val updatedSectDetails = data.sectDetails.toMutableMap()
            updatedSectDetails[sectId] = sectDetail.copy(
                tradeItems = newItems,
                tradeLastRefreshYear = currentYear
            )
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) {
                ts.gameData = ts.gameData.copy(sectDetails = updatedSectDetails)
            } else {
                scope.launch { stateStore.update { gameData = gameData.copy(sectDetails = updatedSectDetails) } }
            }
            return newItems
        }

        return sectDetail.tradeItems
    }

    /**
     * Buy item from sect trade
     */
    private data class SectTradeValidation(
        val sect: WorldSect,
        val item: MerchantItem,
        val actualQuantity: Int,
        val totalPrice: Long,
        val updatedSectDetails: Map<String, SectDetail>
    )

    private fun validateSectTrade(data: GameData, sectId: String, itemId: String, quantity: Int): SectTradeValidation? {
        val sect = data.worldMapSects.find { it.id == sectId } ?: return null
        val sectDetail = data.sectDetails[sectId]
        val tradeItems = sectDetail?.tradeItems ?: emptyList()
        val item = tradeItems.find { it.id == itemId } ?: return null

        val relation = getSectRelation(data, sectId)
        val relationLevel = GameUtils.getSectRelationLevel(relation)
        if (relationLevel !in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY, SectRelationLevel.INTIMATE)) {
            addGameEvent("关系不足，无法与${sect.name}交易", EventType.WARNING)
            return null
        }

        val maxAllowedRarity = relationLevel.maxAllowedRarity
        if (item.rarity > maxAllowedRarity) {
            addGameEvent("关系等级不足，无法购买${GameConfig.Rarity.get(item.rarity).name}物品${item.name}", EventType.WARNING)
            return null
        }

        val actualQuantity = minOf(quantity, item.quantity)
        val priceMultiplier = calculatePriceMultiplier(data, sectId)
        val totalPrice = (item.price * priceMultiplier).toLong() * actualQuantity

        if (data.spiritStones < totalPrice) {
            addGameEvent("灵石不足，无法购买${item.name}", EventType.WARNING)
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
            else -> false
        }
        if (!capacityOk) {
            addGameEvent("仓库容量不足，无法购买${item.name}", EventType.WARNING)
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

    fun buyFromSectTrade(
        sectId: String,
        itemId: String,
        quantity: Int = 1
    ) {
        val data = stateStore.gameData.value
        val v = validateSectTrade(data, sectId, itemId, quantity) ?: return

        scope.launch {
            stateStore.update {
                gameData = gameData.copy(
                    spiritStones = gameData.spiritStones - v.totalPrice,
                    sectDetails = v.updatedSectDetails
                )
                addSectTradeItemToMutableState(v.item, v.actualQuantity)
                events = listOf(GameEvent(
                    year = gameData.gameYear,
                    month = gameData.gameMonth,
                    message = "从${v.sect.name}购买了${v.item.name} x${v.actualQuantity}",
                    type = EventType.SUCCESS
                )) + events.take(99)
            }
        }
    }

    suspend fun buyFromSectTradeSync(
        sectId: String,
        itemId: String,
        quantity: Int = 1
    ) {
        val data = stateStore.gameData.value
        val v = validateSectTrade(data, sectId, itemId, quantity) ?: return

        stateStore.update {
            gameData = gameData.copy(
                spiritStones = gameData.spiritStones - v.totalPrice,
                sectDetails = v.updatedSectDetails
            )
            addSectTradeItemToMutableState(v.item, v.actualQuantity)
            events = listOf(GameEvent(
                year = gameData.gameYear,
                month = gameData.gameMonth,
                message = "从${v.sect.name}购买了${v.item.name} x${v.actualQuantity}",
                type = EventType.SUCCESS
            )) + events.take(99)
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
        }
    }

    // ==================== 辅助方法 ====================

    private fun calculatePriceMultiplier(data: GameData, sectId: String): Double =
        GameUtils.calculateSectTradePriceMultiplier(data.worldMapSects, data.sectRelations, data.alliances, sectId)

    private fun getSectRelation(data: GameData, sectId: String): Int =
        GameUtils.getSectRelation(data.worldMapSects, data.sectRelations, sectId)

    private fun generateRarityByProbability(probabilities: List<Double>, random: Random): Int {
        val roll = random.nextDouble() * 100
        var cumulative = 0.0

        probabilities.forEachIndexed { index, probability ->
            cumulative += probability
            if (roll <= cumulative) {
                return index + 1
            }
        }

        return probabilities.size
    }
}

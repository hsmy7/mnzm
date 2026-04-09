package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.GameUtils

import java.util.UUID
import kotlin.random.Random

/**
 * 事件服务 - 负责游戏事件和商人系统
 *
 * 职责域：
 * - 游戏事件生成和处理
 * - events StateFlow
 * - 商人刷新逻辑（MERCHANT_* 常量相关方法）
 * - 交易系统
 */
class EventService constructor(
    private val _gameData: MutableStateFlow<GameData>,
    private val _events: MutableStateFlow<List<GameEvent>>,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "EventService"
        private const val MAX_EVENTS = 50
    }

    // ==================== StateFlow 暴露 ====================

    /**
     * Get events StateFlow
     */
    fun getEvents(): StateFlow<List<GameEvent>> = _events

    // ==================== 事件管理 ====================

    /**
     * Add new game event
     */
    fun addGameEvent(message: String, type: EventType = EventType.INFO) {
        val data = _gameData.value
        val event = GameEvent(
            year = data.gameYear,
            month = data.gameMonth,
            message = message,
            type = type
        )
        _events.value = listOf(event) + _events.value.take(MAX_EVENTS - 1)
    }

    /**
     * Clear all events
     */
    fun clearEvents() {
        _events.value = emptyList()
    }

    /**
     * Get recent events (last N)
     */
    fun getRecentEvents(count: Int = 20): List<GameEvent> {
        return _events.value.take(count)
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
                    val basePrice = template?.price ?: GameConfig.Rarity.get(rarity).basePrice
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
                    val basePrice = template?.price ?: GameConfig.Rarity.get(rarity).basePrice
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
                    val basePrice = template.price
                    MerchantItem(
                        id = UUID.randomUUID().toString(),
                        name = pill.name,
                        type = "pill",
                        itemId = pill.id,
                        rarity = pill.rarity,
                        price = GameUtils.applyPriceFluctuation(basePrice, random),
                        quantity = calcStock(type, rarity),
                        obtainedYear = year,
                        obtainedMonth = 1
                    )
                }
                "material" -> {
                    val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
                    if (materials.isEmpty()) continue
                    val material = materials.random(random)
                    val basePrice = material.price
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
                    val basePrice = herb.price
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
                    val basePrice = seed.price
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
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return emptyList()

        val currentYear = data.gameYear
        val shouldRefresh = currentYear - sect.tradeLastRefreshYear >= 3 || sect.tradeItems.isEmpty()

        if (shouldRefresh) {
            val newItems = generateSectTradeItems(currentYear, sectId)
            val updatedSects = data.worldMapSects.map {
                if (it.id == sectId) it.copy(
                    tradeItems = newItems,
                    tradeLastRefreshYear = currentYear
                ) else it
            }
            _gameData.value = data.copy(worldMapSects = updatedSects)
            return newItems
        }

        return sect.tradeItems
    }

    /**
     * Buy item from sect trade
     */
    fun buyFromSectTrade(
        sectId: String,
        itemId: String,
        quantity: Int = 1,
        onAddEquipment: (Equipment) -> Unit,
        onAddManual: (Manual) -> Unit,
        onAddPill: (Pill) -> Unit,
        onAddMaterial: (Material) -> Unit,
        onAddHerb: (Herb) -> Unit,
        onAddSeed: (Seed) -> Unit
    ) {
        val data = _gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return
        val item = sect.tradeItems.find { it.id == itemId } ?: return

        // Check relation and calculate price (would include diplomacy logic here)
        val actualQuantity = minOf(quantity, item.quantity)
        val totalPrice = item.price * actualQuantity

        if (data.spiritStones < totalPrice) {
            addEvent("灵石不足，无法购买${item.name}", EventType.WARNING)
            return
        }

        // Update trade items
        val updatedTradeItems = if (item.quantity > actualQuantity) {
            sect.tradeItems.map {
                if (it.id == itemId) it.copy(quantity = it.quantity - actualQuantity)
                else it
            }
        } else {
            sect.tradeItems.filter { it.id != itemId }
        }

        val updatedSects = data.worldMapSects.map {
            if (it.id == sectId) it.copy(tradeItems = updatedTradeItems) else it
        }

        // Deduct spirit stones
        _gameData.value = data.copy(
            spiritStones = data.spiritStones - totalPrice,
            worldMapSects = updatedSects
        )

        // Add items to warehouse (would delegate to InventoryService)
        for (i in 0 until actualQuantity) {
            when (item.type) {
                "equipment" -> onAddEquipment(createMerchantEquipment(item))
                "manual" -> onAddManual(createMerchantManual(item))
                "pill" -> onAddPill(createMerchantPill(item))
                "material" -> onAddMaterial(createMerchantMaterial(item))
                "herb" -> onAddHerb(createMerchantHerb(item))
                "seed" -> onAddSeed(createMerchantSeed(item))
            }
        }

        addEvent("从${sect.name}购买了${item.name} x$actualQuantity", EventType.SUCCESS)
    }

    // ==================== 辅助方法 ====================

    /**
     * Generate rarity based on probability distribution
     */
    private fun generateRarityByProbability(probabilities: List<Double>, random: Random): Int {
        val roll = random.nextDouble() * 100
        var cumulative = 0.0

        probabilities.forEachIndexed { index, probability ->
            cumulative += probability
            if (roll <= cumulative) {
                return index + 1 // Rarity is 1-based
            }
        }

        return probabilities.size // Fallback to highest rarity
    }

    /**
     * Create equipment from merchant item (simplified)
     */
    private fun createMerchantEquipment(item: MerchantItem): Equipment {
        val template = EquipmentDatabase.generateRandom(item.rarity, item.rarity)
        return template.copy(id = UUID.randomUUID().toString(), rarity = item.rarity)
    }

    private fun createMerchantManual(item: MerchantItem): Manual {
        val template = ManualDatabase.getByName(item.name)
        if (template != null) {
            return Manual(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                type = template.type,
                stats = template.stats,
                minRealm = GameConfig.Realm.getMinRealmForRarity(item.rarity),
                quantity = 1
            )
        }
        return ManualDatabase.generateRandom(item.rarity, item.rarity).copy(
            id = UUID.randomUUID().toString(),
            rarity = item.rarity
        )
    }

    private fun createMerchantPill(item: MerchantItem): Pill {
        val template = PillRecipeDatabase.getRecipeById(item.itemId)
        if (template != null) {
            return Pill(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = template.rarity,
                quantity = item.quantity,
                description = template.description,
                category = template.category,
                breakthroughChance = template.breakthroughChance,
                targetRealm = template.targetRealm,
                cultivationSpeed = template.cultivationSpeed,
                duration = template.effectDuration,
                cultivationPercent = template.cultivationPercent,
                skillExpPercent = template.skillExpPercent,
                extendLife = template.extendLife,
                physicalAttackPercent = template.physicalAttackPercent,
                magicAttackPercent = template.magicAttackPercent,
                physicalDefensePercent = template.physicalDefensePercent,
                magicDefensePercent = template.magicDefensePercent,
                hpPercent = template.hpPercent,
                mpPercent = template.mpPercent,
                speedPercent = template.speedPercent,
                healPercent = template.healPercent,
                healMaxHpPercent = template.healMaxHpPercent
            )
        }
        val pillTemplates = ItemDatabase.getPillsByRarity(item.rarity)
        if (pillTemplates.isNotEmpty()) {
            val t = pillTemplates.random()
            return ItemDatabase.createPillFromTemplate(t).copy(quantity = item.quantity)
        }
        return Pill(id = UUID.randomUUID().toString(), name = item.name, rarity = item.rarity, quantity = item.quantity)
    }

    private fun createMerchantMaterial(item: MerchantItem): Material {
        val template = BeastMaterialDatabase.getMaterialById(item.itemId)
        if (template != null) {
            return Material(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                quantity = item.quantity,
                description = template.description,
                category = try { MaterialCategory.valueOf(template.category) } catch (e: IllegalArgumentException) { MaterialCategory.BEAST_HIDE }
            )
        }
        val randomMaterial = ItemDatabase.generateRandomMaterial(minRarity = item.rarity, maxRarity = item.rarity)
        return randomMaterial.copy(quantity = item.quantity)
    }

    private fun createMerchantHerb(item: MerchantItem): Herb {
        val template = HerbDatabase.getHerbById(item.itemId)
        if (template != null) {
            return Herb(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                category = template.category,
                quantity = item.quantity
            )
        }
        val herbTemplate = HerbDatabase.generateRandomHerb(minRarity = item.rarity, maxRarity = item.rarity)
        return Herb(
            id = UUID.randomUUID().toString(),
            name = herbTemplate.name,
            rarity = herbTemplate.rarity,
            description = herbTemplate.description,
            category = herbTemplate.category,
            quantity = item.quantity
        )
    }

    private fun createMerchantSeed(item: MerchantItem): Seed {
        val template = HerbDatabase.getSeedById(item.itemId)
        if (template != null) {
            return Seed(
                id = UUID.randomUUID().toString(),
                name = template.name,
                rarity = item.rarity,
                description = template.description,
                growTime = template.growTime,
                yield = template.yield,
                quantity = item.quantity
            )
        }
        val seedTemplate = HerbDatabase.generateRandomSeed(minRarity = item.rarity, maxRarity = item.rarity)
        return Seed(
            id = UUID.randomUUID().toString(),
            name = seedTemplate.name,
            rarity = seedTemplate.rarity,
            description = seedTemplate.description,
            growTime = seedTemplate.growTime,
            yield = seedTemplate.yield,
            quantity = item.quantity
        )
    }
}

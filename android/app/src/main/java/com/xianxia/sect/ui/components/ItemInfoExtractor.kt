package com.xianxia.sect.ui.components

import com.xianxia.sect.core.model.*

data class ItemDisplayInfo(
    val name: String,
    val rarity: Int,
    val type: String,
    val quantity: Int,
    val price: Int,
    val description: String = "",
    val itemId: String = "",
    val itemType: String = ""
)

object ItemInfoExtractor {
    
    private const val SELL_PRICE_RATIO = 0.8
    
    fun extract(item: Any): ItemDisplayInfo {
        return when (item) {
            is Equipment -> extractFromEquipment(item)
            is Manual -> extractFromManual(item)
            is Pill -> extractFromPill(item)
            is Material -> extractFromMaterial(item)
            is Herb -> extractFromHerb(item)
            is Seed -> extractFromSeed(item)
            is StorageBagItem -> extractFromStorageBagItem(item)
            else -> ItemDisplayInfo(
                name = "未知物品",
                rarity = 1,
                type = "未知",
                quantity = 1,
                price = 0
            )
        }
    }
    
    fun extractFromStorageBagItem(item: StorageBagItem): ItemDisplayInfo {
        val type = when (item.itemType) {
            "equipment" -> "装备"
            "manual" -> "功法"
            "pill" -> "丹药"
            "material" -> "材料"
            "herb" -> "灵药"
            "seed" -> "种子"
            else -> "物品"
        }
        return ItemDisplayInfo(
            name = item.name,
            rarity = item.rarity,
            type = type,
            quantity = item.quantity,
            price = 0,
            itemId = item.itemId,
            itemType = item.itemType
        )
    }
    
    private fun extractFromEquipment(equipment: Equipment): ItemDisplayInfo {
        return ItemDisplayInfo(
            name = equipment.name,
            rarity = equipment.rarity,
            type = "装备",
            quantity = 1,
            price = (equipment.basePrice * SELL_PRICE_RATIO).toInt(),
            description = equipment.description ?: "",
            itemId = equipment.id,
            itemType = "equipment"
        )
    }
    
    private fun extractFromManual(manual: Manual): ItemDisplayInfo {
        return ItemDisplayInfo(
            name = manual.name,
            rarity = manual.rarity,
            type = "功法",
            quantity = 1,
            price = (manual.basePrice * SELL_PRICE_RATIO).toInt(),
            description = manual.description ?: "",
            itemId = manual.id,
            itemType = "manual"
        )
    }
    
    private fun extractFromPill(pill: Pill): ItemDisplayInfo {
        return ItemDisplayInfo(
            name = pill.name,
            rarity = pill.rarity,
            type = "丹药",
            quantity = pill.quantity,
            price = (pill.basePrice * pill.quantity * SELL_PRICE_RATIO).toInt(),
            description = pill.description ?: "",
            itemId = pill.id,
            itemType = "pill"
        )
    }
    
    private fun extractFromMaterial(material: Material): ItemDisplayInfo {
        return ItemDisplayInfo(
            name = material.name,
            rarity = material.rarity,
            type = "材料",
            quantity = material.quantity,
            price = (material.basePrice * material.quantity * SELL_PRICE_RATIO).toInt(),
            description = material.description ?: "",
            itemId = material.id,
            itemType = "material"
        )
    }
    
    private fun extractFromHerb(herb: Herb): ItemDisplayInfo {
        return ItemDisplayInfo(
            name = herb.name,
            rarity = herb.rarity,
            type = "灵药",
            quantity = herb.quantity,
            price = (herb.basePrice * herb.quantity * SELL_PRICE_RATIO).toInt(),
            description = herb.description ?: "",
            itemId = herb.id,
            itemType = "herb"
        )
    }
    
    private fun extractFromSeed(seed: Seed): ItemDisplayInfo {
        return ItemDisplayInfo(
            name = seed.name,
            rarity = seed.rarity,
            type = "种子",
            quantity = seed.quantity,
            price = (seed.basePrice * seed.quantity * SELL_PRICE_RATIO).toInt(),
            description = seed.description ?: "",
            itemId = seed.id,
            itemType = "seed"
        )
    }
    
    fun getRarityColor(rarity: Int): androidx.compose.ui.graphics.Color {
        return when (rarity) {
            1 -> androidx.compose.ui.graphics.Color(0xFF808080)
            2 -> androidx.compose.ui.graphics.Color(0xFF00FF00)
            3 -> androidx.compose.ui.graphics.Color(0xFF00BFFF)
            4 -> androidx.compose.ui.graphics.Color(0xFF800080)
            5 -> androidx.compose.ui.graphics.Color(0xFFFFD700)
            6 -> androidx.compose.ui.graphics.Color(0xFFFF4500)
            else -> androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        }
    }
    
    fun getRarityName(rarity: Int): String {
        return when (rarity) {
            1 -> "凡品"
            2 -> "灵品"
            3 -> "宝品"
            4 -> "玄品"
            5 -> "地品"
            6 -> "天品"
            else -> "未知"
        }
    }
    
    fun calculateSellPrice(basePrice: Int, quantity: Int = 1): Int {
        return (basePrice * quantity * SELL_PRICE_RATIO).toInt()
    }
}

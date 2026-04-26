package com.xianxia.sect.core.warehouse

object StorageKeyUtil {
    fun generateKey(item: WarehouseItem): String {
        return "${item.itemId}:${item.itemType}:${item.rarity}:${item.itemName}"
    }
}

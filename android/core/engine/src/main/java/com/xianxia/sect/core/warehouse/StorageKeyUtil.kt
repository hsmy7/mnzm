package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem

object StorageKeyUtil {
    fun generateKey(item: WarehouseItem): String {
        return "${item.itemId}:${item.itemType}:${item.rarity}:${item.itemName}"
    }
}

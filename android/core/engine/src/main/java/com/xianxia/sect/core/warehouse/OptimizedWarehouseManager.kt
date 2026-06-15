package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 仓库操作管理器（精简版）。
 *
 * 移除了未接入生产的 WarehouseCache/Pager/DiffManager/Compressor 依赖，
 * 保留 addItem / addItems / addSpiritStones / removeItem / clear 五个核心操作。
 */
@Singleton
class OptimizedWarehouseManager @Inject constructor(
    private val inventoryConfig: InventoryConfig
) {

    /** 添加单个物品（堆叠合并） */
    fun addItem(warehouse: SectWarehouse, item: WarehouseItem): SectWarehouse {
        val existingIdx = warehouse.items.indexOfFirst {
            it.itemId == item.itemId && it.itemType == item.itemType
                && it.rarity == item.rarity
        }
        return if (existingIdx >= 0) {
            val existing = warehouse.items[existingIdx]
            val maxStack = inventoryConfig.getMaxStackSize(item.itemType)
            val newQty = (existing.quantity + item.quantity)
                .coerceAtMost(maxStack)
            val updated = warehouse.items.toMutableList().apply {
                this[existingIdx] = existing.copy(quantity = newQty)
            }
            warehouse.copy(items = updated)
        } else {
            warehouse.copy(items = warehouse.items + item)
        }
    }

    /** 批量添加物品 */
    fun addItems(warehouse: SectWarehouse, items: List<WarehouseItem>): SectWarehouse {
        return items.fold(warehouse) { w, item -> addItem(w, item) }
    }

    /** 添加灵石 */
    fun addSpiritStones(
        warehouse: SectWarehouse, amount: Long
    ): SectWarehouse = warehouse.copy(
        spiritStones = warehouse.spiritStones + amount
    )

    /** 按 ID 移除指定数量 */
    fun removeItem(
        warehouse: SectWarehouse, itemId: String, count: Int = 1
    ): SectWarehouse {
        val newItems = warehouse.items.mapNotNull { item ->
            if (item.itemId != itemId) return@mapNotNull item
            val newQty = item.quantity - count
            if (newQty > 0) item.copy(quantity = newQty) else null
        }
        return warehouse.copy(items = newItems)
    }

    /** 清空仓库 */
    fun clear(): SectWarehouse = SectWarehouse()
}

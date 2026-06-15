package com.xianxia.sect.core.engine

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.domain.battle.PlayerLootLossResult
import com.xianxia.sect.core.engine.domain.battle.WarRewards
import com.xianxia.sect.core.engine.domain.exploration.CaveRewards
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.warehouse.OptimizedWarehouseManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宗门仓库门面（精简版）。
 *
 * 移除了未接入生产的 loadPage / search / getStats / diff / compress
 * 等死代码，保留核心 CRUD + 战利品转换 + 掠夺损失计算。
 */
@Singleton
class SectWarehouseManager @Inject constructor(
    private val optimizedManager: OptimizedWarehouseManager
) {

    var inventoryConfig: InventoryConfig = InventoryConfig.DEFAULT

    fun addItemToWarehouse(
        warehouse: SectWarehouse, item: WarehouseItem
    ): SectWarehouse = optimizedManager.addItem(warehouse, item)

    fun addItemsToWarehouse(
        warehouse: SectWarehouse, items: List<WarehouseItem>
    ): SectWarehouse = optimizedManager.addItems(warehouse, items)

    fun addSpiritStonesToWarehouse(
        warehouse: SectWarehouse, amount: Long
    ): SectWarehouse = optimizedManager.addSpiritStones(warehouse, amount)

    fun clearWarehouse(): SectWarehouse = optimizedManager.clear()

    fun removeItem(
        warehouse: SectWarehouse, itemId: String, count: Int = 1
    ): SectWarehouse = optimizedManager.removeItem(warehouse, itemId, count)

    /** 将洞窟奖励转换为仓库物品列表 */
    fun convertCaveRewardsToWarehouseItems(
        rewards: CaveRewards
    ): List<WarehouseItem> = rewards.items
        .filter { it.type != "spiritStones" }
        .map { reward ->
            WarehouseItem(
                itemId = reward.itemId,
                itemName = reward.name,
                itemType = reward.type,
                rarity = reward.rarity,
                quantity = reward.quantity
            )
        }

    /** 将宗门战奖励转换为仓库物品列表 */
    fun convertWarRewardsToWarehouseItems(
        rewards: WarRewards
    ): List<WarehouseItem> {
        val items = mutableListOf<WarehouseItem>()

        rewards.equipmentStacks.forEach { stack ->
            items.add(WarehouseItem(
                itemId = "equipment_${stack.name}_${stack.rarity}",
                itemName = stack.name, itemType = "equipment_stack",
                rarity = stack.rarity, quantity = stack.quantity
            ))
        }

        rewards.manualStacks.forEach { stack ->
            items.add(WarehouseItem(
                itemId = "manual_${stack.name}_${stack.rarity}",
                itemName = stack.name, itemType = "manual_stack",
                rarity = stack.rarity, quantity = stack.quantity
            ))
        }

        rewards.pills.forEach { pill ->
            items.add(WarehouseItem(
                itemId = pill.id, itemName = pill.name,
                itemType = "pill", rarity = pill.rarity,
                quantity = pill.quantity
            ))
        }

        rewards.materials.forEach { material ->
            items.add(WarehouseItem(
                itemId = material.id, itemName = material.name,
                itemType = "material", rarity = material.rarity,
                quantity = material.quantity
            ))
        }

        rewards.herbs.forEach { herb ->
            items.add(WarehouseItem(
                itemId = herb.id, itemName = herb.name,
                itemType = "herb", rarity = herb.rarity,
                quantity = herb.quantity
            ))
        }

        rewards.seeds.forEach { seed ->
            items.add(WarehouseItem(
                itemId = seed.id, itemName = seed.name,
                itemType = "seed", rarity = seed.rarity,
                quantity = seed.quantity
            ))
        }

        return items
    }

    /** 计算仓库被掠夺时的损失（40% 灵石 + 40% 物品） */
    fun calculateWarehouseLootLoss(
        warehouse: SectWarehouse
    ): PlayerLootLossResult {
        val lostSpiritStones =
            (warehouse.spiritStones * 0.4).toLong().coerceAtLeast(0)
        val lostMaterials = mutableMapOf<String, Int>()
        warehouse.items.filter { it.quantity > 0 }.forEach { item ->
            val loss = (item.quantity * 0.4).toInt().coerceAtLeast(1)
            val key = "${item.itemId}:${item.itemType}" +
                ":${item.rarity}:${item.itemName}"
            lostMaterials[key] = loss
        }
        return PlayerLootLossResult(lostSpiritStones, lostMaterials)
    }

    /** 将掠夺损失应用到仓库 */
    fun applyLootLossToWarehouse(
        warehouse: SectWarehouse, loss: PlayerLootLossResult
    ): SectWarehouse {
        val updatedSpiritStones =
            (warehouse.spiritStones - loss.lostSpiritStones)
                .coerceAtLeast(0)
        val removals = loss.lostMaterials
        val updatedItems = warehouse.items.mapNotNull { item ->
            val key = "${item.itemId}:${item.itemType}" +
                ":${item.rarity}:${item.itemName}"
            val removeCount = removals[key] ?: 0
            if (removeCount <= 0) return@mapNotNull item
            val newQuantity = item.quantity - removeCount
            if (newQuantity > 0) item.copy(quantity = newQuantity) else null
        }
        return warehouse.copy(
            items = updatedItems, spiritStones = updatedSpiritStones
        )
    }
}

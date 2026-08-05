package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.StorageBagItem

/**
 * 弟子储物袋（storageBagItems）纯列表工具。
 *
 * 范围限定：本工具只操作"背包引用列表"（[StorageBagItem] 列表的增删查），
 * **不涉及仓库堆叠合并**——装备/功法实例转回仓库堆叠的统一入口在
 * `:core:engine` 的 InventorySystem（addEquipmentInstanceToBag /
 * addManualInstanceToBag，P-20 迁移），保证真实容量约束 + 溢出转邮件 +
 * 来源追踪，防止 domain 侧手写合并绕过守卫测试。
 */
object StorageBagUtils {

    private const val COOLING_PERIOD_PHASES = 9
    private const val COOLING_PERIOD_MONTHS = 3

    fun isInCoolingPeriod(item: StorageBagItem, currentYear: Int, currentMonth: Int, currentPhase: Int): Boolean {
        val forgetYear = item.forgetYear ?: return false
        val forgetMonth = item.forgetMonth ?: return false
        val forgetPhase = item.forgetPhase
        if (forgetPhase != null) {
            val forgetTotalPhases = forgetYear * 36 + (forgetMonth - 1) * 3 + forgetPhase
            val currentTotalPhases = currentYear * 36 + (currentMonth - 1) * 3 + currentPhase
            return currentTotalPhases - forgetTotalPhases < COOLING_PERIOD_PHASES
        } else {
            val forgetTotalMonths = forgetYear * 12 + forgetMonth
            val currentTotalMonths = currentYear * 12 + currentMonth
            return currentTotalMonths - forgetTotalMonths < COOLING_PERIOD_MONTHS
        }
    }

    fun decreaseItemQuantity(items: List<StorageBagItem>, itemId: String, amount: Int = 1): List<StorageBagItem> {
        val mutableItems = items.toMutableList()
        val index = mutableItems.indexOfFirst { it.itemId == itemId }
        if (index < 0) return items
        val item = mutableItems[index]
        val newQuantity = item.quantity - amount
        if (newQuantity > 0) {
            mutableItems[index] = item.copy(quantity = newQuantity)
        } else {
            mutableItems.removeAt(index)
        }
        return mutableItems.toList()
    }

    /**
     * 向背包引用列表追加引用（同 itemId 合并数量）。
     *
     * P-20：引用是显示记录（指向仓库堆叠），数量由仓库堆叠约束；
     * 旧实现的截断语义会静默丢弃溢出引用，玩家物品在仓库却从背包 UI
     * 消失——故引用列表不设截断（守卫测试扫描截断反模式，注释不含其字样）。
     */
    fun increaseItemQuantity(
        items: List<StorageBagItem>,
        item: StorageBagItem
    ): List<StorageBagItem> {
        val mutableItems = items.toMutableList()
        val existingIndex = mutableItems.indexOfFirst { it.itemId == item.itemId && it.itemType == item.itemType }
        if (existingIndex >= 0) {
            val existing = mutableItems[existingIndex]
            mutableItems[existingIndex] = existing.copy(quantity = existing.quantity + item.quantity)
        } else {
            mutableItems.add(item.copy(quantity = item.quantity))
        }
        return mutableItems.toList()
    }

    fun decreaseMultipleItems(items: List<StorageBagItem>, itemIds: List<String>): List<StorageBagItem> {
        var result = items
        itemIds.forEach { itemId -> result = decreaseItemQuantity(result, itemId) }
        return result
    }

    fun hasEnoughItems(items: List<StorageBagItem>, itemId: String, requiredQuantity: Int = 1): Boolean {
        val item = items.find { it.itemId == itemId }
        return item != null && item.quantity >= requiredQuantity
    }

    fun getItemQuantity(items: List<StorageBagItem>, itemId: String): Int {
        return items.find { it.itemId == itemId }?.quantity ?: 0
    }
}

fun List<StorageBagItem>.decreaseItem(itemId: String, amount: Int = 1): List<StorageBagItem> =
    StorageBagUtils.decreaseItemQuantity(this, itemId, amount)

fun List<StorageBagItem>.increaseItem(item: StorageBagItem): List<StorageBagItem> =
    StorageBagUtils.increaseItemQuantity(this, item)

fun List<StorageBagItem>.hasItem(itemId: String, amount: Int = 1): Boolean =
    StorageBagUtils.hasEnoughItems(this, itemId, amount)

fun List<StorageBagItem>.getItemQty(itemId: String): Int =
    StorageBagUtils.getItemQuantity(this, itemId)

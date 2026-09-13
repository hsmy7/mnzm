package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacadeImpl.StorageBagRewardBatch

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── InventoryFacadeImpl 拆分域 1/1（行为零变更） ──

private val TAG = InventoryFacadeImpl.TAG
/** 没收结果落地：成功移除袋条目，溢出/失败保留待重试 */
internal fun MutableGameState.applyConfiscationResult(
    disciple: Disciple,
    currentItem: StorageBagItem,
    hasInstance: Boolean,
    result: DomainResult<*>?
) {
    when (result) {
        // 模板不存在：仅引用无法重建，物品丢弃（袋条目保留，玩家可再次尝试）
        null -> DomainLog.w(TAG, "没收物品失败：找不到 ${currentItem.name} 的模板")
        // 仓库已入仓，从弟子储物袋移除（实例整条删除，堆叠减 1）
        is DomainResult.Success -> {
            val updatedItems = if (hasInstance) {
                // 实例条目：整条移除（实例不可分，防 quantity>1 实例重复没收复制）
                disciple.equipment.storageBagItems.filterNot { it.itemId == currentItem.itemId }
            } else {
                // 堆叠条目：每次没收 1 个，袋内剩余数量保留
                StorageBagUtils.decreaseItemQuantity(
                    disciple.equipment.storageBagItems, currentItem.itemId, 1
                )
            }
            discipleTables.update(disciple.copy(
                equipment = disciple.equipment.copy(storageBagItems = updatedItems)
            ))
        }
        // 溢出：保留袋内物品，玩家清理后重试补齐（已入仓部分合并不重复）
        is DomainResult.Partial ->
            DomainLog.w(TAG, "没收物品溢出：${currentItem.name} 溢出 ${result.overflow} 个，保留袋内物品待重试")
        // 仓库满：保留袋内物品待重试（C1 防复制）
        is DomainResult.Failure -> {}
    }
}

/** 在 [MutableGameState] 事务内直接扣减堆叠物品（无需临时 StackableItemStore）。 */
@Suppress("UNCHECKED_CAST")
internal fun <T> MutableGameState.deductStack(
    id: String,
    quantity: Int,
    store: EntityStore<T>,
    getBasePrice: (T) -> Int
): Long where T : HasId, T : StackableItem {
    val item = store.get(id) ?: return 0L
    if (item.isLocked || quantity !in 1..item.quantity) return 0L
    val newQty = item.quantity - quantity
    if (newQty <= 0) store.remove(id) else store.update(id) { it.withQuantity(newQty) as T }
    return GameConfig.Rarity.calculateSellPrice(getBasePrice(item), quantity)
}

/** 商人灵石商品入账：中品/上品灵石直加余额 */
internal fun MutableGameState.grantMerchantSpiritStones(merchantItem: MerchantItem, quantity: Int) {
    when (merchantItem.name) {
        "中品灵石" -> gameData = gameData.copy(
            midGradeSpiritStones = gameData.midGradeSpiritStones + quantity
        )
        "上品灵石" -> gameData = gameData.copy(
            highGradeSpiritStones = gameData.highGradeSpiritStones + quantity
        )
    }
}

/** 商家库存扣减 */

internal fun MutableGameState.reduceMerchantStock(itemId: String, quantity: Int) {
    gameData = gameData.copy(
        travelingMerchantItems = gameData.travelingMerchantItems.map { item ->
            if (item.id == itemId) {
                if (quantity >= item.quantity) null else item.copy(quantity = item.quantity - quantity)
            } else item
        }.filterNotNull()
    )
}

/** 灵石出售扣减：中品/上品灵石直接扣减玩家余额（下限 0） */
internal fun MutableGameState.deductSoldSpiritStones(name: String, quantity: Int) {
    when (name) {
        "中品灵石" -> gameData = gameData.copy(
            midGradeSpiritStones = (gameData.midGradeSpiritStones - quantity).coerceAtLeast(0L))
        "上品灵石" -> gameData = gameData.copy(
            highGradeSpiritStones = (gameData.highGradeSpiritStones - quantity).coerceAtLeast(0L))
    }
}

/**
 * D-21 存档完整性防御:数量越界或价格非正(篡改档)拒绝收购——
 * 防"先移除仓库物品、后 wallet.add 拒绝入账"致物品丢失。
 * @return true 表示交易请求非法,应拒绝
 */

internal fun InventoryFacadeImpl.isInvalidTradeRequest(
    acquisitionItemId: String, quantity: Int, price: Long, maxQuantity: Int
): Boolean {
    if (quantity <= 0 || quantity > maxQuantity || price <= 0) {
        DomainLog.w(TAG, "收购被拒:非法参数 id=$acquisitionItemId price=$price qty=$quantity")
        return true
    }
    return false
}

internal fun InventoryFacadeImpl.warehouseCount(

    item: MerchantItem): Int = when (item.type.lowercase(java.util.Locale.getDefault())
) {
    "equipment" -> countWarehouseStacks(equipmentStacks.value, item)
    "manual" -> countWarehouseStacks(manualStacks.value, item)
    "pill" -> countWarehousePills(pills.value, item)
    "material" -> countWarehouseStacks(materials.value, item)
    "herb" -> countWarehouseStacks(herbs.value, item)
    "seed" -> countWarehouseStacks(seeds.value, item)
    "spiritstone" -> warehouseSpiritStoneCount(item)
    else -> 0
}

/** 灵石仓库计数：按显示名映射品阶取玩家余额（下限 0，未知名称返回 0） */

internal fun InventoryFacadeImpl.warehouseSpiritStoneCount(item: MerchantItem): Int {
    val grade = SpiritStoneGrade.fromDisplayName(item.name) ?: return 0
    return stateStore.gameData.value.spiritStoneCount(grade).toInt().coerceAtLeast(0)
}

/**
 * Deduct up to [amount] from [items] where [match] holds, processing in list order.
 * Each matched item has its quantity reduced; items reaching zero are removed.
 * @return updated list with deductions applied.
 */

internal inline fun <T> InventoryFacadeImpl.removeMatching(
    items: List<T>,
    crossinline match: (T) -> Boolean,
    crossinline getQty: (T) -> Int,
    crossinline setQty: (T, Int) -> T,
    amount: Int
): List<T> {
    var remaining = amount
    return items.mapNotNull { item ->
        if (remaining > 0 && match(item)) {
            val deduct = remaining.coerceAtMost(getQty(item))
            val newQty = getQty(item) - deduct
            remaining -= deduct
            if (newQty <= 0) null else setQty(item, newQty)
        } else item
    }
}


/** 储物袋消耗与奖励入仓：单事务原子写入 */

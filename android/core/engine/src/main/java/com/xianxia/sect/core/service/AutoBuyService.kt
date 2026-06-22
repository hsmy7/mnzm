package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.annotation.GameService
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("AutoBuyService")
class AutoBuyService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val merchantAndRecruitService: MerchantAndRecruitService
) {
    companion object {
        private const val TAG = "AutoBuyService"

        /** 判断自动购买条目是否匹配商人商品 */
        internal fun matches(
            entry: AutoBuyEntry,
            item: MerchantItem
        ): Boolean =
            item.name == entry.itemName &&
            item.type == entry.itemType &&
            item.rarity == entry.rarity

        /** 根据灵石和价格计算可购买数量 */
        internal fun calculateBuyQuantity(
            spiritStones: Long,
            price: Long,
            merchantQuantity: Int
        ): Int {
            if (merchantQuantity <= 0) return 0
            val maxAffordable = if (price > 0L)
                (spiritStones / price).toInt()
            else merchantQuantity
            return minOf(merchantQuantity, maxAffordable.coerceAtLeast(0))
        }
    }

    // ── 自动购买执行 ────────────────────────────────────────────────

    /**
     * 执行自动购买：遍历 autoBuyList，匹配当前商人商品，买入最大数量。
     * 灵石不足或仓库满时跳过该物品。在 stateStore.update {} 中原子执行。
     */
    suspend fun executeAutoBuy(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val autoBuyList = data.autoBuyList
        if (autoBuyList.isEmpty()) return

        val merchantItems = data.travelingMerchantItems
        if (merchantItems.isEmpty()) return

        var purchasedCount = 0
        var skippedNoFunds = 0

        stateStore.update {
            var stones = gameData.spiritStones
            val newMerchantItems = gameData.travelingMerchantItems.toMutableList()

            for (entry in autoBuyList) {
                val matchIdx = newMerchantItems.indexOfFirst { item ->
                    matches(entry, item)
                }
                if (matchIdx < 0) continue

                val merchantItem = newMerchantItems[matchIdx]
                if (merchantItem.quantity <= 0) continue

                // 检查仓库容量
                if (!canAddToWarehouse(merchantItem)) {
                    DomainLog.i(TAG,
                        "自动购买跳过（仓库满）: ${merchantItem.name}")
                    continue
                }

                // 计算可买数量
                val buyQty = calculateBuyQuantity(
                    stones, merchantItem.price, merchantItem.quantity)
                if (buyQty <= 0) {
                    skippedNoFunds++
                    continue
                }
                val cost = merchantItem.price * buyQty

                stones -= cost

                // 减少商人库存
                val remaining = merchantItem.quantity - buyQty
                if (remaining <= 0) {
                    newMerchantItems.removeAt(matchIdx)
                } else {
                    newMerchantItems[matchIdx] =
                        merchantItem.copy(quantity = remaining)
                }

                // 加入仓库（在 MutableGameState 上下文中）
                addToWarehouse(merchantItem, buyQty)
                purchasedCount++
            }

            gameData = gameData.copy(
                spiritStones = stones,
                travelingMerchantItems = newMerchantItems
            )
        }

        if (purchasedCount > 0) {
            DomainLog.i(TAG,
                "自动购买: ${purchasedCount}种物品 于 ${year}年${month}月" +
                if (skippedNoFunds > 0) " ($skippedNoFunds 种灵石不足)"
                else "")
        }
    }

    // ── 物品目录 ────────────────────────────────────────────────────

    /** 返回所有可被商人出售的物品，按品阶降序排列，供 UI 选择界面使用。 */
    fun getAllAutoBuyableItems(): List<AutoBuyCatalogItem> {
        val pools = merchantAndRecruitService.buildMerchantItemPools()
        val items = mutableListOf<AutoBuyCatalogItem>()
        for (rarity in 6 downTo 1) {
            pools.poolByRarity[rarity]?.forEach { entry ->
                items.add(AutoBuyCatalogItem(
                    name = entry.name,
                    type = entry.type,
                    rarity = rarity
                ))
            }
        }
        return items.distinctBy { "${it.name}:${it.type}:${it.rarity}" }
    }

    // ── 内部方法（在 MutableGameState 上下文中调用） ─────────────────

    private fun canAddToWarehouse(item: MerchantItem): Boolean =
        when (item.type.lowercase(Locale.ROOT)) {
            "equipment" -> {
                val eq = MerchantItemConverter.toEquipment(item)
                inventorySystem.canAddEquipment(eq.name, eq.rarity, eq.slot)
            }
            "manual" -> {
                val m = MerchantItemConverter.toManual(item)
                inventorySystem.canAddManual(m.name, m.rarity, m.type)
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(item)
                inventorySystem.canAddPill(
                    p.name, p.rarity, p.category, p.grade)
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(item)
                inventorySystem.canAddMaterial(
                    m.name, m.rarity, m.category)
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(item)
                inventorySystem.canAddHerb(
                    h.name, h.rarity, h.category)
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(item)
                inventorySystem.canAddSeed(
                    s.name, s.rarity, s.growTime)
            }
            else -> false
        }

    private fun MutableGameState.addToWarehouse(
        item: MerchantItem,
        quantity: Int
    ) {
        when (item.type.lowercase(Locale.ROOT)) {
            "equipment" -> {
                val stack = MerchantItemConverter.toEquipment(item)
                    .copy(quantity = quantity)
                val existing = equipmentStacks.all().find {
                    it.name == stack.name && it.rarity == stack.rarity &&
                    it.slot == stack.slot
                }
                if (existing != null) {
                    equipmentStacks.update(existing.id) {
                        it.copy(quantity = it.quantity + stack.quantity)
                    }
                } else {
                    equipmentStacks.add(stack)
                }
            }
            "manual" -> {
                val stack = MerchantItemConverter.toManual(item)
                    .copy(quantity = quantity)
                val existing = manualStacks.all().find {
                    it.name == stack.name && it.rarity == stack.rarity &&
                    it.type == stack.type
                }
                if (existing != null) {
                    manualStacks.update(existing.id) {
                        it.copy(quantity = it.quantity + stack.quantity)
                    }
                } else {
                    manualStacks.add(stack)
                }
            }
            "pill" -> {
                val p = MerchantItemConverter.toPill(item)
                    .copy(quantity = quantity)
                val existing = pills.all().find {
                    it.name == p.name && it.rarity == p.rarity &&
                    it.category == p.category && it.grade == p.grade
                }
                if (existing != null) {
                    val newQty = (existing.quantity + p.quantity)
                        .coerceAtMost(inventoryConfig.getMaxStackSize("pill"))
                    pills.update(existing.id) {
                        it.copy(quantity = newQty)
                    }
                } else {
                    pills.add(p)
                }
            }
            "material" -> {
                val m = MerchantItemConverter.toMaterial(item)
                    .copy(quantity = quantity)
                val existing = materials.all().find {
                    it.name == m.name && it.rarity == m.rarity &&
                    it.category == m.category
                }
                if (existing != null) {
                    val newQty = (existing.quantity + m.quantity)
                        .coerceAtMost(inventoryConfig.getMaxStackSize("material"))
                    materials.update(existing.id) {
                        it.copy(quantity = newQty)
                    }
                } else {
                    materials.add(m)
                }
            }
            "herb" -> {
                val h = MerchantItemConverter.toHerb(item)
                    .copy(quantity = quantity)
                val existing = herbs.all().find {
                    it.name == h.name && it.rarity == h.rarity &&
                    it.category == h.category
                }
                if (existing != null) {
                    val newQty = (existing.quantity + h.quantity)
                        .coerceAtMost(inventoryConfig.getMaxStackSize("herb"))
                    herbs.update(existing.id) {
                        it.copy(quantity = newQty)
                    }
                } else {
                    herbs.add(h)
                }
            }
            "seed" -> {
                val s = MerchantItemConverter.toSeed(item)
                    .copy(quantity = quantity)
                val existing = seeds.all().find {
                    it.name == s.name && it.rarity == s.rarity &&
                    it.growTime == s.growTime
                }
                if (existing != null) {
                    val newQty = (existing.quantity + s.quantity)
                        .coerceAtMost(inventoryConfig.getMaxStackSize("seed"))
                    seeds.update(existing.id) {
                        it.copy(quantity = newQty)
                    }
                } else {
                    seeds.add(s)
                }
            }
        }
    }
}

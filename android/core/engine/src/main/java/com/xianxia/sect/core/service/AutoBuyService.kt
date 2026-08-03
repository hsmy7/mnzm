package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@GameService("AutoBuyService")
class AutoBuyService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val merchantAndRecruitService: MerchantAndRecruitService,
    private val spiritStoneWallet: SpiritStoneWallet
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
    fun executeAutoBuy(year: Int, month: Int) {
        stateStore.update { executeAutoBuy(year, month, this) }
    }

    fun executeAutoBuy(year: Int, month: Int, state: MutableGameState) {
        val data = state.gameData
        if (data.autoBuyList.isEmpty()) return
        if (data.travelingMerchantItems.isEmpty()) return

        var purchasedCount = 0
        var skippedNoFunds = 0

        val newMerchantItems = data.travelingMerchantItems.toMutableList()

        for (entry in data.autoBuyList) {
            val matchIdx = newMerchantItems.indexOfFirst { item ->
                matches(entry, item)
            }
            if (matchIdx < 0) continue

            val merchantItem = newMerchantItems[matchIdx]
            if (merchantItem.quantity <= 0) continue

            // 检查仓库容量
            if (!state.canAddToWarehouse(merchantItem)) {
                DomainLog.i(TAG,
                    "自动购买跳过（仓库满）: ${merchantItem.name}")
                continue
            }

            // 计算可买数量
            val buyQty = calculateBuyQuantity(
                state.gameData.spiritStones, merchantItem.price, merchantItem.quantity)
            if (buyQty <= 0) {
                skippedNoFunds++
                continue
            }
            val cost = merchantItem.price * buyQty

            // 通过钱包扣除灵石（检查扣除结果，失败则跳过该物品）
            val deductResult = spiritStoneWallet.deduct(state, cost, SpiritStoneGrade.LOW, SpiritStoneReason.Purchase, SpiritStoneSource.MerchantTrade)
            if (deductResult !is DeductResult.Success) {
                skippedNoFunds++
                continue
            }

            // 减少商人库存
            val remaining = merchantItem.quantity - buyQty
            if (remaining <= 0) {
                newMerchantItems.removeAt(matchIdx)
            } else {
                newMerchantItems[matchIdx] =
                    merchantItem.copy(quantity = remaining)
            }

            // 加入仓库（在 MutableGameState 上下文中）
            state.addToWarehouse(merchantItem, buyQty)
            purchasedCount++
        }

        state.gameData = state.gameData.copy(
            travelingMerchantItems = newMerchantItems
        )

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

    /**
     * 自动购买预检（在事务缓冲内调用）。
     *
     * 对抗性审查 M3 修复：先查事务缓冲槽位（批内条目可见前一条已占用的槽位，
     * 避免"预检已提交快照过期 → 灵石花在溢出转邮件的物品上"），
     * 再按类型查合并空间（已提交快照，宽松检查——实际由 addXxx 兜底）。
     */
    private fun MutableGameState.canAddToWarehouse(item: MerchantItem): Boolean {
        if (!inventorySystem.canAddItemInTransaction(this)) return false
        return when (item.type.lowercase(Locale.ROOT)) {
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
            "spiritstone" -> true
            else -> false
        }
    }

    /**
     * 自动购买入库——统一委托 [InventorySystem.addXxx]（重入事务操作同一缓冲）。
     * 溢出部分由溢出邮件机制转为邮件通知玩家（自动类路径，物品不丢失）。
     */
    private fun MutableGameState.addToWarehouse(
        item: MerchantItem,
        quantity: Int
    ) {
        inventorySystem.withTrackingSource("merchant") {
            when (item.type.lowercase(Locale.ROOT)) {
                "equipment" ->
                    inventorySystem.addEquipmentStack(MerchantItemConverter.toEquipment(item).copy(quantity = quantity))
                "manual" ->
                    inventorySystem.addManualStack(MerchantItemConverter.toManual(item).copy(quantity = quantity))
                "pill" ->
                    inventorySystem.addPill(MerchantItemConverter.toPill(item).copy(quantity = quantity))
                "material" ->
                    inventorySystem.addMaterial(MerchantItemConverter.toMaterial(item).copy(quantity = quantity))
                "herb" ->
                    inventorySystem.addHerb(MerchantItemConverter.toHerb(item).copy(quantity = quantity))
                "seed" ->
                    inventorySystem.addSeed(MerchantItemConverter.toSeed(item).copy(quantity = quantity))
                "spiritstone" -> {
                    when (item.name) {
                        "中品灵石" -> gameData = gameData.copy(
                            midGradeSpiritStones = gameData.midGradeSpiritStones + quantity
                        )
                        "上品灵石" -> gameData = gameData.copy(
                            highGradeSpiritStones = gameData.highGradeSpiritStones + quantity
                        )
                    }
                }
            }
        }
    }
}

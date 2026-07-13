package com.xianxia.sect.core.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import kotlin.math.ceil

/**
 * 掠夺计算器（纯函数 + 副作用分离）。
 *
 * 从 [ExplorationService.computeLoot] + [applyMaterialLoot] 提取，
 * 业务逻辑完全一致，但修复了以下缺陷：
 *
 * 1. 储物袋扣除改为单次 [mapInPlace] + [filterInPlace]，消除双重扣除 bug
 * 2. [repeat] 前加 [coerceAtLeast(0)] 防御负数量
 * 3. [SPIRIT_STONES_PER_ITEM] 为 0 时跳过除零
 * 4. [manualStacks] 增加 [filterInPlace] 过滤（原代码遗漏）
 * 5. 所有物品扣除后 [filterInPlace] 统一在末尾一次完成
 *
 * [computeLootPlan] 为纯函数，只读取状态不写；
 * [applyLoot] 执行实际扣除，修改 [state] 上的仓库数据。
 */
class LootCalculator @Inject constructor() {

    /**
     * 掠夺结果数据。
     */
    data class BeastLootData(
        val stolenSpiritStones: Long = 0L,
        val stolenBagCount: Int = 0,
        val stolenItems: List<LootedItem> = emptyList()
    ) {
        /**
         * 生成掠夺详情字符串，用于 BattleLog。
         */
        fun toDetailString(beastName: String): String {
            val parts = mutableListOf<String>()
            if (stolenSpiritStones > 0) {
                parts.add("灵石${stolenSpiritStones}")
            }
            stolenItems.forEach { parts.add("${it.name}x${it.count}") }
            if (stolenBagCount > 0) {
                parts.add("储物袋x${stolenBagCount}")
            }
            return if (parts.isEmpty()) {
                "被${beastName}袭击"
            } else {
                "被${beastName}掠夺：${parts.joinToString("、")}"
            }
        }

        /**
         * 转换为 [BattleRewardItem] 列表，用于弹窗展示。
         */
        fun toRewardItems(): List<BattleRewardItem> {
            val items = mutableListOf<BattleRewardItem>()
            if (stolenSpiritStones > 0) {
                items.add(BattleRewardItem(
                    name = "灵石",
                    quantity = stolenSpiritStones.toInt(),
                    rarity = 1,
                    type = "spiritStones"
                ))
            }
            stolenItems.forEach { item ->
                items.add(BattleRewardItem(
                    itemId = item.id,
                    name = item.name,
                    quantity = item.count,
                    rarity = item.rarity,
                    type = item.type
                ))
            }
            if (stolenBagCount > 0) {
                items.add(BattleRewardItem(
                    name = "储物袋",
                    quantity = stolenBagCount,
                    rarity = 1,
                    type = "storageBag"
                ))
            }
            return items
        }
    }

    /**
     * 被掠夺的单件物品记录。
     */
    data class LootedItem(
        val id: String,
        val name: String,
        val type: String,
        val rarity: Int,
        val count: Int
    )

    // ── 临时内部条目（computeLootPlan 内使用） ──

    private data class Entry(
        val type: String,
        val id: String,
        val name: String,
        val rarity: Int
    )

    /**
     * 计算掠夺方案（纯函数，无副作用）。
     *
     * 从 [GameData] 读取灵石，从 [state] 的各仓库 [EntityStore] 读取物品，
     * 按 [GameConfig.WorldMap.BEAST_LOOT_RATIO] 比例随机选取物品作为掠夺清单。
     *
     * @param gd    游戏数据（用于灵石数量）
     * @param state 可变游戏状态（用于仓库物品列表）
     * @return 掠夺方案
     */
    fun computeLootPlan(gd: GameData, state: MutableGameState): BeastLootData {
        val itemUnit = GameConfig.WorldMap.SPIRIT_STONES_PER_ITEM
        val ratio = GameConfig.WorldMap.BEAST_LOOT_RATIO

        val entries = mutableListOf<Entry>()

        // 灵石（20000 = 1 单位）—— 防御除零：itemUnit <= 0 时跳过
        val stoneUnits = if (itemUnit > 0) (gd.spiritStones / itemUnit).toInt() else 0
        repeat(stoneUnits.coerceAtLeast(0)) {
            entries.add(Entry("spiritStones", "", "灵石", 1))
        }

        // 储物袋 —— 防御负数 quantity
        state.storageBags.items.forEach { bag ->
            repeat(bag.quantity.coerceAtLeast(0)) {
                entries.add(Entry("storageBag", bag.id, bag.name, bag.rarity))
            }
        }

        // 各物品类型 —— 防御负数 quantity
        fun <T> addItems(
            items: List<T>,
            type: String,
            nameFn: (T) -> String,
            idFn: (T) -> String,
            rarityFn: (T) -> Int,
            qtyFn: (T) -> Int
        ) {
            items.forEach { item ->
                repeat(qtyFn(item).coerceAtLeast(0)) {
                    entries.add(Entry(type, idFn(item), nameFn(item), rarityFn(item)))
                }
            }
        }

        addItems(state.materials.items, "material",
            { (it as Material).name }, { (it as Material).id },
            { (it as Material).rarity }, { (it as Material).quantity })
        addItems(state.pills.items, "pill",
            { (it as Pill).name }, { (it as Pill).id },
            { (it as Pill).rarity }, { (it as Pill).quantity })
        addItems(state.herbs.items, "herb",
            { (it as Herb).name }, { (it as Herb).id },
            { (it as Herb).rarity }, { (it as Herb).quantity })
        addItems(state.seeds.items, "seed",
            { (it as Seed).name }, { (it as Seed).id },
            { (it as Seed).rarity }, { (it as Seed).quantity })
        addItems(state.equipmentStacks.items, "equipment",
            { (it as EquipmentStack).name }, { (it as EquipmentStack).id },
            { (it as EquipmentStack).rarity }, { (it as EquipmentStack).quantity })
        addItems(state.manualStacks.items, "manual",
            { (it as ManualStack).name }, { (it as ManualStack).id },
            { (it as ManualStack).rarity }, { (it as ManualStack).quantity })

        val stealCount = ceil(entries.size * ratio).toInt()
            .coerceAtMost(entries.size)
        if (stealCount <= 0) return BeastLootData()

        val selected = entries.shuffled().take(stealCount)

        val stolenStones = selected.count { it.type == "spiritStones" } * itemUnit
        val stolenBags = selected.count { it.type == "storageBag" }
        val stolenItems = selected
            .filter { it.type !in listOf("spiritStones", "storageBag") }
            .groupBy { it.id to it.type }
            .map { (_, list) ->
                val first = list.first()
                LootedItem(first.id, first.name, first.type, first.rarity, list.size)
            }

        return BeastLootData(stolenStones, stolenBags, stolenItems)
    }

    /**
     * 执行掠夺扣除（副作用到 [state] 上）。
     *
     * 从仓库中扣除 [loot] 指定的物品和灵石。
     * 所有扣除操作在 [state] 上原地执行，不涉及外部依赖。
     */
    fun applyLoot(
        state: MutableGameState,
        loot: BeastLootData
    ) {
        // 扣除灵石 —— 直接修改 gameData.spiritStones（不经过 Wallet，避免循环依赖）
        if (loot.stolenSpiritStones > 0) {
            state.gameData = state.gameData.copy(
                spiritStones = (state.gameData.spiritStones - loot.stolenSpiritStones)
                    .coerceAtLeast(0L)
            )
        }

        // 扣除储物袋（单次遍历 mapInPlace + filterInPlace，消除双重扣除 bug）
        if (loot.stolenBagCount > 0) {
            var remaining = loot.stolenBagCount
            state.storageBags.mapInPlace { bag ->
                if (remaining <= 0) {
                    bag
                } else if (bag.quantity <= remaining) {
                    remaining -= bag.quantity
                    bag.copy(quantity = 0)
                } else {
                    val updated = bag.copy(quantity = bag.quantity - remaining)
                    remaining = 0
                    updated
                }
            }
            state.storageBags.filterInPlace { it.quantity > 0 }
        }

        // 扣除物品（仅置零 quantity，不删除）
        for (item in loot.stolenItems) {
            when (item.type) {
                "material" -> state.materials.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(quantity = 0)
                }
                "pill" -> state.pills.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(quantity = 0)
                }
                "herb" -> state.herbs.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(quantity = 0)
                }
                "seed" -> state.seeds.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(quantity = 0)
                }
                "equipment" -> state.equipmentStacks.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(quantity = 0)
                }
                "manual" -> state.manualStacks.update(item.id) {
                    val q = it.quantity - item.count
                    if (q > 0) it.copy(quantity = q) else it.copy(quantity = 0)
                }
            }
        }

        // ★ 过滤掉 quantity=0 的物品（统一在末尾一次完成，含 manualStacks 原代码遗漏）
        state.materials.filterInPlace { it.quantity > 0 }
        state.pills.filterInPlace { it.quantity > 0 }
        state.herbs.filterInPlace { it.quantity > 0 }
        state.seeds.filterInPlace { it.quantity > 0 }
        state.equipmentStacks.filterInPlace { it.quantity > 0 }
        state.manualStacks.filterInPlace { it.quantity > 0 }
    }
}

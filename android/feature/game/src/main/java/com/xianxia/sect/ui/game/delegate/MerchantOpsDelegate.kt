package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.MerchantRefreshResult
import com.xianxia.sect.core.engine.bulkSellItems
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xianxia.sect.core.engine.purchaseMerchantRefresh
import com.xianxia.sect.core.engine.refreshTravelingMerchantManual

/**
 * 商人操作委托（自 GameViewModel 拆出，行为零变更）。
 *
 * 一键出售（批量筛选 + 引擎事务）/ 玉符购商人刷新 / 行商功法手动刷新。
 * 结果反馈经 [onSuccess]/[onError] 回调（GameViewModel 注入 showError/showSuccess）。
 */
class MerchantOpsDelegate(
    private val gameEngine: GameEngine,
    private val onSuccess: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    /** 消耗 1 玉符获取 3 次商人刷新次数（上限 999） */
    suspend fun purchaseMerchantRefresh(): MerchantRefreshResult =
        gameEngine.purchaseMerchantRefresh()

    fun refreshTravelingMerchantManual() {
        gameEngine.launchOnEngine { gameEngine.refreshTravelingMerchantManual() }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun bulkSellItems(selectedRarities: Set<Int>, selectedTypes: Set<String>) {
        gameEngine.launchOnEngine {
            try {
                val operations = collectBulkSellOperations(selectedRarities, selectedTypes)
                if (operations.isEmpty()) {
                    withContext(Dispatchers.Main) { onError("没有符合条件的物品可出售（已排除锁定物品）") }
                    return@launchOnEngine
                }
                val result = gameEngine.bulkSellItems(operations)
                withContext(Dispatchers.Main) {
                    if (result.soldCount > 0) {
                        onSuccess("成功出售 ${result.soldCount} 件物品，获得 ${result.totalEarned} 灵石" +
                            result.failedItemNames.takeIf { it
                                .isNotEmpty() }?.let { "\n以下物品出售失败：${it.joinToString("、")}" } ?: "")
                    } else onError("出售失败，物品可能已被锁定或不存在")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { withContext(Dispatchers.Main) { onError(e.message ?: "一键出售失败") } }
        }
    }

    /** 批量出售操作收集：按类型筛选 + 稀有度匹配 + 锁定排除 */
    private fun collectBulkSellOperations(
        selectedRarities: Set<Int>,
        selectedTypes: Set<String>
    ): List<GameEngine.BulkSellOperation> {
        val operations = mutableListOf<GameEngine.BulkSellOperation>()
        val typeConfigs = listOf(
            "EQUIPMENT" to (gameEngine.equipmentStacks.value as List<Any>),
            "MANUAL" to (gameEngine.manualStacks.value as List<Any>),
            "PILL" to (gameEngine.pills.value as List<Any>),
            "MATERIAL" to (gameEngine.materials.value as List<Any>),
            "HERB" to (gameEngine.herbs.value as List<Any>),
            "SEED" to (gameEngine.seeds.value as List<Any>)
        )
        for ((typeName, items) in typeConfigs) {
            if (!selectedTypes.contains(typeName)) continue
            @Suppress("UNCHECKED_CAST")
            (items as? List<*>)?.forEach { item ->
                val rarity = bulkSellItemRarity(item) ?: return@forEach
                val locked = bulkSellItemLocked(item)
                if (selectedRarities.contains(rarity) && !locked) {
                    val qty = bulkSellItemQuantity(item)
                    operations.add(GameEngine.BulkSellOperation(bulkSellItemKey(item), "", qty,
                        typeName.lowercase()))
                }
            }
        }
        return operations
    }
}

/** 批量出售条目稀有度：未知类型为 null（调用方跳过） */
private fun bulkSellItemRarity(item: Any?): Int? = when (item) {
    is EquipmentStack -> item.rarity
    is ManualStack -> item.rarity
    is Pill -> item.rarity
    is Material -> item.rarity
    is Herb -> item.rarity
    is Seed -> item.rarity
    else -> null
}

/** 批量出售条目键：id 字符串化，未知类型为空串 */
private fun bulkSellItemKey(item: Any?): String = when (item) {
    is EquipmentStack -> item.id.toString()
    is ManualStack -> item.id.toString()
    is Pill -> item.id.toString()
    is Material -> item.id.toString()
    is Herb -> item.id.toString()
    is Seed -> item.id.toString()
    else -> ""
}

/** 批量出售条目锁定判定：装备/功法堆叠可锁定 */
private fun bulkSellItemLocked(item: Any?): Boolean =
    item is EquipmentStack && item.isLocked || item is ManualStack && item.isLocked

/** 批量出售条目数量：未知类型按 1 */
private fun bulkSellItemQuantity(item: Any?): Int = when (item) {
    is EquipmentStack -> item.quantity
    is ManualStack -> item.quantity
    is Pill -> item.quantity
    is Material -> item.quantity
    is Herb -> item.quantity
    is Seed -> item.quantity
    else -> 1
}

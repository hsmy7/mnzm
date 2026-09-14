package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.StackKeys
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.model.StorageBagItem

// ── InventorySystem 拆分域 3/7（行为零变更） ──

private val SOURCE_DISCIPLE_DEATH = InventorySystem.SOURCE_DISCIPLE_DEATH

private val TAG = InventorySystem.TAG
private val VALID_RARITY_RANGE = InventorySystem.VALID_RARITY_RANGE
internal fun InventorySystem.validateStackableItem(name: String, rarity: Int, quantity: Int): DomainResult<Unit> {
    if (name.isBlank()) return DomainResult.Failure(AppError.Domain.Inventory.InvalidName())
    if (rarity !in VALID_RARITY_RANGE) return DomainResult.Failure(AppError.Domain.Inventory.InvalidRarity(rarity))
    if (quantity <= 0) return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(quantity))
    return DomainResult.Success(Unit)
}

/**
 * 添加装备堆叠（合并 + 溢出转邮件 + 年度来源追踪）。
 *
 * @param item 待添加的装备堆叠
 */

fun InventorySystem.returnEquipmentToStack(instance: EquipmentInstance): DomainResult<EquipmentStack> {
    return stateStore.updateAndReturn {
        val otherTypes = manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
        val store = StackableItemStore(
            initialItems = equipmentStacks.all(),
            stackKeyOf = StackKeys::equipment,
            maxStack = getMaxStackForType("equipment_stack"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val item = instance.toStack(quantity = 1)
        val result = store.add(item)
        equipmentStacks.replaceAll(store.all())
        handleOverflowResult(result, "equipment", item)
        result
    }
}

/**
 * 袋条目物化回仓库（发放类——溢出自动转邮件，物品不丢）。
 *
 * 调用时机：弟子死亡/逐出时，袋物品物化回仓库（玩家保留物品，不随弟子消失）。
 * 调用方在 [stateStore.update] 事务内调用（本方法经 updateAndReturn 重入同一缓冲）。
 * 调用方包裹 [withTrackingSource] 归因年度报告。
 *
 * - 实例条目（equipment_instance/manual_instance）：[returnEquipmentToStack]/
 *   [returnManualToStack] 完整保真（含 nurture）
 * - 堆叠条目（equipment_stack/manual_stack/pill/material/herb/seed）：模板重建
 *   （[BagItemReconstructor]，minRealm/quantity 条目保真）
 * - 模板缺失：丢弃 + 日志（无法重建，不阻塞删除流程）
 * - 未物化条目（payload 空）：忽略（读档物化器已处理，运行期不应出现）
 *
 * @return 物化成功的条目数（供日志）
 */

fun InventorySystem.materializeBagItemsToWarehouse(items: List<StorageBagItem>): Int =
    items.count { materializeSingleBagItem(it) }

/** 单个袋条目物化。@return 是否物化成功 */

internal fun InventorySystem.materializeSingleBagItem(item: StorageBagItem): Boolean {
    // 跨模块 public API 属性不能 smart cast，先取局部变量
    val eqInstance = item.equipmentInstance
    val mnInstance = item.manualInstance
    return when {
        eqInstance != null -> materializeEquipmentInstance(eqInstance, item)
        mnInstance != null -> materializeManualInstance(mnInstance, item)
        item.stackedData != null -> materializeStackedItem(item)
        // 未物化条目（payload 空）：忽略
        else -> false
    }
}

/** 装备实例物化：入仓成功或溢出转邮件即完成 */

internal fun InventorySystem.materializeEquipmentInstance(
    eqInstance: EquipmentInstance,
    item: StorageBagItem
): Boolean {
    val result = returnEquipmentToStack(eqInstance)
    // 物化完成判据：入仓成功（Success/Partial）或仓库满已转邮件（Failure Full——
    // handleOverflowResult 已把物品转邮件，实例删除防复制）；其他失败保留实例
    val completed = result is DomainResult.Success || result is DomainResult.Partial ||
        (result is DomainResult.Failure && result.error is AppError.Domain.Inventory.Full)
    if (completed) {
        // 防双持有：实例已物化入栈（或溢出转邮件），从实例表删除——
        // 外层（死亡/逐出）大事务包裹本方法，回滚时删除一并回滚，原子性由外层保证
        stateStore.update { equipmentInstances = equipmentInstances.filter { it.id != eqInstance.id } }
    } else {
        DomainLog.w(TAG, "物化装备失败 ${item.name}: ${(result as? DomainResult.Failure)?.error}")
    }
    return completed
}

/** 功法实例物化：入仓成功或溢出转邮件即完成 */

internal fun InventorySystem.materializeManualInstance(
    mnInstance: ManualInstance,
    item: StorageBagItem
): Boolean {
    val result = returnManualToStack(mnInstance)
    val completed = result is DomainResult.Success || result is DomainResult.Partial ||
        (result is DomainResult.Failure && result.error is AppError.Domain.Inventory.Full)
    if (completed) {
        // 防双持有：同装备分支
        stateStore.update { manualInstances = manualInstances.filter { it.id != mnInstance.id } }
    } else {
        DomainLog.w(TAG, "物化功法失败 ${item.name}: ${(result as? DomainResult.Failure)?.error}")
    }
    return completed
}

/** 堆叠条目物化前置：数量合法性检查 + 模板重建 */

internal fun InventorySystem.reconstructStackedItem(item: StorageBagItem): ReconstructedBagStack? {
    // 篡改防御：堆叠条目数量非法（<=0）拒绝物化——防 0/负数量白得物品
    if (item.quantity <= 0) {
        DomainLog.w(TAG, "物化失败（数量非法 quantity=${item.quantity}，跳过）：${item.name}")
        return null
    }
    val reconstructed = BagItemReconstructor.reconstruct(item)
    if (reconstructed == null) {
        DomainLog.w(TAG, "物化失败（无模板，随弟子删除）：${item.name}")
    }
    return reconstructed
}

/** 堆叠条目模板重建物化 */

internal fun InventorySystem.materializeStackedItem(item: StorageBagItem): Boolean {
    val reconstructed = reconstructStackedItem(item) ?: return false
    val result = when (reconstructed) {
        is ReconstructedBagStack.Equipment -> addEquipmentStack(reconstructed.stack)
        is ReconstructedBagStack.Manual -> addManualStack(reconstructed.stack)
        is ReconstructedBagStack.Pill -> addPill(reconstructed.stack)
        is ReconstructedBagStack.Herb -> addHerb(reconstructed.stack)
        is ReconstructedBagStack.Seed -> addSeed(reconstructed.stack)
        is ReconstructedBagStack.Material -> addMaterial(reconstructed.stack)
    }
    val completed = result is DomainResult.Success || result is DomainResult.Partial
    if (!completed) {
        DomainLog.w(TAG, "物化失败 ${item.name}: ${(result as? DomainResult.Failure)?.error}")
    }
    return completed
}

// W4-C 遮蔽根治：此处曾有与本类成员 [InventorySystem.materializeDiscipleBagAndMarkDead]
// **同签名**的顶层扩展函数（内容逐行相同）——Kotlin 解析中成员恒胜出，该扩展
// 自始为死代码且构成"改错文件"陷阱（同签名遮蔽静默行为分歧风险，w3-08 顺手根治项）。
// 已收敛为 InventorySystem.kt:294 成员单一实现；死亡统一入口契约（事务内调用 /
// wasAlive 双计防线 / 全部 5 条死亡路径恰好计 1 次）以成员 KDoc 为准。


fun InventorySystem.returnManualToStack(instance: ManualInstance): DomainResult<ManualStack> {
    return stateStore.updateAndReturn {
        val otherTypes = equipmentStacks.size + pills.size + materials.size + herbs.size + seeds.size
        val store = StackableItemStore(
            initialItems = manualStacks.all(),
            stackKeyOf = StackKeys::manual,
            maxStack = getMaxStackForType("manual_stack"),
            maxSlots = { computeMaxSlots() - otherTypes },
            notFound = { AppError.Domain.Inventory.NotFound(it) }
        )
        val item = instance.toStack(quantity = 1)
        val result = store.add(item)
        manualStacks.replaceAll(store.all())
        handleOverflowResult(result, "manual", item)
        result
    }
}

fun InventorySystem.removeEquipment(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
    if (quantity <= 0) return false
    return stateStore.updateAndReturn {
        val existing = equipmentStacks.find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked equipment: ${existing.hashCode()}")
            return@updateAndReturn false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity equipment, only ${existing.quantity} available")
            return@updateAndReturn false
        }
        var removed = false
        equipmentStacks = equipmentStacks.mapNotNull { item ->
            if (item.id == id && !removed) {
                val newQty = item.quantity - quantity
                when {
                    newQty < 0 -> item
                    newQty == 0 -> { removed = true; null }
                    else -> { removed = true; item.copy(quantity = newQty) }
                }
            } else item
        }
        true
    }
}

fun InventorySystem.removeEquipmentInstance(id: String): Boolean {
    return stateStore.updateAndReturn {
        val oldSize = equipmentInstances.size
        equipmentInstances = equipmentInstances.filter { it.id != id }
        equipmentInstances.size < oldSize
    }
}

fun InventorySystem.updateEquipmentStack(id: String, transform: (EquipmentStack) -> EquipmentStack): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        equipmentStacks = equipmentStacks.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

fun InventorySystem.updateEquipmentInstance(
    id: String, transform: (EquipmentInstance) -> EquipmentInstance
): Boolean {
    return stateStore.updateAndReturn {
        var found = false
        equipmentInstances = equipmentInstances.map {
            if (it.id == id) {
                found = true
                transform(it)
            } else it
        }
        found
    }
}

package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.Herb
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
 * 装备实例归还仓库（B3 实例轨：一行一实例，经 [addEquipmentInstance] 查重 + 追加；
 * 方法名保留旧称以兼容调用点）。装备无堆叠合并，仓库满不转邮件（实例保留在原处）。
 *
 * @param instance 待归还的装备实例
 */
fun InventorySystem.returnEquipmentToStack(instance: EquipmentInstance): DomainResult<EquipmentInstance> =
    addEquipmentInstance(instance)

/**
 * 袋条目物化回仓库（发放类——溢出自动转邮件，物品不丢）。
 *
 * 调用时机：弟子死亡/逐出时，袋物品物化回仓库（玩家保留物品，不随弟子消失）。
 * 调用方在 [stateStore.update] 事务内调用（本方法经 updateAndReturn 重入同一缓冲）。
 * 调用方包裹 [withTrackingSource] 归因年度报告。
 *
 * - 实例条目（equipment_instance/manual_instance）：[returnEquipmentToStack]/
 *   [returnManualToStack] 完整保真（等级/词条随实例）
 * - 堆叠条目（manual_stack/pill/material/herb/seed）：模板重建
 *   （[BagItemReconstructor]，minRealm/quantity 条目保真）；装备堆叠条目
 *   不再重建（丢弃 + 日志，实例条目由物化器原样保留）
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
        val otherTypes = equipmentInstances.size + pills.size + materials.size + herbs.size + seeds.size
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

/**
 * 按实例 id 移除装备（B3 实例轨：装备无数量，1 件 = 1 条目；方法名保留旧称兼容调用点）。
 *
 * @param bypassLock true 时忽略锁定保护（死亡清算等系统路径用）
 */
fun InventorySystem.removeEquipment(id: String, @Suppress("UNUSED_PARAMETER") quantity: Int = 1,
    bypassLock: Boolean = false): Boolean {
    return stateStore.updateAndReturn {
        val existing = equipmentInstances.all().find { it.id == id } ?: return@updateAndReturn false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked equipment: ${existing.name}")
            return@updateAndReturn false
        }
        equipmentInstances = equipmentInstances.filter { it.id != id }
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

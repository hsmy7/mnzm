package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.util.UUID
import com.xianxia.sect.core.engine.domain.disciple.ITEM_TYPE_MANUAL_INSTANCE
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.util.StorageBagUtils


// ── Cross-domain: Use pill ──────────────────────────────────────────

fun GameEngine.usePill(discipleId: String, pillId: String) {
    giveItemToDisciple(discipleId, pillId, "pill")
}

// ── 弟子管理事务 native 转发（batch-08：装备穿脱/功法学忘）────────────
//
// C++ disciple_tx.h 事务 AUTHORITATIVE 转发（InventoryNativeForward 同族
// 机制：flag 门控 + 失败信封/降级返回 null → 调用方回退 Kotlin 原路径——
// 双实现并行契约）。成功时镜像已回读（applyDirtyFromNative）。
// equip 信封附 logLine 日志草稿 → [applyEquipLogDraft] 回写 lifeEvents 瞬态列
//（disciple_purchase PurchaseLogDraft 同族；C++ 无该列）。

private fun GameEngine.tryDiscipleTxNative(
    actionId: Int,
    paramsBuilder: JsonObjectBuilder.() -> Unit
): JsonElement? = InventoryNativeForward.tryForward(this, actionId, paramsBuilder)

/** equip 日志草稿回写（Kotlin lifeEvents 瞬态列；C++ 无该协议字段）。 */
private fun GameEngine.applyEquipLogDraft(discipleId: String, logLine: String?) {
    if (logLine.isNullOrEmpty()) return
    val id = discipleId.toIntOrNull() ?: return
    stateStore.update {
        if (id in discipleTables.ids) {
            discipleTables.lifeEvents[id] =
                discipleTables.lifeEvents.getOrDefault(id, emptyList()) + logLine
        }
    }
}

/** 装备卸下事务转发（成功 true；flag 关/降级/失败信封 false）。 */
private fun GameEngine.tryUnequipNative(discipleId: String, equipmentId: String): Boolean =
    tryDiscipleTxNative(ActionIds.DISCIPLE_TX_UNEQUIP) {
        put("discipleId", discipleId)
        put("equipmentId", equipmentId)
    }?.str("unequipped") == "true"

// ── Cross-domain: Manual operations ─────────────────────────────────

suspend fun GameEngine.equipItem(discipleId: String, equipmentId: String): DomainResult<Unit> {
    // C++ 真相先行（校验链/双段写在 C++；logLine 由 Kotlin 回写瞬态列）
    val data = tryDiscipleTxNative(ActionIds.DISCIPLE_TX_EQUIP) {
        put("discipleId", discipleId)
        put("equipmentId", equipmentId)
    }
    if (data?.str("equipped") == "true") {
        applyEquipLogDraft(discipleId, data.str("logLine"))
        return DomainResult.Success(Unit)
    }
    return discipleService.equipEquipment(discipleId, equipmentId)
}

suspend fun GameEngine.unequipItem(discipleId: String, slot: EquipmentSlot): DomainResult<Unit>? {
    val disciple = getDiscipleById(discipleId) ?: return null
    val equipId = when (slot) { EquipmentSlot.WEAPON -> disciple.equipment.weaponId; EquipmentSlot.ARMOR -> disciple
        .equipment.armorId; EquipmentSlot.BOOTS -> disciple.equipment.bootsId; EquipmentSlot.ACCESSORY -> disciple
            .equipment.accessoryId }
    if (equipId.isEmpty()) return null
    if (tryUnequipNative(discipleId, equipId)) return DomainResult.Success(Unit)
    val result = discipleService.unequipEquipment(discipleId, equipId)
    return result
}

suspend fun GameEngine.unequipItemById(discipleId: String, equipmentId: String): DomainResult<Unit> {
    if (tryUnequipNative(discipleId, equipmentId)) return DomainResult.Success(Unit)
    val result = discipleService.unequipEquipment(discipleId, equipmentId)
    return result
}

suspend fun GameEngine.forgetManual(discipleId: String, instanceId: String) {
    // C++ 真相先行（实例入袋/manualIds/熟练度清理在 C++）
    val data = tryDiscipleTxNative(ActionIds.DISCIPLE_TX_UNLEARN_MANUAL) {
        put("discipleId", discipleId)
        put("instanceId", instanceId)
    }
    if (data?.str("unlearned") == "true") return
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val instance = manualInstances.get(instanceId) ?: return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val currentDisciple = discipleTables.assemble(id)
            // D-03：遗忘的功法实例直接铸造入袋（容量无上限，永不失败），
            // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
            val updatedManualIds = currentDisciple.manualIds.filter { mid -> mid != instanceId }
            val updatedDisciple = currentDisciple.copy(
                manualIds = updatedManualIds,
                equipment = currentDisciple.equipment.copy(
                    storageBagItems = StorageBagUtils.increaseItemQuantity(
                        currentDisciple.equipment.storageBagItems,
                        StorageBagItem(
                            itemId = instanceId, itemType = ITEM_TYPE_MANUAL_INSTANCE,
                            name = instance.name, rarity = instance.rarity, quantity = 1,
                            obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                            manualInstance = instance
                        )
                    )
                )
            )
            discipleTables.remove(id)
            discipleTables.insert(updatedDisciple)
            // 实例入袋后从实例表删除，防止双持有
            manualInstances.remove(instanceId)
            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies[discipleId]?.let { profList ->
                val filtered = profList.filter { it.manualId != instanceId }
                if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                else updatedProficiencies[discipleId] = filtered
            }
            gameData = gameData.copy(manualProficiencies = updatedProficiencies)
        }
    }
}

suspend fun GameEngine.replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val oldInstance = manualInstances.get(oldInstanceId) ?: return@update
            val newStack = manualStacks.get(newStackId) ?: return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val disciple = discipleTables.assemble(id)
            if (newStack.quantity < 1) return@update
            if (!GameConfig.Realm.meetsRealmRequirement(disciple.realm, newStack.minRealm)) return@update
            val blocked = newStack.type == ManualType.MIND && oldInstance.type != ManualType.MIND && disciple.manualIds
                .filter { it != oldInstanceId }.any { mid -> manualInstances.get(mid)?.type == ManualType.MIND }
            if (blocked) return@update
            val hasSameName = disciple.manualIds.filter { it != oldInstanceId }.any { mid -> manualInstances
                .get(mid)?.name == newStack.name }
            if (hasSameName) return@update
            // D-03：替换功法时旧实例直接铸造入袋（容量无上限，永不失败），
            // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
            val currentNewStack = manualStacks.get(newStackId) ?: return@update
            val newQty = currentNewStack.quantity - 1
            if (newQty <= 0) manualStacks.remove(newStackId)
            else manualStacks.update(newStackId) { it.copy(quantity = newQty) }
            val newInstance = newStack.toInstance(id = java.util.UUID.randomUUID().toString(), ownerId = discipleId,
                isLearned = true)
            manualInstances.add(newInstance)
            val updatedProficiencies = gameData.manualProficiencies.toMutableMap()
            updatedProficiencies[discipleId]?.let { profList ->
                val filtered = profList.filter { it.manualId != oldInstanceId }
                if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                else updatedProficiencies[discipleId] = filtered
            }
            val updatedManualIds = (disciple.manualIds.filter { mid -> mid != oldInstanceId }) + newInstance.id
            val updatedDisciple = disciple.copy(
                manualIds = updatedManualIds,
                equipment = disciple.equipment.copy(
                    storageBagItems = StorageBagUtils.increaseItemQuantity(
                        disciple.equipment.storageBagItems,
                        StorageBagItem(
                            itemId = oldInstanceId, itemType = ITEM_TYPE_MANUAL_INSTANCE,
                            name = oldInstance.name, rarity = oldInstance.rarity, quantity = 1,
                            obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                            manualInstance = oldInstance
                        )
                    )
                )
            )
            discipleTables.remove(id)
            discipleTables.insert(updatedDisciple)
            // 旧实例入袋后从实例表删除，防止双持有（新实例已 add）
            manualInstances = manualInstances.filter { it.id != oldInstanceId }

            // 记录功法替换日志
            val replaceAge = discipleTables.ages[id]
            val replaceEvents = discipleTables.lifeEvents.getOrDefault(id, emptyList())
            discipleTables.lifeEvents[id] = replaceEvents +
                "${replaceAge}岁：将功法${oldInstance.name}替换为${newStack.name}"

            gameData = gameData.copy(manualProficiencies = updatedProficiencies)
        }
    }
}

suspend fun GameEngine.learnManual(discipleId: String, stackId: String) {
    // C++ 真相先行（资格守卫/堆叠消耗/实例铸造/HP/MP 增量在 C++）
    val data = tryDiscipleTxNative(ActionIds.DISCIPLE_TX_LEARN_MANUAL) {
        put("discipleId", discipleId)
        put("stackId", stackId)
    }
    if (data?.str("learned") == "true") return
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val stack = manualStacks.get(stackId) ?: return@update
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val disciple = discipleTables.assemble(id)
            if (!canLearnManualFromStack(disciple, stack)) return@update
            consumeManualStackForLearn(stackId, stack)
            val instanceId = java.util.UUID.randomUUID().toString()
            val instance = stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true)
            manualInstances.add(instance)
            if (!disciple.manualIds.contains(instanceId)) {
                applyLearnedManualSnapshot(id, disciple, stack, instanceId)
            }
        }
    }
}

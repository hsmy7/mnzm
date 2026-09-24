package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.rebaselineNativeMirror
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.model.BagStackedData

// ── DiscipleFacadeImpl 拆分域 1/2（行为零变更） ──

internal fun DiscipleFacadeImpl.rewardEquipment(discipleId: String, item: RewardSelectedItem) {
    var wrote = false
    // 捕获豁免（updateMirror，§2.81）：赏赐写 9 类实体集合（equipmentStacks/
    // equipmentInstances——已关闭回导，引用比较检测不适用值等值收敛）；本链无
    // native 臂（C++ rewardItemTx 仅支持消耗品四类），Kotlin 即 AUTHORITATIVE
    // 正主——写入经尾部基线重建回导 C++（§2.79 宗门改名迁移同口径）
    stateStore.updateMirror {
        val stack = equipmentStacks.get(item.id)
        if (stack == null || stack.quantity < 1) return@updateMirror
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) return@updateMirror
        wrote = true
        val discipleRealm = discipleTables.realms[id]
        val canEquip = GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
        if (canEquip) {
            val slot = stack.slot
            val oldEquipId = equippedItemIdOf(id = id, slot = slot)
            if (oldEquipId.isNotEmpty()) {
                // 卸下的装备实例直接铸造入袋（容量无上限，永不失败），
                // 不再转仓库堆叠（不占仓库槽位、无溢出邮件路径）
                depositOldEquipmentToBag(id = id, oldEquipId = oldEquipId)
                clearEquipmentSlot(id = id, slot = slot)
            }
            consumeEquipmentStack(itemId = item.id, stack = stack)
            val instanceId = java.util.UUID.randomUUID().toString()
            equipmentInstances.add(stack.toInstance(id = instanceId, ownerId = discipleId, isEquipped = true))
            setEquipmentSlot(id = id, slot = slot, instanceId = instanceId)
        } else {
            consumeEquipmentStack(itemId = item.id, stack = stack)
            // 赏赐装备铸造袋条目（容量无上限，永不失败）——扣仓库数量后
            // 袋条目自带 stackedData（minRealm/slot 供取回重建），不再经仓库中转
            grantEquipmentToBag(discipleId = discipleId, item = item, stack = stack, id = id)
        }
    }
    // 条件性重建：未发生写入（堆叠缺失/弟子无效）时零成本
    if (wrote) gameEngineCore.rebaselineNativeMirror("仓库赏赐装备")
}

/** 当前装备 ID 读取 */

internal fun MutableGameState.equippedItemIdOf(id: Int, slot: EquipmentSlot): String = when (slot) {
    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
    EquipmentSlot.ARMOR -> discipleTables.armorIds[id]
    EquipmentSlot.BOOTS -> discipleTables.bootsIds[id]
    EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id]
    else -> ""
}

/** 旧装备卸装入袋：实例直接铸造入袋并防双持有 */

internal fun MutableGameState.depositOldEquipmentToBag(id: Int, oldEquipId: String) {
    val oldInstance = equipmentInstances.get(oldEquipId)
    if (oldInstance != null) {
        val updatedDisciple = discipleTables.assemble(id)
        discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
            updatedDisciple.equipment.storageBagItems,
            StorageBagItem(
                itemId = oldEquipId, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
                name = oldInstance.name, rarity = oldInstance.rarity, quantity = 1,
                obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                equipmentInstance = oldInstance
            )
        )
        discipleTables.storageBagSpiritStones[id] = updatedDisciple.equipment.storageBagSpiritStones
        discipleTables.discipleSpiritStones[id] = updatedDisciple.equipment.spiritStones
        // 实例入袋后从实例表删除，防止双持有
        equipmentInstances = equipmentInstances.filter { it.id != oldEquipId }
    }
}

/** 装备槽位清空 */

internal fun MutableGameState.clearEquipmentSlot(id: Int, slot: EquipmentSlot) {
    when (slot) {
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = ""
        EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = ""
        EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = ""
        EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = ""
        else -> {}
    }
}

/** 装备槽位写入 */

internal fun MutableGameState.setEquipmentSlot(id: Int, slot: EquipmentSlot, instanceId: String) {
    when (slot) {
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = instanceId
        EquipmentSlot.ARMOR -> discipleTables.armorIds[id] = instanceId
        EquipmentSlot.BOOTS -> discipleTables.bootsIds[id] = instanceId
        EquipmentSlot.ACCESSORY -> discipleTables.accessoryIds[id] = instanceId
        else -> {}
    }
}

/** 仓库装备堆叠消耗 */

internal fun MutableGameState.consumeEquipmentStack(itemId: String, stack: EquipmentStack) {
    if (stack.quantity > 1) {
        equipmentStacks.update(itemId) { it.copy(quantity = it.quantity - 1) }
    } else {
        equipmentStacks.remove(itemId)
    }
}

/** 赏赐装备铸造袋条目：扣仓库数量后袋条目自带 stackedData */

@Suppress("UnusedParameter")
internal fun MutableGameState.grantEquipmentToBag(
    discipleId: String,
    item: RewardSelectedItem,
    stack: EquipmentStack,
    id: Int
) {
    discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
        discipleTables.storageBagItems[id],
        StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_EQUIPMENT_STACK,
            name = stack.name, rarity = stack.rarity, quantity = 1,
            obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
            forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
            forgetPhase = gameData.gamePhase,
            stackedData = BagStackedData(minRealm = stack.minRealm, slot = stack.slot.name))
    )
}

internal fun DiscipleFacadeImpl.rewardManual(discipleId: String, item: RewardSelectedItem) {
    var wrote = false
    // 捕获豁免（updateMirror，§2.81）：赏赐写 9 类实体集合（manualStacks/
    // manualInstances——已关闭回导）；无 native 臂，Kotlin 即 AUTHORITATIVE 正主，
    // 写入经尾部基线重建回导 C++（rewardEquipment 同口径）
    stateStore.updateMirror {
        val stack = manualStacks.get(item.id)
        if (stack == null || stack.quantity < 1) return@updateMirror
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) return@updateMirror
        wrote = true
        val discipleRealm = discipleTables.realms[id]
        val currentManualIds = discipleTables.manualIds[id]
        val canLearn = GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm) &&
            currentManualIds.size < DiscipleStatCalculator.getMaxManualSlots(discipleTables.assemble(id)) &&
            !(stack.type == ManualType.MIND && currentManualIds.any { manualInstances.get(it)?.type == ManualType
                .MIND }) &&
            !currentManualIds.any { manualInstances.get(it)?.name == stack.name }
        if (canLearn) {
            if (stack.quantity <= 1) manualStacks.remove(item.id)
            else manualStacks.update(item.id) { it.copy(quantity = stack.quantity - 1) }
            val instanceId = java.util.UUID.randomUUID().toString()
            manualInstances.add(stack.toInstance(id = instanceId, ownerId = discipleId, isLearned = true))
            discipleTables.manualIds[id] = currentManualIds + instanceId
        } else {
            if (stack.quantity <= 1) manualStacks.remove(item.id)
            else manualStacks.update(item.id) { it.copy(quantity = stack.quantity - 1) }
            // 赏赐功法铸造袋条目（容量无上限，永不失败）——扣仓库数量后
            // 袋条目自带 stackedData（minRealm/manualType 供取回重建）
            discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
                discipleTables.storageBagItems[id],
                StorageBagItem(itemId = item.id, itemType = ITEM_TYPE_MANUAL_STACK,
                    name = stack.name, rarity = stack.rarity, quantity = 1,
                    obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
                    forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
                    forgetPhase = gameData.gamePhase,
                    stackedData = BagStackedData(minRealm = stack.minRealm, manualType = stack.type.name))
            )
        }
    }
    // 条件性重建：未发生写入（堆叠缺失/弟子无效）时零成本
    if (wrote) gameEngineCore.rebaselineNativeMirror("仓库赏赐功法")
}


/** 修炼值丹药效果 */
internal fun MutableGameState.applyCultivationAddEffect(id: Int, effect: PillEffect) {
    discipleTables.cultivations[id] = discipleTables.cultivations[id] + effect.cultivationAdd
}

/** 功法经验丹药效果 */

internal fun MutableGameState.applySkillExpEffect(id: Int, effect: PillEffect) {
    discipleTables.manualMasteries[id] = discipleTables.manualMasteries[id].mapValues { (_, v) ->
        (v + effect.skillExpAdd).coerceAtMost(10000)
    }
}


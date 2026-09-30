package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.rebaselineNativeMirror
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.StorageBagUtils
import com.xianxia.sect.core.model.BagStackedData

// ── DiscipleFacadeImpl 拆分域 1/2（行为零变更） ──

internal fun DiscipleFacadeImpl.rewardEquipment(discipleId: String, item: RewardSelectedItem) {
    var wrote = false
    // 捕获豁免（updateMirror，§2.81）：赏赐写 equipmentInstances 实例集合
    //（B3 装备无堆叠轨——仓库赏赐直接实例轨，可穿即穿、不可穿入袋）；本链无
    // native 臂（C++ rewardItemTx 仅支持消耗品四类），Kotlin 即 AUTHORITATIVE
    // 正主——写入经尾部基线重建回导 C++（§2.79 宗门改名迁移同口径）
    stateStore.updateMirror {
        val instance = equipmentInstances.get(item.id)
        if (instance == null || instance.isEquipped) return@updateMirror
        val id = discipleId.toIntOrNull()
        if (id == null || !discipleTables.ids.contains(id)) return@updateMirror
        wrote = true
        val discipleRealm = discipleTables.realms[id]
        val canEquip = GameConfig.Realm.meetsRealmRequirement(discipleRealm, instance.minRealm)
        if (canEquip) {
            val slot = instance.part
            val oldEquipId = equippedItemIdOf(id = id, slot = slot)
            if (oldEquipId.isNotEmpty()) {
                // 卸下的装备实例完整入袋（等级/词条随实例保真，容量无上限）
                depositOldEquipmentToBag(id = id, oldEquipId = oldEquipId)
            }
            equipmentInstances.update(instance.id) { it.copy(isEquipped = true, ownerId = discipleId) }
            setEquipmentSlot(id = id, slot = slot, instanceId = instance.id)
        } else {
            // 境界不足：实例入袋（R5 保真），不入槽
            grantEquipmentToBag(instance = instance, id = id)
            equipmentInstances.update(instance.id) { it.copy(isEquipped = false, ownerId = null) }
        }
    }
    // 条件性重建：未发生写入（实例缺失/弟子无效）时零成本
    if (wrote) gameEngineCore.rebaselineNativeMirror("仓库赏赐装备")
}

/** 当前装备 ID 读取 */

internal fun MutableGameState.equippedItemIdOf(id: Int, slot: EquipmentSlot): String = when (slot) {
    EquipmentSlot.HEAD -> discipleTables.headIds[id]
    EquipmentSlot.BODY -> discipleTables.bodyIds[id]
    EquipmentSlot.HANDS -> discipleTables.handsIds[id]
    EquipmentSlot.FEET -> discipleTables.feetIds[id]
    EquipmentSlot.WEAPON -> discipleTables.weaponIds[id]
    EquipmentSlot.LEGS -> discipleTables.legsIds[id]
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
        // B3：实例保留在实例表（下线态 isEquipped=false），等级/词条随实例单点
        equipmentInstances.update(oldEquipId) { it.copy(isEquipped = false, ownerId = null) }
    }
}

/** 装备槽位清空 */

internal fun MutableGameState.clearEquipmentSlot(id: Int, slot: EquipmentSlot) {
    when (slot) {
        EquipmentSlot.HEAD -> discipleTables.headIds[id] = ""
        EquipmentSlot.BODY -> discipleTables.bodyIds[id] = ""
        EquipmentSlot.HANDS -> discipleTables.handsIds[id] = ""
        EquipmentSlot.FEET -> discipleTables.feetIds[id] = ""
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = ""
        EquipmentSlot.LEGS -> discipleTables.legsIds[id] = ""
    }
}

/** 装备槽位写入 */

internal fun MutableGameState.setEquipmentSlot(id: Int, slot: EquipmentSlot, instanceId: String) {
    when (slot) {
        EquipmentSlot.HEAD -> discipleTables.headIds[id] = instanceId
        EquipmentSlot.BODY -> discipleTables.bodyIds[id] = instanceId
        EquipmentSlot.HANDS -> discipleTables.handsIds[id] = instanceId
        EquipmentSlot.FEET -> discipleTables.feetIds[id] = instanceId
        EquipmentSlot.WEAPON -> discipleTables.weaponIds[id] = instanceId
        EquipmentSlot.LEGS -> discipleTables.legsIds[id] = instanceId
    }
}

/** 赏赐装备入袋：完整实例条目（等级/词条随实例保真；B3 无堆叠语义） */

internal fun MutableGameState.grantEquipmentToBag(
    instance: EquipmentInstance,
    id: Int
) {
    discipleTables.storageBagItems[id] = StorageBagUtils.increaseItemQuantity(
        discipleTables.storageBagItems[id],
        StorageBagItem(itemId = instance.id, itemType = ITEM_TYPE_EQUIPMENT_INSTANCE,
            name = instance.name, rarity = instance.rarity, quantity = 1,
            obtainedYear = gameData.gameYear, obtainedMonth = gameData.gameMonth,
            forgetYear = gameData.gameYear, forgetMonth = gameData.gameMonth,
            forgetPhase = gameData.gamePhase,
            equipmentInstance = instance)
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


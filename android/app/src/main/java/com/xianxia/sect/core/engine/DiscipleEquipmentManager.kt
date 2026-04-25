package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.StorageBagUtils

object DiscipleEquipmentManager {

    private const val MAX_EQUIPMENT_STACK = 999

    data class EquipmentProcessResult(
        val disciple: Disciple,
        val newInstances: List<EquipmentInstance>,
        val replacedInstances: List<EquipmentInstance>,
        val stackUpdates: List<StackUpdate>,
        val replacedEquipmentStacks: List<EquipmentStack>,
        val events: List<String>
    )

    private data class SlotConfig(
        val slotType: EquipmentSlot,
        val currentEquipIdGetter: (Disciple) -> String?,
        val equipSetter: (Disciple, String) -> Disciple
    )

    private val slotConfigs = listOf(
        SlotConfig(
            slotType = EquipmentSlot.WEAPON,
            currentEquipIdGetter = { it.equipment.weaponId },
            equipSetter = { d, id -> d.copyWith(weaponId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ARMOR,
            currentEquipIdGetter = { it.equipment.armorId },
            equipSetter = { d, id -> d.copyWith(armorId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.BOOTS,
            currentEquipIdGetter = { it.equipment.bootsId },
            equipSetter = { d, id -> d.copyWith(bootsId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ACCESSORY,
            currentEquipIdGetter = { it.equipment.accessoryId },
            equipSetter = { d, id -> d.copyWith(accessoryId = id) }
        )
    )

    fun processAutoEquip(
        disciple: Disciple,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        gameYear: Int,
        gameMonth: Int,
        gameDay: Int,
        maxStack: Int = MAX_EQUIPMENT_STACK,
        instantMessage: Boolean = false
    ): EquipmentProcessResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        val allNewInstances = mutableListOf<EquipmentInstance>()
        val allReplacedInstances = mutableListOf<EquipmentInstance>()
        val allStackUpdates = mutableListOf<StackUpdate>()
        val allReplacedStacks = mutableListOf<EquipmentStack>()

        val bagStackRefs = updatedDisciple.equipment.storageBagItems
            .filter { it.itemType == "equipment_stack" && !StorageBagUtils.isInCoolingPeriod(it, gameYear, gameMonth, gameDay) }

        if (bagStackRefs.isEmpty()) {
            return EquipmentProcessResult(disciple, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        slotConfigs.forEach { config ->
            val result = processSlot(
                disciple = updatedDisciple,
                config = config,
                equipmentStacks = equipmentStacks,
                equipmentInstances = equipmentInstances,
                bagStackRefs = bagStackRefs,
                gameYear = gameYear,
                gameMonth = gameMonth,
                gameDay = gameDay,
                maxStack = maxStack,
                instantMessage = instantMessage
            )

            if (result.newInstances.isNotEmpty()) {
                updatedDisciple = result.disciple
                allNewInstances.addAll(result.newInstances)
                allReplacedInstances.addAll(result.replacedInstances)
                allStackUpdates.addAll(result.stackUpdates)
                allReplacedStacks.addAll(result.replacedEquipmentStacks)
                events.addAll(result.events)
            }
        }

        return EquipmentProcessResult(updatedDisciple, allNewInstances, allReplacedInstances, allStackUpdates, allReplacedStacks, events)
    }

    private fun processSlot(
        disciple: Disciple,
        config: SlotConfig,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        bagStackRefs: List<StorageBagItem>,
        gameYear: Int,
        gameMonth: Int,
        gameDay: Int,
        maxStack: Int,
        instantMessage: Boolean
    ): EquipmentProcessResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple

        val slotStacks = bagStackRefs.mapNotNull { ref ->
            equipmentStacks.find { it.id == ref.itemId && it.slot == config.slotType }
        }.filter { disciple.realm <= it.minRealm }

        if (slotStacks.isEmpty()) {
            return EquipmentProcessResult(disciple, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val currentEquipId = config.currentEquipIdGetter(disciple)
        val currentInstance = currentEquipId?.let { equipmentInstances[it] }
        val currentRarity = currentInstance?.rarity ?: 0

        val bestStack = slotStacks
            .filter { it.rarity > currentRarity }
            .maxByOrNull { it.rarity }

        if (bestStack == null) {
            return EquipmentProcessResult(disciple, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val replacedInstances = mutableListOf<EquipmentInstance>()
        val replacedStacks = mutableListOf<EquipmentStack>()

        currentInstance?.let { oldInstance ->
            replacedInstances.add(oldInstance)

            val oldStack = oldInstance.toStack(quantity = 1)

            val bagStackIds = updatedDisciple.equipment.storageBagItems
                .filter { it.itemType == "equipment_stack" }
                .map { it.itemId }
                .toSet()

            val existingBagStack = equipmentStacks.find {
                it.name == oldStack.name && it.rarity == oldStack.rarity && it.slot == oldStack.slot && it.id in bagStackIds
            }

            val storageItemId: String
            val isMerge: Boolean
            if (existingBagStack != null) {
                val mergedQty = (existingBagStack.quantity + 1).coerceAtMost(maxStack)
                replacedStacks.add(existingBagStack.copy(quantity = mergedQty))
                storageItemId = existingBagStack.id
                isMerge = true
            } else {
                replacedStacks.add(oldStack)
                storageItemId = oldStack.id
                isMerge = false
            }

            val storageItem = StorageBagItem(
                itemId = storageItemId,
                itemType = "equipment_stack",
                name = oldInstance.name,
                rarity = oldInstance.rarity,
                quantity = 1,
                obtainedYear = gameYear,
                obtainedMonth = gameMonth,
                forgetYear = gameYear,
                forgetMonth = gameMonth,
                forgetDay = gameDay
            )
            val increasedBagItems = StorageBagUtils.increaseItemQuantity(updatedDisciple.equipment.storageBagItems, storageItem, maxStack)
            updatedDisciple = updatedDisciple.copyWith(
                storageBagItems = if (isMerge) {
                    increasedBagItems.map { bagItem ->
                        if (bagItem.itemId == storageItemId && bagItem.itemType == "equipment_stack") {
                            bagItem.copy(forgetYear = gameYear, forgetMonth = gameMonth, forgetDay = gameDay)
                        } else bagItem
                    }
                } else {
                    increasedBagItems
                }
            )
        }

        val instanceId = java.util.UUID.randomUUID().toString()
        val newInstance = bestStack.toInstance(id = instanceId, ownerId = disciple.id, isEquipped = true)

        val newQty = bestStack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = bestStack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = bestStack.id, newQuantity = newQty, isDeletion = false)
        }

        updatedDisciple = config.equipSetter(updatedDisciple, instanceId)

        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = StorageBagUtils.decreaseItemQuantity(updatedDisciple.equipment.storageBagItems, bestStack.id)
        )

        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}装备了 ${bestStack.name}")

        return EquipmentProcessResult(updatedDisciple, listOf(newInstance), replacedInstances, listOf(stackUpdate), replacedStacks, events)
    }

    fun canEquip(disciple: Disciple, stack: EquipmentStack): Boolean {
        return disciple.realm <= stack.minRealm
    }

    fun canEquip(disciple: Disciple, instance: EquipmentInstance): Boolean {
        return disciple.realm <= instance.minRealm
    }

    fun getEquipSlot(disciple: Disciple, slot: EquipmentSlot): String? {
        return when (slot) {
            EquipmentSlot.WEAPON -> disciple.equipment.weaponId
            EquipmentSlot.ARMOR -> disciple.equipment.armorId
            EquipmentSlot.BOOTS -> disciple.equipment.bootsId
            EquipmentSlot.ACCESSORY -> disciple.equipment.accessoryId
        }
    }
}

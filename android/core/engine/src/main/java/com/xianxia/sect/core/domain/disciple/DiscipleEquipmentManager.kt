package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.baseMagicAttack
import com.xianxia.sect.core.model.basePhysicalAttack
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.weaponId
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class DiscipleEquipmentManager @Inject constructor() {

    companion object {
        private const val MAX_EQUIPMENT_STACK = 999
    }

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
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(weaponId = id)) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ARMOR,
            currentEquipIdGetter = { it.equipment.armorId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(armorId = id)) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.BOOTS,
            currentEquipIdGetter = { it.equipment.bootsId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(bootsId = id)) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ACCESSORY,
            currentEquipIdGetter = { it.equipment.accessoryId },
            equipSetter = { d, id -> d.copy(equipment = d.equipment.copy(accessoryId = id)) }
        )
    )

    fun canEquip(disciple: Disciple, stack: EquipmentStack): Boolean {
        return disciple.realm <= stack.minRealm
    }

    fun processAutoEquipFromWarehouse(
        disciple: Disciple,
        warehouseStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        gameYear: Int,
        gameMonth: Int,
        gamePhase: Int,
        maxStack: Int = MAX_EQUIPMENT_STACK
    ): EquipmentProcessResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        val allNewInstances = mutableListOf<EquipmentInstance>()
        val allStackUpdates = mutableListOf<StackUpdate>()

        slotConfigs.forEach { config ->
            val currentEquipId = config.currentEquipIdGetter(updatedDisciple)
            if (!currentEquipId.isNullOrEmpty()) return@forEach

            val candidates = warehouseStacks.filter { stack ->
                stack.slot == config.slotType &&
                updatedDisciple.realm <= stack.minRealm &&
                !stack.isLocked
            }

            val prefersPhysical = disciple.basePhysicalAttack >= disciple.baseMagicAttack
            val bestStack = candidates.maxWithOrNull(
                compareBy<EquipmentStack> { stack ->
                    if (prefersPhysical && stack.physicalAttack > 0) 1
                    else if (!prefersPhysical && stack.magicAttack > 0) 1
                    else 0
                }.thenBy { it.rarity }
            ) ?: return@forEach

            val instanceId = java.util.UUID.randomUUID().toString()
            val newInstance = bestStack.toInstance(id = instanceId, ownerId = disciple.id, isEquipped = true)

            val newQty = bestStack.quantity - 1
            val stackUpdate = if (newQty <= 0) {
                StackUpdate(stackId = bestStack.id, newQuantity = 0, isDeletion = true)
            } else {
                StackUpdate(stackId = bestStack.id, newQuantity = newQty, isDeletion = false)
            }

            updatedDisciple = config.equipSetter(updatedDisciple, instanceId)
            allNewInstances.add(newInstance)
            allStackUpdates.add(stackUpdate)
            events.add("${disciple.name} 自动装备了 ${bestStack.name}")
        }

        return EquipmentProcessResult(
            disciple = updatedDisciple,
            newInstances = allNewInstances,
            replacedInstances = emptyList(),
            stackUpdates = allStackUpdates,
            replacedEquipmentStacks = emptyList(),
            events = events
        )
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

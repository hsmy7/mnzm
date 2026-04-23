package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class AddResult {
    SUCCESS,
    PARTIAL_SUCCESS,
    FULL,
    INVALID_ID,
    INVALID_NAME,
    INVALID_RARITY,
    INVALID_QUANTITY,
    DUPLICATE_ID,
    ITEM_LOCKED
}

data class CapacityInfo(
    val currentSlots: Int,
    val maxSlots: Int,
    val remainingSlots: Int,
    val isFull: Boolean
)

data class StackUpdate(
    val stackId: String,
    val newQuantity: Int,
    val isDeletion: Boolean = false
)

@SystemPriority(order = 50)
@Singleton
class InventorySystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val inventoryConfig: InventoryConfig
) : GameSystem, ItemAdder {

    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        const val MAX_INVENTORY_SIZE = 2000
        private val VALID_RARITY_RANGE = 1..6
    }

    private val scope get() = applicationScopeProvider.scope

    val equipmentStacks: StateFlow<List<EquipmentStack>> get() = stateStore.equipmentStacks
    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = stateStore.equipmentInstances
    val manualStacks: StateFlow<List<ManualStack>> get() = stateStore.manualStacks
    val manualInstances: StateFlow<List<ManualInstance>> get() = stateStore.manualInstances
    val pills: StateFlow<List<Pill>> get() = stateStore.pills
    val materials: StateFlow<List<Material>> get() = stateStore.materials
    val herbs: StateFlow<List<Herb>> get() = stateStore.herbs
    val seeds: StateFlow<List<Seed>> get() = stateStore.seeds

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        Log.d(TAG, "InventorySystem initialized")
    }

    override fun release() {
        Log.d(TAG, "InventorySystem released")
    }

    override suspend fun clear() {
        stateStore.update {
            equipmentStacks = emptyList()
            equipmentInstances = emptyList()
            manualStacks = emptyList()
            manualInstances = emptyList()
            pills = emptyList()
            materials = emptyList()
            herbs = emptyList()
            seeds = emptyList()
        }
    }

    fun loadInventory(
        equipmentStacksList: List<EquipmentStack>,
        equipmentInstancesList: List<EquipmentInstance>,
        manualStacksList: List<ManualStack>,
        manualInstancesList: List<ManualInstance>,
        pillsList: List<Pill>,
        materialsList: List<Material>,
        herbsList: List<Herb>,
        seedsList: List<Seed>
    ) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks = equipmentStacksList
            ts.equipmentInstances = equipmentInstancesList
            ts.manualStacks = manualStacksList
            ts.manualInstances = manualInstancesList
            ts.pills = pillsList
            ts.materials = materialsList
            ts.herbs = herbsList
            ts.seeds = seedsList
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacksList
                equipmentInstances = equipmentInstancesList
                manualStacks = manualStacksList
                manualInstances = manualInstancesList
                pills = pillsList
                materials = materialsList
                herbs = herbsList
                seeds = seedsList
            } }
        }
    }

    private fun validateQuantity(quantity: Int, name: String = "quantity"): Boolean {
        if (quantity <= 0) {
            Log.w(TAG, "Invalid $name: $quantity, must be positive")
            return false
        }
        return true
    }

    private fun logWarning(msg: String) = Log.w(TAG, msg)

    private fun getMaxStackForType(type: String): Int = inventoryConfig.getMaxStackSize(type)

    private fun getTotalSlotCount(): Int {
        return stateStore.equipmentStacks.value.size +
               stateStore.manualStacks.value.size +
               stateStore.pills.value.size +
               stateStore.materials.value.size +
               stateStore.herbs.value.size +
               stateStore.seeds.value.size
    }

    fun getCapacityInfo(): CapacityInfo {
        val current = getTotalSlotCount()
        return CapacityInfo(
            currentSlots = current,
            maxSlots = MAX_INVENTORY_SIZE,
            remainingSlots = MAX_INVENTORY_SIZE - current,
            isFull = current >= MAX_INVENTORY_SIZE
        )
    }

    fun canAddItem(): Boolean = getTotalSlotCount() < MAX_INVENTORY_SIZE

    fun canAddItems(count: Int): Boolean = getTotalSlotCount() + count <= MAX_INVENTORY_SIZE

    fun canAddEquipment(name: String, rarity: Int, slot: EquipmentSlot): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.equipmentStacks ?: stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.slot == slot && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddPill(name: String, rarity: Int, category: PillCategory): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.pills ?: stateStore.pills.value
        val maxStack = getMaxStackForType("pill")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddManual(name: String, rarity: Int, type: ManualType): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.manualStacks ?: stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.type == type && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddMaterial(name: String, rarity: Int, category: MaterialCategory): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.materials ?: stateStore.materials.value
        val maxStack = getMaxStackForType("material")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddHerb(name: String, rarity: Int, category: String): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.herbs ?: stateStore.herbs.value
        val maxStack = getMaxStackForType("herb")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    fun canAddSeed(name: String, rarity: Int, growTime: Int): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.seeds ?: stateStore.seeds.value
        val maxStack = getMaxStackForType("seed")
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.growTime == growTime && it.quantity < maxStack }
        return canMerge || canAddItem()
    }

    private fun validateStackableItem(name: String, rarity: Int, quantity: Int): AddResult {
        if (name.isBlank()) return AddResult.INVALID_NAME
        if (rarity !in VALID_RARITY_RANGE) return AddResult.INVALID_RARITY
        if (quantity <= 0) return AddResult.INVALID_QUANTITY
        return AddResult.SUCCESS
    }

    override fun addEquipmentStack(item: EquipmentStack): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentStacks = ts?.equipmentStacks ?: stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")

        val existing = currentStacks.find {
            it.name == item.name && it.rarity == item.rarity && it.slot == item.slot
        }
        if (existing != null) {
            val totalQty = existing.quantity + item.quantity
            val newQty = totalQty.coerceAtMost(maxStack)
            if (ts != null) {
                ts.equipmentStacks = ts.equipmentStacks.map {
                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                }
            } else {
                scope.launch { stateStore.update {
                    equipmentStacks = equipmentStacks.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } }
            }
            return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks + item
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks + item
            } }
        }
        return AddResult.SUCCESS
    }

    override fun addEquipmentInstance(item: EquipmentInstance): AddResult {
        if (item.id.isBlank()) return AddResult.INVALID_ID
        if (item.name.isBlank()) return AddResult.INVALID_NAME
        if (item.rarity !in VALID_RARITY_RANGE) return AddResult.INVALID_RARITY

        val ts = stateStore.currentTransactionMutableState()
        val currentInstances = ts?.equipmentInstances ?: stateStore.equipmentInstances.value
        if (currentInstances.any { it.id == item.id }) return AddResult.DUPLICATE_ID

        if (ts != null) {
            ts.equipmentInstances = ts.equipmentInstances + item
        } else {
            scope.launch { stateStore.update {
                equipmentInstances = equipmentInstances + item
            } }
        }
        return AddResult.SUCCESS
    }

    override fun addManualStack(item: ManualStack, merge: Boolean): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentStacks = ts?.manualStacks ?: stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")

        if (merge) {
            val existing = currentStacks.find {
                it.name == item.name && it.rarity == item.rarity && it.type == item.type
            }
            if (existing != null) {
                val totalQty = existing.quantity + item.quantity
                val newQty = totalQty.coerceAtMost(maxStack)
                if (ts != null) {
                    ts.manualStacks = ts.manualStacks.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } else {
                    scope.launch { stateStore.update {
                        manualStacks = manualStacks.map {
                            if (it.id == existing.id) it.copy(quantity = newQty) else it
                        }
                    } }
                }
                return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
            }
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.manualStacks = ts.manualStacks + item
        } else {
            scope.launch { stateStore.update {
                manualStacks = manualStacks + item
            } }
        }
        return AddResult.SUCCESS
    }

    override fun addManualInstance(item: ManualInstance): AddResult {
        if (item.id.isBlank()) return AddResult.INVALID_ID
        if (item.name.isBlank()) return AddResult.INVALID_NAME
        if (item.rarity !in VALID_RARITY_RANGE) return AddResult.INVALID_RARITY

        val ts = stateStore.currentTransactionMutableState()
        val currentInstances = ts?.manualInstances ?: stateStore.manualInstances.value
        if (currentInstances.any { it.id == item.id }) return AddResult.DUPLICATE_ID

        if (ts != null) {
            ts.manualInstances = ts.manualInstances + item
        } else {
            scope.launch { stateStore.update {
                manualInstances = manualInstances + item
            } }
        }
        return AddResult.SUCCESS
    }

    fun returnEquipmentToStack(instance: EquipmentInstance): AddResult {
        val ts = stateStore.currentTransactionMutableState()
        val currentStacks = ts?.equipmentStacks ?: stateStore.equipmentStacks.value
        val maxStack = getMaxStackForType("equipment_stack")

        val existing = currentStacks.find {
            it.name == instance.name && it.rarity == instance.rarity && it.slot == instance.slot
        }
        if (existing != null) {
            val totalQty = existing.quantity + 1
            val newQty = totalQty.coerceAtMost(maxStack)
            if (ts != null) {
                ts.equipmentStacks = ts.equipmentStacks.map {
                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                }
            } else {
                scope.launch { stateStore.update {
                    equipmentStacks = equipmentStacks.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } }
            }
            return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
        }
        if (!canAddItem()) return AddResult.FULL
        val newStack = instance.toStack(quantity = 1)
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks + newStack
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks + newStack
            } }
        }
        return AddResult.SUCCESS
    }

    fun returnManualToStack(instance: ManualInstance): AddResult {
        val ts = stateStore.currentTransactionMutableState()
        val currentStacks = ts?.manualStacks ?: stateStore.manualStacks.value
        val maxStack = getMaxStackForType("manual_stack")

        val existing = currentStacks.find {
            it.name == instance.name && it.rarity == instance.rarity && it.type == instance.type
        }
        if (existing != null) {
            val totalQty = existing.quantity + 1
            val newQty = totalQty.coerceAtMost(maxStack)
            if (ts != null) {
                ts.manualStacks = ts.manualStacks.map {
                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                }
            } else {
                scope.launch { stateStore.update {
                    manualStacks = manualStacks.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } }
            }
            return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
        }
        if (!canAddItem()) return AddResult.FULL
        val newStack = instance.toStack(quantity = 1)
        if (ts != null) {
            ts.manualStacks = ts.manualStacks + newStack
        } else {
            scope.launch { stateStore.update {
                manualStacks = manualStacks + newStack
            } }
        }
        return AddResult.SUCCESS
    }

    fun removeEquipment(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.equipmentStacks.value.find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked equipment stack: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks.mapNotNull { stack ->
                if (stack.id == id && !removed) {
                    val newQty = stack.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${stack.quantity} available")
                            stack
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            stack.copy(quantity = newQty)
                        }
                    }
                } else stack
            }
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks.mapNotNull { stack ->
                    if (stack.id == id && !removed) {
                        val newQty = stack.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${stack.quantity} available")
                                stack
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                stack.copy(quantity = newQty)
                            }
                        }
                    } else stack
                }
            } }
        }
        return removed
    }

    fun removeEquipmentInstance(id: String): Boolean {
        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val oldSize = ts.equipmentInstances.size
            ts.equipmentInstances = ts.equipmentInstances.filter { it.id != id }
            removed = ts.equipmentInstances.size < oldSize
        } else {
            scope.launch { stateStore.update {
                val oldSize = equipmentInstances.size
                equipmentInstances = equipmentInstances.filter { it.id != id }
                removed = equipmentInstances.size < oldSize
            } }
        }
        return removed
    }

    fun updateEquipmentStack(id: String, transform: (EquipmentStack) -> EquipmentStack): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun updateEquipmentInstance(id: String, transform: (EquipmentInstance) -> EquipmentInstance): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentInstances = ts.equipmentInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                equipmentInstances = equipmentInstances.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getEquipmentStackById(id: String): EquipmentStack? {
        return stateStore.equipmentStacks.value.find { it.id == id }
    }

    fun getEquipmentInstanceById(id: String): EquipmentInstance? {
        return stateStore.equipmentInstances.value.find { it.id == id }
    }

    fun removeManual(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.manualStacks.value.find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked manual stack: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manualStacks = ts.manualStacks.mapNotNull { stack ->
                if (stack.id == id && !removed) {
                    val newQty = stack.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${stack.quantity} available")
                            stack
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            stack.copy(quantity = newQty)
                        }
                    }
                } else stack
            }
        } else {
            scope.launch { stateStore.update {
                manualStacks = manualStacks.mapNotNull { stack ->
                    if (stack.id == id && !removed) {
                        val newQty = stack.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${stack.quantity} available")
                                stack
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                stack.copy(quantity = newQty)
                            }
                        }
                    } else stack
                }
            } }
        }
        return removed
    }

    fun removeManualInstance(id: String): Boolean {
        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val oldSize = ts.manualInstances.size
            ts.manualInstances = ts.manualInstances.filter { it.id != id }
            removed = ts.manualInstances.size < oldSize
        } else {
            scope.launch { stateStore.update {
                val oldSize = manualInstances.size
                manualInstances = manualInstances.filter { it.id != id }
                removed = manualInstances.size < oldSize
            } }
        }
        return removed
    }

    fun updateManualStack(id: String, transform: (ManualStack) -> ManualStack): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manualStacks = ts.manualStacks.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                manualStacks = manualStacks.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun updateManualInstance(id: String, transform: (ManualInstance) -> ManualInstance): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manualInstances = ts.manualInstances.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                manualInstances = manualInstances.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getManualStackById(id: String): ManualStack? {
        return stateStore.manualStacks.value.find { it.id == id }
    }

    fun getManualInstanceById(id: String): ManualInstance? {
        return stateStore.manualInstances.value.find { it.id == id }
    }

    fun addPill(item: Pill, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentPills = ts?.pills ?: stateStore.pills.value

        if (merge) {
            val existing = currentPills.find {
                it.name == item.name && it.rarity == item.rarity && it.category == item.category && it.grade == item.grade
            }
            if (existing != null) {
                val maxStack = getMaxStackForType("pill")
                val totalQty = existing.quantity + item.quantity
                val newQty = totalQty.coerceAtMost(maxStack)
                if (ts != null) {
                    ts.pills = ts.pills.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } else {
                    scope.launch { stateStore.update {
                        pills = pills.map {
                            if (it.id == existing.id) it.copy(quantity = newQty) else it
                        }
                    } }
                }
                return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
            }
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.pills = ts.pills + item
        } else {
            scope.launch { stateStore.update {
                pills = pills + item
            } }
        }
        return AddResult.SUCCESS
    }

    fun removePill(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.pills.value.find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked pill: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.pills = ts.pills.mapNotNull { pill ->
                if (pill.id == id && !removed) {
                    val newQty = pill.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                            pill
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            pill.copy(quantity = newQty)
                        }
                    }
                } else pill
            }
        } else {
            scope.launch { stateStore.update {
                pills = pills.mapNotNull { pill ->
                    if (pill.id == id && !removed) {
                        val newQty = pill.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                                pill
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                pill.copy(quantity = newQty)
                            }
                        }
                    } else pill
                }
            } }
        }
        return removed
    }

    fun removePillByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.pills.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked pill: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.pills = ts.pills.mapNotNull { pill ->
                if (pill.id == existing.id && !removed) {
                    val newQty = pill.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                            pill
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            pill.copy(quantity = newQty)
                        }
                    }
                } else pill
            }
        } else {
            scope.launch { stateStore.update {
                pills = pills.mapNotNull { pill ->
                    if (pill.id == existing.id && !removed) {
                        val newQty = pill.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${pill.quantity} available")
                                pill
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                pill.copy(quantity = newQty)
                            }
                        }
                    } else pill
                }
            } }
        }
        return removed
    }

    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.pills = ts.pills.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                pills = pills.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getPillById(id: String): Pill? {
        return stateStore.pills.value.find { it.id == id }
    }

    fun getPillQuantity(id: String): Int {
        return stateStore.pills.value.find { it.id == id }?.quantity ?: 0
    }

    fun hasPill(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = stateStore.pills.value.find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addMaterial(item: Material, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentMaterials = ts?.materials ?: stateStore.materials.value

        if (merge) {
            val existing = currentMaterials.find {
                it.name == item.name && it.rarity == item.rarity && it.category == item.category
            }
            if (existing != null) {
                val maxStack = getMaxStackForType("material")
                val totalQty = existing.quantity + item.quantity
                val newQty = totalQty.coerceAtMost(maxStack)
                if (ts != null) {
                    ts.materials = ts.materials.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } else {
                    scope.launch { stateStore.update {
                        materials = materials.map {
                            if (it.id == existing.id) it.copy(quantity = newQty) else it
                        }
                    } }
                }
                return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
            }
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.materials = ts.materials + item
        } else {
            scope.launch { stateStore.update {
                materials = materials + item
            } }
        }
        return AddResult.SUCCESS
    }

    fun removeMaterial(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.materials.value.find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked material: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.materials = ts.materials.mapNotNull { material ->
                if (material.id == id && !removed) {
                    val newQty = material.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                            material
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            material.copy(quantity = newQty)
                        }
                    }
                } else material
            }
        } else {
            scope.launch { stateStore.update {
                materials = materials.mapNotNull { material ->
                    if (material.id == id && !removed) {
                        val newQty = material.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                                material
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                material.copy(quantity = newQty)
                            }
                        }
                    } else material
                }
            } }
        }
        return removed
    }

    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.materials.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked material: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.materials = ts.materials.mapNotNull { material ->
                if (material.id == existing.id && !removed) {
                    val newQty = material.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                            material
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            material.copy(quantity = newQty)
                        }
                    }
                } else material
            }
        } else {
            scope.launch { stateStore.update {
                materials = materials.mapNotNull { material ->
                    if (material.id == existing.id && !removed) {
                        val newQty = material.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${material.quantity} available")
                                material
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                material.copy(quantity = newQty)
                            }
                        }
                    } else material
                }
            } }
        }
        return removed
    }

    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.materials = ts.materials.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                materials = materials.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getMaterialById(id: String): Material? {
        return stateStore.materials.value.find { it.id == id }
    }

    fun getMaterialQuantity(id: String): Int {
        return stateStore.materials.value.find { it.id == id }?.quantity ?: 0
    }

    fun hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = stateStore.materials.value.find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addHerb(item: Herb, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentHerbs = ts?.herbs ?: stateStore.herbs.value

        if (merge) {
            val existing = currentHerbs.find {
                it.name == item.name && it.rarity == item.rarity && it.category == item.category
            }
            if (existing != null) {
                val maxStack = getMaxStackForType("herb")
                val totalQty = existing.quantity + item.quantity
                val newQty = totalQty.coerceAtMost(maxStack)
                if (ts != null) {
                    ts.herbs = ts.herbs.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } else {
                    scope.launch { stateStore.update {
                        herbs = herbs.map {
                            if (it.id == existing.id) it.copy(quantity = newQty) else it
                        }
                    } }
                }
                return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
            }
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.herbs = ts.herbs + item
        } else {
            scope.launch { stateStore.update {
                herbs = herbs + item
            } }
        }
        return AddResult.SUCCESS
    }

    fun removeHerb(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.herbs.value.find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked herb: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.herbs = ts.herbs.mapNotNull { herb ->
                if (herb.id == id && !removed) {
                    val newQty = herb.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                            herb
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            herb.copy(quantity = newQty)
                        }
                    }
                } else herb
            }
        } else {
            scope.launch { stateStore.update {
                herbs = herbs.mapNotNull { herb ->
                    if (herb.id == id && !removed) {
                        val newQty = herb.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                                herb
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                herb.copy(quantity = newQty)
                            }
                        }
                    } else herb
                }
            } }
        }
        return removed
    }

    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.herbs.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked herb: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.herbs = ts.herbs.mapNotNull { herb ->
                if (herb.id == existing.id && !removed) {
                    val newQty = herb.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                            herb
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            herb.copy(quantity = newQty)
                        }
                    }
                } else herb
            }
        } else {
            scope.launch { stateStore.update {
                herbs = herbs.mapNotNull { herb ->
                    if (herb.id == existing.id && !removed) {
                        val newQty = herb.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${herb.quantity} available")
                                herb
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                herb.copy(quantity = newQty)
                            }
                        }
                    } else herb
                }
            } }
        }
        return removed
    }

    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.herbs = ts.herbs.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                herbs = herbs.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getHerbById(id: String): Herb? {
        return stateStore.herbs.value.find { it.id == id }
    }

    fun getHerbQuantity(id: String): Int {
        return stateStore.herbs.value.find { it.id == id }?.quantity ?: 0
    }

    fun hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = stateStore.herbs.value.find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun addSeed(item: Seed, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentSeeds = ts?.seeds ?: stateStore.seeds.value

        if (merge) {
            val existing = currentSeeds.find {
                it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
            }
            if (existing != null) {
                val maxStack = getMaxStackForType("seed")
                val totalQty = existing.quantity + item.quantity
                val newQty = totalQty.coerceAtMost(maxStack)
                if (ts != null) {
                    ts.seeds = ts.seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } else {
                    scope.launch { stateStore.update {
                        seeds = seeds.map {
                            if (it.id == existing.id) it.copy(quantity = newQty) else it
                        }
                    } }
                }
                return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
            }
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.seeds = ts.seeds + item
        } else {
            scope.launch { stateStore.update {
                seeds = seeds + item
            } }
        }
        return AddResult.SUCCESS
    }

    suspend fun addSeedSync(item: Seed, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val currentSeeds = ts.seeds
            if (merge) {
                val existing = currentSeeds.find {
                    it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
                }
                if (existing != null) {
                    val maxStack = getMaxStackForType("seed")
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    ts.seeds = ts.seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    return if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            ts.seeds = ts.seeds + item
            return AddResult.SUCCESS
        }

        var overflowResult: AddResult = AddResult.SUCCESS
        stateStore.update {
            if (merge) {
                val existing = seeds.find {
                    it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
                }
                if (existing != null) {
                    val maxStack = getMaxStackForType("seed")
                    val totalQty = existing.quantity + item.quantity
                    val newQty = totalQty.coerceAtMost(maxStack)
                    seeds = seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    overflowResult = if (totalQty > maxStack) AddResult.PARTIAL_SUCCESS else AddResult.SUCCESS
                    return@update
                }
            }
            if (seeds.size >= MAX_INVENTORY_SIZE) {
                overflowResult = AddResult.FULL
                return@update
            }
            seeds = seeds + item
        }
        return overflowResult
    }

    fun removeSeed(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.seeds.value.find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.seeds = ts.seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                            seed
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            seed.copy(quantity = newQty)
                        }
                    }
                } else seed
            }
        } else {
            scope.launch { stateStore.update {
                seeds = seeds.mapNotNull { seed ->
                    if (seed.id == id && !removed) {
                        val newQty = seed.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                                seed
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                seed.copy(quantity = newQty)
                            }
                        }
                    } else seed
                }
            } }
        }
        return removed
    }

    suspend fun removeSeedSync(id: String, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false

        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val existing = ts.seeds.find { it.id == id }
            if (!bypassLock && existing?.isLocked == true) {
                logWarning("Cannot remove locked seed: ${existing.name}")
                return false
            }
            var removed = false
            ts.seeds = ts.seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                            seed
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            seed.copy(quantity = newQty)
                        }
                    }
                } else seed
            }
            return removed
        }

        val existing = stateStore.getCurrentSeeds().find { it.id == id }
        if (!bypassLock && existing?.isLocked == true) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return false
        }
        if (existing == null) return false
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items, only ${existing.quantity} available")
            return false
        }

        var removed = false
        stateStore.update {
            seeds = seeds.mapNotNull { seed ->
                if (seed.id == id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
                            seed
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            seed.copy(quantity = newQty)
                        }
                    }
                } else seed
            }
        }
        return removed
    }

    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1, bypassLock: Boolean = false): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.seeds.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (!bypassLock && existing.isLocked) {
            logWarning("Cannot remove locked seed: ${existing.name}")
            return false
        }
        if (existing.quantity < quantity) {
            logWarning("Cannot remove $quantity items '$name', only ${existing.quantity} available")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.seeds = ts.seeds.mapNotNull { seed ->
                if (seed.id == existing.id && !removed) {
                    val newQty = seed.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                            seed
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            seed.copy(quantity = newQty)
                        }
                    }
                } else seed
            }
        } else {
            scope.launch { stateStore.update {
                seeds = seeds.mapNotNull { seed ->
                    if (seed.id == existing.id && !removed) {
                        val newQty = seed.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${seed.quantity} available")
                                seed
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                seed.copy(quantity = newQty)
                            }
                        }
                    } else seed
                }
            } }
        }
        return removed
    }

    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.seeds = ts.seeds.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                seeds = seeds.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getSeedById(id: String): Seed? {
        return stateStore.seeds.value.find { it.id == id }
    }

    fun getSeedQuantity(id: String): Int {
        return stateStore.seeds.value.find { it.id == id }?.quantity ?: 0
    }

    fun hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean {
        val item = stateStore.seeds.value.find { it.name == name && it.rarity == rarity } ?: return false
        return item.quantity >= quantity
    }

    fun getItemCountByType(type: String): Int {
        return when (type.lowercase(java.util.Locale.getDefault())) {
            "equipment_stack" -> stateStore.equipmentStacks.value.size
            "equipment_instance" -> stateStore.equipmentInstances.value.size
            "manual_stack" -> stateStore.manualStacks.value.size
            "manual_instance" -> stateStore.manualInstances.value.size
            "equipment" -> stateStore.equipmentStacks.value.size + stateStore.equipmentInstances.value.size
            "manual" -> stateStore.manualStacks.value.size + stateStore.manualInstances.value.size
            "pill" -> stateStore.pills.value.size
            "material" -> stateStore.materials.value.size
            "herb" -> stateStore.herbs.value.size
            "seed" -> stateStore.seeds.value.size
            else -> 0
        }
    }

    fun sortWarehouse() {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipmentStacks = ts.equipmentStacks.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name })
            ts.equipmentInstances = ts.equipmentInstances.sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name })
            ts.manualStacks = ts.manualStacks.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name })
            ts.manualInstances = ts.manualInstances.sortedWith(compareByDescending<ManualInstance> { it.rarity }.thenBy { it.name })
            ts.pills = ts.pills.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name })
            ts.materials = ts.materials.sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name })
            ts.herbs = ts.herbs.sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name })
            ts.seeds = ts.seeds.sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
        } else {
            scope.launch { stateStore.update {
                equipmentStacks = equipmentStacks.sortedWith(compareByDescending<EquipmentStack> { it.rarity }.thenBy { it.name })
                equipmentInstances = equipmentInstances.sortedWith(compareByDescending<EquipmentInstance> { it.rarity }.thenBy { it.name })
                manualStacks = manualStacks.sortedWith(compareByDescending<ManualStack> { it.rarity }.thenBy { it.name })
                manualInstances = manualInstances.sortedWith(compareByDescending<ManualInstance> { it.rarity }.thenBy { it.name })
                pills = pills.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name })
                materials = materials.sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name })
                herbs = herbs.sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name })
                seeds = seeds.sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
            } }
        }
    }

    fun hasEnoughSpiritStones(currentStones: Long, required: Long): Boolean {
        return currentStones >= required
    }

    fun deductSpiritStones(amount: Long): Long {
        var result = 0L
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val newAmount = (ts.gameData.spiritStones - amount).coerceAtLeast(0L)
            ts.gameData = ts.gameData.copy(spiritStones = newAmount)
            result = newAmount
        } else {
            scope.launch { stateStore.update {
                val newAmount = (gameData.spiritStones - amount).coerceAtLeast(0L)
                gameData = gameData.copy(spiritStones = newAmount)
                result = newAmount
            } }
        }
        return result
    }

    fun addSpiritStones(amount: Long): Long {
        var result = 0L
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val newAmount = ts.gameData.spiritStones + amount
            ts.gameData = ts.gameData.copy(spiritStones = newAmount)
            result = newAmount
        } else {
            scope.launch { stateStore.update {
                val newAmount = gameData.spiritStones + amount
                gameData = gameData.copy(spiritStones = newAmount)
                result = newAmount
            } }
        }
        return result
    }

    fun createEquipmentFromRecipe(recipe: ForgeRecipe): EquipmentStack {
        val template = EquipmentDatabase.getTemplateByName(recipe.name)
        if (template != null) {
            return EquipmentStack(
                id = java.util.UUID.randomUUID().toString(),
                name = template.name,
                slot = template.slot,
                rarity = recipe.rarity,
                physicalAttack = template.physicalAttack,
                magicAttack = template.magicAttack,
                physicalDefense = template.physicalDefense,
                magicDefense = template.magicDefense,
                speed = template.speed,
                hp = template.hp,
                mp = template.mp,
                description = template.description,
                minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity)
            )
        }
        return EquipmentDatabase.generateRandom(recipe.rarity, recipe.rarity).copy(
            id = java.util.UUID.randomUUID().toString(),
            rarity = recipe.rarity
        )
    }

    fun createEquipmentFromMerchantItem(item: MerchantItem): EquipmentStack {
        val eq = MerchantItemConverter.toEquipment(item)
        return eq.copy(quantity = 1)
    }

    fun createManualFromMerchantItem(item: MerchantItem): ManualStack {
        val manual = MerchantItemConverter.toManual(item)
        return manual.copy(quantity = 1)
    }

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        MerchantItemConverter.toPill(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        MerchantItemConverter.toMaterial(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        MerchantItemConverter.toHerb(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        MerchantItemConverter.toSeed(item)

    override fun addPill(item: Pill): AddResult = addPill(item, merge = true)
    override fun addMaterial(item: Material): AddResult = addMaterial(item, merge = true)
    override fun addHerb(item: Herb): AddResult = addHerb(item, merge = true)
    override fun addSeed(item: Seed): AddResult = addSeed(item, merge = true)
}

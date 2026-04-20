package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.EquipmentDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.ItemDatabase
import com.xianxia.sect.core.data.ManualDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase.ForgeRecipe
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Manual
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

@SystemPriority(order = 50)
@Singleton
class InventorySystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider
) : GameSystem, ItemAdder {

    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        const val MAX_INVENTORY_SIZE = 2000
        const val MAX_STACK_SIZE = 999
        private val VALID_RARITY_RANGE = 1..6
    }

    private val scope get() = applicationScopeProvider.scope

    val equipment: StateFlow<List<Equipment>> get() = stateStore.equipment
    val manuals: StateFlow<List<Manual>> get() = stateStore.manuals
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
            equipment = emptyList()
            manuals = emptyList()
            pills = emptyList()
            materials = emptyList()
            herbs = emptyList()
            seeds = emptyList()
        }
    }

    fun loadInventory(
        equipmentList: List<Equipment>,
        manualsList: List<Manual>,
        pillsList: List<Pill>,
        materialsList: List<Material>,
        herbsList: List<Herb>,
        seedsList: List<Seed>
    ) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipment = equipmentList
            ts.manuals = manualsList
            ts.pills = pillsList
            ts.materials = materialsList
            ts.herbs = herbsList
            ts.seeds = seedsList
        } else {
            scope.launch { stateStore.update {
                equipment = equipmentList
                manuals = manualsList
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

    private fun getTotalSlotCount(): Int {
        return stateStore.equipment.value.size +
               stateStore.manuals.value.size +
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

    fun canAddPill(name: String, rarity: Int, category: PillCategory): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.pills ?: stateStore.pills.value
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category }
        return canMerge || canAddItem()
    }

    fun canAddManual(name: String, rarity: Int, type: ManualType): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.manuals ?: stateStore.manuals.value
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.type == type && !it.isLearned }
        return canMerge || canAddItem()
    }

    fun canAddMaterial(name: String, rarity: Int, category: MaterialCategory): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.materials ?: stateStore.materials.value
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category }
        return canMerge || canAddItem()
    }

    fun canAddHerb(name: String, rarity: Int, category: String): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.herbs ?: stateStore.herbs.value
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.category == category }
        return canMerge || canAddItem()
    }

    fun canAddSeed(name: String, rarity: Int, growTime: Int): Boolean {
        val ts = stateStore.currentTransactionMutableState()
        val current = ts?.seeds ?: stateStore.seeds.value
        val canMerge = current.any { it.name == name && it.rarity == rarity && it.growTime == growTime }
        return canMerge || canAddItem()
    }

    private fun validateEquipment(item: Equipment): AddResult {
        if (item.id.isBlank()) return AddResult.INVALID_ID
        if (item.name.isBlank()) return AddResult.INVALID_NAME
        if (item.rarity !in VALID_RARITY_RANGE) return AddResult.INVALID_RARITY
        return AddResult.SUCCESS
    }

    private fun validateStackableItem(name: String, rarity: Int, quantity: Int): AddResult {
        if (name.isBlank()) return AddResult.INVALID_NAME
        if (rarity !in VALID_RARITY_RANGE) return AddResult.INVALID_RARITY
        if (quantity <= 0) return AddResult.INVALID_QUANTITY
        return AddResult.SUCCESS
    }

    override fun addEquipment(item: Equipment): AddResult {
        val validation = validateEquipment(item)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentEquipment = ts?.equipment ?: stateStore.equipment.value
        if (currentEquipment.any { it.id == item.id }) return AddResult.DUPLICATE_ID
        if (!canAddItem()) return AddResult.FULL

        if (ts != null) {
            ts.equipment = ts.equipment + item
        } else {
            scope.launch { stateStore.update {
                equipment = equipment + item
            } }
        }
        return AddResult.SUCCESS
    }

    fun removeEquipment(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.equipment.value.find { it.id == id }
        if (existing?.isLocked == true) {
            logWarning("Cannot remove locked equipment: ${existing.name}")
            return false
        }

        var removed = 0
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val newList = ts.equipment.filterNot { eq ->
                if (eq.id == id && removed < quantity) {
                    removed++
                    true
                } else false
            }
            ts.equipment = newList
        } else {
            scope.launch { stateStore.update {
                val newList = equipment.filterNot { eq ->
                    if (eq.id == id && removed < quantity) {
                        removed++
                        true
                    } else false
                }
                equipment = newList
            } }
        }
        return removed > 0
    }

    fun updateEquipment(id: String, transform: (Equipment) -> Equipment): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.equipment = ts.equipment.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                equipment = equipment.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getEquipmentById(id: String): Equipment? {
        return stateStore.equipment.value.find { it.id == id }
    }

    fun addManual(item: Manual, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation

        val ts = stateStore.currentTransactionMutableState()
        val currentManuals = ts?.manuals ?: stateStore.manuals.value

        if (merge) {
            val existing = currentManuals.find {
                it.name == item.name && it.rarity == item.rarity && it.type == item.type && !it.isLearned
            }
            if (existing != null) {
                val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                if (ts != null) {
                    ts.manuals = ts.manuals.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                } else {
                    scope.launch { stateStore.update {
                        manuals = manuals.map {
                            if (it.id == existing.id) it.copy(quantity = newQty) else it
                        }
                    } }
                }
                return AddResult.SUCCESS
            }
        }
        if (!canAddItem()) return AddResult.FULL
        if (ts != null) {
            ts.manuals = ts.manuals + item
        } else {
            scope.launch { stateStore.update {
                manuals = manuals + item
            } }
        }
        return AddResult.SUCCESS
    }

    fun removeManual(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.manuals.value.find { it.id == id }
        if (existing?.isLocked == true) {
            logWarning("Cannot remove locked manual: ${existing.name}")
            return false
        }

        var removed = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manuals = ts.manuals.mapNotNull { manual ->
                if (manual.id == id && !removed) {
                    val newQty = manual.quantity - quantity
                    when {
                        newQty < 0 -> {
                            logWarning("Cannot remove $quantity items, only ${manual.quantity} available")
                            manual
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            manual.copy(quantity = newQty)
                        }
                    }
                } else manual
            }
        } else {
            scope.launch { stateStore.update {
                manuals = manuals.mapNotNull { manual ->
                    if (manual.id == id && !removed) {
                        val newQty = manual.quantity - quantity
                        when {
                            newQty < 0 -> {
                                logWarning("Cannot remove $quantity items, only ${manual.quantity} available")
                                manual
                            }
                            newQty == 0 -> {
                                removed = true
                                null
                            }
                            else -> {
                                removed = true
                                manual.copy(quantity = newQty)
                            }
                        }
                    } else manual
                }
            } }
        }
        return removed
    }

    fun updateManual(id: String, transform: (Manual) -> Manual): Boolean {
        var found = false
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            ts.manuals = ts.manuals.map {
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
        } else {
            scope.launch { stateStore.update {
                manuals = manuals.map {
                    if (it.id == id) {
                        found = true
                        transform(it)
                    } else it
                }
            } }
        }
        return found
    }

    fun getManualById(id: String): Manual? {
        return stateStore.manuals.value.find { it.id == id }
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
                val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
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
                return AddResult.SUCCESS
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

    fun removePill(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.pills.value.find { it.id == id }
        if (existing?.isLocked == true) {
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

    fun removePillByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.pills.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (existing.isLocked) {
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
                        newQty <= 0 -> {
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
                            newQty <= 0 -> {
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
                val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
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
                return AddResult.SUCCESS
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

    fun removeMaterial(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.materials.value.find { it.id == id }
        if (existing?.isLocked == true) {
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

    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.materials.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (existing.isLocked) {
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
                        newQty <= 0 -> {
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
                            newQty <= 0 -> {
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
                val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
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
                return AddResult.SUCCESS
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

    fun removeHerb(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.herbs.value.find { it.id == id }
        if (existing?.isLocked == true) {
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

    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.herbs.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (existing.isLocked) {
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
                        newQty <= 0 -> {
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
                            newQty <= 0 -> {
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
                val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
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
                return AddResult.SUCCESS
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
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    ts.seeds = ts.seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    return AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            ts.seeds = ts.seeds + item
            return AddResult.SUCCESS
        }

        val currentSeedsSnapshot = stateStore.seeds.value
        val canMerge = merge && currentSeedsSnapshot.find {
            it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
        } != null

        if (!canMerge && currentSeedsSnapshot.size >= MAX_INVENTORY_SIZE) {
            return AddResult.FULL
        }

        stateStore.update {
            val currentSeeds = seeds
            if (merge) {
                val existing = currentSeeds.find {
                    it.name == item.name && it.rarity == item.rarity && it.growTime == item.growTime
                }
                if (existing != null) {
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    seeds = seeds.map {
                        if (it.id == existing.id) it.copy(quantity = newQty) else it
                    }
                    return@update
                }
            }
            if (seeds.size >= MAX_INVENTORY_SIZE) {
                return@update
            }
            seeds = seeds + item
        }
        return AddResult.SUCCESS
    }

    fun removeSeed(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.seeds.value.find { it.id == id }
        if (existing?.isLocked == true) {
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

    suspend fun removeSeedSync(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false

        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) {
            val existing = ts.seeds.find { it.id == id }
            if (existing?.isLocked == true) {
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
        if (existing?.isLocked == true) {
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

    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        val existing = stateStore.seeds.value.find { it.name == name && it.rarity == rarity }
            ?: return false
        if (existing.isLocked) {
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
                        newQty <= 0 -> {
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
                            newQty <= 0 -> {
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
            "equipment" -> stateStore.equipment.value.size
            "manual" -> stateStore.manuals.value.size
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
            ts.equipment = ts.equipment.sortedWith(compareByDescending<Equipment> { it.rarity }.thenBy { it.name })
            ts.manuals = ts.manuals.sortedWith(compareByDescending<Manual> { it.rarity }.thenBy { it.name })
            ts.pills = ts.pills.sortedWith(compareByDescending<Pill> { it.rarity }.thenBy { it.name })
            ts.materials = ts.materials.sortedWith(compareByDescending<Material> { it.rarity }.thenBy { it.name })
            ts.herbs = ts.herbs.sortedWith(compareByDescending<Herb> { it.rarity }.thenBy { it.name })
            ts.seeds = ts.seeds.sortedWith(compareByDescending<Seed> { it.rarity }.thenBy { it.name })
        } else {
            scope.launch { stateStore.update {
                equipment = equipment.sortedWith(compareByDescending<Equipment> { it.rarity }.thenBy { it.name })
                manuals = manuals.sortedWith(compareByDescending<Manual> { it.rarity }.thenBy { it.name })
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

    fun createEquipmentFromRecipe(recipe: ForgeRecipe): Equipment {
        val template = EquipmentDatabase.getTemplateByName(recipe.name)
        if (template != null) {
            return Equipment(
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

    fun createEquipmentFromMerchantItem(item: MerchantItem): Equipment =
        MerchantItemConverter.toEquipment(item)

    fun createManualFromMerchantItem(item: MerchantItem): Manual =
        MerchantItemConverter.toManual(item)

    fun createPillFromMerchantItem(item: MerchantItem): Pill =
        MerchantItemConverter.toPill(item)

    fun createMaterialFromMerchantItem(item: MerchantItem): Material =
        MerchantItemConverter.toMaterial(item)

    fun createHerbFromMerchantItem(item: MerchantItem): Herb =
        MerchantItemConverter.toHerb(item)

    fun createSeedFromMerchantItem(item: MerchantItem): Seed =
        MerchantItemConverter.toSeed(item)

    override fun addManual(item: Manual): AddResult = addManual(item, merge = true)
    override fun addPill(item: Pill): AddResult = addPill(item, merge = true)
    override fun addMaterial(item: Material): AddResult = addMaterial(item, merge = true)
    override fun addHerb(item: Herb): AddResult = addHerb(item, merge = true)
    override fun addSeed(item: Seed): AddResult = addSeed(item, merge = true)
}

package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.StateFlowListUtils
import com.xianxia.sect.core.util.StackableItem
import com.xianxia.sect.core.util.StackableItemUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

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

@Singleton
class InventorySystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
        const val MAX_INVENTORY_SIZE = 2000
        const val MAX_STACK_SIZE = 999
        private val VALID_RARITY_RANGE = 1..6
    }
    
    private val equipmentLock = ReentrantReadWriteLock()
    private val manualsLock = ReentrantReadWriteLock()
    private val pillsLock = ReentrantReadWriteLock()
    private val materialsLock = ReentrantReadWriteLock()
    private val herbsLock = ReentrantReadWriteLock()
    private val seedsLock = ReentrantReadWriteLock()
    
    private val _equipment = MutableStateFlow<List<Equipment>>(emptyList())
    val equipment: StateFlow<List<Equipment>> = _equipment.asStateFlow()
    internal val mutableEquipment: MutableStateFlow<List<Equipment>> get() = _equipment
    
    private val _manuals = MutableStateFlow<List<Manual>>(emptyList())
    val manuals: StateFlow<List<Manual>> = _manuals.asStateFlow()
    internal val mutableManuals: MutableStateFlow<List<Manual>> get() = _manuals
    
    private val _pills = MutableStateFlow<List<Pill>>(emptyList())
    val pills: StateFlow<List<Pill>> = _pills.asStateFlow()
    internal val mutablePills: MutableStateFlow<List<Pill>> get() = _pills
    
    private val _materials = MutableStateFlow<List<Material>>(emptyList())
    val materials: StateFlow<List<Material>> = _materials.asStateFlow()
    internal val mutableMaterials: MutableStateFlow<List<Material>> get() = _materials
    
    private val _herbs = MutableStateFlow<List<Herb>>(emptyList())
    val herbs: StateFlow<List<Herb>> = _herbs.asStateFlow()
    internal val mutableHerbs: MutableStateFlow<List<Herb>> get() = _herbs
    
    private val _seeds = MutableStateFlow<List<Seed>>(emptyList())
    val seeds: StateFlow<List<Seed>> = _seeds.asStateFlow()
    internal val mutableSeeds: MutableStateFlow<List<Seed>> get() = _seeds
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "InventorySystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "InventorySystem released")
    }
    
    override fun clear() {
        _equipment.value = emptyList()
        _manuals.value = emptyList()
        _pills.value = emptyList()
        _materials.value = emptyList()
        _herbs.value = emptyList()
        _seeds.value = emptyList()
    }
    
    fun loadInventory(
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>
    ) {
        _equipment.value = equipment
        _manuals.value = manuals
        _pills.value = pills
        _materials.value = materials
        _herbs.value = herbs
        _seeds.value = seeds
    }
    
    private fun validateQuantity(quantity: Int, name: String = "quantity"): Boolean {
        if (quantity <= 0) {
            Log.w(TAG, "Invalid $name: $quantity, must be positive")
            return false
        }
        return true
    }
    
    private fun logWarning(msg: String) = Log.w(TAG, msg)
    
    fun getCapacityInfo(): CapacityInfo {
        return equipmentLock.readLock().withLock {
            manualsLock.readLock().withLock {
                pillsLock.readLock().withLock {
                    materialsLock.readLock().withLock {
                        herbsLock.readLock().withLock {
                            seedsLock.readLock().withLock {
                                val current = getTotalSlotCountInternal()
                                CapacityInfo(
                                    currentSlots = current,
                                    maxSlots = MAX_INVENTORY_SIZE,
                                    remainingSlots = MAX_INVENTORY_SIZE - current,
                                    isFull = current >= MAX_INVENTORY_SIZE
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun getTotalSlotCountInternal(): Int {
        return _equipment.value.size + 
               _manuals.value.size + 
               _pills.value.size +
               _materials.value.size +
               _herbs.value.size +
               _seeds.value.size
    }
    
    fun canAddItem(): Boolean = getTotalSlotCountInternal() < MAX_INVENTORY_SIZE
    
    fun canAddItems(count: Int): Boolean = getTotalSlotCountInternal() + count <= MAX_INVENTORY_SIZE
    
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
    
    fun addEquipment(item: Equipment): AddResult {
        val validation = validateEquipment(item)
        if (validation != AddResult.SUCCESS) return validation
        
        if (!canAddItem()) return AddResult.FULL
        
        equipmentLock.writeLock().withLock {
            if (_equipment.value.any { it.id == item.id }) {
                return AddResult.DUPLICATE_ID
            }
            _equipment.value = _equipment.value + item
        }
        return AddResult.SUCCESS
    }
    
    fun removeEquipment(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        equipmentLock.writeLock().withLock {
            val item = _equipment.value.find { it.id == id }
            if (item?.isLocked == true) {
                logWarning("Cannot remove locked equipment: ${item.name}")
                return false
            }
            var removed = 0
            val newList = _equipment.value.filterNot { eq ->
                if (eq.id == id && removed < quantity) {
                    removed++
                    true
                } else false
            }
            _equipment.value = newList
            return removed > 0
        }
    }
    
    fun updateEquipment(id: String, transform: (Equipment) -> Equipment): Boolean {
        equipmentLock.writeLock().withLock {
            var found = false
            _equipment.value = _equipment.value.map { 
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    fun getEquipmentById(id: String): Equipment? {
        return equipmentLock.readLock().withLock {
            _equipment.value.find { it.id == id }
        }
    }
    
    fun addManual(item: Manual, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation
        
        manualsLock.writeLock().withLock {
            if (merge) {
                val existingIndex = _manuals.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity && it.type == item.type
                }
                if (existingIndex >= 0) {
                    val existing = _manuals.value[existingIndex]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    _manuals.value = _manuals.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            _manuals.value = _manuals.value + item
        }
        return AddResult.SUCCESS
    }
    
    fun removeManual(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        manualsLock.writeLock().withLock {
            val item = _manuals.value.find { it.id == id }
            if (item?.isLocked == true) {
                logWarning("Cannot remove locked manual: ${item.name}")
                return false
            }
            var removed = false
            _manuals.value = _manuals.value.mapNotNull { manual ->
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
            return removed
        }
    }
    
    fun updateManual(id: String, transform: (Manual) -> Manual): Boolean {
        manualsLock.writeLock().withLock {
            var found = false
            _manuals.value = _manuals.value.map { 
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    fun getManualById(id: String): Manual? {
        return manualsLock.readLock().withLock {
            _manuals.value.find { it.id == id }
        }
    }
    
    fun addPill(item: Pill, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation
        
        pillsLock.writeLock().withLock {
            if (merge) {
                val existingIndex = _pills.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity && it.category == item.category
                }
                if (existingIndex >= 0) {
                    val existing = _pills.value[existingIndex]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    _pills.value = _pills.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            _pills.value = _pills.value + item
        }
        return AddResult.SUCCESS
    }
    
    fun removePill(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        pillsLock.writeLock().withLock {
            val item = _pills.value.find { it.id == id }
            if (item?.isLocked == true) {
                logWarning("Cannot remove locked pill: ${item.name}")
                return false
            }
            var removed = false
            _pills.value = _pills.value.mapNotNull { pill ->
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
            return removed
        }
    }
    
    fun removePillByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        pillsLock.writeLock().withLock {
            val item = _pills.value.find { it.name == name && it.rarity == rarity } ?: return false
            if (item.isLocked) {
                logWarning("Cannot remove locked pill: ${item.name}")
                return false
            }
            if (item.quantity < quantity) {
                logWarning("Cannot remove $quantity items '$name', only ${item.quantity} available")
                return false
            }
            var removed = false
            _pills.value = _pills.value.mapNotNull { pill ->
                if (pill.id == item.id && !removed) {
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
            return removed
        }
    }
    
    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean {
        pillsLock.writeLock().withLock {
            var found = false
            _pills.value = _pills.value.map { 
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    fun getPillById(id: String): Pill? {
        return pillsLock.readLock().withLock {
            _pills.value.find { it.id == id }
        }
    }
    
    fun getPillQuantity(id: String): Int {
        return pillsLock.readLock().withLock {
            _pills.value.find { it.id == id }?.quantity ?: 0
        }
    }
    
    fun hasPill(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return pillsLock.readLock().withLock {
            val item = _pills.value.find { it.name == name && it.rarity == rarity } ?: return false
            item.quantity >= quantity
        }
    }
    
    fun addMaterial(item: Material, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation
        
        materialsLock.writeLock().withLock {
            if (merge) {
                val existingIndex = _materials.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity && it.category == item.category 
                }
                if (existingIndex >= 0) {
                    val existing = _materials.value[existingIndex]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    _materials.value = _materials.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            _materials.value = _materials.value + item
        }
        return AddResult.SUCCESS
    }
    
    fun removeMaterial(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        materialsLock.writeLock().withLock {
            val item = _materials.value.find { it.id == id }
            if (item?.isLocked == true) {
                logWarning("Cannot remove locked material: ${item.name}")
                return false
            }
            var removed = false
            _materials.value = _materials.value.mapNotNull { material ->
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
            return removed
        }
    }
    
    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        materialsLock.writeLock().withLock {
            val item = _materials.value.find { it.name == name && it.rarity == rarity } ?: return false
            if (item.isLocked) {
                logWarning("Cannot remove locked material: ${item.name}")
                return false
            }
            if (item.quantity < quantity) {
                logWarning("Cannot remove $quantity items '$name', only ${item.quantity} available")
                return false
            }
            var removed = false
            _materials.value = _materials.value.mapNotNull { material ->
                if (material.id == item.id && !removed) {
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
            return removed
        }
    }
    
    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean {
        materialsLock.writeLock().withLock {
            var found = false
            _materials.value = _materials.value.map { 
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    fun getMaterialById(id: String): Material? {
        return materialsLock.readLock().withLock {
            _materials.value.find { it.id == id }
        }
    }
    
    fun getMaterialQuantity(id: String): Int {
        return materialsLock.readLock().withLock {
            _materials.value.find { it.id == id }?.quantity ?: 0
        }
    }
    
    fun hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return materialsLock.readLock().withLock {
            val item = _materials.value.find { it.name == name && it.rarity == rarity } ?: return false
            item.quantity >= quantity
        }
    }
    
    fun addHerb(item: Herb, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation
        
        herbsLock.writeLock().withLock {
            if (merge) {
                val existingIndex = _herbs.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity && it.category == item.category 
                }
                if (existingIndex >= 0) {
                    val existing = _herbs.value[existingIndex]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    _herbs.value = _herbs.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            _herbs.value = _herbs.value + item
        }
        return AddResult.SUCCESS
    }
    
    fun removeHerb(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        herbsLock.writeLock().withLock {
            val item = _herbs.value.find { it.id == id }
            if (item?.isLocked == true) {
                logWarning("Cannot remove locked herb: ${item.name}")
                return false
            }
            var removed = false
            _herbs.value = _herbs.value.mapNotNull { herb ->
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
            return removed
        }
    }
    
    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        herbsLock.writeLock().withLock {
            val item = _herbs.value.find { it.name == name && it.rarity == rarity } ?: return false
            if (item.isLocked) {
                logWarning("Cannot remove locked herb: ${item.name}")
                return false
            }
            if (item.quantity < quantity) {
                logWarning("Cannot remove $quantity items '$name', only ${item.quantity} available")
                return false
            }
            var removed = false
            _herbs.value = _herbs.value.mapNotNull { herb ->
                if (herb.id == item.id && !removed) {
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
            return removed
        }
    }
    
    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
        herbsLock.writeLock().withLock {
            var found = false
            _herbs.value = _herbs.value.map { 
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    fun getHerbById(id: String): Herb? {
        return herbsLock.readLock().withLock {
            _herbs.value.find { it.id == id }
        }
    }
    
    fun getHerbQuantity(id: String): Int {
        return herbsLock.readLock().withLock {
            _herbs.value.find { it.id == id }?.quantity ?: 0
        }
    }
    
    fun hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return herbsLock.readLock().withLock {
            val item = _herbs.value.find { it.name == name && it.rarity == rarity } ?: return false
            item.quantity >= quantity
        }
    }
    
    fun addSeed(item: Seed, merge: Boolean = true): AddResult {
        val validation = validateStackableItem(item.name, item.rarity, item.quantity)
        if (validation != AddResult.SUCCESS) return validation
        
        seedsLock.writeLock().withLock {
            if (merge) {
                val existingIndex = _seeds.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity
                }
                if (existingIndex >= 0) {
                    val existing = _seeds.value[existingIndex]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                    _seeds.value = _seeds.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return AddResult.SUCCESS
                }
            }
            if (!canAddItem()) return AddResult.FULL
            _seeds.value = _seeds.value + item
        }
        return AddResult.SUCCESS
    }
    
    fun removeSeed(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        seedsLock.writeLock().withLock {
            val item = _seeds.value.find { it.id == id }
            if (item?.isLocked == true) {
                logWarning("Cannot remove locked seed: ${item.name}")
                return false
            }
            var removed = false
            _seeds.value = _seeds.value.mapNotNull { seed ->
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
    }
    
    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        seedsLock.writeLock().withLock {
            val item = _seeds.value.find { it.name == name && it.rarity == rarity } ?: return false
            if (item.isLocked) {
                logWarning("Cannot remove locked seed: ${item.name}")
                return false
            }
            if (item.quantity < quantity) {
                logWarning("Cannot remove $quantity items '$name', only ${item.quantity} available")
                return false
            }
            var removed = false
            _seeds.value = _seeds.value.mapNotNull { seed ->
                if (seed.id == item.id && !removed) {
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
            return removed
        }
    }
    
    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
        seedsLock.writeLock().withLock {
            var found = false
            _seeds.value = _seeds.value.map { 
                if (it.id == id) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    fun getSeedById(id: String): Seed? {
        return seedsLock.readLock().withLock {
            _seeds.value.find { it.id == id }
        }
    }
    
    fun getSeedQuantity(id: String): Int {
        return seedsLock.readLock().withLock {
            _seeds.value.find { it.id == id }?.quantity ?: 0
        }
    }
    
    fun hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return seedsLock.readLock().withLock {
            val item = _seeds.value.find { it.name == name && it.rarity == rarity } ?: return false
            item.quantity >= quantity
        }
    }
    
    fun addMaterials(items: List<Material>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        materialsLock.writeLock().withLock {
            var currentList = _materials.value
            for (item in validItems) {
                if (merge) {
                    val existingIndex = currentList.indexOfFirst { 
                        it.name == item.name && it.rarity == item.rarity && it.category == item.category 
                    }
                    if (existingIndex >= 0) {
                        val existing = currentList[existingIndex]
                        val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                        currentList = currentList.mapIndexed { index, listItem ->
                            if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                        }
                    } else {
                        if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                        currentList = currentList + item
                    }
                } else {
                    if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                    currentList = currentList + item
                }
            }
            _materials.value = currentList
        }
        return AddResult.SUCCESS
    }
    
    fun addHerbs(items: List<Herb>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        herbsLock.writeLock().withLock {
            var currentList = _herbs.value
            for (item in validItems) {
                if (merge) {
                    val existingIndex = currentList.indexOfFirst { 
                        it.name == item.name && it.rarity == item.rarity && it.category == item.category 
                    }
                    if (existingIndex >= 0) {
                        val existing = currentList[existingIndex]
                        val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                        currentList = currentList.mapIndexed { index, listItem ->
                            if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                        }
                    } else {
                        if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                        currentList = currentList + item
                    }
                } else {
                    if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                    currentList = currentList + item
                }
            }
            _herbs.value = currentList
        }
        return AddResult.SUCCESS
    }
    
    fun addSeeds(items: List<Seed>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        seedsLock.writeLock().withLock {
            var currentList = _seeds.value
            for (item in validItems) {
                if (merge) {
                    val existingIndex = currentList.indexOfFirst { 
                        it.name == item.name && it.rarity == item.rarity
                    }
                    if (existingIndex >= 0) {
                        val existing = currentList[existingIndex]
                        val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                        currentList = currentList.mapIndexed { index, listItem ->
                            if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                        }
                    } else {
                        if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                        currentList = currentList + item
                    }
                } else {
                    if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                    currentList = currentList + item
                }
            }
            _seeds.value = currentList
        }
        return AddResult.SUCCESS
    }
    
    fun addPills(items: List<Pill>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        pillsLock.writeLock().withLock {
            var currentList = _pills.value
            for (item in validItems) {
                if (merge) {
                    val existingIndex = currentList.indexOfFirst { 
                        it.name == item.name && it.rarity == item.rarity && it.category == item.category 
                    }
                    if (existingIndex >= 0) {
                        val existing = currentList[existingIndex]
                        val newQty = (existing.quantity + item.quantity).coerceAtMost(MAX_STACK_SIZE)
                        currentList = currentList.mapIndexed { index, listItem ->
                            if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                        }
                    } else {
                        if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                        currentList = currentList + item
                    }
                } else {
                    if (currentList.size >= MAX_INVENTORY_SIZE) return AddResult.FULL
                    currentList = currentList + item
                }
            }
            _pills.value = currentList
        }
        return AddResult.SUCCESS
    }
    
    fun getTotalItemCount(): Int {
        return equipmentLock.readLock().withLock {
            manualsLock.readLock().withLock {
                pillsLock.readLock().withLock {
                    materialsLock.readLock().withLock {
                        herbsLock.readLock().withLock {
                            seedsLock.readLock().withLock {
                                _equipment.value.size + 
                                _manuals.value.size + 
                                _pills.value.sumOf { it.quantity } +
                                _materials.value.sumOf { it.quantity } +
                                _herbs.value.sumOf { it.quantity } +
                                _seeds.value.sumOf { it.quantity }
                            }
                        }
                    }
                }
            }
        }
    }
    
    fun hasItem(itemType: String, itemId: String): Boolean {
        return when (itemType) {
            "equipment" -> equipmentLock.readLock().withLock { _equipment.value.any { it.id == itemId } }
            "manual" -> manualsLock.readLock().withLock { _manuals.value.any { it.id == itemId } }
            "pill" -> pillsLock.readLock().withLock { _pills.value.any { it.id == itemId } }
            "material" -> materialsLock.readLock().withLock { _materials.value.any { it.id == itemId } }
            "herb" -> herbsLock.readLock().withLock { _herbs.value.any { it.id == itemId } }
            "seed" -> seedsLock.readLock().withLock { _seeds.value.any { it.id == itemId } }
            else -> false
        }
    }
    
    fun getItemQuantity(itemType: String, itemId: String): Int {
        return when (itemType) {
            "equipment" -> equipmentLock.readLock().withLock { _equipment.value.count { it.id == itemId } }
            "manual" -> manualsLock.readLock().withLock { _manuals.value.count { it.id == itemId } }
            "pill" -> pillsLock.readLock().withLock { _pills.value.find { it.id == itemId }?.quantity ?: 0 }
            "material" -> materialsLock.readLock().withLock { _materials.value.find { it.id == itemId }?.quantity ?: 0 }
            "herb" -> herbsLock.readLock().withLock { _herbs.value.find { it.id == itemId }?.quantity ?: 0 }
            "seed" -> seedsLock.readLock().withLock { _seeds.value.find { it.id == itemId }?.quantity ?: 0 }
            else -> 0
        }
    }
}

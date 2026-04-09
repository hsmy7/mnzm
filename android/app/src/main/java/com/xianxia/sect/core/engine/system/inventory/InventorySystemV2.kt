@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package com.xianxia.sect.core.engine.system.inventory

import android.util.Log
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Manual
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class CapacityInfo(
    val currentSlots: Int,
    val maxSlots: Int,
    val remainingSlots: Int,
    val isFull: Boolean,
    val totalItemCount: Int,
    val utilizationRate: Float
)

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

@Singleton
class InventorySystemV2 @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "InventorySystemV2"
        const val SYSTEM_NAME = "InventorySystemV2"
        const val MAX_INVENTORY_SIZE = 2000
        private val VALID_RARITY_RANGE = 1..6
    }
    
    private val scope = CoroutineScope(Dispatchers.Default)
    
    private val equipmentContainer = ItemContainer<InventoryItemAdapter.EquipmentAdapter>()
    private val manualsContainer = ItemContainer<InventoryItemAdapter.ManualAdapter>()
    private val pillsContainer = ItemContainer<InventoryItemAdapter.PillAdapter>()
    private val materialsContainer = ItemContainer<InventoryItemAdapter.MaterialAdapter>()
    private val herbsContainer = ItemContainer<InventoryItemAdapter.HerbAdapter>()
    private val seedsContainer = ItemContainer<InventoryItemAdapter.SeedAdapter>()
    
    private val eventBus = InventoryEventBus()
    
    private val containerRegistry: Map<ItemType, ItemContainer<out InventoryItem>> = mapOf(
        ItemType.EQUIPMENT to equipmentContainer,
        ItemType.MANUAL to manualsContainer,
        ItemType.PILL to pillsContainer,
        ItemType.MATERIAL to materialsContainer,
        ItemType.HERB to herbsContainer,
        ItemType.SEED to seedsContainer
    )
    
    @Suppress("UNCHECKED_CAST")
    private fun <T : InventoryItem> getContainer(itemType: ItemType): ItemContainer<T>? {
        return containerRegistry[itemType] as? ItemContainer<T>
    }
    
    private val _capacityInfo = MutableStateFlow(
        CapacityInfo(0, MAX_INVENTORY_SIZE, MAX_INVENTORY_SIZE, false, 0, 0f)
    )
    val capacityInfo: StateFlow<CapacityInfo> = _capacityInfo.asStateFlow()
    
    val equipment: StateFlow<List<InventoryItemAdapter.EquipmentAdapter>> = equipmentContainer.items
    val manuals: StateFlow<List<InventoryItemAdapter.ManualAdapter>> = manualsContainer.items
    val pills: StateFlow<List<InventoryItemAdapter.PillAdapter>> = pillsContainer.items
    val materials: StateFlow<List<InventoryItemAdapter.MaterialAdapter>> = materialsContainer.items
    val herbs: StateFlow<List<InventoryItemAdapter.HerbAdapter>> = herbsContainer.items
    val seeds: StateFlow<List<InventoryItemAdapter.SeedAdapter>> = seedsContainer.items
    
    internal val equipmentListDelegate: MutableStateFlow<List<Equipment>> = MutableStateFlow(emptyList())
    internal val manualsListDelegate: MutableStateFlow<List<Manual>> = MutableStateFlow(emptyList())
    internal val pillsListDelegate: MutableStateFlow<List<Pill>> = MutableStateFlow(emptyList())
    internal val materialsListDelegate: MutableStateFlow<List<Material>> = MutableStateFlow(emptyList())
    internal val herbsListDelegate: MutableStateFlow<List<Herb>> = MutableStateFlow(emptyList())
    internal val seedsListDelegate: MutableStateFlow<List<Seed>> = MutableStateFlow(emptyList())
    
    fun syncDelegates() {
        equipmentListDelegate.value = equipmentContainer.getAll().map { it.unwrap() }
        manualsListDelegate.value = manualsContainer.getAll().map { it.unwrap() }
        pillsListDelegate.value = pillsContainer.getAll().map { it.unwrap() }
        materialsListDelegate.value = materialsContainer.getAll().map { it.unwrap() }
        herbsListDelegate.value = herbsContainer.getAll().map { it.unwrap() }
        seedsListDelegate.value = seedsContainer.getAll().map { it.unwrap() }
    }
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "InventorySystemV2 initialized")
    }
    
    override fun release() {
        Log.d(TAG, "InventorySystemV2 released")
    }
    
    override suspend fun clear() {
        val itemCount = getTotalSlotCount()
        equipmentContainer.clear()
        manualsContainer.clear()
        pillsContainer.clear()
        materialsContainer.clear()
        herbsContainer.clear()
        seedsContainer.clear()
        updateCapacityInfo()
        syncDelegates()
        
        eventBus.emit(InventoryEvent.InventoryCleared(itemCount))
    }
    
    private fun updateCapacityInfo() {
        val currentSlots = getTotalSlotCount()
        val totalItems = getTotalItemCount()
        _capacityInfo.value = CapacityInfo(
            currentSlots = currentSlots,
            maxSlots = MAX_INVENTORY_SIZE,
            remainingSlots = MAX_INVENTORY_SIZE - currentSlots,
            isFull = currentSlots >= MAX_INVENTORY_SIZE,
            totalItemCount = totalItems,
            utilizationRate = if (MAX_INVENTORY_SIZE > 0) currentSlots.toFloat() / MAX_INVENTORY_SIZE else 0f
        )
    }
    
    private fun getTotalSlotCount(): Int {
        return equipmentContainer.size + 
               manualsContainer.size + 
               pillsContainer.size +
               materialsContainer.size +
               herbsContainer.size +
               seedsContainer.size
    }
    
    fun canAddItem(): Boolean = getTotalSlotCount() < MAX_INVENTORY_SIZE
    
    fun canAddItems(count: Int): Boolean = getTotalSlotCount() + count <= MAX_INVENTORY_SIZE
    
    private fun validateRarity(rarity: Int): Boolean = rarity in VALID_RARITY_RANGE
    
    private fun <T : InventoryItem> addItemGeneric(
        container: ItemContainer<T>,
        item: T,
        merge: Boolean = true,
        checkDuplicate: Boolean = false
    ): AddResult {
        if (item.name.isBlank()) return AddResult.INVALID_NAME
        if (!validateRarity(item.rarity)) return AddResult.INVALID_RARITY
        if (item.isStackable && item.quantity <= 0) return AddResult.INVALID_QUANTITY
        
        if (checkDuplicate && container.hasItem(item.id)) {
            return AddResult.DUPLICATE_ID
        }
        
        if (!canAddItem()) return AddResult.FULL
        
        val result = container.add(item, merge, MAX_INVENTORY_SIZE)
        
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch { eventBus.emit(InventoryEvent.ItemAdded(item, item.quantity, result.mergedCount > 0)) }
        }
        
        return if (result.success) AddResult.SUCCESS else AddResult.FULL
    }
    
    private fun <T : InventoryItem> removeItemGeneric(
        container: ItemContainer<T>,
        id: String,
        quantity: Int = 1
    ): Boolean {
        if (quantity <= 0) return false
        
        val item = container.getById(id) ?: return false
        if (item.isLocked) {
            Log.w(TAG, "Cannot remove locked item: ${item.name}")
            return false
        }
        
        val result = container.remove(id, quantity)
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch {
                eventBus.emit(InventoryEvent.ItemRemoved(
                    item.withQuantity(quantity) as T,
                    quantity
                ))
            }
        }
        return result.success
    }
    
    private fun <T : InventoryItem> removeItemByNameGeneric(
        container: ItemContainer<T>,
        name: String,
        rarity: Int,
        quantity: Int
    ): Boolean {
        val item = container.getByNameAndRarity(name, rarity)
        if (item == null) {
            Log.w(TAG, "Item not found: $name with rarity $rarity")
            return false
        }
        
        if (item.quantity < quantity) {
            Log.w(TAG, "Cannot remove $quantity items '$name', only ${item.quantity} available")
            return false
        }
        
        val result = container.remove(item.id, quantity)
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch {
                eventBus.emit(InventoryEvent.ItemRemoved(
                    item.withQuantity(quantity) as T,
                    quantity
                ))
            }
        }
        return result.success
    }
    
    fun addEquipment(item: Equipment): AddResult {
        if (item.id.isBlank()) return AddResult.INVALID_ID
        if (item.name.isBlank()) return AddResult.INVALID_NAME
        if (!validateRarity(item.rarity)) return AddResult.INVALID_RARITY
        
        if (equipmentContainer.hasItem(item.id)) {
            return AddResult.DUPLICATE_ID
        }
        
        if (!canAddItem()) return AddResult.FULL
        
        val adapter = InventoryItemAdapter.EquipmentAdapter(item)
        val result = equipmentContainer.add(adapter, merge = false, maxSlots = MAX_INVENTORY_SIZE)
        
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch { eventBus.emit(InventoryEvent.ItemAdded(adapter, 1, false)) }
        }
        
        return if (result.success) AddResult.SUCCESS else AddResult.FULL
    }
    
    fun addManual(item: Manual, merge: Boolean = true): AddResult {
        val adapter = InventoryItemAdapter.ManualAdapter(item)
        return addItemGeneric(manualsContainer, adapter, merge)
    }
    
    fun addPill(item: Pill, merge: Boolean = true): AddResult {
        val adapter = InventoryItemAdapter.PillAdapter(item)
        return addItemGeneric(pillsContainer, adapter, merge)
    }
    
    fun addMaterial(item: Material, merge: Boolean = true): AddResult {
        val adapter = InventoryItemAdapter.MaterialAdapter(item)
        return addItemGeneric(materialsContainer, adapter, merge)
    }
    
    fun addHerb(item: Herb, merge: Boolean = true): AddResult {
        val adapter = InventoryItemAdapter.HerbAdapter(item)
        return addItemGeneric(herbsContainer, adapter, merge)
    }
    
    fun addSeed(item: Seed, merge: Boolean = true): AddResult {
        val adapter = InventoryItemAdapter.SeedAdapter(item)
        return addItemGeneric(seedsContainer, adapter, merge)
    }
    
    fun removeEquipment(id: String): Boolean {
        return removeItemGeneric(equipmentContainer, id)
    }
    
    fun removeManual(id: String, quantity: Int = 1): Boolean {
        return removeItemGeneric(manualsContainer, id, quantity)
    }
    
    fun removePill(id: String, quantity: Int = 1): Boolean {
        return removeItemGeneric(pillsContainer, id, quantity)
    }
    
    fun removeMaterial(id: String, quantity: Int = 1): Boolean {
        return removeItemGeneric(materialsContainer, id, quantity)
    }
    
    fun removeHerb(id: String, quantity: Int = 1): Boolean {
        return removeItemGeneric(herbsContainer, id, quantity)
    }
    
    fun removeSeed(id: String, quantity: Int = 1): Boolean {
        return removeItemGeneric(seedsContainer, id, quantity)
    }
    
    fun removePillByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return removeItemByNameGeneric(pillsContainer, name, rarity, quantity)
    }
    
    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return removeItemByNameGeneric(materialsContainer, name, rarity, quantity)
    }
    
    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return removeItemByNameGeneric(herbsContainer, name, rarity, quantity)
    }
    
    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return removeItemByNameGeneric(seedsContainer, name, rarity, quantity)
    }
    
    fun updateEquipment(id: String, transform: (Equipment) -> Equipment): Boolean {
        return equipmentContainer.update(id) { adapter ->
            InventoryItemAdapter.EquipmentAdapter(transform(adapter.unwrap()))
        }.also { if (it) syncDelegates() }
    }
    
    fun updateManual(id: String, transform: (Manual) -> Manual): Boolean {
        return manualsContainer.update(id) { adapter ->
            InventoryItemAdapter.ManualAdapter(transform(adapter.unwrap()))
        }.also { if (it) syncDelegates() }
    }
    
    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean {
        return pillsContainer.update(id) { adapter ->
            InventoryItemAdapter.PillAdapter(transform(adapter.unwrap()))
        }.also { if (it) syncDelegates() }
    }
    
    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean {
        return materialsContainer.update(id) { adapter ->
            InventoryItemAdapter.MaterialAdapter(transform(adapter.unwrap()))
        }.also { if (it) syncDelegates() }
    }
    
    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean {
        return herbsContainer.update(id) { adapter ->
            InventoryItemAdapter.HerbAdapter(transform(adapter.unwrap()))
        }.also { if (it) syncDelegates() }
    }
    
    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean {
        return seedsContainer.update(id) { adapter ->
            InventoryItemAdapter.SeedAdapter(transform(adapter.unwrap()))
        }.also { if (it) syncDelegates() }
    }
    
    fun getEquipmentById(id: String): Equipment? = equipmentContainer.getById(id)?.unwrap()
    fun getManualById(id: String): Manual? = manualsContainer.getById(id)?.unwrap()
    fun getPillById(id: String): Pill? = pillsContainer.getById(id)?.unwrap()
    fun getMaterialById(id: String): Material? = materialsContainer.getById(id)?.unwrap()
    fun getHerbById(id: String): Herb? = herbsContainer.getById(id)?.unwrap()
    fun getSeedById(id: String): Seed? = seedsContainer.getById(id)?.unwrap()
    
    fun hasPill(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return pillsContainer.hasItemByName(name, rarity, quantity)
    }
    
    fun hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return materialsContainer.hasItemByName(name, rarity, quantity)
    }
    
    fun hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return herbsContainer.hasItemByName(name, rarity, quantity)
    }
    
    fun hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean {
        return seedsContainer.hasItemByName(name, rarity, quantity)
    }
    
    fun getPillByName(name: String, rarity: Int): Pill? = pillsContainer.getByNameAndRarity(name, rarity)?.unwrap()
    fun getMaterialByName(name: String, rarity: Int): Material? = materialsContainer.getByNameAndRarity(name, rarity)?.unwrap()
    fun getHerbByName(name: String, rarity: Int): Herb? = herbsContainer.getByNameAndRarity(name, rarity)?.unwrap()
    fun getSeedByName(name: String, rarity: Int): Seed? = seedsContainer.getByNameAndRarity(name, rarity)?.unwrap()
    
    fun getPillQuantity(id: String): Int = pillsContainer.getQuantity(id)
    fun getMaterialQuantity(id: String): Int = materialsContainer.getQuantity(id)
    fun getHerbQuantity(id: String): Int = herbsContainer.getQuantity(id)
    fun getSeedQuantity(id: String): Int = seedsContainer.getQuantity(id)
    
    fun getTotalItemCount(): Int {
        return equipmentContainer.totalItemCount() + 
               manualsContainer.totalItemCount() + 
               pillsContainer.totalItemCount() +
               materialsContainer.totalItemCount() +
               herbsContainer.totalItemCount() +
               seedsContainer.totalItemCount()
    }
    
    fun getCapacityInfo(): CapacityInfo {
        val currentSlots = getTotalSlotCount()
        val totalItems = getTotalItemCount()
        return CapacityInfo(
            currentSlots = currentSlots,
            maxSlots = MAX_INVENTORY_SIZE,
            remainingSlots = MAX_INVENTORY_SIZE - currentSlots,
            isFull = currentSlots >= MAX_INVENTORY_SIZE,
            totalItemCount = totalItems,
            utilizationRate = if (MAX_INVENTORY_SIZE > 0) currentSlots.toFloat() / MAX_INVENTORY_SIZE else 0f
        )
    }
    
    fun hasItem(itemType: String, itemId: String): Boolean = when (itemType) {
        "equipment" -> equipmentContainer.hasItem(itemId)
        "manual" -> manualsContainer.hasItem(itemId)
        "pill" -> pillsContainer.hasItem(itemId)
        "material" -> materialsContainer.hasItem(itemId)
        "herb" -> herbsContainer.hasItem(itemId)
        "seed" -> seedsContainer.hasItem(itemId)
        else -> false
    }
    
    fun getItemQuantity(itemType: String, itemId: String): Int = when (itemType) {
        "equipment" -> if (equipmentContainer.hasItem(itemId)) 1 else 0
        "manual" -> manualsContainer.getQuantity(itemId)
        "pill" -> pillsContainer.getQuantity(itemId)
        "material" -> materialsContainer.getQuantity(itemId)
        "herb" -> herbsContainer.getQuantity(itemId)
        "seed" -> seedsContainer.getQuantity(itemId)
        else -> 0
    }
    
    fun loadInventory(
        equipment: List<Equipment>,
        manuals: List<Manual>,
        pills: List<Pill>,
        materials: List<Material>,
        herbs: List<Herb>,
        seeds: List<Seed>
    ) {
        equipmentContainer.loadItems(equipment.map { InventoryItemAdapter.EquipmentAdapter(it) })
        manualsContainer.loadItems(manuals.map { InventoryItemAdapter.ManualAdapter(it) })
        pillsContainer.loadItems(pills.map { InventoryItemAdapter.PillAdapter(it) })
        materialsContainer.loadItems(materials.map { InventoryItemAdapter.MaterialAdapter(it) })
        herbsContainer.loadItems(herbs.map { InventoryItemAdapter.HerbAdapter(it) })
        seedsContainer.loadItems(seeds.map { InventoryItemAdapter.SeedAdapter(it) })
        updateCapacityInfo()
        syncDelegates()
    }
    
    fun getEquipmentList(): List<Equipment> = equipmentContainer.getAll().map { it.unwrap() }
    fun getManualList(): List<Manual> = manualsContainer.getAll().map { it.unwrap() }
    fun getPillList(): List<Pill> = pillsContainer.getAll().map { it.unwrap() }
    fun getMaterialList(): List<Material> = materialsContainer.getAll().map { it.unwrap() }
    fun getHerbList(): List<Herb> = herbsContainer.getAll().map { it.unwrap() }
    fun getSeedList(): List<Seed> = seedsContainer.getAll().map { it.unwrap() }
    
    fun addMaterials(items: List<Material>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        val adapters = validItems.map { InventoryItemAdapter.MaterialAdapter(it) }
        val result = materialsContainer.addAll(adapters, merge, MAX_INVENTORY_SIZE)
        
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch {
                eventBus.emit(InventoryEvent.ItemsAdded(
                    adapters, 
                    result.mergedCount, 
                    result.addedCount
                ))
            }
        }
        
        return if (result.success) AddResult.SUCCESS else AddResult.FULL
    }
    
    fun addHerbs(items: List<Herb>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        val adapters = validItems.map { InventoryItemAdapter.HerbAdapter(it) }
        val result = herbsContainer.addAll(adapters, merge, MAX_INVENTORY_SIZE)
        
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch {
                eventBus.emit(InventoryEvent.ItemsAdded(
                    adapters, 
                    result.mergedCount, 
                    result.addedCount
                ))
            }
        }
        
        return if (result.success) AddResult.SUCCESS else AddResult.FULL
    }
    
    fun addSeeds(items: List<Seed>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        val adapters = validItems.map { InventoryItemAdapter.SeedAdapter(it) }
        val result = seedsContainer.addAll(adapters, merge, MAX_INVENTORY_SIZE)
        
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch {
                eventBus.emit(InventoryEvent.ItemsAdded(
                    adapters, 
                    result.mergedCount, 
                    result.addedCount
                ))
            }
        }
        
        return if (result.success) AddResult.SUCCESS else AddResult.FULL
    }
    
    fun addPills(items: List<Pill>, merge: Boolean = true): AddResult {
        if (items.isEmpty()) return AddResult.SUCCESS
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return AddResult.INVALID_QUANTITY
        
        val adapters = validItems.map { InventoryItemAdapter.PillAdapter(it) }
        val result = pillsContainer.addAll(adapters, merge, MAX_INVENTORY_SIZE)
        
        if (result.success) {
            updateCapacityInfo()
            syncDelegates()
            scope.launch {
                eventBus.emit(InventoryEvent.ItemsAdded(
                    adapters, 
                    result.mergedCount, 
                    result.addedCount
                ))
            }
        }
        
        return if (result.success) AddResult.SUCCESS else AddResult.FULL
    }
}

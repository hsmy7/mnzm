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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class InventorySystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "InventorySystem"
        const val SYSTEM_NAME = "InventorySystem"
    }
    
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
    
    fun addEquipment(item: Equipment) = StateFlowListUtils.addItem(_equipment, item)
    
    fun removeEquipment(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StateFlowListUtils.removeItemById(_equipment, id, { it.id }, quantity)
    }
    
    fun updateEquipment(id: String, transform: (Equipment) -> Equipment): Boolean =
        StateFlowListUtils.updateItemById(_equipment, id, { it.id }, transform)
    
    fun getEquipmentById(id: String): Equipment? = StateFlowListUtils.findItemById(_equipment, id, { it.id })
    
    fun addManual(item: Manual, merge: Boolean = true) {
        if (item.quantity <= 0) return
        StateFlowListUtils.globalLock.withLock {
            if (merge) {
                val existingIndex = _manuals.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity && it.type == item.type
                }
                if (existingIndex >= 0) {
                    val existing = _manuals.value[existingIndex]
                    val newQty = existing.quantity + item.quantity
                    _manuals.value = _manuals.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return@withLock
                }
            }
            _manuals.value = _manuals.value + item
        }
    }
    
    fun removeManual(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StateFlowListUtils.removeItemById(_manuals, id, { it.id }, quantity)
    }
    
    fun updateManual(id: String, transform: (Manual) -> Manual): Boolean =
        StateFlowListUtils.updateItemById(_manuals, id, { it.id }, transform)
    
    fun getManualById(id: String): Manual? = StateFlowListUtils.findItemById(_manuals, id, { it.id })
    
    fun addPill(item: Pill, merge: Boolean = true) {
        if (item.quantity <= 0) return
        StateFlowListUtils.globalLock.withLock {
            if (merge) {
                val existingIndex = _pills.value.indexOfFirst { 
                    it.name == item.name && it.rarity == item.rarity && it.category == item.category
                }
                if (existingIndex >= 0) {
                    val existing = _pills.value[existingIndex]
                    val newQty = existing.quantity + item.quantity
                    _pills.value = _pills.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) listItem.copy(quantity = newQty) else listItem
                    }
                    return@withLock
                }
            }
            _pills.value = _pills.value + item
        }
    }
    
    fun removePill(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackable(
            flow = _pills,
            id = id,
            quantity = quantity,
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { pill, qty -> pill.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun removePillByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackableByName(
            flow = _pills,
            name = name,
            rarity = rarity,
            quantity = quantity,
            getName = { it.name },
            getRarity = { it.rarity },
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { pill, qty -> pill.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun updatePill(id: String, transform: (Pill) -> Pill): Boolean =
        StateFlowListUtils.updateItemById(_pills, id, { it.id }, transform)
    
    fun getPillById(id: String): Pill? = StateFlowListUtils.findItemById(_pills, id, { it.id })
    
    fun getPillQuantity(id: String): Int = StackableItemUtils.getStackableQuantity(_pills, id, { it.id }, { it.quantity })
    
    fun hasPill(name: String, rarity: Int, quantity: Int = 1): Boolean =
        StackableItemUtils.hasStackable(_pills, name, rarity, quantity, { it.name }, { it.rarity }, { it.quantity })
    
    fun addMaterial(item: Material, merge: Boolean = true) {
        StackableItemUtils.addStackable(
            flow = _materials,
            item = item,
            merge = merge,
            matchPredicate = { existing, new -> 
                existing.name == new.name && existing.rarity == new.rarity && existing.category == new.category 
            },
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { material, qty -> material.copy(quantity = qty) }
        )
    }
    
    fun removeMaterial(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackable(
            flow = _materials,
            id = id,
            quantity = quantity,
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { material, qty -> material.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun removeMaterialByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackableByName(
            flow = _materials,
            name = name,
            rarity = rarity,
            quantity = quantity,
            getName = { it.name },
            getRarity = { it.rarity },
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { material, qty -> material.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun updateMaterial(id: String, transform: (Material) -> Material): Boolean =
        StateFlowListUtils.updateItemById(_materials, id, { it.id }, transform)
    
    fun getMaterialById(id: String): Material? = StateFlowListUtils.findItemById(_materials, id, { it.id })
    
    fun getMaterialQuantity(id: String): Int = StackableItemUtils.getStackableQuantity(_materials, id, { it.id }, { it.quantity })
    
    fun hasMaterial(name: String, rarity: Int, quantity: Int = 1): Boolean =
        StackableItemUtils.hasStackable(_materials, name, rarity, quantity, { it.name }, { it.rarity }, { it.quantity })
    
    fun addHerb(item: Herb, merge: Boolean = true) {
        StackableItemUtils.addStackable(
            flow = _herbs,
            item = item,
            merge = merge,
            matchPredicate = { existing, new -> 
                existing.name == new.name && existing.rarity == new.rarity && existing.category == new.category 
            },
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { herb, qty -> herb.copy(quantity = qty) }
        )
    }
    
    fun removeHerb(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackable(
            flow = _herbs,
            id = id,
            quantity = quantity,
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { herb, qty -> herb.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun removeHerbByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackableByName(
            flow = _herbs,
            name = name,
            rarity = rarity,
            quantity = quantity,
            getName = { it.name },
            getRarity = { it.rarity },
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { herb, qty -> herb.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun updateHerb(id: String, transform: (Herb) -> Herb): Boolean =
        StateFlowListUtils.updateItemById(_herbs, id, { it.id }, transform)
    
    fun getHerbById(id: String): Herb? = StateFlowListUtils.findItemById(_herbs, id, { it.id })
    
    fun getHerbQuantity(id: String): Int = StackableItemUtils.getStackableQuantity(_herbs, id, { it.id }, { it.quantity })
    
    fun hasHerb(name: String, rarity: Int, quantity: Int = 1): Boolean =
        StackableItemUtils.hasStackable(_herbs, name, rarity, quantity, { it.name }, { it.rarity }, { it.quantity })
    
    fun addSeed(item: Seed, merge: Boolean = true) {
        StackableItemUtils.addStackable(
            flow = _seeds,
            item = item,
            merge = merge,
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { seed, qty -> seed.copy(quantity = qty) }
        )
    }
    
    fun removeSeed(id: String, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackable(
            flow = _seeds,
            id = id,
            quantity = quantity,
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { seed, qty -> seed.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun removeSeedByName(name: String, rarity: Int, quantity: Int = 1): Boolean {
        if (!validateQuantity(quantity, "remove quantity")) return false
        return StackableItemUtils.removeStackableByName(
            flow = _seeds,
            name = name,
            rarity = rarity,
            quantity = quantity,
            getName = { it.name },
            getRarity = { it.rarity },
            getId = { it.id },
            getQuantity = { it.quantity },
            withQuantity = { seed, qty -> seed.copy(quantity = qty) },
            logWarning = ::logWarning
        )
    }
    
    fun updateSeed(id: String, transform: (Seed) -> Seed): Boolean =
        StateFlowListUtils.updateItemById(_seeds, id, { it.id }, transform)
    
    fun getSeedById(id: String): Seed? = StateFlowListUtils.findItemById(_seeds, id, { it.id })
    
    fun getSeedQuantity(id: String): Int = StackableItemUtils.getStackableQuantity(_seeds, id, { it.id }, { it.quantity })
    
    fun hasSeed(name: String, rarity: Int, quantity: Int = 1): Boolean =
        StackableItemUtils.hasStackable(_seeds, name, rarity, quantity, { it.name }, { it.rarity }, { it.quantity })
    
    fun addMaterials(items: List<Material>, merge: Boolean = true) {
        StackableItemUtils.addStackableBatch(
            flow = _materials,
            items = items,
            merge = merge,
            matchPredicate = { existing, new -> 
                existing.name == new.name && existing.rarity == new.rarity && existing.category == new.category 
            },
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { material, qty -> material.copy(quantity = qty) }
        )
    }
    
    fun addHerbs(items: List<Herb>, merge: Boolean = true) {
        StackableItemUtils.addStackableBatch(
            flow = _herbs,
            items = items,
            merge = merge,
            matchPredicate = { existing, new -> 
                existing.name == new.name && existing.rarity == new.rarity && existing.category == new.category 
            },
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { herb, qty -> herb.copy(quantity = qty) }
        )
    }
    
    fun addSeeds(items: List<Seed>, merge: Boolean = true) {
        StackableItemUtils.addStackableBatch(
            flow = _seeds,
            items = items,
            merge = merge,
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { seed, qty -> seed.copy(quantity = qty) }
        )
    }
    
    fun addPills(items: List<Pill>, merge: Boolean = true) {
        StackableItemUtils.addStackableBatch(
            flow = _pills,
            items = items,
            merge = merge,
            matchPredicate = { existing, new -> 
                existing.name == new.name && existing.rarity == new.rarity && existing.category == new.category 
            },
            getName = { it.name },
            getRarity = { it.rarity },
            getQuantity = { it.quantity },
            withQuantity = { pill, qty -> pill.copy(quantity = qty) }
        )
    }
    
    fun getTotalItemCount(): Int {
        return _equipment.value.size + 
               _manuals.value.size + 
               _pills.value.sumOf { it.quantity } +
               _materials.value.sumOf { it.quantity } +
               _herbs.value.sumOf { it.quantity } +
               _seeds.value.sumOf { it.quantity }
    }
    
    fun hasItem(itemType: String, itemId: String): Boolean {
        return when (itemType) {
            "equipment" -> _equipment.value.any { it.id == itemId }
            "manual" -> _manuals.value.any { it.id == itemId }
            "pill" -> _pills.value.any { it.id == itemId }
            "material" -> _materials.value.any { it.id == itemId }
            "herb" -> _herbs.value.any { it.id == itemId }
            "seed" -> _seeds.value.any { it.id == itemId }
            else -> false
        }
    }
    
    fun getItemQuantity(itemType: String, itemId: String): Int {
        return when (itemType) {
            "equipment" -> _equipment.value.count { it.id == itemId }
            "manual" -> _manuals.value.count { it.id == itemId }
            "pill" -> _pills.value.find { it.id == itemId }?.quantity ?: 0
            "material" -> _materials.value.find { it.id == itemId }?.quantity ?: 0
            "herb" -> _herbs.value.find { it.id == itemId }?.quantity ?: 0
            "seed" -> _seeds.value.find { it.id == itemId }?.quantity ?: 0
            else -> 0
        }
    }
}

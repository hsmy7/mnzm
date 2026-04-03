package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.engine.system.inventory.InventorySystemV2
import com.xianxia.sect.core.engine.system.inventory.InventoryLockManager
import com.xianxia.sect.core.engine.system.inventory.InventoryItem
import com.xianxia.sect.core.engine.system.inventory.InventoryItemAdapter
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.Seed

data class TransactionResult(
    val success: Boolean,
    val changes: List<TransactionChange> = emptyList(),
    val error: TransactionError? = null,
    val rollbackFailures: List<RollbackFailure> = emptyList()
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

data class RollbackFailure(
    val operationIndex: Int,
    val error: Exception
)

sealed class TransactionChange {
    data class ItemAdded(val item: InventoryItem, val quantity: Int) : TransactionChange()
    data class ItemRemoved(val item: InventoryItem, val quantity: Int) : TransactionChange()
    data class ItemUpdated(val itemId: String, val oldQuantity: Int, val newQuantity: Int) : TransactionChange()
}

enum class TransactionError {
    INSUFFICIENT_ITEMS,
    INVENTORY_FULL,
    ITEM_LOCKED,
    INVALID_OPERATION,
    UNKNOWN
}

class TransactionException(val error: TransactionError, message: String) : Exception(message)

class InventoryTransaction private constructor(
    private val inventory: InventorySystemV2,
    private val lockManager: InventoryLockManager
) {
    private val operations = mutableListOf<() -> TransactionChange>()
    private val rollbackOps = mutableListOf<() -> Unit>()
    private var executed = false
    
    companion object {
        fun begin(inventory: InventorySystemV2, lockManager: InventoryLockManager): InventoryTransaction {
            return InventoryTransaction(inventory, lockManager)
        }
    }
    
    fun addEquipment(item: Equipment): InventoryTransaction {
        operations.add {
            val adapter = InventoryItemAdapter.EquipmentAdapter(item)
            val result = inventory.addEquipment(item)
            if (result == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS) {
                TransactionChange.ItemAdded(adapter, 1)
            } else {
                throw TransactionException(TransactionError.INVENTORY_FULL, "Cannot add equipment")
            }
        }
        rollbackOps.add {
            inventory.removeEquipment(item.id)
        }
        return this
    }
    
    fun addPill(item: Pill): InventoryTransaction {
        operations.add {
            val adapter = InventoryItemAdapter.PillAdapter(item)
            val result = inventory.addPill(item)
            if (result == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS) {
                TransactionChange.ItemAdded(adapter, item.quantity)
            } else {
                throw TransactionException(TransactionError.INVENTORY_FULL, "Cannot add pill")
            }
        }
        rollbackOps.add {
            inventory.removePill(item.id, item.quantity)
        }
        return this
    }
    
    fun addMaterial(item: Material): InventoryTransaction {
        operations.add {
            val adapter = InventoryItemAdapter.MaterialAdapter(item)
            val result = inventory.addMaterial(item)
            if (result == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS) {
                TransactionChange.ItemAdded(adapter, item.quantity)
            } else {
                throw TransactionException(TransactionError.INVENTORY_FULL, "Cannot add material")
            }
        }
        rollbackOps.add {
            inventory.removeMaterial(item.id, item.quantity)
        }
        return this
    }
    
    fun addHerb(item: Herb): InventoryTransaction {
        operations.add {
            val adapter = InventoryItemAdapter.HerbAdapter(item)
            val result = inventory.addHerb(item)
            if (result == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS) {
                TransactionChange.ItemAdded(adapter, item.quantity)
            } else {
                throw TransactionException(TransactionError.INVENTORY_FULL, "Cannot add herb")
            }
        }
        rollbackOps.add {
            inventory.removeHerb(item.id, item.quantity)
        }
        return this
    }
    
    fun addSeed(item: Seed): InventoryTransaction {
        operations.add {
            val adapter = InventoryItemAdapter.SeedAdapter(item)
            val result = inventory.addSeed(item)
            if (result == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS) {
                TransactionChange.ItemAdded(adapter, item.quantity)
            } else {
                throw TransactionException(TransactionError.INVENTORY_FULL, "Cannot add seed")
            }
        }
        rollbackOps.add {
            inventory.removeSeed(item.id, item.quantity)
        }
        return this
    }
    
    fun removeEquipment(itemId: String): InventoryTransaction {
        var removedItem: Equipment? = null
        operations.add {
            val item = inventory.getEquipmentById(itemId)
                ?: throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Equipment not found")
            removedItem = item
            val success = inventory.removeEquipment(itemId)
            if (success) {
                val adapter = InventoryItemAdapter.EquipmentAdapter(item)
                TransactionChange.ItemRemoved(adapter, 1)
            } else {
                throw TransactionException(TransactionError.ITEM_LOCKED, "Cannot remove equipment")
            }
        }
        rollbackOps.add {
            removedItem?.let { inventory.addEquipment(it) }
        }
        return this
    }
    
    fun removePill(itemId: String, quantity: Int): InventoryTransaction {
        var removedItem: Pill? = null
        operations.add {
            val item = inventory.getPillById(itemId)
                ?: throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Pill not found")
            removedItem = item
            val success = inventory.removePill(itemId, quantity)
            if (success) {
                val adapter = InventoryItemAdapter.PillAdapter(item)
                TransactionChange.ItemRemoved(adapter, quantity)
            } else {
                throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Cannot remove pill")
            }
        }
        rollbackOps.add {
            removedItem?.let { inventory.addPill(it) }
        }
        return this
    }
    
    fun removeMaterial(itemId: String, quantity: Int): InventoryTransaction {
        var removedItem: Material? = null
        operations.add {
            val item = inventory.getMaterialById(itemId)
                ?: throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Material not found")
            removedItem = item
            val success = inventory.removeMaterial(itemId, quantity)
            if (success) {
                val adapter = InventoryItemAdapter.MaterialAdapter(item)
                TransactionChange.ItemRemoved(adapter, quantity)
            } else {
                throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Cannot remove material")
            }
        }
        rollbackOps.add {
            removedItem?.let { inventory.addMaterial(it) }
        }
        return this
    }
    
    fun removeHerb(itemId: String, quantity: Int): InventoryTransaction {
        var removedItem: Herb? = null
        operations.add {
            val item = inventory.getHerbById(itemId)
                ?: throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Herb not found")
            removedItem = item
            val success = inventory.removeHerb(itemId, quantity)
            if (success) {
                val adapter = InventoryItemAdapter.HerbAdapter(item)
                TransactionChange.ItemRemoved(adapter, quantity)
            } else {
                throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Cannot remove herb")
            }
        }
        rollbackOps.add {
            removedItem?.let { inventory.addHerb(it) }
        }
        return this
    }
    
    fun removeSeed(itemId: String, quantity: Int): InventoryTransaction {
        var removedItem: Seed? = null
        operations.add {
            val item = inventory.getSeedById(itemId)
                ?: throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Seed not found")
            removedItem = item
            val success = inventory.removeSeed(itemId, quantity)
            if (success) {
                val adapter = InventoryItemAdapter.SeedAdapter(item)
                TransactionChange.ItemRemoved(adapter, quantity)
            } else {
                throw TransactionException(TransactionError.INSUFFICIENT_ITEMS, "Cannot remove seed")
            }
        }
        rollbackOps.add {
            removedItem?.let { inventory.addSeed(it) }
        }
        return this
    }
    
    fun commit(): TransactionResult {
        if (executed) {
            return TransactionResult(false, error = TransactionError.INVALID_OPERATION)
        }
        
        return lockManager.withWriteLock {
            val changes = mutableListOf<TransactionChange>()
            try {
                operations.forEach { op ->
                    changes.add(op())
                }
                executed = true
                TransactionResult(success = true, changes = changes)
            } catch (e: TransactionException) {
                val failures = rollback()
                TransactionResult(
                    success = false, 
                    changes = changes, 
                    error = e.error,
                    rollbackFailures = failures
                )
            } catch (e: Exception) {
                val failures = rollback()
                TransactionResult(
                    success = false, 
                    changes = changes, 
                    error = TransactionError.UNKNOWN,
                    rollbackFailures = failures
                )
            }
        }
    }
    
    fun rollback(): List<RollbackFailure> {
        val failures = mutableListOf<RollbackFailure>()
        if (!executed) {
            rollbackOps.reversed().forEachIndexed { index, op ->
                try {
                    op()
                } catch (e: Exception) {
                    failures.add(RollbackFailure(rollbackOps.size - 1 - index, e))
                }
            }
        }
        operations.clear()
        rollbackOps.clear()
        return failures
    }
}

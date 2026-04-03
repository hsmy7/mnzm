package com.xianxia.sect.core.transaction

import android.util.Log
import com.xianxia.sect.core.engine.system.inventory.InventorySystemV2
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.warehouse.OptimizedWarehouseManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ItemTransactionManager @Inject constructor(
    private val inventorySystem: InventorySystemV2
) {
    private val activeTransactions = ConcurrentHashMap<String, TransactionSnapshot>()
    private val transactionMutexes = ConcurrentHashMap<String, Mutex>()
    private val itemLocks = ConcurrentHashMap<String, Mutex>()
    private val globalMutex = Mutex()
    
    companion object {
        private const val TAG = "ItemTransactionManager"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val MAX_RETRY_ATTEMPTS = 3
    }
    
    suspend fun <T> executeInTransaction(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        operation: String = "unknown",
        block: suspend TransactionContext.() -> T
    ): TransactionResult<T> {
        val snapshot = TransactionSnapshot()
        val context = TransactionContext(snapshot, this)
        
        activeTransactions[snapshot.id] = snapshot
        
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                block(context)
            }
            
            if (result != null) {
                context.commit()
                TransactionResult.Success(result, snapshot.copy(state = TransactionState.COMMITTED))
            } else {
                context.rollback()
                TransactionResult.Failed(
                    reason = "Transaction timeout after ${timeoutMs}ms",
                    code = TransactionResult.ErrorCode.LOCK_TIMEOUT,
                    rollbackData = RollbackData(snapshot.operations, "Timeout")
                )
            }
        } catch (e: TransactionException) {
            Log.w(TAG, "Transaction failed: ${e.message}", e)
            context.rollback()
            TransactionResult.Failed(
                reason = e.message ?: "Unknown transaction error",
                code = e.code,
                rollbackData = RollbackData(snapshot.operations, e.message ?: "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Transaction error: ${e.message}", e)
            context.rollback()
            TransactionResult.Failed(
                reason = e.message ?: "Unknown error",
                code = TransactionResult.ErrorCode.INTERNAL_ERROR,
                rollbackData = RollbackData(snapshot.operations, e.message ?: "")
            )
        } finally {
            activeTransactions.remove(snapshot.id)
        }
    }
    
    suspend fun transferToWarehouse(
        sourceItemType: String,
        sourceItemId: String,
        quantity: Int,
        targetWarehouse: SectWarehouse,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): TransactionResult<SectWarehouse> {
        if (quantity <= 0) {
            return TransactionResult.Failed(
                "Invalid quantity: $quantity",
                TransactionResult.ErrorCode.INTERNAL_ERROR
            )
        }
        
        return executeInTransaction(timeoutMs, "transferToWarehouse") {
            recordOperation(OperationType.TRANSFER, sourceItemType, sourceItemId, quantity)
            
            val currentQty = inventorySystem.getItemQuantity(sourceItemType, sourceItemId)
            if (currentQty < quantity) {
                throw TransactionException(
                    "Insufficient quantity: have $currentQty, need $quantity",
                    TransactionResult.ErrorCode.INSUFFICIENT_QUANTITY
                )
            }
            
            val item = findItemInInventory(sourceItemType, sourceItemId)
            if (item == null) {
                throw TransactionException(
                    "Item not found: $sourceItemId",
                    TransactionResult.ErrorCode.ITEM_NOT_FOUND
                )
            }
            
            val removeSuccess = removeItemFromInventory(sourceItemType, sourceItemId, quantity)
            if (!removeSuccess) {
                throw TransactionException(
                    "Failed to remove item from inventory",
                    TransactionResult.ErrorCode.INTERNAL_ERROR
                )
            }
            
            val warehouseItem = createWarehouseItem(item, quantity)
            val newWarehouse = OptimizedWarehouseManager.addItem(targetWarehouse, warehouseItem)
            
            newWarehouse
        }
    }
    
    suspend fun transferFromWarehouse(
        sourceWarehouse: SectWarehouse,
        itemId: String,
        quantity: Int,
        targetItemType: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): TransactionResult<SectWarehouse> {
        if (quantity <= 0) {
            return TransactionResult.Failed(
                "Invalid quantity: $quantity",
                TransactionResult.ErrorCode.INTERNAL_ERROR
            )
        }
        
        return executeInTransaction(timeoutMs, "transferFromWarehouse") {
            recordOperation(OperationType.TRANSFER, targetItemType, itemId, quantity)
            
            val item = OptimizedWarehouseManager.findByItemId(sourceWarehouse, itemId)
                ?: throw TransactionException(
                    "Item not found in warehouse: $itemId",
                    TransactionResult.ErrorCode.ITEM_NOT_FOUND
                )
            
            if (item.quantity < quantity) {
                throw TransactionException(
                    "Insufficient quantity in warehouse: have ${item.quantity}, need $quantity",
                    TransactionResult.ErrorCode.INSUFFICIENT_QUANTITY
                )
            }
            
            if (!inventorySystem.canAddItems(1)) {
                throw TransactionException(
                    "Inventory is full",
                    TransactionResult.ErrorCode.TARGET_FULL
                )
            }
            
            val newWarehouse = OptimizedWarehouseManager.removeItem(sourceWarehouse, itemId, quantity)
            
            val addSuccess = addItemToInventoryByType(targetItemType, item, quantity)
            if (!addSuccess) {
                throw TransactionException(
                    "Failed to add item to inventory",
                    TransactionResult.ErrorCode.INTERNAL_ERROR
                )
            }
            
            newWarehouse
        }
    }
    
    suspend fun batchTransfer(
        transfers: List<TransferRequest>,
        initialWarehouse: SectWarehouse,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): TransactionResult<BatchTransferResult> {
        if (transfers.isEmpty()) {
            return TransactionResult.Success(
                BatchTransferResult(emptyList(), initialWarehouse),
                TransactionSnapshot()
            )
        }
        
        return executeInTransaction(timeoutMs, "batchTransfer") {
            val results = mutableListOf<TransferResult>()
            var currentWarehouse = initialWarehouse
            
            for (transfer in transfers) {
                val result = when (transfer.direction) {
                    TransferDirection.TO_WAREHOUSE -> {
                        val r = transferToWarehouse(
                            transfer.itemType, transfer.itemId, transfer.quantity,
                            currentWarehouse, timeoutMs / transfers.size
                        )
                        when (r) {
                            is TransactionResult.Success -> {
                                currentWarehouse = r.data
                                TransferResult.Success(transfer.itemId, transfer.quantity)
                            }
                            is TransactionResult.Failed -> TransferResult.Failed(transfer.itemId, r.reason)
                            is TransactionResult.PartialSuccess -> TransferResult.Success(transfer.itemId, transfer.quantity)
                        }
                    }
                    TransferDirection.FROM_WAREHOUSE -> {
                        val r = transferFromWarehouse(
                            currentWarehouse, transfer.itemId, transfer.quantity,
                            transfer.itemType, timeoutMs / transfers.size
                        )
                        when (r) {
                            is TransactionResult.Success -> {
                                currentWarehouse = r.data
                                TransferResult.Success(transfer.itemId, transfer.quantity)
                            }
                            is TransactionResult.Failed -> TransferResult.Failed(transfer.itemId, r.reason)
                            is TransactionResult.PartialSuccess -> TransferResult.Success(transfer.itemId, transfer.quantity)
                        }
                    }
                }
                results.add(result)
            }
            
            BatchTransferResult(results, currentWarehouse)
        }
    }
    
    suspend fun addItemWithTransaction(
        itemType: String,
        itemId: String,
        quantity: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): TransactionResult<Boolean> {
        return executeInTransaction(timeoutMs, "addItem") {
            recordOperation(OperationType.ADD, itemType, itemId, quantity)
            
            if (!inventorySystem.canAddItems(1)) {
                throw TransactionException(
                    "Inventory is full",
                    TransactionResult.ErrorCode.TARGET_FULL
                )
            }
            
            true
        }
    }
    
    suspend fun removeItemWithTransaction(
        itemType: String,
        itemId: String,
        quantity: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): TransactionResult<Boolean> {
        return executeInTransaction(timeoutMs, "removeItem") {
            recordOperation(OperationType.REMOVE, itemType, itemId, quantity)
            
            val currentQty = inventorySystem.getItemQuantity(itemType, itemId)
            if (currentQty < quantity) {
                throw TransactionException(
                    "Insufficient quantity: have $currentQty, need $quantity",
                    TransactionResult.ErrorCode.INSUFFICIENT_QUANTITY
                )
            }
            
            val success = removeItemFromInventory(itemType, itemId, quantity)
            if (!success) {
                throw TransactionException(
                    "Failed to remove item",
                    TransactionResult.ErrorCode.INTERNAL_ERROR
                )
            }
            
            true
        }
    }
    
    private val currentTransactionId = ThreadLocal<String?>()
    
    internal fun setCurrentTransaction(id: String) {
        currentTransactionId.set(id)
    }
    
    internal fun clearCurrentTransaction() {
        currentTransactionId.remove()
    }
    
    internal fun recordOperation(
        type: OperationType,
        itemType: String,
        itemId: String,
        quantity: Int,
        previousState: ItemState? = null
    ) {
        val transactionId = currentTransactionId.get() ?: return
        val currentSnapshot = activeTransactions[transactionId] ?: return
        val newOperation = OperationRecord(type, itemType, itemId, quantity, previousState)
        activeTransactions[transactionId] = currentSnapshot.copy(
            operations = currentSnapshot.operations + newOperation
        )
    }
    
    private suspend fun removeItemFromInventory(itemType: String, itemId: String, quantity: Int): Boolean {
        return when (itemType.lowercase()) {
            "equipment" -> inventorySystem.removeEquipment(itemId)
            "manual" -> inventorySystem.removeManual(itemId, quantity)
            "pill" -> inventorySystem.removePill(itemId, quantity)
            "material" -> inventorySystem.removeMaterial(itemId, quantity)
            "herb" -> inventorySystem.removeHerb(itemId, quantity)
            "seed" -> inventorySystem.removeSeed(itemId, quantity)
            else -> false
        }
    }
    
    private suspend fun addItemToInventoryByType(itemType: String, item: WarehouseItem, quantity: Int): Boolean {
        return when (itemType.lowercase()) {
            "equipment" -> {
                val equipment = com.xianxia.sect.core.model.Equipment(
                    id = item.itemId,
                    name = item.itemName,
                    rarity = item.rarity
                )
                inventorySystem.addEquipment(equipment) == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS
            }
            "manual" -> {
                val manual = com.xianxia.sect.core.model.Manual(
                    id = item.itemId,
                    name = item.itemName,
                    rarity = item.rarity,
                    quantity = quantity
                )
                inventorySystem.addManual(manual) == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS
            }
            "pill" -> {
                val pill = com.xianxia.sect.core.model.Pill(
                    id = item.itemId,
                    name = item.itemName,
                    rarity = item.rarity,
                    quantity = quantity
                )
                inventorySystem.addPill(pill) == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS
            }
            "material" -> {
                val material = com.xianxia.sect.core.model.Material(
                    id = item.itemId,
                    name = item.itemName,
                    rarity = item.rarity,
                    quantity = quantity
                )
                inventorySystem.addMaterial(material) == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS
            }
            "herb" -> {
                val herb = com.xianxia.sect.core.model.Herb(
                    id = item.itemId,
                    name = item.itemName,
                    rarity = item.rarity,
                    quantity = quantity
                )
                inventorySystem.addHerb(herb) == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS
            }
            "seed" -> {
                val seed = com.xianxia.sect.core.model.Seed(
                    id = item.itemId,
                    name = item.itemName,
                    rarity = item.rarity,
                    quantity = quantity
                )
                inventorySystem.addSeed(seed) == com.xianxia.sect.core.engine.system.inventory.AddResult.SUCCESS
            }
            else -> false
        }
    }
    
    private fun findItemInInventory(itemType: String, itemId: String): WarehouseItem? {
        return when (itemType.lowercase()) {
            "equipment" -> inventorySystem.getEquipmentById(itemId)?.let {
                WarehouseItem(it.id, it.name, "equipment", it.rarity, 1)
            }
            "manual" -> inventorySystem.getManualById(itemId)?.let {
                WarehouseItem(it.id, it.name, "manual", it.rarity, it.quantity)
            }
            "pill" -> inventorySystem.getPillById(itemId)?.let {
                WarehouseItem(it.id, it.name, "pill", it.rarity, it.quantity)
            }
            "material" -> inventorySystem.getMaterialById(itemId)?.let {
                WarehouseItem(it.id, it.name, "material", it.rarity, it.quantity)
            }
            "herb" -> inventorySystem.getHerbById(itemId)?.let {
                WarehouseItem(it.id, it.name, "herb", it.rarity, it.quantity)
            }
            "seed" -> inventorySystem.getSeedById(itemId)?.let {
                WarehouseItem(it.id, it.name, "seed", it.rarity, it.quantity)
            }
            else -> null
        }
    }
    
    private fun createWarehouseItem(item: WarehouseItem, quantity: Int): WarehouseItem {
        return item.copy(quantity = quantity)
    }
    
    internal fun getActiveTransactionCount(): Int = activeTransactions.size
    
    internal fun clearCompletedTransactions() {
        activeTransactions.entries.removeIf { 
            it.value.state == TransactionState.COMMITTED || 
            it.value.state == TransactionState.ROLLED_BACK ||
            it.value.state == TransactionState.FAILED
        }
    }
    
    fun getTransactionStats(): TransactionStats {
        val active = activeTransactions.values
        return TransactionStats(
            activeCount = active.size,
            pendingCount = active.count { it.state == TransactionState.PENDING },
            committedCount = active.count { it.state == TransactionState.COMMITTED },
            rolledBackCount = active.count { it.state == TransactionState.ROLLED_BACK },
            failedCount = active.count { it.state == TransactionState.FAILED }
        )
    }
}

class TransactionContext(
    internal val snapshot: TransactionSnapshot,
    private val manager: ItemTransactionManager
) {
    private var committed = false
    private var rolledBack = false
    
    internal fun recordOperation(
        type: OperationType,
        itemType: String,
        itemId: String,
        quantity: Int,
        previousState: ItemState? = null
    ) {
        if (committed || rolledBack) return
        manager.recordOperation(type, itemType, itemId, quantity, previousState)
    }
    
    internal suspend fun commit() {
        if (committed || rolledBack) return
        committed = true
        manager.clearCompletedTransactions()
        Log.d("TransactionContext", "Transaction ${snapshot.id} committed")
    }
    
    internal suspend fun rollback() {
        if (committed || rolledBack) return
        rolledBack = true
        Log.d("TransactionContext", "Transaction ${snapshot.id} rolled back")
    }
    
    val isActive: Boolean get() = !committed && !rolledBack
}

data class TransferRequest(
    val direction: TransferDirection,
    val itemType: String,
    val itemId: String,
    val quantity: Int
)

enum class TransferDirection { TO_WAREHOUSE, FROM_WAREHOUSE }

sealed class TransferResult {
    data class Success(val itemId: String, val quantity: Int) : TransferResult()
    data class Failed(val itemId: String, val reason: String) : TransferResult()
}

data class BatchTransferResult(
    val results: List<TransferResult>,
    val finalWarehouse: SectWarehouse?
)

data class TransactionStats(
    val activeCount: Int,
    val pendingCount: Int,
    val committedCount: Int,
    val rolledBackCount: Int,
    val failedCount: Int
)

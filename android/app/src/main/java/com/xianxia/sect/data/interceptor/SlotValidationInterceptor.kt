package com.xianxia.sect.data.interceptor

import android.util.Log
import com.xianxia.sect.data.concurrent.SlotLockManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class SlotValidationResult(
    val isValid: Boolean,
    val slot: Int,
    val error: SlotValidationError? = null
)

sealed class SlotValidationError {
    data class InvalidSlotNumber(val slot: Int, val maxSlots: Int) : SlotValidationError()
    data class SlotNotActive(val slot: Int, val activeSlot: Int) : SlotValidationError()
    data class SlotLocked(val slot: Int) : SlotValidationError()
    data class SlotCorrupted(val slot: Int) : SlotValidationError()
    data class CrossSlotOperation(val fromSlot: Int, val toSlot: Int) : SlotValidationError()
    data class ReservedSlot(val slot: Int) : SlotValidationError()
}

data class SlotValidationConfig(
    val enforceActiveSlot: Boolean = true,
    val allowCrossSlotRead: Boolean = true,
    val allowCrossSlotWrite: Boolean = false,
    val validateBeforeOperation: Boolean = true,
    val logViolations: Boolean = true,
    val throwOnViolation: Boolean = false
)

data class SlotValidationStats(
    val totalValidations: Long = 0,
    val passedValidations: Long = 0,
    val failedValidations: Long = 0,
    val violationsByType: Map<String, Long> = emptyMap(),
    val lastViolationTime: Long = 0,
    val lastViolationType: String? = null
)

class SlotValidationInterceptor(
    private val lockManager: SlotLockManager,
    private val config: SlotValidationConfig = SlotValidationConfig()
) {
    companion object {
        private const val TAG = "SlotValidation"
        const val AUTO_SAVE_SLOT = 0
        const val EMERGENCY_SLOT = -1
        const val MIN_USER_SLOT = 1
    }
    
    private val maxSlots = lockManager.getMaxSlots()
    private val activeSlot = MutableStateFlow(1)
    private val slotStates = ConcurrentHashMap<Int, SlotState>()
    
    private val totalValidations = AtomicLong(0)
    private val passedValidations = AtomicLong(0)
    private val failedValidations = AtomicLong(0)
    private val violationCounts = ConcurrentHashMap<String, AtomicLong>()
    
    private val stateLock = Any()
    
    private val _stats = MutableStateFlow(SlotValidationStats())
    val stats: StateFlow<SlotValidationStats> = _stats.asStateFlow()
    
    init {
        for (slot in MIN_USER_SLOT..maxSlots) {
            slotStates[slot] = SlotState(
                slot = slot,
                isActive = slot == 1,
                isLocked = false,
                isCorrupted = false,
                lastAccessTime = 0L,
                accessCount = 0L
            )
        }
    }
    
    fun setActiveSlot(slot: Int): SlotValidationResult {
        val validation = validateSlot(slot, OperationType.SWITCH)
        if (!validation.isValid) {
            return validation
        }
        
        synchronized(stateLock) {
            val previousSlot = activeSlot.value
            activeSlot.value = slot
            
            slotStates[previousSlot]?.let { state ->
                slotStates[previousSlot] = state.copy(isActive = false)
            }
            slotStates[slot]?.let { state ->
                slotStates[slot] = state.copy(isActive = true)
            }
            
            Log.i(TAG, "Active slot changed from $previousSlot to $slot")
        }
        return SlotValidationResult(true, slot)
    }
    
    fun getActiveSlot(): Int = activeSlot.value
    
    fun validateForSave(slot: Int): SlotValidationResult {
        return validateSlot(slot, OperationType.SAVE)
    }
    
    fun validateForLoad(slot: Int): SlotValidationResult {
        return validateSlot(slot, OperationType.LOAD)
    }
    
    fun validateForDelete(slot: Int): SlotValidationResult {
        return validateSlot(slot, OperationType.DELETE)
    }
    
    fun validateForBackup(slot: Int): SlotValidationResult {
        return validateSlot(slot, OperationType.BACKUP)
    }
    
    fun validateForRestore(slot: Int): SlotValidationResult {
        return validateSlot(slot, OperationType.RESTORE)
    }
    
    fun validateSlot(slot: Int, operation: OperationType): SlotValidationResult {
        totalValidations.incrementAndGet()
        
        val errors = mutableListOf<SlotValidationError>()
        
        if (!isValidSlotNumber(slot)) {
            val error = SlotValidationError.InvalidSlotNumber(slot, maxSlots)
            errors.add(error)
            recordViolation(error)
            
            if (config.logViolations) {
                Log.e(TAG, "Invalid slot number: $slot (max: $maxSlots), operation: $operation")
            }
            
            if (config.throwOnViolation) {
                throw SlotValidationException(error)
            }
            
            failedValidations.incrementAndGet()
            updateStats(error)
            return SlotValidationResult(false, slot, error)
        }
        
        if (config.enforceActiveSlot && !isReservedSlot(slot)) {
            val currentActive = activeSlot.value
            if (slot != currentActive) {
                val isWriteOp = operation in listOf(
                    OperationType.SAVE, 
                    OperationType.DELETE, 
                    OperationType.RESTORE
                )
                
                if (isWriteOp && !config.allowCrossSlotWrite) {
                    val error = SlotValidationError.SlotNotActive(slot, currentActive)
                    errors.add(error)
                    recordViolation(error)
                    
                    if (config.logViolations) {
                        Log.w(TAG, "Slot $slot is not active (active: $currentActive), operation: $operation")
                    }
                    
                    if (config.throwOnViolation) {
                        throw SlotValidationException(error)
                    }
                    
                    failedValidations.incrementAndGet()
                    updateStats(error)
                    return SlotValidationResult(false, slot, error)
                }
                
                if (!isWriteOp && !config.allowCrossSlotRead) {
                    val error = SlotValidationError.SlotNotActive(slot, currentActive)
                    errors.add(error)
                    recordViolation(error)
                    
                    if (config.logViolations) {
                        Log.w(TAG, "Slot $slot is not active (active: $currentActive), operation: $operation")
                    }
                    
                    if (config.throwOnViolation) {
                        throw SlotValidationException(error)
                    }
                    
                    failedValidations.incrementAndGet()
                    updateStats(error)
                    return SlotValidationResult(false, slot, error)
                }
            }
        }
        
        if (isReservedSlot(slot)) {
            val error = SlotValidationError.ReservedSlot(slot)
            if (config.logViolations) {
                Log.w(TAG, "Access to reserved slot: $slot, operation: $operation")
            }
        }
        
        val state = slotStates[slot]
        if (state != null) {
            if (state.isCorrupted && operation != OperationType.RESTORE) {
                val error = SlotValidationError.SlotCorrupted(slot)
                errors.add(error)
                recordViolation(error)
                
                if (config.logViolations) {
                    Log.e(TAG, "Slot $slot is marked as corrupted, operation: $operation")
                }
                
                if (config.throwOnViolation) {
                    throw SlotValidationException(error)
                }
                
                failedValidations.incrementAndGet()
                updateStats(error)
                return SlotValidationResult(false, slot, error)
            }
            
            slotStates[slot] = state.copy(
                lastAccessTime = System.currentTimeMillis(),
                accessCount = state.accessCount + 1
            )
        }
        
        passedValidations.incrementAndGet()
        updateStats()
        return SlotValidationResult(true, slot)
    }
    
    fun markSlotCorrupted(slot: Int) {
        slotStates[slot]?.let { state ->
            slotStates[slot] = state.copy(isCorrupted = true)
            Log.w(TAG, "Slot $slot marked as corrupted")
        }
    }
    
    fun clearSlotCorruption(slot: Int) {
        slotStates[slot]?.let { state ->
            slotStates[slot] = state.copy(isCorrupted = false)
            Log.i(TAG, "Slot $slot corruption flag cleared")
        }
    }
    
    fun isSlotCorrupted(slot: Int): Boolean {
        return slotStates[slot]?.isCorrupted ?: false
    }
    
    fun getSlotState(slot: Int): SlotState? {
        return slotStates[slot]
    }
    
    fun getAllSlotStates(): Map<Int, SlotState> {
        return slotStates.toMap()
    }
    
    private fun isValidSlotNumber(slot: Int): Boolean {
        return lockManager.isValidSlot(slot)
    }
    
    private fun isReservedSlot(slot: Int): Boolean {
        return slot == AUTO_SAVE_SLOT || slot == EMERGENCY_SLOT
    }
    
    private fun recordViolation(error: SlotValidationError) {
        val errorType = error::class.simpleName ?: "Unknown"
        violationCounts.computeIfAbsent(errorType) { AtomicLong(0) }.incrementAndGet()
    }
    
    private fun updateStats(error: SlotValidationError? = null) {
        val violationsMap = violationCounts.mapValues { it.value.get() }
        
        _stats.value = SlotValidationStats(
            totalValidations = totalValidations.get(),
            passedValidations = passedValidations.get(),
            failedValidations = failedValidations.get(),
            violationsByType = violationsMap,
            lastViolationTime = if (error != null) System.currentTimeMillis() else _stats.value.lastViolationTime,
            lastViolationType = error?.let { it::class.simpleName } ?: _stats.value.lastViolationType
        )
    }
    
    fun resetStats() {
        totalValidations.set(0)
        passedValidations.set(0)
        failedValidations.set(0)
        violationCounts.clear()
        _stats.value = SlotValidationStats()
        Log.i(TAG, "Slot validation stats reset")
    }
}

enum class OperationType {
    SAVE,
    LOAD,
    DELETE,
    BACKUP,
    RESTORE,
    SWITCH,
    VERIFY
}

data class SlotState(
    val slot: Int,
    val isActive: Boolean,
    val isLocked: Boolean,
    val isCorrupted: Boolean,
    val lastAccessTime: Long,
    val accessCount: Long
)

class SlotValidationException(
    val error: SlotValidationError,
    message: String = error.toString()
) : Exception(message) {
    override fun toString(): String = "SlotValidationException(error=$error)"
}

interface SlotValidationAware {
    fun setValidationInterceptor(interceptor: SlotValidationInterceptor)
    fun getValidationInterceptor(): SlotValidationInterceptor?
}

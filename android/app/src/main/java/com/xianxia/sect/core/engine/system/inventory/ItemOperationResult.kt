package com.xianxia.sect.core.engine.system.inventory

sealed class ItemOperationResult<out T> {
    data class Success<T>(val data: T, val metadata: OperationMetadata = OperationMetadata()) : ItemOperationResult<T>()
    data class PartialSuccess<T>(val data: T, val warning: String, val metadata: OperationMetadata = OperationMetadata()) : ItemOperationResult<T>()
    data class Failed<T>(val reason: String, val code: ErrorCode, val context: ErrorContext? = null) : ItemOperationResult<T>()
    
    enum class ErrorCode {
        ITEM_NOT_FOUND,
        INSUFFICIENT_QUANTITY,
        INVALID_QUANTITY,
        INVALID_ID,
        INVALID_NAME,
        INVALID_RARITY,
        DUPLICATE_ID,
        ITEM_LOCKED,
        CONTAINER_FULL,
        CONCURRENT_MODIFICATION,
        INTERNAL_ERROR,
        VALIDATION_FAILED
    }
    
    data class ErrorContext(
        val itemType: String? = null,
        val itemId: String? = null,
        val requestedQuantity: Int? = null,
        val availableQuantity: Int? = null,
        val additionalInfo: Map<String, Any> = emptyMap()
    )
    
    data class OperationMetadata(
        val affectedItems: Int = 0,
        val mergedCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    inline fun <R> map(transform: (T) -> R): ItemOperationResult<R> = when (this) {
        is Success -> Success(transform(data), metadata)
        is PartialSuccess -> PartialSuccess(transform(data), warning, metadata)
        is Failed -> Failed(reason, code, context)
    }
    
    inline fun <R> flatMap(transform: (T) -> ItemOperationResult<R>): ItemOperationResult<R> = when (this) {
        is Success -> transform(data)
        is PartialSuccess -> transform(data)
        is Failed -> Failed(reason, code, context)
    }
    
    inline fun onSuccess(action: (T) -> Unit): ItemOperationResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (String, ErrorCode, ErrorContext?) -> Unit): ItemOperationResult<T> {
        if (this is Failed) action(reason, code, context)
        return this
    }
    
    inline fun onPartialSuccess(action: (T, String) -> Unit): ItemOperationResult<T> {
        if (this is PartialSuccess) action(data, warning)
        return this
    }
    
    inline fun recover(transform: (Failed<@UnsafeVariance T>) -> ItemOperationResult<@UnsafeVariance T>): ItemOperationResult<T> = when (this) {
        is Failed -> transform(this)
        else -> this
    }
    
    inline fun recoverWith(default: @UnsafeVariance T): ItemOperationResult<T> = when (this) {
        is Failed -> Success(default)
        else -> this
    }
    
    val isSuccess: Boolean get() = this is Success
    val isFailed: Boolean get() = this is Failed
    val isPartialSuccess: Boolean get() = this is PartialSuccess
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is PartialSuccess -> data
        is Failed -> null
    }
    
    fun getOrElse(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is PartialSuccess -> data
        is Failed -> default
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is PartialSuccess -> data
        is Failed -> throw ItemOperationException(reason, code, context)
    }
    
    companion object {
        fun <T> success(data: T, affectedItems: Int = 1, mergedCount: Int = 0): ItemOperationResult<T> =
            Success(data, OperationMetadata(affectedItems, mergedCount))
        
        fun <T> partialSuccess(data: T, warning: String): ItemOperationResult<T> =
            PartialSuccess(data, warning)
        
        fun <T> notFound(itemType: String? = null, itemId: String? = null): ItemOperationResult<T> =
            Failed("Item not found", ErrorCode.ITEM_NOT_FOUND, ErrorContext(itemType, itemId))
        
        fun <T> insufficientQuantity(requested: Int, available: Int, itemType: String? = null, itemId: String? = null): ItemOperationResult<T> =
            Failed(
                "Insufficient quantity: requested $requested, available $available",
                ErrorCode.INSUFFICIENT_QUANTITY,
                ErrorContext(itemType, itemId, requested, available)
            )
        
        fun <T> invalidQuantity(quantity: Int): ItemOperationResult<T> =
            Failed("Invalid quantity: $quantity", ErrorCode.INVALID_QUANTITY)
        
        fun <T> containerFull(): ItemOperationResult<T> =
            Failed("Container is full", ErrorCode.CONTAINER_FULL)
        
        fun <T> itemLocked(itemType: String? = null, itemId: String? = null): ItemOperationResult<T> =
            Failed("Item is locked", ErrorCode.ITEM_LOCKED, ErrorContext(itemType, itemId))
        
        fun <T> duplicateId(itemId: String): ItemOperationResult<T> =
            Failed("Duplicate item ID: $itemId", ErrorCode.DUPLICATE_ID, ErrorContext(itemId = itemId))
        
        fun <T> validationFailed(reason: String): ItemOperationResult<T> =
            Failed(reason, ErrorCode.VALIDATION_FAILED)
    }
}

class ItemOperationException(
    message: String,
    val code: ItemOperationResult.ErrorCode,
    val context: ItemOperationResult.ErrorContext? = null
) : Exception(message) {
    override fun toString(): String = buildString {
        append("ItemOperationException: $message")
        append(" (code=$code)")
        context?.let { ctx ->
            ctx.itemType?.let { append(", itemType=$it") }
            ctx.itemId?.let { append(", itemId=$it") }
            ctx.requestedQuantity?.let { append(", requested=$it") }
            ctx.availableQuantity?.let { append(", available=$it") }
        }
    }
}

sealed class ItemValidationResult {
    data object Valid : ItemValidationResult()
    data class Invalid(val reason: String, val code: ItemOperationResult.ErrorCode) : ItemValidationResult()
    
    val isValid: Boolean get() = this is Valid
    val isInvalid: Boolean get() = this is Invalid
    
    fun toResult(): ItemOperationResult<Unit> = when (this) {
        is Valid -> ItemOperationResult.success(Unit)
        is Invalid -> ItemOperationResult.Failed(reason, code)
    }
    
    companion object {
        fun valid(): ItemValidationResult = Valid
        fun invalid(reason: String, code: ItemOperationResult.ErrorCode = ItemOperationResult.ErrorCode.VALIDATION_FAILED): ItemValidationResult =
            Invalid(reason, code)
    }
}

object ItemValidator {
    private val VALID_RARITY_RANGE = 1..6
    const val MAX_STACK_SIZE = 999
    const val MAX_INVENTORY_SIZE = 2000
    
    fun validateId(id: String): ItemValidationResult {
        if (id.isBlank()) return ItemValidationResult.invalid("ID cannot be blank", ItemOperationResult.ErrorCode.INVALID_ID)
        return ItemValidationResult.valid()
    }
    
    fun validateName(name: String): ItemValidationResult {
        if (name.isBlank()) return ItemValidationResult.invalid("Name cannot be blank", ItemOperationResult.ErrorCode.INVALID_NAME)
        return ItemValidationResult.valid()
    }
    
    fun validateRarity(rarity: Int): ItemValidationResult {
        if (rarity !in VALID_RARITY_RANGE) {
            return ItemValidationResult.invalid(
                "Rarity must be in range $VALID_RARITY_RANGE, got $rarity",
                ItemOperationResult.ErrorCode.INVALID_RARITY
            )
        }
        return ItemValidationResult.valid()
    }
    
    fun validateQuantity(quantity: Int, allowZero: Boolean = false): ItemValidationResult {
        if (quantity < 0) return ItemValidationResult.invalid("Quantity cannot be negative", ItemOperationResult.ErrorCode.INVALID_QUANTITY)
        if (!allowZero && quantity == 0) return ItemValidationResult.invalid("Quantity cannot be zero", ItemOperationResult.ErrorCode.INVALID_QUANTITY)
        if (quantity > MAX_STACK_SIZE) return ItemValidationResult.invalid("Quantity exceeds max stack size $MAX_STACK_SIZE", ItemOperationResult.ErrorCode.INVALID_QUANTITY)
        return ItemValidationResult.valid()
    }
    
    fun validateStackableItem(name: String, rarity: Int, quantity: Int): ItemValidationResult {
        return mergeResults(
            validateName(name),
            validateRarity(rarity),
            validateQuantity(quantity)
        )
    }
    
    fun validateEquipment(id: String, name: String, rarity: Int): ItemValidationResult {
        return mergeResults(
            validateId(id),
            validateName(name),
            validateRarity(rarity)
        )
    }
    
    private fun mergeResults(vararg results: ItemValidationResult): ItemValidationResult {
        val invalid = results.filterIsInstance<ItemValidationResult.Invalid>().firstOrNull()
        return invalid ?: ItemValidationResult.valid()
    }
}

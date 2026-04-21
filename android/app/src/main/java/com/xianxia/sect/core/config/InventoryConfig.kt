package com.xianxia.sect.core.config

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryConfig @Inject constructor() {
    var maxInventorySize: Int = 2000
    var maxWarehouseSize: Int = 5000
    var maxStackSize: Int = 9999
    var cacheSize: Int = 500
    var cacheTtlMs: Long = 5 * 60 * 1000L
    var transactionTimeoutMs: Long = 5000L
    var maxRetryAttempts: Int = 3
    var enableCompression: Boolean = true
    var enableObjectPool: Boolean = true
    var enableCache: Boolean = true
    var enableMonitoring: Boolean = true
    
    private val typeSpecificStackLimits = mutableMapOf<String, Int>()
    private val typeSpecificCapacityLimits = mutableMapOf<String, Int>()
    private val rarityRange = 1..6
    
    init {
        typeSpecificStackLimits["pill"] = 999
        typeSpecificStackLimits["material"] = 9999
        typeSpecificStackLimits["herb"] = 999
        typeSpecificStackLimits["seed"] = 99
        typeSpecificStackLimits["manual_stack"] = 99
        typeSpecificStackLimits["manual_instance"] = 1
        typeSpecificStackLimits["equipment_stack"] = 99
        typeSpecificStackLimits["equipment_instance"] = 1
    }
    
    fun getMaxStackSize(itemType: String): Int = typeSpecificStackLimits[itemType.lowercase()] ?: maxStackSize
    
    fun getMaxCapacity(itemType: String): Int = typeSpecificCapacityLimits[itemType.lowercase()] ?: maxInventorySize
    
    fun setMaxStackSize(itemType: String, limit: Int) {
        if (limit > 0) {
            typeSpecificStackLimits[itemType.lowercase()] = limit
        }
    }
    
    fun setMaxCapacity(itemType: String, limit: Int) {
        if (limit > 0) {
            typeSpecificCapacityLimits[itemType.lowercase()] = limit
        }
    }
    
    fun validateQuantity(itemType: String, quantity: Int): Result<Int> {
        val maxStack = getMaxStackSize(itemType)
        return when {
            quantity < 0 -> Result.failure(IllegalArgumentException("Quantity cannot be negative"))
            quantity > maxStack -> Result.failure(IllegalArgumentException("Quantity $quantity exceeds max stack size: $maxStack"))
            else -> Result.success(quantity)
        }
    }
    
    fun validateRarity(rarity: Int): Result<Int> {
        return if (rarity in rarityRange) {
            Result.success(rarity)
        } else {
            Result.failure(IllegalArgumentException("Invalid rarity: $rarity, must be in $rarityRange"))
        }
    }
    
    fun isValidRarity(rarity: Int): Boolean = rarity in rarityRange
    
    fun getRarityRange(): IntRange = rarityRange
    
    fun toMap(): Map<String, Any> = mapOf(
        "maxInventorySize" to maxInventorySize,
        "maxWarehouseSize" to maxWarehouseSize,
        "maxStackSize" to maxStackSize,
        "cacheSize" to cacheSize,
        "cacheTtlMs" to cacheTtlMs,
        "transactionTimeoutMs" to transactionTimeoutMs,
        "maxRetryAttempts" to maxRetryAttempts,
        "enableCompression" to enableCompression,
        "enableObjectPool" to enableObjectPool,
        "enableCache" to enableCache,
        "enableMonitoring" to enableMonitoring,
        "typeSpecificStackLimits" to typeSpecificStackLimits.toMap(),
        "typeSpecificCapacityLimits" to typeSpecificCapacityLimits.toMap()
    )
    
    fun fromMap(config: Map<String, Any>) {
        config["maxInventorySize"]?.let { maxInventorySize = (it as Number).toInt() }
        config["maxWarehouseSize"]?.let { maxWarehouseSize = (it as Number).toInt() }
        config["maxStackSize"]?.let { maxStackSize = (it as Number).toInt() }
        config["cacheSize"]?.let { cacheSize = (it as Number).toInt() }
        config["cacheTtlMs"]?.let { cacheTtlMs = (it as Number).toLong() }
        config["transactionTimeoutMs"]?.let { transactionTimeoutMs = (it as Number).toLong() }
        config["maxRetryAttempts"]?.let { maxRetryAttempts = (it as Number).toInt() }
        config["enableCompression"]?.let { enableCompression = it as Boolean }
        config["enableObjectPool"]?.let { enableObjectPool = it as Boolean }
        config["enableCache"]?.let { enableCache = it as Boolean }
        config["enableMonitoring"]?.let { enableMonitoring = it as Boolean }
        
        @Suppress("UNCHECKED_CAST")
        (config["typeSpecificStackLimits"] as? Map<String, Int>)?.let {
            typeSpecificStackLimits.putAll(it)
        }
        
        @Suppress("UNCHECKED_CAST")
        (config["typeSpecificCapacityLimits"] as? Map<String, Int>)?.let {
            typeSpecificCapacityLimits.putAll(it)
        }
    }
    
    fun reset() {
        maxInventorySize = 2000
        maxWarehouseSize = 5000
        maxStackSize = 9999
        cacheSize = 500
        cacheTtlMs = 5 * 60 * 1000L
        transactionTimeoutMs = 5000L
        maxRetryAttempts = 3
        enableCompression = true
        enableObjectPool = true
        enableCache = true
        enableMonitoring = true
        typeSpecificStackLimits.clear()
        typeSpecificCapacityLimits.clear()
        
        typeSpecificStackLimits["pill"] = 999
        typeSpecificStackLimits["material"] = 9999
        typeSpecificStackLimits["herb"] = 999
        typeSpecificStackLimits["seed"] = 99
        typeSpecificStackLimits["manual_stack"] = 99
        typeSpecificStackLimits["manual_instance"] = 1
        typeSpecificStackLimits["equipment_stack"] = 99
        typeSpecificStackLimits["equipment_instance"] = 1
    }
    
    companion object {
        val DEFAULT = InventoryConfig()
    }
}

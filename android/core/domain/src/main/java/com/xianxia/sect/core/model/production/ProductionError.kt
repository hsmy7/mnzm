package com.xianxia.sect.core.model.production

import androidx.room.Entity
import androidx.room.PrimaryKey

data class MaterialConsumptionLog(
    val id: String,
    val timestamp: Long,
    val slotIndex: Int,
    val recipeId: String,
    val recipeName: String,
    val materials: Map<String, Int>,
    val reason: String,
    val buildingId: String
)

@Entity(tableName = "material_consumption_logs")
data class MaterialConsumptionLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val slotIndex: Int,
    val recipeId: String,
    val recipeName: String,
    val materialsSerialized: String,
    val reason: String,
    val buildingId: String
) {
    fun toLog(): MaterialConsumptionLog = MaterialConsumptionLog(
        id = id,
        timestamp = timestamp,
        slotIndex = slotIndex,
        recipeId = recipeId,
        recipeName = recipeName,
        materials = materialsSerialized.split(",")
            .filter { it.contains(":") }
            .associate {
                val parts = it.split(":")
                parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            },
        reason = reason,
        buildingId = buildingId
    )
    
    companion object {
        fun fromLog(log: MaterialConsumptionLog): MaterialConsumptionLogEntity = MaterialConsumptionLogEntity(
            id = log.id,
            timestamp = log.timestamp,
            slotIndex = log.slotIndex,
            recipeId = log.recipeId,
            recipeName = log.recipeName,
            materialsSerialized = log.materials.entries.joinToString(",") { "${it.key}:${it.value}" },
            reason = log.reason,
            buildingId = log.buildingId
        )
    }
}

@Deprecated("Use AppError.Domain.Production", ReplaceWith("AppError.Domain.Production", "com.xianxia.sect.core.util.AppError"))
sealed class ProductionError {
    abstract val code: String
    abstract val message: String
    abstract val context: Map<String, Any>
    
    data class SlotBusy(
        override val message: String = "槽位正在工作中",
        val slotIndex: Int
    ) : ProductionError() {
        override val code: String = "PRODUCTION_SLOT_BUSY"
        override val context: Map<String, Any> = mapOf("slotIndex" to slotIndex)
    }
    
    data class InsufficientMaterials(
        override val message: String = "材料不足",
        val missingMaterials: Map<String, Int>
    ) : ProductionError() {
        override val code: String = "PRODUCTION_INSUFFICIENT_MATERIALS"
        override val context: Map<String, Any> = mapOf("missingMaterials" to missingMaterials)
    }
    
    data class InvalidSlot(
        override val message: String = "无效的槽位",
        val slotIndex: Int
    ) : ProductionError() {
        override val code: String = "PRODUCTION_INVALID_SLOT"
        override val context: Map<String, Any> = mapOf("slotIndex" to slotIndex)
    }
    
    data class RecipeNotFound(
        override val message: String = "配方不存在",
        val recipeId: String
    ) : ProductionError() {
        override val code: String = "PRODUCTION_RECIPE_NOT_FOUND"
        override val context: Map<String, Any> = mapOf("recipeId" to recipeId)
    }
    
    data class DiscipleNotAvailable(
        override val message: String = "弟子不可用",
        val discipleId: String
    ) : ProductionError() {
        override val code: String = "PRODUCTION_DISCIPLE_NOT_AVAILABLE"
        override val context: Map<String, Any> = mapOf("discipleId" to discipleId)
    }
    
    data class InvalidStateTransition(
        override val message: String = "无效的状态转换",
        val fromStatus: String,
        val toStatus: String
    ) : ProductionError() {
        override val code: String = "PRODUCTION_INVALID_STATE_TRANSITION"
        override val context: Map<String, Any> = mapOf(
            "fromStatus" to fromStatus,
            "toStatus" to toStatus
        )
    }
    
    data class ProductionFailed(
        override val message: String = "生产失败",
        val recipeName: String
    ) : ProductionError() {
        override val code: String = "PRODUCTION_FAILED"
        override val context: Map<String, Any> = mapOf("recipeName" to recipeName)
    }
    
    data class Unknown(
        override val message: String = "未知错误",
        override val context: Map<String, Any> = emptyMap()
    ) : ProductionError() {
        override val code: String = "PRODUCTION_UNKNOWN_ERROR"
    }
}

@Deprecated("Use AppError.Domain.Production via toAppError()", ReplaceWith("ProductionOperationResult", "com.xianxia.sect.core.model.production.ProductionOperationResult"))
sealed class ProductionOperationResult<out T> {
    data class Success<T>(val data: T) : ProductionOperationResult<T>()
    data class Failure(val error: ProductionError) : ProductionOperationResult<Nothing>()
    
    inline fun <R> map(transform: (T) -> R): ProductionOperationResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> ProductionOperationResult<R>): ProductionOperationResult<R> = 
        when (this) {
            is Success -> transform(data)
            is Failure -> this
        }
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun getErrorOrNull(): ProductionError? = when (this) {
        is Success -> null
        is Failure -> error
    }
    
    companion object {
        inline fun <T> catching(block: () -> T): ProductionOperationResult<T> = try {
            Success(block())
        } catch (e: Exception) {
            Failure(ProductionError.Unknown(
                message = e.message ?: "Unknown error",
                context = mapOf("exception" to (e.message ?: ""))
            ))
        }
    }
}

typealias OperationResult<T> = ProductionOperationResult<T>

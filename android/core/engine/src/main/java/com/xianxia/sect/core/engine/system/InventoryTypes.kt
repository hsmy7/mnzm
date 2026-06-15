package com.xianxia.sect.core.engine.system

/**
 * 仓库操作结果枚举
 * 从 InventorySystem.kt 提取的类型定义
 */
enum class AddResult {
    SUCCESS,
    PARTIAL_SUCCESS,
    FULL,
    INVALID_ID,
    INVALID_NAME,
    INVALID_RARITY,
    INVALID_QUANTITY,
    DUPLICATE_ID,
    ITEM_LOCKED
}

/**
 * 仓库容量信息
 */
data class CapacityInfo(
    val currentSlots: Int,
    val maxSlots: Int,
    val remainingSlots: Int,
    val isFull: Boolean
)

/**
 * 物品栈更新记录
 */
data class StackUpdate(
    val stackId: String,
    val newQuantity: Int,
    val isDeletion: Boolean = false
)

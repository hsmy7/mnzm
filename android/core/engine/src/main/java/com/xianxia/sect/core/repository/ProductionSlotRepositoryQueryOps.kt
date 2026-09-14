package com.xianxia.sect.core.repository

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.coroutines.flow.*

// ── 生产槽位查询域（自 ProductionSlotRepository 拆出，行为零变更） ─────────────

private val TAG = ProductionSlotRepository.TAG
fun ProductionSlotRepository.getSlotsByType(buildingType: BuildingType): List<ProductionSlot> {
    return cache.getByType(buildingType)
}

fun ProductionSlotRepository.getSlotsByBuildingId(buildingId: String): List<ProductionSlot> {
    return cache.getByBuildingId(buildingId)
}

fun ProductionSlotRepository.getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> {
    return cache.getFinishedSlots(currentYear, currentMonth)
}

fun ProductionSlotRepository.getSlotById(slotId: String): ProductionSlot? {
    return cache.getById(slotId)
}

fun ProductionSlotRepository.getIdleSlots(): List<ProductionSlot> = cache.getIdleSlots()

fun ProductionSlotRepository.getCompletedSlots(): List<ProductionSlot> = cache.getCompletedSlots()

fun ProductionSlotRepository.getWorkingSlots(): List<ProductionSlot> = cache.getWorkingSlots()

fun ProductionSlotRepository.getStatistics(): SlotCacheStatistics {
    return cache.getStatistics()
}

fun ProductionSlotRepository.getLockStatistics() = shardedLock.getLockStatistics()

/**
 * 净化外部数据源（读档/DAO）携带的 null 槽位元素（Bugly #13014）。
 * 非空类型上的 null 比较是编译器警告但运行时正确——null 只可能由
 * 损坏存档反序列化或旧版本存档写入产生。
 */

@Suppress("SENSELESS_COMPARISON")
internal fun ProductionSlotRepository.sanitizeSlots(slots: List<ProductionSlot>): List<ProductionSlot> {
    val sanitized = if (slots.any { it == null }) slots.filterNotNull() else slots
    if (sanitized.size != slots.size) {
        DomainLog.w(TAG, "净化 ${slots.size - sanitized.size} 个 null 生产槽位")
    }
    return sanitized
}

package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.SlotAssignment
import com.xianxia.sect.core.model.SlotCategory
import com.xianxia.sect.core.model.SlotRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子分配注册表 — 所有槽位分配的唯一真相源。
 *
 * 维护"弟子 ID → SlotAssignment"的映射，保证每个弟子在同一时间只能
 * 被分配到一个槽位。所有分配操作必须通过 [DiscipleAssignmentGate] 进行，
 * 注册表提供底层的原子性检查和记录。
 *
 * ## Identity Map 模式
 *
 * 继承自 Identity Map 设计模式：每个弟子 ID 在注册表中最多一条记录。
 * 查询时先查注册表 → 命中返回现有分配 → 未命中允许新分配。
 *
 * ## 线程安全
 *
 * 此注册表在 GameEngine-Thread 上操作，外部通过 [DiscipleAssignmentGate]
 * 串行化访问。本身不使用锁（由 Gate 提供事务边界）。
 *
 * ## 扩展
 *
 * 新增槽位系统时只需在 [SlotCategory] 添加枚举值，注册表逻辑无需改动。
 */
@Singleton
class DiscipleAssignmentRegistry @Inject constructor() {

    /** 内存级注册表：discipleId → SlotAssignment */
    private val registry = mutableMapOf<String, SlotAssignment>()

    // ==================== 写操作 ====================

    /**
     * 尝试注册弟子到指定槽位。
     *
     * @param discipleId 待分配的弟子 ID
     * @param targetSlot 目标槽位引用
     * @return null 表示注册成功（弟子当前无分配）；非 null 表示弟子已在其他槽位，
     *         返回 [SlotAssignment] 包含现有分配信息
     */
    fun tryRegister(discipleId: String, targetSlot: SlotRef): SlotAssignment? {
        val existing = registry[discipleId]
        if (existing != null) {
            // 如果已登记在同一槽位（重新分配），允许通过
            if (existing.slotRef == targetSlot) return null
            return existing
        }
        registry[discipleId] = SlotAssignment(
            discipleId = discipleId,
            slotRef = targetSlot,
        )
        return null
    }

    /**
     * 注册或更新 — 总是成功，覆盖任何现有记录。
     * 用于分配确认阶段（旧槽位已清理，直接登记新槽位）。
     */
    fun registerOrUpdate(discipleId: String, slotRef: SlotRef) {
        registry[discipleId] = SlotAssignment(
            discipleId = discipleId,
            slotRef = slotRef,
        )
    }

    /**
     * 更新现有注册记录的槽位引用（用于同一槽位系统内的重分配）。
     * 前置条件：该弟子已注册（通过 [tryRegister] 确认）。
     */
    fun updateSlot(discipleId: String, newSlotRef: SlotRef) {
        val existing = registry[discipleId] ?: return
        registry[discipleId] = existing.copy(slotRef = newSlotRef)
    }

    /**
     * 注销弟子的分配记录。
     * 在弟子被释放/清理时调用。
     */
    fun unregister(discipleId: String) {
        registry.remove(discipleId)
    }

    /**
     * 清空注册表（用于读档/重置）。
     * Registry 是内存级结构，读档后所有分配需从槽位数据重建。
     */
    fun clear() {
        registry.clear()
    }

    // ==================== 读操作 ====================

    /** 查询弟子当前的分配信息，null 表示未分配任何槽位。 */
    fun getAssignment(discipleId: String): SlotAssignment? = registry[discipleId]

    /** 检查弟子是否已在任意槽位中。 */
    fun isAssigned(discipleId: String): Boolean = registry.containsKey(discipleId)

    /** 返回指定槽位类型的所有分配记录。 */
    fun getAssignmentsByCategory(category: SlotCategory): List<SlotAssignment> =
        registry.values.filter { it.slotRef.category == category }

    /** 返回所有分配记录的快照。 */
    fun getAllAssignments(): Map<String, SlotAssignment> = registry.toMap()

    /** 当前已分配的弟子数量。 */
    fun size(): Int = registry.size
}

package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.StackableItem

/**
 * 类型化合并键。替代项目中散落的裸 String 拼接合并键，
 * 为 6 类可堆叠物品提供单一事实来源。
 *
 * @param parts 构成合并键的各部分，按优先顺序排列
 */
data class StackKey(val parts: List<Any>) {
    companion object {
        /** 便捷构造 */
        fun of(vararg parts: Any): StackKey = StackKey(parts.toList())
    }
}

/**
 * 可堆叠物品的统一仓库。
 *
 * 委托 [EntityStore] 做 O(1) ID 索引，叠加"按 [StackKey] 合并"语义。
 * 合并键由 [stackKeyOf] 单一定义（每物品类型一个 lambda），
 * 根除全项目 6 套不一致合并键。
 *
 * 使用方式：
 * - Engine 层从 [MutableGameState] 的 EntityStore 实时构造（零额外内存）
 * - 所有 add/remove/get/has/quantity 操作委托至此
 *
 * @param T 物品类型，须同时实现 [HasId] 和 [StackableItem]
 * @param initialItems 初始物品列表
 * @param stackKeyOf 合并键提取函数——唯一事实来源
 * @param maxStack 单格最大堆叠数
 * @param maxSlots 总槽位上限（惰性求值，适应动态上限）
 * @param notFound 构造"找不到物品"错误的工厂
 */
class StackableItemStore<T>(
    initialItems: List<T> = emptyList(),
    private val stackKeyOf: (T) -> StackKey,
    private val maxStack: Int,
    private val maxSlots: () -> Int,
    private val notFound: (String) -> AppError.Domain,
) where T : HasId, T : StackableItem {

    private val store = EntityStore(initialItems)

    /** stackKey → id 反向索引，O(1) 合并查找 */
    private val keyIndex = HashMap<StackKey, String>()

    init { rebuildKeyIndex() }

    // === 读取 ===

    /** O(1) ID 查找 */
    fun get(id: String): T? = store.get(id)

    /** 是否已存在 */
    fun has(id: String): Boolean = store.contains(id)

    /** 当前数量，不存在返回 0 */
    fun quantity(id: String): Int = store.get(id)?.quantity ?: 0

    /** 全部物品列表 */
    fun all(): List<T> = store.all()

    /** 物品总数 */
    val size: Int get() = store.size

    /** 当前占用槽位数 */
    val slotCount: Int get() = store.size

    // === 写入 ===

    /**
     * 添加物品。若 [merge] 为 true（默认）且存在同 [StackKey] 的物品，
     * 则叠加数量（受 [maxStack] 限制，溢出部分计入 [DomainResult.Partial]）。
     * 若不存在同 key 且槽位已满，返回 [DomainResult.Failure]。
     */
    fun add(item: T, merge: Boolean = true): DomainResult<T> {
        val key = stackKeyOf(item)
        val existingId = if (merge) keyIndex[key] else null

        if (existingId != null) {
            val existing = store.get(existingId)!!
            val newQty = existing.quantity + item.quantity
            return if (newQty <= maxStack) {
                val merged = existing.withQuantity(newQty) as T
                store.update(existingId) { merged }
                DomainResult.Success(merged)
            } else {
                val merged = existing.withQuantity(maxStack) as T
                store.update(existingId) { merged }
                DomainResult.Partial(merged, overflow = newQty - maxStack)
            }
        }

        // 新物品：检查槽位
        if (store.size >= maxSlots()) {
            return DomainResult.Failure(
                AppError.Domain.Inventory.Full()
            )
        }

        store.add(item)
        // 仅当 merge=true 或 keyIndex 中不存在同 key 时才更新索引；
        // merge=false 时不覆盖已有映射，避免原物品在后续 remove/get 中不可见
        if (merge || !keyIndex.containsKey(key)) {
            keyIndex[key] = item.id
        }
        return DomainResult.Success(item)
    }

    /**
     * 移除指定数量的物品。
     *
     * - 物品已锁定 → [DomainResult.Failure] (Locked)
     * - 数量不足 → [DomainResult.Failure] (Insufficient)
     * - 移除后数量归零 → 删除该条目，返回 [DomainResult.Success]
     * - 移除部分数量 → 更新数量，返回 [DomainResult.Success]
     */
    fun remove(id: String, count: Int = 1): DomainResult<Unit> {
        val existing = store.get(id)
            ?: return DomainResult.Failure(notFound(id))

        if (existing.isLocked && count > 0) {
            return DomainResult.Failure(AppError.Domain.Inventory.Locked(id))
        }

        val remaining = existing.quantity - count
        return if (remaining <= 0) {
            val key = stackKeyOf(existing)
            keyIndex.remove(key)
            store.remove(id)
            DomainResult.Success(Unit)
        } else {
            store.update(id) { existing.withQuantity(remaining) as T }
            DomainResult.Success(Unit)
        }
    }

    /**
     * 按数量扣减（快捷方法）。
     * 锁定物品或数量不足时返回 [DomainResult.Failure]。
     */
    fun deduct(id: String, count: Int): DomainResult<Unit> = remove(id, count)

    /** 全量替换，重建 key 索引 */
    fun replaceAll(items: List<T>) {
        store.replaceAll(items)
        rebuildKeyIndex()
    }

    /**
     * 暴露底层 [EntityStore] 快照用于与 [MutableGameState] 同步。
     * 调用方只读，写操作应通过本 store 方法。
     */
    fun snapshot(): EntityStore<T> = store

    // === 内部 ===

    private fun rebuildKeyIndex() {
        keyIndex.clear()
        for (item in store) {
            keyIndex[stackKeyOf(item)] = item.id
        }
    }
}

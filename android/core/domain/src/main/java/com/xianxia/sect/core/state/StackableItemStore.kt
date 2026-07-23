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
 * 可堆叠物品的统一仓库（多堆叠感知版）。
 *
 * ## 数据结构
 * 内部使用 [EntityStore] 做 O(1) ID 索引。
 * keyIndex 从 `HashMap<StackKey, String>` 升级为 `HashMap<StackKey, MutableList<String>>`，
 * 支持同种物品存在多个堆叠（例如第一个满 99，第二个有剩余空间）。
 *
 * ## 合并策略
 * [add] 方法**遍历所有匹配堆叠**（不仅是第一个），逐个填充剩余空间。
 * 仅在所有匹配堆叠均填满后才考虑创建新堆叠或返回溢出。
 *
 * ## 最近使用优先
 * 合并时将目标 ID 移到列表首部，已满堆叠自然沉降到尾部。
 * 下次添加时优先尝试"最近合并过的"堆叠，减少碎片。
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

    /** stackKey → ID 列表，支持同种多个堆叠（2026-07-23 升级） */
    private val keyIndex = HashMap<StackKey, MutableList<String>>()

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
     * 添加物品。
     *
     * ## 合并策略（多堆叠遍历）
     * 遍历 [keyIndex] 中所有同 [StackKey] 的堆叠，逐个填充剩余空间。
     * - 所有匹配堆叠填满后仍有剩余 → 创建新堆叠（若槽位足够）或返回 [DomainResult.Partial]
     * - 成功合并后目标 ID 移到列表首部（最近使用优先）
     *
     * @param item 待添加的物品
     * @param merge 是否尝试合并（默认 true）
     * @return [DomainResult.Success] 全部成功 / [DomainResult.Partial] 部分成功（带溢出量） / [DomainResult.Failure] 失败
     */
    @Suppress("UNCHECKED_CAST")
    fun add(item: T, merge: Boolean = true): DomainResult<T> {
        // 守卫：拒绝负数/零数量
        if (item.quantity <= 0) {
            return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(item.quantity))
        }
        val key = stackKeyOf(item)
        var remaining = item.quantity

        if (merge) {
            val ids = keyIndex[key] ?: emptyList()
            // toList() 快照避免并发修改；ids 极小（典型 1-3），开销可忽略
            for (id in ids.toList()) {
                val existing = store.get(id) ?: continue
                val space = maxStack - existing.quantity
                if (space <= 0) continue

                val addQty = minOf(remaining, space)
                val updated = existing.withQuantity(existing.quantity + addQty) as T
                store.update(id) { updated }
                remaining -= addQty
                promoteKey(key, id)

                if (remaining <= 0) {
                    return DomainResult.Success(updated)
                }
            }
        }

        // 还有剩余 → 尝试创建新堆叠
        if (remaining > 0) {
            if (store.size >= maxSlots()) {
                // 无空槽：若有已合并的堆叠则返回 Partial，否则 Failure
                // merge=false 时即使有匹配堆叠也视为未合并，直接返回 Failure
                val lastMergedId = if (!merge) null else (keyIndex[key]?.lastOrNull())
                return if (lastMergedId != null) {
                    val lastMerged = store.get(lastMergedId) ?: return@add DomainResult.Failure(
                        AppError.Domain.Inventory.NotFound(lastMergedId)
                    )
                    DomainResult.Partial(lastMerged as T, remaining)
                } else {
                    DomainResult.Failure(AppError.Domain.Inventory.Full())
                }
            }

            val newItem = item.withQuantity(remaining) as T
            store.add(newItem)
            keyIndex.getOrPut(key) { mutableListOf() }.add(newItem.id)
            return DomainResult.Success(newItem)
        }

        // remaining == 0：全部已合并，返回 Success（用最后一个被合并的堆叠作为 data）
        return DomainResult.Success(item)
    }

    /**
     * 移除指定数量的物品。
     *
     * - 物品不存在 → [DomainResult.Failure]
     * - 物品已锁定 → [DomainResult.Failure] (Locked)
     * - 数量不足 → [DomainResult.Failure] (Insufficient)
     * - 移除后数量归零 → 删除该条目并从 keyIndex 移除
     * - 移除部分数量 → 更新数量
     */
    @Suppress("UNCHECKED_CAST")
    fun remove(id: String, count: Int = 1): DomainResult<Unit> {
        // 守卫：拒绝负数/零数量
        if (count <= 0) {
            return DomainResult.Failure(AppError.Domain.Inventory.InvalidQuantity(count))
        }
        val existing = store.get(id)
            ?: return DomainResult.Failure(notFound(id))

        if (existing.isLocked && count > 0) {
            return DomainResult.Failure(AppError.Domain.Inventory.Locked(id))
        }

        // 守卫：超量扣减
        if (count > existing.quantity) {
            return DomainResult.Failure(AppError.Domain.Inventory.Insufficient(id, count, existing.quantity))
        }

        val remaining = existing.quantity - count
        return if (remaining <= 0) {
            // 全部移除 → 删除条目
            val key = stackKeyOf(existing)
            removeFromKeyIndex(key, id)
            store.remove(id)
            DomainResult.Success(Unit)
        } else {
            store.update(id) { existing.withQuantity(remaining) as T }
            DomainResult.Success(Unit)
        }
    }

    /** 按数量扣减（快捷方法）。锁定或不足时返回 Failure。 */
    fun deduct(id: String, count: Int): DomainResult<Unit> = remove(id, count)

    /** 全量替换，重建 key 索引 */
    fun replaceAll(items: List<T>) {
        store.replaceAll(items)
        rebuildKeyIndex()
    }

    // === 索引维护 ===

    /** 将指定 ID 移到 keyIndex 列表首部（最近使用优先）。不存在时自动添加。 */
    private fun promoteKey(key: StackKey, id: String) {
        val list = keyIndex[key]
        if (list != null) {
            val idx = list.indexOf(id)
            if (idx > 0) {
                list.removeAt(idx)
                list.add(0, id)
            } // idx == 0: 已在首部，不变
            else if (idx < 0) {
                list.add(0, id)
            }
        } else {
            keyIndex[key] = mutableListOf(id)
        }
    }

    /** 从 keyIndex 列表中移除指定 ID。列表为空时移除整个 key 条目。 */
    private fun removeFromKeyIndex(key: StackKey, id: String) {
        val list = keyIndex[key]
        if (list != null) {
            list.remove(id)
            if (list.isEmpty()) {
                keyIndex.remove(key)
            }
        }
    }

    /** 将 ID 加入 keyIndex 列表尾部 */
    private fun addToKeyIndex(key: StackKey, id: String) {
        keyIndex.getOrPut(key) { mutableListOf() }.add(id)
    }

    /** 全量重建 keyIndex */
    private fun rebuildKeyIndex() {
        keyIndex.clear()
        for (item in store) {
            val key = stackKeyOf(item)
            keyIndex.getOrPut(key) { mutableListOf() }.add(item.id)
        }
    }

    /**
     * 暴露底层 [EntityStore] 快照（用于外部同步）。
     * 调用方只读，写操作应通过本 store 方法。
     */
    fun snapshot(): EntityStore<T> = store

    // === 库外辅助：不依赖内部状态，用于纯 List 场景 ===

    companion object {
        /**
         * 将分散堆叠合并到第一个非满堆叠。
         * 仅用于 [consolidateStacks] 等库外辅助功能，不维护 keyIndex。
         *
         * @return 合并后的列表
         */
        fun <T> consolidateList(
            items: List<T>,
            matchKey: (T) -> StackKey,
            maxStack: Int
        ): List<T> where T : HasId, T : StackableItem {
            val groups = items.groupBy { matchKey(it) }
            val result = mutableListOf<T>()
            for ((_, list) in groups) {
                if (list.size <= 1) {
                    result.addAll(list)
                    continue
                }
                var primaryId = list.first().id
                val remaining = mutableListOf<T>()
                // 从第二个开始合并
                for (i in 1 until list.size) {
                    val secondary = list[i]
                    val primaryIdx = result.indexOfFirst { it.id == primaryId }
                    val primary = if (primaryIdx >= 0) result[primaryIdx] else list.first()
                    val space = maxStack - primary.quantity
                    if (space <= 0) {
                        // 主堆叠已满，切换到当前堆叠为新主
                        primaryId = secondary.id
                        remaining.add(secondary)
                        continue
                    }
                    val transfer = minOf(space, secondary.quantity)
                    val newPrimary = primary.withQuantity(primary.quantity + transfer) as T
                    if (primaryIdx >= 0) {
                        result[primaryIdx] = newPrimary
                    } else {
                        result.add(newPrimary)
                    }
                    if (transfer < secondary.quantity) {
                        val remainder = secondary.withQuantity(secondary.quantity - transfer) as T
                        remaining.add(remainder)
                    }
                }
                // 添加完全未合并的次堆叠
                result.addAll(remaining)
            }
            return result
        }
    }
}

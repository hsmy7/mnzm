package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.util.StackableItem

/**
 * 轻量级实体存储容器：O(1) ID 查找 + List 兼容操作。
 *
 * 内部用 [HashMap] 维护 ID→实体 索引，同时保持 [List] 接口兼容。
 * 所有写操作分配新列表（!== 引用变化检测）。
 *
 * @param T 实体类型，须实现 [HasId]
 */
class EntityStore<T : HasId>(initialItems: List<T> = emptyList()) : Iterable<T> {

    /** 当前列表。写操作后为新引用。 */
    var items: List<T> = initialItems
        private set

    private val index: MutableMap<String, T> = HashMap(initialItems.size)

    init { rebuildIndex() }

    // === 读取 ===

    /** O(1) ID 查找 */
    fun get(id: String): T? = index[id]

    /** 返回当前全部实体列表 */
    fun all(): List<T> = items

    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun isNotEmpty(): Boolean = items.isNotEmpty()

    /** 按 ID 判断是否存在 */
    fun contains(id: String): Boolean = id in index

    override fun iterator(): Iterator<T> = items.iterator()

    // === List 兼容操作（委托给 items） ===

    /**
     * 映射全部实体，返回新的 EntityStore。
     * 替代旧的 `store = store.map { ... }` 赋值模式。
     */
    inline fun map(transform: (T) -> T): EntityStore<T> =
        EntityStore(items.map(transform))

    /**
     * 映射全部实体，返回 List。
     * 用于需要 List 结果的场景。
     */
    inline fun mapToList(transform: (T) -> T): List<T> = items.map(transform)

    inline fun filter(predicate: (T) -> Boolean): EntityStore<T> =
        EntityStore(items.filter(predicate))

    inline fun filterToList(predicate: (T) -> Boolean): List<T> = items.filter(predicate)

    inline fun mapNotNull(transform: (T) -> T?): EntityStore<T> =
        EntityStore(items.mapNotNull(transform))

    /** 随机选取一个元素 */
    fun random(): T = items.random()

    /** 检查是否空 */
    // inherited: isEmpty(), isNotEmpty(), size

    /** 获取前 n 个 */
    fun take(n: Int): EntityStore<T> = EntityStore(items.take(n))

    /** 拼接另一个 EntityStore */
    operator fun plus(other: EntityStore<T>): EntityStore<T> =
        EntityStore(this.items + other.items)

    /** 拼接 List */
    operator fun plus(elements: List<T>): EntityStore<T> =
        EntityStore(this.items + elements)

    /** 减去指定列表 */
    operator fun minus(other: List<T>): EntityStore<T> =
        EntityStore(this.items - other.toSet())

    /** 转换为 List */
    fun toList(): List<T> = items
    inline fun forEach(action: (T) -> Unit) { items.forEach(action) }
    inline fun count(predicate: (T) -> Boolean): Int = items.count(predicate)
    inline fun firstOrNull(predicate: (T) -> Boolean): T? = items.firstOrNull(predicate)
    inline fun any(predicate: (T) -> Boolean): Boolean = items.any(predicate)
    inline fun none(predicate: (T) -> Boolean): Boolean = items.none(predicate)
    fun toMutableList(): MutableList<T> = items.toMutableList()

    /**
     * 按 ID 构建 Map。替代旧的 .associateBy { it.id } 模式。
     */
    fun associateById(): Map<String, T> = index.toMap()

    // === 写入（分配新 List） ===

    /** 添加。list = list + item 的替代 */
    operator fun plus(item: T): EntityStore<T> {
        val copy = EntityStore(items + item)
        return copy
    }

    /** 添加实体（原地修改） */
    fun add(item: T) {
        items = items + item
        index[item.id] = item
    }

    /** 删除 */
    fun remove(id: String) {
        if (index.remove(id) != null) {
            items = items.filter { it.id != id }
        }
    }

    /** 原地更新指定 ID */
    fun update(id: String, transform: (T) -> T) {
        val old = index[id] ?: return
        val newItem = transform(old)
        items = items.map { if (it.id == id) newItem else it }
        index[id] = newItem
    }

    /** 全量替换 */
    fun replaceAll(newItems: List<T>) {
        items = newItems
        rebuildIndex()
    }

    /**
     * 全量替换列表并重建索引。保持旧接口兼容。
     * 替代 oldList = newList 的赋值操作。
     */
    fun setItems(newItems: List<T>) {
        replaceAll(newItems)
    }

    /**
     * 对全部实体应用转换后原地替换。
     * 替代 `store = store.map { ... }` 模式。
     */
    inline fun mapInPlace(transform: (T) -> T) {
        replaceAll(items.map(transform))
    }

    /**
     * 过滤实体后原地替换。
     * 替代 `store = store.filter { ... }` 模式。
     */
    inline fun filterInPlace(predicate: (T) -> Boolean) {
        replaceAll(items.filter(predicate))
    }

    // === 内部 ===

    private fun rebuildIndex() {
        index.clear()
        for (item in items) {
            index[item.id] = item
        }
    }
}

/**
 * 将可堆叠物品合并到 [EntityStore]，溢出时新建堆叠。
 *
 * 替代旧的 `coerceAtMost(maxStack)` 截断模式，避免物品数量静默丢失。
 *
 * 语义：
 * - 存在同 [matchPredicate] 的堆叠且合并后不超过 [maxStack] → 合并到现有堆叠
 * - 存在同类堆叠但合并后超过 [maxStack] → 现有堆叠填满至 [maxStack]，溢出部分新建堆叠
 * - 不存在同类堆叠 → 直接添加为新堆叠
 *
 * @param item 待添加的物品
 * @param matchPredicate 判断两个物品是否属于同一合并组（名称/品质/类型等）
 * @param maxStack 单格最大堆叠数
 * @return 合并后的 EntityStore（可能为原引用或新引用）
 */
inline fun <T> EntityStore<T>.mergeStackable(
    item: T,
    crossinline matchPredicate: (T) -> Boolean,
    maxStack: Int
): EntityStore<T> where T : HasId, T : StackableItem {
    val existing = firstOrNull(matchPredicate)
    return if (existing != null) {
        val total = existing.quantity + item.quantity
        if (total <= maxStack) {
            update(existing.id) { (it as StackableItem).withQuantity(total) as T }
            this
        } else {
            update(existing.id) { (it as StackableItem).withQuantity(maxStack) as T }
            val overflow = (item as StackableItem).withQuantity(total - maxStack) as T
            this + overflow
        }
    } else {
        this + item
    }
}

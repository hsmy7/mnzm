package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.HasId
import com.xianxia.sect.core.util.StackableItem

/**
 * 实体存储容器（增量更新版）。
 *
 * ## 设计变更
 *
 * 原实现每次 [add]/[remove]/[update] 都分配新 [List]（写时复制），
 * 依赖 `!==` 引用比较检测变化。在 1000+ 物品场景下 GC 压力大。
 *
 * 现改为：
 * - 内部使用 [MutableList] 原地修改，写操作零分配
 * - [dirty] 标记记录是否有未冻结的修改
 * - [freeze] 在 StateFlow 发射前调用，重建不可变快照
 * - [items] 返回最近一次 freeze 的结果（无写操作时复用同一引用）
 *
 * 调用方（GameStateStoreImpl.emitChanges）需在发射 EntityStore 前调用 [freeze]，以触发 `!==` 检测。
 *
 * @param T 实体类型，须实现 [HasId]
 */
class EntityStore<T : HasId>(initialItems: List<T> = emptyList()) : Iterable<T> {

    // ★ 内部可变列表 — 写操作原地修改，零分配
    private val items_ = initialItems.toMutableList()
    private val index: MutableMap<String, T> = HashMap(initialItems.size)
    // ★ 已冻结的快照（供 items 返回）
    private var frozenSnapshot: List<T> = initialItems
    // ★ dirty 标记：是否有未冻结的修改
    private var dirty = false

    init { rebuildIndex() }

    /**
     * 当前已冻结的列表引用。
     * 无写入时复用同一引用（!== 检测正常工作）。
     * 有写入后调用 [freeze] 才更新。
     */
    val items: List<T> get() = frozenSnapshot

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

    // === 写入（零分配原地修改） ===

    /** 添加实体。不分配新 List，只标记 dirty。 */
    fun add(item: T) {
        items_.add(item)
        index[item.id] = item
        dirty = true
    }

    /** 删除 */
    fun remove(id: String) {
        if (index.remove(id) != null) {
            items_.removeAll { it.id == id }
            dirty = true
        }
    }

    /** 原地更新指定 ID。不分配新 List。 */
    fun update(id: String, transform: (T) -> T) {
        val old = index[id] ?: return
        val newItem = transform(old)
        val idx = items_.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items_[idx] = newItem
            index[id] = newItem
            dirty = true
        }
    }

    /** 全量替换 */
    fun replaceAll(newItems: List<T>) {
        items_.clear()
        items_.addAll(newItems)
        rebuildIndex()
        dirty = true
    }

    fun setItems(newItems: List<T>) { replaceAll(newItems) }

    fun mapInPlace(transform: (T) -> T) {
        for (i in items_.indices) {
            items_[i] = transform(items_[i])
        }
        rebuildIndex()
        dirty = true
    }

    fun filterInPlace(predicate: (T) -> Boolean) {
        items_.removeAll { !predicate(it) }
        rebuildIndex()
        dirty = true
    }

    // ★ 拼接（仍返回新 EntityStore，保持语义不变）
    operator fun plus(item: T): EntityStore<T> {
        val newItems = this.items_.toMutableList()
        newItems.add(item)
        return EntityStore(newItems)
    }

    // ★ 冻结：写入 items 快照供 StateFlow 发射用。仅 dirty 时分配新列表。
    fun freeze(): EntityStore<T> {
        if (dirty) {
            frozenSnapshot = items_.toList()
            dirty = false
        }
        return this
    }

    // === 内部 ===

    private fun rebuildIndex() {
        index.clear()
        for (i in items_.indices) {
            index[items_[i].id] = items_[i]
        }
    }

    /** 是否自上次 freeze() 后有修改（供 GameStateStoreImpl 使用） */
    val isDirty: Boolean get() = dirty
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

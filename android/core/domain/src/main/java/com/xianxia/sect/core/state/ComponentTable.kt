package com.xianxia.sect.core.state

import android.util.SparseArray
import android.util.SparseIntArray

/**
 * 组件表：存储"所有实体的同一种属性"。
 *
 * 这是整个新架构的基础数据结构。
 * 一张 ComponentTable 就是 id → value 的映射，内部使用 SparseArray。
 *
 * @param T 值类型。对于 Int/Double/Long 等基本类型，优先使用
 *          IntComponentTable/DoubleComponentTable（避免装箱）。
 *          字符串、枚举、List 等引用类型直接使用 ComponentTable<T>。
 */
class ComponentTable<T> @JvmOverloads constructor(
    initialCapacity: Int = 64
) {
    @PublishedApi internal val store = SparseArray<T>(initialCapacity)

    /** 可选写入回调，由 DiscipleTables 注入以自动 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null

    // === 读取 ===

    /** O(log n) 获取 */
    operator fun get(id: Int): T = store[id]
        ?: throw NoSuchElementException("ComponentTable: no entry for id=$id")

    /** O(log n) 获取，可能为 null */
    fun getOrNull(id: Int): T? = store[id]

    /** O(log n) 用默认值获取 */
    fun getOrDefault(id: Int, default: T): T = store[id] ?: default

    // === 写入 ===

    /** 设置值 */
    operator fun set(id: Int, value: T) {
        store.put(id, value); onWrite?.invoke()
    }

    /** 原子更新（读取 → 变换 → 写回） */
    inline fun update(id: Int, block: (T) -> T) {
        store[id] = block(store[id]); onWrite?.invoke()
    }

    // === 遍历 ===

    /** 所有键 */
    fun ids(): IntArray {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        return result
    }

    /** 大小 */
    val size: Int get() = store.size()

    /** 是否为空 */
    fun isEmpty(): Boolean = store.size() == 0

    /** 包含 ID */
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0

    /** 迭代 */
    inline fun forEach(action: (Int, T) -> Unit) {
        for (i in 0 until store.size()) {
            action(store.keyAt(i), store.valueAt(i))
        }
    }

    /** 迭代（仅值） */
    inline fun forEachValue(action: (T) -> Unit) {
        for (i in 0 until store.size()) action(store.valueAt(i))
    }

    /** 映射为列表（仅值） */
    fun values(): List<T> {
        return (0 until store.size()).map { store.valueAt(it) }
    }

    // === 增删 ===

    /** 插入 */
    fun put(id: Int, value: T) {
        store.put(id, value); onWrite?.invoke()
    }

    /** 删除 */
    fun remove(id: Int) {
        store.remove(id); onWrite?.invoke()
    }

    /** 清空 */
    fun clear() {
        store.clear(); onWrite?.invoke()
    }
}

// ============================================================
// Packed Array 实现：基于 dense array + id→index 映射 +
// swap-on-remove 的 Sparse Set 模式。
//
// 相比 SparseIntArray 的优势：
// - 值存储在连续 IntArray 中，缓存友好、零装箱
// - 删除操作为 O(1)，无需移动后续元素（swap-on-remove）
// - 迭代全部有效元素，无空洞
// - grow 策略简单：capacity 翻倍
//
// 注意：swap-on-remove 会使迭代顺序在删除后改变，
// 但不影响 `contains`/`get`/`put` 等操作的正确性。
// ============================================================

/**
 * 基于 packed array + swap-on-remove 的 int→int 稀疏集合。
 *
 * 内部结构：
 * - `values: IntArray` — 连续排列的 dense 值数组
 * - `keys: IntArray` — 连续排列的 key 数组（与 values 一一对应）
 * - `idToIndex: SparseIntArray` — id → index 映射，O(log N) 查询
 * - `size_` — 有效条目数
 *
 * swap-on-remove：删除条目时，用最后一个有效条目填充空洞，使 dense 数组保持连续。
 */
class IntPackedArray @JvmOverloads constructor(
    initialCapacity: Int = 64
) {
    @PublishedApi internal var values = IntArray(initialCapacity)
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var idToIndex = SparseIntArray(initialCapacity)
    @PublishedApi internal var size_ = 0

    // === 读取 ===

    /** O(log N) 获取值，不存在返回 0 */
    operator fun get(key: Int): Int {
        val idx = idToIndex.get(key, -1)
        return if (idx >= 0) values[idx] else 0
    }

    /** O(log N) 获取值，不存在返回 [default] */
    fun get(key: Int, default: Int): Int {
        val idx = idToIndex.get(key, -1)
        return if (idx >= 0) values[idx] else default
    }

    // === 写入 ===

    /** 设置值（存在则更新，否则插入） */
    fun put(key: Int, value: Int) {
        val idx = idToIndex.get(key, -1)
        if (idx >= 0) {
            values[idx] = value
            return
        }
        if (size_ >= values.size) grow()
        keys[size_] = key
        values[size_] = value
        idToIndex.put(key, size_)
        size_++
    }

    /** 删除 */
    fun delete(key: Int) {
        val idx = idToIndex.get(key, -1)
        if (idx < 0) return
        idToIndex.delete(key)
        val lastIdx = size_ - 1
        if (idx != lastIdx) {
            // swap-on-remove：用最后一个有效条目填充空洞
            keys[idx] = keys[lastIdx]
            values[idx] = values[lastIdx]
            idToIndex.put(keys[idx], idx)
        }
        size_--
    }

    /** 清空（保留容量） */
    fun clear() {
        idToIndex.clear()
        size_ = 0
    }

    // === 迭代兼容 API（与 SparseIntArray 保持方法签名一致） ===

    /** 有效条目数 */
    fun size(): Int = size_

    /** 返回第 [index] 个条目的 key */
    fun keyAt(index: Int): Int = keys[index]

    /** 返回第 [index] 个条目的 value */
    fun valueAt(index: Int): Int = values[index]

    /** 包含检测（返回 >= 0 表示存在，与 SparseIntArray.indexOfKey 行为兼容） */
    fun indexOfKey(key: Int): Int = if (idToIndex.get(key, -1) >= 0) 1 else -1

    // === 内部 ===

    private fun grow() {
        val newSize = maxOf(values.size * 2, 8)
        values = values.copyOf(newSize)
        keys = keys.copyOf(newSize)
    }
}

/**
 * 基于 packed array + swap-on-remove 的 int→double 稀疏集合。
 *
 * 与 [IntPackedArray] 结构相同，但值类型为 [Double]。
 * 替代 [android.util.SparseArray]<Double>，避免装箱。
 */
class DoublePackedArray @JvmOverloads constructor(
    initialCapacity: Int = 64
) {
    @PublishedApi internal var values = DoubleArray(initialCapacity)
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var idToIndex = SparseIntArray(initialCapacity)
    @PublishedApi internal var size_ = 0

    // === 读取 ===

    /** O(log N) 获取值，不存在返回 0.0 */
    operator fun get(key: Int): Double {
        val idx = idToIndex.get(key, -1)
        return if (idx >= 0) values[idx] else 0.0
    }

    /** O(log N) 获取值，不存在返回 [default] */
    fun get(key: Int, default: Double): Double {
        val idx = idToIndex.get(key, -1)
        return if (idx >= 0) values[idx] else default
    }

    // === 写入 ===

    /** 设置值（存在则更新，否则插入） */
    fun put(key: Int, value: Double) {
        val idx = idToIndex.get(key, -1)
        if (idx >= 0) {
            values[idx] = value
            return
        }
        if (size_ >= values.size) grow()
        keys[size_] = key
        values[size_] = value
        idToIndex.put(key, size_)
        size_++
    }

    /** 删除 */
    fun delete(key: Int) {
        val idx = idToIndex.get(key, -1)
        if (idx < 0) return
        idToIndex.delete(key)
        val lastIdx = size_ - 1
        if (idx != lastIdx) {
            // swap-on-remove
            keys[idx] = keys[lastIdx]
            values[idx] = values[lastIdx]
            idToIndex.put(keys[idx], idx)
        }
        size_--
    }

    /** 清空（保留容量） */
    fun clear() {
        idToIndex.clear()
        size_ = 0
    }

    // === 迭代兼容 API ===

    /** 有效条目数 */
    fun size(): Int = size_

    /** 返回第 [index] 个条目的 key */
    fun keyAt(index: Int): Int = keys[index]

    /** 返回第 [index] 个条目的 value */
    fun valueAt(index: Int): Double = values[index]

    /** 包含检测 */
    fun indexOfKey(key: Int): Int = if (idToIndex.get(key, -1) >= 0) 1 else -1

    // === 内部 ===

    private fun grow() {
        val newSize = maxOf(values.size * 2, 8)
        values = values.copyOf(newSize)
        keys = keys.copyOf(newSize)
    }
}

// ============================================================
// Int 值组件表
// ============================================================

/**
 * 基本类型组件表：int 值，无装箱。
 * 底层使用 [IntPackedArray]（dense array + swap-on-remove），
 * 比原 [android.util.SparseIntArray] 更优的缓存局部性和迭代性能。
 */
class IntComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = IntPackedArray(initialCapacity)

    /** 可选写入回调，由 DiscipleTables 注入以自动 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null

    operator fun get(id: Int): Int = store[id]
    fun getOrDefault(id: Int, default: Int): Int = store.get(id, default)
    operator fun set(id: Int, value: Int) { store.put(id, value); onWrite?.invoke() }
    inline fun update(id: Int, block: (Int) -> Int) {
        store.put(id, block(store[id])); onWrite?.invoke()
    }
    fun ids(): IntArray {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        return result
    }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    inline fun forEach(action: (Int, Int) -> Unit) {
        for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
    }
    fun values(): List<Int> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Int) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.delete(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }
}

// ============================================================
// Double 值组件表
// ============================================================

/**
 * 基本类型组件表：double 值，无装箱。
 * 底层使用 [DoublePackedArray]（dense array + swap-on-remove），
 * 替代原 [android.util.SparseArray]<Double> 的装箱开销。
 */
class DoubleComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = DoublePackedArray(initialCapacity)

    /** 可选写入回调，由 DiscipleTables 注入以自动 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null

    operator fun get(id: Int): Double = store[id]
    fun getOrDefault(id: Int, default: Double): Double = store.get(id, default)
    operator fun set(id: Int, value: Double) { store.put(id, value); onWrite?.invoke() }
    inline fun update(id: Int, block: (Double) -> Double) {
        store.put(id, block(store[id])); onWrite?.invoke()
    }
    fun ids(): IntArray {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        return result
    }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    inline fun forEach(action: (Int, Double) -> Unit) {
        for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
    }
    fun values(): List<Double> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Double) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.delete(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }
}

// ============================================================
// 统一接口：迭代式 CRUD 操作（用于 DiscipleTables 重构）
// ============================================================

/**
 * 组件表统一接口，支持 [remove] 和 [clear] 迭代操作。
 */
sealed interface ComponentTableLike {
    fun remove(id: Int)
    fun clear()
    val size: Int
    val debugName: String
}

/**
 * 带深拷贝能力的组件表引用，用于 [DiscipleTables.deepCopy]。
 */
sealed interface CopyableTableRef : ComponentTableLike {
    fun copyTo(dest: DiscipleTables)
}

/** [IntComponentTable] 的拷贝引用（无需 lambda，使用属性引用避免匿名类膨胀） */
class IntTableRef(
    @JvmField val table: IntComponentTable,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, IntComponentTable>,
    override val debugName: String
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) {
        val dst = destProp.get(dest)
        for (i in 0 until table.store.size()) {
            dst.store.put(table.store.keyAt(i), table.store.valueAt(i))
        }
    }
}

/** [DoubleComponentTable] 的拷贝引用 */
class DoubleTableRef(
    @JvmField val table: DoubleComponentTable,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, DoubleComponentTable>,
    override val debugName: String
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) {
        val dst = destProp.get(dest)
        for (i in 0 until table.store.size()) {
            dst.store.put(table.store.keyAt(i), table.store.valueAt(i))
        }
    }
}

/** [ComponentTable] 的引用拷贝（值不可变类型，浅拷贝安全） */
class RefTableRef<T>(
    @JvmField val table: ComponentTable<T>,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, ComponentTable<T>>,
    override val debugName: String
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) {
        val dst = destProp.get(dest)
        for (i in 0 until table.store.size()) {
            dst.store.put(table.store.keyAt(i), table.store.valueAt(i))
        }
    }
}

/** [ComponentTable] 的拷贝引用（值可变类型，使用 [deepCopyFn] 深拷贝每个元素） */
class MutableTableRef<T>(
    @JvmField val table: ComponentTable<T>,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, ComponentTable<T>>,
    override val debugName: String,
    private val deepCopyFn: (T) -> T
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) {
        val dst = destProp.get(dest)
        for (i in 0 until table.store.size()) {
            dst.store.put(table.store.keyAt(i), deepCopyFn(table.store.valueAt(i)))
        }
    }
}

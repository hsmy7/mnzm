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
        store.put(id, value)
    }

    /** 原子更新（读取 → 变换 → 写回） */
    inline fun update(id: Int, block: (T) -> T) {
        store[id] = block(store[id])
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
        store.put(id, value)
    }

    /** 删除 */
    fun remove(id: Int) {
        store.remove(id)
    }

    /** 清空 */
    fun clear() {
        store.clear()
    }
}

/**
 * 基本类型组件表：int 值，无装箱。
 * 用于 loyalty, hp, realm 等 int 字段。
 */
class IntComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = SparseIntArray(initialCapacity)

    operator fun get(id: Int): Int = store[id]
    fun getOrDefault(id: Int, default: Int): Int = store.get(id, default)
    operator fun set(id: Int, value: Int) { store.put(id, value) }
    inline fun update(id: Int, block: (Int) -> Int) { store.put(id, block(store[id])) }
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
    fun put(id: Int, value: Int) { store.put(id, value) }
    fun remove(id: Int) { store.delete(id) }
    fun clear() { store.clear() }
}

/**
 * 基本类型组件表：double 值，无装箱。
 * 用于 cultivation 等 double 字段。
 */
class DoubleComponentTable(initialCapacity: Int = 64) {
    // 使用 SparseArray<Double> 的包装来存 double
    // Android 没有 SparseDoubleArray，用 SparseArray<kotlin.Double> 装箱成本可接受
    // 因为弟子数 < 500，性能不是瓶颈
    @PublishedApi internal val store = SparseArray<Double>(initialCapacity)

    operator fun get(id: Int): Double = store[id] ?: 0.0
    fun getOrDefault(id: Int, default: Double): Double = store[id] ?: default
    operator fun set(id: Int, value: Double) { store.put(id, value) }
    inline fun update(id: Int, block: (Double) -> Double) {
        store[id] = block(store[id] ?: 0.0)
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
    fun put(id: Int, value: Double) { store.put(id, value) }
    fun remove(id: Int) { store.remove(id) }
    fun clear() { store.clear() }
}

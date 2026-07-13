package com.xianxia.sect.core.state

import android.util.SparseArray

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

    /** 锁对象 — 保护 store 并发访问（kotlinx Mutex 在协程挂起时会释放锁） */
    @PublishedApi internal val lock = Any()

    /** 可选写入回调，由 DiscipleTables 注入以自动 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null

    // === 读取 ===

    /** O(log n) 获取 */
    operator fun get(id: Int): T = synchronized(lock) {
        store[id]
    } ?: throw NoSuchElementException("ComponentTable: no entry for id=$id")

    /** O(log n) 获取，可能为 null */
    fun getOrNull(id: Int): T? = synchronized(lock) { store[id] }

    /** O(log n) 用默认值获取 */
    fun getOrDefault(id: Int, default: T): T = synchronized(lock) { store[id] ?: default }

    // === 写入 ===

    /** 设置值 */
    operator fun set(id: Int, value: T) {
        synchronized(lock) { store.put(id, value) }
        onWrite?.invoke()
    }

    /** 原子更新（读取 → 变换 → 写回） */
    inline fun update(id: Int, block: (T) -> T) {
        synchronized(lock) { store[id] = block(store[id]) }
        onWrite?.invoke()
    }

    // === 遍历 ===

    /** 所有键 */
    fun ids(): IntArray = synchronized(lock) {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        result
    }

    /** 大小 */
    val size: Int get() = synchronized(lock) { store.size() }

    /** 是否为空 */
    fun isEmpty(): Boolean = synchronized(lock) { store.size() == 0 }

    /** 包含 ID */
    fun contains(id: Int): Boolean = synchronized(lock) { store.indexOfKey(id) >= 0 }

    /** 迭代 */
    inline fun forEach(action: (Int, T) -> Unit) {
        synchronized(lock) {
            for (i in 0 until store.size()) {
                action(store.keyAt(i), store.valueAt(i))
            }
        }
    }

    /** 迭代（仅值） */
    inline fun forEachValue(action: (T) -> Unit) {
        synchronized(lock) {
            for (i in 0 until store.size()) action(store.valueAt(i))
        }
    }

    /** 映射为列表（仅值） */
    fun values(): List<T> = synchronized(lock) {
        (0 until store.size()).map { store.valueAt(it) }
    }

    // === 增删 ===

    /** 插入 */
    fun put(id: Int, value: T) {
        synchronized(lock) { store.put(id, value) }
        onWrite?.invoke()
    }

    /** 删除 */
    fun remove(id: Int) {
        synchronized(lock) { store.remove(id) }
        onWrite?.invoke()
    }

    /** 清空 */
    fun clear() {
        synchronized(lock) { store.clear() }
        onWrite?.invoke()
    }

    /** 安全遍历所有条目（供 RefTableRef.copyTo 等内部使用，避免直接 store 访问） */
    @PublishedApi internal fun forEachEntry(action: (Int, T) -> Unit) = synchronized(lock) {
        for (i in 0 until store.size()) {
            action(store.keyAt(i), store.valueAt(i))
        }
    }
}

// ============================================================
// 平铺数组实现：ID 直接索引 + 紧凑 keys 迭代列表。
//
// IntPackedArray 原使用 SparseIntArray(id→packed索引) 做 O(log N)
// 查找，safeIndex 的 indexOfKey/get 混淆曾导致数据损坏 bug。
//
// 重构为平铺 IntArray 后：
// - values[id] 直接 O(1) 访问，零查找开销
// - idToSlot 是平铺 IntArray（非 SparseIntArray），O(1) 存在性检测
// - 紧凑 keys 列表仅含存活 ID，供 forEach/copyTo 遍历
// - 消除整个 id→packed索引 映射层，从根源消灭索引混淆 bug 类
// ============================================================

/**
 * 平铺 int→int 映射：ID 直接作为数组索引。
 *
 * 内部结构：
 * - `values: IntArray` — 由 ID 直接索引，O(1) 读写
 * - `idToSlot: IntArray` — ID → keys[] 索引，-1=不存在；O(1) 存在性检测
 * - `keys: IntArray` — 紧凑的存活 ID 列表，用于迭代
 * - `size_` — 存活条目数
 *
 * 删除使用 swap-on-remove 维护 keys 紧凑性。
 * 不依赖 [android.util.SparseIntArray]，无二分查找，无索引混淆风险。
 */
class IntFlatArray @JvmOverloads constructor(
    initialCapacity: Int = 64
) {
    /** 平铺值数组，由弟子 ID 直接索引 */
    @PublishedApi internal var values = IntArray(initialCapacity)
    /** ID → keys[] 索引；-1=该 ID 不存在 */
    @PublishedApi internal var idToSlot = IntArray(initialCapacity) { -1 }
    /** 紧凑的存活 ID 列表，供迭代使用 */
    @PublishedApi internal var keys = IntArray(initialCapacity)
    /** 存活条目数 */
    @PublishedApi internal var size_ = 0

    private fun ensureCapacity(key: Int) {
        if (key >= values.size) {
            val oldSize = values.size
            val newSize = maxOf(values.size * 2, key + 1 + 64)
            values = values.copyOf(newSize)
            idToSlot = idToSlot.copyOf(newSize)
            for (i in oldSize until newSize) idToSlot[i] = -1
        }
    }

    private fun growKeys() {
        keys = keys.copyOf(maxOf(keys.size * 2, 8))
    }

    // === 读取 ===

    /** O(1) 获取值，不存在返回 0 */
    @Synchronized
    operator fun get(key: Int): Int = if (key < values.size && idToSlot[key] >= 0) values[key] else 0

    /** O(1) 获取值，不存在返回 [default] */
    @Synchronized
    fun get(key: Int, default: Int): Int = if (key < values.size && idToSlot[key] >= 0) values[key] else default

    /** O(1) 存在性检测 */
    @Synchronized
    fun contains(key: Int): Boolean = key < values.size && idToSlot[key] >= 0

    // === 写入 ===

    /** O(1) 设置值：存在则更新，否则插入 */
    @Synchronized
    fun put(key: Int, value: Int) {
        // ★ ensureCapacity + growKeys 必须在 values[key] 写入前完成，
        //    否则若 growKeys OOM，values[key] 已提交但 keys 未追踪 → 静默数据丢失
        ensureCapacity(key)
        if (size_ >= keys.size) growKeys()
        values[key] = value
        if (idToSlot[key] < 0) {
            // 新 ID：加入紧凑 keys 列表
            idToSlot[key] = size_
            keys[size_] = key
            size_++
        }
    }

    /** 原子读-改-写：读取当前值 → 变换 → 写回，整个操作在同一个锁内 */
    @Synchronized
    fun update(key: Int, block: (Int) -> Int) {
        if (key < values.size && idToSlot[key] >= 0) {
            val result = block(values[key])
            values[key] = result
            // keys 已存在，无需 growKeys
        } else {
            val result = block(0)
            ensureCapacity(key)
            if (size_ >= keys.size) growKeys()
            values[key] = result
            idToSlot[key] = size_
            keys[size_] = key
            size_++
        }
    }

    /** O(1) 删除：标记为不存在，keys 中使用 swap-on-remove */
    @Synchronized
    fun delete(key: Int) {
        if (key >= values.size || idToSlot[key] < 0) return
        val slot = idToSlot[key]
        val lastIdx = size_ - 1
        if (slot != lastIdx) {
            keys[slot] = keys[lastIdx]
            idToSlot[keys[slot]] = slot
        }
        idToSlot[key] = -1
        values[key] = 0
        size_--
    }

    /** 清空 */
    @Synchronized
    fun clear() {
        for (i in 0 until values.size) values[i] = 0
        for (i in 0 until idToSlot.size) idToSlot[i] = -1
        size_ = 0
    }

    // === 迭代 ===

    /** 存活条目数 */
    @Synchronized
    fun size(): Int = size_

    /** 返回第 [index] 个存活条目的 ID */
    @Synchronized
    fun keyAt(index: Int): Int = keys[index]

    /** 返回第 [index] 个存活条目的值 */
    @Synchronized
    fun valueAt(index: Int): Int = values[keys[index]]

    /** 包含检测，兼容 SparseIntArray.indexOfKey 语义 */
    @Synchronized
    fun indexOfKey(key: Int): Int = if (contains(key)) 1 else -1

    /** 安全遍历所有存活条目 （同一锁内迭代，避免 size/keyAt/valueAt 锁间隙） */
    @Synchronized
    fun forEachEntry(action: (Int, Int) -> Unit) {
        for (i in 0 until size_) action(keys[i], values[keys[i]])
    }
}

/**
 * 平铺 int→double 映射：ID 直接作为数组索引。
 *
 * 与 [IntFlatArray] 结构相同，但值类型为 [Double]。
 */
class DoubleFlatArray @JvmOverloads constructor(
    initialCapacity: Int = 64
) {
    @PublishedApi internal var values = DoubleArray(initialCapacity)
    @PublishedApi internal var idToSlot = IntArray(initialCapacity) { -1 }
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var size_ = 0

    private fun ensureCapacity(key: Int) {
        if (key >= values.size) {
            val oldSize = values.size
            val newSize = maxOf(values.size * 2, key + 1 + 64)
            values = values.copyOf(newSize)
            idToSlot = idToSlot.copyOf(newSize)
            for (i in oldSize until newSize) idToSlot[i] = -1
        }
    }

    private fun growKeys() {
        keys = keys.copyOf(maxOf(keys.size * 2, 8))
    }

    // === 读取 ===

    /** O(1) 获取值，不存在返回 0.0 */
    @Synchronized
    operator fun get(key: Int): Double = if (key < values.size && idToSlot[key] >= 0) values[key] else 0.0

    /** O(1) 获取值，不存在返回 [default] */
    @Synchronized
    fun get(key: Int, default: Double): Double = if (key < values.size && idToSlot[key] >= 0) values[key] else default

    /** O(1) 存在性检测 */
    @Synchronized
    fun contains(key: Int): Boolean = key < values.size && idToSlot[key] >= 0

    // === 写入 ===

    /** O(1) 设置值 */
    @Synchronized
    fun put(key: Int, value: Double) {
        ensureCapacity(key)
        if (size_ >= keys.size) growKeys()
        values[key] = value
        if (idToSlot[key] < 0) {
            idToSlot[key] = size_
            keys[size_] = key
            size_++
        }
    }

    /** 原子读-改-写 */
    @Synchronized
    fun update(key: Int, block: (Double) -> Double) {
        if (key < values.size && idToSlot[key] >= 0) {
            val result = block(values[key])
            values[key] = result
        } else {
            val result = block(0.0)
            ensureCapacity(key)
            if (size_ >= keys.size) growKeys()
            values[key] = result
            idToSlot[key] = size_
            keys[size_] = key
            size_++
        }
    }

    /** O(1) 删除 */
    @Synchronized
    fun delete(key: Int) {
        if (key >= values.size || idToSlot[key] < 0) return
        val slot = idToSlot[key]
        val lastIdx = size_ - 1
        if (slot != lastIdx) {
            keys[slot] = keys[lastIdx]
            idToSlot[keys[slot]] = slot
        }
        idToSlot[key] = -1
        values[key] = 0.0
        size_--
    }

    /** 清空 */
    @Synchronized
    fun clear() {
        for (i in 0 until values.size) values[i] = 0.0
        for (i in 0 until idToSlot.size) idToSlot[i] = -1
        size_ = 0
    }

    // === 迭代 ===

    @Synchronized
    fun size(): Int = size_
    @Synchronized
    fun keyAt(index: Int): Int = keys[index]
    @Synchronized
    fun valueAt(index: Int): Double = values[keys[index]]
    /** 包含检测，兼容 SparseIntArray.indexOfKey 语义 */
    @Synchronized
    fun indexOfKey(key: Int): Int = if (contains(key)) 1 else -1

    /** 安全遍历所有存活条目 （同一锁内迭代，避免 size/keyAt/valueAt 锁间隙） */
    @Synchronized
    fun forEachEntry(action: (Int, Double) -> Unit) {
        for (i in 0 until size_) action(keys[i], values[keys[i]])
    }
}

// ============================================================
// Int 值组件表
// ============================================================

/**
 * 基本类型组件表：int 值，无装箱。
 * 底层使用 [IntFlatArray]（平铺数组 + ID 直接索引），
 * O(1) 读写，迭代使用紧凑 keys 列表。
 */
class IntComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = IntFlatArray(initialCapacity)

    /** 可选写入回调，由 DiscipleTables 注入以自动 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null

    operator fun get(id: Int): Int = store[id]
    fun getOrDefault(id: Int, default: Int): Int = store.get(id, default)
    /** 安全获取，缺失返回 null */
    fun getOrNull(id: Int): Int? = if (store.contains(id)) store[id] else null
    operator fun set(id: Int, value: Int) { store.put(id, value); onWrite?.invoke() }
    fun update(id: Int, block: (Int) -> Int) {
        store.update(id, block); onWrite?.invoke()
    }
    fun ids(): IntArray = synchronized(store) {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        result
    }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    fun forEach(action: (Int, Int) -> Unit) {
        synchronized(store) {
            for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
        }
    }
    fun values(): List<Int> = synchronized(store) {
        (0 until store.size()).map { store.valueAt(it) }
    }
    fun put(id: Int, value: Int) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.delete(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }

    /** 安全遍历所有条目（供 RefTableRef.copyTo 等内部使用） */
    @PublishedApi internal fun forEachEntry(action: (Int, Int) -> Unit) {
        store.forEachEntry(action)
    }
}

// ============================================================
// Double 值组件表
// ============================================================

/**
 * 基本类型组件表：double 值，无装箱。
 * 底层使用 [DoubleFlatArray]（平铺数组 + ID 直接索引），
 * 替代原 [android.util.SparseArray]<Double> 的装箱开销。
 */
class DoubleComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = DoubleFlatArray(initialCapacity)

    /** 可选写入回调，由 DiscipleTables 注入以自动 bump mutationVersion */
    @JvmField var onWrite: (() -> Unit)? = null

    operator fun get(id: Int): Double = store[id]
    fun getOrDefault(id: Int, default: Double): Double = store.get(id, default)
    operator fun set(id: Int, value: Double) { store.put(id, value); onWrite?.invoke() }
    fun update(id: Int, block: (Double) -> Double) {
        store.update(id, block); onWrite?.invoke()
    }
    fun ids(): IntArray = synchronized(store) {
        val result = IntArray(store.size())
        for (i in 0 until store.size()) result[i] = store.keyAt(i)
        result
    }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    fun forEach(action: (Int, Double) -> Unit) {
        synchronized(store) {
            for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i))
        }
    }
    fun values(): List<Double> = synchronized(store) {
        (0 until store.size()).map { store.valueAt(it) }
    }
    fun put(id: Int, value: Double) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.delete(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }

    /** 安全遍历所有条目（供 RefTableRef.copyTo 等内部使用） */
    @PublishedApi internal fun forEachEntry(action: (Int, Double) -> Unit) {
        store.forEachEntry(action)
    }
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
        table.forEachEntry { key, value -> dst.store.put(key, value) }
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
        table.forEachEntry { key, value -> dst.store.put(key, value) }
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
        table.forEachEntry { key, value -> dst.store.put(key, value) }
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
        table.forEachEntry { key, value -> dst.store.put(key, deepCopyFn(value)) }
    }
}

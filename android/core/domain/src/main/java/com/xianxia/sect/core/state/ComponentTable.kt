package com.xianxia.sect.core.state

import android.util.SparseArray

/** 组件表 ID 安全上限（游戏弟子规模远小于此，防篡改存档的溢出 OOM） */
private const val MAX_SAFE_CAPACITY = 10_000_000

/**
 * 线程安全由 GameStateStoreImpl.transactionLock（ReentrantLock）保证，
 * 所有写操作在 stateStore.update {} 内串行执行，无需额外同步。
 *
 * Copy-on-Write 快照隔离：
 * - [adopt] 让事务缓冲共享源表存储（O(1) 零数据复制）；
 * - 共享后首次写入触发 [ensureOwned] 私有化（clone 存储），此后写私有副本；
 * - 旧快照（UI 持有）引用旧存储，事务永不原地修改源存储，天然隔离。
 */
class ComponentTable<T> @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal var store = SparseArray<T>(initialCapacity)
    private var shared = false
    private var onWrite: (() -> Unit)? = null
    /** 写入前守卫检查，由 [DiscipleTables.bindAllOnWrite] 绑定为 [DiscipleTables.requireWriteAccess] */
    private var requireWrite: (() -> Unit)? = null
    /** 按 id 的写入回调（2026-08-01 增量组装基建）：记录被写入的弟子 ID */
    private var onIdWrite: ((Int) -> Unit)? = null

    /** 设置写入守卫回调（update {} 事务外调用）。替换 @JvmField var requireWrite */
    fun setWriteGuard(callback: () -> Unit) { requireWrite = callback }
    /** 设置变更回调（update {} 事务外调用）。替换 @JvmField var onWrite */
    fun setMutationCallback(callback: () -> Unit) { onWrite = callback }
    /** 设置按 id 写入回调（update {} 事务外调用）。替换 @JvmField var onIdWrite */
    fun setIdWriteCallback(callback: (Int) -> Unit) { onIdWrite = callback }
    /**
     * 带守卫的跨表/外部写入：通过本表的写入守卫后，将值写入存储。
     * 替换对 `target.store.put(key, value)` 的直接访问。
     */
    @PublishedApi internal fun putTo(key: Int, value: T) {
        requireWrite?.invoke(); ensureOwned(); store.put(key, value); onWrite?.invoke(); onIdWrite?.invoke(key)
    }

    /**
     * 事务缓冲共享源表存储（COW 快照隔离核心）。
     * 共享本身 O(1) 零数据复制；共享后**首次写入**该列时触发
     * [ensureOwned] 私有化（SparseArray clone，O(size)），此后写私有副本。
     * 源表与旧快照不受影响。值对象按引用共享——与旧 copyTo 语义一致。
     */
    @PublishedApi internal fun adopt(source: SparseArray<T>) { store = source; shared = true }

    /**
     * 急切深拷贝共享存储（Mutable 列专用）：克隆存储后逐值 [deepCopyFn] 深拷贝，
     * 与旧 copyTo 语义逐字一致（防值对象原地修改泄漏到源快照）。
     * 先置 shared = false 再逐值拷贝——即使 [deepCopyFn] 中途抛异常，
     * 副本也持有私有存储（半克隆体被上层丢弃，不会残留共享标记）。
     */
    @PublishedApi internal fun adoptDeep(source: SparseArray<T>, deepCopyFn: (T) -> T) {
        store = source.clone()
        shared = false
        for (i in 0 until store.size()) store.setValueAt(i, deepCopyFn(store.valueAt(i)))
    }

    /** Copy-on-Write：共享存储首次写入前私有化（clone 为浅拷贝，值引用共享=旧语义） */
    private fun ensureOwned() {
        if (shared) { store = store.clone(); shared = false }
    }

    operator fun get(id: Int): T {
        // 区分"无条目"（抛异常）与"条目存在但值为 null"（nullable 列合法返回 null）
        if (store.indexOfKey(id) < 0) {
            throw NoSuchElementException("ComponentTable: no entry for id=$id")
        }
        return store[id]
    }
    fun getOrNull(id: Int): T? = store[id]
    fun getOrDefault(id: Int, default: T): T = store[id] ?: default
    operator fun set(id: Int, value: T) { requireWrite?.invoke(); ensureOwned(); store.put(id, value); onWrite?.invoke(); onIdWrite?.invoke(id) }
    fun update(id: Int, block: (T) -> T) { requireWrite?.invoke(); ensureOwned(); store[id] = block(store[id]); onWrite?.invoke(); onIdWrite?.invoke(id) }

    /**
     * 无回调写入（2026-08-01）：仅 COW 私有化 + 存储写入，不触发 onWrite/onIdWrite。
     * 供 Mutable 列 unmodifiable 包装专用——包装值写入新副本时不应产生脏标记/changedId。
     */
    @PublishedApi internal fun putNoCallback(id: Int, value: T) {
        ensureOwned(); store.put(id, value)
    }

    fun ids(): IntArray { val r = IntArray(store.size()); for (i in 0 until store.size()) r[i] = store.keyAt(i); return r }
    val size: Int get() = store.size()
    fun isEmpty(): Boolean = store.size() == 0
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    inline fun forEach(action: (Int, T) -> Unit) { for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i)) }
    inline fun forEachValue(action: (T) -> Unit) { for (i in 0 until store.size()) action(store.valueAt(i)) }
    fun values(): List<T> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: T) { requireWrite?.invoke(); ensureOwned(); store.put(id, value); onWrite?.invoke(); onIdWrite?.invoke(id) }
    fun remove(id: Int) { requireWrite?.invoke(); ensureOwned(); store.remove(id); onWrite?.invoke() }
    fun clear() { requireWrite?.invoke(); ensureOwned(); store.clear(); onWrite?.invoke() }
}

/**
 * 值对象 unmodifiable 包装（2026-08-01 Mutable 列浅共享防御）。
 * List/Set/Map 包装为不可变视图；其余类型原样返回。
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> wrapUnmodifiableValue(value: T): T = when (value) {
    is List<*> -> java.util.Collections.unmodifiableList(value) as T
    is Set<*> -> java.util.Collections.unmodifiableSet(value) as T
    is Map<*, *> -> java.util.Collections.unmodifiableMap(value) as T
    else -> value
}

class IntFlatArray @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal var values = IntArray(initialCapacity)
    @PublishedApi internal var idToSlot = IntArray(initialCapacity) { -1 }
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var size_ = 0
    private fun ensureCapacity(key: Int) {
        if (key >= values.size && key >= 0) {
            // 防篡改存档的非法极大 ID：key+1+64 在 Int.MAX_VALUE 附近溢出为负，
            // 直接 copyOf(Int.MAX_VALUE) 会 OOM。显式上限防御（游戏弟子数远小于此）。
            require(key < MAX_SAFE_CAPACITY) {
                "ComponentTable ID 超出上限 $MAX_SAFE_CAPACITY: $key（存档可能被篡改）"
            }
            val oldSize = values.size
            val newSize = maxOf(values.size * 2, key + 1 + 64)
            values = values.copyOf(newSize)
            idToSlot = idToSlot.copyOf(newSize)
            for (i in oldSize until newSize) idToSlot[i] = -1
        }
    }
    private fun growKeys() { keys = keys.copyOf(maxOf(keys.size * 2, 8)) }
    operator fun get(key: Int): Int = if (key >= 0 && key < values.size && idToSlot[key] >= 0) values[key] else 0
    fun get(key: Int, default: Int): Int = if (key >= 0 && key < values.size && idToSlot[key] >= 0) values[key] else default
    fun contains(key: Int): Boolean = key >= 0 && key < values.size && idToSlot[key] >= 0
    fun put(key: Int, value: Int) { if (key < 0) return; ensureCapacity(key); if (size_ >= keys.size) growKeys(); values[key] = value; if (idToSlot[key] < 0) { idToSlot[key] = size_; keys[size_] = key; size_++ } }
    fun update(key: Int, block: (Int) -> Int) {
        if (key < 0) return
        if (key >= values.size || idToSlot[key] < 0) {
            ensureCapacity(key)
            if (size_ >= keys.size) growKeys()
            idToSlot[key] = size_
            keys[size_] = key
            size_++
        }
        values[key] = block(values[key])
    }
    fun delete(key: Int) {
        if (key < 0 || key >= values.size || idToSlot[key] < 0) return
        val s = idToSlot[key]
        val l = size_ - 1
        if (l < 0) return
        if (s != l) { keys[s] = keys[l]; idToSlot[keys[s]] = s }
        idToSlot[key] = -1; values[key] = 0; size_--
    }
    fun clear() { for (i in 0 until values.size) values[i] = 0; for (i in 0 until idToSlot.size) idToSlot[i] = -1; for (i in 0 until size_) keys[i] = 0; size_ = 0 }
    /**
     * 写时复制私有化：复制全部平铺数组（O(capacity) 内存拷贝）。
     * 仅共享存储首次写入前调用一次。
     */
    fun copyForWrite(): IntFlatArray {
        val c = IntFlatArray(0)
        c.values = values.copyOf()
        c.idToSlot = idToSlot.copyOf()
        c.keys = keys.copyOf()
        c.size_ = size_
        return c
    }
    fun size(): Int = size_
    fun keyAt(index: Int): Int = if (index >= 0 && index < size_) keys[index] else throw IndexOutOfBoundsException("keyAt($index) out of bounds, size=$size_")
    fun valueAt(index: Int): Int = if (index >= 0 && index < size_) values[keys[index]] else throw IndexOutOfBoundsException("valueAt($index) out of bounds, size=$size_")
    fun indexOfKey(key: Int): Int = if (contains(key)) idToSlot[key] else -1
}

class DoubleFlatArray @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal var values = DoubleArray(initialCapacity)
    @PublishedApi internal var idToSlot = IntArray(initialCapacity) { -1 }
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var size_ = 0
    private fun ensureCapacity(key: Int) {
        if (key >= values.size && key >= 0) {
            // 防篡改存档的非法极大 ID：key+1+64 在 Int.MAX_VALUE 附近溢出为负，
            // 直接 copyOf(Int.MAX_VALUE) 会 OOM。显式上限防御（游戏弟子数远小于此）。
            require(key < MAX_SAFE_CAPACITY) {
                "ComponentTable ID 超出上限 $MAX_SAFE_CAPACITY: $key（存档可能被篡改）"
            }
            val oldSize = values.size
            val newSize = maxOf(values.size * 2, key + 1 + 64)
            values = values.copyOf(newSize)
            idToSlot = idToSlot.copyOf(newSize)
            for (i in oldSize until newSize) idToSlot[i] = -1
        }
    }
    private fun growKeys() { keys = keys.copyOf(maxOf(keys.size * 2, 8)) }
    operator fun get(key: Int): Double = if (key >= 0 && key < values.size && idToSlot[key] >= 0) values[key] else 0.0
    fun get(key: Int, default: Double): Double = if (key >= 0 && key < values.size && idToSlot[key] >= 0) values[key] else default
    fun contains(key: Int): Boolean = key >= 0 && key < values.size && idToSlot[key] >= 0
    fun put(key: Int, value: Double) { if (key < 0) return; ensureCapacity(key); if (size_ >= keys.size) growKeys(); values[key] = value; if (idToSlot[key] < 0) { idToSlot[key] = size_; keys[size_] = key; size_++ } }
    fun update(key: Int, block: (Double) -> Double) {
        if (key < 0) return
        if (key >= values.size || idToSlot[key] < 0) {
            ensureCapacity(key)
            if (size_ >= keys.size) growKeys()
            idToSlot[key] = size_
            keys[size_] = key
            size_++
        }
        values[key] = block(values[key])
    }
    fun delete(key: Int) {
        if (key < 0 || key >= values.size || idToSlot[key] < 0) return
        val s = idToSlot[key]
        val l = size_ - 1
        if (l < 0) return
        if (s != l) { keys[s] = keys[l]; idToSlot[keys[s]] = s }
        idToSlot[key] = -1; values[key] = 0.0; size_--
    }
    fun clear() { for (i in 0 until values.size) values[i] = 0.0; for (i in 0 until idToSlot.size) idToSlot[i] = -1; for (i in 0 until size_) keys[i] = 0; size_ = 0 }
    /**
     * 写时复制私有化：复制全部平铺数组（O(capacity) 内存拷贝）。
     * 仅共享存储首次写入前调用一次。
     */
    fun copyForWrite(): DoubleFlatArray {
        val c = DoubleFlatArray(0)
        c.values = values.copyOf()
        c.idToSlot = idToSlot.copyOf()
        c.keys = keys.copyOf()
        c.size_ = size_
        return c
    }
    fun size(): Int = size_
    fun keyAt(index: Int): Int = if (index >= 0 && index < size_) keys[index] else throw IndexOutOfBoundsException("keyAt($index) out of bounds, size=$size_")
    fun valueAt(index: Int): Double = if (index >= 0 && index < size_) values[keys[index]] else throw IndexOutOfBoundsException("valueAt($index) out of bounds, size=$size_")
    fun indexOfKey(key: Int): Int = if (contains(key)) idToSlot[key] else -1
}

class IntComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal var store = IntFlatArray(initialCapacity)
    private var shared = false
    private var onWrite: (() -> Unit)? = null
    /** 写入前守卫检查，由 [DiscipleTables.bindAllOnWrite] 绑定为 [DiscipleTables.requireWriteAccess] */
    private var requireWrite: (() -> Unit)? = null
    /** 按 id 的写入回调（2026-08-01 增量组装基建）：记录被写入的弟子 ID */
    private var onIdWrite: ((Int) -> Unit)? = null

    /** 设置写入守卫回调（update {} 事务外调用）。替换 @JvmField var requireWrite */
    fun setWriteGuard(callback: () -> Unit) { requireWrite = callback }
    /** 设置变更回调（update {} 事务外调用）。替换 @JvmField var onWrite */
    fun setMutationCallback(callback: () -> Unit) { onWrite = callback }
    /** 设置按 id 写入回调（update {} 事务外调用）。替换 @JvmField var onIdWrite */
    fun setIdWriteCallback(callback: (Int) -> Unit) { onIdWrite = callback }
    /**
     * 带守卫的跨表/外部写入：通过本表的写入守卫后，将值写入存储。
     * 替换对 `target.store.put(key, value)` 的直接访问。
     */
    @PublishedApi internal fun putTo(key: Int, value: Int) {
        requireWrite?.invoke(); ensureOwned(); store.put(key, value); onWrite?.invoke(); onIdWrite?.invoke(key)
    }

    /**
     * 事务缓冲共享源表存储（COW 快照隔离核心）。
     * 共享本身 O(1) 零数据复制；共享后**首次写入**该列时触发
     * [ensureOwned] 私有化（平铺数组整体 copyOf，O(capacity)），此后写私有副本。
     * 源表与旧快照不受影响。
     */
    @PublishedApi internal fun adopt(source: IntFlatArray) { store = source; shared = true }

    /** Copy-on-Write：共享存储首次写入前私有化 */
    private fun ensureOwned() {
        if (shared) { store = store.copyForWrite(); shared = false }
    }

    operator fun get(id: Int): Int = store[id]
    fun getOrDefault(id: Int, default: Int): Int = store.get(id, default)
    fun getOrNull(id: Int): Int? = if (store.contains(id)) store[id] else null

    /** 同值写短路（2026-08-01）：已存在且值相同则跳过——每旬热点满血重写不再触发脏标记/COW 私有化 */
    private fun isSameValue(id: Int, value: Int): Boolean = store.contains(id) && store[id] == value

    operator fun set(id: Int, value: Int) {
        requireWrite?.invoke(); if (isSameValue(id, value)) return
        ensureOwned(); store.put(id, value); onWrite?.invoke(); onIdWrite?.invoke(id)
    }
    fun update(id: Int, block: (Int) -> Int) { requireWrite?.invoke(); ensureOwned(); store.update(id, block); onWrite?.invoke(); onIdWrite?.invoke(id) }
    fun ids(): IntArray { val r = IntArray(store.size()); for (i in 0 until store.size()) r[i] = store.keyAt(i); return r }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    fun forEach(action: (Int, Int) -> Unit) { for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i)) }
    fun values(): List<Int> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Int) {
        requireWrite?.invoke(); if (isSameValue(id, value)) return
        ensureOwned(); store.put(id, value); onWrite?.invoke(); onIdWrite?.invoke(id)
    }
    fun remove(id: Int) { requireWrite?.invoke(); ensureOwned(); store.delete(id); onWrite?.invoke() }
    fun clear() { requireWrite?.invoke(); ensureOwned(); store.clear(); onWrite?.invoke() }
}

class DoubleComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal var store = DoubleFlatArray(initialCapacity)
    private var shared = false
    private var onWrite: (() -> Unit)? = null
    /** 写入前守卫检查，由 [DiscipleTables.bindAllOnWrite] 绑定为 [DiscipleTables.requireWriteAccess] */
    private var requireWrite: (() -> Unit)? = null
    /** 按 id 的写入回调（2026-08-01 增量组装基建）：记录被写入的弟子 ID */
    private var onIdWrite: ((Int) -> Unit)? = null

    /** 设置写入守卫回调（update {} 事务外调用）。替换 @JvmField var requireWrite */
    fun setWriteGuard(callback: () -> Unit) { requireWrite = callback }
    /** 设置变更回调（update {} 事务外调用）。替换 @JvmField var onWrite */
    fun setMutationCallback(callback: () -> Unit) { onWrite = callback }
    /** 设置按 id 写入回调（update {} 事务外调用）。替换 @JvmField var onIdWrite */
    fun setIdWriteCallback(callback: (Int) -> Unit) { onIdWrite = callback }
    /**
     * 带守卫的跨表/外部写入：通过本表的写入守卫后，将值写入存储。
     * 替换对 `target.store.put(key, value)` 的直接访问。
     */
    @PublishedApi internal fun putTo(key: Int, value: Double) {
        requireWrite?.invoke(); ensureOwned(); store.put(key, value); onWrite?.invoke(); onIdWrite?.invoke(key)
    }

    /**
     * 事务缓冲共享源表存储（COW 快照隔离核心）。
     * 共享本身 O(1) 零数据复制；共享后**首次写入**该列时触发
     * [ensureOwned] 私有化（平铺数组整体 copyOf，O(capacity)），此后写私有副本。
     * 源表与旧快照不受影响。
     */
    @PublishedApi internal fun adopt(source: DoubleFlatArray) { store = source; shared = true }

    /** Copy-on-Write：共享存储首次写入前私有化 */
    private fun ensureOwned() {
        if (shared) { store = store.copyForWrite(); shared = false }
    }

    /** 同值写短路（2026-08-01）：已存在且值相同则跳过——每旬热点重复写不再触发脏标记/COW 私有化 */
    private fun isSameValue(id: Int, value: Double): Boolean = store.contains(id) && store[id] == value

    operator fun get(id: Int): Double = store[id]
    fun getOrDefault(id: Int, default: Double): Double = store.get(id, default)
    operator fun set(id: Int, value: Double) {
        requireWrite?.invoke(); if (isSameValue(id, value)) return
        ensureOwned(); store.put(id, value); onWrite?.invoke(); onIdWrite?.invoke(id)
    }
    fun update(id: Int, block: (Double) -> Double) { requireWrite?.invoke(); ensureOwned(); store.update(id, block); onWrite?.invoke(); onIdWrite?.invoke(id) }
    fun ids(): IntArray { val r = IntArray(store.size()); for (i in 0 until store.size()) r[i] = store.keyAt(i); return r }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    fun forEach(action: (Int, Double) -> Unit) { for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i)) }
    fun values(): List<Double> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Double) {
        requireWrite?.invoke(); if (isSameValue(id, value)) return
        ensureOwned(); store.put(id, value); onWrite?.invoke(); onIdWrite?.invoke(id)
    }
    fun remove(id: Int) { requireWrite?.invoke(); ensureOwned(); store.delete(id); onWrite?.invoke() }
    fun clear() { requireWrite?.invoke(); ensureOwned(); store.clear(); onWrite?.invoke() }
}

sealed interface ComponentTableLike {
    fun remove(id: Int)
    fun clear()
    val size: Int
    val debugName: String
    fun contains(id: Int): Boolean
}
sealed interface CopyableTableRef : ComponentTableLike {
    /** 组件表在 _allCopyableRefs 中的索引，用于 DirtyTracker 位掩码。在 DiscipleTables 构造时自动分配。 */
    var columnIndex: Int
    fun copyTo(dest: DiscipleTables)
    /** 共享源表存储到 dest（O(1) 零数据复制，COW 快照隔离核心）。Mutable 列走急切深拷贝 [adoptDeep]。 */
    fun shareStoreTo(dest: DiscipleTables)
}

class IntTableRef(
    @JvmField val table: IntComponentTable,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, IntComponentTable>,
    override val debugName: String
) : CopyableTableRef {
    override var columnIndex: Int = -1
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun contains(id: Int): Boolean = table.contains(id)
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), table.store.valueAt(i)) }
    override fun shareStoreTo(dest: DiscipleTables) { destProp.get(dest).adopt(table.store) }
    /** 仅复制本表数据到 dest（用于 DirtyTracker 增量 deepCopy） */
    fun copySelfTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), table.store.valueAt(i)) }
}

class DoubleTableRef(
    @JvmField val table: DoubleComponentTable,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, DoubleComponentTable>,
    override val debugName: String
) : CopyableTableRef {
    override var columnIndex: Int = -1
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun contains(id: Int): Boolean = table.contains(id)
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), table.store.valueAt(i)) }
    override fun shareStoreTo(dest: DiscipleTables) { destProp.get(dest).adopt(table.store) }
    fun copySelfTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), table.store.valueAt(i)) }
}

class RefTableRef<T>(
    @JvmField val table: ComponentTable<T>,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, ComponentTable<T>>,
    override val debugName: String
) : CopyableTableRef {
    override var columnIndex: Int = -1
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun contains(id: Int): Boolean = table.contains(id)
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), table.store.valueAt(i)) }
    override fun shareStoreTo(dest: DiscipleTables) { destProp.get(dest).adopt(table.store) }
    fun copySelfTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), table.store.valueAt(i)) }
}

class MutableTableRef<T>(
    @JvmField val table: ComponentTable<T>,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, ComponentTable<T>>,
    override val debugName: String,
    private val deepCopyFn: (T) -> T
) : CopyableTableRef {
    override var columnIndex: Int = -1
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun contains(id: Int): Boolean = table.contains(id)
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), deepCopyFn(table.store.valueAt(i))) }

    /**
     * 2026-08-01 修复：Mutable 列改为 O(1) 浅共享（与 RefTableRef 一致）。
     *
     * 历史问题：旧实现每事务对全部弟子的 List/Map/Set 列急切深拷贝（adoptDeep）——
     * 全库审计确认 13 列写点均为整体重新赋值（无原地修改模式），急切深拷贝是纯浪费
     * （O(D×均值长度) 分配/GC，lifeEvents 逐年增长线性恶化）。
     * 安全性由 [DiscipleTables.mutableValueGuardEnabled]（Debug 开）的
     * unmodifiable 包装兜底：未来任何原地修改在事务缓冲上立即抛异常。
     * 包装用 putNoCallback（不触发 onWrite/onIdWrite）——包装值写入新副本
     * 不应产生脏标记或污染 changedIdTracker。
     */
    override fun shareStoreTo(dest: DiscipleTables) {
        val dst = destProp.get(dest)
        if (DiscipleTables.mutableValueGuardEnabled) {
            for (i in 0 until table.store.size()) {
                dst.putNoCallback(table.store.keyAt(i), wrapUnmodifiableValue(table.store.valueAt(i)))
            }
        } else {
            dst.adopt(table.store)
        }
    }
    fun copySelfTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.putTo(table.store.keyAt(i), deepCopyFn(table.store.valueAt(i))) }
}

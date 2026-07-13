package com.xianxia.sect.core.state

import android.util.SparseArray

/**
 * 线程安全由 GameStateStoreImpl.transactionLock（ReentrantLock）保证，
 * 所有写操作在 stateStore.update {} 内串行执行，无需额外同步。
 */
class ComponentTable<T> @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal val store = SparseArray<T>(initialCapacity)
    @JvmField var onWrite: (() -> Unit)? = null
    operator fun get(id: Int): T = store[id] ?: throw NoSuchElementException("ComponentTable: no entry for id=$id")
    fun getOrNull(id: Int): T? = store[id]
    fun getOrDefault(id: Int, default: T): T = store[id] ?: default
    operator fun set(id: Int, value: T) { store.put(id, value); onWrite?.invoke() }
    inline fun update(id: Int, block: (T) -> T) { store[id] = block(store[id]); onWrite?.invoke() }
    fun ids(): IntArray { val r = IntArray(store.size()); for (i in 0 until store.size()) r[i] = store.keyAt(i); return r }
    val size: Int get() = store.size()
    fun isEmpty(): Boolean = store.size() == 0
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    inline fun forEach(action: (Int, T) -> Unit) { for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i)) }
    inline fun forEachValue(action: (T) -> Unit) { for (i in 0 until store.size()) action(store.valueAt(i)) }
    fun values(): List<T> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: T) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.remove(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }
}

class IntFlatArray @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal var values = IntArray(initialCapacity)
    @PublishedApi internal var idToSlot = IntArray(initialCapacity) { -1 }
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var size_ = 0
    private fun ensureCapacity(key: Int) { if (key >= values.size) { val newSize = maxOf(values.size * 2, key + 1 + 64); values = values.copyOf(newSize); idToSlot = idToSlot.copyOf(newSize); for (i in values.size until newSize) idToSlot[i] = -1 } }
    private fun growKeys() { keys = keys.copyOf(maxOf(keys.size * 2, 8)) }
    operator fun get(key: Int): Int = if (key < values.size && idToSlot[key] >= 0) values[key] else 0
    fun get(key: Int, default: Int): Int = if (key < values.size && idToSlot[key] >= 0) values[key] else default
    fun contains(key: Int): Boolean = key < values.size && idToSlot[key] >= 0
    fun put(key: Int, value: Int) { ensureCapacity(key); if (size_ >= keys.size) growKeys(); values[key] = value; if (idToSlot[key] < 0) { idToSlot[key] = size_; keys[size_] = key; size_++ } }
    fun update(key: Int, block: (Int) -> Int) { val c = if (key < values.size && idToSlot[key] >= 0) values[key] else 0; val r = block(c); if (idToSlot[key] < 0) { ensureCapacity(key); if (size_ >= keys.size) growKeys(); idToSlot[key] = size_; keys[size_] = key; size_++ }; values[key] = r }
    fun delete(key: Int) { if (key >= values.size || idToSlot[key] < 0) return; val s = idToSlot[key]; val l = size_ - 1; if (s != l) { keys[s] = keys[l]; idToSlot[keys[s]] = s }; idToSlot[key] = -1; values[key] = 0; size_-- }
    fun clear() { for (i in 0 until values.size) values[i] = 0; for (i in 0 until idToSlot.size) idToSlot[i] = -1; for (i in 0 until size_) keys[i] = 0; size_ = 0 }
    fun size(): Int = size_
    fun keyAt(index: Int): Int = keys[index]
    fun valueAt(index: Int): Int = values[keys[index]]
    fun indexOfKey(key: Int): Int = if (contains(key)) 1 else -1
}

class DoubleFlatArray @JvmOverloads constructor(initialCapacity: Int = 64) {
    @PublishedApi internal var values = DoubleArray(initialCapacity)
    @PublishedApi internal var idToSlot = IntArray(initialCapacity) { -1 }
    @PublishedApi internal var keys = IntArray(initialCapacity)
    @PublishedApi internal var size_ = 0
    private fun ensureCapacity(key: Int) { if (key >= values.size) { val newSize = maxOf(values.size * 2, key + 1 + 64); values = values.copyOf(newSize); idToSlot = idToSlot.copyOf(newSize); for (i in values.size until newSize) idToSlot[i] = -1 } }
    private fun growKeys() { keys = keys.copyOf(maxOf(keys.size * 2, 8)) }
    operator fun get(key: Int): Double = if (key < values.size && idToSlot[key] >= 0) values[key] else 0.0
    fun get(key: Int, default: Double): Double = if (key < values.size && idToSlot[key] >= 0) values[key] else default
    fun contains(key: Int): Boolean = key < values.size && idToSlot[key] >= 0
    fun put(key: Int, value: Double) { ensureCapacity(key); if (size_ >= keys.size) growKeys(); values[key] = value; if (idToSlot[key] < 0) { idToSlot[key] = size_; keys[size_] = key; size_++ } }
    fun update(key: Int, block: (Double) -> Double) { val c = if (key < values.size && idToSlot[key] >= 0) values[key] else 0.0; val r = block(c); if (idToSlot[key] < 0) { ensureCapacity(key); if (size_ >= keys.size) growKeys(); idToSlot[key] = size_; keys[size_] = key; size_++ }; values[key] = r }
    fun delete(key: Int) { if (key >= values.size || idToSlot[key] < 0) return; val s = idToSlot[key]; val l = size_ - 1; if (s != l) { keys[s] = keys[l]; idToSlot[keys[s]] = s }; idToSlot[key] = -1; values[key] = 0.0; size_-- }
    fun clear() { for (i in 0 until values.size) values[i] = 0.0; for (i in 0 until idToSlot.size) idToSlot[i] = -1; for (i in 0 until size_) keys[i] = 0; size_ = 0 }
    fun size(): Int = size_
    fun keyAt(index: Int): Int = keys[index]
    fun valueAt(index: Int): Double = values[keys[index]]
    fun indexOfKey(key: Int): Int = if (contains(key)) 1 else -1
}

class IntComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = IntFlatArray(initialCapacity)
    @JvmField var onWrite: (() -> Unit)? = null
    operator fun get(id: Int): Int = store[id]
    fun getOrDefault(id: Int, default: Int): Int = store.get(id, default)
    fun getOrNull(id: Int): Int? = if (store.contains(id)) store[id] else null
    operator fun set(id: Int, value: Int) { store.put(id, value); onWrite?.invoke() }
    fun update(id: Int, block: (Int) -> Int) { store.update(id, block); onWrite?.invoke() }
    fun ids(): IntArray { val r = IntArray(store.size()); for (i in 0 until store.size()) r[i] = store.keyAt(i); return r }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    fun forEach(action: (Int, Int) -> Unit) { for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i)) }
    fun values(): List<Int> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Int) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.delete(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }
}

class DoubleComponentTable(initialCapacity: Int = 64) {
    @PublishedApi internal val store = DoubleFlatArray(initialCapacity)
    @JvmField var onWrite: (() -> Unit)? = null
    operator fun get(id: Int): Double = store[id]
    fun getOrDefault(id: Int, default: Double): Double = store.get(id, default)
    operator fun set(id: Int, value: Double) { store.put(id, value); onWrite?.invoke() }
    fun update(id: Int, block: (Double) -> Double) { store.update(id, block); onWrite?.invoke() }
    fun ids(): IntArray { val r = IntArray(store.size()); for (i in 0 until store.size()) r[i] = store.keyAt(i); return r }
    val size: Int get() = store.size()
    fun contains(id: Int): Boolean = store.indexOfKey(id) >= 0
    fun forEach(action: (Int, Double) -> Unit) { for (i in 0 until store.size()) action(store.keyAt(i), store.valueAt(i)) }
    fun values(): List<Double> = (0 until store.size()).map { store.valueAt(it) }
    fun put(id: Int, value: Double) { store.put(id, value); onWrite?.invoke() }
    fun remove(id: Int) { store.delete(id); onWrite?.invoke() }
    fun clear() { store.clear(); onWrite?.invoke() }
}

sealed interface ComponentTableLike { fun remove(id: Int); fun clear(); val size: Int; val debugName: String }
sealed interface CopyableTableRef : ComponentTableLike { fun copyTo(dest: DiscipleTables) }

class IntTableRef(
    @JvmField val table: IntComponentTable,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, IntComponentTable>,
    override val debugName: String
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.store.put(table.store.keyAt(i), table.store.valueAt(i)) }
}

class DoubleTableRef(
    @JvmField val table: DoubleComponentTable,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, DoubleComponentTable>,
    override val debugName: String
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.store.put(table.store.keyAt(i), table.store.valueAt(i)) }
}

class RefTableRef<T>(
    @JvmField val table: ComponentTable<T>,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, ComponentTable<T>>,
    override val debugName: String
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.store.put(table.store.keyAt(i), table.store.valueAt(i)) }
}

class MutableTableRef<T>(
    @JvmField val table: ComponentTable<T>,
    @JvmField val destProp: kotlin.reflect.KProperty1<DiscipleTables, ComponentTable<T>>,
    override val debugName: String,
    private val deepCopyFn: (T) -> T
) : CopyableTableRef {
    override fun remove(id: Int) = table.remove(id)
    override fun clear() = table.clear()
    override val size: Int get() = table.size
    override fun copyTo(dest: DiscipleTables) { val dst = destProp.get(dest); for (i in 0 until table.store.size()) dst.store.put(table.store.keyAt(i), deepCopyFn(table.store.valueAt(i))) }
}

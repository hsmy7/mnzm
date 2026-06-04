package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ObjectPoolTest {

    private lateinit var pool: ObjectPool

    @Before
    fun setUp() {
        pool = ObjectPool()
    }

    // ---- PoolFactory ----

    private val stringFactory = object : ObjectPool.PoolFactory<String> {
        override fun create(): String = StringBuilder().toString()
        override fun reset(instance: String) { /* strings are immutable, no-op */ }
        override fun validate(instance: String): Boolean = true
    }

    private val listFactory = object : ObjectPool.PoolFactory<MutableList<String>> {
        override fun create(): MutableList<String> = mutableListOf()
        override fun reset(instance: MutableList<String>) { instance.clear() }
        override fun validate(instance: MutableList<String>): Boolean = instance.size < 10000
    }

    // ---- registerPool / getPool ----

    @Test
    fun registerPool_createsPool() {
        val p = pool.registerPool("strings", stringFactory)
        assertNotNull(p)
    }

    @Test
    fun getPool_returnsRegisteredPool() {
        pool.registerPool("strings", stringFactory)
        val p = pool.getPool<String>("strings")
        assertNotNull(p)
    }

    @Test
    fun getPool_unregisteredKey_returnsNull() {
        assertNull(pool.getPool<String>("nonexistent"))
    }

    // ---- borrow / release ----

    @Test
    fun borrow_returnsNonNull() {
        pool.registerPool("lists", listFactory)
        val list = pool.borrow<MutableList<String>>("lists")
        assertNotNull(list)
    }

    @Test
    fun release_andBorrow_reusesObject() {
        val p = pool.registerPool("lists", listFactory, maxSize = 64)
        val list = p.borrow()
        list.add("test")
        p.release(list)
        val reused = p.borrow()
        // After release, the list should be reset (cleared)
        assertTrue(reused.isEmpty())
    }

    @Test
    fun borrow_fromUnregisteredKey_returnsNull() {
        assertNull(pool.borrow<String>("nonexistent"))
    }

    // ---- clear ----

    @Test
    fun clear_emptiesPool() {
        val p = pool.registerPool("lists", listFactory, maxSize = 64)
        // Borrow all initial objects and release them
        val borrowed = mutableListOf<MutableList<String>>()
        repeat(4) { borrowed.add(p.borrow()) }
        borrowed.forEach { p.release(it) }
        p.clear()
        val stats = p.getStats()
        assertEquals(0, stats.poolSize)
    }

    // ---- getStats ----

    @Test
    fun getStats_initialState() {
        val p = pool.registerPool("lists", listFactory, maxSize = 64)
        val stats = p.getStats()
        assertEquals("lists", stats.objectType)
        assertTrue(stats.createdCount > 0) // initial size = 16
    }

    @Test
    fun getStats_afterBorrow() {
        val p = pool.registerPool("lists", listFactory, maxSize = 64)
        p.borrow()
        val stats = p.getStats()
        assertEquals(1, stats.borrowedCount)
    }

    // ---- clearAll / cleanup ----

    @Test
    fun clearAll_clearsAllPools() {
        pool.registerPool("lists", listFactory)
        pool.clearAll()
        // No exception means success
    }

    @Test
    fun cleanup_clearsAndRemovesAllPools() {
        pool.registerPool("lists", listFactory)
        pool.cleanup()
        assertNull(pool.getPool<MutableList<String>>("lists"))
    }

    // ---- getAllStats ----

    @Test
    fun getAllStats_returnsStatsForAllPools() {
        pool.registerPool("lists", listFactory)
        pool.registerPool("strings", stringFactory)
        val stats = pool.getAllStats()
        assertEquals(2, stats.size)
    }

    @Test
    fun getAllStats_noPools_returnsEmpty() {
        val stats = pool.getAllStats()
        assertTrue(stats.isEmpty())
    }

    // ---- Pool maxSize ----

    @Test
    fun pool_doesNotExceedMaxSize() {
        val p = pool.registerPool("lists", listFactory, maxSize = 2)
        val a = p.borrow()
        val b = p.borrow()
        p.release(a)
        p.release(b)
        // Pool should have at most 2 items
        val stats = p.getStats()
        assertTrue(stats.poolSize <= 2)
    }

    // ---- TypedMapPool ----

    @Test
    fun typedMapPool_borrowAndRelease() {
        val mapPool = TypedMapPool<String, Int>(pool, "mapPool")
        mapPool.initialize(maxSize = 4)
        val map = mapPool.borrow()
        assertNotNull(map)
        map["key"] = 1
        mapPool.release(map)
        val reused = mapPool.borrow()
        assertTrue(reused.isEmpty())
    }

    // ---- TypedListPool ----

    @Test
    fun typedListPool_borrowAndRelease() {
        val listPool = TypedListPool<String>(pool, "listPool")
        listPool.initialize(maxSize = 4)
        val list = listPool.borrow()
        assertNotNull(list)
        list.add("item")
        listPool.release(list)
        val reused = listPool.borrow()
        assertTrue(reused.isEmpty())
    }

    // ---- StringBuilderPool ----

    @Test
    fun stringBuilderPool_borrowAndRelease() {
        val sbPool = StringBuilderPool(pool)
        sbPool.initialize(maxSize = 4)
        val sb = sbPool.borrow()
        assertNotNull(sb)
        sb.append("hello")
        sbPool.release(sb)
        val reused = sbPool.borrow()
        assertEquals(0, reused.length)
    }

    @Test
    fun stringBuilderPool_use_executesBlock() {
        val sbPool = StringBuilderPool(pool)
        sbPool.initialize(maxSize = 4)
        val result = sbPool.use { sb ->
            sb.append("test")
            sb.toString()
        }
        assertEquals("test", result)
    }
}

package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ListenerManagerTest {

    private lateinit var manager: ListenerManager<String>

    @Before
    fun setUp() {
        manager = ListenerManager()
    }

    // ---- add / remove / size / isEmpty / isNotEmpty ----

    @Test
    fun add_increasesSize() {
        manager.add("a")
        assertEquals(1, manager.size())
        manager.add("b")
        assertEquals(2, manager.size())
    }

    @Test
    fun remove_decreasesSize() {
        manager.add("a")
        manager.add("b")
        manager.remove("a")
        assertEquals(1, manager.size())
        assertEquals("b", manager.listeners[0])
    }

    @Test
    fun remove_nonExistent_noEffect() {
        manager.add("a")
        manager.remove("z")
        assertEquals(1, manager.size())
    }

    @Test
    fun isEmpty_newManager_returnsTrue() {
        assertTrue(manager.isEmpty())
    }

    @Test
    fun isEmpty_afterAdd_returnsFalse() {
        manager.add("a")
        assertFalse(manager.isEmpty())
    }

    @Test
    fun isNotEmpty_afterAdd_returnsTrue() {
        manager.add("a")
        assertTrue(manager.isNotEmpty())
    }

    // ---- clear ----

    @Test
    fun clear_removesAllListeners() {
        manager.add("a")
        manager.add("b")
        manager.clear()
        assertTrue(manager.isEmpty())
        assertEquals(0, manager.size())
    }

    // ---- listeners property ----

    @Test
    fun listeners_returnsCopy() {
        manager.add("a")
        val list = manager.listeners
        assertEquals(listOf("a"), list)
        // Modifying returned list should not affect manager
        // (toList() already creates a copy)
    }

    // ---- notify ----

    @Test
    fun notify_callsActionOnAllListeners() {
        val results = mutableListOf<String>()
        manager.add("a")
        manager.add("b")
        manager.notify { results.add(it) }
        assertEquals(listOf("a", "b"), results)
    }

    @Test
    fun notify_exceptionInOneListener_doesNotStopOthers() {
        val results = mutableListOf<String>()
        manager.add("a")
        manager.add("bad")
        manager.add("c")
        manager.notify { listener ->
            if (listener == "bad") throw RuntimeException("test error")
            results.add(listener)
        }
        assertEquals(listOf("a", "c"), results)
    }

    @Test
    fun notify_emptyManager_noException() {
        manager.notify { /* no-op */ }
    }

    // ---- notifySafe ----

    @Test
    fun notifySafe_returnsZeroErrorsWhenNoExceptions() {
        manager.add("a")
        manager.add("b")
        val errors = manager.notifySafe { /* no-op */ }
        assertEquals(0, errors)
    }

    @Test
    fun notifySafe_returnsErrorCount() {
        manager.add("a")
        manager.add("bad")
        manager.add("c")
        manager.add("bad2")
        val errors = manager.notifySafe { listener ->
            if (listener.startsWith("bad")) throw RuntimeException("test")
        }
        assertEquals(2, errors)
    }

    @Test
    fun notifySafe_exceptionDoesNotStopOthers() {
        val results = mutableListOf<String>()
        manager.add("a")
        manager.add("bad")
        manager.add("c")
        manager.notifySafe { listener ->
            if (listener == "bad") throw RuntimeException("test")
            results.add(listener)
        }
        assertEquals(listOf("a", "c"), results)
    }

    // ---- mapNotNull ----

    @Test
    fun mapNotNull_transformsListeners() {
        manager.add("1")
        manager.add("2")
        manager.add("3")
        val result = manager.mapNotNull { it.toIntOrNull() }
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun mapNotNull_filtersNulls() {
        manager.add("1")
        manager.add("not_a_number")
        manager.add("3")
        val result = manager.mapNotNull { it.toIntOrNull() }
        assertEquals(listOf(1, 3), result)
    }

    @Test
    fun mapNotNull_exceptionReturnsNull() {
        manager.add("1")
        manager.add("bad")
        manager.add("3")
        val result = manager.mapNotNull { listener ->
            if (listener == "bad") throw RuntimeException("test")
            listener.toIntOrNull()
        }
        assertEquals(listOf(1, 3), result)
    }

    @Test
    fun mapNotNull_emptyManager_returnsEmpty() {
        val result = manager.mapNotNull { it }
        assertTrue(result.isEmpty())
    }

    // ---- tag parameter ----

    @Test
    fun customTag_usedInManager() {
        val taggedManager = ListenerManager<String>("MyTag")
        taggedManager.add("a")
        // Tag is used for logging; just verify it doesn't crash
        taggedManager.notifySafe { /* no-op */ }
    }
}

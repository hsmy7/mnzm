package com.xianxia.sect.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StateFlowListUtilsTest {

    // ---- addItem ----

    @Test
    fun addItem_addsItemToList() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b"))
        StateFlowListUtils.addItem(flow, "c")
        assertEquals(listOf("a", "b", "c"), flow.value)
    }

    @Test
    fun addItem_toEmptyList() = runBlocking {
        val flow = MutableStateFlow(emptyList<String>())
        StateFlowListUtils.addItem(flow, "x")
        assertEquals(listOf("x"), flow.value)
    }

    // ---- addAll ----

    @Test
    fun addAll_addsMultipleItems() = runBlocking {
        val flow = MutableStateFlow(listOf("a"))
        StateFlowListUtils.addAll(flow, listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), flow.value)
    }

    @Test
    fun addAll_emptyItems_noChange() = runBlocking {
        val flow = MutableStateFlow(listOf("a"))
        StateFlowListUtils.addAll(flow, emptyList())
        assertEquals(listOf("a"), flow.value)
    }

    // ---- removeItem ----

    @Test
    fun removeItem_removesMatchingItem() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b", "c"))
        val result = StateFlowListUtils.removeItem(flow, predicate = { it == "b" })
        assertTrue(result)
        assertEquals(listOf("a", "c"), flow.value)
    }

    @Test
    fun removeItem_noMatch_returnsFalse() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b"))
        val result = StateFlowListUtils.removeItem(flow, predicate = { it == "z" })
        assertFalse(result)
        assertEquals(listOf("a", "b"), flow.value)
    }

    @Test
    fun removeItem_withQuantity_removesOnlySpecifiedCount() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "a", "a", "b"))
        val result = StateFlowListUtils.removeItem(flow, { it == "a" }, quantity = 2)
        assertTrue(result)
        assertEquals(listOf("a", "b"), flow.value)
    }

    // ---- removeItemById ----

    @Test
    fun removeItemById_removesById() = runBlocking {
        data class Item(val id: String, val name: String)
        val flow = MutableStateFlow(listOf(Item("1", "a"), Item("2", "b")))
        val result = StateFlowListUtils.removeItemById(flow, "1", Item::id)
        assertTrue(result)
        assertEquals(1, flow.value.size)
        assertEquals("2", flow.value[0].id)
    }

    // ---- removeWhere ----

    @Test
    fun removeWhere_removesAllMatching() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3, 4, 5))
        val removed = StateFlowListUtils.removeWhere(flow) { it % 2 == 0 }
        assertEquals(2, removed)
        assertEquals(listOf(1, 3, 5), flow.value)
    }

    @Test
    fun removeWhere_noMatch_returnsZero() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val removed = StateFlowListUtils.removeWhere(flow) { it > 100 }
        assertEquals(0, removed)
    }

    // ---- updateItem ----

    @Test
    fun updateItem_updatesMatchingItem() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val result = StateFlowListUtils.updateItem(flow, { it == 2 }) { it * 10 }
        assertTrue(result)
        assertEquals(listOf(1, 20, 3), flow.value)
    }

    @Test
    fun updateItem_noMatch_returnsFalse() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val result = StateFlowListUtils.updateItem(flow, { it == 99 }) { it * 10 }
        assertFalse(result)
        assertEquals(listOf(1, 2, 3), flow.value)
    }

    // ---- updateItemById ----

    @Test
    fun updateItemById_updatesById() = runBlocking {
        data class Item(val id: String, var value: Int)
        val flow = MutableStateFlow(listOf(Item("1", 10), Item("2", 20)))
        val result = StateFlowListUtils.updateItemById(flow, "1", Item::id) { it.copy(value = 100) }
        assertTrue(result)
        assertEquals(100, flow.value[0].value)
    }

    // ---- findItem ----

    @Test
    fun findItem_returnsMatchingItem() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b", "c"))
        val result = StateFlowListUtils.findItem(flow) { it == "b" }
        assertEquals("b", result)
    }

    @Test
    fun findItem_noMatch_returnsNull() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b", "c"))
        val result = StateFlowListUtils.findItem(flow) { it == "z" }
        assertNull(result)
    }

    // ---- findItemById ----

    @Test
    fun findItemById_returnsItem() = runBlocking {
        data class Item(val id: String, val name: String)
        val flow = MutableStateFlow(listOf(Item("1", "a"), Item("2", "b")))
        val result = StateFlowListUtils.findItemById(flow, "2", Item::id)
        assertNotNull(result)
        assertEquals("b", result!!.name)
    }

    // ---- hasItem ----

    @Test
    fun hasItem_matchingPredicate_returnsTrue() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b", "c"))
        assertTrue(StateFlowListUtils.hasItem(flow) { it == "b" })
    }

    @Test
    fun hasItem_noMatch_returnsFalse() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b", "c"))
        assertFalse(StateFlowListUtils.hasItem(flow) { it == "z" })
    }

    // ---- hasItemById ----

    @Test
    fun hasItemById_existingId_returnsTrue() = runBlocking {
        data class Item(val id: String)
        val flow = MutableStateFlow(listOf(Item("1"), Item("2")))
        assertTrue(StateFlowListUtils.hasItemById(flow, "1", Item::id))
    }

    @Test
    fun hasItemById_missingId_returnsFalse() = runBlocking {
        data class Item(val id: String)
        val flow = MutableStateFlow(listOf(Item("1"), Item("2")))
        assertFalse(StateFlowListUtils.hasItemById(flow, "99", Item::id))
    }

    // ---- clearList ----

    @Test
    fun clearList_emptiesList() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b"))
        StateFlowListUtils.clearList(flow)
        assertTrue(flow.value.isEmpty())
    }

    // ---- setList ----

    @Test
    fun setList_replacesList() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b"))
        StateFlowListUtils.setList(flow, listOf("x", "y", "z"))
        assertEquals(listOf("x", "y", "z"), flow.value)
    }

    // ---- atomicUpdate ----

    @Test
    fun atomicUpdate_transformsList() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val result = StateFlowListUtils.atomicUpdate(flow) { it.map { v -> v * 2 } }
        assertEquals(listOf(2, 4, 6), result)
        assertEquals(listOf(2, 4, 6), flow.value)
    }

    // ---- atomicRead ----

    @Test
    fun atomicRead_readsValue() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val result = StateFlowListUtils.atomicRead(flow) { it.sum() }
        assertEquals(6, result)
    }

    // ---- addItemSuspend (alias for addItem) ----

    @Test
    fun addItemSuspend_addsItem() = runBlocking {
        val flow = MutableStateFlow(listOf("a"))
        StateFlowListUtils.addItemSuspend(flow, "b")
        assertEquals(listOf("a", "b"), flow.value)
    }

    // ---- addAllSuspend ----

    @Test
    fun addAllSuspend_addsItems() = runBlocking {
        val flow = MutableStateFlow(listOf("a"))
        StateFlowListUtils.addAllSuspend(flow, listOf("b", "c"))
        assertEquals(listOf("a", "b", "c"), flow.value)
    }

    // ---- removeItemSuspend ----

    @Test
    fun removeItemSuspend_removesItem() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b", "c"))
        val result = StateFlowListUtils.removeItemSuspend(flow, predicate = { it == "b" })
        assertTrue(result)
        assertEquals(listOf("a", "c"), flow.value)
    }

    // ---- updateItemSuspend ----

    @Test
    fun updateItemSuspend_updatesItem() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val result = StateFlowListUtils.updateItemSuspend(flow, { it == 2 }) { it * 10 }
        assertTrue(result)
        assertEquals(listOf(1, 20, 3), flow.value)
    }

    // ---- clearListSuspend ----

    @Test
    fun clearListSuspend_clearsList() = runBlocking {
        val flow = MutableStateFlow(listOf("a", "b"))
        StateFlowListUtils.clearListSuspend(flow)
        assertTrue(flow.value.isEmpty())
    }

    // ---- setListSuspend ----

    @Test
    fun setListSuspend_setsList() = runBlocking {
        val flow = MutableStateFlow(listOf("a"))
        StateFlowListUtils.setListSuspend(flow, listOf("x", "y"))
        assertEquals(listOf("x", "y"), flow.value)
    }

    // ---- withLockSuspend ----

    @Test
    fun withLockSuspend_executesBlock() = runBlocking {
        val flow = MutableStateFlow(listOf(1, 2))
        StateFlowListUtils.withLockSuspend(flow) { f ->
            f.value = f.value + 3
        }
        assertEquals(listOf(1, 2, 3), flow.value)
    }
}

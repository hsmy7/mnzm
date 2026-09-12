package com.xianxia.sect.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AtomicStateFlowUpdatesTest {

    // ---- atomicUpdate (suspend) ----

    @Test
    fun atomicUpdate_transformsValue() = runBlocking {
        val flow = MutableStateFlow(10)
        val result = AtomicStateFlowUpdates.atomicUpdate(flow) { it + 5 }
        assertEquals(15, result)
        assertEquals(15, flow.value)
    }

    @Test
    fun atomicUpdate_multipleUpdates_sequential() = runBlocking {
        val flow = MutableStateFlow(0)
        AtomicStateFlowUpdates.atomicUpdate(flow) { it + 1 }
        AtomicStateFlowUpdates.atomicUpdate(flow) { it + 2 }
        AtomicStateFlowUpdates.atomicUpdate(flow) { it + 3 }
        assertEquals(6, flow.value)
    }

    // ---- atomicUpdateWithResult ----

    @Test
    fun atomicUpdateWithResult_returnsResultTrue() = runBlocking {
        val flow = MutableStateFlow(5)
        val (newValue, result) = AtomicStateFlowUpdates.atomicUpdateWithResult(flow) {
            it + 10 to true
        }
        assertEquals(15, newValue)
        assertTrue(result)
        assertEquals(15, flow.value)
    }

    @Test
    fun atomicUpdateWithResult_returnsResultFalse() = runBlocking {
        val flow = MutableStateFlow(5)
        val (newValue, result) = AtomicStateFlowUpdates.atomicUpdateWithResult(flow) {
            it to false
        }
        assertEquals(5, newValue)
        assertFalse(result)
    }

    // ---- atomicUpdateSync ----

    @Test
    fun atomicUpdateSync_transformsValue() {
        val flow = MutableStateFlow(10)
        val result = AtomicStateFlowUpdates.atomicUpdateSync(flow) { it * 2 }
        assertEquals(20, result)
        assertEquals(20, flow.value)
    }

    @Test
    fun atomicUpdateSync_multipleUpdates() {
        val flow = MutableStateFlow(0)
        AtomicStateFlowUpdates.atomicUpdateSync(flow) { it + 1 }
        AtomicStateFlowUpdates.atomicUpdateSync(flow) { it + 2 }
        assertEquals(3, flow.value)
    }

    // ---- atomicRead ----

    @Test
    fun atomicRead_readsValue() {
        val flow = MutableStateFlow(42)
        val result = AtomicStateFlowUpdates.atomicRead(flow) { it * 2 }
        assertEquals(84, result)
        assertEquals(42, flow.value) // value unchanged
    }

    @Test
    fun atomicRead_complexRead() {
        val flow = MutableStateFlow(listOf(1, 2, 3))
        val result = AtomicStateFlowUpdates.atomicRead(flow) { it.sum() }
        assertEquals(6, result)
    }

    // ---- Extension functions ----

    @Test
    fun extension_atomicUpdate() = runBlocking {
        val flow = MutableStateFlow(10)
        val result = flow.atomicUpdate { it + 5 }
        assertEquals(15, result)
        assertEquals(15, flow.value)
    }

    @Test
    fun extension_atomicUpdateWithResult() = runBlocking {
        val flow = MutableStateFlow(5)
        val (newValue, result) = flow.atomicUpdateWithResult { it + 10 to true }
        assertEquals(15, newValue)
        assertTrue(result)
    }

    @Test
    fun extension_atomicUpdateSync() {
        val flow = MutableStateFlow(10)
        val result = flow.atomicUpdateSync { it * 3 }
        assertEquals(30, result)
        assertEquals(30, flow.value)
    }

    // ---- getMutex / getLock internal ----

    @Test
    fun getMutex_sameFlow_returnsSameMutex() {
        val flow = MutableStateFlow(0)
        val m1 = AtomicStateFlowUpdates.getMutex(flow)
        val m2 = AtomicStateFlowUpdates.getMutex(flow)
        assertSame(m1, m2)
    }

    @Test
    fun getMutex_differentFlows_returnsDifferentMutex() {
        val flow1 = MutableStateFlow(0)
        val flow2 = MutableStateFlow(0)
        val m1 = AtomicStateFlowUpdates.getMutex(flow1)
        val m2 = AtomicStateFlowUpdates.getMutex(flow2)
        assertNotSame(m1, m2)
    }

    @Test
    fun getLock_sameFlow_returnsSameLock() {
        val flow = MutableStateFlow(0)
        val l1 = AtomicStateFlowUpdates.getLock(flow)
        val l2 = AtomicStateFlowUpdates.getLock(flow)
        assertSame(l1, l2)
    }

    @Test
    fun getLock_differentFlows_returnsDifferentLock() {
        val flow1 = MutableStateFlow(0)
        val flow2 = MutableStateFlow(0)
        val l1 = AtomicStateFlowUpdates.getLock(flow1)
        val l2 = AtomicStateFlowUpdates.getLock(flow2)
        assertNotSame(l1, l2)
    }
}

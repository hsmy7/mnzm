package com.xianxia.sect.data.concurrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 存档锁管理器（单档单锁）单元测试。
 *
 * 「读锁/写锁」实为同一把排他 Mutex：本测试锁定排他语义与
 * 全局锁独立可用两条契约。
 */
class SaveLockManagerTest {

    private lateinit var lockManager: SaveLockManager

    @Before
    fun setUp() {
        lockManager = SaveLockManager()
    }

    // ==================== withWriteLockLight ====================

    @Test
    fun `withWriteLockLight - executes block and returns result`() = runTest {
        val result = lockManager.withWriteLockLight { 100 }
        assertEquals(100, result)
    }

    @Test
    fun `withWriteLockLight - is exclusive across calls`() {
        runBlocking {
            var concurrent = 0
            var peak = 0
            val jobs = (1..20).map {
                launch(Dispatchers.Default) {
                    lockManager.withWriteLockLight {
                        concurrent++
                        peak = maxOf(peak, concurrent)
                        delay(1)
                        concurrent--
                    }
                }
            }
            jobs.forEach { it.join() }
            assertEquals("排他锁内不得并发", 1, peak)
        }
    }

    // ==================== withReadLockLight ====================

    @Test
    fun `withReadLockLight - executes block and returns result`() = runTest {
        val result = lockManager.withReadLockLight { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `withReadLockLight - serializes with write lock`() {
        runBlocking {
            var concurrent = 0
            var peak = 0
            val jobs = (1..20).map { i ->
                launch(Dispatchers.Default) {
                    if (i % 2 == 0) {
                        lockManager.withWriteLockLight {
                            concurrent++
                            peak = maxOf(peak, concurrent)
                            delay(1)
                            concurrent--
                        }
                    } else {
                        lockManager.withReadLockLight {
                            concurrent++
                            peak = maxOf(peak, concurrent)
                            delay(1)
                            concurrent--
                        }
                    }
                }
            }
            jobs.forEach { job: Job -> job.join() }
            assertEquals("读锁与写锁同把互斥，不得并发", 1, peak)
        }
    }

    // ==================== 全局锁 ====================

    @Test
    fun `withGlobalWriteLockLight - executes block`() = runTest {
        val result = lockManager.withGlobalWriteLockLight { "global" }
        assertEquals("global", result)
    }

    @Test
    fun `withGlobalReadLockLight - executes block`() = runTest {
        val result = lockManager.withGlobalReadLockLight { listOf(1, 2) }
        assertTrue(result.isNotEmpty())
    }
}

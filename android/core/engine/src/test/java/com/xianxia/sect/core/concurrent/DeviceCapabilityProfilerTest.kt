package com.xianxia.sect.core.concurrent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 设备能力分析器测试。
 *
 * 验证 [DeviceCapabilityProfiler] 的硬件检测逻辑和线程池创建。
 * 注意：测试结果取决于运行环境的实际 CPU/内存配置。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DeviceCapabilityProfilerTest { // 2026-08-14: 2.5 双任务拆分标记（注解顺序在前）

    private val profiler = DeviceCapabilityProfiler()

    @Test
    fun `totalCores - returns at least 1`() {
        assertTrue("CPU cores should be >= 1", profiler.totalCores >= 1)
    }

    @Test
    fun `totalRamGB - returns at least 1`() {
        assertTrue("RAM should be >= 1GB", profiler.totalRamGB >= 1)
    }

    @Test
    fun `recommendedWorkerCount - always at least 1`() {
        assertTrue("Worker count should be >= 1", profiler.recommendedWorkerCount >= 1)
    }

    @Test
    fun `enableParallelTick - true when workerCount greater than 1`() {
        assertEquals(
            "enableParallelTick should match workerCount > 1",
            profiler.recommendedWorkerCount > 1,
            profiler.enableParallelTick
        )
    }

    @Test
    fun `enableBackgroundJobs - true when 4GB+ RAM`() {
        assertEquals(
            "Background jobs should be enabled with >= 4GB RAM",
            profiler.totalRamGB >= 4,
            profiler.enableBackgroundJobs
        )
    }

    @Test
    fun `batchDisabled - true when low end`() {
        assertEquals(
            "batchDisabled should match isLowEnd",
            profiler.isLowEnd,
            profiler.batchDisabled
        )
    }

    @Test
    fun `parallelDispatcher - runs tasks on worker threads`() = runBlocking {
        val mainThreadName = Thread.currentThread().name
        var workerThreadName: String? = null
        val result = withContext(profiler.parallelDispatcher) {
            workerThreadName = Thread.currentThread().name
            42
        }
        assertEquals("Task should return correct value", 42, result)
        assertNotNull("Worker thread should be assigned", workerThreadName)
        assertNotEquals(
            "Worker thread should differ from test thread",
            mainThreadName, workerThreadName
        )
    }

    @Test
    fun `backgroundDispatcher - runs tasks on bg threads`() = runBlocking {
        val mainThreadName = Thread.currentThread().name
        var workerThreadName: String? = null
        val result = withContext(profiler.backgroundDispatcher) {
            workerThreadName = Thread.currentThread().name
            "bg"
        }
        assertEquals("Background task should return correct value", "bg", result)
        assertNotNull("Background thread should be assigned", workerThreadName)
        assertNotEquals(
            "Background thread should differ from test thread",
            mainThreadName, workerThreadName
        )
    }

    @Test
    fun `summary - contains key fields`() {
        val summary = profiler.summary
        assertTrue("Summary contains DeviceCapability prefix", summary.startsWith("DeviceCapability"))
        assertTrue("Summary mentions cores", summary.contains("cores="))
        assertTrue("Summary mentions ram", summary.contains("ram="))
        assertTrue("Summary mentions workers", summary.contains("workers="))
    }
}

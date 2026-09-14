package com.xianxia.sect.core

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CrashRecoveryEngine 失败台账测试。
 *
 * 覆盖维度（根治判据 R2：学习是单调状态机，无「读取前被无条件清零」路径）：
 * - 残留消费：写前标记残留 → kill+1 且标记自清；无残留 → 计数保持（不清零）
 * - 窗口语义：窗口外失败重新从 1 计
 * - 时间衰减：14 天无失败 → onCleanLaunch 清零台账
 * - 成功清零：Vulkan 建链成功 → 计数清零 + GPU 信息落台账
 * - GLES 降级判定：kill/soft ≥ 3（窗口内）→ shouldPreferGles
 * - 安全模式：归因收窄 / 24h 滚动窗口 / 7 天 TTL 自动解除
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S]) // API 31（targetSdk 35 超 Robolectric 上限 34，需固定）
class CrashRecoveryEngineLedgerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun ledgerPrefs() =
        context.getSharedPreferences("crash_recovery", Context.MODE_PRIVATE)

    @Before
    fun setup() {
        ledgerPrefs().edit().clear().commit()
        CrashRecoveryEngine.initialize(context)
    }

    // ── 残留增量消费 ──────────────────────────────────────────────

    @Test
    fun `prewarm 残留消费为 kill 1 且标记自清`() {
        CrashRecoveryEngine.markPrewarmStarted()
        CrashRecoveryEngine.onCleanLaunch()
        assertEquals(1, CrashRecoveryEngine.getVkKillCount())
        assertFalse("标记必须被增量消费清除", CrashRecoveryEngine.wasPrewarmKilled())
    }

    @Test
    fun `surface init 残留消费为 kill`() {
        CrashRecoveryEngine.markSurfaceInitStarted()
        CrashRecoveryEngine.onCleanLaunch()
        assertEquals(1, CrashRecoveryEngine.getVkKillCount())
        assertFalse(CrashRecoveryEngine.wasSurfaceInitKilled())
    }

    @Test
    fun `无残留的干净启动不清零 kill 计数`() {
        // 前次启动消费了残留 kill=1；本次干净启动（进程正常死）
        CrashRecoveryEngine.markPrewarmStarted()
        CrashRecoveryEngine.onCleanLaunch()
        assertEquals(1, CrashRecoveryEngine.getVkKillCount())
        // 再次干净启动：无残留 → kill 保持 1（旧实现会被无条件清零——死代码根因）
        CrashRecoveryEngine.onCleanLaunch()
        assertEquals("干净启动不得清零台账", 1, CrashRecoveryEngine.getVkKillCount())
    }

    // ── 窗口语义 ──────────────────────────────────────────────────

    @Test
    fun `窗口外失败重新从 1 计`() {
        // 4 天前有 2 次 kill
        ledgerPrefs().edit()
            .putInt("vk_kill_count", 2)
            .putLong("vk_last_failure_at", System.currentTimeMillis() - 4 * 24 * 3_600_000L)
            .commit()
        CrashRecoveryEngine.recordVulkanSoftFailure("initSurface")
        assertEquals("窗口外重新从 1 计（soft 计数独立）", 1, CrashRecoveryEngine.getVkSoftFailCount())
    }

    // ── 时间衰减 ──────────────────────────────────────────────────

    @Test
    fun `14 天无失败衰减清零台账`() {
        ledgerPrefs().edit()
            .putInt("vk_kill_count", 3)
            .putInt("vk_soft_fail_count", 3)
            .putLong("vk_last_failure_at", System.currentTimeMillis() - 15 * 24 * 3_600_000L)
            .commit()
        CrashRecoveryEngine.onCleanLaunch()
        assertTrue(CrashRecoveryEngine.shouldPreferGles().not())
        assertEquals(0, CrashRecoveryEngine.getVkKillCount())
        assertEquals(0, CrashRecoveryEngine.getVkSoftFailCount())
    }

    @Test
    fun `窗口内达标仍降 GLES`() {
        ledgerPrefs().edit()
            .putInt("vk_kill_count", 3)
            .putLong("vk_last_failure_at", System.currentTimeMillis())
            .commit()
        assertTrue(CrashRecoveryEngine.shouldPreferGles())
        assertTrue(CrashRecoveryEngine.isVulkanCrashLoop())
    }

    @Test
    fun `soft fail 达阈值同样降 GLES`() {
        repeat(3) { CrashRecoveryEngine.recordVulkanSoftFailure("initSurface") }
        assertTrue(CrashRecoveryEngine.shouldPreferGles())
        assertFalse("soft-fail 不是崩溃循环", CrashRecoveryEngine.isVulkanCrashLoop())
    }

    // ── 成功清零 + GPU 信息落台账 ─────────────────────────────────

    @Test
    fun `建链成功清零计数并持久化设备信息`() {
        ledgerPrefs().edit()
            .putInt("vk_kill_count", 3)
            .putLong("vk_last_failure_at", System.currentTimeMillis())
            .commit()
        assertTrue(CrashRecoveryEngine.isVulkanCrashLoop())

        CrashRecoveryEngine.recordVulkanSuccess(
            vendorId = 0x5143, apiVersion = (1 shl 22) or (3 shl 12), driverVersion = 0x0A540C5D,
            deviceName = "Adreno (TM) 740"
        )
        assertFalse("能建链已证明设备可用", CrashRecoveryEngine.isVulkanCrashLoop())
        assertFalse(CrashRecoveryEngine.shouldPreferGles())

        val gpu = CrashRecoveryEngine.readPersistedGpuInfo()
        assertNotNull(gpu)
        assertEquals("Adreno (TM) 740", gpu?.deviceName)
        assertEquals(GpuVendor.QUALCOMM, gpu?.vendor)
    }

    @Test
    fun `无台账时 GPU 信息返回 null`() {
        assertNull(CrashRecoveryEngine.readPersistedGpuInfo())
    }

    // ── 安全模式（归因收窄 + 滚动窗口 + TTL） ────────────────

    @Test
    fun `非渲染线程崩溃不触发安全模式`() {
        CrashRecoveryEngine.recordCrash("NullPointerException", threadName = "main")
        CrashRecoveryEngine.recordCrash("OOM at dalvik", threadName = "FinalizerDaemon")
        assertFalse(CrashRecoveryEngine.isSafeMode())
        assertEquals(0, CrashRecoveryEngine.getRenderCrashCountInWindow())
    }

    @Test
    fun `渲染链崩溃计入窗口`() {
        CrashRecoveryEngine.recordCrash(null, threadName = "NativeRenderer")
        CrashRecoveryEngine.recordCrash("SIGSEGV in vkCreateShaderModule", threadName = "VulkanInit")
        assertEquals(2, CrashRecoveryEngine.getRenderCrashCountInWindow())
        assertFalse(CrashRecoveryEngine.isSafeMode())
    }

    @Test
    fun `24h 窗口内 3 次渲染崩溃触发安全模式`() {
        CrashRecoveryEngine.recordCrash(null, threadName = "NativeRenderer")
        CrashRecoveryEngine.recordCrash(null, threadName = "NativeRenderer")
        CrashRecoveryEngine.recordCrash(null, threadName = "NativeRenderer")
        assertTrue(CrashRecoveryEngine.isSafeMode())
    }

    @Test
    fun `堆栈含渲染特征也归因渲染链`() {
        CrashRecoveryEngine.recordCrash(
            "at com.xianxia.sect.core.nativebridge.NativeBridge.initRenderer(Native Method)",
            threadName = "main"
        )
        assertEquals(1, CrashRecoveryEngine.getRenderCrashCountInWindow())
    }

    @Test
    fun `安全模式 7 天 TTL 自动解除`() {
        ledgerPrefs().edit()
            .putBoolean("render_safe_mode", true)
            .putLong("safe_mode_entered_at", System.currentTimeMillis() - 8 * 24 * 3_600_000L)
            .commit()
        assertFalse("超 TTL 自动解除", CrashRecoveryEngine.isSafeMode())
        assertFalse("解除后持久标记被清",
            ledgerPrefs().getBoolean("render_safe_mode", false))
    }

    @Test
    fun `leaveSafeMode 清除崩溃窗口`() {
        CrashRecoveryEngine.recordCrash(null, threadName = "NativeRenderer")
        CrashRecoveryEngine.leaveSafeMode()
        assertEquals(0, CrashRecoveryEngine.getRenderCrashCountInWindow())
        assertFalse(CrashRecoveryEngine.isSafeMode())
    }

    // ── 渲染回退持久化 ────────────────────────────────────

    @Test
    fun `回退摘要落盘并可读回`() {
        CrashRecoveryEngine.recordLastFallback("VULKAN>GLES|VK_SWAPCHAIN|1520|Adreno (TM) 740|null")
        assertEquals(
            "VULKAN>GLES|VK_SWAPCHAIN|1520|Adreno (TM) 740|null",
            CrashRecoveryEngine.getLastFallback()
        )
    }
}

package com.xianxia.sect.core

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.VulkanPolicy.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VulkanPolicy 量化阈值判定测试（缺口 G4 渲染路径修正·次主线）。
 *
 * 覆盖：
 * - evaluateVulkanTier：按 GPU 厂商 + Vulkan API 版本（Unity Device Filtering 规格）判定
 *   ——低于厂商阈值 → PROBLEMATIC（Deny Vulkan），其余 → SAFE（默认 Allow，窄 Deny）。
 * - setVulkanDeviceInfo：低于阈值 → 记录 Vulkan 初始化失败（下次启动走 GPU GLES）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S]) // API 31（Robolectric 上限 34；targetSdk 35 超上限，需固定）
class VulkanPolicyQuantizedThresholdTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun vkMake(major: Int, minor: Int, patch: Int): Int =
        (major shl 22) or (minor shl 12) or patch

    @Before
    fun setup() {
        CrashRecoveryEngine.initialize(context)
        VulkanPolicy.initialize(context)
        // 清掉可能由其他用例/先前运行留下的 Vulkan init failure 持久标记，保证隔离
        CrashRecoveryEngine.clearVulkanInitFailure()
    }

    // ── evaluateVulkanTier：各厂商阈值边界 ──────────────────────────────

    @Test
    fun `arm mali below api threshold is denied`() {
        val info = VulkanDeviceInfo(GpuVendor.ARM_MALI, vkMake(1, 0, 60), 0, "Mali-G57")
        assertEquals(DeviceTier.PROBLEMATIC, evaluateVulkanTier(info))
    }

    @Test
    fun `arm mali at api threshold allowed`() {
        val info = VulkanDeviceInfo(GpuVendor.ARM_MALI, vkMake(1, 0, 61), 0, "Mali-G615")
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(info))
    }

    @Test
    fun `arm mali above api threshold allowed`() {
        val info = VulkanDeviceInfo(GpuVendor.ARM_MALI, vkMake(1, 1, 0), 0, "Mali-G720")
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(info))
    }

    @Test
    fun `qualcomm below api threshold denied`() {
        val info = VulkanDeviceInfo(GpuVendor.QUALCOMM, vkMake(1, 0, 48), 0, "Adreno 610")
        assertEquals(DeviceTier.PROBLEMATIC, evaluateVulkanTier(info))
    }

    @Test
    fun `qualcomm at api threshold allowed`() {
        val info = VulkanDeviceInfo(GpuVendor.QUALCOMM, vkMake(1, 0, 49), 0, "Adreno 640")
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(info))
    }

    @Test
    fun `imagination below api threshold denied`() {
        val info = VulkanDeviceInfo(GpuVendor.IMAGINATION, vkMake(1, 1, 169), 0, "PowerVR")
        assertEquals(DeviceTier.PROBLEMATIC, evaluateVulkanTier(info))
    }

    @Test
    fun `imagination at api threshold allowed`() {
        val info = VulkanDeviceInfo(GpuVendor.IMAGINATION, vkMake(1, 1, 170), 0, "PowerVR")
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(info))
    }

    @Test
    fun `nvidia below api threshold denied`() {
        val info = VulkanDeviceInfo(GpuVendor.NVIDIA, vkMake(1, 0, 12), 0, "Adreno/other")
        assertEquals(DeviceTier.PROBLEMATIC, evaluateVulkanTier(info))
    }

    @Test
    fun `unknown vendor defaults allowed`() {
        val info = VulkanDeviceInfo(GpuVendor.UNKNOWN, vkMake(1, 0, 0), 0, "?")
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(info))
    }

    @Test
    fun `other vendor defaults allowed`() {
        val info = VulkanDeviceInfo(GpuVendor.OTHER, vkMake(1, 0, 0), 0, "Other")
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(info))
    }

    @Test
    fun `null info defaults allowed`() {
        assertEquals(DeviceTier.SAFE, evaluateVulkanTier(null))
    }

    // ── setVulkanDeviceInfo：低于阈值 → 记录初始化失败 ────────────────────

    @Test
    fun `setVulkanDeviceInfo below threshold records init failure`() {
        VulkanPolicy.setVulkanDeviceInfo(
            GpuVendor.ARM_MALI.vendorId, vkMake(1, 0, 60), 0, "Mali-G57"
        )
        assertTrue(CrashRecoveryEngine.hasVulkanInitFailure())
        assertEquals("Mali-G57", VulkanPolicy.getDeviceInfo()?.deviceName)
        assertEquals(GpuVendor.ARM_MALI, VulkanPolicy.getDeviceInfo()?.vendor)
        CrashRecoveryEngine.clearVulkanInitFailure()
    }

    @Test
    fun `setVulkanDeviceInfo above threshold does not record failure`() {
        VulkanPolicy.setVulkanDeviceInfo(
            GpuVendor.QUALCOMM.vendorId, vkMake(1, 0, 49), 0, "Adreno 640"
        )
        assertTrue(!CrashRecoveryEngine.hasVulkanInitFailure())
        assertEquals(GpuVendor.QUALCOMM, VulkanPolicy.getDeviceInfo()?.vendor)
    }

    @Test
    fun `detectTier reflects probed below-threshold device`() {
        // 模拟 prewarm 后上报设备信息，detectTier 应回到 PROBLEMATIC（量化后置判定）
        VulkanPolicy.setVulkanDeviceInfo(
            GpuVendor.ARM_MALI.vendorId, vkMake(1, 0, 60), 0, "Mali-G57"
        )
        assertEquals(DeviceTier.PROBLEMATIC, VulkanPolicy.detectTier(context))
        CrashRecoveryEngine.clearVulkanInitFailure()
    }
}

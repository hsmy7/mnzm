package com.xianxia.sect.core

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.VulkanPolicy.DeviceTier
import com.xianxia.sect.core.VulkanPolicy.RenderStrategy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VulkanPolicy 单元测试。
 *
 * 覆盖维度：
 * - isKnownGoodOldDevice() 白名单/黑名单逻辑
 * - getRenderStrategy() API < 31 保守策略
 * - getRenderStrategy() API >= 31 行为不变
 */
@RunWith(RobolectricTestRunner::class)
class VulkanPolicyTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() {
        // CrashRecoveryEngine 和 VulkanPolicy 需要在使用前初始化
        // （正常流程中由 XianxiaApplication.onCreate 完成）
        CrashRecoveryEngine.initialize(context)
        VulkanPolicy.initialize(context)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O]) // API 26
    fun `getRenderStrategy API26 非白名单返回GLES_PREFERRED`() {
        // Robolectric 默认 Build.MANUFACTURER = "unknown" → 非白名单；旧 API 非白名单
        // 设备有 GPU 但 Vulkan 驱动不可靠 → GPU GLES 中间层
        val strategy = VulkanPolicy.getRenderStrategy(context)
        assertEquals("API 26 非白名单设备应走 GPU GLES",
            RenderStrategy.GLES_PREFERRED, strategy)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q]) // API 29
    fun `getRenderStrategy API29 非白名单返回GLES_PREFERRED`() {
        val strategy = VulkanPolicy.getRenderStrategy(context)
        assertEquals("API 29 非白名单设备应走 GPU GLES",
            RenderStrategy.GLES_PREFERRED, strategy)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // API 30
    fun `getRenderStrategy API30 非白名单返回GLES_PREFERRED`() {
        val strategy = VulkanPolicy.getRenderStrategy(context)
        assertEquals("API 30 非白名单设备应走 GPU GLES",
            RenderStrategy.GLES_PREFERRED, strategy)
    }

    @Test
    @Config(sdk = [31]) // API 31 (Android 12)
    fun `getRenderStrategy API31 行为不变`() {
        // API 31+ 走原有 detectTier 逻辑，不再被 API < 31 检查拦截
        val strategy = VulkanPolicy.getRenderStrategy(context)
        // 应返回有效策略（不抛异常即为通过）
        assertNotNull("API 31+ 应返回有效策略", strategy)
    }

    // ── shouldDisableHardwareAcceleration 测试 ──

    @Test
    @Config(sdk = [Build.VERSION_CODES.O]) // API 26
    fun `shouldDisableHWAccel API26 非白名单返回true`() {
        // Robolectric Build.MANUFACTURER = "unknown" → 非白名单
        // API < 31 非白名单设备应关闭 HW 加速（定制 ROM 可能回传 SkiaVK）
        val disabled = VulkanPolicy.isAccelerationDisabled()
        assertTrue("API 26 非白名单设备应关闭硬件加速", disabled)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // API 30
    fun `shouldDisableHWAccel API30 非白名单返回true`() {
        // 荣耀畅玩30 Plus 的场景：API 30, manufacturer="HONOR" 非白名单
        val disabled = VulkanPolicy.isAccelerationDisabled()
        assertTrue("API 30 非白名单设备应关闭硬件加速", disabled)
    }

    @Test
    @Config(sdk = [31]) // API 31 (Android 12)
    fun `shouldDisableHWAccel API31 非白名单返回false`() {
        // API 31+ 使用 android.graphics.renderer="skiagl" metadata 提示
        // 硬件加速保持开启
        val disabled = VulkanPolicy.isAccelerationDisabled()
        assertFalse("API 31+ 应保持硬件加速开启", disabled)
    }

    // ── 失败台账策略测试（布尔标记 → 计数+窗口+衰减台账） ──

    private fun seedKillCount(count: Int, lastFailureAtMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences("crash_recovery", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("vk_kill_count", count)
            .putLong("vk_last_failure_at", lastFailureAtMs)
            .commit()
    }

    @Test
    @Config(sdk = [31])
    fun `kill 达 3 窗口内返回 GLES_PREFERRED`() {
        seedKillCount(3)
        assertEquals(
            "崩溃循环设备下次启动应降 GPU GLES（仍是 GPU，不是软件）",
            RenderStrategy.GLES_PREFERRED, VulkanPolicy.getRenderStrategy(context)
        )
    }

    @Test
    @Config(sdk = [31])
    fun `kill 未达阈值保持 VULKAN_PREFERRED 重试`() {
        seedKillCount(2)
        assertEquals(
            "未达台账阈值应保留干净启动重试",
            RenderStrategy.VULKAN_PREFERRED, VulkanPolicy.getRenderStrategy(context)
        )
    }

    @Test
    @Config(sdk = [31])
    fun `台账 3 天窗口外不再降级`() {
        seedKillCount(3, lastFailureAtMs = System.currentTimeMillis() - 4 * 24 * 3_600_000L)
        assertEquals(
            "窗口外失败不累积——应重试 Vulkan",
            RenderStrategy.VULKAN_PREFERRED, VulkanPolicy.getRenderStrategy(context)
        )
    }

    // ── 黑名单降为遥测队列（PROBLEMATIC → WARNING/cohort） ──

    @Test
    @Config(sdk = [31])
    fun `名单内机型走 VULKAN_PREFERRED 且 tier 为 WARNING`() {
        // Robolectric 注解无法直接改 Build.MODEL——反射设置（小米 14 Pro 的 model）
        val field = Build::class.java.getDeclaredField("MODEL")
        field.isAccessible = true
        field.set(null, "23127pn0cc")

        assertEquals(
            "名单命中只打 cohort 遥测标记，不再未试先降",
            DeviceTier.WARNING, VulkanPolicy.detectTier(context)
        )
        assertEquals(
            "WARNING tier → VULKAN_PREFERRED（运行时回退链兜底）",
            RenderStrategy.VULKAN_PREFERRED, VulkanPolicy.getRenderStrategy(context)
        )
        assertTrue("cohort 标志置位供遥测事件消费", VulkanPolicy.inProblemModelCohort)
    }
}

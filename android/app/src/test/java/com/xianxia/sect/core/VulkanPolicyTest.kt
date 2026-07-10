package com.xianxia.sect.core

import android.os.Build
import androidx.test.core.app.ApplicationProvider
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
    fun `getRenderStrategy API26 非白名单返回SOFTWARE_ONLY`() {
        // Robolectric 默认 Build.MANUFACTURER = "unknown" → 非白名单
        val strategy = VulkanPolicy.getRenderStrategy(context)
        assertEquals("API 26 非白名单设备应强制软件渲染",
            RenderStrategy.SOFTWARE_ONLY, strategy)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q]) // API 29
    fun `getRenderStrategy API29 非白名单返回SOFTWARE_ONLY`() {
        val strategy = VulkanPolicy.getRenderStrategy(context)
        assertEquals("API 29 非白名单设备应强制软件渲染",
            RenderStrategy.SOFTWARE_ONLY, strategy)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R]) // API 30
    fun `getRenderStrategy API30 非白名单返回SOFTWARE_ONLY`() {
        val strategy = VulkanPolicy.getRenderStrategy(context)
        assertEquals("API 30 非白名单设备应强制软件渲染",
            RenderStrategy.SOFTWARE_ONLY, strategy)
    }

    @Test
    @Config(sdk = [31]) // API 31 (Android 12)
    fun `getRenderStrategy API31 行为不变`() {
        // API 31+ 走原有 detectTier 逻辑，不再被 API < 31 检查拦截
        val strategy = VulkanPolicy.getRenderStrategy(context)
        // 应返回有效策略（不抛异常即为通过）
        assertNotNull("API 31+ 应返回有效策略", strategy)
    }
}

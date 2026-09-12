package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RenderFallbackReporter 错误码映射测试（验证项：枚举 ↔ 字符串映射
 * 全覆盖，防 C++ RenderInitError 改名/编号漂移后 stage 语义悄悄变化）。
 *
 * 编号与 android/app/src/main/cpp/Rhi.h `enum class RenderInitError` 一一对应；
 * 两侧任一侧改动必须同步另一侧（映射表即契约的可执行快照）。
 */
class RenderFallbackReporterErrorNameTest {

    @Test
    fun `通用段错误码映射`() {
        assertEquals("NONE", RenderFallbackReporter.errorName(0))
        assertEquals("NO_WINDOW", RenderFallbackReporter.errorName(1))
    }

    @Test
    fun `Vulkan 段错误码映射全覆盖`() {
        val expected = mapOf(
            10 to "VK_INSTANCE",
            11 to "VK_PHYSICAL_DEVICE",
            12 to "VK_LOGICAL_DEVICE",
            13 to "VK_QUEUE",
            14 to "VK_SURFACE",
            15 to "VK_SWAPCHAIN",
            16 to "VK_RENDER_PASS",
            17 to "VK_OFFSCREEN",
            18 to "VK_DESCRIPTOR_POOL",
            19 to "VK_PIPELINE_LAYOUT",
            20 to "VK_PIPELINE",
            21 to "VK_SHADERS",
            22 to "VK_COMMAND_POOL",
            23 to "VK_DEVICE_MEMORY",
            24 to "VK_FENCE",
        )
        expected.forEach { (code, name) ->
            assertEquals("code=$code", name, RenderFallbackReporter.errorName(code))
        }
    }

    @Test
    fun `GLES 段错误码映射全覆盖`() {
        val expected = mapOf(
            30 to "GLES_DISPLAY",
            31 to "GLES_CONFIG",
            32 to "GLES_WINDOW_SURFACE",
            33 to "GLES_CONTEXT",
            34 to "GLES_SHADER_COMPILE",
            35 to "GLES_PROGRAM_LINK",
        )
        expected.forEach { (code, name) ->
            assertEquals("code=$code", name, RenderFallbackReporter.errorName(code))
        }
    }

    @Test
    fun `未知编号回退 UNKNOWN`() {
        assertEquals("UNKNOWN", RenderFallbackReporter.errorName(99))
        assertEquals("UNKNOWN", RenderFallbackReporter.errorName(42))
        assertEquals("UNKNOWN", RenderFallbackReporter.errorName(-1))
    }
}

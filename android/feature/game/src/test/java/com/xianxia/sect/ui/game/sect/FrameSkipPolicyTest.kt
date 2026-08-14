package com.xianxia.sect.ui.game.sect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脏帧跳过判定策略测试（2026-08-14 平板省电）。
 *
 * 覆盖维度：
 * - 五守卫各自触发/放行（相机脏/帧引用变/总线脏/淡入中/缩放变）
 * - 全部静止 → 跳过；任一信号 → 不跳过
 * - 组合场景（多信号叠加不跳过）
 */
class FrameSkipPolicyTest {

    /** 全静止输入（五守卫全 false） */
    private fun idleInputs() = FrameSkipInputs(
        cameraDirty = false,
        frameChanged = false,
        buildingBusDirty = false,
        fadeActive = false,
        scaleChanged = false
    )

    @Test
    fun `all quiet - frame skipped`() {
        assertTrue(FrameSkipPolicy.shouldSkipFrame(idleInputs()))
    }

    @Test
    fun `camera dirty - must render`() {
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(cameraDirty = true)))
    }

    @Test
    fun `frame reference changed - must render`() {
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(frameChanged = true)))
    }

    @Test
    fun `building bus dirty - must render`() {
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(buildingBusDirty = true)))
    }

    @Test
    fun `fade in progress - must render`() {
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(fadeActive = true)))
    }

    @Test
    fun `render scale changed - must render`() {
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(scaleChanged = true)))
    }

    @Test
    fun `combined signals - must render`() {
        // 多信号叠加：任一生效即不跳过（AND 语义——全静止才跳过）
        val combined = idleInputs().copy(
            cameraDirty = true,
            buildingBusDirty = true
        )
        assertFalse(FrameSkipPolicy.shouldSkipFrame(combined))
    }

    @Test
    fun `only fade active among signals - must render`() {
        val input = idleInputs().copy(fadeActive = true)
        assertFalse(FrameSkipPolicy.shouldSkipFrame(input))
    }
}

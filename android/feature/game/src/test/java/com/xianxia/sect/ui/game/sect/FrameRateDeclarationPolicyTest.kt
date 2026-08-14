package com.xianxia.sect.ui.game.sect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧率↔刷新率联动声明策略测试（2026-08-14 平板省电）。
 *
 * 覆盖维度：
 * - 60Hz 面板旧行为逐位一致（≤30 声明 + 回升恢复声明）
 * - >60Hz 面板 {60, 30} 两档（首帧 60 / 深闲置 30 / 回升 60）
 * - 面板不支持声明（displayFps ≤ 0）返回 null
 * - FIXED_SOURCE 判定（仅高刷面板）
 */
class FrameRateDeclarationPolicyTest {

    // ── 60Hz 面板：旧行为逐位一致 ──

    @Test
    fun `60Hz - idle 10fps declares 10`() {
        assertEquals(10, FrameRateDeclarationPolicy.targetDeclareFps(60, 10, 0))
    }

    @Test
    fun `60Hz - idle 30fps declares 30`() {
        assertEquals(30, FrameRateDeclarationPolicy.targetDeclareFps(60, 30, 0))
    }

    @Test
    fun `60Hz - active 60fps never declared on fresh session`() {
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(60, 60, 0))
    }

    @Test
    fun `60Hz - upshift restores declaration to prevent panel stickiness`() {
        // 30→60 回升：lastDeclared=30 > 0 → 声明 60（防 OEM 面板粘滞旧行为）
        assertEquals(60, FrameRateDeclarationPolicy.targetDeclareFps(60, 60, 30))
    }

    @Test
    fun `60Hz - upshift to 45 declares 45`() {
        assertEquals(45, FrameRateDeclarationPolicy.targetDeclareFps(60, 45, 30))
    }

    @Test
    fun `60Hz - same value no declaration`() {
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(60, 30, 30))
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(60, 60, 60))
    }

    // ── 高刷面板：{60, 30} 两档 ──

    @Test
    fun `120Hz - first frame declares 60 on fresh session`() {
        // 会话首帧：120→60 声明（恰逢地图淡入遮罩，省屏耗 50% 的核心动作）
        assertEquals(60, FrameRateDeclarationPolicy.targetDeclareFps(120, 60, 0))
    }

    @Test
    fun `120Hz - idle 30fps declares 30`() {
        assertEquals(30, FrameRateDeclarationPolicy.targetDeclareFps(120, 30, 60))
    }

    @Test
    fun `120Hz - deep idle 10fps declares 30 not 10`() {
        // 10fps 不声明（部分面板不支持低档，帧节拍由 FrameDropPolicy 自行跳帧）
        assertEquals(30, FrameRateDeclarationPolicy.targetDeclareFps(120, 10, 60))
    }

    @Test
    fun `120Hz - upshift 30 to 60 returns target for debounce state machine`() {
        assertEquals(60, FrameRateDeclarationPolicy.targetDeclareFps(120, 60, 30))
    }

    @Test
    fun `120Hz - 45fps maps to 60 declaration`() {
        // 高刷面板只有两档：45fps（热控档）声明 60
        assertEquals(60, FrameRateDeclarationPolicy.targetDeclareFps(120, 45, 30))
    }

    @Test
    fun `120Hz - same value no declaration`() {
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(120, 60, 60))
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(120, 30, 30))
    }

    @Test
    fun `144Hz - same two-tier behavior`() {
        assertEquals(60, FrameRateDeclarationPolicy.targetDeclareFps(144, 60, 0))
        assertEquals(30, FrameRateDeclarationPolicy.targetDeclareFps(144, 10, 60))
    }

    // ── 异常面板 ──

    @Test
    fun `displayFps zero - provider failure no declaration`() {
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(0, 60, 0))
        assertNull(FrameRateDeclarationPolicy.targetDeclareFps(-1, 30, 60))
    }

    // ── FIXED_SOURCE 判定 ──

    @Test
    fun `useFixedSource - only high refresh panels`() {
        assertFalse(FrameRateDeclarationPolicy.useFixedSource(60))
        assertFalse(FrameRateDeclarationPolicy.useFixedSource(30))
        assertTrue(FrameRateDeclarationPolicy.useFixedSource(90))
        assertTrue(FrameRateDeclarationPolicy.useFixedSource(120))
        assertTrue(FrameRateDeclarationPolicy.useFixedSource(144))
    }
}

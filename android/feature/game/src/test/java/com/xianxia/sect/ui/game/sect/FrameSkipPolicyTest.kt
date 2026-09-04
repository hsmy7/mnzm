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

    /** 全静止输入（八守卫全 false） */
    private fun idleInputs() = FrameSkipInputs(
        cameraDirty = false,
        frameChanged = false,
        buildingBusDirty = false,
        fadeActive = false,
        scaleChanged = false,
        cloudDirty = false,
        previewDirty = false,
        skyDirty = false
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
    fun `cloud dirty - must render`() {
        // 云朵运动/生成/销毁 → 画面持续变化，必须渲染（防云层动画被脏帧跳过定格）
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(cloudDirty = true)))
    }

    @Test
    fun `preview dirty - must render`() {
        // 预览快通道版本未消费（拖动/放置预览更新）：必须立即渲染，防预览帧被脏帧跳过漏画
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(previewDirty = true)))
    }

    @Test
    fun `sky config changed - must render`() {
        // 天空渐变配置（天气/时间系统）变化：静止画面也须强制渲染更新天空配色，
        // 否则配色要等到下一次相机/数据变化才生效（当前帧定格在旧配色）
        assertFalse(FrameSkipPolicy.shouldSkipFrame(idleInputs().copy(skyDirty = true)))
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

    // ============================================================
    // 淡入完成兜底帧（2026-08-18 修复"进入游戏全屏半透明白色覆盖"）
    // ============================================================

    @Test
    fun `fade just completed but last frame semi-transparent - must render completion frame`() {
        // 淡入已结束（当前 alpha=1）但最后一帧仍以淡入中 alpha（0.5）渲染——
        // 必须强制补渲一帧完整不透明地图，防止脏帧跳过定格"半透明白色"帧
        assertTrue(needsFadeCompletionFrame(lastRenderedFadeAlpha = 0.5f, currentFadeAlpha = 1f))
    }

    @Test
    fun `fade in progress - no completion frame needed yet`() {
        // 淡入进行中（当前 alpha < 1）：由 fadeActive 守卫保证持续渲染，
        // 兜底帧判定不应干扰淡入本身
        assertFalse(needsFadeCompletionFrame(lastRenderedFadeAlpha = 0.3f, currentFadeAlpha = 0.6f))
    }

    @Test
    fun `last frame already full alpha - no completion frame needed`() {
        // 最后一帧已以完整 alpha 渲染（定格帧是正常地图）→ 无需兜底
        assertFalse(needsFadeCompletionFrame(lastRenderedFadeAlpha = 1f, currentFadeAlpha = 1f))
        // 淡入进行中但上一帧已完整渲染（场景切换后重入淡入）→ 同样无需兜底
        assertFalse(needsFadeCompletionFrame(lastRenderedFadeAlpha = 1f, currentFadeAlpha = 0.5f))
    }
}

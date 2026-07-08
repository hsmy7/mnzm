package com.xianxia.sect.ui.game.map.sect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宗门地图相机状态测试 — 验证 scale 缩放在各场景下的正确性。
 *
 * v4.0.45+ 默认视角高度更改为 0.5（DEFAULT_SCALE），
 * 大屏设备仍使用 Fill 适配策略。
 */
class SectCameraStateTest {

    // 宗门地图固定尺寸：3072 × 3072
    private val worldWidth = 3072f
    private val worldHeight = 3072f

    // 常见手机分辨率：1080 × 1920（竖屏）
    private val phoneVpW = 1080
    private val phoneVpH = 1920

    // 大屏分辨率：3840 × 2160（4K 横屏）
    private val largeVpW = 3840
    private val largeVpH = 2160

    // 默认视角高度（用户要求提高50%，取整）
    private val defaultScale = 0.5f

    // ==================== scale 计算 ====================

    @Test
    fun `updateViewport - viewport smaller than world - uses default scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH)
        assertEquals("手机竖屏视口小于世界，scale 应为默认视角高度 0.5",
            defaultScale, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport wider than world in one axis - scale gt 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 宽屏：3840 > 3072，但 2160 < 3072
        camera.updateViewport(largeVpW, largeVpH)
        // scale = maxOf(3840/3072, 2160/3072) = maxOf(1.25, 0.703) = 1.25
        assertEquals("4K 横屏宽度超出世界，scale 应为 1.25", 1.25f, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport taller than world in one axis - scale gt 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 竖屏大屏：2160 < 3072，但 3840 > 3072
        camera.updateViewport(largeVpH, largeVpW) // 2160 × 3840
        // scale = maxOf(2160/3072, 3840/3072) = maxOf(0.703, 1.25) = 1.25
        assertEquals("竖屏大屏高度超出世界，scale 应为 1.25", 1.25f, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport larger than world in both axes - scale gt 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(4096, 4096)
        // scale = maxOf(4096/3072, 4096/3072) = 1.333
        assertEquals("巨屏双轴超出，scale 应为 1.333", 1.333f, camera.scale, 0.01f)
    }

    @Test
    fun `updateViewport - viewport equal to world - uses default scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(3072, 3072)
        assertEquals("视口等于世界（未超出），scale 应为默认视角高度 0.5",
            defaultScale, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - second call does not reset user scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH) // first: scale=0.5
        camera.zoom(2.0f, phoneVpW / 2f, phoneVpH / 2f) // user zoom → scale=1.0
        camera.updateViewport(phoneVpW, phoneVpH) // second call → should NOT reset to 0.5
        assertEquals("用户缩放后 viewport 更新不应覆盖 scale", 1.0f, camera.scale, 0.001f)
    }

    // ==================== Fill 保证 ====================

    @Test
    fun `fill guarantee - rendered world covers viewport on overflowing axis`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(largeVpW, largeVpH)
        // renderedW = worldWidth * scale = 3072 * 1.25 = 3840 = largeVpW
        // renderedH = worldHeight * scale = 3072 * 1.25 = 3840 > largeVpH
        assertTrue("scale 应保证至少一个维度填满视口",
            worldWidth * camera.scale >= largeVpW ||
            worldHeight * camera.scale >= largeVpH)
    }

    // ==================== 坐标转换 ====================

    @Test
    fun `worldToScreen - default scale maps coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale = 0.5
        }
        // screenX = (worldX - cameraX) * scale = (100 - 0) * 0.5 = 50
        assertEquals(50f, camera.worldToScreenX(100f), 0.001f)
        assertEquals(25f, camera.worldToScreenY(50f), 0.001f)
    }

    @Test
    fun `screenToWorld - default scale un-maps coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale = 0.5
        }
        // worldX = screenX / scale + cameraX = 100 / 0.5 + 0 = 200
        assertEquals(200f, camera.screenToWorldX(100f), 0.001f)
        assertEquals(100f, camera.screenToWorldY(50f), 0.001f)
    }

    @Test
    fun `worldToScreen - scale gt 1 scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 1.25
        }
        // screenX = (worldX - cameraX) * scale = (100 - 0) * 1.25 = 125
        assertEquals(125f, camera.worldToScreenX(100f), 0.001f)
        assertEquals(125f, camera.worldToScreenY(100f), 0.001f)
    }

    @Test
    fun `screenToWorld - scale gt 1 un-scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 1.25
        }
        assertEquals(80f, camera.screenToWorldX(100f), 0.001f)
        assertEquals(80f, camera.screenToWorldY(100f), 0.001f)
    }

    @Test
    fun `roundTrip - worldToScreen then screenToWorld returns original`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH)
            // 相机偏移后再验证
            pan(50f, 100f)
        }
        val originalWx = 500f
        val originalWy = 800f
        val sx = camera.worldToScreenX(originalWx)
        val sy = camera.worldToScreenY(originalWy)
        assertEquals(originalWx, camera.screenToWorldX(sx), 0.01f)
        assertEquals(originalWy, camera.screenToWorldY(sy), 0.01f)
    }

    // ==================== 平移与 clamp ====================

    @Test
    fun `pan - with default scale moves camera by screen pixels over scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale = 0.5
        }
        camera.pan(-100f, -200f) // 左滑/上滑 → 相机右移/下移
        // cameraX = 0 - (-100/0.5) = 200, maxX = 3072-1080/0.5 = 912 → 200
        // cameraY = 0 - (-200/0.5) = 400, maxY = 3072-1920/0.5 = -768 → clamped to 0
        assertEquals(200f, camera.cameraX, 0.001f)
        assertEquals(0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `pan - with scale gt 1 applies inverse scale to camera`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(4000, 3000) // scale = 1.302 (fill)
        }
        // 宽轴（X）填满视口无法平移，在 Y 轴测试 pan
        camera.pan(0f, -150f)
        // cameraY = 0 - (-150/1.302) = 115 → 可见高=3000/1.302=2304, 范围[0,768], 115 在范围内
        assertEquals(115f, camera.cameraY, 1f)
    }

    @Test
    fun `clamp - camera cannot go below zero`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.pan(-500f, -1000f)
        assertTrue("cameraX 不应小于 0", camera.cameraX >= 0f)
        assertTrue("cameraY 不应小于 0", camera.cameraY >= 0f)
    }

    @Test
    fun `clamp - camera cannot exceed world bounds`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        // 尝试大幅右移
        camera.pan(-99999f, -99999f)
        // 可见世界 = 视口/scale = 1080/0.5 × 1920/0.5 = 2160 × 3840
        // cameraX max = max(0, 3072-2160) = 912
        // cameraY max = max(0, 3072-3840) = 0（3840 > 3072，Y 方向无法平移）
        val maxX = (worldWidth - phoneVpW / camera.scale).coerceAtLeast(0f)
        val maxY = (worldHeight - phoneVpH / camera.scale).coerceAtLeast(0f)
        assertTrue("cameraX 不应超过世界边界", camera.cameraX <= maxX)
        assertTrue("cameraY 不应超过世界边界", camera.cameraY <= maxY)
    }

    // ==================== centerOn ====================

    @Test
    fun `centerOn - with default scale accounts for scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale = 0.5
        }
        camera.centerOn(500f, 1000f)
        // cameraX = 500 - 1080/(2*0.5) = 500 - 1080 = -580 → clamped to 0
        // cameraY = 1000 - 1920/(2*0.5) = 1000 - 1920 = -920 → clamped to 0
        assertEquals(0f, camera.cameraX, 0.001f)
        assertEquals(0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `centerOn - with scale gt 1 accounts for reduced visible world`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 1.25
        }
        camera.centerOn(1500f, 1500f)
        // visibleW = 3840/1.25 = 3072 → 正好等于世界宽 → cameraX 被钳制到 0
        // visibleH = 2160/1.25 = 1728 → cameraY = 1500 - 1728/2 = 1500 - 864 = 636
        assertEquals(0f, camera.cameraX, 0.001f)
        assertEquals(636f, camera.cameraY, 0.001f)
    }

    // ==================== zoom ====================

    @Test
    fun `zoom - preserves world point under focus`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val focusSx = phoneVpW / 2f
        val focusSy = phoneVpH / 2f
        val worldBeforeX = camera.screenToWorldX(focusSx)
        val worldBeforeY = camera.screenToWorldY(focusSy)

        camera.zoom(2.0f, focusSx, focusSy)

        val worldAfterX = camera.screenToWorldX(focusSx)
        val worldAfterY = camera.screenToWorldY(focusSy)
        assertEquals("缩放后焦点下世界坐标应保持不变", worldBeforeX, worldAfterX, 0.1f)
        assertEquals("缩放后焦点下世界坐标应保持不变", worldBeforeY, worldAfterY, 0.1f)
    }

    @Test
    fun `zoom - clamps to min scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.zoom(0.01f, phoneVpW / 2f, phoneVpH / 2f)
        assertTrue("zoom 缩小不应低于 MIN_ZOOM", camera.scale >= SectCameraState.MIN_ZOOM)
    }

    @Test
    fun `zoom - clamps to max scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.zoom(100f, phoneVpW / 2f, phoneVpH / 2f)
        assertTrue("zoom 放大不应超过 MAX_ZOOM", camera.scale <= SectCameraState.MAX_ZOOM)
    }

    @Test
    fun `zoom - sets userScale flag preventing updateViewport override`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale=0.5
        }
        camera.zoom(2.0f, phoneVpW / 2f, phoneVpH / 2f) // scale=1.0, userScale=true
        camera.updateViewport(phoneVpW, phoneVpH) // should NOT reset
        assertEquals("userScale=true 后 updateViewport 不应覆盖 scale", 1.0f, camera.scale, 0.001f)
    }

    // ==================== isVisible ====================

    @Test
    fun `isVisible - visible point returns true`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        assertTrue("视口中心点应可见", camera.isVisible(300f, 300f))
    }

    @Test
    fun `isVisible - margin extends visibility range`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val outside = camera.isVisible(-10f, -10f, margin = 30f)
        assertTrue("margin 应扩展可见范围", outside)
    }

    // ==================== reset ====================

    @Test
    fun `reset - restores initial state`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
            pan(100f, 200f)
            reset()
        }
        assertEquals("reset 后 cameraX 应为 0", 0f, camera.cameraX, 0.001f)
        assertEquals("reset 后 cameraY 应为 0", 0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `reset - after reset updateViewport reapplies default scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale=0.5
            zoom(2.0f, 500f, 500f) // scale=1.0, userScale=true
            updateViewport(phoneVpW, phoneVpH) // should NOT reset (userScale=true)
            reset() // clear userScale and vpInitialized
            updateViewport(phoneVpW, phoneVpH) // should reapply default scale
        }
        assertEquals("reset 后 updateViewport 应重新应用默认视角高度",
            defaultScale, camera.scale, 0.001f)
    }
}

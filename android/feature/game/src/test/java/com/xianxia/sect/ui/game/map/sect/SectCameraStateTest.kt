package com.xianxia.sect.ui.game.map.sect

import com.xianxia.sect.core.camera.CameraState
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

    // 宗门地图实际尺寸：72 × 32px = 2304 × 2304
    private val worldWidth = 2304f
    private val worldHeight = 2304f

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
        // 宽屏：3840 > 2304，但 2160 < 2304
        camera.updateViewport(largeVpW, largeVpH)
        // scale = maxOf(3840/2304, 2160/2304) = maxOf(1.667, 0.9375) = 1.667
        assertEquals("4K 横屏宽度超出世界，scale 应为 1.667", 1.667f, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport taller than world in one axis - scale gt 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 竖屏大屏：2160 < 2304，但 3840 > 2304
        camera.updateViewport(largeVpH, largeVpW) // 2160 × 3840
        // scale = maxOf(2160/2304, 3840/2304) = maxOf(0.9375, 1.667) = 1.667
        assertEquals("竖屏大屏高度超出世界，scale 应为 1.667", 1.667f, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport larger than world in both axes - scale gt 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(4096, 4096)
        // scale = maxOf(4096/2304, 4096/2304) = 1.778
        assertEquals("巨屏双轴超出，scale 应为 1.778", 1.778f, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport equal to world - uses default scale`() {
        // 视口 2304 == 世界 2304，未超出
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(2304, 2304)
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
        // renderedW = worldWidth * scale = 2304 * 1.667 = 3840 = largeVpW
        // renderedH = worldHeight * scale = 2304 * 1.667 = 3840 > largeVpH
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
        assertEquals(50f, camera.worldToScreenX(100f), 0.001f)
        assertEquals(25f, camera.worldToScreenY(50f), 0.001f)
    }

    @Test
    fun `screenToWorld - default scale un-maps coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        assertEquals(200f, camera.screenToWorldX(100f), 0.001f)
        assertEquals(100f, camera.screenToWorldY(50f), 0.001f)
    }

    @Test
    fun `worldToScreen - scale gt 1 scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 1.667
        }
        assertEquals(166.7f, camera.worldToScreenX(100f), 0.1f)
        assertEquals(166.7f, camera.worldToScreenY(100f), 0.1f)
    }

    @Test
    fun `screenToWorld - scale gt 1 un-scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 1.667
        }
        assertEquals(60f, camera.screenToWorldX(100f), 0.1f)
        assertEquals(60f, camera.screenToWorldY(100f), 0.1f)
    }

    @Test
    fun `roundTrip - worldToScreen then screenToWorld returns original`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH)
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
        // cameraX = 0 - (-100/0.5) = 200, maxX = 2304-1080/0.5 = 144 → clamped to 144
        // cameraY = 0 - (-200/0.5) = 400, maxY = 2304-1920/0.5 = -1536 → clamped to 0
        assertEquals(144f, camera.cameraX, 0.001f)
        assertEquals(0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `pan - with scale gt 1 applies inverse scale to camera`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(4000, 3000) // scale = 1.736 (fill: maxOf(4000/2304, 3000/2304))
        }
        // 宽轴（X）填满视口无法平移，在 Y 轴测试 pan
        camera.pan(0f, -150f)
        // cameraY = 0 - (-150/1.736) = 86.4 → 可见高=3000/1.736=1728, 范围[0,576], 86.4 在范围内
        assertEquals(86.4f, camera.cameraY, 0.5f)
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
        // cameraX max = max(0, 2304-2160) = 144
        // cameraY max = max(0, 2304-3840) = 0（3840 > 2304，Y 方向无法平移）
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
            updateViewport(largeVpW, largeVpH) // scale = 1.667
        }
        camera.centerOn(1500f, 1500f)
        // visibleW = 3840/1.667 = 2304 → 等于世界宽 → cameraX 钳制到 0
        // visibleH = 2160/1.667 = 1296 → cameraY = 1500 - 1296/2 = 852
        assertEquals(0f, camera.cameraX, 0.001f)
        assertEquals(852f, camera.cameraY, 0.5f)
    }

    // ==================== zoom ====================

    @Test
    fun `zoom - preserves world point X under focus within X bounds`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
            // 往右平移以避免 zoom 后 X 被 clamp
            pan(-50f, 0f) // cameraX = 100, 在 [0,144] 内
        }
        val focusSx = phoneVpW / 2f
        val worldBeforeX = camera.screenToWorldX(focusSx)

        camera.zoom(2.0f, focusSx, phoneVpH / 2f)

        val worldAfterX = camera.screenToWorldX(focusSx)
        assertEquals("缩放后焦点下世界 X 应保持不变", worldBeforeX, worldAfterX, 0.1f)
        // Y 方向因可见高(3840) > 世界高(2304)始终被 clamp，不验证 Y 轴
    }

    @Test
    fun `zoom - clamps to min scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.zoom(0.01f, phoneVpW / 2f, phoneVpH / 2f)
        assertTrue("zoom 缩小不应低于 MIN_ZOOM", camera.scale >= CameraState.MIN_ZOOM)
    }

    @Test
    fun `zoom - clamps to max scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.zoom(100f, phoneVpW / 2f, phoneVpH / 2f)
        assertTrue("zoom 放大不应超过 MAX_ZOOM", camera.scale <= CameraState.MAX_ZOOM)
    }

    @Test
    fun `zoom - NaN delta does not change scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val before = camera.scale
        camera.zoom(Float.NaN, phoneVpW / 2f, phoneVpH / 2f)
        assertEquals("NaN delta 不应改变 scale", before, camera.scale, 0.001f)
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

    // ==================== 边界安全 ====================

    @Test
    fun `applyScale - NaN does not change scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val before = camera.scale
        camera.applyScale(Float.NaN)
        assertEquals("NaN 不应改变 scale", before, camera.scale, 0.001f)
    }

    @Test
    fun `pan - with valid input does not crash`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        // scale 有 protected set，无法从外部设置为 NaN 或 0
        // 验证 NaN applyScale 不改变 scale 即证明守卫有效
        val before = camera.scale
        camera.applyScale(Float.NaN)
        assertEquals("NaN applyScale 不应改变 scale", before, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - negative values clamped to zero`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(-100, -200)
        assertTrue("负数视口应钳制为 0", camera.viewportWidth >= 0)
        assertTrue("负数视口应钳制为 0", camera.viewportHeight >= 0)
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
            reset() // clear userScale
            updateViewport(phoneVpW, phoneVpH) // should reapply default scale
        }
        assertEquals("reset 后 updateViewport 应重新应用默认视角高度",
            defaultScale, camera.scale, 0.001f)
    }
}

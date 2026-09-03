package com.xianxia.sect.ui.game.map.sect

import com.xianxia.sect.core.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 宗门地图相机状态测试 — 验证 scale 缩放在各场景下的正确性。
 *
 * v4.0.45+ 默认视角改为「缩放区间几何中值」（√(minScale × MAX_ZOOM)）：
 * 保证从初始视角向放大/缩小两端可缩放的倍数一致，且缩小不超出世界边界。
 */
class SectCameraStateTest {

    // 测试用世界尺寸（相机数学与具体尺寸无关；实际宗门地图由
    // GameConfig.SectMap.WORLD_PIXEL_WIDTH/HEIGHT = 128 格 × TILE_SIZE 决定）
    private val worldWidth = 4608f
    private val worldHeight = 4608f

    // 常见手机分辨率
    private val phoneVpW = 1080
    private val phoneVpH = 1920
    // 全面屏 20:9（当前市场主流）
    private val tallVpW = 1080
    private val tallVpH = 2400
    // 大屏分辨率：3840 × 2160（4K 横屏）
    private val largeVpW = 3840
    private val largeVpH = 2160

    // 缩放中值策略：computeDefaultScale = sqrt(max(MIN_ZOOM, vpW/worldW, vpH/worldH) × MAX_ZOOM)
    private fun expectedScale(vpW: Int, vpH: Int): Float {
        val minSafeScale = maxOf(
            CameraState.MIN_ZOOM,
            vpW.toFloat() / worldWidth,
            vpH.toFloat() / worldHeight
        )
        return sqrt(minSafeScale * CameraState.MAX_ZOOM)
    }

    // ==================== 自适应缩放（新） ====================

    @Test
    fun `computeDefaultScale - 16-9 phone returns middle scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH)
        val want = expectedScale(phoneVpW, phoneVpH)
        assertEquals("16:9 手机应使用缩放中值", want, camera.scale, 0.001f)
    }

    @Test
    fun `computeDefaultScale - 初始视角为缩放中值，放大与缩小倍数一致`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH)
        val minBound = maxOf(
            CameraState.MIN_ZOOM,
            phoneVpW.toFloat() / worldWidth,
            phoneVpH.toFloat() / worldHeight
        )
        // 从初始视角向两端缩放的倍数应一致：scale/minBound == MAX_ZOOM/scale
        val zoomOutFactor = camera.scale / minBound
        val zoomInFactor = CameraState.MAX_ZOOM / camera.scale
        assertEquals("可缩小倍数与可放大倍数应一致", zoomOutFactor, zoomInFactor, 0.001f)
    }

    @Test
    fun `computeDefaultScale - tall 20-9 phone fills screen no empty space`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(tallVpW, tallVpH)
        val want = expectedScale(tallVpW, tallVpH)
        assertEquals("20:9 全面屏应使用自适应缩放", want, camera.scale, 0.001f)
        // 验证：视口世界高度不超过世界高度（无底部空白）
        val eh = tallVpH / camera.scale
        assertTrue("视口世界高度不应超过世界高度", eh <= worldHeight + 0.1f)
        // 验证：至少一个维度刚好填满视口
        assertTrue("至少一个维度应填满视口",
            (worldWidth * camera.scale >= tallVpW - 0.5f) ||
            (worldHeight * camera.scale >= tallVpH - 0.5f))
    }

    @Test
    fun `computeDefaultScale - landscape phone fills screen no empty space`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 横屏：1920×1080
        val lw = 1920; val lh = 1080
        camera.updateViewport(lw, lh)
        val want = expectedScale(lw, lh)
        assertEquals("横屏应使用自适应缩放", want, camera.scale, 0.001f)
        val ew = lw / camera.scale
        val eh = lh / camera.scale
        assertTrue("视口世界宽度不应超过世界宽度", ew <= worldWidth + 0.1f)
        assertTrue("视口世界高度不应超过世界高度", eh <= worldHeight + 0.1f)
    }

    @Test
    fun `computeDefaultScale - tablet portrait fills screen no empty space`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 平板竖屏：1536×2048
        val tw = 1536; val th = 2048
        camera.updateViewport(tw, th)
        val want = expectedScale(tw, th)
        assertEquals("平板竖屏应使用自适应缩放", want, camera.scale, 0.001f)
        val eh = th / camera.scale
        assertTrue("视口世界高度不应超过世界高度", eh <= worldHeight + 0.1f)
    }

    @Test
    fun `computeDefaultScale - small screen not below MIN_ZOOM`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 极小屏：480×800
        camera.updateViewport(480, 800)
        assertTrue("极小屏缩放不应低于 MIN_ZOOM",
            camera.scale >= CameraState.MIN_ZOOM)
        val eh = 800 / camera.scale
        assertTrue("MIN_ZOOM 限制下视口不应超出世界", eh <= worldHeight + 0.1f)
    }

    @Test
    fun `computeDefaultScale - fill guarantee for all phone aspect ratios`() {
        // 验证各种常见比例下，视口世界尺寸均不超过世界尺寸
        val ratios = listOf(
            1920 to 1080,  // 16:9 横屏
            1080 to 1920,  // 16:9 竖屏
            2400 to 1080,  // 20:9 竖屏
            1080 to 2400,  // 20:9 竖屏反
            1440 to 3120,  // 21:9 竖屏
            3120 to 1440,  // 21:9 横屏
            2560 to 1600,  // 16:10 横屏
            1600 to 2560,  // 16:10 竖屏
            2732 to 2048,  // iPad Pro 4:3 横屏
            2048 to 2732,  // iPad Pro 4:3 竖屏
            3840 to 2160,  // 4K 横屏
            2160 to 3840,  // 4K 竖屏
        )
        for ((w, h) in ratios) {
            val camera = SectCameraState(worldWidth, worldHeight)
            camera.updateViewport(w, h)
            val ew = w / camera.scale
            val eh = h / camera.scale
            val msg = "w=${w}h=${h} scale=${camera.scale}: world viewport (${ew}x${eh}) " +
                      "exceeds world (${worldWidth}x${worldHeight})"
            assertTrue(msg, ew <= worldWidth + 0.1f)
            assertTrue(msg, eh <= worldHeight + 0.1f)
        }
    }

    // ==================== scale 计算（回归测试） ====================

    @Test
    fun `updateViewport - viewport wider than world in one axis - scale gt 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 宽屏：3840 < 4608，但 2160 < 4608
        camera.updateViewport(largeVpW, largeVpH)
        // minScale = maxOf(0.3, 3840/4608, 2160/4608) = 0.833；defaultScale = √(0.833×3) ≈ 1.58
        val want = expectedScale(largeVpW, largeVpH)
        assertEquals("大屏横屏 scale 应为缩放中值", want, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport equal to world - caps at computed scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(4608, 4608)
        // minScale = maxOf(0.3, 1, 1) = 1.0；defaultScale = √(1×3) ≈ 1.73
        val want = expectedScale(4608, 4608)
        assertEquals("视口等于世界时应使用预期缩放", want, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - viewport smaller than world - uses middle scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH)
        // minScale = maxOf(0.3, 1080/4608, 1920/4608) = 0.417；defaultScale = √(0.417×3) ≈ 1.118
        val want = expectedScale(phoneVpW, phoneVpH)
        assertEquals("手机竖屏 scale 应为缩放中值", want, camera.scale, 0.001f)
    }

    @Test
    fun `updateViewport - second call does not reset user scale`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH) // first: scale = 中值 ≈ 1.118
        camera.zoom(2.0f, phoneVpW / 2f, phoneVpH / 2f) // user zoom → scale ≈ 2.37
        camera.updateViewport(phoneVpW, phoneVpH) // second call → should NOT reset
        val expectedAfterZoom = expectedScale(phoneVpW, phoneVpH) * 2.0f
            .coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
        assertEquals("用户缩放后 viewport 更新不应覆盖 scale",
            expectedAfterZoom, camera.scale, 0.001f)
    }

    // ==================== Fill 保证 ====================

    @Test
    fun `fill guarantee - rendered world covers viewport on overflowing axis`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(largeVpW, largeVpH)
        assertTrue("scale 应保证至少一个维度填满视口",
            worldWidth * camera.scale >= largeVpW - 0.5f ||
            worldHeight * camera.scale >= largeVpH - 0.5f)
    }

    // ==================== 坐标转换 ====================

    @Test
    fun `worldToScreen - default scale maps coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val sx = camera.worldToScreenX(100f)
        val sy = camera.worldToScreenY(50f)
        // scale = 缩放中值 ≈ 1.186，cameraX/Y = 0 → sx = 100*scale
        assertEquals(100f * camera.scale, sx, 0.001f)
        assertEquals(50f * camera.scale, sy, 0.001f)
    }

    @Test
    fun `screenToWorld - default scale un-maps coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        assertEquals(100f / camera.scale, camera.screenToWorldX(100f), 0.001f)
        assertEquals(50f / camera.scale, camera.screenToWorldY(50f), 0.001f)
    }

    @Test
    fun `worldToScreen - scale gt 1 scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 0.938
        }
        val expected = 100f * camera.scale
        assertEquals(expected, camera.worldToScreenX(100f), 0.1f)
        assertEquals(expected, camera.worldToScreenY(100f), 0.1f)
    }

    @Test
    fun `screenToWorld - scale gt 1 un-scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH)
        }
        assertEquals(100f / camera.scale, camera.screenToWorldX(100f), 0.1f)
        assertEquals(100f / camera.scale, camera.screenToWorldY(100f), 0.1f)
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
            updateViewport(phoneVpW, phoneVpH) // scale = 0.469
        }
        camera.pan(-100f, -200f) // 左滑/上滑 → 相机右移/下移
        // cameraX = 0 - (-100/scale), cameraY = 0 - (-200/scale)
        assertTrue("cameraX 应在 [0, worldWidth - vpW/scale] 范围内",
            camera.cameraX >= 0f &&
            camera.cameraX <= (worldWidth - phoneVpW / camera.scale).coerceAtLeast(0f))
        assertTrue("cameraY 应在 [0, worldHeight - vpH/scale] 范围内",
            camera.cameraY >= 0f &&
            camera.cameraY <= (worldHeight - phoneVpH / camera.scale).coerceAtLeast(0f))
    }

    @Test
    fun `pan - with scale gt 1 applies inverse scale to camera`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(4000, 3000) // scale = maxOf(4000/4608, 3000/4608) = 0.868
        }
        camera.pan(0f, -150f)
        assertTrue("cameraY 应在有效范围内", camera.cameraY >= 0f)
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
        val maxX = (worldWidth - phoneVpW / camera.scale).coerceAtLeast(0f)
        val maxY = (worldHeight - phoneVpH / camera.scale).coerceAtLeast(0f)
        assertTrue("cameraX 不应超过世界边界", camera.cameraX <= maxX + 0.001f)
        assertTrue("cameraY 不应超过世界边界", camera.cameraY <= maxY + 0.001f)
    }

    // ==================== centerOn ====================

    @Test
    fun `centerOn - with default scale accounts for scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.centerOn(500f, 1000f)
        assertTrue("cameraX 不应小于 0", camera.cameraX >= 0f)
        assertTrue("cameraY 不应小于 0", camera.cameraY >= 0f)
    }

    @Test
    fun `centerOn - with scale gt 1 accounts for reduced visible world`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 0.938
        }
        camera.centerOn(1500f, 1500f)
        assertTrue("centerOn 后 cameraX 不应小于 0", camera.cameraX >= 0f)
        assertTrue("centerOn 后 cameraY 不应小于 0", camera.cameraY >= 0f)
    }

    // ==================== zoom ====================

    @Test
    fun `zoom - preserves world point X under focus within X bounds`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
            pan(-50f, 0f) // 向右平移
        }
        val focusSx = phoneVpW / 2f
        val worldBeforeX = camera.screenToWorldX(focusSx)

        camera.zoom(2.0f, focusSx, phoneVpH / 2f)

        val worldAfterX = camera.screenToWorldX(focusSx)
        assertEquals("缩放后焦点下世界 X 应保持不变", worldBeforeX, worldAfterX, 0.1f)
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
    fun `zoom - 缩小受限安全下界，视口不看到地图外`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val minBound = maxOf(
            CameraState.MIN_ZOOM,
            phoneVpW.toFloat() / worldWidth,
            phoneVpH.toFloat() / worldHeight
        )
        // 极端缩小 → scale 恰好被钳制到安全下界
        camera.zoom(0.001f, phoneVpW / 2f, phoneVpH / 2f)
        assertEquals("缩小下界应等于安全下界", minBound, camera.scale, 0.001f)
        // 视口世界尺寸不得超出世界尺寸（不看到地图外）
        val ew = phoneVpW / camera.scale
        val eh = phoneVpH / camera.scale
        assertTrue("缩小后视口世界宽度不应超过世界宽度", ew <= worldWidth + 0.1f)
        assertTrue("缩小后视口世界高度不应超过世界高度", eh <= worldHeight + 0.1f)
        // 至少一个维度正好填满视口（无空白）
        assertTrue(
            "缩小到极限时应无空白",
            worldWidth * camera.scale >= phoneVpW - 0.5f ||
                worldHeight * camera.scale >= phoneVpH - 0.5f
        )
    }

    @Test
    fun `zoom - 从初始视角放大缩小倍数一致`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val initial = camera.scale
        val minBound = maxOf(
            CameraState.MIN_ZOOM,
            phoneVpW.toFloat() / worldWidth,
            phoneVpH.toFloat() / worldHeight
        )
        // 以同样倍数放大后再缩小，应回到初始视角附近
        val factor = 1.5f
        camera.zoom(factor, phoneVpW / 2f, phoneVpH / 2f)
        val zoomedIn = camera.scale
        camera.zoom(1f / factor, phoneVpW / 2f, phoneVpH / 2f)
        assertEquals("放大后再缩小应回到初始视角", initial, camera.scale, 0.001f)
        // 且初始视角到上下界的倍数一致
        assertEquals("可缩小倍数与可放大倍数应一致",
            initial / minBound, CameraState.MAX_ZOOM / initial, 0.001f)
        assertTrue("放大后 scale 应大于初始", zoomedIn > initial)
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
            updateViewport(phoneVpW, phoneVpH) // scale = adaptive
        }
        camera.zoom(2.0f, phoneVpW / 2f, phoneVpH / 2f) // userScale=true
        camera.updateViewport(phoneVpW, phoneVpH) // should NOT reset
        assertEquals("userScale=true 后 updateViewport 不应覆盖 scale",
            2.0f * expectedScale(phoneVpW, phoneVpH).coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM),
            camera.scale, 0.001f)
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
            updateViewport(phoneVpW, phoneVpH) // scale = adaptive
            zoom(2.0f, 500f, 500f) // userScale=true
            updateViewport(phoneVpW, phoneVpH) // should NOT reset (userScale=true)
            reset() // clear userScale
            updateViewport(phoneVpW, phoneVpH) // should reapply default scale
        }
        val want = expectedScale(phoneVpW, phoneVpH)
        assertEquals("reset 后 updateViewport 应重新应用默认缩放",
            want, camera.scale, 0.001f)
    }
}

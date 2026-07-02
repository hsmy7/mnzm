package com.xianxia.sect.ui.game.map.sect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宗门地图相机状态测试 — 验证 scale 缩放在各场景下的正确性。
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

    // ==================== scale 计算 ====================

    @Test
    fun `updateViewport - viewport smaller than world - scale is 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(phoneVpW, phoneVpH)
        assertEquals("手机竖屏视口小于世界，scale 应为 1.0", 1f, camera.scale, 0.001f)
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
    fun `updateViewport - viewport equal to world - scale is 1`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        camera.updateViewport(3072, 3072)
        assertEquals("视口等于世界，scale 应为 1.0", 1f, camera.scale, 0.001f)
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
    fun `worldToScreen - scale 1 returns identity-like mapping`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        assertEquals(100f, camera.worldToScreenX(100f), 0.001f)
        assertEquals(50f, camera.worldToScreenY(50f), 0.001f)
    }

    @Test
    fun `screenToWorld - scale 1 returns identity-like mapping`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        assertEquals(100f, camera.screenToWorldX(100f), 0.001f)
        assertEquals(50f, camera.screenToWorldY(50f), 0.001f)
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
    fun `pan - with scale 1 moves camera by screen pixels`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.pan(-100f, -200f) // 左滑/上滑 → 相机右移/下移
        assertEquals(100f, camera.cameraX, 0.001f)
        assertEquals(200f, camera.cameraY, 0.001f)
    }

    @Test
    fun `pan - with scale gt 1 applies inverse scale to camera`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(4000, 3000) // scale = maxOf(4000/3072, 3000/3072) = 1.302
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
        // 可见世界尺寸 = 视口 / scale = 1080/1 x 1920/1
        // cameraX max = 3072 - 1080 = 1992
        assertTrue("cameraX 不应超过世界边界", camera.cameraX <= worldWidth - phoneVpW)
        assertTrue("cameraY 不应超过世界边界", camera.cameraY <= worldHeight - phoneVpH)
    }

    // ==================== centerOn ====================

    @Test
    fun `centerOn - with scale 1 centers correctly`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.centerOn(500f, 1000f)
        // cameraX = 500 - 1080/2 = 500 - 540 = -40 → clamped to 0
        // cameraY = 1000 - 1920/2 = 1000 - 960 = 40
        assertEquals(0f, camera.cameraX, 0.001f)
        assertEquals(40f, camera.cameraY, 0.001f)
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
        val outside = camera.isVisible(-10f, -10f, margin = 20f)
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
}

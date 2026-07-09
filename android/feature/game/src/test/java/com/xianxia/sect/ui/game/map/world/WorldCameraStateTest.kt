package com.xianxia.sect.ui.game.map.world

import com.xianxia.sect.core.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界地图相机状态测试。
 *
 * 世界地图尺寸 1698×926（来自 GameConfig.WorldMap），
 * 视口 1080×1920 在 Y 轴（1920px）远大于世界高度（926px），
 * 因此 Y 方向始终被 clamp 到 [0, 0]，仅 X 方向可平移。
 * 测试中涉及 Y 轴边界时需考虑此约束。
 */
class WorldCameraStateTest {

    private val worldWidth = 1698f
    private val worldHeight = 926f
    private val vpW = 1080
    private val vpH = 1920

    /** scale=1.0 时 Y 方向可见高度 = 1920 > 926 → 永远 clamp 到 0 */
    private val maxCameraY = 0f

    // ==================== 构造与默认缩放 ====================

    @Test
    fun `constructor - initialScale is respected`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 0.8f)
        assertEquals("构造时传入 initialScale=0.8 应生效", 0.8f, camera.scale, 0.001f)
    }

    @Test
    fun `constructor - default initialScale is 1f`() {
        val camera = WorldCameraState(worldWidth, worldHeight)
        assertEquals("默认 initialScale 应为 1.0f", 1.0f, camera.scale, 0.001f)
    }

    @Test
    fun `constructor - initialScale is clamped to valid range`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 100f)
        assertEquals("超出最大缩放的 initialScale 应被钳制", CameraState.MAX_ZOOM,
            camera.scale, 0.001f)
    }

    @Test
    fun `computeDefaultScale - preserves scale through updateViewport`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 0.8f)
        camera.updateViewport(vpW, vpH)
        assertEquals("updateViewport 不应覆盖构造时的 scale", 0.8f, camera.scale, 0.001f)
    }

    @Test
    fun `reset then updateViewport - scale unchanged (computeDefaultScale=current)`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 1.2f).apply {
            updateViewport(vpW, vpH)
        }
        val before = camera.scale
        camera.reset()
        camera.updateViewport(vpW, vpH)
        assertEquals("reset+updateViewport 不应改变 scale", before, camera.scale, 0.001f)
    }

    // ==================== updateScale ====================

    @Test
    fun `updateScale - changes scale and preserves X center`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 1.0f).apply {
            updateViewport(vpW, vpH)
        }
        val centerBeforeX = camera.cameraX + vpW / (2f * camera.scale)

        camera.updateScale(1.5f)

        assertEquals("updateScale 应更新 scale", 1.5f, camera.scale, 0.001f)
        val centerAfterX = camera.cameraX + vpW / (2f * camera.scale)
        assertEquals("视口中心 X 应保持不变", centerBeforeX, centerAfterX, 0.1f)
        // Y 方向因世界高度 < 视口高度始终被 clamp，不做焦点保持断言
    }

    @Test
    fun `updateScale - sets userScale preventing viewport override`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
            updateScale(2.0f)
            updateViewport(vpW, vpH)
        }
        assertEquals("userScale=true 后 updateViewport 不应覆盖", 2.0f, camera.scale, 0.001f)
    }

    @Test
    fun `updateScale - same scale is no-op`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 1.5f).apply {
            updateViewport(vpW, vpH)
        }
        val posBeforeX = camera.cameraX
        camera.updateScale(1.5f)
        assertEquals("相同 scale 不应改变位置", posBeforeX, camera.cameraX, 0.001f)
    }

    // ==================== zoom ====================

    @Test
    fun `zoom - preserves world point under focus within bounds`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 1.0f).apply {
            updateViewport(vpW, vpH)
            pan(-300f, 0f) // 平移到世界中部，让焦点在 clamp 范围内
        }
        val focusSx = vpW / 2f
        val focusSy = vpH / 2f
        val worldBeforeX = camera.screenToWorldX(focusSx)
        val worldBeforeY = camera.screenToWorldY(focusSy)

        camera.zoom(2.0f, focusSx, focusSy)

        val worldAfterX = camera.screenToWorldX(focusSx)
        assertEquals("缩放后焦点下世界 X 应保持不变", worldBeforeX, worldAfterX, 0.1f)
        // Y 方向因世界高度 < 视口高度始终被 clamp，只验证 X
    }

    @Test
    fun `zoom - clamps to min scale`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        camera.zoom(0.01f, vpW / 2f, vpH / 2f)
        assertTrue(camera.scale >= CameraState.MIN_ZOOM)
    }

    @Test
    fun `zoom - clamps to max scale`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        camera.zoom(100f, vpW / 2f, vpH / 2f)
        assertTrue(camera.scale <= CameraState.MAX_ZOOM)
    }

    @Test
    fun `zoom - NaN delta does not change scale`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        val before = camera.scale
        camera.zoom(Float.NaN, vpW / 2f, vpH / 2f)
        assertEquals("NaN delta 不应改变 scale", before, camera.scale, 0.001f)
    }

    @Test
    fun `zoom - sets userScale flag`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        camera.zoom(2.0f, vpW / 2f, vpH / 2f)
        camera.updateViewport(vpW, vpH)
        assertEquals("userScale=true 后 updateViewport 不应覆盖", 2.0f, camera.scale, 0.001f)
    }

    // ==================== pan ====================

    @Test
    fun `pan - moves camera inversely proportional to scale`() {
        val camera = WorldCameraState(worldWidth, worldHeight, 2.0f).apply {
            updateViewport(vpW, vpH)
        }
        camera.pan(100f, 0f)
        // cameraX = 0 - 100/2.0 = -50 → clamped to 0
        assertEquals(0f, camera.cameraX, 0.001f)
        assertEquals(0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `clamp - camera cannot exceed world boundaries`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        camera.pan(-99999f, -99999f)
        val maxX = (worldWidth - vpW / camera.scale).coerceAtLeast(0f)
        assertTrue("cameraX 不应超过上界", camera.cameraX <= maxX)
        assertTrue("cameraY 不应小于 0", camera.cameraY >= 0f)
    }

    // ==================== applyScale ====================

    @Test
    fun `applyScale - changes scale and sets userScale`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        camera.applyScale(2.5f)
        assertEquals(2.5f, camera.scale, 0.001f)
        camera.updateViewport(vpW, vpH)
        assertEquals("userScale=true 后 updateViewport 不应覆盖", 2.5f, camera.scale, 0.001f)
    }

    @Test
    fun `applyScale - NaN does not change scale`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        val before = camera.scale
        camera.applyScale(Float.NaN)
        assertEquals("NaN 不应改变 scale", before, camera.scale, 0.001f)
    }

    // ==================== tryCenterOn ====================

    @Test
    fun `tryCenterOn - first call centers on target within bounds`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        // 选择 X 方向可达的目标（worldWidth 1698, 可见宽 1080 → 最大可达 1698-540=1158）
        camera.tryCenterOn(800f, 0f)
        // X: cameraX = 800 - 540 = 260 → 在 [0, 1158] 范围内
        // Y: cameraY = 0 - 960 = -960 → clamped to 0
        assertEquals(260f, camera.cameraX, 0.5f)
        assertEquals(0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `tryCenterOn - second call with same coords is no-op`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        camera.tryCenterOn(800f, 0f)
        val posAfterFirstX = camera.cameraX
        camera.tryCenterOn(800f, 0f)
        assertEquals("重复相同坐标不应再居中", posAfterFirstX, camera.cameraX, 0.001f)
    }

    // ==================== isVisible ====================

    @Test
    fun `isVisible - world center is visible`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
        }
        assertTrue("世界中心应可见", camera.isVisible(worldWidth / 2f, worldHeight / 2f))
    }

    // ==================== reset ====================

    @Test
    fun `reset - clears position`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
            tryCenterOn(800f, 0f)
            pan(50f, 0f)
            reset()
        }
        assertEquals("reset 后 cameraX 应为 0", 0f, camera.cameraX, 0.001f)
        assertEquals("reset 后 cameraY 应为 0", 0f, camera.cameraY, 0.001f)
    }

    @Test
    fun `reset - tryCenterOn re-centers after reset`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
            tryCenterOn(800f, 0f)
            pan(50f, 0f) // 偏移
            reset() // hasInitialized=false
            tryCenterOn(800f, 0f) // 应重新居中
        }
        // cameraX = 800 - 540 = 260, 在 [0, 1158] 内
        assertEquals("reset 后 tryCenterOn 应重新居中", 800f,
            camera.cameraX + vpW / (2f * camera.scale), 0.5f)
    }

    @Test
    fun `reset - clears userScale`() {
        val camera = WorldCameraState(worldWidth, worldHeight).apply {
            updateViewport(vpW, vpH)
            zoom(2.0f, vpW / 2f, vpH / 2f) // userScale=true
            reset()
            updateViewport(vpW, vpH)
        }
        assertEquals("reset+updateViewport 应保持当前 scale", 2.0f, camera.scale, 0.001f)
    }

    // ==================== 继承守卫 ====================

    @Test
    fun `updateViewport - negative values clamped to zero`() {
        val camera = WorldCameraState(worldWidth, worldHeight)
        camera.updateViewport(-100, -200)
        assertTrue(camera.viewportWidth >= 0)
        assertTrue(camera.viewportHeight >= 0)
    }
}

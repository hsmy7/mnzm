package com.xianxia.sect.ui.game.map.sect

import com.xianxia.sect.core.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 宗门地图相机状态测试 — 验证 scale 缩放在各场景下的正确性。
 *
 * v4.0.45+ 默认视角为「缩放区间几何中值」（√(minScale × MAX_ZOOM)）：
 * 保证从初始视角向放大/缩小两端可缩放的倍数一致，且缩小不超出世界边界。
 */
class SectCameraStateTest {

    // 测试用世界尺寸（相机数学与具体尺寸无关；实际宗门地图由
    // GameConfig.SectMap.WORLD_PIXEL_WIDTH/HEIGHT = 128 格 × TILE_SIZE 决定）
    private val worldWidth = 4608f
    private val worldHeight = 4608f

    // 俯视纵向压缩系数（与 SectCameraState.worldYScale ← SpriteAtlasDef.TOPDOWN_Y_SCALE
    // 同值——统一俯视视角；修改 LAYOUT.topdownYScale 必同步）
    private val topdownYScale = 0.75f

    // 常见手机分辨率
    private val phoneVpW = 1080
    private val phoneVpH = 1920
    // 全面屏 20:9（当前市场主流）
    private val tallVpW = 1080
    private val tallVpH = 2400
    // 大屏分辨率：3840 × 2160（4K 横屏）
    private val largeVpW = 3840
    private val largeVpH = 2160

    // 缩放中值策略：computeDefaultScale =
    // sqrt(max(MIN_ZOOM, vpW/worldW, vpH/(worldH×yScale)) × MAX_ZOOM)
    //（高度比按俯视纵向压缩系数扩大：铺满视口需 worldH×scale×yScale ≥ vpH）
    private fun expectedScale(vpW: Int, vpH: Int): Float {
        val minSafeScale = maxOf(
            CameraState.MIN_ZOOM,
            vpW.toFloat() / worldWidth,
            vpH.toFloat() / (worldHeight * topdownYScale)
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
            phoneVpH.toFloat() / (worldHeight * topdownYScale)
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
        // 验证：视口世界高度（按俯视压缩）不超过世界高度（无底部空白）
        val eh = tallVpH / (camera.scale * topdownYScale)
        assertTrue("视口世界高度不应超过世界高度", eh <= worldHeight + 0.1f)
        // 验证：至少一个维度刚好填满视口
        assertTrue("至少一个维度应填满视口",
            (worldWidth * camera.scale >= tallVpW - 0.5f) ||
            (worldHeight * camera.scale * topdownYScale >= tallVpH - 0.5f))
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
        val eh = lh / (camera.scale * topdownYScale)
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
        val eh = th / (camera.scale * topdownYScale)
        assertTrue("视口世界高度不应超过世界高度", eh <= worldHeight + 0.1f)
    }

    @Test
    fun `computeDefaultScale - small screen not below MIN_ZOOM`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        // 极小屏：480×800
        camera.updateViewport(480, 800)
        assertTrue("极小屏缩放不应低于 MIN_ZOOM",
            camera.scale >= CameraState.MIN_ZOOM)
        val eh = 800 / (camera.scale * topdownYScale)
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
            val eh = h / (camera.scale * topdownYScale)
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
        // scale = 缩放中值，cameraX/Y = 0 → sx = 100*scale；Y 按俯视压缩系数缩放
        assertEquals(100f * camera.scale, sx, 0.001f)
        assertEquals(50f * camera.scale * topdownYScale, sy, 0.001f)
    }

    @Test
    fun `screenToWorld - default scale un-maps coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        assertEquals(100f / camera.scale, camera.screenToWorldX(100f), 0.001f)
        assertEquals(50f / (camera.scale * topdownYScale), camera.screenToWorldY(50f), 0.001f)
    }

    @Test
    fun `worldToScreen - scale gt 1 scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH) // scale = 0.938
        }
        assertEquals(100f * camera.scale, camera.worldToScreenX(100f), 0.1f)
        assertEquals(100f * camera.scale * topdownYScale, camera.worldToScreenY(100f), 0.1f)
    }

    @Test
    fun `screenToWorld - scale gt 1 un-scales coordinates`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(largeVpW, largeVpH)
        }
        assertEquals(100f / camera.scale, camera.screenToWorldX(100f), 0.1f)
        assertEquals(100f / (camera.scale * topdownYScale), camera.screenToWorldY(100f), 0.1f)
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

    /** 崖壁带外扩边距（与 SectCameraState.ISLAND_CLIFF_VISIBLE_OUTSET 同值——修改必同步）。
     *  2026-09 素材换代：37 张薄切片 island_edge（时代 outset=400）→ 7 张整块崖壁
     *  island_cliff（最大 1180×3552，绘制于世界矩形外侧）→ outset = 1180/2400 + 余量 100。 */
    private val edgeOutset = 2500f

    @Test
    fun `pan - with default scale moves camera by screen pixels over scale`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH) // scale = 0.469
        }
        camera.pan(-100f, -200f) // 左滑/上滑 → 相机右移/下移
        // cameraX = 0 - (-100/scale), cameraY = 0 - (-200/scale)
        val outset = edgeOutset
        val visibleW = phoneVpW / camera.scale
        val visibleH = phoneVpH / (camera.scale * topdownYScale)
        assertTrue(
            "cameraX 应在 [-outset, worldWidth + outset - visibleW] 范围内（边缘带可进入）",
            camera.cameraX >= -outset &&
                camera.cameraX <= (worldWidth + outset - visibleW).coerceAtLeast(-outset)
        )
        assertTrue(
            "cameraY 应在 [-outset, worldHeight + outset - visibleH] 范围内（边缘带可进入）",
            camera.cameraY >= -outset &&
                camera.cameraY <= (worldHeight + outset - visibleH).coerceAtLeast(-outset)
        )
    }

    @Test
    fun `pan - with scale gt 1 applies inverse scale to camera`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(4000, 3000) // scale = maxOf(4000/4608, 3000/4608) = 0.868
        }
        camera.pan(0f, -150f)
        assertTrue("cameraY 应在有效范围内（边缘带外扩）", camera.cameraY >= -edgeOutset)
    }

    @Test
    fun `clamp - camera cannot go below island edge outset`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        camera.pan(-500f, -1000f)
        assertTrue("cameraX 不应小于 -outset", camera.cameraX >= -edgeOutset)
        assertTrue("cameraY 不应小于 -outset", camera.cameraY >= -edgeOutset)
    }

    @Test
    fun `clamp - camera cannot exceed world plus island edge outset`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        // 尝试大幅右移 → 相机到视口右沿 = 世界右边界 + 边缘带外扩
        camera.pan(-99999f, -99999f)
        val outset = edgeOutset
        val maxX = (worldWidth + outset - phoneVpW / camera.scale).coerceAtLeast(-outset)
        val maxY = (worldHeight + outset - phoneVpH / (camera.scale * topdownYScale))
            .coerceAtLeast(-outset)
        assertTrue("cameraX 不应超过世界边界 + 边缘外扩", camera.cameraX <= maxX + 0.001f)
        assertTrue("cameraY 不应超过世界边界 + 边缘外扩", camera.cameraY <= maxY + 0.001f)
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
        // 允许缩到世界适配以下（看到地图外天空），但不应低于合理的绝对下限（不缩成一点）
        assertTrue("zoom 缩小不应低于合理下限", camera.scale > 0.05f)
        assertTrue("zoom 缩小应为有限值", camera.scale.isFinite())
    }

    @Test
    fun `zoom - 缩小可超出世界适配，浮空岛四周露出天空`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        val default = camera.scale
        // 极端缩小 → 被钳制到天空可视下界（低于世界适配），视口超出世界 → 露出天空
        camera.zoom(0.001f, phoneVpW / 2f, phoneVpH / 2f)
        assertTrue("缩小后 scale 应低于世界适配（可看到地图外天空）", camera.scale < default)
        val ew = phoneVpW / camera.scale
        val eh = phoneVpH / (camera.scale * topdownYScale)
        assertTrue("缩小后视口世界尺寸应超出世界（露出天空）", ew > worldWidth || eh > worldHeight)
        // 天空可视下界受绝对下限保护（不缩成一点）
        assertTrue("scale 不应低于合理下限", camera.scale > 0.05f)
        // 岛屿仍完整可见：世界中心在视口内（未被钳到角落/移出画面）
        val cx = camera.worldToScreenX(worldWidth / 2f)
        val cy = camera.worldToScreenY(worldHeight / 2f)
        assertTrue("世界中心应在视口内",
            cx in (0f..phoneVpW.toFloat()) && cy in (0f..phoneVpH.toFloat()))
    }

    // ==================== 浮空岛边缘带可见性（地图边缘系统） ====================

    @Test
    fun `clampPosition - 视口小于世界时允许进入边缘带（拖到地图边缘可见悬崖）`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        // 视口 < 世界（默认游玩视角）：硬边界外扩边缘带厚度
        val visibleW = phoneVpW / camera.scale
        val visibleH = phoneVpH / (camera.scale * topdownYScale)
        assertTrue(visibleW < worldWidth)

        // 向左拖（pan dx>0 → cameraX 减小）到最左：相机 X 被钳制到 -outset（而非 0）——
        // 悬崖边缘带（y ∈ [-224, 0] 的上环与左右环）进入视口
        camera.pan(100000f, 0f)
        assertEquals("拖到最左边缘时相机应外扩边缘带", -edgeOutset, camera.cameraX, 0.001f)
        // 向右拖到最右：右侧外扩对称成立
        camera.pan(-200000f, 0f)
        assertEquals(
            "拖到最右边缘时相机应外扩边缘带",
            worldWidth + edgeOutset - visibleW,
            camera.cameraX,
            0.001f
        )
        // 向上拖到最上：上环悬崖带完整进入视口
        camera.pan(0f, 100000f)
        assertEquals("拖到最上边缘时相机应外扩边缘带", -edgeOutset, camera.cameraY, 0.001f)
        // 向下拖到最下：下环悬垂带对称
        camera.pan(0f, -200000f)
        assertEquals(
            "拖到最下边缘时相机应外扩边缘带",
            worldHeight + edgeOutset - visibleH,
            camera.cameraY,
            0.001f
        )
    }

    @Test
    fun `clampPosition - 视口超出世界时整岛居中悬浮（天空语义保留）`() {
        val camera = SectCameraState(worldWidth, worldHeight).apply {
            updateViewport(phoneVpW, phoneVpH)
        }
        // 缩到最小：视口世界尺寸 ≥ 世界（整岛完整可见）→ 该轴居中——
        // 浮空岛悬浮天际契约（两侧对称天空，岛不滞留在崖壁钳制带一侧）。
        // 注：居中触发阈值 = 视口 ≥ 世界（非世界 + 2×outset）——outset(2500)
        // 远超最小缩放视口的天空余量，以 outset 为门槛时居中分支在常见机型
        // 不可达（回归史：崖壁素材换代曾把该阈值抬到世界+2×outset）。
        camera.zoom(0.001f, phoneVpW / 2f, phoneVpH / 2f)
        val visibleW = phoneVpW / camera.scale
        val visibleH = phoneVpH / (camera.scale * topdownYScale)
        assertTrue("极限缩小时视口应超出世界（整岛完整可见）", visibleW >= worldWidth)
        assertTrue("极限缩小时视口高度应超出世界", visibleH >= worldHeight)
        assertEquals("岛中心 X 应屏幕居中", phoneVpW / 2f, camera.worldToScreenX(worldWidth / 2f), 0.5f)
        assertEquals("岛中心 Y 应屏幕居中", phoneVpH / 2f, camera.worldToScreenY(worldHeight / 2f), 0.5f)
    }

    // ==================== 浮空岛整岛可见（下界基数 max→min） ====================

    /** 与 SectCameraState.SKY_MARGIN_FACTOR 对齐（天空边距系数：岛占视口短边比例） */
    private val skyMarginFactor = 0.75f

    @Test
    fun `minScaleBound - 极限缩小时整座岛完整可见且居中（横屏）`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        val vpW = 1920; val vpH = 1080
        camera.updateViewport(vpW, vpH)
        camera.zoom(0.001f, vpW / 2f, vpH / 2f)
        // 下界 = 整岛适配（min 基数：完整进入视口的缩放，高度比按俯视压缩扩大）× 天空边距系数
        val wantMin = minOf(
            vpW.toFloat() / worldWidth,
            vpH.toFloat() / (worldHeight * topdownYScale)
        ) * skyMarginFactor
        assertEquals("横屏最小缩放 = 整岛适配 × 0.75", wantMin, camera.scale, 0.001f)
        // 两个方向视口均超出世界 → 整岛完整可见、四周露天空（短轴被裁剪成"长方形条带"即为回归）
        assertTrue("横屏极限缩小时视口宽度应超出世界（左右露天空）",
            vpW / camera.scale > worldWidth)
        assertTrue("横屏极限缩小时视口高度应超出世界（上下露天空）",
            vpH / (camera.scale * topdownYScale) > worldHeight)
        // clampPosition 两轴均超出 → 岛居中悬浮于天空中央（非锚定左上角）
        assertEquals("岛中心 X 应屏幕居中", vpW / 2f, camera.worldToScreenX(worldWidth / 2f), 0.5f)
        assertEquals("岛中心 Y 应屏幕居中", vpH / 2f, camera.worldToScreenY(worldHeight / 2f), 0.5f)
    }

    @Test
    fun `minScaleBound - 极限缩小时整座岛完整可见且居中（竖屏）`() {
        val camera = SectCameraState(worldWidth, worldHeight)
        val vpW = 1080; val vpH = 2400
        camera.updateViewport(vpW, vpH)
        camera.zoom(0.001f, vpW / 2f, vpH / 2f)
        val wantMin = minOf(
            vpW.toFloat() / worldWidth,
            vpH.toFloat() / (worldHeight * topdownYScale)
        ) * skyMarginFactor
        assertEquals("竖屏最小缩放 = 整岛适配 × 0.75", wantMin, camera.scale, 0.001f)
        assertTrue("竖屏极限缩小时视口宽度应超出世界（左右露天空）",
            vpW / camera.scale > worldWidth)
        assertTrue("竖屏极限缩小时视口高度应超出世界（上下露天空）",
            vpH / (camera.scale * topdownYScale) > worldHeight)
        assertEquals("岛中心 X 应屏幕居中", vpW / 2f, camera.worldToScreenX(worldWidth / 2f), 0.5f)
        assertEquals("岛中心 Y 应屏幕居中", vpH / 2f, camera.worldToScreenY(worldHeight / 2f), 0.5f)
    }

    @Test
    fun `minScaleBound - 全机型极限缩小均整岛完整可见`() {
        // 镜像 fill guarantee：任意常见长宽比下缩到最小都必须能看到整座岛
        //（短轴被裁剪即为回归——玩家会误以为地图是长方形）
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
            camera.zoom(0.001f, w / 2f, h / 2f)
            val ew = w / camera.scale
            val eh = h / (camera.scale * topdownYScale)
            val msg = "w=${w}h=${h} scale=${camera.scale}: 视口世界尺寸(${ew}x${eh}) " +
                      "应超出世界(${worldWidth}x${worldHeight})——整岛必须完整可见"
            assertTrue(msg, ew > worldWidth)
            assertTrue(msg, eh > worldHeight)
        }
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
            phoneVpH.toFloat() / (worldHeight * topdownYScale)
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

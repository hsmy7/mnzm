package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderFlags
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 宗门地图地面 REPEAT 源缩放的回归守卫（2026-09-03「地图变成米色空隙」问题根因）。
 *
 * ## 背景
 * [com.xianxia.sect.ui.game.sect.SectAtlasAssembler] 为防低端机 OOM 把软件路径图集按
 * canvasAtlasScale = CANVAS_ATLAS_MAX / ATLAS_W = 2048/4096 = 0.5 缩放到 2048，但
 * SoftwareCanvasBackend 采样 SpriteAtlasDef 源矩形时曾直接用 4096 坐标系——地面 REPEAT
 * 源裁出「绿块 + 透明」区域，整图平铺后地面出现米色空隙（正是截图症状）。
 *
 * ## 守卫方式
 * 用真实生产尺寸（tileSize=36、世界 128×128）渲染一个 0.5× 缩放图集，统计米色像素。
 * 修复前（源矩形未缩放）米色占比显著；修复后地面应连续纯地面色，米色占比≈0。
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ReproGroundScaleTest {

    @Test
    fun `ground fill covers whole view - no cream gaps when atlas is 0_5 scaled`() {
        val backend = SoftwareCanvasBackend(
            NativeRenderConfig(
                tileSize = GameConfig.SectMap.TILE_SIZE,
                worldWidthCells = GameConfig.SectMap.WORLD_WIDTH_CELLS,
                worldHeightCells = GameConfig.SectMap.WORLD_HEIGHT_CELLS,
                worldPixelWidth = GameConfig.SectMap.WORLD_PIXEL_WIDTH,
                worldPixelHeight = GameConfig.SectMap.WORLD_PIXEL_HEIGHT,
                renderFlags = RenderFlags()
            )
        )

        // 0.5× 缩放图集：GROUND 槽位 (0,0,128,128) → (0,0,64,64) 画纯绿，其余透明。
        val atlas = createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val c = Canvas(atlas)
        val groundRect = SpriteAtlasDef.TileType.GROUND.rect
        val s = fixtureSourceScale(atlas.width)
        c.drawRect(
            groundRect.x * s, groundRect.y * s,
            (groundRect.x + groundRect.w) * s, (groundRect.y + groundRect.h) * s,
            Paint().apply { color = Color.rgb(0, 255, 0) }
        )

        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = IntArray(SpriteAtlasDef.TileType.values().let {
                GameConfig.SectMap.WORLD_WIDTH_CELLS * GameConfig.SectMap.WORLD_HEIGHT_CELLS
            }) { SpriteAtlasDef.TileType.GROUND.index },
            cols = GameConfig.SectMap.WORLD_WIDTH_CELLS,
            rows = GameConfig.SectMap.WORLD_HEIGHT_CELLS,
            buildingData = null,
            buildingCount = 0,
            buildingVisible = true
        )

        val result = backend.renderFrame(frame, atlas, vpW = 360, vpH = 360)
        assertTrue("renderFrame 不应返回 null", result != null)
        val fb = result!!

        var creamCount = 0
        var greenCount = 0
        var sampled = 0
        for (y in 0 until fb.height step 3) {
            for (x in 0 until fb.width step 3) {
                sampled++
                val px = fb.getPixel(x, y)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                val isCream = kotlin.math.abs(r - 0xF2) <= 12 &&
                    kotlin.math.abs(g - 0xED) <= 12 && kotlin.math.abs(b - 0xE4) <= 12
                val isGreen = g > 100 && g > r + 30 && g > b + 30
                if (isCream) creamCount++
                if (isGreen) greenCount++
            }
        }
        val creamRatio = creamCount.toFloat() / sampled
        // 修复前：地面源裁到相邻槽位+透明 → 米色占比显著（>10%）。修复后应 <1%。
        assertTrue(
            "地面出现米色空隙占比=$creamRatio (cream=$creamCount green=$greenCount sampled=$sampled)——" +
                "源矩形未按图集缩放，REPEAT 地面源裁到错误区域",
            creamRatio < 0.01f
        )
        assertTrue(
            "地面应主要为主绿色 (green=$greenCount sampled=$sampled)",
            greenCount > sampled * 0.9f
        )
    }
}

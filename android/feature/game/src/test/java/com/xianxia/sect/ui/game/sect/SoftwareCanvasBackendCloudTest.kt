package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 云层渲染测试（2026-08-22 动态云层）。
 *
 * 覆盖维度：
 * - 云层画在建筑之上（顶部区域像素 = 云色而非建筑色）
 * - 无云数据（null）不绘制
 * - 视口外云朵被剔除
 * - alpha × 淡入乘算（半透明混合像素断言）
 * - 装饰 LOD/热控降质（decorSkip）时云层整层跳过
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（与 SoftwareCanvasBackendTest 同约定）。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendCloudTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    /** 云层测试图集：2048×2048，槽位按 SpriteAtlasDef 源矩形缩放绘制（与后端采样对齐） */
    private fun createCloudAtlas(): Bitmap {
        val bmp = createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // 地面源（chunk 底）
        val groundRect = scaledFixtureRect(2048, SpriteAtlasDef.TileType.GROUND.rect)
        c.drawRect(groundRect, Paint().apply { color = Color.rgb(100, 100, 100) })
        // 灵田建筑精灵源（nameIdx=2 → buildingRect(2)）= 白色
        val buildingRect = scaledFixtureRect(2048, SpriteAtlasDef.buildingRect(2))
        c.drawRect(buildingRect, Paint().apply { color = Color.WHITE })
        // 云层 1 槽位（CLOUD_RECTS.first()）= 亮青色
        val cloudRect = SpriteAtlasDef.CLOUD_RECTS.first().second
        val cloudArea = scaledFixtureRect(2048, cloudRect)
        c.drawRect(
            cloudArea.left.toFloat(), cloudArea.top.toFloat(),
            cloudArea.right.toFloat(), cloudArea.bottom.toFloat(),
            Paint().apply { color = Color.rgb(CLOUD_RED, CLOUD_GREEN, CLOUD_BLUE) }
        )
        return bmp
    }

    /** 云层测试帧：建筑 (0,0) 占地 1×1 + 相机原点 */
    private fun cloudFrame(): RenderFrame =
        spiritFieldFrame(createFlatTileData(10, 10))

    /** 单朵云数据：覆盖世界 (0,0)-(484,120)，类型 0，alpha 可调 */
    private fun cloudDataAt(x: Float, alpha: Float = 1f): FloatArray {
        val cloudRect = SpriteAtlasDef.CLOUD_RECTS.first().second
        return floatArrayOf(
            x, 0f, cloudRect.w.toFloat(), cloudRect.h.toFloat(), 0f, alpha
        )
    }

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        atlas = createCloudAtlas()
    }

    @Test
    fun `云层画在建筑之上`() {
        // 无云：采样点 (32,32) 是建筑白色
        val noCloud = backend.renderFrame(cloudFrame(), atlas, vpW = 200, vpH = 200)!!
        val without = noCloud.getPixel(32, 32)
        assertNear(255, Color.red(without), 8)
        assertNear(255, Color.green(without), 8)

        // 有云：云朵覆盖 (0,0)-(484,120)，绘制在建筑之后 → 采样点为云色
        val withCloud = backend.renderFrame(
            cloudFrame(), atlas, vpW = 200, vpH = 200, cloudData = cloudDataAt(0f)
        )!!
        val px = withCloud.getPixel(32, 32)
        assertNear(CLOUD_RED, Color.red(px), 8)
        assertNear(CLOUD_GREEN, Color.green(px), 8)
        assertNear(CLOUD_BLUE, Color.blue(px), 8)
    }

    @Test
    fun `云数据为 null 时不绘制云层`() {
        val result = backend.renderFrame(cloudFrame(), atlas, vpW = 200, vpH = 200)!!
        // 采样点应为建筑白色（无云层覆盖）
        val px = result.getPixel(32, 32)
        assertNear(255, Color.red(px), 8)
        assertNear(255, Color.green(px), 8)
    }

    @Test
    fun `视口外云朵被剔除`() {
        // 云朵完全位于世界左侧外（x + w ≤ 0，云宽 968 → x ≤ -968）→ 视口内不可见，建筑保持白色
        val offScreen = backend.renderFrame(
            cloudFrame(), atlas, vpW = 200, vpH = 200, cloudData = cloudDataAt(-1200f)
        )!!
        val px = offScreen.getPixel(32, 32)
        assertNear(255, Color.red(px), 8)
        assertNear(255, Color.green(px), 8)
    }

    @Test
    fun `云层 alpha 与淡入乘算`() {
        // alpha=1.0 × fade=0.5 → 半透明混合：0.5×云色 + 0.5×建筑白
        val half = backend.renderFrame(
            cloudFrame(), atlas, vpW = 200, vpH = 200,
            fadeAlpha = 0.5f, cloudData = cloudDataAt(0f)
        )!!
        val px = half.getPixel(32, 32)
        val expR = (CLOUD_RED + 255) / 2
        val expG = (CLOUD_GREEN + 255) / 2
        val expB = (CLOUD_BLUE + 255) / 2
        assertNear(expR, Color.red(px), 8)
        assertNear(expG, Color.green(px), 8)
        assertNear(expB, Color.blue(px), 8)
    }

    @Test
    fun `装饰 LOD 降质时云层整层跳过`() {
        // qualityFactor < 0.6 → decorSkip=true（与装饰层同判定）→ 云层不绘制
        val lowBackend = SoftwareCanvasBackend(testRenderConfig()).apply {
            qualityFactor = 0.5f
        }
        val result = lowBackend.renderFrame(
            cloudFrame(), atlas, vpW = 200, vpH = 200, cloudData = cloudDataAt(0f)
        )!!
        val px = result.getPixel(32, 32)
        // 建筑白色仍在，云色（青）不应出现
        assertNear(255, Color.red(px), 16)
        assertNear(255, Color.green(px), 16)
        // 红通道远大于云色红通道（198）——排除云层上屏
        assertTrue("装饰降质时云层应跳过 (r=${Color.red(px)})", Color.red(px) > 230)
    }

    @Test
    fun `renderFrame 云层路径不抛异常_非法云数据防御`() {
        // 含 NaN/非法条目的云数据不应抛异常（与 C++ 段同语义——跳过非法实例）
        val badData = floatArrayOf(
            Float.NaN, 0f, 100f, 100f, 0f, 1f,   // x=NaN → 跳过
            0f, 0f, -5f, 100f, 0f, 1f,           // w=-5 → 跳过
            0f, 0f, 100f, 100f, 0f, 2f           // alpha=2 → 跳过
        )
        val result = backend.renderFrame(
            cloudFrame(), atlas, vpW = 200, vpH = 200, cloudData = badData
        )
        assertNotNull(result)
        // 全部非法 → 无云绘制，采样点为建筑白色
        val px = result!!.getPixel(32, 32)
        assertNear(255, Color.red(px), 8)
    }

    private companion object {
        /** 云层源色（亮青色，与建筑白/地面灰/米色底区分） */
        const val CLOUD_RED = 200
        const val CLOUD_GREEN = 240
        const val CLOUD_BLUE = 255
    }
}

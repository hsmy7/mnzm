package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import com.xianxia.sect.core.render.NativeRenderConfig
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AtlasAsyncPipeline] RGBA mip 链守卫。
 *
 * 锁定不变量：
 * 1. [encodeBitmapToRgbaMipChain]：2048² 源 → **11 级** mip 链（2048→2，逐级 50%
 *    双线性级联），level-major 紧凑布局——首级 = 完整图集（单级回退兼容），
 *    总字节 = Σ level²×4。
 * 2. [AtlasAsyncPipeline.uploadAtlas]：RGBA 路径经注入点
 *    [NativeSurfaceView.mipChainUploader] 多级上传——Fake 断言 JNI 入参
 *    （mipCount/级尺寸/首级数据完整性）。
 *
 * 边界：native 库不加载（JVM 测试环境），C++ uploadMipChainTexture 逐级拷贝与
 * VUID 对齐由 `externalNativeBuildRelease` 编译门 + 真机验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AtlasAsyncPipelineMipChainTest {

    @Before
    fun setup() {
        // native 库不加载（JVM 环境）——NativeBridge.external 调用会抛
        // UnsatisfiedLinkError，测试只走注入 Fake，不触碰真实 JNI。
        // uploadGroundTexture 内部 catch Throwable，静默降级不影响断言。
    }

    @Test
    fun `2048 源编码 - 11 级 mip 链级联减半到 2`() {
        val src = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val chain = encodeBitmapToRgbaMipChain(src)
        try {
            // 2048→2 共 11 级（与 ASTC KTX 的 11 级 mip 语义对齐）
            assertEquals("mip 层级数", 11, chain.mipCount)
            // 各级尺寸：2048, 1024, ..., 2
            for (k in 0 until chain.mipCount) {
                val expected = 2048 shr k
                assertEquals("mip[$k] 宽", expected, chain.widths[k])
                assertEquals("mip[$k] 高", expected, chain.heights[k])
            }
            // level-major 紧凑：总字节 = Σ level²×4
            val expectedBytes = chain.widths.fold(0L) { acc, w -> acc + w.toLong() * w * 4 }
            assertEquals("缓冲区容量 = 各级像素总和×4", expectedBytes, chain.buffer.capacity().toLong())
            // 首级 = 完整图集（单级回退兼容：uploadTextureDirect 按 buffer 起始地址读取）
            assertEquals("首级宽 = 源宽", src.width, chain.widths[0])
        } finally {
            chain.buffer.clear()
        }
    }

    @Test
    fun `小图源编码 - 级数随尺寸收敛且容量守恒`() {
        val src = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val chain = encodeBitmapToRgbaMipChain(src)
        // 高轴 32→2 后停止（>2 才继续）：64/32 → 32/16 → 16/8 → 8/4 → 4/2 共 5 级
        assertEquals(5, chain.mipCount)
        assertEquals(intArrayOf(64, 32, 16, 8, 4).toList(), chain.widths.toList())
        assertEquals(intArrayOf(32, 16, 8, 4, 2).toList(), chain.heights.toList())
        val expectedBytes = chain.widths.indices.sumOf { k ->
            chain.widths[k].toLong() * chain.heights[k] * 4
        }
        assertEquals(expectedBytes, chain.buffer.capacity().toLong())
    }

    @Test
    fun `uploadAtlas RGBA 路径 - Fake 注入断言 mip 链入参`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = createView()
        val pipeline = AtlasAsyncPipeline(view)

        // Fake 注入：捕获 (buffer, w, h, mipCount) 入参，返回哨兵纹理 ID
        var captured: Quad<ByteBuffer, Int, Int, Int>? = null
        view.mipChainUploader = { buffer, w, h, mips ->
            captured = Quad(buffer, w, h, mips)
            42
        }

        // 64² 源 mip 链（64→2 共 6 级）+ 单级回退缓冲共享首级数据
        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        src.eraseColor(0xFF336699.toInt())
        val chain = encodeBitmapToRgbaMipChain(src)
        val payload = AtlasPayload(
            rgbaMipPixels = chain.buffer,
            mipCount = chain.mipCount,
            width = 64,
            height = 64
        )

        val texId = pipeline.uploadAtlas(context, payload)

        assertEquals("应透传 Fake 哨兵 ID", 42, texId)
        val cap = captured ?: throw AssertionError("mipChainUploader 未被调用")
        assertEquals("入参 width", 64, cap.second)
        assertEquals("入参 height", 64, cap.third)
        assertEquals("入参 mipCount（含首级）", 6, cap.fourth)
        // 首级数据完整性：buffer 前 64×64×4 字节即完整图集像素（非空校验）
        assertEquals("缓冲容量 ≥ 首级尺寸", (64L * 64 * 4).let { it <= cap.first.capacity() }, true)
        assertEquals("缓冲按 nativeOrder 布局", ByteOrder.nativeOrder(), cap.first.order())
    }

    private fun createView(): NativeSurfaceView {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = NativeRenderConfig(
            tileSize = 64,
            worldWidthCells = 10,
            worldHeightCells = 10,
            worldPixelWidth = 640,
            worldPixelHeight = 640
        )
        return NativeSurfaceView(context, config)
    }

    /** 简易 4 元组（测试断言用） */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

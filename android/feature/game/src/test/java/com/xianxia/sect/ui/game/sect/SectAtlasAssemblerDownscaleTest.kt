package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SectAtlasAssembler.downscaleWithBilinearChain] 守卫（防道路缩放闪烁）。
 *
 * 锁定不变量：图集拼装对**深度降采样**精灵（道路主体 1254→64 ≈ 20:1×、云层 ~2:1×）
 * 必须先经双线性链式预降采样（每级 ≤2:1×，近似软件 mip 链的面积平均语义），
 * 由绘制层双线性完成最后一级——点采样（NEAREST）直接 20:1× 降采样会产出密集
 * 摩尔纹，缩放时道路呈"模糊线框 ↔ 噪点细节"跳变（视觉似闪烁）。
 */
@RunWith(RobolectricTestRunner::class)
class SectAtlasAssemblerDownscaleTest {

    @Test
    fun `深度降采样 - 链式结果收敛到目标 1~2 倍区间`() {
        val src = Bitmap.createBitmap(2500, 1200, Bitmap.Config.ARGB_8888)
        val out = SectAtlasAssembler.downscaleWithBilinearChain(src, 64, 48)
        try {
            // 终止条件为"≤2×目标"（严格大于才继续），故上界含 2×
            assertTrue("宽度应 ∈ [64, 128]，实际 ${out.width}", out.width in 64..128)
            assertTrue("高度应 ∈ [48, 96]，实际 ${out.height}", out.height in 48..96)
        } finally {
            out.recycle()
        }
    }

    @Test
    fun `非等比源 - 逐级减半终止且两轴都贴合上限`() {
        val src = Bitmap.createBitmap(513, 71, Bitmap.Config.ARGB_8888)
        val out = SectAtlasAssembler.downscaleWithBilinearChain(src, 64, 32)
        try {
            // 宽度轴：513 → 256 → 128（≤128 停止，恰为 2× 目标）
            assertTrue("宽度应 ∈ [64, 128]，实际 ${out.width}", out.width in 64..128)
            // 高度轴：71 → 35（≤64 停止）
            assertTrue("高度应 ∈ [32, 64]，实际 ${out.height}", out.height in 32..64)
        } finally {
            out.recycle()
        }
    }

    @Test
    fun `比率不超过 2 - 返回源位图不新建`() {
        val src = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val out = SectAtlasAssembler.downscaleWithBilinearChain(src, 64, 64)
        assertSame("2:1× 比率不触发预降采样（双线性绘制完成末步）", src, out)
    }

    @Test
    fun `源小于目标 - 返回源位图（绘制层负责上采样）`() {
        val src = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val out = SectAtlasAssembler.downscaleWithBilinearChain(src, 64, 64)
        assertSame("上采样不预降采样", src, out)
    }
}

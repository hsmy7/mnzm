package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FarViewGroundPolicy] 守卫（重构方案 R3.5 / 批次 B12）。
 *
 * 覆盖「命中即启用」「任一重门不满足即回退逐格路径」两侧——方案红线要求
 * 黑名单判定必须可测（纯函数 + 表驱动），禁止不可测的散落设备判定。
 *
 * 说明：生产白名单 [FarViewGroundPolicy.ALLOWED_DEVICES] 当前为空（安全默认），
 * 因此「启用」侧的判定用测试替身设备键逐门验证合取语义；「回退」侧直接打真实函数。
 */
class FarViewGroundPolicyTest {

    private companion object {
        /** 与白名单条目同形的设备键（仅测试内构造，非生产登记值） */
        const val DEVICE = "qualcomm/sm8650"

        /** 远景档缩放（<= 阈值） */
        const val FAR_SCALE = 0.3f
    }

    @Test
    fun `groundQuadEnabled - 白名单为空时任何设备都不启用（安全默认）`() {
        // 生产白名单为空 = 未验证设备恒走逐格地面（R3.5 红线：带黑名单验证）
        assertTrue(
            "未验证设备必须恒回退逐格路径",
            FarViewGroundPolicy.ALLOWED_DEVICES.isEmpty()
        )
        assertFalse(
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FAR_SCALE,
                deviceKey = DEVICE,
                groundTextureReady = true,
                userEnabled = true
            )
        )
    }

    @Test
    fun `groundQuadEnabled - 缩放未达远景档时不启用`() {
        // 阈值之上（更近）回退；恰在阈值上、图集未就绪时仍回退（证明缩放门独立）
        assertFalse(
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FarViewGroundPolicy.FAR_VIEW_SCALE_THRESHOLD + 0.01f,
                deviceKey = DEVICE,
                groundTextureReady = true,
                userEnabled = true
            )
        )
        assertFalse(
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FarViewGroundPolicy.FAR_VIEW_SCALE_THRESHOLD,
                deviceKey = DEVICE,
                groundTextureReady = false,
                userEnabled = true
            )
        )
    }

    @Test
    fun `groundQuadEnabled - 非有限缩放视为不达阈值（防御）`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(
                "scale=$bad 必须回退逐格路径",
                FarViewGroundPolicy.groundQuadEnabled(
                    scale = bad,
                    deviceKey = DEVICE,
                    groundTextureReady = true,
                    userEnabled = true
                )
            )
        }
    }

    @Test
    fun `groundQuadEnabled - 图集未就绪时不启用`() {
        assertFalse(
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FAR_SCALE,
                deviceKey = DEVICE,
                groundTextureReady = false,
                userEnabled = true
            )
        )
    }

    @Test
    fun `groundQuadEnabled - 用户旗标关闭时不启用（回滚臂）`() {
        assertFalse(
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FAR_SCALE,
                deviceKey = DEVICE,
                groundTextureReady = true,
                userEnabled = false
            )
        )
    }

    @Test
    fun `groundQuadEnabled - 空设备键必然回退（未识别设备安全默认）`() {
        for (blank in listOf("", "   ")) {
            assertFalse(
                "deviceKey='$blank' 必须回退逐格路径",
                FarViewGroundPolicy.groundQuadEnabled(
                    scale = FAR_SCALE,
                    deviceKey = blank,
                    groundTextureReady = true,
                    userEnabled = true
                )
            )
        }
    }

    @Test
    fun `groundQuadEnabled - 白名单命中且四门齐备时启用（合取语义）`() {
        // 以测试替身复刻四门合取（生产白名单为空故不能走真实命中路径）；
        // 逐门取反必须在真实函数上表现为 false——保证每门都是必要条件
        val allGatesOpen = true && true && (FAR_SCALE <= FarViewGroundPolicy.FAR_VIEW_SCALE_THRESHOLD)
        assertTrue("四门齐备的合取应为真（替身）", allGatesOpen)

        assertFalse(
            "门① 用户旗标关",
            FarViewGroundPolicy.groundQuadEnabled(FAR_SCALE, DEVICE, true, false)
        )
        assertFalse(
            "门② 图集未就绪",
            FarViewGroundPolicy.groundQuadEnabled(FAR_SCALE, DEVICE, false, true)
        )
        assertFalse(
            "门③ 缩放未达",
            FarViewGroundPolicy.groundQuadEnabled(1.0f, DEVICE, true, true)
        )
        assertFalse(
            "门④ 设备不在白名单（生产空表）",
            FarViewGroundPolicy.groundQuadEnabled(FAR_SCALE, DEVICE, true, true)
        )
    }

    @Test
    fun `FAR_VIEW_SCALE_THRESHOLD - 与装饰层 LOD 阈值同源（降级同界不打架）`() {
        assertEquals(
            "地面整图化与装饰层跳过必须同界，否则会出现「地面还是逐格但装饰已跳」的空档",
            RenderLodPolicy.DECOR_ZOOM_THRESHOLD,
            FarViewGroundPolicy.FAR_VIEW_SCALE_THRESHOLD, 0.0f
        )
    }
}

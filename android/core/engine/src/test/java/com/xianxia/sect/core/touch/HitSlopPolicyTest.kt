package com.xianxia.sect.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HitSlopPolicy 命中外扩策略测试。
 *
 * 覆盖：dp→px 换算、expandCells 在常见缩放档位下均保证 ≥ 最小触控目标、
 * 边界（tileSize 非法 / scale NaN / 极小值防御）。
 */
class HitSlopPolicyTest {

    @Test
    fun `minHitTargetPx converts dp to pixels by density`() {
        val policy = HitSlopPolicy(minHitTargetDp = 40f, density = 2.75f)
        assertEquals(110f, policy.minHitTargetPx, 0.001f)
    }

    @Test
    fun `expandCells guarantees at least min target at default sect zoom`() {
        // 宗门地图默认缩放 ≈ √(minScale×3.0)；1080×2400/420dpi 设备 ≈ 1.326
        val policy = HitSlopPolicy(minHitTargetDp = 40f, density = 2.75f)
        val expand = policy.expandCells(tileSize = 32, scale = 1.326f)
        // 外扩后命中区屏幕尺寸 = (2×expand+1) × 32 × 1.326 ≥ 110px
        val hitPx = (2 * expand + 1) * 32f * 1.326f
        assertTrue("默认缩放下 1×1 建筑命中区应 ≥ 40dp(110px)，实际 ${hitPx}px", hitPx >= policy.minHitTargetPx)
    }

    @Test
    fun `expandCells grows when zooming out and shrinks when zooming in`() {
        val policy = HitSlopPolicy(minHitTargetDp = 40f, density = 2.75f)
        val zoomedIn = policy.expandCells(tileSize = 32, scale = 3.0f)
        val zoomedOut = policy.expandCells(tileSize = 32, scale = 0.3f)
        assertTrue("缩小时外扩格数应更多", zoomedOut > zoomedIn)
    }

    @Test
    fun `expandCells zero for invalid tile size or scale`() {
        val policy = HitSlopPolicy(minHitTargetDp = 40f, density = 2.75f)
        assertEquals(0, policy.expandCells(tileSize = 0, scale = 1f))
        assertEquals(0, policy.expandCells(tileSize = -32, scale = 1f))
        assertEquals(0, policy.expandCells(tileSize = 32, scale = 0f))
        assertEquals(0, policy.expandCells(tileSize = 32, scale = Float.NaN))
        assertEquals(0, policy.expandCells(tileSize = 32, scale = Float.POSITIVE_INFINITY))
    }

    @Test
    fun `expandCells is at least one when tile barely covers target`() {
        // 命中区 1 格已达标时也应至少外扩 0（return 0 = 单格命中），
        // 这里验证极小 tileSize 下外扩 ≥ 1（保证小格地图同样可点）
        val policy = HitSlopPolicy(minHitTargetDp = 40f, density = 1f) // 40px 目标
        val expand = policy.expandCells(tileSize = 8, scale = 1f)
        assertTrue("40px 目标 / 8px 格 → 外扩 ≥ 2", expand >= 2)
    }

    @Test
    fun `constructor rejects invalid parameters`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HitSlopPolicy(minHitTargetDp = 0f, density = 2f)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HitSlopPolicy(minHitTargetDp = 40f, density = 0f)
        }
    }
}

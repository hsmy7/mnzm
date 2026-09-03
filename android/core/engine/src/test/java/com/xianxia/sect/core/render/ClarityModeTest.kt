package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自选清晰度档位守卫测试（2026-09-02 D1）。
 *
 * 覆盖：
 * - fromStorage 解析（null/非法回退默认 = 中；合法值正确解析）
 * - 档位参数单调性（renderScaleCap/qualityFactor 随档位递增）
 * - mipmap/各向异性策略（仅中档及以上启用）
 */
class ClarityModeTest {

    @Test
    fun `fromStorage - null and invalid fall back to MEDIUM`() {
        assertEquals(ClarityMode.MEDIUM, ClarityMode.fromStorage(null))
        assertEquals(ClarityMode.MEDIUM, ClarityMode.fromStorage("UNKNOWN"))
        assertEquals(ClarityMode.MEDIUM, ClarityMode.fromStorage(""))
    }

    @Test
    fun `fromStorage - valid name parses correctly`() {
        assertEquals(ClarityMode.VERY_LOW, ClarityMode.fromStorage("VERY_LOW"))
        assertEquals(ClarityMode.LOW, ClarityMode.fromStorage("LOW"))
        assertEquals(ClarityMode.HIGH, ClarityMode.fromStorage("HIGH"))
        assertEquals(ClarityMode.VERY_HIGH, ClarityMode.fromStorage("VERY_HIGH"))
    }

    @Test
    fun `renderScaleCap is non-decreasing across tiers`() {
        val caps = ClarityMode.entries.map { it.renderScaleCap }
        for (i in 1 until caps.size) {
            assertTrue(
                "renderScaleCap 应随档位递增: ${ClarityMode.entries[i - 1]}(${caps[i - 1]}) > " +
                    "${ClarityMode.entries[i]}(${caps[i]})",
                caps[i] >= caps[i - 1]
            )
        }
        assertEquals(0.5f, ClarityMode.VERY_LOW.renderScaleCap, 0.001f)
        assertEquals(1.0f, ClarityMode.VERY_HIGH.renderScaleCap, 0.001f)
    }

    @Test
    fun `qualityFactor is non-decreasing across tiers`() {
        val q = ClarityMode.entries.map { it.qualityFactor }
        for (i in 1 until q.size) {
            assertTrue("qualityFactor 应随档位递增", q[i] >= q[i - 1])
        }
        assertEquals(0.40f, ClarityMode.VERY_LOW.qualityFactor, 0.001f)
        assertEquals(1.00f, ClarityMode.VERY_HIGH.qualityFactor, 0.001f)
    }

    @Test
    fun `mipmap only enabled for MEDIUM and above`() {
        assertFalse(ClarityMode.VERY_LOW.mipmap)
        assertFalse(ClarityMode.LOW.mipmap)
        assertTrue(ClarityMode.MEDIUM.mipmap)
        assertTrue(ClarityMode.HIGH.mipmap)
        assertTrue(ClarityMode.VERY_HIGH.mipmap)
    }

    @Test
    fun `anisotropy scales with tier`() {
        assertEquals(AnisotropyMode.OFF, ClarityMode.VERY_LOW.anisotropy)
        assertEquals(AnisotropyMode.OFF, ClarityMode.LOW.anisotropy)
        assertEquals(AnisotropyMode.X2, ClarityMode.MEDIUM.anisotropy)
        assertEquals(AnisotropyMode.X4, ClarityMode.HIGH.anisotropy)
        assertEquals(AnisotropyMode.X8, ClarityMode.VERY_HIGH.anisotropy)
    }
}

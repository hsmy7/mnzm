package com.xianxia.sect.core.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RenderLodPolicy] 纯函数测试——装饰层 LOD 判定真值表。
 */
class RenderLodPolicyTest {

    @Test
    fun `decorationsEnabled - all conditions satisfied returns true`() {
        assertTrue(RenderLodPolicy.decorationsEnabled(scale = 1.0f, decorationsDisabled = false, qualityFactor = 1.0f))
    }

    @Test
    fun `decorationsEnabled - exact threshold boundaries pass`() {
        assertTrue(RenderLodPolicy.decorationsEnabled(0.6f, false, 0.6f))
    }

    @Test
    fun `decorationsEnabled - decorations disabled flag forces off`() {
        assertFalse(RenderLodPolicy.decorationsEnabled(1.0f, true, 1.0f))
    }

    @Test
    fun `decorationsEnabled - low quality factor forces off`() {
        assertFalse(RenderLodPolicy.decorationsEnabled(1.0f, false, 0.59f))
    }

    @Test
    fun `decorationsEnabled - zoomed out below threshold forces off`() {
        assertFalse(RenderLodPolicy.decorationsEnabled(0.59f, false, 1.0f))
    }

    @Test
    fun `decorationsEnabled - NaN or infinite scale forces off (defensive)`() {
        assertFalse(RenderLodPolicy.decorationsEnabled(Float.NaN, false, 1.0f))
        assertFalse(RenderLodPolicy.decorationsEnabled(Float.POSITIVE_INFINITY, false, 1.0f))
        assertFalse(RenderLodPolicy.decorationsEnabled(Float.NEGATIVE_INFINITY, false, 1.0f))
    }

    @Test
    fun `decorationsEnabled - any single failing condition disables`() {
        // 全真值表：仅全满足为 true
        assertFalse(RenderLodPolicy.decorationsEnabled(0.5f, true, 0.5f))
        assertFalse(RenderLodPolicy.decorationsEnabled(0.5f, true, 1.0f))
        assertFalse(RenderLodPolicy.decorationsEnabled(1.0f, true, 0.5f))
        assertFalse(RenderLodPolicy.decorationsEnabled(0.5f, false, 0.5f))
    }
}

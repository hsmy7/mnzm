package com.xianxia.sect.ui.game

import androidx.compose.ui.graphics.ImageBitmap
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePreloaderTest {

    // ── calculateSampleSize 逻辑测试 ──

    @Test
    fun `calculateSampleSize - small image returns 1`() {
        // 图片尺寸小于 maxDimension 时不缩放
        val result = ResourcePreloader.calcSampleSize(
            width = 100, height = 100, maxDimension = 300
        )
        assertEquals(1, result)
    }

    @Test
    fun `calculateSampleSize - image exactly at max returns 1`() {
        val result = ResourcePreloader.calcSampleSize(
            width = 300, height = 300, maxDimension = 300
        )
        assertEquals(1, result)
    }

    @Test
    fun `calculateSampleSize - double max dimension returns 2`() {
        // 600 / (1*2) = 300 >= 300 → sampleSize*=2 → 2
        // 600 / (2*2) = 150 < 300 → stop → 2
        val result = ResourcePreloader.calcSampleSize(
            width = 600, height = 600, maxDimension = 300
        )
        assertEquals(2, result)
    }

    @Test
    fun `calculateSampleSize - large image returns appropriate sample`() {
        val result = ResourcePreloader.calcSampleSize(
            width = 2400, height = 2400, maxDimension = 300
        )
        // 2400/(1*2)=1200>=300 → *2
        // 2400/(2*2)=600>=300 → *2
        // 2400/(4*2)=300>=300 → *2
        // 2400/(8*2)=150<300 → stop → 8
        assertEquals(8, result)
    }

    @Test
    fun `calculateSampleSize - wide thin image scales by width`() {
        // 1200/(1*2)=600>=300 → *2
        // 1200/(2*2)=300>=300 → *2
        // 1200/(4*2)=150<300 → stop → 4
        val result = ResourcePreloader.calcSampleSize(
            width = 1200, height = 10, maxDimension = 300
        )
        assertEquals(4, result)
    }

    @Test
    fun `calculateSampleSize - tall thin image scales by height`() {
        // 1200/(1*2)=600>=300 → *2
        // 1200/(2*2)=300>=300 → *2
        // 1200/(4*2)=150<300 → stop → 4
        val result = ResourcePreloader.calcSampleSize(
            width = 10, height = 1200, maxDimension = 300
        )
        assertEquals(4, result)
    }

    @Test
    fun `calculateSampleSize - custom max dimension for portraits`() {
        // 512 / (1*2) = 256 >= 256 → *2
        // 512 / (2*2) = 128 < 256 → stop → 2
        val result = ResourcePreloader.calcSampleSize(
            width = 512, height = 512, maxDimension = 256
        )
        assertEquals(2, result)
    }

    // ── PreloadResult 数据类测试 ──

    @Test
    fun `PreloadResult - constructed with empty maps`() {
        val result = ResourcePreloader.PreloadResult(
            buildingBitmaps = emptyMap(),
            itemSprites = emptyMap(),
            portraitSprites = emptyMap(),
            uiSprites = emptyMap()
        )
        assertNotNull(result)
        assertTrue(result.buildingBitmaps.isEmpty())
        assertTrue(result.itemSprites.isEmpty())
        assertTrue(result.portraitSprites.isEmpty())
        assertTrue(result.uiSprites.isEmpty())
    }

    @Test
    fun `PreloadResult - constructed with populated maps`() {
        val mockBitmap = mockk<ImageBitmap>(relaxed = true)
        val result = ResourcePreloader.PreloadResult(
            buildingBitmaps = mapOf("alchemy" to mockBitmap),
            itemSprites = mapOf(1 to mockBitmap, 2 to mockBitmap),
            portraitSprites = mapOf("male_1" to mockBitmap),
            uiSprites = mapOf("ui_button" to mockBitmap)
        )
        assertEquals(1, result.buildingBitmaps.size)
        assertEquals(2, result.itemSprites.size)
        assertEquals(1, result.portraitSprites.size)
        assertEquals(1, result.uiSprites.size)
    }
}

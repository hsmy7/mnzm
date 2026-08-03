package com.xianxia.sect.ui.game.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历战卡片轮转状态机单元测试。
 * 覆盖：循环翻页（正反向）、单卡禁用、槽位环绕计算、列表收缩收敛。
 */
class LizhanCarouselStateTest {

    // ── next / prev 循环 ─────────────────────────────────────────────

    @Test
    fun `next - 双卡循环 0 到 1 再回到 0`() {
        val carousel = LizhanCarouselState(2)
        assertEquals(0, carousel.currentIndex)
        assertEquals(1, carousel.next())
        assertEquals(0, carousel.next())
    }

    @Test
    fun `next - 三卡循环 0 到 1 到 2 再回到 0`() {
        val carousel = LizhanCarouselState(3)
        assertEquals(1, carousel.next())
        assertEquals(2, carousel.next())
        assertEquals(0, carousel.next())
    }

    @Test
    fun `prev - 双卡循环 0 到 1（负数取模安全）`() {
        val carousel = LizhanCarouselState(2)
        assertEquals(1, carousel.prev())
        assertEquals(0, carousel.prev())
    }

    @Test
    fun `prev - 三卡循环 0 到 2 到 1 到 0`() {
        val carousel = LizhanCarouselState(3)
        assertEquals(2, carousel.prev())
        assertEquals(1, carousel.prev())
        assertEquals(0, carousel.prev())
    }

    // ── 单卡禁用边界 ─────────────────────────────────────────────────

    @Test
    fun `单卡 - canFlip 为 false 且 next prev 均不改变索引`() {
        val carousel = LizhanCarouselState(1)
        assertFalse(carousel.canFlip)
        assertEquals(0, carousel.next())
        assertEquals(0, carousel.prev())
        assertEquals(0, carousel.currentIndex)
    }

    // ── 槽位环绕计算 ─────────────────────────────────────────────────

    @Test
    fun `slotIndex - 双卡时左右副卡为同一张卡`() {
        val carousel = LizhanCarouselState(2)
        // n=2 时 -1 与 +1 环绕到同一张卡（另一张卡）
        assertEquals(1, carousel.slotIndex(-1))
        assertEquals(0, carousel.slotIndex(0))
        assertEquals(1, carousel.slotIndex(1))
        assertEquals(0, carousel.slotIndex(-2))
    }

    @Test
    fun `slotIndex - 三卡环绕计算含正负越界`() {
        val carousel = LizhanCarouselState(3)
        assertEquals(2, carousel.slotIndex(-1))
        assertEquals(0, carousel.slotIndex(0))
        assertEquals(1, carousel.slotIndex(1))
        assertEquals(2, carousel.slotIndex(2))
        assertEquals(1, carousel.slotIndex(-2))
    }

    @Test
    fun `slotIndex - 翻页后相对槽位跟随主卡片移动`() {
        val carousel = LizhanCarouselState(3)
        carousel.next() // currentIndex: 0 -> 1
        assertEquals(1, carousel.slotIndex(0))
        // 旧主卡（0）落到左槽，右槽是下一张（2）
        assertEquals(0, carousel.slotIndex(-1))
        assertEquals(2, carousel.slotIndex(1))
    }

    // ── 列表收缩收敛 ─────────────────────────────────────────────────

    @Test
    fun `updateItemCount - 索引越界时环绕钳制`() {
        val carousel = LizhanCarouselState(7)
        carousel.next()
        carousel.next()
        carousel.next()
        carousel.next()
        carousel.next()
        assertEquals(5, carousel.currentIndex)
        carousel.updateItemCount(3)
        assertEquals(2, carousel.currentIndex)
        assertEquals(0, carousel.next())
    }

    @Test
    fun `updateItemCount - 空列表归零且不越界`() {
        val carousel = LizhanCarouselState(3)
        carousel.next()
        carousel.updateItemCount(0)
        assertEquals(0, carousel.currentIndex)
        assertFalse(carousel.canFlip)
        assertEquals(0, carousel.next())
    }

    @Test
    fun `slotIndex - 空列表不抛除零异常`() {
        val carousel = LizhanCarouselState(3)
        carousel.updateItemCount(0)
        assertEquals(0, carousel.slotIndex(0))
        assertEquals(0, carousel.slotIndex(-1))
        assertEquals(0, carousel.slotIndex(5))
    }

    @Test
    fun `updateItemCount - 索引未越界时保持不变`() {
        val carousel = LizhanCarouselState(5)
        carousel.next()
        carousel.updateItemCount(4)
        assertEquals(1, carousel.currentIndex)
    }

    @Test
    fun `updateItemCount - 收缩后 prev 不越界`() {
        val carousel = LizhanCarouselState(5)
        carousel.next()
        carousel.next()
        assertEquals(2, carousel.currentIndex)
        carousel.updateItemCount(2)
        assertEquals(0, carousel.currentIndex)
        assertEquals(1, carousel.prev())
        assertEquals(0, carousel.prev())
    }

    // ── 初始状态 ─────────────────────────────────────────────────────

    @Test
    fun `初始 - 索引为 0 且多卡时 canFlip 为 true`() {
        val carousel = LizhanCarouselState(2)
        assertEquals(0, carousel.currentIndex)
        assertTrue(carousel.canFlip)
    }
}

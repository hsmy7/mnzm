package com.xianxia.sect.core.profession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 槽位上方晋升进度条展示状态 [promotionProgressStatus] 纯逻辑测试。
 *
 * 覆盖：满级返回 null、各等级门槛数值、数量/境界/属性三重门槛判定、
 * 数量超额仍达标、level 0 首次晋升、负数防御。
 */
class PromotionProgressStatusTest {

    @Test
    fun `promotionProgressStatus - max level returns null`() {
        assertNull(promotionProgressStatus(level = 5, promotionCount = 0, realm = 3, skill = 110))
    }

    @Test
    fun `promotionProgressStatus - level above max returns null`() {
        assertNull(promotionProgressStatus(level = 99, promotionCount = 999, realm = 0, skill = 999))
    }

    @Test
    fun `promotionProgressStatus - level1 thresholds are correct`() {
        val status = promotionProgressStatus(level = 1, promotionCount = 0, realm = 7, skill = 55)

        requireNotNull(status)
        assertEquals(1, status.level)
        assertEquals(200, status.requiredCount)
        assertEquals(7, status.requiredRealm)
        assertEquals(55, status.requiredSkill)
    }

    @Test
    fun `promotionProgressStatus - count below requirement not met`() {
        val status = promotionProgressStatus(level = 1, promotionCount = 199, realm = 7, skill = 55)

        requireNotNull(status)
        assertEquals(199, status.currentCount)
        assertFalse(status.meetsCount)
        assertTrue(status.meetsRealm)
        assertTrue(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - count reached but realm not met`() {
        // level 1 需金丹（realm <= 7），炼气 realm 9 不足
        val status = promotionProgressStatus(level = 1, promotionCount = 200, realm = 9, skill = 55)

        requireNotNull(status)
        assertTrue(status.meetsCount)
        assertFalse(status.meetsRealm)
        assertTrue(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - count reached but skill not met`() {
        // level 1 需炼丹属性 55，实际 40 不足
        val status = promotionProgressStatus(level = 1, promotionCount = 200, realm = 7, skill = 40)

        requireNotNull(status)
        assertTrue(status.meetsCount)
        assertTrue(status.meetsRealm)
        assertFalse(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - count reached and both realm and skill not met`() {
        val status = promotionProgressStatus(level = 1, promotionCount = 200, realm = 9, skill = 40)

        requireNotNull(status)
        assertTrue(status.meetsCount)
        assertFalse(status.meetsRealm)
        assertFalse(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - count exceeded requirement still met`() {
        // 晋升受阻时计数持续累计（250 > 200），数量门槛保持达标
        val status = promotionProgressStatus(level = 1, promotionCount = 250, realm = 9, skill = 40)

        requireNotNull(status)
        assertEquals(250, status.currentCount)
        assertTrue(status.meetsCount)
        assertFalse(status.meetsRealm)
        assertFalse(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - level0 requires one craft`() {
        val status = promotionProgressStatus(level = 0, promotionCount = 0, realm = 9, skill = 50)

        requireNotNull(status)
        assertEquals(1, status.requiredCount)
        assertFalse(status.meetsCount)
        assertTrue(status.meetsRealm)
        assertTrue(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - level0 count reached blocks only on skill`() {
        // level 0：境界要求炼气（realm <= 9）恒满足，仅属性 40 门槛可能不足
        val status = promotionProgressStatus(level = 0, promotionCount = 1, realm = 9, skill = 39)

        requireNotNull(status)
        assertTrue(status.meetsCount)
        assertTrue(status.meetsRealm)
        assertFalse(status.meetsSkill)
    }

    @Test
    fun `promotionProgressStatus - negative level clamps to zero`() {
        val status = promotionProgressStatus(level = -5, promotionCount = 0, realm = 9, skill = 50)

        requireNotNull(status)
        assertEquals(0, status.level)
        assertEquals(1, status.requiredCount)
    }

    @Test
    fun `promotionProgressStatus - negative count clamps to zero`() {
        val status = promotionProgressStatus(level = 1, promotionCount = -3, realm = 7, skill = 55)

        requireNotNull(status)
        assertEquals(0, status.currentCount)
        assertFalse(status.meetsCount)
    }

    @Test
    fun `promotionProgressStatus - forge path uses same thresholds`() {
        // 锻造与炼丹共用同一门槛表（函数只接收数值，不区分职业）
        val status = promotionProgressStatus(level = 2, promotionCount = 499, realm = 6, skill = 69)

        requireNotNull(status)
        assertEquals(500, status.requiredCount)
        assertEquals(6, status.requiredRealm)
        assertEquals(70, status.requiredSkill)
        assertFalse(status.meetsCount)
        assertTrue(status.meetsRealm)
        assertFalse(status.meetsSkill)
    }
}

package com.xianxia.sect.taptap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排行榜上报节流纯策略测试。
 */
class LeaderboardUploadPolicyTest {

    private val today = "2026-08-05"

    @Test
    fun `shouldUpload - 从未上报时上报`() {
        assertTrue(LeaderboardUploadPolicy.shouldUpload(100, null, null, today))
    }

    @Test
    fun `shouldUpload - 同日同战力跳过`() {
        assertFalse(LeaderboardUploadPolicy.shouldUpload(100, 100, today, today))
    }

    @Test
    fun `shouldUpload - 同日战力变化上报`() {
        assertTrue(LeaderboardUploadPolicy.shouldUpload(200, 100, today, today))
    }

    @Test
    fun `shouldUpload - 跨天上报（每日首次进游戏语义）`() {
        assertTrue(LeaderboardUploadPolicy.shouldUpload(100, 100, "2026-08-04", today))
    }

    @Test
    fun `shouldUpload - 战力为零不上报`() {
        assertFalse(LeaderboardUploadPolicy.shouldUpload(0, null, null, today))
        assertFalse(LeaderboardUploadPolicy.shouldUpload(-1, null, null, today))
    }

    @Test
    fun `shouldUpload - 从未上报但战力为零不上报`() {
        assertFalse(LeaderboardUploadPolicy.shouldUpload(0, null, null, today))
    }

    @Test
    fun `formatDate - 输出 yyyy-MM-dd 格式`() {
        // 2026-08-05 00:00:00 UTC+8（本地时区由 JVM 决定，用固定毫秒值验证格式）
        val formatted = LeaderboardUploadPolicy.formatDate(1_782_633_600_000L)
        assertEquals(10, formatted.length)
        assertTrue(formatted.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}

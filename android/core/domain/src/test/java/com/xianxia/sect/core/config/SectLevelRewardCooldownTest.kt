package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.SectLevelClaimRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SR-5 C3：周奖励冷却判据的唯一真源。
 *
 * 收敛前三处各写一份（引擎领取闸门、`canClaimSectLevelReward`、`GameViewModel` 徽章），
 * 本测试锁定收敛后的边界语义与收敛前**逐位一致**——特别是 `>= WEEK_MS` 的边界与
 * 墙钟回拨（elapsed 为负）时判"不可领"的既有保守方向。
 */
class SectLevelRewardCooldownTest {

    @Test
    fun `从未领取即可领`() {
        assertTrue(SectLevelRewardCooldown.isClaimable(null, nowMs = 1_700_000_000_000L))
    }

    @Test
    fun `恰好满七天可领_差一毫秒不可领`() {
        val claimed = 1_700_000_000_000L
        assertTrue(
            SectLevelRewardCooldown.isClaimable(claimed, claimed + SectLevelRewardCooldown.WEEK_MS)
        )
        assertFalse(
            SectLevelRewardCooldown.isClaimable(
                claimed,
                claimed + SectLevelRewardCooldown.WEEK_MS - 1
            )
        )
    }

    @Test
    fun `墙钟回拨时保守判不可领`() {
        val claimed = 1_700_000_000_000L
        assertFalse(SectLevelRewardCooldown.isClaimable(claimed, claimed - 1L))
    }

    @Test
    fun `下次可领时刻`() {
        val claimed = 1_700_000_000_000L
        assertEquals(
            claimed + SectLevelRewardCooldown.WEEK_MS,
            SectLevelRewardCooldown.nextClaimableAt(claimed)
        )
        assertNull(SectLevelRewardCooldown.nextClaimableAt(null))
    }

    @Test
    fun `按等级取上次领取时刻`() {
        val records = listOf(
            SectLevelClaimRecord(level = 1, claimedAtEpochMs = 100L),
            SectLevelClaimRecord(level = 3, claimedAtEpochMs = 300L)
        )
        assertEquals(100L, SectLevelRewardCooldown.lastClaimedAt(records, 1))
        assertEquals(300L, SectLevelRewardCooldown.lastClaimedAt(records, 3))
        assertNull("该等级无记录必须返回 null（可领），而非 0L", SectLevelRewardCooldown.lastClaimedAt(records, 2))
    }
}

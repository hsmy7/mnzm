package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 远古秘境关闭倒计时（remainingMonthsUntilClose / formatRemainingMonths）单元测试：
 * 剩余月数计算与月结关闭判定（year >= spawnYear + OPEN_YEARS）同口径、格式化边界。
 */
class SecretRealmCountdownTest {

    @Test
    fun `remainingMonthsUntilClose - 跨年剩余 3 年 10 月`() {
        // 现世第 5 年 → 第 10 年 1 月关闭；当前 6 年 3 月 → 46 个月
        assertEquals(46, GameConfig.SecretRealm.remainingMonthsUntilClose(6, 3, spawnYear = 5))
    }

    @Test
    fun `remainingMonthsUntilClose - 年底余 1 月`() {
        assertEquals(1, GameConfig.SecretRealm.remainingMonthsUntilClose(9, 12, spawnYear = 5))
    }

    @Test
    fun `remainingMonthsUntilClose - 现世当年即关闭年`() {
        // 关闭年 1 月 → 0（当月结算即关闭）
        assertEquals(0, GameConfig.SecretRealm.remainingMonthsUntilClose(10, 1, spawnYear = 5))
        // 关闭年后 → 负（已关闭）
        assertEquals(-5, GameConfig.SecretRealm.remainingMonthsUntilClose(10, 6, spawnYear = 5))
    }

    @Test
    fun `remainingMonthsUntilClose - 与月结关闭判定同口径`() {
        // 剩余 ≤0 ⟺ year >= spawnYear + OPEN_YEARS（processMonthlyExpiryCheck 同判据）
        for (year in 5..11) {
            for (month in 1..12) {
                val remaining = GameConfig.SecretRealm.remainingMonthsUntilClose(year, month, spawnYear = 5)
                val shouldClose = year >= 5 + GameConfig.SecretRealm.OPEN_YEARS
                assertEquals(
                    "year=$year month=$month 关闭判定不一致",
                    shouldClose, remaining <= 0
                )
            }
        }
    }

    @Test
    fun `formatRemainingMonths - 满年显示 X 年 Y 月`() {
        assertEquals("剩余 3 年 10 月", GameConfig.SecretRealm.formatRemainingMonths(46))
        assertEquals("剩余 3 年 3 月", GameConfig.SecretRealm.formatRemainingMonths(39))
    }

    @Test
    fun `formatRemainingMonths - 整年省略月份`() {
        assertEquals("剩余 1 年", GameConfig.SecretRealm.formatRemainingMonths(12))
        assertEquals("剩余 5 年", GameConfig.SecretRealm.formatRemainingMonths(60))
    }

    @Test
    fun `formatRemainingMonths - 不足一年仅显示月`() {
        assertEquals("剩余 7 月", GameConfig.SecretRealm.formatRemainingMonths(7))
        assertEquals("剩余 1 月", GameConfig.SecretRealm.formatRemainingMonths(1))
    }

    @Test
    fun `formatRemainingMonths - 已到期返回 null 供 UI 隐藏`() {
        assertNull(GameConfig.SecretRealm.formatRemainingMonths(0))
        assertNull(GameConfig.SecretRealm.formatRemainingMonths(-3))
    }
}

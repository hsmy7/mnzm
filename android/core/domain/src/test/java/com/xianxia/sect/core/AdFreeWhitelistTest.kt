package com.xianxia.sect.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFreeWhitelistTest {

    @After
    fun tearDown() {
        // 清除测试状态，防止污染后续测试
        AdFreeWhitelist.initialize(null)
    }

    @Test
    fun `isCurrentUserPrivileged - no unionId returns false`() {
        AdFreeWhitelist.initialize(null)
        assertFalse(AdFreeWhitelist.isCurrentUserPrivileged())
    }

    @Test
    fun `isCurrentUserPrivileged - empty unionId returns false`() {
        AdFreeWhitelist.initialize("")
        assertFalse(AdFreeWhitelist.isCurrentUserPrivileged())
    }

    @Test
    fun `isCurrentUserPrivileged - non whitelist unionId returns false`() {
        AdFreeWhitelist.initialize("non_existent_union_id")
        assertFalse(AdFreeWhitelist.isCurrentUserPrivileged())
    }

    @Test
    fun `isCurrentUserPrivileged - reinitialize after null resets state`() {
        AdFreeWhitelist.initialize("some_id")
        AdFreeWhitelist.initialize(null)
        assertFalse(AdFreeWhitelist.isCurrentUserPrivileged())
    }

    @Test
    fun `isCurrentUserPrivileged - new whitelist unionId returns true`() {
        // 数据回归：GameConfig.Whitelist.AD_FREE_UNION_IDS 新增的用户
        AdFreeWhitelist.initialize("4FTGX7tp7MO1nr+j/Vwm5A==")
        assertTrue(AdFreeWhitelist.isCurrentUserPrivileged())
    }
}

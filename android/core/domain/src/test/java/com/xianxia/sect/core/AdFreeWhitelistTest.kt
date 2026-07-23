package com.xianxia.sect.core

import org.junit.After
import org.junit.Assert.assertFalse
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
}

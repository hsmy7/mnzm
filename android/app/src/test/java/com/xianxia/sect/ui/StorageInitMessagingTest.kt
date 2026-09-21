package com.xianxia.sect.ui

import com.xianxia.sect.data.cloud.SaveBackendMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StorageInitMessagingTest — 初始化失败文案守卫（SR-3，审计 §12-J 修复）。
 *
 * 守护目标：全败阻断文案必须模式感知——LEGACY 点明本地档是唯一进度来源；
 * 非 LEGACY 点明缓存不可写的具体影响；错误详情与重试指引必须如实携带。
 */
class StorageInitMessagingTest {

    @Test
    fun `legacy message names local save as sole source of truth`() {
        val msg = storageInitFailureMessage(SaveBackendMode.LEGACY, "db corrupted")
        assertTrue(msg.contains("db corrupted"))
        assertTrue(msg.contains("本地存档是唯一的进度来源"))
        assertTrue(msg.contains("重试"))
    }

    @Test
    fun `cloud transition message names cache write impact`() {
        val msg = storageInitFailureMessage(SaveBackendMode.CLOUD_TRANSITION, null)
        assertTrue(msg.contains("未知错误")) // 空错误详情降级为"未知错误"，不显示 null
        assertTrue(msg.contains("缓存不可写"))
        assertFalse(msg.contains("null"))
    }

    @Test
    fun `cloud only message names cloud unavailability`() {
        val msg = storageInitFailureMessage(SaveBackendMode.CLOUD_ONLY, "timeout")
        assertTrue(msg.contains("云端存档服务不可达"))
        assertTrue(msg.contains("timeout"))
    }

    @Test
    fun `retry count is honest`() {
        val msg = storageInitFailureMessage(SaveBackendMode.LEGACY, "x")
        assertTrue(msg.contains("已重试 3 次"))
    }
}

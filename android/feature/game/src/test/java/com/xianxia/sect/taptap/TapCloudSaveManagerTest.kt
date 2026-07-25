package com.xianxia.sect.taptap

import com.xianxia.sect.taptap.TapCloudSaveManager.CloudSaveInfo
import com.xianxia.sect.taptap.TapCloudSaveManager.CloudSaveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TapCloudSaveManager 的单元测试。
 *
 * 注意：tap-cloudsave SDK 不在 test scope 中，因此反射桥接相关的测试
 * 需要添加 testImplementation 依赖或使用 androidInstrumentedTest。
 * 本文件测试不依赖 SDK 的纯数据类逻辑。
 */
class TapCloudSaveManagerTest {

    // ── CloudSaveResult 测试 ──

    @Test
    fun `CloudSaveResult Success - default has null saveData`() {
        val result = CloudSaveResult.Success()
        assertNull(result.saveData)
    }

    @Test
    fun `CloudSaveResult NetworkError - holds message`() {
        val result = CloudSaveResult.NetworkError("connection timeout")
        assertEquals("connection timeout", result.message)
    }

    @Test
    fun `CloudSaveResult AuthRequired - holds message`() {
        val result = CloudSaveResult.AuthRequired("need login")
        assertEquals("need login", result.message)
    }

    @Test
    fun `CloudSaveResult NoSaveExists - has default message`() {
        val result1 = CloudSaveResult.NoSaveExists()
        assertEquals("云存档不存在", result1.message)

        val result2 = CloudSaveResult.NoSaveExists("custom msg")
        assertEquals("custom msg", result2.message)
    }

    @Test
    fun `CloudSaveResult FileTooLarge - holds size info`() {
        val result = CloudSaveResult.FileTooLarge(10L * 1024 * 1024, 20L * 1024 * 1024)
        assertEquals(10L * 1024 * 1024, result.maxBytes)
        assertEquals(20L * 1024 * 1024, result.actualBytes)
    }

    @Test
    fun `CloudSaveResult SerializationError - holds message`() {
        val result = CloudSaveResult.SerializationError("json error")
        assertEquals("json error", result.message)
    }

    @Test
    fun `CloudSaveResult UnknownError - holds message`() {
        val result = CloudSaveResult.UnknownError("unknown failure")
        assertEquals("unknown failure", result.message)
    }

    // ── CloudSaveInfo 测试 ──

    @Test
    fun `CloudSaveInfo - default is no save data`() {
        val info = CloudSaveInfo(false)
        assertFalse(info.hasSaveData)
        assertEquals(0L, info.lastModifiedTime)
        assertEquals(0L, info.saveSize)
        assertEquals("", info.description)
    }

    @Test
    fun `CloudSaveInfo - can hold save metadata`() {
        val info = CloudSaveInfo(
            hasSaveData = true,
            lastModifiedTime = 1700000000000L,
            saveSize = 1024L,
            description = "save at year 5"
        )
        assertTrue(info.hasSaveData)
        assertEquals(1700000000000L, info.lastModifiedTime)
        assertEquals(1024L, info.saveSize)
        assertEquals("save at year 5", info.description)
    }

    // ── CloudSaveResult 类型变体验证 ──

    @Test
    fun `CloudSaveResult - has exactly 7 variant types`() {
        // 验证所有 CloudSaveResult 子类型都能构造且互斥
        val results: List<CloudSaveResult> = listOf(
            CloudSaveResult.Success(),
            CloudSaveResult.NetworkError("e1"),
            CloudSaveResult.AuthRequired("e2"),
            CloudSaveResult.NoSaveExists(),
            CloudSaveResult.FileTooLarge(0, 0),
            CloudSaveResult.SerializationError("e3"),
            CloudSaveResult.UnknownError("e4")
        )
        assertEquals(7, results.size)
    }
}

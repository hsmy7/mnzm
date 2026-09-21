package com.xianxia.sect.data.cloud

import com.xianxia.sect.data.prefs.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SaveBackendMode 三态开关守卫测试（SR-2 硬红线：**默认 LEGACY = 全链零行为变化**）。
 */
class SaveBackendModeTest {

    private lateinit var store: InMemoryKeyValueStore
    private lateinit var provider: SaveBackendModeProvider

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        provider = SaveBackendModeProvider(store)
    }

    @Test
    fun `默认模式 = LEGACY（硬红线：未写入存储时新机制必须全链惰性）`() {
        assertEquals(SaveBackendMode.LEGACY, provider.current())
        assertFalse(shouldEnqueueCloudUpload(provider.current()))
    }

    @Test
    fun `非法存储值回落 LEGACY（失败封闭：未知值不得激活新链路）`() {
        assertEquals(SaveBackendMode.LEGACY, SaveBackendModeProvider.fromStored(null))
        assertEquals(SaveBackendMode.LEGACY, SaveBackendModeProvider.fromStored(""))
        assertEquals(SaveBackendMode.LEGACY, SaveBackendModeProvider.fromStored("cloud_only"))
        assertEquals(SaveBackendMode.LEGACY, SaveBackendModeProvider.fromStored("CLOUD_TRANSITION "))
        assertEquals(SaveBackendMode.LEGACY, SaveBackendModeProvider.fromStored("YUN_ONLY"))
    }

    @Test
    fun `三态 roundtrip 与门控纯函数`() {
        for (mode in SaveBackendMode.values()) {
            provider.set(mode)
            assertEquals(mode, provider.current())
        }
        // 门控：LEGACY 短路；CLOUD_TRANSITION/CLOUD_ONLY 投递
        assertTrue(shouldEnqueueCloudUpload(SaveBackendMode.CLOUD_TRANSITION))
        assertTrue(shouldEnqueueCloudUpload(SaveBackendMode.CLOUD_ONLY))
        assertFalse(shouldEnqueueCloudUpload(SaveBackendMode.LEGACY))
    }

    @Test
    fun `IN3 - core data 测试 classpath 无 TapTap SDK 类型（存储层结构性隔离守卫）`() {
        // core:data 的 build 依赖不含 tap-cloudsave/xdsdk——若未来有人把 SDK 依赖
        // 引入存储层，此处 Class.forName 将成功并立即红灯（IN3 静态守卫第二层，
        // 第一层 = feature/game 的 konsist 源扫描）
        val sdkTypes = listOf(
            "com.taptap.sdk.cloudsave.TapTapCloudSave",
            "com.taptap.sdk.cloudsave.ArchiveMetadata",
            "com.xd.sdk.taptap.XDTapCloudSave"
        )
        for (type in sdkTypes) {
            val found = try {
                Class.forName(type)
                true
            } catch (_: ClassNotFoundException) {
                false
            } catch (_: NoClassDefFoundError) {
                false
            }
            assertFalse("存储层 classpath 不应可见 TapTap SDK 类型: $type", found)
        }
    }
}

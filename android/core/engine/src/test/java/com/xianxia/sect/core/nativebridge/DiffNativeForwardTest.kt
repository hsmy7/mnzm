package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DiffNativeForwardTest — 批次 9 剩余转发层对拍守护。
 *
 * 守护目标：
 *   1. feature flag 关闭时 tryExecuteNative 静默降级 null（Kotlin 引擎照常）
 *   2. feature flag 开启但 native 未加载时同样降级 null（不崩溃）
 *   3. StateSyncService 字段级宽松合并不丢未迁移字段（镜像安全）
 *   4. withMode 临时开关自动恢复旧值
 *
 * 注：GameEngineNativeOps 走生产桥 GameCoreBridge；单测桌面 JNI 加载的是
 * 对拍桥 DiffRngBridge（符号不同），故生产桥 isLoaded=false → 降级路径。
 * 真实 C++ 执行语义由 DiffExecuteTest 经 DiffRngBridge 守护。
 */
class DiffNativeForwardTest {

    @Before
    fun setUp() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.OFF
    }

    @Test
    fun `flag off tryExecuteNative returns null`() {
        // feature flag 关闭 → 转发静默降级（Kotlin 引擎照常）
        val service = StateSyncService(FakeAtomicStateStore())
        val result = GameEngineNativeOps.tryExecuteNative(
            service, ActionIds.WALLET_ADD,
            GameEngineNativeOps.params { put("amount", 100); put("grade", "LOW") }
        )
        assertNull("flag 关闭时应降级 null", result)
    }

    @Test
    fun `flag on but native unavailable returns null`() {
        // 生产桥未加载（单测环境）→ 降级 null 不崩溃
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val service = StateSyncService(FakeAtomicStateStore())
            val result = GameEngineNativeOps.tryExecuteNative(
                service, ActionIds.WALLET_ADD,
                GameEngineNativeOps.params { put("amount", 100); put("grade", "LOW") }
            )
            assertNull("native 未加载时应降级 null", result)
        }
    }

    @Test
    fun `flag lifecycle restores previous value`() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(!NativeEngineFlag.enabled)
        }
        assertTrue("withMode 应恢复旧值", NativeEngineFlag.enabled)
    }

    @Test
    fun `merge keeps unmigrated fields via engine ops`() {
        // StateSyncService 宽松合并：白名单外字段保留（镜像安全）
        val store = FakeAtomicStateStore()
        val service = StateSyncService(store)
        val current = GameData().apply { spiritStones = 100; sectName = "青云宗"; jadeSymbols = 7 }
        val snapshot = GameData().apply { spiritStones = 300 }
        val merged = service.mergeGameData(current, snapshot, exportedKeys = setOf("spiritStones"))
        assertEquals(300L, merged.spiritStones)
        assertEquals("青云宗", merged.sectName)
        assertEquals(7, merged.jadeSymbols)
    }
}

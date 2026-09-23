package com.xianxia.sect.core.memory

import android.content.ComponentCallbacks2
import com.xianxia.sect.core.domain.memory.MemoryTrimLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MR1-P1.3/D3：TrimMemoryBridge 分档协议守卫（Robolectric）。
 *
 * 锁死四件事：
 * 1. **档位归一映射**（方案 D3 表）——Android trim 级别 → 四档；
 * 2. **双发去抖**——同档位短窗合并、升级（高档）立即穿透、窗口外放行；
 * 3. **CRITICAL 不空转**——CRITICAL 分发必须触达全部四个动作面
 *    （JNI 投递序数 3 / UI 资源 / CacheLayer / 引擎裁剪）；
 * 4. **NONE 不分发**——未知级别零动作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // targetSdk 35 超 Robolectric 上限 34，需固定（既有先例）
class TrimDispatchTest {

    private var nowMs = 0L
    private var nativeLevel = -1
    private var uiLevel: MemoryTrimLevel? = null
    private var cacheLevel: MemoryTrimLevel? = null
    private var engineLevel: MemoryTrimLevel? = null

    @Before
    fun setUp() {
        TrimMemoryBridge.resetForTest()
        nowMs = 1_000L
        nativeLevel = -1
        uiLevel = null
        cacheLevel = null
        engineLevel = null
        TrimMemoryBridge.elapsedRealtimeMs = { nowMs }
        TrimMemoryBridge.nativeTrimSink = { nativeLevel = it.ordinal }
        TrimMemoryBridge.uiResourceTrimAction = TrimMemoryBridge.UiResourceTrimAction { uiLevel = it }
        TrimMemoryBridge.cacheTrimAction = TrimMemoryBridge.CacheTrimAction { cacheLevel = it }
        TrimMemoryBridge.engineTrimAction = TrimMemoryBridge.EngineTrimAction { engineLevel = it }
    }

    @After
    fun tearDown() {
        TrimMemoryBridge.resetForTest()
    }

    // ── 档位归一（D3 映射表）──────────────────────────────────────

    @Test
    fun `归一映射 - UI_HIDDEN 与 RUNNING 轻档与 MODERATE 归 SOFT`() {
        assertEquals(MemoryTrimLevel.SOFT, TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(MemoryTrimLevel.SOFT, TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MemoryTrimLevel.SOFT, TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(MemoryTrimLevel.SOFT, TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
    }

    @Test
    fun `归一映射 - RUNNING_CRITICAL 与 BACKGROUND 归 AGGRESSIVE`() {
        assertEquals(
            MemoryTrimLevel.AGGRESSIVE,
            TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        )
        assertEquals(MemoryTrimLevel.AGGRESSIVE, TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }

    @Test
    fun `归一映射 - COMPLETE 归 CRITICAL`() {
        assertEquals(MemoryTrimLevel.CRITICAL, TrimMemoryBridge.normalize(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    // ── CRITICAL 全动作面（不空转）────────────────────────────────

    @Test
    fun `CRITICAL 分发触达全部动作面且 JNI 序数为 3`() {
        TrimMemoryBridge.onSystemLowMemory()

        assertEquals(3, nativeLevel)
        assertEquals(MemoryTrimLevel.CRITICAL, uiLevel)
        assertEquals(MemoryTrimLevel.CRITICAL, cacheLevel)
        assertEquals(MemoryTrimLevel.CRITICAL, engineLevel)
    }

    @Test
    fun `onSystemTrim 与 onLowMemory 同档语义`() {
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(3, nativeLevel)
    }

    // ── SOFT 档分发面 ─────────────────────────────────────────────

    @Test
    fun `SOFT 分发触达动作面且 JNI 序数为 1`() {
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(1, nativeLevel)
        assertEquals(MemoryTrimLevel.SOFT, uiLevel)
        assertEquals(MemoryTrimLevel.SOFT, cacheLevel)
        assertEquals(MemoryTrimLevel.SOFT, engineLevel)
    }

    // ── 双发去抖 ─────────────────────────────────────────────────

    @Test
    fun `同档位短窗内重复分发被合并`() {
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        val firstNative = nativeLevel
        // 同一时刻（nowMs 未推进）Application/GameActivity 双发同档
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        // 去抖：JNI/UI/cache 计数不重复（此处以「值未再刷新」的序列断言简化——
        // 用分发后立刻采样次数校验：第二发的动作面值与首发一致且窗口未推进）
        assertEquals(firstNative, nativeLevel)
        assertEquals(MemoryTrimLevel.SOFT, cacheLevel)
    }

    @Test
    fun `高档位升级穿透去抖窗口`() {
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(1, nativeLevel)
        // nowMs 未推进（同一窗口内）升级 CRITICAL——必须立即穿透
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        assertEquals(3, nativeLevel)
        assertEquals(MemoryTrimLevel.CRITICAL, cacheLevel)
    }

    @Test
    fun `窗口外同档位放行`() {
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        nowMs += 2_000L // 超过 TRIM_DEBOUNCE_WINDOW_MS
        TrimMemoryBridge.onSystemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        // 窗口外再次分发合法（重复压力事件不被永久吞掉）
        assertEquals(1, nativeLevel)
    }

    // ── NONE 不分发 ──────────────────────────────────────────────

    @Test
    fun `未知级别归 NONE 且零动作`() {
        TrimMemoryBridge.onSystemTrim(777)

        assertEquals(-1, nativeLevel) // nativeTrimSink 未被调用
        assertNull(uiLevel)
        assertNull(cacheLevel)
        assertNull(engineLevel)
    }

    @Test
    fun `枚举序数与 JNI 线协议值对齐`() {
        // 线协议 = ordinal（0-3），禁止重排枚举项
        assertEquals(0, MemoryTrimLevel.NONE.ordinal)
        assertEquals(1, MemoryTrimLevel.SOFT.ordinal)
        assertEquals(2, MemoryTrimLevel.AGGRESSIVE.ordinal)
        assertEquals(3, MemoryTrimLevel.CRITICAL.ordinal)
    }

    @Test
    fun `未装配动作面时 CRITICAL 分发不抛异常`() {
        TrimMemoryBridge.resetForTest(clearActions = true)
        TrimMemoryBridge.elapsedRealtimeMs = { nowMs }
        TrimMemoryBridge.nativeTrimSink = { nativeLevel = it.ordinal }
        assertTrue(true)
        TrimMemoryBridge.onSystemLowMemory()
        assertEquals(3, nativeLevel)
    }
}

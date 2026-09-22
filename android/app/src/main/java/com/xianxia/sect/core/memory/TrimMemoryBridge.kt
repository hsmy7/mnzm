package com.xianxia.sect.core.memory

import android.os.SystemClock
import android.util.Log
import com.xianxia.sect.core.domain.memory.MemoryTrimLevel
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeBridge

/**
 * TrimMemoryBridge — 内存压力统一收敛入口（MR1-P1.3/D3，**收敛不是新增旁路**）。
 *
 * ## 收敛了谁（迁移后生产 `onTrimMemory`/`onLowMemory` 的游戏内存消费者 = 1）
 * 本对象吸收并替换既有四路并行 trim 面（守卫 `TrimConsumerCountGuardTest` 锁死）：
 * 1. `XianxiaApplication.notifyMemoryPressure` 游戏侧广播（原 `MemoryPressureListener`
 *    注册机制随收敛删除——唯一注册者 GameMonitorManager 为空壳实现）；
 * 2. `CacheLayer`/`GameDataCacheMemoryPressure` 自注册 `ComponentCallbacks2` 的
 *    档位动作（自注册已删，动作面收敛为 [CacheTrimAction]）；
 * 3. `GameActivity.onTrimMemory` 的 CRITICAL 分支（地图预载引用/图集预取清空）；
 * 4. `GameLoopDelegate.onMemoryPressure` → `GameEngine.releaseMemory`（原入口
 *    无注册者即死代码，动作收敛为 [EngineTrimAction]）。
 *
 * ## 线程面（线程契约表四 nativeMemoryTrim 通道）
 * 系统回调在 UI 主线程进入 [onSystemTrim]：归一 → 去抖 → 同步分发各动作面 +
 * JNI 投递（命令投递式——trim 回调线程禁止 GPU 操作/纹理重上传）。C++ 侧渲染
 * 线程帧边界消费渲染水位；引擎线程结算边界消费 gamecore 水位（账本 cap 归一 +
 * CRITICAL shrink）。
 *
 * ## 复用不新建
 * 档位语义与 `GCOptimizer`（SOFT/HARD/CRITICAL 阈值轴）/`DynamicMemoryManager`
 * （设备分档）对齐；预算真相源 = `MemoryBudgetView`（P4.4 接线）——本桥只做
 * 档位归一与分发，不写第二套分级、不持有任何预算状态。
 *
 * 去抖：同档位短窗合并（系统对同源重复级别连发）；**高档位立即穿透**（升级
 * 不被去抖延迟）；窗口外降级放行。
 */
object TrimMemoryBridge {

    private const val TAG = "TrimMemoryBridge"

    /** 同档位去抖窗口（ms；系统连发同级别合并，CRITICAL 升级穿透不受影响） */
    private const val TRIM_DEBOUNCE_WINDOW_MS = 1_000L

    /** 归一后最近一次分发档位（主线程读写——系统 trim 回调恒主线程） */
    private var lastDispatchedLevel: MemoryTrimLevel = MemoryTrimLevel.NONE

    /** 最近一次分发时刻（[SystemClock.elapsedRealtime] 基；0 = 无历史） */
    private var lastDispatchElapsedMs = 0L

    /** JNI 投递面（可注入替身供单测断言序数；生产 = 双库 nativeMemoryTrim） */
    internal var nativeTrimSink: (MemoryTrimLevel) -> Unit = { level ->
        NativeBridge.nativeMemoryTrim(level.ordinal)
        GameCoreBridge.nativeMemoryTrim(level.ordinal)
    }

    /** 去抖时钟（可注入替身；生产 = 系统单调墙钟 elapsedRealtime） */
    internal var elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime

    /** CacheLayer 档位动作面（Application 装配时注册；null = 未装配直接跳过） */
    fun interface CacheTrimAction {
        fun onTrim(level: MemoryTrimLevel)
    }

    /** 引擎重列表裁剪动作面（`GameEngine.releaseMemory` 的收敛入口） */
    fun interface EngineTrimAction {
        fun onTrim(level: MemoryTrimLevel)
    }

    /** UI 资源动作面（GameActivity 域：地图预载引用/图集预取/场景基线/宗门图缓存） */
    fun interface UiResourceTrimAction {
        fun onTrim(level: MemoryTrimLevel)
    }

    @Volatile
    var cacheTrimAction: CacheTrimAction? = null

    @Volatile
    var engineTrimAction: EngineTrimAction? = null

    @Volatile
    var uiResourceTrimAction: UiResourceTrimAction? = null

    /**
     * Android trim 级别归一（方案 D3 映射表——全仓唯一归一点）：
     * - UI_HIDDEN / RUNNING_MODERATE / RUNNING_LOW / MODERATE → [MemoryTrimLevel.SOFT]
     * - RUNNING_CRITICAL / BACKGROUND → [MemoryTrimLevel.AGGRESSIVE]
     * - COMPLETE → [MemoryTrimLevel.CRITICAL]
     * - 未知级别 → [MemoryTrimLevel.NONE]
     */
    fun normalize(androidLevel: Int): MemoryTrimLevel = when (androidLevel) {
        android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> MemoryTrimLevel.SOFT

        android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryTrimLevel.AGGRESSIVE

        android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryTrimLevel.CRITICAL

        else -> MemoryTrimLevel.NONE
    }

    /** `onLowMemory` = [MemoryTrimLevel.CRITICAL]（D3 映射表最后一行） */
    fun onSystemLowMemory() {
        dispatch(MemoryTrimLevel.CRITICAL)
    }

    /**
     * 系统 trim 入口（XianxiaApplication.onTrimMemory 唯一转发）：归一 → 去抖 →
     * 分发。NONE（未知级别）不触发任何动作面。
     */
    fun onSystemTrim(androidLevel: Int) {
        dispatch(normalize(androidLevel))
    }

    /** 测试钩子：重置去抖状态与（可选）动作面注册 */
    internal fun resetForTest(clearActions: Boolean = true) {
        lastDispatchedLevel = MemoryTrimLevel.NONE
        lastDispatchElapsedMs = 0L
        elapsedRealtimeMs = SystemClock::elapsedRealtime
        if (clearActions) {
            cacheTrimAction = null
            engineTrimAction = null
            uiResourceTrimAction = null
            nativeTrimSink = { level ->
                NativeBridge.nativeMemoryTrim(level.ordinal)
                GameCoreBridge.nativeMemoryTrim(level.ordinal)
            }
        }
    }

    private fun dispatch(level: MemoryTrimLevel) {
        if (level == MemoryTrimLevel.NONE) return
        val now = elapsedRealtimeMs()
        if (level == lastDispatchedLevel &&
            now - lastDispatchElapsedMs < TRIM_DEBOUNCE_WINDOW_MS
        ) {
            Log.d(TAG, "trim level=$level debounced (within ${TRIM_DEBOUNCE_WINDOW_MS}ms window)")
            return
        }
        lastDispatchedLevel = level
        lastDispatchElapsedMs = now
        Log.i(TAG, "memory trim dispatch: level=$level")

        // ① JNI 投递（渲染帧边界 + 引擎结算边界双消费；命令投递式，本线程零 GPU 操作）
        nativeTrimSink(level)

        // ② UI 资源面（非当前宗门图/场景基线 SOFT 起；CRITICAL 追加图集预取与地图预载引用）
        uiResourceTrimAction?.onTrim(level)

        // ③ CacheLayer 域（逐出/紧急清空）
        cacheTrimAction?.onTrim(level)

        // ④ 引擎重列表裁剪（GameEngine.releaseMemory 收敛入口）
        engineTrimAction?.onTrim(level)
    }
}

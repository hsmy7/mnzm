package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException

/**
 * GameEngineCoreNativeOps — C++ 引擎 shadow 桥接辅助（Kotlin→C++ 迁移批次 9）。
 *
 * 承载 [GameEngineCore] 的 C++ 集成逻辑（tick 桥 / 读档基线对齐），
 * 保持 GameEngineCore.kt 主体聚焦生命周期与帧循环（文件行数约束）。
 *
 * shadow 对拍模式：Kotlin 引擎仍是运行时真相源；SHADOW 模式下
 * C++ 影子状态随 tick 推进、读档后对齐基线，供对拍/灰度预热。
 * AUTHORITATIVE 模式（T2.4）下 C++ 即真相源——影子推进必须停用，
 * 避免双份推进；其集成逻辑在 GameEngineCore.processAuthoritativeTick。
 */
internal fun GameEngineCore.tickNativeShadow(deltaNs: Long, nowMs: Long) {
    if (NativeEngineFlag.mode != NativeEngineFlag.Mode.SHADOW ||
        !GameCoreBridge.isLoaded
    ) {
        return
    }
    // 双实现并行契约：native 异常降级日志，Kotlin 引擎照常运行
    @Suppress("TooGenericExceptionCaught")
    try {
        GameCoreBridge.nativeAdvance(deltaNs, nowMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w("GameEngineCore", "native tick bridge failed, falling back to Kotlin: ${e.message}")
    }
}

/** 读档后把 Kotlin 状态导入 C++ 影子引擎（同起点对拍基线）。 */
internal suspend fun GameEngineCore.loadNativeBaseline(stateSyncService: StateSyncService) {
    if (!NativeEngineFlag.enabled || !GameCoreBridge.isLoaded) return
    @Suppress("TooGenericExceptionCaught")
    try {
        stateSyncService.importToNative()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w("GameEngineCore", "native import baseline failed, shadow disabled: ${e.message}")
    }
}

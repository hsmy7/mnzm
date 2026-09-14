package com.xianxia.sect.core.engine

import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException

/**
 * GameEngineCoreNativeOps — C++ 引擎桥接辅助。
 *
 * 承载 [GameEngineCore] 的 C++ 集成逻辑（读档基线对齐），保持
 * GameEngineCore.kt 主体聚焦生命周期与帧循环（文件行数约束）。
 *
 * 读档路径：native 可用时把 Kotlin 状态导入 C++（AUTHORITATIVE 真相源
 * 基线对齐 / 反向增量通道起点）。
 */

/** 读档后把 Kotlin 状态导入 C++ 引擎（同起点基线，反向增量通道基线对齐）。 */
internal suspend fun GameEngineCore.loadNativeBaseline(stateSyncService: StateSyncService) {
    if (!NativeEngineFlag.enabled || !GameCoreBridge.isLoaded) return
    @Suppress("TooGenericExceptionCaught")
    try {
        stateSyncService.importToNative()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w("GameEngineCore", "native import baseline failed: ${e.message}")
    }
}

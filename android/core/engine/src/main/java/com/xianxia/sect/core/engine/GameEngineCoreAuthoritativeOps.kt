package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.GameCoreRngChannel
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException

/** native 快照 schema 版本（与 C++ GameCoreConfig 默认值一致） */
private const val NATIVE_SNAPSHOT_SCHEMA_VERSION = "0.1.0"

private const val TAG = "GameEngineCore"

/**
 * GameEngineCoreAuthoritativeOps — T2.4 AUTHORITATIVE 过渡期 tick 集成
 * （计划 v2 阶段 2d；独立文件承载以守住 GameEngineCore.kt 行数约束）。
 *
 * 语义切分（correctness-first，与 Kotlin 全量引擎逐位等价）：
 * - C++ 真相源：每旬时间推进 + 核心结算（步骤 1-5 零 RNG 批量，
 *   即阶段 0 实测的每旬热点路径）
 * - Kotlin 残留：自动装备/丹药/突破（偷盗钩子/亲属赠送/埋点副作用面）
 *   + 完整月变/年变编排——RNG 经 NativeBackedRng 委托 native 单一真相源
 */

/**
 * AUTHORITATIVE 过渡期 tick：每旬互插。
 *
 * ① nativeSettlePhase——C++ 单旬推进（时间 + 核心结算；月/年边界只记标志）
 * ② applyDirtyFromNative——增量镜像（失败先试全量兜底，仍失败走异常回退）
 * ③ Kotlin 残留执行器（单事务：自动装备/丹药/突破 + 边界月/年完整编排）
 * ④ 每旬全量回导 C++（含边界结算效果）——残留副作用（如突破清零）必须
 *    在下一旬核心结算前写回 C++，否则月内多旬窗口 C++ 从旧修为继续累积
 *    导致跨语言漂移（100 旬对拍实测 breakthroughCount 7 vs 8 教训）；
 *    每旬回导的过渡期成本由阶段 3 SoA 反向增量通道取代
 *
 * 时钟语义：墙钟消费/速度/暂停/refundPhases 仍由 Kotlin GameTimeClock 独占
 * （与 t2-4-design.md §2 的差异点：C++ 累积器推迟到阶段 5 引擎循环迁移时
 * 接管，避免过渡期双语言维护两套 speed/pause/refund 状态机）。
 *
 * 失败语义：native 链路任何失败 → refundPhases 后重抛（与
 * GameEngineCore.processTickPhases 相同的看门狗处理路径），下个 tick 由
 * [ensureAuthoritativeNative] 决定是否重建或回退纯 Kotlin 路径。
 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：native 链路失败统一 refund+重抛
internal suspend fun GameEngineCore.processAuthoritativeTick(phasesToAdvance: Int) {
    val capped = phasesToAdvance.coerceAtMost(GameTimeClock.MAX_PHASES_PER_TICK)
    try {
        repeat(capped) {
            // ① C++ 单旬推进（时间 + 步骤 1-5 核心结算）
            val settleFlags = GameCoreBridge.nativeSettlePhase()
            // ② 增量镜像；失败先试全量兜底，仍失败则走异常回退路径
            val applied = stateSyncServiceRef.applyDirtyFromNative()
            if (applied == null && !stateSyncServiceRef.syncFromNative()) {
                error("AUTHORITATIVE 镜像失败（增量+全量均不可用）")
            }
            // ②' 重置反向捕获窗口：② 的变更由 C++ 产生、无需回导（阶段 3 反向通道）
            stateStore.resetReverseAccumulator()
            // ③ Kotlin 残留执行器（单事务：自动装备/丹药/突破）
            stateStore.update { phaseSettlementExecutor.executeResidual(this) }
            // ④ 月/年边界：完整编排（年变先于月变）
            if (settleFlags != 0) {
                processMonthYearChange(
                    monthChanged = (settleFlags and GameCoreBridge.FLAG_MONTH_CHANGED) != 0,
                    yearChanged = (settleFlags and GameCoreBridge.FLAG_YEAR_CHANGED) != 0
                )
            }
            // ⑤ 反向增量回导 C++（残留 + 边界效果写回真相源；阶段 3 取代每旬全量回导）。
            // restoreRng=false 语义保留：增量信封剔除 rngStates；容量拒绝/失败降级全量
            if (!stateSyncServiceRef.applyDirtyToNative()) {
                DomainLog.w(TAG, "AUTHORITATIVE 反向增量回导失败（降级全量回导）")
                if (!stateSyncServiceRef.importToNative(restoreRng = false)) {
                    DomainLog.w(TAG, "AUTHORITATIVE 全量回导失败（下一旬重试）")
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        gameClock.refundPhases(capped)
        throw e
    }
}

/**
 * 确保 AUTHORITATIVE 所需的 native 链路就绪（幂等；引擎线程串行调用）。
 *
 * 加载 .so → nativeInit（authoritativeTickMode=true，种子取当前档 mapSeed）
 * → 挂载 RNG 委托通道 → 全量导入 Kotlin 状态（含 rngStates 恢复 C++ 分区）。
 * 任一步失败返回 false（调用方回退纯 Kotlin 路径）。
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")  // 降级契约：多 return 逐级回退
internal fun GameEngineCore.ensureAuthoritativeNative(): Boolean {
    return try {
        if (!GameCoreBridge.isLoaded) {
            GameCoreBridge.ensureLoaded()
        }
        if (!GameCoreBridge.nativeIsInitialized()) {
            val initialized = GameCoreBridge.nativeInit(
                snapshotSchemaVersion = NATIVE_SNAPSHOT_SCHEMA_VERSION,
                systemSeed = stateStore.gameData.value.mapSeed.toLong(),
                seedInitialized = true,
                authoritativeTickMode = true
            )
            if (!initialized) return false
            gameRngManager.attachNativeChannel(GameCoreRngChannel)
            if (!stateSyncServiceRef.importToNative()) return false
            // 全量导入成功后清空反向捕获窗口——读档/加载路径的事务捕获若残留，
            // 首个 ⑤ 会把陈旧变更误发 C++（阶段 3 反向通道窗口管理）
            stateStore.resetReverseAccumulator()
            DomainLog.i(TAG, "AUTHORITATIVE native 引擎已初始化（seed=${stateStore.gameData.value.mapSeed}）")
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w(TAG, "AUTHORITATIVE native 初始化失败，本 tick 回退纯 Kotlin: ${e.message}")
        false
    }
}

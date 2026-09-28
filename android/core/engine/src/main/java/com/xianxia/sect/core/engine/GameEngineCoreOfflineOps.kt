package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException

private const val TAG = "GameEngineCoreOffline"

/**
 * GameEngineCoreOfflineOps — 离线收益注入编排（结算改造 2026-09-27 §10 B7，
 * 方案 §2.3「离线时段 ∩ 上限 注入连续积分轨；日历投影同步跳变」）。
 *
 * 两段式（线程契约见 docs/threading-contract.md 表四 offline 通道行）：
 * - **staging**（[stageOfflineProgress]，boot 编排调用）：以存档 lastSaveTime
 *   为起点计量现实离线时段，经 [GameConfig.Time.offlineGameMs] 折算（12h 全额
 *   + 50% 至 24h 硬顶，§1.4 已定口径），写入 [GameEngineCore.pendingOfflineGameMs]。
 *   纯 Kotlin 计算，零 native 依赖。
 * - **consume**（[consumePendingOfflineProgress]，仅引擎线程——
 *   [GameEngineCore.ensureAuthoritativeNative] 尾部消费点）：native 就绪 →
 *   [GameCoreBridge.nativeInjectOfflineGameMs]（C++ 三轴推进 + L1/L3 积分
 *   全额结算 + 日历投影 set + 判定轨/月年事件 0 次）+ 镜像同步；native 不可用
 *   → 回退臂 [com.xianxia.sect.core.engine.system.GameTimeClock.addOfflineGameMs]
 *   （单引擎终态下仅测试/降级触达）。消费即清零——native 初始化重试不重复注入。
 *
 * 时序约束：消费点位于 `importToNative`（注入会被导入覆盖则无效）与
 * `nativeLoopStart`（PhaseClock.start() 清轴会清掉注入推进）**之后**——
 * 由 ensureAuthoritativeNative 初始化分支的调用序保证。
 */

/** 离线回归报告（UI 提示输入；只含展示面，不含注入数值——玩家文案不泄数值） */
data class OfflineReturnReport(
    /** 现实离线时长毫秒（UI 通俗格式化；上限提示文案固定不随数值变化） */
    val offlineWallMs: Long
)

/**
 * staging：折算离线收益并挂起待注入（boot 编排调用，引擎线程）。
 *
 * @param lastSaveWallMs 存档最后保存时刻（GameData.lastSaveTime，现实墙钟基
 *   System.currentTimeMillis）。≤0 = 无保存记录（新档/异常档）→ 不注入。
 */
internal fun GameEngineCore.stageOfflineProgress(lastSaveWallMs: Long) {
    if (lastSaveWallMs <= 0L) {
        pendingOfflineGameMs = 0L
        pendingOfflineWallMs = 0L
        return
    }
    val offlineWallMs = (wallClock.currentTimeMillis() - lastSaveWallMs)
        .coerceAtLeast(0L)
    pendingOfflineWallMs = offlineWallMs
    pendingOfflineGameMs = GameConfig.Time.offlineGameMs(offlineWallMs)
    DomainLog.i(
        TAG,
        "离线收益 staging：离线 ${offlineWallMs}ms → 折算 ${pendingOfflineGameMs}ms" +
            "(整旬交付)"
    )
}

/**
 * 消费待注入离线收益（仅引擎线程；幂等——消费即清零）。
 * 调用点 = [GameEngineCore.ensureAuthoritativeNative] 成功尾部（所有调用方
 * 统一覆盖：boot 后首帧 / 逐旬快路径 / 重读档后的下一次 ensure）。
 *
 * 降级契约（@Suppress 显式设计）：native 分流/镜像/报告发布的异常源跨
 * IO/JNI 不可枚举——任何失败放弃本段离线收益并日志留痕，不杀引擎首帧
 * （与 authoritativeLoopIteration 单帧降级契约同型）。
 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：注入失败统一放弃收益不杀首帧
internal fun GameEngineCore.consumePendingOfflineProgress() {
    val gameMs = pendingOfflineGameMs
    if (gameMs <= 0L) return
    val offlineWallMs = pendingOfflineWallMs
    pendingOfflineGameMs = 0L
    pendingOfflineWallMs = 0L
    try {
        if (GameCoreBridge.isLoaded && GameCoreBridge.nativeIsInitialized()) {
            val newElapsed = GameCoreBridge.nativeInjectOfflineGameMs(gameMs)
            // 镜像同步：注入改动经增量拉取（同 runAccrualArm 模式，失败全量兜底）
            val applied = stateSyncServiceRef.applyDirtyFromNative()
            if (applied == null && !stateSyncServiceRef.syncFromNative()) {
                DomainLog.w(TAG, "离线注入后镜像同步失败——待下一帧镜像收敛")
            }
            // 连续臂差分基准重锚到注入后轴值（防把注入当帧增量重复积分）
            accruedElapsedGameMs = newElapsed
            DomainLog.i(TAG, "离线收益注入完成：+${gameMs}ms → 权威轴 $newElapsed")
        } else {
            // 回退臂：单引擎终态下 native 不可用 = 跳过结算语义，注入交
            // Kotlin 累积器（超追补上限丢弃，与"异常挂起追补"同口径）
            gameClock.addOfflineGameMs(gameMs)
            DomainLog.w(TAG, "native 未就绪，离线收益走回退臂累积器：+${gameMs}ms")
        }
        if (offlineWallMs > 0L) {
            offlineReturnReportMutable.value = OfflineReturnReport(offlineWallMs)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 降级契约：注入失败不杀引擎首帧——离线收益放弃（≤18h 游戏时间量级，
        // 下一档存档恢复在线语义），日志留痕
        DomainLog.w(TAG, "离线收益注入失败（放弃本段收益）: ${e.message}")
    }
}

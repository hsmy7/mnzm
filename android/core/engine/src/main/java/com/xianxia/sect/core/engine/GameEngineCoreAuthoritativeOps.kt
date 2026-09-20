package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.config.GameConfigNativeBridge
import com.xianxia.sect.core.engine.config.GameDataNativeBridge
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.GameCoreRngChannel
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException

/** native 快照 schema 版本（与 C++ GameCoreConfig 默认值一致） */
private const val NATIVE_SNAPSHOT_SCHEMA_VERSION = "0.1.0"

private const val TAG = "GameEngineCore"

/**
 * GameEngineCoreAuthoritativeOps — AUTHORITATIVE 过渡期 tick 集成
 * （独立文件承载以守住 GameEngineCore.kt 行数约束）。
 *
 * 语义切分（correctness-first，与 Kotlin 全量引擎逐位等价）：
 * - C++ 真相源：每旬时间推进 + 核心结算（步骤 1-5 零 RNG 批量，
 *   即实测的每旬热点路径）
 * - Kotlin 残留：自动装备/丹药/突破（偷盗钩子/亲属赠送/埋点副作用面）
 *   + 完整月变/年变编排——RNG 经 NativeBackedRng 委托 native 单一真相源
 *
 * 反向增量回导已随 w3-13 删除（handover §2.82）：tick 管线对 C++ 状态只读，
 * Kotlin 侧事件后状态收敛走 [rebaselineNativeMirror]（importToNative 全量重建）。
 */

/**
 * AUTHORITATIVE 过渡期 tick：每旬互插。
 *
     * ① nativeSettlePhase——C++ 单旬推进（时间 + 完整七步结算：自动装备/
     *    核心批次/丹药+偷盗钩子/突破+亲属赠送；月/年边界只记标志），
     *    C++ 每旬即完整结算，Kotlin 侧无残留结算路径。
     * ② applyDirtyFromNative——增量镜像（失败先试全量兜底，仍失败走异常回退）
     *    镜像写入经 updateMirror（C++ 真相源投影语义）
 * ②' 突破埋点（UI 通知类残留）：基线在 settle 前捕获，镜像后差分上报
 *    ——C++ 每旬突破判定不再经 Kotlin 处理器，埋点由镜像差分重建
 * ③ 月/年边界：完整编排（年变先于月变）
 *
 * 时钟语义：墙钟消费/速度/暂停/refundPhases 仍由 Kotlin GameTimeClock 独占
 * （C++ 侧不维护 speed/pause/refund 状态机，避免双语言两套状态机漂移）。
 *
 * 失败语义：native 链路任何失败 → refundPhases 后重抛（与
 * GameEngineCore.processTickPhases 相同的看门狗处理路径），下个 tick 由
 * [ensureAuthoritativeNative] 决定是否重建或回退纯 Kotlin 路径。
 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：native 链路失败统一 refund+重抛
internal suspend fun GameEngineCore.processAuthoritativeTick(phasesToAdvance: Int) {
    // 追补上限按当前速度缩放（ 单一来源）：旧固定 3 在
    // 2x 下挂起 ≥6s 计划 6 旬只执行 3 旬且不 refund——游戏时间相对墙钟持续变慢
    val capped = phasesToAdvance.coerceAtMost(GameTimeClock.maxPhasesPerTick(gameClock.speed))
    try {
        repeat(capped) {
            // 分段计时（batch-22a debug 埋点；release 恒 null = 零实例化零开销）
            val segments = if (BuildConfig.DEBUG) PhaseSegmentTimer() else null
            // ① 埋点基线（旬间 Kotlin 域突破并入基线，防重复上报）
            breakthroughAnalyticsObserver.captureBaseline(stateStore.discipleTables)
            segments?.split("baseline")
            // ② C++ 单旬推进（时间 + 完整七步结算）
            val settleFlags = GameCoreBridge.nativeSettlePhase()
            segments?.split("settle")
            // ③ 增量镜像；失败先试全量兜底，仍失败则走异常回退路径
            val applied = stateSyncServiceRef.applyDirtyFromNative()
            if (applied == null && !stateSyncServiceRef.syncFromNative()) {
                error("AUTHORITATIVE 镜像失败（增量+全量均不可用）")
            }
            segments?.split(if (applied == null) "mirror.full" else "mirror.inc")
            // ③' 突破埋点（UI 通知类残留——镜像后差分）
            breakthroughAnalyticsObserver.reportNewBreakthroughs(
                stateStore.discipleTables
            )
            segments?.split("breakthrough")
            // ④ 月/年边界：完整编排（年变先于月变）
            if (settleFlags != 0) {
                processMonthYearChange(
                    monthChanged = (settleFlags and GameCoreBridge.FLAG_MONTH_CHANGED) != 0,
                    yearChanged = (settleFlags and GameCoreBridge.FLAG_YEAR_CHANGED) != 0
                )
            }
            segments?.split("boundary")
            segments?.let { timer ->
                val gd = stateStore.gameData.value
                timer.log(TAG, "y${gd.gameYear}m${gd.gameMonth}")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // refund 按时间真相源分流——native 帧计划管线活跃时归还
        // native PhaseClock，遗留纯 Kotlin 路径仍归还 Kotlin gameClock
        if (nativeLoopPipelineActive) {
            runCatching { GameCoreBridge.nativeLoopRefundPhases(capped) }
            gameClock.consumeDeadTime()
        } else {
            gameClock.refundPhases(capped)
        }
        throw e
    }
}

/**
 * Kotlin 侧写入后的 native 基线重建（w3-13 通道关闭配套——§2.75④ "自愈后全量重建基线"）。
 *
 * 用途：世界/AI 域的中途防御性写入（存档自愈/完整性修复/攻宗占领/升级回退臂）在
 * 对应单元关闭后不再经反向通道回导——写入完成即调本函数以当前镜像**全量重建**
 * C++ 基线（读档/新档基线路径 `importToNative` 的 sanctioned 复用；restoreRng=false
 * 保持 native RNG 真相源）。事件均为低频用户/存档动作，O(状态) 成本可接受。
 *
 * native 未就绪（boot 序列）时静默跳过——基线由 `ensureAuthoritativeNative` 的
 * 全量导入建立，届时吸收已修复状态。
 */
internal fun GameEngine.rebaselineNativeMirror(reason: String) {
    rebaselineNativeMirror(stateSyncService, reason)
}

/**
 * [GameEngineCore] 侧变体：服务/门面层（仅持 [GameEngineCore]，如 DiscipleFacadeImpl
 * 残差钩子）在非捕获写入（updateMirror）后重建基线用——与 [GameEngine] 变体同一
 * StateSyncService 实例（`GameEngine.stateSyncService = gameEngineCore.stateSyncServiceRef`）。
 */
internal fun GameEngineCore.rebaselineNativeMirror(reason: String) {
    rebaselineNativeMirror(stateSyncServiceRef, reason)
}

private fun rebaselineNativeMirror(stateSync: StateSyncService, reason: String) {
    if (!GameCoreBridge.isLoaded || !GameCoreBridge.nativeIsInitialized()) return
    if (stateSync.importToNative(restoreRng = false)) {
        DomainLog.i(TAG, "native 基线重建完成（$reason）")
    } else {
        DomainLog.w(TAG, "native 基线重建失败（$reason）——C++ 侧待下一回导/导入收敛")
    }
}

/**
 * 每旬分段计时器（batch-22a debug 埋点专用；release 构建恒不实例化 = 零开销）。
 *
 * 采集项（µs 精度，每旬一行 DomainLog.d）：
 *   baseline（Kotlin 突破基线捕获）→ settle（C++ 单旬结算）→
 *   mirror.inc / mirror.full（前向增量镜像，.full = 增量失败已走全量兜底）→
 *   breakthrough（突破差分上报）→ boundary（月/年边界编排）。
 * 为 WS-1 再评估阈值（每旬镜像 >100ms，handover §4.1）提供真机绝对值观测输入。
 */
private class PhaseSegmentTimer {
    private var lastNanos = System.nanoTime()
    private val parts = ArrayList<String>(8)

    /** 结束当前段并记录耗时（自上一次 [split] 或构造起）。 */
    fun split(name: String) {
        val now = System.nanoTime()
        parts += "$name=${(now - lastNanos) / 1_000L}µs"
        lastNanos = now
    }

    fun log(tag: String, phaseLabel: String) {
        DomainLog.d(tag, "每旬分段[$phaseLabel]: ${parts.joinToString(" ")}")
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
                authoritativeTickMode = true,
                // 地图冻结（WS-5b）：地形生成参数由 GameConfig.SectMap 传值
                //（单一数据源不落 C++，§2.19 口径）——C++ importStateInternal
                // 归一化族 ensureTerrainGenerated 消费（无段老档按 mapSeed 生成）
                terrainWidthCells = GameConfig.SectMap.WORLD_WIDTH_CELLS,
                terrainHeightCells = GameConfig.SectMap.WORLD_HEIGHT_CELLS,
                terrainDensity = GameConfig.SectMap.DECORATION_DENSITY,
                terrainBorderRing = GameConfig.SectMap.BORDER_TREE_RING,
                terrainGateX = GameConfig.SectMap.GATE_X,
                terrainGateY = GameConfig.SectMap.GATE_Y,
                terrainGateWidth = GameConfig.SectMap.GATE_WIDTH,
                terrainGateHeight = GameConfig.SectMap.GATE_HEIGHT,
                terrainMapGenVersion = GameConfig.SectMap.MAP_GEN_VERSION
            )
            if (!initialized) return false
            gameRngManager.attachNativeChannel(GameCoreRngChannel)
            // 镜像通道传输编码换轨（R2.2 灰度）：native 初始化后立即推送分发
            // 模式，早于任何 nativeExportDirty 调用——与 Kotlin 解码侧读同一
            // [NativeEngineFlag.mirrorProtobufTransport]，生产/解码两端一致。
            GameCoreBridge.nativeSetDirtyExportProtobuf(NativeEngineFlag.mirrorProtobufTransport)
            // 列级增量导出接线（R2.4/B09 灰度）：与传输编码旗标同点推送，
            // 早于任何 nativeExportDirty 调用——C++ 导出模式与 Kotlin 解码侧
            // 读同一 [NativeEngineFlag.dirtyColumnExport]。
            GameCoreBridge.nativeSetDirtyExportColumn(NativeEngineFlag.dirtyColumnExport)
            // native 初始化完成后补注运行时配置
            // （CultivationEventProcessor 构造时 native 可能未加载——此处幂等补注）
            GameConfigNativeBridge.ensureInjected()
            // 静态数据表补注（R6.2/B16 数值外置）：与上同点幂等，
            // 保证 native 引擎开始结算前 DB 已由数据文件注入（否则用内联兜底）
            GameDataNativeBridge.ensureInjected()
            if (!stateSyncServiceRef.importToNative()) return false
            // 引擎循环时钟基准启动（防 PhaseClock 残留 lastWallMs 造成
            // 首帧巨量 delta → 追补上限截断丢时间）
            GameCoreBridge.nativeLoopStart()
            DomainLog.i(TAG, "AUTHORITATIVE native 引擎已初始化（seed=${stateStore.gameData.value.mapSeed}）")
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // 生产默认切换暴露的降级契约缺口：System.loadLibrary 失败抛
        // UnsatisfiedLinkError（Error 而非 Exception——split APK 损坏/16KB 对齐失败等
        // 场景，JVM 测试环境同样触达），降级契约"任一步失败返回 false 回退纯 Kotlin"
        // 必须覆盖 Throwable（CancellationException 仍穿透）
        DomainLog.w(TAG, "AUTHORITATIVE native 初始化失败，本 tick 回退纯 Kotlin: ${e.message}")
        false
    }
}

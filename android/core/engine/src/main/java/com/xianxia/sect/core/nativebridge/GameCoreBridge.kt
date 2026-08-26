package com.xianxia.sect.core.nativebridge

/**
 * GameCoreBridge — JNI 桥接到 C++ 游戏引擎（game-core）。
 *
 * 对应 C++ 侧 `GameCoreBridge.cpp`（extern "C" 实现）。
 * 设计：**通用入口**（非每方法一个 JNI）——业务操作经 [nativeExecute] +
 * ActionId 协议分发；参数/结果用 JSON 字节传输（Kotlin kotlinx 序列化
 * ↔ C++ nlohmann/json）。
 *
 * 线程契约：所有调用必须在**引擎线程**（GameEngineCore 单线程调度器）
 * 串行执行；C++ 侧无锁单线程模型与此对齐。
 */
@Suppress("TooManyFunctions")  // JNI 入口对象：入口数与通道数成正比，属桥面职责边界
object GameCoreBridge {

    /** 是否已加载原生库 */
    private var loaded = false

    /** 原生库是否已加载（转发层/tick 桥在调用前检查，未加载则静默跳过） */
    val isLoaded: Boolean get() = loaded

    /** 加载原生库（独立于渲染库 native-renderer） */
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("native-game-core")
            loaded = true
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /** 初始化引擎（幂等拒绝重复初始化；config 由 Kotlin 侧 GameCoreModule 提供）
     *
     * @param authoritativeTickMode T2.4 AUTHORITATIVE 过渡模式——true 时每旬
     *        只跑 C++ 核心结算（步骤 1-5），月/年边界以标志位返回由 Kotlin
     *        残留执行器处理
     */
    external fun nativeInit(
        snapshotSchemaVersion: String,
        systemSeed: Long,
        seedInitialized: Boolean,
        authoritativeTickMode: Boolean
    ): Boolean

    /** 关闭引擎（幂等） */
    external fun nativeDestroy()

    /** 引擎是否已初始化 */
    external fun nativeIsInitialized(): Boolean

    // ============================================================
    // 逻辑 tick
    // ============================================================

    /**
     * 推进一个逻辑步（对应 Kotlin 帧循环 tickInternal；100ms 逻辑步长）。
     *
     * @param deltaNs 本次推进的逻辑时间（纳秒，accumulator 消费）
     * @param nowMs 现实时间戳（System.currentTimeMillis；显式传入保证对拍可控）
     * @return 是否成功推进
     */
    external fun nativeAdvance(deltaNs: Long, nowMs: Long): Boolean

    // ============================================================
    // AUTHORITATIVE tick 标量通道（计划 v2 阶段 2d）
    // ============================================================

    /** settleOnePhase 边界标志位：本旬跨月 */
    const val FLAG_MONTH_CHANGED = 1
    /** settleOnePhase 边界标志位：本旬跨年 */
    const val FLAG_YEAR_CHANGED = 2

    /**
     * 单旬推进（AUTHORITATIVE tick 标量通道）：时间推进 + C++ 核心每旬结算
     * （步骤 1-5 零 RNG 批量），返回边界标志位（[FLAG_MONTH_CHANGED] /
     * [FLAG_YEAR_CHANGED] 位组合）——月/年结算由 Kotlin 残留执行器处理。
     * 引擎未初始化返回 0。
     */
    external fun nativeSettlePhase(): Int

    /**
     * RNG 分区标量抽取：指定分区下一个 32 位整数（PCG-XSH-RR 原始输出，
     * 与 Kotlin [com.xianxia.sect.core.util.DeterministicRng.nextInt] 逐位一致）。
     * T2.4 起 AUTHORITATIVE 模式下 Kotlin 抽取经此通道委托单一真相源，
     * 保证跨语言随机序列逐位统一。
     */
    external fun nativeRngNextInt(partitionId: Int): Int

    /** 读取分区当前状态（对应 DeterministicRng.snapshot()）；未初始化返回 0 */
    external fun nativeRngSnapshotPartition(partitionId: Int): Long

    /** 写入分区状态（对应 DeterministicRng.restore(state)）；非法分区忽略 */
    external fun nativeRngRestorePartition(partitionId: Int, state: Long)

    /** 重置系统种子（新档 initSystemSeed 对应；各分区 seed+partitionId 重播） */
    external fun nativeRngInitSeed(seed: Long)

    // ============================================================
    // 业务操作（ActionId 协议）
    // ============================================================

    /**
     * 执行业务操作。
     *
     * @param actionId 操作码（见 [ActionIds]，与 C++ action_ids.h 同源生成）
     * @param paramsJson 参数 JSON 字节（Kotlin 侧 kotlinx 序列化）
     * @param nowMs 现实时间戳
     * @return 结果 JSON 字节（含 sealed 结果语义，Kotlin 侧反序列化重建）
     */
    external fun nativeExecute(actionId: Int, paramsJson: ByteArray, nowMs: Long): ByteArray

    // ============================================================
    // 状态快照
    // ============================================================

    /** 导出全量状态快照（JSON 字节；存档前/读档后全量同步调用） */
    external fun nativeExportState(): ByteArray

    /** 导入全量状态快照（读档调用） */
    external fun nativeImportState(stateJson: ByteArray): Boolean

    /**
     * 导入全量状态快照但不恢复 RNG 分区（T2.4 AUTHORITATIVE 每旬回导专用：
     * 委托模式下 native RNG 即真相源，恢复镜像里的滞后 rngStates 会造成
     * 分区回卷与跨语言漂移）
     */
    external fun nativeImportStateNoRng(stateJson: ByteArray): Boolean

    /** 导出自上次导出以来的变更集（JSON；UI 镜像增量同步） */
    external fun nativeExportDirty(): ByteArray

    /** 导出事件队列（JSON；Kotlin 侧 poll 消费后转 DomainEvent） */
    external fun nativePollEvents(): ByteArray
}

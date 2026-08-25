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

    /** 初始化引擎（幂等拒绝重复初始化；config 由 Kotlin 侧 GameCoreModule 提供） */
    external fun nativeInit(
        snapshotSchemaVersion: String,
        systemSeed: Long,
        seedInitialized: Boolean
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

    /** 导出自上次导出以来的变更集（JSON；UI 镜像增量同步） */
    external fun nativeExportDirty(): ByteArray

    /** 导出事件队列（JSON；Kotlin 侧 poll 消费后转 DomainEvent） */
    external fun nativePollEvents(): ByteArray
}

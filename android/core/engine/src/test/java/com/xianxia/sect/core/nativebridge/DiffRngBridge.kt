package com.xianxia.sect.core.nativebridge

import java.io.File

/**
 * DiffRngBridge — 桌面 JNI 对拍桥（仅测试用，不进生产 APK）。
 *
 * 对应 C++ 侧 `gamecore/jni/GameCoreJni.cpp`（无 Android 依赖的桌面 JNI）。
 * 用途：JUnit 测试加载本库，与真实 Kotlin `DeterministicRng` 做跨语言差分对拍
 * （确定性守护：C++ 复刻与 Kotlin 输出必须逐位一致）。
 *
 * 加载方式：`-Dgamecore.jni.path=<绝对路径>/libgamecorejni.so`（Gradle test 配置注入），
 * 未配置时跳过对拍测试（CI 桌面 job 与本地均需先构建桌面 JNI）。
 */
object DiffRngBridge {

    private val loaded: Boolean by lazy {
        val path = System.getProperty("gamecore.jni.path")
        if (path.isNullOrBlank()) {
            false
        } else {
            System.load(File(path).absolutePath)
            true
        }
    }

    /** 桌面 JNI 是否可用（未配置路径时对拍测试应跳过而非失败） */
    fun isAvailable(): Boolean = loaded

    // ── DeterministicRng 通道 ────────────────────────────────
    external fun nativeFromSeed(seed: Long)
    external fun nativeNextInt(): Int
    external fun nativeNextIntBound(bound: Int): Int
    external fun nativeNextLongBound(bound: Long): Long
    external fun nativeNextDouble(): Double
    external fun nativeSnapshot(): Long

    // ── RngManager（分区）通道 ───────────────────────────────
    external fun nativeManagerInit(seed: Long)
    external fun nativeManagerNextInt(partitionId: Int, bound: Int): Int
    external fun nativeManagerSnapshot(partitionId: Int): Long

    // ── GameCore 状态快照通道（批次 1） ──────────────────────
    external fun nativeCoreInit()
    external fun nativeCoreImportState(stateJson: ByteArray): Boolean
    external fun nativeCoreExportState(): ByteArray

    /** 导入但不恢复 RNG 分区（AUTHORITATIVE 每旬回导对拍用） */
    external fun nativeCoreImportStateNoRng(stateJson: ByteArray): Boolean

    /** 按模式（重）创建引擎（AUTHORITATIVE 对拍用；模式一致时复用单例） */
    external fun nativeCoreInitMode(authoritativeTickMode: Boolean)

    // ── GameCore 变更集通道（计划 v2 阶段 1：exportDirty 对拍用） ──
    external fun nativeCoreExportDirty(): ByteArray

    // ── GameCore 时间推进通道（批次 3，对拍用） ──────────────
    external fun nativeCoreAdvancePhases(phaseCount: Int): Int

    // ── AUTHORITATIVE tick 标量通道（计划 v2 阶段 2d，对拍用） ──
    /** 单旬推进（时间 + 核心结算），返回边界标志位（bit0=月变 bit1=年变） */
    external fun nativeCoreSettlePhase(): Int
    /** RNG 分区标量抽取（PCG-XSH-RR 原始输出，与 DeterministicRng.nextInt 逐位一致） */
    external fun nativeCoreRngNextInt(partitionId: Int): Int
    /** 读取分区状态（对应 DeterministicRng.snapshot()） */
    external fun nativeCoreRngSnapshotPartition(partitionId: Int): Long
    /** 写入分区状态（对应 DeterministicRng.restore(state)） */
    external fun nativeCoreRngRestorePartition(partitionId: Int, state: Long)
    /** 重置系统种子（各分区 seed+partitionId 重播） */
    external fun nativeCoreRngInitSeed(seed: Long)

    // ── 经济/库存操作通道（批次 4，对拍用） ──────────────────
    external fun nativeCoreExecOps(opsJson: ByteArray): ByteArray

    // ── 弟子属性计算通道（批次 5，对拍用） ──────────────────
    external fun nativeCoreDiscipleOp(opJson: ByteArray): ByteArray

    // ── 修炼推进计算通道（批次 5b，对拍用） ────────────────
    external fun nativeCoreCultivationOp(opJson: ByteArray): ByteArray

    // ── 战斗计算通道（批次 6a，对拍用） ────────────────────
    external fun nativeCoreBattleOp(opJson: ByteArray): ByteArray

    // ── 内政计算通道（批次 7，对拍用） ────────────────────
    external fun nativeCoreGovernmentOp(opJson: ByteArray): ByteArray

    // ── 探索计算通道（批次 8a，对拍用） ────────────────────
    external fun nativeCoreExplorationOp(opJson: ByteArray): ByteArray

    // ── execute 分发表通道（批次 9，对拍用） ────────────────
    external fun nativeCoreExecute(actionId: Int, paramsJson: ByteArray): ByteArray

    // ── 道路系统通道（批次 R，对拍用） ─────────────────────
    external fun nativeRoadOp(opJson: ByteArray): ByteArray

    external fun nativeDestroy()
}

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
    /** 正态分布（Box-Muller 跨语言精度对拍——StrictMath vs 内嵌 fdlibm） */
    external fun nativeNextGaussian(mean: Double, stddev: Double): Double
    /** 中文名继承对拍（Kotlin NameService.inheritName 分区 rng 版
     *  vs C++ name_service.h——返回 fullName，g_rng 为随机源） */
    external fun nativeNameInherit(
        surname: String, gender: String, existingJson: String
    ): String
    /** 弟子创建对拍（Kotlin DiscipleFactory.create vs C++
     *  createDisciple——输入 seed JSON，返回弟子生成结果 JSON，g_rng 为随机源） */
    external fun nativeCreateDisciple(seedJson: String): String
    external fun nativeSnapshot(): Long

    // ── RngManager（分区）通道 ───────────────────────────────
    external fun nativeManagerInit(seed: Long)
    external fun nativeManagerNextInt(partitionId: Int, bound: Int): Int
    external fun nativeManagerSnapshot(partitionId: Int): Long

    // ── GameCore 状态快照通道 ──────────────────────────────
    external fun nativeCoreInit()
    external fun nativeCoreImportState(stateJson: ByteArray): Boolean
    external fun nativeCoreExportState(): ByteArray

    /** 导入但不恢复 RNG 分区（AUTHORITATIVE 每旬回导对拍用） */
    external fun nativeCoreImportStateNoRng(stateJson: ByteArray): Boolean

    // ── 反向增量通道（applyReverseDirty 对拍用） ──
    external fun nativeCoreApplyReverseDirty(dirtyJson: ByteArray): Boolean

    /** 手动招募单招（Kotlin DiscipleFacadeImpl.recruitDiscipleFromList 等价
     *  下沉对拍用：C++ 直接招募入宗，返回信封 JSON 字节——协议与生产
     *  GameCoreBridge.nativeManualRecruitFromList 一致） */
    external fun nativeCoreManualRecruitFromList(discipleId: String): ByteArray

    /** 按模式（重）创建引擎（AUTHORITATIVE 对拍用；模式一致时复用单例） */
    external fun nativeCoreInitMode(authoritativeTickMode: Boolean)

    // ── GameCore 变更集通道（exportDirty 对拍用） ──
    external fun nativeCoreExportDirty(): ByteArray

    // ── GameCore 时间推进通道（对拍用） ──────────────────
    external fun nativeCoreAdvancePhases(phaseCount: Int): Int

    // ── AUTHORITATIVE tick 标量通道（对拍用） ──
    /** 单旬推进（时间 + 核心结算），返回边界标志位（bit0=月变 bit1=年变） */
    external fun nativeCoreSettlePhase(): Int
    /** C++ 完整月变结算（信封 JSON——与生产 GameCoreBridge.nativeSettleMonth 同协议） */
    external fun nativeCoreSettleMonth(): ByteArray
    /** C++ 完整年变结算（信封 JSON——与生产 GameCoreBridge.nativeSettleYear 同协议） */
    external fun nativeCoreSettleYear(): ByteArray
    /** RNG 分区标量抽取（PCG-XSH-RR 原始输出，与 DeterministicRng.nextInt 逐位一致） */
    external fun nativeCoreRngNextInt(partitionId: Int): Int
    /** 读取分区状态（对应 DeterministicRng.snapshot()） */
    external fun nativeCoreRngSnapshotPartition(partitionId: Int): Long
    /** 写入分区状态（对应 DeterministicRng.restore(state)） */
    external fun nativeCoreRngRestorePartition(partitionId: Int, state: Long)
    /** 重置系统种子（各分区 seed+partitionId 重播） */
    external fun nativeCoreRngInitSeed(seed: Long)

    // ── 经济/库存操作通道（对拍用） ──────────────────
    external fun nativeCoreExecOps(opsJson: ByteArray): ByteArray

    // ── 弟子属性计算通道（对拍用） ──────────────────
    external fun nativeCoreDiscipleOp(opJson: ByteArray): ByteArray

    // ── 修炼推进计算通道（对拍用） ────────────────
    external fun nativeCoreCultivationOp(opJson: ByteArray): ByteArray

    // ── 战斗计算通道（对拍用） ────────────────────
    external fun nativeCoreBattleOp(opJson: ByteArray): ByteArray

    // ── 内政计算通道（对拍用） ────────────────────
    external fun nativeCoreGovernmentOp(opJson: ByteArray): ByteArray

    // ── 探索计算通道（对拍用） ────────────────────
    external fun nativeCoreExplorationOp(opJson: ByteArray): ByteArray

    // ── AI 兽袭目标预计算直调（对拍用） ─────────────
    // 直接作用于 g_core 当前状态（导入/导出经 nativeCoreImportState/
    // nativeCoreExportState），与 Kotlin AISectBeastAttackProcessor.
    // precomputeTargets 逐位对拍——不经过完整月变管线（规避步骤 4e
    // moveBeasts 的 EXPLORATION 干扰；生产路径经 runMonthSettlement 步骤 3）
    external fun nativeCorePrecomputeTargets()

    // ── G7-2：AI 攻击决策直调（对拍用，作用于 g_core 当前状态） ─────
    /** AI vs AI 逐目标攻击判定（Kotlin AISectAttackManager.checkAttackConditions
     *  vs C++ sect_attack_decision.h——输入 id + playerGarrison JSON，消费 BATTLE 分区） */
    external fun nativeCoreCheckAttackConditions(
        attackerId: String, defenderId: String, playerGarrisonJson: String
    ): Boolean
    /** AI 攻玩家预警决策（Kotlin AISectAttackManager.decidePlayerAttack
     *  vs C++ sect_attack_decision.h——返回 JSON 决策，消费 BATTLE 分区） */
    external fun nativeCoreDecidePlayerAttack(): String

    // ── execute 分发表通道（对拍用） ────────────────
    external fun nativeCoreExecute(actionId: Int, paramsJson: ByteArray): ByteArray

    // ── 道路系统通道（对拍用） ─────────────────────
    external fun nativeRoadOp(opJson: ByteArray): ByteArray

    // 浮空岛崖壁布局（地图边缘系统）无桌面对拍通道：合成器为纯头文件
    // gamecore/map/island_cliff.h，由桌面 GTest island_cliff_test 直接覆盖
    // （比 JNI 往返更直接）；JNI 装配层（GameCoreBridge.nativeIslandCliffCompose）
    // 仅做参数搬运，无算法分支。

    // ── 引擎循环 + 看门狗通道（对拍用） ──────
    /** 循环启动/重启：帧累积清零 + 时钟基准重置 */
    external fun nativeCoreLoopStart()
    /** 循环状态完全重置（测试隔离：tick 计数/速度/累积/帧状态清零；
     *  JUnit 用例间对齐 Kotlin 侧 new GameTimeClock 的干净基准） */
    external fun nativeCoreLoopReset()
    /** 速度切换（C++ PhaseClock 旧速度结算语义） */
    external fun nativeCoreLoopSetSpeed(speed: Int)
    /** 固定单调时钟推进（对拍脚本驱动 FixedMonotonicClock） */
    external fun nativeCoreLoopSetMonoMs(nowMs: Long)
    /** 死区时间消费（刷新基准不累积） */
    external fun nativeCoreLoopConsumeDeadTime()
    /** 归还已消费旬数（native 链路失败整批回滚对拍） */
    external fun nativeCoreLoopRefundPhases(count: Int)
    /** 用户活跃通知（输入端口 idleNs 维护对拍） */
    external fun nativeCoreLoopNotifyUserActivity()
    /** 当前旬内累积游戏毫秒（镜像查询） */
    external fun nativeCoreLoopAccumulatedGameMs(): Long
    /** 累计逻辑 tick 计数查询 */
    external fun nativeCoreLoopTickTotal(): Long
    /**
     * 单帧迭代计划（17 槽 LongArray，协议与 NativeLoopPlan.unpack 同源：
     * [0]paused · [1]tickCount · [2..6]tickKind · [7..11]tickPhases ·
     * [12]alpha 位模式 · [13]frameDeltaNs · [14]idleNs · [15]tickTotal ·
     * [16]accumulatedGameMs；引擎未初始化返回空数组）
     */
    external fun nativeCoreLoopFrame(pausedOrLoading: Boolean, isSaving: Boolean): LongArray
    /**
     * 看门狗统一判据（GameCore 组合通道）：引擎侧状态由 C++ 组合，
     * 平台侧 flags 由参数传入。返回 0-4 判定码；-1 = 未初始化。
     */
    external fun nativeCoreWatchdogVerdict(
        loopActive: Boolean,
        isPaused: Boolean,
        isSaving: Boolean,
        isLoading: Boolean,
        secretRealmPauseLock: Boolean,
        secretRealmPauseRenewedAtMs: Long
    ): Int
    /** 独立判据通道重置（每测试用例新建基准） */
    external fun nativeCoreMonitorReset()
    /**
     * 独立判据通道：直接喂快照判定（12 字段与 GameTimeProgressSnapshot 一一对应），
     * 与 Kotlin GameTimeProgressMonitor 同序列对拍。返回 0-4 判定码。
     */
    @Suppress("LongParameterList")  // JNI 快照对拍：12 参数与 ProgressSnapshot 字段一一对应
    external fun nativeCoreMonitorEvaluate(
        tickCount: Long,
        totalPhases: Long,
        accumulatedGameMs: Long,
        loopActive: Boolean,
        isPaused: Boolean,
        isSaving: Boolean,
        isLoading: Boolean,
        speed: Int,
        secretRealmPauseLock: Boolean,
        secretRealmPauseRenewedAtMs: Long,
        loopActiveAtMs: Long,
        recordedAtMs: Long
    ): Int

    // ── 宗门地图地形生成通道（桌面 JNI 同签名——与生产
    //    GameCoreBridge.nativeGenerateSectTerrain 等价，DiffSectTerrainTest
    //    双端全数组逐位对拍用）──
    /** 返回行主序展平瓦片数组（size = width*height）；width/height 非法返回 null */
    @Suppress("LongParameterList")  // JNI 声明 1:1 平铺（同生产入口）
    external fun nativeGenerateSectTerrain(
        seed: Int, width: Int, height: Int, density: Float, borderTreeRing: Int,
        gateX: Int, gateY: Int, gateWidth: Int, gateHeight: Int, gateSpriteY: Int
    ): IntArray?

    /** 位级对拍探针：C++ terrain cellHash（Kotlin SectMapTileGenerator.cellHash 对照） */
    external fun nativeSectCellHash(x: Int, y: Int, seed: Int): Float

    /** 位级对拍探针：C++ terrain smoothNoise（Kotlin smoothNoise 对照） */
    external fun nativeSectSmoothNoise(x: Int, y: Int, scale: Int, seed: Int): Float

    external fun nativeDestroy()
}

package com.xianxia.sect.core.nativebridge

/**
 * GameCoreBridge — JNI 桥接到 C++ 游戏引擎（game-core）。
 *
 * 对应 C++ 侧 `GameCoreBridge.cpp`（extern "C" 实现）。
 * 设计：**通用入口**（非每方法一个 JNI）——业务操作经 [nativeExecute] +
 * ActionId 协议分发；参数/结果用 JSON 字节传输（Kotlin kotlinx 序列化
 * ↔ C++ nlohmann/json）。
 *
 * ## 线程契约
 *
 * C++ 侧 `g_gameCore` 是**无锁单线程模型**（游戏状态/RNG 分区/引擎循环
 * 热字段均非原子）。入口分两类，与 C++ 侧 `jniRequireEngineThread` 守卫
 * 逐条对应：
 *
 * - **kEngineOnly**（必须引擎线程）：settle 系、execute、import/export、
 *   dirty、battle 系、loopFrame/refundPhases/consumeDeadTime、
 *   resetAutoRecruitIdle、destroy、**rng 系四入口**（P1-4 正式收口：
 *   跨线程调用面已全部收敛，2026-09-10 真机/开发期 ≥1h debug 混合操作
 *   零警告后由过渡 WARN 守卫升级为断言，batch-10）。debug 构建下 C++ 侧
 *   记录 owner 线程并在非法线程进入时 abort（release 零开销）。**紧急重启
 *   换线程后新驱动线程经 loopFrame 首帧 owner 重锚（见 kAnyThread 项），
 *   不误杀。**
 * - **kAnyThread**（设计上的跨线程端口）：isInitialized（只读探针）、
 *   watchdogVerdict（看门狗/主线程监控）、loopStart / loopOnRestart
 *   （**生命周期输入端口**——startGameLoop 按设计可从主线程调用：前台
 *   服务 onStartCommand / Activity onResume 后台恢复；紧急重启可从
 *   看门狗/主线程/闹钟兜底调用，且 Kotlin loopOpLock + phase 状态机与
 *   iterate 串行化，进入时循环必非运行态）、
 *   loopSetSpeed / loopNotifyUserActivity（UI 输入端口）、
 *   loopSetThermalStatus / loopSetBatteryStatus（平台推送）、
 *   roadCompose（纯函数）、setGameConfig（Dagger 构造期注入，线程不保证）。
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
     * @param authoritativeTickMode AUTHORITATIVE 过渡模式——true 时每旬
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

    // ============================================================
    // AUTHORITATIVE tick 标量通道
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
     * 单月推进（月变真相源切换）：C++ 完整月变结算（八步事务编排 +
     * 十六子事件已下沉面），返回 JSON 信封字节——`policyCosts.disabledPolicies`
     * （事务外 checkpointAllProduction 决策）+ `secretRealmClose`（秘境
     * 到期关闭草稿：memberIds/backpack/slotId，Kotlin 发关闭邮件 + 释放 gate）
     * + `purchaseLogs`（弟子购买日志草稿：discipleId/itemName/age，
     * Kotlin 写 lifeEvents 瞬态列）。引擎未初始化返回 "{}"。
     */
    /** S8：AI 热控批量上界推送（Kotlin ThermalMonitor 平台决策——12/6/3） */
    external fun nativeSetAiThermalBatchSize(batchSize: Int)

    external fun nativeSettleMonth(): ByteArray

    /**
     * 重置自动招募惰性门：Kotlin 侧重置点（年度招募刷新/玩家改
     * 筛选/生育/净化）调用，通知 C++ 复位 autoRecruitIdle——月变真相源切换后
     * autoRecruit 在 C++ 侧执行，重置点仍分布在 Kotlin，必须经此通道同步。
     */
    external fun nativeResetAutoRecruitIdle()

    /**
     * 单年推进（年变真相源切换）：C++ 完整年变结算（T1 已下沉面 +
     * T2 已下沉面 + 年报快照 + 年俸），返回 JSON 信封（当前为空对象——年变
     * 残留执行器为 Kotlin 侧纯状态 + 平台效应，无 C++ 草稿回传）。引擎未
     * 初始化返回 "{}"。
     */
    external fun nativeSettleYear(): ByteArray

    /**
     * RNG 分区标量抽取：指定分区下一个 32 位整数（PCG-XSH-RR 原始输出，
     * 与 Kotlin [com.xianxia.sect.core.util.DeterministicRng.nextInt] 逐位一致）。
     * AUTHORITATIVE 模式下 Kotlin 抽取经此通道委托单一真相源，
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

    /**
     * 战斗执行通道（AI 兽战/任务完成生产接线）。
     *
     * 输入 op JSON 字节：`{"team":[Combatant...], "beasts":[Combatant...],
     * "playerDamageModifier":1.0, "maxTurns":25, "timeoutMs":-1}`；
     * 输出：`{"turn":N, "timedOut":bool, "winner":"TEAM|BEASTS|DRAW",
     * "rewards":{...}, "team":[Combatant...], "beasts":[Combatant...]}`。
     *
     * RNG：C++ 侧消费 BATTLE 分区（kBattle）——AUTHORITATIVE 下委托式 RNG
     * 单一真相源，与 Kotlin NativeBackedRng 委托同一分区，序列天然一致。
     * 失败返回 `{"error":"..."}`（调用方回退 Kotlin 战斗执行）。
     */
    external fun nativeBattleExecute(opJson: ByteArray): ByteArray

    /**
     * AI 宗门战执行通道（洞天 AI 操作生产接线）。
     *
     * 输入 op JSON 字节：`{"attackers":[Combatant...], "defenders":[Combatant...]}`；
     * 输出：`{"turns":N, "winner":"ATTACKER|DEFENDER|DRAW",
     * "rounds":[...], "attackers":[Combatant...], "defenders":[Combatant...]}`。
     *
     * 第三战斗引擎 executeUnifiedAIBattle 等价（sect_battle.h）——AI vs AI
     * 宗门战/洞天 AI 操作 100% 共用；RNG 消费 BATTLE 分区（委托式真相源）。
     * 失败返回 `{"error":"..."}`（调用方回退 Kotlin）。
     */
    external fun nativeAiBattleExecute(opJson: ByteArray): ByteArray

    /**
     * AI 攻玩家预警决策通道（AI 攻击决策下沉）。
     *
     * 自包含（消费 GameCore 当前状态 + BATTLE 分区）；输出 JSON：
     * `{"type":"GENERATE_WARNING","attackerSectId":"..","attackerSectName":".."}`
     * 或 `{"type":"SKIP"}`；失败返回 `{"error":".."}`（调用方回退 Kotlin）。
     */
    external fun nativeDecidePlayerAttack(): ByteArray

    /**
     * AI vs AI 逐目标攻击判定通道。
     *
     * @param attackerId / defenderId 宗门 id（在 GameCore 状态 worldMapSects 中查找）
     * @param playerGarrisonJson 玩家占领守军 JSON（Map<String, List<Disciple>>，
     *        defender.isPlayerOccupied 时消费；空则守军战力 0）
     * @return 是否攻击（判定后消费 1 次 BATTLE 抽取）；id 未找到/异常返回 false（不消费）
     */
    external fun nativeCheckAttackConditions(
        attackerId: String,
        defenderId: String,
        playerGarrisonJson: ByteArray
    ): Boolean

    /**
     * 战胜后占领判定通道：AI vs AI 宗门战结束后的
     * `winner==ATTACKER && 高阶全灭` 判定（sect_attack_decision.h computeCanOccupy，
     * 纯确定性、零 RNG）。Kotlin `executeSectBattleCore` 在战斗胜利后调用，
     * 传入 `allSectDisciples`（防守方全宗门弟子池）+ `deadDefenderIds`。
     *
     * @param payloadJson `{"winnerIsAttacker":bool,"deadDefenderIds":[...],
     *        "defenders":[Disciple...]}`（defenders 为全宗门池，含复活/驻军）
     * @return 是否可占领；解析失败/异常返回 false（调用方回退 Kotlin 判定）
     */
    external fun nativeComputeCanOccupy(payloadJson: ByteArray): Boolean

    // ============================================================
    // 状态快照
    // ============================================================

    /** 导出全量状态快照（JSON 字节；存档前/读档后全量同步调用） */
    external fun nativeExportState(): ByteArray

    /** 导入全量状态快照（读档调用） */
    external fun nativeImportState(stateJson: ByteArray): Boolean

    /**
     * 导入全量状态快照但不恢复 RNG 分区（AUTHORITATIVE 每旬回导专用：
     * 委托模式下 native RNG 即真相源，恢复镜像里的滞后 rngStates 会造成
     * 分区回卷与跨语言漂移）
     */
    external fun nativeImportStateNoRng(stateJson: ByteArray): Boolean

    /**
     * 应用 Kotlin 侧反向增量变更集（取代 AUTHORITATIVE 每旬
     * 全量回导）。协议与 forward 一致 {version, changed, removed}：
     * - changed["gameData"]  → 全量 gameData（不含 rngStates——native RNG 真相源）
     * - changed["disciples"] → 按 id 全实体 upsert
     * - changed["<集合名>"]  → 该集合全量实体（幂等覆盖）
     * - removed["disciples"] / removed["<集合名>"] → 按 id 删除
     * 应用后 C++ 侧同步 DirtyTracker 基线（防下一旬 forward 重发）。
     *
     * @param dirtyJson 反向变更集 JSON 字节
     * @return true 应用成功；false 版本乱序/解析失败（调用方降级全量回导）
     */
    external fun nativeApplyReverseDirty(dirtyJson: ByteArray): Boolean

    /**
     * 手动招募单招（Kotlin [com.xianxia.sect.core.domain.disciple.DiscipleFacadeImpl]
     * 手动招募等价下沉——AUTHORITATIVE 单真相源：C++ 直接招募入宗，状态变化经
     * 下一 tick 前向 diff 推送镜像，消除"Kotlin 镜像修改 vs C++ 权威结算"窗口。
     *
     * 返回 JSON 信封字节：
     * `{"ok":bool, "newId":string, "age":int, "name":string,
     *   "reason":"SUCCESS|MONTHLY_LIMIT|NOT_FOUND|CORRUPTED|UNKNOWN"}`
     * - ok=false + reason=MONTHLY_LIMIT：本月招募已达上限（弹上限通知）
     * - ok=false + reason=NOT_FOUND：该弟子已不在招募列表
     * - ok=false + reason=CORRUPTED：数据损坏（C++ 已同事务移除，name 有效）
     * - ok=false + reason=UNKNOWN：异常兜底（调用方回退 Kotlin 实现）
     * - age 供 Kotlin 镜像补写 lifeEvents（Kotlin 类体属性，不进协议）
     * 引擎未初始化返回 ok=false + reason=UNKNOWN。
     *
     * @param discipleId 待招募弟子在 recruitList 中的 id（UI 层 DiscipleAggregate.id）
     */
    external fun nativeManualRecruitFromList(discipleId: String): ByteArray

    /**
     * 一键招募全部（Kotlin [com.xianxia.sect.core.GameEngine].recruitAllFromList
     * 等价下沉）。返回 JSON 信封字节：
     * `{"ok":bool, "count":int, "reason":"SUCCESS|MONTHLY_LIMIT|UNKNOWN"}`。
     * - ok=true + count=0：净化后无候选（不弹提示，与 Kotlin 现状一致）
     * - ok=false + reason=MONTHLY_LIMIT：本月招募已达上限（弹上限通知）
     * - ok=false + reason=UNKNOWN：异常兜底（调用方回退 Kotlin 实现）
     * 引擎未初始化返回 ok=false + reason=UNKNOWN。
     */
    external fun nativeRecruitAllFromList(): ByteArray

    /** 导出自上次导出以来的变更集（JSON；UI 镜像增量同步） */
    external fun nativeExportDirty(): ByteArray

    // ============================================================
    // 引擎循环 + 看门狗（游戏循环入 C++）
    // ============================================================

    /** 看门狗判定码（C++ ProgressMonitor 数值码；-1 = 引擎未初始化） */
    const val VERDICT_HEALTHY = 0
    const val VERDICT_LOOP_STALLED = 1
    const val VERDICT_FAKE_RUN_DETECTED = 2
    const val VERDICT_PAUSED_BY_OWNER = 3
    const val VERDICT_STALE_PAUSE_DETECTED = 4

    /**
     * 循环启动/重启：帧累积清零 + 时钟基准重置（prepareLoopStart 调用）。
     * kAnyThread 生命周期端口：startGameLoop 可从主线程调用（前台服务/
     * Activity 后台恢复），Kotlin 状态机保证进入时循环非运行态。
     */
    external fun nativeLoopStart()

    /**
     * 速度切换（真相源同步）：C++ PhaseClock 按旧速度结算累积（切换零丢失）。
     * 经 [com.xianxia.sect.core.engine.system.GameTimeClock.onSpeedChanged] 钩子
     * 自动推送，UI 直接调 gameClock.setSpeed 不感知本通道。
     */
    external fun nativeLoopSetSpeed(speed: Int)

    /**
     * 单帧迭代计划（AUTHORITATIVE 帧驱动入口）：C++ EngineLoop 消费墙钟 →
     * 帧累积/逻辑步进/时间消费/心跳全部在 native 真相源完成，返回本帧执行指令。
     *
     * @param pausedOrLoading isPaused || isLoading（暂停分支：死区消费 + 累积清零）
     * @param isSaving 保存中（tick 级跳过：不推进计数、消费死区）
     * @return 17 槽 LongArray（[NativeLoopPlan.unpack]；引擎未初始化返回空数组）
     */
    external fun nativeLoopFrame(pausedOrLoading: Boolean, isSaving: Boolean): LongArray

    /** 消耗死区时间（异常恢复/暂停阻塞路径；刷新基准不累积） */
    external fun nativeLoopConsumeDeadTime()

    /** 归还已消费旬数（native 链路失败整批回滚；对应 gameClock.refundPhases） */
    external fun nativeLoopRefundPhases(count: Int)

    /** 用户活跃通知（输入端口：onUserActivity → C++ idleNs 维护） */
    external fun nativeLoopNotifyUserActivity()

    /**
     * 循环紧急重启（换线程）：帧状态清零（performEmergencyRestart 调用）。
     * kAnyThread 生命周期端口：看门狗/主线程/闹钟兜底均可调用；同时置位
     * C++ 侧 owner 重锚标志，由新驱动线程的首个 nativeLoopFrame 消费。
     */
    external fun nativeLoopOnRestart()

    /**
     * 看门狗统一判据（AUTHORITATIVE 真相源判据）：引擎侧状态
     * （tickCount/totalPhases/accumulatedGameMs/speed/loopActiveAtMs）由 C++
     * 组合，平台侧运行态（暂停/保存/加载/秘境租约）由本参数传入。
     *
     * @return [VERDICT_*] 判定码；-1 = 引擎未初始化（调用方回退 Kotlin 判据）
     */
    external fun nativeWatchdogVerdict(
        loopActive: Boolean,
        isPaused: Boolean,
        isSaving: Boolean,
        isLoading: Boolean,
        secretRealmPauseLock: Boolean,
        secretRealmPauseRenewedAtMs: Long
    ): Int

    /**
     * 热控状态推送（平台能力接口化：Kotlin ThermalMonitor 轮询 → C++ Settable 端口）。
     * severity 数值码：0=None 1=Light 2=Moderate 3=Severe 5=Emergency（C++ ThermalState）
     */
    external fun nativeLoopSetThermalStatus(severity: Int)

    /** 电量状态推送（BatteryAwareController 语义镜像 → C++ Settable 端口） */
    external fun nativeLoopSetBatteryStatus(
        isLowBattery: Boolean,
        isPowerSaveMode: Boolean,
        fpsCap: Int,
        thermalThresholdOffsetC: Float
    )

    /**
     * 运行时游戏配置注入（Kotlin GameConfigProvider →
     * C++ 全局 GameConfig，消除库存容量/执法堂配置双端漂移）。
     * 引擎初始化后调用（引擎线程串行）；参数与 Kotlin
     * GameConfigData.WarehouseSection / LawEnforcementSection 字段一一对应。
     */
    @Suppress("LongParameterList")  // JNI 标量通道：16 个配置字段与 C++ GameConfig 一一对应
    external fun nativeSetGameConfig(
        warehouseBaseCapacity: Int,
        warehouseCapacityPerBuilding: Int,
        lawLoyaltyThreshold: Int,
        lawMoralityThreshold: Int,
        lawHerdLoyaltyThreshold: Int,
        lawProbPerPoint: Double,
        lawMaxProb: Double,
        lawBaseCaptureRate: Double,
        lawIntelligenceBase: Int,
        lawElderBonusPerPoint: Double,
        lawDiscipleIntelligenceStep: Int,
        lawDiscipleBonusPerStep: Double,
        lawReflectionYears: Int,
        lawNewDiscipleProtectionMonths: Int,
        lawMaxTheftPerYear: Int,
        lawMaxTheftJudgementsPerMonth: Int
    )

    // ============================================================
    // 渲染合成器通道（道路逐格合成单一权威）
    // ============================================================

    /**
     * 单格道路绘制操作合成（gamecore/map/road_compositor.h 单一权威）。
     *
     * 无状态纯函数——不依赖引擎实例，可在 nativeInit 前调用（仅需库已加载）。
     *
     * @param mask 4-bit 邻接掩码（0 调用方应跳过；返回仍为主体 1 op）
     * @param tileSize 格像素尺寸（运行时恒为 GameConfig.TILE_SIZE=48，
     *   4 的倍数下整型几何与 Vulkan 浮点路径逐位一致）
     * @return 扁平 IntArray：[sprite, x, y, w, h] × N——sprite 序 =
     *   RoadSprite 枚举序 = ROAD_RECTS 声明序（RoadCompositorBridge.SPRITE_KEYS
     *   下标）；x/y/w/h 为格内局部整型像素（十字中心装饰可为负/外溢）
     */
    external fun nativeRoadCompose(mask: Int, tileSize: Int): IntArray

    /**
     * 浮空岛崖壁布局合成（纯函数，kAnyThread——与 [nativeRoadCompose] 同语义，
     * 不依赖引擎实例；仅需库已加载）。
     *
     * 布局合成单一权威 = `gamecore/map/island_cliff.h`。崖壁走**独立纹理**，故输出
     * 携带纹理下标 + 逐条目 UV + 镜像位，不依赖图集精灵索引与全局 UV 表。
     *
     * @param textureSizes 纹理尺寸表 [w, h] × N（序 = IslandCliffTextureSet 下标序）
     * @param poolBase 每池在 [poolFlat] 中的起始偏移（[IslandCliffBridge.POOL_COUNT] 个）
     * @param poolCount 每池数量（[IslandCliffBridge.POOL_COUNT] 个）
     * @param poolFlat 池平铺表（元素 = 纹理下标）
     * @param topInset 岛面顶线内缩（像素；0 = 素材顶边贴地图边）
     * @param bottomStartRatio 下环起点（占左下角纹理宽的比例，自其内缘向内）
     * @param bottomEndRatio 下环终点（占右下角纹理宽的比例）
     * @param textureMask 纹理上传位掩码（bit i = 纹理 i 可用；不可用则不产出该条目）
     * @return 扁平浮点数组 [texIdx, x, y, w, h, u0, v0, u1, v1, flags] × N
     */
    // JNI external 声明必须与 C++ 函数签名 1:1 平铺（参数分组破坏 JNI 映射）——
    // LongParameterList 抑制为声明性豁免（同 drawAllTiles 惯例）
    @Suppress("LongParameterList")
    external fun nativeIslandCliffCompose(
        cols: Int,
        rows: Int,
        tileSize: Int,
        seed: Int,
        textureSizes: FloatArray,
        poolBase: IntArray,
        poolCount: IntArray,
        poolFlat: IntArray,
        topInset: Float,
        bottomStartRatio: Float,
        bottomEndRatio: Float,
        textureMask: Int
    ): FloatArray

    /**
     * 宗门地图地形生成（地形生成单一权威 = gamecore/map/terrain.h，
     * Kotlin SectMapTileGenerator 的位级等价移植）。
     *
     * 无状态纯函数——不依赖引擎实例，kAnyThread（同 [nativeRoadCompose]；
     * 生产调用方 SectTerrainBridge 在 Dispatchers.Default）。门楼常量按
     * GameConfig.SectMap 传值（单一数据源不落 C++）。
     *
     * @param density 总装饰密度（SectMapTileGenerator 生产默认 0.18f）
     * @return 行主序展平瓦片数组（row*width+col = 格值），size = width*height；
     *         width/height 非法返回 null
     */
    // LongParameterList：门楼盒 5 常量平铺传参（JNI 声明惯例，同上）
    @Suppress("LongParameterList")
    external fun nativeGenerateSectTerrain(
        seed: Int,
        width: Int,
        height: Int,
        density: Float,
        borderTreeRing: Int,
        gateX: Int,
        gateY: Int,
        gateWidth: Int,
        gateHeight: Int,
        gateSpriteY: Int
    ): IntArray
}

/**
 * nativeLoopFrame 帧计划（C++ `system::LoopFramePlan` 17 槽 LongArray 解包；
 * 槽位协议与 engine_loop.h 注释同源）：
 * [0] paused · [1] tickCount · [2..6] tickKind(1=active/0=isSaving 跳过) ·
 * [7..11] tickPhases · [12] alpha 位模式 · [13] frameDeltaNs ·
 * [14] idleNs(<0=从未活跃) · [15] tickTotal · [16] accumulatedGameMs
 */
class NativeLoopPlan(
    val paused: Boolean,
    val tickCount: Int,
    /** 每 tick 类型：1=正常执行 0=isSaving 跳过 */
    val tickKind: IntArray,
    /** 每 tick 应推进旬数（0=时间不足一旬） */
    val tickPhases: IntArray,
    /** 插值因子原始值（JitterSmoother 滤波在 Kotlin 渲染侧） */
    val alpha: Float,
    /** 本帧实际间隔（钳制后；ADPF 上报输入） */
    val frameDeltaNs: Long,
    /** 距上次用户活跃纳秒（<0 = 从未活跃） */
    val idleNs: Long,
    /** 累计逻辑 tick 计数（Kotlin _tickCount 镜像真相源） */
    val tickTotal: Long,
    /** 当前旬内累积游戏毫秒（GameTimeClock 镜像推送源） */
    val accumulatedGameMs: Long
) {
    companion object {
        /** 解包 17 槽 LongArray；长度不符（引擎未初始化等）返回 null */
        fun unpack(raw: LongArray): NativeLoopPlan? {
            if (raw.size != 17) return null
            return NativeLoopPlan(
                paused = raw[0] != 0L,
                tickCount = raw[1].toInt(),
                tickKind = IntArray(5) { raw[2 + it].toInt() },
                tickPhases = IntArray(5) { raw[7 + it].toInt() },
                alpha = Float.fromBits(raw[12].toInt()),
                frameDeltaNs = raw[13],
                idleNs = raw[14],
                tickTotal = raw[15],
                accumulatedGameMs = raw[16]
            )
        }
    }
}

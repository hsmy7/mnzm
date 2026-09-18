#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/core/platform.h"
#include "gamecore/ecs/job_system.h"
#include "gamecore/ecs/system.h"
#include "gamecore/ecs/world.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/column_dirty.h"
#include "gamecore/state/dirty_tracker.h"
#include "gamecore/state/models.h"
#include "gamecore/system/ai_sect_ops.h"
#include "gamecore/system/engine_loop.h"
#include "gamecore/system/settlement.h"
#include "gamecore/system/watchdog.h"

// ============================================================
// GameCore — 游戏引擎门面
//
// 架构定位：C++ 引擎的**真相源**。Kotlin 侧经 JNI 桥调用本类
// （GameCoreBridge.cpp），方法语义对应 Kotlin GameEngineCore/GameEngine：
//   - advance      ↔ Kotlin 帧循环 tickInternal（100ms 逻辑步）
//   - execute      ↔ Kotlin GameEngine 业务操作（ActionId 协议；经 execute_dispatch 分发表）
//   - export/import↔ 全量状态快照（JSON）
//   - exportDirty  ↔ 变更集增量同步（见 DirtyTracker）
//
// 线程契约：单线程使用（所有调用来自 Kotlin 引擎线程，Kotlin 侧已串行化），
// 内部无锁。与现有"双线程模型 + ReentrantLock 串行化"契约对齐。
// ============================================================
namespace gamecore {

/// 引擎初始化配置
struct GameCoreConfig {
    /// 快照 schema 版本标记（与 Kotlin 快照编解码版本对应）
    std::string snapshotSchemaVersion = "0.1.0";
    /// 系统种子（对应 Kotlin initSystemSeed；新档由创建入口传入）
    int64_t systemSeed = 0;
    /// 是否已初始化种子（false 时 advance 前必须 init）
    bool seedInitialized = false;
    /// AUTHORITATIVE 模式：true 时每旬只跑核心结算（时间推进 +
    /// 步骤 1-5 零 RNG 批量，onCoreSettle），月/年边界仅记录标志不触发
    /// 结算钩子——由 Kotlin 残留执行器（丹药/突破/月变/年变完整编排）处理，
    /// 保证结算行为零丢失。默认 false（shadow 对拍/diff 测试语义不变）。
    bool authoritativeTickMode = false;

    // ── 地图冻结（WS-5b）地形生成参数 ──
    // 单一数据源 = Kotlin GameConfig.SectMap（经 nativeInit 传入，不落 C++
    // 硬编码，§2.19 口径）；width/height<=0 表示未配置 ⇒ ensureTerrainGenerated
    // 跳过（桌面最小测试面零影响）。
    int32_t terrainWidthCells = 0;
    int32_t terrainHeightCells = 0;
    float terrainDecorationDensity = 0.0f;
    int32_t terrainBorderTreeRing = 0;
    int32_t terrainGateX = 0;
    int32_t terrainGateY = 0;
    int32_t terrainGateWidth = 0;
    int32_t terrainGateHeight = 0;
    /// 当前生成器版本戳（生成回填时写入 GameData.mapGenVersion；Kotlin 传入）
    int32_t terrainMapGenVersion = 0;
};

/// 平台能力提供者集合（Clock/Telemetry/热控/电量注入）。
/// 空指针保留默认实现（SteadyMonotonicClock / NullTelemetry / 不降载档位）。
struct PlatformProviders {
    MonotonicClock* monotonicClock = nullptr;      // 引擎循环时间源（elapsedRealtime 语义）
    TelemetrySink* telemetry = nullptr;            // 循环/看门狗遥测事件
    ThermalStatusProvider* thermal = nullptr;      // 热状态（遥测记录；判据消费留 Kotlin）
    BatteryStatusProvider* battery = nullptr;      // 电量状态（同上）
};

/// 看门狗判定输入 flags（Kotlin 平台侧运行态；引擎侧状态由 GameCore 组合）
struct WatchdogFlags {
    bool loopActive = false;
    bool isPaused = false;
    bool isSaving = false;
    bool isLoading = false;
    bool secretRealmPauseLock = false;
    int64_t secretRealmPauseRenewedAtMs = 0;
};

/// 游戏引擎门面
class GameCore {
public:
    GameCore(Clock* clock, Logger* logger);
    ~GameCore() = default;

    // 禁止拷贝（持有资源与状态）
    GameCore(const GameCore&) = delete;
    GameCore& operator=(const GameCore&) = delete;

    // ── 生命周期 ──────────────────────────────────────────
    /// 初始化（注入配置；幂等——重复调用返回 false 并记日志）
    bool initialize(const GameCoreConfig& config);
    /// 关闭（释放资源；幂等）
    void shutdown();

    bool isInitialized() const { return initialized_; }

    // ── 逻辑 tick ─────────────────────────────────────────
    /// 推进墙钟增量（对应 Kotlin 帧循环 tickInternal → GameTimeClock.tick）：
    /// wallDeltaMs 为自上次 tick 的墙钟毫秒增量（由 Kotlin 桥传入，保证对拍可控），
    /// 内部按 msPerPhase=2000ms@1x × speed 换算旬数，单 tick 上限 3×speed 旬
    bool advance(int64_t wallDeltaMs, int64_t nowMs);

    /// 直接推进 N 旬（绕过 accumulator；对拍/测试用）
    system::TickResult advancePhases(int phaseCount);

    /// 单旬推进（AUTHORITATIVE tick 标量通道）：时间推进 + 每旬结算钩子
    /// （core 模式 = onCoreSettle），返回 kSettleFlag* 边界标志位组合；
    /// 月/年结算由 Kotlin 残留执行器按标志处理。未初始化返回 0。
    int settleOnePhase();

    /// 单月推进（月变真相源切换新增）：直接执行完整月变结算
    /// （runMonthSettlement——八步事务编排 + 十六子事件已下沉面），返回
    /// JSON 信封（MonthSettlementResult：policyCosts.disabledPolicies +
    /// secretRealmClose 草稿 + purchaseLogs 草稿）供 Kotlin 残留
    /// 执行器消费平台效应（checkpointAllProduction/秘境邮件与 gate/
    /// lifeEvents 日志）。未初始化返回空对象。
    std::string settleMonth();

    /// 重置自动招募惰性门（Kotlin 侧重置点——年度招募刷新/玩家
    /// 改筛选/生育/净化——经 JNI 通知 C++ 复位 autoRecruitIdle，防月变真相源
    /// 切换后 C++ 侧 autoRecruit 永久惰性）。未初始化忽略。
    void resetAutoRecruitIdle();

    /// 单年推进（年变真相源切换）：直接执行完整年变结算
    /// （runYearSettlement——T1 已下沉面 + T2 已下沉面 + 年报 + 年俸），返回
    /// JSON 信封（当前为空——年变残留执行器（死亡链/招募生成/AI 招募/商人
    /// 收购/交易刷新）为 Kotlin 侧纯状态 + 平台效应，无 C++ 草稿回传）。
    /// 未初始化返回空对象。
    std::string settleYear();

    // ── RNG 分区标量通道（Kotlin 抽取委托单一真相源） ──────────
    /// 指定分区抽取下一个 32 位整数（PCG-XSH-RR 原始输出，与 Kotlin
    /// DeterministicRng.nextInt() 逐位一致）；非法分区返回 0 并记日志。
    int32_t rngNextInt(int partitionId);
    /// 读取分区当前状态（对应 Kotlin DeterministicRng.snapshot()）
    int64_t rngSnapshotPartition(int partitionId);
    /// 写入分区状态（对应 Kotlin DeterministicRng.restore(state)）；
    /// 非法分区忽略并记日志。成功返回 true。
    bool rngRestorePartition(int partitionId, int64_t state);
    /// 重置系统种子（新档 initSystemSeed 对应；各分区以 seed+partitionId 重播）
    void rngInitSystemSeed(int64_t seed);

    /// 结算引擎访问器（系统钩子经此注册）
    system::SettlementEngine& settlement() { return settlement_; }

    // ── 引擎循环 + 看门狗（游戏循环入 C++） ────────
    /// 平台能力注入（nativeInit 后由桥层调用；幂等可重设）
    void setPlatformProviders(const PlatformProviders& providers);
    /// AUTHORITATIVE 引擎循环（墙钟消费/速度/暂停/refund 状态机 + 帧迭代计划）
    system::EngineLoop& loop() { return loop_; }
    const system::EngineLoop& loop() const { return loop_; }
    /// 看门狗统一判据：引擎侧状态（tickCount/totalPhases/accumulatedGameMs/
    /// speed/loopActiveAtMs）由 GameCore 组合，平台侧 flags 由调用方传入。
    /// 返回 system::StallVerdict 数值码（0=Healthy/1=LoopStalled/
    /// 2=FakeRunDetected/3=PausedByOwner/4=StalePauseDetected）。
    int watchdogVerdict(const WatchdogFlags& flags);

    // ── 业务操作 ──────────────────────────────────────────
    /// 执行业务操作（ActionId 协议；paramsJson 为参数 JSON）
    /// 返回结果 JSON 字节（sealed 结果语义）。
    std::string execute(int32_t actionId, const std::string& paramsJson, int64_t nowMs);

    // ── 状态快照（JSON 全量导出/导入）────────────────────────────
    /// 导出全量状态快照（JSON；存档前/读档后全量同步调用）
    std::string exportStateJson();
    /// 导入全量状态快照（读档调用）；宽松解析（未知字段忽略）；
    /// 读档后从快照恢复 RNG 分区状态并重置变更集基线
    bool importStateJson(const std::string& json);
    /// 导入全量状态快照但不恢复 RNG 分区（AUTHORITATIVE 每旬回导用：
    /// 委托模式下 native RNG 即真相源，镜像 rngStates 可能滞后于残留执行器
    /// 的抽取——恢复会把分区回卷导致跨语言漂移）
    bool importStateJsonNoRng(const std::string& json);
    /// 导出自上次导出以来的变更集（增量同步协议，见 DirtyTracker）
    std::string exportDirtyJson();

    // ── 镜像通道传输编码（重构方案 R2.2：JSON → protobuf 换轨）──────
    /// 导出变更集 GameView protobuf 信封（schema 见
    /// core/engine/src/main/proto/game_view.proto；编码器 gameview_encode.h）。
    /// 与 [exportDirtyJson] 同一 diffToTree 源（同一版本号/同一基线消费语义，
    /// 双格式逐值等价）——同一封不可两种格式各导一次（导出即消费）。
    /// 未初始化/异常返回合法的空信封字节（消费端零写入快速路径）。
    std::string exportDirtyProto();
    /// nativeExportDirty 传输编码开关（true = protobuf 信封；false = 旧 JSON
    /// 文本）。缺省 false = 旧格式（跨版本回滚安全缺省）；生产由 Kotlin
    /// NativeEngineFlag.mirrorProtobufTransport 经 SET_DIRTY_EXPORT_FORMAT
    /// 动作驱动（灰度开关，新旧共存一个版本周期）。仅影响 [exportDirty]
    /// 分发——两格式导出能力本身恒并存（对拍/回退面不动）。
    void setDirtyExportProtobuf(bool on) { dirtyExportProtobuf_ = on; }
    bool dirtyExportProtobuf() const { return dirtyExportProtobuf_; }

    /// ── 列级增量导出开关（重构方案 R2.4/B09：R1.4 列级写屏障接生产）──
    /// true = exportDirtyProto 优先走 ColumnDirtyTracker 整树导出（弟子域
    /// 仅脏行×脏列；gameData/集合域与全量 diff 共享同一比对段，构造等价）；
    /// false = 全量树 diff（对拍显式依赖的全量模式开关，零漂移缺省）。
    /// 生产由 Kotlin NativeEngineFlag.dirtyColumnExport 驱动（新增引擎控制
    /// 端口，登记豁免——与 setDirtyExportProtobuf 同族）。**月/年/旬边界
    /// 之外的异构写入路径（业务事务/战斗/招募）自动锁存回退全量导出一封**
    /// （[noteNonSettlementMutation]），防列屏障未覆盖路径漏报。
    void setDirtyExportColumn(bool on) { columnLevelDirtyExport_ = on; }
    bool dirtyExportColumn() const { return columnLevelDirtyExport_; }
    /// 异构路径锁存：任何不经结算边界（settleOnePhase/settleMonth/settleYear）
    /// 的状态写入路径（业务事务/战斗/招募等）调用后，下一封导出回退全量
    /// 树 diff（该封之后恢复列级——全量封已携带全部变更，位图同时清零）。
    void noteNonSettlementMutation() { columnExportBlocked_ = true; }

    /// 按 [setDirtyExportProtobuf]/[setDirtyExportColumn] 模式导出变更集
    ///（nativeExportDirty JNI 面唯一入口；JNI 签名不变、仅输出编码换轨）
    std::string exportDirty();

    /// 列级增量树导出，JSON 文本（对拍守卫专用）：仅消费
    /// ColumnDirtyTracker（位图/非弟子域基线），不触碰 DirtyTracker 基线、
    /// 异构锁存与 eventFeed 队列——与 [exportDirtyJson] 组成"同写集双臂
    /// 对照"（先列级后全量，两封分别消费各自追踪器；全量封恒携带自上次
    /// 全量导出以来的全部变更，列级封只携写屏障标脏面）。
    std::string exportDirtyColumnJson();

    /// 手动招募单招（Kotlin DiscipleFacadeImpl.recruitDiscipleFromList 等价下沉
    /// ——AUTHORITATIVE 单真相源，与自动招募同侧；循环外任意时刻调用，状态
    /// 变化经下一 tick 前向 diff 推送镜像）。返回 JSON 信封：
    /// `{"ok":bool,"newId":string,"age":int,"reason":"SUCCESS|MONTHLY_LIMIT|
    ///   NOT_FOUND|CORRUPTED|UNKNOWN"}`——reason 供 Kotlin 组装用户提示，
    /// age 供镜像补写 lifeEvents（Kotlin 类体属性，不进协议）。
    /// 未初始化/异常返回 ok=false + reason=UNKNOWN。
    std::string manualRecruitFromList(const std::string& discipleId);
    /// 一键招募全部（Kotlin GameEngine.recruitAllFromList 等价下沉）。返回
    /// `{"ok":bool,"count":int,"reason":...}`——count 为成功招募数；reason=SUCCESS
    /// 时可能 count=0（净化后无候选，Kotlin 现状不弹提示）；
    /// reason=MONTHLY_LIMIT 时 Kotlin 弹上限通知。
    std::string manualRecruitAll();

    // ── 状态访问（供系统实现使用；单线程契约） ────────────────────
    state::GameState& state() { return state_; }
    const state::GameState& state() const { return state_; }

    // ── 访问器 ────────────────────────────────────────────
    rng::RngManager& rng() { return rng_; }
    const rng::RngManager& rng() const { return rng_; }

    /// AI 宗门独立分区 RNG（Kotlin AISectDiscipleManager.rng 等价——
    /// 种子 systemSeed + AI_SECT.id(6) × 31337，initForSlot 语义；读档从
    /// GameData.mapSeed 重播）。AI 弟子生成/招募/演化专用。
    ///
    /// 归档通道：经 [mirrorAiRng] 把状态镜像到 `kAiSectMirror`(9) 分区，
    /// 随 `rngStates` 落盘 → AI 演化**读档可续接**（本流与分区 `kAiSect`(6) 是
    /// 不同种子、不同序列的独立流，不做合并——合并会改 AI 演化行为基线）。
    rng::DeterministicRng& aiRng() { return aiRng_; }

    /// 把 aiRng_ 当前状态镜像到 kAiSectMirror 分区。
    /// 必须在任何 `syncRngStates()`（导出 `rngStates`）之前调用，否则导出的键 9
    /// 是陈旧值；读档时若存档含键 9 则以其覆盖 aiRng_（续接归档态），
    /// 无键（旧档）则保持 `mapSeed + 6×31337` 播种——与既有行为逐位一致。
    void mirrorAiRng() {
        rng_.getRng(rng::RngPartition::kAiSectMirror).restore(aiRng_.snapshot());
    }

    /// 推送 AI 热控批量上界（Kotlin ThermalMonitor 平台决策——12/6/3）
    void setAiThermalBatchSize(int32_t batchSize) {
        if (batchSize > 0) aiMonthBatch_.thermalBatchSize = batchSize;
    }

    /// 热控分批内存态访问（测试/桥层）
    system::ai_ops::AiMonthBatchState& aiMonthBatch() { return aiMonthBatch_; }

    /// 月/年结算迭代域经本对象持久实体集行序
    /// 桥接（syncDiscipleEntities 校验/恢复）——测试直接复用同一实体集，
    /// 与生产钩子（onMonthChange/onYearChange）同语义。
    ecs::World& ecsWorld() { return ecsWorld_; }

    Clock* clock() const { return clock_; }
    Logger* logger() const { return logger_; }

private:
    Clock* clock_ = nullptr;    // 注入（不持有）
    Logger* logger_ = nullptr;  // 注入（不持有）
    bool initialized_ = false;
    rng::RngManager rng_;
    rng::DeterministicRng aiRng_;      // AI 宗门独立 RNG（独立播种）
    // AI 热控分批内存态（computeAIBatch 状态机 + Kotlin 推送的热档上界；
    // 纯内存运行态，不入存档协议，读档不重置——Kotlin
    // AISectBattleProcessor 成员语义同源：lastSettleMonth 由首调相位对齐）
    system::ai_ops::AiMonthBatchState aiMonthBatch_;
    state::GameState state_;    // 游戏状态真相源
    system::SettlementEngine settlement_;  // 惰性结算引擎
    // AUTHORITATIVE core 模式每旬核心批次经 ECS System 调度 +
    // JobSystem 并行化（消除 5000 弟子单线程 O(D) 热点）。jobs_ 惰性创建
    //（仅 core 模式），故非 core 测试/降级路径零线程开销。
    ecs::World ecsWorld_;
    ecs::SystemScheduler ecsScheduler_;
    std::unique_ptr<ecs::JobSystem> jobs_;
    system::EngineLoop loop_;              // 引擎循环（AUTHORITATIVE 真相源）
    system::ProgressMonitor progressMonitor_;  // 看门狗统一判据
    BatteryStatusProvider* batteryProvider_ = nullptr;  // 注入（不持有；访问器暴露）
    state::DirtyTracker dirtyTracker_;     // 变更集追踪

    /// 把 RNG 分区当前状态回写进 gameData.rngStates（导出/变更集前调用，
    /// 保证镜像与存档拿到最新确定性状态）
    void syncRngStates();
    /// 导入内部实现（restoreRng=false 时跳过 RNG 恢复——AUTHORITATIVE 回导用）
    bool importStateInternal(const std::string& json, bool restoreRng);
    /// 地图冻结（WS-5b）"生成即数据"：无地形段 ⇒ 按 mapSeed + 初始化时传入的
    /// 地形配置生成并落为权威数据（有段恒优先不重算；未配置地形/无种子则跳过）。
    /// 生成零 RNG（seed+坐标纯函数），调用点位于 resetBaseline 之前 ⇒
    /// 生成段计入导入基线，前向/反向镜像零载荷。
    void ensureTerrainGenerated();
    /// initialize(config) 的配置留存（ensureTerrainGenerated 消费地形参数）
    GameCoreConfig config_;
    /// nativeExportDirty 传输编码模式（R2.2 灰度开关，缺省 false = 旧 JSON）
    bool dirtyExportProtobuf_ = false;
    /// 列级增量导出模式（R2.4/B09；缺省 false = 全量树 diff，零漂移缺省）
    bool columnLevelDirtyExport_ = false;
    /// 异构写入锁存（[noteNonSettlementMutation]；导出后消费清零）
    bool columnExportBlocked_ = false;
    /// 列级写屏障追踪器（R1.4 能力 + B09 挂载：与 dirtyTracker_ 同点
    /// resetBaseline、同点全量导出清位图）
    state::ColumnDirtyTracker columnTracker_;
    /// 月/年结算边界粗粒度列标脏（审计列集全行标脏——月/年级频率，
    /// 宁多标不漏标；phase 路径为写点级精确标脏不经此）
    void markMonthYearBoundaryColumns();
};

}  // namespace gamecore

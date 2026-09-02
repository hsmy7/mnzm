#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/core/platform.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/dirty_tracker.h"
#include "gamecore/state/models.h"
#include "gamecore/system/engine_loop.h"
#include "gamecore/system/settlement.h"
#include "gamecore/system/watchdog.h"

// ============================================================
// GameCore — 游戏引擎门面（Kotlin→C++ 迁移批次 0 骨架）
//
// 架构定位：C++ 引擎的**真相源**。Kotlin 侧经 JNI 桥调用本类
// （GameCoreBridge.cpp），方法语义对应 Kotlin GameEngineCore/GameEngine：
//   - advance      ↔ Kotlin 帧循环 tickInternal（100ms 逻辑步）
//   - execute      ↔ Kotlin GameEngine 业务操作（ActionId 协议，批次 1+ 填充）
//   - export/import↔ 全量状态快照（JSON，批次 1 填充）
//   - exportDirty  ↔ 变更集增量同步（批次 1 填充）
//   - pollEvents   ↔ 事件队列回传（批次 1 填充）
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
    /// T2.4 AUTHORITATIVE 过渡模式：true 时每旬只跑核心结算（时间推进 +
    /// 步骤 1-5 零 RNG 批量，onCoreSettle），月/年边界仅记录标志不触发
    /// 结算钩子——由 Kotlin 残留执行器（丹药/突破/月变/年变完整编排）处理，
    /// 保证未迁移系统行为零丢失。默认 false（shadow 对拍/diff 测试语义不变）。
    bool authoritativeTickMode = false;
};

/// 平台能力提供者集合（计划 v2 阶段 5：Clock/Telemetry/热控/电量注入）。
/// 空指针保留默认实现（SteadyMonotonicClock / NullTelemetry / 不降载档位）。
struct PlatformProviders {
    MonotonicClock* monotonicClock = nullptr;      // 引擎循环时间源（elapsedRealtime 语义）
    TelemetrySink* telemetry = nullptr;            // 循环/看门狗遥测事件
    ThermalStatusProvider* thermal = nullptr;      // 热状态（遥测记录；判据消费阶段 6+）
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

    /// 单旬推进（T2.4 AUTHORITATIVE tick 标量通道）：时间推进 + 每旬结算钩子
    /// （core 模式 = onCoreSettle），返回 kSettleFlag* 边界标志位组合；
    /// 月/年结算由 Kotlin 残留执行器按标志处理。未初始化返回 0。
    int settleOnePhase();

    /// 单月推进（月变真相源切换批 M-1 新增）：直接执行完整月变结算
    /// （runMonthSettlement——八步事务编排 + 十六子事件已下沉面），返回
    /// JSON 信封（MonthSettlementResult：policyCosts.disabledPolicies +
    /// secretRealmClose 草稿 S-17 + purchaseLogs 草稿 S-20）供 Kotlin 残留
    /// 执行器消费平台效应（checkpointAllProduction/秘境邮件与 gate/
    /// lifeEvents 日志）。未初始化返回空对象。
    std::string settleMonth();

    /// 重置自动招募惰性门（S-16 清偿：Kotlin 侧重置点——年度招募刷新/玩家
    /// 改筛选/生育/净化——经 JNI 通知 C++ 复位 autoRecruitIdle，防月变真相源
    /// 切换后 C++ 侧 autoRecruit 永久惰性）。未初始化忽略。
    void resetAutoRecruitIdle();

    /// 单年推进（年变真相源切换批 Y-switch）：直接执行完整年变结算
    /// （runYearSettlement——T1 已下沉面 + T2 已下沉面 + 年报 + 年俸），返回
    /// JSON 信封（当前为空——年变残留执行器（死亡链/招募生成/AI 招募/商人
    /// 收购/交易刷新）为 Kotlin 侧纯状态 + 平台效应，无 C++ 草稿回传）。
    /// 未初始化返回空对象。
    std::string settleYear();

    // ── RNG 分区标量通道（T2.4：Kotlin 抽取委托单一真相源） ──────────
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

    /// 结算引擎访问器（批次 4+ 注册系统钩子用）
    system::SettlementEngine& settlement() { return settlement_; }

    // ── 引擎循环 + 看门狗（计划 v2 阶段 5：游戏循环入 C++） ────────
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
    /// 返回结果 JSON 字节（含 sealed 结果语义；批次 1+ 实现，当前返回未实现错误）
    std::string execute(int32_t actionId, const std::string& paramsJson, int64_t nowMs);

    // ── 状态快照（批次 1 实现：JSON 全量导出/导入）────────────────
    /// 导出全量状态快照（JSON；存档前/读档后全量同步调用）
    std::string exportStateJson();
    /// 导入全量状态快照（读档调用）；宽松解析（未知字段忽略）；
    /// 读档后从快照恢复 RNG 分区状态（C-13）并重置变更集基线
    bool importStateJson(const std::string& json);
    /// 导入全量状态快照但不恢复 RNG 分区（T2.4 AUTHORITATIVE 每旬回导用：
    /// 委托模式下 native RNG 即真相源，镜像 rngStates 可能滞后于残留执行器
    /// 的抽取——恢复会把分区回卷导致跨语言漂移）
    bool importStateJsonNoRng(const std::string& json);
    /// 应用 Kotlin 侧反向增量变更集（计划 v2 阶段 3：取代 AUTHORITATIVE 每旬
    /// 全量回导）。协议见 applyReverseDirty 实现——{version, changed, removed}，
    /// changed 含 gameData（全量，不含 rngStates）/disciples/实体集合 upsert，
    /// removed 含按 id 删除。应用成功返回 true 并同步 DirtyTracker 基线；
    /// 版本乱序/解析失败返回 false（调用方降级全量回导）。
    bool applyReverseDirty(const std::string& dirtyJson);
    /// 导出自上次导出以来的变更集（增量同步协议，见 DirtyTracker——阶段 1 实现）
    std::string exportDirtyJson();
    /// 导出事件队列（JSON；Kotlin 侧 poll 消费——批次 3+ 实现）
    std::string pollEventsJson();

    // ── 状态访问（供系统实现使用；单线程契约） ────────────────────
    state::GameState& state() { return state_; }
    const state::GameState& state() const { return state_; }

    // ── 访问器 ────────────────────────────────────────────
    rng::RngManager& rng() { return rng_; }
    const rng::RngManager& rng() const { return rng_; }

    /// AI 宗门独立分区 RNG（批 Y-4c：Kotlin AISectDiscipleManager._rng 等价——
    /// 种子 systemSeed + AI_SECT.id(6) × 31337，initForSlot 语义；读档从
    /// GameData.mapSeed 重播）。AI 弟子生成/招募专用，不入 rngStates 分区。
    rng::DeterministicRng& aiRng() { return aiRng_; }

    Clock* clock() const { return clock_; }
    Logger* logger() const { return logger_; }

private:
    Clock* clock_ = nullptr;    // 注入（不持有）
    Logger* logger_ = nullptr;  // 注入（不持有）
    bool initialized_ = false;
    rng::RngManager rng_;
    rng::DeterministicRng aiRng_;      // AI 宗门独立 RNG（批 Y-4c）
    state::GameState state_;    // 游戏状态真相源
    system::SettlementEngine settlement_;  // 惰性结算引擎（批次 3）
    system::EngineLoop loop_;              // 引擎循环（阶段 5：AUTHORITATIVE 真相源）
    system::ProgressMonitor progressMonitor_;  // 看门狗统一判据（阶段 5）
    BatteryStatusProvider* batteryProvider_ = nullptr;  // 注入（不持有；访问器暴露）
    state::DirtyTracker dirtyTracker_;     // 变更集追踪（计划 v2 阶段 1）
    uint64_t reverseVersion_ = 0;          // 反向增量版本（阶段 3；严格递增校验）

    /// 把 RNG 分区当前状态回写进 gameData.rngStates（导出/变更集前调用，
    /// 保证镜像与存档拿到最新确定性状态）
    void syncRngStates();
    /// 导入内部实现（restoreRng=false 时跳过 RNG 恢复——AUTHORITATIVE 回导用）
    bool importStateInternal(const std::string& json, bool restoreRng);
};

}  // namespace gamecore

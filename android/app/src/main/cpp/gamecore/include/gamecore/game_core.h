#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/dirty_tracker.h"
#include "gamecore/state/models.h"
#include "gamecore/system/settlement.h"

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

    Clock* clock() const { return clock_; }
    Logger* logger() const { return logger_; }

private:
    Clock* clock_ = nullptr;    // 注入（不持有）
    Logger* logger_ = nullptr;  // 注入（不持有）
    bool initialized_ = false;
    rng::RngManager rng_;
    state::GameState state_;    // 游戏状态真相源
    system::SettlementEngine settlement_;  // 惰性结算引擎（批次 3）
    state::DirtyTracker dirtyTracker_;     // 变更集追踪（计划 v2 阶段 1）

    /// 把 RNG 分区当前状态回写进 gameData.rngStates（导出/变更集前调用，
    /// 保证镜像与存档拿到最新确定性状态）
    void syncRngStates();
    /// 导入内部实现（restoreRng=false 时跳过 RNG 恢复——AUTHORITATIVE 回导用）
    bool importStateInternal(const std::string& json, bool restoreRng);
};

}  // namespace gamecore

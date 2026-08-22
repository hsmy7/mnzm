#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/rng/rng_manager.h"
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

    /// 结算引擎访问器（批次 4+ 注册系统钩子用）
    system::SettlementEngine& settlement() { return settlement_; }

    // ── 业务操作 ──────────────────────────────────────────
    /// 执行业务操作（ActionId 协议；paramsJson 为参数 JSON）
    /// 返回结果 JSON 字节（含 sealed 结果语义；批次 1+ 实现，当前返回未实现错误）
    std::string execute(int32_t actionId, const std::string& paramsJson, int64_t nowMs);

    // ── 状态快照（批次 1 实现：JSON 全量导出/导入）────────────────
    /// 导出全量状态快照（JSON；存档前/读档后全量同步调用）
    std::string exportStateJson();
    /// 导入全量状态快照（读档调用）；宽松解析（未知字段忽略）
    bool importStateJson(const std::string& json);
    /// 导出自上次导出以来的变更集（JSON；UI 镜像增量同步——批次 5+ 实现）
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
};

}  // namespace gamecore

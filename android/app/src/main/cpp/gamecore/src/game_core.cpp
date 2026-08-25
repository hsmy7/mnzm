#include "gamecore/game_core.h"

#include <chrono>
#include <cstdio>
#include <ctime>

#include <nlohmann/json.hpp>

#include "gamecore/state/json_codec.h"

namespace gamecore {

// ── SystemClock ────────────────────────────────────────────────────
// 跨平台兜底实现（chrono steady/system clock）。
// Android 上桥层可注入基于 System.currentTimeMillis 的精确实现；
// 对拍/测试一律注入 FixedClock。
int64_t SystemClock::nowMs() {
    return static_cast<int64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch())
            .count());
}

// ── ConsoleLogger ──────────────────────────────────────────────────
void ConsoleLogger::log(LogLevel level, const std::string& tag,
                        const std::string& message) {
    static const char* kLevelNames[] = {"D", "I", "W", "E"};
    const int idx = static_cast<int>(level);
    const char* name = (idx >= 0 && idx < 4) ? kLevelNames[idx] : "?";
    FILE* out = (level >= LogLevel::kWarn) ? stderr : stdout;
    std::fprintf(out, "[%s/%s] %s\n", name, tag.c_str(), message.c_str());
    std::fflush(out);
}

// ── GameCore ───────────────────────────────────────────────────────

GameCore::GameCore(Clock* clock, Logger* logger)
    : clock_(clock), logger_(logger) {
    if (!clock_) {
        // 防御：空指针时用系统时钟兜底（正常路径由桥层注入）
        static SystemClock fallbackClock;
        clock_ = &fallbackClock;
    }
    if (!logger_) {
        static ConsoleLogger fallbackLogger;
        logger_ = &fallbackLogger;
    }
}

bool GameCore::initialize(const GameCoreConfig& config) {
    if (initialized_) {
        logger_->log(LogLevel::kWarn, "GameCore", "initialize: already initialized, ignored");
        return false;
    }
    if (config.seedInitialized) {
        rng_.initSystemSeed(config.systemSeed);
    }
    initialized_ = true;
    logger_->log(LogLevel::kInfo, "GameCore",
                 "initialized (schema=" + config.snapshotSchemaVersion + ")");
    return true;
}

void GameCore::shutdown() {
    if (!initialized_) return;
    initialized_ = false;
    logger_->log(LogLevel::kInfo, "GameCore", "shutdown");
}

bool GameCore::advance(int64_t wallDeltaMs, int64_t nowMs) {
    if (!initialized_) return false;
    (void)nowMs;
    // 批次 3：墙钟毫秒 → GameTimeClock 等价推进 + 边界检测（系统结算钩子批次 4+ 注册）
    settlement_.advance(state_, wallDeltaMs);
    return true;
}

system::TickResult GameCore::advancePhases(int phaseCount) {
    if (!initialized_) return {};
    return settlement_.advancePhases(state_, phaseCount);
}
std::string GameCore::exportStateJson() {
    if (!initialized_) return "{}";
    try {
        return state::dumpStateJson(state_);
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("exportStateJson failed: ") + e.what());
        return "{}";
    }
}

bool GameCore::importStateJson(const std::string& json) {
    if (!initialized_) return false;
    try {
        const auto j = nlohmann::json::parse(json);
        state_ = j.get<state::GameState>();
        // 对抗性审查 A1（2026-08-22）：读档后必须复位结算引擎累积——
        // 否则旧会话残留的墙钟累积会在下一 tick 多推进旬数
        settlement_.reset();
        return true;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("importStateJson failed: ") + e.what());
        return false;
    }
}

std::string GameCore::exportDirtyJson() {
    // 批次 1 实现：变更集增量
    return R"({"version":0,"changed":{},"removed":[]})";
}

std::string GameCore::pollEventsJson() {
    // 批次 1 实现：事件队列
    return "[]";
}

}  // namespace gamecore

#include "gamecore/game_core.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ctime>

#include <nlohmann/json.hpp>

#include "gamecore/state/json_codec.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/phase_settlement.h"
#include "gamecore/system/year_settlement.h"

namespace gamecore {

// ── 反向增量应用辅助（计划 v2 阶段 3） ──────────────────────────────

namespace {

/// 实体集合按 id upsert：已存在原位覆盖（保序），新实体追加末尾（保序）。
/// 与 Kotlin StateSyncService.applyToStore 的"先删后插"不同——原位覆盖保留
/// 既有顺序（RNG 对拍红线：弟子向量序 == Kotlin ids 序）。
template <typename T>
void upsertEntities(std::vector<T>& vec, const nlohmann::json& upserts) {
    for (const auto& e : upserts) {
        if (!e.is_object() || !e.contains("id")) continue;
        T entity = e.get<T>();
        const auto it = std::find_if(vec.begin(), vec.end(),
            [&](const T& x) { return x.id == entity.id; });
        if (it != vec.end()) {
            *it = std::move(entity);
        } else {
            vec.push_back(std::move(entity));
        }
    }
}

/// 实体集合按 id 删除（其余顺序保留）。
template <typename T>
void removeEntities(std::vector<T>& vec, const nlohmann::json& removed) {
    for (const auto& idEl : removed) {
        if (!idEl.is_string()) continue;
        const std::string id = idEl.get<std::string>();
        vec.erase(std::remove_if(vec.begin(), vec.end(),
            [&](const T& x) { return x.id == id; }), vec.end());
    }
}

/// 集合名分发：upsert（disciples + 9 实体集合；未知集合宽松忽略——前向兼容）。
/// disciples 走 DiscipleStore（SoA）：已存在原位覆盖（保序）、新弟子追加（保序）。
void applyCollectionUpsert(state::GameState& s, const std::string& name,
                           const nlohmann::json& arr) {
    if (name == "disciples") {
        for (const auto& e : arr) {
            if (!e.is_object() || !e.contains("id")) continue;
            s.disciples.upsertDisciple(e.get<state::Disciple>());
        }
    } else if (name == "equipmentStacks") upsertEntities(s.equipmentStacks, arr);
    else if (name == "equipmentInstances") upsertEntities(s.equipmentInstances, arr);
    else if (name == "manualStacks") upsertEntities(s.manualStacks, arr);
    else if (name == "manualInstances") upsertEntities(s.manualInstances, arr);
    else if (name == "pills") upsertEntities(s.pills, arr);
    else if (name == "materials") upsertEntities(s.materials, arr);
    else if (name == "herbs") upsertEntities(s.herbs, arr);
    else if (name == "seeds") upsertEntities(s.seeds, arr);
    else if (name == "storageBags") upsertEntities(s.storageBags, arr);
}

/// 集合名分发：remove
void applyCollectionRemove(state::GameState& s, const std::string& name,
                           const nlohmann::json& arr) {
    if (name == "disciples") {
        for (const auto& idEl : arr) {
            if (idEl.is_string()) s.disciples.removeById(idEl.get<std::string>());
        }
    } else if (name == "equipmentStacks") removeEntities(s.equipmentStacks, arr);
    else if (name == "equipmentInstances") removeEntities(s.equipmentInstances, arr);
    else if (name == "manualStacks") removeEntities(s.manualStacks, arr);
    else if (name == "manualInstances") removeEntities(s.manualInstances, arr);
    else if (name == "pills") removeEntities(s.pills, arr);
    else if (name == "materials") removeEntities(s.materials, arr);
    else if (name == "herbs") removeEntities(s.herbs, arr);
    else if (name == "seeds") removeEntities(s.seeds, arr);
    else if (name == "storageBags") removeEntities(s.storageBags, arr);
}

}  // namespace

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

// ── SteadyMonotonicClock（阶段 5 平台端口兜底实现） ────────────────
// Android 桥层注入 CLOCK_BOOTTIME（与 SystemClock.elapsedRealtime 一致，
// 含深度睡眠）；本实现为桌面/测试兜底（steady_clock，单调但不含休眠）。
int64_t SteadyMonotonicClock::nowMs() {
    return static_cast<int64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
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
        // 批 Y-4c：AI 宗门独立 RNG 播种（Kotlin AISectDiscipleManager.
        // initForSlot(systemSeed)——aiSeed = systemSeed + AI_SECT.id(6)×31337）
        aiRng_ = rng::DeterministicRng::fromSeed(
            config.systemSeed + static_cast<int64_t>(6) * 31337LL);
    }
    // T2.1（计划 v2 阶段 2）：每旬弟子结算钩子——六步结算（恢复/修炼/熟练度/
    // 孕养/丹药/突破），RNG 仅消耗 BREAKTHROUGH 分区，抽取顺序与 Kotlin
    // checkBreakthroughsAndPills 逐位一致
    settlement_.onPhaseSettle = [this](state::GameState& s, state::GameData&) {
        system::runPhaseSettlement(s, rng_);
    };
    // T2.2（计划 v2 阶段 2）：月变结算钩子——八步事务编排（政策/月效/七系统
    // 扇出/血炼/排班忠诚/月衰减/月度事件），RNG 消耗 EXPLORATION（妖兽移动）
    // 与 SYSTEM（收获 roll/伴侣配对），抽取顺序与 Kotlin processMonthYearChange
    // 的 monthChanged 分支逐位一致（未下沉扇出见 month_settlement.h 文件头）
    settlement_.onMonthChange = [this](state::GameState& s, state::GameData&) {
        system::runMonthSettlement(s, rng_);
    };
    // T2.3（计划 v2 阶段 2）：年变结算钩子——年报快照 + annual* 清零 +
    // gameMonth==1 年俸；年变全程零 RNG 抽取（场景规避后）。钩子调用序
    // （年变先于月变）在 settlement.h advanceOnePhase 中对齐 Kotlin。
    settlement_.onYearChange = [this](state::GameState& s, state::GameData&) {
        system::runYearSettlement(s, rng_, aiRng_);
    };
    if (config.authoritativeTickMode) {
        // T2.4（计划 v2 阶段 2d）：AUTHORITATIVE 过渡模式——core 模式下每旬
        // 只跑核心批次（步骤 1-5，零 RNG），月/年结算由 Kotlin 残留执行器
        // 按 settleOnePhase 标志处理。
        // P0 ECS 接入：核心批次经 ECS System 调度（PhaseCoreBatchSystem）+
        // JobSystem 并行化——消除 5000 弟子单线程 O(D) 热点的生产路径。
        jobs_ = std::make_unique<ecs::JobSystem>();
        ecsScheduler_.attach(
            std::make_unique<system::PhaseCoreBatchSystem>(state_, *jobs_));
        settlement_.setCoreMode(true);
        settlement_.onCoreSettle = [this](state::GameState& s, state::GameData&) {
            // s == state_（SettlementEngine 以 state_ 驱动）；系统持有 state_
            // 引用，经调度器驱动核心批次。
            (void)s;
            ecsScheduler_.runAll(ecsWorld_);
        };
    }
    syncRngStates();
    dirtyTracker_.resetBaseline(state_);
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

int GameCore::settleOnePhase() {
    if (!initialized_) return system::kSettleFlagNone;
    return settlement_.settleOnePhase(state_);
}

std::string GameCore::settleMonth() {
    if (!initialized_) return "{}";
    const system::MonthSettlementResult result =
        system::runMonthSettlement(state_, rng_);

    // 信封 JSON（nativeSettleMonth 回传 Kotlin 残留执行器的平台效应输入：
    // disabledPolicies → checkpointAllProduction；secretRealmClose → 秘境
    // 关闭邮件 + gate release；purchaseLogs → lifeEvents 瞬态列写入）
    nlohmann::json env = nlohmann::json::object();
    env["policyCosts"]["disabledPolicies"] =
        result.policyCosts.disabledPolicies;
    if (result.secretRealmClose.has_value()) {
        const auto& c = result.secretRealmClose.value();
        env["secretRealmClose"]["closed"] = c.closed;
        env["secretRealmClose"]["memberIds"] = c.memberIds;
        nlohmann::json bp;
        to_json(bp, c.backpack);
        env["secretRealmClose"]["backpack"] = std::move(bp);
        env["secretRealmClose"]["slotId"] = c.slotId;
    }
    nlohmann::json logs = nlohmann::json::array();
    for (const auto& log : result.purchaseLogs) {
        logs.push_back({{"discipleId", log.discipleId},
                        {"itemName", log.itemName},
                        {"age", log.age}});
    }
    env["purchaseLogs"] = std::move(logs);
    return env.dump();
}

void GameCore::resetAutoRecruitIdle() {
    if (!initialized_) return;
    state_.autoRecruitIdle = false;
}

std::string GameCore::settleYear() {
    if (!initialized_) return "{}";
    system::YearSettlementDraft draft;
    system::runYearSettlement(state_, rng_, aiRng_, &draft);

    // 信封 JSON（nativeSettleYear 回传 Kotlin 残留执行器的平台效应输入：
    // agedDeaths → 袋物品物化/DAO 清理/DeathEvent/死亡记录档案；
    // bereavements → lifeEvents 丧亲事件）
    nlohmann::json env = nlohmann::json::object();
    nlohmann::json deaths = nlohmann::json::array();
    for (const auto& d : draft.agedDeaths) {
        nlohmann::json j = {{"discipleId", d.discipleId},
                            {"name", d.name},
                            {"surname", d.surname},
                            {"age", d.age},
                            {"realm", d.realm},
                            {"realmLayer", d.realmLayer},
                            {"deathYear", d.deathYear},
                            {"cause", d.cause}};
        nlohmann::json bags = nlohmann::json::array();
        for (const auto& item : d.storageBagItems) {
            nlohmann::json itemJson;
            to_json(itemJson, item);   // 完整协议（物化回仓库需要实例/堆叠重建数据）
            bags.push_back(std::move(itemJson));
        }
        j["storageBagItems"] = std::move(bags);
        deaths.push_back(std::move(j));
    }
    env["agedDeaths"] = std::move(deaths);
    nlohmann::json bereavements = nlohmann::json::array();
    for (const auto& b : draft.bereavements) {
        bereavements.push_back({{"grievingId", b.grievingId},
                                {"relationship", b.relationship},
                                {"deceasedName", b.deceasedName},
                                {"grievingAge", b.grievingAge}});
    }
    env["bereavements"] = std::move(bereavements);
    return env.dump();
}

// ── 引擎循环 + 看门狗（计划 v2 阶段 5） ─────────────────────────────

void GameCore::setPlatformProviders(const PlatformProviders& providers) {
    if (providers.monotonicClock) loop_.setMonotonicClock(providers.monotonicClock);
    if (providers.telemetry) loop_.setTelemetry(providers.telemetry);
    if (providers.thermal) loop_.setThermalProvider(providers.thermal);
    loop_.setLogger(logger_);
    batteryProvider_ = providers.battery;
    logger_->log(LogLevel::kInfo, "GameCore", "platform providers injected");
}

int GameCore::watchdogVerdict(const WatchdogFlags& flags) {
    system::ProgressSnapshot snapshot;
    snapshot.tickCount = loop_.tickCount();
    snapshot.totalPhases = system::totalPhases(state_.gameData);
    snapshot.accumulatedGameMs = loop_.time().accumulatedGameMs();
    snapshot.loopActive = flags.loopActive;
    snapshot.isPaused = flags.isPaused;
    snapshot.isSaving = flags.isSaving;
    snapshot.isLoading = flags.isLoading;
    snapshot.speed = loop_.time().speed();
    snapshot.secretRealmPauseLock = flags.secretRealmPauseLock;
    snapshot.secretRealmPauseRenewedAtMs = flags.secretRealmPauseRenewedAtMs;
    snapshot.loopActiveAtMs = loop_.lastLoopActivityMs();
    snapshot.recordedAtMs = loop_.nowMs();
    return static_cast<int>(progressMonitor_.evaluate(snapshot));
}

int32_t GameCore::rngNextInt(int partitionId) {
    if (!initialized_ || partitionId < 0 ||
        partitionId > static_cast<int>(rng::RngPartition::kSecretRealm)) {
        logger_->log(LogLevel::kWarn, "GameCore",
                     "rngNextInt: invalid partition " + std::to_string(partitionId));
        return 0;
    }
    return rng_.getRng(static_cast<rng::RngPartition>(partitionId)).nextInt();
}

int64_t GameCore::rngSnapshotPartition(int partitionId) {
    if (!initialized_ || partitionId < 0 ||
        partitionId > static_cast<int>(rng::RngPartition::kSecretRealm)) {
        return 0;
    }
    return rng_.getRng(static_cast<rng::RngPartition>(partitionId)).snapshot();
}

bool GameCore::rngRestorePartition(int partitionId, int64_t state) {
    if (!initialized_ || partitionId < 0 ||
        partitionId > static_cast<int>(rng::RngPartition::kSecretRealm)) {
        logger_->log(LogLevel::kWarn, "GameCore",
                     "rngRestorePartition: invalid partition " + std::to_string(partitionId));
        return false;
    }
    rng_.getRng(static_cast<rng::RngPartition>(partitionId)).restore(state);
    return true;
}

void GameCore::rngInitSystemSeed(int64_t seed) {
    if (!initialized_) return;
    rng_.initSystemSeed(seed);
    syncRngStates();
}
std::string GameCore::exportStateJson() {
    if (!initialized_) return "{}";
    syncRngStates();
    try {
        std::string out = state::dumpStateJson(state_);
        // 全量导出即完整基线：接收方已拿到全部状态，变更集从此刻起算
        dirtyTracker_.resetBaseline(state_);
        return out;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("exportStateJson failed: ") + e.what());
        return "{}";
    }
}

bool GameCore::importStateJson(const std::string& json) {
    return importStateInternal(json, /*restoreRng=*/true);
}

bool GameCore::importStateJsonNoRng(const std::string& json) {
    return importStateInternal(json, /*restoreRng=*/false);
}

bool GameCore::importStateInternal(const std::string& json, bool restoreRng) {
    if (!initialized_) return false;
    try {
        const auto j = nlohmann::json::parse(json);
        state_ = j.get<state::GameState>();
        // 批 Y-4c：AI 宗门独立 RNG 读档重播——Kotlin loadData 调
        // AISectDiscipleManager.initForSlot(mapSeed)（mapSeed = 存档
        // GameData.mapSeed），C++ 同源播种保证 AI 招募/演化可复现
        aiRng_ = rng::DeterministicRng::fromSeed(
            state_.gameData.mapSeed + static_cast<int64_t>(6) * 31337LL);
        // 对抗性审查 A1（2026-08-22）：读档后必须复位结算引擎累积——
        // 否则旧会话残留的墙钟累积会在下一 tick 多推进旬数
        settlement_.reset();
        // C-13（计划 v2 阶段 1）：读档后从 GameData.rngStates 恢复 RNG 分区
        // 状态——C++ 真相源语义下，"存档→读档→推进"必须与不中断逐位一致。
        // T2.4（计划 v2 阶段 2d）：AUTHORITATIVE 每旬回导走 restoreRng=false
        // 分支——委托模式下 Kotlin 残留执行器的抽取已直接推进 native 分区，
        // 镜像 rngStates 可能滞后，恢复会造成分区回卷与跨语言漂移。
        if (restoreRng) {
            rng_.restoreStates(state_.gameData.rngStates);
        }
        dirtyTracker_.resetBaseline(state_);
        return true;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("importStateJson failed: ") + e.what());
        return false;
    }
}

std::string GameCore::exportDirtyJson() {
    if (!initialized_) return R"({"version":0,"changed":{},"removed":{}})";
    syncRngStates();
    try {
        return dirtyTracker_.diffToJson(state_);
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("exportDirtyJson failed: ") + e.what());
        return R"({"version":0,"changed":{},"removed":{}})";
    }
}

std::string GameCore::pollEventsJson() {
    // 批次 1 实现：事件队列
    return "[]";
}

bool GameCore::applyReverseDirty(const std::string& dirtyJson) {
    if (!initialized_) return false;
    try {
        const auto j = nlohmann::json::parse(dirtyJson);
        // 版本严格递增校验（防乱序/重复应用——Kotlin 侧单线程发送天然有序，
        // 但全量回导兜底路径可能交错，防御性拒绝）
        const uint64_t v = j.value("version", 0ULL);
        if (v != reverseVersion_ + 1) {
            logger_->log(LogLevel::kWarn, "GameCore",
                "applyReverseDirty: version mismatch v=" + std::to_string(v) +
                " expected=" + std::to_string(reverseVersion_ + 1));
            return false;
        }
        reverseVersion_ = v;

        // changed：gameData 全量覆盖（不含 rngStates——Kotlin 侧已剔除，
        // 缺失键保持 native 分区真相）+ 实体集合按 id upsert
        if (j.contains("changed") && j.at("changed").is_object()) {
            const auto& changed = j.at("changed");
            for (auto it = changed.begin(); it != changed.end(); ++it) {
                const std::string& name = it.key();
                const auto& value = it.value();
                if (name == "gameData") {
                    if (value.is_object()) {
                        state_.gameData = value.get<state::GameData>();
                    }
                } else if (name == "aiSectDisciples") {
                    // S-15：AI 宗门弟子池全量段（GameState 顶层字段——Kotlin
                    // GameData.aiSectDisciples @Transient 不入 gameData JSON）
                    if (value.is_object()) {
                        state_.aiSectDisciples = value.get<
                            std::map<std::string, std::vector<state::Disciple>>>();
                    }
                } else if (value.is_array()) {
                    applyCollectionUpsert(state_, name, value);
                }
            }
        }
        // removed：按 id 删除
        if (j.contains("removed") && j.at("removed").is_object()) {
            const auto& removed = j.at("removed");
            for (auto it = removed.begin(); it != removed.end(); ++it) {
                if (it.value().is_array()) {
                    applyCollectionRemove(state_, it.key(), it.value());
                }
            }
        }
        // 基线同步：C++ 状态已与 Kotlin 一致——防下一旬 exportDirty 把反向
        // 应用值当变更重发回 Kotlin（冗余镜像写）
        dirtyTracker_.syncBaselineToCurrent(state_);
        return true;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("applyReverseDirty failed: ") + e.what());
        return false;
    }
}

void GameCore::syncRngStates() {
    state_.gameData.rngStates = rng_.exportStates();
}

}  // namespace gamecore

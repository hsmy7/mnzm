#include "gamecore/game_core.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ctime>

#include <nlohmann/json.hpp>

#include <unordered_map>
#include <unordered_set>

#include "gamecore/state/json_codec.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/phase_settlement.h"
#include "gamecore/system/year_settlement.h"

namespace gamecore {

// ── 反向增量应用辅助 ─────────────────────────────

namespace {

/// 实体集合按 id upsert：已存在原位覆盖（保序），新实体追加末尾（保序）。
/// 与 Kotlin StateSyncService.applyToStore 的"先删后插"不同——原位覆盖保留
/// 既有顺序（RNG 对拍红线：弟子向量序 == Kotlin ids 序）。
template <typename T>
void upsertEntities(std::vector<T>& vec, const nlohmann::json& upserts) {
    // 规模解耦（/ 方案 D7 改动 2）：一次 O(N) 建 id→index 索引，
    // M 条增量 O(1) 查找——替代 O(N×M) find_if（旬节拍成本随状态规模平方级
    // 放大的根因）。upsert 语义保持：已存在原位覆盖（保序）、新实体追加。
    if (upserts.empty()) return;
    std::unordered_map<std::string, std::size_t> index;
    index.reserve(vec.size() * 2);
    for (std::size_t i = 0; i < vec.size(); ++i) index.emplace(vec[i].id, i);
    for (const auto& e : upserts) {
        if (!e.is_object() || !e.contains("id")) continue;
        T entity = e.get<T>();
        const auto it = index.find(entity.id);
        if (it != index.end()) {
            vec[it->second] = std::move(entity);
        } else {
            index.emplace(entity.id, vec.size());
            vec.push_back(std::move(entity));
        }
    }
}

/// 实体集合按 id 删除（其余顺序保留）。
template <typename T>
void removeEntities(std::vector<T>& vec, const nlohmann::json& removed) {
    // 同 upsertEntities：待删 id 集合化后单趟 compaction——
    // 替代每条增量一趟全表 remove_if 的 O(N×M)
    if (removed.empty()) return;
    std::unordered_set<std::string> toRemove;
    toRemove.reserve(removed.size() * 2);
    for (const auto& idEl : removed) {
        if (idEl.is_string()) toRemove.insert(idEl.get<std::string>());
    }
    if (toRemove.empty()) return;
    vec.erase(std::remove_if(vec.begin(), vec.end(),
        [&](const T& x) { return toRemove.count(x.id) > 0; }), vec.end());
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

/// 导入侧 id 计数器对齐：递归遍历存档 JSON 全部字符串，
/// 凡 "gc-<prefix>-<纯数字>" 形态即把对应注册表计数器推到 max(current, N)
/// （解析与幂等语义见 inventory.h observeItemIdForReseed）。
/// 扫描面=全存档字符串而非枚举容器——新容器/新前缀自动纳入（R1 泛化）；
/// O(json 规模)，仅导入时支付一次。Kotlin UUID 形态自动跳过
/// （UUID 空间与计数器空间不相交，审计运行时验证点 3 的静态推演）。
void reseedItemIdAllocatorsFromJson(const nlohmann::json& j) {
    using jt = nlohmann::json::value_t;
    switch (j.type()) {
        case jt::string:
            system::observeItemIdForReseed(j.get_ref<const std::string&>());
            break;
        case jt::object:
            for (auto it = j.begin(); it != j.end(); ++it) reseedItemIdAllocatorsFromJson(it.value());
            break;
        case jt::array:
            for (auto it = j.begin(); it != j.end(); ++it) reseedItemIdAllocatorsFromJson(*it);
            break;
        default:
            break;
    }
}

// ── 账本族导入侧归一─────────────
// 「只进不出」账本的保留窗口 = 其消费/幂等语义窗口（R4）。写入侧由 Kotlin
// 追加点裁剪（MailService takeLast(500) / GameEngineBattleOps 战史窗口过滤），
// 导入侧在此统一兜底——老档/篡改档首次读入即回缩，多次导入幂等一致。

/// mailRecords 保留条数（防重复发放窗口；与 MailService.kt
/// MAIL_RECORD_RETENTION = 500 同源，改值须双端同步）
constexpr std::size_t MAIL_RECORD_RETENTION = 500;

/// 战史消费窗口（年；与 sect_attack_decision.h countRecentBattleRecords 的
/// year >= gameYear - 3 及 Kotlin GameEngineBattleOps.kt
/// BATTLE_RECORD_WINDOW_YEARS = 3 同源，改值须双端同步）
constexpr int32_t BATTLE_RECORD_WINDOW_YEARS = 3;

/// 消息栏事件条数（与生成侧 cap 对齐，防旧档/篡改档一次性放大）
constexpr std::size_t GAME_EVENT_RECORDS_LIMIT = 200;

void normalizeLedgers(state::GameData& gd) {
    // mailRecords：保留最近 MAIL_RECORD_RETENTION 条（追加序，末尾为最新）
    if (gd.mailRecords.size() > MAIL_RECORD_RETENTION) {
        gd.mailRecords.erase(gd.mailRecords.begin(),
                             gd.mailRecords.end() - static_cast<std::ptrdiff_t>(MAIL_RECORD_RETENTION));
    }
    // sectBattleRecords：只留消费窗口内的条目
    const int32_t windowFloor = gd.gameYear - BATTLE_RECORD_WINDOW_YEARS;
    gd.sectBattleRecords.erase(
        std::remove_if(gd.sectBattleRecords.begin(), gd.sectBattleRecords.end(),
                       [windowFloor](const state::SectBattleRecord& r) {
                           return r.year < windowFloor;
                       }),
        gd.sectBattleRecords.end());
    // gameEventRecords：保留最近 GAME_EVENT_RECORDS_LIMIT 条
    if (gd.gameEventRecords.size() > GAME_EVENT_RECORDS_LIMIT) {
        gd.gameEventRecords.erase(gd.gameEventRecords.begin(),
                                  gd.gameEventRecords.end() - static_cast<std::ptrdiff_t>(GAME_EVENT_RECORDS_LIMIT));
    }
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

// ── SteadyMonotonicClock（平台端口兜底实现） ────────────────
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
        // AI 宗门独立 RNG 播种（Kotlin AISectDiscipleManager.
        // initForSlot(systemSeed)——aiSeed = systemSeed + AI_SECT.id(6)×31337）
        aiRng_ = rng::DeterministicRng::fromSeed(
            config.systemSeed + static_cast<int64_t>(6) * 31337LL);
    }
    // 每旬弟子结算钩子——六步结算（恢复/修炼/熟练度/
    // 孕养/丹药/突破），RNG 仅消耗 BREAKTHROUGH 分区，抽取顺序与 Kotlin
    // checkBreakthroughsAndPills 逐位一致
    settlement_.onPhaseSettle = [this](state::GameState& s, state::GameData&) {
        // 步骤 0/1-5/6/7 迭代域经 ecsWorld_ 行序桥接（sync 校验/恢复）
        system::runPhaseSettlement(s, rng_, ecsWorld_);
    };
    // 月变结算钩子——八步事务编排（政策/月效/七系统
    // 扇出/血炼/排班忠诚/月衰减/月度事件），RNG 消耗 EXPLORATION（妖兽移动）
    // 与 SYSTEM（收获 roll/伴侣配对），抽取顺序与 Kotlin processMonthYearChange
    // 的 monthChanged 分支逐位一致（未下沉扇出见 month_settlement.h 文件头）
    settlement_.onMonthChange = [this](state::GameState& s, state::GameData&) {
        // 月结域全部弟子迭代经 ecsWorld_ 行序桥接
        system::runMonthSettlement(s, rng_, aiRng_, aiMonthBatch_, ecsWorld_);
    };
    // 年变结算钩子——年报快照 + annual* 清零 +
    // gameMonth==1 年俸；年变全程零 RNG 抽取（场景规避后）。钩子调用序
    // （年变先于月变）在 settlement.h advanceOnePhase 中对齐 Kotlin。
    settlement_.onYearChange = [this](state::GameState& s, state::GameData&) {
        // 年结域全部弟子迭代经 ecsWorld_ 行序桥接
        system::runYearSettlement(s, rng_, aiRng_, ecsWorld_);
    };
    if (config.authoritativeTickMode) {
        // AUTHORITATIVE 模式——core 模式每旬
        // 走完整七步结算（runPhaseSettlementCore：0 自动装备 → 1-5 核心
        // 批次 → 6 丹药+偷盗钩子 → 7 突破+亲属赠送；Kotlin executeResidual
        // 生产路径已删除）。
        // 月/年边界仍由 Kotlin 按 settleOnePhase 标志编排（未下沉扇出）。
        // 核心批次经 ECS System 调度（PhaseCoreBatchSystem）+
        // JobSystem 并行化——消除 5000 弟子单线程 O(D) 热点的生产路径。
        // System 真用 World/View——迭代域经
        // View<DiscipleRef> 行序映射（syncDiscipleEntities 校验/恢复不变量），
        // 实体集由本对象持久 ecsWorld_ 承载：首旬惰性装配，弟子增删漂移
        // 即重建，稳态零重建成本。
        jobs_ = std::make_unique<ecs::JobSystem>();
        ecsScheduler_.attach(
            std::make_unique<system::PhaseCoreBatchSystem>(state_, *jobs_));
        settlement_.setCoreMode(true);
        settlement_.onCoreSettle = [this](state::GameState& s, state::GameData&) {
            // s == state_（SettlementEngine 以 state_ 驱动）；系统持有 state_
            // 引用，经调度器驱动核心批次。
            (void)s;
            system::runPhaseSettlementCore(state_, rng_, [this]() {
                ecsScheduler_.runAll(ecsWorld_);
            }, ecsWorld_);
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
    // 墙钟毫秒 → GameTimeClock 等价推进 + 边界检测（结算钩子在 init 注册）
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
        system::runMonthSettlement(state_, rng_, aiRng_, aiMonthBatch_, ecsWorld_);

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
    // 子事件 6b 征伐环平台效应草稿：玩家占领宗门被夺回 → 建筑没收 sectId 集
    nlohmann::json seized = nlohmann::json::array();
    for (const auto& sectId : result.seizedSectBuildings) {
        seized.push_back(sectId);
    }
    env["seizedSectBuildings"] = std::move(seized);
    return env.dump();
}

void GameCore::resetAutoRecruitIdle() {
    if (!initialized_) return;
    state_.autoRecruitIdle = false;
}

std::string GameCore::settleYear() {
    if (!initialized_) return "{}";
    system::YearSettlementDraft draft;
    system::runYearSettlement(state_, rng_, aiRng_, ecsWorld_, &draft);

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

// ── 引擎循环 + 看门狗 ─────────────────────────────

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
        // AI 宗门独立 RNG 读档重播——Kotlin loadData 调
        // AISectDiscipleManager.initForSlot(mapSeed)（mapSeed = 存档
        // GameData.mapSeed），C++ 同源播种保证 AI 招募/演化可复现
        aiRng_ = rng::DeterministicRng::fromSeed(
            state_.gameData.mapSeed + static_cast<int64_t>(6) * 31337LL);
        // 读档后必须复位结算引擎累积——
        // 否则旧会话残留的墙钟累积会在下一 tick 多推进旬数
        settlement_.reset();
        // 读档后从 GameData.rngStates 恢复 RNG 分区
        // 状态——C++ 真相源语义下，"存档→读档→推进"必须与不中断逐位一致。
        // AUTHORITATIVE 每旬回导走 restoreRng=false
        // 分支——委托模式下 Kotlin 残留执行器的抽取已直接推进 native 分区，
        // 镜像 rngStates 可能滞后，恢复会造成分区回卷与跨语言漂移。
        if (restoreRng) {
            rng_.restoreStates(state_.gameData.rngStates);
        }
        // 导入即 reseed：id 计数器生命周期与存档对齐——
        // 计数器推到存档已见最大后缀 +1 之后，重启读档后新分配的 id 不可能
        // 与存档既有 id 撞号。只推高不回退：重复导入幂等（R3：源头错位修复）。
        reseedItemIdAllocatorsFromJson(j);
        // 导入侧 AI 尸体归一+幂等压缩：老档首次读入
        // 即回缩（死亡超保留窗口条目移除、缺 deathYear 补导入年）——
        // 必须先于 resetBaseline（导入后的首次导出以净化后的状态为基线）
        system::detail::normalizeAICorpseEntries(state_);
        // 导入侧账本族归一：mailRecords/战史/事件栏
        // 按保留窗口回缩——先于 resetBaseline，导入后首次导出以净化态为基线
        normalizeLedgers(state_.gameData);
        dirtyTracker_.resetBaseline(state_);
        // 全量导入 = 重同步基线：反向增量版本归零（配对 Kotlin
        // StateSyncService.importToNative 成功后重置 reverseVersion——
        // 防跨会话/热重载两端版本错位导致增量回导永久 version mismatch）
        reverseVersion_ = 0;
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

        // changed：gameData 字段级补丁（反向通道——Kotlin 侧只发
        // 变更字段的 dirty 集）+ 实体集合按 id upsert。补丁语义：从当前
        // gameData 起步仅覆盖信封携带键（from_json 宽松读：缺失键保持现值）；
        // 全量信封（携带全部键）与旧"整段替换"逐位等价。rngStates 由 Kotlin
        // 侧剔除、缺失保持 live 值——旧全量解码会把缺失键落成默认空表再整体
        // 赋值（覆盖后由下次导出的 syncRngStates 回写兜住），现显式不动。
        if (j.contains("changed") && j.at("changed").is_object()) {
            const auto& changed = j.at("changed");
            for (auto it = changed.begin(); it != changed.end(); ++it) {
                const std::string& name = it.key();
                const auto& value = it.value();
                if (name == "gameData") {
                    if (value.is_object()) {
                        state::GameData patched = state_.gameData;
                        value.get_to(patched);
                        state_.gameData = std::move(patched);
                    }
                } else if (name == "aiSectDisciples") {
                    // AI 宗门弟子池全量段（GameState 顶层字段——Kotlin
                    // GameData.aiSectDisciples @Transient 不入 gameData JSON）
                    if (value.is_object()) {
                        state_.aiSectDisciples = value.get<
                            std::map<std::string, std::vector<state::Disciple>>>();
                    }
                } else if (name == "lockedBeastIds") {
                    // 妖兽视图锁定顶层段（GameState.lockedBeastIds——Kotlin
                    // GameData.lockedBeastIds @Transient 不入 gameData JSON）。
                    // 语义 = 整体替换（Kotlin 变化检测后发全量 id 集，lock/unlock
                    // 均为集合重写）；月结跳过判定消费（month_settlement）。
                    if (value.is_array()) {
                        state_.lockedBeastIds.clear();
                        for (const auto& idEl : value) {
                            if (idEl.is_string()) {
                                state_.lockedBeastIds.push_back(
                                    idEl.get<std::string>());
                            }
                        }
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
        // 版本推进必须在**全部应用成功后**执行：若在应用逻辑前推进，后续
        // 步骤抛异常时版本已前进但返回 false，与 Kotlin 侧"发送成功才++"
        // 失去对称 → 之后所有增量回导将永久 version mismatch，只能退化为
        // 每旬全量回导兜底。
        reverseVersion_ = v;
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

std::string GameCore::manualRecruitFromList(const std::string& discipleId) {
    if (!initialized_) {
        return R"({"ok":false,"newId":"","age":0,"name":"","reason":"UNKNOWN"})";
    }
    try {
        const auto result = system::recruit_settle::manualRecruitFromList(
            state_, discipleId);
        nlohmann::json j;
        j["ok"] = result.reason == system::recruit_settle::ManualRecruitReason::kSuccess;
        j["newId"] = result.newId;
        j["age"] = result.age;
        j["name"] = result.name;
        j["reason"] = system::recruit_settle::manualRecruitReasonName(result.reason);
        return j.dump();
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("manualRecruitFromList failed: ") + e.what());
        return R"({"ok":false,"newId":"","age":0,"name":"","reason":"UNKNOWN"})";
    }
}

std::string GameCore::manualRecruitAll() {
    if (!initialized_) {
        return R"({"ok":false,"count":0,"reason":"UNKNOWN"})";
    }
    try {
        system::recruit_settle::ManualRecruitReason reason =
            system::recruit_settle::ManualRecruitReason::kSuccess;
        const int32_t count = system::recruit_settle::manualRecruitAll(state_, reason);
        nlohmann::json j;
        j["ok"] = reason == system::recruit_settle::ManualRecruitReason::kSuccess;
        j["count"] = count;
        j["reason"] = system::recruit_settle::manualRecruitReasonName(reason);
        return j.dump();
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("manualRecruitAll failed: ") + e.what());
        return R"({"ok":false,"count":0,"reason":"UNKNOWN"})";
    }
}

}  // namespace gamecore

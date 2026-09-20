#include "gamecore/game_core.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <ctime>

#include <nlohmann/json.hpp>

#include <unordered_map>
#include <unordered_set>

#include "gamecore/state/json_codec.h"
#include "gamecore/state/gameview_encode.h"
#include "gamecore/map/terrain.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/phase_settlement.h"
#include "gamecore/system/year_settlement.h"

namespace gamecore {

namespace {

/// gameEventRecords 当前最大 sequenceId（收割水位初始化/导入防重放用）
int64_t maxGameEventSequence_(const state::GameState& s) {
    int64_t maxSeq = 0;
    for (const auto& r : s.gameData.gameEventRecords) {
        maxSeq = std::max(maxSeq, r.sequenceId);
    }
    return maxSeq;
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
    // 留存初始化配置（WS-5b：ensureTerrainGenerated 消费地形生成参数——
    // 单一数据源 = Kotlin GameConfig.SectMap，经 nativeInit 传入）
    config_ = config;
    if (config.seedInitialized) {
        rng_.initSystemSeed(config.systemSeed);
        // AI 宗门独立 RNG 播种（Kotlin AISectDiscipleManager.
        // initForSlot(systemSeed)——aiSeed = systemSeed + AI_SECT.id(6)×31337）
        aiRng_ = rng::DeterministicRng::fromSeed(
            config.systemSeed + static_cast<int64_t>(6) * 31337LL);
        // 同步镜像分区初值，使 syncRngStates 导出的键 9 与 aiRng_ 一致
        mirrorAiRng();
    }
    // 每旬弟子结算钩子——六步结算（恢复/修炼/熟练度/
    // 孕养/丹药/突破），RNG 仅消耗 BREAKTHROUGH 分区，抽取顺序与 Kotlin
    // checkBreakthroughsAndPills 逐位一致
    settlement_.onPhaseSettle = [this](state::GameState& s, state::GameData&) {
        // 步骤 0/1-5/6/7 迭代域经 ecsWorld_ 行序桥接（sync 校验/恢复）
        system::runPhaseSettlement(s, rng_, ecsWorld_);
        // R2.4：突破事件收割（列写点已在结算内逐一 markCol）
        harvestBreakthroughEvents();
    };
    // 月变结算钩子——八步事务编排（政策/月效/七系统
    // 扇出/血炼/排班忠诚/月衰减/月度事件），RNG 消耗 EXPLORATION（妖兽移动）
    // 与 SYSTEM（收获 roll/伴侣配对），抽取顺序与 Kotlin processMonthYearChange
    // 的 monthChanged 分支逐位一致（未下沉扇出见 month_settlement.h 文件头）
    settlement_.onMonthChange = [this](state::GameState& s, state::GameData&) {
        // 月结域全部弟子迭代经 ecsWorld_ 行序桥接
        system::runMonthSettlement(s, rng_, aiRng_, aiMonthBatch_, ecsWorld_);
        // R2/B09：月结边界粗粒度列标脏（advance 路径同生产 settleMonth 口径）
        markMonthYearBoundaryColumns();
    };
    // 年变结算钩子——年报快照 + annual* 清零 +
    // gameMonth==1 年俸；年变全程零 RNG 抽取（场景规避后）。钩子调用序
    // （年变先于月变）在 settlement.h advanceOnePhase 中对齐 Kotlin。
    settlement_.onYearChange = [this](state::GameState& s, state::GameData&) {
        // 年结域全部弟子迭代经 ecsWorld_ 行序桥接
        system::runYearSettlement(s, rng_, aiRng_, ecsWorld_);
        // R2/B09：年结边界粗粒度列标脏（advance 路径同生产 settleYear 口径）
        markMonthYearBoundaryColumns();
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
            // R2.4：突破事件收割（核心批次列写点同走 markCol 屏障）
            harvestBreakthroughEvents();
        };
    }
    syncRngStates();
    dirtyTracker_.resetBaseline(state_);
    // R2.4/B09：列级写屏障挂载 + 基线同点重置（位图清零 + 非弟子域基线树）
    state_.disciples.attachColumnDirtyTracker(&columnTracker_);
    columnTracker_.resetBaseline(state_);
    breakthroughHarvestedSequence_ = maxGameEventSequence_(state_);
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


void GameCore::harvestBreakthroughEvents() {
    for (const auto& r : state_.gameData.gameEventRecords) {
        if (r.sequenceId <= breakthroughHarvestedSequence_) continue;
        if (r.eventType != "breakthrough") continue;
        breakthroughHarvestedSequence_ = r.sequenceId;
        nlohmann::json detail = {{"discipleId", r.relatedEntityId},
                                 {"summary", r.summary}};
        queueViewEvent(state::ViewEventType::kBreakthrough, detail.dump());
    }
    // 水位推进到当前最大（含非突破记录——它们不经事件流入流）
    breakthroughHarvestedSequence_ =
        std::max(breakthroughHarvestedSequence_, maxGameEventSequence_(state_));
}

void GameCore::queueViewEvent(state::ViewEventType type, const std::string& detailJson) {
    // 传输臂退役（B18）：生产通道恒 protobuf，事件恒入队
    state::ViewEventDraft draft;
    draft.type = type;
    draft.gameYear = state_.gameData.gameYear;
    draft.gameMonth = state_.gameData.gameMonth;
    draft.detailJson = detailJson;
    pendingViewEvents_.push_back(std::move(draft));
}

void GameCore::markMonthYearBoundaryColumns() {
    // 月/年结算路径审计写列并集（month_settlement/year_settlement/
    // profession/disciple_purchase/mission_completion/government/production/
    // sect_defense_battle/recruit_settlement 俘虏装备与月度衰减/晋升/购买/
    // 任务/政策/防守战/死亡链——宁多标不漏标；行序 = 店行序）
    static constexpr state::DiscipleColumn kBoundaryColumns[] = {
        state::DiscipleColumn::Loyalty,
        state::DiscipleColumn::Morality,
        state::DiscipleColumn::PartnerId,
        state::DiscipleColumn::MasterId,
        state::DiscipleColumn::Status,
        state::DiscipleColumn::StatusData,
        state::DiscipleColumn::GriefEndYear,
        state::DiscipleColumn::Age,
        state::DiscipleColumn::RealmLayer,
        state::DiscipleColumn::IsAlive,
        state::DiscipleColumn::SoulPower,
        state::DiscipleColumn::CurrentHp,
        state::DiscipleColumn::CurrentMp,
        state::DiscipleColumn::ManualIds,
        state::DiscipleColumn::ManualMasteries,
        state::DiscipleColumn::WeaponId,
        state::DiscipleColumn::ArmorId,
        state::DiscipleColumn::BootsId,
        state::DiscipleColumn::AccessoryId,
        state::DiscipleColumn::StorageBagItems,
        state::DiscipleColumn::StorageBagSpiritStones,
        state::DiscipleColumn::SpiritStones,
        state::DiscipleColumn::SalaryPaidCount,
        state::DiscipleColumn::ChildBirthMonth,
        state::DiscipleColumn::LastChildYear,
        state::DiscipleColumn::PillPhysicalAttackBonus,
        state::DiscipleColumn::PillMagicAttackBonus,
        state::DiscipleColumn::PillPhysicalDefenseBonus,
        state::DiscipleColumn::PillMagicDefenseBonus,
        state::DiscipleColumn::PillHpBonus,
        state::DiscipleColumn::PillMpBonus,
        state::DiscipleColumn::PillSpeedBonus,
        state::DiscipleColumn::PillCritRateBonus,
        state::DiscipleColumn::PillCritEffectBonus,
        state::DiscipleColumn::PillCultivationSpeedBonus,
        state::DiscipleColumn::PillSkillExpSpeedBonus,
        state::DiscipleColumn::PillNurtureSpeedBonus,
        state::DiscipleColumn::PillEffectDuration,
        state::DiscipleColumn::ActivePillTypes,
        state::DiscipleColumn::ActivePillCategory,
        state::DiscipleColumn::AlchemyLevel,
        state::DiscipleColumn::AlchemyPromotionCount,
        state::DiscipleColumn::ForgeLevel,
        state::DiscipleColumn::ForgePromotionCount,
    };
    const std::size_t rows = state_.disciples.size();
    for (const auto col : kBoundaryColumns) {
        for (std::size_t row = 0; row < rows; ++row) {
            state_.disciples.markCol(col, row);
        }
    }
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
    // R2/B09：月结边界粗粒度列标脏（列集 = 月/年路径审计写列的并集，
    // 全行标脏——宁多标不漏标；phase 路径为写点级精确标脏不经此）
    markMonthYearBoundaryColumns();

    // ── R2.4 eventFeed 入队（proto 传输开启时；JSON 回滚臂不入队——
    //    信封 JSON 面零变更红线，事件与信封同一事实双面）──────────
    {
        nlohmann::json detail;
        detail["disabledPolicies"] = result.policyCosts.disabledPolicies;
        nlohmann::json seized = nlohmann::json::array();
        for (const auto& sectId : result.seizedSectBuildings) seized.push_back(sectId);
        detail["seizedSectBuildings"] = std::move(seized);
        queueViewEvent(state::ViewEventType::kMonthSettled, detail.dump());
    }
    for (const auto& log : result.purchaseLogs) {
        nlohmann::json detail = {{"discipleId", log.discipleId},
                                 {"itemName", log.itemName},
                                 {"age", log.age}};
        queueViewEvent(state::ViewEventType::kPurchase, detail.dump());
    }
    if (result.secretRealmClose.has_value() && result.secretRealmClose.value().closed) {
        const auto& c = result.secretRealmClose.value();
        nlohmann::json bp;
        to_json(bp, c.backpack);
        nlohmann::json detail = {{"memberIds", c.memberIds},
                                 {"backpack", std::move(bp)}};
        queueViewEvent(state::ViewEventType::kSecretRealmClosed, detail.dump());
    }

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
    // R2/B09：年结边界粗粒度列标脏（与月结共用审计并集列集）
    markMonthYearBoundaryColumns();

    // ── R2.4 eventFeed 入队（proto 传输开启时）────────────────────
    for (const auto& d : draft.agedDeaths) {
        nlohmann::json bags = nlohmann::json::array();
        for (const auto& item : d.storageBagItems) {
            nlohmann::json itemJson;
            to_json(itemJson, item);   // 完整协议（物化回仓库需要实例/堆叠重建数据）
            bags.push_back(std::move(itemJson));
        }
        nlohmann::json detail = {{"discipleId", d.discipleId},
                                 {"name", d.name},
                                 {"surname", d.surname},
                                 {"age", d.age},
                                 {"realm", d.realm},
                                 {"realmLayer", d.realmLayer},
                                 {"deathYear", d.deathYear},
                                 {"cause", d.cause},
                                 {"storageBagItems", std::move(bags)}};
        queueViewEvent(state::ViewEventType::kDeath, detail.dump());
    }
    {
        nlohmann::json bereavements = nlohmann::json::array();
        for (const auto& b : draft.bereavements) {
            bereavements.push_back({{"grievingId", b.grievingId},
                                    {"relationship", b.relationship},
                                    {"deceasedName", b.deceasedName},
                                    {"grievingAge", b.grievingAge}});
        }
        nlohmann::json detail;
        detail["bereavements"] = std::move(bereavements);
        queueViewEvent(state::ViewEventType::kYearSettled, detail.dump());
    }

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
        partitionId > rng::RngManager::kMaxPartitionId) {
        logger_->log(LogLevel::kWarn, "GameCore",
                     "rngNextInt: invalid partition " + std::to_string(partitionId));
        return 0;
    }
    // 镜像分区（9）直取 AI 流本体——Kotlin NativeBackedRng 委托模式下
    // AISectDiscipleManager 的抽取即此流（与 C++ 月结/年结消费的 aiRng_ 同源）
    if (static_cast<rng::RngPartition>(partitionId) == rng::RngPartition::kAiSectMirror) {
        return aiRng_.nextInt();
    }
    return rng_.getRng(static_cast<rng::RngPartition>(partitionId)).nextInt();
}

int64_t GameCore::rngSnapshotPartition(int partitionId) {
    if (!initialized_ || partitionId < 0 ||
        partitionId > rng::RngManager::kMaxPartitionId) {
        return 0;
    }
    if (static_cast<rng::RngPartition>(partitionId) == rng::RngPartition::kAiSectMirror) {
        return aiRng_.snapshot();
    }
    return rng_.getRng(static_cast<rng::RngPartition>(partitionId)).snapshot();
}

bool GameCore::rngRestorePartition(int partitionId, int64_t state) {
    if (!initialized_ || partitionId < 0 ||
        partitionId > rng::RngManager::kMaxPartitionId) {
        logger_->log(LogLevel::kWarn, "GameCore",
                     "rngRestorePartition: invalid partition " + std::to_string(partitionId));
        return false;
    }
    // 镜像分区双向对称：写 aiRng_ 本体 **且** 同步镜像分区，
    // 使随后 syncRngStates/exportStates 导出的键 9 恒等于 aiRng_ 真态
    if (static_cast<rng::RngPartition>(partitionId) == rng::RngPartition::kAiSectMirror) {
        aiRng_.restore(state);
        rng_.getRng(rng::RngPartition::kAiSectMirror).restore(state);
        return true;
    }
    rng_.getRng(static_cast<rng::RngPartition>(partitionId)).restore(state);
    return true;
}

void GameCore::rngInitSystemSeed(int64_t seed) {
    if (!initialized_) return;
    rng_.initSystemSeed(seed);
    // AI 流随系统种子重播（Kotlin initSystemSeed 的调用点 = createNewGame/
    // restartGame 的"播种在引擎线程、生成世界前完成"契约）：与 GameCore::initialize
    // 同式，否则新档/重启后 aiRng_ 仍停在上一个存档的序列上
    aiRng_ = rng::DeterministicRng::fromSeed(seed + static_cast<int64_t>(6) * 31337LL);
    syncRngStates();
}
std::string GameCore::exportStateJson() {
    if (!initialized_) return "{}";
    syncRngStates();
    try {
        std::string out = state::dumpStateJson(state_);
        // 全量导出即完整基线：接收方已拿到全部状态，变更集从此刻起算
        dirtyTracker_.resetBaseline(state_);
        columnTracker_.resetBaseline(state_);
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
            // AI 流归档续接：存档含键 9（新档格式）→ 以其覆盖上面的 mapSeed 重播，
            // 使"存档→读档→推进"的 AI 演化与不中断逐位一致；旧档无键 9 →
            // 保持 mapSeed + 6×31337 播种（与引入镜像前的行为逐位一致，零回归）
            // 0 显式排除：PCG-XSH-RR 的 state = (seed<<1)|1 后经一轮混合，
            // **数学上不可能为 0**，故 0 只可能是"旧档无该键时的缺省填充"，
            // 不能据此覆盖 aiRng_（否则会把合法的 mapSeed 播种态回卷掉）
            const auto aiIt = state_.gameData.rngStates.find(
                static_cast<int32_t>(rng::RngPartition::kAiSectMirror));
            if (aiIt != state_.gameData.rngStates.end() && aiIt->second != 0) {
                aiRng_.restore(aiIt->second);
            }
        }
        // 导出侧一致性：无论是否续接，键 9 都必须等于 aiRng_ 真态
        // （restoreRng=false 的每旬回导分支同样需要——否则下次导出的键 9 会是陈旧值）
        mirrorAiRng();
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
        // 地图冻结（WS-5b）"生成即数据"：老档无段 ⇒ 按 mapSeed 生成 + 落为
        // 权威数据；新档与老档**同一条路径**（归一化族口径）。有段恒优先
        // （跨版本冻结，不重算）。先于 resetBaseline ⇒ 生成段计入导入基线，
        // 前向/反向镜像零载荷（稳态每旬零增量）。
        ensureTerrainGenerated();
        dirtyTracker_.resetBaseline(state_);
        // R2.4/B09：导入整体替换 state_ ⇒ 新 DiscipleStore 的写屏障指针随
        // 对象归零，必须重挂；列级基线（位图/非弟子域树）同点重置，
        // 收割游标推到导入态最大（防旧档记录重放）
        state_.disciples.attachColumnDirtyTracker(&columnTracker_);
        columnTracker_.resetBaseline(state_);
        breakthroughHarvestedSequence_ = maxGameEventSequence_(state_);
        pendingViewEvents_.clear();
        return true;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("importStateJson failed: ") + e.what());
        return false;
    }
}

// ── 地图冻结（WS-5b）：生成即数据 ────────────────────────────────────
// 无地形段 ⇒ 按 mapSeed + 初始化配置（Kotlin GameConfig.SectMap 传值）
// 生成行主序 flat 瓦片段并落为权威数据 + 戳生成器版本；有段恒优先
//（跨版本冻结，老档老地图、新档新地图——Minecraft"区块边境"模式）。
// 生成零 RNG（seed+坐标纯函数，与 Kotlin SectMapTileGenerator 位级等价）。
// 防御：地形未配置（width<=0，桌面最小测试面）/ mapSeed==0（引擎裸 init
// 未建档的默认态）⇒ 跳过，保持既有行为零变化。
void GameCore::ensureTerrainGenerated() {
    auto& gd = state_.gameData;
    if (!gd.terrainTiles.empty()) {
        return;  // 有段 ⇒ 直接采用（存的地形恒优先，不重算）
    }
    if (config_.terrainWidthCells <= 0 || config_.terrainHeightCells <= 0) {
        return;  // 未配置地形生成参数（桌面最小测试面）⇒ 跳过
    }
    if (gd.mapSeed == 0) {
        return;  // 无种子（未建档默认态）⇒ 无从生成
    }
    gamecore::map::terrain::GateBox gate;
    gate.x = config_.terrainGateX;
    gate.y = config_.terrainGateY;
    gate.width = config_.terrainGateWidth;
    gate.height = config_.terrainGateHeight;
    gd.terrainTiles = gamecore::map::terrain::generateTileData(
        config_.terrainWidthCells, config_.terrainHeightCells,
        config_.terrainDecorationDensity, gd.mapSeed,
        config_.terrainBorderTreeRing, gate);
    gd.mapGenVersion = config_.terrainMapGenVersion;
}

std::string GameCore::exportDirtyJson() {
    if (!initialized_) return R"({"version":0,"changed":{},"removed":{}})";
    syncRngStates();
    try {
        const std::string out = dirtyTracker_.diffToJson(state_);
        // 全量封已携带全部变更：列级位图/事件游标随基线清零（防之后列级封
        // 重发同值变更——重发无害，清零更省）
        columnTracker_.resetBaseline();
        return out;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("exportDirtyJson failed: ") + e.what());
        return R"({"version":0,"changed":{},"removed":{}})";
    }
}

std::string GameCore::exportDirtyProto() {
    // 空信封（version=0、无 changed/removed）：消费端零写入快速路径——
    // 未初始化/异常时恒产出合法 GameView，Kotlin parseFrom 得 DirtyApplyResult(0,0,0,0)
    if (!initialized_) return state::encodeGameView(nlohmann::json::object(), "");
    syncRngStates();
    try {
        // R2.4/B09 混合导出：列级模式（且无异构写入锁存）走 ColumnDirtyTracker
        // 整树导出（弟子域仅脏行×脏列；gameData/集合域与全量 diff 共享同一
        // 比对段，构造等价）；全量开关/锁存命中 = 全量树 diff（对拍零漂移）。
        // 全量封后列级位图清零（变更已由全量封携带，位图重置防重发）。
        const bool useColumnLevel = columnLevelDirtyExport_ && !columnExportBlocked_;
        columnExportBlocked_ = false;
        nlohmann::json tree = useColumnLevel
            ? columnTracker_.exportDirtyTree(state_)
            : [this]() {
                  nlohmann::json t = dirtyTracker_.diffToTree(state_);
                  columnTracker_.resetBaseline();
                  return t;
              }();
        // 事件流随封产出（导出即消费：编码成功后清空队列——编码异常时保留
        // 供下一封重试，与变更集的"基线未推进"降级语义一致）
        const std::string out = state::encodeGameView(
            tree, config_.snapshotSchemaVersion, &pendingViewEvents_);
        pendingViewEvents_.clear();
        return out;
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("exportDirtyProto failed: ") + e.what());
        return state::encodeGameView(nlohmann::json::object(), "");
    }
}

std::string GameCore::exportDirty() {
    // B18 传输臂退役：恒 protobuf（JSON 导出能力保留在 exportDirtyJson 供对拍）
    return exportDirtyProto();
}

std::string GameCore::exportDirtyColumnJson() {
    if (!initialized_) return R"({"version":0,"changed":{},"removed":{}})";
    syncRngStates();
    return columnTracker_.exportDirtyJson(state_);
}

void GameCore::syncRngStates() {
    // 导出前刷新 AI 镜像分区（键 9 = aiRng_ 真态）——AI 演进发生在月结/年结/
    // 招募等任意路径，若不在此刷新，导出的键 9 会停留在上次镜像时的陈旧值，
    // 读档续接即回卷（与"每旬回导"同族的漂移缺陷）
    mirrorAiRng();
    state_.gameData.rngStates = rng_.exportStates();
    // 键 6（kAiSect）**不再写入导出面**：AI 域真实消费的流是 aiRng_
    //（键 9 = aiRng_ 真态），而 kAiSect 分区仅供 Kotlin 委托通道消费——
    // 阶段 1② 归一后 Kotlin 侧 AI 抽取改走通道/本地等价流，6 号键在两侧都不再
    // 是"AI 流态"的载体。继续导出会让跨语言对拍读到一条两侧序列本就不同的键
    //（Kotlin 6 号停在播种态 vs 宿主 6 号被 AI 演进推进），制造伪分歧。
    // 读档兼容不受影响：restoreStates 对未知/缺键按键集遍历，旧档的 6 号
    // 仍会被恢复进 kAiSect 分区（该分区无生产消费者，恢复与否无行为差异）。
    state_.gameData.rngStates.erase(static_cast<int32_t>(rng::RngPartition::kAiSect));
}

std::string GameCore::manualRecruitFromList(const std::string& discipleId) {
    noteNonSettlementMutation();   // R2/B09：非结算写入路径锁存回退全量导出
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
    noteNonSettlementMutation();   // R2/B09：非结算写入路径锁存回退全量导出
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

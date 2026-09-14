// ============================================================
// appointment_tx.h — 弟子管理三：长老任命/仓库驻守/洗炼消耗族事务
//
// batch-15（ui-read-surface §4.1 弟子管理·长老单值槽 / 仓库驻守 / 玉符段
// 写者下沉；语义权威 = 各 Kotlin 源文件，判定序与抽取序逐字对齐）：
//  - ElderManagementUseCase.assignElder/removeElder
//    （usecase 编排域——batch-08 登记留 W3 的长老单值槽任命；本头只承
//     elderSlots 数据写段 + 全槽清理数据段，Gate/checkpoint/状态同步残差
//     留 Kotlin）
//  - GameEngineWarehouseOps.assignWarehouseGarrisonAtomic
//    （仓库驻守 gameData.warehouseGarrisons 写段——与 worldMapSects[]
//     分舵驻守（batch-13 域）无关）
//  - GameEngineSpiritRootOps.washSpiritRoot（洗炼灵根：先扣后抽 + sealed
//     三态语义）
//  - GameEngineTraitAddOps.rollTraitAdd/confirmTraitAdd（新增 Roll/Confirm
//     两段——刷新即扣玉符 + pending 落盘；确认不扣费）
//  - GameEngineTraitWashOps.washTraitSlot（特质单槽洗炼；confirmTraitWash
//     /confirmSpiritRootWash 不在本批范围——纯数据写残差留 Kotlin）
//
// 玉符记账路线（batch-15 拍板，PR 声明）：**C++ 承扣**——余额检查 + 扣减
// 与抽取同一事务原子（失败臂零写入零抽取）；Kotlin native 臂成功后经
// JadeSymbolService.deduct 同步运行时 totalCount（守卫下幂等绝对值覆写），
// 消除 checkpointNow 回涨（CLAUDE.md 13.3 绝对值覆盖写模型）。Kotlin 臂
// 零 copy(jadeSymbols=)——JadeSymbolConsumptionGuardTest 零改动通过。
//
// RNG 契约（对拍命门——SYSTEM 分区，双臂同源逐位一致）：
//  - 任命/驻守/特质确认：**零抽取**（签名级：API 不接受 rng 参数）。
//  - washSpiritRoot：保底路径 5×nextInt()（仅元素洗牌）；普通路径
//    1×nextDouble + 5×nextInt()。失败臂（保底非法/弟子不存在/死亡/玉符
//    不足）在抽取前返回——零抽取零写入（防无谓消耗序列位移）。
//  - rollTraitAdd / washTraitSlot：1×nextDouble（品阶）+ 1×nextInt
//    （选择，randomOrNull 空池不消耗——预检保证非空）；洗炼保底路径
//    1×nextInt（池选择；过滤后池空 → 放弃产出 0 抽取）。失败臂同上零抽取。
//  - 抽取原语与 Kotlin 逐位同源：shuffled = 逐元素 1×nextInt() 后稳定排序
//    （RngExt.shuffled 非 Fisher-Yates）；random/randomOrNull =
//    DeterministicRng.nextInt(bound)（Lemire，low32 有符号比较）；品阶累计
//    阈值 0.40 / 0.7000000000000001 / 1.0（IEEE 双精度增量累加同序）。
//  - 候选池迭代序 = Kotlin allXxxData.values 插入序（正面 buildList 序 +
//    负面 buildList 序）——C++ trait_db.h 模板向量同序构建（对拍守卫
//    trait_db_test），过滤保序。
//
// 已知范围边界（对拍约定，disciple_tx.h / patrol_tx.h 同口径）：
//  - DiscipleAssignmentGate 登记/释放、syncSingleDiscipleStatus、
//    checkpointAllProduction/checkpointAllDisciples、Room 生产槽回放、
//    releaseDiscipleFromAllSlotsAtomic（状态重置域）为 Kotlin 运行态残差，
//    native 成功后照原序执行。
//  - 洗炼族结果只回传不落弟子；确认替换（confirmSpiritRootWash /
//    confirmTraitWash）留 Kotlin 原路径（纯数据写，无玉符/RNG 面）。
//  - 事务外 publishJadeSymbolStateNow + 运行时 totalCount 同步由 Kotlin
//    native 臂执行（玉符 UI 状态域）。
// ============================================================
#pragma once

#include <algorithm>
#include <cstdlib>
#include <cstdint>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/data/trait_db.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/disciple_store.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple_factory.h"    // realmMaxAge
#include "gamecore/system/disciple_stats.h"      // talent/affix effects（lifespan 同步）
#include "gamecore/system/settlement_detail.h"   // settle_util::toIntOrNull
#include "gamecore/system/slot_cleanup.h"        // clearAllSlotsDataOnly

namespace gamecore::system::appointment_tx {

/// 事务形参类型别名（detail 内另有 using，本层供事务签名使用）
using gamecore::state::GameState;

// ── GameConfig 常量（Kotlin GameConfig.SpiritRoot / TraitWash / TraitAdd）──

/// 洗炼保底阈值（SpiritRoot.WASH_PITY_THRESHOLD == TraitWash.WASH_PITY_THRESHOLD == 2）
inline constexpr int32_t kWashPityThreshold = 2;

/// SpiritRoot.WASH_DOUBLE_WEIGHT（双灵根概率）
inline constexpr double kSpiritRootDoubleWeight = 0.60;

/// TraitWash.TOP_RARITY（上品品阶）
inline constexpr int32_t kTraitWashTopRarity = 3;

/// TraitAdd.MAX_TRAITS_PER_CATEGORY（单类特质上限）
inline constexpr int32_t kMaxTraitsPerCategory = 5;

/// SpiritRoot.WASH_ELEMENT_KEYS（洗炼元素表——shuffled 抽取集）
inline const std::vector<std::string>& washElementKeys() {
    static const std::vector<std::string> kKeys = {
        "metal", "wood", "water", "fire", "earth",
    };
    return kKeys;
}

/// TalentDatabase.DEPRECATED_TALENT_TYPES（退役天赋类型——洗炼/新增候选池
/// 过滤，保底池同滤；退役超模条目不经任何玉符玩法路径回流）
inline const std::set<std::string>& deprecatedTalentTypes() {
    static const std::set<std::string> kTypes = {
        "CULT_SPEED", "BREAK_CHANCE", "LIFESPAN", "MANUAL_SLOT", "WIN_GROWTH",
    };
    return kTypes;
}

namespace detail {

using gamecore::state::DiscipleStore;
using gamecore::state::DirectDiscipleSlot;
using gamecore::state::ElderSlots;
using gamecore::state::GameState;

/// 弟子行解析（patrol_tx.h resolveDiscipleRow 同源：Kotlin 校验整数集合
/// 语义——toIntOrNull → canonical 字符串 → rowOf；非数字/不存在 → nullopt）
inline std::optional<std::pair<std::string, std::size_t>> resolveDiscipleRow(
    const DiscipleStore& ds, const std::string& discipleId) {
    const auto intId = settle_util::toIntOrNull(discipleId);
    if (!intId.has_value()) return std::nullopt;
    const std::string canonicalId = std::to_string(*intId);
    const auto row = ds.rowOf(canonicalId);
    if (!row.has_value()) return std::nullopt;
    return std::make_pair(canonicalId, *row);
}

/// 洗炼灵根串合法性（Kotlin `isValidWashedRootType` 逐字等价）：
/// `split(",")` 后 1~2 个元素、无重复、全部在 [washElementKeys] 内。
/// 空串/空白 → split 出空元素 → 不在元素表 → 拒绝（Kotlin `"".split(",")`
/// == `[""]` 同象）。
inline bool isValidWashedRootType(const std::string& newRootType) {
    std::vector<std::string> parts;
    std::size_t start = 0;
    while (true) {
        const std::size_t comma = newRootType.find(',', start);
        const std::size_t endPos =
            (comma == std::string::npos) ? newRootType.size() : comma;
        parts.push_back(newRootType.substr(start, endPos - start));
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    if (parts.empty() || parts.size() > 2) return false;
    const std::vector<std::string>& keys = washElementKeys();
    for (std::size_t i = 0; i < parts.size(); ++i) {
        if (std::find(keys.begin(), keys.end(), parts[i]) == keys.end()) return false;
        for (std::size_t j = i + 1; j < parts.size(); ++j) {
            if (parts[i] == parts[j]) return false;  // distinct 语义
        }
    }
    return true;
}

/// 11 类槽位清理（disciple_tx.h / patrol_tx.h detail 同族——本头文件独立
/// 提供，保持各 tx 头自包含；includeResidence=false 工作分配语义）
inline void clearAllDiscipleSlots(GameState& state, const std::string& discipleId) {
    gamecore::system::SlotCleanupInput in;
    in.spiritMineSlots = state.gameData.spiritMineSlots;
    in.librarySlots = state.gameData.librarySlots;
    in.elderSlots = state.gameData.elderSlots;
    in.residenceSlots = state.gameData.residenceSlots;
    in.activeBloodRefinements = state.gameData.activeBloodRefinements;
    in.patrolSlots = state.gameData.patrolSlots;
    in.warehouseGarrisons = state.gameData.warehouseGarrisons;
    in.battleTeams = state.gameData.battleTeams;
    in.worldMapSects = state.gameData.worldMapSects;
    in.productionSlots = state.gameData.productionSlots;
    in.caveExplorationTeams = state.gameData.caveExplorationTeams;
    in.activeMissions =
        gamecore::system::toMissionLiteList(state.gameData.activeMissions);
    const auto out =
        gamecore::system::clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/false);
    state.gameData.spiritMineSlots = out.spiritMineSlots;
    state.gameData.librarySlots = out.librarySlots;
    state.gameData.elderSlots = out.elderSlots;
    state.gameData.residenceSlots = out.residenceSlots;
    state.gameData.activeBloodRefinements = out.activeBloodRefinements;
    state.gameData.patrolSlots = out.patrolSlots;
    state.gameData.warehouseGarrisons = out.warehouseGarrisons;
    state.gameData.battleTeams = out.battleTeams;
    state.gameData.worldMapSects = out.worldMapSects;
    state.gameData.productionSlots = out.productionSlots;
    state.gameData.caveExplorationTeams = out.caveExplorationTeams;
    state.gameData.activeMissions =
        gamecore::system::mergeMissionLiteList(state.gameData.activeMissions,
                                               out.activeMissions);
}

// ── 长老单值槽族（ElderManagementUseCase 的 10 字段穷举）────────────────

/// ElderSlotType.name → 槽位字段指针（未知类型 nullptr——协议违规防御臂）
inline std::string* elderFieldOf(ElderSlots& slots, const std::string& slotType) {
    if (slotType == "VICE_SECT_MASTER") return &slots.viceSectMaster;
    if (slotType == "HERB_GARDEN") return &slots.herbGardenElder;
    if (slotType == "ALCHEMY") return &slots.alchemyElder;
    if (slotType == "FORGE") return &slots.forgeElder;
    if (slotType == "OUTER_ELDER") return &slots.outerElder;
    if (slotType == "PREACHING") return &slots.preachingElder;
    if (slotType == "LAW_ENFORCEMENT") return &slots.lawEnforcementElder;
    if (slotType == "INNER_ELDER") return &slots.innerElder;
    if (slotType == "RECRUITING") return &slots.recruitingElder;
    if (slotType == "CLOUD_PREACHING") return &slots.qingyunPreachingElder;
    return nullptr;
}

/// 任命时被清空的亲传列表（ElderManagementUseCase
/// SLOT_TYPES_CLEARING_DIRECT_DISCIPLES 六类；其余类型 nullptr = 不清列表）。
/// spiritMineDeaconDisciples（第 7 列表）不在任命清空族——仅全槽清理触达。
inline std::vector<DirectDiscipleSlot>* elderClearedListOf(
    ElderSlots& slots, const std::string& slotType) {
    if (slotType == "HERB_GARDEN") return &slots.herbGardenDisciples;
    if (slotType == "ALCHEMY") return &slots.alchemyDisciples;
    if (slotType == "FORGE") return &slots.forgeDisciples;
    if (slotType == "PREACHING") return &slots.preachingMasters;
    if (slotType == "LAW_ENFORCEMENT") return &slots.lawEnforcementDisciples;
    if (slotType == "CLOUD_PREACHING") return &slots.qingyunPreachingMasters;
    return nullptr;
}

/// 亲传列表在岗弟子 id（Kotlin getClearedDirectList：discipleId.ifEmpty 跳过）
inline std::vector<std::string> directListIds(
    const std::vector<DirectDiscipleSlot>& list) {
    std::vector<std::string> ids;
    for (const auto& slot : list) {
        if (!slot.discipleId.empty()) ids.push_back(slot.discipleId);
    }
    return ids;
}

// ── 特质族（TraitWashType TALENT/PHYSIQUE/AFFIX；池序 = Kotlin 插入序）────

enum class TraitKind { kTalent, kPhysique, kAffix };

/// TraitWashType.name → kind（未知 nullptr 语义 → nullopt）
inline std::optional<TraitKind> traitKindOf(const std::string& type) {
    if (type == "TALENT") return TraitKind::kTalent;
    if (type == "PHYSIQUE") return TraitKind::kPhysique;
    if (type == "AFFIX") return TraitKind::kAffix;
    return std::nullopt;
}

/// 洗炼/新增引擎内部条目（Kotlin TraitWashEntry：id/品阶/template）
struct TraitEntry {
    std::string id;
    int32_t rarity = 0;
    std::string tmpl;
};

/// 弟子特质 id 列（TraitWashType.idsOf）
inline const std::vector<std::string>& traitIdsOf(
    const gamecore::state::DiscipleStore& ds, std::size_t row, TraitKind kind) {
    if (kind == TraitKind::kTalent) return ds.talentIds[row];
    if (kind == TraitKind::kPhysique) return ds.physiqueIds[row];
    return ds.affixIds[row];
}

inline std::vector<std::string>& traitIdsOf(
    gamecore::state::DiscipleStore& ds, std::size_t row, TraitKind kind) {
    if (kind == TraitKind::kTalent) return ds.talentIds[row];
    if (kind == TraitKind::kPhysique) return ds.physiqueIds[row];
    return ds.affixIds[row];
}

/// TraitWashType.resolveOne：id → 条目（未知 id nullopt——排除集构造时跳过）
inline std::optional<TraitEntry> resolveOne(TraitKind kind, const std::string& id) {
    using gamecore::data::affixById;
    using gamecore::data::physiqueById;
    using gamecore::data::talentById;
    if (kind == TraitKind::kTalent) {
        const auto t = talentById(id);
        if (!t.has_value()) return std::nullopt;
        return TraitEntry{t->id, t->rarity, t->tmpl};
    }
    if (kind == TraitKind::kPhysique) {
        const auto p = physiqueById(id);
        if (!p.has_value()) return std::nullopt;
        return TraitEntry{p->id, p->rarity, p->tmpl};
    }
    const auto a = affixById(id);
    if (!a.has_value()) return std::nullopt;
    return TraitEntry{a->id, a->rarity, a->tmpl};
}

/// 洗炼/新增候选池（Kotlin rollSingleTalent/rollSinglePhysique/rollSingleAffix
/// 的 candidates 过滤段：非负面 + template 不在排除集（+ 天赋退役类型滤除）；
/// 迭代序 = Kotlin allXxxData.values 插入序——C++ 模板向量同序构建，过滤保序）
inline std::vector<TraitEntry> rollCandidatesOf(
    TraitKind kind, const std::set<std::string>& excludedTemplates) {
    std::vector<TraitEntry> out;
    if (kind == TraitKind::kTalent) {
        for (const auto& t : gamecore::data::talentTemplates()) {
            if (t.isNegative) continue;
            if (deprecatedTalentTypes().count(t.type) != 0) continue;
            if (excludedTemplates.count(t.tmpl) != 0) continue;
            out.push_back(TraitEntry{t.id, t.rarity, t.tmpl});
        }
    } else if (kind == TraitKind::kPhysique) {
        for (const auto& p : gamecore::data::physiqueTemplates()) {
            if (p.isNegative) continue;
            if (excludedTemplates.count(p.tmpl) != 0) continue;
            out.push_back(TraitEntry{p.id, p.rarity, p.tmpl});
        }
    } else {
        for (const auto& a : gamecore::data::affixTemplates()) {
            if (a.isNegative) continue;
            if (excludedTemplates.count(a.tmpl) != 0) continue;
            out.push_back(TraitEntry{a.id, a.rarity, a.tmpl});
        }
    }
    return out;
}

/// 保底池（Kotlin pickTopByType：TOP_RARITY=3 正向池——天赋另滤退役类型；
/// 再按已用 template 过滤由调用方完成）
inline std::vector<TraitEntry> topRarityPoolOf(TraitKind kind) {
    std::vector<TraitEntry> pool;
    if (kind == TraitKind::kTalent) {
        for (const auto& t : gamecore::data::talentTemplates()) {
            if (t.rarity != kTraitWashTopRarity || t.isNegative) continue;
            if (deprecatedTalentTypes().count(t.type) != 0) continue;
            pool.push_back(TraitEntry{t.id, t.rarity, t.tmpl});
        }
    } else if (kind == TraitKind::kPhysique) {
        for (const auto& p : gamecore::data::physiqueTemplates()) {
            if (p.rarity != kTraitWashTopRarity || p.isNegative) continue;
            pool.push_back(TraitEntry{p.id, p.rarity, p.tmpl});
        }
    } else {
        for (const auto& a : gamecore::data::affixTemplates()) {
            if (a.rarity != kTraitWashTopRarity || a.isNegative) continue;
            pool.push_back(TraitEntry{a.id, a.rarity, a.tmpl});
        }
    }
    return pool;
}

/// List.random/randomOrNull 等价（asKotlinRandom.nextInt(bound) → Lemire）；
/// 空池返回 nullopt **不消耗抽取**（Kotlin randomOrNull 空池早退同语义）
inline std::optional<TraitEntry> randomOf(const std::vector<TraitEntry>& pool,
                                          gamecore::rng::DeterministicRng& rng) {
    if (pool.empty()) return std::nullopt;
    return pool[rng.nextInt(static_cast<int32_t>(pool.size()))];
}

/// 洗炼/新增品阶分布抽取（Kotlin rollCumulative(WASH_TRAIT_QUALITY_DISTRIBUTION)
/// 等价：单次 nextDouble，累计阈值 0.40 / 0.4+0.3 / 1.0——IEEE 增量累加同序；
/// 浮点兜底末档与 Kotlin 一致性由权重和恒 1.0 保证（roll < 1.0 恒命中末档前））
inline int32_t rollWashTraitQuality(gamecore::rng::DeterministicRng& rng) {
    const double roll = rng.nextDouble();
    const double kDistribution[3] = {0.40, 0.30, 0.30};
    double cumulative = 0.0;
    for (int32_t i = 0; i < 3; ++i) {
        cumulative += kDistribution[i];
        if (roll <= cumulative) return i + 1;
    }
    return 3;  // 浮点兜底末档（不可达——roll < 1.0）
}

/// 按洗炼/新增分布从正向候选池抽取一条（Kotlin pickPositiveWash 等价：
/// 品阶精确匹配优先；无精确匹配取差值最小档（平局取较高档）。
/// 抽取次数：1×nextDouble + 恰好 1×nextInt（两分支等消费——与 Kotlin
/// randomOrNull 不可达分支的"空池不消耗"语义在可达域内一致）；
/// candidates 空为调用方契约违规（预检保证），防御返回 nullopt）
inline std::optional<TraitEntry> pickPositiveWash(
    const std::vector<TraitEntry>& candidates, gamecore::rng::DeterministicRng& rng) {
    if (candidates.empty()) return std::nullopt;
    const int32_t quality = rollWashTraitQuality(rng);
    std::vector<TraitEntry> exact;
    for (const auto& c : candidates) {
        if (c.rarity == quality) exact.push_back(c);
    }
    if (!exact.empty()) return randomOf(exact, rng);
    // 差值最小档（平局取较高档）——compareBy { abs(it - quality) }.thenByDescending { it }
    int32_t fallbackRarity = candidates.front().rarity;
    for (const auto& c : candidates) {
        const int32_t curDist = std::abs(c.rarity - quality);
        const int32_t bestDist = std::abs(fallbackRarity - quality);
        if (curDist < bestDist || (curDist == bestDist && c.rarity > fallbackRarity)) {
            fallbackRarity = c.rarity;
        }
    }
    std::vector<TraitEntry> fallback;
    for (const auto& c : candidates) {
        if (c.rarity == fallbackRarity) fallback.push_back(c);
    }
    return randomOf(fallback, rng);
}

/// 天赋 + 词条 lifespan 效果合计（GameEngineTraitWashOps.lifespanBonusOf 同源：
/// calculateTalentEffects/calculateAffixEffects 的 "lifespan" 键和，缺失 0.0）
inline double lifespanBonusOf(const std::vector<std::string>& talentIds,
                              const std::vector<std::string>& affixIds) {
    using gamecore::stats::affixEffectsFor;
    using gamecore::stats::effectValue;
    using gamecore::stats::talentEffectsFor;
    return effectValue(talentEffectsFor(talentIds), "lifespan") +
           effectValue(affixEffectsFor(affixIds), "lifespan");
}

}  // namespace detail

// ── 结果信封（失败零写入；failure → Kotlin 回退原路径重执行校验链）────────

struct TxResult {
    bool ok = false;
    std::string errorType;
    std::string message;
};

/// 长老任命结果：附被顶替者 id（原样追加序 = Kotlin collectReplacedIds：
/// 旧长老在前 + 被清空亲传列表成员在后；distinct 与 != appointee 过滤在
/// Kotlin 残差 releaseReplacedIds 应用）
struct ElderAppointOutcome {
    TxResult base;
    std::vector<std::string> replacedIds;
};

/// 长老卸任结果：附被卸任者 id（空串 = 槽原本无人）
struct ElderDismissOutcome {
    TxResult base;
    std::string removedId;
};

/// 仓库驻守结果：附覆写前旧 occupant（空串 = 无）
struct WarehouseAssignOutcome {
    TxResult base;
    std::string oldOccupantId;
};

/// 洗炼灵根结果（Kotlin SpiritRootWashResult.Success 字段面）
struct SpiritRootWashOutcome {
    TxResult base;
    std::string newRootType;
    int32_t newPityCount = 0;
    int32_t jadeAfter = 0;
};

/// 新增特质刷新结果（Kotlin TraitAddResult.Success 字段面）
struct TraitRollOutcome {
    TxResult base;
    std::string newId;
    int32_t jadeAfter = 0;
};

/// 特质单槽洗炼结果（Kotlin TraitWashResult.Success 字段面）
struct TraitWashOutcome {
    TxResult base;
    std::string newId;
    int32_t newPityCount = 0;
    int32_t jadeAfter = 0;
};

// ── 事务 1：长老单值槽任命（ElderManagementUseCase.assignElder 写段）──────
//
// 判定序（Kotlin 原序）：弟子存在 → 存活 →（Kotlin 残差已先行执行
// releaseDiscipleFromAllSlotsAtomic——本事务内自带同语义数据清理段，双臂
// 幂等）→ 全槽清理（11 类，includeResidence=false）→ 捕获被顶替者（清后、
// 覆写前）→ 写槽位字段 + 清空对应亲传列表（六类）。
inline ElderAppointOutcome elderAppointTx(gamecore::state::GameState& state,
                                          const std::string& slotType,
                                          const std::string& discipleId) {
    ElderAppointOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // 1. 校验链（Kotlin UseCase 预校验同序：存在 → 存活）。
    //    **原样字符串相等语义**——Kotlin `disciples.find { it.id == discipleId }`
    //    不做 toIntOrNull canonical 化（与仓库/洗炼族的整数集合判定不同源），
    //    "0123" 类非 canonical 输入在此不存在。
    const auto row = ds.rowOf(discipleId);
    if (!row.has_value()) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在 " + discipleId;
        return out;
    }
    if (ds.isAlive[*row] == 0) {
        out.base.errorType = "NotAlive";
        out.base.message = "弟子已死亡 " + discipleId;
        return out;
    }
    // 2. 槽位类型解析（ElderSlotType 为十值枚举，未知 = 协议违规防御臂）
    std::string* field = detail::elderFieldOf(gd.elderSlots, slotType);
    if (field == nullptr) {
        out.base.errorType = "UnknownSlotType";
        out.base.message = "未知长老槽位 " + slotType;
        return out;
    }
    // 3. 全槽清理（appointee 旧槽位数据段——Kotlin releaseDiscipleFromAllSlots
    //    Atomic 数据面等价，原样字符串匹配；Gate/状态重置残差留 Kotlin）
    detail::clearAllDiscipleSlots(state, discipleId);
    // 4. 捕获被顶替者（清后、覆写前——Kotlin collectReplacedIds 快照序）
    if (!field->empty() && *field != discipleId) out.replacedIds.push_back(*field);
    if (std::vector<gamecore::state::DirectDiscipleSlot>* cleared =
            detail::elderClearedListOf(gd.elderSlots, slotType)) {
        for (const std::string& id : detail::directListIds(*cleared)) {
            out.replacedIds.push_back(id);
        }
    }
    // 5. 写段：槽位字段 + 清空对应亲传列表（buildElderSlotsWithAppointment
    //    等价——写入原样 discipleId，与 Kotlin 快照组装同源）
    *field = discipleId;
    if (std::vector<gamecore::state::DirectDiscipleSlot>* cleared =
            detail::elderClearedListOf(gd.elderSlots, slotType)) {
        cleared->clear();
    }
    out.base.ok = true;
    return out;
}

// ── 事务 2：长老单值槽卸任（ElderManagementUseCase.removeElder 写段）──────
//
// 写段：字段清空 + 对应亲传列表清空（六类）。**不**做全槽清理（Kotlin 原样）
// ——被卸任者的其他槽位由其自身分配流清理。
inline ElderDismissOutcome elderDismissTx(gamecore::state::GameState& state,
                                          const std::string& slotType) {
    ElderDismissOutcome out;
    auto& gd = state.gameData;
    std::string* field = detail::elderFieldOf(gd.elderSlots, slotType);
    if (field == nullptr) {
        out.base.errorType = "UnknownSlotType";
        out.base.message = "未知长老槽位 " + slotType;
        return out;
    }
    out.removedId = *field;
    field->clear();
    if (std::vector<gamecore::state::DirectDiscipleSlot>* cleared =
            detail::elderClearedListOf(gd.elderSlots, slotType)) {
        cleared->clear();
    }
    out.base.ok = true;
    return out;
}

// ── 事务 3：仓库驻守分配（GameEngineWarehouseOps.assignWarehouseGarrison
// Atomic 事务段）────────────────────────────────────────────────────────────
//
// 判定序（Kotlin 原序）：弟子存在 → 存活 → 旧 occupant 捕获（覆写前）→
// 全槽清理（防多槽位）→ 移除同建筑实例条目 + 追加新条目。
// **原样字符串语义**：清理与写入用 Kotlin 原参 discipleId（校验行解析用
// canonical——patrol_tx.h 双轨同款口径）。
inline WarehouseAssignOutcome warehouseGarrisonAssignTx(
    gamecore::state::GameState& state, const std::string& buildingInstanceId,
    const std::string& discipleId, const std::string& discipleName,
    const std::string& sectId) {
    WarehouseAssignOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // 1. 校验链（Kotlin require 同序：id 整数合法且在弟子集合 + 存活）
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在: " + discipleId;
        return out;
    }
    if (ds.isAlive[resolved->second] == 0) {
        out.base.errorType = "NotAlive";
        out.base.message = "弟子已死亡: " + discipleId;
        return out;
    }
    // 2. 旧 occupant 捕获（覆写前——Kotlin 事务内读取防快照竞态同序）
    for (const auto& garrison : gd.warehouseGarrisons) {
        if (garrison.buildingInstanceId == buildingInstanceId) {
            out.oldOccupantId = garrison.discipleId;
            break;
        }
    }
    // 3. 全槽清理（含原样字符串匹配——clearAllSlotsDataOnly 语义同 Kotlin）
    detail::clearAllDiscipleSlots(state, discipleId);
    // 4. 移除同建筑条目 + 追加（filter + append 等价；slotIndex 缺省 0）
    std::vector<gamecore::state::WarehouseGarrisonSlot> kept;
    kept.reserve(gd.warehouseGarrisons.size());
    for (auto& garrison : gd.warehouseGarrisons) {
        if (garrison.buildingInstanceId != buildingInstanceId) {
            kept.push_back(std::move(garrison));
        }
    }
    gd.warehouseGarrisons = std::move(kept);
    gamecore::state::WarehouseGarrisonSlot slot;
    slot.buildingInstanceId = buildingInstanceId;
    slot.discipleId = discipleId;
    slot.discipleName = discipleName;
    slot.sectId = sectId;
    gd.warehouseGarrisons.push_back(std::move(slot));
    out.base.ok = true;
    return out;
}

// ── 事务 4：洗炼灵根（GameEngineSpiritRootOps.washSpiritRoot 事务段）──────
//
// 判定序：弟子存在 → 存活 → 玉符余额 → 扣减 → 保底判定抽取（先扣后抽；
// 全部失败臂在抽取前返回——零抽取零写入）。
// 抽取序：保底路径 5×nextInt()（仅元素洗牌）；普通路径 1×nextDouble
// （双灵根判定）+ 5×nextInt()（元素洗牌）。保底计数：单灵根归零，双灵根 +1
//（与 Kotlin rollSpiritRootWash 一致——保底路径产物必为单灵根故归零）。
inline SpiritRootWashOutcome spiritRootWashTx(gamecore::state::GameState& state,
                                              gamecore::rng::DeterministicRng& rng,
                                              const std::string& discipleId,
                                              int32_t pityCount, int32_t cost) {
    SpiritRootWashOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // 0. 保底计数防御（Kotlin 非法保底计数前置拒绝——native 臂同样前置）
    if (pityCount < 0) {
        out.base.errorType = "InvalidPity";
        out.base.message = "非法保底计数";
        return out;
    }
    // 1. 校验链
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在";
        return out;
    }
    if (ds.isAlive[resolved->second] != 1) {
        out.base.errorType = "NotAlive";
        out.base.message = "弟子已死亡";
        return out;
    }
    // 2. 玉符余额 + 扣减（C++ 承扣——与抽取同事务原子；Kotlin 残差同步运行时）
    if (gd.jadeSymbols < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "玉符不足";
        return out;
    }
    gd.jadeSymbols -= cost;
    out.jadeAfter = gd.jadeSymbols;
    // 3. 抽取（rollSpiritRootWash 逐字对齐：保底路径零 nextDouble；洗牌恒消费）
    int32_t rootCount;
    if (pityCount >= kWashPityThreshold) {
        rootCount = 1;  // 保底：必出单灵根（不消耗 nextDouble 判定）
    } else if (rng.nextDouble() < kSpiritRootDoubleWeight) {
        rootCount = 2;
    } else {
        rootCount = 1;
    }
    // shuffled = 逐元素 1×nextInt() 后稳定排序（RngExt.shuffled 同源，非 Fisher-Yates）
    const std::vector<std::string>& keys = washElementKeys();
    std::vector<std::pair<int32_t, std::size_t>> keyed;
    keyed.reserve(keys.size());
    for (std::size_t i = 0; i < keys.size(); ++i) {
        keyed.emplace_back(rng.nextInt(), i);
    }
    std::stable_sort(keyed.begin(), keyed.end(),
                     [](const auto& a, const auto& b) { return a.first < b.first; });
    std::string rootType;
    for (int32_t i = 0; i < rootCount && i < static_cast<int32_t>(keyed.size()); ++i) {
        if (i > 0) rootType += ",";
        rootType += keys[keyed[static_cast<std::size_t>(i)].second];
    }
    out.newRootType = rootType;
    out.newPityCount = rootCount == 1 ? 0 : pityCount + 1;
    out.base.ok = true;
    return out;
}

// ── 事务 5：新增特质刷新（GameEngineTraitAddOps.rollTraitAdd 事务段）──────
//
// 判定序（Kotlin rollTraitAddInner 原序）：存在 → 存活 → 上限（5）→
// 排除集（已有槽位 template）→ 候选预检（无消耗）→ 玉符余额 → 扣减 → 抽取
// → pending 落盘（同 (disciple,type) 覆盖）。
// 抽取序：1×nextDouble（品阶）+ 1×nextInt（选择）。
inline TraitRollOutcome traitAddRollTx(gamecore::state::GameState& state,
                                       gamecore::rng::DeterministicRng& rng,
                                       const std::string& discipleId,
                                       const std::string& type, int32_t cost) {
    TraitRollOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    const auto kind = detail::traitKindOf(type);
    if (!kind.has_value()) {
        out.base.errorType = "UnknownTraitType";
        out.base.message = "未知特质类型 " + type;
        return out;
    }
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在";
        return out;
    }
    const std::string canonicalId = resolved->first;
    const std::size_t row = resolved->second;
    if (ds.isAlive[row] != 1) {
        out.base.errorType = "NotAlive";
        out.base.message = "弟子已死亡";
        return out;
    }
    // 上限校验（单类特质最多 5 个）
    const std::vector<std::string>& currentIds = detail::traitIdsOf(ds, row, *kind);
    if (static_cast<int32_t>(currentIds.size()) >= kMaxTraitsPerCategory) {
        out.base.errorType = "SLOTS_FULL";
        out.base.message = "该弟子特质已满";
        return out;
    }
    // 排除集（新增不得与已有槽位 template 冲突；未知 id 跳过——resolveOne 语义）
    std::set<std::string> excludedTemplates;
    for (const std::string& keptId : currentIds) {
        const auto kept = detail::resolveOne(*kind, keptId);
        if (kept.has_value()) excludedTemplates.insert(kept->tmpl);
    }
    // 扣费前候选预检（无随机消耗）
    const std::vector<detail::TraitEntry> candidates =
        detail::rollCandidatesOf(*kind, excludedTemplates);
    if (candidates.empty()) {
        out.base.errorType = "NO_CANDIDATE";
        out.base.message = "暂无可用新增结果";
        return out;
    }
    // 玉符余额 + 扣减（先扣后抽）
    if (gd.jadeSymbols < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "玉符不足";
        return out;
    }
    gd.jadeSymbols -= cost;
    out.jadeAfter = gd.jadeSymbols;
    // 抽取（预检与抽取同池同过滤——恒非空；防御臂与 Kotlin 同构）
    const auto entry = detail::pickPositiveWash(candidates, rng);
    if (!entry.has_value()) {
        // 理论不可达（同池同过滤）；防御语义：玉符已扣、不写 pending——
        // failure 信封 → Kotlin 回退臂重执行（Kotlin 原分支同样扣费返错）
        out.base.errorType = "NO_CANDIDATE";
        out.base.message = "暂无可用新增结果";
        return out;
    }
    // pending 落盘：同 (disciple, type) 已有产物则覆盖（继续消耗刷新），其余保留
    std::vector<gamecore::state::PendingTraitAdd> kept;
    kept.reserve(gd.pendingTraitAdds.size());
    for (auto& pending : gd.pendingTraitAdds) {
        if (!(pending.discipleId == canonicalId && pending.type == type)) {
            kept.push_back(std::move(pending));
        }
    }
    gd.pendingTraitAdds = std::move(kept);
    gamecore::state::PendingTraitAdd pendingAdd;
    pendingAdd.discipleId = canonicalId;
    pendingAdd.type = type;
    pendingAdd.traitId = entry->id;
    gd.pendingTraitAdds.push_back(std::move(pendingAdd));
    out.newId = entry->id;
    out.base.ok = true;
    return out;
}

// ── 事务 6：新增特质确认（GameEngineTraitAddOps.confirmTraitAdd 事务段）───
//
// 判定序：存在 → 存活 → 上限 → 合法性（产物可解析 + 不在列表 + template
// 不重复）→ 追加 + lifespan 同步 + checkpoint + 清 pending。
// **零抽取**（纯数据写事务——API 不接受 rng 参数）。
inline TxResult traitAddConfirmTx(gamecore::state::GameState& state,
                                  const std::string& discipleId,
                                  const std::string& type, const std::string& newId) {
    TxResult out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    const auto kind = detail::traitKindOf(type);
    if (!kind.has_value()) {
        out.errorType = "UnknownTraitType";
        out.message = "未知特质类型 " + type;
        return out;
    }
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.errorType = "NotFound";
        out.message = "弟子不存在";
        return out;
    }
    const std::string canonicalId = resolved->first;
    const std::size_t row = resolved->second;
    if (ds.isAlive[row] != 1) {
        out.errorType = "NotAlive";
        out.message = "弟子已死亡";
        return out;
    }
    std::vector<std::string>& currentIds = detail::traitIdsOf(ds, row, *kind);
    if (static_cast<int32_t>(currentIds.size()) >= kMaxTraitsPerCategory) {
        out.errorType = "SLOTS_FULL";
        out.message = "该弟子特质已满";
        return out;
    }
    // 合法性校验（isValidTraitAdd：产物可解析 + 不在当前列表 + template 不重复）
    const auto newEntry = detail::resolveOne(*kind, newId);
    if (!newEntry.has_value() ||
        std::find(currentIds.begin(), currentIds.end(), newId) != currentIds.end()) {
        out.errorType = "INVALID";
        out.message = "该特质已无法新增";
        return out;
    }
    std::set<std::string> currentTemplates;
    for (const std::string& keptId : currentIds) {
        const auto kept = detail::resolveOne(*kind, keptId);
        if (kept.has_value()) currentTemplates.insert(kept->tmpl);
    }
    if (currentTemplates.count(newEntry->tmpl) != 0) {
        out.errorType = "INVALID";
        out.message = "该特质已无法新增";
        return out;
    }
    // lifespan 同步（syncLifespanForTraitChange：新加成差 × 境界基准寿命折算，
    // toInt 截断；delta==0 跳过；lifespan 下限 1）——旧列表先捕获再追加
    const std::vector<std::string> oldTalentIds = ds.talentIds[row];
    const std::vector<std::string> oldAffixIds = ds.affixIds[row];
    currentIds.push_back(newId);
    const double bonusDiff =
        detail::lifespanBonusOf(ds.talentIds[row], ds.affixIds[row]) -
        detail::lifespanBonusOf(oldTalentIds, oldAffixIds);
    const int32_t delta =
        static_cast<int32_t>(static_cast<double>(realmMaxAge(ds.realms[row])) *
                             bonusDiff);
    if (delta != 0) {
        const int32_t updated = ds.lifespans[row] + delta;
        ds.lifespans[row] = updated > 1 ? updated : 1;
    }
    // checkpoint 重记账（体质/词条影响修炼速率——追加瞬间重新投影基准）
    ds.cultivationCheckpoints[row] = ds.cultivations[row];
    ds.cultivationCheckpointGameMonths[row] = gd.gameYear * 12 + gd.gameMonth;
    // 清 pending（产物已落盘到弟子）
    std::vector<gamecore::state::PendingTraitAdd> kept;
    kept.reserve(gd.pendingTraitAdds.size());
    for (auto& pending : gd.pendingTraitAdds) {
        if (!(pending.discipleId == canonicalId && pending.type == type)) {
            kept.push_back(std::move(pending));
        }
    }
    gd.pendingTraitAdds = std::move(kept);
    out.ok = true;
    return out;
}

// ── 事务 7：特质单槽洗炼（GameEngineTraitWashOps.washTraitSlot 事务段）────
//
// 判定序（Kotlin washSlotInner 原序）：存在 → 存活 → 目标在列表 → 排除集
// （含目标自身——禁止"刷回原样"）→ 候选预检 → 玉符余额 → 扣减 → 保底判定
// 抽取。
// 抽取序：保底路径 1×nextInt（保底池选择；过滤后池空 → 放弃产出零消耗）；
// 普通路径 1×nextDouble + 1×nextInt。
inline TraitWashOutcome traitWashSlotTx(gamecore::state::GameState& state,
                                        gamecore::rng::DeterministicRng& rng,
                                        const std::string& discipleId,
                                        const std::string& type,
                                        const std::string& targetId,
                                        int32_t pityCount, int32_t cost) {
    TraitWashOutcome out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    if (pityCount < 0) {
        out.base.errorType = "InvalidPity";
        out.base.message = "非法保底计数";
        return out;
    }
    const auto kind = detail::traitKindOf(type);
    if (!kind.has_value()) {
        out.base.errorType = "UnknownTraitType";
        out.base.message = "未知特质类型 " + type;
        return out;
    }
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.base.errorType = "NotFound";
        out.base.message = "弟子不存在";
        return out;
    }
    const std::size_t row = resolved->second;
    if (ds.isAlive[row] != 1) {
        out.base.errorType = "NotAlive";
        out.base.message = "弟子已死亡";
        return out;
    }
    const std::vector<std::string>& currentIds = detail::traitIdsOf(ds, row, *kind);
    if (std::find(currentIds.begin(), currentIds.end(), targetId) == currentIds.end()) {
        out.base.errorType = "INVALID_TARGET";
        out.base.message = "该特质已不存在";
        return out;
    }
    // 排除集（已有槽位 template，含目标槽位自身）
    std::set<std::string> excludedTemplates;
    for (const std::string& currentId : currentIds) {
        const auto current = detail::resolveOne(*kind, currentId);
        if (current.has_value()) excludedTemplates.insert(current->tmpl);
    }
    // 扣费前候选预检（无随机消耗）
    const std::vector<detail::TraitEntry> candidates =
        detail::rollCandidatesOf(*kind, excludedTemplates);
    if (candidates.empty()) {
        out.base.errorType = "NO_CANDIDATE";
        out.base.message = "暂无可用洗炼结果";
        return out;
    }
    // 玉符余额 + 扣减（先扣后抽）
    if (gd.jadeSymbols < cost) {
        out.base.errorType = "INSUFFICIENT_JADE";
        out.base.message = "玉符不足";
        return out;
    }
    gd.jadeSymbols -= cost;
    out.jadeAfter = gd.jadeSymbols;
    // 抽取（rollSingleTraitWash 逐字对齐：保底路径走 3 阶正向池）
    std::optional<detail::TraitEntry> entry;
    if (pityCount >= kWashPityThreshold) {
        std::vector<detail::TraitEntry> pool;
        for (const auto& top : detail::topRarityPoolOf(*kind)) {
            if (excludedTemplates.count(top.tmpl) == 0) pool.push_back(top);
        }
        entry = detail::randomOf(pool, rng);  // 池空 → 放弃产出（零消耗）
    } else {
        entry = detail::pickPositiveWash(candidates, rng);
    }
    // 保底计数：产物含 3 阶归零；否则 +1 封顶阈值（防整数域放大）
    const bool hasTop = entry.has_value() && entry->rarity == kTraitWashTopRarity;
    out.newPityCount =
        hasTop ? 0 : std::min(pityCount + 1, kWashPityThreshold);
    // 预检保证候选非空；保底放弃产出时兜底目标不变（Kotlin roll.newId ?: targetId）
    out.newId = entry.has_value() ? entry->id : targetId;
    out.base.ok = true;
    return out;
}

// ── 事务 8：洗炼灵根确认替换（GameEngineSpiritRootOps.confirmSpiritRootWash）──
//
// Kotlin 源语义（GameEngineSpiritRootOps.kt:138）：
//   1) isValidWashedRootType(newRootType) —— split(",") 后 1~2 个元素、
//      无重复、全部在 WASH_ELEMENT_KEYS 内（防外部篡改写入；空串/空白会被
//      split 出的空元素自然拒绝——Kotlin `"".split(",")` == [""] → 空元素
//      不在元素表 → 拒绝）
//   2) discipleId.toIntOrNull()（非法 → 拒绝）
//   3) 事务内：存在 → 存活 → spiritRootType 覆写 → checkpointDisciple
//      （灵根影响修炼速率——替换瞬间重新记账，见 CheckpointCallSiteGuardTest）
//
// 校验失败语义与 Kotlin 一致：非法灵根串/非法弟子 ID → 拒绝且零写入。
// **零 RNG**（纯数据写；API 不接受 rng 参数）。
inline TxResult spiritRootWashConfirmTx(GameState& state,
                                        const std::string& discipleId,
                                        const std::string& newRootType) {
    TxResult out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    // 1) 灵根串合法性（isValidWashedRootType 逐字等价）
    if (!detail::isValidWashedRootType(newRootType)) {
        out.errorType = "INVALID_ROOT";
        out.message = "非法灵根数据";
        return out;
    }
    // 2) 弟子 id 解析（toIntOrNull → canonical → rowOf）
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.errorType = "NotFound";
        out.message = "弟子不存在";
        return out;
    }
    const std::size_t row = resolved->second;
    if (ds.isAlive[row] != 1) {
        out.errorType = "InvalidId";
        out.message = "弟子不存在";
        return out;
    }
    // 3) 覆写 + checkpoint 重记账
    ds.spiritRootTypes[row] = newRootType;
    ds.cultivationCheckpoints[row] = ds.cultivations[row];
    ds.cultivationCheckpointGameMonths[row] = gd.gameYear * 12 + gd.gameMonth;
    out.ok = true;
    return out;
}

// ── 事务 9：特质单槽确认替换（GameEngineTraitWashOps.confirmTraitWash）────
//
// Kotlin 源语义（GameEngineTraitWashOps.kt:176）：
//   1) discipleId.toIntOrNull()（非法 → 拒绝）
//   2) 事务内三态：不存在 → NOT_FOUND；已死亡 → DEAD；
//      替换校验失败（isValidSlotWash）→ INVALID
//   3) 通过：replaceSlot（目标槽位替换）→ syncLifespanForTraitChange
//      （天赋/词条 lifespan 加成差 × 境界基准寿命折算，toInt 截断，
//        delta==0 跳过，lifespan 下限 1）→ checkpointDisciple
//
// isValidSlotWash 逐字等价（GameEngineTraitWashOps.kt:237）：
//   targetId ∈ currentIds ∧ newId 非空白 ∧ resolveOne(newId) 可解析
//   ∧ 替换后每条目均可解析（resolved.size == replaced.size）
//   ∧ 替换后 template 无重复（同一 template 的特质互斥）
//
// **零 RNG**；失败零写入（三态判定全部先于写段）。
inline TxResult traitWashConfirmTx(GameState& state,
                                   const std::string& discipleId,
                                   const std::string& type,
                                   const std::string& targetId,
                                   const std::string& newId) {
    TxResult out;
    auto& ds = state.disciples;
    auto& gd = state.gameData;

    const auto kind = detail::traitKindOf(type);
    if (!kind.has_value()) {
        out.errorType = "UnknownTraitType";
        out.message = "未知特质类型 " + type;
        return out;
    }
    const auto resolved = detail::resolveDiscipleRow(ds, discipleId);
    if (!resolved.has_value()) {
        out.errorType = "NOT_FOUND";
        out.message = "弟子不存在";
        return out;
    }
    const std::size_t row = resolved->second;
    if (ds.isAlive[row] != 1) {
        out.errorType = "DEAD";
        out.message = "弟子已死亡";
        return out;
    }
    std::vector<std::string>& currentIds = detail::traitIdsOf(ds, row, *kind);

    // ── 替换校验（isValidSlotWash 逐字等价；零写入）─────────────────
    const bool targetPresent =
        std::find(currentIds.begin(), currentIds.end(), targetId) != currentIds.end();
    const auto newEntry = detail::resolveOne(*kind, newId);
    if (!targetPresent || newId.empty() || !newEntry.has_value()) {
        out.errorType = "INVALID";
        out.message = "该特质已不存在";
        return out;
    }
    // 替换后 template 无重复 + 每条目均可解析
    std::set<std::string> templates;
    for (const std::string& currentId : currentIds) {
        const std::string replacedId = (currentId == targetId) ? newId : currentId;
        const auto entry = detail::resolveOne(*kind, replacedId);
        if (!entry.has_value()) {
            out.errorType = "INVALID";
            out.message = "该特质已不存在";
            return out;
        }
        if (templates.count(entry->tmpl) != 0) {
            out.errorType = "INVALID";
            out.message = "该特质已不存在";
            return out;
        }
        templates.insert(entry->tmpl);
    }

    // ── 写段：替换 + lifespan 同步 + checkpoint ─────────────────────
    const std::vector<std::string> oldTalentIds = ds.talentIds[row];
    const std::vector<std::string> oldAffixIds = ds.affixIds[row];
    for (std::string& currentId : currentIds) {
        if (currentId == targetId) currentId = newId;  // Kotlin map { if (it == target) new else it }
    }
    const double bonusDiff =
        detail::lifespanBonusOf(ds.talentIds[row], ds.affixIds[row]) -
        detail::lifespanBonusOf(oldTalentIds, oldAffixIds);
    const int32_t delta = static_cast<int32_t>(
        static_cast<double>(realmMaxAge(ds.realms[row])) * bonusDiff);
    if (delta != 0) {
        const int32_t updated = ds.lifespans[row] + delta;
        ds.lifespans[row] = updated > 1 ? updated : 1;
    }
    ds.cultivationCheckpoints[row] = ds.cultivations[row];
    ds.cultivationCheckpointGameMonths[row] = gd.gameYear * 12 + gd.gameMonth;
    out.ok = true;
    return out;
}

}  // namespace gamecore::system::appointment_tx

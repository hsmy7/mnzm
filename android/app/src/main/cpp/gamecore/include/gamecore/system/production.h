// ============================================================
// production.h — 炼丹/锻造生产结算与自动排班
//
// 等价移植（语义权威 = 各 Kotlin 源文件）：
//  - FormulaService（core/engine/.../service/FormulaService.kt）：
//    SuccessRateZones 乘区 / DurationZones 加速 / 长老职位加成 /
//    长老+亲传弟子综合加成 / calculateWorkDurationWithAllDisciples
//  - ProductionProcessor.processBuildingProduction（月结同步完成结算段）：
//    processForgeCompletion / processAlchemyCompletion / rollProductionSuccess /
//    producePill / produceForgeEquipment / completeXxxSlot / settleProductionCompletion
//    （ProductionSettlement.kt）/ resetSlotToIdle 镜像段
//  - ProductionProcessor.processAutoAlchemy / processAutoForge（autoRestart
//    续炼启动段——Kotlin 原为月结事务后异步独立事务，C++ 版置于月结编排末尾
//    执行，读取的月结最终状态一致；validateAutoSlot 镜像一致性段随 Room
//    通道取消——C++ 侧镜像即唯一视图）
//  - ProfessionRules（profession.h 晋升规则）
//
// RNG 契约（对拍命门）：
//  - 完成结算：每到期锻造槽恰 1 次 SYSTEM nextDouble（成功率判定）+ 成功时
//    0 次（装备无 grade roll）；每到期炼丹槽恰 1 次成功判定 + 成功时再 1 次
//    grade roll——抽取序 = 槽位镜像遍历序（forge 全部在前、alchemy 在后，
//    对齐 Kotlin processBuildingProduction 顺序）。零配方/入库失败不回抽。
//  - 自动排班：零 RNG（公式化成功率，配方 successRate 不参与）。
//
// 双存储口径：
//  - 槽位操作全部发生在 gameData.productionSlots 镜像（C++ 唯一视图）；
//    Room 写回由 Kotlin 月结管线承担（settleMonthNative 前后窗口对齐）。
//  - 物品入库走 inventory.h 原语（与 Kotlin InventorySystem 同构），溢出
//    邮件草稿本地收集后丢弃（邮件实体不在快照协议——spirit_field.h 4c
//    同边界口径）；对拍场景保证仓库容量充足。
//  - 产出物品 id 为确定性生成（"gc-pill-..."/"gc-eq-..." 前缀惯例，
//    spirit_field.h 先例）——对拍面忽略新增条目 id 字段。
// ============================================================
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/data/equipment_db.h"
#include "gamecore/data/recipe_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/profession.h"
#include "gamecore/system/recruit_settlement.h"  // minRealmForRarity
#include "gamecore/system/settlement_detail.h"   // recordGameEvent/indexById
#include "gamecore/system/disciple_stats.h"      // talentEffectsFor/baseStats
#include "gamecore/system/slot_cleanup.h"        // batch-17 任命事务全槽位清理

namespace gamecore::system::production {

// ── 常量（Kotlin GameConfig.PolicyConfig——编译期常量非远程配置段） ──

/// 丹道激励：成功率 +10%
inline constexpr double kAlchemyIncentiveEffect = 0.10;
/// 锻造激励：成功率 +10%
inline constexpr double kForgeIncentiveEffect = 0.10;
/// 丹道激励：时间 +10%
inline constexpr double kAlchemyTimePenalty = 0.10;
/// 锻造激励：时间 +10%
inline constexpr double kForgeTimePenalty = 0.10;
/// 长老/亲传弟子技能基线（Kotlin ELDER_SKILL_BASELINE）
constexpr int32_t kElderSkillBaseline = 80;
/// 炼丹/锻造高品阶 roll 阈值（ProductionProcessor.PILL_GRADE_HIGH_THRESHOLD）
constexpr double kPillGradeHighThreshold = 0.06;
/// 炼丹/锻造中品阶 roll 阈值（PILL_GRADE_MEDIUM_THRESHOLD）
constexpr double kPillGradeMediumThreshold = 0.40;

namespace detail {

namespace stats = gamecore::stats;

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::ProductionSlot;

// ── 模板线性查找（data 层无索引——月结每槽至多数次调用，成本可忽略） ──

inline const gamecore::data::HerbTemplate* herbTemplateById(const std::string& id) {
    for (const auto& t : gamecore::data::herbTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

// ── FormulaService 等价 ────────────────────────────────────────────

/// 境界成功率加成（Kotlin getRealmSuccessRateBonus）
inline double realmSuccessRateBonus(int32_t realm) {
    switch (realm) {
        case 0: return 0.30;
        case 1: return 0.25;
        case 2: return 0.22;
        case 3: return 0.19;
        case 4: return 0.16;
        case 5: return 0.13;
        case 6: return 0.10;
        case 7: return 0.07;
        case 8: return 0.04;
        default: return 0.0;
    }
}

/// 建筑工艺固定加成键（Kotlin getBuildingCraftFlatBonus）
inline const char* craftFlatKey(const std::string& buildingId) {
    if (buildingId == "alchemy") return "pillRefiningFlat";
    if (buildingId == "forge") return "artifactRefiningFlat";
    if (buildingId == "herbGarden") return "spiritPlantingFlat";
    return "";
}

/// 成功率天赋加成（Kotlin getSuccessRateTalentBonus——仅天赋表，无词条）
inline double successRateTalentBonus(const DiscipleStore& ds, std::size_t row,
                                     const std::string& buildingId) {
    const auto effects = stats::talentEffectsFor(ds.talentIds[row]);
    double breakthrough = 0.0;
    double craftFlat = 0.0;
    const auto it = effects.find("breakthroughChance");
    if (it != effects.end()) breakthrough = it->second;
    const char* key = craftFlatKey(buildingId);
    if (key[0] != '\0') {
        const auto it2 = effects.find(key);
        if (it2 != effects.end()) craftFlat = it2->second;
    }
    return breakthrough * 0.80 + craftFlat * 0.006;
}

/// 长老职位乘算因子作用下的技能差加成（Kotlin getElderPositionBonus 的
/// ALCHEMY/FORGE 分支；herbGarden 不参与炼丹/锻造成功率）。
/// 建筑无对应长老 → 0。
inline double elderPositionSkillBonus(const GameState& state,
                                      const std::string& buildingId) {
    const auto& elderSlots = state.gameData.elderSlots;
    const std::string& elderId = buildingId == "forge"
                                     ? elderSlots.forgeElder
                                     : buildingId == "alchemy" ? elderSlots.alchemyElder
                                                               : std::string();
    if (elderId.empty()) return 0.0;
    const DiscipleStore& ds = state.disciples;
    // rowOf O(1) 哈希查找（原全表线性扫描 O(N)/槽）
    const auto rowOpt = ds.rowOf(elderId);
    if (!rowOpt.has_value()) return 0.0;  // 长老 id 不在弟子表（数据异常）
    const std::size_t row = *rowOpt;
    const Disciple elder = ds.materialize(row);
    const auto stats = stats::baseStats(elder);
    const int32_t baseline = kElderSkillBaseline;
    const int32_t diff = buildingId == "forge"
                             ? (stats.artifactRefining - baseline)
                             : (stats.pillRefining - baseline);
    const int32_t diffPos = diff > 0 ? diff : 0;
    const char* slotType = buildingId == "forge" ? "FORGE" : "ALCHEMY";
    const double posBonus = stats::positionEffectBonus(ds, row, slotType);
    return static_cast<double>(diffPos) * 0.01 * (1.0 + posBonus);
}

/// 长老+亲传弟子综合速度加成（Kotlin calculateElderAndDisciplesBonus 的
/// ALCHEMY/FORGE 分支——亲传弟子无职务乘算因子）。
inline double elderAndDisciplesSpeedBonus(const GameState& state,
                                          const std::string& buildingId) {
    const auto& elderSlots = state.gameData.elderSlots;
    const std::string& elderId =
        buildingId == "forge" ? elderSlots.forgeElder
                              : buildingId == "alchemy" ? elderSlots.alchemyElder
                                                        : std::string();
    const auto& discipleSlots =
        buildingId == "forge" ? elderSlots.forgeDisciples
                              : buildingId == "alchemy" ? elderSlots.alchemyDisciples
                                                        : std::vector<state::DirectDiscipleSlot>();
    const DiscipleStore& ds = state.disciples;
    auto skillOf = [&](const Disciple& d) -> int32_t {
        const auto stats = stats::baseStats(d);
        return buildingId == "forge" ? stats.artifactRefining : stats.pillRefining;
    };
    // rowOf O(1) 哈希查找（原逐 id 全表线性扫描 O(N)）
    auto findById = [&](const std::string& id) -> std::optional<std::size_t> {
        return ds.rowOf(id);
    };
    double speedBonus = 0.0;
    if (!elderId.empty()) {
        if (auto row = findById(elderId)) {
            const Disciple elder = ds.materialize(*row);
            const int32_t diff = skillOf(elder) - kElderSkillBaseline;
            const char* slotType = buildingId == "forge" ? "FORGE" : "ALCHEMY";
            const double posBonus =
                stats::positionEffectBonus(ds, *row, slotType);
            speedBonus += static_cast<double>(diff > 0 ? diff : 0) * 0.01 *
                          (1.0 + posBonus);
        }
    }
    for (const auto& slot : discipleSlots) {
        if (slot.discipleId.empty()) continue;
        if (auto row = findById(slot.discipleId)) {
            const Disciple d = ds.materialize(*row);
            const int32_t diff = skillOf(d) - kElderSkillBaseline;
            speedBonus += static_cast<double>(diff > 0 ? diff : 0) * 0.01;
        }
    }
    return speedBonus;
}

/// 工作持续时间乘区合成（Kotlin calculateWorkDurationWithAllDisciples——
/// ALCHEMY/FORGE 分支；时长 = ceil(base / (1+speed))，政策时间惩罚在其后
/// 乘算；结果 coerceAtLeast(1)）
inline int32_t calculateWorkDuration(const GameState& state, int32_t baseDuration,
                                     const std::string& buildingId) {
    const double skillSpeedBonus = elderAndDisciplesSpeedBonus(state, buildingId);
    double policyTimePenalty = 0.0;
    if (buildingId == "alchemy" && state.gameData.sectPolicies.alchemyIncentive) {
        policyTimePenalty = kAlchemyTimePenalty;
    } else if (buildingId == "forge" && state.gameData.sectPolicies.forgeIncentive) {
        policyTimePenalty = kForgeTimePenalty;
    }
    double multiplier = 1.0 + skillSpeedBonus;
    if (multiplier <= 0.0) multiplier = 1.0;
    const int32_t baseResult = static_cast<int32_t>(
        std::ceil(static_cast<double>(baseDuration) / multiplier));
    const int32_t floorResult = baseResult < 1 ? 1 : baseResult;
    if (policyTimePenalty > 0.0) {
        const double scaled = std::round(
            static_cast<double>(floorResult) * (1.0 + policyTimePenalty));
        const int32_t penalized = static_cast<int32_t>(scaled);
        return penalized > floorResult ? penalized : floorResult;
    }
    return floorResult;
}

/// 公式化成功率（Kotlin buildSuccessRateZones().calculate() 合成——
/// baseProb = clamp01(baseRate(恒0) + skillZone + professionZone)；
/// final = clamp01(baseProb × (1 + realm+talent+policy+elder))）
inline double formulaSuccessRate(const GameState& state, std::size_t workerRow,
                                 const std::string& buildingId, int32_t recipeTier,
                                 double policyBonus) {
    const DiscipleStore& ds = state.disciples;
    const int32_t skill = buildingId == "alchemy" ? ds.pillRefinings[workerRow]
                                                  : ds.artifactRefinings[workerRow];
    const int32_t level = buildingId == "alchemy" ? ds.alchemyLevels[workerRow]
                                                  : ds.forgeLevels[workerRow];
    const int64_t skillDelta = static_cast<int64_t>(skill) - profession::kSkillZoneBaseline;
    const double skillZone =
        static_cast<double>(skillDelta > 0 ? skillDelta : 0) * profession::kSkillZoneRate;
    const double skillZoneClamped =
        skillZone > profession::kSkillZoneMax ? profession::kSkillZoneMax : skillZone;
    const double professionZone =
        static_cast<double>(
            (profession::maxCraftableTier(level) - recipeTier) > 0
                ? (profession::maxCraftableTier(level) - recipeTier)
                : 0) *
        profession::kProfessionZonePerTier;
    const double baseProb = 0.0 + skillZoneClamped + professionZone;
    const double baseClamped = baseProb < 0.0 ? 0.0 : baseProb > 1.0 ? 1.0 : baseProb;
    const double positiveSum =
        realmSuccessRateBonus(ds.realms[workerRow]) +
        successRateTalentBonus(ds, workerRow, buildingId) + policyBonus +
        elderPositionSkillBonus(state, buildingId);
    const double result = baseClamped * (1.0 + positiveSum);
    return result < 0.0 ? 0.0 : result > 1.0 ? 1.0 : result;
}

// ── 完成判定与产出 ────────────────────────────────────────────────

/// 槽位完成判定（Kotlin isSlotCompleteDynamic——Checkpoint 快照法：
/// baseDuration > 0 时按当前政策/长老重算有效 duration；isWorking 由
/// 调用方先行判定）
inline bool isSlotCompleteDynamic(const GameState& state, const ProductionSlot& slot,
                                  int32_t year, int32_t month) {
    if (slot.duration <= 0) return true;  // 保护：duration=0 → 立即完成
    const int32_t effectiveDuration =
        slot.baseDuration > 0
            ? calculateWorkDuration(state, slot.baseDuration, slot.buildingId)
            : slot.duration;  // 旧数据回退
    // Kotlin TimeProgressUtil.isTimeElapsed：elapsed >= duration
    const int32_t elapsed = (year - slot.startYear) * 12 + (month - slot.startMonth);
    return elapsed >= effectiveDuration;
}

// S5：nextItemId 上移 inventory.h 共享（任务奖励与生产产线共用计数器）——
// 本文件经 #include "gamecore/system/inventory.h" 继续可见。

/// 炼丹产出（Kotlin producePill：grade roll → 模板查询 → addPill）。
/// @return true=产出成功（溢出转邮件也算成功）；false=配方无效/入库失败
inline bool producePill(GameState& state, const ProductionSlot& slot,
                        rng::RngManager& rng, OverflowMailCollector& overflowMail) {
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const double roll = rngSystem.nextDouble();
    const char* gradeLower = roll < kPillGradeHighThreshold
                                 ? "high"
                                 : roll < kPillGradeMediumThreshold ? "medium" : "low";
    // Kotlin：baseId = recipeId.substringBeforeLast('_')；模板 id = base + "_" + grade
    const std::string recipeId = slot.recipeId.value_or("");
    const std::size_t lastUs = recipeId.rfind('_');
    if (recipeId.empty() || lastUs == std::string::npos) return false;
    const std::string templateId = recipeId.substr(0, lastUs) + "_" + gradeLower;
    const auto tpl = gamecore::data::detail::pillTemplateById(templateId);
    if (!tpl.has_value()) return false;  // M3：无模板 → 炼制失败（不结算晋升）

    state::Pill pill;
    pill.id = nextItemId("gc-pill");
    pill.name = tpl->name;
    pill.rarity = tpl->rarity;
    pill.description = tpl->description;
    pill.category = tpl->category;
    // PillGrade.name（LOW/MEDIUM/HIGH）——gradeLower 反推
    pill.grade = gradeLower[0] == 'h' ? "HIGH" : gradeLower[0] == 'm' ? "MEDIUM" : "LOW";
    pill.pillType = tpl->pillType;
    pill.effects.breakthroughChance = tpl->breakthroughChance;
    pill.effects.targetRealm = tpl->targetRealm;
    pill.effects.isAscension = tpl->isAscension;
    pill.effects.cultivationSpeedPercent = tpl->cultivationSpeedPercent;
    pill.effects.skillExpSpeedPercent = tpl->skillExpSpeedPercent;
    pill.effects.nurtureSpeedPercent = tpl->nurtureSpeedPercent;
    pill.effects.cultivationAdd = tpl->cultivationAdd;
    pill.effects.skillExpAdd = tpl->skillExpAdd;
    pill.effects.nurtureAdd = tpl->nurtureAdd;
    pill.effects.duration = tpl->duration;
    pill.effects.cannotStack = tpl->cannotStack;
    pill.effects.physicalAttackAdd = tpl->physicalAttackAdd;
    pill.effects.magicAttackAdd = tpl->magicAttackAdd;
    pill.effects.physicalDefenseAdd = tpl->physicalDefenseAdd;
    pill.effects.magicDefenseAdd = tpl->magicDefenseAdd;
    pill.effects.hpAdd = tpl->hpAdd;
    pill.effects.mpAdd = tpl->mpAdd;
    pill.effects.speedAdd = tpl->speedAdd;
    pill.effects.critRateAdd = tpl->critRateAdd;
    pill.effects.critEffectAdd = tpl->critEffectAdd;
    pill.effects.extendLife = tpl->extendLife;
    pill.effects.intelligenceAdd = tpl->intelligenceAdd;
    pill.effects.charmAdd = tpl->charmAdd;
    pill.effects.loyaltyAdd = tpl->loyaltyAdd;
    pill.effects.comprehensionAdd = tpl->comprehensionAdd;
    pill.effects.artifactRefiningAdd = tpl->artifactRefiningAdd;
    pill.effects.pillRefiningAdd = tpl->pillRefiningAdd;
    pill.effects.spiritPlantingAdd = tpl->spiritPlantingAdd;
    pill.effects.teachingAdd = tpl->teachingAdd;
    pill.effects.moralityAdd = tpl->moralityAdd;
    pill.effects.miningAdd = tpl->miningAdd;
    pill.minRealm = tpl->minRealm;
    pill.quantity = 1;
    const auto r = addPill(state, pill, overflowMail, "alchemy",
                           /*overflowMailSuppressed=*/false);
    return r.status == InventoryStatus::kSuccess || r.status == InventoryStatus::kPartial;
}

/// 装备模板按名线性查找（Kotlin EquipmentDatabase.getTemplateByName；
/// 72 条模板线性扫——月结每槽最多 1 次）
inline const gamecore::data::EquipmentTemplate* equipmentTemplateByName(
    const std::string& name) {
    for (const auto& t : gamecore::data::equipmentTemplates()) {
        if (t.name == name) return &t;
    }
    return nullptr;
}

/// 锻造产出（Kotlin produceForgeEquipment：配方查询 → 装备构造 → addEquipmentStack）。
/// @return true=产出成功（溢出转邮件也算成功）；false=配方无效/入库失败
inline bool produceForgeEquipment(GameState& state, const ProductionSlot& slot,
                                  OverflowMailCollector& overflowMail) {
    const std::string recipeId = slot.recipeId.value_or("");
    const auto recipe = gamecore::data::forgeRecipeById(recipeId);
    if (!recipe.has_value()) return false;
    state::EquipmentStack eq;
    if (const auto* tpl = equipmentTemplateByName(recipe->name)) {
        eq.id = nextItemId("gc-eq");
        eq.name = tpl->name;
        eq.slot = tpl->slot;
        eq.rarity = recipe->rarity;
        eq.physicalAttack = tpl->physicalAttack;
        eq.magicAttack = tpl->magicAttack;
        eq.physicalDefense = tpl->physicalDefense;
        eq.magicDefense = tpl->magicDefense;
        eq.speed = tpl->speed;
        eq.hp = tpl->hp;
        eq.mp = tpl->mp;
        eq.description = tpl->description;
        eq.minRealm = gamecore::system::recruit_settle::minRealmForRarity(recipe->rarity);
    } else {
        // Kotlin fallback generateRandom(rarity, rarity) 用全局 Random（非分区
        // RNG，双端不确定）——C++ 确定性回退取首个同 rarity 模板；配方名与
        // 模板名同源（recipe_db.h 头注释），本分支生产不可达，对拍场景规避。
        for (const auto& t : gamecore::data::equipmentTemplates()) {
            if (t.rarity == recipe->rarity) {
                eq.id = nextItemId("gc-eq");
                eq.name = t.name;
                eq.slot = t.slot;
                eq.rarity = t.rarity;
                eq.physicalAttack = t.physicalAttack;
                eq.magicAttack = t.magicAttack;
                eq.physicalDefense = t.physicalDefense;
                eq.magicDefense = t.magicDefense;
                eq.speed = t.speed;
                eq.hp = t.hp;
                eq.mp = t.mp;
                eq.description = t.description;
                eq.critChance = t.critChance;
                eq.minRealm = gamecore::system::recruit_settle::minRealmForRarity(t.rarity);
                break;
            }
        }
        if (eq.id.empty()) return false;
    }
    eq.quantity = 1;
    const auto r = addEquipmentStack(state, eq, overflowMail, "forge",
                                     /*overflowMailSuppressed=*/false);
    return r.status == InventoryStatus::kSuccess || r.status == InventoryStatus::kPartial;
}

/// 弟子槽位回 IDLE + 成功时晋升进度（Kotlin settleProductionCompletion 的
/// 弟子表段——空表保守视为存活；晋升事件同 Kotlin recordPromotionEvent）。
inline bool settleDiscipleProduction(GameState& state, const std::string& discipleId,
                                     int32_t recipeTier, bool success, bool isAlchemy) {
    DiscipleStore& ds = state.disciples;
    bool discipleAlive = false;
    // rowOf O(1) 哈希查找（原全表线性扫描 O(N)/槽）
    const auto rowOpt = ds.rowOf(discipleId);
    if (rowOpt.has_value() && ds.isAlive[*rowOpt] == 1) {
        const std::size_t row = *rowOpt;
        discipleAlive = true;
        ds.statuses[row] = "IDLE";
        if (success) {
            const auto progress =
                profession::applyPromotionProgress(ds, row, recipeTier, isAlchemy);
            if (progress.promoted) {
                gamecore::system::settle_util::recordGameEvent(state, "SECT",
                                isAlchemy ? "alchemist_promoted" : "forgemaster_promoted",
                                std::string(ds.names[row]) + "晋升为" +
                                    profession::displayName(progress.newLevel, isAlchemy),
                                ds.ids[row], ds.names[row]);
            }
        }
    }
    return discipleAlive;
}

/// 完成结算（Kotlin completeAlchemySlot/completeForgeSlot + settleProductionCompletion
/// 统计段 + resetSlotToIdle 镜像段——B5 身份守卫随 Room 通道取消：C++ 单线程
/// 镜像内联重置无窗口竞态）。
/// @return 弟子是否存在且存活（供槽位保留弟子关联，B3）
inline bool completeSlot(GameState& state, ProductionSlot& slot, bool isAlchemy,
                         rng::RngManager& rng, OverflowMailCollector& overflowMail) {
    // 1. 成功率判定（SYSTEM 分区，无条件抽取——与 Kotlin rollProductionSuccess 逐位一致；
    //    NaN 不被 clamp 钳制 → 显式归零）
    auto& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const double rate = std::isnan(slot.successRate)
                            ? 0.0
                            : std::min(1.0, std::max(0.0, slot.successRate));
    bool success = rngSystem.nextDouble() <= rate;
    // 2. 产出（失败视为炼制失败，不结算晋升——B4）
    if (success) {
        success = isAlchemy ? producePill(state, slot, rng, overflowMail)
                            : produceForgeEquipment(state, slot, overflowMail);
    }
    // 3. 统计 + 晋升（guideCounters/annual 计数无条件——成功与否独立）
    auto& gd = state.gameData;
    const std::string counterKey = isAlchemy ? "alchemyCompleted" : "forgeCompleted";
    ++gd.guideCounters[counterKey];
    if (isAlchemy) ++gd.annualAlchemyCount; else ++gd.annualForgeCount;
    int32_t recipeTier = 0;
    if (slot.recipeId.has_value() && !slot.recipeId->empty()) {
        if (isAlchemy) {
            const auto recipe = gamecore::data::pillRecipeById(*slot.recipeId);
            if (recipe.has_value()) recipeTier = recipe->tier;
        } else {
            const auto recipe = gamecore::data::forgeRecipeById(*slot.recipeId);
            if (recipe.has_value()) recipeTier = recipe->tier;
        }
    }
    bool discipleAlive = true;
    if (slot.assignedDiscipleId.has_value() && !slot.assignedDiscipleId->empty()) {
        discipleAlive =
            settleDiscipleProduction(state, *slot.assignedDiscipleId, recipeTier,
                                     success, isAlchemy);
    } else {
        discipleAlive = false;
    }
    return discipleAlive;
}

/// 槽位重置 IDLE（Kotlin resetSlotToIdle / ProductionSlot.createIdle 镜像段——
/// 全清空，保留 id/slotIndex/buildingType/buildingId/autoRestart/配方（无条件，
/// 供续炼）+ 弟子（仅存活时，B3））
inline void resetSlotToIdle(ProductionSlot& slot, bool keepDisciple) {
    const std::string id = slot.id;
    const int32_t slotIndex = slot.slotIndex;
    const std::string buildingType = slot.buildingType;
    const std::string buildingId = slot.buildingId;
    const bool autoRestart = slot.autoRestartEnabled;
    const std::string discipleId =
        keepDisciple ? (slot.assignedDiscipleId.value_or("")) : "";
    const std::string discipleName = keepDisciple ? slot.assignedDiscipleName : "";
    const std::string recipeId = slot.recipeId.value_or("");
    const bool hasDisciple = !discipleId.empty();
    const bool hasRecipe = !recipeId.empty();
    slot = ProductionSlot{};
    slot.id = id;
    slot.slotIndex = slotIndex;
    slot.buildingType = buildingType;
    slot.buildingId = buildingId;
    slot.status = "IDLE";
    slot.autoRestartEnabled = autoRestart;
    if (hasDisciple) {
        slot.assignedDiscipleId = discipleId;
        slot.assignedDiscipleName = discipleName;
    }
    if (hasRecipe) slot.recipeId = recipeId;
}

/// 单建筑完成结算主循环（Kotlin processForgeCompletion/processAlchemyCompletion——
/// 槽位遍历序 = 镜像序，SYSTEM 抽取序对拍命门；匹配口径逐位对齐 Kotlin：
/// 锻造按 buildingId（getSlotsByBuildingId）、炼丹按 buildingType
///（getSlotsByType））。
inline void processCompletionByType(GameState& state, const char* buildingId,
                                    bool matchByType, const char* typeValue,
                                    bool isAlchemy, rng::RngManager& rng,
                                    OverflowMailCollector& overflowMail) {
    const int32_t year = state.gameData.gameYear;
    const int32_t month = state.gameData.gameMonth;
    for (auto& slot : state.gameData.productionSlots) {
        if (matchByType ? (slot.buildingType != typeValue)
                        : (slot.buildingId != buildingId)) {
            continue;
        }
        if (slot.status != "WORKING") continue;
        // Kotlin：WORKING 且无弟子 → 跳过（不重置不结算）
        if (!slot.assignedDiscipleId.has_value() || slot.assignedDiscipleId->empty()) {
            continue;
        }
        if (!isSlotCompleteDynamic(state, slot, year, month)) continue;
        const bool discipleAlive =
            completeSlot(state, slot, isAlchemy, rng, overflowMail);
        resetSlotToIdle(slot, discipleAlive);
    }
}

// ── 自动排班（autoRestart 续炼启动） ────────────────────────────────

/// 弟子可用性守卫（Kotlin validateAutoSlot 的镜像段——存活 + IDLE/对应工作状态；
/// 镜像一致性段随 Room 通道取消——C++ 侧镜像即唯一视图）
inline bool autoSlotDiscipleUsable(const GameState& state,
                                   const ProductionSlot& slot,
                                   const char* expectedStatus) {
    if (!slot.assignedDiscipleId.has_value() || slot.assignedDiscipleId->empty()) {
        return false;
    }
    const DiscipleStore& ds = state.disciples;
    // rowOf O(1) 哈希查找（原全表线性扫描 O(N)/槽）
    const auto rowOpt = ds.rowOf(*slot.assignedDiscipleId);
    if (!rowOpt.has_value()) return false;  // 查无此人
    const std::size_t row = *rowOpt;
    if (ds.isAlive[row] != 1) return false;
    return ds.statuses[row] == "IDLE" || ds.statuses[row] == expectedStatus;
}

/// 灵草余量索引（name+rarity → quantity 一次构建 O(H)，替代
/// 每配方材料逐堆全表扫描——与 buildMaterialIndex 同形状）
inline std::map<std::pair<std::string, int32_t>, int32_t> buildHerbIndex(
    const GameState& state) {
    std::map<std::pair<std::string, int32_t>, int32_t> index;
    for (const auto& herb : state.herbs) {
        index[{herb.name, herb.rarity}] += herb.quantity;
    }
    return index;
}

/// 炼丹配方材料充足（Kotlin hasMaterials 灵草版——模板 id → name+rarity
/// 反查后查索引；与 buildHerbIndex 配对使用）
inline bool alchemyRecipeHasMaterials(
    const gamecore::data::PillRecipeTemplate& recipe,
    const std::map<std::pair<std::string, int32_t>, int32_t>& index) {
    for (const auto& [herbId, requiredQty] : recipe.materials) {
        const auto* herbData = herbTemplateById(herbId);
        if (herbData == nullptr) return false;
        const auto it = index.find({herbData->name, herbData->rarity});
        const int32_t have = it != index.end() ? it->second : 0;
        if (have < requiredQty) return false;
    }
    return true;
}

/// 配方灵草余量求和（Kotlin 选配方口径：currentHerbs.filter { name == herbData.
/// name && rarity == herbData.rarity }.sumOf { quantity }——精确 name+rarity 堆；
/// 与消耗同口径，start 原子事务的 id 聚合检查在正常数据下恒宽松于本检查）
inline int32_t herbQtyForMaterial(const GameState& state, const std::string& herbId) {
    const auto* herbData = herbTemplateById(herbId);
    if (herbData == nullptr) return 0;
    int32_t total = 0;
    for (const auto& herb : state.herbs) {
        if (herb.name == herbData->name && herb.rarity == herbData->rarity) {
            total += herb.quantity;
        }
    }
    return total;
}

/// 灵草消耗（Kotlin ProductionCoordinator.consumeHerbsForRecipe——遍历 herbs
/// 原序，配方材料表（std::map 序）逐堆扣减 name+rarity 匹配量；qty>0 保留）。
/// 前置：材料充足检查已通过。
inline void consumeHerbsForRecipe(GameState& state,
                                  const std::map<std::string, int32_t>& recipeMaterials) {
    auto remaining = recipeMaterials;
    for (auto& herb : state.herbs) {
        if (remaining.empty()) break;
        int32_t newQty = herb.quantity;
        for (auto it = remaining.begin(); it != remaining.end();) {
            const auto* herbData = herbTemplateById(it->first);
            if (herbData == nullptr) {
                ++it;
                continue;
            }
            if (herbData->name != herb.name || herbData->rarity != herb.rarity) {
                ++it;
                continue;
            }
            const int32_t consume = std::min(newQty, it->second);
            newQty -= consume;
            it->second -= consume;
            if (it->second <= 0) it = remaining.erase(it);
            else ++it;
        }
        herb.quantity = newQty;
    }
    // 消耗后清 qty==0 行（Kotlin add 语义：newQty>0 才保留）
    state.herbs.erase(std::remove_if(state.herbs.begin(), state.herbs.end(),
                                     [](const state::Herb& h) { return h.quantity <= 0; }),
                      state.herbs.end());
}

/// 矿材可用聚合（Kotlin startForgingAtomic 的 materialIndex——name+rarity 键）
inline std::map<std::pair<std::string, int32_t>, int32_t> buildMaterialIndex(
    const GameState& state) {
    std::map<std::pair<std::string, int32_t>, int32_t> index;
    for (const auto& m : state.materials) {
        index[{m.name, m.rarity}] += m.quantity;
    }
    return index;
}

/// 锻造配方材料充足（Kotlin hasMaterials——模板 id → name+rarity 反查）
inline bool forgeRecipeHasMaterials(const gamecore::data::ForgeRecipeTemplate& recipe,
                                    const std::map<std::pair<std::string, int32_t>, int32_t>& index) {
    for (const auto& [materialId, requiredQty] : recipe.materials) {
        const auto* matData = gamecore::data::beastMaterialById(materialId);
        if (matData == nullptr) return false;
        const auto it = index.find({matData->name, matData->rarity});
        const int32_t have = it != index.end() ? it->second : 0;
        if (have < requiredQty) return false;
    }
    return true;
}

/// 矿材消耗（Kotlin ProductionCoordinator.consumeMaterialsForRecipe——
/// name+rarity 匹配逐堆扣减，序同 consumeHerbsForRecipe）
inline void consumeMaterialsForRecipe(
    GameState& state, const std::map<std::string, int32_t>& recipeMaterials) {
    auto remaining = recipeMaterials;
    for (auto& item : state.materials) {
        if (remaining.empty()) break;
        int32_t newQty = item.quantity;
        for (auto it = remaining.begin(); it != remaining.end();) {
            const auto* matData = gamecore::data::beastMaterialById(it->first);
            if (matData == nullptr) {
                ++it;
                continue;
            }
            if (matData->name != item.name || matData->rarity != item.rarity) {
                ++it;
                continue;
            }
            const int32_t consume = std::min(newQty, it->second);
            newQty -= consume;
            it->second -= consume;
            if (it->second <= 0) it = remaining.erase(it);
            else ++it;
        }
        item.quantity = newQty;
    }
    state.materials.erase(std::remove_if(state.materials.begin(), state.materials.end(),
                                         [](const state::Material& m) {
                                             return m.quantity <= 0;
                                         }),
                          state.materials.end());
}

/// 槽位启动 WORKING（Kotlin SlotStateMachine.startProduction + 外层 duration
/// 重算合并——completionMonth = year*12+month + actualDuration.coerceAtLeast(1)，
/// 外层后写覆盖口径；FORGE/ALCHEMY completionPhase = 2）
inline void startSlotWorking(GameState& state, ProductionSlot& slot,
                             const std::string& recipeId, const std::string& recipeName,
                             int32_t baseDuration, double successRate,
                             const std::string& outputItemId,
                             const std::string& outputItemName, int32_t outputItemRarity) {
    const int32_t actualDuration =
        calculateWorkDuration(state, baseDuration, slot.buildingId);
    const int32_t absMonth =
        state.gameData.gameYear * 12 + state.gameData.gameMonth;
    slot.status = "WORKING";
    slot.recipeId = recipeId;
    slot.recipeName = recipeName;
    slot.startYear = state.gameData.gameYear;
    slot.startMonth = state.gameData.gameMonth;
    slot.duration = actualDuration;
    slot.baseDuration = baseDuration;
    slot.successRate = successRate;
    slot.outputItemId = outputItemId;
    slot.outputItemName = outputItemName;
    slot.outputItemRarity = outputItemRarity;
    slot.completionMonth = absMonth + (actualDuration < 1 ? 1 : actualDuration);
    slot.completionPhase = 2;
}

/// 自动炼丹（autoRestart 续炼启动；Kotlin processAutoAlchemy 事务段等价——
/// 零 RNG。材料匹配经 HerbDatabase 模板反查 name+rarity，与 Kotlin
/// consumeHerbsForRecipe 逐位一致）
inline void processAutoAlchemyStep(GameState& state) {
    for (auto& slot : state.gameData.productionSlots) {
        if (slot.buildingId != "alchemy") continue;
        if (!slot.autoRestartEnabled || slot.status != "IDLE") continue;
        if (!slot.assignedDiscipleId.has_value() || slot.assignedDiscipleId->empty()) {
            continue;
        }
        if (!autoSlotDiscipleUsable(state, slot, "ALCHEMY")) continue;

        const std::string workerId = *slot.assignedDiscipleId;
        const DiscipleStore& ds = state.disciples;
        // rowOf O(1) 哈希查找（原逐槽线性扫描 O(N) → O(slots×N)）
        const auto workerRowOpt = ds.rowOf(workerId);
        if (!workerRowOpt.has_value()) continue;
        const std::size_t workerRow = *workerRowOpt;

        // 材料余量索引每槽重建 O(H)（Kotlin 真实路径 processAutoAlchemy
        // 逐槽 `state.herbs.all()` 实时读同语义——批首快照会在同批多槽竞争
        // 时用旧余量选配方导致少扣材料；索引化后配方检查 O(M) 查表，
        // 原 O(H×M) 逐堆扫描 → O(slots×(H+R×M))）
        const auto herbIndex = buildHerbIndex(state);
        const auto policyBonus = state.gameData.sectPolicies.alchemyIncentive
                                     ? kAlchemyIncentiveEffect
                                     : 0.0;
        const int32_t maxTier =
            profession::maxCraftableTier(ds.alchemyLevels[workerRow]);

        // 配方选取：续炼原配方（材料充足时）else 材料充足的最高阶
        //（Kotlin PillRecipeDatabase.findBestCraftableRecipe：
        //  sortedWith(compareByDescending tier thenByDescending rarity).firstOrNull）
        std::optional<gamecore::data::PillRecipeTemplate> chosen;
        if (slot.recipeId.has_value() && !slot.recipeId->empty()) {
            auto prev = gamecore::data::pillRecipeById(*slot.recipeId);
            if (prev.has_value() && prev->tier <= maxTier &&
                alchemyRecipeHasMaterials(*prev, herbIndex)) {
                chosen = std::move(prev);
            }
        }
        if (!chosen.has_value()) {
            // 全配方表按 tier 降序、rarity 降序取首个可炼（tier<=maxTier）。
            // 排序与状态无关（静态模板表）→ 首次构建后进程内缓存
            //（原逐槽重排 O(slots×R log R)）
            static const std::vector<const gamecore::data::PillRecipeTemplate*>
                kSorted = [] {
                    const auto& all = gamecore::data::pillRecipes();
                    std::vector<const gamecore::data::PillRecipeTemplate*> sorted;
                    sorted.reserve(all.size());
                    for (const auto& r : all) sorted.push_back(&r);
                    std::stable_sort(sorted.begin(), sorted.end(),
                                     [](const auto* a, const auto* b) {
                                         if (a->tier != b->tier) return a->tier > b->tier;
                                         return a->rarity > b->rarity;
                                     });
                    return sorted;
                }();
            for (const auto* r : kSorted) {
                if (r->tier > maxTier) continue;
                if (alchemyRecipeHasMaterials(*r, herbIndex)) {
                    chosen = *r;
                    break;
                }
            }
        }
        if (!chosen.has_value()) continue;

        const double rate =
            formulaSuccessRate(state, workerRow, "alchemy", chosen->tier, policyBonus);
        consumeHerbsForRecipe(state, chosen->materials);
        startSlotWorking(state, slot, chosen->id, chosen->name, chosen->duration, rate,
                         chosen->id, chosen->name, chosen->rarity);
    }
}

/// 自动锻造（autoRestart 续炼启动；Kotlin processAutoForge 事务段等价——
/// 零 RNG。配方序 = rarity 降序（Kotlin getAllRecipes().sortedByDescending
/// rarity 后 firstOrNull），材料经 BeastMaterialDatabase 反查 name+rarity）
inline void processAutoForgeStep(GameState& state) {
    const auto& all = gamecore::data::forgeRecipes();
    // 排序与状态无关（静态模板表）→ 进程内缓存（原每步重排）
    static const std::vector<const gamecore::data::ForgeRecipeTemplate*> kSorted = [] {
        const auto& recipes = gamecore::data::forgeRecipes();
        std::vector<const gamecore::data::ForgeRecipeTemplate*> sorted;
        sorted.reserve(recipes.size());
        for (const auto& r : recipes) sorted.push_back(&r);
        std::stable_sort(sorted.begin(), sorted.end(),
                         [](const auto* a, const auto* b) { return a->rarity > b->rarity; });
        return sorted;
    }();

    for (auto& slot : state.gameData.productionSlots) {
        if (slot.buildingId != "forge") continue;
        if (!slot.autoRestartEnabled || slot.status != "IDLE") continue;
        if (!slot.assignedDiscipleId.has_value() || slot.assignedDiscipleId->empty()) {
            continue;
        }
        if (!autoSlotDiscipleUsable(state, slot, "FORGE")) continue;

        const std::string workerId = *slot.assignedDiscipleId;
        const DiscipleStore& ds = state.disciples;
        // rowOf O(1) 哈希查找（逐槽 O(1)，整体 O(slots)）
        const auto workerRowOpt = ds.rowOf(workerId);
        if (!workerRowOpt.has_value()) continue;
        const std::size_t workerRow = *workerRowOpt;

        // 材料余量索引每槽重建 O(M)——Kotlin 真实路径 processAutoForgeSlot
        // 逐槽 `stateStore.getCurrentMaterials()` 实时读同语义（同批多槽
        // 材料竞争时以旧余量选配方会导致少扣材料，必须逐槽重建）
        const auto materialIndex = buildMaterialIndex(state);
        const auto policyBonus = state.gameData.sectPolicies.forgeIncentive
                                     ? kForgeIncentiveEffect
                                     : 0.0;
        const int32_t maxTier =
            profession::maxCraftableTier(ds.forgeLevels[workerRow]);

        // 配方选取：续炼原配方（材料充足）else 首个 tier<=maxTier 且材料充足
        std::optional<gamecore::data::ForgeRecipeTemplate> chosen;
        if (slot.recipeId.has_value() && !slot.recipeId->empty()) {
            auto prev = gamecore::data::forgeRecipeById(*slot.recipeId);
            if (prev.has_value() && prev->tier <= maxTier &&
                forgeRecipeHasMaterials(*prev, materialIndex)) {
                chosen = std::move(prev);
            }
        }
        if (!chosen.has_value()) {
            for (const auto* r : kSorted) {
                if (r->tier > maxTier) continue;
                if (forgeRecipeHasMaterials(*r, materialIndex)) {
                    chosen = *r;
                    break;
                }
            }
        }
        if (!chosen.has_value()) continue;

        const double rate =
            formulaSuccessRate(state, workerRow, "forge", chosen->tier, policyBonus);
        consumeMaterialsForRecipe(state, chosen->materials);
        // Kotlin processAutoForgeSlot：ForgeRecipeDatabase.getDurationByTier(tier)
        // = TIER_DURATION[tier] ?: 2
        const int32_t baseDuration =
            chosen->tier >= 1 && chosen->tier <= 6
                ? gamecore::data::detail::kTierDuration[chosen->tier]
                : 2;
        startSlotWorking(state, slot, chosen->id, chosen->name, baseDuration, rate,
                         chosen->id, chosen->name, chosen->rarity);
    }
}

}  // namespace detail

// ── 月结接线入口 ──────────────────────────────────────────────────

/// 月结步骤 4a/4b：炼丹/锻造完成结算（Kotlin AlchemySystem(210)/ForgeSystem(211)
/// 同步段——完成判定/成功率 roll/产出入库/晋升/槽位重置）。
inline void processBuildingProductionStep(gamecore::state::GameState& state,
                                          rng::RngManager& rng) {
    OverflowMailCollector overflowMail;  // 溢出草稿本地收集后丢弃
    // Kotlin processBuildingProduction：forge 先、alchemy 后（SYSTEM 抽取序）；
    // 锻造按 buildingId 匹配（getSlotsByBuildingId）、炼丹按 buildingType
    // 匹配（getSlotsByType）——口径与 Kotlin 各自查询逐位一致
    detail::processCompletionByType(state, "forge", /*matchByType=*/false, "FORGE",
                                    /*isAlchemy=*/false, rng, overflowMail);
    detail::processCompletionByType(state, "alchemy", /*matchByType=*/true, "ALCHEMY",
                                    /*isAlchemy=*/true, rng, overflowMail);
}

/// 月结末尾：自动排班（autoRestart 续炼启动；Kotlin 原为月结事务提交后异步
/// 独立事务——C++ 版置于月结编排末尾执行，读取的月结最终状态一致；零 RNG
/// 不扰动抽取序）。
inline void processAutoProductionStep(gamecore::state::GameState& state) {
    detail::processAutoAlchemyStep(state);
    detail::processAutoForgeStep(state);
}

namespace detail {

// ── 交互排班事务（手动排班 C++ 真相先行）────────────────────────────
// Kotlin BuildingService.executeAlchemyStart/executeForgingStart →
// ProductionCoordinator.startAlchemyAtomic/startForgingAtomic →
// ProductionTransactionManager.executeStartProductionByBuildingId +
// SlotStateMachine.startProduction 组合等价。材料检查/消耗原语（name+rarity
// 求和 + 模板反查 + 逐堆扣减）复用既有函数——与 Kotlin 逐位一致。
// 时长重算（calculateWorkDuration——FormulaService 等价）直接并入槽位写入
//（Kotlin 为"先写原始值 + 外层异步修正"两段，C++ 单段写最终值等价——
// startSlotWorking 同口径）。

/// 排班事务结果（Kotlin DomainResult.Failure 分型 + missingMaterials 对应物）
struct ProductionStartOutcome {
    bool ok = false;
    std::string errorType;      // RecipeNotFound / SlotBusy / InsufficientMaterials
    std::string message;
    std::map<std::string, int32_t> missingMaterials;   // materialId → 缺口
};

/// 手动排班事务（buildingId 区分炼丹/锻造——与月结匹配口径不同：手动排班
/// 双域均按 buildingId 寻槽，配方轨道由 isAlchemy 决定）。
/// @param successRate 成功率；**< 0 = 原生计算**（formulaSuccessRate——S4 公式，
///   按槽位弟子/建筑/配方档/政策加成；无弟子 → 0.0）
/// @param policyBonus 原生计算路径的政策加成（炼丹 alchemyIncentive/锻造
///   forgeIncentive——调用方按 data.sectPolicies 传入，与 Kotlin 值一致）
/// @param isAlchemy true=炼丹（herbs 轨道 + PillRecipe）/ false=锻造（materials
///   轨道 + ForgeRecipe）
inline ProductionStartOutcome startProductionTransaction(
    gamecore::state::GameState& state, const std::string& buildingId,
    int32_t slotIndex, const std::string& recipeId, double successRate,
    double policyBonus, bool isAlchemy) {
    ProductionStartOutcome out;
    auto& gd = state.gameData;

    // 1. 配方（Kotlin PillRecipeDatabase/ForgeRecipeDatabase.getRecipeById）
    std::string recipeName;
    int32_t baseDuration = 0;
    int32_t recipeRarity = 1;
    int32_t recipeTier = 1;
    std::map<std::string, int32_t> recipeMaterials;
    if (isAlchemy) {
        const auto recipe = gamecore::data::pillRecipeById(recipeId);
        if (!recipe.has_value()) {
            out.errorType = "RecipeNotFound";
            out.message = "配方不存在: " + recipeId;
            return out;
        }
        recipeName = recipe->name;
        baseDuration = recipe->duration;
        recipeRarity = recipe->rarity;
        recipeTier = recipe->tier;
        recipeMaterials = recipe->materials;
    } else {
        const auto recipe = gamecore::data::forgeRecipeById(recipeId);
        if (!recipe.has_value()) {
            out.errorType = "RecipeNotFound";
            out.message = "配方不存在: " + recipeId;
            return out;
        }
        recipeName = recipe->name;
        baseDuration = recipe->duration;
        recipeRarity = recipe->rarity;
        recipeTier = recipe->tier;
        recipeMaterials = recipe->materials;
    }

    // 2. 槽位 ensure（缺槽创建 IDLE——ProductionSlot{} 默认值即 createIdle 语义：
    //    completionPhase=1 / outputItemRarity=1）
    ProductionSlot* slotPtr = nullptr;
    for (auto& s : gd.productionSlots) {
        if (s.buildingId == buildingId && s.slotIndex == slotIndex) {
            slotPtr = &s;
            break;
        }
    }
    if (slotPtr == nullptr) {
        ProductionSlot created;
        created.slotIndex = slotIndex;
        created.buildingType = isAlchemy ? "ALCHEMY" : "FORGE";
        created.buildingId = buildingId;
        created.status = "IDLE";
        gd.productionSlots.push_back(created);
        slotPtr = &gd.productionSlots.back();
    }
    if (slotPtr->status == "WORKING") {
        out.errorType = "SlotBusy";
        out.message = "Slot is already working";
        return out;
    }

    // 2.5 成功率原生计算（successRate < 0 哨兵——Kotlin 门面转发路径不预算，
    // 与 Kotlin 回退臂 formulaService.buildSuccessRateZones 同一 S4 公式）
    if (successRate < 0.0) {
        successRate = 0.0;
        if (slotPtr->assignedDiscipleId.has_value() &&
            !slotPtr->assignedDiscipleId->empty()) {
            const auto& ds = state.disciples;
            const auto rowOpt = ds.rowOf(*slotPtr->assignedDiscipleId);
            if (rowOpt.has_value()) {
                successRate = formulaSuccessRate(state, *rowOpt, buildingId,
                                                 recipeTier, policyBonus);
            }
        }
    }

    // 3. 材料充足检查（name+rarity 求和 vs 配方 id 反查）
    std::map<std::pair<std::string, int32_t>, int32_t> available;
    if (isAlchemy) {
        available = buildHerbIndex(state);
    } else {
        available = buildMaterialIndex(state);
    }
    for (const auto& [materialId, requiredQty] : recipeMaterials) {
        const std::pair<std::string, int32_t>* key = nullptr;
        std::pair<std::string, int32_t> k;
        if (isAlchemy) {
            const auto* tpl = herbTemplateById(materialId);
            if (tpl == nullptr) continue;   // 空洞模板跳过（Kotlin buildAlchemyAvailableMaterials 同义）
            k = {tpl->name, tpl->rarity};
        } else {
            const auto* tpl = gamecore::data::beastMaterialById(materialId);
            if (tpl == nullptr) continue;
            k = {tpl->name, tpl->rarity};
        }
        key = &k;
        const auto it = available.find(*key);
        const int32_t have = it != available.end() ? it->second : 0;
        if (have < requiredQty) out.missingMaterials[materialId] = requiredQty - have;
    }
    if (!out.missingMaterials.empty()) {
        out.errorType = "InsufficientMaterials";
        out.message = "材料不足";
        return out;
    }

    // 4. 槽位 WORKING（duration=重算最终值 + completionMonth 合并写）+ 材料消耗
    startSlotWorking(state, *slotPtr, recipeId, recipeName, baseDuration,
                     successRate, recipeId, recipeName, recipeRarity);
    if (isAlchemy) {
        consumeHerbsForRecipe(state, recipeMaterials);
    } else {
        consumeMaterialsForRecipe(state, recipeMaterials);
    }
    out.ok = true;
    return out;
}

/// 手动重置事务（Kotlin resetSlotAtomic → executeResetSlot——槽位回 IDLE 全清空，
/// 不退材料；门禁：槽位存在性。keepDisciple=false——B3 弟子保留仅死亡路径）。
inline bool resetProductionSlotTransaction(gamecore::state::GameState& state,
                                           const std::string& buildingId,
                                           int32_t slotIndex) {
    auto& gd = state.gameData;
    for (auto& s : gd.productionSlots) {
        if (s.buildingId == buildingId && s.slotIndex == slotIndex) {
            resetSlotToIdle(s, /*keepDisciple=*/false);
            return true;
        }
    }
    return false;
}

}  // namespace detail

/// 对外导出（交互域转发经 gamecore::system::production 消费）
using detail::ProductionStartOutcome;
using detail::startProductionTransaction;
using detail::resetProductionSlotTransaction;

// ============================================================
// batch-17：生产 UI 操作面事务族（镜像真源，Room 持久化为 Kotlin 后置残差）
//
// 等价移植 Kotlin BuildingFacadeImpl 的生产子集 UI 写者：
//   - assignDiscipleToProductionSlot（任命：全槽位清理 + 目标槽写 + 他槽清空）
//   - removeDiscipleFromProductionSlot（卸任：占用捕获 + WORKING 剩余时长归一）
//   - toggleAutoRestart（自动续炼开关翻转）
//   - addProductionSlot（惰性建槽 / 镜像槽维护，按 buildingId+slotIndex 幂等 upsert）
//
// 口径要点（S7 同族——"C++ 真相先行 + Room 持久化后置"）：
//   ① 全部事务只写 gameData.productionSlots 镜像（C++ 唯一视图）；Room 单槽/
//      整槽回放由 Kotlin 残差承担（productionNativeStart 先例）。任命事务另经
//      slot_cleanup.h 全 11 类槽位清理（includeResidence=false——工作分配保留
//      住所，与 Kotlin clearAllSlotsDataOnly 同参）。
//   ② 判定序逐位对齐 Kotlin：先读目标槽（缺失 → 失败零写入，Kotlin 回退原路径
//      重执行校验链），再捕获旧 occupant，最后才变更。
//   ③ 卸任的"剩余时长归一"（WORKING 且原占用者非空 → startYear/startMonth 取
//      当前月、duration = max(剩余月, 1)）与 Kotlin 回退臂同式；镜像同步获得
//      该归一值（回退臂只写 repo，镜像由月结窗口 alignMirrorFromRepository
//      以 repo 覆盖——两径最终一致，属口径收敛而非行为变更）。
//   ④ 零 RNG：全事务不触达 RngManager（GTest 全分区快照差分断言）。
//   ⑤ 失败零写入：校验链先行，任一校验失败不触碰任何状态（failure 信封）。
//   ⑥ 弟子状态推导（DiscipleAssignmentGate / syncSingleDiscipleStatus）与
//      Room 回放留 Kotlin——纯内存注册表与非确定性快照逻辑，不在本文件。
// ============================================================
namespace ui_tx {

namespace sc = gamecore::system;
namespace state = gamecore::state;

using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::ProductionSlot;

/// 生产 UI 事务结果（Kotlin 门面判定 + 残差组装备注）
struct ProductionUiOutcome {
    bool ok = false;
    std::string errorType;   // InvalidSlot
    std::string message;
    std::string oldOccupantId;    // 任命/卸任前目标槽占用者（gate 释放用）
    std::string oldOccupantName;
    std::string discipleId;       // 卸任回执占用者
    bool newValue = false;        // 自动续炼翻转后值
    bool created = false;         // 惰性建槽：本次是否新建
    ProductionSlot slot;          // 惰性建槽回执
};

/// 生产槽定位（buildingType + slotIndex——Kotlin 任命/卸任/翻转三处同口径）
inline ProductionSlot* findProductionSlot(state::GameData& gd,
                                          const std::string& buildingType,
                                          int32_t slotIndex) {
    for (auto& s : gd.productionSlots) {
        if (s.buildingType == buildingType && s.slotIndex == slotIndex) return &s;
    }
    return nullptr;
}

/// 生产槽写回镜像（惰性建槽 upsert：命中 → 整体覆写，缺失 → 追加）
inline ProductionSlot& upsertProductionSlot(state::GameData& gd,
                                            const ProductionSlot& slot) {
    for (auto& s : gd.productionSlots) {
        if (s.buildingId == slot.buildingId && s.slotIndex == slot.slotIndex) {
            s = slot;
            return s;
        }
    }
    gd.productionSlots.push_back(slot);
    return gd.productionSlots.back();
}

/// 任命事务（Kotlin assignDiscipleToProductionSlot 的镜像段等价）
///
/// 判定序：① 目标槽存在性（缺失 → InvalidSlot 零写入）
///         ② 捕获目标槽旧 occupant（回传 Kotlin 做 gate 释放）
///         ③ clearAllSlotsDataOnly(discipleId, includeResidence=false)
///         ④ 目标槽写 occupant ∧ 该弟子其余生产槽清 occupant
///
/// 零 RNG。Room repo 写入 / gate 注册 / 状态推导由 Kotlin 残差承担：
/// 残差失败时 Kotlin 回滚镜像目标槽 occupant（rollbackMirrorSlot 同式），
/// 与回退臂"镜像已写而 repo 未写"补偿语义一致。
inline ProductionUiOutcome assignProductionSlotTx(
    gamecore::state::GameState& state, const std::string& buildingType,
    int32_t slotIndex, const std::string& discipleId,
    const std::string& discipleName) {
    ProductionUiOutcome out;
    auto& gd = state.gameData;
    const ProductionSlot* target = findProductionSlot(gd, buildingType, slotIndex);
    if (target == nullptr) {
        out.errorType = "InvalidSlot";
        out.message = "槽位不存在: " + buildingType + "[" + std::to_string(slotIndex) + "]";
        return out;
    }
    out.oldOccupantId = target->assignedDiscipleId.value_or("");
    out.oldOccupantName = target->assignedDiscipleName;

    // 全槽位清理（含 productionSlots 段——他槽 occupant 清空由此承担）
    sc::SlotCleanupInput in;
    in.spiritMineSlots = gd.spiritMineSlots;
    in.librarySlots = gd.librarySlots;
    in.elderSlots = gd.elderSlots;
    in.residenceSlots = gd.residenceSlots;
    in.activeBloodRefinements = gd.activeBloodRefinements;
    in.patrolSlots = gd.patrolSlots;
    in.warehouseGarrisons = gd.warehouseGarrisons;
    in.battleTeams = gd.battleTeams;
    in.worldMapSects = gd.worldMapSects;
    in.productionSlots = gd.productionSlots;
    in.caveExplorationTeams = gd.caveExplorationTeams;
    in.activeMissions = sc::toMissionLiteList(gd.activeMissions);
    const sc::SlotCleanupResult cleaned =
        sc::clearAllSlotsDataOnly(in, discipleId, /*includeResidence=*/false);
    gd.spiritMineSlots = cleaned.spiritMineSlots;
    gd.librarySlots = cleaned.librarySlots;
    gd.elderSlots = cleaned.elderSlots;
    gd.residenceSlots = cleaned.residenceSlots;
    gd.activeBloodRefinements = cleaned.activeBloodRefinements;
    gd.patrolSlots = cleaned.patrolSlots;
    gd.warehouseGarrisons = cleaned.warehouseGarrisons;
    gd.battleTeams = cleaned.battleTeams;
    gd.worldMapSects = cleaned.worldMapSects;
    gd.productionSlots = cleaned.productionSlots;
    gd.caveExplorationTeams = cleaned.caveExplorationTeams;
    gd.activeMissions = sc::mergeMissionLiteList(gd.activeMissions, cleaned.activeMissions);

    // 目标槽写 occupant（清理已保证他槽无该弟子占用——再兜一层幂等）
    for (auto& s : gd.productionSlots) {
        if (s.buildingType == buildingType && s.slotIndex == slotIndex) {
            s.assignedDiscipleId = discipleId;
            s.assignedDiscipleName = discipleName;
        } else if (s.assignedDiscipleId.has_value() && *s.assignedDiscipleId == discipleId) {
            s.assignedDiscipleId = std::nullopt;
            s.assignedDiscipleName.clear();
        }
    }
    out.ok = true;
    return out;
}

/// 卸任事务（Kotlin removeDiscipleFromProductionSlot 的镜像段等价）
///
/// 判定序：① 目标槽存在性（缺失 → InvalidSlot 零写入）
///         ② 捕获占用者（回传 Kotlin 做 gate 释放与状态推导）
///         ③ WORKING 且原占用者非空 → 剩余时长归一；
///            否则仅清 occupant（status 不变——卸任不改变生产状态，与 Kotlin 同）
///
/// 零 RNG。Room 写入由 Kotlin 残差回放本事务产出的镜像槽（同式归一秒级一致）。
inline ProductionUiOutcome removeProductionSlotDiscipleTx(
    gamecore::state::GameState& state, const std::string& buildingType,
    int32_t slotIndex) {
    ProductionUiOutcome out;
    auto& gd = state.gameData;
    ProductionSlot* slot = findProductionSlot(gd, buildingType, slotIndex);
    if (slot == nullptr) {
        out.errorType = "InvalidSlot";
        out.message = "槽位不存在: " + buildingType + "[" + std::to_string(slotIndex) + "]";
        return out;
    }
    out.discipleId = slot->assignedDiscipleId.value_or("");
    out.oldOccupantId = out.discipleId;
    const bool hasOccupant = !out.discipleId.empty();
    if (slot->status == "WORKING" && hasOccupant) {
        // TimeProgressUtil.calculateRemainingMonths 等价：max(duration - elapsed, 0)
        const int32_t elapsed =
            (gd.gameYear - slot->startYear) * 12 + (gd.gameMonth - slot->startMonth);
        const int32_t remaining = slot->duration - elapsed;
        slot->assignedDiscipleId = std::nullopt;
        slot->assignedDiscipleName.clear();
        slot->startYear = gd.gameYear;
        slot->startMonth = gd.gameMonth;
        slot->duration = remaining > 1 ? remaining : 1;
    } else {
        slot->assignedDiscipleId = std::nullopt;
        slot->assignedDiscipleName.clear();
    }
    out.ok = true;
    return out;
}

/// 自动续炼开关翻转事务（Kotlin toggleAutoRestart 的镜像段等价）。
/// 判定序：槽位存在性 → 翻转镜像字段并回传新值（Kotlin 残差按回执回放 Room）。
/// 零 RNG。
inline ProductionUiOutcome toggleAutoRestartTx(state::GameState& state,
                                               const std::string& buildingType,
                                               int32_t slotIndex) {
    ProductionUiOutcome out;
    ProductionSlot* slot = findProductionSlot(state.gameData, buildingType, slotIndex);
    if (slot == nullptr) {
        out.errorType = "InvalidSlot";
        out.message = "槽位不存在: " + buildingType + "[" + std::to_string(slotIndex) + "]";
        return out;
    }
    slot->autoRestartEnabled = !slot->autoRestartEnabled;
    out.newValue = slot->autoRestartEnabled;
    out.ok = true;
    return out;
}

/// 惰性建槽 / 镜像槽维护事务（Kotlin addProductionSlot 的镜像段等价）。
///
/// BuildingDelegate 语义：放置建筑时 Kotlin 已把新槽追加进镜像（updateGameData）
/// 再逐槽落 Room；本事务按 (buildingId, slotIndex) upsert（命中 → 覆写，缺失 →
/// 追加），使镜像恒为真源，Kotlin 残差随后回放 Room（addSlot 幂等）。
/// 零 RNG。
inline ProductionUiOutcome addProductionSlotTx(state::GameState& state,
                                               const ProductionSlot& slot) {
    ProductionUiOutcome out;
    const bool existed = findProductionSlot(state.gameData, slot.buildingType,
                                            slot.slotIndex) != nullptr;
    out.slot = upsertProductionSlot(state.gameData, slot);
    out.created = !existed;
    out.ok = true;
    return out;
}

}  // namespace ui_tx

using ui_tx::ProductionUiOutcome;
using ui_tx::assignProductionSlotTx;
using ui_tx::removeProductionSlotDiscipleTx;
using ui_tx::toggleAutoRestartTx;
using ui_tx::addProductionSlotTx;

}  // namespace gamecore::system::production

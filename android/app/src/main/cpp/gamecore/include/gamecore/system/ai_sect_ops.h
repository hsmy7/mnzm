#pragma once

// ============================================================
// ai_sect_ops.h — AI 宗门月度运营与兽战余量（对应 Kotlin 子事件 6/9）
//
// Kotlin 权威面（逐位对齐目标）：
//   - AISectBattleProcessor.processAISectOperations(year, month, state)
//     （子事件 6：仓库清场 + AI 弟子热控分批修炼 + 宗门等级同步 + 成员过滤）
//   - AISectDiscipleManager.processMonthlyCultivation（修炼/突破/熟练度/孕养）
//   - AISectDiscipleManager.ensureDiscipleGear / rollMissingCategories
//     （等级升级补全——体质/词条/天赋生成器走 disciple_factory.h 既有端口）
//   - AISectBeastAttackProcessor.processRemainingTargets（子事件 9：
//     兽战余量——单 AI 攻妖 / 双 AI 遭遇战 PvP→胜者攻妖）
//   - AISectDiscipleManager.prepareDisciplesForBattle（战前持久化字段组装）
//
// RNG 契约：
//   - AI 独立 RNG（aiRng——systemSeed + 6×31337 播种，独立于
//     RngManager 分区）：突破 roll / 装备槽洗牌种子 / 模板抽取 / 补全生成
//   - BATTLE 分区：battle::executeBattle（与 Kotlin BattleExecutionRouter
//     →nativeBattleExecute 同分区）
//
// 热控分批：
//   - 批状态机（lastSettleMonth 哨兵 -1 + 月差判定）在 C++ 内存运行；
//   - 批量上界（ThermalMonitor 12/6/3）为平台效应——Kotlin 经
//     nativeSetAiThermalBatchSize 推送（默认 3 = 正常档），C++ 不读平台热状态；
//   - 跳过月份不消耗 RNG（Kotlin 同款，跨热状态演化差异为既定行为）。
//
// 边界（登记）：
//   - aiSectBeastDirectTargets 迭代序：Kotlin 为 Map 插入序（precompute 按
//     妖兽 id 升序写入且消费期不新增键）——C++ std::map 键升序与该序一致
//   - computeAIBatch 的 first-call 相位对齐 / 时钟回退跳过语义逐位保留
//   - AI 弟子突破失败无 HP/MP 惩罚（currentHp -1 满血语义，Kotlin 同款）
// ============================================================

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"
#include "gamecore/system/ai_sect_recruit.h"     // AI 装备链（aiPickEquipmentTemplate/
                                                 // aiGenerateManuals/applyGearToAiDisciple/
                                                 // aiRealmMaxRarity/JavaRandomCompat）
#include "gamecore/system/battle_execution.h"
#include "gamecore/system/disciple_factory.h"    // generateTraitsForDiscipleT（补全生成）
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/mission_completion.h"  // manualStackFromTemplate/JavaRandom
#include "gamecore/system/settlement.h"         // kMsPerPhase1x（phase_settlement 依赖）
#include "gamecore/system/nurture_constants.h"  // 孕养曲线/熟练度常量（S8 上移）
#include "gamecore/system/settlement_detail.h"   // recordGameEvent

namespace gamecore::system::ai_ops {

using gamecore::state::Disciple;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipmentNurtureData;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;
using gamecore::state::ManualStack;
using gamecore::state::WorldLevel;
using gamecore::state::WorldSect;

// ai_sect_recruit.h / phase_settlement.h 的 detail 域助手（装备链/孕养曲线）
using gamecore::system::detail::aiGenerateManuals;
using gamecore::system::detail::aiPickEquipmentTemplate;
using gamecore::system::detail::aiRealmMaxRarity;
using gamecore::system::detail::applyGearToAiDisciple;
using gamecore::system::detail::expRequiredForLevelUp;
using gamecore::system::detail::kAiEquipmentCountByLevel;
using gamecore::system::detail::kAiManualCountByLevel;
using gamecore::system::detail::nurtureMaxLevel;

constexpr int32_t kAiPhasesPerMonth = 3;             // PHASES_PER_MONTH
constexpr int32_t kAiTeamSize = 10;                  // GameConfig.AI.TEAM_SIZE
constexpr int32_t kAiThermalEmergencyBatch = 12;     // THERMAL_EMERGENCY_BATCH
constexpr int32_t kAiThermalReduceBatch = 6;         // THERMAL_REDUCE_BATCH
constexpr int32_t kAiThermalNormalBatch = 3;         // L2 降频：季度批量（默认）
constexpr int32_t kAiMaxProficiency = 30000;         // MAX_PROFICIENCY
const char* const kAiGearRollMarker = "aiGearRolled";  // GEAR_ROLL_MARKER

/// 装备模板按 id 查找（线性扫——equipment_db.h 无 byId 原语）
inline const gamecore::data::EquipmentTemplate* aiEquipmentTemplateById(
        const std::string& id) {
    if (id.empty()) return nullptr;
    for (const auto& t : gamecore::data::equipmentTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

/// 宗门等级 → 装备数量（EQUIPMENT_COUNT_BY_SECT_LEVEL；SectLevel 0..3）——
/// ai_sect_recruit.h kAiEquipmentCountByLevel 同源（{1,2,4,4}）
inline int32_t aiEquipmentCountByLevel(int32_t sectLevel) {
    return kAiEquipmentCountByLevel[std::clamp(sectLevel, 0, 3)];
}

/// 宗门等级 → 功法数量（MANUAL_COUNT_BY_SECT_LEVEL——{1,3,6,6}）
inline int32_t aiManualCountByLevel(int32_t sectLevel) {
    return kAiManualCountByLevel[std::clamp(sectLevel, 0, 3)];
}

inline const char* aiSectLevelName(int32_t level) {
    switch (level) {
        case 1: return "中型宗门";
        case 2: return "大型宗门";
        case 3: return "顶级宗门";
        default: return "小型宗门";   // Kotlin levelName else 分支
    }
}

/// 槽位 id 读取（Kotlin EquipmentSet.idFor）
inline const std::string& aiSlotId(const Disciple& d, int slot) {
    static const std::string kEmpty;
    switch (slot) {
        case 0: return d.weaponId;
        case 1: return d.armorId;
        case 2: return d.bootsId;
        default: return d.accessoryId;
    }
}

/// 槽位写入（Kotlin EquipmentSet.withEquipped——含孕养数据）
inline void aiSetSlot(Disciple& d, int slot, const std::string& id,
                      const EquipmentNurtureData& nurture) {
    switch (slot) {
        case 0: d.weaponId = id; d.weaponNurture = nurture; break;
        case 1: d.armorId = id; d.armorNurture = nurture; break;
        case 2: d.bootsId = id; d.bootsNurture = nurture; break;
        default: d.accessoryId = id; d.accessoryNurture = nurture; break;
    }
}

// ── 熟练度/修炼（processMonthlyCultivation 链） ─────────────────────

/// manualMasteries → 熟练度数据（buildProficiencyDataFromMasteries：
/// proficiency 钳 [0,30000]；fromProficiency 阈值 1000/10000/30000）
inline std::map<std::string, ManualProficiencyData> buildProficiencyDataFromMasteries(
        const Disciple& d) {
    std::map<std::string, ManualProficiencyData> out;
    for (const std::string& mId : d.manualIds) {
        const auto it = d.manualMasteries.find(mId);
        const int32_t mastery = it != d.manualMasteries.end() ? it->second : 0;
        ManualProficiencyData p;
        p.manualId = mId;
        const double clamped = std::min(std::max(static_cast<double>(mastery), 0.0),
                                        static_cast<double>(kAiMaxProficiency));
        p.proficiency = clamped;
        p.maxProficiency = kAiMaxProficiency;
        p.masteryLevel = gamecore::stats::masteryLevelFromProficiency(clamped);
        out.emplace(mId, p);
    }
    return out;
}

/// AI 每旬修炼速率（Kotlin calculateCultivationPerPhase 对象路径——
/// manuals=emptyMap → 功法走静态模板兜底查询；政策/丧亲不参与（对象版
/// 无 sectPolicies 入参、grief 由调用方传 0）；寿命惩罚参与）
inline double aiCultivationRate(
        const Disciple& d,
        const std::map<std::string, ManualProficiencyData>& proficiencies) {
    int32_t rootCount = 1;
    if (!d.spiritRootType.empty()) rootCount = 1;
    for (char c : d.spiritRootType) {
        if (c == ',') ++rootCount;
    }

    // 资质乘区：天赋 + 体质 + 资质属性
    const auto effects = gamecore::stats::mergeEffects(
        gamecore::stats::talentEffectsFor(d.talentIds),
        gamecore::stats::affixEffectsFor(d.affixIds));
    const double aptitudeBonus = gamecore::stats::effectValue(effects, "cultivationSpeed") +
        gamecore::stats::physiqueCultivationBonusFor(d.physiqueIds) +
        gamecore::disciple::aptitudeCultivationBonus(d.aptitude);

    // 资源乘区：manuals 空映射 → Kotlin 兜底分支（ManualDatabase 静态查询）
    double resourceBonus = 0.0;   // buildingBonus(1.0) - 1.0
    for (const std::string& manualId : d.manualIds) {
        const gamecore::data::ManualTemplate* tpl = gamecore::data::manualById(manualId);
        if (tpl == nullptr) continue;
        int32_t masteryLevel = 0;
        const auto p = proficiencies.find(manualId);
        if (p != proficiencies.end()) masteryLevel = p->second.masteryLevel;
        const double masteryBonus = gamecore::stats::masteryLevelBonus(masteryLevel);
        const auto s = tpl->stats.find("cultivationSpeedPercent");
        const int32_t speedPct = s != tpl->stats.end() ? s->second : 0;
        resourceBonus += static_cast<double>(speedPct) * masteryBonus / 100.0;
    }

    // 状态乘区：政策津贴 0 - 丧亲 0（对象版调用方传 0） - 寿命惩罚
    const double statusBonus =
        -gamecore::disciple::calculateLifespanCultivationPenalty(d.age, d.lifespan);

    // 临时乘区：丹药持续加速
    double temporaryBonus = 0.0;
    if (d.pillEffectDuration > 0 && d.pillCultivationSpeedBonus > 0.0) {
        temporaryBonus += d.pillCultivationSpeedBonus;
    }

    const int32_t clampedRoots = std::max(rootCount, 1);
    const double base =
        gamecore::disciple::realmSpeedPerPhase(d.realm) / static_cast<double>(clampedRoots);
    return std::max(base * (1.0 + aptitudeBonus) * (1.0 + resourceBonus) *
                        (1.0 + 0.0) * (1.0 + statusBonus) * (1.0 + temporaryBonus),
                    gamecore::disciple::kMinCultivationPerPhase);
}

/// 突破成功（applyBreakthroughSuccess：修为清零、层数+1 或大境界+1 +
/// 大境界寿命增益）
inline void aiApplyBreakthroughSuccess(Disciple& d) {
    d.cultivation = 0.0;
    const int32_t oldRealm = d.realm;
    if (d.realmLayer < gamecore::disciple::realmConfig(d.realm).maxLayers) {
        d.realmLayer += 1;
    } else {
        d.realm -= 1;
        d.realmLayer = 1;
    }
    if (d.realm != oldRealm) {
        d.lifespan += gamecore::stats::calculateBreakthroughLifespanGain(
            d.realm, d.talentIds, d.affixIds);
    }
}

/// 突破失败（applyBreakthroughFailure：AI 弟子无真实战斗资源状态时跳过
/// HP/MP 惩罚——currentHp/Mp -1 满血语义；BREAKTHROUGH_FAILURE_HP_MP_RATIO）
inline void aiApplyBreakthroughFailure(Disciple& d) {
    constexpr double kFailureRatio = 0.1;
    const bool hasRealHpState = d.currentHp >= 0 || d.currentMp >= 0;
    if (!hasRealHpState) {
        d.cultivation = 0.0;
        return;
    }
    const auto fs = gamecore::stats::finalStats(d, {}, {}, {}, nullptr);
    const int32_t curHp = d.currentHp < 0 ? fs.maxHp : d.currentHp;
    const int32_t curMp = d.currentMp < 0 ? fs.maxMp : d.currentMp;
    d.cultivation = 0.0;
    d.currentHp = std::max(static_cast<int32_t>(static_cast<double>(curHp) * kFailureRatio), 1);
    d.currentMp = std::max(static_cast<int32_t>(static_cast<double>(curMp) * kFailureRatio), 1);
}

/// 单月修炼结算（settleMonthlyCultivation：速率×3 旬累积 → 突破循环 →
/// 大境界变化且品阶上限提升时刷新装备/功法）
inline Disciple aiSettleMonthlyCultivation(Disciple disciple, int32_t sectLevel,
                                           rng::DeterministicRng& aiRng) {
    const auto proficiencies = buildProficiencyDataFromMasteries(disciple);
    const double speed = aiCultivationRate(disciple, proficiencies);
    const double baseCultivation =
        std::isfinite(disciple.cultivation)
            ? std::max(disciple.cultivation, 0.0)
            : 0.0;
    const double safeSpeed = std::isfinite(speed) ? speed : 0.0;
    disciple.cultivation = baseCultivation + safeSpeed * kAiPhasesPerMonth;

    const int32_t realmBefore = disciple.realm;
    while (disciple.cultivation >= gamecore::system::computeMaxCultivation(
                                       disciple.realm, disciple.realmLayer,
                                       disciple.cultivation) &&
           disciple.realm > 0) {
        const gamecore::stats::BreakthroughChanceInput zeroInput;
        const double chance =
            gamecore::stats::calculateBreakthroughChance(disciple, zeroInput);
        if (aiRng.nextDouble() >= chance) {
            aiApplyBreakthroughFailure(disciple);
            break;
        }
        aiApplyBreakthroughSuccess(disciple);
    }

    // 大境界变化且品阶上限提升时才刷新装备/功法（品阶不变的突破不重刷——
    // 防清空孕养/熟练度积累）
    if (disciple.realm != realmBefore &&
        aiRealmMaxRarity(disciple.realm) >
            aiRealmMaxRarity(realmBefore)) {
        applyGearToAiDisciple(aiRng, disciple, sectLevel);
    }
    return disciple;
}

/// 功法熟练度月度等效增长（applyMonthlyProficiencyGain：每旬公式 × 3 旬；
/// 只保留 manualIds 键；模板缺失键值原样保留；负熟练度钳 0）
inline Disciple aiApplyMonthlyProficiencyGain(Disciple disciple) {
    if (disciple.manualIds.empty()) return disciple;
    // calculateProficiencyGainPerPhase(libraryBonus=0) × 3 =
    // BASE_PROFICIENCY_RATE(6.0) × (1+0) × MS_PER_PHASE_1X(2000)/1000 × 3 = 36.0
    const double perMonthGain =
        gamecore::system::kBaseProficiencyRate * 1.0 * 2.0 *
        static_cast<double>(kAiPhasesPerMonth);
    std::map<std::string, int32_t> updated;
    for (const auto& [mId, mastery] : disciple.manualMasteries) {
        if (std::find(disciple.manualIds.begin(), disciple.manualIds.end(), mId) ==
            disciple.manualIds.end()) {
            continue;   // 只保留 manualIds 中的键（清理孤儿条目）
        }
        if (gamecore::data::manualById(mId) == nullptr) {
            updated.emplace(mId, mastery);   // 模板缺失：原样保留
            continue;
        }
        const int32_t v = static_cast<int32_t>(
            static_cast<double>(mastery) + perMonthGain);
        updated.emplace(mId, std::clamp(v, 0, kAiMaxProficiency));
    }
    disciple.manualMasteries = std::move(updated);
    return disciple;
}

/// 单槽孕养增长（applyMonthlyNurtureGain.growNurture：老档回填 + 防御钳制 +
/// 升级曲线与玩家共用）
inline EquipmentNurtureData aiGrowNurture(const std::string& slotEquipmentId,
                                          const EquipmentNurtureData& nurture,
                                          double monthlyGain) {
    // 老档回填：槽位有装备但记录为空 → 0 级起步
    EquipmentNurtureData normalized = nurture;
    if (!slotEquipmentId.empty() && nurture.equipmentId.empty()) {
        const gamecore::data::EquipmentTemplate* tpl = aiEquipmentTemplateById(slotEquipmentId);
        normalized.equipmentId = slotEquipmentId;
        normalized.rarity = tpl ? tpl->rarity : 0;
        normalized.nurtureLevel = 0;
        normalized.nurtureProgress = 0.0;
    }
    const int32_t safeLevel = std::max(normalized.nurtureLevel, 0);
    const double safeProgress =
        std::isfinite(normalized.nurtureProgress)
            ? std::max(normalized.nurtureProgress, 0.0)
            : 0.0;
    normalized.nurtureLevel = safeLevel;
    normalized.nurtureProgress = safeProgress;
    const int32_t maxLevel = nurtureMaxLevel(normalized.rarity);
    const bool canGrow = !normalized.equipmentId.empty() && safeLevel < maxLevel;
    if (!canGrow) return normalized;
    const double expRequired =
        expRequiredForLevelUp(safeLevel, normalized.rarity);
    const double newProgress = safeProgress + monthlyGain;
    const int32_t newLevel = safeLevel + 1;
    if (newProgress >= expRequired) {
        normalized.nurtureLevel = newLevel;
        normalized.nurtureProgress = newLevel >= maxLevel ? 0.0 : newProgress - expRequired;
        return normalized;
    }
    normalized.nurtureProgress = newProgress;
    return normalized;
}

/// 装备孕养月度增长（applyMonthlyNurtureGain：每旬 10.0 × 3 旬；四槽独立）
inline Disciple aiApplyMonthlyNurtureGain(Disciple disciple) {
    constexpr double kMonthlyGain = gamecore::system::kNurtureGainPerPhase * 3.0;
    disciple.weaponNurture = aiGrowNurture(disciple.weaponId, disciple.weaponNurture, kMonthlyGain);
    disciple.armorNurture = aiGrowNurture(disciple.armorId, disciple.armorNurture, kMonthlyGain);
    disciple.bootsNurture = aiGrowNurture(disciple.bootsId, disciple.bootsNurture, kMonthlyGain);
    disciple.accessoryNurture =
        aiGrowNurture(disciple.accessoryId, disciple.accessoryNurture, kMonthlyGain);
    return disciple;
}

/// 缺失体质/词条/天赋补全（rollMissingCategories：空分类 0-3 随机生成，
/// 实际 roll 过才写 GEAR_ROLL_MARKER——防重复 roll 造成 AI 分区序列漂移）
inline Disciple aiRollMissingCategories(Disciple d, rng::DeterministicRng& aiRng) {
    bool rolled = false;
    if (d.physiqueIds.empty()) {
        d.physiqueIds = gamecore::system::detail::generateTraitsForDiscipleT(
            gamecore::data::physiqueTemplates(), aiRng,
            [](const gamecore::data::PhysiqueTemplate&) { return false; });
        rolled = true;
    }
    if (d.affixIds.empty()) {
        d.affixIds = gamecore::system::detail::generateTraitsForDiscipleT(
            gamecore::data::affixTemplates(), aiRng,
            [](const gamecore::data::AffixTemplate&) { return false; });
        rolled = true;
    }
    if (d.talentIds.empty()) {
        d.talentIds = gamecore::system::detail::generateTraitsForDiscipleT(
            gamecore::data::talentTemplates(), aiRng,
            [](const gamecore::data::TalentTemplate& t) {
                return gamecore::system::kDeprecatedTalentTypes().count(t.type) > 0;
            });
        rolled = true;
    }
    if (rolled) {
        d.statusData[kAiGearRollMarker] = "1";
    }
    return d;
}

/// 初始装备孕养数据（generateInitialNurture：0 级 0 进度；模板缺失空记录）
inline EquipmentNurtureData aiGenerateInitialNurture(const std::string& equipmentId) {
    EquipmentNurtureData n;
    const gamecore::data::EquipmentTemplate* tpl = aiEquipmentTemplateById(equipmentId);
    if (tpl == nullptr) return n;   // equipmentId 空串 + rarity 0（Kotlin EquipmentNurtureData("", 0)）
    n.equipmentId = equipmentId;
    n.rarity = tpl->rarity;
    n.nurtureLevel = 0;
    n.nurtureProgress = 0.0;
    return n;
}

/// 只补缺不覆盖（ensureDiscipleGear：体质/词条/天赋空则生成（写标记）；
/// 装备/功法不足则补至宗门等级数量——装备空槽洗牌补齐、功法按含心法口径补全）
inline Disciple aiEnsureDiscipleGear(Disciple d, int32_t sectLevel,
                                     rng::DeterministicRng& aiRng) {
    if (d.statusData.count(kAiGearRollMarker) == 0 ||
        d.statusData[kAiGearRollMarker] != "1") {
        d = aiRollMissingCategories(std::move(d), aiRng);
    }

    const int32_t maxRarity = aiRealmMaxRarity(d.realm);
    const int32_t expectedEquip = aiEquipmentCountByLevel(sectLevel);
    const int32_t expectedManuals = aiManualCountByLevel(sectLevel);

    // 装备：空槽洗牌（java.util.Random 种子 1×nextInt）补至等级数量
    static constexpr int kAllSlots[4] = {0, 1, 2, 3};
    const int32_t currentEquip = static_cast<int32_t>(std::count_if(
        std::begin(kAllSlots), std::end(kAllSlots),
        [&](int slot) { return !aiSlotId(d, slot).empty(); }));
    if (currentEquip < expectedEquip) {
        std::vector<int> emptySlots;
        for (int slot = 0; slot < 4; ++slot) {
            if (aiSlotId(d, slot).empty()) emptySlots.push_back(slot);
        }
        // Kotlin: .shuffled(java.util.Random(rng.nextInt().toLong()))
        const int64_t javaSeed = static_cast<int64_t>(aiRng.nextInt());
        emptySlots = JavaRandomCompat::shuffle(emptySlots, javaSeed);
        const int32_t toAdd = std::min(expectedEquip - currentEquip,
                                       static_cast<int32_t>(emptySlots.size()));
        for (int32_t i = 0; i < toAdd; ++i) {
            const int slot = emptySlots[static_cast<std::size_t>(i)];
            const auto tpl = aiPickEquipmentTemplate(
                aiRng, slot == 0 ? "WEAPON" : slot == 1 ? "ARMOR" : slot == 2 ? "BOOTS"
                                                                              : "ACCESSORY",
                maxRarity);
            if (!tpl.has_value() || *tpl == nullptr) continue;
            const std::string& tid = (*tpl)->id;
            aiSetSlot(d, slot, tid, aiGenerateInitialNurture(tid));
        }
    }

    // 功法：已有心法时补全不再生心法；无则补 1 本（aiGenerateManuals 口径）
    const int32_t currentManuals = static_cast<int32_t>(d.manualIds.size());
    if (currentManuals < expectedManuals) {
        std::set<std::string> existing(d.manualIds.begin(), d.manualIds.end());
        bool hasMindManual = false;
        for (const std::string& mId : d.manualIds) {
            const gamecore::data::ManualTemplate* tpl = gamecore::data::manualById(mId);
            if (tpl != nullptr && tpl->type == "MIND") {
                hasMindManual = true;
                break;
            }
        }
        const auto picked = aiGenerateManuals(
            aiRng, maxRarity, expectedManuals, /*includeMind=*/!hasMindManual);
        std::vector<std::pair<std::string, int32_t>> filtered;
        for (const auto& [mId, mastery] : picked) {
            if (existing.count(mId) == 0) filtered.emplace_back(mId, mastery);
        }
        const int32_t takeCount = std::min(
            static_cast<int32_t>(filtered.size()), expectedManuals - currentManuals);
        for (int32_t i = 0; i < takeCount; ++i) {
            d.manualIds.push_back(filtered[static_cast<std::size_t>(i)].first);
            d.manualMasteries[filtered[static_cast<std::size_t>(i)].first] =
                filtered[static_cast<std::size_t>(i)].second;
        }
    }
    return d;
}

/// 月度修炼（processMonthlyCultivation：逐弟子 repeat(batchMonths)）
inline std::vector<Disciple> aiProcessMonthlyCultivation(
        const std::vector<Disciple>& disciples, int32_t batchMonths,
        int32_t sectLevel, rng::DeterministicRng& aiRng) {
    if (batchMonths <= 0 || disciples.empty()) return disciples;
    std::vector<Disciple> out;
    out.reserve(disciples.size());
    for (const auto& disciple : disciples) {
        if (!disciple.isAlive) {
            out.push_back(disciple);
            continue;
        }
        Disciple working = disciple;
        for (int32_t i = 0; i < batchMonths; ++i) {
            working = aiSettleMonthlyCultivation(working, sectLevel, aiRng);
            working = aiApplyMonthlyProficiencyGain(std::move(working));
            working = aiApplyMonthlyNurtureGain(std::move(working));
        }
        out.push_back(std::move(working));
    }
    return out;
}

// ── 兽战余量（processRemainingTargets 链） ──────────────────────────

/// 战前准备（prepareDisciplesForBattle：持久化装备/功法字段 → 临时实例映射；
/// 装备按弟子独立 map（孕养覆盖），功法模板 id 即实例 id；丹药/血炼不计入）
struct AiPreparedBattle {
    std::vector<Disciple> disciples;
    std::map<std::string, std::map<std::string, EquipmentInstance>> equipmentMapByDisciple;
    std::map<std::string, ManualInstance> manualMap;
    std::map<std::string, std::map<std::string, ManualProficiencyData>> proficiencies;
};

/// 单弟子装备实例映射（buildEquipmentMapForDisciple：模板 → 实例 + 孕养覆盖）
inline std::map<std::string, EquipmentInstance> aiBuildEquipmentMapForDisciple(
        const Disciple& d) {
    std::map<std::string, EquipmentInstance> out;
    auto addEntry = [&](const std::string& eqId, const EquipmentNurtureData& nurture) {
        if (eqId.empty() || out.count(eqId) > 0) return;
        const gamecore::data::EquipmentTemplate* tpl = aiEquipmentTemplateById(eqId);
        if (tpl == nullptr) return;
        EquipmentInstance inst;
        inst.id = eqId;   // 模板 id 即实例 id（AI 侧不落玩家实例表）
        inst.name = tpl->name;
        inst.rarity = tpl->rarity;
        inst.description = tpl->description;
        inst.slot = tpl->slot;
        inst.physicalAttack = tpl->physicalAttack;
        inst.magicAttack = tpl->magicAttack;
        inst.physicalDefense = tpl->physicalDefense;
        inst.magicDefense = tpl->magicDefense;
        inst.speed = tpl->speed;
        inst.hp = tpl->hp;
        inst.mp = tpl->mp;
        inst.critChance = tpl->critChance;
        inst.minRealm = mission_settle::detail::realmMinForRarity(tpl->rarity);
        if (nurture.equipmentId == eqId) {
            inst.nurtureLevel = nurture.nurtureLevel;
            inst.nurtureProgress = nurture.nurtureProgress;
        }
        out.emplace(eqId, std::move(inst));
    };
    addEntry(d.weaponId, d.weaponNurture);
    addEntry(d.armorId, d.armorNurture);
    addEntry(d.bootsId, d.bootsNurture);
    addEntry(d.accessoryId, d.accessoryNurture);
    return out;
}

inline AiPreparedBattle aiPrepareDisciplesForBattle(const std::vector<Disciple>& disciples) {
    AiPreparedBattle prepared;
    prepared.disciples = disciples;
    for (const auto& d : disciples) {
        prepared.equipmentMapByDisciple[d.id] = aiBuildEquipmentMapForDisciple(d);
        // buildManualDataForDisciple：manualIds 逐条模板 → 实例（模板 id 即实例
        // id，去重）；熟练度数据来自 manualMasteries
        for (const std::string& mId : d.manualIds) {
            if (prepared.manualMap.count(mId) > 0) continue;
            const gamecore::data::ManualTemplate* tpl = gamecore::data::manualById(mId);
            if (tpl == nullptr) continue;
            const ManualStack stack =
                mission_settle::detail::manualStackFromTemplate(*tpl);
            ManualInstance inst;
            static_cast<gamecore::state::ManualBase&>(inst) = stack;   // 基类字段复制
            inst.id = mId;
            inst.ownerId = std::nullopt;
            prepared.manualMap.emplace(mId, std::move(inst));
        }
        prepared.proficiencies[d.id] = buildProficiencyDataFromMasteries(d);
    }
    return prepared;
}

/// AI 战斗组装（createAIBattle：preGenStats 妖兽——resolveBeastStats 预计算
/// 属性分支，钳制防存档损坏；beastType=null → getType(0) 虎妖）
inline gamecore::battle::BattleState aiCreateBattle(
        const std::vector<Disciple>& disciples, const WorldLevel& beast,
        const AiPreparedBattle& prepared) {
    gamecore::battle::BattleState state;
    for (const auto& d : prepared.disciples) {
        const auto& eqMap = prepared.equipmentMapByDisciple.at(d.id);
        state.team.push_back(mission_settle::detail::discipleToCombatant(
            d, eqMap, prepared.manualMap, prepared.proficiencies, nullptr));
    }
    // 妖兽（resolveBeastStats preGenStats 分支：钳制后直用）
    const auto& type = gamecore::data::beastTypes().front();   // getType(0) 虎妖
    const int32_t realmIndex = std::clamp(beast.realm, 0, 9);
    gamecore::battle::Combatant b;
    b.id = "beast_1";
    b.name = std::string(type.prefix) + type.name;
    b.side = gamecore::battle::CombatantSide::kAttacker;
    b.hp = std::clamp(beast.beastMaxHp, 1, 10000000);
    b.maxHp = b.hp;
    b.mp = std::max(beast.beastMaxMp, 0);
    b.maxMp = b.mp;
    b.physicalAttack = std::max(beast.beastPhysicalAttack, 0);
    b.magicAttack = std::max(beast.beastMagicAttack, 0);
    b.physicalDefense = std::max(beast.beastPhysicalDefense, 0);
    b.magicDefense = std::max(beast.beastMagicDefense, 0);
    b.speed = std::max(beast.beastSpeed, 0);
    b.critRate = 0.05 + realmIndex * 0.01;
    b.realm = realmIndex;
    b.realmLayer = beast.realmLayer;
    b.element = type.element;
    b.isBeast = true;
    for (const auto& sc : *type.skills) {
        gamecore::battle::CombatSkill s;
        s.name = sc.name;
        s.skillType = sc.skillType;
        s.damageType = sc.damageType;
        s.damageMultiplier = sc.damageMultiplier;
        s.mpCost = sc.mpCost;
        s.cooldown = sc.cooldown;
        s.hits = sc.hits;
        s.healPercent = sc.healPercent;
        s.buffType = sc.buffType;
        s.buffValue = sc.buffValue;
        s.buffDuration = sc.buffDuration;
        s.buffs = sc.buffs;
        s.isAoe = sc.isAoe;
        s.targetScope = sc.targetScope;
        s.shieldPercent = sc.shieldPercent;
        s.turnAdvancePercent = sc.turnAdvancePercent;
        b.skills.push_back(s);
    }
    for (int32_t i = 1; i <= std::max(beast.count, 1); ++i) {
        gamecore::battle::Combatant clone = b;
        clone.id = "beast_" + std::to_string(i);
        state.beasts.push_back(std::move(clone));
    }
    state.maxTurns = gamecore::battle::kMaxTurns;
    return state;
}

// == 兽战余量主链（processRemainingTargets / executeAIVersusBeast /
//    executeAIEncounterBattle / handleAIDeaths / markBeastDefeated） ==-

/// AI 弟子死亡处理（handleAIDeaths：终态 team isDead → aiSectDisciples 标记；
/// AI 弟子池非 DiscipleTables——不走玩家死亡处理器）
inline void aiHandleDeaths(GameState& state, const std::string& aiSectId,
                           const gamecore::battle::BattleResult& result) {
    std::set<std::string> deadIds;
    for (const auto& c : result.team) {
        if (c.isDead()) deadIds.insert(c.id);
    }
    if (deadIds.empty()) return;
    auto it = state.aiSectDisciples.find(aiSectId);
    if (it == state.aiSectDisciples.end()) return;
    for (auto& d : it->second) {
        if (deadIds.count(d.id) > 0) {
            d.isAlive = false;
            d.status = "DEAD";
            d.deathYear = state.gameData.gameYear;  // 尸体保留窗口基准
        }
    }
}

/// 直接标记宗门弟子死亡（applyEncounterDeaths 的 B 侧——PvP beasts 侧死亡）
inline void aiMarkSideDead(GameState& state, const std::string& aiSectId,
                           const std::set<std::string>& deadIds) {
    if (deadIds.empty()) return;
    auto it = state.aiSectDisciples.find(aiSectId);
    if (it == state.aiSectDisciples.end()) return;
    for (auto& d : it->second) {
        if (deadIds.count(d.id) > 0) {
            d.isAlive = false;
            d.status = "DEAD";
            d.deathYear = state.gameData.gameYear;  // 尸体保留窗口基准
        }
    }
}

/// 标记妖兽关卡击败（markBeastDefeated）
inline void aiMarkBeastDefeated(GameState& state, const std::string& beastId) {
    for (auto& level : state.gameData.worldLevels) {
        if (level.id == beastId) {
            level.defeated = true;
            break;
        }
    }
}

/// 单 AI 宗门 vs 妖兽（executeAIVersusBeast：创建/执行/事件/死亡）
inline void aiExecuteVersusBeast(GameState& state, const WorldSect& aiSect,
                                 const WorldLevel& beast,
                                 rng::DeterministicRng& battleRng) {
    auto it = state.aiSectDisciples.find(aiSect.id);
    if (it == state.aiSectDisciples.end()) return;
    std::vector<Disciple> disciples;
    for (const auto& d : it->second) {
        if (d.isAlive) disciples.push_back(d);
        if (static_cast<int32_t>(disciples.size()) >= kAiTeamSize) break;
    }
    if (disciples.empty()) return;

    const auto prepared = aiPrepareDisciplesForBattle(disciples);
    gamecore::battle::BattleState battle =
        aiCreateBattle(prepared.disciples, beast, prepared);
    const auto result =
        gamecore::battle::executeBattle(battle, 1.0, battleRng, -1, nullptr);

    if (result.winner == gamecore::battle::BattleWinner::kTeam) {
        aiMarkBeastDefeated(state, beast.id);
        gamecore::system::settle_util::recordGameEvent(
            state, "WORLD", "ai_beast_hunt",
            aiSect.name + "击败了妖兽「" + beast.beastName + "」");
    } else {
        gamecore::system::settle_util::recordGameEvent(
            state, "WORLD", "ai_beast_fail",
            aiSect.name + "讨伐妖兽「" + beast.beastName + "」失败");
    }
    aiHandleDeaths(state, aiSect.id, result);
}

/// 双 AI 遭遇战（executeAIEncounterBattle：PvP → 死亡 → 胜者 vs 妖兽）。
/// Phase 1 A=DEFENDER / B=ATTACKER，fullHeal=true（满血入战）。
inline void aiExecuteEncounterBattle(GameState& state, const WorldSect& sectA,
                                     const WorldSect& sectB, const WorldLevel& beast,
                                     rng::DeterministicRng& battleRng) {
    auto itA = state.aiSectDisciples.find(sectA.id);
    auto itB = state.aiSectDisciples.find(sectB.id);
    if (itA == state.aiSectDisciples.end() || itB == state.aiSectDisciples.end()) return;
    std::vector<Disciple> teamA;
    for (const auto& d : itA->second) {
        if (d.isAlive) teamA.push_back(d);
        if (static_cast<int32_t>(teamA.size()) >= kAiTeamSize) break;
    }
    std::vector<Disciple> teamB;
    for (const auto& d : itB->second) {
        if (d.isAlive) teamB.push_back(d);
        if (static_cast<int32_t>(teamB.size()) >= kAiTeamSize) break;
    }
    if (teamA.empty() || teamB.empty()) return;

    // Phase 1: AI vs AI PvP
    const auto preparedA = aiPrepareDisciplesForBattle(teamA);
    const auto preparedB = aiPrepareDisciplesForBattle(teamB);
    gamecore::battle::BattleState pvp;
    pvp.maxTurns = gamecore::battle::kMaxTurns;
    for (const auto& d : preparedA.disciples) {
        const auto& eq = preparedA.equipmentMapByDisciple.at(d.id);
        gamecore::battle::Combatant c = mission_settle::detail::discipleToCombatant(
            d, eq, preparedA.manualMap, preparedA.proficiencies, nullptr);
        c.hp = c.maxHp;
        c.mp = c.maxMp;
        pvp.team.push_back(std::move(c));
    }
    for (const auto& d : preparedB.disciples) {
        const auto& eq = preparedB.equipmentMapByDisciple.at(d.id);
        gamecore::battle::Combatant c = mission_settle::detail::discipleToCombatant(
            d, eq, preparedB.manualMap, preparedB.proficiencies, nullptr);
        c.hp = c.maxHp;
        c.mp = c.maxMp;
        c.side = gamecore::battle::CombatantSide::kAttacker;
        pvp.beasts.push_back(std::move(c));
    }
    const auto pvpResult =
        gamecore::battle::executeBattle(pvp, 1.0, battleRng, -1, nullptr);

    std::set<std::string> teamADead;
    for (const auto& c : pvpResult.team) {
        if (c.isDead()) teamADead.insert(c.id);
    }
    std::set<std::string> teamBDead;
    for (const auto& c : pvpResult.beasts) {
        if (c.isDead()) teamBDead.insert(c.id);
    }

    // 死亡处理（A 经 handleAIDeaths；B 直标——applyEncounterDeaths 口径）
    aiHandleDeaths(state, sectA.id, pvpResult);
    aiMarkSideDead(state, sectB.id, teamBDead);

    // Phase 2: 胜者 vs 妖兽
    std::vector<Disciple> winner;
    const bool teamAWon = pvpResult.winner == gamecore::battle::BattleWinner::kTeam;
    const WorldSect& winnerSect = teamAWon ? sectA : sectB;
    const auto& winnerPool = teamAWon ? teamA : teamB;
    const auto& winnerDead = teamAWon ? teamADead : teamBDead;
    for (const auto& d : winnerPool) {
        if (winnerDead.count(d.id) == 0) winner.push_back(d);
    }
    if (winner.empty()) return;

    const auto preparedW = aiPrepareDisciplesForBattle(winner);
    gamecore::battle::BattleState beastBattle =
        aiCreateBattle(preparedW.disciples, beast, preparedW);
    const auto beastResult =
        gamecore::battle::executeBattle(beastBattle, 1.0, battleRng, -1, nullptr);
    if (beastResult.winner == gamecore::battle::BattleWinner::kTeam) {
        aiMarkBeastDefeated(state, beast.id);
        gamecore::system::settle_util::recordGameEvent(
            state, "WORLD", "ai_beast_hunt",
            winnerSect.name + "击败了妖兽「" + beast.beastName + "」");
    }
    aiHandleDeaths(state, winnerSect.id, beastResult);
}

/// 子事件 9：AI 兽战余量（processRemainingTargets——目标消费 + 清空）
inline void aiProcessRemainingTargets(GameState& state,
                                      rng::DeterministicRng& battleRng) {
    const auto targets = state.aiSectBeastDirectTargets;
    if (targets.empty()) return;

    for (const auto& [beastId, aiSectIds] : targets) {
        const WorldLevel* beast = nullptr;
        for (const auto& level : state.gameData.worldLevels) {
            if (level.id == beastId && !level.defeated) {
                beast = &level;
                break;
            }
        }
        if (beast == nullptr) continue;

        const WorldSect* sectA = nullptr;
        const WorldSect* sectB = nullptr;
        if (aiSectIds.size() >= 2) {
            for (const auto& sect : state.gameData.worldMapSects) {
                if (sect.id == aiSectIds[0]) sectA = &sect;
                if (sect.id == aiSectIds[1]) sectB = &sect;
            }
            if (sectA == nullptr || sectB == nullptr) continue;
            aiExecuteEncounterBattle(state, *sectA, *sectB, *beast, battleRng);
        } else if (aiSectIds.size() == 1) {
            for (const auto& sect : state.gameData.worldMapSects) {
                if (sect.id == aiSectIds[0]) {
                    sectA = &sect;
                    break;
                }
            }
            if (sectA == nullptr) continue;
            aiExecuteVersusBeast(state, *sectA, *beast, battleRng);
        }
    }
    state.aiSectBeastDirectTargets.clear();
}

// == 子事件 6：AI 宗门月度运营（processAISectOperations 3 参版） ==-

/// 宗门等级升级判定（any{} 阈值链——SMALL→MEDIUM realm<=5 /
/// MEDIUM→LARGE realm<=4 / LARGE→TOP realm<=2；只升不降）
inline int32_t aiNextSectLevel(int32_t level, const std::vector<Disciple>& disciples) {
    auto anyAliveAtMost = [&](int32_t realmCap) {
        for (const auto& d : disciples) {
            if (d.isAlive && d.realm <= realmCap) return true;
        }
        return false;
    };
    switch (level) {
        case 0: return anyAliveAtMost(5) ? 1 : level;
        case 1: return anyAliveAtMost(4) ? 2 : level;
        case 2: return anyAliveAtMost(2) ? 3 : level;
        default: return level;
    }
}

/// 月度运营（仓库清场 + 热控分批修炼 + 宗门等级同步 + 成员过滤）。
/// @param batchMonths computeAiBatch 结果（0 = 跳过修炼）
inline void aiProcessSectOperations(GameState& state, rng::DeterministicRng& aiRng,
                                    int32_t batchMonths) {
    // 1. 仓库清场（sectDetails 非玩家宗门 warehouse 置空）
    for (auto& [sectId, detail] : state.gameData.sectDetails) {
        const WorldSect* sect = nullptr;
        for (const auto& s : state.gameData.worldMapSects) {
            if (s.id == sectId) {
                sect = &s;
                break;
            }
        }
        if (sect != nullptr && !sect->isPlayerSect && !detail.warehouse.items.empty()) {
            detail.warehouse = gamecore::state::SectWarehouse{};
        }
    }

    // 2. AI 弟子修炼（热控分批：0 = 保留原数据）
    std::map<std::string, std::vector<Disciple>> updatedAiDisciples;
    if (batchMonths > 0) {
        for (const auto& [sectId, disciples] : state.aiSectDisciples) {
            const WorldSect* sect = nullptr;
            for (const auto& s : state.gameData.worldMapSects) {
                if (s.id == sectId) {
                    sect = &s;
                    break;
                }
            }
            if (sect == nullptr || sect->isPlayerSect) {
                updatedAiDisciples[sectId] = disciples;
                continue;
            }
            updatedAiDisciples[sectId] =
                aiProcessMonthlyCultivation(disciples, batchMonths, sect->level, aiRng);
        }
    } else {
        updatedAiDisciples = state.aiSectDisciples;
    }

    // 3. 宗门等级同步（月度修炼只会变强，仅 any{} 短路检查升级——只升不降；
    //    玩家宗门手动升级跳过；顶级跳过）
    std::map<std::string, std::vector<Disciple>> finalAiDisciples = updatedAiDisciples;
    for (auto& sect : state.gameData.worldMapSects) {
        if (sect.isPlayerSect) continue;
        if (sect.level >= 3) continue;   // SectLevel.TOP
        const auto it2 = updatedAiDisciples.find(sect.id);
        if (it2 == updatedAiDisciples.end()) continue;
        const int32_t newLevel = aiNextSectLevel(sect.level, it2->second);
        if (newLevel != sect.level) {
            auto upgraded = it2->second;
            for (auto& d : upgraded) {
                d = aiEnsureDiscipleGear(d, newLevel, aiRng);
            }
            finalAiDisciples[sect.id] = std::move(upgraded);
            sect.level = newLevel;
            sect.levelName = aiSectLevelName(newLevel);
        }
    }

    // 4. 写回：calculated ∩ currentIds（跨系统移除的弟子过滤）
    std::map<std::string, std::vector<Disciple>> merged;
    for (const auto& [sectId, current] : state.aiSectDisciples) {
        std::set<std::string> currentIds;
        for (const auto& d : current) currentIds.insert(d.id);
        std::vector<Disciple> filtered;
        const auto calcIt = finalAiDisciples.find(sectId);
        if (calcIt != finalAiDisciples.end()) {
            for (const auto& d : calcIt->second) {
                if (currentIds.count(d.id) > 0) filtered.push_back(d);
            }
        }
        merged[sectId] = std::move(filtered);
    }
    state.aiSectDisciples = std::move(merged);
}

/// 热控分批状态（computeAIBatch 内存态——不入存档协议）
struct AiMonthBatchState {
    int32_t lastSettleMonth = -1;   // 哨兵 -1 = 未初始化
    int32_t thermalBatchSize = kAiThermalNormalBatch;   // Kotlin 推送（12/6/3）
};

/// 批量计算（computeAIBatch：首调相位对齐 batch=0；时钟回退/同月 batch=0；
/// monthsSince >= batchSize → batch=monthsSince 并推进基准）
inline int32_t aiComputeBatch(AiMonthBatchState& batch, int32_t currentAbsoluteMonth) {
    if (batch.lastSettleMonth < 0) {
        batch.lastSettleMonth =
            (currentAbsoluteMonth - 1) - ((currentAbsoluteMonth - 1) % 3);
        return 0;
    }
    const int32_t monthsSince = currentAbsoluteMonth - batch.lastSettleMonth;
    if (monthsSince <= 0) return 0;
    if (monthsSince >= batch.thermalBatchSize) {
        batch.lastSettleMonth = currentAbsoluteMonth;
        return monthsSince;
    }
    return 0;
}

}  // namespace gamecore::system::ai_ops

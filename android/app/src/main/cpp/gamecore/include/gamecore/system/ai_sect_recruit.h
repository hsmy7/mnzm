#pragma once

// ============================================================
// ai_sect_recruit.h — AI 宗门周期性招募（年变下沉）
//
// Kotlin AISectDiscipleManager（generateYearlyRecruits/generateRandomDisciple/
// applyGearToDisciple/truncateToLimit）+ CaveExplorationProcessor.
// processSectDisciplesYearlyRecruitment + runSectRecruitmentIfDue 差值判据
// 等价移植。
//
// RNG 契约：**AI 独立分区 RNG**（Kotlin AISectDiscipleManager._rng——
// 种子 systemSeed + AI_SECT.id(6) × 31337，initForSlot 播种；不入
// rngStates 分区）。消费序逐位对齐 Kotlin generateRandomDisciple：
//   gender 1×nextInt → 名字（姓氏 1×nextInt + 给定名 1×nextDouble +
//   1×nextInt，冲突循环）→ 灵根（1×nextDouble + Fisher-Yates 4×nextInt）
//   → 悟性 1×nextInt → 资质 1×nextInt → 7×nextGaussian（14×nextDouble）
//   → 三分类（WeightedRoll：数量 1×nextDouble + 每非空轮品阶 1×nextDouble
//   + 选池 1×nextInt）→ 肖像 1×nextInt → 年龄 1×nextInt → 技能
//   9×nextGaussian（18×nextDouble）→ 基础属性/寿命（纯计算）
// 装备/功法（applyGearToAiDisciple）：槽位洗牌与攻防池洗牌用
// java.util.Random 种子（1×nextInt 播种，48 位 LCG 序列由
// JavaRandomCompat 复刻——redeem_code.h），模板选取 1×nextInt/零消费。
//
// 已知边界（对拍排除）：Disciple.id 为 Kotlin UUID 镜像生成字段——
// C++ 用确定性自增 id 占位（同 worldLevels/recruitList 契约）。
// ============================================================

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/data/equipment_db.h"
#include "gamecore/system/inventory.h"  // nextItemIdCounter（id 注册表）
#include "gamecore/data/manual_db.h"
#include "gamecore/data/trait_db.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"
#include "gamecore/system/child_birth.h"
#include "gamecore/system/disciple_factory.h"
#include "gamecore/system/name_service.h"
#include "gamecore/system/redeem_code.h"

namespace gamecore::system {
namespace detail {

// ── 常量（Kotlin AISectDiscipleManager companion 逐值对齐）──
// 周期性招募每周期人数范围（SECT_RECRUIT_MIN_COUNT/MAX_COUNT）
constexpr int32_t kAiSectRecruitMinCount = 1;
constexpr int32_t kAiSectRecruitMaxCount = 5;
// AI 宗门弟子池硬上限（PlantSlotData.MAX_AI_DISCIPLES_PER_SECT）
constexpr int32_t kAiDisciplesPerSectLimit = 1000;
// 装备/功法数量按宗门等级（EQUIPMENT_COUNT_BY_SECT_LEVEL /
// MANUAL_COUNT_BY_SECT_LEVEL：小型/中型/大型/顶级）
constexpr int32_t kAiEquipmentCountByLevel[4] = {1, 2, 4, 4};
constexpr int32_t kAiManualCountByLevel[4] = {1, 3, 6, 6};
// 宗门招募间隔（CultivationEventProcessor.AI_SECT_RECRUIT_INTERVAL_YEARS）
constexpr int32_t kAiSectRecruitIntervalYears = 3;
// 弟子创建各技能上限（GameConfig.Disciple.SKILL_MAX / MAX_LOYALTY）
constexpr int32_t kAiSkillMax = 200;
constexpr int32_t kAiMaxLoyalty = 100;
// 境界 → 装备/功法最高品阶（GameConfig.Realm.getMaxRarity：9,8→1 等）
inline int32_t aiRealmMaxRarity(int32_t realm) {
    switch (realm) {
        case 9:
        case 8: return 1;
        case 7: return 2;
        case 6: return 3;
        case 5: return 4;
        case 4:
        case 3: return 5;
        case 2:
        case 1:
        case 0: return 6;
        default: return 1;
    }
}

// ── AI 弟子确定性 id（Kotlin UUID——镜像生成字段，对拍排除）──────
inline std::string nextAiDiscipleId() {
    // 进程级 static 计数器收敛到 inventory.h 注册表
    return "gc-ai-d-" + std::to_string(nextItemIdCounter("gc-ai-d"));
}

/// AI 版正态整数值（Kotlin `rng.nextGaussian(mean, sigma).roundToInt().
/// coerceIn(min, max)`）——DeterministicRng.nextGaussian 恰好 2×nextDouble
///（Box-Muller + fdlibm，pcg_xsh_rr.h）+ Math.round 半值向正无穷 + 钳制。
inline int32_t aiGaussianInt(rng::DeterministicRng& rng, double mean, double sigma,
                             int32_t min, int32_t max) {
    const double v = rng.nextGaussian(mean, sigma);
    const double rounded = std::floor(v + 0.5);  // Math.round（正负半值均向正无穷）
    const double clamped =
        std::clamp(rounded, static_cast<double>(min), static_cast<double>(max));
    return static_cast<int32_t>(clamped);
}

/// 灵根数 → 悟性/资质阶梯（Kotlin when(spiritRootCount)：1根 80+nextInt(21) …
/// 5根 1+nextInt(20)——与玩家 createDisciple 同构；资质另经 avoidSentinel50）
inline int32_t aiRollByRootCount(rng::DeterministicRng& rng, int32_t spiritRootCount) {
    switch (spiritRootCount) {
        case 1: return 80 + rng.nextInt(21);
        case 2: return 60 + rng.nextInt(21);
        case 3: return 40 + rng.nextInt(21);
        case 4: return 20 + rng.nextInt(21);
        default: return 1 + rng.nextInt(20);
    }
}

/// 随机生成 AI 弟子（Kotlin AISectDiscipleManager.generateRandomDisciple
/// 等价；消费序见文件头——AI 独立分区 RNG）。
/// [usedNames] 当前弟子名集合（冲突规避——每次尝试消费完整名字 RNG 序列）。
inline state::Disciple generateRandomAiDisciple(rng::DeterministicRng& rng,
                                                const std::set<std::string>& usedNames) {
    state::Disciple d;
    d.id = nextAiDiscipleId();               // 镜像生成字段
    d.gender = (rng.nextInt(2) == 0) ? "male" : "female";
    // 1. 名字（XIANXIA 风格；名字 RNG 收敛 AI 分区——Kotlin 同源）
    const auto nameResult = generateName(d.gender, NameStyle::kXianxia, usedNames, rng);
    d.name = nameResult.fullName;
    d.surname = nameResult.surname;
    // 2. 灵根（SpiritRootGenerator：1×nextDouble 定根数 + Fisher-Yates）
    d.spiritRootType = child_birth::generateSpiritRoot(rng);
    const int32_t rootCount = 1 + static_cast<int32_t>(
        std::count(d.spiritRootType.begin(), d.spiritRootType.end(), ','));
    // 3. 悟性/资质（各 1×nextInt；资质避开哨兵 50——自愈判定收敛）
    const int32_t comprehension = aiRollByRootCount(rng, rootCount);
    const int32_t aptitude = avoidSentinel50(aiRollByRootCount(rng, rootCount));
    // 4. 六维方差（7×nextGaussian = 14×nextDouble——AI 版非 gaussianInt）
    const double kVarianceSigma = 16.667;
    d.hpVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.mpVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.physicalAttackVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.magicAttackVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.physicalDefenseVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.magicDefenseVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    d.speedVariance = aiGaussianInt(rng, 0.0, kVarianceSigma, -50, 50);
    // 5. 天赋/体质/词条三分类（与玩家 createDisciple 同构——WeightedRoll；
    // generateTraitsForDiscipleT 定义于 disciple_factory.h 的
    // gamecore::system::detail——与本源文件同命名空间直接调用）
    d.talentIds = generateTraitsForDiscipleT(
        data::talentTemplates(), rng, [](const data::TalentTemplate& t) {
            return kDeprecatedTalentTypes().count(t.type) > 0;
        });
    d.physiqueIds = generateTraitsForDiscipleT(
        data::physiqueTemplates(), rng,
        [](const data::PhysiqueTemplate&) { return false; });
    d.affixIds = generateTraitsForDiscipleT(
        data::affixTemplates(), rng,
        [](const data::AffixTemplate&) { return false; });
    // 6. 肖像（1×nextInt——male 20 / female 17 池）
    const auto& portraits =
        (d.gender == "male") ? malePortraits() : femalePortraits();
    d.portraitRes = portraits[static_cast<std::size_t>(
        rng.nextInt(static_cast<int32_t>(portraits.size())))];
    // 7. 年龄（16 + 1×nextInt(14)——Kotlin 构造参数求值序在技能前）
    d.age = 16 + rng.nextInt(14);
    // 8. 技能（9×nextGaussian = 18×nextDouble + 悟性/资质直填）
    constexpr double kSkillMean = 50.5;
    constexpr double kSkillSigma = 16.5;
    d.intelligence = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.charm = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.loyalty = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiMaxLoyalty);
    d.comprehension = comprehension;
    d.morality = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.artifactRefining = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.pillRefining = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.spiritPlanting = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.mining = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.teaching = aiGaussianInt(rng, kSkillMean, kSkillSigma, 1, kAiSkillMax);
    d.aptitude = aptitude;
    // 9. 基础属性（创建期基准，无 realm 乘区——calculateBaseStatsWithVariance）
    {
        DiscipleRolls rolls;  // 复用聚合结构（值来自 AI 版 variance）
        rolls.hpVariance = d.hpVariance;
        rolls.mpVariance = d.mpVariance;
        rolls.physicalAttackVariance = d.physicalAttackVariance;
        rolls.magicAttackVariance = d.magicAttackVariance;
        rolls.physicalDefenseVariance = d.physicalDefenseVariance;
        rolls.magicDefenseVariance = d.magicDefenseVariance;
        rolls.speedVariance = d.speedVariance;
        applyBaseStats(d, rolls);
    }
    // 10. 寿命（realm 9 基础 80 × (1 + 天赋/词条 lifespan)）
    d.lifespan = computeLifespan(d.talentIds, d.affixIds, /*realm=*/9);
    d.realm = 9;
    d.realmLayer = 1;
    d.cultivation = 0.0;
    d.isAlive = true;
    d.discipleType = "outer";
    return d;
}

// ── 装备/功法（Kotlin applyGearToDisciple 链）──────────────────

/// 按槽位选模板（Kotlin pickEquipmentTemplate）：品阶精确池 1×nextInt；
/// 精确池空 → 同槽最高品阶兜底（maxByOrNull——零消费）；槽池空 → null
inline std::optional<const data::EquipmentTemplate*> aiPickEquipmentTemplate(
    rng::DeterministicRng& rng, const std::string& slot, int32_t maxRarity) {
    std::vector<const data::EquipmentTemplate*> slotTemplates;
    for (const auto& t : data::equipmentTemplates()) {
        if (t.slot == slot) slotTemplates.push_back(&t);
    }
    if (slotTemplates.empty()) return std::nullopt;
    std::vector<const data::EquipmentTemplate*> exact;
    for (const auto* t : slotTemplates) {
        if (t->rarity == maxRarity) exact.push_back(t);
    }
    if (!exact.empty()) {
        return exact[static_cast<std::size_t>(
            rng.nextInt(static_cast<int32_t>(exact.size())))];
    }
    const data::EquipmentTemplate* best = nullptr;
    for (const auto* t : slotTemplates) {
        if (best == nullptr || t->rarity > best->rarity) best = t;
    }
    return best;
}

/// 随机选 count 个槽位生成装备（Kotlin generateEquipmentIds：
/// 4 槽 java.util.Random 洗牌（1×nextInt 播种）→ take(count) → 逐槽选模板）。
/// 返回 槽位 → 模板 id 的有序对列表（槽位序 = 洗牌后 take 序）。
inline std::vector<std::pair<std::string, const data::EquipmentTemplate*>>
aiGenerateEquipmentIds(rng::DeterministicRng& rng, int32_t maxRarity, int32_t count) {
    std::vector<std::pair<std::string, const data::EquipmentTemplate*>> out;
    if (count <= 0) return out;
    std::vector<std::string> slots = {"WEAPON", "ARMOR", "BOOTS", "ACCESSORY"};
    // Kotlin: EquipmentSlot.values().shuffled(java.util.Random(rng.nextInt().toLong()))
    const int64_t javaSeed = static_cast<int64_t>(rng.nextInt());
    const auto shuffled = JavaRandomCompat::shuffle(slots, javaSeed);
    const int32_t takeCount = std::min(count, static_cast<int32_t>(shuffled.size()));
    for (int32_t i = 0; i < takeCount; ++i) {
        const auto picked = aiPickEquipmentTemplate(rng, shuffled[static_cast<std::size_t>(i)],
                                                    maxRarity);
        if (picked.has_value() && *picked != nullptr) {
            out.emplace_back(shuffled[static_cast<std::size_t>(i)], *picked);
        }
    }
    return out;
}

/// 生成功法（Kotlin generateManuals：攻+防池 java.util.Random 洗牌
///（1×nextInt 播种）→ take；心法恒带 1 本（mind 池 1×nextInt））。
/// 返回 (模板 id, 熟练度 0) 对——selectedMind + nonMind.take(remaining) 序。
inline std::vector<std::pair<std::string, int32_t>> aiGenerateManuals(
    rng::DeterministicRng& rng, int32_t maxRarity, int32_t count,
    bool includeMind = true) {
    std::vector<const data::ManualTemplate*> attackPool;
    std::vector<const data::ManualTemplate*> defensePool;
    std::vector<const data::ManualTemplate*> mindPool;
    for (const auto& m : data::manualTemplates()) {
        if (m.rarity != maxRarity) continue;
        if (m.type == "ATTACK") attackPool.push_back(&m);
        else if (m.type == "DEFENSE") defensePool.push_back(&m);
        else if (m.type == "MIND") mindPool.push_back(&m);
    }
    // 攻防合并池洗牌（java.util.Random 种子——1×nextInt）
    std::vector<const data::ManualTemplate*> nonMind = attackPool;
    nonMind.insert(nonMind.end(), defensePool.begin(), defensePool.end());
    const int64_t javaSeed = static_cast<int64_t>(rng.nextInt());
    const auto shuffled = JavaRandomCompat::shuffle(nonMind, javaSeed);

    std::vector<std::pair<std::string, int32_t>> selected;
    if (includeMind && !mindPool.empty()) {
        const auto& mind = mindPool[static_cast<std::size_t>(
            rng.nextInt(static_cast<int32_t>(mindPool.size())))];
        selected.emplace_back(mind->id, 0);
    }
    const int32_t remaining = std::max(count - static_cast<int32_t>(selected.size()), 0);
    for (int32_t i = 0; i < remaining && i < static_cast<int32_t>(shuffled.size()); ++i) {
        selected.emplace_back(shuffled[static_cast<std::size_t>(i)]->id, 0);
    }
    return selected;
}

/// 装备/功法装配（Kotlin applyGearToDisciple：数量按宗门等级；
/// 品阶恒为境界上限 aiRealmMaxRarity(realm)——AI 新弟子炼气 → 凡品 1；
/// 孕养初始 0 级 0 进度（generateInitialNurture））。
inline void applyGearToAiDisciple(rng::DeterministicRng& rng, state::Disciple& d,
                                  int32_t sectLevel) {
    const int32_t levelIdx = std::clamp(sectLevel, 0, 3);
    const int32_t maxRarity = aiRealmMaxRarity(d.realm);
    const int32_t equipCount = kAiEquipmentCountByLevel[levelIdx];
    const int32_t manualCount = kAiManualCountByLevel[levelIdx];

    const auto equipmentIds = aiGenerateEquipmentIds(rng, maxRarity, equipCount);
    const auto manuals = aiGenerateManuals(rng, maxRarity, manualCount);

    auto nurtureFor = [](const std::string& slotId,
                         const std::vector<std::pair<std::string, const data::EquipmentTemplate*>>& list)
        -> state::EquipmentNurtureData {
        for (const auto& kv : list) {
            if (kv.first == slotId) {
                return state::EquipmentNurtureData{
                    kv.second->id, kv.second->rarity, 0, 0.0};
            }
        }
        return state::EquipmentNurtureData{};
    };
    d.weaponNurture = nurtureFor("WEAPON", equipmentIds);
    d.armorNurture = nurtureFor("ARMOR", equipmentIds);
    d.bootsNurture = nurtureFor("BOOTS", equipmentIds);
    d.accessoryNurture = nurtureFor("ACCESSORY", equipmentIds);
    for (const auto& kv : equipmentIds) {
        if (kv.first == "WEAPON") d.weaponId = kv.second->id;
        else if (kv.first == "ARMOR") d.armorId = kv.second->id;
        else if (kv.first == "BOOTS") d.bootsId = kv.second->id;
        else if (kv.first == "ACCESSORY") d.accessoryId = kv.second->id;
    }
    d.manualIds.clear();
    d.manualMasteries.clear();
    for (const auto& m : manuals) {
        d.manualIds.push_back(m.first);
        d.manualMasteries[m.first] = m.second;
    }
}

/// 按战力降序截断至宗门池上限（Kotlin truncateToLimit，稳定排序）。
/// 排序键：isAlive 优先（尸体不再挤占 1000/宗名额、不再把
/// 高属性活弟子保留位挤掉冻结宗门战力），再按 base stats 降序。
inline std::vector<state::Disciple> truncateToAiLimit(
    std::vector<state::Disciple> disciples) {
    if (static_cast<int32_t>(disciples.size()) <= kAiDisciplesPerSectLimit) {
        return disciples;
    }
    std::stable_sort(disciples.begin(), disciples.end(),
                     [](const state::Disciple& a, const state::Disciple& b) {
                         if (a.isAlive != b.isAlive) return a.isAlive > b.isAlive;
                         const int64_t pa = static_cast<int64_t>(a.basePhysicalAttack) +
                                            a.baseMagicAttack + a.baseHp;
                         const int64_t pb = static_cast<int64_t>(b.basePhysicalAttack) +
                                            b.baseMagicAttack + b.baseHp;
                         return pa > pb;
                     });
    disciples.resize(static_cast<std::size_t>(kAiDisciplesPerSectLimit));
    return disciples;
}

/// 生成一批周期性招募新弟子（Kotlin generateYearlyRecruits：
/// count = 1 + 1×nextInt(5)；炼气弟子（applyGearToAiDisciple 前置））
inline std::vector<state::Disciple> generateYearlyAiRecruits(
    rng::DeterministicRng& rng, const std::string& /*sectName*/,
    const std::vector<state::Disciple>& existingDisciples, int32_t sectLevel) {
    std::set<std::string> usedNames;
    for (const auto& d : existingDisciples) usedNames.insert(d.name);
    const int32_t count =
        kAiSectRecruitMinCount + rng.nextInt(kAiSectRecruitMaxCount);
    std::vector<state::Disciple> newRecruits;
    for (int32_t i = 0; i < count; ++i) {
        state::Disciple d = generateRandomAiDisciple(rng, usedNames);
        usedNames.insert(d.name);
        applyGearToAiDisciple(rng, d, sectLevel);
        newRecruits.push_back(std::move(d));
    }
    return newRecruits;
}

// ── 年变招募路由（Kotlin processSectDisciplesYearlyRecruitment）──

/// AI 宗门弟子周期性招募（Kotlin CaveExplorationProcessor.
/// processSectDisciplesYearlyRecruitment + runSectRecruitmentIfDue 差值判据）：
/// year - lastAiSectRecruitYear >= 3 才执行（不满足零效果零消费——惰性门）。
/// 每非玩家宗门生成新弟子并按占领路由分发（玩家占领 → recruitList；
/// 其他宗门占领 → 占领者池；否则自身池——均 truncateToLimit 1000）；
/// 尾部重置招募惰性门 + 自动招募（recruit_settle::processAutoRecruit——
/// 零 RNG，与 Kotlin RecruitService.processAutoRecruit 同源）。
/// [aiRng] AI 独立分区 RNG（调用方 GameCore::aiRng()）。
inline void runSectRecruitmentIfDue(state::GameState& state,
                                    rng::DeterministicRng& aiRng,
                                    int32_t year) {
    auto& gd = state.gameData;
    if (year - gd.lastAiSectRecruitYear < kAiSectRecruitIntervalYears) return;

    std::map<std::string, std::vector<state::Disciple>> updatedAi = state.aiSectDisciples;
    std::vector<state::Disciple> updatedRecruitList = gd.recruitList;

    for (const auto& kv : state.aiSectDisciples) {
        const std::string& sectId = kv.first;
        const state::WorldSect* sect = nullptr;
        for (const auto& s : gd.worldMapSects) {
            if (s.id == sectId) { sect = &s; break; }
        }
        if (sect == nullptr) continue;
        if (sect->isPlayerSect) continue;

        const auto newRecruits =
            generateYearlyAiRecruits(aiRng, sect->name, kv.second, sect->level);
        if (sect->isPlayerOccupied) {
            updatedRecruitList.insert(updatedRecruitList.end(),
                                      newRecruits.begin(), newRecruits.end());
        } else if (!sect->occupierSectId.empty()) {
            std::vector<state::Disciple> merged =
                updatedAi.count(sect->occupierSectId) != 0
                    ? updatedAi.at(sect->occupierSectId) : std::vector<state::Disciple>{};
            merged.insert(merged.end(), newRecruits.begin(), newRecruits.end());
            updatedAi[sect->occupierSectId] = truncateToAiLimit(std::move(merged));
        } else {
            std::vector<state::Disciple> merged = kv.second;
            merged.insert(merged.end(), newRecruits.begin(), newRecruits.end());
            updatedAi[sectId] = truncateToAiLimit(std::move(merged));
        }
    }
    state.aiSectDisciples = std::move(updatedAi);
    gd.recruitList = std::move(updatedRecruitList);
    gd.lastAiSectRecruitYear = year;
    // 被占领 AI 宗门产生新弟子后立即执行自动招募检查 + 重置惰性
    //（Kotlin RecruitService.resetAutoRecruitIdle + autoRejectIdle=false +
    // processAutoRecruit——C++ 侧瞬态门直接复位）
    state.autoRecruitIdle = false;
    state.autoRejectIdle = false;
    recruit_settle::processAutoRecruit(state);
}

}  // namespace detail
}  // namespace gamecore::system

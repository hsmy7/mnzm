#pragma once

// ============================================================
// 招募域月结下沉（S8 子事件 2：autoRecruit）
//
// Kotlin RecruitService.processAutoRecruit + RecruitIntegrity +
// DiscipleTables.allocateAndInsert（资质补算段）+ CaptiveGearUtils.
// materializeCaptiveGear 等价移植（月结残留执行器增量 C++ 化批次 11-1）。
//
// RNG 契约：全链零 RNG 抽取（processAutoRecruit 无任何分区调用；
// allocateAndInsert 的资质补算为 id 确定性散列；俘虏装备/功法落库
// createFromTemplate/toInstance 为纯模板字段复制）。插入顺序即
// DiscipleStore 行序（RNG 红线：行序 == 数组序），俘虏实例 id 采用
// 确定性自增（Kotlin 用 UUID，语义等价——id 不参与业务逻辑，镜像侧
// 以 Kotlin 为准，见 inventory.h generateNewId 同款注释）。
//
// 已知边界（登记 S-16）：
// - 惰性门 autoRecruitIdle 为纯内存运行态（GameState 瞬态字段，不进
//   JSON 协议）；重置点（年度列表刷新/改筛选/生育/净化）仍在 Kotlin，
//   月变真相源切换时接线跨层同步
// - lifeEvents（Kotlin 类体属性）不进快照协议——C++ 侧不维护
// ============================================================

#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "gamecore/data/equipment_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple_stats.h"
#include "gamecore/system/settlement_detail.h"
#include "nlohmann/json.hpp"

namespace gamecore::system::recruit_settle {

using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::EquipmentInstance;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;
namespace settle_util = gamecore::system::settle_util;

// ── 常量（Kotlin RecruitService / DiscipleTables / GameConfig / ManualProficiencySystem） ──
constexpr int32_t kMaxReasonableAge = 10000;      // RecruitIntegrity.MAX_REASONABLE_AGE
constexpr int32_t kRecruitMonthlyLimit = 30;      // GameConfig.RECRUIT_MONTHLY_LIMIT
constexpr int32_t kDefaultAptitude = 50;          // DiscipleTables.DEFAULT_APTITUDE
constexpr int64_t kAptitudeHashMultiplier = 527L; // DiscipleTables.APTITUDE_HASH_MULTIPLIER
constexpr int64_t kAptitudeHashOffset = 31L;      // DiscipleTables.APTITUDE_HASH_OFFSET
constexpr int32_t kBaseManualSlots = 6;           // DiscipleStatCalculator.BASE_MANUAL_SLOTS
constexpr double kMaxProficiency = 30000.0;       // ManualProficiencySystem.MAX_PROFICIENCY
/// 同人签名分隔符（Kotlin SIGNATURE_SEPARATOR："\u0001"）
constexpr char kSignatureSeparator = '\x01';

/// 实例 id 生成（确定性自增——Kotlin 用 UUID，语义等价：仅保证唯一，
/// id 不参与业务逻辑；存档恢复时以 Kotlin 镜像为准，同 inventory.h）
inline std::string nextInstanceId() {
    static uint64_t counter = 0;
    return "gc-inst-" + std::to_string(++counter);
}

/// Kotlin Math.floorMod 等价（Long 语义，m 恒正）
inline int64_t floorMod(int64_t v, int64_t m) {
    const int64_t r = v % m;
    return (r < 0) ? r + m : r;
}

/// 空白判定（settle_util 同源）
inline bool isBlank(const std::string& s) { return settle_util::isBlankString(s); }

/// 灵根数（Kotlin `spiritRootType.split(",").count { it.isNotBlank() }`；
/// 空串 → [""] → 0；"metal" → 1；"metal,,fire" → 2）
inline int32_t nonBlankRootCount(const std::string& spiritRootType) {
    if (isBlank(spiritRootType)) return 0;
    int32_t count = 0;
    std::size_t start = 0;
    while (start <= spiritRootType.size()) {
        const std::size_t comma = spiritRootType.find(',', start);
        const std::size_t end = (comma == std::string::npos) ? spiritRootType.size() : comma;
        const std::string part = spiritRootType.substr(start, end - start);
        if (!isBlank(part)) ++count;
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    return count;
}

/// Kotlin 双精度最短往返字符串（经 nlohmann 数值序列化对齐 Kotlin
/// Double.toString：整数值带 ".0"；0.2 → "0.2"）
inline std::string kotlinDoubleString(double v) {
    return nlohmann::json(v).dump();
}

// ── RecruitIntegrity 移植 ─────────────────────────────────────────

/// 招募条目合法性（Kotlin RecruitIntegrity.isValidRecruit：
/// name 非空白 && age in 1..10000 && realm in 0..9 && 灵根各段非空白）
inline bool isValidRecruit(const Disciple& d) {
    if (isBlank(d.name)) return false;
    if (d.age < 1 || d.age > kMaxReasonableAge) return false;
    if (d.realm < 0 || d.realm > 9) return false;   // VALID_REALM_RANGE = CONFIGS 键域 0..9
    // spiritRootType.split(",").all { isNotBlank }（空串 → [""] → false）
    if (d.spiritRootType.empty()) return false;
    std::size_t start = 0;
    while (start <= d.spiritRootType.size()) {
        const std::size_t comma = d.spiritRootType.find(',', start);
        const std::size_t end = (comma == std::string::npos) ? d.spiritRootType.size() : comma;
        if (isBlank(d.spiritRootType.substr(start, end - start))) return false;
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    return true;
}

/// 同人稳定签名（Kotlin samePersonSignature：name+surname+gender+
/// spiritRootType+sorted(talentIds).join(",")，"\x01" 分隔）
inline std::string samePersonSignature(const Disciple& d) {
    std::vector<std::string> sortedTalents = d.talentIds;
    std::sort(sortedTalents.begin(), sortedTalents.end());
    std::string talents;
    for (std::size_t i = 0; i < sortedTalents.size(); ++i) {
        if (i > 0) talents += ",";
        talents += sortedTalents[i];
    }
    return d.name + kSignatureSeparator + d.surname + kSignatureSeparator +
           d.gender + kSignatureSeparator + d.spiritRootType + kSignatureSeparator +
           talents;
}

/// 跨表同人判定（Kotlin RecruitIntegrity.isSamePerson：签名相等 +
/// 存活对称容差 2 / 已故非对称容差 2）
inline bool isSamePerson(const Disciple& a, const Disciple& b) {
    if (samePersonSignature(a) != samePersonSignature(b)) return false;
    if (b.isAlive) {
        const int32_t diff = a.age > b.age ? a.age - b.age : b.age - a.age;
        return diff <= 2;
    }
    return a.age >= b.age - 2;
}

/// 内容去重判定（Kotlin `copy(id="same", slotId=0)` data class 全字段相等
/// ——协议排除 id/slotId，故比较 id 之外全部协议字段）
inline bool discipleContentEquals(const Disciple& a, const Disciple& b) {
    if (a.name != b.name || a.surname != b.surname) return false;
    if (a.realm != b.realm || a.realmLayer != b.realmLayer) return false;
    if (a.cultivation != b.cultivation ||
        a.cultivationCheckpoint != b.cultivationCheckpoint ||
        a.cultivationCheckpointGameMonth != b.cultivationCheckpointGameMonth) return false;
    if (a.spiritRootType != b.spiritRootType || a.age != b.age ||
        a.lifespan != b.lifespan || a.isAlive != b.isAlive ||
        a.gender != b.gender || a.portraitRes != b.portraitRes) return false;
    if (a.manualIds != b.manualIds || a.talentIds != b.talentIds ||
        a.physiqueIds != b.physiqueIds || a.affixIds != b.affixIds) return false;
    if (a.manualMasteries != b.manualMasteries) return false;
    if (a.status != b.status || a.statusData != b.statusData) return false;
    if (a.cultivationSpeedBonus != b.cultivationSpeedBonus ||
        a.cultivationSpeedDuration != b.cultivationSpeedDuration) return false;
    if (a.discipleType != b.discipleType || a.soulPower != b.soulPower) return false;
    if (a.cultivationCompletionMonth != b.cultivationCompletionMonth ||
        a.cultivationCompletionPhase != b.cultivationCompletionPhase ||
        a.manualCompletionMonth != b.manualCompletionMonth ||
        a.manualCompletionPhase != b.manualCompletionPhase ||
        a.equipmentNurturingCompletionMonth != b.equipmentNurturingCompletionMonth ||
        a.equipmentNurturingCompletionPhase != b.equipmentNurturingCompletionPhase) return false;
    if (a.baseHp != b.baseHp || a.baseMp != b.baseMp ||
        a.basePhysicalAttack != b.basePhysicalAttack ||
        a.baseMagicAttack != b.baseMagicAttack ||
        a.basePhysicalDefense != b.basePhysicalDefense ||
        a.baseMagicDefense != b.baseMagicDefense ||
        a.baseSpeed != b.baseSpeed) return false;
    if (a.hpVariance != b.hpVariance || a.mpVariance != b.mpVariance ||
        a.physicalAttackVariance != b.physicalAttackVariance ||
        a.magicAttackVariance != b.magicAttackVariance ||
        a.physicalDefenseVariance != b.physicalDefenseVariance ||
        a.magicDefenseVariance != b.magicDefenseVariance ||
        a.speedVariance != b.speedVariance) return false;
    if (a.totalCultivation != b.totalCultivation ||
        a.breakthroughCount != b.breakthroughCount ||
        a.breakthroughFailCount != b.breakthroughFailCount ||
        a.currentHp != b.currentHp || a.currentMp != b.currentMp) return false;
    if (a.pillPhysicalAttackBonus != b.pillPhysicalAttackBonus ||
        a.pillMagicAttackBonus != b.pillMagicAttackBonus ||
        a.pillPhysicalDefenseBonus != b.pillPhysicalDefenseBonus ||
        a.pillMagicDefenseBonus != b.pillMagicDefenseBonus ||
        a.pillHpBonus != b.pillHpBonus || a.pillMpBonus != b.pillMpBonus ||
        a.pillSpeedBonus != b.pillSpeedBonus) return false;
    if (a.pillCritRateBonus != b.pillCritRateBonus ||
        a.pillCritEffectBonus != b.pillCritEffectBonus ||
        a.pillCultivationSpeedBonus != b.pillCultivationSpeedBonus ||
        a.pillSkillExpSpeedBonus != b.pillSkillExpSpeedBonus ||
        a.pillNurtureSpeedBonus != b.pillNurtureSpeedBonus ||
        a.pillEffectDuration != b.pillEffectDuration) return false;
    if (a.activePillTypes != b.activePillTypes ||
        a.activePillCategory != b.activePillCategory) return false;
    if (a.weaponId != b.weaponId || a.armorId != b.armorId ||
        a.bootsId != b.bootsId || a.accessoryId != b.accessoryId) return false;
    if (a.weaponNurture.equipmentId != b.weaponNurture.equipmentId ||
        a.weaponNurture.rarity != b.weaponNurture.rarity ||
        a.weaponNurture.nurtureLevel != b.weaponNurture.nurtureLevel ||
        a.weaponNurture.nurtureProgress != b.weaponNurture.nurtureProgress) return false;
    if (a.armorNurture.equipmentId != b.armorNurture.equipmentId ||
        a.armorNurture.rarity != b.armorNurture.rarity ||
        a.armorNurture.nurtureLevel != b.armorNurture.nurtureLevel ||
        a.armorNurture.nurtureProgress != b.armorNurture.nurtureProgress) return false;
    if (a.bootsNurture.equipmentId != b.bootsNurture.equipmentId ||
        a.bootsNurture.rarity != b.bootsNurture.rarity ||
        a.bootsNurture.nurtureLevel != b.bootsNurture.nurtureLevel ||
        a.bootsNurture.nurtureProgress != b.bootsNurture.nurtureProgress) return false;
    if (a.accessoryNurture.equipmentId != b.accessoryNurture.equipmentId ||
        a.accessoryNurture.rarity != b.accessoryNurture.rarity ||
        a.accessoryNurture.nurtureLevel != b.accessoryNurture.nurtureLevel ||
        a.accessoryNurture.nurtureProgress != b.accessoryNurture.nurtureProgress) return false;
    if (a.storageBagItems.size() != b.storageBagItems.size()) return false;
    for (std::size_t i = 0; i < a.storageBagItems.size(); ++i) {
        const auto& x = a.storageBagItems[i];
        const auto& y = b.storageBagItems[i];
        if (x.itemId != y.itemId || x.itemType != y.itemType ||
            x.name != y.name || x.rarity != y.rarity ||
            x.quantity != y.quantity ||
            x.obtainedYear != y.obtainedYear ||
            x.obtainedMonth != y.obtainedMonth) return false;
        if (x.effect.has_value() != y.effect.has_value()) return false;
        if (x.effect.has_value() &&
            !(x.effect->tier == y.effect->tier &&
              x.effect->cultivationSpeedPercent == y.effect->cultivationSpeedPercent &&
              x.effect->skillExpSpeedPercent == y.effect->skillExpSpeedPercent &&
              x.effect->nurtureSpeedPercent == y.effect->nurtureSpeedPercent &&
              x.effect->breakthroughChance == y.effect->breakthroughChance &&
              x.effect->targetRealm == y.effect->targetRealm &&
              x.effect->cultivationAdd == y.effect->cultivationAdd &&
              x.effect->skillExpAdd == y.effect->skillExpAdd &&
              x.effect->nurtureAdd == y.effect->nurtureAdd &&
              x.effect->healMaxHpPercent == y.effect->healMaxHpPercent &&
              x.effect->mpRecoverMaxMpPercent == y.effect->mpRecoverMaxMpPercent &&
              x.effect->hpAdd == y.effect->hpAdd &&
              x.effect->mpAdd == y.effect->mpAdd &&
              x.effect->extendLife == y.effect->extendLife &&
              x.effect->physicalAttackAdd == y.effect->physicalAttackAdd &&
              x.effect->magicAttackAdd == y.effect->magicAttackAdd &&
              x.effect->physicalDefenseAdd == y.effect->physicalDefenseAdd &&
              x.effect->magicDefenseAdd == y.effect->magicDefenseAdd &&
              x.effect->speedAdd == y.effect->speedAdd &&
              x.effect->critRateAdd == y.effect->critRateAdd &&
              x.effect->critEffectAdd == y.effect->critEffectAdd &&
              x.effect->intelligenceAdd == y.effect->intelligenceAdd &&
              x.effect->charmAdd == y.effect->charmAdd &&
              x.effect->loyaltyAdd == y.effect->loyaltyAdd &&
              x.effect->comprehensionAdd == y.effect->comprehensionAdd &&
              x.effect->artifactRefiningAdd == y.effect->artifactRefiningAdd &&
              x.effect->pillRefiningAdd == y.effect->pillRefiningAdd &&
              x.effect->spiritPlantingAdd == y.effect->spiritPlantingAdd &&
              x.effect->teachingAdd == y.effect->teachingAdd &&
              x.effect->moralityAdd == y.effect->moralityAdd &&
              x.effect->miningAdd == y.effect->miningAdd &&
              x.effect->revive == y.effect->revive &&
              x.effect->clearAll == y.effect->clearAll &&
              x.effect->isAscension == y.effect->isAscension &&
              x.effect->duration == y.effect->duration &&
              x.effect->cannotStack == y.effect->cannotStack &&
              x.effect->minRealm == y.effect->minRealm &&
              x.effect->pillCategory == y.effect->pillCategory &&
              x.effect->pillType == y.effect->pillType)) return false;
        if (x.grade.has_value() != y.grade.has_value()) return false;
        if (x.grade.has_value() && *x.grade != *y.grade) return false;
        if (x.forgetYear != y.forgetYear || x.forgetMonth != y.forgetMonth ||
            x.forgetPhase != y.forgetPhase) return false;
        if (x.stackedData.has_value() != y.stackedData.has_value()) return false;
        if (x.stackedData.has_value() &&
            !(x.stackedData->minRealm == y.stackedData->minRealm &&
              x.stackedData->slot == y.stackedData->slot &&
              x.stackedData->manualType == y.stackedData->manualType)) return false;
    }
    if (a.storageBagSpiritStones != b.storageBagSpiritStones ||
        a.spiritStones != b.spiritStones) return false;
    if (a.partnerId != b.partnerId || a.partnerSectId != b.partnerSectId ||
        a.parentId1 != b.parentId1 || a.parentId2 != b.parentId2 ||
        a.lastChildYear != b.lastChildYear ||
        a.childBirthMonth != b.childBirthMonth ||
        a.griefEndYear != b.griefEndYear ||
        a.masterId != b.masterId) return false;
    if (a.intelligence != b.intelligence || a.charm != b.charm ||
        a.loyalty != b.loyalty || a.comprehension != b.comprehension ||
        a.artifactRefining != b.artifactRefining ||
        a.pillRefining != b.pillRefining ||
        a.spiritPlanting != b.spiritPlanting ||
        a.mining != b.mining || a.teaching != b.teaching ||
        a.morality != b.morality || a.aptitude != b.aptitude) return false;
    if (a.salaryPaidCount != b.salaryPaidCount ||
        a.salaryMissedCount != b.salaryMissedCount ||
        a.alchemyLevel != b.alchemyLevel ||
        a.alchemyPromotionCount != b.alchemyPromotionCount ||
        a.forgeLevel != b.forgeLevel ||
        a.forgePromotionCount != b.forgePromotionCount) return false;
    if (a.usedPermanentPillKeys != b.usedPermanentPillKeys ||
        a.usedExtendLifePillTypes != b.usedExtendLifePillTypes ||
        a.usedFunctionalPillTypes != b.usedFunctionalPillTypes ||
        a.usedExtendLifePillIds != b.usedExtendLifePillIds) return false;
    if (a.recruitedMonth != b.recruitedMonth ||
        a.hasReviveEffect != b.hasReviveEffect ||
        a.hasClearAllEffect != b.hasClearAllEffect) return false;
    return true;
}

/// 列表内三级去重（Kotlin RecruitIntegrity.dedupeRecruits：
/// id → 内容（id="same"）→ 同人签名，均保留首个）
inline std::vector<Disciple> dedupeRecruits(const std::vector<Disciple>& recruits) {
    // ① 按 id 去重（保留首个）
    std::vector<Disciple> idDeduped;
    std::set<std::string> idSeen;
    for (const auto& d : recruits) {
        if (idSeen.insert(d.id).second) idDeduped.push_back(d);
    }
    // ② 按内容去重（保留首个）
    std::vector<Disciple> contentDeduped;
    for (const auto& d : idDeduped) {
        bool dup = false;
        for (const auto& seen : contentDeduped) {
            if (discipleContentEquals(d, seen)) { dup = true; break; }
        }
        if (!dup) contentDeduped.push_back(d);
    }
    // ③ 按同人签名去重（组内保序，最后按原始位置排序恢复全局保序）
    std::map<std::string, std::vector<std::pair<std::size_t, Disciple>>> groups;
    for (std::size_t i = 0; i < contentDeduped.size(); ++i) {
        groups[samePersonSignature(contentDeduped[i])].push_back({i, contentDeduped[i]});
    }
    std::vector<std::pair<std::size_t, Disciple>> personDeduped;
    for (auto& kv : groups) {
        std::vector<std::pair<std::size_t, Disciple>> kept;
        for (auto& p : kv.second) {
            bool same = false;
            for (const auto& k : kept) {
                if (isSamePerson(k.second, p.second)) { same = true; break; }
            }
            if (!same) kept.push_back(p);
        }
        personDeduped.insert(personDeduped.end(), kept.begin(), kept.end());
    }
    std::sort(personDeduped.begin(), personDeduped.end(),
              [](const auto& x, const auto& y) { return x.first < y.first; });
    std::vector<Disciple> out;
    out.reserve(personDeduped.size());
    for (auto& p : personDeduped) out.push_back(p.second);
    return out;
}

// ── DiscipleTables.allocateAndInsert 等价（资质补算段） ────────────

/// 资质确定性散列（Kotlin DiscipleTables.rollHealedAptitude：
/// floorMod(id*527+31, span) 阶梯补算；命中哨兵 50 强制 +1 收敛）
inline int32_t rollHealedAptitude(int32_t id, int32_t rootCount) {
    int32_t min;
    int32_t span;
    switch (rootCount) {
        case 1: min = 80; span = 21; break;
        case 2: min = 60; span = 21; break;
        case 3: min = 40; span = 21; break;
        case 4: min = 20; span = 21; break;
        default: min = 1; span = 20; break;
    }
    const int64_t roll = floorMod(
        static_cast<int64_t>(id) * kAptitudeHashMultiplier + kAptitudeHashOffset,
        static_cast<int64_t>(span));
    const int32_t aptitude = min + static_cast<int32_t>(roll);
    return (aptitude == kDefaultAptitude) ? kDefaultAptitude + 1 : aptitude;
}

/// 下一个弟子 id（Kotlin `(_ids.maxOrNull() ?: 0) + 1`）
inline std::string nextDiscipleId(const DiscipleStore& ds) {
    int32_t maxId = 0;
    for (std::size_t row = 0; row < ds.size(); ++row) {
        const auto id = settle_util::toIntOrNull(ds.idAt(row));
        if (id.has_value() && *id > maxId) maxId = *id;
    }
    return std::to_string(static_cast<int64_t>(maxId) + 1);
}

/// allocateAndInsert 等价：分配 id（max+1）+ 资质补算 + recruitedMonth +
/// 末尾追加（行序 = 追加序，RNG 红线）。返回新 id。
/// lifeEvents（Kotlin 类体属性，不进协议）C++ 侧不维护（登记 S-16）。
inline std::string allocateAndInsert(DiscipleStore& ds, Disciple d,
                                     int32_t currentMonthIndex) {
    const std::string idStr = nextDiscipleId(ds);
    d.id = idStr;
    d.recruitedMonth = currentMonthIndex;
    if (d.aptitude == kDefaultAptitude) {
        const int32_t rootCount = isBlank(d.spiritRootType)
            ? 5
            : static_cast<int32_t>(std::count(d.spiritRootType.begin(),
                                              d.spiritRootType.end(), ',')) + 1;
        const auto intId = settle_util::toIntOrNull(idStr);
        d.aptitude = rollHealedAptitude(intId.value_or(0), rootCount);
    }
    ds.appendDisciple(d);
    return idStr;
}

// ── CaptiveGearUtils.materializeCaptiveGear 等价 ──────────────────

/// 品阶最低境界（GameConfig.Realm.getMinRealmForRarity）
inline int32_t minRealmForRarity(int32_t rarity) {
    switch (rarity) {
        case 1: return 9;
        case 2: return 7;
        case 3: return 6;
        case 4: return 5;
        case 5: return 4;
        case 6: return 2;
        default: return 9;
    }
}

/// 幂等/合法性守卫（Kotlin shouldMaterializeCaptiveGear：id 存在且未落库
/// ——manualProficiencies 未注册 + 四槽位均未被玩家实例覆盖）
inline bool shouldMaterializeCaptiveGear(const GameState& state,
                                         const Disciple& captive,
                                         const std::string& newId) {
    const auto row = state.disciples.rowOf(newId);
    if (!row.has_value()) return false;
    if (state.gameData.manualProficiencies.count(newId) != 0) return false;
    const auto& ds = state.disciples;
    const auto differsFromTemplate = [&](const std::string& slotId,
                                         const std::string& captiveTemplateId) {
        return !slotId.empty() && slotId != captiveTemplateId;
    };
    if (differsFromTemplate(ds.weaponIds[*row], captive.weaponId) ||
        differsFromTemplate(ds.armorIds[*row], captive.armorId) ||
        differsFromTemplate(ds.bootsIds[*row], captive.bootsId) ||
        differsFromTemplate(ds.accessoryIds[*row], captive.accessoryId)) {
        return false;
    }
    return true;
}

/// 按模板重建单个装备实例（Kotlin buildEquipmentInstanceForCaptive：
/// 模板缺失/空 id → nullopt；孕养继承当且仅当 nurture.equipmentId == 模板 id）
inline std::optional<EquipmentInstance> buildEquipmentInstanceForCaptive(
    const std::string& templateId, const std::string& ownerId,
    const gamecore::state::EquipmentNurtureData& nurture) {
    if (templateId.empty()) return std::nullopt;
    const auto& templates = gamecore::data::equipmentTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
        [&](const auto& t) { return t.id == templateId; });
    if (it == templates.end()) return std::nullopt;
    EquipmentInstance inst;
    inst.id = nextInstanceId();
    inst.name = it->name;
    inst.rarity = it->rarity;
    inst.description = it->description;
    inst.slot = it->slot;
    inst.physicalAttack = it->physicalAttack;
    inst.magicAttack = it->magicAttack;
    inst.physicalDefense = it->physicalDefense;
    inst.magicDefense = it->magicDefense;
    inst.speed = it->speed;
    inst.hp = it->hp;
    inst.mp = it->mp;
    inst.critChance = it->critChance;
    inst.minRealm = minRealmForRarity(it->rarity);
    inst.ownerId = ownerId;
    inst.isEquipped = true;
    if (nurture.equipmentId == templateId) {
        inst.nurtureLevel = nurture.nurtureLevel;
        inst.nurtureProgress = nurture.nurtureProgress;
    }
    return inst;
}

/// 功法熟练度等级（ManualProficiencySystem.MasteryLevel.fromProficiency）
inline int32_t masteryLevelFromProficiency(double proficiency) {
    if (proficiency >= kMaxProficiency) return 3;          // PERFECTION
    if (proficiency >= 10000.0) return 2;                  // GREAT_SUCCESS
    if (proficiency >= 1000.0) return 1;                   // SMALL_SUCCESS
    return 0;                                              // NOVICE
}

/// 单本功法实例构建（Kotlin createManualForCaptive：模板缺失 → null；
/// HP/MP 增量仅当 rawHp >= 0 且增益为正）
struct CreatedManual {
    ManualInstance instance;
    int32_t hp;
    int32_t mp;
    ManualProficiencyData proficiency;
};

inline std::optional<CreatedManual> createManualForCaptive(
    const Disciple& captive, const std::string& templateId,
    const std::string& newId, int32_t maxProf, int32_t hp, int32_t mp) {
    const gamecore::data::ManualTemplate* tpl = gamecore::data::manualById(templateId);
    if (tpl == nullptr) return std::nullopt;
    ManualInstance inst;
    inst.id = nextInstanceId();
    inst.name = tpl->name;
    inst.type = tpl->type;
    inst.rarity = tpl->rarity;
    inst.description = tpl->description;
    inst.stats = tpl->stats;
    inst.skillName = tpl->skillName;
    inst.skillDescription = tpl->skillDescription;
    inst.skillType = tpl->skillType;
    inst.skillDamageType = tpl->skillDamageType;
    inst.skillHits = tpl->skillHits;
    inst.skillDamageMultiplier = tpl->skillDamageMultiplier;
    inst.skillCooldown = tpl->skillCooldown;
    inst.skillMpCost = tpl->skillMpCost;
    inst.skillHealPercent = tpl->skillHealPercent;
    inst.skillHealFixed = tpl->skillHealFixed;
    inst.skillHealType = tpl->skillHealType;
    inst.skillBuffType = tpl->skillBuffType;
    inst.skillBuffValue = tpl->skillBuffValue;
    inst.skillBuffDuration = tpl->skillBuffDuration;
    // skillBuffsJson（Kotlin `skillBuffs.joinToString("|") { "type,value,duration" }`）
    std::string buffsJson;
    for (std::size_t i = 0; i < tpl->skillBuffs.size(); ++i) {
        if (i > 0) buffsJson += "|";
        buffsJson += tpl->skillBuffs[i].type + "," +
                     kotlinDoubleString(tpl->skillBuffs[i].value) + "," +
                     std::to_string(tpl->skillBuffs[i].duration);
    }
    inst.skillBuffsJson = std::move(buffsJson);
    inst.skillIsAoe = tpl->skillIsAoe;
    inst.skillTargetScope = tpl->skillTargetScope;
    inst.skillShieldPercent = tpl->skillShieldPercent;
    inst.skillTurnAdvancePercent = tpl->skillTurnAdvancePercent;
    inst.skillDamageSharePercent = tpl->skillDamageSharePercent;
    inst.skillDamageLinkPercent = tpl->skillDamageLinkPercent;
    inst.minRealm = minRealmForRarity(tpl->rarity);
    inst.ownerId = newId;
    inst.isLearned = true;

    // HP/MP 增量（Kotlin learnManual 对齐：rawHp >= 0 且增益为正才累加）
    const int32_t hpDelta = tpl->stats.count("hp")    ? tpl->stats.at("hp")
                          : tpl->stats.count("maxHp") ? tpl->stats.at("maxHp") : 0;
    const int32_t mpDelta = tpl->stats.count("mp")    ? tpl->stats.at("mp")
                          : tpl->stats.count("maxMp") ? tpl->stats.at("maxMp") : 0;
    const int32_t newHp = (hp >= 0 && hpDelta > 0) ? hp + hpDelta : hp;
    const int32_t newMp = (mp >= 0 && mpDelta > 0) ? mp + mpDelta : mp;

    const double mastery = captive.manualMasteries.count(templateId)
        ? static_cast<double>(captive.manualMasteries.at(templateId)) : 0.0;
    ManualProficiencyData prof;
    prof.manualId = inst.id;
    prof.manualName = tpl->name;
    // 上下界防护：损坏存档负熟练度归零（Kotlin coerceIn(0, maxProf)）
    prof.proficiency = std::max(0.0, std::min(mastery, static_cast<double>(maxProf)));
    prof.maxProficiency = maxProf;
    prof.masteryLevel = masteryLevelFromProficiency(mastery);
    return CreatedManual{std::move(inst), newHp, newMp, std::move(prof)};
}

/// 俘虏装备/功法落库（Kotlin materializeCaptiveGear 全链等价移植；
/// 幂等守卫 + 四槽位实例 + 功法去重截断 + HP/MP 增量 + 熟练度注册）
inline void materializeCaptiveGear(GameState& state, const Disciple& captive,
                                   const std::string& newId) {
    if (!shouldMaterializeCaptiveGear(state, captive, newId)) return;
    const auto intIdOpt = settle_util::toIntOrNull(newId);
    if (!intIdOpt.has_value()) return;
    const int32_t intId = *intIdOpt;
    const auto row = state.disciples.rowOf(newId);
    if (!row.has_value()) return;
    auto& ds = state.disciples;

    // ① 四槽位装备实例（Kotlin materializeEquipments：WEAPON/ARMOR/BOOTS/ACCESSORY）
    struct SlotSpec {
        std::string type;
        std::string templateId;
        gamecore::state::EquipmentNurtureData nurture;
    };
    const std::vector<SlotSpec> slots = {
        {"WEAPON", captive.weaponId, captive.weaponNurture},
        {"ARMOR", captive.armorId, captive.armorNurture},
        {"BOOTS", captive.bootsId, captive.bootsNurture},
        {"ACCESSORY", captive.accessoryId, captive.accessoryNurture},
    };
    for (const auto& s : slots) {
        auto instance = buildEquipmentInstanceForCaptive(s.templateId, newId, s.nurture);
        if (!instance.has_value()) continue;
        const std::string instId = instance->id;
        state.equipmentInstances.push_back(std::move(*instance));
        if (s.type == "WEAPON") ds.weaponIds[*row] = instId;
        else if (s.type == "ARMOR") ds.armorIds[*row] = instId;
        else if (s.type == "BOOTS") ds.bootsIds[*row] = instId;
        else ds.accessoryIds[*row] = instId;
    }

    // ② 功法实例（Kotlin materializeManuals）
    if (captive.manualIds.empty()) return;
    const auto& merged = gamecore::stats::mergeEffects(
        gamecore::stats::talentEffectsFor(captive.talentIds),
        gamecore::stats::affixEffectsFor(captive.affixIds));
    const auto slotIt = merged.find("manualSlot");
    const int32_t maxManualSlots =
        kBaseManualSlots + (slotIt != merged.end() ? static_cast<int32_t>(slotIt->second) : 0);
    // 去重 + 对齐功法槽位上限（Kotlin distinct().take(maxManualSlots)）
    std::vector<std::string> manualTemplateIds;
    for (const auto& id : captive.manualIds) {
        if (std::find(manualTemplateIds.begin(), manualTemplateIds.end(), id) ==
            manualTemplateIds.end()) {
            manualTemplateIds.push_back(id);
        }
        if (static_cast<int32_t>(manualTemplateIds.size()) >= maxManualSlots) break;
    }
    std::map<std::string, std::string> templateToInstanceId;
    std::vector<std::string> newManualIds;
    std::vector<ManualProficiencyData> proficiencyList;
    int32_t hp = ds.currentHps[*row];
    int32_t mp = ds.currentMps[*row];
    for (const auto& templateId : manualTemplateIds) {
        auto created = createManualForCaptive(captive, templateId, newId,
                                              static_cast<int32_t>(kMaxProficiency), hp, mp);
        if (!created.has_value()) continue;
        state.manualInstances.push_back(created->instance);
        templateToInstanceId[templateId] = created->instance.id;
        newManualIds.push_back(created->instance.id);
        if (created->hp != hp) {
            hp = created->hp;
            ds.currentHps[*row] = hp;
        }
        if (created->mp != mp) {
            mp = created->mp;
            ds.currentMps[*row] = mp;
        }
        proficiencyList.push_back(created->proficiency);
    }
    if (newManualIds.empty()) return;
    ds.manualIds[*row] = newManualIds;
    std::map<std::string, int32_t> remappedMasteries;
    for (const auto& kv : captive.manualMasteries) {
        const auto it = templateToInstanceId.find(kv.first);
        if (it != templateToInstanceId.end()) remappedMasteries[it->second] = kv.second;
    }
    ds.manualMasteries[*row] = std::move(remappedMasteries);
    state.gameData.manualProficiencies[newId] = std::move(proficiencyList);
}

// ── RecruitService.processAutoRecruit 主流程 ──────────────────────

/// 自动招募月度执行（Kotlin RecruitService.processAutoRecruit 等价移植；
/// 零 RNG；惰性门 autoRecruitIdle 为 GameState 瞬态字段——见文件头边界）
inline int32_t processAutoRecruit(GameState& state) {
    auto& gd = state.gameData;
    if (state.autoRecruitIdle) return 0;

    const auto& rawFilter = gd.autoRecruitSpiritRootFilter;
    if (rawFilter.empty()) return 0;
    std::set<int32_t> filter;
    for (int32_t v : rawFilter) {
        if (v >= 1 && v <= 5) filter.insert(v);
    }
    if (filter.empty()) return 0;

    // 先过滤损坏条目，再三级去重（Kotlin dedupeValidRecruits）
    std::vector<Disciple> validRecruits;
    for (const auto& d : gd.recruitList) {
        if (isValidRecruit(d)) validRecruits.push_back(d);
    }
    const std::vector<Disciple> distinctRecruits = dedupeRecruits(validRecruits);

    std::vector<Disciple> autoRecruits;
    std::vector<Disciple> keepManual;
    for (const auto& d : distinctRecruits) {
        if (filter.count(nonBlankRootCount(d.spiritRootType)) != 0) {
            autoRecruits.push_back(d);
        } else {
            keepManual.push_back(d);
        }
    }
    if (autoRecruits.empty()) {
        state.autoRecruitIdle = true;
        return 0;
    }

    const int32_t currentCount = std::max(gd.recruitCountThisMonth, 0);
    const int32_t remaining = kRecruitMonthlyLimit - currentCount;
    if (remaining <= 0) return 0;

    const int32_t currentMonthIndex = gd.gameYear * 12 + gd.gameMonth;
    int32_t recruited = 0;
    std::set<std::string> corruptedIds;

    const std::vector<Disciple> toRecruit(
        autoRecruits.begin(), autoRecruits.begin() + std::min<std::size_t>(
            static_cast<std::size_t>(remaining), autoRecruits.size()));
    const std::vector<Disciple> overflowKeep(
        autoRecruits.begin() + toRecruit.size(), autoRecruits.end());

    // 招募配额内弟子入库（Kotlin recruitAutoDisciples）
    for (const auto& disciple : toRecruit) {
        if (!isValidRecruit(disciple)) {
            corruptedIds.insert(disciple.id);
            continue;
        }
        const std::string newId =
            allocateAndInsert(state.disciples, disciple, currentMonthIndex);
        if (!newId.empty()) {
            materializeCaptiveGear(state, disciple, newId);
            ++recruited;
        }
    }

    // 招募结算（Kotlin settleAutoRecruit）
    std::vector<Disciple> corruptedKeep;
    for (const auto& d : autoRecruits) {
        if (corruptedIds.count(d.id) != 0) corruptedKeep.push_back(d);
    }
    const int32_t newRecruitCount = gd.recruitCountThisMonth + recruited;
    std::vector<Disciple> newRecruitList = keepManual;
    newRecruitList.insert(newRecruitList.end(), overflowKeep.begin(), overflowKeep.end());
    newRecruitList.insert(newRecruitList.end(), corruptedKeep.begin(), corruptedKeep.end());
    gd.recruitList = std::move(newRecruitList);
    gd.recruitCountThisMonth = newRecruitCount;
    gd.annualNewDisciples = gd.annualNewDisciples + recruited;

    if (recruited == 0) {
        state.autoRecruitIdle = true;
    }
    return recruited;
}

}  // namespace gamecore::system::recruit_settle

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

// 注意：DiscipleStore/GameState 完整定义由 models.h 提供（其文件尾包含
// disciple_store.h）。本头文件在 tracker 类声明处只需指针（DiscipleStore
// 侧为前向声明 + 指针成员，无反向包含）；导出函数与列序列化需完整类型
// 与 JSON 编解码声明（to_json/normalizeIntegralFloats，均在 json_codec.h）。
#include "gamecore/state/json_codec.h"

// ============================================================
// ColumnDirtyTracker — 列级写屏障（重构方案 R1.4，R2 的前置）
//
// 与 [DirtyTracker]（全量树 diff：每次导出对当前状态做一次全量序列化 +
// 逐键深比较，"任何遗漏路径不漏报"的健壮性口径）互补：本层在**写入点**
// 标记脏（写屏障），导出只序列化脏行 × 脏列 + 集合 tombstone + 标脏的
// gameData 顶层域，免去每次导出的全量序列化与树比较。
//
// ## 范围（本批 = C++ 侧能力，生产不挂载）
//   - 追踪对象 = DiscipleStore SoA 协议列（[DiscipleColumn]，109 列——与
//     Disciple to_json 字段一一对应；非协议派生列 numericIds/hasNumericIds/
//     deathYears/lastTheftJudgementYears 不在册，协议字段 deathYear 无列
//     支撑亦不在册）+ 集合 tombstone（通用：任意实体集合名）+ gameData
//     顶层域名集合。
//   - 写屏障挂点（本批）= DiscipleStore 协议边界变更原语（append/upsert
//     旋转/eraseAt 行位移/swapRows/clear）——即 recruit/死亡/叛逃/读档等
//     行结构变更路径。结算热路径的列直写（ds.cultivations[row] += …）**
//     尚不经屏障**——写点标脏接线属 R2（protobuf 视图契约）范围；生产
//     exportDirtyJson 仍走全量树 diff（对拍显式依赖其逐位行为，零漂移），
//     本层导出为显式 opt-in 能力，挂载前不存在"半正确"的中间态。
//
// ## 导出协议（与 diffToJson 同形：{version, changed, removed}）
//   - changed["disciples"] = 脏行数组；每行恒携带 "id"（键）+ 仅脏列字段；
//   - removed[集合名] = 被删 id 有序数组（std::set，确定性输出）；
//   - changed["gameData.<field>"] = 标脏域的当前值（域级粒度；gameData
//     子树一次序列化后取值，R2 protobuf 直取域成员后无需子树）；
//   - tombstone 撤销规则：被删 id 已回到店内（upsert 保序旋转 = 删→追→
//     旋回）⇒ 撤销 removed 指令、行内容交给行级脏标记（差分协议对同一
//     id 不同时携带 removed 与 changed）；防屏障漏标的保守兜底 = 该行
//     强制整行标脏（宁多报不漏报）。
//   - 版本号语义同 DirtyTracker：每次导出单调 +1、导出即消费（标脏集
//     清空）；resetBaseline 只清标脏集不回退版本。
//
// ## 存储：行主序位图
//   rowBits_[row × kWordsPerRow + w]：每行 kWordsPerRow 个 64 位字，位
//   (row, col)。行级整行/区段标脏（本批挂点的主导模式）为 O(列字数)；
//   单列单行标脏（R2 热路径写点模式）为 O(1) 置位。行索引可复用（删除
//   后追加），陈旧脏位只会多报不会漏报（保守方向安全）。
//
// 确定性约束：tombstone/gameData 域集合一律 std::set/std::map（有序），
// 禁止 unordered 参与导出迭代（RNG 红线对齐）。
// ============================================================
namespace gamecore::state {

/// DiscipleStore SoA 协议列枚举（列身份；与 Disciple to_json 协议字段
/// 一一对应，守卫测试锁定双射——新增列时本枚举与序列化同步扩展）
enum class DiscipleColumn : uint16_t {
    Id,
    Name,
    Surname,
    Realm,
    RealmLayer,
    Cultivation,
    CultivationCheckpoint,
    CultivationCheckpointGameMonth,
    SpiritRootType,
    Age,
    Lifespan,
    IsAlive,
    Gender,
    PortraitRes,
    ManualIds,
    TalentIds,
    PhysiqueIds,
    AffixIds,
    ManualMasteries,
    Status,
    StatusData,
    CultivationSpeedBonus,
    CultivationSpeedDuration,
    DiscipleType,
    SoulPower,
    CultivationCompletionMonth,
    CultivationCompletionPhase,
    ManualCompletionMonth,
    ManualCompletionPhase,
    EquipmentNurturingCompletionMonth,
    EquipmentNurturingCompletionPhase,
    // CombatAttributes
    BaseHp,
    BaseMp,
    BasePhysicalAttack,
    BaseMagicAttack,
    BasePhysicalDefense,
    BaseMagicDefense,
    BaseSpeed,
    HpVariance,
    MpVariance,
    PhysicalAttackVariance,
    MagicAttackVariance,
    PhysicalDefenseVariance,
    MagicDefenseVariance,
    SpeedVariance,
    TotalCultivation,
    BreakthroughCount,
    BreakthroughFailCount,
    CurrentHp,
    CurrentMp,
    // PillEffects
    PillPhysicalAttackBonus,
    PillMagicAttackBonus,
    PillPhysicalDefenseBonus,
    PillMagicDefenseBonus,
    PillHpBonus,
    PillMpBonus,
    PillSpeedBonus,
    PillCritRateBonus,
    PillCritEffectBonus,
    PillCultivationSpeedBonus,
    PillSkillExpSpeedBonus,
    PillNurtureSpeedBonus,
    PillEffectDuration,
    ActivePillTypes,
    ActivePillCategory,
    // EquipmentSet
    WeaponId,
    ArmorId,
    BootsId,
    AccessoryId,
    WeaponNurture,
    ArmorNurture,
    BootsNurture,
    AccessoryNurture,
    StorageBagItems,
    StorageBagSpiritStones,
    SpiritStones,
    // SocialData
    PartnerId,
    PartnerSectId,
    ParentId1,
    ParentId2,
    LastChildYear,
    ChildBirthMonth,
    GriefEndYear,
    MasterId,
    // SkillStats
    Intelligence,
    Charm,
    Loyalty,
    Comprehension,
    ArtifactRefining,
    PillRefining,
    SpiritPlanting,
    Mining,
    Teaching,
    Morality,
    Aptitude,
    SalaryPaidCount,
    SalaryMissedCount,
    AlchemyLevel,
    AlchemyPromotionCount,
    ForgeLevel,
    ForgePromotionCount,
    // UsageTracking
    UsedPermanentPillKeys,
    UsedExtendLifePillTypes,
    UsedFunctionalPillTypes,
    UsedExtendLifePillIds,
    RecruitedMonth,
    HasReviveEffect,
    HasClearAllEffect,
    kCount,
};

inline constexpr uint16_t kDiscipleColumnCount =
    static_cast<uint16_t>(DiscipleColumn::kCount);

/// 弟子集合名（DirtyTracker kEntityCollections 首项；tombstone 行级撤销
/// 规则仅适用于本集合——其余集合无列级行语义）
inline constexpr const char* kDisciplesCollection = "disciples";

/// 列 → JSON 协议字段名（与 Disciple to_json 键一致；守卫测试锁定双射）
constexpr const char* discipleColumnName(DiscipleColumn col) {
    switch (col) {
        case DiscipleColumn::Id: return "id";
        case DiscipleColumn::Name: return "name";
        case DiscipleColumn::Surname: return "surname";
        case DiscipleColumn::Realm: return "realm";
        case DiscipleColumn::RealmLayer: return "realmLayer";
        case DiscipleColumn::Cultivation: return "cultivation";
        case DiscipleColumn::CultivationCheckpoint: return "cultivationCheckpoint";
        case DiscipleColumn::CultivationCheckpointGameMonth: return "cultivationCheckpointGameMonth";
        case DiscipleColumn::SpiritRootType: return "spiritRootType";
        case DiscipleColumn::Age: return "age";
        case DiscipleColumn::Lifespan: return "lifespan";
        case DiscipleColumn::IsAlive: return "isAlive";
        case DiscipleColumn::Gender: return "gender";
        case DiscipleColumn::PortraitRes: return "portraitRes";
        case DiscipleColumn::ManualIds: return "manualIds";
        case DiscipleColumn::TalentIds: return "talentIds";
        case DiscipleColumn::PhysiqueIds: return "physiqueIds";
        case DiscipleColumn::AffixIds: return "affixIds";
        case DiscipleColumn::ManualMasteries: return "manualMasteries";
        case DiscipleColumn::Status: return "status";
        case DiscipleColumn::StatusData: return "statusData";
        case DiscipleColumn::CultivationSpeedBonus: return "cultivationSpeedBonus";
        case DiscipleColumn::CultivationSpeedDuration: return "cultivationSpeedDuration";
        case DiscipleColumn::DiscipleType: return "discipleType";
        case DiscipleColumn::SoulPower: return "soulPower";
        case DiscipleColumn::CultivationCompletionMonth: return "cultivationCompletionMonth";
        case DiscipleColumn::CultivationCompletionPhase: return "cultivationCompletionPhase";
        case DiscipleColumn::ManualCompletionMonth: return "manualCompletionMonth";
        case DiscipleColumn::ManualCompletionPhase: return "manualCompletionPhase";
        case DiscipleColumn::EquipmentNurturingCompletionMonth: return "equipmentNurturingCompletionMonth";
        case DiscipleColumn::EquipmentNurturingCompletionPhase: return "equipmentNurturingCompletionPhase";
        case DiscipleColumn::BaseHp: return "baseHp";
        case DiscipleColumn::BaseMp: return "baseMp";
        case DiscipleColumn::BasePhysicalAttack: return "basePhysicalAttack";
        case DiscipleColumn::BaseMagicAttack: return "baseMagicAttack";
        case DiscipleColumn::BasePhysicalDefense: return "basePhysicalDefense";
        case DiscipleColumn::BaseMagicDefense: return "baseMagicDefense";
        case DiscipleColumn::BaseSpeed: return "baseSpeed";
        case DiscipleColumn::HpVariance: return "hpVariance";
        case DiscipleColumn::MpVariance: return "mpVariance";
        case DiscipleColumn::PhysicalAttackVariance: return "physicalAttackVariance";
        case DiscipleColumn::MagicAttackVariance: return "magicAttackVariance";
        case DiscipleColumn::PhysicalDefenseVariance: return "physicalDefenseVariance";
        case DiscipleColumn::MagicDefenseVariance: return "magicDefenseVariance";
        case DiscipleColumn::SpeedVariance: return "speedVariance";
        case DiscipleColumn::TotalCultivation: return "totalCultivation";
        case DiscipleColumn::BreakthroughCount: return "breakthroughCount";
        case DiscipleColumn::BreakthroughFailCount: return "breakthroughFailCount";
        case DiscipleColumn::CurrentHp: return "currentHp";
        case DiscipleColumn::CurrentMp: return "currentMp";
        case DiscipleColumn::PillPhysicalAttackBonus: return "pillPhysicalAttackBonus";
        case DiscipleColumn::PillMagicAttackBonus: return "pillMagicAttackBonus";
        case DiscipleColumn::PillPhysicalDefenseBonus: return "pillPhysicalDefenseBonus";
        case DiscipleColumn::PillMagicDefenseBonus: return "pillMagicDefenseBonus";
        case DiscipleColumn::PillHpBonus: return "pillHpBonus";
        case DiscipleColumn::PillMpBonus: return "pillMpBonus";
        case DiscipleColumn::PillSpeedBonus: return "pillSpeedBonus";
        case DiscipleColumn::PillCritRateBonus: return "pillCritRateBonus";
        case DiscipleColumn::PillCritEffectBonus: return "pillCritEffectBonus";
        case DiscipleColumn::PillCultivationSpeedBonus: return "pillCultivationSpeedBonus";
        case DiscipleColumn::PillSkillExpSpeedBonus: return "pillSkillExpSpeedBonus";
        case DiscipleColumn::PillNurtureSpeedBonus: return "pillNurtureSpeedBonus";
        case DiscipleColumn::PillEffectDuration: return "pillEffectDuration";
        case DiscipleColumn::ActivePillTypes: return "activePillTypes";
        case DiscipleColumn::ActivePillCategory: return "activePillCategory";
        case DiscipleColumn::WeaponId: return "weaponId";
        case DiscipleColumn::ArmorId: return "armorId";
        case DiscipleColumn::BootsId: return "bootsId";
        case DiscipleColumn::AccessoryId: return "accessoryId";
        case DiscipleColumn::WeaponNurture: return "weaponNurture";
        case DiscipleColumn::ArmorNurture: return "armorNurture";
        case DiscipleColumn::BootsNurture: return "bootsNurture";
        case DiscipleColumn::AccessoryNurture: return "accessoryNurture";
        case DiscipleColumn::StorageBagItems: return "storageBagItems";
        case DiscipleColumn::StorageBagSpiritStones: return "storageBagSpiritStones";
        case DiscipleColumn::SpiritStones: return "spiritStones";
        case DiscipleColumn::PartnerId: return "partnerId";
        case DiscipleColumn::PartnerSectId: return "partnerSectId";
        case DiscipleColumn::ParentId1: return "parentId1";
        case DiscipleColumn::ParentId2: return "parentId2";
        case DiscipleColumn::LastChildYear: return "lastChildYear";
        case DiscipleColumn::ChildBirthMonth: return "childBirthMonth";
        case DiscipleColumn::GriefEndYear: return "griefEndYear";
        case DiscipleColumn::MasterId: return "masterId";
        case DiscipleColumn::Intelligence: return "intelligence";
        case DiscipleColumn::Charm: return "charm";
        case DiscipleColumn::Loyalty: return "loyalty";
        case DiscipleColumn::Comprehension: return "comprehension";
        case DiscipleColumn::ArtifactRefining: return "artifactRefining";
        case DiscipleColumn::PillRefining: return "pillRefining";
        case DiscipleColumn::SpiritPlanting: return "spiritPlanting";
        case DiscipleColumn::Mining: return "mining";
        case DiscipleColumn::Teaching: return "teaching";
        case DiscipleColumn::Morality: return "morality";
        case DiscipleColumn::Aptitude: return "aptitude";
        case DiscipleColumn::SalaryPaidCount: return "salaryPaidCount";
        case DiscipleColumn::SalaryMissedCount: return "salaryMissedCount";
        case DiscipleColumn::AlchemyLevel: return "alchemyLevel";
        case DiscipleColumn::AlchemyPromotionCount: return "alchemyPromotionCount";
        case DiscipleColumn::ForgeLevel: return "forgeLevel";
        case DiscipleColumn::ForgePromotionCount: return "forgePromotionCount";
        case DiscipleColumn::UsedPermanentPillKeys: return "usedPermanentPillKeys";
        case DiscipleColumn::UsedExtendLifePillTypes: return "usedExtendLifePillTypes";
        case DiscipleColumn::UsedFunctionalPillTypes: return "usedFunctionalPillTypes";
        case DiscipleColumn::UsedExtendLifePillIds: return "usedExtendLifePillIds";
        case DiscipleColumn::RecruitedMonth: return "recruitedMonth";
        case DiscipleColumn::HasReviveEffect: return "hasReviveEffect";
        case DiscipleColumn::HasClearAllEffect: return "hasClearAllEffect";
        case DiscipleColumn::kCount: break;
    }
    return nullptr;
}

/// 把 DiscipleStore 单行的单列序列化为 JSON 字段（口径与 Disciple to_json
/// 逐字段一致——cultivationCheckpoint 的 Long 截断 / isAlive 的 bool 语义
/// 同源；ADL to_json 复用嵌套类型编码）
inline void serializeDiscipleColumn(nlohmann::json& row,
                                    const DiscipleStore& ds, std::size_t r,
                                    DiscipleColumn col) {
    using nlohmann::json;
    switch (col) {
        case DiscipleColumn::Id: row["id"] = ds.ids[r]; break;
        case DiscipleColumn::Name: row["name"] = ds.names[r]; break;
        case DiscipleColumn::Surname: row["surname"] = ds.surnames[r]; break;
        case DiscipleColumn::Realm: row["realm"] = ds.realms[r]; break;
        case DiscipleColumn::RealmLayer: row["realmLayer"] = ds.realmLayers[r]; break;
        case DiscipleColumn::Cultivation: row["cultivation"] = ds.cultivations[r]; break;
        // cultivationCheckpoint：Kotlin 序列化为 Long（to_json 同语义向零截断）
        case DiscipleColumn::CultivationCheckpoint:
            row["cultivationCheckpoint"] = static_cast<int64_t>(ds.cultivationCheckpoints[r]);
            break;
        case DiscipleColumn::CultivationCheckpointGameMonth:
            row["cultivationCheckpointGameMonth"] = ds.cultivationCheckpointGameMonths[r];
            break;
        case DiscipleColumn::SpiritRootType: row["spiritRootType"] = ds.spiritRootTypes[r]; break;
        case DiscipleColumn::Age: row["age"] = ds.ages[r]; break;
        case DiscipleColumn::Lifespan: row["lifespan"] = ds.lifespans[r]; break;
        case DiscipleColumn::IsAlive: row["isAlive"] = (ds.isAlive[r] != 0); break;
        case DiscipleColumn::Gender: row["gender"] = ds.genders[r]; break;
        case DiscipleColumn::PortraitRes: row["portraitRes"] = ds.portraitRes[r]; break;
        case DiscipleColumn::ManualIds: row["manualIds"] = ds.manualIds[r]; break;
        case DiscipleColumn::TalentIds: row["talentIds"] = ds.talentIds[r]; break;
        case DiscipleColumn::PhysiqueIds: row["physiqueIds"] = ds.physiqueIds[r]; break;
        case DiscipleColumn::AffixIds: row["affixIds"] = ds.affixIds[r]; break;
        case DiscipleColumn::ManualMasteries: row["manualMasteries"] = ds.manualMasteries[r]; break;
        case DiscipleColumn::Status: row["status"] = ds.statuses[r]; break;
        case DiscipleColumn::StatusData: row["statusData"] = ds.statusData[r]; break;
        case DiscipleColumn::CultivationSpeedBonus:
            row["cultivationSpeedBonus"] = ds.cultivationSpeedBonuses[r];
            break;
        case DiscipleColumn::CultivationSpeedDuration:
            row["cultivationSpeedDuration"] = ds.cultivationSpeedDurations[r];
            break;
        case DiscipleColumn::DiscipleType: row["discipleType"] = ds.discipleTypes[r]; break;
        case DiscipleColumn::SoulPower: row["soulPower"] = ds.soulPowers[r]; break;
        case DiscipleColumn::CultivationCompletionMonth:
            row["cultivationCompletionMonth"] = ds.cultivationCompletionMonths[r];
            break;
        case DiscipleColumn::CultivationCompletionPhase:
            row["cultivationCompletionPhase"] = ds.cultivationCompletionPhases[r];
            break;
        case DiscipleColumn::ManualCompletionMonth:
            row["manualCompletionMonth"] = ds.manualCompletionMonths[r];
            break;
        case DiscipleColumn::ManualCompletionPhase:
            row["manualCompletionPhase"] = ds.manualCompletionPhases[r];
            break;
        case DiscipleColumn::EquipmentNurturingCompletionMonth:
            row["equipmentNurturingCompletionMonth"] = ds.equipmentNurturingCompletionMonths[r];
            break;
        case DiscipleColumn::EquipmentNurturingCompletionPhase:
            row["equipmentNurturingCompletionPhase"] = ds.equipmentNurturingCompletionPhases[r];
            break;
        case DiscipleColumn::BaseHp: row["baseHp"] = ds.baseHps[r]; break;
        case DiscipleColumn::BaseMp: row["baseMp"] = ds.baseMps[r]; break;
        case DiscipleColumn::BasePhysicalAttack:
            row["basePhysicalAttack"] = ds.basePhysicalAttacks[r];
            break;
        case DiscipleColumn::BaseMagicAttack:
            row["baseMagicAttack"] = ds.baseMagicAttacks[r];
            break;
        case DiscipleColumn::BasePhysicalDefense:
            row["basePhysicalDefense"] = ds.basePhysicalDefenses[r];
            break;
        case DiscipleColumn::BaseMagicDefense:
            row["baseMagicDefense"] = ds.baseMagicDefenses[r];
            break;
        case DiscipleColumn::BaseSpeed: row["baseSpeed"] = ds.baseSpeeds[r]; break;
        case DiscipleColumn::HpVariance: row["hpVariance"] = ds.hpVariances[r]; break;
        case DiscipleColumn::MpVariance: row["mpVariance"] = ds.mpVariances[r]; break;
        case DiscipleColumn::PhysicalAttackVariance:
            row["physicalAttackVariance"] = ds.physicalAttackVariances[r];
            break;
        case DiscipleColumn::MagicAttackVariance:
            row["magicAttackVariance"] = ds.magicAttackVariances[r];
            break;
        case DiscipleColumn::PhysicalDefenseVariance:
            row["physicalDefenseVariance"] = ds.physicalDefenseVariances[r];
            break;
        case DiscipleColumn::MagicDefenseVariance:
            row["magicDefenseVariance"] = ds.magicDefenseVariances[r];
            break;
        case DiscipleColumn::SpeedVariance: row["speedVariance"] = ds.speedVariances[r]; break;
        case DiscipleColumn::TotalCultivation:
            row["totalCultivation"] = ds.totalCultivations[r];
            break;
        case DiscipleColumn::BreakthroughCount:
            row["breakthroughCount"] = ds.breakthroughCounts[r];
            break;
        case DiscipleColumn::BreakthroughFailCount:
            row["breakthroughFailCount"] = ds.breakthroughFailCounts[r];
            break;
        case DiscipleColumn::CurrentHp: row["currentHp"] = ds.currentHps[r]; break;
        case DiscipleColumn::CurrentMp: row["currentMp"] = ds.currentMps[r]; break;
        case DiscipleColumn::PillPhysicalAttackBonus:
            row["pillPhysicalAttackBonus"] = ds.pillPhysicalAttackBonuses[r];
            break;
        case DiscipleColumn::PillMagicAttackBonus:
            row["pillMagicAttackBonus"] = ds.pillMagicAttackBonuses[r];
            break;
        case DiscipleColumn::PillPhysicalDefenseBonus:
            row["pillPhysicalDefenseBonus"] = ds.pillPhysicalDefenseBonuses[r];
            break;
        case DiscipleColumn::PillMagicDefenseBonus:
            row["pillMagicDefenseBonus"] = ds.pillMagicDefenseBonuses[r];
            break;
        case DiscipleColumn::PillHpBonus: row["pillHpBonus"] = ds.pillHpBonuses[r]; break;
        case DiscipleColumn::PillMpBonus: row["pillMpBonus"] = ds.pillMpBonuses[r]; break;
        case DiscipleColumn::PillSpeedBonus: row["pillSpeedBonus"] = ds.pillSpeedBonuses[r]; break;
        case DiscipleColumn::PillCritRateBonus:
            row["pillCritRateBonus"] = ds.pillCritRateBonuses[r];
            break;
        case DiscipleColumn::PillCritEffectBonus:
            row["pillCritEffectBonus"] = ds.pillCritEffectBonuses[r];
            break;
        case DiscipleColumn::PillCultivationSpeedBonus:
            row["pillCultivationSpeedBonus"] = ds.pillCultivationSpeedBonuses[r];
            break;
        case DiscipleColumn::PillSkillExpSpeedBonus:
            row["pillSkillExpSpeedBonus"] = ds.pillSkillExpSpeedBonuses[r];
            break;
        case DiscipleColumn::PillNurtureSpeedBonus:
            row["pillNurtureSpeedBonus"] = ds.pillNurtureSpeedBonuses[r];
            break;
        case DiscipleColumn::PillEffectDuration:
            row["pillEffectDuration"] = ds.pillEffectDurations[r];
            break;
        case DiscipleColumn::ActivePillTypes: row["activePillTypes"] = ds.activePillTypes[r]; break;
        case DiscipleColumn::ActivePillCategory:
            row["activePillCategory"] = ds.activePillCategories[r];
            break;
        case DiscipleColumn::WeaponId: row["weaponId"] = ds.weaponIds[r]; break;
        case DiscipleColumn::ArmorId: row["armorId"] = ds.armorIds[r]; break;
        case DiscipleColumn::BootsId: row["bootsId"] = ds.bootsIds[r]; break;
        case DiscipleColumn::AccessoryId: row["accessoryId"] = ds.accessoryIds[r]; break;
        case DiscipleColumn::WeaponNurture: row["weaponNurture"] = ds.weaponNurtures[r]; break;
        case DiscipleColumn::ArmorNurture: row["armorNurture"] = ds.armorNurtures[r]; break;
        case DiscipleColumn::BootsNurture: row["bootsNurture"] = ds.bootsNurtures[r]; break;
        case DiscipleColumn::AccessoryNurture:
            row["accessoryNurture"] = ds.accessoryNurtures[r];
            break;
        case DiscipleColumn::StorageBagItems: row["storageBagItems"] = ds.storageBagItems[r]; break;
        case DiscipleColumn::StorageBagSpiritStones:
            row["storageBagSpiritStones"] = ds.storageBagSpiritStones[r];
            break;
        case DiscipleColumn::SpiritStones: row["spiritStones"] = ds.spiritStones[r]; break;
        case DiscipleColumn::PartnerId: row["partnerId"] = ds.partnerIds[r]; break;
        case DiscipleColumn::PartnerSectId: row["partnerSectId"] = ds.partnerSectIds[r]; break;
        case DiscipleColumn::ParentId1: row["parentId1"] = ds.parentId1s[r]; break;
        case DiscipleColumn::ParentId2: row["parentId2"] = ds.parentId2s[r]; break;
        case DiscipleColumn::LastChildYear: row["lastChildYear"] = ds.lastChildYears[r]; break;
        case DiscipleColumn::ChildBirthMonth:
            row["childBirthMonth"] = ds.childBirthMonths[r];
            break;
        case DiscipleColumn::GriefEndYear: row["griefEndYear"] = ds.griefEndYears[r]; break;
        case DiscipleColumn::MasterId: row["masterId"] = ds.masterIds[r]; break;
        case DiscipleColumn::Intelligence: row["intelligence"] = ds.intelligences[r]; break;
        case DiscipleColumn::Charm: row["charm"] = ds.charms[r]; break;
        case DiscipleColumn::Loyalty: row["loyalty"] = ds.loyalties[r]; break;
        case DiscipleColumn::Comprehension: row["comprehension"] = ds.comprehensions[r]; break;
        case DiscipleColumn::ArtifactRefining:
            row["artifactRefining"] = ds.artifactRefinings[r];
            break;
        case DiscipleColumn::PillRefining: row["pillRefining"] = ds.pillRefinings[r]; break;
        case DiscipleColumn::SpiritPlanting:
            row["spiritPlanting"] = ds.spiritPlantings[r];
            break;
        case DiscipleColumn::Mining: row["mining"] = ds.minings[r]; break;
        case DiscipleColumn::Teaching: row["teaching"] = ds.teachings[r]; break;
        case DiscipleColumn::Morality: row["morality"] = ds.moralities[r]; break;
        case DiscipleColumn::Aptitude: row["aptitude"] = ds.aptitudes[r]; break;
        case DiscipleColumn::SalaryPaidCount:
            row["salaryPaidCount"] = ds.salaryPaidCounts[r];
            break;
        case DiscipleColumn::SalaryMissedCount:
            row["salaryMissedCount"] = ds.salaryMissedCounts[r];
            break;
        case DiscipleColumn::AlchemyLevel: row["alchemyLevel"] = ds.alchemyLevels[r]; break;
        case DiscipleColumn::AlchemyPromotionCount:
            row["alchemyPromotionCount"] = ds.alchemyPromotionCounts[r];
            break;
        case DiscipleColumn::ForgeLevel: row["forgeLevel"] = ds.forgeLevels[r]; break;
        case DiscipleColumn::ForgePromotionCount:
            row["forgePromotionCount"] = ds.forgePromotionCounts[r];
            break;
        case DiscipleColumn::UsedPermanentPillKeys:
            row["usedPermanentPillKeys"] = ds.usedPermanentPillKeys[r];
            break;
        case DiscipleColumn::UsedExtendLifePillTypes:
            row["usedExtendLifePillTypes"] = ds.usedExtendLifePillTypes[r];
            break;
        case DiscipleColumn::UsedFunctionalPillTypes:
            row["usedFunctionalPillTypes"] = ds.usedFunctionalPillTypes[r];
            break;
        case DiscipleColumn::UsedExtendLifePillIds:
            row["usedExtendLifePillIds"] = ds.usedExtendLifePillIds[r];
            break;
        case DiscipleColumn::RecruitedMonth: row["recruitedMonth"] = ds.recruitedMonths[r]; break;
        case DiscipleColumn::HasReviveEffect:
            row["hasReviveEffect"] = (ds.hasReviveEffects[r] != 0);
            break;
        case DiscipleColumn::HasClearAllEffect:
            row["hasClearAllEffect"] = (ds.hasClearAllEffects[r] != 0);
            break;
        case DiscipleColumn::kCount: break;
    }
}

/// 列级写屏障追踪器（挂载/消费语义见文件头注释）
class ColumnDirtyTracker {
public:
    // ── 写屏障（写入点标记）──────────────────────────────────
    /// 单列单行标脏（R2 热路径写点模式；本批挂点走整行/区段）
    void markColumn(DiscipleColumn col, std::size_t row) {
        setBit(row, col);
    }

    /// 整行标脏（协议边界行写入：append/upsert 旋转/swap）
    void markRowAllColumns(std::size_t row) {
        ensureRowCapacity(row + 1);
        const std::size_t base = row * kWordsPerRow;
        for (std::size_t w = 0; w < kWordsPerRow; ++w) {
            rowBits_[base + w] = ~uint64_t{0};
        }
    }

    /// 行区段标脏（eraseAt 行位移：[from, rowCount) 每行内容整体移位）
    void markRowsShiftedFrom(std::size_t from, std::size_t rowCount) {
        for (std::size_t row = from; row < rowCount; ++row) {
            markRowAllColumns(row);
        }
    }

    /// 全脏（装载/清空重建后的基线前状态；基线重置路径一般直接
    /// resetBaseline，本入口供保守全量标脏使用）
    void markAllRowsAllColumns(std::size_t rowCount) {
        markRowsShiftedFrom(0, rowCount);
    }

    /// 集合 tombstone（按 id 删除；任意实体集合名，通用 API）
    void tombstone(const std::string& collection, const std::string& id) {
        tombstones_[collection].insert(id);
    }

    /// gameData 顶层域标脏（域级粒度）
    void markGameDataField(const std::string& field) {
        gameDataFields_.insert(field);
    }

    // ── 查询（守卫/测试）────────────────────────────────────
    bool columnRowDirty(DiscipleColumn col, std::size_t row) const {
        const std::size_t base = row * kWordsPerRow;
        const std::size_t w = static_cast<std::size_t>(col) / 64;
        if (base + w >= rowBits_.size()) return false;
        return (rowBits_[base + w] >> (static_cast<std::size_t>(col) % 64)) & 1u;
    }

    bool anyRowDirty(std::size_t rowCount) const {
        const std::size_t words =
            std::min(rowCount * kWordsPerRow, rowBits_.size());
        for (std::size_t i = 0; i < words; ++i) {
            if (rowBits_[i] != 0) return true;
        }
        return false;
    }

    // ── 导出（导出即消费；协议/撤销规则见文件头）────────────────
    /// 基线重置（全量导出/导入后；清空标脏集，版本号不回退——与
    /// DirtyTracker::resetBaseline 版本语义一致）
    void resetBaseline() {
        rowBits_.clear();
        tombstones_.clear();
        gameDataFields_.clear();
    }

    /// 仅复位行位图（DiscipleStore::clear 用：行已全部删除、每 id 经
    /// tombstone 记账——tombstone 必须保留供导出 removed，行位图随行消亡）
    void clearRowBits() {
        rowBits_.clear();
    }

    /// 导出仅脏列/行/tombstone/域；返回后标脏集清空（导出即消费）。
    /// 参数 = 弟子店 + gameData（域级标脏取值源）；不依赖 GameState 全量
    /// 结构（列级通道的依赖面就是这两块）
    std::string exportDirtyJson(const DiscipleStore& ds, const GameData& gd) {
        ++version_;
        const std::size_t rowCount = ds.size();

        using nlohmann::json;
        json changed = json::object();
        json removed = json::object();

        // gameData 标脏域：域级粒度取当前值（子树一次序列化）
        if (!gameDataFields_.empty()) {
            const json gdTree = json(gd);
            for (const auto& field : gameDataFields_) {
                const auto it = gdTree.find(field);
                if (it != gdTree.end()) changed["gameData." + field] = *it;
            }
        }

        // tombstone 消费：弟子集合中被删 id 已回到店内的（upsert 保序旋转
        // = 删→追→旋回）撤销删除指令、行内容交给行级脏标记——保守兜底
        // 强制整行标脏（宁多报不漏报）；其余集合原样输出 removed
        for (const auto& [collection, ids] : tombstones_) {
            json removedIds = json::array();
            for (const auto& id : ids) {
                if (collection == kDisciplesCollection) {
                    const auto row = ds.rowOf(id);
                    if (row.has_value()) {
                        markRowAllColumns(*row);
                        continue;
                    }
                }
                removedIds.push_back(id);
            }
            if (!removedIds.empty()) removed[collection] = std::move(removedIds);
        }

        // 脏行 × 脏列序列化（行序 = 店行序，确定性）；"id" 为差分协议的
        // 行键，恒携带（无论 Id 位是否标脏）
        if (anyRowDirty(rowCount)) {
            json upserts = json::array();
            for (std::size_t row = 0; row < rowCount; ++row) {
                if (!rowHasAnyBit(row)) continue;
                json e = json::object();
                e["id"] = ds.ids[row];
                for (uint16_t c = 0; c < kDiscipleColumnCount; ++c) {
                    const auto col = static_cast<DiscipleColumn>(c);
                    if (col == DiscipleColumn::Id) continue;  // 键已携带
                    if (columnRowDirty(col, row)) {
                        serializeDiscipleColumn(e, ds, row, col);
                    }
                }
                upserts.push_back(std::move(e));
            }
            if (!upserts.empty()) changed[kDisciplesCollection] = std::move(upserts);
        }

        json out;
        out["version"] = version_;
        out["changed"] = std::move(changed);
        out["removed"] = std::move(removed);

        // 面向 kotlinx 解码器的浮点规范化（与 diffToJson/dumpStateJson 同一规则）
        normalizeIntegralFloats(out);

        resetBaseline();
        return out.dump();
    }

    /// 当前版本号（每次导出后递增；初始 0——语义同 DirtyTracker）
    uint64_t version() const { return version_; }

private:
    static constexpr std::size_t kWordsPerRow =
        (static_cast<std::size_t>(kDiscipleColumnCount) + 63) / 64;

    void ensureRowCapacity(std::size_t rows) {
        const std::size_t need = rows * kWordsPerRow;
        if (rowBits_.size() < need) rowBits_.resize(need, 0);
    }

    void setBit(std::size_t row, DiscipleColumn col) {
        ensureRowCapacity(row + 1);
        const std::size_t base = row * kWordsPerRow;
        rowBits_[base + static_cast<std::size_t>(col) / 64] |=
            (uint64_t{1} << (static_cast<std::size_t>(col) % 64));
    }

    bool rowHasAnyBit(std::size_t row) const {
        const std::size_t base = row * kWordsPerRow;
        if (base + kWordsPerRow > rowBits_.size()) return false;
        for (std::size_t w = 0; w < kWordsPerRow; ++w) {
            if (rowBits_[base + w] != 0) return true;
        }
        return false;
    }

    std::vector<uint64_t> rowBits_;                 // 行主序脏位图（懒扩容）
    std::map<std::string, std::set<std::string>> tombstones_;  // 集合 → 被删 id（有序）
    std::set<std::string> gameDataFields_;          // gameData 标脏顶层域（有序）
    uint64_t version_ = 0;
};

}  // namespace gamecore::state

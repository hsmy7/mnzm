// ============================================================
// trait_db.h — 天赋/体质/词条静态表（C++ 侧等价复刻 Kotlin 生成逻辑）
//
// 与 equipment_db.h / herb_db.h 不同，Talent/Physique/Affix 三个 Registry
// 的数据是**程序化生成**的（config 梯度列表 + 循环拼接字符串），无法用正则
// 提取字面量。故本头文件在 C++ 侧**等价复刻 Kotlin 的生成逻辑**：
//   相同的 config 数值梯度、相同的 id/name/description 拼接规则、
//   相同的 rarity 映射（talentGrade：旧 1-6 阶 → 新 1-3 品）。
//
// 对拍目标（后续守卫测试逐字段比对）：
//   - C++ 表（本头文件）  ←→  JSON 快照 trait_db_sample.json
//   - JSON 快照          ←→  Kotlin Registry 实时数据
//
// 必须与 Kotlin **逐字一致**。命名例外：Kotlin 字段 `template` 为 C++
// 关键字，本表字段名为 `tmpl`（JSON 快照仍用 `template` 键）。
//
// 注意：禁止手改数值/字符串。若 Kotlin 侧调整梯度，须同步更新本表与快照。
// ============================================================
#pragma once

#include <cstdint>
#include <cstdio>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace gamecore::data {

// ============================================================
// 结构体（字段与 Kotlin Registry 的 data class 对应）
// ============================================================

/// 职务职能效果加成（对应 Kotlin `PositionBonus`）
struct PositionBonus {
    /// ElderSlotType 枚举名（如 VICE_SECT_MASTER）
    std::string slotType;
    /// 该职务职能效果的百分比加成（乘算因子）
    double effectBonus = 0.0;

    /// B16/R6.2 数值等价守卫用。C++20 默认派生 ==：逐成员比较由编译器生成，
    /// 覆盖**全部字段**（比手写字段清单更严——不存在漏比某字段的可能）。
    friend bool operator==(const PositionBonus&, const PositionBonus&) = default;
};

/// 天赋模板（对应 Kotlin `TalentDatabase.TalentData`）
struct TalentTemplate {
    std::string id;
    std::string name;
    std::string description;
    int32_t rarity = 0;
    /// 效果 key → 数值（std::map 保证有序，禁止 unordered_map）
    std::map<std::string, double> effects;
    bool isNegative = false;
    /// TalentType 枚举名（如 CULT_SPEED / BAT_PHY_ATK / POSITION_VICE_SECT_MASTER）
    std::string type;
    /// Kotlin 字段名 `template`；C++ 关键字因故改名 tmpl
    std::string tmpl;
    /// 职务加成（无则为空）
    std::optional<PositionBonus> positionBonus;

    /// B16/R6.2 数值等价守卫用（同 PositionBonus：默认派生，全字段比较）
    friend bool operator==(const TalentTemplate&, const TalentTemplate&) = default;
};

/// 体质模板（对应 Kotlin `PhysiqueDatabase.PhysiqueData`）
struct PhysiqueTemplate {
    std::string id;
    std::string name;
    std::string description;
    int32_t rarity = 0;
    double cultivationSpeedBonus = 0.0;
    double damageAmplification = 0.0;
    double damageReduction = 0.0;
    double critDamageBonus = 0.0;
    double defenseBonus = 0.0;
    bool isNegative = false;
    /// PhysiqueType 枚举名
    std::string type;
    /// Kotlin 字段名 `template`；C++ 关键字因故改名 tmpl
    std::string tmpl;

    /// B16/R6.2 数值等价守卫用（同 PositionBonus：默认派生，全字段比较）
    friend bool operator==(const PhysiqueTemplate&, const PhysiqueTemplate&) = default;
};

/// 词条模板（对应 Kotlin `AffixDatabase.AffixData`）
struct AffixTemplate {
    std::string id;
    std::string name;
    std::string description;
    int32_t rarity = 0;
    /// 效果 key → 数值（std::map 保证有序，禁止 unordered_map）
    std::map<std::string, double> effects;
    bool isNegative = false;
    /// AffixType 枚举名
    std::string type;
    /// Kotlin 字段名 `template`；C++ 关键字因故改名 tmpl
    std::string tmpl;
    /// 职务加成（无则为空）
    std::optional<PositionBonus> positionBonus;

    /// B16/R6.2 数值等价守卫用（同 PositionBonus：默认派生，全字段比较）
    friend bool operator==(const AffixTemplate&, const AffixTemplate&) = default;
};

namespace detail {

// ============================================================
// 数值格式化辅助
// ============================================================

/// 与 Kotlin `String.format(Locale.ROOT, "%.Nf", val)` 等价的格式化。
/// 所有配置梯度值 ×100 均为干净的整数百分比（如 0.06 → "6"），
/// 用 snprintf 与 Java Formatter 的 IEEE 754 舍入规则保持一致。
inline std::string formatPercent(double fraction, int decimals) {
    const double pct = fraction * 100.0;
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.*f", decimals, pct);
    return std::string(buf);
}

/// rarity 序号(1-6) → 品级(1-3)（对应 Kotlin `talentGrade`）
inline int talentGrade(int indexRarity) {
    switch (indexRarity) {
        case 1:
        case 2:
            return 1;
        case 3:
        case 4:
            return 2;
        case 5:
        case 6:
            return 3;
        default:
            return 1;
    }
}

// ============================================================
// 配置梯度（与 Kotlin TalentDatabase 字面量逐值一致）
// ============================================================

struct CultSpeedConfig { int rarity; double value; };
struct BreakChanceConfig { int rarity; double value; };
struct LifespanConfig { int rarity; double value; };
struct BattlePctConfig { int rarity; double value; };
struct BaseFlatConfig { int rarity; int value; };
struct PositionBonusConfig { int rarity; double value; };

static constexpr CultSpeedConfig kCultSpeedConfigs[] = {
    {1, 0.06}, {2, 0.10}, {3, 0.15}, {4, 0.22}, {5, 0.25}, {6, 0.32}};
static constexpr BreakChanceConfig kBreakChanceConfigs[] = {
    {1, 0.01}, {2, 0.015}, {3, 0.03}, {4, 0.04}, {5, 0.05}, {6, 0.07}};
static constexpr LifespanConfig kLifespanConfigs[] = {
    {1, 0.10}, {2, 0.16}, {3, 0.25}, {4, 0.35}, {5, 0.45}, {6, 0.60}};

static constexpr BattlePctConfig kBatAtkDefSpeedConfigs[] = {
    {1, 0.06}, {2, 0.13}, {3, 0.22}};
static constexpr BattlePctConfig kBatHpConfigs[] = {
    {1, 0.10}, {2, 0.18}, {3, 0.30}};
static constexpr BattlePctConfig kBatMpConfigs[] = {
    {1, 0.10}, {2, 0.18}, {3, 0.30}};
static constexpr BattlePctConfig kBatCritConfigs[] = {
    {1, 0.04}, {2, 0.08}, {3, 0.14}};

static constexpr BaseFlatConfig kBaseFlatConfigs[] = {
    {1, 4}, {2, 10}, {3, 18}};

static constexpr PositionBonusConfig kPositionBonusConfigs[] = {
    {1, 0.07}, {2, 0.14}, {3, 0.22}};

// ============================================================
// 职务模板（template 名 / 显示名 / ElderSlotType 枚举名 / 加成描述 / 天赋类型名）
// ============================================================

/// 天赋职务条目（与 Kotlin addPositionTalents 的 positionTemplates 一一对应）
struct PositionTplSpec {
    const char* tmpl;
    const char* name;
    const char* slotType;
    const char* bonusDesc;
    const char* talentType;
};

static constexpr PositionTplSpec kTalentPositionSpecs[] = {
    {"vice_sect_master", "辅政之才", "VICE_SECT_MASTER", "政策效果加成", "POSITION_VICE_SECT_MASTER"},
    {"herb_garden", "灵田灵手", "HERB_GARDEN", "灵药成熟速度加成", "POSITION_HERB_GARDEN"},
    {"alchemy", "丹道宗师", "ALCHEMY", "炼丹成功率加成", "POSITION_ALCHEMY"},
    {"forge", "器道宗师", "FORGE", "炼器成功率加成", "POSITION_FORGE"},
    {"outer_elder", "外门栋梁", "OUTER_ELDER", "外门弟子突破指导加成", "POSITION_OUTER_ELDER"},
    {"preaching", "传道大师", "PREACHING", "外门弟子传道修炼速度加成", "POSITION_PREACHING"},
    {"law_enforcement", "执法金刚", "LAW_ENFORCEMENT", "叛逃/偷盗捕获率加成", "POSITION_LAW_ENFORCEMENT"},
    {"inner_elder", "内门柱石", "INNER_ELDER", "内门弟子突破指导加成", "POSITION_INNER_ELDER"},
    {"recruiting", "招贤伯乐", "RECRUITING", "招募弟子数上限加成", "POSITION_RECRUITING"},
    {"cloud_preaching", "青云传道", "CLOUD_PREACHING", "内门弟子传道修炼速度加成", "POSITION_CLOUD_PREACHING"},
};

/// 词条职务条目（与 Kotlin addPositionAffixes 的 positionTemplates 一一对应）
struct AffixPositionSpec {
    const char* tmpl;
    const char* name;
    const char* slotType;
    const char* bonusDesc;
};

static constexpr AffixPositionSpec kAffixPositionSpecs[] = {
    {"vice_sect_master", "辅政", "VICE_SECT_MASTER", "政策效果加成"},
    {"herb_garden", "灵田", "HERB_GARDEN", "灵药成熟速度加成"},
    {"alchemy", "丹道", "ALCHEMY", "炼丹成功率加成"},
    {"forge", "器道", "FORGE", "炼器成功率加成"},
    {"outer_elder", "外门", "OUTER_ELDER", "外门弟子突破指导加成"},
    {"preaching", "传道", "PREACHING", "外门弟子传道修炼速度加成"},
    {"law_enforcement", "执法", "LAW_ENFORCEMENT", "叛逃/偷盗捕获率加成"},
    {"inner_elder", "内门", "INNER_ELDER", "内门弟子突破指导加成"},
    {"recruiting", "招贤", "RECRUITING", "招募弟子数上限加成"},
    {"cloud_preaching", "青云", "CLOUD_PREACHING", "内门弟子传道修炼速度加成"},
};

// ============================================================
// 构造辅助
// ============================================================

inline void pushTalent(std::vector<TalentTemplate>& out,
                       const std::string& id, const std::string& name,
                       const std::string& description, int rarity,
                       std::map<std::string, double> effects, bool isNegative,
                       const std::string& type, const std::string& tmpl,
                       std::optional<PositionBonus> positionBonus) {
    out.push_back(TalentTemplate{id, name, description, rarity, std::move(effects),
                                 isNegative, type, tmpl, std::move(positionBonus)});
}

/// 生成配置梯度对应的 id 前缀字符串（如 "r1_bat_phy_atk"）
inline std::string prefixedId(const char* prefix, int rarity) {
    return "r" + std::to_string(rarity) + prefix;
}

// ============================================================
// 天赋表生成（与 Kotlin TalentDatabase.buildList 逐块对应）
// ============================================================

/// 旧天赋：修炼速度（6 阶，rarity 经 talentGrade 映射）
inline void buildOldCultSpeed(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kCultSpeedConfigs) {
        pushTalent(out, prefixedId("_cult_speed", cfg.rarity), "灵脉流转",
                   "修炼速度+" + formatPercent(cfg.value, 0) + "%",
                   talentGrade(cfg.rarity), {{"cultivationSpeed", cfg.value}},
                   false, "CULT_SPEED", "cult_speed", std::nullopt);
    }
}

/// 旧天赋：突破概率（6 阶，描述保留一位小数）
inline void buildOldBreakChance(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kBreakChanceConfigs) {
        pushTalent(out, prefixedId("_break_chance", cfg.rarity), "悟道通玄",
                   "突破概率+" + formatPercent(cfg.value, 1) + "%",
                   talentGrade(cfg.rarity), {{"breakthroughChance", cfg.value}},
                   false, "BREAK_CHANCE", "break_chance", std::nullopt);
    }
}

/// 旧天赋：寿命（6 阶）
inline void buildOldLifespan(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kLifespanConfigs) {
        pushTalent(out, prefixedId("_lifespan", cfg.rarity), "寿元绵长",
                   "寿命+" + formatPercent(cfg.value, 0) + "%",
                   talentGrade(cfg.rarity), {{"lifespan", cfg.value}},
                   false, "LIFESPAN", "lifespan", std::nullopt);
    }
}

/// 旧天赋：功法槽 + 战斗成长（各 1 条，固定 3 阶）
inline void buildOldSpecial(std::vector<TalentTemplate>& out) {
    pushTalent(out, "r6_manual_slot", "天衍道藏", "功法槽位+1", 3,
               {{"manualSlot", 1.0}}, false, "MANUAL_SLOT", "manual_slot", std::nullopt);
    pushTalent(out, "r6_win_growth", "百战通神",
               "每胜利一场战斗后，随机一个属性+1（无上限）", 3,
               {{"winBattleRandomAttrPlus", 1.0}}, false, "WIN_GROWTH", "win_growth", std::nullopt);
}

/// 新天赋：战斗属性百分比（物攻/法攻/物防/法防/速度 共用 batAtkDefSpeed 梯度）
inline void buildNewBattlePct(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kBatAtkDefSpeedConfigs) {
        pushTalent(out, prefixedId("_bat_phy_atk", cfg.rarity), "勇武",
                   "物攻+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"physicalAttack", cfg.value}}, false, "BAT_PHY_ATK", "bat_phy_atk", std::nullopt);
    }
    for (const auto& cfg : kBatAtkDefSpeedConfigs) {
        pushTalent(out, prefixedId("_bat_mag_atk", cfg.rarity), "神通",
                   "法攻+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"magicAttack", cfg.value}}, false, "BAT_MAG_ATK", "bat_mag_atk", std::nullopt);
    }
    for (const auto& cfg : kBatAtkDefSpeedConfigs) {
        pushTalent(out, prefixedId("_bat_phy_def", cfg.rarity), "铁骨",
                   "物防+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"physicalDefense", cfg.value}}, false, "BAT_PHY_DEF", "bat_phy_def", std::nullopt);
    }
    for (const auto& cfg : kBatAtkDefSpeedConfigs) {
        pushTalent(out, prefixedId("_bat_mag_def", cfg.rarity), "玄清",
                   "法防+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"magicDefense", cfg.value}}, false, "BAT_MAG_DEF", "bat_mag_def", std::nullopt);
    }
    for (const auto& cfg : kBatAtkDefSpeedConfigs) {
        pushTalent(out, prefixedId("_bat_speed", cfg.rarity), "疾风",
                   "速度+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"speed", cfg.value}}, false, "BAT_SPEED", "bat_speed", std::nullopt);
    }
}

/// 新天赋：气血（batHp 梯度）
inline void buildNewBattleHp(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kBatHpConfigs) {
        pushTalent(out, prefixedId("_bat_hp", cfg.rarity), "体健",
                   "气血+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"maxHp", cfg.value}}, false, "BAT_HP", "bat_hp", std::nullopt);
    }
}

/// 新天赋：法力（batMp 梯度）
inline void buildNewBattleMp(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kBatMpConfigs) {
        pushTalent(out, prefixedId("_bat_mp", cfg.rarity), "气海",
                   "法力+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"maxMp", cfg.value}}, false, "BAT_MP", "bat_mp", std::nullopt);
    }
}

/// 新天赋：暴击率（batCrit 梯度）
inline void buildNewBattleCrit(std::vector<TalentTemplate>& out) {
    for (const auto& cfg : kBatCritConfigs) {
        pushTalent(out, prefixedId("_bat_crit", cfg.rarity), "锋锐",
                   "暴击+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                   {{"critRate", cfg.value}}, false, "BAT_CRIT", "bat_crit", std::nullopt);
    }
}

/// 新天赋：基础属性扁平加成（10 类 × 3 阶）
inline void buildBaseFlat(std::vector<TalentTemplate>& out) {
    struct AttrSpec { const char* tmpl; const char* name; const char* descPrefix; const char* effectKey; const char* type; };
    static constexpr AttrSpec kAttrs[] = {
        {"base_int", "天慧", "智力", "intelligenceFlat", "BASE_INT"},
        {"base_charm", "仙姿", "魅力", "charmFlat", "BASE_CHARM"},
        {"base_loyal", "赤诚", "忠诚", "loyaltyFlat", "BASE_LOYAL"},
        {"base_comp", "顿悟", "悟性", "comprehensionFlat", "BASE_COMP"},
        {"base_arti", "天工", "炼器", "artifactRefiningFlat", "BASE_ARTI"},
        {"base_pill", "天丹", "炼丹", "pillRefiningFlat", "BASE_PILL"},
        {"base_plant", "青帝", "灵植", "spiritPlantingFlat", "BASE_PLANT"},
        {"base_teach", "夫子", "传道", "teachingFlat", "BASE_TEACH"},
        {"base_moral", "仁心", "德行", "moralityFlat", "BASE_MORAL"},
        {"base_mining", "地眼", "采矿", "miningFlat", "BASE_MINING"},
    };
    for (const auto& attr : kAttrs) {
        for (const auto& cfg : kBaseFlatConfigs) {
            pushTalent(out, prefixedId(("_" + std::string(attr.tmpl)).c_str(), cfg.rarity),
                       attr.name, std::string(attr.descPrefix) + "+" + std::to_string(cfg.value),
                       cfg.rarity, {{attr.effectKey, static_cast<double>(cfg.value)}},
                       false, attr.type, attr.tmpl, std::nullopt);
        }
    }
}

/// 职务类天赋（10 职务 × 3 阶；effects 为空，positionBonus 承载 slotType/数值）
inline void buildPositionTalents(std::vector<TalentTemplate>& out) {
    for (const auto& spec : kTalentPositionSpecs) {
        for (const auto& cfg : kPositionBonusConfigs) {
            std::string tmpl = "pos_" + std::string(spec.tmpl);
            pushTalent(out, prefixedId(("_" + tmpl).c_str(), cfg.rarity), spec.name,
                       std::string(spec.bonusDesc) + "+" + formatPercent(cfg.value, 0) + "%",
                       cfg.rarity, {}, false, spec.talentType, tmpl,
                       PositionBonus{spec.slotType, cfg.value});
        }
    }
}

/// 负面天赋（固定 5 条，rarity=0，isNegative=true）
inline void buildNegativeTalents(std::vector<TalentTemplate>& out) {
    pushTalent(out, "neg_base_comprehension", "神识迟钝", "悟性/智力/传道 -8", 0,
               {{"comprehensionFlat", -8.0}, {"intelligenceFlat", -8.0}, {"teachingFlat", -8.0}},
               true, "BASE_COMP", "neg_base_comprehension", std::nullopt);
    pushTalent(out, "neg_base_craft", "百艺生疏", "炼器/炼丹/种植 -6", 0,
               {{"artifactRefiningFlat", -6.0}, {"pillRefiningFlat", -6.0}, {"spiritPlantingFlat", -6.0}},
               true, "BASE_ARTI", "neg_base_craft", std::nullopt);
    pushTalent(out, "neg_base_social", "心性偏执", "魅力/忠诚/道德 -6", 0,
               {{"charmFlat", -6.0}, {"loyaltyFlat", -6.0}, {"moralityFlat", -6.0}},
               true, "BASE_CHARM", "neg_base_social", std::nullopt);
    pushTalent(out, "neg_battle_offense", "怯战失锋", "物攻/法攻/暴击下降", 0,
               {{"physicalAttack", -0.10}, {"magicAttack", -0.10}, {"critRate", -0.02}},
               true, "BAT_PHY_ATK", "neg_battle_offense", std::nullopt);
    pushTalent(out, "neg_battle_survival", "体魄亏空", "生存属性下降", 0,
               {{"maxHp", -0.15}, {"maxMp", -0.08}, {"physicalDefense", -0.10},
                {"magicDefense", -0.10}, {"speed", -0.06}},
               true, "BAT_HP", "neg_battle_survival", std::nullopt);
}

inline std::vector<TalentTemplate> buildTalentTemplates() {
    std::vector<TalentTemplate> out;
    // 正面（旧天赋 → 新天赋 → 职务）
    buildOldCultSpeed(out);
    buildOldBreakChance(out);
    buildOldLifespan(out);
    buildOldSpecial(out);
    buildNewBattlePct(out);
    buildNewBattleHp(out);
    buildNewBattleMp(out);
    buildNewBattleCrit(out);
    buildBaseFlat(out);
    buildPositionTalents(out);
    // 负面
    buildNegativeTalents(out);
    return out;
}

// ============================================================
// 体质表生成（与 Kotlin PhysiqueDatabase.buildList 对应）
// ============================================================

struct CultSpeedCfg { int rarity; double value; };
struct DmgAmpCfg { int rarity; double value; };
struct DmgReduceCfg { int rarity; double value; };
struct CritDmgCfg { int rarity; double value; };
struct DefCfg { int rarity; double value; };
struct HybridOffCfg { int rarity; double amp; double crit; };
struct HybridDefCfg { int rarity; double reduce; double def; };

static constexpr CultSpeedCfg kPSpeedConfigs[] = {{1, 0.08}, {2, 0.16}, {3, 0.28}};
static constexpr DmgAmpCfg kPAmpConfigs[] = {{1, 0.05}, {2, 0.11}, {3, 0.20}};
static constexpr DmgReduceCfg kPReduceConfigs[] = {{1, 0.04}, {2, 0.09}, {3, 0.16}};
static constexpr CritDmgCfg kPCritDmgConfigs[] = {{1, 0.10}, {2, 0.22}, {3, 0.38}};
static constexpr DefCfg kPDefConfigs[] = {{1, 0.06}, {2, 0.13}, {3, 0.22}};
static constexpr HybridOffCfg kPHybridOffConfigs[] = {{1, 0.03, 0.06}, {2, 0.07, 0.14}, {3, 0.12, 0.24}};
static constexpr HybridDefCfg kPHybridDefConfigs[] = {{1, 0.02, 0.04}, {2, 0.05, 0.08}, {3, 0.09, 0.14}};

inline void pushPhysique(std::vector<PhysiqueTemplate>& out,
                         const std::string& id, const std::string& name,
                         const std::string& description, int rarity,
                         double cult, double amp, double reduce, double crit, double def,
                         bool isNegative, const std::string& type, const std::string& tmpl) {
    out.push_back(PhysiqueTemplate{id, name, description, rarity, cult, amp, reduce, crit, def,
                                   isNegative, type, tmpl});
}

/// 体质：修炼速度
inline void buildPhysiqueCultSpeed(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPSpeedConfigs) {
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_cult_speed",
                     "灵脉天成", "修炼速度+" + formatPercent(cfg.value, 0) + "%",
                     cfg.rarity, cfg.value, 0.0, 0.0, 0.0, 0.0,
                     false, "CULT_SPEED", "phys_cult_speed");
    }
}

/// 体质：伤害加成
inline void buildPhysiqueDmgAmp(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPAmpConfigs) {
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_dmg_amp",
                     "九阳真身", "伤害加成+" + formatPercent(cfg.value, 0) + "%",
                     cfg.rarity, 0.0, cfg.value, 0.0, 0.0, 0.0,
                     false, "DAMAGE_AMP", "phys_dmg_amp");
    }
}

/// 体质：减伤
inline void buildPhysiqueDmgReduce(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPReduceConfigs) {
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_dmg_reduce",
                     "金身不坏", "减伤+" + formatPercent(cfg.value, 0) + "%",
                     cfg.rarity, 0.0, 0.0, cfg.value, 0.0, 0.0,
                     false, "DAMAGE_REDUCTION", "phys_dmg_reduce");
    }
}

/// 体质：暴击伤害
inline void buildPhysiqueCritDmg(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPCritDmgConfigs) {
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_crit_dmg",
                     "天眼通", "暴击伤害+" + formatPercent(cfg.value, 0) + "%",
                     cfg.rarity, 0.0, 0.0, 0.0, cfg.value, 0.0,
                     false, "CRIT_DAMAGE", "phys_crit_dmg");
    }
}

/// 体质：防御加成
inline void buildPhysiqueDefense(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPDefConfigs) {
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_defense",
                     "玄铁体质", "防御加成+" + formatPercent(cfg.value, 0) + "%",
                     cfg.rarity, 0.0, 0.0, 0.0, 0.0, cfg.value,
                     false, "DEFENSE_BONUS", "phys_defense");
    }
}

/// 体质：混合进攻（伤害加成 + 暴击伤害）
inline void buildPhysiqueHybridOff(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPHybridOffConfigs) {
        const std::string desc = "伤害加成+" + formatPercent(cfg.amp, 0) + "%，暴击伤害+" + formatPercent(cfg.crit, 0) + "%";
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_hybrid_off",
                     "战魔之体", desc,
                     cfg.rarity, 0.0, cfg.amp, 0.0, cfg.crit, 0.0,
                     false, "HYBRID_OFFENSE", "phys_hybrid_off");
    }
}

/// 体质：混合防御（减伤 + 防御加成）
inline void buildPhysiqueHybridDef(std::vector<PhysiqueTemplate>& out) {
    for (const auto& cfg : kPHybridDefConfigs) {
        const std::string desc = "减伤+" + formatPercent(cfg.reduce, 0) + "%，防御加成+" + formatPercent(cfg.def, 0) + "%";
        pushPhysique(out, "r" + std::to_string(cfg.rarity) + "_phys_hybrid_def",
                     "磐石体质", desc,
                     cfg.rarity, 0.0, 0.0, cfg.reduce, 0.0, cfg.def,
                     false, "HYBRID_DEFENSE", "phys_hybrid_def");
    }
}

/// 负面体质（固定 3 条）
inline void buildNegativePhysiques(std::vector<PhysiqueTemplate>& out) {
    pushPhysique(out, "neg_phys_cult", "经脉堵塞", "修炼速度-20%", 0,
                 -0.20, 0.0, 0.0, 0.0, 0.0, true, "CULT_SPEED", "neg_phys_cult");
    pushPhysique(out, "neg_phys_defense", "体弱多病", "减伤-10%，防御加成-15%", 0,
                 0.0, 0.0, -0.10, 0.0, -0.15, true, "HYBRID_DEFENSE", "neg_phys_defense");
    pushPhysique(out, "neg_phys_offense", "灵根残缺", "伤害加成-12%，暴击伤害-20%", 0,
                 0.0, -0.12, 0.0, -0.20, 0.0, true, "HYBRID_OFFENSE", "neg_phys_offense");
}

inline std::vector<PhysiqueTemplate> buildPhysiqueTemplates() {
    std::vector<PhysiqueTemplate> out;
    buildPhysiqueCultSpeed(out);
    buildPhysiqueDmgAmp(out);
    buildPhysiqueDmgReduce(out);
    buildPhysiqueCritDmg(out);
    buildPhysiqueDefense(out);
    buildPhysiqueHybridOff(out);
    buildPhysiqueHybridDef(out);
    buildNegativePhysiques(out);
    return out;
}

// ============================================================
// 词条表生成（与 Kotlin AffixDatabase.buildList 对应）
// ============================================================

struct BaseFlatCfg { int rarity; int value; };
struct BatPctCfg { int rarity; double value; };
struct CultSpeedCfg2 { int rarity; double value; };
struct LifespanCfg2 { int rarity; double value; };
struct DmgAmpCfg2 { int rarity; double value; };
struct DmgReduceCfg2 { int rarity; double value; };
struct CritDmgCfg2 { int rarity; double value; };
struct DefCfg2 { int rarity; double value; };
struct PositionCfg2 { int rarity; double value; };

static constexpr BaseFlatCfg kAffBaseFlatConfigs[] = {{1, 3}, {2, 7}, {3, 12}};
static constexpr BatPctCfg kAffBatPctConfigs[] = {{1, 0.04}, {2, 0.09}, {3, 0.16}};
static constexpr CultSpeedCfg2 kAffCultSpeedConfigs[] = {{1, 0.05}, {2, 0.11}, {3, 0.20}};
static constexpr LifespanCfg2 kAffLifespanConfigs[] = {{1, 0.08}, {2, 0.16}, {3, 0.28}};
static constexpr DmgAmpCfg2 kAffDmgAmpConfigs[] = {{1, 0.03}, {2, 0.07}, {3, 0.13}};
static constexpr DmgReduceCfg2 kAffDmgReduceConfigs[] = {{1, 0.03}, {2, 0.06}, {3, 0.11}};
static constexpr CritDmgCfg2 kAffCritDmgConfigs[] = {{1, 0.06}, {2, 0.14}, {3, 0.24}};
static constexpr DefCfg2 kAffDefConfigs[] = {{1, 0.04}, {2, 0.09}, {3, 0.15}};
static constexpr PositionCfg2 kAffPositionConfigs[] = {{1, 0.06}, {2, 0.12}, {3, 0.20}};

inline void pushAffix(std::vector<AffixTemplate>& out,
                      const std::string& id, const std::string& name,
                      const std::string& description, int rarity,
                      std::map<std::string, double> effects, bool isNegative,
                      const std::string& type, const std::string& tmpl,
                      std::optional<PositionBonus> positionBonus) {
    out.push_back(AffixTemplate{id, name, description, rarity, std::move(effects),
                                isNegative, type, tmpl, std::move(positionBonus)});
}

/// 词条：基础属性扁平加成（智力/悟性/魅力）
inline void buildAffixBaseFlat(std::vector<AffixTemplate>& out) {
    struct AttrSpec { const char* tmpl; const char* name; const char* descPrefix; const char* effectKey; };
    static constexpr AttrSpec kAttrs[] = {
        {"aff_base_int", "聪慧", "智力", "intelligenceFlat"},
        {"aff_base_comp", "灵慧", "悟性", "comprehensionFlat"},
        {"aff_base_charm", "风采", "魅力", "charmFlat"},
    };
    for (const auto& attr : kAttrs) {
        for (const auto& cfg : kAffBaseFlatConfigs) {
            pushAffix(out, prefixedId(("_" + std::string(attr.tmpl)).c_str(), cfg.rarity),
                      attr.name, std::string(attr.descPrefix) + "+" + std::to_string(cfg.value),
                      cfg.rarity, {{attr.effectKey, static_cast<double>(cfg.value)}},
                      false, "BASE_FLAT", attr.tmpl, std::nullopt);
        }
    }
}

/// 词条：战斗属性百分比（物攻/法攻 共用一点；气血；速度）
inline void buildAffixBatPct(std::vector<AffixTemplate>& out) {
    for (const auto& cfg : kAffBatPctConfigs) {
        pushAffix(out, prefixedId("_aff_bat_atk", cfg.rarity), "锐利",
                  "物攻+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"physicalAttack", cfg.value}, {"magicAttack", cfg.value}},
                  false, "BAT_PCT", "aff_bat_atk", std::nullopt);
    }
    for (const auto& cfg : kAffBatPctConfigs) {
        pushAffix(out, prefixedId("_aff_bat_hp", cfg.rarity), "厚甲",
                  "气血+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"maxHp", cfg.value}}, false, "BAT_PCT", "aff_bat_hp", std::nullopt);
    }
    for (const auto& cfg : kAffBatPctConfigs) {
        pushAffix(out, prefixedId("_aff_bat_speed", cfg.rarity), "轻盈",
                  "速度+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"speed", cfg.value}}, false, "BAT_PCT", "aff_bat_speed", std::nullopt);
    }
}

/// 词条：修炼速度
inline void buildAffixCultSpeed(std::vector<AffixTemplate>& out) {
    for (const auto& cfg : kAffCultSpeedConfigs) {
        pushAffix(out, prefixedId("_aff_cult_speed", cfg.rarity), "悟道",
                  "修炼速度+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"cultivationSpeed", cfg.value}}, false, "CULT_SPEED", "aff_cult_speed", std::nullopt);
    }
}

/// 词条：寿命
inline void buildAffixLifespan(std::vector<AffixTemplate>& out) {
    for (const auto& cfg : kAffLifespanConfigs) {
        pushAffix(out, prefixedId("_aff_lifespan", cfg.rarity), "延年",
                  "寿命+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"lifespan", cfg.value}}, false, "LIFESPAN", "aff_lifespan", std::nullopt);
    }
}

/// 词条：功法槽 / 战斗成长（各 1 条，固定 3 阶）
inline void buildAffixSpecial(std::vector<AffixTemplate>& out) {
    pushAffix(out, "r3_aff_manual_slot", "道藏", "功法槽位+1", 3,
              {{"manualSlot", 1.0}}, false, "MANUAL_SLOT", "aff_manual_slot", std::nullopt);
    pushAffix(out, "r3_aff_win_growth", "战悟",
              "每胜利一场战斗后，随机一个属性+1（无上限）", 3,
              {{"winBattleRandomAttrPlus", 1.0}}, false, "WIN_GROWTH", "aff_win_growth", std::nullopt);
}

/// 词条：独立乘算因子（伤害加成/减伤/暴击伤害/防御加成）
inline void buildAffixCombat(std::vector<AffixTemplate>& out) {
    for (const auto& cfg : kAffDmgAmpConfigs) {
        pushAffix(out, prefixedId("_aff_dmg_amp", cfg.rarity), "煞气",
                  "伤害加成+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"damageAmplification", cfg.value}}, false, "DAMAGE_AMP", "aff_dmg_amp", std::nullopt);
    }
    for (const auto& cfg : kAffDmgReduceConfigs) {
        pushAffix(out, prefixedId("_aff_dmg_reduce", cfg.rarity), "护体",
                  "减伤+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"damageReduction", cfg.value}}, false, "DAMAGE_REDUCTION", "aff_dmg_reduce", std::nullopt);
    }
    for (const auto& cfg : kAffCritDmgConfigs) {
        pushAffix(out, prefixedId("_aff_crit_dmg", cfg.rarity), "破军",
                  "暴击伤害+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"critDamageBonus", cfg.value}}, false, "CRIT_DAMAGE", "aff_crit_dmg", std::nullopt);
    }
    for (const auto& cfg : kAffDefConfigs) {
        pushAffix(out, prefixedId("_aff_defense", cfg.rarity), "坚壁",
                  "防御加成+" + formatPercent(cfg.value, 0) + "%", cfg.rarity,
                  {{"defenseBonus", cfg.value}}, false, "DEFENSE_BONUS", "aff_defense", std::nullopt);
    }
}

/// 词条：职务加成（10 职务 × 3 阶；name 追加 "之印"）
inline void buildAffixPosition(std::vector<AffixTemplate>& out) {
    for (const auto& spec : kAffixPositionSpecs) {
        for (const auto& cfg : kAffPositionConfigs) {
            std::string tmpl = "aff_pos_" + std::string(spec.tmpl);
            pushAffix(out, prefixedId(("_" + tmpl).c_str(), cfg.rarity),
                      std::string(spec.name) + "之印",
                      std::string(spec.bonusDesc) + "+" + formatPercent(cfg.value, 0) + "%",
                      cfg.rarity, {}, false, "POSITION", tmpl,
                      PositionBonus{spec.slotType, cfg.value});
        }
    }
}

/// 负面词条（固定 3 条）
inline void buildNegativeAffixes(std::vector<AffixTemplate>& out) {
    pushAffix(out, "neg_aff_base", "愚钝", "智力/悟性/魅力 -5", 0,
              {{"intelligenceFlat", -5.0}, {"comprehensionFlat", -5.0}, {"charmFlat", -5.0}},
              true, "BASE_FLAT", "neg_aff_base", std::nullopt);
    pushAffix(out, "neg_aff_battle", "虚弱", "物攻/法攻/气血 -8%", 0,
              {{"physicalAttack", -0.08}, {"magicAttack", -0.08}, {"maxHp", -0.08}},
              true, "BAT_PCT", "neg_aff_battle", std::nullopt);
    pushAffix(out, "neg_aff_lifespan", "夭折", "寿命-15%", 0,
              {{"lifespan", -0.15}}, true, "LIFESPAN", "neg_aff_lifespan", std::nullopt);
}

inline std::vector<AffixTemplate> buildAffixTemplates() {
    std::vector<AffixTemplate> out;
    buildAffixBaseFlat(out);
    buildAffixBatPct(out);
    buildAffixCultSpeed(out);
    buildAffixLifespan(out);
    buildAffixSpecial(out);
    buildAffixCombat(out);
    buildAffixPosition(out);
    buildNegativeAffixes(out);
    return out;
}

}  // namespace detail

// ============================================================
// 公开访问接口（静态表 + 按 id 查询）
// ============================================================

/// 全部天赋模板（正面 104 + 负面 5 = 109；生成顺序与 Kotlin buildList 一致）
///
/// B16/R6.2 数值外置：本表为**内联默认值兜底**（= detail::buildTalentTemplates()
/// 产出），与数据文件 `assets/data/game-data.json` 的 `db.talents` 段同源
/// （中性源 scripts/data/trait_db_sample.json 复刻同一 Kotlin 生成逻辑）。
/// 运行时由 `gamecore/data/data_inject.h` 初始化期一次性注入；注入前/失败时
/// 此处即为权威值（与数据文件默认值逐字段相等，data_store_test 锁定）。
inline std::vector<TalentTemplate>& talentTemplatesMutable() {
    static std::vector<TalentTemplate> kTemplates = detail::buildTalentTemplates();
    return kTemplates;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<TalentTemplate>& talentTemplates() {
    return talentTemplatesMutable();
}

/// 全部体质模板（正面 21 + 负面 3 = 24）
///
/// B16/R6.2：同 talentTemplatesMutable——兜底与 `db.physiques` 段同源。
inline std::vector<PhysiqueTemplate>& physiqueTemplatesMutable() {
    static std::vector<PhysiqueTemplate> kTemplates = detail::buildPhysiqueTemplates();
    return kTemplates;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<PhysiqueTemplate>& physiqueTemplates() {
    return physiqueTemplatesMutable();
}

/// 全部词条模板（正面 68 + 负面 3 = 71）
///
/// B16/R6.2：同 talentTemplatesMutable——兜底与 `db.affixes` 段同源。
inline std::vector<AffixTemplate>& affixTemplatesMutable() {
    static std::vector<AffixTemplate> kTemplates = detail::buildAffixTemplates();
    return kTemplates;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<AffixTemplate>& affixTemplates() {
    return affixTemplatesMutable();
}

/// 按 id 查询天赋（不存在返回空 optional）
inline std::optional<TalentTemplate> talentById(const std::string& id) {
    static const std::map<std::string, const TalentTemplate*> kIndex = [] {
        std::map<std::string, const TalentTemplate*> idx;
        for (const auto& t : talentTemplates()) idx.emplace(t.id, &t);
        return idx;
    }();
    const auto it = kIndex.find(id);
    if (it == kIndex.end()) return std::nullopt;
    return *it->second;
}

/// 按 id 查询体质（不存在返回空 optional）
inline std::optional<PhysiqueTemplate> physiqueById(const std::string& id) {
    static const std::map<std::string, const PhysiqueTemplate*> kIndex = [] {
        std::map<std::string, const PhysiqueTemplate*> idx;
        for (const auto& t : physiqueTemplates()) idx.emplace(t.id, &t);
        return idx;
    }();
    const auto it = kIndex.find(id);
    if (it == kIndex.end()) return std::nullopt;
    return *it->second;
}

/// 按 id 查询词条（不存在返回空 optional）
inline std::optional<AffixTemplate> affixById(const std::string& id) {
    static const std::map<std::string, const AffixTemplate*> kIndex = [] {
        std::map<std::string, const AffixTemplate*> idx;
        for (const auto& t : affixTemplates()) idx.emplace(t.id, &t);
        return idx;
    }();
    const auto it = kIndex.find(id);
    if (it == kIndex.end()) return std::nullopt;
    return *it->second;
}

}  // namespace gamecore::data

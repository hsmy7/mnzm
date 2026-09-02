// ============================================================
// recipe_db.h — 锻造/炼丹配方静态表（Kotlin→C++ 迁移批次 2 剩余子步）
//
// 数据来源：
//   1. 锻造配方（forgeRecipes，72 条）——**静态字面量**，逐字复刻
//      Kotlin ForgeRecipeDatabase.kt 的 tier1~tier6 六个列表
//      （ForgeRecipe("id", "name", EquipmentSlot.X, tier, rarity, "desc",
//        mapOf(...), duration, successRate)）。
//   2. 丹药配方（pillRecipes，732 条）——**程序化生成**，C++ 侧等价复刻
//      Kotlin PillRecipeDatabase.kt 的生成循环（TIER_DURATION /
//      TIER_SUCCESS_RATE / TIER_HERB_IDS / herbMat / PillGrade 循环）。
//      配方依赖 ItemDatabase.getPillById 的丹药模板（id/name/description/
//      效果字段），故本头文件同时在 detail 命名空间复刻了 ItemDatabase.kt
//      的 PillTemplate 生成逻辑（732 个模板，修炼 138 + 战斗 288 + 功能 306），
//      与 Kotlin 依赖链一致。
//
// 对拍目标（后续守卫测试逐字段比对）：
//   - C++ 表（本头文件）     ←→  JSON 快照 recipe_db_sample.json
//   - JSON 快照              ←→  Kotlin Registry 实时数据
//   （快照由 scripts/gen-recipe-db.mjs 生成，与 Kotlin 生成逻辑同源）
//
// 重新生成方法：修改 Kotlin Registry 后运行
//   node scripts/gen-recipe-db.mjs
// 重新生成 JSON 快照；C++ 表须手动同步（或由脚本后续扩展生成）。
// 禁止手改数值/字符串；禁止用 unordered_map 参与业务迭代
// （材料表用 std::map 保证有序迭代，与 JSON 排序键输出一致）。
//
// 格式陷阱（已按 Kotlin 逐字复刻，勿"修正"）：
//   - 双属性丹药描述中属性名为**英文键**（如 "增加2点physicalAttack和
//     1点physicalDefense"），单属性为中文名——Kotlin 即如此拼接。
//   - 暴击率/暴击效果百分比描述中的数值来自 (value*multiplier*100)
//     roundToInt（如 0.03*0.5*100=1.5 → "2%"），为 IEEE 754 双精度
//     运算结果，与 Kotlin 完全一致。
// ============================================================
#pragma once

#include <cstdint>
#include <cmath>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace gamecore::data {

// ============================================================
// 结构体（字段与 Kotlin data class 对应）
// ============================================================

/// 锻造配方模板（对应 Kotlin `ForgeRecipeDatabase.ForgeRecipe`）
struct ForgeRecipeTemplate {
    std::string id;
    std::string name;
    /// EquipmentSlot.name（WEAPON/ARMOR/BOOTS/ACCESSORY）
    std::string type;
    int32_t tier = 0;
    int32_t rarity = 0;
    std::string description;
    /// 材料 id → 数量（std::map 有序，禁止 unordered_map）
    std::map<std::string, int32_t> materials;
    int32_t duration = 0;
    double successRate = 0.0;
};

/// 丹药配方模板（对应 Kotlin `PillRecipeDatabase.PillRecipe`）
struct PillRecipeTemplate {
    std::string id;
    std::string name;
    int32_t tier = 0;
    int32_t rarity = 0;
    /// 模板价（批 Y-4 补全：Kotlin `ItemDatabase.PillTemplate.price`——
    /// tierPrice(rarity) × gradeMultiplier ×（双属性功能/战斗丹 1.2），
    /// 商人收购/交易池 priceMap 与 basePrice 用；非远程配置编译期常量）
    int32_t price = 0;
    /// PillCategory.name（CULTIVATION/BATTLE/FUNCTIONAL）
    std::string category;
    /// PillGrade.name.lowercase()（low/medium/high）
    std::string grade;
    std::string pillType;
    std::string description;
    /// 材料 id → 数量（std::map 有序，禁止 unordered_map）
    std::map<std::string, int32_t> materials;
    int32_t duration = 0;
    double successRate = 0.0;
    double breakthroughChance = 0.0;
    int32_t targetRealm = 0;
    double cultivationSpeedPercent = 0.0;
    double skillExpSpeedPercent = 0.0;
    double nurtureSpeedPercent = 0.0;
    int32_t cultivationAdd = 0;
    int32_t skillExpAdd = 0;
    int32_t nurtureAdd = 0;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t hpAdd = 0;
    int32_t mpAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t extendLife = 0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;
    int32_t loyaltyAdd = 0;
    int32_t comprehensionAdd = 0;
    int32_t artifactRefiningAdd = 0;
    int32_t pillRefiningAdd = 0;
    int32_t spiritPlantingAdd = 0;
    int32_t teachingAdd = 0;
    int32_t moralityAdd = 0;
    int32_t miningAdd = 0;
};

namespace detail {

// ============================================================
// 数值辅助（与 Kotlin Double.roundToInt 一致；配方内数值均为正，
// 无符号差异，std::round 与 Math.round 结果相同）
// ============================================================

inline int roundToInt(double v) {
    return static_cast<int>(std::round(v));
}

// ============================================================
// ItemDatabase.PillTemplate 等价复刻（仅配方所需字段）
// ============================================================

/// 丹药模板（对应 Kotlin `ItemDatabase.PillTemplate`，仅保留配方引用字段）
struct PillTemplateSpec {
    std::string id;
    std::string name;
    std::string description;
    double breakthroughChance = 0.0;
    int32_t targetRealm = 0;
    double cultivationSpeedPercent = 0.0;
    double skillExpSpeedPercent = 0.0;
    double nurtureSpeedPercent = 0.0;
    int32_t cultivationAdd = 0;
    int32_t skillExpAdd = 0;
    int32_t nurtureAdd = 0;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t hpAdd = 0;
    int32_t mpAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t extendLife = 0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;
    int32_t loyaltyAdd = 0;
    int32_t comprehensionAdd = 0;
    int32_t artifactRefiningAdd = 0;
    int32_t pillRefiningAdd = 0;
    int32_t spiritPlantingAdd = 0;
    int32_t teachingAdd = 0;
    int32_t moralityAdd = 0;
    int32_t miningAdd = 0;
};

// ── 常量表（与 Kotlin ItemDatabase 字面量逐值一致）────────────────

/// TIER_NAMES（1 凡品 … 6 天品）
static constexpr const char* kTierNames[7] = {"", "凡品", "灵品", "宝品", "玄品", "地品", "天品"};
/// PillGrade.displayName（LOW 下品 / MEDIUM 中品 / HIGH 上品）
static constexpr const char* kGradeDisplay[3] = {"下品", "中品", "上品"};
/// PillGrade.name.lowercase()
static constexpr const char* kGradeLower[3] = {"low", "medium", "high"};
/// PillGrade.multiplier
static constexpr double kGradeMultiplier[3] = {0.5, 1.0, 2.0};
/// PillGrade 突破成功率（LOW/MEDIUM/HIGH）
static constexpr double kBreakChanceByGrade[3] = {0.05, 0.12, 0.20};

/// GameConfig.Rarity.get(rarity).pillBasePrice（丹药模板价基准；下标 0 未用）
static constexpr int32_t kPillBasePrice[7] = {0, 4000, 16000, 80000, 480000, 3360000, 26880000};

/// REFERENCE_BASE_*（境界基准属性）
static constexpr int32_t kBaseHp[7] = {0, 120, 780, 2040, 5400, 13200, 69600};
static constexpr int32_t kBaseMp[7] = {0, 60, 390, 1020, 2700, 6600, 34800};
static constexpr int32_t kBasePa[7] = {0, 12, 78, 204, 540, 1320, 6960};
static constexpr int32_t kBaseMa[7] = {0, 12, 78, 204, 540, 1320, 6960};
static constexpr int32_t kBasePd[7] = {0, 10, 65, 170, 450, 1100, 5800};
static constexpr int32_t kBaseMd[7] = {0, 8, 52, 136, 360, 880, 4640};
static constexpr int32_t kBaseSpd[7] = {0, 15, 97, 255, 675, 1650, 8700};
static constexpr int32_t kCultBase[7] = {0, 600, 8000, 20000, 50000, 100000, 300000};

/// SPEED_PERCENT_MEDIUM / CRIT_RATE_MEDIUM / CRIT_EFFECT_MEDIUM /
/// EXTEND_LIFE_MEDIUM / BASE_ATTR_MEDIUM
static constexpr double kSpeedPct[7] = {0.0, 0.30, 0.35, 0.40, 0.50, 0.60, 0.80};
static constexpr double kCritRate[7] = {0.0, 0.03, 0.05, 0.07, 0.10, 0.13, 0.16};
static constexpr double kCritEffect[7] = {0.0, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40};
static constexpr int32_t kExtendLife[7] = {0, 5, 10, 20, 35, 50, 80};
static constexpr int32_t kBaseAttr[7] = {0, 3, 5, 8, 12, 16, 20};
/// 孕养度丹基础值（listOf(50,100,200,400,800,1600)[tier-1]）
static constexpr int32_t kNurtureBase[6] = {50, 100, 200, 400, 800, 1600};

// ── 丹药名称表（与 Kotlin 字面量逐值一致，下标 0 未用）────────────

static constexpr const char* kSpeedNames[7] = {"", "引灵丹", "聚灵丹", "凝元丹", "炼气丹", "混元丹", "仙灵丹"};
static constexpr const char* kSkillSpeedNames[7] = {"", "悟法丹", "通法丹", "玄法丹", "道法丹", "天法丹", "仙法丹"};
static constexpr const char* kNurtureSpeedNames[7] = {"", "养器丹", "灵养丹", "宝养丹", "玄养丹", "地养丹", "天养丹"};
static constexpr const char* kCultAddNames[7] = {"", "增元丹", "培元丹", "固元丹", "真元丹", "玄元丹", "仙元丹"};
static constexpr const char* kSkillAddNames[7] = {"", "悟道丹", "明心丹", "通玄丹", "慧灵丹", "道悟丹", "天机丹"};
static constexpr const char* kNurtureAddNames[7] = {"", "蕴器丹", "灵蕴丹", "宝蕴丹", "玄蕴丹", "地蕴丹", "天蕴丹"};
static constexpr const char* kExtendLifeNames[7] = {"", "延寿丹", "续命丹", "长生丹", "不老丹", "万寿丹", "永生丹"};

/// 单属性战斗丹配置（pillType / attrName / 各 tier 名称）
struct SingleAttrSpec {
    const char* pillType;
    const char* attrName;
    const char* names[7];
};

static constexpr SingleAttrSpec kSingleAttrSpecs[] = {
    {"physicalAttack", "物攻", {"", "虎力丹", "熊力丹", "龙力丹", "神力丹", "霸力丹", "天力丹"}},
    {"magicAttack", "法攻", {"", "灵火丹", "真火丹", "三昧丹", "玄火丹", "地火丹", "天火丹"}},
    {"physicalDefense", "物防", {"", "铁甲丹", "铜墙丹", "金刚丹", "玄盾丹", "地罡丹", "天罡丹"}},
    {"magicDefense", "法防", {"", "灵盾丹", "法盾丹", "神盾丹", "玄罡丹", "地护丹", "天护丹"}},
    {"hp", "生命", {"", "气血丹", "血精丹", "血魂丹", "玄血丹", "地血丹", "天血丹"}},
    {"mp", "灵力", {"", "回灵丹", "汇灵丹", "凝灵丹", "玄灵丹", "地灵丹", "天灵丹"}},
    {"speed", "速度", {"", "疾风丹", "迅风丹", "神风丹", "玄风丹", "地风丹", "天风丹"}},
};

/// 双属性战斗丹配置（pillType / attr1 / attr2 / descName / 各 tier 名称）
struct DualAttrSpec {
    const char* pillType;
    const char* attr1;
    const char* attr2;
    const char* descName;
    const char* names[7];
};

static constexpr DualAttrSpec kDualAttrSpecs[] = {
    {"physicalAttackDefense", "physicalAttack", "physicalDefense", "物攻物防",
     {"", "战体丹", "战魂丹", "战意丹", "战心丹", "战圣丹", "战神丹"}},
    {"magicAttackDefense", "magicAttack", "magicDefense", "法攻法防",
     {"", "法体丹", "法魂丹", "法意丹", "法心丹", "法圣丹", "法神丹"}},
    {"attackMixed", "physicalAttack", "magicAttack", "物法双攻",
     {"", "双攻丹", "灵攻丹", "龙攻丹", "玄攻丹", "地攻丹", "天攻丹"}},
    {"defenseMixed", "physicalDefense", "magicDefense", "物法双防",
     {"", "双御丹", "灵御丹", "龙御丹", "玄御丹", "地御丹", "天御丹"}},
    {"hpMp", "hp", "mp", "生命灵力",
     {"", "生灵丹", "命灵丹", "元灵丹", "真灵丹", "混灵丹", "圣灵丹"}},
    {"attackSpeed", "physicalAttack", "speed", "攻速",
     {"", "疾攻丹", "迅攻丹", "神攻丹", "玄攻丹", "地攻丹", "天攻丹"}},
    {"magicSpeed", "magicAttack", "speed", "法速",
     {"", "疾法丹", "迅法丹", "神法丹", "玄法丹", "地法丹", "天法丹"}},
};

/// 暴击类名称（critRate / critEffect）
static constexpr const char* kCritRateNames[7] = {"", "破击丹", "锐击丹", "必杀丹", "玄击丹", "绝杀丹", "天击丹"};
static constexpr const char* kCritEffectNames[7] = {"", "烈击丹", "猛击丹", "暴烈丹", "玄烈丹", "毁灭丹", "天裂丹"};

/// 单基础属性功能丹配置（pillType / attrName / 各 tier 名称）
static constexpr SingleAttrSpec kSingleBaseAttrSpecs[] = {
    {"intelligence", "智力", {"", "慧根丹", "灵慧丹", "明慧丹", "玄慧丹", "地慧丹", "天慧丹"}},
    {"charm", "魅力", {"", "仙姿丹", "灵姿丹", "玉姿丹", "玄姿丹", "地姿丹", "天姿丹"}},
    {"loyalty", "忠诚", {"", "忠心丹", "赤诚丹", "铁心丹", "玄心丹", "地心丹", "天心丹"}},
    {"comprehension", "悟性", {"", "悟道丹", "明悟丹", "通悟丹", "玄悟丹", "地悟丹", "天悟丹"}},
    {"artifactRefining", "炼器", {"", "铸魂丹", "灵铸丹", "宝铸丹", "玄铸丹", "地铸丹", "天铸丹"}},
    {"pillRefining", "炼丹", {"", "丹心丹", "灵丹丹", "宝丹丹", "玄丹丹", "地丹丹", "天丹丹"}},
    {"spiritPlanting", "种植", {"", "灵植丹", "灵耘丹", "宝耘丹", "玄耘丹", "地耘丹", "天耘丹"}},
    {"teaching", "教学", {"", "传道丹", "灵传丹", "宝传丹", "玄传丹", "地传丹", "天传丹"}},
    {"morality", "道德", {"", "善行丹", "灵善丹", "宝善丹", "玄善丹", "地善丹", "天善丹"}},
    {"mining", "采矿", {"", "探矿丹", "灵石丹", "宝矿丹", "玄矿丹", "地矿丹", "天矿丹"}},
};

/// 双基础属性功能丹配置（pillType / attr1 / attr2 / descName / 各 tier 名称）
static constexpr DualAttrSpec kDualBaseAttrSpecs[] = {
    {"intelligenceComprehension", "intelligence", "comprehension", "智悟",
     {"", "智悟丹", "灵悟丹", "明悟丹", "玄悟丹", "地悟丹", "天悟丹"}},
    {"charmLoyalty", "charm", "loyalty", "魅忠",
     {"", "忠媚丹", "灵忠丹", "宝忠丹", "玄忠丹", "地忠丹", "天忠丹"}},
    {"pillRefiningArtifactRefining", "pillRefining", "artifactRefining", "炼丹炼器",
     {"", "双炼丹", "灵炼丹", "宝炼丹", "玄炼丹", "地炼丹", "天炼丹"}},
    {"spiritPlantingTeaching", "spiritPlanting", "teaching", "种植教学",
     {"", "师农丹", "灵师丹", "宝师丹", "玄师丹", "地师丹", "天师丹"}},
    {"intelligenceCharm", "intelligence", "charm", "智魅",
     {"", "智魅丹", "灵魅丹", "明魅丹", "玄魅丹", "地魅丹", "天魅丹"}},
    {"comprehensionMorality", "comprehension", "morality", "悟德",
     {"", "悟德丹", "灵德丹", "宝德丹", "玄德丹", "地德丹", "天德丹"}},
};

// ── 突破丹数据（与 Kotlin breakthroughData 一致）──────────────────

struct BreakthroughTierData {
    int32_t tier;
    /// 目标境界（targetRealm），下标对应 targets 顺序
    int32_t targets[3];
    int32_t targetCount;
};

static constexpr BreakthroughTierData kBreakthroughTiers[] = {
    {1, {9}, 1},
    {2, {8, 7, 6}, 3},
    {3, {5, 4}, 2},
    {5, {3}, 1},
    {6, {2, 1, 0}, 3},
};

/// 突破丹名称（与 ItemDatabase.addCultivationBreakthroughPills 的 Triple 一致）
inline const char* breakthroughName(int realm) {
    switch (realm) {
        case 9: return "聚气丹";
        case 8: return "筑基丹";
        case 7: return "凝金丹";
        case 6: return "结婴丹";
        case 5: return "化神丹";
        case 4: return "破虚丹";
        case 3: return "合道丹";
        case 2: return "大乘丹";
        case 1: return "渡劫丹";
        case 0: return "登仙丹";
        default: return "";
    }
}

/// 境界名称（与 GameConfig.Realm.getName 一致，仅配方描述所需）
inline const char* realmName(int realm) {
    switch (realm) {
        case 9: return "炼气";
        case 8: return "筑基";
        case 7: return "金丹";
        case 6: return "元婴";
        case 5: return "化神";
        case 4: return "炼虚";
        case 3: return "合体";
        case 2: return "大乘";
        case 1: return "渡劫";
        case 0: return "仙人";
        default: return "";
    }
}

// ============================================================
// PillTemplate 生成（与 Kotlin ItemDatabase 各生成函数对应）
// ============================================================

/// 修炼速度丹（cultivationSpeed / skillExpSpeed / nurtureSpeed）
inline void buildCultivationSpeedPills(std::vector<PillTemplateSpec>& out) {
    for (int tier = 1; tier <= 6; ++tier) {
        const double speedPct = kSpeedPct[tier];
        const std::string tierName = kTierNames[tier];
        for (int g = 0; g < 3; ++g) {
            const double mult = kGradeMultiplier[g];
            const std::string gradeLower = kGradeLower[g];
            const std::string gradeName = kGradeDisplay[g];
            const int pct = roundToInt((speedPct * mult) * 100.0);
            const std::string pctStr = std::to_string(pct);

            out.push_back(PillTemplateSpec{
                "cultivationSpeed_" + std::to_string(tier) + "_" + gradeLower,
                kSpeedNames[tier],
                tierName + gradeName + "修炼速度丹，提升境界修炼速度" + pctStr + "%，持续9旬",
                0.0, 0, speedPct * mult, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            out.push_back(PillTemplateSpec{
                "skillExpSpeed_" + std::to_string(tier) + "_" + gradeLower,
                kSkillSpeedNames[tier],
                tierName + gradeName + "功法速度丹，提升功法熟练度修炼速度" + pctStr + "%，持续9旬",
                0.0, 0, 0.0, speedPct * mult, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            out.push_back(PillTemplateSpec{
                "nurtureSpeed_" + std::to_string(tier) + "_" + gradeLower,
                kNurtureSpeedNames[tier],
                tierName + gradeName + "孕养速度丹，提升装备孕养等级修炼速度" + pctStr + "%，持续9旬",
                0.0, 0, 0.0, 0.0, speedPct * mult, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        }
    }
}

/// 修为/熟练度/孕养度加值丹（cultivationAdd / skillExpAdd / nurtureAdd）
inline void buildCultivationValuePills(std::vector<PillTemplateSpec>& out) {
    for (int tier = 1; tier <= 6; ++tier) {
        const int baseCult = kCultBase[tier];
        const int cultBase = roundToInt(baseCult * 0.375);
        const int skillBase = roundToInt(baseCult * 0.1);
        const int nurtureBase = kNurtureBase[tier - 1];
        const std::string tierName = kTierNames[tier];
        for (int g = 0; g < 3; ++g) {
            const double mult = kGradeMultiplier[g];
            const std::string gradeLower = kGradeLower[g];
            const std::string gradeName = kGradeDisplay[g];

            const int cultAddVal = roundToInt(cultBase * mult);
            const int skillAddVal = roundToInt(skillBase * mult);
            const int nurtureAddVal = roundToInt(nurtureBase * mult);

            out.push_back(PillTemplateSpec{
                "cultivationAdd_" + std::to_string(tier) + "_" + gradeLower,
                kCultAddNames[tier],
                tierName + gradeName + "境界修为丹，立即增加" + std::to_string(cultAddVal) + "点境界修为",
                0.0, 0, 0.0, 0.0, 0.0, cultAddVal, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            out.push_back(PillTemplateSpec{
                "skillExpAdd_" + std::to_string(tier) + "_" + gradeLower,
                kSkillAddNames[tier],
                tierName + gradeName + "功法熟练丹，立即增加" + std::to_string(skillAddVal) + "点功法熟练度",
                0.0, 0, 0.0, 0.0, 0.0, 0, skillAddVal, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            out.push_back(PillTemplateSpec{
                "nurtureAdd_" + std::to_string(tier) + "_" + gradeLower,
                kNurtureAddNames[tier],
                tierName + gradeName + "孕养度丹，立即增加" + std::to_string(nurtureAddVal) + "点装备孕养度",
                0.0, 0, 0.0, 0.0, 0.0, 0, 0, nurtureAddVal, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        }
    }
}

/// 突破丹（聚气/筑基/凝金等，breakthrough_{realm}_{grade}）
inline void buildBreakthroughPills(std::vector<PillTemplateSpec>& out) {
    for (const auto& bt : kBreakthroughTiers) {
        for (int i = 0; i < bt.targetCount; ++i) {
            const int targetRealm = bt.targets[i];
            const char* name = breakthroughName(targetRealm);
            for (int g = 0; g < 3; ++g) {
                const double chance = kBreakChanceByGrade[g];
                const int pct = roundToInt(chance * 100.0);
                out.push_back(PillTemplateSpec{
                    "breakthrough_" + std::to_string(targetRealm) + "_" + kGradeLower[g],
                    name,
                    std::string("增加") + realmName(targetRealm) + "期突破成功率" +
                        std::to_string(pct) + "%",
                    chance, targetRealm, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            }
        }
    }
}

/// 单属性战斗丹（physicalAttack/magicAttack/.../speed）
inline void buildSingleAttrBattlePills(std::vector<PillTemplateSpec>& out) {
    for (const auto& cfg : kSingleAttrSpecs) {
        for (int tier = 1; tier <= 6; ++tier) {
            const int ref = [&]() -> int {
                const std::string& t = cfg.pillType;
                if (t == "physicalAttack") return kBasePa[tier];
                if (t == "magicAttack") return kBaseMa[tier];
                if (t == "physicalDefense") return kBasePd[tier];
                if (t == "magicDefense") return kBaseMd[tier];
                if (t == "hp") return kBaseHp[tier];
                if (t == "mp") return kBaseMp[tier];
                return kBaseSpd[tier];  // speed
            }();
            const int mediumVal = roundToInt(ref * 0.375);
            const std::string tierName = kTierNames[tier];
            for (int g = 0; g < 3; ++g) {
                const double mult = kGradeMultiplier[g];
                const int val = roundToInt(mediumVal * mult);
                const std::string gradeLower = kGradeLower[g];
                const std::string gradeName = kGradeDisplay[g];
                const std::string desc = tierName + gradeName + cfg.attrName + "丹，增加" +
                    std::to_string(val) + "点" + cfg.attrName + "，持续9旬";
                out.push_back(PillTemplateSpec{
                    std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + gradeLower,
                    cfg.names[tier], desc,
                    0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0,
                    std::string(cfg.pillType) == "physicalAttack" ? val : 0,
                    std::string(cfg.pillType) == "magicAttack" ? val : 0,
                    std::string(cfg.pillType) == "physicalDefense" ? val : 0,
                    std::string(cfg.pillType) == "magicDefense" ? val : 0,
                    std::string(cfg.pillType) == "hp" ? val : 0,
                    std::string(cfg.pillType) == "mp" ? val : 0,
                    std::string(cfg.pillType) == "speed" ? val : 0,
                    0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            }
        }
    }
}

/// 双属性战斗丹（physicalAttackDefense/.../magicSpeed）
inline void buildDualAttrBattlePills(std::vector<PillTemplateSpec>& out) {
    for (const auto& cfg : kDualAttrSpecs) {
        for (int tier = 1; tier <= 6; ++tier) {
            const int ref1 = [&]() -> int {
                const std::string& a = cfg.attr1;
                if (a == "physicalAttack") return kBasePa[tier];
                if (a == "magicAttack") return kBaseMa[tier];
                if (a == "physicalDefense") return kBasePd[tier];
                if (a == "magicDefense") return kBaseMd[tier];
                if (a == "hp") return kBaseHp[tier];
                if (a == "mp") return kBaseMp[tier];
                return kBaseSpd[tier];  // speed
            }();
            const int ref2 = [&]() -> int {
                const std::string& a = cfg.attr2;
                if (a == "physicalAttack") return kBasePa[tier];
                if (a == "magicAttack") return kBaseMa[tier];
                if (a == "physicalDefense") return kBasePd[tier];
                if (a == "magicDefense") return kBaseMd[tier];
                if (a == "hp") return kBaseHp[tier];
                if (a == "mp") return kBaseMp[tier];
                return kBaseSpd[tier];  // speed
            }();
            const int med1 = roundToInt(ref1 * 0.375 * 0.6);
            const int med2 = roundToInt(ref2 * 0.375 * 0.6);
            const std::string tierName = kTierNames[tier];
            for (int g = 0; g < 3; ++g) {
                const double mult = kGradeMultiplier[g];
                const int v1 = roundToInt(med1 * mult);
                const int v2 = roundToInt(med2 * mult);
                const std::string gradeLower = kGradeLower[g];
                const std::string gradeName = kGradeDisplay[g];
                // 格式陷阱：双属性描述用英文属性键（Kotlin 即如此）
                const std::string desc = tierName + gradeName + cfg.descName + "丹，增加" +
                    std::to_string(v1) + "点" + cfg.attr1 + "和" + std::to_string(v2) + "点" +
                    cfg.attr2 + "，持续9旬";
                const auto attrVal = [&](const std::string& attr) -> int {
                    if (attr == cfg.attr1) return v1;
                    if (attr == cfg.attr2) return v2;
                    return 0;
                };
                out.push_back(PillTemplateSpec{
                    std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + gradeLower,
                    cfg.names[tier], desc,
                    0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0,
                    attrVal("physicalAttack"), attrVal("magicAttack"),
                    attrVal("physicalDefense"), attrVal("magicDefense"),
                    attrVal("hp"), attrVal("mp"), attrVal("speed"),
                    0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            }
        }
    }
}

/// 暴击率/暴击效果战斗丹
inline void buildCritBattlePills(std::vector<PillTemplateSpec>& out) {
    for (int tier = 1; tier <= 6; ++tier) {
        const std::string tierName = kTierNames[tier];
        for (int g = 0; g < 3; ++g) {
            const double mult = kGradeMultiplier[g];
            const std::string gradeLower = kGradeLower[g];
            const std::string gradeName = kGradeDisplay[g];

            const double cr = kCritRate[tier] * mult;
            out.push_back(PillTemplateSpec{
                "critRate_" + std::to_string(tier) + "_" + gradeLower,
                kCritRateNames[tier],
                tierName + gradeName + "暴击率丹，增加" + std::to_string(roundToInt(cr * 100.0)) +
                    "%暴击率，持续9旬",
                0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, cr, 0.0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

            const double ce = kCritEffect[tier] * mult;
            out.push_back(PillTemplateSpec{
                "critEffect_" + std::to_string(tier) + "_" + gradeLower,
                kCritEffectNames[tier],
                tierName + gradeName + "暴击效果丹，增加" + std::to_string(roundToInt(ce * 100.0)) +
                    "%暴击效果，持续9旬",
                0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, ce,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        }
    }
}

/// 延寿丹
inline void buildExtendLifePills(std::vector<PillTemplateSpec>& out) {
    for (int tier = 1; tier <= 6; ++tier) {
        const std::string tierName = kTierNames[tier];
        for (int g = 0; g < 3; ++g) {
            const double mult = kGradeMultiplier[g];
            const int lifeVal = roundToInt(kExtendLife[tier] * mult);
            out.push_back(PillTemplateSpec{
                "extendLife_" + std::to_string(tier) + "_" + kGradeLower[g],
                kExtendLifeNames[tier],
                tierName + kGradeDisplay[g] + "延寿丹，增加" + std::to_string(lifeVal) + "年寿元",
                0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                lifeVal, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        }
    }
}

/// 单基础属性功能丹（intelligence/.../mining）
inline void buildSingleBaseAttrPills(std::vector<PillTemplateSpec>& out) {
    for (const auto& cfg : kSingleBaseAttrSpecs) {
        for (int tier = 1; tier <= 6; ++tier) {
            const std::string tierName = kTierNames[tier];
            for (int g = 0; g < 3; ++g) {
                const double mult = kGradeMultiplier[g];
                const int val = roundToInt(kBaseAttr[tier] * mult);
                const std::string desc = tierName + kGradeDisplay[g] + cfg.attrName + "丹，永久增加" +
                    std::to_string(val) + "点" + cfg.attrName;
                out.push_back(PillTemplateSpec{
                    std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + kGradeLower[g],
                    cfg.names[tier], desc,
                    0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                    0,
                    std::string(cfg.pillType) == "intelligence" ? val : 0,
                    std::string(cfg.pillType) == "charm" ? val : 0,
                    std::string(cfg.pillType) == "loyalty" ? val : 0,
                    std::string(cfg.pillType) == "comprehension" ? val : 0,
                    std::string(cfg.pillType) == "artifactRefining" ? val : 0,
                    std::string(cfg.pillType) == "pillRefining" ? val : 0,
                    std::string(cfg.pillType) == "spiritPlanting" ? val : 0,
                    std::string(cfg.pillType) == "teaching" ? val : 0,
                    std::string(cfg.pillType) == "morality" ? val : 0,
                    std::string(cfg.pillType) == "mining" ? val : 0});
            }
        }
    }
}

/// 双基础属性功能丹（intelligenceComprehension/.../comprehensionMorality）
inline void buildDualBaseAttrPills(std::vector<PillTemplateSpec>& out) {
    for (const auto& cfg : kDualBaseAttrSpecs) {
        for (int tier = 1; tier <= 6; ++tier) {
            const int med = kBaseAttr[tier];
            const std::string tierName = kTierNames[tier];
            for (int g = 0; g < 3; ++g) {
                const double mult = kGradeMultiplier[g];
                const int v1 = roundToInt(roundToInt(med * 0.6) * mult);
                const int v2 = v1;
                // 格式陷阱：双属性描述用英文属性键（Kotlin 即如此）
                const std::string desc = tierName + kGradeDisplay[g] + cfg.descName + "丹，永久增加" +
                    std::to_string(v1) + "点" + cfg.attr1 + "和" + std::to_string(v2) + "点" +
                    cfg.attr2;
                const auto attrVal = [&](const std::string& attr) -> int {
                    if (attr == cfg.attr1) return v1;
                    if (attr == cfg.attr2) return v2;
                    return 0;
                };
                out.push_back(PillTemplateSpec{
                    std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + kGradeLower[g],
                    cfg.names[tier], desc,
                    0.0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                    0,
                    attrVal("intelligence"), attrVal("charm"), attrVal("loyalty"),
                    attrVal("comprehension"), attrVal("artifactRefining"),
                    attrVal("pillRefining"), attrVal("spiritPlanting"),
                    attrVal("teaching"), attrVal("morality"), 0});
            }
        }
    }
}

/// 全部丹药模板（732 = 修炼 138 + 战斗 288 + 功能 306，与 Kotlin allPills 同构）
inline std::vector<PillTemplateSpec> buildPillTemplates() {
    std::vector<PillTemplateSpec> out;
    buildCultivationSpeedPills(out);
    buildCultivationValuePills(out);
    buildBreakthroughPills(out);
    buildSingleAttrBattlePills(out);
    buildDualAttrBattlePills(out);
    buildCritBattlePills(out);
    buildExtendLifePills(out);
    buildSingleBaseAttrPills(out);
    buildDualBaseAttrPills(out);
    return out;
}

inline const std::vector<PillTemplateSpec>& pillTemplates() {
    static const std::vector<PillTemplateSpec> kTemplates = buildPillTemplates();
    return kTemplates;
}

/// 按 id 查询丹药模板（对应 Kotlin `ItemDatabase.getPillById`）
inline std::optional<PillTemplateSpec> pillTemplateById(const std::string& id) {
    static const std::map<std::string, const PillTemplateSpec*> kIndex = [] {
        std::map<std::string, const PillTemplateSpec*> idx;
        for (const auto& t : pillTemplates()) idx.emplace(t.id, &t);
        return idx;
    }();
    const auto it = kIndex.find(id);
    if (it == kIndex.end()) return std::nullopt;
    return *it->second;
}

// ============================================================
// ForgeRecipe 静态表（逐字复刻 Kotlin ForgeRecipeDatabase 字面量）
// ============================================================

inline std::vector<ForgeRecipeTemplate> buildForgeRecipes() {
    // 字段顺序与 ForgeRecipe(id, name, type, tier, rarity, description,
    // materials, duration, successRate) 一致
    return {
        // ── tier 1 ──
        {"ironSword", "精铁剑", "WEAPON", 1, 1, "普通铁匠打造的精铁剑",
         {{"tigerBlood0", 3}, {"tigerTooth0", 2}}, 3, 0.70},
        {"bronzeDagger", "精铁刀", "WEAPON", 1, 1, "精铁锻造的宝刀",
         {{"tigerTooth0", 4}, {"eagleClaw0", 2}}, 3, 0.70},
        {"woodenStaff", "桃木杖", "WEAPON", 1, 1, "百年桃木制成的法杖",
         {{"snakeBlood0", 3}, {"snakeCore0", 2}}, 3, 0.70},
        {"crystalOrb", "碧木扇", "WEAPON", 1, 1, "蕴含微量灵气的碧木扇",
         {{"snakeCore0", 3}, {"foxCore0", 2}}, 3, 0.70},
        {"leatherArmor", "皮甲", "ARMOR", 1, 1, "野兽皮革制成的护甲",
         {{"bearHide0", 4}, {"bearBone0", 2}}, 3, 0.70},
        {"chainMail", "锁子甲", "ARMOR", 1, 1, "铁环相扣的护甲",
         {{"bearBone0", 5}, {"bearHide0", 2}}, 3, 0.70},
        {"bronzePlate", "精铁甲", "ARMOR", 1, 1, "精铁铸造的铠甲",
         {{"turtleShell0", 4}, {"turtleBone0", 2}}, 3, 0.70},
        {"clothRobe", "灵竹衣", "ARMOR", 1, 1, "灵竹纤维制成的衣物",
         {{"turtleShell0", 3}, {"snakeScale0", 2}}, 3, 0.70},
        {"clothBoots", "青澜靴", "BOOTS", 1, 1, "青澜丝线织就的轻靴",
         {{"wolfHide0", 3}, {"wolfBone0", 2}}, 3, 0.70},
        {"leatherBoots", "兽皮靴", "BOOTS", 1, 1, "兽皮鞣制的厚靴",
         {{"wolfHide0", 4}, {"wolfTooth0", 1}}, 3, 0.70},
        {"jadeRing", "玉戒指", "ACCESSORY", 1, 1, "蕴含微量灵气的玉戒指",
         {{"foxCore0", 2}, {"foxBone0", 3}}, 3, 0.70},
        {"copperNecklace", "铜项链", "ACCESSORY", 1, 1, "铜制项链",
         {{"foxBone0", 3}, {"foxTail0", 2}}, 3, 0.70},
        // ── tier 2 ──
        {"spiritSword", "灵锋剑", "WEAPON", 2, 2, "注入灵气的锋利长剑",
         {{"tigerBlood1", 4}, {"tigerTooth1", 3}}, 6, 0.65},
        {"battleAxe", "凌华刀", "WEAPON", 2, 2, "刀光凌厉如华",
         {{"tigerBlood1", 5}, {"eagleClaw1", 2}}, 6, 0.65},
        {"jadeStaff", "碧玉杖", "WEAPON", 2, 2, "碧玉雕刻的法杖",
         {{"snakeBlood1", 4}, {"snakeCore1", 2}}, 6, 0.65},
        {"spiritFan", "灵风扇", "WEAPON", 2, 2, "可扇出灵风的法器",
         {{"eagleFeather1", 4}, {"snakeCore1", 2}}, 6, 0.65},
        {"ironPlate", "碧叶甲", "ARMOR", 2, 2, "碧玉叶片打造的护甲",
         {{"bearHide1", 5}, {"bearBone1", 2}}, 6, 0.65},
        {"steelArmor", "丹羽衣", "ARMOR", 2, 2, "丹砂羽线织成的法衣",
         {{"bearBone1", 4}, {"bearCore1", 2}}, 6, 0.65},
        {"spiritRobe", "灵丝袍", "ARMOR", 2, 2, "灵蚕丝织成的法袍",
         {{"turtleShell1", 4}, {"snakeScale1", 2}}, 6, 0.65},
        {"cloudRobe", "云纹袍", "ARMOR", 2, 2, "绣有云纹的法袍",
         {{"turtleShell1", 3}, {"turtleBone1", 3}}, 6, 0.65},
        {"swiftBoots", "疾风靴", "BOOTS", 2, 2, "穿上可大幅提升移动速度",
         {{"wolfHide1", 4}, {"wolfBone1", 2}}, 6, 0.65},
        {"lightBoots", "轻羽靴", "BOOTS", 2, 2, "如羽毛般轻盈",
         {{"wolfHide1", 3}, {"eagleFeather1", 3}}, 6, 0.65},
        {"spiritPendant", "灵玉佩", "ACCESSORY", 2, 2, "蕴含灵气的玉佩",
         {{"foxCore1", 3}, {"foxBone1", 3}}, 6, 0.65},
        {"healthRing", "蕴灵戒", "ACCESSORY", 2, 2, "可蕴养灵力的戒指",
         {{"wolfTooth1", 3}, {"foxTail1", 2}}, 6, 0.65},
        // ── tier 3 ──
        {"frostBlade", "青碧刃", "WEAPON", 3, 3, "蕴含青碧灵力的宝刀",
         {{"tigerBlood2", 5}, {"snakeScale2", 3}, {"tigerTooth2", 2}}, 12, 0.60},
        {"flameSword", "烈焰剑", "WEAPON", 3, 3, "燃烧着火焰的灵剑",
         {{"tigerBlood2", 4}, {"tigerCore2", 2}, {"tigerTooth2", 3}}, 12, 0.60},
        {"thunderStaff", "玄雷杖", "WEAPON", 3, 3, "可召唤雷电的法杖",
         {{"snakeBlood2", 4}, {"snakeCore2", 3}, {"eagleFeather2", 2}}, 12, 0.60},
        {"frostOrb", "玄冰扇", "WEAPON", 3, 3, "蕴含玄冰之力的宝扇",
         {{"snakeCore2", 4}, {"foxCore2", 2}, {"snakeBlood2", 2}}, 12, 0.60},
        {"scaleArmor", "青鳞铠", "ARMOR", 3, 3, "妖兽青鳞打造的铠甲",
         {{"snakeScale2", 5}, {"snakeBlood2", 3}, {"snakeCore2", 2}}, 12, 0.60},
        {"plateArmor", "银板铠", "ARMOR", 3, 3, "厚重的银板护甲",
         {{"bearHide2", 4}, {"bearBone2", 3}, {"bearCore2", 2}}, 12, 0.60},
        {"mysticRobe", "汐流衣", "ARMOR", 3, 3, "蕴含汐流之力的法衣",
         {{"turtleShell2", 5}, {"turtleBone2", 3}, {"turtleCore2", 2}}, 12, 0.60},
        {"starRobe", "星辰袍", "ARMOR", 3, 3, "绣有星辰图案的法袍",
         {{"bearHide2", 3}, {"snakeScale2", 3}, {"bearCore2", 2}}, 12, 0.60},
        {"windBoots", "追风靴", "BOOTS", 3, 3, "追逐风的速度",
         {{"wolfHide2", 4}, {"wolfBone2", 3}, {"wolfCore2", 2}}, 12, 0.60},
        {"mistBoots", "云栖靴", "BOOTS", 3, 3, "云栖之处步履轻盈",
         {{"wolfHide2", 4}, {"eagleFeather2", 2}, {"wolfTooth2", 2}}, 12, 0.60},
        {"storageRing", "灵泉戒", "ACCESSORY", 3, 3, "蕴含灵泉之力的戒指",
         {{"foxCore2", 4}, {"foxBone2", 3}, {"foxTail2", 2}}, 12, 0.60},
        {"wisdomOrb", "迅捷珠", "ACCESSORY", 3, 3, "可提升身法速度的宝珠",
         {{"wolfTooth2", 4}, {"eagleClaw2", 2}, {"foxCore2", 2}}, 12, 0.60},
        // ── tier 4 ──
        {"thunderSword", "雷霆剑", "WEAPON", 4, 4, "引动天雷的玄妙飞剑",
         {{"tigerBlood3", 6}, {"tigerHide3", 4}, {"tigerCore3", 2}}, 36, 0.35},
        {"shadowBlade", "暗影刃", "WEAPON", 4, 4, "融入暗影的短刃",
         {{"tigerBlood3", 5}, {"eagleClaw3", 4}, {"foxCore3", 2}}, 36, 0.35},
        {"voidStaff", "虚华杖", "WEAPON", 4, 4, "虚华流转的玄妙法杖",
         {{"snakeBlood3", 5}, {"snakeCore3", 4}, {"foxCore3", 2}}, 36, 0.35},
        {"phoenixFan", "凰焰扇", "WEAPON", 4, 4, "凰焰淬炼的神扇",
         {{"eagleFeather3", 6}, {"eagleClaw3", 3}, {"eagleCore3", 2}}, 36, 0.35},
        {"dragonScale", "龙鳞铠", "ARMOR", 4, 4, "真龙鳞片锻造的铠甲",
         {{"snakeScale3", 6}, {"snakeBlood3", 4}, {"snakeCore3", 2}}, 36, 0.35},
        {"titanArmor", "渊岩铠", "ARMOR", 4, 4, "深渊岩铁铸造的铠甲",
         {{"bearHide3", 5}, {"bearBone3", 4}, {"bearCore3", 3}}, 36, 0.35},
        {"voidRobe", "瑶光袍", "ARMOR", 4, 4, "蕴含瑶光之力的法袍",
         {{"turtleShell3", 6}, {"turtleBone3", 4}, {"turtleCore3", 2}}, 36, 0.35},
        {"moonRobe", "月华袍", "ARMOR", 4, 4, "吸收月华之力织成的法袍",
         {{"bearHide3", 5}, {"snakeScale3", 3}, {"bearCore3", 3}}, 36, 0.35},
        {"cloudBoots", "踏云履", "BOOTS", 4, 4, "踏云而行的仙家法宝",
         {{"wolfHide3", 5}, {"eagleFeather3", 4}, {"wolfCore3", 2}}, 36, 0.35},
        {"thunderBoots", "奔雷靴", "BOOTS", 4, 4, "如雷电般迅捷的靴子",
         {{"wolfHide3", 5}, {"wolfBone3", 3}, {"wolfCore3", 3}}, 36, 0.35},
        {"dragonEye", "龙灵珠", "ACCESSORY", 4, 4, "真龙之灵凝聚的宝珠",
         {{"foxCore3", 5}, {"foxBone3", 4}, {"foxHide3", 2}}, 36, 0.35},
        {"phoenixHeart", "凤羽坠", "ACCESSORY", 4, 4, "凤凰羽翼炼制的坠饰",
         {{"wolfTooth3", 5}, {"eagleClaw3", 3}, {"foxTail3", 3}}, 36, 0.35},
        // ── tier 5 ──
        {"dragonSlayer", "凤炎刃", "WEAPON", 5, 5, "蕴含凤炎之力的绝世神兵",
         {{"tigerBlood4", 6}, {"tigerTooth4", 4}, {"tigerCore4", 2}, {"dragonHorn4", 2}}, 72, 0.30},
        {"godSlayer", "青莲剑", "WEAPON", 5, 5, "自青莲中诞生的神剑",
         {{"tigerBlood4", 5}, {"tigerTooth4", 4}, {"eagleClaw4", 3}, {"tigerCore4", 2}}, 72, 0.30},
        {"phoenixWing", "阴阳扇", "WEAPON", 5, 5, "蕴含阴阳之力的神扇",
         {{"eagleFeather4", 6}, {"eagleClaw4", 4}, {"eagleCore4", 2}, {"snakeCore4", 2}}, 72, 0.30},
        {"celestialOrb", "天玄杖", "WEAPON", 5, 5, "蕴含天玄之力的神杖",
         {{"snakeBlood4", 5}, {"snakeCore4", 4}, {"foxCore4", 2}, {"dragonCore4", 2}}, 72, 0.30},
        {"earthArmor", "玄幽袍", "ARMOR", 5, 5, "承载玄幽之力的法袍",
         {{"snakeScale4", 6}, {"snakeBlood4", 4}, {"snakeCore4", 2}, {"dragonScale4", 2}}, 72, 0.30},
        {"divinePlate", "墨幽铠", "ARMOR", 5, 5, "墨幽玄铁铸造的铠甲",
         {{"bearHide4", 6}, {"bearBone4", 4}, {"bearClaw4", 2}, {"bearCore4", 2}}, 72, 0.30},
        {"celestialRobe", "凌星袍", "ARMOR", 5, 5, "凌驾星辰之力的法袍",
         {{"turtleShell4", 6}, {"turtleBone4", 4}, {"turtleCore4", 2}, {"dragonScale4", 2}}, 72, 0.30},
        {"voidShadowRobe", "定海铠", "ARMOR", 5, 5, "定海之力凝聚的铠甲",
         {{"bearHide4", 5}, {"snakeScale4", 3}, {"bearCore4", 3}, {"foxCore4", 2}}, 72, 0.30},
        {"voidBoots", "溯光靴", "BOOTS", 5, 5, "溯光逐影穿梭虚空",
         {{"wolfHide4", 5}, {"wolfTooth4", 4}, {"wolfCore4", 2}, {"dragonScale4", 2}}, 72, 0.30},
        {"shadowStepBoots", "赤煞靴", "BOOTS", 5, 5, "赤煞之气凝聚的战靴",
         {{"wolfHide4", 5}, {"eagleClaw4", 3}, {"wolfCore4", 2}, {"foxTail4", 2}}, 72, 0.30},
        {"earthCore", "渡厄佩", "ACCESSORY", 5, 5, "可渡厄解难的灵佩",
         {{"foxCore4", 6}, {"foxBone4", 4}, {"foxHide4", 2}, {"dragonCore4", 2}}, 72, 0.30},
        {"dragonEyePendant", "隐云佩", "ACCESSORY", 5, 5, "隐于云端的灵佩",
         {{"wolfTooth4", 5}, {"eagleClaw4", 4}, {"foxTail4", 2}, {"wolfCore4", 2}}, 72, 0.30},
        // ── tier 6 ──
        {"immortalSword", "诛仙剑", "WEAPON", 6, 6, "上古仙人遗留的仙器",
         {{"tigerBlood5", 8}, {"tigerTooth5", 5}, {"tigerCore5", 3}, {"dragonHorn5", 3}}, 120, 0.25},
        {"chaosBlade", "玄玉刃", "WEAPON", 6, 6, "玄玉淬炼的神刃",
         {{"tigerBlood5", 6}, {"tigerTooth5", 5}, {"eagleClaw5", 4}, {"tigerCore5", 3}}, 120, 0.25},
        {"primordialStaff", "天星杖", "WEAPON", 6, 6, "凝聚天星之力的法杖",
         {{"snakeBlood5", 7}, {"snakeCore5", 5}, {"eagleFeather5", 3}, {"dragonCore5", 3}}, 120, 0.25},
        {"yinYangOrb", "天玄扇", "WEAPON", 6, 6, "蕴含天玄道韵的至宝",
         {{"snakeBlood5", 6}, {"snakeCore5", 5}, {"foxCore5", 3}, {"dragonCore5", 3}}, 120, 0.25},
        {"immortalArmor", "不朽铠", "ARMOR", 6, 6, "仙界神甲",
         {{"snakeScale5", 8}, {"snakeBlood5", 5}, {"snakeCore5", 3}, {"dragonScale5", 3}}, 120, 0.25},
        {"primordialArmor", "苍罡铠", "ARMOR", 6, 6, "苍罡之力凝聚的神甲",
         {{"bearHide5", 8}, {"bearBone5", 5}, {"bearCore5", 3}, {"dragonClaw5", 3}}, 120, 0.25},
        {"immortalRobe", "曦光铠", "ARMOR", 6, 6, "蕴含曦光之力的铠甲",
         {{"turtleShell5", 8}, {"turtleBone5", 5}, {"turtleCore5", 3}, {"dragonScale5", 3}}, 120, 0.25},
        {"chaosRobe", "云影袍", "ARMOR", 6, 6, "云影交织的法袍",
         {{"bearHide5", 6}, {"snakeScale5", 4}, {"bearCore5", 4}, {"dragonClaw5", 3}}, 120, 0.25},
        {"immortalBoots", "鸾羽履", "BOOTS", 6, 6, "鸾鸟仙羽织就的灵履",
         {{"wolfHide5", 7}, {"wolfTooth5", 5}, {"wolfCore5", 3}, {"dragonScale5", 3}}, 120, 0.25},
        {"chaosStepBoots", "鹤岚靴", "BOOTS", 6, 6, "鹤翔岚雾而行",
         {{"wolfHide5", 6}, {"eagleClaw5", 4}, {"wolfCore5", 4}, {"dragonClaw5", 3}}, 120, 0.25},
        {"chaosBead", "幽朔珠", "ACCESSORY", 6, 6, "蕴含幽朔之力的灵珠",
         {{"foxCore5", 8}, {"foxBone5", 5}, {"foxHide5", 3}, {"dragonCore5", 3}}, 120, 0.25},
        {"heavenRing", "长明坠", "ACCESSORY", 6, 6, "长明不灭的灵坠",
         {{"wolfTooth5", 7}, {"eagleClaw5", 5}, {"foxTail5", 3}, {"dragonScale5", 3}}, 120, 0.25},
    };
}

// ============================================================
// PillRecipe 生成（与 Kotlin PillRecipeDatabase 生成循环对应）
// ============================================================

/// TIER_DURATION（与 ForgeRecipeDatabase.TIER_DURATION 同源）
static constexpr int32_t kTierDuration[7] = {0, 3, 6, 12, 36, 72, 120};
/// TIER_SUCCESS_RATE
static constexpr double kTierSuccessRate[7] = {0.0, 0.75, 0.65, 0.60, 0.45, 0.35, 0.20};

/// TIER_HERB_IDS（每 tier 9 种灵草：3 草 + 3 花 + 3 果）
static constexpr const char* kTierHerbs[7][9] = {
    {},
    {"spiritGrass1", "spiritGrass2", "spiritGrass3", "spiritFlower1", "spiritFlower2", "spiritFlower3", "spiritFruit1", "spiritFruit2", "spiritFruit3"},
    {"spiritGrass4", "spiritGrass5", "spiritGrass6", "spiritFlower4", "spiritFlower5", "spiritFlower6", "spiritFruit4", "spiritFruit5", "spiritFruit6"},
    {"spiritGrass7", "spiritGrass8", "spiritGrass9", "spiritFlower7", "spiritFlower8", "spiritFlower9", "spiritFruit7", "spiritFruit8", "spiritFruit9"},
    {"spiritGrass10", "spiritGrass11", "spiritGrass12", "spiritFlower10", "spiritFlower11", "spiritFlower12", "spiritFruit10", "spiritFruit11", "spiritFruit12"},
    {"spiritGrass13", "spiritGrass14", "spiritGrass15", "spiritFlower13", "spiritFlower14", "spiritFlower15", "spiritFruit13", "spiritFruit14", "spiritFruit15"},
    {"spiritGrass16", "spiritGrass17", "spiritGrass18", "spiritFlower16", "spiritFlower17", "spiritFlower18", "spiritFruit16", "spiritFruit17", "spiritFruit18"},
};

/// herbMat 等价：对每个下标取 herbs[idx.coerceIn(0,8)]，数量累加 2
inline std::map<std::string, int32_t> herbMat(int tier, const std::vector<int>& indices) {
    std::map<std::string, int32_t> result;
    for (int idx : indices) {
        const int clamped = idx < 0 ? 0 : (idx > 8 ? 8 : idx);
        result[kTierHerbs[tier][clamped]] += 2;
    }
    return result;
}

/// 从丹药模板构造配方（对应 Kotlin PillRecipe 构造；Kotlin 按类别显式
/// 拷贝效果字段，而模板中非本类字段恒为 0，故整体拷贝等价）
/// [dualPrice]：双属性丹（BATTLE dual / FUNCTIONAL dual）模板价 ×1.2
///（Kotlin `tierPrice(tier) * 1.2 * grade.priceMultiplier`，单属性 ×1.0）。
inline PillRecipeTemplate recipeFromTemplate(const PillTemplateSpec& t, int tier, int rarity,
                                             const std::string& category, const std::string& grade,
                                             const std::string& pillType,
                                             std::map<std::string, int32_t> materials,
                                             int duration, double successRate,
                                             double breakthroughChance, int32_t targetRealm,
                                             bool dualPrice = false) {
    PillRecipeTemplate r;
    r.id = t.id;
    r.name = t.name;
    r.tier = tier;
    r.rarity = rarity;
    // Kotlin PillTemplate.price：tierPrice(tier) × gradeMultiplier ×（dual 1.2）
    // tierPrice = pillBasePrice(rarity)；gradeMultiplier 按 grade lower 名索引
    {
        int gi = 1;  // medium 默认（防御：未知 grade 按中品）
        if (grade == "low") gi = 0;
        else if (grade == "high") gi = 2;
        const double base = static_cast<double>(kPillBasePrice[rarity]) *
                            kGradeMultiplier[gi] * (dualPrice ? 1.2 : 1.0);
        r.price = roundToInt(base);
    }
    r.category = category;
    r.grade = grade;
    r.pillType = pillType;
    r.description = t.description;
    r.materials = std::move(materials);
    r.duration = duration;
    r.successRate = successRate;
    r.breakthroughChance = breakthroughChance;
    r.targetRealm = targetRealm;
    r.cultivationSpeedPercent = t.cultivationSpeedPercent;
    r.skillExpSpeedPercent = t.skillExpSpeedPercent;
    r.nurtureSpeedPercent = t.nurtureSpeedPercent;
    r.cultivationAdd = t.cultivationAdd;
    r.skillExpAdd = t.skillExpAdd;
    r.nurtureAdd = t.nurtureAdd;
    r.physicalAttackAdd = t.physicalAttackAdd;
    r.magicAttackAdd = t.magicAttackAdd;
    r.physicalDefenseAdd = t.physicalDefenseAdd;
    r.magicDefenseAdd = t.magicDefenseAdd;
    r.hpAdd = t.hpAdd;
    r.mpAdd = t.mpAdd;
    r.speedAdd = t.speedAdd;
    r.critRateAdd = t.critRateAdd;
    r.critEffectAdd = t.critEffectAdd;
    r.extendLife = t.extendLife;
    r.intelligenceAdd = t.intelligenceAdd;
    r.charmAdd = t.charmAdd;
    r.loyaltyAdd = t.loyaltyAdd;
    r.comprehensionAdd = t.comprehensionAdd;
    r.artifactRefiningAdd = t.artifactRefiningAdd;
    r.pillRefiningAdd = t.pillRefiningAdd;
    r.spiritPlantingAdd = t.spiritPlantingAdd;
    r.teachingAdd = t.teachingAdd;
    r.moralityAdd = t.moralityAdd;
    r.miningAdd = t.miningAdd;
    return r;
}

/// 常规修炼配方（对应 Kotlin addCultivationStandardRecipes）
inline void buildCultivationStandardRecipes(std::vector<PillRecipeTemplate>& out) {
    static constexpr const char* kStandardPillTypes[6] = {
        "cultivationSpeed", "skillExpSpeed", "nurtureSpeed",
        "cultivationAdd", "skillExpAdd", "nurtureAdd"};
    // herbPatterns：与 Kotlin listOf(listOf(0,3), ...) 一致
    static constexpr int kHerbPatterns[6][2] = {
        {0, 3}, {1, 6}, {2, 4}, {0, 7}, {5, 8}, {3, 7}};

    for (int tier = 1; tier <= 6; ++tier) {
        const int duration = kTierDuration[tier];
        const double successRate = kTierSuccessRate[tier];
        for (int idx = 0; idx < 6; ++idx) {
            const std::map<std::string, int32_t> materials =
                herbMat(tier, {kHerbPatterns[idx][0], kHerbPatterns[idx][1]});
            for (int g = 0; g < 3; ++g) {
                const std::string id = std::string(kStandardPillTypes[idx]) + "_" +
                    std::to_string(tier) + "_" + kGradeLower[g];
                const auto tpl = pillTemplateById(id);
                if (!tpl) continue;  // 对应 Kotlin `?: continue`
                out.push_back(recipeFromTemplate(*tpl, tier, tier, "CULTIVATION",
                                                 kGradeLower[g], kStandardPillTypes[idx],
                                                 materials, duration, successRate, 0.0, 0));
            }
        }
    }
}

/// 突破配方（对应 Kotlin addCultivationBreakthroughRecipes）
inline void buildCultivationBreakthroughRecipes(std::vector<PillRecipeTemplate>& out) {
    // 聚气丹显式材料（Kotlin explicitBreakthroughMaterials[9]）
    for (const auto& bt : kBreakthroughTiers) {
        const int tier = bt.tier;
        const int duration = kTierDuration[tier];
        const double successRate = kTierSuccessRate[tier];
        for (int idx = 0; idx < bt.targetCount; ++idx) {
            const int targetRealm = bt.targets[idx];
            std::map<std::string, int32_t> materials;
            if (targetRealm == 9) {
                materials = {{"spiritGrass2", 2}, {"spiritFruit2", 2}};
            } else {
                materials = {{kTierHerbs[tier][(idx * 2) % 9], 2},
                             {kTierHerbs[tier][(idx * 2 + 3) % 9], 2}};
            }
            // tier >= 3 追加第三味材料（Kotlin `(materials as MutableMap)[...] = 2`）
            if (tier >= 3) {
                materials[kTierHerbs[tier][(idx * 2 + 5) % 9]] = 2;
            }
            for (int g = 0; g < 3; ++g) {
                const std::string id = "breakthrough_" + std::to_string(targetRealm) + "_" +
                    kGradeLower[g];
                const auto tpl = pillTemplateById(id);
                if (!tpl) continue;
                out.push_back(recipeFromTemplate(*tpl, tier, tier, "CULTIVATION",
                                                 kGradeLower[g], "breakthrough",
                                                 materials, duration, successRate,
                                                 tpl->breakthroughChance, targetRealm));
            }
        }
    }
}

/// 单属性战斗配方（对应 Kotlin addBattleSingleRecipes）
inline void buildBattleSingleRecipes(std::vector<PillRecipeTemplate>& out, int tier) {
    const int duration = kTierDuration[tier];
    const double successRate = kTierSuccessRate[tier];
    const int singleCount = static_cast<int>(sizeof(kSingleAttrSpecs) / sizeof(kSingleAttrSpecs[0]));
    for (int idx = 0; idx < singleCount; ++idx) {
        const auto& cfg = kSingleAttrSpecs[idx];
        // mapOf(herbs[idx % 9] to 2, herbs[(idx + 4) % 9] to 2)
        const std::map<std::string, int32_t> materials = {
            {kTierHerbs[tier][idx % 9], 2}, {kTierHerbs[tier][(idx + 4) % 9], 2}};
        for (int g = 0; g < 3; ++g) {
            const std::string id = std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" +
                kGradeLower[g];
            const auto tpl = pillTemplateById(id);
            if (!tpl) continue;
            out.push_back(recipeFromTemplate(*tpl, tier, tier, "BATTLE", kGradeLower[g],
                                             cfg.pillType, materials, duration, successRate,
                                             0.0, 0));
        }
    }
}

/// 双属性战斗配方（对应 Kotlin addBattleDualRecipes）
inline void buildBattleDualRecipes(std::vector<PillRecipeTemplate>& out, int tier) {
    const int duration = kTierDuration[tier];
    const double successRate = kTierSuccessRate[tier];
    const int dualCount = static_cast<int>(sizeof(kDualAttrSpecs) / sizeof(kDualAttrSpecs[0]));
    for (int idx = 0; idx < dualCount; ++idx) {
        const auto& cfg = kDualAttrSpecs[idx];
        // mapOf(herbs[idx % 9] to 2, herbs[(idx + 3) % 9] to 2)
        const std::map<std::string, int32_t> materials = {
            {kTierHerbs[tier][idx % 9], 2}, {kTierHerbs[tier][(idx + 3) % 9], 2}};
        for (int g = 0; g < 3; ++g) {
            const std::string id = std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" +
                kGradeLower[g];
            const auto tpl = pillTemplateById(id);
            if (!tpl) continue;
            out.push_back(recipeFromTemplate(*tpl, tier, tier, "BATTLE", kGradeLower[g],
                                             cfg.pillType, materials, duration, successRate,
                                             0.0, 0, /*dualPrice=*/true));
        }
    }
}

/// 暴击类战斗配方（对应 Kotlin addBattleCritRecipes）
inline void buildBattleCritRecipes(std::vector<PillRecipeTemplate>& out, int tier) {
    const int duration = kTierDuration[tier];
    const double successRate = kTierSuccessRate[tier];
    // mapOf(herbs[0] to 2, herbs[5] to 2)
    const std::map<std::string, int32_t> materials = {
        {kTierHerbs[tier][0], 2}, {kTierHerbs[tier][5], 2}};
    static constexpr const char* kCritTypes[2] = {"critRate", "critEffect"};
    for (const char* pillType : kCritTypes) {
        for (int g = 0; g < 3; ++g) {
            const std::string id = std::string(pillType) + "_" + std::to_string(tier) + "_" +
                kGradeLower[g];
            const auto tpl = pillTemplateById(id);
            if (!tpl) continue;
            out.push_back(recipeFromTemplate(*tpl, tier, tier, "BATTLE", kGradeLower[g],
                                             pillType, materials, duration, successRate,
                                             0.0, 0));
        }
    }
}

/// 单基础属性功能配方（对应 Kotlin addFunctionalSingleRecipes；
/// Kotlin singleTypes 首项为 extendLife，共 11 项，此处先单独处理 extendLife）
inline void buildFunctionalSingleRecipes(std::vector<PillRecipeTemplate>& out, int tier) {
    const int duration = kTierDuration[tier];
    const double successRate = kTierSuccessRate[tier];
    // Kotlin idx=0：extendLife（mapOf(herbs[0] to 2, herbs[6] to 2)）
    {
        const std::map<std::string, int32_t> materials = {
            {kTierHerbs[tier][0], 2}, {kTierHerbs[tier][6], 2}};
        for (int g = 0; g < 3; ++g) {
            const std::string id = "extendLife_" + std::to_string(tier) + "_" + kGradeLower[g];
            const auto tpl = pillTemplateById(id);
            if (!tpl) continue;
            out.push_back(recipeFromTemplate(*tpl, tier, tier, "FUNCTIONAL", kGradeLower[g],
                                             "extendLife", materials, duration, successRate,
                                             0.0, 0));
        }
    }
    // Kotlin idx=1..10：基础属性（对应 kSingleBaseAttrSpecs 下标 i = idx-1）
    const int singleCount =
        static_cast<int>(sizeof(kSingleBaseAttrSpecs) / sizeof(kSingleBaseAttrSpecs[0]));
    for (int i = 0; i < singleCount; ++i) {
        const auto& cfg = kSingleBaseAttrSpecs[i];
        // mapOf(herbs[(i+1) % 9] to 2, herbs[(i+7) % 9] to 2)
        const std::map<std::string, int32_t> materials = {
            {kTierHerbs[tier][(i + 1) % 9], 2}, {kTierHerbs[tier][(i + 7) % 9], 2}};
        for (int g = 0; g < 3; ++g) {
            const std::string id = std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" +
                kGradeLower[g];
            const auto tpl = pillTemplateById(id);
            if (!tpl) continue;
            out.push_back(recipeFromTemplate(*tpl, tier, tier, "FUNCTIONAL", kGradeLower[g],
                                             cfg.pillType, materials, duration, successRate,
                                             0.0, 0));
        }
    }
}

/// 双基础属性功能配方（对应 Kotlin addFunctionalDualRecipes）
inline void buildFunctionalDualRecipes(std::vector<PillRecipeTemplate>& out, int tier) {
    const int duration = kTierDuration[tier];
    const double successRate = kTierSuccessRate[tier];
    const int dualCount =
        static_cast<int>(sizeof(kDualBaseAttrSpecs) / sizeof(kDualBaseAttrSpecs[0]));
    for (int idx = 0; idx < dualCount; ++idx) {
        const auto& cfg = kDualBaseAttrSpecs[idx];
        // mapOf(herbs[idx % 9] to 2, herbs[(idx + 2) % 9] to 2)
        const std::map<std::string, int32_t> materials = {
            {kTierHerbs[tier][idx % 9], 2}, {kTierHerbs[tier][(idx + 2) % 9], 2}};
        for (int g = 0; g < 3; ++g) {
            const std::string id = std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" +
                kGradeLower[g];
            const auto tpl = pillTemplateById(id);
            if (!tpl) continue;
            out.push_back(recipeFromTemplate(*tpl, tier, tier, "FUNCTIONAL", kGradeLower[g],
                                             cfg.pillType, materials, duration, successRate,
                                             0.0, 0, /*dualPrice=*/true));
        }
    }
}

/// 全部丹药配方（对应 Kotlin _allRecipes =
/// generateCultivationRecipes + generateBattleRecipes + generateFunctionalRecipes）
inline std::vector<PillRecipeTemplate> buildPillRecipes() {
    std::vector<PillRecipeTemplate> out;
    // 修炼类
    buildCultivationStandardRecipes(out);
    buildCultivationBreakthroughRecipes(out);
    // 战斗类
    for (int tier = 1; tier <= 6; ++tier) {
        buildBattleSingleRecipes(out, tier);
        buildBattleDualRecipes(out, tier);
        buildBattleCritRecipes(out, tier);
    }
    // 功能类
    for (int tier = 1; tier <= 6; ++tier) {
        buildFunctionalSingleRecipes(out, tier);
        buildFunctionalDualRecipes(out, tier);
    }
    return out;
}

}  // namespace detail

// ============================================================
// 公开访问接口（静态表 + 按 id 查询）
// ============================================================

/// 全部锻造配方（6 tier × 12 = 72 条，顺序与 Kotlin tier1~tier6 一致）
inline const std::vector<ForgeRecipeTemplate>& forgeRecipes() {
    static const std::vector<ForgeRecipeTemplate> kRecipes = detail::buildForgeRecipes();
    return kRecipes;
}

/// 全部丹药配方（修炼 138 + 战斗 288 + 功能 306 = 732 条）
inline const std::vector<PillRecipeTemplate>& pillRecipes() {
    static const std::vector<PillRecipeTemplate> kRecipes = detail::buildPillRecipes();
    return kRecipes;
}

/// 按 id 查询锻造配方（不存在返回空 optional）
inline std::optional<ForgeRecipeTemplate> forgeRecipeById(const std::string& id) {
    static const std::map<std::string, const ForgeRecipeTemplate*> kIndex = [] {
        std::map<std::string, const ForgeRecipeTemplate*> idx;
        for (const auto& r : forgeRecipes()) idx.emplace(r.id, &r);
        return idx;
    }();
    const auto it = kIndex.find(id);
    if (it == kIndex.end()) return std::nullopt;
    return *it->second;
}

/// 按 id 查询丹药配方（不存在返回空 optional）
inline std::optional<PillRecipeTemplate> pillRecipeById(const std::string& id) {
    static const std::map<std::string, const PillRecipeTemplate*> kIndex = [] {
        std::map<std::string, const PillRecipeTemplate*> idx;
        for (const auto& r : pillRecipes()) idx.emplace(r.id, &r);
        return idx;
    }();
    const auto it = kIndex.find(id);
    if (it == kIndex.end()) return std::nullopt;
    return *it->second;
}

/// 品阶名称（对应 Kotlin PillRecipeDatabase.getTierName）
inline std::string pillTierName(int tier) {
    switch (tier) {
        case 1: return "凡品";
        case 2: return "灵品";
        case 3: return "宝品";
        case 4: return "玄品";
        case 5: return "地品";
        case 6: return "天品";
        default: return "未知";
    }
}

/// 品阶炼制时长（对应 ForgeRecipeDatabase.TIER_DURATION，未知 tier 回退 2）
inline int32_t recipeDurationByTier(int tier) {
    if (tier >= 1 && tier <= 6) return detail::kTierDuration[tier];
    return 2;
}

}  // namespace gamecore::data

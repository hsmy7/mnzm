// ============================================================
// recipe_db.h — 锻造/炼丹配方静态表（与 Kotlin 数据库同源）
//
// 数据来源：
//   1. 锻造配方（forgeRecipes，24 条套装部件配方 = 6 套 × 4 部位，
//      四部位化 F3 口径）——**静态字面量**，与 Kotlin ForgeRecipeDatabase
//      同构派生（id = "forge_{pieceId}"，材料表按部位族 6 档）。
//   2. 丹药配方（pillRecipes，660 条）——**程序化生成**，C++ 侧等价复刻
//      Kotlin PillRecipeDatabase.kt 的生成循环（TIER_DURATION /
//      TIER_SUCCESS_RATE / TIER_HERB_IDS / herbMat / PillGrade 循环）。
//      配方依赖 ItemDatabase.getPillById 的丹药模板（id/name/description/
//      效果字段），故本头文件同时在 detail 命名空间复刻了 ItemDatabase.kt
//      的 PillTemplate 生成逻辑（660 个模板，修炼 138 + 战斗 288 + 功能 234），
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

#include "gamecore/data/index_snapshot.h"

// S5：pillFromSpec 构造 state::Pill（models.h 无反向依赖，无环）
#include "gamecore/state/models.h"

namespace gamecore::data {

// ============================================================
// 结构体（字段与 Kotlin data class 对应）
// ============================================================

/// 锻造配方模板（对应 Kotlin `ForgeRecipeDatabase.ForgeRecipe`，四部位化 F3 口径）
struct ForgeRecipeTemplate {
    /// 配方 id = "forge_{pieceId}"（pieceId ∈ EquipmentDatabase 24 部件表）
    std::string id;
    std::string pieceId;
    std::string setId;
    /// EquipmentSlot.name（HEAD/BODY/HANDS/FEET；WEAPON/LEGS 已退役禁复用）
    std::string part;
    std::string name;
    std::string description;
    /// 品阶 1..6 材料表（下标 tier-1；产出品阶 r 消耗 tierMaterials[r-1]——
    /// Kotlin `tierMaterials: List<Map<String, Int>>` 对偶；std::map 有序，
    /// 禁止 unordered_map）
    std::vector<std::map<std::string, int32_t>> tierMaterials;

    /// B16/R6.2 数值等价守卫用。C++20 默认派生 ==：逐成员比较由编译器生成，
    /// 覆盖**全部字段**（比手写字段清单更严——不存在漏比某字段的可能）。
    friend bool operator==(const ForgeRecipeTemplate&, const ForgeRecipeTemplate&) = default;
};

/// 锻造配方派生档（Kotlin `ForgeRecipe.materialsFor` 同式：tier-1 下标取档，
/// 越界回退末档——tier<=0 时 getOrElse(-1) 落 last 分支，与 Kotlin 一致）
inline const std::map<std::string, int32_t>& forgeMaterialsFor(
        const ForgeRecipeTemplate& recipe, int32_t tier) {
    if (tier >= 1 && tier <= static_cast<int32_t>(recipe.tierMaterials.size())) {
        return recipe.tierMaterials[static_cast<std::size_t>(tier - 1)];
    }
    return recipe.tierMaterials.back();
}

/// 丹药配方模板（对应 Kotlin `PillRecipeDatabase.PillRecipe`）
struct PillRecipeTemplate {
    std::string id;
    std::string name;
    int32_t tier = 0;
    int32_t rarity = 0;
    /// 模板价（Kotlin `ItemDatabase.PillTemplate.price`——
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
    int32_t cultivationAdd = 0;
    int32_t skillExpAdd = 0;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t hpAdd = 0;
    int32_t mpAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;
    int32_t comprehensionAdd = 0;
    int32_t artifactRefiningAdd = 0;
    int32_t pillRefiningAdd = 0;
    int32_t spiritPlantingAdd = 0;
    int32_t teachingAdd = 0;
    int32_t moralityAdd = 0;
    int32_t miningAdd = 0;

    /// B16/R6.2 数值等价守卫用（同 ForgeRecipeTemplate：默认派生，全字段比较）。
    /// 注：`price` 为派生字段（见上方字段注释）——数据文件不含该键，注入后由
    /// `data_inject.h` 按 C++ 同一公式回填，等价守卫仍含 price 全字段比对。
    friend bool operator==(const PillRecipeTemplate&, const PillRecipeTemplate&) = default;

    // ── 单列口径（B1，尾部追加）──
    int32_t attackAdd = 0;
    int32_t defenseAdd = 0;
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
// ItemDatabase.PillTemplate 等价复刻（配方引用字段 + 产出字段）
// ============================================================

/// 丹药模板（对应 Kotlin `ItemDatabase.PillTemplate`）
/// 注：S4 生产结算（production.h producePill）按本表构造完整 Pill——
/// 尾部"产出字段"块为 Kotlin createPillFromTemplate 消费面（category/
/// pillType/rarity/duration/cannotStack/minRealm/isAscension），各生成函数
/// 末尾补设置（默认值 = Kotlin PillTemplate 字段默认：duration=3/
/// cannotStack=true/minRealm=9）。healMaxHpPercent/mpRecoverMaxMpPercent/
/// revive/clearAll 在 Kotlin 全部生成函数中均未设置（默认零值），不承载。
struct PillTemplateSpec {
    std::string id;
    std::string name;
    std::string description;
    double breakthroughChance = 0.0;
    int32_t targetRealm = 0;
    double cultivationSpeedPercent = 0.0;
    double skillExpSpeedPercent = 0.0;
    int32_t cultivationAdd = 0;
    int32_t skillExpAdd = 0;
    int32_t physicalAttackAdd = 0;
    int32_t magicAttackAdd = 0;
    int32_t physicalDefenseAdd = 0;
    int32_t magicDefenseAdd = 0;
    int32_t hpAdd = 0;
    int32_t mpAdd = 0;
    int32_t speedAdd = 0;
    double critRateAdd = 0.0;
    double critEffectAdd = 0.0;
    int32_t intelligenceAdd = 0;
    int32_t charmAdd = 0;
    int32_t comprehensionAdd = 0;
    int32_t artifactRefiningAdd = 0;
    int32_t pillRefiningAdd = 0;
    int32_t spiritPlantingAdd = 0;
    int32_t teachingAdd = 0;
    int32_t moralityAdd = 0;
    int32_t miningAdd = 0;
    // ── S4 产出字段块（尾部追加——既有聚合初始化位置不变，默认值兜底）──
    std::string category;             // PillCategory.name
    std::string pillType;             // Kotlin PillTemplate.pillType
    int32_t rarity = 0;               // TIER_RARITY[tier] == tier
    int32_t duration = 3;             // Kotlin 默认 3（速度丹 9 / 战斗丹 3 / 其余 0）
    bool cannotStack = true;          // Kotlin 默认 true（功能/突破/加值丹 false）
    int32_t minRealm = 9;             // Kotlin 默认 9（按 tier 设 tierMinRealm）
    bool isAscension = false;         // 仅登仙丹 true
    // ── S5：PillGrade（0=LOW/1=MEDIUM/2=HIGH，对应 kGradeDisplay/kGradeMultiplier
    //    下标）——任务奖励 generateRandomPill 消费（createPillFromTemplate 的
    //    grade 字段）；finalize 由 id 尾段（low/medium/high）解析
    int32_t grade = 1;
    // ── 单列口径（B1，尾部追加——既有聚合初始化位置不变）──
    // 物法攻合并 attackAdd、物法防合并 defenseAdd
    int32_t attackAdd = 0;
    int32_t defenseAdd = 0;
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
/// BASE_ATTR_MEDIUM
static constexpr double kSpeedPct[7] = {0.0, 0.30, 0.35, 0.40, 0.50, 0.60, 0.80};
static constexpr double kCritRate[7] = {0.0, 0.03, 0.05, 0.07, 0.10, 0.13, 0.16};
static constexpr double kCritEffect[7] = {0.0, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40};
static constexpr int32_t kBaseAttr[7] = {0, 3, 5, 8, 12, 16, 20};
/// 孕养度丹基础值（listOf(50,100,200,400,800,1600)[tier-1]）

// ── 丹药名称表（与 Kotlin 字面量逐值一致，下标 0 未用）────────────

static constexpr const char* kSpeedNames[7] = {"", "引灵丹", "聚灵丹", "凝元丹", "炼气丹", "混元丹", "仙灵丹"};
static constexpr const char* kSkillSpeedNames[7] = {"", "悟法丹", "通法丹", "玄法丹", "道法丹", "天法丹", "仙法丹"};
static constexpr const char* kCultAddNames[7] = {"", "增元丹", "培元丹", "固元丹", "真元丹", "玄元丹", "仙元丹"};
static constexpr const char* kSkillAddNames[7] = {"", "悟道丹", "明心丹", "通玄丹", "慧灵丹", "道悟丹", "天机丹"};

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

/// 修炼速度丹（cultivationSpeed / skillExpSpeed）
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
                "cultivationSpeed_" + std::to_string(tier) + "_" + gradeLower,kSpeedNames[tier],tierName + gradeName + "修炼速度丹，提升境界修炼速度" + pctStr + "%，持续9旬",
                0.0, 0, speedPct * mult, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0, 0, 0});
            out.push_back(PillTemplateSpec{
                "skillExpSpeed_" + std::to_string(tier) + "_" + gradeLower,kSkillSpeedNames[tier],tierName + gradeName + "功法速度丹，提升功法熟练度修炼速度" + pctStr + "%，持续9旬",
                0.0, 0, 0.0, speedPct * mult, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0, 0, 0});
        }
    }
}

/// 修为/熟练度加值丹（cultivationAdd / skillExpAdd）
inline void buildCultivationValuePills(std::vector<PillTemplateSpec>& out) {
    for (int tier = 1; tier <= 6; ++tier) {
        const int baseCult = kCultBase[tier];
        const int cultBase = roundToInt(baseCult * 0.375);
        const int skillBase = roundToInt(baseCult * 0.1);
        const std::string tierName = kTierNames[tier];
        for (int g = 0; g < 3; ++g) {
            const double mult = kGradeMultiplier[g];
            const std::string gradeLower = kGradeLower[g];
            const std::string gradeName = kGradeDisplay[g];

            const int cultAddVal = roundToInt(cultBase * mult);
            const int skillAddVal = roundToInt(skillBase * mult);

            out.push_back(PillTemplateSpec{
                "cultivationAdd_" + std::to_string(tier) + "_" + gradeLower,kCultAddNames[tier],tierName + gradeName + "境界修为丹，立即增加" + std::to_string(cultAddVal) + "点境界修为",
                0.0, 0, 0.0, 0.0, cultAddVal, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0, 0, 0});
            out.push_back(PillTemplateSpec{
                "skillExpAdd_" + std::to_string(tier) + "_" + gradeLower,kSkillAddNames[tier],tierName + gradeName + "功法熟练丹，立即增加" + std::to_string(skillAddVal) + "点功法熟练度",
                0.0, 0, 0.0, 0.0, 0, skillAddVal, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0, 0, 0});
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
                "breakthrough_" + std::to_string(targetRealm) + "_" + kGradeLower[g],name,std::string("增加") + realmName(targetRealm) + "期突破成功率" +
                        std::to_string(pct) + "%",
                chance, targetRealm, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0, 0, 0});
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
                // 单列口径（B1）：物法攻合并 attackAdd、物法防合并 defenseAdd
                //（attackAdd/defenseAdd 位于聚合尾部 S4 块之后，位置列表不可达 → push 后按名赋值）
                out.push_back(PillTemplateSpec{
                std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + gradeLower,cfg.names[tier],desc,
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, std::string(cfg.pillType) == "hp" ? val : 0, std::string(cfg.pillType) == "mp" ? val : 0, std::string(cfg.pillType) == "speed" ? val : 0, 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0});
                out.back().attackAdd =
                    (std::string(cfg.pillType) == "physicalAttack" ||
                     std::string(cfg.pillType) == "magicAttack") ? val : 0;
                out.back().defenseAdd =
                    (std::string(cfg.pillType) == "physicalDefense" ||
                     std::string(cfg.pillType) == "magicDefense") ? val : 0;
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
                // 单列口径（B1）：物法攻/防各自合并（尾部字段 push 后按名赋值）
                out.push_back(PillTemplateSpec{
                std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + gradeLower,cfg.names[tier],desc,
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, attrVal("hp"), attrVal("mp"), attrVal("speed"), 0.0, 0.0, 0, 0,
                0, 0, 0, 0, 0});
                out.back().attackAdd =
                    attrVal("physicalAttack") + attrVal("magicAttack");
                out.back().defenseAdd =
                    attrVal("physicalDefense") + attrVal("magicDefense");
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
                "critRate_" + std::to_string(tier) + "_" + gradeLower,kCritRateNames[tier],tierName + gradeName + "暴击率丹，增加" + std::to_string(roundToInt(cr * 100.0)) +
                    "%暴击率，持续9旬",
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, cr, 0.0, 0, 0,
                0, 0, 0, 0, 0, 0, 0});

            const double ce = kCritEffect[tier] * mult;
            out.push_back(PillTemplateSpec{
                "critEffect_" + std::to_string(tier) + "_" + gradeLower,kCritEffectNames[tier],tierName + gradeName + "暴击效果丹，增加" + std::to_string(roundToInt(ce * 100.0)) +
                    "%暴击效果，持续9旬",
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, ce, 0, 0,
                0, 0, 0, 0, 0, 0, 0});
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
                std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + kGradeLower[g],cfg.names[tier],desc,
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, std::string(cfg.pillType) == "intelligence" ? val : 0, std::string(cfg.pillType) == "charm" ? val : 0,
                0, std::string(cfg.pillType) == "artifactRefining" ? val : 0, std::string(cfg.pillType) == "pillRefining" ? val : 0, std::string(cfg.pillType) == "spiritPlanting" ? val : 0, std::string(cfg.pillType) == "teaching" ? val : 0, std::string(cfg.pillType) == "morality" ? val : 0, std::string(cfg.pillType) == "mining" ? val : 0});
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
                std::string(cfg.pillType) + "_" + std::to_string(tier) + "_" + kGradeLower[g],cfg.names[tier],desc,
                0.0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, attrVal("intelligence"), attrVal("charm"),
                attrVal("comprehension"), attrVal("artifactRefining"), attrVal("pillRefining"), attrVal("spiritPlanting"), attrVal("teaching"), attrVal("morality"), 0});
            }
        }
    }
}

/// 品阶最低境界（Kotlin GameConfig.Realm.getMinRealmForRarity——本表内自持，
/// 避免数据层反向依赖 system 层结算头）
inline int32_t tierRarityMinRealm(int32_t tier) {
    switch (tier) {
        case 2: return 7;
        case 3: return 6;
        case 4: return 5;
        case 5: return 4;
        case 6: return 2;
        default: return 9;  // tier 1 及越界
    }
}

/// S4 产出字段后处理（category/pillType/rarity/duration/cannotStack/minRealm/
/// isAscension）——Kotlin createPillFromTemplate 消费面，production.h 构造
/// Pill 用。id 形态 "${pillType}_${tier|targetRealm}_${gradeLower}"（pillType
/// 全集无下划线）；breakthrough 的第二段为 targetRealm，rarity 按
/// kBreakthroughTiers tier 反查。口径 = Kotlin 各生成函数显式值 + 默认值
/// （战斗丹 minRealm 未设 → 9；isAscension 仅登仙丹）。
inline void finalizePillSpecOut(std::vector<PillTemplateSpec>& specs) {
    // breakthrough id 第二段（targetRealm）→ 生成 tier（kBreakthroughTiers 逐行）
    auto breakthroughTier = [](int32_t targetRealm) -> int32_t {
        for (const auto& bt : kBreakthroughTiers) {
            for (int i = 0; i < bt.targetCount; ++i) {
                if (bt.targets[i] == targetRealm) return bt.tier;
            }
        }
        return 1;
    };
    for (auto& s : specs) {
        const std::size_t lastUs = s.id.rfind('_');
        if (lastUs == std::string::npos || lastUs == 0) continue;
        const std::size_t secondUs = s.id.rfind('_', lastUs - 1);
        if (secondUs == std::string::npos) continue;
        s.pillType = s.id.substr(0, secondUs);
        {
            const std::string g = s.id.substr(lastUs + 1);
            s.grade = g == "high" ? 2 : g == "medium" ? 1 : 0;
        }
        const int32_t seg = std::atoi(s.id.substr(secondUs + 1, lastUs - secondUs - 1).c_str());
        if (s.pillType == "breakthrough") {
            s.category = "CULTIVATION";
            s.rarity = breakthroughTier(seg);
            s.duration = 0;
            s.cannotStack = false;
            s.minRealm = tierRarityMinRealm(s.rarity);
            s.isAscension = s.targetRealm == 0;
        } else if (s.pillType == "cultivationSpeed" || s.pillType == "skillExpSpeed") {
            s.category = "CULTIVATION";
            s.rarity = seg;
            s.duration = 9;
            s.cannotStack = true;
            s.minRealm = tierRarityMinRealm(seg);
        } else if (s.pillType == "cultivationAdd" || s.pillType == "skillExpAdd") {
            s.category = "CULTIVATION";
            s.rarity = seg;
            s.duration = 0;
            s.cannotStack = false;
            s.minRealm = tierRarityMinRealm(seg);
        } else if (s.pillType == "physicalAttack" || s.pillType == "magicAttack" ||
                   s.pillType == "physicalDefense" || s.pillType == "magicDefense" ||
                   s.pillType == "hp" || s.pillType == "mp" || s.pillType == "speed" ||
                   s.pillType == "physicalAttackDefense" || s.pillType == "magicAttackDefense" ||
                   s.pillType == "attackMixed" || s.pillType == "defenseMixed" ||
                   s.pillType == "hpMp" || s.pillType == "attackSpeed" ||
                   s.pillType == "magicSpeed" || s.pillType == "critRate" ||
                   s.pillType == "critEffect") {
            s.category = "BATTLE";
            s.rarity = seg;
            s.duration = 3;
            s.cannotStack = true;
            // Kotlin 战斗丹 minRealm = tierRarityMinRealm(tier)
            //（与 ItemDatabase 各战斗丹生成器逐行一致，任务奖励 battle 丹同此规则）
            s.minRealm = tierRarityMinRealm(seg);
        } else {
            // 功能丹全集（单/双基础属性）——Kotlin 统一 FUNCTIONAL / duration 0 /
            // cannotStack false / tierMinRealm
            s.category = "FUNCTIONAL";
            s.rarity = seg;
            s.duration = 0;
            s.cannotStack = false;
            s.minRealm = tierRarityMinRealm(seg);
        }
    }
}

/// 全部丹药模板（660 = 修炼 138 + 战斗 288 + 功能 234，与 Kotlin allPills 同构）
inline std::vector<PillTemplateSpec> buildPillTemplates() {
    std::vector<PillTemplateSpec> out;
    buildCultivationSpeedPills(out);
    buildCultivationValuePills(out);
    buildBreakthroughPills(out);
    buildSingleAttrBattlePills(out);
    buildDualAttrBattlePills(out);
    buildCritBattlePills(out);
    buildSingleBaseAttrPills(out);
    buildDualBaseAttrPills(out);
    finalizePillSpecOut(out);
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

/// PillGrade.name（kGradeDisplay 下标语义：0=LOW/1=MEDIUM/2=HIGH）
inline const char* pillGradeName(int32_t grade) {
    return grade == 2 ? "HIGH" : grade == 1 ? "MEDIUM" : "LOW";
}

/// 模板 → 物品丹（Kotlin ItemDatabase.createPillFromTemplate 等价——
/// id 由调用方注入：生产域 nextItemId("gc-pill")，对拍面忽略新增条目 id）。
/// S5 从 production.h producePill 的内联映射提取共享（单一来源防漂移）。
inline ::gamecore::state::Pill pillFromSpec(const PillTemplateSpec& tpl,
                                            const std::string& id) {
    ::gamecore::state::Pill pill;
    pill.id = id;
    pill.name = tpl.name;
    pill.rarity = tpl.rarity;
    pill.description = tpl.description;
    pill.category = tpl.category;
    pill.grade = pillGradeName(tpl.grade);
    pill.pillType = tpl.pillType;
    auto& e = pill.effects;
    e.breakthroughChance = tpl.breakthroughChance;
    e.targetRealm = tpl.targetRealm;
    e.isAscension = tpl.isAscension;
    e.cultivationSpeedPercent = tpl.cultivationSpeedPercent;
    e.skillExpSpeedPercent = tpl.skillExpSpeedPercent;
    e.cultivationAdd = tpl.cultivationAdd;
    e.skillExpAdd = tpl.skillExpAdd;
    e.duration = tpl.duration;
    e.cannotStack = tpl.cannotStack;
    e.physicalAttackAdd = tpl.physicalAttackAdd;
    e.attackAdd = tpl.attackAdd;
    e.defenseAdd = tpl.defenseAdd;
    e.magicAttackAdd = tpl.magicAttackAdd;
    e.physicalDefenseAdd = tpl.physicalDefenseAdd;
    e.magicDefenseAdd = tpl.magicDefenseAdd;
    e.hpAdd = tpl.hpAdd;
    e.mpAdd = tpl.mpAdd;
    e.speedAdd = tpl.speedAdd;
    e.critRateAdd = tpl.critRateAdd;
    e.critEffectAdd = tpl.critEffectAdd;
    e.intelligenceAdd = tpl.intelligenceAdd;
    e.charmAdd = tpl.charmAdd;
    e.comprehensionAdd = tpl.comprehensionAdd;
    e.artifactRefiningAdd = tpl.artifactRefiningAdd;
    e.pillRefiningAdd = tpl.pillRefiningAdd;
    e.spiritPlantingAdd = tpl.spiritPlantingAdd;
    e.teachingAdd = tpl.teachingAdd;
    e.moralityAdd = tpl.moralityAdd;
    e.miningAdd = tpl.miningAdd;
    pill.minRealm = tpl.minRealm;
    pill.quantity = 1;
    return pill;
}

/// 品阶区间过滤模板表（Kotlin `allPills.values.filter { it.rarity in min..max }`
/// 等价——pillTemplates() 生成序 = Kotlin allPills 插入序，随机索引逐位一致）。
inline std::vector<const PillTemplateSpec*> pillTemplatesByRarityRange(int32_t minRarity,
                                                                       int32_t maxRarity) {
    std::vector<const PillTemplateSpec*> out;
    for (const auto& t : pillTemplates()) {
        if (t.rarity >= minRarity && t.rarity <= maxRarity) out.push_back(&t);
    }
    return out;
}

// ============================================================
// ForgeRecipe 静态表（四部位化 F3：24 条套装部件配方 = 6 套 × 4 部位）
//
// 与 Kotlin ForgeRecipeDatabase 同构派生：配方 id = "forge_{pieceId}"
//（pieceId ∈ EquipmentDatabase 24 部件表）；name/description = 部件模板
// 同源（Kotlin recipe() 经 EquipmentDatabase.getPieceById 派生，此处逐字
// 复刻派生结果）；材料表按部位族在 6 套间同构复用（Kotlin 字面量即同构
// 复制，注释原话「材料表逐部位同构复用」）。产出品阶由锻造槽位 tier 决定
//（完成期 equipment_factory::create 消费），配方不再携带 tier/rarity/
// duration/successRate 静态档（时长走 kTierDuration 派生，成功率由 S4
// 公式合成——Kotlin formulaService 同口径）。
// ============================================================

/// 部位材料族：品阶 1..6 材料表（与 Kotlin allRecipes 逐条一致；
/// HEAD/BODY/HANDS/FEET 四族在 6 套间同构复用）
inline std::vector<std::map<std::string, int32_t>> forgeHeadMaterials() {
    return {
        {{"bearHide0", 3}, {"bearBone0", 2}},
        {{"bearHide1", 4}, {"bearBone1", 3}},
        {{"bearHide2", 5}, {"bearBone2", 3}, {"bearCore2", 2}},
        {{"bearHide3", 5}, {"bearBone3", 4}, {"bearCore3", 3}},
        {{"bearHide4", 6}, {"bearBone4", 4}, {"bearCore4", 2}, {"dragonScale4", 2}},
        {{"bearHide5", 8}, {"bearBone5", 5}, {"bearCore5", 3}, {"dragonClaw5", 3}},
    };
}

inline std::vector<std::map<std::string, int32_t>> forgeBodyMaterials() {
    return {
        {{"bearHide0", 4}, {"bearBone0", 2}},
        {{"bearHide1", 5}, {"bearBone1", 2}},
        {{"snakeScale2", 5}, {"snakeBlood2", 3}, {"snakeCore2", 2}},
        {{"snakeScale3", 6}, {"snakeBlood3", 4}, {"snakeCore3", 2}},
        {{"snakeScale4", 6}, {"snakeBlood4", 4}, {"snakeCore4", 2}, {"dragonScale4", 2}},
        {{"snakeScale5", 8}, {"snakeBlood5", 5}, {"snakeCore5", 3}, {"dragonScale5", 3}},
    };
}

inline std::vector<std::map<std::string, int32_t>> forgeHandsMaterials() {
    return {
        {{"eagleClaw0", 3}, {"eagleFeather0", 2}},
        {{"eagleClaw1", 4}, {"eagleFeather1", 3}},
        {{"eagleFeather2", 5}, {"eagleClaw2", 3}, {"eagleCore2", 2}},
        {{"eagleFeather3", 6}, {"eagleClaw3", 4}, {"eagleCore3", 2}},
        {{"eagleFeather4", 6}, {"eagleClaw4", 4}, {"eagleCore4", 2}, {"snakeCore4", 2}},
        {{"eagleFeather5", 7}, {"eagleClaw5", 5}, {"eagleCore5", 3}, {"dragonCore5", 3}},
    };
}

inline std::vector<std::map<std::string, int32_t>> forgeFeetMaterials() {
    return {
        {{"wolfHide0", 3}, {"wolfBone0", 2}},
        {{"wolfHide1", 4}, {"wolfBone1", 2}},
        {{"wolfHide2", 4}, {"wolfBone2", 3}, {"wolfCore2", 2}},
        {{"wolfHide3", 5}, {"wolfBone3", 3}, {"wolfCore3", 2}},
        {{"wolfHide4", 5}, {"wolfTooth4", 4}, {"wolfCore4", 2}, {"dragonScale4", 2}},
        {{"wolfHide5", 7}, {"wolfTooth5", 5}, {"wolfCore5", 3}, {"dragonScale5", 3}},
    };
}

/// 单条配方（Kotlin recipe(pieceId, part, materials) 同式——id = "forge_{pieceId}"，
/// setId 取 pieceId 首段）
inline ForgeRecipeTemplate forgeRecipe(const std::string& pieceId,
                                       const std::string& part,
                                       const std::string& name,
                                       const std::string& description,
                                       std::vector<std::map<std::string, int32_t>> materials) {
    ForgeRecipeTemplate r;
    r.id = "forge_" + pieceId;
    r.pieceId = pieceId;
    r.setId = pieceId.substr(0, pieceId.find('_'));
    r.part = part;
    r.name = name;
    r.description = description;
    r.tierMaterials = std::move(materials);
    return r;
}

inline std::vector<ForgeRecipeTemplate> buildForgeRecipes() {
    // 24 条 = 6 套 × 4 部位；套间序 = lietian/gengjin/qingmu/xuanshui/lihuo/houtu，
    // 套内序 = HEAD/BODY/HANDS/FEET（均与 Kotlin allRecipes 声明序一致）
    const char* const kSetIds[6] = {"lietian", "gengjin", "qingmu",
                                    "xuanshui", "lihuo", "houtu"};
    const char* const kPartNames[4] = {"HEAD", "BODY", "HANDS", "FEET"};
    // 部件模板名（EquipmentDatabase.SetPieceTemplate.name 派生结果，逐字）
    const char* const kPieceNames[6][4] = {
        {"裂天罡煞·头冠", "裂天罡煞·重铠", "裂天罡煞·战手", "裂天罡煞·战靴"},
        {"庚金白虎·灵冠", "庚金白虎·法袍", "庚金白虎·灵护", "庚金白虎·云履"},
        {"青木长生·灵冠", "青木长生·法袍", "青木长生·灵护", "青木长生·云履"},
        {"玄水寒渊·灵冠", "玄水寒渊·法袍", "玄水寒渊·灵护", "玄水寒渊·云履"},
        {"离火焚天·灵冠", "离火焚天·法袍", "离火焚天·灵护", "离火焚天·云履"},
        {"厚土镇岳·灵冠", "厚土镇岳·法袍", "厚土镇岳·灵护", "厚土镇岳·云履"},
    };
    // 部件模板描述（EquipmentDatabase.SetPieceTemplate.description 派生结果，逐字）
    const char* const kPieceDescs[6][4] = {
        {"裂天罡煞套装头冠，罡煞之气护持识海", "裂天罡煞套装重铠，煞气凝甲坚不可摧",
         "裂天罡煞套装护手，罡风附刃裂石开碑", "裂天罡煞套装战靴，踏罡步斗势如奔雷"},
        {"庚金白虎套装灵冠，白虎金睛洞察秋毫", "庚金白虎套装法袍，金气织体刀兵不侵",
         "庚金白虎套装灵护，锐金凝爪裂金断玉", "庚金白虎套装云履，虎啸风生金戈疾行"},
        {"青木长生套装灵冠，青木灵韵清心明神", "青木长生套装法袍，生生不息缠枝为衣",
         "青木长生套装灵护，藤蔓缠腕生机盎然", "青木长生套装云履，踏叶而行轻若春风"},
        {"玄水寒渊套装灵冠，寒渊之息凝神静念", "玄水寒渊套装法袍，玄水环身百法不沾",
         "玄水寒渊套装灵护，寒潮覆掌冻结万机", "玄水寒渊套装云履，凌波微步踏水无痕"},
        {"离火焚天套装灵冠，离火真焰炼神涤魄", "离火焚天套装法袍，炎纹织体烈焰随身",
         "离火焚天套装灵护，火灵附掌焚尽八荒", "离火焚天套装云履，踏火而行燎原疾影"},
        {"厚土镇岳套装灵冠，厚土之德沉稳心神", "厚土镇岳套装法袍，山岳之甲岿然不动",
         "厚土镇岳套装灵护，镇岳之力撼地崩山", "厚土镇岳套装云履，踏地生根移山填谷"},
    };

    std::vector<ForgeRecipeTemplate> out;
    out.reserve(24);
    for (int s = 0; s < 6; ++s) {
        const std::string sid = kSetIds[s];
        out.push_back(forgeRecipe(sid + "_HEAD", kPartNames[0], kPieceNames[s][0],
                                  kPieceDescs[s][0], forgeHeadMaterials()));
        out.push_back(forgeRecipe(sid + "_BODY", kPartNames[1], kPieceNames[s][1],
                                  kPieceDescs[s][1], forgeBodyMaterials()));
        out.push_back(forgeRecipe(sid + "_HANDS", kPartNames[2], kPieceNames[s][2],
                                  kPieceDescs[s][2], forgeHandsMaterials()));
        out.push_back(forgeRecipe(sid + "_FEET", kPartNames[3], kPieceNames[s][3],
                                  kPieceDescs[s][3], forgeFeetMaterials()));
    }
    return out;
}

// ============================================================
// PillRecipe 生成（与 Kotlin PillRecipeDatabase 生成循环对应）
// ============================================================

/// TIER_DURATION（与 ForgeRecipeDatabase.TIER_DURATION 同源）
static constexpr int32_t kTierDuration[7] = {0, 3, 6, 12, 36, 72, 120};
/// TIER_SUCCESS_RATE
static constexpr double kTierSuccessRate[7] = {0.0, 0.75, 0.65, 0.60, 0.45, 0.35, 0.20};

/// 锻造时长按品阶档（Kotlin `ForgeRecipeDatabase.getDurationByTier` 同式：
/// `TIER_DURATION[tier] ?: 2`——越界不 coerce，直接回退 2 旬）
inline int32_t forgeDurationByTier(int32_t tier) {
    return tier >= 1 && tier <= 6 ? kTierDuration[tier] : 2;
}

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
    r.cultivationAdd = t.cultivationAdd;
    r.skillExpAdd = t.skillExpAdd;
    r.physicalAttackAdd = t.physicalAttackAdd;
    r.attackAdd = t.attackAdd;
    r.defenseAdd = t.defenseAdd;
    r.magicAttackAdd = t.magicAttackAdd;
    r.physicalDefenseAdd = t.physicalDefenseAdd;
    r.magicDefenseAdd = t.magicDefenseAdd;
    r.hpAdd = t.hpAdd;
    r.mpAdd = t.mpAdd;
    r.speedAdd = t.speedAdd;
    r.critRateAdd = t.critRateAdd;
    r.critEffectAdd = t.critEffectAdd;
    r.intelligenceAdd = t.intelligenceAdd;
    r.charmAdd = t.charmAdd;
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
    static constexpr const char* kStandardPillTypes[4] = {
        "cultivationSpeed", "skillExpSpeed", "cultivationAdd", "skillExpAdd"};
    // herbPatterns：与 Kotlin listOf(listOf(0,3), ...) 一致
    static constexpr int kHerbPatterns[4][2] = {
        {0, 3}, {1, 6}, {0, 7}, {5, 8}};

    for (int tier = 1; tier <= 6; ++tier) {
        const int duration = kTierDuration[tier];
        const double successRate = kTierSuccessRate[tier];
        for (int idx = 0; idx < 4; ++idx) {
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

/// 单基础属性功能配方（对应 Kotlin addFunctionalSingleRecipes）
inline void buildFunctionalSingleRecipes(std::vector<PillRecipeTemplate>& out, int tier) {
    const int duration = kTierDuration[tier];
    const double successRate = kTierSuccessRate[tier];
    // herbPatterns：与 Kotlin herbPatterns 一致（灵草槽位固定）
    static constexpr int kSingleBaseFuncPatterns[8][2] = {
        {1, 7}, {2, 8}, {5, 2}, {6, 3}, {7, 4}, {8, 5}, {0, 6}, {1, 7}};
    const int singleCount =
        static_cast<int>(sizeof(kSingleBaseAttrSpecs) / sizeof(kSingleBaseAttrSpecs[0]));
    for (int i = 0; i < singleCount; ++i) {
        const auto& cfg = kSingleBaseAttrSpecs[i];
        const std::map<std::string, int32_t> materials =
            herbMat(tier, {kSingleBaseFuncPatterns[i][0], kSingleBaseFuncPatterns[i][1]});
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
    // herbPatterns：与 Kotlin herbPatterns 一致（灵草槽位固定）
    static constexpr int kDualBaseFuncPatterns[5][2] = {
        {0, 2}, {2, 4}, {3, 5}, {4, 6}, {5, 7}};
    const int dualCount =
        static_cast<int>(sizeof(kDualBaseAttrSpecs) / sizeof(kDualBaseAttrSpecs[0]));
    for (int idx = 0; idx < dualCount; ++idx) {
        const auto& cfg = kDualBaseAttrSpecs[idx];
        const std::map<std::string, int32_t> materials =
            herbMat(tier, {kDualBaseFuncPatterns[idx][0], kDualBaseFuncPatterns[idx][1]});
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

/// 全部锻造配方（24 条 = 6 套 × 4 部位，顺序与 Kotlin allRecipes 一致）
///
/// B16/R6.2 数值外置：本表为**内联默认值兜底**（= detail::buildForgeRecipes()
/// 产出），与数据文件 `assets/data/game-data.json` 的 `db.forgeRecipes` 段同源
/// （中性源 scripts/data/recipe_db_sample.json 复刻同一 Kotlin 生成逻辑）。
/// 运行时由 `gamecore/data/data_inject.h` 初始化期一次性注入；注入前/失败时
/// 此处即为权威值（与数据文件默认值逐字段相等，data_store_test 锁定）。
inline std::vector<ForgeRecipeTemplate>& forgeRecipesMutable() {
    static std::vector<ForgeRecipeTemplate> kRecipes = detail::buildForgeRecipes();
    return kRecipes;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<ForgeRecipeTemplate>& forgeRecipes() {
    return forgeRecipesMutable();
}

/// 全部丹药配方（修炼 138 + 战斗 288 + 功能 234 = 660 条）
///
/// B16/R6.2：同 forgeRecipesMutable——兜底与 `db.pillRecipes` 段同源。
/// 注：条目的 `price` 是派生字段（数据文件不含该键），注入后由
/// `data_inject.h` 按 detail 同一构建公式回填（派生逻辑保持 C++ 侧）。
inline std::vector<PillRecipeTemplate>& pillRecipesMutable() {
    static std::vector<PillRecipeTemplate> kRecipes = detail::buildPillRecipes();
    return kRecipes;
}

/// 只读消费入口（外置后签名零变更）
inline const std::vector<PillRecipeTemplate>& pillRecipes() {
    return pillRecipesMutable();
}

/// 按 id 查询锻造配方（不存在返回空 optional）
inline std::optional<ForgeRecipeTemplate> forgeRecipeById(const std::string& id) {
    // 失效自检的索引快照（根治悬挂指针 UAF，见 index_snapshot.h）：向量被数据
    // 注入/测试复位整体替换时自动重建，注入后稳态零重建（指针稳定性契约不变）
    static detail::IdIndexSnapshot<ForgeRecipeTemplate> kIndex;
    const ForgeRecipeTemplate* r = kIndex.find(forgeRecipes(), id);
    if (r == nullptr) return std::nullopt;
    return *r;
}

/// 按 id 查询丹药配方（不存在返回空 optional）
inline std::optional<PillRecipeTemplate> pillRecipeById(const std::string& id) {
    // 失效自检的索引快照（根治悬挂指针 UAF，见 index_snapshot.h）：向量被数据
    // 注入/测试复位整体替换时自动重建，注入后稳态零重建（指针稳定性契约不变）
    static detail::IdIndexSnapshot<PillRecipeTemplate> kIndex;
    const PillRecipeTemplate* r = kIndex.find(pillRecipes(), id);
    if (r == nullptr) return std::nullopt;
    return *r;
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

/// 按 Kotlin ItemDatabase.allPills 模板序组织的丹药配方视图（值拷贝）：
/// 遍历 pillTemplates()（spec 生成序 = Kotlin allPills 序——grade 外层 ×
/// 丹名内层，如 引灵丹,悟法丹,养器丹 ×3 grade）按同 id 取 pillRecipes()
/// 配方条目（含 rarity/price/grade）。
///
/// 背景（2026 批收尾整链对拍实锤）：Kotlin 商人交易（getPillsByRarity）与
/// 收购（buildMerchantItemPools）池均遍历 **allPills.values（模板序）**；
/// C++ 原直接遍历 pillRecipes() 得到**配方生成序**（丹名外层 × grade 内层，
/// 如 引灵丹×3 grade 连续——buildCultivationStandardRecipes 的 idx×g 循环
/// 与 Kotlin 模板 g×丹 循环相反）→ 同 rarity 池内条目序与 Kotlin 相反 →
/// nextInt 选中错位。交易/收购池必须消费本视图而非 pillRecipes()。
inline const std::vector<PillRecipeTemplate>& pillRecipesInTemplateOrder() {
    static const std::vector<PillRecipeTemplate> kOrdered = [] {
        std::map<std::string, const PillRecipeTemplate*> byId;
        for (const auto& r : pillRecipes()) byId.emplace(r.id, &r);
        std::vector<PillRecipeTemplate> out;
        out.reserve(detail::pillTemplates().size());
        for (const auto& spec : detail::pillTemplates()) {
            const auto it = byId.find(spec.id);
            if (it != byId.end()) out.push_back(*it->second);
        }
        return out;
    }();
    return kOrdered;
}

}  // namespace gamecore::data

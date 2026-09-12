#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/exploration.h"

// ============================================================
// 世界关卡生成器
//
// 等价移植 Kotlin LevelGenerator 的**纯生成逻辑**：
//   - selectBeastRealm：年份锚点权重线性插值 + 加权随机（EXPLORATION 分区）
//   - generateWorldLevels：位置去重 + 类型判定（妖兽 80/85、洞府 5/85）+ 距离校验
//   - generateBeastLevel：妖兽最终属性预生成（层数倍率 + 随机方差，6 个月过期）
//   - generateCaveLevel：洞府名/守护者名/境界生成（6 个月过期）
//   - getCaveReward / getMaxRarity：洞府奖励配置与境界最高品阶
//
// 与 Kotlin 语义对齐要点：
//   - RNG 走 EXPLORATION 分区（rng.getRng(RngPartition::kExploration)）
//   - RNG 消费顺序与 Kotlin 逐位一致（对拍红线，见各函数注释）
//   - toInt() 截断；coerceIn/coerceAtLeast 显式实现
//   - 过期月换算：month+6 → year+(m-1)/12, (m-1)%12+1
//   - 境界：0=仙人 … 9=炼气（数值越小境界越高）
// ============================================================
namespace gamecore::system {

// ── 妖兽类型配置（Kotlin GameConfig.Beast.TYPES，8 种）─────────

struct BeastTypeConfig {
    std::string name;
    std::string prefix;
    double hpMod = 1.0;
    double atkMod = 1.0;
    double defMod = 1.0;
    double speedMod = 1.0;
    double lootBonus = 1.0;
};

inline const std::vector<BeastTypeConfig>& beastTypeConfigs() {
    static const std::vector<BeastTypeConfig> kTypes = {
        {"虎妖", "狂暴", 1.3, 1.4, 0.7, 1.0, 1.1},
        {"狼妖", "迅捷", 0.6, 1.2, 0.6, 1.5, 1.0},
        {"蛇妖", "剧毒", 0.7, 1.5, 0.5, 1.1, 1.2},
        {"熊妖", "铁甲", 1.5, 0.5, 1.4, 0.5, 1.1},
        {"鹰妖", "神风", 0.5, 1.3, 0.5, 1.6, 1.3},
        {"狐妖", "幻魅", 0.7, 1.0, 0.7, 1.4, 1.4},
        {"龙妖", "远古", 1.2, 1.3, 1.1, 1.0, 1.5},
        {"龟妖", "玄甲", 1.6, 0.4, 1.5, 0.4, 1.0},
    };
    return kTypes;
}

/// 按索引取妖兽类型（越界回退第 0 种，Kotlin getOrElse { TYPES[0] }）
inline const BeastTypeConfig& beastTypeConfig(int32_t index) {
    const auto& types = beastTypeConfigs();
    if (index < 0 || index >= static_cast<int32_t>(types.size())) {
        return types.front();
    }
    return types[static_cast<std::size_t>(index)];
}

// ── 妖兽境界属性表（Kotlin GameConfig.Beast.REALM_STATS）────────

struct BeastRealmStats {
    int32_t hp = 0;
    int32_t mp = 0;
    int32_t attack = 0;
    int32_t defense = 0;
    int32_t speed = 0;
};

inline const std::vector<BeastRealmStats>& beastRealmStatsTable() {
    static const std::vector<BeastRealmStats> kStats = {
        // index 0 = realm 9（炼气）… index 9 = realm 0（仙人），与 Kotlin map 一致
        {339, 130, 31, 22, 16},        // 9 炼气
        {847, 326, 76, 57, 41},        // 8 筑基
        {2201, 847, 195, 148, 104},    // 7 金丹
        {5756, 2214, 514, 384, 272},   // 6 元婴
        {15236, 5860, 1359, 1016, 722}, // 5 化神
        {37243, 14324, 3324, 2483, 1763}, // 4 炼虚
        {88029, 33858, 7855, 5869, 4167}, // 3 合体
        {196374, 75528, 17523, 13091, 9296}, // 2 大乘
        {406249, 156265, 36254, 27087, 19233}, // 1 渡劫
        {846353, 325553, 75528, 56429, 40068}, // 0 仙人
    };
    return kStats;
}

/// 按境界取妖兽属性（Kotlin getRealmStats：未知回退炼气 9）
inline BeastRealmStats beastRealmStats(int32_t realm) {
    const int32_t idx = std::max(0, std::min(9 - realm, 9));
    return beastRealmStatsTable()[static_cast<std::size_t>(idx)];
}

// ── 守护者/洞穴名称表（Kotlin LevelGenerator companion）────────

inline const std::vector<std::string>& guardianPrefixes() {
    static const std::vector<std::string> kList = {
        "碧眼", "赤焰", "九幽", "玄冰", "紫电", "青冥", "金翅", "银鬃", "黑水", "白虹",
        "幽冥", "焚天", "冰魄", "龙吟", "凤鸣", "裂空", "噬魂", "镇岳", "吞天", "撼地",
    };
    return kList;
}

inline const std::vector<std::string>& guardianSuffixes() {
    static const std::vector<std::string> kList = {
        "金蟾", "玄龟", "魔蛟", "灵蟒", "妖鹏", "神猿", "古蜥", "鬼蝠", "仙鹤", "石犀",
        "铜虎", "银狼", "铁熊", "血鹰", "幻狐", "雷龙", "寒蛇",
    };
    return kList;
}

inline const std::vector<std::string>& caveNamePrefixes() {
    static const std::vector<std::string> kList = {
        "玄天", "紫霄", "太虚", "青云", "幽冥", "焚天", "冰魄", "龙吟", "凤鸣",
    };
    return kList;
}

inline const std::vector<std::string>& caveNameSuffixes() {
    static const std::vector<std::string> kList = {
        "洞府", "秘洞", "灵窟", "仙窟", "古洞",
    };
    return kList;
}

// ── 妖兽境界年份锚点（Kotlin BEAST_REALM_ANCHORS）──────────────

struct BeastRealmAnchor {
    int32_t year = 1;
    std::vector<int32_t> weights;
};

inline const std::vector<BeastRealmAnchor>& beastRealmAnchors() {
    static const std::vector<BeastRealmAnchor> kAnchors = {
        {1, {1, 3, 8, 15, 30, 60, 120, 220, 320, 400}},
        {500, {40, 60, 90, 130, 170, 180, 150, 100, 50, 20}},
        {2000, {160, 170, 150, 120, 90, 60, 30, 15, 6, 2}},
    };
    return kAnchors;
}

/// 洞府奖励配置（Kotlin LevelGenerator.getCaveReward）
struct CaveRewardConfig {
    double baseSpiritStones = 20000.0;
    int32_t rarityMin = 1;
    int32_t rarityMax = 2;
};

inline CaveRewardConfig caveReward(int32_t realm) {
    switch (realm) {
        case 5: return {20000.0, 1, 2};      // 化神: 灵品~宝品
        case 4: return {100000.0, 2, 3};     // 炼虚: 宝品~玄品
        case 3: return {300000.0, 2, 5};     // 合体: 宝品~地品
        case 2: return {700000.0, 3, 6};     // 大乘: 玄品~天品
        case 1: return {1500000.0, 5, 6};    // 渡劫: 地品~天品
        default: return {20000.0, 1, 2};
    }
}

/// 境界最高可出品阶（Kotlin GameConfig.Realm.getMaxRarity）
inline int32_t maxRarityForRealm(int32_t realm) {
    switch (realm) {
        case 9: case 8: return 1;
        case 7: return 2;
        case 6: return 3;
        case 5: return 4;
        case 4: case 3: return 5;
        case 2: case 1: case 0: return 6;
        default: return 1;
    }
}

/// 境界名（Kotlin GameConfig.Realm.getName）
inline std::string realmName(int32_t realm) {
    return disciple::realmConfig(realm).name;
}

// ── selectBeastRealm：年份加权随机（Kotlin LevelGenerator）───────

/// 按年份在锚点间线性插值权重，加权随机选取妖兽境界；playerAvgRealm
/// 非空时 clamp 到 [avg-1, avg+2] 范围。
/// RNG 消费：恰好 1×nextDouble（加权随机）。
inline int32_t selectBeastRealm(rng::RngManager& rng, int32_t year,
                                const int32_t* playerAvgRealm = nullptr) {
    const int32_t clampedYear = std::max(year, 1);
    const auto& anchors = beastRealmAnchors();

    // lower = 最后一个 year <= clampedYear 的锚点
    std::size_t lowerIdx = 0;
    for (std::size_t i = 0; i < anchors.size(); ++i) {
        if (anchors[i].year <= clampedYear) lowerIdx = i;
    }
    // upper = 第一个 year >= clampedYear 的锚点（无则取 lower）
    std::size_t upperIdx = lowerIdx;
    for (std::size_t i = 0; i < anchors.size(); ++i) {
        if (anchors[i].year >= clampedYear) { upperIdx = i; break; }
    }
    const auto& lower = anchors[lowerIdx];
    const auto& upper = anchors[upperIdx];

    std::vector<double> weights;
    weights.reserve(lower.weights.size());
    if (lowerIdx == upperIdx) {
        for (int32_t w : lower.weights) weights.push_back(static_cast<double>(w));
    } else {
        const double fraction = static_cast<double>(clampedYear - lower.year) /
                                static_cast<double>(upper.year - lower.year);
        for (std::size_t i = 0; i < lower.weights.size(); ++i) {
            const double lo = static_cast<double>(lower.weights[i]);
            const double hi = static_cast<double>(upper.weights[i]);
            weights.push_back(lo + (hi - lo) * fraction);
        }
    }

    double total = 0.0;
    for (double w : weights) total += w;
    double roll = rng.getRng(rng::RngPartition::kExploration).nextDouble() * total;
    int32_t selected = 9;  // fallback
    for (std::size_t i = 0; i < weights.size(); ++i) {
        roll -= weights[i];
        if (roll <= 0.0) { selected = static_cast<int32_t>(i); break; }
    }

    if (playerAvgRealm != nullptr) {
        const int32_t minRealm = std::max(0, std::min(*playerAvgRealm - 1, 9));
        const int32_t maxRealm = std::max(0, std::min(*playerAvgRealm + 2, 9));
        selected = std::max(minRealm, std::min(selected, maxRealm));
    }
    return selected;
}

// ── 世界关卡生成常量（Kotlin GameConfig.WorldMap）───────────────

/// 洞府与宗门最小距离（CAVE_MIN_SECT_DISTANCE）
constexpr double kCaveMinSectDistance = 28.0;
/// 关卡间最小距离（LEVEL_MIN_DISTANCE）
constexpr double kLevelMinDistance = 20.0;
/// 妖兽在关卡中占比分母（Kotlin generateWorldLevels 的 5/85 判定）
constexpr double kCaveSpawnRatio = 5.0 / 85.0;
/// 位置重试上限（Kotlin generateWorldLevels attempts < 5000）
constexpr int32_t kLevelPositionMaxAttempts = 5000;
/// 新关卡默认上限（Kotlin maxNewLevels = 6）
constexpr int32_t kDefaultMaxNewLevels = 6;
/// 妖兽类型数量（Kotlin rng.nextInt(8)）
constexpr int32_t kBeastTypeCount = 8;
/// 妖兽层数上限（Kotlin rng.nextInt(9)+1）
constexpr int32_t kBeastLayerMax = 9;
/// 妖兽数量上限（Kotlin rng.nextInt(13)+1）
constexpr int32_t kBeastCountMax = 13;
/// 妖兽/洞府持续月数（Kotlin currentMonth + 6）
constexpr int32_t kLevelDurationMonths = 6;
/// 妖兽属性方差下界/跨度（Kotlin -0.2 + nextDouble()*0.4）
constexpr double kBeastVarianceMin = -0.2;
constexpr double kBeastVarianceSpan = 0.4;
/// 层数倍率步长（Kotlin 1.0 + (realmLayer-1)*0.1）
constexpr double kLayerMultiplierStep = 0.1;
/// 洞府境界档位数量（Kotlin rng.nextInt(5)）
constexpr int32_t kCaveRealmVariantCount = 5;
/// 洞府图像变体数量（Kotlin rng.nextInt(3)）
constexpr int32_t kCaveImageVariantCount = 3;
/// 洞府守护者数量（Kotlin count = 2）
constexpr int32_t kCaveGuardianCount = 2;
/// Int.MAX_VALUE（Kotlin Int.MAX_VALUE 防溢出）
constexpr int32_t kIntMax = 2147483647;

/// 位置校验（Kotlin isValidPosition / isTooClose）

inline bool isTooClose(int32_t x, int32_t y, float targetX, float targetY,
                       double minDist) {
    const double dx = static_cast<double>(x) - static_cast<double>(targetX);
    const double dy = static_cast<double>(y) - static_cast<double>(targetY);
    return std::sqrt(dx * dx + dy * dy) < minDist;
}

inline bool isValidLevelPosition(
    int32_t x, int32_t y,
    const std::vector<std::pair<int32_t, int32_t>>& usedPositions,
    const std::vector<state::WorldSect>& sects,
    const std::vector<state::WorldLevel>& existingLevels) {
    const auto pos = std::make_pair(x, y);
    for (const auto& p : usedPositions) {
        if (p == pos) return false;
    }
    for (const auto& sect : sects) {
        if (isTooClose(x, y, sect.x, sect.y, kCaveMinSectDistance)) return false;
    }
    for (const auto& level : existingLevels) {
        if (isTooClose(x, y, level.x, level.y, kLevelMinDistance)) return false;
    }
    return true;
}

// ── generateBeastLevel（Kotlin LevelGenerator.generateBeastLevel）─
// RNG 消费顺序（确定性关键，顺序固定）：
//   1×nextInt(8) 妖兽类型 → selectBeastRealm（1×nextDouble）→
//   1×nextInt(9)+1 层数 → 1×nextInt(13)+1 数量 → 4×nextDouble 方差

inline state::WorldLevel generateBeastLevel(
    rng::RngManager& rng, int32_t currentYear, int32_t currentMonth,
    int32_t x, int32_t y, const int32_t* playerAvgRealm = nullptr) {
    auto& r = rng.getRng(rng::RngPartition::kExploration);
    const int32_t beastTypeIndex = r.nextInt(kBeastTypeCount);
    const auto& beastConfig = beastTypeConfig(beastTypeIndex);
    const int32_t realm = selectBeastRealm(rng, currentYear, playerAvgRealm);
    const int32_t realmLayer = r.nextInt(kBeastLayerMax) + 1;
    const int32_t count = r.nextInt(kBeastCountMax) + 1;

    // 妖兽最终属性预生成（含随机方差，与 Kotlin 同公式）
    const double layerMult = 1.0 + (realmLayer - 1) * kLayerMultiplierStep;
    const auto stats = beastRealmStats(realm);
    const double hpVariance = kBeastVarianceMin + r.nextDouble() * kBeastVarianceSpan;
    const double atkVariance = kBeastVarianceMin + r.nextDouble() * kBeastVarianceSpan;
    const double defVariance = kBeastVarianceMin + r.nextDouble() * kBeastVarianceSpan;
    const double speedVariance = kBeastVarianceMin + r.nextDouble() * kBeastVarianceSpan;

    const int32_t maxHp = static_cast<int32_t>(
        stats.hp * layerMult * (beastConfig.hpMod + hpVariance));
    const int32_t maxMp = static_cast<int32_t>(
        stats.mp * layerMult * (beastConfig.hpMod + hpVariance));
    const int32_t atk = static_cast<int32_t>(
        stats.attack * layerMult * (beastConfig.atkMod + atkVariance));
    const int32_t def = static_cast<int32_t>(
        stats.defense * layerMult * (beastConfig.defMod + defVariance));
    const int32_t speed = static_cast<int32_t>(
        stats.speed * layerMult * (beastConfig.speedMod + speedVariance));

    // 持续 6 个月
    const int32_t beastNewMonth = currentMonth + kLevelDurationMonths;
    const int32_t beastExpiryYear = currentYear + (beastNewMonth - 1) / 12;
    const int32_t beastExpiryMonth = (beastNewMonth - 1) % 12 + 1;

    state::WorldLevel level;
    level.type = "BEAST";
    level.beastType = beastTypeIndex;
    level.realm = realm;
    level.realmLayer = realmLayer;
    level.beastName = beastConfig.prefix + beastConfig.name;
    level.x = static_cast<float>(x);
    level.y = static_cast<float>(y);
    level.spawnYear = currentYear;
    level.spawnMonth = currentMonth;
    level.expiryYear = beastExpiryYear;
    level.expiryMonth = beastExpiryMonth;
    level.count = count;
    level.beastMaxHp = maxHp;
    level.beastMaxMp = maxMp;
    level.beastPhysicalAttack = atk;
    level.beastMagicAttack = atk;
    level.beastPhysicalDefense = def;
    level.beastMagicDefense = def;
    level.beastSpeed = speed;
    return level;
}

// ── generateCaveLevel（Kotlin LevelGenerator.generateCaveLevel）──
// RNG 消费顺序：1×nextInt(5) 洞府境界 → 1×nextInt(9)+1 层数 →
// 1×nextInt(3) 图像 → 1×nextInt(前缀数) → 1×nextInt(后缀数) →
// 1×nextInt(洞府前缀数) → 1×nextInt(洞府后缀数)

inline state::WorldLevel generateCaveLevel(
    rng::RngManager& rng, int32_t currentYear, int32_t currentMonth,
    int32_t x, int32_t y) {
    auto& r = rng.getRng(rng::RngPartition::kExploration);
    int32_t caveRealm = 5;  // 默认化神（Kotlin when 的 else 分支）
    switch (r.nextInt(kCaveRealmVariantCount)) {
        case 0: caveRealm = 5; break;  // 化神
        case 1: caveRealm = 4; break;  // 炼虚
        case 2: caveRealm = 3; break;  // 合体
        case 3: caveRealm = 2; break;  // 大乘
        case 4: caveRealm = 1; break;  // 渡劫
        default: caveRealm = 5; break;
    }
    const int32_t realmLayer = r.nextInt(kBeastLayerMax) + 1;
    const int32_t caveImageIndex = r.nextInt(kCaveImageVariantCount);
    const auto& prefixes = guardianPrefixes();
    const auto& suffixes = guardianSuffixes();
    const std::string guardianName =
        prefixes[static_cast<std::size_t>(r.nextInt(
            static_cast<int32_t>(prefixes.size())))] +
        suffixes[static_cast<std::size_t>(r.nextInt(
            static_cast<int32_t>(suffixes.size())))];
    const std::string realmNameStr = realmName(caveRealm);
    const auto& cavePre = caveNamePrefixes();
    const auto& caveSuf = caveNameSuffixes();
    const std::string caveName =
        cavePre[static_cast<std::size_t>(r.nextInt(
            static_cast<int32_t>(cavePre.size())))] +
        realmNameStr +
        caveSuf[static_cast<std::size_t>(r.nextInt(
            static_cast<int32_t>(caveSuf.size())))];

    const int32_t caveNewMonth = currentMonth + kLevelDurationMonths;
    const int32_t caveExpiryYear = currentYear + (caveNewMonth - 1) / 12;
    const int32_t caveExpiryMonth = (caveNewMonth - 1) % 12 + 1;

    state::WorldLevel level;
    level.type = "CAVE";
    level.realm = caveRealm;
    level.realmLayer = realmLayer;
    level.guardianName = guardianName;
    level.caveName = caveName;
    level.x = static_cast<float>(x);
    level.y = static_cast<float>(y);
    level.spawnYear = currentYear;
    level.spawnMonth = currentMonth;
    level.expiryYear = caveExpiryYear;
    level.expiryMonth = caveExpiryMonth;
    level.count = kCaveGuardianCount;
    level.caveImageIndex = caveImageIndex;
    return level;
}

// ── generateWorldLevels（Kotlin LevelGenerator.generateWorldLevels）─
// RNG 消费顺序：
//   1×nextInt(maxNewLevels) 新关卡数量（maxNewLevels<=0 → 0）
//   循环内：1×nextInt(宽) x → 1×nextInt(高) y → 1×nextDouble 类型判定
//   失败重试（位置冲突）不消费类型判定 RNG（Kotlin continue 语义）

struct GeneratedLevelsResult {
    std::vector<state::WorldLevel> levels;
    bool generated = false;  // 是否实际生成（数量 > 0）
};

inline GeneratedLevelsResult generateWorldLevels(
    rng::RngManager& rng, const std::vector<state::WorldSect>& existingSects,
    int32_t currentYear, int32_t currentMonth,
    const std::vector<state::WorldLevel>& existingLevels,
    int32_t maxNewLevels, const int32_t* playerAvgRealm = nullptr) {
    GeneratedLevelsResult out;
    if (maxNewLevels <= 0) return out;

    std::vector<std::pair<int32_t, int32_t>> usedPositions;
    usedPositions.reserve(existingSects.size() + existingLevels.size());
    for (const auto& sect : existingSects) {
        usedPositions.emplace_back(static_cast<int32_t>(sect.x),
                                   static_cast<int32_t>(sect.y));
    }
    for (const auto& level : existingLevels) {
        usedPositions.emplace_back(static_cast<int32_t>(level.x),
                                   static_cast<int32_t>(level.y));
    }

    auto& r = rng.getRng(rng::RngPartition::kExploration);
    // Kotlin: safeBound = maxNewLevels >= Int.MAX_VALUE-1 ? Int.MAX_VALUE : maxNewLevels+1
    // newLevelCount = rng.nextInt(safeBound - 1) + 1
    const int32_t safeBound = (maxNewLevels >= kIntMax - 1) ? kIntMax : maxNewLevels + 1;
    const int32_t newLevelCount = r.nextInt(safeBound - 1) + 1;

    int32_t attempts = 0;
    while (static_cast<int32_t>(out.levels.size()) < newLevelCount &&
           attempts < kLevelPositionMaxAttempts) {
        ++attempts;
        // Kotlin: rng.nextInt(MAP_WIDTH - BORDER_PADDING*2) + BORDER_PADDING
        const int32_t mapWidth = static_cast<int32_t>(kMapWidth);
        const int32_t mapHeight = static_cast<int32_t>(kMapHeight);
        const int32_t border = static_cast<int32_t>(kBorderPadding);
        const int32_t x = r.nextInt(mapWidth - border * 2) + border;
        const int32_t y = r.nextInt(mapHeight - border * 2) + border;

        std::vector<state::WorldLevel> allLevels = existingLevels;
        for (const auto& l : out.levels) allLevels.push_back(l);
        if (!isValidLevelPosition(x, y, usedPositions, existingSects, allLevels)) {
            continue;
        }

        const bool isCave = r.nextDouble() < kCaveSpawnRatio;
        state::WorldLevel level = isCave
            ? generateCaveLevel(rng, currentYear, currentMonth, x, y)
            : generateBeastLevel(rng, currentYear, currentMonth, x, y, playerAvgRealm);
        out.levels.push_back(level);
        usedPositions.emplace_back(x, y);
    }
    out.generated = !out.levels.empty();
    return out;
}

}  // namespace gamecore::system

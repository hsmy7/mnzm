#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/system/level_generator.h"

// ============================================================
// 远古秘境状态机核心（Kotlin→C++ 迁移计划 v2 阶段 4 / 批 4-3）
//
// 等价移植 Kotlin 秘境确定性纯逻辑：
//   - SecretRealmEventGenerator：playerAvgRealm / rollBeastRealm /
//     generateBeastEvent / generateRestAreaEvent / generateRuinsEvent /
//     generateDirectionEvent / generateAISectEncounterEvent / rollNextEvent /
//     generateRuinsTreasure / buildBeastPreGenStats / rollBeastLoot
//   - SecretRealmRuinsResolver：resolveRuinsExplore / resolveRuinsSearch /
//     resolveRuinsResult（判定 + 结果文本 + 方向事件）
//   - SecretRealmBattleHelper.applyLootLoss：丢失物品比例 + 洗牌选取
//   - SecretRealmAIProcessor.processMonthlyAiTeams：AI 队伍派遣
//   - SecretRealmService：findFreePosition / calculateNewStamina /
//     processYearlySpawn 冷却判据 + 精灵变体随机
//
// 与 Kotlin 语义对齐要点：
//   - 全部随机走 SECRET_REALM 分区（rng::RngPartition::kSecretRealm），RNG 消费
//     顺序与 Kotlin 逐位一致（对拍红线，见各函数注释）。
//   - toInt() 截断；coerceIn/coerceAtLeast 显式实现（Long 中间量防回绕）。
//   - 境界：0=仙人 … 9=炼气（数值越小境界越高）。
//   - shuffled(random) = kotlin.collections Fisher-Yates（从后往前，
//     nextInt(i+1)，Lemire 无偏采样）——applyLootLoss 索引洗牌同算法。
//   - 已知边界（保留 Kotlin）：
//       · 战斗执行（BattleSystem 完整模拟器）——C++ battle.h 仅公式
//       · 秘宝模板实例化（EquipmentDatabase.createFromTemplate 等注册表）
//         ——generateRuinsTreasure 的候选模板列表由调用方（Kotlin 转发层）
//         从真实数据库填充后传入（C++ 只做 RNG 消费 + 选取，零数据库依赖）
//       · UUID（java.util.UUID）——AI 队伍/秘境/秘宝实例 id 由 Kotlin 生成
// ============================================================
namespace gamecore::system {

// ── SecretRealm 配置（Kotlin GameConfig.SecretRealm 同源）──────
namespace secret_realm_cfg {
inline constexpr int32_t kCooldownYears = 50;            // 每 50 年开启一次
inline constexpr int32_t kRealmMin = 0;                  // 仙人
inline constexpr int32_t kRealmMax = 9;                  // 炼气
inline constexpr int32_t kBeastLayerVariantCount = 9;    // 妖兽层数档位 1..9
inline constexpr int32_t kSpriteVariantCount = 3;        // 秘境精灵图变体
inline constexpr int32_t kPositionAttempts = 100;        // 位置随机尝试次数
inline constexpr int32_t kFallbackScanStep = 8;          // 兜底扫描步长
inline constexpr int32_t kSectClearance = 20;            // 与宗门安全距离余量
inline constexpr int32_t kStaminaMax = 20;               // 体力上限
inline constexpr int32_t kStaminaCostPerChoice = 1;      // 每选一个选项扣 1
inline constexpr int32_t kTeamSize = 4;                  // 探索队伍人数
inline constexpr int32_t kBeastCountMin = 1;
inline constexpr int32_t kBeastCountMax = 6;
inline constexpr double kAmbushBeastHpReduction = 0.10;  // 偷袭成功 -10% 血量
inline constexpr double kFleeDetectChance = 0.30;        // 远离被发现的概率
inline constexpr double kAmbushDetectChance = 0.50;      // 偷袭被发现的概率
inline constexpr double kRestAreaChance = 0.30;
inline constexpr double kRuinsChance = 0.20;
inline constexpr double kRuinsTreasureChance = 0.50;
inline constexpr int32_t kSimpleSearchCountMin = 1;
inline constexpr int32_t kSimpleSearchCountMax = 5;
inline constexpr int32_t kCarefulSearchCountMin = 2;
inline constexpr int32_t kCarefulSearchCountMax = 7;
inline constexpr int32_t kSimpleSearchRarityMin = 2;
inline constexpr int32_t kSimpleSearchRarityMax = 3;
inline constexpr int32_t kCarefulSearchRarityMin = 2;
inline constexpr int32_t kCarefulSearchRarityMax = 4;
inline constexpr int32_t kCarefulSearchStaminaCost = 2;
inline constexpr double kRestRecoveryRatio = 0.40;       // 休整恢复 40% 最大生命
inline constexpr double kLootLossMin = 0.20;
inline constexpr double kLootLossMax = 0.45;
inline constexpr int32_t kAiTeamSize = 4;                // AI 宗门队伍人数
inline constexpr int32_t kOpenYears = 5;                 // 秘境现世存在年数
inline constexpr double kAiEncounterChance = 0.15;
inline constexpr int32_t kRuinPickMaxAttempts = 5;       // 秘宝补生成上限倍数
}  // namespace secret_realm_cfg

// ── 事件类型名（SecretRealmEventType.name）────────────────────
namespace secret_realm_type {
inline constexpr const char* kBeastEncounter = "BEAST_ENCOUNTER";
inline constexpr const char* kRestArea = "REST_AREA";
inline constexpr const char* kRuinExplore = "RUIN_EXPLORE";
inline constexpr const char* kRuinResult = "RUIN_RESULT";
inline constexpr const char* kDirectionChoice = "DIRECTION_CHOICE";
inline constexpr const char* kAiSectEncounter = "AI_SECT_ENCOUNTER";
}  // namespace secret_realm_type

// ── 妖兽预生成属性（Kotlin BattleSystem.BeastPreGenStats）─────
struct SecretRealmBeastPreGenStats {
    int32_t maxHp = 0;
    int32_t maxMp = 0;
    int32_t physicalAttack = 0;
    int32_t magicAttack = 0;
    int32_t physicalDefense = 0;
    int32_t magicDefense = 0;
    int32_t speed = 0;
    int32_t realmLayer = 1;
};

/// 秘境选择结算载体（Kotlin SecretRealmBeastChoiceResolution 精简版；
/// 战斗字段 enteredCombat/combatLog/victory/deadIds 属战斗执行边界，Kotlin 保留）
struct SecretRealmResolution {
    std::string resultText;
    state::SecretRealmBackpack backpack;
    std::vector<state::SecretRealmMemberState> members;
    state::SecretRealmEventParams params;
    state::SecretRealmEventRecord nextEvent;
};

/// 丢失物品结算（Kotlin LootLossResult）
struct SecretRealmLootLossResult {
    state::SecretRealmBackpack backpack;
    int32_t lostItemCount = 0;
    int64_t lostSpiritStones = 0;
};

/// 秘宝模板候选（Kotlin 数据库 getByRarity → (id, name) 列表；RNG 选取用）
using SecretRealmTemplatePair = std::pair<std::string, std::string>;
using SecretRealmRarityCandidates = std::map<int32_t, std::vector<SecretRealmTemplatePair>>;
using SecretRealmTypeCandidates = std::map<std::string, SecretRealmRarityCandidates>;

// ── 存活成员平均境界（Kotlin playerAvgRealm）──────────────────
// 全灭（无存活成员）取 REALM_MAX；average().toInt() 截断。
inline int32_t secretRealmPlayerAvgRealm(
    const std::vector<state::SecretRealmMemberState>& members) {
    int64_t sum = 0;
    int32_t count = 0;
    for (const auto& m : members) {
        if (!m.isDead) {
            sum += m.realm;
            ++count;
        }
    }
    if (count == 0) return secret_realm_cfg::kRealmMax;
    return static_cast<int32_t>(sum / count);
}

// ── 秘境妖兽境界：avg-1..avg+2，clamp 0..9（Kotlin rollBeastRealm）──
// RNG 消费：1×nextInt(max-min+1)。
inline int32_t rollSecretRealmBeastRealm(rng::RngManager& rng, int32_t playerAvgRealm) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const int32_t minRealm = std::clamp(playerAvgRealm - 1, secret_realm_cfg::kRealmMin,
                                        secret_realm_cfg::kRealmMax);
    const int32_t maxRealm = std::clamp(playerAvgRealm + 2, secret_realm_cfg::kRealmMin,
                                        secret_realm_cfg::kRealmMax);
    return minRealm + sr.nextInt(maxRealm - minRealm + 1);
}

// ── 生成"遭遇妖兽"事件（Kotlin generateBeastEvent）─────────────
// RNG 消费顺序（确定性关键，不可调换）：
//   1×nextInt(8 类型) → rollBeastRealm(1×nextInt) → 1×nextInt(9 层数)
//   → 1×nextInt(6 数量)
inline state::SecretRealmEventRecord generateSecretRealmBeastEvent(rng::RngManager& rng,
                                                                   int32_t playerAvgRealm) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const auto& types = beastTypeConfigs();
    const auto& config = types[static_cast<std::size_t>(
        sr.nextInt(static_cast<int32_t>(types.size())))];
    const int32_t realm = rollSecretRealmBeastRealm(rng, playerAvgRealm);
    const int32_t layer = 1 + sr.nextInt(secret_realm_cfg::kBeastLayerVariantCount);
    const int32_t count = secret_realm_cfg::kBeastCountMin +
        sr.nextInt(secret_realm_cfg::kBeastCountMax - secret_realm_cfg::kBeastCountMin + 1);
    const std::string beastName = config.prefix + config.name;
    const std::string realmNameStr = realmName(realm);

    state::SecretRealmEventRecord rec;
    rec.eventType = secret_realm_type::kBeastEncounter;
    rec.title = "遭遇妖兽";
    rec.description = "途中遭遇妖兽！" + beastName + " × " + std::to_string(count) +
                      "，境界：" + realmNameStr;
    rec.options = {
        {"远离妖兽", "小心避让，有一定概率被妖兽察觉"},
        {"发起战斗", "与妖兽正面交锋"},
        {"尝试偷袭", "伺机偷袭，成功则妖兽血量削减一成"},
    };
    rec.params.beastTypeName = config.name;
    rec.params.beastRealm = realm;
    rec.params.beastLayer = layer;
    rec.params.beastCount = count;
    return rec;
}

// ── 生成"平坦空地"事件（Kotlin generateRestAreaEvent；无随机）──
inline state::SecretRealmEventRecord generateSecretRealmRestAreaEvent() {
    state::SecretRealmEventRecord rec;
    rec.eventType = secret_realm_type::kRestArea;
    rec.title = "发现空地";
    rec.description = "发现一处平坦空地";
    rec.options = {
        {"原地休整", "所有弟子恢复40%状态"},
        {"继续前进", "不做停留，继续探索"},
    };
    return rec;
}

// ── 生成"发现遗迹"事件（Kotlin generateRuinsEvent；无随机）────
inline state::SecretRealmEventRecord generateSecretRealmRuinsEvent() {
    state::SecretRealmEventRecord rec;
    rec.eventType = secret_realm_type::kRuinExplore;
    rec.title = "发现遗迹";
    rec.description = "发现未知遗迹可能存在未知宝物";
    rec.options = {
        {"直接离开", "不进入遗迹，继续探索"},
        {"简单搜寻", "简单搜寻一番，可能有所发现"},
        {"仔细搜寻", "仔细搜寻一番，消耗更多体力但收获更丰",
         secret_realm_cfg::kCarefulSearchStaminaCost},
    };
    return rec;
}

// ── 生成"探索方向"事件（Kotlin generateDirectionEvent；无随机）──
inline state::SecretRealmEventRecord generateSecretRealmDirectionEvent(
    const std::string& resultText) {
    state::SecretRealmEventRecord rec;
    rec.eventType = secret_realm_type::kDirectionChoice;
    rec.title = "探索方向";
    // 空结果文本防御：不产生" ，请选择探索方向"前导逗号
    rec.description = resultText.empty() ? "请选择探索方向"
                                         : resultText + "，请选择探索方向";
    rec.options = {
        {"向左走", ""},
        {"走中间", ""},
        {"向右走", ""},
    };
    return rec;
}

// ── 生成"遭遇 AI 宗门探索队伍"事件（Kotlin generateAISectEncounterEvent）──
// RNG 消费：1×nextInt(teams.size) 选队伍。
inline state::SecretRealmEventRecord generateSecretRealmAiEncounterEvent(
    rng::RngManager& rng, const std::vector<state::SecretRealmAITeam>& teams) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const auto& team = teams[static_cast<std::size_t>(
        sr.nextInt(static_cast<int32_t>(teams.size())))];
    state::SecretRealmEventRecord rec;
    rec.eventType = secret_realm_type::kAiSectEncounter;
    rec.title = "遭遇" + team.sectName + "探索队伍";
    rec.description = "前方发现" + team.sectName + "的探索队伍，狭路相逢";
    rec.options = {
        {"向左避让", "悄然绕行，必能避开对方"},
        {"与之交战", "与对方的探索队伍正面交锋"},
        {"向右避让", "悄然绕行，必能避开对方"},
    };
    rec.params.aiSectId = team.sectId;
    rec.params.aiSectName = team.sectName;
    rec.params.aiSectLevel = team.sectLevel;
    rec.params.aiMembers = team.members;
    return rec;
}

// ── 方向选择后的下一事件分派（Kotlin rollNextEvent）────────────
// 一次 nextDouble() 分段判定：<0.30 空地；[0.30,0.50) 遗迹；
// [0.50,0.65) AI 遭遇（队伍池空回退妖兽事件，不额外消费 RNG）；
// 其余妖兽事件。仍只消费一次 nextDouble()（读档确定性不变）。
inline state::SecretRealmEventRecord rollSecretRealmNextEvent(
    rng::RngManager& rng, int32_t playerAvgRealm,
    const std::vector<state::SecretRealmAITeam>& aiTeams) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const double roll = sr.nextDouble();
    if (roll < secret_realm_cfg::kRestAreaChance) {
        return generateSecretRealmRestAreaEvent();
    }
    if (roll < secret_realm_cfg::kRestAreaChance + secret_realm_cfg::kRuinsChance) {
        return generateSecretRealmRuinsEvent();
    }
    if (roll < secret_realm_cfg::kRestAreaChance + secret_realm_cfg::kRuinsChance +
                   secret_realm_cfg::kAiEncounterChance) {
        if (aiTeams.empty()) {
            return generateSecretRealmBeastEvent(rng, playerAvgRealm);  // 空池回退妖兽
        }
        return generateSecretRealmAiEncounterEvent(rng, aiTeams);
    }
    return generateSecretRealmBeastEvent(rng, playerAvgRealm);
}

// ── 妖兽最终属性预生成（Kotlin buildBeastPreGenStats）──────────
// RNG 消费顺序：4×nextDouble（hp/atk/def/speed 方差，均为 -0.2 + d*0.4）。
inline SecretRealmBeastPreGenStats buildSecretRealmBeastPreGenStats(
    rng::RngManager& rng, int32_t realm, const std::string& beastTypeName,
    bool ambushSucceeded, int32_t beastLayer) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const auto& types = beastTypeConfigs();
    const BeastTypeConfig* config = nullptr;
    for (const auto& t : types) {
        if (t.name == beastTypeName) {
            config = &t;
            break;
        }
    }
    if (config == nullptr) config = &types.front();  // Kotlin TYPES[0] 兜底
    const BeastRealmStats stats = beastRealmStats(realm);
    const int32_t clampedLayer = std::clamp(beastLayer, 1, secret_realm_cfg::kBeastLayerVariantCount);
    const double layerMult = 1.0 + (clampedLayer - 1) * 0.1;
    const double hpVariance = -0.2 + sr.nextDouble() * 0.4;
    const double atkVariance = -0.2 + sr.nextDouble() * 0.4;
    const double defVariance = -0.2 + sr.nextDouble() * 0.4;
    const double speedVariance = -0.2 + sr.nextDouble() * 0.4;

    int32_t maxHp = std::max(
        static_cast<int32_t>(stats.hp * layerMult * (config->hpMod + hpVariance)), 1);
    if (ambushSucceeded) {
        maxHp = std::max(static_cast<int32_t>(
                             maxHp * (1.0 - secret_realm_cfg::kAmbushBeastHpReduction)),
                         1);
    }
    SecretRealmBeastPreGenStats out;
    out.maxHp = maxHp;
    out.maxMp = std::max(
        static_cast<int32_t>(stats.mp * layerMult * (config->hpMod + hpVariance)), 1);
    out.physicalAttack = std::max(
        static_cast<int32_t>(stats.attack * layerMult * (config->atkMod + atkVariance)), 1);
    out.magicAttack = out.physicalAttack;
    out.physicalDefense = std::max(
        static_cast<int32_t>(stats.defense * layerMult * (config->defMod + defVariance)), 1);
    out.magicDefense = out.physicalDefense;
    out.speed = std::max(
        static_cast<int32_t>(stats.speed * layerMult * (config->speedMod + speedVariance)), 1);
    out.realmLayer = clampedLayer;
    return out;
}

// ── 妖兽类型名 → 材料 id 前缀（Kotlin BeastMaterialDatabase.getMaterialsByBeastType）──
inline const char* beastMaterialIdPrefix(const std::string& beastTypeName) {
    if (beastTypeName == "虎妖") return "tiger";
    if (beastTypeName == "狼妖") return "wolf";
    if (beastTypeName == "蛇妖") return "snake";
    if (beastTypeName == "熊妖") return "bear";
    if (beastTypeName == "鹰妖") return "eagle";
    if (beastTypeName == "狐妖") return "fox";
    if (beastTypeName == "龙妖") return "dragon";
    if (beastTypeName == "龟妖") return "turtle";
    return nullptr;
}

// ── 妖兽战斗胜利掉落（Kotlin rollBeastLoot）───────────────────
// 每只妖兽固定 2 个该妖兽材料；按妖兽境界最高品阶过滤；加权选取。
// RNG 消费：beastCount*2 × nextDouble（roll = d*totalWeight；顺序不可调换）。
inline std::vector<state::SecretRealmRewardItem> rollSecretRealmBeastLoot(
    rng::RngManager& rng, const std::string& beastTypeName, int32_t beastRealm,
    int32_t beastCount) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const char* prefix = beastMaterialIdPrefix(beastTypeName);
    if (prefix == nullptr) return {};
    const int32_t tier = maxRarityForRealm(beastRealm);
    const auto& all = data::beastMaterialTemplates();
    std::vector<const data::BeastMaterialTemplate*> candidates;
    for (const auto& m : all) {
        if (m.id.rfind(prefix, 0) == 0 && m.tier == tier) {
            candidates.push_back(&m);
        }
    }
    if (candidates.empty()) return {};
    double totalWeight = 0.0;
    for (const auto* c : candidates) totalWeight += c->dropWeight;

    std::vector<state::SecretRealmRewardItem> rewards;
    for (int32_t i = 0; i < beastCount * 2; ++i) {
        double roll = sr.nextDouble() * totalWeight;
        const data::BeastMaterialTemplate* selected = nullptr;
        for (const auto* c : candidates) {
            roll -= c->dropWeight;
            if (roll <= 0.0) {
                selected = c;
                break;
            }
        }
        if (selected == nullptr) selected = candidates.front();  // Kotlin first() 兜底
        state::SecretRealmRewardItem item;
        item.type = "material";
        item.itemId = selected->id;
        item.name = selected->name;
        item.rarity = selected->rarity;
        item.quantity = 1;
        rewards.push_back(std::move(item));
    }
    return rewards;
}

// ── 遗迹秘宝描述符生成（Kotlin generateRuinsTreasure）──────────
// 候选模板列表由调用方从真实数据库填充（C++ 零数据库依赖边界）。
// RNG 消费顺序（确定性关键）：1×nextInt(数量) → 每件 1×nextInt(类型) +
// 1×nextInt(品阶) + 候选非空时 1×nextInt(模板选取)；数据空洞 continue 不消费。
inline std::vector<state::SecretRealmRewardItem> generateSecretRealmRuinsTreasure(
    rng::RngManager& rng, int32_t minCount, int32_t maxCount, int32_t minRarity,
    int32_t maxRarity, const SecretRealmTypeCandidates& candidatesByType) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    static const std::vector<std::string> kTypes = {
        "equipment", "manual", "pill", "material", "herb", "seed"};
    const int32_t count = minCount + sr.nextInt(maxCount - minCount + 1);
    std::vector<state::SecretRealmRewardItem> rewards;
    int32_t attempts = 0;
    while (static_cast<int32_t>(rewards.size()) < count &&
           attempts < count * secret_realm_cfg::kRuinPickMaxAttempts) {
        ++attempts;
        const std::string& type =
            kTypes[static_cast<std::size_t>(sr.nextInt(static_cast<int32_t>(kTypes.size())))];
        const int32_t rarity = minRarity + sr.nextInt(maxRarity - minRarity + 1);
        const auto tit = candidatesByType.find(type);
        if (tit == candidatesByType.end()) continue;
        const auto rit = tit->second.find(rarity);
        if (rit == tit->second.end() || rit->second.empty()) continue;
        const auto& list = rit->second;
        const auto& tpl =
            list[static_cast<std::size_t>(sr.nextInt(static_cast<int32_t>(list.size())))];
        state::SecretRealmRewardItem item;
        item.type = type;
        item.itemId = tpl.first;
        item.name = tpl.second;
        item.rarity = rarity;
        item.quantity = 1;
        rewards.push_back(std::move(item));
    }
    return rewards;
}

// ── 无战斗分支结算载体（Kotlin directionResolution）───────────
inline SecretRealmResolution secretRealmDirectionResolution(
    const std::string& resultText,
    const std::vector<state::SecretRealmMemberState>& members,
    const state::SecretRealmBackpack& backpack) {
    SecretRealmResolution r;
    r.resultText = resultText;
    r.members = members;
    r.backpack = backpack;
    r.nextEvent = generateSecretRealmDirectionEvent(resultText);
    return r;
}

// ── 遗迹搜寻结算（Kotlin SecretRealmRuinsResolver.resolveRuinsSearch）──
// RNG 消费顺序：1×nextDouble(秘宝判定) → 秘宝路径 generateRuinsTreasure。
// 秘宝实例化（背包写入）属注册表边界，由 Kotlin 调用方完成；本函数仅
// 产出描述符（params.itemRewards）+ 结果文本 + 方向事件。
inline SecretRealmResolution resolveSecretRealmRuinsSearch(
    int32_t optionIndex, const std::vector<state::SecretRealmMemberState>& members,
    const state::SecretRealmBackpack& backpack, rng::RngManager& rng,
    const SecretRealmTypeCandidates& candidatesByType) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const bool careful = optionIndex >= 2;
    const bool treasure = sr.nextDouble() < secret_realm_cfg::kRuinsTreasureChance;
    if (!treasure) {
        return secretRealmDirectionResolution("你方在遗迹中搜寻一番，空无一物", members, backpack);
    }
    const int32_t minCount = careful ? secret_realm_cfg::kCarefulSearchCountMin
                                     : secret_realm_cfg::kSimpleSearchCountMin;
    const int32_t maxCount = careful ? secret_realm_cfg::kCarefulSearchCountMax
                                     : secret_realm_cfg::kSimpleSearchCountMax;
    const int32_t minRarity = careful ? secret_realm_cfg::kCarefulSearchRarityMin
                                      : secret_realm_cfg::kSimpleSearchRarityMin;
    const int32_t maxRarity = careful ? secret_realm_cfg::kCarefulSearchRarityMax
                                      : secret_realm_cfg::kSimpleSearchRarityMax;
    const auto rewards = generateSecretRealmRuinsTreasure(
        rng, minCount, maxCount, minRarity, maxRarity, candidatesByType);
    // 极端数据空洞降级为空无一物（RNG 消费顺序不变，确定性保持）
    if (rewards.empty()) {
        return secretRealmDirectionResolution("你方在遗迹中搜寻一番，空无一物", members, backpack);
    }
    std::string itemText;
    for (std::size_t i = 0; i < rewards.size(); ++i) {
        if (i > 0) itemText += "、";
        itemText += rewards[i].name;
        if (rewards[i].quantity > 1) itemText += "×" + std::to_string(rewards[i].quantity);
    }
    const std::string resultText = "你方在遗迹中发现了宝物！获得：" + itemText;
    SecretRealmResolution r = secretRealmDirectionResolution(resultText, members, backpack);
    r.params.itemRewards = rewards;  // 描述符入 params（chooseOption 写历史保留）
    return r;
}

// ── 遗迹探索选项结算（Kotlin resolveRuinsExplore）──────────────
inline SecretRealmResolution resolveSecretRealmRuinsExplore(
    int32_t optionIndex, const std::vector<state::SecretRealmMemberState>& members,
    const state::SecretRealmBackpack& backpack, rng::RngManager& rng,
    const SecretRealmTypeCandidates& candidatesByType) {
    if (optionIndex == 0) {
        return secretRealmDirectionResolution("你方决定离开遗迹，继续探索", members, backpack);
    }
    return resolveSecretRealmRuinsSearch(optionIndex, members, backpack, rng, candidatesByType);
}

// ── 旧档兼容 RUIN_RESULT 结算（Kotlin resolveRuinsResult）──────
inline SecretRealmResolution resolveSecretRealmRuinsResult(
    const std::vector<state::SecretRealmMemberState>& members,
    const state::SecretRealmBackpack& backpack,
    const state::SecretRealmEventRecord& event) {
    const std::string resultText = event.params.itemRewards.empty()
                                       ? "遗迹中空无一物，你方继续前行"
                                       : "你方携秘宝离开遗迹，继续前行";
    SecretRealmResolution r =
        secretRealmDirectionResolution(resultText, members, backpack);
    r.params = event.params;  // 保留秘宝描述符（历史记录明细）
    return r;
}

// ── 背包总件数（Kotlin SecretRealmBackpack.totalItemCount）────
inline int32_t secretRealmTotalItemCount(const state::SecretRealmBackpack& backpack) {
    return static_cast<int32_t>(backpack.equipment.size() + backpack.manuals.size() +
                                backpack.pills.size() + backpack.materials.size() +
                                backpack.herbs.size() + backpack.seeds.size());
}

// ── 战斗失败丢失背包物品（Kotlin SecretRealmBattleHelper.applyLootLoss）──
// 比例 20%~45%（1×nextDouble）；件数 ceil 宁多不少；索引空间 = 六类列表拼接。
// 洗牌 = kotlin.collections.shuffled(Random) Fisher-Yates（从后往前，
// nextInt(i+1)），RNG 消费顺序一致。
inline SecretRealmLootLossResult applySecretRealmLootLoss(
    const state::SecretRealmBackpack& backpack, rng::RngManager& rng) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    const double ratio = secret_realm_cfg::kLootLossMin +
        sr.nextDouble() * (secret_realm_cfg::kLootLossMax - secret_realm_cfg::kLootLossMin);
    const int32_t totalItems = secretRealmTotalItemCount(backpack);
    const int32_t lostItemCount =
        static_cast<int32_t>(std::ceil(static_cast<double>(totalItems) * ratio));
    const int64_t lostSpiritStones =
        static_cast<int64_t>(std::ceil(static_cast<double>(backpack.spiritStones) * ratio));
    if (lostItemCount == 0 && lostSpiritStones == 0) {
        return {backpack, 0, 0};
    }
    // 索引洗牌（0..totalItems-1）；take(lostItemCount) 前 lostItemCount 个
    std::vector<int32_t> indices(static_cast<std::size_t>(totalItems));
    for (int32_t i = 0; i < totalItems; ++i) indices[static_cast<std::size_t>(i)] = i;
    for (int32_t i = totalItems - 1; i >= 1; --i) {
        const int32_t j = sr.nextInt(i + 1);
        std::swap(indices[static_cast<std::size_t>(i)], indices[static_cast<std::size_t>(j)]);
    }
    std::vector<bool> lost(static_cast<std::size_t>(totalItems), false);
    for (int32_t i = 0; i < lostItemCount; ++i) {
        lost[static_cast<std::size_t>(indices[static_cast<std::size_t>(i)])] = true;
    }
    // 遍历六类（顺序与 Kotlin collectKept 一致）：命中丢失索引 → 丢弃计数
    int32_t cursor = 0;
    int32_t lostTotal = 0;
    auto collectKept = [&](const auto& items, auto& kept) {
        int32_t lostHere = 0;
        for (const auto& item : items) {
            if (lost[static_cast<std::size_t>(cursor++)]) ++lostHere;
            else kept.push_back(item);
        }
        lostTotal += lostHere;
    };
    state::SecretRealmBackpack out;
    out.spiritStones = backpack.spiritStones - lostSpiritStones;
    collectKept(backpack.equipment, out.equipment);
    collectKept(backpack.manuals, out.manuals);
    collectKept(backpack.pills, out.pills);
    collectKept(backpack.materials, out.materials);
    collectKept(backpack.herbs, out.herbs);
    collectKept(backpack.seeds, out.seeds);
    SecretRealmLootLossResult r;
    r.backpack = std::move(out);
    r.lostItemCount = lostTotal;
    r.lostSpiritStones = lostSpiritStones;
    return r;
}

// ── 选择选项后体力计算（Kotlin SecretRealmService.calculateNewStamina）──
// 非法消耗（0/负数/超大值）clamp 到 1..STAMINA_MAX；Long 中间量防回绕。
inline int32_t secretRealmStaminaAfterChoice(
    const state::SecretRealmExplorationSession& session,
    const state::SecretRealmEventRecord& event, int32_t optionIndex) {
    const int32_t optionCost =
        optionIndex >= 0 && optionIndex < static_cast<int32_t>(event.options.size())
            ? event.options[static_cast<std::size_t>(optionIndex)].staminaCost
            : secret_realm_cfg::kStaminaCostPerChoice;
    const int32_t safeCost = std::clamp(optionCost, secret_realm_cfg::kStaminaCostPerChoice,
                                        secret_realm_cfg::kStaminaMax);
    const int64_t raw = static_cast<int64_t>(session.stamina) - safeCost;
    return static_cast<int32_t>(std::clamp<int64_t>(raw, 0, secret_realm_cfg::kStaminaMax));
}

// ── 秘境空闲位置寻找（Kotlin SecretRealmService.findFreePosition）──
// 世界地图常量：宽 1698 / 高 926 / 边界 34 / 宗门半径 20；最小距离 =
// SECT_RADIUS + SECT_CLEARANCE = 40。最多 100 次随机尝试（每次 2×nextInt）；
// 失败兜底：步长 8 扫描取与所有宗门最远点（无 RNG）。
// 精度：Kotlin 用 Float 运算（sect.x Float），对拍红线——C++ 同样用 float。
inline std::pair<int32_t, int32_t> findSecretRealmPosition(
    rng::RngManager& rng, const std::vector<state::WorldSect>& sects) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    constexpr int32_t kMapWidth = 1698;
    constexpr int32_t kMapHeight = 926;
    constexpr int32_t kBorderPadding = 34;
    constexpr int32_t kSectRadius = 20;
    const float minDistSq = static_cast<float>(kSectRadius + secret_realm_cfg::kSectClearance) *
                            static_cast<float>(kSectRadius + secret_realm_cfg::kSectClearance);
    for (int32_t attempt = 0; attempt < secret_realm_cfg::kPositionAttempts; ++attempt) {
        const int32_t x = kBorderPadding + sr.nextInt(kMapWidth - kBorderPadding * 2);
        const int32_t y = kBorderPadding + sr.nextInt(kMapHeight - kBorderPadding * 2);
        bool nearSect = false;
        for (const auto& sect : sects) {
            const float dx = sect.x - static_cast<float>(x);
            const float dy = sect.y - static_cast<float>(y);
            if (dx * dx + dy * dy < minDistSq) {
                nearSect = true;
                break;
            }
        }
        if (!nearSect) return {x, y};
    }
    // 兜底：与所有宗门最远点（Kotlin 网格扫描；dist 取 minOfOrNull ?: 0f）
    int32_t bestX = kBorderPadding;
    int32_t bestY = kBorderPadding;
    float bestDist = -1.0f;
    for (int32_t x = kBorderPadding; x < kMapWidth - kBorderPadding;
         x += secret_realm_cfg::kFallbackScanStep) {
        for (int32_t y = kBorderPadding; y < kMapHeight - kBorderPadding;
             y += secret_realm_cfg::kFallbackScanStep) {
            float minD = 0.0f;
            bool any = false;
            for (const auto& sect : sects) {
                const float dx = sect.x - static_cast<float>(x);
                const float dy = sect.y - static_cast<float>(y);
                const float d = dx * dx + dy * dy;
                if (!any || d < minD) {
                    minD = d;
                    any = true;
                }
            }
            const float dist = any ? minD : 0.0f;
            if (dist > bestDist) {
                bestDist = dist;
                bestX = x;
                bestY = y;
            }
        }
    }
    return {bestX, bestY};
}

// ── 年变现世冷却判据（Kotlin processYearlySpawn）──────────────
// 篡改档防御：负冷却年 clamp 到 0；首次（cooldown=0）第 50 年现世。
inline bool secretRealmYearlySpawnEligible(int32_t year, int32_t cooldown) {
    const int32_t safeCooldown = std::max(cooldown, 0);
    return (year - safeCooldown) >= secret_realm_cfg::kCooldownYears;
}

/// 精灵变体随机（1×nextInt(SPRITE_VARIANT_COUNT)）
inline int32_t rollSecretRealmSpriteIndex(rng::RngManager& rng) {
    auto& sr = rng.getRng(rng::RngPartition::kSecretRealm);
    return sr.nextInt(secret_realm_cfg::kSpriteVariantCount);
}

// ── AI 宗门队伍派遣（Kotlin SecretRealmAIProcessor.processMonthlyAiTeams）──
// 每个有存活弟子的未派遣宗门：存活弟子按境界升序（数值小=高）取前
// AI_TEAM_SIZE 名。id（UUID）由 Kotlin 生成，本函数输出空串。

/// 宗门弟子池（Kotlin aiSectDisciples 单宗门条目 + 宗门信息）
struct SecretRealmAiPool {
    std::string sectId;
    std::string sectName;    // 找到宗门时的 name（可能为空串）；未找到时忽略
    bool sectFound = false;  // worldMapSects.find{id==sectId} 是否命中
    int32_t sectLevel = 0;   // 0小型/1中型/2大型/3顶级
    std::vector<state::Disciple> disciples;  // 宗门全部弟子快照（含 isAlive）
};

/// 派遣结果：newTeams（id 空串，Kotlin 补 UUID）
/// 每个宗门：过滤存活 → sortedBy realm 稳定升序（数值小=境界高）→ take 4。
/// sectName 语义 = Kotlin `sect?.name ?: sectId`：找到宗门用其 name（含空串），
/// 未找到回退 sectId。
inline std::vector<state::SecretRealmAITeam> dispatchSecretRealmAiTeams(
    const std::vector<SecretRealmAiPool>& pools) {
    std::vector<state::SecretRealmAITeam> teams;
    for (const auto& pool : pools) {
        std::vector<const state::Disciple*> alive;
        for (const auto& d : pool.disciples) {
            if (d.isAlive) alive.push_back(&d);
        }
        if (alive.empty()) continue;
        // sortedBy { it.realm } 稳定升序（境界数值小 = 境界高）
        std::stable_sort(alive.begin(), alive.end(),
                         [](const state::Disciple* a, const state::Disciple* b) {
                             return a->realm < b->realm;
                         });
        if (static_cast<int32_t>(alive.size()) > secret_realm_cfg::kAiTeamSize) {
            alive.resize(static_cast<std::size_t>(secret_realm_cfg::kAiTeamSize));
        }
        state::SecretRealmAITeam team;
        team.sectId = pool.sectId;
        team.sectName = pool.sectFound ? pool.sectName : pool.sectId;
        team.sectLevel = pool.sectLevel;
        for (const auto* d : alive) {
            state::SecretRealmAIMember member;
            member.discipleId = d->id;
            member.name = d->name;
            member.portraitRes = d->portraitRes;
            member.realm = d->realm;
            team.members.push_back(std::move(member));
        }
        teams.push_back(std::move(team));
    }
    return teams;
}

}  // namespace gamecore::system

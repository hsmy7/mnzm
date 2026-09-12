#pragma once

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/month_settlement.h"      // calculateSectPower/calculateAiSectPower
#include "gamecore/system/sect_attack_decision.h"  // countRecentBattleRecords
#include "gamecore/system/sect_decision.h"         // allianceDecisionProfile/vassalDecisionProfile/sectDecisionChance
#include "gamecore/system/settlement_detail.h"     // settle_util::recordGameEvent

// ============================================================
// 外交/好感/附庸 UI 操作事务族（batch-09 WS-2 规模下沉）
//
// 等价移植 Kotlin 写者（语义逐条对齐）：
//   - core/domain FavorDomain + 同文件好感度纯函数族
//     （calculateGiftFavorIncrease / calculateRejectProbability /
//      calculatePreferenceMultiplier / calculatePreferenceRejectModifier）
//   - core/engine GiftService.giftSpiritStones（FAVOR_GIFT 写者）
//   - core/engine DiplomacyService.requestAllianceSimple /
//     dissolveAllianceSimple（DIPLOMACY_TX 写者）
//   - core/engine VassalService.requestVassalContract /
//     dissolveVassalContract（VASSAL_TX 写者）
//
// 范围红线（batch-09 §4）：
//   - 月结/年结面（好感衰减/纳贡/脱离检查）已下沉（year_settlement.h /
//     month_settlement.h processVassalBreakaway），本头文件不触碰其循环形状。
//   - 宣战/停战/和平无 UI 操作面（AI 决策域，sect_attack_decision.h 已下沉）。
//
// RNG 纪律（本组 C 最高风险域）：
//   - 赠礼拒绝 roll：SYSTEM 分区 1×nextInt(100)（Lemire 无偏回绝采样，与
//     Kotlin DeterministicRng.nextInt(100) 逐位一致）——抽取点在全部校验
//     通过之后、任何状态写入之前，与 Kotlin 抽取序完全同位。
//   - 结盟/附属 roll：SYSTEM 分区 1×nextDouble——结盟在 aiPower<=0 时不
//     抽取（Kotlin computeAllianceSuccessChance 返回 null 早退）；附属在
//     资格通过后恒抽取（Kotlin calculateVassalChance 对 aiPower<=0 返回
//     0.0 但仍掷骰）。
//   - AUTHORITATIVE 下 Kotlin SYSTEM 分区为 NativeBackedRng 委托通道——
//     双臂（native 事务 / Kotlin 回退）消费同一分区同一条序列，逐位一致。
//   - 纯公式（好感增长/拒绝概率/偏好修正）零 roll（头注释论证）。
//     聊天响应模板抽取（SectResponseTexts.random()）为 kotlin Random.Default
//     非游戏分区消费，留 Kotlin（batch-09 §1 out-of-scope）。
//
// 浮点纪律（favor 公式逐位对齐）：
//   - Kotlin `((baseFavor + percentageIncrease) * preferenceMultiplier).toInt()`
//     的 Int*Double 提升、double 乘法、向零截断语义按原式保留——运算序
//     不得重排（乘区与钳制逐位一致，对拍可锁）。
//
// 零写入论证：钱包 deduct 的 Insufficient 路径先于任何状态赋值返回
//（autoConvert 补差价计划不满足时原样返回，与 Kotlin SpiritStoneWallet
// 同构）——"failed"/校验失败信封 = 状态零变化，仅 roll 消费差异由信封
// 类型区分（校验失败走 failure 信封回退，roll 后结果走 success 信封）。
// ============================================================
namespace gamecore::system {

namespace diplomacy_tx {

// ── 配置常量（Kotlin FavorConfig / GiftConfig 同源）──────────

inline constexpr int32_t kFavorMin = 0;     // FavorConfig.MIN_FAVOR
inline constexpr int32_t kFavorMax = 100;   // FavorConfig.MAX_FAVOR

/// 灵石送礼档位（GiftConfig.SpiritStoneGiftTier；name 为展示字段留 Kotlin）
struct GiftTier {
    int32_t tier = 0;
    int64_t spiritStones = 0;
    int32_t baseFavor = 0;
};

/// GiftConfig.SpiritStoneGiftConfig.TIERS（1=薄礼 2=厚礼 3=重礼 4=大礼）
inline const GiftTier* findGiftTier(int32_t tier) {
    static const GiftTier kTiers[] = {
        {1, 20'000, 2},
        {2, 200'000, 5},
        {3, 800'000, 10},
        {4, 4'000'000, 15},
    };
    for (const auto& t : kTiers) {
        if (t.tier == tier) return &t;
    }
    return nullptr;
}

/// GiftConfig.FavorPercentageConfig.getFavorPercentage（缺失返回 nullptr
/// ——Kotlin Int? null 语义，走 baseFavor-only 分支）
inline const int32_t* favorPercentageFor(int32_t sectLevel, int32_t tier) {
    static const std::map<int32_t, std::map<int32_t, int32_t>> kMatrix = {
        {0, {{1, 20}, {2, 40}, {3, 100}, {4, 200}}},
        {1, {{1, 10}, {2, 30}, {3, 70}, {4, 150}}},
        {2, {{3, 40}, {4, 70}}},
        {3, {{3, 30}, {4, 50}}},
    };
    const auto lvl = kMatrix.find(sectLevel);
    if (lvl == kMatrix.end()) return nullptr;
    const auto t = lvl->second.find(tier);
    if (t == lvl->second.end()) return nullptr;
    return &t->second;
}

/// GiftConfig.SectRejectConfig.getRejectProbability（未知等级回退 level 0，
/// 未知稀有度 0——Kotlin REJECT_MATRIX[sectLevel] ?: REJECT_MATRIX[0] 同口径）
inline int32_t rejectProbabilityFor(int32_t sectLevel, int32_t rarity) {
    static const std::map<int32_t, std::map<int32_t, int32_t>> kMatrix = {
        {0, {{1, 50}, {2, 20}, {3, 0}, {4, 0}, {5, 0}, {6, 0}}},
        {1, {{1, 70}, {2, 50}, {3, 30}, {4, 0}, {5, 0}, {6, 0}}},
        {2, {{1, 100}, {2, 100}, {3, 100}, {4, 30}, {5, 10}, {6, 0}}},
        {3, {{1, 100}, {2, 100}, {3, 100}, {4, 50}, {5, 20}, {6, 0}}},
    };
    const auto lvl = kMatrix.find(sectLevel);
    const auto& levelConfig =
        (lvl != kMatrix.end()) ? lvl->second : kMatrix.begin()->second;
    const auto r = levelConfig.find(rarity);
    return (r != levelConfig.end()) ? r->second : 0;
}

// ═══════════ 好感度纯函数族（FavorDomain 逐字移植）═══════════

/// FavorDomain.findRelation：双向匹配首条；不存在返回 nullptr
inline const state::SectRelation* findRelation(
    const std::vector<state::SectRelation>& relations,
    const std::string& sectIdA, const std::string& sectIdB) {
    for (const auto& r : relations) {
        if ((r.sectId1 == sectIdA && r.sectId2 == sectIdB) ||
            (r.sectId1 == sectIdB && r.sectId2 == sectIdA)) {
            return &r;
        }
    }
    return nullptr;
}

/// FavorDomain.findFavor：无关系返回 0
inline int32_t findFavor(const std::vector<state::SectRelation>& relations,
                         const std::string& fromSectId,
                         const std::string& toSectId) {
    const state::SectRelation* r = findRelation(relations, fromSectId, toSectId);
    return (r != nullptr) ? r->favor : 0;
}

/// FavorDomain.setAcquainted（幂等；不存在则新建 acquainted=true 记录，
/// id 按 min/max 归一化落位）
inline std::vector<state::SectRelation> setAcquainted(
    const std::vector<state::SectRelation>& relations,
    const std::string& sectId1, const std::string& sectId2, int32_t year) {
    const state::SectRelation* existing = findRelation(relations, sectId1, sectId2);
    if (existing != nullptr) {
        if (existing->acquainted) return relations;
        const std::string id1 = std::min(sectId1, sectId2);
        const std::string id2 = std::max(sectId1, sectId2);
        std::vector<state::SectRelation> out = relations;
        for (auto& r : out) {
            if (r.sectId1 == id1 && r.sectId2 == id2) {
                r.acquainted = true;
                r.lastInteractionYear = year;
            }
        }
        return out;
    }
    std::vector<state::SectRelation> out = relations;
    state::SectRelation r;
    r.sectId1 = std::min(sectId1, sectId2);
    r.sectId2 = std::max(sectId1, sectId2);
    r.acquainted = true;
    r.lastInteractionYear = year;
    out.push_back(std::move(r));
    return out;
}

/// FavorDomain.updateFavor（设定绝对值）：同宗 no-op；已存在未相识 no-op；
/// clamp [0,100]；增量刷新（newFavor > 旧值）清零 noGiftYears；缺失追加
/// （id 归一化 min/max，与 Kotlin 插入口径一致）
inline std::vector<state::SectRelation> updateFavor(
    const std::vector<state::SectRelation>& relations,
    const std::string& sectId1, const std::string& sectId2,
    int32_t newFavor, int32_t year) {
    if (sectId1 == sectId2) return relations;
    const state::SectRelation* existing = findRelation(relations, sectId1, sectId2);
    if (existing != nullptr && !existing->acquainted) return relations;
    const int32_t clamped = std::clamp(newFavor, kFavorMin, kFavorMax);
    const std::string id1 = std::min(sectId1, sectId2);
    const std::string id2 = std::max(sectId1, sectId2);
    std::vector<state::SectRelation> out = relations;
    auto it = std::find_if(out.begin(), out.end(),
                           [&](const state::SectRelation& r) {
                               return r.sectId1 == id1 && r.sectId2 == id2;
                           });
    if (it != out.end()) {
        const bool increased = newFavor > it->favor;
        it->favor = clamped;
        it->lastInteractionYear = year;
        if (increased) it->noGiftYears = 0;
    } else {
        state::SectRelation r;
        r.sectId1 = id1;
        r.sectId2 = id2;
        r.favor = clamped;
        r.lastInteractionYear = year;
        r.noGiftYears = 0;
        out.push_back(std::move(r));
    }
    return out;
}

// ── 好感度文件级纯函数族（FavorDomain.kt 同文件顶层函数移植）──

/// calculatePreferenceMultiplier（NONE→1.0；SPIRIT_STONE+灵石→1.3）
inline double calculatePreferenceMultiplier(const std::string& giftPreference,
                                            bool isSpiritStone) {
    if (giftPreference == "NONE") return 1.0;
    if (isSpiritStone && giftPreference == "SPIRIT_STONE") return 1.3;
    return 1.0;
}

/// calculatePreferenceRejectModifier（NONE→0；SPIRIT_STONE+灵石→-15）
inline int32_t calculatePreferenceRejectModifier(
    const std::string& giftPreference, bool isSpiritStone) {
    if (giftPreference == "NONE") return 0;
    if (isSpiritStone && giftPreference == "SPIRIT_STONE") return -15;
    return 0;
}

/// calculateRejectProbability（查表）
inline int32_t calculateRejectProbability(int32_t sectLevel, int32_t rarity) {
    return rejectProbabilityFor(sectLevel, rarity);
}

/// calculateGiftFavorIncrease（送礼好感增长——浮点运算序与 Kotlin 逐位对齐，
/// 见头注释浮点纪律；零 roll）
inline int32_t calculateGiftFavorIncrease(int32_t currentFavor, int32_t tier,
                                          int32_t sectLevel,
                                          const std::string& giftPreference) {
    const GiftTier* tierConfig = findGiftTier(tier);
    if (tierConfig == nullptr) return 0;
    const int32_t* percentage = favorPercentageFor(sectLevel, tier);
    const double preferenceMultiplier =
        calculatePreferenceMultiplier(giftPreference, /*isSpiritStone=*/true);
    const int32_t baseFavor = tierConfig->baseFavor;
    if (percentage != nullptr) {
        // Kotlin: currentFavor * percentage / 100（Int 截断除法）
        const int32_t percentageIncrease = currentFavor * (*percentage) / 100;
        // Kotlin: ((baseFavor + percentageIncrease) * preferenceMultiplier).toInt()
        const int32_t adjustedIncrease = static_cast<int32_t>(
            (baseFavor + percentageIncrease) * preferenceMultiplier);
        return (adjustedIncrease == 0) ? 1 : adjustedIncrease;
    }
    // Kotlin: (baseFavor * preferenceMultiplier).toInt().coerceAtLeast(1)
    const int32_t plain =
        static_cast<int32_t>(baseFavor * preferenceMultiplier);
    return std::max(plain, 1);
}

// ═══════════ 事务族（Kotlin 写者等价移植）═══════════

/// 玩家宗门（worldMapSects 首个 isPlayerSect——Kotlin find 同序）
inline const state::WorldSect* findPlayerSect(const state::GameData& gd) {
    for (const auto& s : gd.worldMapSects) {
        if (s.isPlayerSect) return &s;
    }
    return nullptr;
}

/// 校验失败描述（errorType 与 Kotlin responseType/失败位置同名——
/// execute 层转 failure 信封回退 Kotlin 原路径，该臂零抽取零写入）
struct TxRejection {
    std::string errorType;
};

/// 赠礼事务结果（信封字段供 Kotlin 侧重建 GiftResult——message 响应模板
/// 留 Kotlin SectResponseTexts，非游戏分区抽取，见头注释 RNG 纪律）
struct GiftOutcome {
    /// "accept" / "rejected" / "failed"（Kotlin GiftResult.responseType 同名）
    std::string responseType;
    int32_t favorChange = 0;
    int32_t newFavor = 0;
    int32_t sectLevel = 0;      /// 接受/拒绝响应模板输入（Kotlin ready.sect.level）
    int64_t neededStones = 0;   /// "failed" 提示输入（Kotlin tierConfig.spiritStones）
};

namespace detail {

/// 拒绝概率（Kotlin GiftService.computeRejectProbability：档位虚拟稀有度
/// clamp [2,5] + 偏好修正，钳制 0..100；纯公式零 roll）
inline int32_t giftRejectProbability(int32_t sectLevel,
                                     const std::string& giftPreference,
                                     int32_t tier) {
    const int32_t virtualRarity = std::clamp(tier + 1, 2, 5);
    const int32_t base = calculateRejectProbability(sectLevel, virtualRarity);
    const int32_t modifier =
        calculatePreferenceRejectModifier(giftPreference, /*isSpiritStone=*/true);
    return std::clamp(base + modifier, 0, 100);
}

}  // namespace detail

/// 赠礼事务（Kotlin GiftService.giftSpiritStones 等价——校验链顺序/
/// 拒绝 roll 位置/好感写入/年度限制/钱包扣减逐条对齐）。
/// 返回 TxRejection = 校验失败（零抽取零写入，调用方转 failure 信封）；
/// 否则 SYSTEM 分区 roll 已消费，outcome.responseType ∈
/// {accept, rejected, failed}（"failed" = 扣费阶段失败——roll 已消费、
/// 状态零写入，与 Kotlin 事务 buffer 语义一致）。
inline GiftOutcome giftSpiritStonesTransaction(state::GameState& state,
                                               rng::RngManager& rng,
                                               const std::string& sectId,
                                               int32_t tier,
                                               bool bypassYearLimit,
                                               TxRejection& rejection) {
    state::GameData& gd = state.gameData;
    // ── Kotlin prepareGift 校验链（同序同语义，零抽取） ──
    const state::WorldSect* sect = nullptr;
    for (const auto& s : gd.worldMapSects) {
        if (s.id == sectId) { sect = &s; break; }
    }
    if (sect == nullptr) {
        rejection = {"sect_not_found"};
        return {};
    }
    if (sect->isPlayerSect) {
        rejection = {"invalid_target"};
        return {};
    }
    // Kotlin: data.sectDetails[sect.id]?.lastGiftYear ?: 0（缺失按 0）
    if (!bypassYearLimit) {
        const auto detailIt = gd.sectDetails.find(sectId);
        const int32_t lastGiftYear =
            (detailIt != gd.sectDetails.end()) ? detailIt->second.lastGiftYear : 0;
        if (lastGiftYear == gd.gameYear) {
            rejection = {"already_gifted"};
            return {};
        }
    }
    const GiftTier* tierConfig = findGiftTier(tier);
    if (tierConfig == nullptr) {
        rejection = {"invalid_tier"};
        return {};
    }
    if (gd.spiritStones < tierConfig->spiritStones) {
        rejection = {"insufficient_resources"};
        return {};
    }

    // 拒绝概率（纯公式）+ 拒绝 roll——SYSTEM 分区 1×nextInt(100)
    const auto detailIt = gd.sectDetails.find(sectId);
    const std::string giftPreference =
        (detailIt != gd.sectDetails.end()) ? detailIt->second.giftPreference : "NONE";
    const int32_t rejectProbability =
        detail::giftRejectProbability(sect->level, giftPreference, tier);
    rng::DeterministicRng& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const bool isRejected = rngSystem.nextInt(100) < rejectProbability;
    if (isRejected) {
        // 零写入（Kotlin 拒绝臂不进事务）
        return {"rejected", 0, 0, sect->level, 0};
    }

    // ── 好感计算（Kotlin computeGiftFavorIncrease：玩家宗门缺失按 0 好感） ──
    const state::WorldSect* playerSect = findPlayerSect(gd);
    const int32_t currentFavor =
        (playerSect != nullptr) ? findFavor(gd.sectRelations, playerSect->id, sectId) : 0;
    const int32_t favorIncrease =
        calculateGiftFavorIncrease(currentFavor, tier, sect->level, giftPreference);
    const int32_t newFavor =
        std::clamp(currentFavor + favorIncrease, kFavorMin, kFavorMax);

    // ── Kotlin applyGiftSpiritStones（事务写入：相识 + 好感 + 送礼年 + 扣费） ──
    if (playerSect == nullptr) {
        // Kotlin livePlayerSect == null → applyGiftSpiritStones false → "failed"
        return {"failed", 0, 0, sect->level, tierConfig->spiritStones};
    }
    std::vector<state::SectRelation> updatedRelations = setAcquainted(
        gd.sectRelations, playerSect->id, sectId, gd.gameYear);
    updatedRelations = updateFavor(updatedRelations, playerSect->id, sectId,
                                   newFavor, gd.gameYear);
    std::map<std::string, state::SectDetail> updatedDetails = gd.sectDetails;
    if (!bypassYearLimit) {
        auto it = updatedDetails.find(sectId);
        if (it == updatedDetails.end()) {
            state::SectDetail created;
            created.sectId = sectId;
            it = updatedDetails.emplace(sectId, std::move(created)).first;
        }
        it->second.lastGiftYear = gd.gameYear;
    }
    // 钱包扣减（Insufficient 零副作用——见头注释零写入论证）
    const auto deduct = SpiritStoneWallet::deduct(
        gd, tierConfig->spiritStones, SpiritStoneGrade::LOW, "Gift", "Internal", true);
    if (deduct.status != DeductStatus::kSuccess) {
        return {"failed", 0, 0, sect->level, tierConfig->spiritStones};
    }
    gd.sectRelations = std::move(updatedRelations);
    gd.sectDetails = std::move(updatedDetails);
    return {"accept", favorIncrease, newFavor, sect->level, 0};
}

/// 结盟请求事务结果（success 对应 Kotlin requestAllianceSimple 返回值）
struct AllianceOutcome {
    bool success = false;
};

/// 结盟请求事务（Kotlin DiplomacyService.requestAllianceSimple 等价——
/// 资格门控/四因素概率/SYSTEM 1×nextDouble/相识+盟约+双方宗门关联写入
/// 逐条对齐；alliance.id 为 Kotlin UUID 镜像生成字段，确定性自增占位——
/// 仅保证唯一，不参与业务逻辑（recruit_settlement.h 先例））。
/// 返回 TxRejection = 校验失败/aiPower 非法（零抽取零写入——Kotlin 同位置
/// return false 不掷骰）。
inline AllianceOutcome requestAllianceTransaction(state::GameState& state,
                                                  rng::RngManager& rng,
                                                  ecs::World& world,
                                                  const std::string& sectId,
                                                  TxRejection& rejection) {
    state::GameData& gd = state.gameData;
    const state::WorldSect* sect = nullptr;
    for (const auto& s : gd.worldMapSects) {
        if (s.id == sectId) { sect = &s; break; }
    }
    if (sect == nullptr) {
        rejection = {"sect_not_found"};
        return {};
    }
    // Kotlin failsAllianceSimpleEligibility（同序）
    if (sect->isPlayerSect || !sect->allianceId.empty()) {
        rejection = {"eligibility"};
        return {};
    }
    bool playerAllied = false;
    for (const auto& alliance : gd.alliances) {
        for (const auto& sid : alliance.sectIds) {
            if (sid == "player") { playerAllied = true; break; }
        }
        if (playerAllied) break;
    }
    const state::WorldSect* playerSect = findPlayerSect(gd);
    if (playerAllied || playerSect == nullptr) {
        rejection = {"eligibility"};
        return {};
    }

    // Kotlin computeAllianceSuccessChance（aiPower<=0 → null 早退，零抽取）
    const int64_t playerPower = ::gamecore::system::detail::calculateSectPower(
        state.disciples, world);
    const int64_t aiPower =
        ::gamecore::system::detail::calculateAiSectPower(state.aiSectDisciples,
                                                         sectId);
    if (aiPower <= 0) {
        rejection = {"ai_power_invalid"};
        return {};
    }
    const double powerRatio =
        static_cast<double>(playerPower) / static_cast<double>(aiPower);
    const int32_t favorLevel =
        ::gamecore::system::detail::favorLevelOrdinal(
            findFavor(gd.sectRelations, playerSect->id, sectId));
    // Kotlin aiSectPersonalities[sect.id] ?: BALANCED（ordinal 1）
    int32_t personality = 1;  // BALANCED
    if (const auto it = gd.aiSectPersonalities.find(sectId);
        it != gd.aiSectPersonalities.end()) {
        personality = it->second;
    }
    const auto counts = ::gamecore::system::detail::countRecentBattleRecords(
        gd.sectBattleRecords, gd.gameYear);
    const double chance = sectDecisionChance(
        allianceDecisionProfile(), powerRatio, counts.conquest, counts.lostSect,
        counts.battleWin, counts.battleLoss, favorLevel, personality);

    // 结盟 roll——SYSTEM 分区 1×nextDouble
    rng::DeterministicRng& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const bool success = rngSystem.nextDouble() < chance;
    if (!success) return {false};

    // Kotlin applyAllianceCreation（相识 + 盟约 + 双方宗门 alliance 字段 +
    // 消息栏事件；事件与状态同事务——settle_util::recordGameEvent 同守卫）
    gd.sectRelations = setAcquainted(gd.sectRelations, playerSect->id, sectId,
                                     gd.gameYear);
    // alliance.id：确定性自增占位（Kotlin UUID 镜像生成字段——碰撞规避扫描）
    int64_t suffix = static_cast<int64_t>(gd.alliances.size()) + 1;
    std::string allianceId = "gc-alliance-" + std::to_string(suffix);
    auto idTaken = [&gd](const std::string& id) {
        for (const auto& a : gd.alliances) {
            if (a.id == id) return true;
        }
        return false;
    };
    while (idTaken(allianceId)) {
        ++suffix;
        allianceId = "gc-alliance-" + std::to_string(suffix);
    }
    state::Alliance alliance;
    alliance.id = allianceId;
    alliance.sectIds = {"player", sectId};
    alliance.startYear = gd.gameYear;
    alliance.initiatorId = "player";
    gd.alliances.push_back(std::move(alliance));
    for (auto& s : gd.worldMapSects) {
        if (s.id == sectId || s.isPlayerSect) {
            s.allianceId = allianceId;
            s.allianceStartYear = gd.gameYear;
        }
    }
    settle_util::recordGameEvent(state, "WORLD", "alliance",
                                 "与" + sect->name + "结为同盟");
    return {true};
}

/// 解除结盟事务（Kotlin DiplomacyService.dissolveAllianceSimple 等价——
/// 零 RNG；失败零写入；成功移除盟约 + 成员宗门 alliance 字段清零 + 事件）
inline AllianceOutcome dissolveAllianceTransaction(state::GameState& state,
                                                   const std::string& sectId,
                                                   TxRejection& rejection) {
    state::GameData& gd = state.gameData;
    const state::WorldSect* sect = nullptr;
    for (const auto& s : gd.worldMapSects) {
        if (s.id == sectId) { sect = &s; break; }
    }
    if (sect == nullptr || sect->allianceId.empty()) {
        rejection = {"no_alliance"};
        return {};
    }
    const state::Alliance* alliance = nullptr;
    for (const auto& a : gd.alliances) {
        if (a.id == sect->allianceId) { alliance = &a; break; }
    }
    if (alliance == nullptr) {
        rejection = {"no_alliance"};
        return {};
    }
    const state::Alliance removed = *alliance;
    auto& alliances = gd.alliances;
    alliances.erase(std::remove_if(alliances.begin(), alliances.end(),
                                   [&removed](const state::Alliance& a) {
                                       return a.id == removed.id;
                                   }),
                    alliances.end());
    for (auto& s : gd.worldMapSects) {
        const bool isMember = std::find(removed.sectIds.begin(),
                                        removed.sectIds.end(), s.id) !=
                              removed.sectIds.end();
        if (isMember) {
            s.allianceId.clear();
            s.allianceStartYear = 0;
        }
    }
    settle_util::recordGameEvent(state, "WORLD", "alliance_break",
                                 "与" + sect->name + "解除同盟");
    return {true};
}

/// 附属请求事务（Kotlin VassalService.requestVassalContract 等价——
/// 资格门控后 SYSTEM 1×nextDouble 恒抽取（aiPower<=0 概率为 0 但仍掷骰，
/// 与 Kotlin calculateVassalChance→rng.nextDouble 同位）；成功写相识 +
/// VassalContract(establishedYear, lastTributeYear=0)）。
/// 返回 TxRejection = 资格失败（零抽取零写入）。
inline AllianceOutcome requestVassalTransaction(state::GameState& state,
                                                rng::RngManager& rng,
                                                ecs::World& world,
                                                const std::string& sectId,
                                                TxRejection& rejection) {
    state::GameData& gd = state.gameData;
    // Kotlin failsVassalContractEligibility（文件级纯函数，同序）
    const state::WorldSect* aiSect = nullptr;
    for (const auto& s : gd.worldMapSects) {
        if (s.id == sectId) { aiSect = &s; break; }
    }
    if (aiSect == nullptr || aiSect->isPlayerSect) {
        rejection = {"eligibility"};
        return {};
    }
    bool alreadyVassal = false;
    for (const auto& c : gd.vassalContracts) {
        if (c.vassalSectId == sectId) { alreadyVassal = true; break; }
    }
    if (alreadyVassal) {
        rejection = {"eligibility"};
        return {};
    }
    for (const auto& alliance : gd.alliances) {
        const bool hasPlayer = std::find(alliance.sectIds.begin(),
                                         alliance.sectIds.end(), "player") !=
                               alliance.sectIds.end();
        const bool hasSect = std::find(alliance.sectIds.begin(),
                                       alliance.sectIds.end(), sectId) !=
                             alliance.sectIds.end();
        if (hasPlayer && hasSect) {
            rejection = {"eligibility"};
            return {};
        }
    }

    // Kotlin 计算链（资格通过后恒抽取）：战力/好感/近 3 年战绩 → VASSAL 概率
    const int64_t playerPower = ::gamecore::system::detail::calculateSectPower(
        state.disciples, world);
    const int64_t aiPower =
        ::gamecore::system::detail::calculateAiSectPower(state.aiSectDisciples,
                                                         sectId);
    const state::WorldSect* playerSect = findPlayerSect(gd);
    if (playerSect == nullptr) {
        // Kotlin `?: return false`（掷骰前早退，零抽取）
        rejection = {"player_sect_missing"};
        return {};
    }
    const int32_t favor =
        findFavor(gd.sectRelations, playerSect->id, sectId);
    const auto counts = ::gamecore::system::detail::countRecentBattleRecords(
        gd.sectBattleRecords, gd.gameYear);
    // Kotlin calculateVassalChance：aiPower<=0 → 0.0；NaN/Inf → 0.0；
    // 否则 VASSAL_PROFILE（无个性修正）
    double chance = 0.0;
    const double powerRatio =
        static_cast<double>(playerPower) / static_cast<double>(aiPower);
    if (aiPower > 0 && std::isfinite(powerRatio)) {
        chance = sectDecisionChance(vassalDecisionProfile(), powerRatio,
                                    counts.conquest, counts.lostSect,
                                    counts.battleWin, counts.battleLoss,
                                    ::gamecore::system::detail::favorLevelOrdinal(favor),
                                    -1);
    }

    rng::DeterministicRng& rngSystem = rng.getRng(rng::RngPartition::kSystem);
    const bool success = rngSystem.nextDouble() < chance;
    if (!success) return {false};

    gd.sectRelations =
        setAcquainted(gd.sectRelations, playerSect->id, sectId, gd.gameYear);
    state::VassalContract contract;
    contract.vassalSectId = sectId;
    contract.establishedYear = gd.gameYear;
    contract.lastTributeYear = 0;
    gd.vassalContracts.push_back(std::move(contract));
    return {true};
}

/// 解除附属事务（Kotlin VassalService.dissolveVassalContract 等价——
/// 过滤移除，恒返回 true，零 RNG）
inline AllianceOutcome dissolveVassalTransaction(state::GameState& state,
                                                 const std::string& sectId) {
    auto& contracts = state.gameData.vassalContracts;
    contracts.erase(std::remove_if(contracts.begin(), contracts.end(),
                                   [&sectId](const state::VassalContract& c) {
                                       return c.vassalSectId == sectId;
                                   }),
                    contracts.end());
    return {true};
}

}  // namespace diplomacy_tx

}  // namespace gamecore::system

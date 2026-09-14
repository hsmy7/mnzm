#pragma once
#ifndef GAMECORE_SYSTEM_SECT_ATTACK_DECISION_H_
#define GAMECORE_SYSTEM_SECT_ATTACK_DECISION_H_

#include <algorithm>
#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/month_settlement.h"   // detail::sectPowerOfDisciple / favorLevelOrdinal / kAiMinDisciplesForAttack
#include "gamecore/system/sect_decision.h"      // gamecore::system::sectDecisionChance / attackDecisionProfile

// ============================================================
// AI 宗门攻击决策下沉（Kotlin AISectAttackManager 的
//   checkAttackConditions / decidePlayerAttack 等价移植）
//
// 确定性红线（BATTLE 分区 RNG 行序）：
//   1. checkAttackConditions 仅当**通过全部前置门**才消费 1 次 kBattle.nextDouble()
//      （`rng.nextDouble() < chance`）——前置门早退（身份/人数/联盟/守军战力 0）
//      一律**不消费**，与 Kotlin `return` 早退逐位一致。
//   2. decidePlayerAttack 对每个攻击者恰消费 1 次 kBattle.nextDouble()
//      （通过 gates + powerRatio 非 null 之后）；gates/powerRatio 早退不消费。
//   3. 计算顺序（sort/短路 && 从左到右）与 Kotlin 完全一致；禁 unordered_map 参与迭代。
//
// 复用的既有 C++ 等价：
//   - details::sectPowerOfDisciple（Kotlin calculateDisciplePower(aggregate, null)）
//   - details::favorLevelOrdinal（Kotlin SectRelationLevel.fromFavor → ordinal）
//   - gamecore::system::sectDecisionChance（Kotlin IntelligentSectDecisionEngine.calculateChance）
// ============================================================
namespace gamecore::system::detail {

/// 好感度查询（Kotlin FavorDomain.findFavor：双向匹配首条，缺失默认 0——注意异于
/// breakawayFavor 的 50，攻击决策必须用 0 默认）
inline int32_t findFavor(const std::vector<state::SectRelation>& relations,
                         const std::string& idA, const std::string& idB) {
    for (const auto& r : relations) {
        if ((r.sectId1 == idA && r.sectId2 == idB) ||
            (r.sectId1 == idB && r.sectId2 == idA)) {
            return r.favor;
        }
    }
    return 0;
}

/// 弟子列表总战力（Kotlin calculateSectPower over List<Disciple>：filter isAlive + sumOf Long）
inline int64_t sectPowerFromList(const std::vector<state::Disciple>& disciples) {
    int64_t power = 0;
    for (const auto& d : disciples) {
        if (!d.isAlive) continue;
        power += sectPowerOfDisciple(d);
    }
    return power;
}

/// 近 3 年战报四类计数（Kotlin recentRecords.count：year >= gameYear - 3）
struct BattleRecordCounts {
    int32_t conquest = 0;
    int32_t lostSect = 0;
    int32_t battleWin = 0;
    int32_t battleLoss = 0;
};

inline BattleRecordCounts countRecentBattleRecords(
    const std::vector<state::SectBattleRecord>& records, int32_t gameYear) {
    BattleRecordCounts c;
    for (const auto& r : records) {
        if (r.year < gameYear - 3) continue;
        if (r.type == "CONQUEST") { ++c.conquest; }
        else if (r.type == "LOST_SECT") { ++c.lostSect; }
        else if (r.type == "BATTLE_WIN") { ++c.battleWin; }
        else if (r.type == "BATTLE_LOSS") { ++c.battleLoss; }
    }
    return c;
}

/// AI 名弟子列表+门（Kotlin filter isAlive；检查后条件未捕获空队返回空 vector）
inline std::vector<state::Disciple> aliveDisciplesOf(
    const std::map<std::string, std::vector<state::Disciple>>& aiDisciples,
    const std::string& sectId) {
    const auto it = aiDisciples.find(sectId);
    if (it == aiDisciples.end()) return {};
    std::vector<state::Disciple> alive;
    alive.reserve(it->second.size());
    for (const auto& d : it->second) {
        if (d.isAlive) alive.push_back(d);
    }
    return alive;
}

/// AI 能否攻击目标（Kotlin AISectAttackManager.checkAttackConditions；返回 bool，消费 1 次 BATTLE nextDouble）
/// defender 为玩家占领时守军来自 playerGarrison（Kotlin playerGarrisonMap）；否则来自 aiSectDisciples。
inline bool checkAttackConditions(
    GameState& state,
    const state::WorldSect& attacker,
    const state::WorldSect& defender,
    const std::map<std::string, std::vector<state::Disciple>>& playerGarrison,
    rng::RngManager& rng) {
    if (attacker.id == defender.id) return false;

    const auto& gameData = state.gameData;
    const std::vector<state::Disciple> attackerDisciples =
        aliveDisciplesOf(state.aiSectDisciples, attacker.id);
    if (static_cast<int32_t>(attackerDisciples.size()) < kAiMinDisciplesForAttack) return false;

    // 同联盟不攻击（硬约束；allianceId 空则放行）
    if (!attacker.allianceId.empty() && attacker.allianceId == defender.allianceId) return false;

    const int64_t attackerPower = sectPowerFromList(attackerDisciples);

    const std::vector<state::Disciple> defenderDisciples = defender.isPlayerOccupied
        ? [&]() {
              const auto it = playerGarrison.find(defender.id);
              return it == playerGarrison.end() ? std::vector<state::Disciple>{} : it->second;
          }()
        : aliveDisciplesOf(state.aiSectDisciples, defender.id);
    const int64_t defenderPower = sectPowerFromList(defenderDisciples);
    if (defenderPower <= 0) return false;

    const double powerRatio = static_cast<double>(attackerPower) / static_cast<double>(defenderPower);

    const int32_t favor = findFavor(gameData.sectRelations, attacker.id, defender.id);
    const int32_t favorLevel = favorLevelOrdinal(favor);
    const auto pIt = gameData.aiSectPersonalities.find(attacker.id);
    const int32_t personality = pIt == gameData.aiSectPersonalities.end() ? 1 : pIt->second;  // 默认 BALANCED=1

    const BattleRecordCounts rc = countRecentBattleRecords(gameData.sectBattleRecords, gameData.gameYear);
    const double chance = sectDecisionChance(
        attackDecisionProfile(), powerRatio, rc.conquest, rc.lostSect, rc.battleWin, rc.battleLoss,
        favorLevel, personality);

    return rng.getRng(rng::RngPartition::kBattle).nextDouble() < chance;
}

/// AI 攻玩家决策（Kotlin decidePlayerAttack：注意态/生成的预警——返回决策）
enum class PlayerAttackDecisionType { kSkip, kGenerateWarning };
struct PlayerAttackDecision {
    PlayerAttackDecisionType type = PlayerAttackDecisionType::kSkip;
    std::string attackerSectId;
    std::string attackerSectName;
};

/// AI 攻玩家前置六道闸（Kotlin passesAttackerGates：附庸/预警/冷却/人数/联盟；未通过返回空）
inline std::vector<state::Disciple> passesAttackerGates(
    GameState& state, const state::WorldSect& attacker, int32_t nowMonth) {
    const auto& gameData = state.gameData;
    const std::vector<state::Disciple> aliveAttackers =
        aliveDisciplesOf(state.aiSectDisciples, attacker.id);

    const auto playerSectIt = std::find_if(
        gameData.worldMapSects.begin(), gameData.worldMapSects.end(),
        [](const state::WorldSect& s) { return s.isPlayerSect; });

    const auto cooldownIt = gameData.sectAttackCooldowns.find(attacker.id);
    const bool cooldownOk = (cooldownIt == gameData.sectAttackCooldowns.end()) ||
                            (nowMonth >= cooldownIt->second);

    bool hasWarning = false;
    for (const auto& w : gameData.activeAttackWarnings) {
        if (w.attackerSectId == attacker.id) { hasWarning = true; break; }
    }

    const bool passes = gameData.suzerainSectId != attacker.id &&
        !hasWarning &&
        cooldownOk &&
        static_cast<int32_t>(aliveAttackers.size()) >= kAiMinDisciplesForAttack &&
        (attacker.allianceId.empty() ||
         (playerSectIt != gameData.worldMapSects.end() &&
          playerSectIt->allianceId != attacker.allianceId));

    if (!passes) return {};
    return aliveAttackers;
}

/// AI 攻玩家战力比守军池（Kotlin computePowerRatio 的 defenderDisciples
/// 数据源修正）：Kotlin 休眠链读 aiDisciplesMap[playerSectId]——世界生成
/// 不含玩家宗门条目 → 恒空 → 守军战力 0 → 决策永不触发（双实现假象的
/// 构成部分，P2-18 前置核实结论）。AUTHORITATIVE 复活后改读 DiscipleStore
/// 玩家弟子权威存储（与防守战守方同源；仅 isAlive 过滤，无状态排除——
/// 与 Kotlin computePowerRatio 的 filter isAlive 口径一致）。
inline std::vector<state::Disciple> playerDefenders(
    const state::DiscipleStore& ds) {
    std::vector<state::Disciple> out;
    out.reserve(ds.size());
    for (std::size_t row = 0; row < ds.size(); ++row) {
        if (ds.isAlive[row] == 1) out.push_back(ds.materialize(row));
    }
    return out;
}

/// AI 攻玩家决策主入口（Kotlin AISectAttackManager.decidePlayerAttack；返回预警决策）
inline PlayerAttackDecision decidePlayerAttack(GameState& state, rng::RngManager& rng) {
    const auto& gameData = state.gameData;
    // Kotlin GameData.isPlayerProtected 计算属性（非存储字段）：派生自底层保护状态
    constexpr int32_t kPlayerProtectionYears = 100;   // GameConfig.PlayerProtection.PROTECTION_YEARS
    const bool isProtected = gameData.playerProtectionEnabled &&
        !gameData.playerHasAttackedAI &&
        (gameData.gameYear - gameData.playerProtectionStartYear) < kPlayerProtectionYears;
    if (isProtected) return PlayerAttackDecision{};

    const auto playerSectIt = std::find_if(
        gameData.worldMapSects.begin(), gameData.worldMapSects.end(),
        [](const state::WorldSect& s) { return s.isPlayerSect; });
    if (playerSectIt == gameData.worldMapSects.end()) return PlayerAttackDecision{};
    const std::string playerSectId = playerSectIt->id;
    const int32_t nowMonth = gameData.gameYear * 12 + gameData.gameMonth;

    for (const auto& attacker : gameData.worldMapSects) {
        if (attacker.isPlayerSect) continue;

        const std::vector<state::Disciple> aliveAttackers =
            passesAttackerGates(state, attacker, nowMonth);
        if (aliveAttackers.empty()) continue;

        const int64_t attackerPower = sectPowerFromList(aliveAttackers);
        const std::vector<state::Disciple> defense =
            playerDefenders(state.disciples);
        const int64_t defenderPower = sectPowerFromList(defense);
        if (defenderPower <= 0) continue;
        const double powerRatio = static_cast<double>(attackerPower) / static_cast<double>(defenderPower);

        // computeAttackChance：好感/战绩/个性
        const int32_t favor = findFavor(gameData.sectRelations, attacker.id, playerSectId);
        const int32_t favorLevel = favorLevelOrdinal(favor);
        const auto pIt = gameData.aiSectPersonalities.find(attacker.id);
        const int32_t personality = pIt == gameData.aiSectPersonalities.end() ? 1 : pIt->second;
        const BattleRecordCounts rc =
            countRecentBattleRecords(gameData.sectBattleRecords, gameData.gameYear);
        const double chance = sectDecisionChance(
            attackDecisionProfile(), powerRatio, rc.conquest, rc.lostSect, rc.battleWin,
            rc.battleLoss, favorLevel, personality);

        if (rng.getRng(rng::RngPartition::kBattle).nextDouble() < chance) {
            return PlayerAttackDecision{PlayerAttackDecisionType::kGenerateWarning,
                                        attacker.id, attacker.name};
        }
    }
    return PlayerAttackDecision{};
}

// ── G7 剩余：战胜后占领判定下沉（Kotlin AISectAttackManager 占领判定） ──
//
// Kotlin executeSectBattleCore（AI vs AI）的占领判定——**纯确定性、零 RNG**：
//   allDefenderDisciples = allSectDisciples.filter { it.isAlive && it.id !in deadDefenderIds }
//   highRealmAllDead      = allDefenderDisciples.filter { it.realm <= kHighRealmOccupiableMax }.isEmpty()
//   canOccupy             = winner == ATTACKER && highRealmAllDead
// 另 executePlayerSectBattle（AI 攻玩家）只有 canOccupy = winner == ATTACKER（无高阶门槛）。
//
// 阈值魔数 5（realm <= 5 视为"高阶战力"= 仙人~中层，战败后仍能守卫宗门者）提为命名常量
//（编码规范 0.4）。realm 语义 0=仙人 … 9=炼气（battle.h / models.h 已对齐）。
constexpr int32_t kHighRealmOccupiableMax = 5;

/// 高阶弟子是否全灭（Kotlin highRealmAllDead：防守方宗门池中存活且未战死的
/// 弟子的 realm 均 > kHighRealmOccupiableMax）。
inline bool highRealmAllDead(
    const std::vector<state::Disciple>& allDefenderDisciples,
    const std::vector<std::string>& deadDefenderIds) {
    for (const auto& d : allDefenderDisciples) {
        if (!d.isAlive) continue;
        const bool dead = std::find(deadDefenderIds.begin(), deadDefenderIds.end(), d.id) !=
                          deadDefenderIds.end();
        if (dead) continue;
        if (d.realm <= kHighRealmOccupiableMax) return false;
    }
    return true;
}

/// AI vs AI 占领判定（Kotlin executeSectBattleCore：winner==ATTACKER && 高阶全灭）
inline bool computeCanOccupy(
    bool winnerIsAttacker, const std::vector<state::Disciple>& allDefenderDisciples,
    const std::vector<std::string>& deadDefenderIds) {
    return winnerIsAttacker && highRealmAllDead(allDefenderDisciples, deadDefenderIds);
}

}  // namespace gamecore::system::detail

// 解析完成标记（month_settlement.h 文件尾据以判定决策符号是否就绪，
// 决定是否包含 sect_defense_battle.h——详见其文件尾注释）
#define GAMECORE_SYSTEM_SECT_ATTACK_DECISION_COMPLETED_
#endif  // GAMECORE_SYSTEM_SECT_ATTACK_DECISION_H_

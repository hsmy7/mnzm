#include "gamecore/game_core.h"

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/system/battle.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/lifecycle.h"
#include "gamecore/system/spirit_field.h"

// ============================================================
// GameCore::execute — ActionId 分发表（Kotlin→C++ 迁移批次 9）
//
// 批次 4-8 已落地的 C++ 系统经此统一入口被 Kotlin 转发层调用。
// 参数/结果一律 JSON（nlohmann/json ↔ kotlinx.serialization），
// 与 ActionIds.kt / action_ids.h 同源（scripts/gen-action-ids.mjs）。
//
// 结果信封（sealed 语义）：
//   {"status":"success","data":{...}}     —— 成功
//   {"status":"failure","code":"...",...}  —— 失败（错误码 + 细节）
// ============================================================
namespace gamecore {

namespace {

using gamecore::system::DeductStatus;
using gamecore::system::SpiritStoneExchange;
using gamecore::system::SpiritStoneGrade;
using gamecore::system::SpiritStoneOperation;
using gamecore::system::SpiritStoneWallet;
using gamecore::system::spiritStoneGradeFromName;
using gamecore::system::spiritStoneCount;

/// 成功信封
nlohmann::json ok(nlohmann::json data) {
    return {{"status", "success"}, {"data", std::move(data)}};
}

/// 失败信封
nlohmann::json fail(const std::string& code, const std::string& message) {
    return {{"status", "failure"}, {"code", code}, {"message", message}};
}

/// 从 JSON 构造效果 map（{"key": value}）
std::map<std::string, double> effectsFromJson(const nlohmann::json& j) {
    std::map<std::string, double> out;
    if (j.is_object()) {
        for (auto it = j.begin(); it != j.end(); ++it) {
            out[it.key()] = it.value().get<double>();
        }
    }
    return out;
}

/// 钱包操作（ActionIds.WALLET_*）
nlohmann::json handleWallet(GameCore* core, int32_t actionId,
                            const nlohmann::json& params) {
    auto& gd = core->state().gameData;
    const std::string gradeName = params.value("grade", "LOW");
    const SpiritStoneGrade grade = spiritStoneGradeFromName(gradeName);
    switch (actionId) {
        case action::WALLET_ADD: {
            const int64_t amount = params.at("amount").get<int64_t>();
            const std::string source = params.value("source", "Internal");
            const int64_t balance = SpiritStoneWallet::add(gd, amount, grade, source);
            return ok({{"balance", balance}});
        }
        case action::WALLET_DEDUCT: {
            const int64_t amount = params.at("amount").get<int64_t>();
            const std::string reason = params.value("reason", "Internal");
            const std::string source = params.value("source", "Internal");
            const bool autoConvert = params.value("autoConvert", true);
            const auto r = SpiritStoneWallet::deduct(gd, amount, grade, reason, source, autoConvert);
            switch (r.status) {
                case DeductStatus::kSuccess:
                    return ok({{"balanceAfter", r.balanceAfter}});
                case DeductStatus::kInsufficient:
                    return fail("INSUFFICIENT", "灵石不足");
                case DeductStatus::kInvalid:
                    return fail("INVALID", "参数无效");
            }
            return fail("INTERNAL", "unreachable");
        }
        case action::WALLET_BATCH: {
            std::vector<SpiritStoneOperation> ops;
            for (const auto& o : params.at("ops")) {
                SpiritStoneOperation so;
                so.delta = o.at("delta").get<int64_t>();
                so.grade = spiritStoneGradeFromName(o.value("grade", "LOW"));
                so.reason = o.value("reason", "Internal");
                so.source = o.value("source", "Internal");
                ops.push_back(so);
            }
            const bool autoConvert = params.value("autoConvert", false);
            const auto r = SpiritStoneWallet::batch(gd, ops, autoConvert);
            nlohmann::json results = nlohmann::json::array();
            for (const auto& d : r.results) {
                switch (d.status) {
                    case DeductStatus::kSuccess:
                        results.push_back({{"status", "success"}, {"balanceAfter", d.balanceAfter}});
                        break;
                    case DeductStatus::kInsufficient:
                        results.push_back({{"status", "insufficient"},
                                           {"balance", d.balance}, {"required", d.required}});
                        break;
                    case DeductStatus::kInvalid:
                        results.push_back({{"status", "invalid"}});
                        break;
                }
            }
            return ok({{"successCount", r.successCount}, {"failedCount", r.failedCount},
                       {"results", results}});
        }
        case action::WALLET_BALANCE:
            return ok({{"balance", spiritStoneCount(gd, grade)}});
        case action::WALLET_TOTAL_SELL_VALUE:
            return ok({{"value", SpiritStoneExchange::totalSellValue(
                          gd.spiritStones, gd.midGradeSpiritStones, gd.highGradeSpiritStones)}});
        default:
            return fail("UNKNOWN_ACTION", "wallet action " + std::to_string(actionId));
    }
}

/// 库存操作（ActionIds.INV_*）
nlohmann::json handleInventory(GameCore* core, int32_t actionId,
                               const nlohmann::json& params) {
    auto& state = core->state();
    gamecore::system::OverflowMailCollector mail;
    const std::string source = params.value("source", "unknown");
    const bool suppressed = params.value("suppressed", false);
    nlohmann::json data;

    auto invResult = [&](const auto& r) {
        nlohmann::json j;
        switch (r.status) {
            case gamecore::system::InventoryStatus::kSuccess: j["status"] = "success"; break;
            case gamecore::system::InventoryStatus::kPartial: j["status"] = "partial"; break;
            case gamecore::system::InventoryStatus::kFailure: j["status"] = "failure"; break;
        }
        j["overflow"] = r.overflow;
        j["errorType"] = static_cast<int>(r.error.type);
        return j;
    };

    switch (actionId) {
        case action::INV_ADD_EQUIPMENT_STACK: {
            gamecore::state::EquipmentStack item;
            item.id = params.value("id", "");
            item.name = params.at("name").get<std::string>();
            item.rarity = params.value("rarity", 1);
            item.slot = params.value("slot", "WEAPON");
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addEquipmentStack(
                state, item, mail, source, suppressed);
            data = invResult(r);
            break;
        }
        case action::INV_ADD_MANUAL_STACK: {
            gamecore::state::ManualStack item;
            item.id = params.value("id", "");
            item.name = params.at("name").get<std::string>();
            item.rarity = params.value("rarity", 1);
            item.type = params.value("type", "MIND");
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addManualStack(
                state, item, mail, source, suppressed,
                params.value("merge", true));
            data = invResult(r);
            break;
        }
        case action::INV_ADD_PILL: {
            gamecore::state::Pill item;
            item.id = params.value("id", "");
            item.name = params.at("name").get<std::string>();
            item.rarity = params.value("rarity", 1);
            item.category = params.value("category", "CULTIVATION");
            item.grade = params.value("grade", "MEDIUM");
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addPill(
                state, item, mail, source, suppressed,
                params.value("merge", true));
            data = invResult(r);
            break;
        }
        case action::INV_ADD_MATERIAL: {
            gamecore::state::Material item;
            item.id = params.value("id", "");
            item.name = params.at("name").get<std::string>();
            item.rarity = params.value("rarity", 1);
            item.category = params.value("category", "BEAST_HIDE");
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addMaterial(
                state, item, mail, source, suppressed,
                params.value("merge", true));
            data = invResult(r);
            break;
        }
        case action::INV_ADD_HERB: {
            gamecore::state::Herb item;
            item.id = params.value("id", "");
            item.name = params.at("name").get<std::string>();
            item.rarity = params.value("rarity", 1);
            item.category = params.value("category", "grass");
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addHerb(
                state, item, mail, source, suppressed,
                params.value("merge", true));
            data = invResult(r);
            break;
        }
        case action::INV_ADD_SEED: {
            gamecore::state::Seed item;
            item.id = params.value("id", "");
            item.name = params.at("name").get<std::string>();
            item.rarity = params.value("rarity", 1);
            item.growTime = params.value("growTime", 3);
            item.yield = params.value("yield", 1);
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addSeed(
                state, item, mail, source, suppressed,
                params.value("merge", true));
            data = invResult(r);
            break;
        }
        case action::INV_ADD_STORAGE_BAG: {
            gamecore::state::StorageBag item;
            item.id = params.value("id", "");
            item.name = params.value("name", "储物袋");
            item.rarity = params.value("rarity", 1);
            item.quantity = params.at("quantity").get<int32_t>();
            const auto r = gamecore::system::addStorageBag(
                state, item, mail, source, suppressed);
            data = invResult(r);
            break;
        }
        case action::INV_REMOVE_EQUIPMENT: {
            const bool ok = gamecore::system::removeEquipment(
                state, params.at("id").get<std::string>(),
                params.value("quantity", 1), params.value("bypassLock", false));
            data = {{"removed", ok}};
            break;
        }
        case action::INV_REMOVE_MANUAL: {
            const bool ok = gamecore::system::removeManual(
                state, params.at("id").get<std::string>(),
                params.value("quantity", 1), params.value("bypassLock", false));
            data = {{"removed", ok}};
            break;
        }
        case action::INV_REMOVE_PILL: {
            const bool ok = gamecore::system::removePill(
                state, params.at("id").get<std::string>(),
                params.value("quantity", 1), params.value("bypassLock", false));
            data = {{"removed", ok}};
            break;
        }
        case action::INV_REMOVE_MATERIAL: {
            const bool ok = gamecore::system::removeMaterial(
                state, params.at("id").get<std::string>(),
                params.value("quantity", 1), params.value("bypassLock", false));
            data = {{"removed", ok}};
            break;
        }
        case action::INV_REMOVE_HERB: {
            const bool ok = gamecore::system::removeHerb(
                state, params.at("id").get<std::string>(),
                params.value("quantity", 1), params.value("bypassLock", false));
            data = {{"removed", ok}};
            break;
        }
        case action::INV_REMOVE_SEED: {
            const bool ok = gamecore::system::removeSeed(
                state, params.at("id").get<std::string>(),
                params.value("quantity", 1), params.value("bypassLock", false));
            data = {{"removed", ok}};
            break;
        }
        case action::INV_CAN_ADD_ITEM:
            data = {{"canAdd", gamecore::system::canAddItem(state)}};
            break;
        case action::INV_CAPACITY_INFO:
            data = {
                {"currentSlots", gamecore::system::computeSlotCount(state)},
                {"maxSlots", gamecore::system::computeMaxSlots(state)},
            };
            break;
        default:
            return fail("UNKNOWN_ACTION", "inventory action " + std::to_string(actionId));
    }
    data["overflowMails"] = mail.all().size();
    return ok(data);
}

/// 灵田收获（ActionIds.SPIRIT_FIELD_HARVEST）
nlohmann::json handleSpiritField(GameCore* core, const nlohmann::json& params) {
    auto& state = core->state();
    if (params.contains("year")) state.gameData.gameYear = params.at("year").get<int32_t>();
    if (params.contains("month")) state.gameData.gameMonth = params.at("month").get<int32_t>();
    gamecore::system::OverflowMailCollector mail;
    const auto r = gamecore::system::processSpiritFieldHarvest(state, core->rng(), mail);
    return ok({{"herbsHarvested", r.herbsHarvested},
               {"seedsHarvested", r.seedsHarvested},
               {"plantsCompleted", r.plantsCompleted},
               {"overflowMails", mail.all().size()}});
}

/// 弟子/修炼/突破/生命周期操作（ActionIds.DISCIPLE_*）
nlohmann::json handleDisciple(GameCore* core, int32_t actionId,
                              const nlohmann::json& params) {
    using gamecore::disciple::BaseStatsInput;
    using gamecore::disciple::BreakthroughZones;
    using gamecore::disciple::CultivationSpeedZones;
    switch (actionId) {
        case action::DISCIPLE_BASE_STATS: {
            BaseStatsInput in;
            in.realm = params.value("realm", 9);
            in.realmLayer = params.value("realmLayer", 1);
            in.hpVariance = params.value("hpVariance", 0);
            in.mpVariance = params.value("mpVariance", 0);
            in.physicalAttackVariance = params.value("physicalAttackVariance", 0);
            in.magicAttackVariance = params.value("magicAttackVariance", 0);
            in.physicalDefenseVariance = params.value("physicalDefenseVariance", 0);
            in.magicDefenseVariance = params.value("magicDefenseVariance", 0);
            in.speedVariance = params.value("speedVariance", 0);
            in.intelligence = params.value("intelligence", 0);
            in.charm = params.value("charm", 0);
            in.loyalty = params.value("loyalty", 0);
            in.comprehension = params.value("comprehension", 0);
            in.aptitude = params.value("aptitude", 50);
            in.teaching = params.value("teaching", 0);
            in.morality = params.value("morality", 0);
            in.mining = params.value("mining", 0);
            in.spiritPlanting = params.value("spiritPlanting", 0);
            in.artifactRefining = params.value("artifactRefining", 0);
            in.pillRefining = params.value("pillRefining", 0);
            if (params.contains("effects")) in.talentEffects = effectsFromJson(params.at("effects"));
            in.bloodHpBonusPct = params.value("bloodHpBonusPct", 0.0);
            in.bloodPhysicalAttackBonusPct = params.value("bloodPhysicalAttackBonusPct", 0.0);
            in.bloodMagicAttackBonusPct = params.value("bloodMagicAttackBonusPct", 0.0);
            in.bloodPhysicalDefenseBonusPct = params.value("bloodPhysicalDefenseBonusPct", 0.0);
            in.bloodMagicDefenseBonusPct = params.value("bloodMagicDefenseBonusPct", 0.0);
            in.bloodSpeedBonusPct = params.value("bloodSpeedBonusPct", 0.0);
            const auto s = gamecore::disciple::computeBaseStats(in);
            return ok({{"maxHp", s.maxHp}, {"maxMp", s.maxMp},
                       {"physicalAttack", s.physicalAttack}, {"magicAttack", s.magicAttack},
                       {"physicalDefense", s.physicalDefense}, {"magicDefense", s.magicDefense},
                       {"speed", s.speed}, {"critRate", s.critRate}});
        }
        case action::DISCIPLE_CULTIVATION_PER_PHASE: {
            CultivationSpeedZones zones;
            zones.aptitudeBonus = params.value("aptitudeBonus", 0.0);
            zones.resourceBonus = params.value("resourceBonus", 0.0);
            zones.socialBonus = params.value("socialBonus", 0.0);
            zones.statusBonus = params.value("statusBonus", 0.0);
            zones.temporaryBonus = params.value("temporaryBonus", 0.0);
            return ok({{"value", gamecore::disciple::calculateCultivationPerPhase(
                           params.value("realm", 9), params.value("rootCount", 1), zones)}});
        }
        case action::DISCIPLE_BREAKTHROUGH_CHANCE: {
            BreakthroughZones zones;
            zones.baseZone = params.value("baseZone", 0.0);
            zones.elderGuidance = params.value("elderGuidance", 0.0);
            zones.selfBonus = params.value("selfBonus", 0.0);
            zones.statusPenalty = params.value("statusPenalty", 0.0);
            zones.adFlatBonus = params.value("adFlatBonus", 0.0);
            return ok({{"value", gamecore::disciple::calculateBreakthroughChance(zones)}});
        }
        case action::DISCIPLE_MAX_AGE:
            return ok({{"value", gamecore::system::computeMaxAge(
                           params.value("lifespan", 0), params.value("realmMaxAge", 0),
                           params.value("lifespanBonus", 0.0))}});
        case action::DISCIPLE_CHECKPOINT: {
            auto& disciples = core->state().disciples;
            const std::string id = params.at("id").get<std::string>();
            const int32_t currentMonth = params.at("currentMonth").get<int32_t>();
            for (auto& d : disciples) {
                if (d.id == id) {
                    gamecore::system::checkpointDisciple(d, currentMonth);
                    return ok({{"checkpointed", true}});
                }
            }
            return ok({{"checkpointed", false}});
        }
        case action::DISCIPLE_ACCUMULATE_CULTIVATION: {
            auto& disciples = core->state().disciples;
            const std::string id = params.at("id").get<std::string>();
            const double rate = params.value("rate", 0.0);
            for (auto& d : disciples) {
                if (d.id == id) {
                    const double updated =
                        gamecore::system::accumulateCultivationPerPhase(d, rate);
                    return ok({{"cultivation", updated}});
                }
            }
            return fail("NOT_FOUND", "disciple " + id);
        }
        case action::DISCIPLE_AGE: {
            const auto out = gamecore::system::ageDisciple(
                params.value("age", 0), params.value("realmLayer", 1),
                params.value("maxAge", 80));
            return ok({{"age", out.age}, {"realmLayer", out.realmLayer}, {"dead", out.dead}});
        }
        case action::DISCIPLE_BREAKTHROUGH: {
            gamecore::state::Disciple d;
            d.id = params.value("id", "");
            d.realm = params.value("realm", 9);
            d.realmLayer = params.value("realmLayer", 1);
            d.cultivation = params.value("cultivation", 0.0);
            d.lifespan = params.value("lifespan", 80);
            d.isAlive = params.value("alive", true);
            const double chance = params.value("chance", 0.0);
            const int32_t gain = params.value("lifespanGain", 0);
            const int32_t currentMonth = params.value("currentMonth", 0);
            // 单次突破尝试（连续循环由 Kotlin 层编排——保持 execute 语义精简）
            if (gamecore::system::tryBreakthrough(d, chance, core->rng())) {
                d = gamecore::system::applyBreakthroughSuccess(d, gain);
                return ok({{"success", true}, {"realm", d.realm},
                           {"realmLayer", d.realmLayer}, {"cultivation", d.cultivation}});
            }
            d = gamecore::system::applyBreakthroughFailure(d);
            (void)currentMonth;
            return ok({{"success", false}, {"realm", d.realm},
                       {"realmLayer", d.realmLayer}, {"cultivation", d.cultivation}});
        }
        case action::DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH:
            return ok({{"value", gamecore::system::estimateMonthsToNextBreakthrough(
                           params.value("remaining", 0.0), params.value("rate", 0.0))}});
        default:
            return fail("UNKNOWN_ACTION", "disciple action " + std::to_string(actionId));
    }
}

/// 战斗操作（ActionIds.BATTLE_*）
nlohmann::json handleBattle(int32_t actionId, const nlohmann::json& params) {
    using gamecore::battle::DamageZones;
    switch (actionId) {
        case action::BATTLE_FINAL_DAMAGE: {
            DamageZones zones;
            if (params.contains("zones")) {
                const auto& z = params.at("zones");
                zones.attackBuffs = z.value("attackBuffs", 0.0);
                zones.damageAmplification = z.value("damageAmplification", 0.0);
                zones.damageReduction = z.value("damageReduction", 0.0);
                zones.physiqueDamageAmplification = z.value("physiqueDamageAmplification", 0.0);
                zones.physiqueCritDamageBonus = z.value("physiqueCritDamageBonus", 0.0);
                zones.physiqueDamageReduction = z.value("physiqueDamageReduction", 0.0);
                zones.physiqueDefenseBonus = z.value("physiqueDefenseBonus", 0.0);
                zones.affixDamageAmplification = z.value("affixDamageAmplification", 0.0);
                zones.affixCritDamageBonus = z.value("affixCritDamageBonus", 0.0);
                zones.affixDamageReduction = z.value("affixDamageReduction", 0.0);
                zones.affixDefenseBonus = z.value("affixDefenseBonus", 0.0);
                zones.realmGapDamageAmplification = z.value("realmGapDamageAmplification", 0.0);
                zones.realmGapDamageReduction = z.value("realmGapDamageReduction", 0.0);
                zones.majorRealmDamageAmplification = z.value("majorRealmDamageAmplification", 0.0);
            }
            return ok({{"value", gamecore::battle::calculateFinalDamage(
                           params.value("rawAttack", 0), params.value("defense", 0),
                           params.value("skillMultiplier", 1.0), zones,
                           params.value("isCrit", false), params.value("variance", 1.0))}});
        }
        case action::BATTLE_REALM_GAP_FACTORS: {
            const auto f = gamecore::battle::calculateRealmGapFactors(
                params.value("attackerRealm", 9), params.value("attackerLayer", 1),
                params.value("defenderRealm", 9), params.value("defenderLayer", 1));
            return ok({{"damageAmplification", f.damageAmplification},
                       {"damageReduction", f.damageReduction},
                       {"majorRealmDamageAmplification", f.majorRealmDamageAmplification}});
        }
        case action::BATTLE_CHECK_INSTANT_KILL:
            return ok({{"value", gamecore::battle::checkInstantKill(
                           params.value("attackerRealm", 9), params.value("defenderRealm", 9),
                           params.value("attackerLayer", 1), params.value("defenderLayer", 1))}});
        case action::BATTLE_DODGE_CHANCE:
            return ok({{"value", gamecore::battle::calculateDodgeChance(
                           params.value("attackerSpeed", 0), params.value("defenderSpeed", 0),
                           params.value("modifier", 0.5))}});
        case action::BATTLE_SHIELD_ABSORPTION: {
            const auto r = gamecore::battle::calculateShieldAbsorption(
                params.value("maxHp", 0), params.value("shieldValue", 0.0),
                params.value("shieldActive", false), params.value("damage", 0));
            return ok({{"absorbed", r.absorbed}, {"remainingDamage", r.remainingDamage},
                       {"remainingShield", r.remainingShield}});
        }
        case action::BATTLE_DOT:
            return ok({{"hp", gamecore::battle::applyDotDamage(
                           params.value("currentHp", 0), params.value("dotDamage", 0))}});
        case action::BATTLE_COOLDOWN_UPDATE: {
            std::vector<std::string> names;
            for (const auto& n : params.at("skillNames")) names.push_back(n.get<std::string>());
            std::vector<int32_t> cooldowns;
            for (const auto& c : params.at("currentCooldowns")) cooldowns.push_back(c.get<int32_t>());
            const auto out = gamecore::battle::updateCooldowns(
                names, cooldowns, params.value("usedSkillName", ""),
                params.value("cooldownOfUsed", 0));
            nlohmann::json j = nlohmann::json::array();
            for (int32_t c : out) j.push_back(c);
            return ok({{"cooldowns", j}});
        }
        default:
            return fail("UNKNOWN_ACTION", "battle action " + std::to_string(actionId));
    }
}

/// 内政操作（ActionIds.GOV_*）
nlohmann::json handleGovernment(GameCore* core, int32_t actionId,
                                const nlohmann::json& params) {
    auto& gd = core->state().gameData;
    switch (actionId) {
        case action::GOV_POLICY_COSTS: {
            const auto r = gamecore::system::processPolicyCosts(
                gd, params.value("discipleCount", 0),
                params.value("huashenBelowCount", 0));
            return ok({{"allPaid", r.allPaid},
                       {"disabledPolicies", r.disabledPolicies},
                       {"deducted", r.deducted}});
        }
        case action::GOV_POLICY_MONTHLY_EFFECTS: {
            const auto d = gamecore::system::policyMonthlyDeltas(gd.sectPolicies);
            return ok({{"loyaltyDelta", d.first}, {"moralityDelta", d.second}});
        }
        case action::GOV_SPIRIT_MINE_MONTHLY: {
            std::vector<int32_t> miningSkills;
            if (params.contains("miningSkills")) {
                for (const auto& m : params.at("miningSkills")) miningSkills.push_back(m.get<int32_t>());
            }
            std::vector<int32_t> deaconMorality;
            if (params.contains("deaconMorality")) {
                for (const auto& m : params.at("deaconMorality")) deaconMorality.push_back(m.get<int32_t>());
            }
            const auto zones = gamecore::system::buildSpiritMineZones(
                params.value("minerCount", 0), miningSkills, deaconMorality,
                params.value("spiritMineBoost", false));
            const auto [output, settled] =
                gamecore::system::settleSpiritMineProduction(gd, zones);
            return ok({{"output", output}, {"settled", settled},
                       {"lastSettledMonth", gd.spiritMineLastSettledMonth}});
        }
        case action::GOV_ANNUAL_SALARY: {
            std::vector<std::pair<int32_t, int32_t>> aliveRealms;
            if (params.contains("aliveRealms")) {
                for (const auto& e : params.at("aliveRealms")) {
                    aliveRealms.emplace_back(e.at(0).get<int32_t>(), e.at(1).get<int32_t>());
                }
            }
            std::map<int32_t, int32_t> salary;
            if (params.contains("yearlySalary")) {
                for (auto it = params.at("yearlySalary").begin();
                     it != params.at("yearlySalary").end(); ++it) {
                    salary[std::stoi(it.key())] = it.value().get<int32_t>();
                }
            }
            std::map<int32_t, bool> enabled;
            if (params.contains("yearlySalaryEnabled")) {
                for (auto it = params.at("yearlySalaryEnabled").begin();
                     it != params.at("yearlySalaryEnabled").end(); ++it) {
                    enabled[std::stoi(it.key())] = it.value().get<bool>();
                }
            }
            const auto plan = gamecore::system::calculateSalaryPlan(
                salary, enabled, aliveRealms);
            const int64_t paid = gamecore::system::payAnnualSalary(
                gd, plan, params.value("frugality", false));
            return ok({{"paid", paid}, {"totalRequired", plan.totalRequired}});
        }
        case action::GOV_ZONE_CALCULATE: {
            std::vector<double> zones;
            if (params.contains("zones")) {
                for (const auto& z : params.at("zones")) zones.push_back(z.get<double>());
            }
            return ok({{"value", gamecore::system::zoneCalculate(
                           params.value("base", 0.0), zones)}});
        }
        default:
            return fail("UNKNOWN_ACTION", "government action " + std::to_string(actionId));
    }
}

/// 探索/世界关卡操作（ActionIds.WORLD_LEVEL_*）
nlohmann::json handleExploration(GameCore* core, int32_t actionId,
                                 const nlohmann::json& params) {
    auto& state = core->state();
    switch (actionId) {
        case action::WORLD_LEVEL_MONTHLY: {
            const auto r = gamecore::system::processWorldLevelsMonthly(
                state.gameData.worldLevels,
                state.gameData.worldLevelLastRefreshMonth,
                params.value("year", state.gameData.gameYear),
                params.value("month", state.gameData.gameMonth),
                core->rng(), params.value("allowRefresh", false));
            state.gameData.worldLevels = r.levels;
            if (r.refreshed) {
                state.gameData.worldLevelLastRefreshMonth =
                    gamecore::system::toAbsoluteMonth(
                        params.value("year", state.gameData.gameYear),
                        params.value("month", state.gameData.gameMonth));
            }
            return ok({{"levelCount", r.levels.size()}, {"refreshed", r.refreshed}});
        }
        case action::WORLD_LEVEL_CHECK_EXPIRED: {
            gamecore::state::WorldLevel level;
            const auto& l = params.at("level");
            level.id = l.value("id", "");
            level.type = l.value("type", "BEAST");
            level.defeated = l.value("defeated", false);
            level.expiryYear = l.value("expiryYear", 0);
            level.expiryMonth = l.value("expiryMonth", 0);
            return ok({{"value", gamecore::system::checkLevelExpired(
                           level, params.value("year", 1), params.value("month", 1))}});
        }
        default:
            return fail("UNKNOWN_ACTION", "exploration action " + std::to_string(actionId));
    }
}

}  // namespace

std::string GameCore::execute(int32_t actionId, const std::string& paramsJson,
                              int64_t nowMs) {
    (void)nowMs;
    if (!initialized_) {
        return R"({"status":"failure","code":"kInternal","message":"GameCore not initialized"})";
    }
    try {
        nlohmann::json params = nlohmann::json::object();
        if (!paramsJson.empty()) {
            params = nlohmann::json::parse(paramsJson);
            if (params.is_null()) {
                params = nlohmann::json::object();  // "null" 输入 → 空对象（防 value() 306）
            }
        }
        nlohmann::json result;
        if (actionId >= action::WALLET_ADD && actionId <= action::WALLET_TOTAL_SELL_VALUE) {
            result = handleWallet(this, actionId, params);
        } else if (actionId >= action::INV_ADD_EQUIPMENT_STACK &&
                   actionId <= action::INV_CAPACITY_INFO) {
            result = handleInventory(this, actionId, params);
        } else if (actionId == action::SPIRIT_FIELD_HARVEST) {
            result = handleSpiritField(this, params);
        } else if (actionId >= action::DISCIPLE_BASE_STATS &&
                   actionId <= action::DISCIPLE_ESTIMATE_BREAKTHROUGH_MONTH) {
            result = handleDisciple(this, actionId, params);
        } else if (actionId >= action::BATTLE_FINAL_DAMAGE &&
                   actionId <= action::BATTLE_COOLDOWN_UPDATE) {
            result = handleBattle(actionId, params);
        } else if (actionId >= action::GOV_POLICY_COSTS &&
                   actionId <= action::GOV_ZONE_CALCULATE) {
            result = handleGovernment(this, actionId, params);
        } else if (actionId >= action::WORLD_LEVEL_MONTHLY &&
                   actionId <= action::WORLD_LEVEL_CHECK_EXPIRED) {
            result = handleExploration(this, actionId, params);
        } else {
            result = fail("NOT_IMPLEMENTED",
                          "action not implemented yet: " + std::to_string(actionId));
        }
        return result.dump();
    } catch (const std::exception& e) {
        logger_->log(LogLevel::kError, "GameCore",
                     std::string("execute failed: ") + e.what());
        return fail("INTERNAL", e.what()).dump();
    }
}

}  // namespace gamecore

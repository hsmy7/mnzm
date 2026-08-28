#include "gamecore/game_core.h"

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/system/battle.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/death_handler.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/lifecycle.h"
#include "gamecore/system/level_generator.h"
#include "gamecore/system/rarity_progression.h"
#include "gamecore/system/redeem_code.h"
#include "gamecore/system/secret_realm.h"
#include "gamecore/system/sect_decision.h"
#include "gamecore/system/sect_power.h"
#include "gamecore/system/sect_trade.h"
#include "gamecore/system/slot_cleanup.h"
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
    if (!mail.empty()) {
        // 溢出邮件草稿回传（批 8-2：Kotlin wrapper 经 OverflowMailSender 落库，
        // 与 Kotlin 原路径同一解析/投递通道——否则溢出物品在 native 通道丢失）
        nlohmann::json drafts = nlohmann::json::array();
        for (const auto& d : mail.all()) {
            drafts.push_back({
                {"source", d.source}, {"itemType", d.itemType}, {"itemName", d.itemName},
                {"itemId", d.itemId}, {"rarity", d.rarity}, {"quantity", d.quantity},
                {"category", d.category}, {"grade", d.grade}, {"slot", d.slot},
                {"type", d.type}, {"growTime", d.growTime}, {"yield", d.yield}
            });
        }
        data["overflowDrafts"] = std::move(drafts);
    }
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
            auto& ds = core->state().disciples;
            const std::string id = params.at("id").get<std::string>();
            const int32_t currentMonth = params.at("currentMonth").get<int32_t>();
            const auto row = ds.rowOf(id);
            if (!row.has_value()) return ok({{"checkpointed", false}});
            // checkpointDisciple 列直写（isAlive 守卫）
            if (ds.isAlive[*row] != 0) {
                ds.cultivationCheckpoints[*row] = ds.cultivations[*row];
                ds.cultivationCheckpointGameMonths[*row] = currentMonth;
            }
            return ok({{"checkpointed", true}});
        }
        case action::DISCIPLE_ACCUMULATE_CULTIVATION: {
            auto& ds = core->state().disciples;
            const std::string id = params.at("id").get<std::string>();
            const double rate = params.value("rate", 0.0);
            const auto row = ds.rowOf(id);
            if (!row.has_value()) {
                return fail("NOT_FOUND", "disciple " + id);
            }
            // accumulateCultivationPerPhase 列直写
            double cultivation = ds.cultivations[*row];
            if (ds.isAlive[*row] != 0 && rate > 0.0) {
                const double maxCultivation =
                    gamecore::system::computeMaxCultivation(
                        ds.realms[*row], ds.realmLayers[*row], cultivation);
                if (cultivation < maxCultivation) {
                    cultivation = std::min(cultivation + rate, maxCultivation);
                    ds.cultivations[*row] = cultivation;
                }
            }
            return ok({{"cultivation", cultivation}});
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

/// 关卡生成操作（计划 v2 阶段 4 批 4-1：ActionIds.LEVEL_*）
nlohmann::json handleLevelGeneration(GameCore* core, int32_t actionId,
                                     const nlohmann::json& params) {
    switch (actionId) {
        case action::LEVEL_SELECT_BEAST_REALM: {
            const int32_t* avg = nullptr;
            int32_t avgValue = 0;
            if (params.contains("playerAvgRealm") &&
                !params.at("playerAvgRealm").is_null()) {
                avgValue = params.at("playerAvgRealm").get<int32_t>();
                avg = &avgValue;
            }
            const int32_t realm = gamecore::system::selectBeastRealm(
                core->rng(), params.value("year", 1), avg);
            return ok({{"realm", realm}});
        }
        case action::LEVEL_GENERATE_LEVELS: {
            std::vector<gamecore::state::WorldSect> sects;
            if (params.contains("sects")) {
                for (const auto& s : params.at("sects")) {
                    sects.push_back(s.get<gamecore::state::WorldSect>());
                }
            }
            std::vector<gamecore::state::WorldLevel> existing;
            if (params.contains("existingLevels")) {
                for (const auto& l : params.at("existingLevels")) {
                    existing.push_back(l.get<gamecore::state::WorldLevel>());
                }
            }
            const int32_t* avg = nullptr;
            int32_t avgValue = 0;
            if (params.contains("playerAvgRealm") &&
                !params.at("playerAvgRealm").is_null()) {
                avgValue = params.at("playerAvgRealm").get<int32_t>();
                avg = &avgValue;
            }
            const auto r = gamecore::system::generateWorldLevels(
                core->rng(), sects, params.value("year", 1),
                params.value("month", 1), existing,
                params.value("maxNewLevels", gamecore::system::kDefaultMaxNewLevels),
                avg);
            nlohmann::json levels = nlohmann::json::array();
            for (const auto& l : r.levels) {
                levels.push_back(l);
            }
            return ok({{"levels", levels}, {"generated", r.generated}});
        }
        default:
            return fail("UNKNOWN_ACTION", "level generation action " + std::to_string(actionId));
    }
}

/// 兑换码与邮件附件（计划 v2 阶段 4 批 4-6：ActionIds.REDEEM_* / MAIL_*）
nlohmann::json handleRedeemCode(GameCore* core, int32_t actionId,
                                const nlohmann::json& params) {
    using gamecore::state::MailAttachment;
    auto& sr = core->rng().getRng(gamecore::rng::RngPartition::kMail);
    switch (actionId) {
        case action::REDEEM_VALIDATE_INPUT: {
            const std::string message = gamecore::system::validateRedeemInput(
                params.at("code").get<std::string>());
            return ok({{"valid", message.empty()}, {"message", message}});
        }
        case action::REDEEM_ROLL_SPIRIT_ROOT: {
            const std::string* typePtr = nullptr;
            std::string typeValue;
            if (params.contains("spiritRootType") && !params.at("spiritRootType").is_null()) {
                typeValue = params.at("spiritRootType").get<std::string>();
                typePtr = &typeValue;
            }
            int32_t* countPtr = nullptr;
            int32_t countValue = 0;
            if (params.contains("spiritRootCount") && !params.at("spiritRootCount").is_null()) {
                countValue = params.at("spiritRootCount").get<int32_t>();
                countPtr = &countValue;
            }
            return ok({{"spiritRoot", gamecore::system::resolveSpiritRoot(
                                          sr, typePtr, countPtr)}});
        }
        case action::REDEEM_RESOLVE_AGE_LIFESPAN: {
            const auto r = gamecore::system::resolveAgeAndLifespan(
                sr, params.at("minAge").get<int32_t>(),
                params.at("maxAge").get<int32_t>(), params.at("realm").get<int32_t>());
            return ok({{"age", r.first}, {"lifespan", r.second}});
        }
        case action::REDEEM_ROLL_SKILLS: {
            const int32_t roll = gamecore::system::rollBySpiritRootCount(
                sr, params.at("spiritRootCount").get<int32_t>());
            return ok({{"roll", roll},
                       {"aptitude", gamecore::system::avoidSentinel50(roll)}});
        }
        case action::REDEEM_GENERATE_VARIANCE: {
            return ok({{"variance", gamecore::system::generateVariance(sr)}});
        }
        case action::MAIL_ATTACHMENT_ENCODE: {
            std::vector<MailAttachment> attachments;
            if (params.contains("attachments")) {
                for (const auto& a : params.at("attachments")) {
                    attachments.push_back(a.get<MailAttachment>());
                }
            }
            // kotlinx 保持字段声明顺序（type/name/quantity/rarity/itemId/extra）——
            // 用 ordered_json 逐字段构造，字符串输出与 Kotlin encodeToString 逐字一致
            nlohmann::ordered_json arr = nlohmann::ordered_json::array();
            for (const auto& a : attachments) {
                nlohmann::ordered_json o;
                o["type"] = a.type;
                o["name"] = a.name;
                o["quantity"] = a.quantity;
                o["rarity"] = a.rarity;
                if (a.itemId.has_value()) {
                    o["itemId"] = *a.itemId;
                } else {
                    o["itemId"] = nullptr;
                }
                nlohmann::ordered_json extra = nlohmann::ordered_json::object();
                for (const auto& [k, v] : a.extra) extra[k] = v;
                o["extra"] = std::move(extra);
                arr.push_back(std::move(o));
            }
            return ok({{"encoded", arr.dump()}});
        }
        default:
            return fail("UNKNOWN_ACTION", "redeem action " + std::to_string(actionId));
    }
}

/// 弟子槽位清理（计划 v2 阶段 4 批 4-5：ActionIds.SLOT_CLEAR_ALL）
nlohmann::json handleSlotCleanup(GameCore* core, const nlohmann::json& params) {
    (void)core;
    using gamecore::system::SlotCleanupInput;
    SlotCleanupInput in;
    const auto& p = params;
    if (p.contains("spiritMineSlots")) {
        for (const auto& e : p.at("spiritMineSlots")) {
            in.spiritMineSlots.push_back(e.get<gamecore::state::SpiritMineSlot>());
        }
    }
    if (p.contains("librarySlots")) {
        for (const auto& e : p.at("librarySlots")) {
            in.librarySlots.push_back(e.get<gamecore::state::LibrarySlot>());
        }
    }
    if (p.contains("elderSlots")) {
        in.elderSlots = p.at("elderSlots").get<gamecore::state::ElderSlots>();
    }
    if (p.contains("residenceSlots")) {
        for (const auto& e : p.at("residenceSlots")) {
            in.residenceSlots.push_back(e.get<gamecore::state::ResidenceSlot>());
        }
    }
    if (p.contains("activeBloodRefinements")) {
        for (auto it = p.at("activeBloodRefinements").begin();
             it != p.at("activeBloodRefinements").end(); ++it) {
            in.activeBloodRefinements[it.key()] =
                it.value().get<gamecore::state::BloodRefinementProgress>();
        }
    }
    if (p.contains("patrolSlots")) {
        for (const auto& e : p.at("patrolSlots")) {
            in.patrolSlots.push_back(e.get<gamecore::state::PatrolSlot>());
        }
    }
    if (p.contains("warehouseGarrisons")) {
        for (const auto& e : p.at("warehouseGarrisons")) {
            in.warehouseGarrisons.push_back(e.get<gamecore::state::WarehouseGarrisonSlot>());
        }
    }
    if (p.contains("battleTeams")) {
        for (const auto& e : p.at("battleTeams")) {
            in.battleTeams.push_back(e.get<gamecore::state::BattleTeam>());
        }
    }
    if (p.contains("worldMapSects")) {
        for (const auto& e : p.at("worldMapSects")) {
            in.worldMapSects.push_back(e.get<gamecore::state::WorldSect>());
        }
    }
    if (p.contains("productionSlots")) {
        for (const auto& e : p.at("productionSlots")) {
            in.productionSlots.push_back(e.get<gamecore::state::ProductionSlot>());
        }
    }
    if (p.contains("caveExplorationTeams")) {
        for (const auto& e : p.at("caveExplorationTeams")) {
            in.caveExplorationTeams.push_back(e.get<gamecore::state::CaveExplorationTeam>());
        }
    }
    if (p.contains("activeMissions")) {
        for (const auto& e : p.at("activeMissions")) {
            in.activeMissions.push_back(e.get<gamecore::state::ActiveMissionLite>());
        }
    }
    const auto out = gamecore::system::clearAllSlotsDataOnly(
        in, params.at("discipleId").get<std::string>(),
        params.value("includeResidence", false));
    nlohmann::json data = {
        {"spiritMineSlots", out.spiritMineSlots},
        {"librarySlots", out.librarySlots},
        {"elderSlots", out.elderSlots},
        {"residenceSlots", out.residenceSlots},
        {"activeBloodRefinements", out.activeBloodRefinements},
        {"patrolSlots", out.patrolSlots},
        {"warehouseGarrisons", out.warehouseGarrisons},
        {"battleTeams", out.battleTeams},
        {"worldMapSects", out.worldMapSects},
        {"productionSlots", out.productionSlots},
        {"caveExplorationTeams", out.caveExplorationTeams},
        {"activeMissions", out.activeMissions},
    };
    return ok(std::move(data));
}

/// 外交/宗门决策操作（计划 v2 阶段 4 批 4-4：ActionIds.SECT_*）
nlohmann::json handleSectDiplomacy(GameCore* core, int32_t actionId,
                                   const nlohmann::json& params) {
    using gamecore::system::SectDecisionKind;
    switch (actionId) {
        case action::SECT_DECISION_CHANCE: {
            const std::string profileName = params.value("profile", "attack");
            const auto* profile = &gamecore::system::attackDecisionProfile();
            if (profileName == "alliance") {
                profile = &gamecore::system::allianceDecisionProfile();
            } else if (profileName == "vassal") {
                profile = &gamecore::system::vassalDecisionProfile();
            }
            const double chance = gamecore::system::sectDecisionChance(
                *profile, params.at("powerRatio").get<double>(),
                params.value("conquestCount", 0), params.value("lostSectCount", 0),
                params.value("battleWinCount", 0), params.value("battleLossCount", 0),
                params.at("favorLevel").get<int32_t>(),
                params.value("personality", -1));
            return ok({{"chance", chance}});
        }
        case action::SECT_DECISION_BREAKAWAY: {
            const double chance = gamecore::system::sectBreakawayChance(
                params.at("powerRatio").get<double>(),
                params.value("conquestCount", 0), params.value("lostSectCount", 0),
                params.value("battleWinCount", 0), params.value("battleLossCount", 0),
                params.at("favorLevel").get<int32_t>());
            return ok({{"chance", chance}});
        }
        case action::SECT_POWER_DISCIPLE: {
            return ok({{"power", gamecore::system::discipleCombatPower(
                                     params.at("physicalAttack").get<int32_t>(),
                                     params.at("magicAttack").get<int32_t>(),
                                     params.at("maxHp").get<int32_t>(),
                                     params.at("physicalDefense").get<int32_t>(),
                                     params.at("magicDefense").get<int32_t>(),
                                     params.at("speed").get<int32_t>())}});
        }
        case action::SECT_POWER_BEAST: {
            return ok({{"power", gamecore::system::beastCombatPower(
                                     params.at("maxHp").get<int32_t>(),
                                     params.at("physicalAttack").get<int32_t>(),
                                     params.at("magicAttack").get<int32_t>(),
                                     params.at("physicalDefense").get<int32_t>(),
                                     params.at("magicDefense").get<int32_t>(),
                                     params.at("speed").get<int32_t>())}});
        }
        case action::SECT_POWER_FINGERPRINT: {
            std::vector<std::string> talentIds;
            if (params.contains("talentIds")) {
                for (const auto& t : params.at("talentIds")) {
                    talentIds.push_back(t.get<std::string>());
                }
            }
            gamecore::system::BloodRefinementPctTotalCpp blood;
            const gamecore::system::BloodRefinementPctTotalCpp* bloodPtr = nullptr;
            if (params.contains("bloodPct") && !params.at("bloodPct").is_null()) {
                const auto& b = params.at("bloodPct");
                blood.hpBonusPct = b.value("hpBonusPct", 0.0);
                blood.physicalAttackBonusPct = b.value("physicalAttackBonusPct", 0.0);
                blood.magicAttackBonusPct = b.value("magicAttackBonusPct", 0.0);
                blood.physicalDefenseBonusPct = b.value("physicalDefenseBonusPct", 0.0);
                blood.magicDefenseBonusPct = b.value("magicDefenseBonusPct", 0.0);
                blood.speedBonusPct = b.value("speedBonusPct", 0.0);
                bloodPtr = &blood;
            }
            const int32_t fp = gamecore::system::sectPowerFingerprint(
                params.at("realm").get<int32_t>(), params.at("realmLayer").get<int32_t>(),
                params.value("hpVariance", 0), params.value("physicalAttackVariance", 0),
                params.value("magicAttackVariance", 0), params.value("physicalDefenseVariance", 0),
                params.value("magicDefenseVariance", 0), params.value("speedVariance", 0),
                talentIds, bloodPtr);
            return ok({{"fingerprint", fp}});
        }
        case action::SECT_RARITY_ROLL: {
            auto& sr = core->rng().getRng(gamecore::rng::RngPartition::kSystem);
            int32_t rarity;
            if (params.contains("seed") && !params.at("seed").is_null()) {
                gamecore::rng::DeterministicRng local =
                    gamecore::rng::DeterministicRng::fromSeed(params.at("seed").get<int64_t>());
                rarity = gamecore::system::rollRarity(local, params.at("year").get<int32_t>());
            } else {
                rarity = gamecore::system::rollRarity(sr, params.at("year").get<int32_t>());
            }
            return ok({{"rarity", rarity}});
        }
        case action::SECT_RARITY_MAX: {
            return ok({{"max", gamecore::system::maxRarityForYear(params.at("year").get<int32_t>())}});
        }
        case action::SECT_RARITY_PITY: {
            return ok({{"pity", gamecore::system::pityRarityForYear(params.at("year").get<int32_t>())}});
        }
        case action::SECT_RARITY_WEIGHTS: {
            const auto weights = gamecore::system::rarityWeightsForYear(params.at("year").get<int32_t>());
            nlohmann::json w = nlohmann::json::object();
            for (const auto& [r, p] : weights) {
                w[std::to_string(r)] = p;
            }
            return ok({{"weights", w}});
        }
        case action::SECT_TRADE_SEED: {
            return ok({{"seed", gamecore::system::sectTradeSeed(
                                   params.at("sectId").get<std::string>(),
                                   params.at("year").get<int32_t>())}});
        }
        case action::SECT_TRADE_STOCK: {
            gamecore::rng::DeterministicRng local =
                gamecore::rng::DeterministicRng::fromSeed(params.at("seed").get<int64_t>());
            return ok({{"stock", gamecore::system::sectTradeStock(
                                     local, params.at("type").get<std::string>(),
                                     params.at("rarity").get<int32_t>())}});
        }
        case action::SECT_TRADE_PRICE: {
            gamecore::rng::DeterministicRng local =
                gamecore::rng::DeterministicRng::fromSeed(params.at("seed").get<int64_t>());
            return ok({{"price", gamecore::system::sectTradePriceFluctuation(
                                     params.at("basePrice").get<int64_t>(), local)}});
        }
        case action::SECT_TRADE_SPIRIT_STONE: {
            const auto r = gamecore::system::sectTradeSpiritStone(
                params.at("rarity").get<int32_t>(), params.at("year").get<int32_t>());
            if (!r.has_value()) return ok({{"present", false}});
            return ok({{"present", true}, {"itemRarity", r->first}, {"basePrice", r->second}});
        }
        default:
            return fail("UNKNOWN_ACTION", "sect diplomacy action " + std::to_string(actionId));
    }
}

/// 远古秘境操作（计划 v2 阶段 4 批 4-3：ActionIds.SECRET_REALM_*）
nlohmann::json handleSecretRealm(GameCore* core, int32_t actionId,
                                 const nlohmann::json& params) {
    using gamecore::system::SecretRealmTypeCandidates;
    using gamecore::state::SecretRealmBackpack;
    using gamecore::state::SecretRealmEventRecord;
    using gamecore::state::SecretRealmMemberState;
    using gamecore::state::SecretRealmAITeam;
    using gamecore::state::WorldSect;
    using gamecore::state::Disciple;
    switch (actionId) {
        case action::SECRET_REALM_PLAYER_AVG_REALM: {
            std::vector<SecretRealmMemberState> members;
            for (const auto& m : params.at("members")) {
                members.push_back(m.get<SecretRealmMemberState>());
            }
            return ok({{"avgRealm", gamecore::system::secretRealmPlayerAvgRealm(members)}});
        }
        case action::SECRET_REALM_ROLL_BEAST_REALM: {
            return ok({{"realm", gamecore::system::rollSecretRealmBeastRealm(
                                     core->rng(), params.at("playerAvgRealm").get<int32_t>())}});
        }
        case action::SECRET_REALM_GENERATE_BEAST_EVENT: {
            const auto event = gamecore::system::generateSecretRealmBeastEvent(
                core->rng(), params.at("playerAvgRealm").get<int32_t>());
            return ok({{"event", event}});
        }
        case action::SECRET_REALM_ROLL_NEXT_EVENT: {
            std::vector<SecretRealmAITeam> teams;
            if (params.contains("aiTeams")) {
                for (const auto& t : params.at("aiTeams")) {
                    teams.push_back(t.get<SecretRealmAITeam>());
                }
            }
            const auto event = gamecore::system::rollSecretRealmNextEvent(
                core->rng(), params.at("playerAvgRealm").get<int32_t>(), teams);
            return ok({{"event", event}});
        }
        case action::SECRET_REALM_BUILD_BEAST_STATS: {
            const auto stats = gamecore::system::buildSecretRealmBeastPreGenStats(
                core->rng(), params.at("realm").get<int32_t>(),
                params.at("beastTypeName").get<std::string>(),
                params.value("ambushSucceeded", false),
                params.value("beastLayer", 1));
            return ok({
                {"maxHp", stats.maxHp}, {"maxMp", stats.maxMp},
                {"physicalAttack", stats.physicalAttack}, {"magicAttack", stats.magicAttack},
                {"physicalDefense", stats.physicalDefense}, {"magicDefense", stats.magicDefense},
                {"speed", stats.speed}, {"realmLayer", stats.realmLayer},
            });
        }
        case action::SECRET_REALM_ROLL_BEAST_LOOT: {
            const auto rewards = gamecore::system::rollSecretRealmBeastLoot(
                core->rng(), params.at("beastTypeName").get<std::string>(),
                params.at("beastRealm").get<int32_t>(),
                params.at("beastCount").get<int32_t>());
            return ok({{"rewards", rewards}});
        }
        case action::SECRET_REALM_GENERATE_RUINS_TREASURE: {
            SecretRealmTypeCandidates candidates;
            if (params.contains("candidates")) {
                for (auto tit = params.at("candidates").begin();
                     tit != params.at("candidates").end(); ++tit) {
                    for (auto rit = tit.value().begin(); rit != tit.value().end(); ++rit) {
                        std::vector<std::pair<std::string, std::string>> list;
                        for (const auto& tpl : rit.value()) {
                            list.emplace_back(tpl.at(0).get<std::string>(),
                                              tpl.at(1).get<std::string>());
                        }
                        candidates[tit.key()][std::stoi(rit.key())] = std::move(list);
                    }
                }
            }
            const auto rewards = gamecore::system::generateSecretRealmRuinsTreasure(
                core->rng(), params.at("minCount").get<int32_t>(),
                params.at("maxCount").get<int32_t>(),
                params.at("minRarity").get<int32_t>(),
                params.at("maxRarity").get<int32_t>(), candidates);
            return ok({{"rewards", rewards}});
        }
        case action::SECRET_REALM_RESOLVE_RUINS: {
            std::vector<SecretRealmMemberState> members;
            for (const auto& m : params.at("members")) {
                members.push_back(m.get<SecretRealmMemberState>());
            }
            SecretRealmBackpack backpack;
            if (params.contains("backpack")) {
                backpack = params.at("backpack").get<SecretRealmBackpack>();
            }
            SecretRealmTypeCandidates candidates;
            if (params.contains("candidates")) {
                for (auto tit = params.at("candidates").begin();
                     tit != params.at("candidates").end(); ++tit) {
                    for (auto rit = tit.value().begin(); rit != tit.value().end(); ++rit) {
                        std::vector<std::pair<std::string, std::string>> list;
                        for (const auto& tpl : rit.value()) {
                            list.emplace_back(tpl.at(0).get<std::string>(),
                                              tpl.at(1).get<std::string>());
                        }
                        candidates[tit.key()][std::stoi(rit.key())] = std::move(list);
                    }
                }
            }
            const auto resolution = gamecore::system::resolveSecretRealmRuinsExplore(
                params.at("optionIndex").get<int32_t>(), members, backpack, core->rng(),
                candidates);
            return ok({{"resultText", resolution.resultText},
                       {"nextEvent", resolution.nextEvent},
                       {"params", resolution.params}});
        }
        case action::SECRET_REALM_LOOT_LOSS: {
            SecretRealmBackpack backpack;
            if (params.contains("backpack")) {
                backpack = params.at("backpack").get<SecretRealmBackpack>();
            }
            const auto r = gamecore::system::applySecretRealmLootLoss(backpack, core->rng());
            return ok({{"backpack", r.backpack},
                       {"lostItemCount", r.lostItemCount},
                       {"lostSpiritStones", r.lostSpiritStones}});
        }
        case action::SECRET_REALM_AI_DISPATCH: {
            std::vector<gamecore::system::SecretRealmAiPool> pools;
            if (params.contains("pools")) {
                for (const auto& p : params.at("pools")) {
                    gamecore::system::SecretRealmAiPool pool;
                    pool.sectId = p.value("sectId", "");
                    pool.sectName = p.value("sectName", "");
                    pool.sectFound = p.value("sectFound", true);
                    pool.sectLevel = p.value("sectLevel", 0);
                    if (p.contains("disciples")) {
                        for (const auto& d : p.at("disciples")) {
                            pool.disciples.push_back(d.get<Disciple>());
                        }
                    }
                    pools.push_back(std::move(pool));
                }
            }
            const auto teams = gamecore::system::dispatchSecretRealmAiTeams(pools);
            return ok({{"teams", teams}});
        }
        case action::SECRET_REALM_FIND_POSITION: {
            std::vector<WorldSect> sects;
            if (params.contains("sects")) {
                for (const auto& s : params.at("sects")) {
                    sects.push_back(s.get<WorldSect>());
                }
            }
            const auto pos = gamecore::system::findSecretRealmPosition(core->rng(), sects);
            return ok({{"x", pos.first}, {"y", pos.second}});
        }
        case action::SECRET_REALM_STAMINA: {
            const int32_t stamina = params.at("stamina").get<int32_t>();
            SecretRealmEventRecord event;
            if (params.contains("event")) {
                event = params.at("event").get<SecretRealmEventRecord>();
            }
            gamecore::state::SecretRealmExplorationSession session;
            session.stamina = stamina;
            return ok({{"stamina", gamecore::system::secretRealmStaminaAfterChoice(
                                       session, event, params.at("optionIndex").get<int32_t>())}});
        }
        case action::SECRET_REALM_YEARLY_SPAWN_CHECK: {
            return ok({{"eligible", gamecore::system::secretRealmYearlySpawnEligible(
                                        params.at("year").get<int32_t>(),
                                        params.at("cooldown").get<int32_t>())}});
        }
        case action::SECRET_REALM_ROLL_SPRITE: {
            return ok({{"spriteIndex", gamecore::system::rollSecretRealmSpriteIndex(core->rng())}});
        }
        default:
            return fail("UNKNOWN_ACTION", "secret realm action " + std::to_string(actionId));
    }
}

/// 死亡物化操作（计划 v2 阶段 4 批 4-2：ActionIds.DISCIPLE_MARK_DEAD /
/// DISCIPLE_BACKFILL_DEATH_YEARS）
nlohmann::json handleDeathHandler(GameCore* core, int32_t actionId,
                                  const nlohmann::json& params) {
    auto& state = core->state();
    auto& store = state.disciples;
    switch (actionId) {
        case action::DISCIPLE_MARK_DEAD: {
            const std::string id = params.at("discipleId").get<std::string>();
            const int32_t deathYear = params.at("deathYear").get<int32_t>();
            const auto r = gamecore::system::markDead(
                store, id, deathYear, state.gameData.annualDeceasedDisciples);
            nlohmann::json data = {
                {"marked", r.marked},
                {"hadEquipment", r.hadEquipment},
                {"annualDeceasedDisciples", state.gameData.annualDeceasedDisciples},
            };
            if (r.marked) {
                const std::size_t row = *store.rowOf(id);
                data["isAlive"] = store.isAlive[row];
                data["status"] = store.statuses[row];
                data["deathYears"] = store.deathYears[row];
            }
            return ok(std::move(data));
        }
        case action::DISCIPLE_BACKFILL_DEATH_YEARS: {
            std::vector<gamecore::state::Disciple> disciples;
            if (params.contains("disciples")) {
                for (const auto& d : params.at("disciples")) {
                    disciples.push_back(d.get<gamecore::state::Disciple>());
                }
            }
            const int32_t deathYear = params.at("deathYear").get<int32_t>();
            const int32_t count = gamecore::system::backfillDeathYears(
                store, disciples, deathYear);
            nlohmann::json entries = nlohmann::json::array();
            for (const auto& d : disciples) {
                if (d.isAlive) continue;
                const auto rowOpt = store.rowOf(d.id);
                if (!rowOpt.has_value()) continue;
                entries.push_back(
                    {{"id", d.id}, {"deathYears", store.deathYears[*rowOpt]}});
            }
            return ok({{"backfilled", count}, {"entries", entries}});
        }
        default:
            return fail("UNKNOWN_ACTION", "death handler action " + std::to_string(actionId));
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
        } else if (actionId >= action::LEVEL_SELECT_BEAST_REALM &&
                   actionId <= action::LEVEL_GENERATE_LEVELS) {
            result = handleLevelGeneration(this, actionId, params);
        } else if (actionId >= action::DISCIPLE_MARK_DEAD &&
                   actionId <= action::DISCIPLE_BACKFILL_DEATH_YEARS) {
            result = handleDeathHandler(this, actionId, params);
        } else if (actionId >= action::SECRET_REALM_PLAYER_AVG_REALM &&
                   actionId <= action::SECRET_REALM_ROLL_SPRITE) {
            result = handleSecretRealm(this, actionId, params);
        } else if (actionId >= action::SECT_DECISION_CHANCE &&
                   actionId <= action::SECT_TRADE_SPIRIT_STONE) {
            result = handleSectDiplomacy(this, actionId, params);
        } else if (actionId == action::SLOT_CLEAR_ALL) {
            result = handleSlotCleanup(this, params);
        } else if (actionId >= action::REDEEM_VALIDATE_INPUT &&
                   actionId <= action::MAIL_ATTACHMENT_ENCODE) {
            result = handleRedeemCode(this, actionId, params);
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

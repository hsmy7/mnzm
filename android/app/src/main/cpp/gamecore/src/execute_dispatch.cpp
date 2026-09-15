#include "gamecore/game_core.h"

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/dispatch_w4.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/system/appointment_tx.h"
#include "gamecore/system/battle.h"
#include "gamecore/system/battle_json.h"
#include "gamecore/system/boundary_tx.h"
#include "gamecore/system/building_tx.h"
#include "gamecore/system/breakthrough.h"
#include "gamecore/system/cultivation.h"
#include "gamecore/system/death_handler.h"
#include "gamecore/system/disciple_tx.h"
#include "gamecore/system/disciple_lifecycle_tx.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/economy.h"
#include "gamecore/system/exploration.h"
#include "gamecore/system/exploration_tx.h"
#include "gamecore/system/government.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/inventory_tx.h"
#include "gamecore/system/jade_tx.h"
#include "gamecore/system/lifecycle.h"
#include "gamecore/system/level_generator.h"
#include "gamecore/system/lock_beast_tx.h"
#include "gamecore/system/rarity_progression.h"
#include "gamecore/system/redeem_code.h"
#include "gamecore/system/secret_realm.h"
#include "gamecore/system/secret_realm_platform_tx.h"
#include "gamecore/system/secret_realm_session.h"
#include "gamecore/system/production.h"
#include "gamecore/system/road_tx.h"
#include "gamecore/system/sect_decision.h"
#include "gamecore/system/sect_power.h"
#include "gamecore/system/sect_trade.h"
#include "gamecore/system/slot_cleanup.h"
#include "gamecore/system/spirit_field.h"
#include "gamecore/system/storage_bag_tx.h"
#include "gamecore/system/patrol_tx.h"
#include "gamecore/system/sect_attack_tx.h"
// recruit_tx.h（batch-16 招募列表 UI 直调事务）传递引入 year_settlement.h →
// month_settlement.h using 声明——按 README §3.3 置于包含块末尾、diplomacy_tx.h 之前
#include "gamecore/system/recruit_tx.h"
// diplomacy_tx.h 置于包含块末尾：其 month_settlement.h 传递引入的
// using 声明会改变后续头文件（disciple_tx.h）的非限定名解析
//（include-order 依赖，batch-09 登记项）
#include "gamecore/system/diplomacy_tx.h"

// ============================================================
// GameCore::execute — ActionId 分发表
//
// 各 C++ 系统经此统一入口被 Kotlin 转发层调用。
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
        case action::INV_CONSOLIDATE:
            gamecore::system::consolidateAllStacks(state);
            data = {{"consolidated", true}};
            break;
        case action::INV_SORT:
            gamecore::system::sortWarehouse(state);
            data = {{"sorted", true}};
            break;
        case action::INV_TOGGLE_LOCK: {
            const bool found = gamecore::system::toggleItemLock(
                state, params.at("itemId").get<std::string>(),
                params.value("itemType", ""));
            data = {{"toggled", found}};
            break;
        }
        default:
            return fail("UNKNOWN_ACTION", "inventory action " + std::to_string(actionId));
    }
    data["overflowMails"] = mail.all().size();
    if (!mail.empty()) {
        // 溢出邮件草稿回传（Kotlin wrapper 经 OverflowMailSender 落库，
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

/// 关卡生成操作（ActionIds.LEVEL_*）
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

/// 兑换码与邮件附件（ActionIds.REDEEM_* / MAIL_*）
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

/// 弟子槽位清理（ActionIds.SLOT_CLEAR_ALL）
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

/// 外交/宗门决策操作（ActionIds.SECT_*）
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

/// 远古秘境操作（ActionIds.SECRET_REALM_*）
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

/// 秘境交互会话域（ActionIds.SECRET_REALM_START/CHOOSE/
/// END/YEARLY_SPAWN——Kotlin SecretRealmService 交互会话下沉；战斗终态 +
/// rounds 回传 Kotlin 重建战报，展示通道非协议）
nlohmann::json handleSecretRealmSession(GameCore* core, int32_t actionId,
                                        const nlohmann::json& params) {
    using namespace gamecore::system::sr_session;
    using gamecore::state::SecretRealmMemberState;
    const auto membersJson = [](const std::vector<SecretRealmMemberState>& ms) {
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& m : ms) arr.push_back(m);
        return arr;
    };
    const auto idSetJson = [](const std::set<std::string>& ids) {
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& id : ids) arr.push_back(id);
        return arr;
    };
    const auto draftsJson = [](const std::vector<gamecore::system::OverflowDraft>& ds) {
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& d : ds) {
            arr.push_back({{"itemType", d.itemType},
                           {"itemName", d.itemName},
                           {"itemId", d.itemId},
                           {"rarity", d.rarity},
                           {"quantity", d.quantity},
                           {"source", d.source}});
        }
        return arr;
    };
    switch (actionId) {
        case action::SECRET_REALM_START: {
            std::vector<std::string> memberIds;
            for (const auto& id : params.at("memberIds")) {
                memberIds.push_back(id.get<std::string>());
            }
            const auto r = startSession(core->state(), memberIds, core->rng());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"started", true}});
        }
        case action::SECRET_REALM_CHOOSE: {
            const auto outcome = chooseOption(
                core->state(), params.at("optionIndex").get<int32_t>(), core->rng());
            if (!outcome.ok) return fail("INVALID_CHOICE", outcome.errorText);
            nlohmann::json env = {
                {"message", outcome.message},
                {"sessionEnded", outcome.sessionEnded},
                {"releasedMemberIds", idSetJson(outcome.releasedMemberIds)},
                {"resultText", outcome.resolution.resultText},
                {"enteredCombat", outcome.resolution.enteredCombat},
                {"victory", outcome.resolution.enteredCombat && outcome.resolution.battle &&
                                outcome.resolution.battle->victory},
                {"deadIds", idSetJson(outcome.resolution.deadIds)},
                {"members", membersJson(outcome.resolution.members)},
                {"nextEvent", outcome.resolution.nextEvent},
                {"params", outcome.resolution.params},
                {"overflowDrafts", draftsJson(outcome.overflowDrafts)},
            };
            if (outcome.resolution.battle.has_value()) {
                const auto& b = *outcome.resolution.battle;
                env["battle"] = {
                    {"type", b.battleType},
                    {"defenderName", b.defenderName},
                    {"details", b.details},
                    {"beastsDefeated", b.beastsDefeated},
                    {"hasBattle", b.hasBattle},
                    {"winner", b.battle.winner == gamecore::battle::BattleWinner::kTeam
                                   ? "TEAM"
                                   : b.battle.winner == gamecore::battle::BattleWinner::kBeasts
                                         ? "BEASTS"
                                         : "DRAW"},
                    {"turn", b.battle.turn},
                    {"teamCasualties", b.teamCasualties},
                    {"rewards", b.battle.rewards},
                    {"team", [&] {
                         nlohmann::json arr = nlohmann::json::array();
                         for (const auto& c : b.battle.team) {
                             arr.push_back(gamecore::battle::combatantToJson(c));
                         }
                         return arr;
                     }()},
                    {"beasts", [&] {
                         nlohmann::json arr = nlohmann::json::array();
                         for (const auto& c : b.battle.beasts) {
                             arr.push_back(gamecore::battle::combatantToJson(c));
                         }
                         return arr;
                     }()},
                    {"rounds", gamecore::battle::roundsToJson(b.battle.rounds)},
                };
            }
            return ok(std::move(env));
        }
        case action::SECRET_REALM_END: {
            gamecore::system::OverflowMailCollector overflowMail;
            const std::string reason =
                params.value("reason", std::string(gamecore::system::sr_session::kEndExplorerEnd));
            const auto released =
                endSession(core->state(), reason.c_str(), overflowMail);
            return ok({{"releasedMemberIds", idSetJson(released)},
                       {"overflowDrafts", draftsJson(overflowMail.all())}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "secret realm session action " + std::to_string(actionId));
    }
}

/// 秘境平台段读档恢复事务（batch-20a——ActionIds.SECRET_REALM_CONTINUE_TX；
/// 零 RNG。会话域判定段下沉：到期关闭/死局重置/成员净化；gate 释放/
/// 溢出邮件投递/关闭邮件重建/状态同步保留 Kotlin——信封 secretRealmClose
/// 段与 nativeSettleMonth 同构，Kotlin 侧复用 applyExpiryCloseDraft 通道）
nlohmann::json handleSecretRealmPlatformTx(GameCore* core, int32_t actionId,
                                           const nlohmann::json& params) {
    (void)actionId;
    (void)params;
    using namespace gamecore::system::sr_platform;
    const auto out = continueSessionTx(core->state());
    nlohmann::json env = {
        {"canContinue", out.canContinue},
        {"action", out.action},
        {"releasedMemberIds", [&] {
             nlohmann::json arr = nlohmann::json::array();
             for (const auto& id : out.releasedMemberIds) arr.push_back(id);
             return arr;
         }()},
    };
    if (!out.overflowDrafts.empty()) {
        nlohmann::json drafts = nlohmann::json::array();
        for (const auto& d : out.overflowDrafts) {
            drafts.push_back({{"itemType", d.itemType},
                              {"itemName", d.itemName},
                              {"itemId", d.itemId},
                              {"rarity", d.rarity},
                              {"quantity", d.quantity},
                              {"source", d.source}});
        }
        env["overflowDrafts"] = std::move(drafts);
    }
    if (out.closeDraft.has_value()) {
        nlohmann::json close = {{"closed", out.closeDraft->closed},
                                {"slotId", out.closeDraft->slotId}};
        close["memberIds"] = out.closeDraft->memberIds;
        nlohmann::json bp;
        gamecore::state::to_json(bp, out.closeDraft->backpack);
        close["backpack"] = std::move(bp);
        env["secretRealmClose"] = std::move(close);
    }
    return ok(std::move(env));
}

/// 攻宗确定性写回事务（ActionIds.SECT_ATTACK_* —— batch-20b）。
/// 零 RNG；失败零写入 → 失败信封供 Kotlin 回退臂重执行。
/// 登记不下沉：战利品生成族（Random.Default 非分区随机域）/ 奖励入账事务 /
/// 战绩记录（与 Kotlin 显示域 battleLogs 同事务）——见 handover §2.51b。
nlohmann::json handleSectAttackTx(GameCore* core, int32_t actionId,
                                  const nlohmann::json& params) {
    namespace sat = gamecore::system::sect_attack_tx;
    auto& state = core->state();

    auto toFailure = [](const sat::TxResult& r) {
        return fail(r.errorType.empty() ? "INVALID" : r.errorType, r.message);
    };

    switch (actionId) {
        case action::SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX: {
            std::vector<std::string> deadIds;
            if (params.contains("deadDefenderIds")) {
                for (const auto& e : params.at("deadDefenderIds")) {
                    deadIds.push_back(e.get<std::string>());
                }
            }
            const auto r = sat::removeDeadDefendersTx(
                state, params.at("sectId").get<std::string>(),
                params.at("defenderPoolSectId").get<std::string>(), deadIds);
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"removedFromPool", r.removedFromPool},
                       {"clearedGarrisonSlots", r.clearedGarrisonSlots}});
        }
        case action::SECT_ATTACK_GRANT_SOUL_POWERS_TX: {
            std::vector<std::string> survivorIds;
            for (const auto& e : params.at("sectSurvivorIds")) {
                survivorIds.push_back(e.get<std::string>());
            }
            const auto r = sat::grantWarSoulPowersTx(state, survivorIds);
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"granted", r.granted}});
        }
        default:
            return fail("NOT_IMPLEMENTED",
                        "unknown sect attack action: " + std::to_string(actionId));
    }
}

/// 巡逻 / 住所 / 矿场 / 年俸 UI 操作面事务（ActionIds.PATROL_* —— batch-12）。
/// 全链零 RNG；失败零写入 → 失败信封供 Kotlin 回退臂重执行校验链。
/// 信封回执 releasedIds/confirmedIds 驱动 Kotlin 事务外残差（gate / Room / 状态同步）。
nlohmann::json handlePatrolTx(GameCore* core, int32_t actionId,
                              const nlohmann::json& params) {
    namespace pt = gamecore::system::patrol_tx;
    auto& state = core->state();

    /// 统一失败信封：ok=false 时按 Kotlin AppError 分型回传
    auto toFailure = [](const pt::TxResult& r) {
        return fail(r.errorType.empty() ? "INVALID" : r.errorType, r.message);
    };
    /// 批量分配回执（releasedIds/confirmedIds/confirmedIndexes 三列同序）
    auto autoAssignEnvelope = [&](const pt::AutoAssignOutcome& r) {
        if (!r.base.ok) return toFailure(r.base);
        nlohmann::json released = nlohmann::json::array();
        for (const auto& id : r.releasedIds) released.push_back(id);
        nlohmann::json confirmed = nlohmann::json::array();
        for (const auto& id : r.confirmedIds) confirmed.push_back(id);
        nlohmann::json indexes = nlohmann::json::array();
        for (const auto idx : r.confirmedIndexes) indexes.push_back(idx);
        return ok({{"changed", r.changed},
                   {"releasedIds", std::move(released)},
                   {"confirmedIds", std::move(confirmed)},
                   {"confirmedIndexes", std::move(indexes)}});
    };

    switch (actionId) {
        case action::PATROL_ASSIGN_RESIDENCE: {
            const auto r = pt::assignToResidenceTx(
                state, params.at("buildingInstanceId").get<std::string>(),
                params.at("slotIndex").get<int32_t>(),
                params.at("discipleId").get<std::string>());
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed},
                       {"releasedOccupantId", r.releasedOccupantId}});
        }
        case action::PATROL_REMOVE_RESIDENCE: {
            const auto r = pt::removeFromResidenceTx(
                state, params.at("buildingInstanceId").get<std::string>(),
                params.at("slotIndex").get<int32_t>());
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"removedDiscipleId", r.removedDiscipleId}});
        }
        case action::PATROL_ASSIGN: {
            const auto r = pt::assignPatrolTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("globalIndex").get<int32_t>());
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed},
                       {"releasedOccupantId", r.releasedOccupantId}});
        }
        case action::PATROL_REMOVE: {
            const auto r = pt::removePatrolTx(
                state, params.at("globalIndex").get<int32_t>());
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"removedDiscipleId", r.removedDiscipleId}});
        }
        case action::PATROL_SWAP: {
            const auto r = pt::swapPatrolTx(
                state, params.at("fromGlobalIndex").get<int32_t>(),
                params.at("toGlobalIndex").get<int32_t>());
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed},
                       {"fromDiscipleId", r.fromDiscipleId},
                       {"toDiscipleId", r.toDiscipleId}});
        }
        case action::PATROL_AUTO_ASSIGN: {
            std::vector<std::pair<int32_t, std::string>> assignments;
            for (const auto& e : params.at("assignments")) {
                assignments.emplace_back(e.at("globalIndex").get<int32_t>(),
                                         e.at("discipleId").get<std::string>());
            }
            return autoAssignEnvelope(pt::autoAssignPatrolTx(state, assignments));
        }
        case action::PATROL_UPDATE_CONFIG: {
            std::vector<gamecore::state::PatrolConfig> configs;
            for (const auto& e : params.at("configs")) {
                gamecore::state::PatrolConfig cfg;
                if (e.contains("targetRealms")) {
                    for (const auto& v : e.at("targetRealms")) {
                        cfg.targetRealms.push_back(v.get<int32_t>());
                    }
                }
                cfg.maxBeastCount = e.value("maxBeastCount", 1);
                cfg.requireFullStatus = e.value("requireFullStatus", true);
                configs.push_back(std::move(cfg));
            }
            const auto r = pt::updatePatrolConfigsTx(state, std::move(configs));
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed}});
        }
        case action::PATROL_UPDATE_SPIRIT_MINE_SLOTS: {
            std::vector<gamecore::state::SpiritMineSlot> slots;
            for (const auto& e : params.at("slots")) {
                slots.push_back(e.get<gamecore::state::SpiritMineSlot>());
            }
            const auto r = pt::updateSpiritMineSlotsTx(state, std::move(slots));
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed}});
        }
        case action::PATROL_FIX_SPIRIT_MINE: {
            const auto r = pt::validateAndFixSpiritMineDataTx(state);
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed}, {"alignedCount", r.alignedCount}});
        }
        case action::PATROL_UPDATE_YEARLY_SALARY: {
            std::map<int32_t, int32_t> salary;
            for (const auto& e : params.at("yearlySalaryEntries")) {
                salary[e.at("realm").get<int32_t>()] = e.at("amount").get<int32_t>();
            }
            const auto r = pt::updateYearlySalaryTx(state, std::move(salary));
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed}});
        }
        default:
            return fail("NOT_IMPLEMENTED",
                        "unknown patrol action: " + std::to_string(actionId));
    }
}

/// 妖兽视图锁定 + 设置项字段补丁事务（ActionIds.BEAST_VIEW_LOCK_TX /
/// SETTINGS_PATCH_TX —— batch-23）。零 RNG；失败零写入 → 失败信封供
/// Kotlin 回退臂重执行（双实现并行契约，见 lock_beast_tx.h 头注释）。
nlohmann::json handleLockBeastTx(GameCore* core, int32_t actionId,
                                 const nlohmann::json& params) {
    namespace lbt = gamecore::system::lock_beast_tx;
    auto& state = core->state();

    auto toFailure = [](const lbt::TxResult& r) {
        return fail(r.errorType.empty() ? "INVALID" : r.errorType, r.message);
    };

    switch (actionId) {
        case action::BEAST_VIEW_LOCK_TX: {
            const auto r = lbt::lockBeastViewTx(
                state, params.at("beastId").get<std::string>(),
                params.at("locked").get<bool>());
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed}, {"lockedCount", r.lockedCount}});
        }
        case action::SETTINGS_PATCH_TX: {
            // 字段补丁：k/v 数组逐项解析（bool → 开关字段；int 数组 → Int 集字段）
            lbt::SettingPatch patch;
            for (const auto& e : params.at("patch")) {
                const std::string field = e.at("field").get<std::string>();
                const auto& value = e.at("value");
                if (value.is_boolean()) {
                    patch[field] = value.get<bool>();
                } else if (value.is_array()) {
                    std::vector<int32_t> ints;
                    ints.reserve(value.size());
                    for (const auto& v : value) ints.push_back(v.get<int32_t>());
                    patch[field] = std::move(ints);
                } else {
                    return fail("TypeMismatch", "未知设置项值类型 " + field);
                }
            }
            const auto r = lbt::updateSettingsTx(state, patch);
            if (!r.base.ok) return toFailure(r.base);
            return ok({{"changed", r.changed}, {"appliedFields", r.appliedFields}});
        }
        default:
            return fail("NOT_IMPLEMENTED",
                        "unknown residual action: " + std::to_string(actionId));
    }
}

/// 生产排程交互事务（ActionIds.PRODUCTION_START/RESET——
/// 手动排班 C++ 真相先行；Kotlin 后置持久化 + 消耗日志）
nlohmann::json handleProductionScheduling(GameCore* core, int32_t actionId,
                                          const nlohmann::json& params) {
    using gamecore::system::production::ProductionStartOutcome;
    switch (actionId) {
        case action::PRODUCTION_START: {
            const auto r = gamecore::system::production::startProductionTransaction(
                core->state(), params.at("buildingId").get<std::string>(),
                params.at("slotIndex").get<int32_t>(),
                params.at("recipeId").get<std::string>(),
                params.value("successRate", -1.0),
                params.value("policyBonus", 0.0),
                params.value("isAlchemy", false));
            if (!r.ok) {
                // 失败信封 → Kotlin 回退原路径重执行校验链（InsufficientMaterials
                // 的缺口明细由 Kotlin 侧自行构建——双实现并行契约）
                return fail(r.errorType, r.message);
            }
            return ok({{"started", true}});
        }
        case action::PRODUCTION_RESET: {
            const bool reset = gamecore::system::production::resetProductionSlotTransaction(
                core->state(), params.at("buildingId").get<std::string>(),
                params.at("slotIndex").get<int32_t>());
            if (!reset) return fail("InvalidSlot", "槽位不存在");
            return ok({{"reset", true}});
        }
        default:
            return fail("UNKNOWN_ACTION", "production scheduling action " + std::to_string(actionId));
    }
}

/// 生产 UI 操作事务（ActionIds.PROD_UI_*——batch-17：生产槽任命/卸任/
/// 自动续炼翻转/惰性建槽）。与 handleProductionScheduling（S7 手动排班）
/// 分开：本组只维护 gameData.productionSlots 镜像与全槽位清理，
/// Room 回放 / gate 注册 / 弟子状态推导为 Kotlin 残差（S7 同族）。
/// 校验链先行 → 失败零写入 → failure 信封 → Kotlin 回退原路径重执行校验链。
nlohmann::json handleProductionUiTx(GameCore* core, int32_t actionId,
                                    const nlohmann::json& params) {
    namespace prod = gamecore::system::production;
    auto& state = core->state();
    switch (actionId) {
        case action::PROD_UI_ASSIGN_SLOT: {
            const auto r = prod::assignProductionSlotTx(
                state, params.at("buildingType").get<std::string>(),
                params.at("slotIndex").get<int32_t>(),
                params.at("discipleId").get<std::string>(),
                params.value("discipleName", std::string()));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"assigned", true},
                       {"oldOccupantId", r.oldOccupantId},
                       {"oldOccupantName", r.oldOccupantName}});
        }
        case action::PROD_UI_REMOVE_SLOT: {
            const auto r = prod::removeProductionSlotDiscipleTx(
                state, params.at("buildingType").get<std::string>(),
                params.at("slotIndex").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"removed", true}, {"discipleId", r.discipleId}});
        }
        case action::PROD_UI_TOGGLE_AUTO_RESTART: {
            const auto r = prod::toggleAutoRestartTx(
                state, params.at("buildingType").get<std::string>(),
                params.at("slotIndex").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"toggled", true}, {"newValue", r.newValue}});
        }
        case action::PROD_UI_ADD_SLOT: {
            gamecore::state::ProductionSlot slot;
            slot.id = params.value("id", std::string());
            slot.slotIndex = params.value("slotIndex", 0);
            slot.buildingType = params.value("buildingType", std::string());
            slot.buildingId = params.value("buildingId", std::string());
            slot.status = params.value("status", std::string("IDLE"));
            const std::string recipeId = params.value("recipeId", std::string());
            if (!recipeId.empty()) slot.recipeId = recipeId;
            slot.recipeName = params.value("recipeName", std::string());
            slot.startYear = params.value("startYear", 0);
            slot.startMonth = params.value("startMonth", 0);
            slot.duration = params.value("duration", 0);
            slot.baseDuration = params.value("baseDuration", 0);
            const std::string assignedId = params.value("assignedDiscipleId", std::string());
            if (!assignedId.empty()) slot.assignedDiscipleId = assignedId;
            slot.assignedDiscipleName = params.value("assignedDiscipleName", std::string());
            slot.successRate = params.value("successRate", 0.0);
            const std::string outputItemId = params.value("outputItemId", std::string());
            if (!outputItemId.empty()) slot.outputItemId = outputItemId;
            slot.outputItemName = params.value("outputItemName", std::string());
            slot.outputItemRarity = params.value("outputItemRarity", 1);
            slot.outputItemSlot = params.value("outputItemSlot", std::string());
            slot.expectedYield = params.value("expectedYield", 0);
            slot.autoRestartEnabled = params.value("autoRestartEnabled", false);
            slot.completionMonth = params.value("completionMonth", 0);
            slot.completionPhase = params.value("completionPhase", 1);
            const auto r = prod::addProductionSlotTx(state, slot);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"added", true}, {"created", r.created}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "production ui tx action " + std::to_string(actionId));
    }
}

/// 灵田种植族 UI 操作事务（ActionIds.SPIRIT_FIELD_PLANT_*/REMOVE_*——batch-17：
/// 单/批播种与移除，零 RNG 纯数据变换 + 同事务扣种）。与
/// SPIRIT_FIELD_HARVEST（月结收获，SYSTEM 分区抽取）分开。
/// 校验链先行（种子存在/未锁定/余量>0）→ 失败零写入 → failure 信封 →
/// Kotlin 回退原路径重执行校验链。
nlohmann::json handleSpiritFieldPlantTx(GameCore* core, int32_t actionId,
                                        const nlohmann::json& params) {
    namespace sfx = gamecore::system::spirit_field_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::SPIRIT_FIELD_PLANT_ONE: {
            const auto r = sfx::plantOnSpiritFieldTx(
                state, params.at("buildingInstanceId").get<std::string>(),
                params.at("seedId").get<std::string>(),
                params.value("sectId", std::string()));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"planted", r.planted}});
        }
        case action::SPIRIT_FIELD_PLANT_BATCH: {
            std::vector<std::string> instanceIds;
            if (params.contains("instanceIds")) {
                for (const auto& e : params.at("instanceIds")) {
                    instanceIds.push_back(e.get<std::string>());
                }
            }
            const auto r = sfx::plantOnSpiritFieldsTx(
                state, instanceIds, params.at("seedId").get<std::string>(),
                params.value("sectId", std::string()));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"planted", r.planted}});
        }
        case action::SPIRIT_FIELD_REMOVE_ONE: {
            const auto r = sfx::removePlantFromSpiritFieldTx(
                state, params.at("buildingInstanceId").get<std::string>());
            return ok({{"removed", r.removed}});
        }
        case action::SPIRIT_FIELD_REMOVE_BATCH: {
            std::vector<std::string> instanceIds;
            if (params.contains("instanceIds")) {
                for (const auto& e : params.at("instanceIds")) {
                    instanceIds.push_back(e.get<std::string>());
                }
            }
            const auto r = sfx::removePlantsFromSpiritFieldsTx(state, instanceIds);
            return ok({{"removed", r.removed}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "spirit field plant tx action " + std::to_string(actionId));
    }
}

/// 道路放置/拆除事务（ActionIds.ROAD_PLACE/ROAD_REMOVE——
/// Kotlin RoadFacade AUTHORITATIVE 转发；失败零写入，失败信封 →
/// Kotlin 回退原路径重执行校验链。占位几何参数与占用集合（本宗建筑占地
/// ∪ 固定结构——宗门过滤需 sectId，C++ GridBuildingData 不承载）由
/// Kotlin 组装传入）
nlohmann::json handleRoadTx(GameCore* core, int32_t actionId,
                            const nlohmann::json& params) {
    auto& gd = core->state().gameData;
    const int32_t gridX = params.at("gridX").get<int32_t>();
    const int32_t gridY = params.at("gridY").get<int32_t>();
    std::vector<int64_t> occupiedCells;
    if (params.contains("occupiedCells")) {
        for (const auto& c : params.at("occupiedCells")) {
            occupiedCells.push_back(c.get<int64_t>());
        }
    }
    switch (actionId) {
        case action::ROAD_PLACE: {
            const auto r = gamecore::system::placeRoadTx(
                gd, gridX, gridY,
                params.at("width").get<int32_t>(),
                params.at("height").get<int32_t>(),
                params.at("border").get<int32_t>(),
                params.at("cost").get<int64_t>(),
                occupiedCells);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"placed", true}, {"spiritStones", r.spiritStonesAfter}});
        }
        case action::ROAD_REMOVE: {
            const auto r = gamecore::system::removeRoadTx(
                gd, gridX, gridY,
                params.at("width").get<int32_t>(),
                params.at("height").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"removed", true}, {"spiritStones", r.spiritStonesAfter}});
        }
        default:
            return fail("UNKNOWN_ACTION", "road tx action " + std::to_string(actionId));
    }
}

/// 弟子管理 UI 操作事务（ActionIds.DISCIPLE_TX_*——batch-08 第一子批：
/// 装备穿脱/功法学习卸下/任命卸任；零 RNG 纯事务，失败零写入，
/// 失败信封 → Kotlin 回退原路径重执行校验链。equip 成功信封附
/// logLine 日志草稿（Kotlin lifeEvents 瞬态列回写），
/// 任命/卸任信封附 occupant（Kotlin gate 注册表释放用））
nlohmann::json handleDiscipleTx(GameCore* core, int32_t actionId,
                                const nlohmann::json& params) {
    namespace disciple_tx = gamecore::system::disciple_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::DISCIPLE_TX_EQUIP: {
            const auto r = disciple_tx::equipTransaction(
                state, params.at("discipleId").get<std::string>(),
                params.at("equipmentId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"equipped", true}, {"logLine", r.logLine}});
        }
        case action::DISCIPLE_TX_UNEQUIP: {
            const auto r = disciple_tx::unequipTransaction(
                state, params.at("discipleId").get<std::string>(),
                params.at("equipmentId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"unequipped", true}});
        }
        case action::DISCIPLE_TX_LEARN_MANUAL: {
            const auto r = disciple_tx::learnManualTransaction(
                state, params.at("discipleId").get<std::string>(),
                params.at("stackId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"learned", true}});
        }
        case action::DISCIPLE_TX_UNLEARN_MANUAL: {
            const auto r = disciple_tx::unlearnManualTransaction(
                state, params.at("discipleId").get<std::string>(),
                params.at("instanceId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"unlearned", true}});
        }
        case action::DISCIPLE_TX_ASSIGN_SLOT: {
            const auto family = params.value("family", "elderDirect") == "library"
                                    ? disciple_tx::SlotFamily::kLibrary
                                    : disciple_tx::SlotFamily::kElderDirect;
            const auto r = disciple_tx::assignSlotTransaction(
                state, family, params.value("elderSlotType", ""),
                params.at("slotIndex").get<int32_t>(),
                params.at("discipleId").get<std::string>(),
                params.value("discipleName", ""),
                params.value("discipleRealm", ""),
                params.value("spiritRootColor", ""));
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"assigned", true}, {"oldOccupantId", r.oldOccupantId}});
        }
        case action::DISCIPLE_TX_UNASSIGN_SLOT: {
            const auto family = params.value("family", "elderDirect") == "library"
                                    ? disciple_tx::SlotFamily::kLibrary
                                    : disciple_tx::SlotFamily::kElderDirect;
            const auto r = disciple_tx::unassignSlotTransaction(
                state, family, params.value("elderSlotType", ""),
                params.at("slotIndex").get<int32_t>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"unassigned", true}, {"removedDiscipleId", r.removedDiscipleId}});
        }
        default:
            return fail("UNKNOWN_ACTION", "disciple tx action " + std::to_string(actionId));
    }
}

/// 外交/好感/附庸 UI 操作事务（ActionIds.DIPLOMACY_TX/FAVOR_GIFT/VASSAL_TX
/// ——Kotlin GiftService/DiplomacyService/VassalService 写者下沉，batch-09；
/// 校验失败 failure 信封回退 Kotlin 原路径（零抽取零写入），roll 后结果
/// success 信封直返——双臂 SYSTEM 分区同源、抽取序逐位一致）
nlohmann::json handleDiplomacyTx(GameCore* core, int32_t actionId,
                                 const nlohmann::json& params) {
    using namespace gamecore::system::diplomacy_tx;
    auto& state = core->state();
    const std::string op = params.value("op", "");
    switch (actionId) {
        case action::DIPLOMACY_TX: {
            if (op == "request_alliance") {
                TxRejection rejection;
                const auto r = requestAllianceTransaction(
                    state, core->rng(), core->ecsWorld(),
                    params.at("sectId").get<std::string>(), rejection);
                if (!rejection.errorType.empty()) {
                    return fail(rejection.errorType, "alliance request rejected");
                }
                return ok({{"success", r.success}});
            }
            if (op == "dissolve_alliance") {
                TxRejection rejection;
                const auto r = dissolveAllianceTransaction(
                    state, params.at("sectId").get<std::string>(), rejection);
                if (!rejection.errorType.empty()) {
                    return fail(rejection.errorType, "no alliance to dissolve");
                }
                return ok({{"success", r.success}});
            }
            return fail("UNKNOWN_OP", "diplomacy op " + op);
        }
        case action::FAVOR_GIFT: {
            TxRejection rejection;
            const auto r = giftSpiritStonesTransaction(
                state, core->rng(), params.at("sectId").get<std::string>(),
                params.at("tier").get<int32_t>(),
                params.value("bypassYearLimit", false), rejection);
            if (!rejection.errorType.empty()) {
                // 校验失败（Kotlin 同位置早退零抽取）→ 回退臂同语义复现
                return fail(rejection.errorType, "gift validation failed");
            }
            // roll 已消费：success 信封直返，Kotlin 侧按 responseType 重建
            // GiftResult（message 模板留 Kotlin——非游戏分区抽取）
            return ok({{"outcome", r.responseType},
                       {"favorChange", r.favorChange},
                       {"newFavor", r.newFavor},
                       {"sectLevel", r.sectLevel},
                       {"neededStones", r.neededStones}});
        }
        case action::VASSAL_TX: {
            if (op == "request_contract") {
                TxRejection rejection;
                const auto r = requestVassalTransaction(
                    state, core->rng(), core->ecsWorld(),
                    params.at("sectId").get<std::string>(), rejection);
                if (!rejection.errorType.empty()) {
                    return fail(rejection.errorType, "vassal request rejected");
                }
                return ok({{"success", r.success}});
            }
            if (op == "dissolve_contract") {
                const auto r = dissolveVassalTransaction(
                    state, params.at("sectId").get<std::string>());
                return ok({{"success", r.success}});
            }
            return fail("UNKNOWN_OP", "vassal op " + op);
        }
        default:
            return fail("UNKNOWN_ACTION", "diplomacy tx action " + std::to_string(actionId));
    }
}

/// 建筑放置/迁移/升级/拆除事务（ActionIds.BUILDING_*——batch-06 下沉；
/// 校验链逐字对齐 Kotlin 判定序，失败零写入 → Kotlin 回退原路径。宗门
/// 过滤与占位几何常量由 Kotlin 组装传入——C++ GridBuildingData 无 sectId
///（§2.36 同偏差登记）；place/upgrade 低阶直扣、remove 走 wallet 返还）
nlohmann::json handleBuildingTx(GameCore* core, int32_t actionId,
                                const nlohmann::json& params) {
    namespace btx = gamecore::system::building_tx;
    using gamecore::state::GridBuildingData;
    auto& gd = core->state().gameData;
    const auto geom = [&] {
        btx::PlaceGeom g;
        g.border = params.value("border", 0);
        g.worldWidth = params.value("worldWidth", 0);
        g.worldHeight = params.value("worldHeight", 0);
        g.gateX = params.value("gateX", 0);
        g.gateY = params.value("gateY", 0);
        g.gateWidth = params.value("gateWidth", 0);
        g.gateHeight = params.value("gateHeight", 0);
        return g;
    }();
    const auto scopeFrom = [&] {
        std::vector<std::string> ids;
        if (params.contains("sectScopedIds")) {
            for (const auto& id : params.at("sectScopedIds")) {
                ids.push_back(id.get<std::string>());
            }
        }
        return ids;
    }();
    switch (actionId) {
        case action::BUILDING_PLACE: {
            GridBuildingData b;
            b.buildingId = params.at("buildingId").get<std::string>();
            b.displayName = params.at("displayName").get<std::string>();
            b.gridX = params.at("gridX").get<int32_t>();
            b.gridY = params.at("gridY").get<int32_t>();
            b.width = params.at("width").get<int32_t>();
            b.height = params.at("height").get<int32_t>();
            b.instanceId = params.at("instanceId").get<std::string>();
            const auto r = btx::placeBuildingTx(
                gd, b, params.at("cost").get<int64_t>(),
                params.value("requiredSectLevel", 0),
                params.value("unlimitedBuild", false),
                params.value("globallyUnique", false),
                params.at("counterKey").get<std::string>(), geom, scopeFrom);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"placed", true},
                       {"instanceId", b.instanceId},
                       {"spiritStones", r.spiritStonesAfter}});
        }
        case action::BUILDING_MOVE: {
            const auto r = btx::moveBuildingTx(
                gd, params.at("instanceId").get<std::string>(),
                params.at("newGridX").get<int32_t>(),
                params.at("newGridY").get<int32_t>(), geom);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"moved", true}, {"spiritStones", r.spiritStonesAfter}});
        }
        case action::BUILDING_UPGRADE: {
            const auto r = btx::upgradeBuildingTx(
                gd, params.at("instanceId").get<std::string>(),
                params.at("targetKey").get<std::string>(),
                params.at("targetDisplayName").get<std::string>(),
                params.at("targetWidth").get<int32_t>(),
                params.at("targetHeight").get<int32_t>(),
                params.at("cost").get<int64_t>(), geom, scopeFrom);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"upgraded", true},
                       {"count", r.upgradedCount},
                       {"spiritStones", r.spiritStonesAfter}});
        }
        case action::BUILDING_UPGRADE_BATCH: {
            const auto r = btx::upgradeBuildingsTx(
                gd, params.at("sourceKey").get<std::string>(),
                params.at("targetKey").get<std::string>(),
                params.at("targetDisplayName").get<std::string>(),
                params.at("targetWidth").get<int32_t>(),
                params.at("targetHeight").get<int32_t>(),
                params.at("maxCount").get<int32_t>(),
                params.at("cost").get<int64_t>(), geom, scopeFrom);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"upgradedCount", r.upgradedCount},
                       {"spaceBlockedCount", r.spaceBlockedCount},
                       {"spiritStones", r.spiritStonesAfter}});
        }
        case action::BUILDING_REMOVE: {
            std::vector<std::pair<std::string, int64_t>> refunds;
            for (const auto& e : params.at("refunds")) {
                refunds.emplace_back(e.at("instanceId").get<std::string>(),
                                     e.at("refund").get<int64_t>());
            }
            const auto r = btx::removeBuildingsTx(gd, refunds);
            nlohmann::json removed = nlohmann::json::array();
            for (const auto& id : r.removedInstanceIds) removed.push_back(id);
            return ok({{"removed", true},
                       {"removedIds", std::move(removed)},
                       {"spiritStones", r.spiritStonesAfter}});
        }
        default:
            return fail("UNKNOWN_ACTION", "building tx action " + std::to_string(actionId));
    }
}

/// 死亡物化操作（ActionIds.DISCIPLE_MARK_DEAD /
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

/// 库存出售/上架 UI 操作事务（ActionIds.INV_SELL_ITEM / INV_BULK_SELL /
/// MERCHANT_SELL_ACQUISITION / MERCHANT_LIST_ITEMS / MERCHANT_REMOVE_LISTED
/// ——W2-a 出售族写者下沉；零 RNG 纯确定性变换，校验失败零写入，
/// 失败信封 → Kotlin 回退原路径重执行校验链）
nlohmann::json handleInventoryTx(GameCore* core, int32_t actionId,
                                 const nlohmann::json& params) {
    namespace inventory_tx = gamecore::system::inventory_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::INV_SELL_ITEM: {
            const auto r = inventory_tx::sellItemTx(
                state, params.value("itemType", ""),
                params.at("itemId").get<std::string>(),
                params.at("quantity").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            const bool sold = r.earned > 0;
            return ok({{"sold", sold}, {"earned", r.earned}});
        }
        case action::INV_BULK_SELL: {
            std::vector<inventory_tx::BulkSellRequest> operations;
            if (params.contains("operations")) {
                for (const auto& op : params.at("operations")) {
                    inventory_tx::BulkSellRequest request;
                    request.id = op.value("id", "");
                    request.name = op.value("name", "");
                    request.itemType = op.value("itemType", "");
                    request.quantity = op.value("quantity", 0);
                    operations.push_back(std::move(request));
                }
            }
            const auto r = inventory_tx::bulkSellTx(state, operations);
            nlohmann::json soldNames = nlohmann::json::array();
            for (const auto& name : r.soldItemNames) soldNames.push_back(name);
            nlohmann::json failedNames = nlohmann::json::array();
            for (const auto& name : r.failedItemNames) failedNames.push_back(name);
            return ok({{"soldCount", r.soldCount},
                       {"totalEarned", r.totalEarned},
                       {"soldItemNames", std::move(soldNames)},
                       {"failedItemNames", std::move(failedNames)}});
        }
        case action::MERCHANT_SELL_ACQUISITION: {
            const auto r = inventory_tx::sellToMerchantTx(
                state, params.at("acquisitionItemId").get<std::string>(),
                params.at("quantity").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"soldQuantity", r.soldQuantity}, {"totalPrice", r.totalPrice}});
        }
        case action::MERCHANT_LIST_ITEMS: {
            std::vector<inventory_tx::ListItemRequest> items;
            if (params.contains("items")) {
                for (const auto& entry : params.at("items")) {
                    inventory_tx::ListItemRequest request;
                    request.itemId = entry.at("itemId").get<std::string>();
                    request.quantity = entry.value("quantity", 0);
                    items.push_back(std::move(request));
                }
            }
            const auto r = inventory_tx::listItemsToMerchantTx(state, items);
            return ok({{"listedCount", r.listedCount}});
        }
        case action::MERCHANT_REMOVE_LISTED: {
            inventory_tx::removePlayerListedItemTx(
                state, params.at("itemId").get<std::string>());
            return ok({{"removed", true}});
        }
        case action::INV_CONSUME_MATERIAL: {
            const bool consumed = inventory_tx::consumeMaterialByNameTx(
                state, params.at("name").get<std::string>(),
                params.at("rarity").get<int32_t>(),
                params.at("quantity").get<int32_t>());
            return ok({{"consumed", consumed}});
        }
        case action::INV_BUY_MERCHANT_ITEM: {
            const auto r = inventory_tx::buyMerchantItemTx(
                state, params.at("itemId").get<std::string>(),
                params.at("quantity").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            nlohmann::json drafts = nlohmann::json::array();
            for (const auto& d : r.overflowDrafts) {
                drafts.push_back({
                    {"source", d.source}, {"itemType", d.itemType},
                    {"itemName", d.itemName}, {"itemId", d.itemId},
                    {"rarity", d.rarity}, {"quantity", d.quantity},
                    {"category", d.category}, {"grade", d.grade},
                    {"slot", d.slot}, {"type", d.type},
                    {"growTime", d.growTime}, {"yield", d.yield}});
            }
            // 奖励卡片由 Kotlin 构造（name/type/rarity/quantity 随信封回传）
            return ok({{"bought", r.bought},
                       {"itemName", r.itemName},
                       {"itemType", r.itemType},
                       {"rarity", r.rarity},
                       {"quantity", r.quantity},
                       {"overflowDrafts", std::move(drafts)}});
        }
        case action::INV_CONFISCATE_BAG_ITEM: {
            const auto r = inventory_tx::confiscateStorageBagItemTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("itemId").get<std::string>());
            // 溢出抑制上下文：恒无草稿；幂等 no-op 臂 confiscated=false
            return ok({{"confiscated", r.confiscated}});
        }
        case action::STORAGE_BAG_OPEN_TX: {
            // 开袋抽签（ADR rng-determinism-remediation 阶段 1①）：
            // 只消费 EXPLORATION 分区产出确定性描述符序列，不触碰游戏状态；
            // 模板物化 + addXxx 入仓留 Kotlin（13.3 红线 + 模板库在 Kotlin）
            const auto r = gamecore::system::storage_bag_tx::openStorageBagTx(
                core->rng().getRng(gamecore::rng::RngPartition::kExploration),
                params.value("bagId", std::string()),
                params.at("rarity").get<int32_t>());
            if (!r.ok) return fail(r.errorType, r.message);
            nlohmann::json draws = nlohmann::json::array();
            for (const auto& d : r.draws) {
                draws.push_back({{"kind", d.kind}});
            }
            return ok({{"count", static_cast<int32_t>(r.draws.size())},
                       {"draws", std::move(draws)}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "inventory tx action " + std::to_string(actionId));
    }
}

/// 弟子管理三事务（ActionIds.ELDER_APPOINT_TX / ELDER_DISMISS_TX /
/// WAREHOUSE_GARRISON_TX / SPIRIT_ROOT_WASH_TX / TRAIT_ADD_ROLL_TX /
/// TRAIT_ADD_CONFIRM_TX / TRAIT_WASH_SLOT_TX——batch-15 任命/驻守/洗炼
/// 消耗族写者下沉；任命/驻守/特质确认零 RNG 纯事务，洗炼三族含玉符消耗
/// （C++ 承扣，余额检查+扣减与抽取同事务原子）与 SYSTEM 分区抽取。校验
/// 失败零写入，失败信封 → Kotlin 回退原路径重执行校验链；洗炼成功信封
/// 附 jadeAfter（Kotlin 运行时 totalCount 同步残差用））
nlohmann::json handleAppointmentTx(GameCore* core, int32_t actionId,
                                   const nlohmann::json& params) {
    namespace appointment_tx = gamecore::system::appointment_tx;
    auto& state = core->state();
    auto& systemRng = core->rng().getRng(gamecore::rng::RngPartition::kSystem);
    switch (actionId) {
        case action::ELDER_APPOINT_TX: {
            const auto r = appointment_tx::elderAppointTx(
                state, params.at("slotType").get<std::string>(),
                params.at("discipleId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            nlohmann::json replaced = nlohmann::json::array();
            for (const auto& id : r.replacedIds) replaced.push_back(id);
            return ok({{"appointed", true}, {"replacedIds", std::move(replaced)}});
        }
        case action::ELDER_DISMISS_TX: {
            const auto r = appointment_tx::elderDismissTx(
                state, params.at("slotType").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"dismissed", true}, {"removedId", r.removedId}});
        }
        case action::WAREHOUSE_GARRISON_TX: {
            const auto r = appointment_tx::warehouseGarrisonAssignTx(
                state, params.at("buildingInstanceId").get<std::string>(),
                params.at("discipleId").get<std::string>(),
                params.value("discipleName", ""),
                params.value("sectId", ""));
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"assigned", true}, {"oldOccupantId", r.oldOccupantId}});
        }
        case action::SPIRIT_ROOT_WASH_TX: {
            const auto r = appointment_tx::spiritRootWashTx(
                state, systemRng, params.at("discipleId").get<std::string>(),
                params.at("pityCount").get<int32_t>(),
                params.at("cost").get<int32_t>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"newRootType", r.newRootType},
                       {"newPityCount", r.newPityCount},
                       {"jadeAfter", r.jadeAfter}});
        }
        case action::TRAIT_ADD_ROLL_TX: {
            const auto r = appointment_tx::traitAddRollTx(
                state, systemRng, params.at("discipleId").get<std::string>(),
                params.at("type").get<std::string>(),
                params.at("cost").get<int32_t>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"newId", r.newId}, {"jadeAfter", r.jadeAfter}});
        }
        case action::TRAIT_ADD_CONFIRM_TX: {
            const auto r = appointment_tx::traitAddConfirmTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("type").get<std::string>(),
                params.at("newId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"confirmed", true}});
        }
        case action::TRAIT_WASH_SLOT_TX: {
            const auto r = appointment_tx::traitWashSlotTx(
                state, systemRng, params.at("discipleId").get<std::string>(),
                params.at("type").get<std::string>(),
                params.at("targetId").get<std::string>(),
                params.at("pityCount").get<int32_t>(),
                params.at("cost").get<int32_t>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"newId", r.newId},
                       {"newPityCount", r.newPityCount},
                       {"jadeAfter", r.jadeAfter}});
        }
        // ── batch-24：confirm 两入口（纯数据写残差，零 RNG / 零玉符）──
        case action::SPIRIT_ROOT_WASH_CONFIRM_TX: {
            const auto r = appointment_tx::spiritRootWashConfirmTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("newRootType").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"replaced", true}});
        }
        case action::TRAIT_WASH_CONFIRM_TX: {
            const auto r = appointment_tx::traitWashConfirmTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("type").get<std::string>(),
                params.at("targetId").get<std::string>(),
                params.at("newId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"replaced", true}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "appointment tx action " + std::to_string(actionId));
    }
}

/// 招募域 UI 操作事务（ActionIds.RECRUIT_REMOVE_TX / RECRUIT_REFRESH_TX /
/// RECRUIT_AGE_TX——batch-16 招募列表维护族写者下沉：移除/老化净化零 RNG
/// 纯事务，刷新复用 year_settlement 候选生成链（SYSTEM 分区，与 Kotlin 臂
/// 逐位同源；差值门内置于 C++ 链）。失败信封 → Kotlin 回退原路径重执行
/// 校验链。命名独立于既有招募专用 JNI（nativeRecruitAllFromList），中央
/// switch 范围分支不重叠）
nlohmann::json handleRecruitTx(GameCore* core, int32_t actionId,
                               const nlohmann::json& params) {
    namespace recruit_tx = gamecore::system::recruit_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::RECRUIT_REMOVE_TX: {
            const auto r = recruit_tx::removeRecruitTx(
                state, params.at("discipleId").get<std::string>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"removed", r.removed}, {"remaining", r.remaining}});
        }
        case action::RECRUIT_REFRESH_TX: {
            const auto r = recruit_tx::refreshRecruitTx(
                state, params.value("year", 1), core->rng());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"generated", r.generated},
                       {"autoRecruited", r.autoRecruited},
                       {"remaining", r.remaining}});
        }
        case action::RECRUIT_AGE_TX: {
            const auto r = recruit_tx::ageRecruitTx(state, core->ecsWorld());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"removed", r.removed}, {"remaining", r.remaining}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "recruit tx action " + std::to_string(actionId));
    }
}

/// 探索域 UI 操作事务（ActionIds.EXPLORE_TX_*——batch-13：世界关卡/侦察
/// 战斗执行（BATTLE 分区）+ 伤亡写回（袋物化）+ 分舵驻守零 RNG 事务；
/// 命名独立于既有 handleExploration（月结/关卡域），中央 switch 范围分支
/// 不重叠。失败信封 → Kotlin 回退原路径重执行校验链）
nlohmann::json handleExplorationTx(GameCore* core, int32_t actionId,
                                   const nlohmann::json& params) {
    namespace exploration_tx = gamecore::system::exploration_tx;
    auto& state = core->state();
    const auto idSetJson = [](const std::vector<std::string>& ids) {
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& id : ids) arr.push_back(id);
        return arr;
    };
    const auto draftsJson = [](const std::vector<gamecore::system::OverflowDraft>& ds) {
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& d : ds) {
            arr.push_back({{"itemType", d.itemType},
                           {"itemName", d.itemName},
                           {"itemId", d.itemId},
                           {"rarity", d.rarity},
                           {"quantity", d.quantity},
                           {"source", d.source}});
        }
        return arr;
    };
    const auto battleJson = [](const gamecore::battle::BattleResult& b) {
        nlohmann::json team = nlohmann::json::array();
        for (const auto& c : b.team) team.push_back(gamecore::battle::combatantToJson(c));
        nlohmann::json beasts = nlohmann::json::array();
        for (const auto& c : b.beasts) beasts.push_back(gamecore::battle::combatantToJson(c));
        return nlohmann::json{
            {"winner", b.winner == gamecore::battle::BattleWinner::kTeam
                           ? "TEAM"
                           : b.winner == gamecore::battle::BattleWinner::kBeasts
                                 ? "BEASTS"
                                 : "DRAW"},
            {"turn", b.turn},
            {"team", std::move(team)},
            {"beasts", std::move(beasts)},
            {"rounds", gamecore::battle::roundsToJson(b.rounds)},
        };
    };
    switch (actionId) {
        case action::EXPLORE_TX_ATTACK_WORLD_LEVEL: {
            std::vector<std::string> discipleIds;
            for (const auto& id : params.at("discipleIds")) {
                discipleIds.push_back(id.get<std::string>());
            }
            gamecore::system::OverflowMailCollector overflowMail;
            const auto r = exploration_tx::attackWorldLevelTx(
                state, core->rng(), params.at("levelId").get<std::string>(),
                discipleIds, params.value("playerDamageModifier", 1.0),
                overflowMail);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"victory", r.victory},
                       {"survivorIds", idSetJson(r.survivorIds)},
                       {"deadIds", idSetJson(r.deadIds)},
                       {"rewards", r.rewards},
                       {"battle", battleJson(r.battle)},
                       {"overflowDrafts", draftsJson(r.overflowDrafts)}});
        }
        case action::EXPLORE_TX_SCOUT_SECT: {
            std::vector<std::string> memberIds;
            for (const auto& id : params.at("memberIds")) {
                memberIds.push_back(id.get<std::string>());
            }
            gamecore::system::OverflowMailCollector overflowMail;
            const auto r = exploration_tx::scoutSectTx(
                state, core->rng(), params.at("sectId").get<std::string>(),
                memberIds, params.value("playerDamageModifier", 1.0),
                overflowMail);
            if (!r.ok) return fail(r.errorType, r.message);
            nlohmann::json defenders = nlohmann::json::array();
            for (const auto& v : r.defenderViews) {
                defenders.push_back({{"id", v.id},
                                     {"realmName", v.realmName},
                                     {"realm", v.realm},
                                     {"realmLayer", v.realmLayer},
                                     {"maxHp", v.maxHp},
                                     {"portraitRes", v.portraitRes}});
            }
            return ok({{"victory", r.victory},
                       {"survivorIds", idSetJson(r.survivorIds)},
                       {"deadIds", idSetJson(r.deadIds)},
                       {"rewards", r.rewards},
                       {"battle", battleJson(r.battle)},
                       {"defenderViews", std::move(defenders)},
                       {"overflowDrafts", draftsJson(r.overflowDrafts)}});
        }
        case action::EXPLORE_TX_ASSIGN_GARRISON: {
            const auto r = exploration_tx::assignGarrisonTx(
                state, params.at("sectId").get<std::string>(),
                params.at("slotIndex").get<int32_t>(),
                params.at("discipleId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"written", r.written},
                       {"oldOccupantId", r.oldOccupantId}});
        }
        case action::EXPLORE_TX_REMOVE_GARRISON: {
            const auto r = exploration_tx::removeGarrisonTx(
                state, params.at("sectId").get<std::string>(),
                params.at("slotIndex").get<int32_t>());
            return ok({{"currentDiscipleId", r.currentDiscipleId}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "exploration tx action " + std::to_string(actionId));
    }
}

/// 玉符/宗门升级落账事务（ActionIds.SECT_LEVEL_UPGRADE_TX /
/// SECT_LEVEL_CLAIM_TX / JADE_PURCHASE_MERCHANT_REFRESH_TX /
/// JADE_PURCHASE_BREAKTHROUGH_BONUS_TX——batch-19：运营商城与宗门升级族中
/// 零 RNG 且模板已定的**落账段**下沉；命名独立于既有 handleRedeemCode
/// （兑换码原语域）与 handleSectDiplomacy（外交域），中央 switch 范围分支
/// 不重叠。失败信封 → Kotlin 回退原路径重执行校验链）
nlohmann::json handleJadeTx(GameCore* core, int32_t actionId,
                            const nlohmann::json& params) {
    namespace jade_tx = gamecore::system::jade_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::SECT_LEVEL_UPGRADE_TX: {
            const auto r = jade_tx::upgradeSectLevelTx(
                state, params.at("targetLevel").get<int32_t>(),
                params.value("levelName", std::string()));
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"newLevel", r.newLevel}, {"levelName", r.levelName}});
        }
        case action::SECT_LEVEL_CLAIM_TX: {
            std::vector<jade_tx::ClaimMaterial> materials;
            if (params.contains("materials")) {
                for (const auto& m : params.at("materials")) {
                    jade_tx::ClaimMaterial item;
                    item.name = m.value("name", std::string());
                    item.rarity = m.value("rarity", 1);
                    item.category = m.value("category", std::string("BEAST_HIDE"));
                    item.quantity = m.value("quantity", 1);
                    materials.push_back(std::move(item));
                }
            }
            std::vector<jade_tx::ClaimStorageBag> storageBags;
            if (params.contains("storageBags")) {
                for (const auto& b : params.at("storageBags")) {
                    jade_tx::ClaimStorageBag item;
                    item.name = b.value("name", std::string());
                    item.rarity = b.value("rarity", 1);
                    item.quantity = b.value("quantity", 1);
                    storageBags.push_back(std::move(item));
                }
            }
            const auto r = jade_tx::claimSectLevelRewardTx(
                state, params.at("level").get<int32_t>(),
                params.value("nowMs", static_cast<int64_t>(0)), materials, storageBags,
                params.value("spiritStones", static_cast<int64_t>(0)));
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"materialCount", r.materialCount},
                       {"storageBagCount", r.storageBagCount},
                       {"spiritStones", r.spiritStones},
                       {"claimedLevel", r.claimedLevel}});
        }
        case action::JADE_PURCHASE_MERCHANT_REFRESH_TX: {
            const auto r = jade_tx::purchaseMerchantRefreshTx(
                state, params.at("cost").get<int32_t>(),
                params.at("perJade").get<int32_t>(),
                params.at("maxChances").get<int32_t>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"jadeSymbols", r.jadeSymbols},
                       {"merchantRefreshChances", r.value}});
        }
        case action::JADE_PURCHASE_BREAKTHROUGH_BONUS_TX: {
            const auto r = jade_tx::purchaseBreakthroughBonusTx(
                state, params.at("discipleId").get<std::string>(),
                params.at("cost").get<int32_t>(), params.at("perJade").get<double>(),
                params.at("maxBonus").get<double>());
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"jadeSymbols", r.jadeSymbols}, {"bonus", r.writtenValue}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "jade tx action " + std::to_string(actionId));
    }
}

/// 月年边界编排族·引导计数面（ActionIds.BOUNDARY_*——batch-18a：引导计数递增 /
/// 自动分配策略+计数合并写 / 建造计数回填，零 RNG 纯确定性事务）。失败信封 →
/// Kotlin 回退原路径重执行校验链；命名独立于既有 handler，中央 switch 范围
/// 分支不重叠（1670-1672）。
nlohmann::json handleBoundaryTx(GameCore* core, int32_t actionId,
                                const nlohmann::json& params) {
    namespace boundary_tx = gamecore::system::boundary_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::BOUNDARY_GUIDE_COUNTER_INCREMENT_TX: {
            const auto r = boundary_tx::incrementGuideCounterTx(
                state, params.at("key").get<std::string>(),
                params.value("amount", static_cast<int64_t>(1)));
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"newValue", r.newValue}});
        }
        case action::BOUNDARY_AUTO_ASSIGN_GUIDE_TX: {
            const auto r = boundary_tx::autoAssignGuideBatchTx(
                state, params.at("policies").get<gamecore::state::SectPolicies>(),
                params.value("mineActivated", false),
                params.value("plantActivated", false),
                params.value("productionActivated", false));
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"autoMineActivated", r.autoMineActivated},
                       {"autoPlantActivated", r.autoPlantActivated},
                       {"autoProductionActivated", r.autoProductionActivated},
                       {"countersChanged", r.countersChanged}});
        }
        case action::BOUNDARY_BUILDING_GUIDE_BACKFILL_TX: {
            const auto r = boundary_tx::backfillBuildingGuideCountersTx(state);
            if (!r.base.ok) return fail(r.base.errorType, r.base.message);
            return ok({{"changed", r.changed},
                       {"backfilledKeys", r.backfilledKeys}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "boundary tx action " + std::to_string(actionId));
    }
}

/// 政策开关事务（ActionIds.GOV_POLICY_TOGGLE_TX / GOV_OPEN_RECRUITMENT_TOGGLE_TX /
/// GOV_SPIRIT_MINE_BOOST_TOGGLE_TX——batch-18b：政策置位 + 首月扣费 + 激活计数 +
/// 修炼全量 checkpoint，零 RNG）。失败信封 → Kotlin 回退臂重执行判定链并产出
/// 用户可见文案。`productionCheckpointNeeded` 回执由 Kotlin 臂消费以触发
/// checkpointAllProduction（CLAUDE.md 6.4/13.3 红线——生产槽位真源在 Kotlin）。
/// 命名与 handleGovernment（1300-1304 月结配方域）独立，范围分支不重叠。
nlohmann::json handlePolicyTx(GameCore* core, int32_t actionId,
                              const nlohmann::json& params) {
    namespace sys = gamecore::system;
    auto& state = core->state();
    const auto payload = [](const sys::PolicyToggleOutcome& r) {
        return nlohmann::json{{"wasEnabled", r.wasEnabled},
                              {"enabled", r.enabled},
                              {"costPaid", r.costPaid},
                              {"cultivationCheckpoint", r.cultivationCheckpoint},
                              {"productionCheckpointNeeded", r.productionCheckpointNeeded},
                              {"checkpointMonth", r.checkpointMonth}};
    };
    switch (actionId) {
        case action::GOV_POLICY_TOGGLE_TX: {
            const auto r = sys::policyToggleTx(
                state, params.at("field").get<std::string>(),
                params.value("monthlyCost", static_cast<int64_t>(0)),
                params.value("affectsCultivationRate", false));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok(payload(r));
        }
        case action::GOV_OPEN_RECRUITMENT_TOGGLE_TX: {
            const auto r = sys::openRecruitmentToggleTx(state);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok(payload(r));
        }
        case action::GOV_SPIRIT_MINE_BOOST_TOGGLE_TX: {
            const auto r = sys::spiritMineBoostToggleTx(state);
            if (!r.ok) return fail(r.errorType, r.message);
            return ok(payload(r));
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "policy tx action " + std::to_string(actionId));
    }
}

/// 弟子生命周期 UI 操作事务（ActionIds.DISCIPLE_LIFECYCLE_EXPEL /
/// APPRENTICE / MARRY_APPROVE / RELEASE_REFLECTION / SALARY_TOGGLE
/// ——batch-14 生命周期族写者下沉；全族零 RNG 纯确定性事务，校验链
/// 先行失败零写入，失败信封 → Kotlin 回退原路径重执行校验链。
/// 逐出信封附 bagItems 草稿（Kotlin 物化回仓库+溢出转邮件）；
/// 拜师信封附双侧 lifeEvents 日志草稿（Kotlin 瞬态列回写）；
/// 婚姻批准 paired=false = 防御检查命中（Kotlin 仅移除提议不记事件））
nlohmann::json handleDiscipleLifecycleTx(GameCore* core, int32_t actionId,
                                         const nlohmann::json& params) {
    namespace lifecycle_tx = gamecore::system::disciple_lifecycle_tx;
    auto& state = core->state();
    switch (actionId) {
        case action::DISCIPLE_LIFECYCLE_EXPEL: {
            const auto r = lifecycle_tx::expelTransaction(
                state, params.at("discipleId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            nlohmann::json bagItems = nlohmann::json::array();
            for (const auto& item : r.bagItems) {
                nlohmann::json j = item;
                bagItems.push_back(std::move(j));
            }
            return ok({{"expelled", true}, {"bagItems", std::move(bagItems)}});
        }
        case action::DISCIPLE_LIFECYCLE_APPRENTICE: {
            const auto r = lifecycle_tx::apprenticeTransaction(
                state, params.at("discipleId").get<std::string>(),
                params.at("masterId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"apprenticed", true},
                       {"apprenticeLogLine", r.apprenticeLogLine},
                       {"masterLogLine", r.masterLogLine}});
        }
        case action::DISCIPLE_LIFECYCLE_MARRY_APPROVE: {
            const auto r = lifecycle_tx::approveMarriageTransaction(
                state, params.at("maleId").get<std::string>(),
                params.at("femaleId").get<std::string>(),
                params.value("maleName", ""),
                params.value("femaleName", ""));
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"paired", r.paired}});
        }
        case action::DISCIPLE_LIFECYCLE_RELEASE_REFLECTION: {
            const auto r = lifecycle_tx::releaseReflectionTransaction(
                state, params.at("discipleId").get<std::string>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"released", true}, {"written", r.written}});
        }
        case action::DISCIPLE_LIFECYCLE_SALARY_TOGGLE: {
            const auto r = lifecycle_tx::salaryToggleTransaction(
                state, params.at("realm").get<int32_t>(),
                params.at("enabled").get<bool>());
            if (!r.ok) return fail(r.errorType, r.message);
            return ok({{"toggled", true}});
        }
        default:
            return fail("UNKNOWN_ACTION",
                        "disciple lifecycle tx action " + std::to_string(actionId));
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
                   actionId <= action::INV_TOGGLE_LOCK) {
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
        } else if (actionId >= action::SECRET_REALM_START &&
                   actionId <= action::SECRET_REALM_END) {
            result = handleSecretRealmSession(this, actionId, params);
        } else if (actionId == action::SECRET_REALM_CONTINUE_TX) {
            result = handleSecretRealmPlatformTx(this, actionId, params);
        } else if (actionId == action::PRODUCTION_START ||
                   actionId == action::PRODUCTION_RESET) {
            result = handleProductionScheduling(this, actionId, params);
        } else if (actionId >= action::PROD_UI_ASSIGN_SLOT &&
                   actionId <= action::PROD_UI_ADD_SLOT) {
            result = handleProductionUiTx(this, actionId, params);
        } else if (actionId >= action::SPIRIT_FIELD_PLANT_ONE &&
                   actionId <= action::SPIRIT_FIELD_REMOVE_BATCH) {
            result = handleSpiritFieldPlantTx(this, actionId, params);
        } else if (actionId == action::ROAD_PLACE ||
                   actionId == action::ROAD_REMOVE) {
            result = handleRoadTx(this, actionId, params);
        } else if (actionId >= action::DISCIPLE_TX_EQUIP &&
                   actionId <= action::DISCIPLE_TX_UNASSIGN_SLOT) {
            result = handleDiscipleTx(this, actionId, params);
        } else if (actionId >= action::DIPLOMACY_TX &&
                   actionId <= action::VASSAL_TX) {
            result = handleDiplomacyTx(this, actionId, params);
        } else if (actionId >= action::INV_SELL_ITEM &&
                   actionId <= action::INV_CONFISCATE_BAG_ITEM) {
            result = handleInventoryTx(this, actionId, params);
        } else if (actionId == action::STORAGE_BAG_OPEN_TX) {
            // 开袋抽签（ADR 阶段 1①）复用 handleInventoryTx；**与上面两段分开判**
            // 的原因：1734 与 1520–1531 之间有其他域的动作号（1730–1733 等），
            // 写成一个连续区间会把它们吞进库存 handler（实测事故：
            // LockBeastTx 1730 收到 "inventory tx action 1730" UNKNOWN_ACTION）
            result = handleInventoryTx(this, actionId, params);
        } else if (actionId >= action::PATROL_ASSIGN_RESIDENCE &&
                   actionId <= action::PATROL_UPDATE_YEARLY_SALARY) {
            result = handlePatrolTx(this, actionId, params);
        } else if (actionId >= action::SECT_ATTACK_REMOVE_DEAD_DEFENDERS_TX &&
                   actionId <= action::SECT_ATTACK_GRANT_SOUL_POWERS_TX) {
            result = handleSectAttackTx(this, actionId, params);
        } else if (actionId >= action::BEAST_VIEW_LOCK_TX &&
                   actionId <= action::SETTINGS_PATCH_TX) {
            result = handleLockBeastTx(this, actionId, params);
        } else if (actionId >= action::DISCIPLE_LIFECYCLE_EXPEL &&
                   actionId <= action::DISCIPLE_LIFECYCLE_SALARY_TOGGLE) {
            result = handleDiscipleLifecycleTx(this, actionId, params);
        } else if (actionId >= action::EXPLORE_TX_ATTACK_WORLD_LEVEL &&
                   actionId <= action::EXPLORE_TX_REMOVE_GARRISON) {
            result = handleExplorationTx(this, actionId, params);
        } else if (actionId >= action::ELDER_APPOINT_TX &&
                   actionId <= action::TRAIT_WASH_SLOT_TX) {
            result = handleAppointmentTx(this, actionId, params);
        } else if (actionId >= action::SPIRIT_ROOT_WASH_CONFIRM_TX &&
                   actionId <= action::TRAIT_WASH_CONFIRM_TX) {
            result = handleAppointmentTx(this, actionId, params);
        } else if (actionId >= action::RECRUIT_REMOVE_TX &&
                   actionId <= action::RECRUIT_AGE_TX) {
            result = handleRecruitTx(this, actionId, params);
        } else if (actionId >= action::BOUNDARY_GUIDE_COUNTER_INCREMENT_TX &&
                   actionId <= action::BOUNDARY_BUILDING_GUIDE_BACKFILL_TX) {
            result = handleBoundaryTx(this, actionId, params);
        } else if (actionId >= action::GOV_POLICY_TOGGLE_TX &&
                   actionId <= action::GOV_SPIRIT_MINE_BOOST_TOGGLE_TX) {
            result = handlePolicyTx(this, actionId, params);
        } else if (actionId >= action::SECT_LEVEL_UPGRADE_TX &&
                   actionId <= action::JADE_PURCHASE_BREAKTHROUGH_BONUS_TX) {
            result = handleJadeTx(this, actionId, params);
        } else if (actionId >= action::BUILDING_PLACE &&
                   actionId <= action::BUILDING_UPGRADE_BATCH) {
            result = handleBuildingTx(this, actionId, params);
        } else if (actionId >= action::SECT_DECISION_CHANCE &&
                   actionId <= action::SECT_TRADE_SPIRIT_STONE) {
            result = handleSectDiplomacy(this, actionId, params);
        } else if (actionId == action::SLOT_CLEAR_ALL) {
            result = handleSlotCleanup(this, params);
        } else if (actionId >= action::REDEEM_VALIDATE_INPUT &&
                   actionId <= action::MAIL_ATTACHMENT_ENCODE) {
            result = handleRedeemCode(this, actionId, params);
        // ── W4 三批次并行分派区（W4-00 并行前置批建立）─────────────────────
        // 三个并行批次的 handler 各自实现在独立源文件 src/dispatch_w4{a,b,c}.cpp，
        // 因此**本文件此后冻结**——三批的 diff 中若出现本文件即为越界（判据见
        // docs/parallel-batches-w4/README.md §5.3）。
        // 认领语义：端口返回 nullopt ⇒ 不认领，继续往后续端口 / NOT_IMPLEMENTED 兜底。
        } else if (auto w4a = dispatchW4A(*this, actionId, params);
                   w4a.has_value()) {
            result = std::move(*w4a);
        } else if (auto w4b = dispatchW4B(*this, actionId, params);
                   w4b.has_value()) {
            result = std::move(*w4b);
        } else if (auto w4c = dispatchW4C(*this, actionId, params);
                   w4c.has_value()) {
            result = std::move(*w4c);
        } else if (auto w4d = dispatchW4D(*this, actionId, params);
                   w4d.has_value()) {
            // W4-D 汇流波（串行收口）端口——三批合入后由 W4-D 追加的第四端口，
            // 同一模式的一次性延续（src/dispatch_w4d.cpp）。
            result = std::move(*w4d);
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

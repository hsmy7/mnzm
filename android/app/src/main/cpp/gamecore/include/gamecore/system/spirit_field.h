#pragma once

#include <algorithm>
#include <cstdint>
#include <string>

#include "gamecore/data/herb_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"

// ============================================================
// 灵田收获系统
//
// 等价移植 Kotlin ProductionProcessor.processSpiritFieldHarvest +
// addHarvestedHerb + addHarvestedSeed 的**纯逻辑**部分：
//
//   - 成熟判定：elapsedMonths = (year-plantYear)*12 + (month-plantMonth)，
//     >= effectiveGrowTime 即成熟（光环加成尚未接入，当前 growTime 原值）
//   - 收获灵草：seedName → HerbDatabase 反查 → 合并入草药仓库（溢出转邮件）
//   - 收获种子：SYSTEM 分区 RNG nextInt(5)（0..4），>0 时按种子模板入种子仓库
//   - 续种：收获后若仓库有同种种子则自动补种（updateSlotAfterHarvest 语义）
//   - 年度报告：annualHerbBySource["spirit_field"]、annualHerbCount、
//     guideCounters["herbsHarvested"]
//
// 与 Kotlin 语义对齐要点：
//   - RNG 走 SYSTEM 分区（rng.getRng(RngPartition::kSystem)）
//   - 跨宗门地块隔离：plant.sectId 非空且 != activeSectId 不收获
//   - expectedYield.coerceAtLeast(1)
//   - 种子奖励 roll 0..4 各 20%（nextInt(5)）
// ============================================================
namespace gamecore::system {

/// 灵田收获结果（统计）
struct SpiritFieldHarvestResult {
    int32_t herbsHarvested = 0;   // 实际入库灵草数
    int32_t seedsHarvested = 0;   // 获得种子数（roll 值，含溢出转邮件部分）
    int32_t plantsCompleted = 0;  // 完成收获的地块数
};

/// 灵田收获（Kotlin processSpiritFieldHarvest 等价）
///
/// @param state 事务内状态（读 gameData.spiritFieldPlants / herbs / seeds）
/// @param rng   SYSTEM 分区 RNG（种子 roll）
/// @param overflowMail 溢出邮件收集器
/// @return 收获统计
inline SpiritFieldHarvestResult processSpiritFieldHarvest(
    state::GameState& state, rng::RngManager& rng,
    OverflowMailCollector& overflowMail) {
    SpiritFieldHarvestResult out;
    auto& gd = state.gameData;
    const int32_t currentYear = gd.gameYear;
    const int32_t currentMonth = gd.gameMonth;
    auto& plants = gd.spiritFieldPlants;
    if (plants.empty()) return out;

    // 整轮共享合并仓库（构建一次，等价 buildHarvestStores）
    auto herbStore = StackableItemStore<state::Herb>(
        state.herbs, herbKey, getMaxStackSize("herb"),
        [&]() { return computeMaxSlots(state); });
    auto seedStore = StackableItemStore<state::Seed>(
        state.seeds, seedKey, getMaxStackSize("seed"),
        [&]() { return computeMaxSlots(state); });

    std::vector<state::SpiritFieldPlant> newPlants;
    newPlants.reserve(plants.size());
    bool hasChanges = false;

    for (const auto& plant : plants) {
        // 无效地块跳过
        if (plant.seedId.empty() || plant.growTime <= 0) {
            newPlants.push_back(plant);
            continue;
        }
        // 跨宗门地块隔离
        if (!plant.sectId.empty() && plant.sectId != gd.activeSectId) {
            newPlants.push_back(plant);
            continue;
        }
        const int32_t elapsedMonths =
            ((currentYear - plant.plantYear) * 12 + (currentMonth - plant.plantMonth));
        const int32_t effectiveGrowTime = plant.growTime;  // 光环加成尚未接入

        if (elapsedMonths < effectiveGrowTime) {
            newPlants.push_back(plant);
            continue;
        }

        // 收获灵草：seedName → 种子模板 → herbId → 灵草模板（对齐 Kotlin
        // HerbDatabase.getHerbFromSeedName：seedByNameMap → seedToHerbMap → herbByIdMap）
        const auto& seedTpls = gamecore::data::seedTemplates();
        const gamecore::data::SeedTemplate* seedTpl = nullptr;
        for (const auto& s : seedTpls) {
            if (s.name == plant.seedName) { seedTpl = &s; break; }
        }
        const gamecore::data::HerbTemplate* dbHerb = nullptr;
        if (seedTpl != nullptr) {
            const std::string herbId = gamecore::data::herbIdFromSeedId(seedTpl->id);
            const auto& herbs = gamecore::data::herbTemplates();
            for (const auto& h : herbs) {
                if (h.id == herbId) { dbHerb = &h; break; }
            }
        }
        if (dbHerb == nullptr) {
            newPlants.push_back(plant);  // 未找到定义，跳过收获（保留地块）
            continue;
        }

        // 灵草入库
        const int32_t finalYield = std::max(plant.expectedYield, 1);
        state::Herb newHerb;
        newHerb.id = "gc-herb-" + plant.seedName + "-" + std::to_string(currentYear) + "-" +
                     std::to_string(currentMonth);
        newHerb.name = dbHerb->name;
        newHerb.rarity = dbHerb->rarity;
        newHerb.category = dbHerb->category;
        newHerb.quantity = finalYield;
        const auto herbResult = herbStore.add(newHerb);
        int32_t actualAdded = 0;
        if (herbResult.status == InventoryStatus::kSuccess) {
            actualAdded = finalYield;
        } else if (herbResult.status == InventoryStatus::kPartial) {
            actualAdded = finalYield - herbResult.overflow;
            OverflowDraft draft;
            draft.source = "spirit_field";
            draft.itemType = "herb";
            draft.itemName = dbHerb->name;
            draft.itemId = dbHerb->id;
            draft.rarity = dbHerb->rarity;
            draft.quantity = herbResult.overflow;
            overflowMail.add(std::move(draft));
        } else {
            OverflowDraft draft;
            draft.source = "spirit_field";
            draft.itemType = "herb";
            draft.itemName = dbHerb->name;
            draft.itemId = dbHerb->id;
            draft.rarity = dbHerb->rarity;
            draft.quantity = finalYield;
            overflowMail.add(std::move(draft));
        }
        if (actualAdded < finalYield) {
            // 空间不足日志语义保留（不落状态）
        }
        gd.annualHerbBySource["spirit_field"] =
            wrapAdd(gd.annualHerbBySource["spirit_field"], actualAdded);
        out.herbsHarvested += actualAdded;

        // 种子奖励（SYSTEM 分区 RNG nextInt(5) = 0..4）
        const int32_t roll = rng.getRng(rng::RngPartition::kSystem).nextInt(5);
        if (roll > 0) {
            const auto& seeds = gamecore::data::seedTemplates();
            const gamecore::data::SeedTemplate* seedTemplate = nullptr;
            for (const auto& s : seeds) {
                if (s.name == plant.seedName) { seedTemplate = &s; break; }
            }
            if (seedTemplate != nullptr) {
                state::Seed newSeed;
                newSeed.id = "gc-seed-" + plant.seedName + "-" + std::to_string(currentYear) +
                             "-" + std::to_string(currentMonth);
                newSeed.name = seedTemplate->name;
                newSeed.rarity = seedTemplate->rarity;
                newSeed.growTime = seedTemplate->growTime;
                newSeed.yield = seedTemplate->yield;
                newSeed.quantity = roll;
                const auto seedResult = seedStore.add(newSeed);
                if (seedResult.status == InventoryStatus::kPartial) {
                    OverflowDraft draft;
                    draft.source = "spirit_field";
                    draft.itemType = "seed";
                    draft.itemName = seedTemplate->name;
                    draft.itemId = seedTemplate->id;
                    draft.rarity = seedTemplate->rarity;
                    draft.quantity = seedResult.overflow;
                    overflowMail.add(std::move(draft));
                } else if (seedResult.status == InventoryStatus::kFailure) {
                    OverflowDraft draft;
                    draft.source = "spirit_field";
                    draft.itemType = "seed";
                    draft.itemName = seedTemplate->name;
                    draft.itemId = seedTemplate->id;
                    draft.rarity = seedTemplate->rarity;
                    draft.quantity = roll;
                    overflowMail.add(std::move(draft));
                }
                out.seedsHarvested += roll;
            }
        }

        // 引导/年度计数
        gd.annualHerbCount++;
        const auto cit = gd.guideCounters.find("herbsHarvested");
        const int64_t cur = (cit != gd.guideCounters.end()) ? cit->second : 0;
        gd.guideCounters["herbsHarvested"] = cur + 1;

        // 续种（updateSlotAfterHarvest 等价：消耗仓库内未锁定同种种子 1 颗；
        // 无种子则清空地——seedId/growTime 置空防 UI 显示存量 0 悬空）
        state::SpiritFieldPlant newPlant = plant;
        const gamecore::data::SeedTemplate* seedTemplatePtr = nullptr;
        {
            const auto& seeds = gamecore::data::seedTemplates();
            for (const auto& s : seeds) {
                if (s.name == plant.seedName) { seedTemplatePtr = &s; break; }
            }
        }
        // 查找未锁定的同种种子（seedStore 内，含本轮刚收获的种子）
        int32_t existingSeedIdx = -1;
        for (size_t i = 0; i < seedStore.all().size(); ++i) {
            const auto& s = seedStore.all()[i];
            if (s.name == plant.seedName && s.rarity == (seedTemplatePtr ? seedTemplatePtr->rarity : 1) &&
                s.growTime == plant.growTime && s.quantity > 0 && !s.isLocked) {
                existingSeedIdx = static_cast<int32_t>(i);
                break;
            }
        }
        if (existingSeedIdx >= 0) {
            const auto& existingSeed = seedStore.all()[static_cast<size_t>(existingSeedIdx)];
            seedStore.remove(existingSeed.id, 1);
            newPlant.seedId = existingSeed.id;
            newPlant.plantYear = currentYear;
            newPlant.plantMonth = currentMonth;
            newPlant.completionMonth = currentYear * 12 + currentMonth +
                                       std::max(plant.growTime, 1);
            newPlant.completionPhase = 3;
        } else {
            newPlant.seedId.clear();
            newPlant.seedName.clear();
            newPlant.growTime = 0;
            newPlant.expectedYield = 0;
            newPlant.plantYear = 0;
            newPlant.plantMonth = 0;
            newPlant.completionMonth = 0;
            newPlant.completionPhase = 1;
        }
        newPlants.push_back(newPlant);
        out.plantsCompleted++;
        hasChanges = true;
    }

    if (hasChanges) {
        state.herbs = herbStore.all();
        state.seeds = seedStore.all();
        gd.spiritFieldPlants = newPlants;
    }
    return out;
}

// ============================================================
// batch-17：灵田种植族 UI 操作事务（零 RNG 纯数据变换）
//
// 等价移植 Kotlin BuildingFacadeImpl 的灵田写者：
//   - plantOnSpiritField（单块播种；事务内读种子锁定态/余量并同事务扣种）
//   - plantOnSpiritFields（批量播种；余量约束上限，按地块镜像序播种）
//   - removePlantFromSpiritField（单块移除：首命中实例清空种植字段）
//   - removePlantsFromSpiritFields（批量移除：实例集合清空种植字段）
//
// 口径（与 Kotlin 逐位一致）：
//   - 可播种判定：seedId 为空 ∧（sectId 为空[旧数据兼容] ∨ sectId == 传入 sectId）
//   - 单块播种先按 buildingInstanceId 命中，再判可播种（缺一即零写入）
//   - 播种写：seedId/seedName/growTime/expectedYield/plant{Year,Month}/sectId/
//     completionMonth = (year*12+month) + max(growTime,1)、completionPhase = 3
//   - 扣种：单块扣 1；批量扣实际播种数；余量归 0 移除条目（Kotlin add 语义）
//   - 移除写：seedId/seedName 清空、growTime/expectedYield/plant{Year,Month}/
//     completionMonth 归零、completionPhase = 1（buildingInstanceId/sectId 保留）
//   - 零 RNG：种植/移除均不触达 RngManager（GTest 全分区快照差分断言）
//   - 失败零写入：种子条目缺失/锁定/余量为 0 → failure 信封，不触碰任何状态
//   - 零 checkpoint：种植/移除不改变速率因子，不触达 checkpointAllProduction()
//     （CLAUDE.md 13.3 红线核对结论见 handover §2.48）
// ============================================================
namespace spirit_field_tx {

/// 灵田事务结果
struct SpiritFieldOutcome {
    bool ok = false;
    std::string errorType;  // SeedNotFound / SeedLocked / SeedEmpty
    std::string message;
    int32_t planted = 0;    // 实际播种地块数
    int32_t removed = 0;    // 实际移除地块数
};

/// 目标地块是否可播种（Kotlin SpiritFieldPlant.isPlantable）
inline bool isPlantable(const state::SpiritFieldPlant& plant, const std::string& sectId) {
    if (!plant.seedId.empty()) return false;
    return plant.sectId.empty() || plant.sectId == sectId;
}

/// 种子条目内联定位（Kotlin seeds.get(seedId)——含锁定态与实时余量）
inline state::Seed* findSeed(state::GameState& state, const std::string& seedId) {
    for (auto& s : state.seeds) {
        if (s.id == seedId) return &s;
    }
    return nullptr;
}

/// 种子入参校验（Kotlin 前置 getSeedById 非空/余量>0 + 事务内锁定态/余量复检）
inline bool validateSeed(SpiritFieldOutcome& out, const state::Seed* seed) {
    if (seed == nullptr) {
        out.errorType = "SeedNotFound";
        out.message = "种子不存在";
        return false;
    }
    if (seed->isLocked) {
        out.errorType = "SeedLocked";
        out.message = "种子已锁定";
        return false;
    }
    if (seed->quantity <= 0) {
        out.errorType = "SeedEmpty";
        out.message = "种子余量不足";
        return false;
    }
    return true;
}

/// 同事务扣种（单块 n=1；批量 n=planted）——余量归 0 移除条目
inline void consumeSeeds(state::GameState& state, const std::string& seedId, int32_t n) {
    for (auto it = state.seeds.begin(); it != state.seeds.end(); ++it) {
        if (it->id != seedId) continue;
        it->quantity -= n;
        if (it->quantity <= 0) state.seeds.erase(it);
        return;
    }
}

/// 地块播种字段写（Kotlin copy 同式）
inline void writePlant(state::SpiritFieldPlant& plant, const state::Seed& seed,
                       const std::string& sectId, int32_t year, int32_t month,
                       int32_t absoluteMonth) {
    plant.seedId = seed.id;
    plant.seedName = seed.name;
    plant.growTime = seed.growTime;
    plant.expectedYield = seed.yield;
    plant.plantYear = year;
    plant.plantMonth = month;
    plant.sectId = sectId;
    plant.completionMonth = absoluteMonth + (seed.growTime > 1 ? seed.growTime : 1);
    plant.completionPhase = 3;  // 种植下旬
}

/// 地块清空（Kotlin removePlant* 同式——保留 buildingInstanceId/sectId）
inline void clearPlant(state::SpiritFieldPlant& plant) {
    plant.seedId.clear();
    plant.seedName.clear();
    plant.growTime = 0;
    plant.expectedYield = 0;
    plant.plantYear = 0;
    plant.plantMonth = 0;
    plant.completionMonth = 0;
    plant.completionPhase = 1;
}

/// 单块播种事务（Kotlin plantOnSpiritField 等价）。
/// 判定序：种子条目存在/未锁定/余量>0 → 首命中实例可播种 → 写种植字段 + 扣 1。
inline SpiritFieldOutcome plantOnSpiritFieldTx(state::GameState& state,
                                               const std::string& buildingInstanceId,
                                               const std::string& seedId,
                                               const std::string& sectId) {
    SpiritFieldOutcome out;
    state::Seed* seed = findSeed(state, seedId);
    if (!validateSeed(out, seed)) return out;
    auto& plants = state.gameData.spiritFieldPlants;
    for (auto& plant : plants) {
        // indexOfFirst { buildingInstanceId == 传入 ∧ isPlantable } 等价：
        // 同实例条目不可播种时继续扫描（正常数据下实例唯一，结果一致）
        if (plant.buildingInstanceId != buildingInstanceId) continue;
        if (!isPlantable(plant, sectId)) continue;
        const int32_t year = state.gameData.gameYear;
        const int32_t month = state.gameData.gameMonth;
        writePlant(plant, *seed, sectId, year, month, year * 12 + month);
        consumeSeeds(state, seedId, 1);
        out.planted = 1;
        out.ok = true;
        return out;
    }
    out.errorType = "NoPlantableField";
    out.message = "无可用地块";
    return out;
}

/// 批量播种事务（Kotlin plantOnSpiritFields 等价）。
/// 上限 = min(种子余量, 目标地块数)；按地块镜像序遍历，批内材料竞争由
/// 逐块扣减表现（实际扣减量 = 实际播种数，未命中地块不扣）。
inline SpiritFieldOutcome plantOnSpiritFieldsTx(state::GameState& state,
                                                const std::vector<std::string>& instanceIds,
                                                const std::string& seedId,
                                                const std::string& sectId) {
    SpiritFieldOutcome out;
    if (instanceIds.empty()) {
        out.ok = true;
        return out;
    }
    state::Seed* seed = findSeed(state, seedId);
    if (!validateSeed(out, seed)) return out;
    const int32_t available = seed->quantity;
    int32_t maxToPlant = available;
    if (static_cast<int32_t>(instanceIds.size()) < maxToPlant) {
        maxToPlant = static_cast<int32_t>(instanceIds.size());
    }
    const int32_t year = state.gameData.gameYear;
    const int32_t month = state.gameData.gameMonth;
    const int32_t absoluteMonth = year * 12 + month;
    for (auto& plant : state.gameData.spiritFieldPlants) {
        if (out.planted >= maxToPlant) break;
        bool targeted = false;
        for (const auto& id : instanceIds) {
            if (id == plant.buildingInstanceId) {
                targeted = true;
                break;
            }
        }
        if (!targeted || !isPlantable(plant, sectId)) continue;
        writePlant(plant, *seed, sectId, year, month, absoluteMonth);
        out.planted++;
    }
    if (out.planted <= 0) {
        out.errorType = "NoPlantableField";
        out.message = "无可用地块";
        return out;
    }
    consumeSeeds(state, seedId, out.planted);
    out.ok = true;
    return out;
}

/// 单块移除事务（Kotlin removePlantFromSpiritField 等价——首命中实例清空）。
/// 未命中也视为成功（Kotlin 为静默空操作，无失败语义）。
inline SpiritFieldOutcome removePlantFromSpiritFieldTx(state::GameState& state,
                                                       const std::string& buildingInstanceId) {
    SpiritFieldOutcome out;
    for (auto& plant : state.gameData.spiritFieldPlants) {
        if (plant.buildingInstanceId == buildingInstanceId) {
            clearPlant(plant);
            out.removed = 1;
            break;
        }
    }
    out.ok = true;
    return out;
}

/// 批量移除事务（Kotlin removePlantsFromSpiritFields 等价——实例集合清空）。
inline SpiritFieldOutcome removePlantsFromSpiritFieldsTx(
    state::GameState& state, const std::vector<std::string>& instanceIds) {
    SpiritFieldOutcome out;
    if (instanceIds.empty()) {
        out.ok = true;
        return out;
    }
    for (auto& plant : state.gameData.spiritFieldPlants) {
        bool targeted = false;
        for (const auto& id : instanceIds) {
            if (id == plant.buildingInstanceId) {
                targeted = true;
                break;
            }
        }
        if (!targeted) continue;
        clearPlant(plant);
        out.removed++;
    }
    out.ok = true;
    return out;
}

}  // namespace spirit_field_tx

using spirit_field_tx::SpiritFieldOutcome;
using spirit_field_tx::plantOnSpiritFieldTx;
using spirit_field_tx::plantOnSpiritFieldsTx;
using spirit_field_tx::removePlantFromSpiritFieldTx;
using spirit_field_tx::removePlantsFromSpiritFieldsTx;

}  // namespace gamecore::system

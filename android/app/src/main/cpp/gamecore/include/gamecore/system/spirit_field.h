#pragma once

#include <algorithm>
#include <cstdint>
#include <string>

#include "gamecore/data/herb_db.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"

// ============================================================
// 灵田收获系统（Kotlin→C++ 迁移批次 4c）
//
// 等价移植 Kotlin ProductionProcessor.processSpiritFieldHarvest +
// addHarvestedHerb + addHarvestedSeed 的**纯逻辑**部分：
//
//   - 成熟判定：elapsedMonths = (year-plantYear)*12 + (month-plantMonth)，
//     >= effectiveGrowTime 即成熟（光环加成批次 7 接入，当前 growTime 原值）
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
        // 跨宗门地块隔离（对抗性审查 F3）
        if (!plant.sectId.empty() && plant.sectId != gd.activeSectId) {
            newPlants.push_back(plant);
            continue;
        }
        const int32_t elapsedMonths =
            ((currentYear - plant.plantYear) * 12 + (currentMonth - plant.plantMonth));
        const int32_t effectiveGrowTime = plant.growTime;  // 光环加成批次 7 接入

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

}  // namespace gamecore::system

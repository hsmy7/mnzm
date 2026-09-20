#pragma once

#include <nlohmann/json.hpp>

#include <map>

#include "gamecore/data/beast_config.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/data_json.h"
#include "gamecore/data/data_store.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"
#include "gamecore/data/trait_db.h"

// ============================================================
// 数据文件 → C++ DB 容器 注入映射（B16 / R6.2）
//
// 职责单一：把 `assets/data/game-data.json` 的 `db.*` 各段**逐字段**写进
// `data/*.h` 的 mutable 容器；任何一段缺失/类型不符即返回 false ⇒
// `data_store.loadFromJson` 落 `kFallbackDefault`（保留内联默认兜底）。
//
// 为什么用 nlohmann/json：仓库已 vendored（third_party/nlohmann/json.hpp）
// 且被 models.h / json_codec.h 等广泛消费，零新依赖。
//
// 为什么 dump 整段而不是逐条目解析：数据段是"单一值源"，整段
// `get<vector<Struct>>()` 由 nlohmann 完成逐字段类型校验，比手写
// 逐字段读取更短且更难写错；同时 `desc.find(...)` 缺失检查保证"显式失败"。
//
// 注意：本头文件**只被注入路径包含**（GameCoreBridge.cpp / 桌面注入工具 /
// 守卫测试）。它 include 了具体 DB 头，会让编译单元变重——这正是把它与
// `data_store.h`（零 DB 依赖）分离的原因。
// ============================================================
namespace gamecore::data::inject {

/// 各段条目数的权威口径（注入后由守卫断言"数据文件段 ↔ 容器 size()"一致）
struct AppliedCounts {
    int32_t equipment = 0;
    int32_t herbs = 0;
    int32_t seeds = 0;
    int32_t manuals = 0;
    int32_t beastMaterials = 0;
    int32_t forgeRecipes = 0;
    int32_t pillRecipes = 0;
    int32_t talents = 0;
    int32_t physiques = 0;
    int32_t affixes = 0;
};

/// 逐段应用；返回 false 表示注入失败（调用方落兜底，容器保持默认）
inline bool applyGameData(const nlohmann::json& doc) {
    if (!doc.contains("db") || !doc["db"].is_object()) return false;
    const auto& db = doc["db"];

    // ── 装备 ────────────────────────────────────────────────
    // 段缺失 = 该表不注入（保持内联默认）——允许**部分段**数据文件，
    // 因为 beast_config 等结构性表本就不进数据文件。
    if (db.contains("equipment")) {
        if (!db["equipment"].is_array() || db["equipment"].empty()) return false;
        auto rows = db["equipment"].get<std::vector<EquipmentTemplate>>();
        equipmentTemplatesMutable() = std::move(rows);
    }

    // ── 灵草 / 种子 ─────────────────────────────────────────
    if (db.contains("herbs")) {
        if (!db["herbs"].is_array() || db["herbs"].empty()) return false;
        auto rows = db["herbs"].get<std::vector<HerbTemplate>>();
        herbTemplatesMutable() = std::move(rows);
    }
    if (db.contains("seeds")) {
        if (!db["seeds"].is_array() || db["seeds"].empty()) return false;
        auto rows = db["seeds"].get<std::vector<SeedTemplate>>();
        seedTemplatesMutable() = std::move(rows);
    }

    // ── 功法 ────────────────────────────────────────────────
    if (db.contains("manuals")) {
        if (!db["manuals"].is_array() || db["manuals"].empty()) return false;
        auto rows = db["manuals"].get<std::vector<ManualTemplate>>();
        manualTemplatesMutable() = std::move(rows);
    }

    // ── 妖兽材料 ────────────────────────────────────────────
    if (db.contains("beastMaterials")) {
        if (!db["beastMaterials"].is_array() || db["beastMaterials"].empty()) return false;
        auto rows = db["beastMaterials"].get<std::vector<BeastMaterialTemplate>>();
        beastMaterialTemplatesMutable() = std::move(rows);
    }

    // ── 锻造配方 ────────────────────────────────────────────
    if (db.contains("forgeRecipes")) {
        if (!db["forgeRecipes"].is_array() || db["forgeRecipes"].empty()) return false;
        auto rows = db["forgeRecipes"].get<std::vector<ForgeRecipeTemplate>>();
        forgeRecipesMutable() = std::move(rows);
    }

    // ── 炼丹配方 ────────────────────────────────────────────
    // `price` 是派生字段（tierPrice × gradeMultiplier × 双属性 1.2，公式在
    // recipe_db.h recipeFromTemplate；数据文件不含该键）⇒ 注入后按 C++ 同一
    // 构建器（detail::buildPillRecipes）按 id 回填，保证与内联兜底逐位一致。
    // 回填行缺失（数据行 id 不在派生源中）= 段与 C++ 面漂移 ⇒ 显式失败落兜底。
    if (db.contains("pillRecipes")) {
        if (!db["pillRecipes"].is_array() || db["pillRecipes"].empty()) return false;
        auto rows = db["pillRecipes"].get<std::vector<PillRecipeTemplate>>();
        std::map<std::string, int32_t> derivedPrice;
        for (const auto& r : detail::buildPillRecipes()) {
            derivedPrice.emplace(r.id, r.price);
        }
        for (auto& r : rows) {
            const auto it = derivedPrice.find(r.id);
            if (it == derivedPrice.end()) return false;
            r.price = it->second;
        }
        pillRecipesMutable() = std::move(rows);
    }

    // ── 天赋 / 体质 / 词条 ──────────────────────────────────
    // 依赖顺序：中性源为单文件聚合 JSON（trait 段的展开依赖已由 C++ build*
    // 兜底承担，注入为整表替换，无跨段读取）⇒ 顺序仅按文件段落排布。
    if (db.contains("talents")) {
        if (!db["talents"].is_array() || db["talents"].empty()) return false;
        auto rows = db["talents"].get<std::vector<TalentTemplate>>();
        talentTemplatesMutable() = std::move(rows);
    }
    if (db.contains("physiques")) {
        if (!db["physiques"].is_array() || db["physiques"].empty()) return false;
        auto rows = db["physiques"].get<std::vector<PhysiqueTemplate>>();
        physiqueTemplatesMutable() = std::move(rows);
    }
    if (db.contains("affixes")) {
        if (!db["affixes"].is_array() || db["affixes"].empty()) return false;
        auto rows = db["affixes"].get<std::vector<AffixTemplate>>();
        affixTemplatesMutable() = std::move(rows);
    }

    return true;
}

/// 注入并返回各表条目数（守卫测试用；生产走 loadFromJson）
inline AppliedCounts applyAndCount(const nlohmann::json& doc) {
    AppliedCounts c{};
    if (!applyGameData(doc)) return c;
    c.equipment = static_cast<int32_t>(equipmentTemplates().size());
    c.herbs = static_cast<int32_t>(herbTemplates().size());
    c.seeds = static_cast<int32_t>(seedTemplates().size());
    c.manuals = static_cast<int32_t>(manualTemplates().size());
    c.beastMaterials = static_cast<int32_t>(beastMaterialTemplates().size());
    c.forgeRecipes = static_cast<int32_t>(forgeRecipes().size());
    c.pillRecipes = static_cast<int32_t>(pillRecipes().size());
    c.talents = static_cast<int32_t>(talentTemplates().size());
    c.physiques = static_cast<int32_t>(physiqueTemplates().size());
    c.affixes = static_cast<int32_t>(affixTemplates().size());
    return c;
}

/// 生产注入入口：`json` = 数据文件全文。
/// 内部把 `apply` 回调接到 `applyGameData`，供 `data_store.loadFromJson` 调用。
inline bool injectFromJson(const std::string& json) {
    return loadFromJson(json, [](const void* raw) {
        return applyGameData(*static_cast<const nlohmann::json*>(raw));
    });
}

}  // namespace gamecore::data::inject

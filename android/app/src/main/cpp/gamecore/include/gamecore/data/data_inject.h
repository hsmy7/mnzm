#pragma once

#include <nlohmann/json.hpp>

#include <map>

#include "gamecore/data/beast_config.h"
#include "gamecore/data/beast_material_db.h"
#include "gamecore/data/data_json.h"
#include "gamecore/data/data_store.h"
#include "gamecore/data/equip_affix_db.h"
#include "gamecore/data/equip_main_stat_db.h"
#include "gamecore/data/equip_set_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/gacha_pool_db.h"
#include "gamecore/data/herb_db.h"
#include "gamecore/data/manual_db.h"
#include "gamecore/data/recipe_db.h"

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
    int32_t gachaPools = 0;
    int32_t characterTemplates = 0;
};

/// 逐段应用；返回 false 表示注入失败（调用方落兜底，容器保持默认）
inline bool applyGameData(const nlohmann::json& doc) {
    if (!doc.contains("db") || !doc["db"].is_object()) return false;
    const auto& db = doc["db"];

    // ── 装备（B3 复合结构）────────────────────────────────────
    // db.equipment = { setPieces, sets, mainStatPools, mainStatBase, subAffixes }。
    // 段缺失 = 该子表不注入（保持内联默认）；段在而非法（非数组/空）= 整体失败。
    // mainStatPools 是 part 键映射（part → {stats[], coefficient}），展开为
    // MainStatPoolDef（槽内 stats 数组序 = 抽取序，原样保留；跨槽遍历序 =
    // JSON 键序，仅影响查表扫描不影响 roll 序——rollMainStat 按部位查池）。
    // mainStatBase 是 stat 键映射（stat → 品阶 1..6 基数数组），展开为 MainStatBaseRow。
    if (db.contains("equipment")) {
        const auto& eq = db["equipment"];
        if (!eq.is_object()) return false;

        if (eq.contains("setPieces")) {
            if (!eq["setPieces"].is_array() || eq["setPieces"].empty()) return false;
            auto rows = eq["setPieces"].get<std::vector<SetPieceTemplate>>();
            setPieceTemplatesMutable() = std::move(rows);
        }
        if (eq.contains("sets")) {
            if (!eq["sets"].is_array() || eq["sets"].empty()) return false;
            auto rows = eq["sets"].get<std::vector<EquipmentSetDef>>();
            equipmentSetDefsMutable() = std::move(rows);
        }
        if (eq.contains("mainStatPools")) {
            const auto& pools = eq["mainStatPools"];
            if (!pools.is_object() || pools.empty()) return false;
            std::vector<MainStatPoolDef> rows;
            rows.reserve(pools.size());
            for (auto it = pools.begin(); it != pools.end(); ++it) {
                if (!it.value().is_object()) return false;
                MainStatPoolDef row;
                row.part = it.key();
                jread(it.value(), "stats", row.stats);
                jread(it.value(), "coefficient", row.coefficient);
                if (row.stats.empty()) return false;
                rows.push_back(std::move(row));
            }
            mainStatPoolsMutable() = std::move(rows);
        }
        if (eq.contains("mainStatBase")) {
            const auto& base = eq["mainStatBase"];
            if (!base.is_object() || base.empty()) return false;
            std::vector<MainStatBaseRow> rows;
            rows.reserve(base.size());
            for (auto it = base.begin(); it != base.end(); ++it) {
                if (!it.value().is_array() || it.value().size() != 6) return false;
                MainStatBaseRow row;
                row.stat = it.key();
                for (std::size_t i = 0; i < 6; ++i) row.values[i] = it.value()[i].get<double>();
                rows.push_back(std::move(row));
            }
            mainStatBaseMutable() = std::move(rows);
        }
        if (eq.contains("subAffixes")) {
            if (!eq["subAffixes"].is_array() || eq["subAffixes"].empty()) return false;
            auto rows = eq["subAffixes"].get<std::vector<EquipAffixDef>>();
            equipAffixesMutable() = std::move(rows);
        }
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

    // ── 寻访卡池 / 角色模板（G09 抽卡数据面）─────────────────────
    // 与其它六段同形制（段缺失=跳过、段在而非数组/空=整体失败）。唯一差别：
    // 这两段**没有内联兜底**——概率表若在 C++ 再抄一份字面量就构成第二真源
    // （`gen-game-data.mjs --check` 管不住 C++ 侧）。未注入即空表，抽卡以
    // PoolNotFound 显式拒绝，不存在"按另一套数值静默出货"的中间态。
    // 口径与理由见 `gamecore/data/gacha_pool_db.h` 头注释。
    if (db.contains("gachaPools")) {
        if (!db["gachaPools"].is_array() || db["gachaPools"].empty()) return false;
        auto rows = db["gachaPools"].get<std::vector<GachaPoolTemplate>>();
        gachaPoolsMutable() = std::move(rows);
    }
    if (db.contains("characterTemplates")) {
        if (!db["characterTemplates"].is_array() || db["characterTemplates"].empty()) return false;
        auto rows = db["characterTemplates"].get<std::vector<CharacterTemplate>>();
        characterTemplatesMutable() = std::move(rows);
    }

    return true;
}

/// 注入并返回各表条目数（守卫测试用；生产走 loadFromJson）
inline AppliedCounts applyAndCount(const nlohmann::json& doc) {
    AppliedCounts c{};
    if (!applyGameData(doc)) return c;
    c.equipment = static_cast<int32_t>(setPieceTemplates().size());
    c.herbs = static_cast<int32_t>(herbTemplates().size());
    c.seeds = static_cast<int32_t>(seedTemplates().size());
    c.manuals = static_cast<int32_t>(manualTemplates().size());
    c.beastMaterials = static_cast<int32_t>(beastMaterialTemplates().size());
    c.forgeRecipes = static_cast<int32_t>(forgeRecipes().size());
    c.pillRecipes = static_cast<int32_t>(pillRecipes().size());
    c.gachaPools = static_cast<int32_t>(gachaPools().size());
    c.characterTemplates = static_cast<int32_t>(characterTemplates().size());
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

// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 sets 段同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 套装效果静态表（B3：2 套 × 2/4/6 三档；件数达档即生效、
// 可越级不叠加，穿满 6 件三档同时生效）
// ============================================================
namespace gamecore::data {

struct EquipStatValueDef {
    std::string stat;   // EquipStat.name
    double value = 0.0;
};

inline bool operator==(const EquipStatValueDef& a, const EquipStatValueDef& b) {
    return a.stat == b.stat && a.value == b.value;
}

struct EquipmentSetDef {
    std::string id;
    std::string name;
    std::string school; // PHYSICAL / MAGIC
    std::vector<EquipStatValueDef> bonus2;
    std::vector<EquipStatValueDef> bonus4;
    std::vector<EquipStatValueDef> bonus6;
};

// 幂等比较（data_inject 注入守卫逐字段比对面；四表 operator== 契约补齐）
inline bool operator==(const EquipmentSetDef& a, const EquipmentSetDef& b) {
    return a.id == b.id && a.name == b.name && a.school == b.school &&
           a.bonus2 == b.bonus2 && a.bonus4 == b.bonus4 && a.bonus6 == b.bonus6;
}

inline std::vector<EquipmentSetDef>& equipmentSetDefsMutable() {
    static std::vector<EquipmentSetDef> kSets = {
        {
            "lietian", "裂天罡煞", "PHYSICAL",
            {
                {"PHYSICAL_DAMAGE_PCT", 0.1},
            },
            {
                {"CRIT_RATE", 0.12},
            },
            {
                {"PHYSICAL_DAMAGE_PCT", 0.2},
            },
        },
        {
            "zifu", "紫府玄冥", "MAGIC",
            {
                {"MAGIC_DAMAGE_PCT", 0.1},
            },
            {
                {"CRIT_DAMAGE", 0.25},
            },
            {
                {"MAGIC_DAMAGE_PCT", 0.2},
            },
        },
    };
    return kSets;
}

inline const std::vector<EquipmentSetDef>& equipmentSetDefs() {
    return equipmentSetDefsMutable();
}

}  // namespace gamecore::data

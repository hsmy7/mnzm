// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 mainStatPools/mainStatBase 同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 部位主词条池静态表（B3，方案 §3.4.2）：主词条从部位候选池等权
// 抽取（nextInt(size) 索引序），声明序禁重排（跨端对拍基准）；
// 暴击伤害主词条基数 = 暴击率同档 × 2
// ============================================================
namespace gamecore::data {

struct MainStatPoolDef {
    std::string part;   // EquipmentSlot.name
    std::vector<std::string> stats;  // 候选池（EquipStat.name，序 = 抽取序）
    double coefficient = 1.0;        // 部位数值系数
};

// 幂等比较（data_inject 注入守卫逐字段比对面；四表 operator== 契约补齐）
inline bool operator==(const MainStatPoolDef& a, const MainStatPoolDef& b) {
    return a.part == b.part && a.stats == b.stats &&
           a.coefficient == b.coefficient;
}

inline std::vector<MainStatPoolDef>& mainStatPoolsMutable() {
    static std::vector<MainStatPoolDef> kPools = {
        {"HEAD", {"HP", "DEFENSE"}, 1},
        {"BODY", {"DEFENSE", "ATTACK", "CRIT_RATE", "CRIT_DAMAGE"}, 1},
        {"HANDS", {"ATTACK", "CRIT_RATE", "CRIT_DAMAGE"}, 1.15},
        {"FEET", {"DEFENSE", "ATTACK", "CRIT_RATE", "CRIT_DAMAGE", "HP"}, 0.95},
    };
    return kPools;
}

inline const std::vector<MainStatPoolDef>& mainStatPools() {
    return mainStatPoolsMutable();
}

/// 主词条品阶基数表（品阶 1..6）
struct MainStatBaseRow {
    std::string stat;
    double values[6];
};

inline bool operator==(const MainStatBaseRow& a, const MainStatBaseRow& b) {
    if (a.stat != b.stat) return false;
    for (int i = 0; i < 6; ++i) {
        if (a.values[i] != b.values[i]) return false;
    }
    return true;
}

inline std::vector<MainStatBaseRow>& mainStatBaseMutable() {
    static std::vector<MainStatBaseRow> kBase = {
        {"ATTACK", {3, 9, 27, 84, 255, 1404}},
        {"DEFENSE", {3, 9, 27, 84, 255, 1404}},
        {"HP", {30, 90, 270, 840, 2550, 14040}},
        {"CRIT_RATE", {0.002, 0.006, 0.018, 0.056, 0.17, 0.52}},
    };
    return kBase;
}

inline const std::vector<MainStatBaseRow>& mainStatBase() {
    return mainStatBaseMutable();
}

}  // namespace gamecore::data

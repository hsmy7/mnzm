// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 subAffixes 段同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 副词条池静态表（B3，方案 §3.4.3）：7 项全局池、权重即概率
//（合计 100）；不放回抽 3 条 = 权重前缀和 + nextInt(total) 定点整数
// 定位，声明序禁重排（同种子抽取序列跨端对拍基准）
// ============================================================
namespace gamecore::data {

struct EquipAffixDef {
    std::string stat;      // EquipStat.name
    std::int32_t weight = 0;  // 权重（=概率%，全表合计 100）
    double tierValues[6];  // 品阶 1..6 单次强化档位值
};

// 幂等比较（data_inject 注入守卫逐字段比对面；四表 operator== 契约补齐）
inline bool operator==(const EquipAffixDef& a, const EquipAffixDef& b) {
    if (a.stat != b.stat || a.weight != b.weight) return false;
    for (int i = 0; i < 6; ++i) {
        if (a.tierValues[i] != b.tierValues[i]) return false;
    }
    return true;
}

inline std::vector<EquipAffixDef>& equipAffixesMutable() {
    static std::vector<EquipAffixDef> kAffixes = {
        {"ATTACK", 13, {1, 3, 8, 21, 64, 195}},
        {"DEFENSE", 13, {1, 3, 6, 14, 43, 130}},
        {"HP", 14, {14, 40, 106, 280, 860, 2600}},
        {"CRIT_RATE", 15, {0.002, 0.003, 0.004, 0.006, 0.008, 0.01}},
        {"CRIT_DAMAGE", 15, {0.004, 0.006, 0.008, 0.012, 0.016, 0.02}},
        {"PHYSICAL_DAMAGE_PCT", 15, {0.004, 0.006, 0.008, 0.012, 0.016, 0.02}},
        {"MAGIC_DAMAGE_PCT", 15, {0.004, 0.006, 0.008, 0.012, 0.016, 0.02}},
    };
    return kAffixes;
}

inline const std::vector<EquipAffixDef>& equipAffixes() {
    return equipAffixesMutable();
}

/// 权重合计（应为 100）
inline std::int32_t equipAffixTotalWeight() {
    std::int32_t total = 0;
    for (const auto& a : equipAffixes()) total += a.weight;
    return total;
}

}  // namespace gamecore::data

// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 equipment_db_sample.json 同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 装备套装部件静态表（B3：2 套 × 6 部位 = 12 条；按品阶展开为
// 72 条可生成条目的基表）
// ============================================================
namespace gamecore::data {

struct SetPieceTemplate {
    std::string id;        // "{setId}_{part}"，如 lietian_HEAD
    std::string setId;     // 套装 id（lietian/zifu）
    std::string part;      // 六部位 EquipmentSlot.name
    std::string name;
    std::string description;
    std::int32_t priceByRarity[6];   // 品阶 1..6 价格
    std::int32_t minRealmByRarity[6]; // 品阶 1..6 穿戴门槛
};

inline bool operator==(const SetPieceTemplate& a, const SetPieceTemplate& b) {
    if (a.id != b.id || a.setId != b.setId || a.part != b.part ||
        a.name != b.name || a.description != b.description) return false;
    for (int i = 0; i < 6; ++i) {
        if (a.priceByRarity[i] != b.priceByRarity[i]) return false;
        if (a.minRealmByRarity[i] != b.minRealmByRarity[i]) return false;
    }
    return true;
}

/// 全部套装部件（12 条；Mutable 入口供 data_inject.h 运行期注入）
inline std::vector<SetPieceTemplate>& setPieceTemplatesMutable() {
    static std::vector<SetPieceTemplate> kPieces = {
        {
            "lietian_HEAD", "lietian", "HEAD", "裂天罡煞·头冠", "裂天罡煞套装头冠，罡煞之气护持识海",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lietian_BODY", "lietian", "BODY", "裂天罡煞·重铠", "裂天罡煞套装重铠，煞气凝甲坚不可摧",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lietian_HANDS", "lietian", "HANDS", "裂天罡煞·战手", "裂天罡煞套装护手，罡风附刃裂石开碑",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lietian_FEET", "lietian", "FEET", "裂天罡煞·战靴", "裂天罡煞套装战靴，踏罡步斗势如奔雷",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lietian_WEAPON", "lietian", "WEAPON", "裂天罡煞·战刃", "裂天罡煞套装战刃，煞刃出鞘天地震动",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lietian_LEGS", "lietian", "LEGS", "裂天罡煞·胫甲", "裂天罡煞套装胫甲，罡气缠腿稳若山岳",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "zifu_HEAD", "zifu", "HEAD", "紫府玄冥·灵冠", "紫府玄冥套装灵冠，玄冥紫气灌顶凝神",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "zifu_BODY", "zifu", "BODY", "紫府玄冥·玄袍", "紫府玄冥套装玄袍，玄冥之雾不侵五行",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "zifu_HANDS", "zifu", "HANDS", "紫府玄冥·灵手", "紫府玄冥套装灵手，灵韵凝掌法随念动",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "zifu_FEET", "zifu", "FEET", "紫府玄冥·云履", "紫府玄冥套装云履，踏云御风玄冥相随",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "zifu_WEAPON", "zifu", "WEAPON", "紫府玄冥·灵剑", "紫府玄冥套装灵剑，紫电青霜斩尽妖邪",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "zifu_LEGS", "zifu", "LEGS", "紫府玄冥·灵甲", "紫府玄冥套装灵甲，玄光护腿百法不侵",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
    };
    return kPieces;
}

/// 只读视图
inline const std::vector<SetPieceTemplate>& setPieceTemplates() {
    return setPieceTemplatesMutable();
}

/// 部件 id → 下标（未命中返回 -1）
inline int setPieceIndexOf(const std::string& id) {
    const auto& pieces = setPieceTemplates();
    for (std::size_t i = 0; i < pieces.size(); ++i) {
        if (pieces[i].id == id) return static_cast<int>(i);
    }
    return -1;
}

}  // namespace gamecore::data

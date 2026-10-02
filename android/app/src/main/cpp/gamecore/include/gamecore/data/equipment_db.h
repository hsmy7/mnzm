// 由 scripts/gen-templates.mjs 生成 — 禁止手改（与中性源 equipment_db_sample.json 同源）
#pragma once

#include <cstdint>
#include <string>
#include <vector>

// ============================================================
// 装备套装部件静态表（6 套 × 4 部位 = 24 条；按品阶展开为
// 144 条可生成条目的基表）
// ============================================================
namespace gamecore::data {

struct SetPieceTemplate {
    std::string id;        // "{setId}_{part}"，如 lietian_HEAD
    std::string setId;     // 套装 id（lietian/gengjin 等）
    std::string part;      // 四部位 EquipmentSlot.name
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

/// 全部套装部件（24 条；Mutable 入口供 data_inject.h 运行期注入）
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
            "gengjin_HEAD", "gengjin", "HEAD", "庚金白虎·灵冠", "庚金白虎套装灵冠，白虎金睛洞察秋毫",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "gengjin_BODY", "gengjin", "BODY", "庚金白虎·法袍", "庚金白虎套装法袍，金气织体刀兵不侵",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "gengjin_HANDS", "gengjin", "HANDS", "庚金白虎·灵护", "庚金白虎套装灵护，锐金凝爪裂金断玉",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "gengjin_FEET", "gengjin", "FEET", "庚金白虎·云履", "庚金白虎套装云履，虎啸风生金戈疾行",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "qingmu_HEAD", "qingmu", "HEAD", "青木长生·灵冠", "青木长生套装灵冠，青木灵韵清心明神",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "qingmu_BODY", "qingmu", "BODY", "青木长生·法袍", "青木长生套装法袍，生生不息缠枝为衣",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "qingmu_HANDS", "qingmu", "HANDS", "青木长生·灵护", "青木长生套装灵护，藤蔓缠腕生机盎然",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "qingmu_FEET", "qingmu", "FEET", "青木长生·云履", "青木长生套装云履，踏叶而行轻若春风",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "xuanshui_HEAD", "xuanshui", "HEAD", "玄水寒渊·灵冠", "玄水寒渊套装灵冠，寒渊之息凝神静念",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "xuanshui_BODY", "xuanshui", "BODY", "玄水寒渊·法袍", "玄水寒渊套装法袍，玄水环身百法不沾",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "xuanshui_HANDS", "xuanshui", "HANDS", "玄水寒渊·灵护", "玄水寒渊套装灵护，寒潮覆掌冻结万机",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "xuanshui_FEET", "xuanshui", "FEET", "玄水寒渊·云履", "玄水寒渊套装云履，凌波微步踏水无痕",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lihuo_HEAD", "lihuo", "HEAD", "离火焚天·灵冠", "离火焚天套装灵冠，离火真焰炼神涤魄",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lihuo_BODY", "lihuo", "BODY", "离火焚天·法袍", "离火焚天套装法袍，炎纹织体烈焰随身",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lihuo_HANDS", "lihuo", "HANDS", "离火焚天·灵护", "离火焚天套装灵护，火灵附掌焚尽八荒",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "lihuo_FEET", "lihuo", "FEET", "离火焚天·云履", "离火焚天套装云履，踏火而行燎原疾影",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "houtu_HEAD", "houtu", "HEAD", "厚土镇岳·灵冠", "厚土镇岳套装灵冠，厚土之德沉稳心神",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "houtu_BODY", "houtu", "BODY", "厚土镇岳·法袍", "厚土镇岳套装法袍，山岳之甲岿然不动",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "houtu_HANDS", "houtu", "HANDS", "厚土镇岳·灵护", "厚土镇岳套装灵护，镇岳之力撼地崩山",
            {4000, 16000, 80000, 480000, 3360000, 26880000},
            {9, 7, 6, 5, 4, 2}
        },
        {
            "houtu_FEET", "houtu", "FEET", "厚土镇岳·云履", "厚土镇岳套装云履，踏地生根移山填谷",
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

#include <gtest/gtest.h>

#include <set>
#include <string>

#include "gamecore/data/equip_affix_db.h"
#include "gamecore/data/equip_main_stat_db.h"
#include "gamecore/data/equip_set_db.h"
#include "gamecore/data/equipment_db.h"
#include "gamecore/data/equipment_entries.h"

namespace gamecore::data {
namespace {

// ============================================================
// 装备静态表守卫测试（B3 重写：12 部件 / 2 套装 / 72 展开条目 /
// 7 项字段面，逐项对齐 Kotlin EquipmentDatabase.kt / EquipmentSetDatabase.kt）
//
// 守护目标：C++ 装备四表（equipment_db.h / equip_set_db.h /
// equip_main_stat_db.h / equip_affix_db.h，由 scripts/gen-templates.mjs 生成）
// 与 Kotlin 注册表字面量一致——双端静态数据漂移拦截。
// Kotlin 侧守卫见 TemplateRegistryGuardTest（快照 ↔ Kotlin Registry）。
// ============================================================

/// 品阶价格表（= GameConfig.Rarity.basePrice，Kotlin RARITY_PRICES 快照）
constexpr int32_t kRarityPrices[6] = {4000, 16000, 80000, 480000, 3360000, 26880000};
/// 品阶穿戴门槛表（= GameConfig.Realm.getMinRealmForRarity，Kotlin RARITY_MIN_REALMS）
constexpr int32_t kRarityMinRealms[6] = {9, 7, 6, 5, 4, 2};

TEST(EquipmentDbTest, PieceTemplateCount) {
    // Kotlin EquipmentDatabase.setPieces：6 套 × 6 部位 = 36 条部件模板
    EXPECT_EQ(36u, setPieceTemplates().size());
}

TEST(EquipmentDbTest, SetCountAndSchool) {
    // Kotlin EquipmentSetDatabase.sets：物理 + 五行六套（同构骨架，方案 §3.4）
    const auto& sets = equipmentSetDefs();
    ASSERT_EQ(6u, sets.size());
    EXPECT_EQ("lietian", sets[0].id);
    EXPECT_EQ("裂天罡煞", sets[0].name);
    EXPECT_EQ("PHYSICAL", sets[0].school);
    EXPECT_EQ("gengjin", sets[1].id);
    EXPECT_EQ("庚金白虎", sets[1].name);
    EXPECT_EQ("METAL", sets[1].school);
    EXPECT_EQ("houtu", sets[5].id);
    EXPECT_EQ("厚土镇岳", sets[5].name);
    EXPECT_EQ("EARTH", sets[5].school);
}

TEST(EquipmentDbTest, ExpandedEntryCount) {
    // 36 部件 × 品阶 1..6 = 216 条展开条目
    EXPECT_EQ(216u, equipmentEntries().size());
    // 每套每部位 × 6 品阶：按部位过滤面各 36 条
    for (const char* part : {"HEAD", "BODY", "HANDS", "FEET", "WEAPON", "LEGS"}) {
        EXPECT_EQ(36u, equipmentEntriesByPart(part).size()) << part;
    }
}

TEST(EquipmentDbTest, PieceTemplateSevenFieldSurface) {
    // 7 项字段面（对齐 Kotlin SetPieceTemplate：id/setId/part/name/description/
    // priceByRarity/minRealmByRarity）：抽样断言首尾条目 + 全量价格/门槛表校验
    const auto& pieces = setPieceTemplates();
    const SetPieceTemplate* lietianHead = nullptr;
    const SetPieceTemplate* lihuoLegs = nullptr;
    for (const auto& p : pieces) {
        if (p.id == "lietian_HEAD") lietianHead = &p;
        if (p.id == "lihuo_LEGS") lihuoLegs = &p;
    }
    ASSERT_NE(nullptr, lietianHead);
    EXPECT_EQ("lietian", lietianHead->setId);
    EXPECT_EQ("HEAD", lietianHead->part);
    EXPECT_EQ("裂天罡煞·头冠", lietianHead->name);
    EXPECT_EQ("裂天罡煞套装头冠，罡煞之气护持识海", lietianHead->description);
    ASSERT_NE(nullptr, lihuoLegs);
    EXPECT_EQ("lihuo", lihuoLegs->setId);
    EXPECT_EQ("LEGS", lihuoLegs->part);
    EXPECT_EQ("离火焚天·护胫", lihuoLegs->name);

    // 全量 36 条：六套部件；价格/门槛两表逐品阶一致（表常量引用，无魔法数字）
    std::set<std::string> parts;
    for (const auto& p : pieces) {
        parts.insert(p.part);
        for (int r = 1; r <= 6; ++r) {
            EXPECT_EQ(kRarityPrices[r - 1], p.priceByRarity[r - 1])
                << p.id << " rarity=" << r;
            EXPECT_EQ(kRarityMinRealms[r - 1], p.minRealmByRarity[r - 1])
                << p.id << " rarity=" << r;
        }
    }
    EXPECT_EQ(6u, parts.size());
}

TEST(EquipmentDbTest, ExpandedEntryFieldFace) {
    // 展开条目字段面（对齐 Kotlin EquipPieceEntry）：id 规则
    // "{pieceId}_r{rarity}"、pieceId/setId/part 透传、name/description 继承、
    // price/minRealm 按品阶取档
    const EquipPieceEntry* first = nullptr;
    const EquipPieceEntry* last = nullptr;
    for (const auto& e : equipmentEntries()) {
        if (e.id == "lietian_HEAD_r1") first = &e;
        if (e.id == "houtu_LEGS_r6") last = &e;
    }
    ASSERT_NE(nullptr, first);
    EXPECT_EQ("lietian_HEAD", first->pieceId);
    EXPECT_EQ("lietian", first->setId);
    EXPECT_EQ("HEAD", first->part);
    EXPECT_EQ(1, first->rarity);
    EXPECT_EQ("裂天罡煞·头冠", first->name);
    EXPECT_EQ(kRarityPrices[0], first->price);
    EXPECT_EQ(kRarityMinRealms[0], first->minRealm);
    ASSERT_NE(nullptr, last);
    EXPECT_EQ("houtu_LEGS", last->pieceId);
    EXPECT_EQ(6, last->rarity);
    EXPECT_EQ(kRarityPrices[5], last->price);
    EXPECT_EQ(kRarityMinRealms[5], last->minRealm);
}

TEST(EquipmentDbTest, EntryLookupAndMiss) {
    // equipmentEntryById 命中/未命中（Kotlin getById null 臂）
    const EquipPieceEntry* hit = equipmentEntryById("lihuo_WEAPON_r3");
    ASSERT_NE(nullptr, hit);
    EXPECT_EQ(3, hit->rarity);
    EXPECT_EQ("WEAPON", hit->part);
    EXPECT_EQ(nullptr, equipmentEntryById("godSlayer"));
    EXPECT_EQ(nullptr, equipmentEntryById("lietian_HEAD_r7"));   // 品阶越界
    EXPECT_EQ(nullptr, equipmentEntryById(""));
}

TEST(EquipmentDbTest, IdsUnique) {
    // 去重守卫：部件 id 与展开条目 id 均唯一（Kotlin Map 键语义）
    std::set<std::string> pieceIds;
    for (const auto& p : setPieceTemplates()) {
        EXPECT_TRUE(pieceIds.insert(p.id).second) << "重复部件 id: " << p.id;
    }
    std::set<std::string> entryIds;
    for (const auto& e : equipmentEntries()) {
        EXPECT_TRUE(entryIds.insert(e.id).second) << "重复条目 id: " << e.id;
    }
}

TEST(EquipmentDbTest, MainStatPoolSurface) {
    // 六部位池齐全（equip_main_stat_db.h）；部位系数来自表常量
    ASSERT_EQ(6u, mainStatPools().size());
    std::set<std::string> parts;
    for (const auto& pool : mainStatPools()) {
        EXPECT_FALSE(pool.stats.empty()) << pool.part;
        parts.insert(pool.part);
    }
    EXPECT_EQ(6u, parts.size());
    // 品阶基数表 4 行（ATTACK/DEFENSE/HP/CRIT_RATE），各 6 档
    ASSERT_EQ(4u, mainStatBase().size());
    for (const auto& row : mainStatBase()) {
        for (int r = 0; r < 6; ++r) {
            EXPECT_GT(row.values[r], 0.0) << row.stat << " rarity=" << (r + 1);
        }
    }
}

TEST(EquipmentDbTest, SubAffixPoolSurface) {
    // 11 项副词条池（物理 7 + 五行各 6）、权重合计 100（equip_affix_db.h equipAffixTotalWeight）
    EXPECT_EQ(11u, equipAffixes().size());
    EXPECT_EQ(100, equipAffixTotalWeight());
    std::set<std::string> stats;
    for (const auto& a : equipAffixes()) {
        EXPECT_GT(a.weight, 0) << a.stat;
        stats.insert(a.stat);
    }
    EXPECT_EQ(11u, stats.size());
}

}  // namespace
}  // namespace gamecore::data

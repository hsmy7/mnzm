// 储物袋统一入袋守卫（审计 P2-8 / 方案 D3 改动 6）
//
// 锁定的不变量（R4：入袋成本有上界；不销毁已有物品）：
//   1. 同类物品（同 itemType+name+rarity，无实例 payload）N 次入袋 → 1 条堆叠；
//   2. 51 件不同物品 → 袋内 50 条 + 第 51 件被拒；
//   3. 实例条目（equipmentInstance/manualInstance）按 itemId 匹配、互不合并；
//   4. bagCanAccept：满袋但存在可合并 kind 条目时仍接受。

#include <gtest/gtest.h>

#include <string>

#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"

namespace gamecore::system {
namespace {

using state::Disciple;
using state::EquipmentInstance;
using state::StorageBagItem;

StorageBagItem makeItem(const std::string& itemType, const std::string& name,
                        int32_t rarity, int32_t quantity = 1) {
    StorageBagItem it;
    it.itemId = itemType + "-" + name + "-" + std::to_string(rarity);
    it.itemType = itemType;
    it.name = name;
    it.rarity = rarity;
    it.quantity = quantity;
    return it;
}

TEST(StorageBagCapacityTest, SameKindMergesIntoSingleEntry) {
    Disciple d;
    for (int i = 0; i < 10; ++i) {
        ASSERT_TRUE(addToDiscipleBag(d, makeItem("pill", "回气丹", 3)));
    }
    ASSERT_EQ(d.storageBagItems.size(), 1u);
    EXPECT_EQ(d.storageBagItems[0].quantity, 10);
}

TEST(StorageBagCapacityTest, CapacityGateRejectsThe51stDistinctItem) {
    Disciple d;
    for (int i = 0; i < 50; ++i) {
        ASSERT_TRUE(addToDiscipleBag(
            d, makeItem("material", "材料" + std::to_string(i), 1)));
    }
    EXPECT_EQ(d.storageBagItems.size(), 50u);
    // 第 51 件不同物品被拒（返回 false），已有物品不受影响
    EXPECT_FALSE(addToDiscipleBag(d, makeItem("material", "溢出材料", 1)));
    EXPECT_EQ(d.storageBagItems.size(), 50u);
}

TEST(StorageBagCapacityTest, InstanceEntriesMergeOnlyByItemId) {
    Disciple d;
    StorageBagItem a = makeItem("equipment_instance", "木剑", 2);
    EquipmentInstance instA;
    instA.id = "eq-a";
    a.equipmentInstance = instA;
    ASSERT_TRUE(addToDiscipleBag(d, a));

    StorageBagItem sameId = a;
    ASSERT_TRUE(addToDiscipleBag(d, sameId));  // 同 itemId 合并
    EXPECT_EQ(d.storageBagItems.size(), 1u);

    StorageBagItem b = makeItem("equipment_instance", "木剑", 2);
    b.itemId = "eq-b";  // 实例合并键 = itemId（与 a 不同 → 不合并）
    EquipmentInstance instB;
    instB.id = "eq-b";
    b.equipmentInstance = instB;
    EXPECT_TRUE(addToDiscipleBag(d, b));  // 不同实例不合并 → 新条目
    EXPECT_EQ(d.storageBagItems.size(), 2u);
}

TEST(StorageBagCapacityTest, BagCanAcceptMergableKindWhenFull) {
    std::vector<StorageBagItem> bag;
    for (int i = 0; i < 50; ++i) {
        bag.push_back(makeItem("material", "材料" + std::to_string(i), 1));
    }
    EXPECT_FALSE(bagCanAccept(bag, "pill", "新丹药", 1));      // 满袋不可合并
    EXPECT_TRUE(bagCanAccept(bag, "material", "材料1", 1));    // 满袋可合并
}

}  // namespace
}  // namespace gamecore::system

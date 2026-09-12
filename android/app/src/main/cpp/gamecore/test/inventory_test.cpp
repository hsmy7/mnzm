#include <gtest/gtest.h>

#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"

namespace gamecore::system {
namespace {

using state::EquipmentStack;
using state::GameState;
using state::ManualStack;
using state::Material;
using state::Pill;
using state::Seed;
using state::StorageBag;

// ── StackableItemStore 泛型核心 ─────────────────────────────────

TEST(StackableItemStoreTest, MergeIntoExistingStack) {
    std::vector<EquipmentStack> items;
    EquipmentStack a;
    a.id = "eq-1";
    a.name = "木剑";
    a.rarity = 1;
    a.slot = "WEAPON";
    a.quantity = 50;
    items.push_back(a);

    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 100; });
    EquipmentStack incoming = a;
    incoming.id = "eq-2";
    incoming.quantity = 30;

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    ASSERT_EQ(store.all().size(), 1u);  // 合并而非新建
    EXPECT_EQ(store.all()[0].quantity, 80);
    EXPECT_EQ(store.all()[0].id, "eq-1");  // 保留目标 id
}

TEST(StackableItemStoreTest, MergeMultipleStacksThenCreateNew) {
    std::vector<EquipmentStack> items;
    EquipmentStack base;
    base.name = "木剑";
    base.rarity = 1;
    base.slot = "WEAPON";
    base.id = "eq-1";
    base.quantity = 990;
    items.push_back(base);
    base.id = "eq-2";
    items.push_back(base);

    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 100; });
    EquipmentStack incoming = base;
    incoming.id = "eq-3";
    incoming.quantity = 20;

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    // 第一个堆叠吸收 9 → 999，第二个吸收 11 → 1001？不：999 上限，吸收 11 超限？
    // 语义：space = maxStack - quantity，每个堆叠分别吸收剩余空间
    // eq-1 space=9 → +9=999；remaining=11；eq-2 space=9 → +9=999；remaining=2 → 新建
    // 验证数量总和 = 990+990+20 = 2000
    int total = 0;
    for (const auto& it : store.all()) total += it.quantity;
    EXPECT_EQ(total, 2000);
    // 且数量不超过 maxStack
    for (const auto& it : store.all()) EXPECT_LE(it.quantity, 999);
}

TEST(StackableItemStoreTest, FullSlotReturnsPartialAfterMerge) {
    std::vector<EquipmentStack> items;
    EquipmentStack base;
    base.name = "木剑";
    base.rarity = 1;
    base.slot = "WEAPON";
    base.id = "eq-1";
    base.quantity = 995;
    items.push_back(base);

    // maxSlots=1（已占用）→ 合并 4 后 remaining>0 且无新槽 → Partial
    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 1; });
    EquipmentStack incoming = base;
    incoming.id = "eq-2";
    incoming.quantity = 10;

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kPartial);
    EXPECT_EQ(result.overflow, 6);  // 995+10=1005，合并 4，溢出 6
    EXPECT_EQ(store.all().size(), 1u);
    EXPECT_EQ(store.all()[0].quantity, 999);
}

TEST(StackableItemStoreTest, FullSlotFailureWhenNoMerge) {
    std::vector<EquipmentStack> items;
    EquipmentStack base;
    base.name = "木剑";
    base.rarity = 1;
    base.slot = "WEAPON";
    base.id = "eq-1";
    base.quantity = 100;
    items.push_back(base);

    // 不同键（不同名称）→ 无法合并 → 无空槽 → Failure(Full)
    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 1; });
    EquipmentStack incoming = base;
    incoming.id = "eq-2";
    incoming.name = "铁剑";

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kFull);
}

TEST(StackableItemStoreTest, ChunkCreationPreservesFirstId) {
    std::vector<EquipmentStack> items;
    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 10; });
    EquipmentStack incoming;
    incoming.id = "eq-1";
    incoming.name = "木剑";
    incoming.rarity = 1;
    incoming.slot = "WEAPON";
    incoming.quantity = 2000;  // 超过 maxStack → 分块

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    ASSERT_EQ(store.all().size(), 3u);  // 999 + 999 + 2
    EXPECT_EQ(store.all()[0].id, "eq-1");  // 首个分块保留原 id
    EXPECT_NE(store.all()[1].id, "eq-1");  // 后续分块新 id
    int total = 0;
    for (const auto& it : store.all()) total += it.quantity;
    EXPECT_EQ(total, 2000);
}

TEST(StackableItemStoreTest, RemoveDecrementsOrDeletes) {
    std::vector<EquipmentStack> items;
    EquipmentStack base;
    base.id = "eq-1";
    base.name = "木剑";
    base.rarity = 1;
    base.slot = "WEAPON";
    base.quantity = 10;
    items.push_back(base);

    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 100; });
    auto r1 = store.remove("eq-1", 3);
    ASSERT_EQ(r1.status, InventoryStatus::kSuccess);
    ASSERT_EQ(store.all().size(), 1u);
    EXPECT_EQ(store.all()[0].quantity, 7);

    auto r2 = store.remove("eq-1", 7);
    ASSERT_EQ(r2.status, InventoryStatus::kSuccess);
    EXPECT_TRUE(store.all().empty());
}

TEST(StackableItemStoreTest, RemoveLockedFails) {
    std::vector<EquipmentStack> items;
    EquipmentStack base;
    base.id = "eq-1";
    base.name = "木剑";
    base.rarity = 1;
    base.slot = "WEAPON";
    base.quantity = 10;
    base.isLocked = true;
    items.push_back(base);

    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 100; });
    const auto result = store.remove("eq-1", 1);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kLocked);
}

TEST(StackableItemStoreTest, RemoveInsufficientFails) {
    std::vector<EquipmentStack> items;
    EquipmentStack base;
    base.id = "eq-1";
    base.name = "木剑";
    base.rarity = 1;
    base.slot = "WEAPON";
    base.quantity = 2;
    items.push_back(base);

    StackableItemStore<EquipmentStack> store(
        items, equipmentKey, 999, []() { return 100; });
    const auto result = store.remove("eq-1", 5);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kInsufficient);
}

// ── InventorySystem 层 ──────────────────────────────────────────

TEST(InventorySystemTest, AddEquipmentStackSuccess) {
    GameState state;
    OverflowMailCollector mail;
    EquipmentStack item;
    item.id = "eq-1";
    item.name = "木剑";
    item.rarity = 1;
    item.slot = "WEAPON";
    item.quantity = 5;

    const auto result =
        addEquipmentStack(state, item, mail, "battle", /*suppressed=*/false);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    ASSERT_EQ(state.equipmentStacks.size(), 1u);
    EXPECT_EQ(state.equipmentStacks[0].quantity, 5);
    // 年度报告追踪
    EXPECT_EQ(state.gameData.annualEquipmentBySource["battle:1"], 5);
}

TEST(InventorySystemTest, AddEquipmentInvalidRarity) {
    GameState state;
    OverflowMailCollector mail;
    EquipmentStack item;
    item.id = "eq-1";
    item.name = "木剑";
    item.rarity = 99;
    item.quantity = 5;

    const auto result =
        addEquipmentStack(state, item, mail, "battle", false);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kInvalidRarity);
    EXPECT_TRUE(state.equipmentStacks.empty());
}

TEST(InventorySystemTest, AddPillRecordsGradeSource) {
    GameState state;
    OverflowMailCollector mail;
    Pill item;
    item.id = "pill-1";
    item.name = "回气丹";
    item.rarity = 2;
    item.category = "CULTIVATION";
    item.grade = "MEDIUM";
    item.quantity = 3;

    const auto result = addPill(state, item, mail, "alchemy", false);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    EXPECT_EQ(state.gameData.annualPillBySource["alchemy:MEDIUM"], 3);
}

TEST(InventorySystemTest, AddOverflowGeneratesMailDraft) {
    // 满仓：baseCapacity=50 且无仓库建筑 → maxSlots=50；
    // 预填 50 个不同名称的装备堆叠占满槽位
    GameState state;
    for (int i = 0; i < 50; ++i) {
        EquipmentStack s;
        s.id = "pre-" + std::to_string(i);
        s.name = "占位" + std::to_string(i);
        s.rarity = 1;
        s.slot = "WEAPON";
        s.quantity = 1;
        state.equipmentStacks.push_back(s);
    }
    OverflowMailCollector mail;
    EquipmentStack item;
    item.id = "new-1";
    item.name = "新物品";
    item.rarity = 1;
    item.slot = "WEAPON";
    item.quantity = 10;

    const auto result =
        addEquipmentStack(state, item, mail, "battle", /*suppressed=*/false);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kFull);
    // 溢出转邮件：全部数量
    ASSERT_EQ(mail.all().size(), 1u);
    EXPECT_EQ(mail.all()[0].itemType, "equipment");
    EXPECT_EQ(mail.all()[0].quantity, 10);
    EXPECT_EQ(mail.all()[0].source, "battle");
}

TEST(InventorySystemTest, AddOverflowSuppressedSkipsMail) {
    GameState state;
    for (int i = 0; i < 50; ++i) {
        EquipmentStack s;
        s.id = "pre-" + std::to_string(i);
        s.name = "占位" + std::to_string(i);
        s.rarity = 1;
        s.slot = "WEAPON";
        s.quantity = 1;
        state.equipmentStacks.push_back(s);
    }
    OverflowMailCollector mail;
    EquipmentStack item;
    item.id = "new-1";
    item.name = "新物品";
    item.rarity = 1;
    item.slot = "WEAPON";
    item.quantity = 10;

    addEquipmentStack(state, item, mail, "mail", /*suppressed=*/true);
    EXPECT_TRUE(mail.empty());
}

TEST(InventorySystemTest, RemoveEquipmentPartialQuantity) {
    GameState state;
    EquipmentStack s;
    s.id = "eq-1";
    s.name = "木剑";
    s.rarity = 1;
    s.slot = "WEAPON";
    s.quantity = 10;
    state.equipmentStacks.push_back(s);

    EXPECT_TRUE(removeEquipment(state, "eq-1", 4));
    ASSERT_EQ(state.equipmentStacks.size(), 1u);
    EXPECT_EQ(state.equipmentStacks[0].quantity, 6);

    EXPECT_TRUE(removeEquipment(state, "eq-1", 6));
    EXPECT_TRUE(state.equipmentStacks.empty());
}

TEST(InventorySystemTest, RemoveEquipmentLockedFails) {
    GameState state;
    EquipmentStack s;
    s.id = "eq-1";
    s.name = "木剑";
    s.rarity = 1;
    s.slot = "WEAPON";
    s.quantity = 10;
    s.isLocked = true;
    state.equipmentStacks.push_back(s);

    EXPECT_FALSE(removeEquipment(state, "eq-1", 1));
    EXPECT_EQ(state.equipmentStacks[0].quantity, 10);
    EXPECT_TRUE(removeEquipment(state, "eq-1", 1, /*bypassLock=*/true));
    EXPECT_EQ(state.equipmentStacks[0].quantity, 9);
}

TEST(InventorySystemTest, CapacityCalculations) {
    GameState state;
    EXPECT_EQ(computeMaxSlots(state), 50);
    EXPECT_EQ(computeSlotCount(state), 0);
    EXPECT_TRUE(canAddItem(state));
    EXPECT_TRUE(canAddItems(state, 50));

    // 放一栋仓库 → +75
    state::GridBuildingData warehouse;
    warehouse.displayName = "仓库";
    state.gameData.placedBuildings.push_back(warehouse);
    EXPECT_EQ(computeMaxSlots(state), 125);

    // 填满 → 无法添加
    for (int i = 0; i < 125; ++i) {
        EquipmentStack s;
        s.id = "pre-" + std::to_string(i);
        s.name = "占位" + std::to_string(i);
        s.rarity = 1;
        s.slot = "WEAPON";
        s.quantity = 1;
        state.equipmentStacks.push_back(s);
    }
    EXPECT_FALSE(canAddItem(state));
}

// ── StackKey 键唯一性 ───────────────────────────────────────────

TEST(StackKeyTest, PillKeyIncludesGrade) {
    state::Pill p1, p2;
    p1.name = "聚气丹";
    p1.rarity = 1;
    p1.category = "CULTIVATION";
    p1.grade = "LOW";
    p2 = p1;
    p2.grade = "HIGH";
    EXPECT_NE(pillKey(p1), pillKey(p2));  // 品阶不同不合并
}

TEST(StackKeyTest, EquipmentKeyIncludesSlot) {
    state::EquipmentStack e1, e2;
    e1.name = "木剑";
    e1.rarity = 1;
    e1.slot = "WEAPON";
    e2 = e1;
    e2.slot = "ARMOR";
    EXPECT_NE(equipmentKey(e1), equipmentKey(e2));
}

}  // namespace
}  // namespace gamecore::system

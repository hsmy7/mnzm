#include <gtest/gtest.h>

#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/inventory.h"

namespace gamecore::system {
namespace {

using state::GameState;
using state::ManualStack;
using state::Material;
using state::Pill;
using state::Seed;
using state::StorageBag;

// B3：装备堆叠轨退役（EquipmentStack 删除）——StackableItemStore 泛型核心
// 的载体改用 Material（与 Kotlin MergeStackableTest 同口径：七用例语义不变）。
// 合并键 materialKey = name + rarity + category。

Material material(const std::string& id, const std::string& name, int32_t rarity,
                  int32_t quantity, const std::string& category = "BEAST_HIDE") {
    Material m;
    m.id = id;
    m.name = name;
    m.rarity = rarity;
    m.category = category;
    m.quantity = quantity;
    return m;
}

// ── StackableItemStore 泛型核心 ─────────────────────────────────

TEST(StackableItemStoreTest, MergeIntoExistingStack) {
    std::vector<Material> items;
    items.push_back(material("m-1", "凡兽骨", 1, 50));

    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 100; });
    Material incoming = material("m-2", "凡兽骨", 1, 30);

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    ASSERT_EQ(store.all().size(), 1u);  // 合并而非新建
    EXPECT_EQ(store.all()[0].quantity, 80);
    EXPECT_EQ(store.all()[0].id, "m-1");  // 保留目标 id
}

TEST(StackableItemStoreTest, MergeMultipleStacksThenCreateNew) {
    std::vector<Material> items;
    Material base = material("m-1", "凡兽骨", 1, 990);
    items.push_back(base);
    base.id = "m-2";
    items.push_back(base);

    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 100; });
    Material incoming = base;
    incoming.id = "m-3";
    incoming.quantity = 20;

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    // 语义：space = maxStack - quantity，每个堆叠分别吸收剩余空间
    // m-1 space=9 → +9=999；remaining=11；m-2 space=9 → +9=999；remaining=2 → 新建
    // 验证数量总和 = 990+990+20 = 2000
    int total = 0;
    for (const auto& it : store.all()) total += it.quantity;
    EXPECT_EQ(total, 2000);
    // 且数量不超过 maxStack
    for (const auto& it : store.all()) EXPECT_LE(it.quantity, 999);
}

TEST(StackableItemStoreTest, FullSlotReturnsPartialAfterMerge) {
    std::vector<Material> items;
    items.push_back(material("m-1", "凡兽骨", 1, 995));

    // maxSlots=1（已占用）→ 合并 4 后 remaining>0 且无新槽 → Partial
    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 1; });
    Material incoming = material("m-2", "凡兽骨", 1, 10);

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kPartial);
    EXPECT_EQ(result.overflow, 6);  // 995+10=1005，合并 4，溢出 6
    EXPECT_EQ(store.all().size(), 1u);
    EXPECT_EQ(store.all()[0].quantity, 999);
}

TEST(StackableItemStoreTest, FullSlotFailureWhenNoMerge) {
    std::vector<Material> items;
    items.push_back(material("m-1", "凡兽骨", 1, 100));

    // 不同键（不同名称）→ 无法合并 → 无空槽 → Failure(Full)
    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 1; });
    Material incoming = material("m-2", "凡兽皮", 1, 100);

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kFull);
}

TEST(StackableItemStoreTest, ChunkCreationPreservesFirstId) {
    std::vector<Material> items;
    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 10; });
    Material incoming = material("m-1", "凡兽骨", 1, 2000);  // 超过 maxStack → 分块

    const auto result = store.add(incoming);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    ASSERT_EQ(store.all().size(), 3u);  // 999 + 999 + 2
    EXPECT_EQ(store.all()[0].id, "m-1");  // 首个分块保留原 id
    EXPECT_NE(store.all()[1].id, "m-1");  // 后续分块新 id
    int total = 0;
    for (const auto& it : store.all()) total += it.quantity;
    EXPECT_EQ(total, 2000);
}

TEST(StackableItemStoreTest, RemoveDecrementsOrDeletes) {
    std::vector<Material> items;
    items.push_back(material("m-1", "凡兽骨", 1, 10));

    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 100; });
    auto r1 = store.remove("m-1", 3);
    ASSERT_EQ(r1.status, InventoryStatus::kSuccess);
    ASSERT_EQ(store.all().size(), 1u);
    EXPECT_EQ(store.all()[0].quantity, 7);

    auto r2 = store.remove("m-1", 7);
    ASSERT_EQ(r2.status, InventoryStatus::kSuccess);
    EXPECT_TRUE(store.all().empty());
}

TEST(StackableItemStoreTest, RemoveLockedFails) {
    std::vector<Material> items;
    Material base = material("m-1", "凡兽骨", 1, 10);
    base.isLocked = true;
    items.push_back(base);

    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 100; });
    const auto result = store.remove("m-1", 1);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kLocked);
}

TEST(StackableItemStoreTest, RemoveInsufficientFails) {
    std::vector<Material> items;
    items.push_back(material("m-1", "凡兽骨", 1, 2));

    StackableItemStore<Material> store(
        items, materialKey, 999, []() { return 100; });
    const auto result = store.remove("m-1", 5);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kInsufficient);
}

// ── InventorySystem 层（B3 装备实例轨） ─────────────────────────

TEST(InventorySystemTest, AddEquipmentInstanceAppendsAndValidates) {
    // B3 单轨实例：校验 id/名称/品阶 + 追加——无槽位上限/无合并/无溢出邮件
    GameState state;
    OverflowMailCollector mail;
    state::EquipmentInstance item;
    item.id = "eq-1";
    item.name = "木剑";
    item.part = "HANDS";
    item.meta.rarity = 1;

    const auto result = addEquipmentInstance(state, item);
    ASSERT_EQ(result.status, InventoryStatus::kSuccess);
    ASSERT_EQ(state.equipmentInstances.size(), 1u);
    EXPECT_EQ(state.equipmentInstances[0].id, "eq-1");
    EXPECT_TRUE(mail.empty());   // 实例轨不产溢出邮件
}

TEST(InventorySystemTest, AddEquipmentInstanceDuplicateIdFails) {
    // 实例唯一性：同 id 拒绝（一行一件不变量）
    GameState state;
    OverflowMailCollector mail;
    state::EquipmentInstance item;
    item.id = "eq-1";
    item.name = "木剑";
    item.meta.rarity = 1;
    ASSERT_EQ(addEquipmentInstance(state, item).status, InventoryStatus::kSuccess);

    const auto dup = addEquipmentInstance(state, item);
    ASSERT_EQ(dup.status, InventoryStatus::kFailure);
    EXPECT_EQ(dup.error.type, InventoryErrorType::kDuplicateId);
    ASSERT_EQ(state.equipmentInstances.size(), 1u);
}

TEST(InventorySystemTest, AddEquipmentInvalidRarity) {
    GameState state;
    OverflowMailCollector mail;
    state::EquipmentInstance item;
    item.id = "eq-1";
    item.name = "木剑";
    item.meta.rarity = 99;

    const auto result = addEquipmentInstance(state, item);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kInvalidRarity);
    EXPECT_TRUE(state.equipmentInstances.empty());
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
    // 预填 50 个不同名称的材料堆叠占满槽位，再加丹药 → Full → 溢出邮件
    GameState state;
    for (int i = 0; i < 50; ++i) {
        state.materials.push_back(
            material("pre-" + std::to_string(i), "占位" + std::to_string(i), 1, 1));
    }
    OverflowMailCollector mail;
    Pill item;
    item.id = "new-1";
    item.name = "新物品";
    item.rarity = 1;
    item.category = "CULTIVATION";
    item.grade = "LOW";
    item.quantity = 10;

    const auto result = addPill(state, item, mail, "battle", /*suppressed=*/false);
    ASSERT_EQ(result.status, InventoryStatus::kFailure);
    EXPECT_EQ(result.error.type, InventoryErrorType::kFull);
    // 溢出转邮件：全部数量
    ASSERT_EQ(mail.all().size(), 1u);
    EXPECT_EQ(mail.all()[0].itemType, "pill");
    EXPECT_EQ(mail.all()[0].quantity, 10);
    EXPECT_EQ(mail.all()[0].source, "battle");
}

TEST(InventorySystemTest, AddOverflowSuppressedSkipsMail) {
    GameState state;
    for (int i = 0; i < 50; ++i) {
        state.materials.push_back(
            material("pre-" + std::to_string(i), "占位" + std::to_string(i), 1, 1));
    }
    OverflowMailCollector mail;
    Pill item;
    item.id = "new-1";
    item.name = "新物品";
    item.rarity = 1;
    item.category = "CULTIVATION";
    item.grade = "LOW";
    item.quantity = 10;

    addPill(state, item, mail, "mail", /*suppressed=*/true);
    EXPECT_TRUE(mail.empty());
}

TEST(InventorySystemTest, RemoveEquipmentInstanceTrack) {
    // B3 实例轨：removeEquipment 无数量语义——1 件 = 1 条目整条移除
    GameState state;
    state::EquipmentInstance s;
    s.id = "eq-1";
    s.name = "木剑";
    s.part = "HANDS";
    s.meta.rarity = 1;
    state.equipmentInstances.push_back(s);

    // quantity 参数保留旧称兼容调用点（实例轨忽略）
    EXPECT_TRUE(removeEquipment(state, "eq-1", 4));
    EXPECT_TRUE(state.equipmentInstances.empty());
    // 再移除同 id：不存在 → false
    EXPECT_FALSE(removeEquipment(state, "eq-1", 1));
}

TEST(InventorySystemTest, RemoveEquipmentLockedFails) {
    GameState state;
    state::EquipmentInstance s;
    s.id = "eq-1";
    s.name = "木剑";
    s.part = "HANDS";
    s.meta.rarity = 1;
    s.meta.isLocked = true;
    state.equipmentInstances.push_back(s);

    EXPECT_FALSE(removeEquipment(state, "eq-1", 1));
    ASSERT_EQ(state.equipmentInstances.size(), 1u);
    EXPECT_TRUE(removeEquipment(state, "eq-1", 1, /*bypassLock=*/true));
    EXPECT_TRUE(state.equipmentInstances.empty());
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

    // 填满（125 槽：124 材料 + 1 装备实例计入）→ 无法添加
    for (int i = 0; i < 124; ++i) {
        state.materials.push_back(
            material("pre-" + std::to_string(i), "占位" + std::to_string(i), 1, 1));
    }
    state::EquipmentInstance eq;
    eq.id = "eq-c1";
    eq.name = "占位剑";
    eq.meta.rarity = 1;
    state.equipmentInstances.push_back(eq);
    EXPECT_EQ(computeSlotCount(state), 125);
    EXPECT_FALSE(canAddItem(state));
}

// ── 装备实例排序（B3 新面）───────────────────────────────────────

TEST(InventorySortTest, SortEquipmentInstancesRarityDescThenNameAsc) {
    // Kotlin compareByDescending rarity thenBy name（stable_sort 保序）
    std::vector<state::EquipmentInstance> items;
    auto mk = [](const std::string& id, const std::string& name, int32_t rarity) {
        state::EquipmentInstance e;
        e.id = id;
        e.name = name;
        e.meta.rarity = rarity;
        return e;
    };
    items.push_back(mk("a", "木剑", 1));
    items.push_back(mk("b", "青云剑", 4));
    items.push_back(mk("c", "铁剑", 1));
    items.push_back(mk("d", "精铁剑", 1));

    sortEquipmentInstances(items);
    ASSERT_EQ(items.size(), 4u);
    EXPECT_EQ(items[0].id, "b");   // rarity 4 最前
    EXPECT_EQ(items[1].id, "a");   // 同 rarity 1：name 升序 木剑
    EXPECT_EQ(items[2].id, "d");   // 精铁剑
    EXPECT_EQ(items[3].id, "c");   // 铁剑
}

TEST(InventorySortTest, SortWarehouseIncludesEquipmentInstances) {
    // sortWarehouse = 合并（实例轨不参与）+ 装备实例排序
    GameState state;
    state::EquipmentInstance eq;
    eq.id = "eq-1";
    eq.name = "木剑";
    eq.meta.rarity = 1;
    state.equipmentInstances.push_back(eq);
    state::EquipmentInstance eq2;
    eq2.id = "eq-2";
    eq2.name = "青云剑";
    eq2.meta.rarity = 4;
    state.equipmentInstances.push_back(eq2);
    state.materials.push_back(material("m-1", "凡兽骨", 1, 5));
    state.materials.push_back(
        material("m-2", "凡兽骨", 1, 3));   // 同键 → 合并进 m-1

    sortWarehouse(state);
    ASSERT_EQ(state.equipmentInstances.size(), 2u);
    EXPECT_EQ(state.equipmentInstances[0].id, "eq-2");
    EXPECT_EQ(state.equipmentInstances[1].id, "eq-1");
    ASSERT_EQ(state.materials.size(), 1u);
    EXPECT_EQ(state.materials[0].quantity, 8);
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

TEST(StackKeyTest, MaterialKeyIncludesCategory) {
    // B3：equipmentKey 随堆叠轨退役——材料键覆盖类别区分面
    state::Material e1, e2;
    e1.name = "凡兽骨";
    e1.rarity = 1;
    e1.category = "BEAST_HIDE";
    e2 = e1;
    e2.category = "BEAST_CORE";
    EXPECT_NE(materialKey(e1), materialKey(e2));  // 类别不同不合并
}

}  // namespace
}  // namespace gamecore::system

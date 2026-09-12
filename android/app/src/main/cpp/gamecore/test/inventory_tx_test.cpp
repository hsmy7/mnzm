// ============================================================
// inventory_tx_test.cpp — 库存出售/上架事务黄金用例（W2-a 下沉）
//
// 守护目标：sellItemTx / bulkSellTx / sellToMerchantTx /
// listItemsToMerchantTx / removePlayerListedItemTx 的校验链判定序与
// 取价公式（与 Kotlin InventoryFacadeImpl 逐字对齐）、灵石精确入账、
// 失败零写入、零 RNG 审计（签名级 + 分发面全分区快照差分）。
//
// 取价黄金值（Kotlin GameConfig.Rarity + ItemDatabase 同源）：
//   装备模板价优先（精铁剑 4000）→ 品阶基准价回退
//   功法/材料/草药/种子 → 品阶基准价
//   丹药 = roundToInt(品阶 pillBasePrice × PillGrade.priceMultiplier)
//   出售价 = basePrice × quantity × 0.8（向零截断）
// ============================================================
#include <gtest/gtest.h>

#include <nlohmann/json.hpp>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/models.h"
#include "gamecore/system/inventory_tx.h"

namespace {

using gamecore::state::EquipmentStack;
using gamecore::state::GameState;
using gamecore::state::Herb;
using gamecore::state::ManualStack;
using gamecore::state::Material;
using gamecore::state::MerchantItem;
using gamecore::state::Pill;
using gamecore::state::Seed;
namespace inventory_tx = gamecore::system::inventory_tx;

EquipmentStack equipmentStack(const std::string& id, const std::string& name,
                              int32_t rarity, int32_t quantity) {
    EquipmentStack s;
    s.id = id;
    s.name = name;
    s.rarity = rarity;
    s.quantity = quantity;
    return s;
}

ManualStack manualStack(const std::string& id, int32_t rarity, int32_t quantity) {
    ManualStack s;
    s.id = id;
    s.name = "测试功法";
    s.rarity = rarity;
    s.quantity = quantity;
    return s;
}

Pill pill(const std::string& id, int32_t rarity, const std::string& grade,
          int32_t quantity) {
    Pill p;
    p.id = id;
    p.name = "测试丹药";
    p.rarity = rarity;
    p.grade = grade;
    p.quantity = quantity;
    return p;
}

MerchantItem acquisitionItem(const std::string& id, const std::string& name,
                             const std::string& type, int64_t price,
                             int32_t quantity, int32_t rarity = 1) {
    MerchantItem item;
    item.id = id;
    item.name = name;
    item.type = type;
    item.price = price;
    item.quantity = quantity;
    item.rarity = rarity;
    return item;
}

/// 空状态（灵石清零——GameData 默认开局 1000，断言以绝对值为准）
GameState newState() {
    GameState st;
    st.gameData.spiritStones = 0;
    st.gameData.midGradeSpiritStones = 0;
    st.gameData.highGradeSpiritStones = 0;
    return st;
}

// ── 单类出售 ────────────────────────────────────────────────────

TEST(InventoryTxTest, SellEquipmentUsesTemplatePriceAndCreditsWallet) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 10));
    const auto r = inventory_tx::sellItemTx(st, "equipment", "e1", 2);
    ASSERT_TRUE(r.ok);
    // 模板价 4000 × 2 × 0.8 = 6400
    EXPECT_EQ(r.earned, 6400);
    EXPECT_EQ(st.gameData.spiritStones, 6400);
    ASSERT_EQ(st.equipmentStacks.size(), 1u);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 8);
    // 年度报告来源键 = Kotlin SpiritStoneSource.Sell("equipment")
    EXPECT_EQ(st.gameData.annualIncomeBySource["Sell(equipment)"], 6400);
}

TEST(InventoryTxTest, SellEquipmentFallsBackToRarityBasePrice) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "未知装备", 2, 1));
    const auto r = inventory_tx::sellItemTx(st, "equipment", "e1", 1);
    ASSERT_TRUE(r.ok);
    // 品阶 2 basePrice 16000 × 1 × 0.8 = 12800
    EXPECT_EQ(r.earned, 12800);
}

TEST(InventoryTxTest, SellRemovesStackWhenFullyConsumed) {
    GameState st = newState();
    st.pills.push_back(pill("p1", 1, "HIGH", 3));
    const auto r = inventory_tx::sellItemTx(st, "pill", "p1", 3);
    ASSERT_TRUE(r.ok);
    // roundToInt(4000 × 2.0) = 8000 → 8000 × 3 × 0.8 = 19200
    EXPECT_EQ(r.earned, 19200);
    EXPECT_TRUE(st.pills.empty());
}

TEST(InventoryTxTest, SellTypePricesMatchKotlinRarityTables) {
    GameState st = newState();
    st.manualStacks.push_back(manualStack("m1", 3, 1));
    st.materials.push_back(Material{.id = "x1", .category = "BEAST_HIDE"});
    st.materials[0].name = "测试材料";
    st.materials[0].rarity = 1;
    st.materials[0].quantity = 1;
    st.herbs.push_back(Herb{.id = "h1", .category = ""});
    st.herbs[0].name = "测试草药";
    st.herbs[0].rarity = 6;
    st.herbs[0].quantity = 1;
    st.seeds.push_back(Seed{.id = "s1"});
    st.seeds[0].name = "测试种子";
    st.seeds[0].rarity = 2;
    st.seeds[0].quantity = 1;

    // 功法品阶 3 → 80000 × 0.8
    EXPECT_EQ(inventory_tx::sellItemTx(st, "manual", "m1", 1).earned, 64000);
    // 材料品阶 1 → 400 × 0.8
    EXPECT_EQ(inventory_tx::sellItemTx(st, "material", "x1", 1).earned, 320);
    // 草药品阶 6 → 2688000 × 0.8
    EXPECT_EQ(inventory_tx::sellItemTx(st, "herb", "h1", 1).earned, 2150400);
    // 种子品阶 2 → 320 × 0.8
    EXPECT_EQ(inventory_tx::sellItemTx(st, "seed", "s1", 1).earned, 256);
}

TEST(InventoryTxTest, SellGuardsRejectWithoutWriting) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 2));
    st.equipmentStacks[0].isLocked = true;
    // 锁定
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 1).earned, 0);
    // 数量 0
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 0).earned, 0);
    // 数量超过持有
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 3).earned, 0);
    // 不存在
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "nope", 1).earned, 0);
    // 未知类型
    EXPECT_EQ(inventory_tx::sellItemTx(st, "unknown", "e1", 1).earned, 0);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    ASSERT_EQ(st.equipmentStacks.size(), 1u);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 2);
    EXPECT_TRUE(st.gameData.annualIncomeBySource.empty());
}

TEST(InventoryTxTest, SellUnlocksOnlyAfterLockCleared) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 2));
    st.equipmentStacks[0].isLocked = true;
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 1).earned, 0);
    st.equipmentStacks[0].isLocked = false;
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 1).earned, 3200);
}

// ── 批量出售 ────────────────────────────────────────────────────

TEST(InventoryTxTest, BulkSellAggregatesAndCreditsOnce) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    st.herbs.push_back(Herb{.id = "h1", .category = ""});
    st.herbs[0].name = "测试草药";
    st.herbs[0].rarity = 1;
    st.herbs[0].quantity = 2;
    st.herbs[0].isLocked = true;  // 锁定 → 失败项

    std::vector<inventory_tx::BulkSellRequest> ops = {
        {.id = "e1", .name = "精铁剑", .itemType = "equipment", .quantity = 2},
        {.id = "h1", .name = "测试草药", .itemType = "herb", .quantity = 1},
        {.id = "nope", .name = "幽灵", .itemType = "pill", .quantity = 1},
    };
    const auto r = inventory_tx::bulkSellTx(st, ops);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldCount, 1);
    EXPECT_EQ(r.totalEarned, 6400);
    ASSERT_EQ(r.soldItemNames.size(), 1u);
    EXPECT_EQ(r.soldItemNames[0], "精铁剑 2");
    ASSERT_EQ(r.failedItemNames.size(), 2u);
    EXPECT_EQ(r.failedItemNames[0], "测试草药");
    EXPECT_EQ(r.failedItemNames[1], "幽灵");
    // 一次入账（来源 Sell(bulk)），非逐条
    EXPECT_EQ(st.gameData.spiritStones, 6400);
    EXPECT_EQ(st.gameData.annualIncomeBySource["Sell(bulk)"], 6400);
    EXPECT_TRUE(st.gameData.annualIncomeBySource.count("Sell(equipment)") == 0);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 3);
    EXPECT_EQ(st.herbs[0].quantity, 2);
}

TEST(InventoryTxTest, BulkSellEmptyOperationsIsNoop) {
    GameState st = newState();
    const auto r = inventory_tx::bulkSellTx(st, {});
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldCount, 0);
    EXPECT_EQ(r.totalEarned, 0);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    EXPECT_TRUE(st.gameData.annualIncomeBySource.empty());
}

// ── 商人收购 ────────────────────────────────────────────────────

TEST(InventoryTxTest, SellToMerchantDeductsByTemplateNameAndRarity) {
    GameState st = newState();
    // 两摞同名同阶（未锁）+ 一摞锁定（不参与扣减，但计入仓库计数）
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 2));
    st.equipmentStacks.push_back(equipmentStack("e2", "精铁剑", 1, 3));
    st.equipmentStacks.push_back(equipmentStack("e3", "精铁剑", 1, 4));
    st.equipmentStacks[2].isLocked = true;
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));

    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 3);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldQuantity, 3);
    EXPECT_EQ(r.totalPrice, 1500);
    EXPECT_EQ(st.gameData.spiritStones, 1500);
    EXPECT_EQ(st.gameData.annualIncomeBySource["MerchantTrade"], 1500);
    // 逐序扣减：e1 清零移除，e2 剩 2
    ASSERT_EQ(st.equipmentStacks.size(), 2u);
    EXPECT_EQ(st.equipmentStacks[0].id, "e2");
    EXPECT_EQ(st.equipmentStacks[0].quantity, 2);
    EXPECT_EQ(st.equipmentStacks[1].id, "e3");
    // 收购项数量回写
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 7);
}

TEST(InventoryTxTest, SellToMerchantClampsToWarehouseQuantity) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 2));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 5);
    ASSERT_TRUE(r.ok);
    // Kotlin actualQuantity = min(请求, 仓库, 收购上限)
    EXPECT_EQ(r.soldQuantity, 2);
    EXPECT_EQ(r.totalPrice, 1000);
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 8);
}

TEST(InventoryTxTest, SellToMerchantZeroWarehouseIsSuccessNoop) {
    GameState st = newState();
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 2);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldQuantity, 0);
    EXPECT_EQ(r.totalPrice, 0);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 10);
}

TEST(InventoryTxTest, SellToMerchantRejectsInvalidRequests) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a2", "精铁剑", "equipment", 0, 10));  // 篡改档 0 价
    // 不存在
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "nope", 1).ok);
    // 数量 0
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "a1", 0).ok);
    // 数量超收购上限
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "a1", 11).ok);
    // 价格非正（D-21 存档完整性防御）
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "a2", 1).ok);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 5);
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 10);
}

TEST(InventoryTxTest, SellToMerchantSpiritStoneDeductsMidGradeBalance) {
    GameState st = newState();
    st.gameData.midGradeSpiritStones = 3;
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "中品灵石", "spiritstone", 8000, 5));
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 5);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldQuantity, 3);
    EXPECT_EQ(r.totalPrice, 24000);
    EXPECT_EQ(st.gameData.midGradeSpiritStones, 0);
    EXPECT_EQ(st.gameData.spiritStones, 24000);
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 2);
}

TEST(InventoryTxTest, SellToMerchantPillRequiresGradeMatch) {
    GameState st = newState();
    st.pills.push_back(pill("p1", 1, "HIGH", 2));
    st.pills.push_back(pill("p2", 1, "LOW", 2));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "测试丹药", "pill", 100, 5));
    st.gameData.merchantAcquisitionItems[0].grade = std::string("上品");
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 5);
    ASSERT_TRUE(r.ok);
    // 仅上品摞可售
    EXPECT_EQ(r.soldQuantity, 2);
    EXPECT_EQ(r.totalPrice, 200);
    ASSERT_EQ(st.pills.size(), 1u);
    EXPECT_EQ(st.pills[0].id, "p2");
    EXPECT_EQ(st.pills[0].quantity, 2);
}

TEST(InventoryTxTest, SellToMerchantCountsLockedStacksInWarehouse) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 2));
    st.equipmentStacks[0].isLocked = true;
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 2);
    ASSERT_TRUE(r.ok);
    // 计数含锁定（Kotlin countWarehouseStacks 不筛锁定），但扣减只作用于未锁定 →
    // 扣减量为 0（无未锁定堆叠可扣），实际成交量仍记 2
    EXPECT_EQ(r.soldQuantity, 2);
    ASSERT_EQ(st.equipmentStacks.size(), 1u);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 2);
}

// ── 上架 / 撤下 ─────────────────────────────────────────────────

TEST(InventoryTxTest, ListItemsToMerchantRegistersEquipmentManualPill) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    st.manualStacks.push_back(manualStack("m1", 3, 4));
    st.pills.push_back(pill("p1", 1, "HIGH", 2));
    st.pills[0].name = "测试丹药";

    std::vector<inventory_tx::ListItemRequest> items = {
        {.itemId = "e1", .quantity = 2},
        {.itemId = "m1", .quantity = 1},
        {.itemId = "p1", .quantity = 2},
        {.itemId = "nope", .quantity = 1},
    };
    const auto r = inventory_tx::listItemsToMerchantTx(st, items);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.listedCount, 3);
    ASSERT_EQ(st.gameData.playerListedItems.size(), 3u);
    // 装备：模板价 4000 × 0.8 = 3200（roundToInt）
    EXPECT_EQ(st.gameData.playerListedItems[0].type, "equipment");
    EXPECT_EQ(st.gameData.playerListedItems[0].price, 3200);
    EXPECT_EQ(st.gameData.playerListedItems[0].quantity, 2);
    // 功法：品阶 3 基准价 80000 × 0.8 = 64000
    EXPECT_EQ(st.gameData.playerListedItems[1].type, "manual");
    EXPECT_EQ(st.gameData.playerListedItems[1].price, 64000);
    // 丹药：roundToInt(4000 × 2.0) × 0.8 = 6400，附品级显示名
    EXPECT_EQ(st.gameData.playerListedItems[2].type, "pill");
    EXPECT_EQ(st.gameData.playerListedItems[2].price, 6400);
    ASSERT_TRUE(st.gameData.playerListedItems[2].grade.has_value());
    EXPECT_EQ(*st.gameData.playerListedItems[2].grade, "上品");
    // 占位 id 批内唯一
    EXPECT_NE(st.gameData.playerListedItems[0].id,
              st.gameData.playerListedItems[1].id);
    // 上架不扣仓库
    EXPECT_EQ(st.equipmentStacks[0].quantity, 5);
    EXPECT_EQ(st.manualStacks[0].quantity, 4);
    EXPECT_EQ(st.pills[0].quantity, 2);
}

TEST(InventoryTxTest, ListItemsToMerchantSkipsOverListedQuantity) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 3));
    st.gameData.playerListedItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 3200, 2));
    st.gameData.playerListedItems[0].itemId = "e1";
    std::vector<inventory_tx::ListItemRequest> items = {
        {.itemId = "e1", .quantity = 2},
    };
    const auto r = inventory_tx::listItemsToMerchantTx(st, items);
    ASSERT_TRUE(r.ok);
    // 已上架 2 + 2 > 3 → 静默跳过（不新增，也不抛错）
    EXPECT_EQ(r.listedCount, 0);
    EXPECT_EQ(st.gameData.playerListedItems.size(), 1u);
}

TEST(InventoryTxTest, ListItemsToMerchantSkipsLockedAndInvalidQuantity) {
    GameState st = newState();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    st.equipmentStacks[0].isLocked = true;
    std::vector<inventory_tx::ListItemRequest> items = {
        {.itemId = "e1", .quantity = 1},
    };
    EXPECT_EQ(inventory_tx::listItemsToMerchantTx(st, items).listedCount, 0);
    st.equipmentStacks[0].isLocked = false;
    std::vector<inventory_tx::ListItemRequest> tooMany = {
        {.itemId = "e1", .quantity = 6},
    };
    EXPECT_EQ(inventory_tx::listItemsToMerchantTx(st, tooMany).listedCount, 0);
    EXPECT_TRUE(st.gameData.playerListedItems.empty());
}

TEST(InventoryTxTest, RemovePlayerListedItemFiltersById) {
    GameState st = newState();
    st.gameData.playerListedItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 3200, 2));
    st.gameData.playerListedItems.push_back(
        acquisitionItem("a2", "测试功法", "manual", 64000, 1));
    inventory_tx::removePlayerListedItemTx(st, "a1");
    ASSERT_EQ(st.gameData.playerListedItems.size(), 1u);
    EXPECT_EQ(st.gameData.playerListedItems[0].id, "a2");
    inventory_tx::removePlayerListedItemTx(st, "missing");
    EXPECT_EQ(st.gameData.playerListedItems.size(), 1u);
}

// ── 材料消耗（consumeMaterialByName） ────────────────────────────

Material materialStack(const std::string& id, const std::string& name, int32_t rarity,
                       int32_t quantity) {
    Material m;
    m.id = id;
    m.name = name;
    m.rarity = rarity;
    m.quantity = quantity;
    return m;
}

TEST(InventoryTxTest, ConsumeMaterialSpansStacksInListOrder) {
    GameState st = newState();
    st.materials.push_back(materialStack("m1", "兽皮", 1, 2));
    st.materials.push_back(materialStack("m2", "兽皮", 1, 3));
    EXPECT_TRUE(inventory_tx::consumeMaterialByNameTx(st, "兽皮", 1, 4));
    // m1 清零移除，m2 剩 1
    ASSERT_EQ(st.materials.size(), 1u);
    EXPECT_EQ(st.materials[0].id, "m2");
    EXPECT_EQ(st.materials[0].quantity, 1);
}

TEST(InventoryTxTest, ConsumeMaterialInsufficientPartiallyWritesAndReturnsFalse) {
    GameState st = newState();
    st.materials.push_back(materialStack("m1", "兽皮", 1, 2));
    EXPECT_FALSE(inventory_tx::consumeMaterialByNameTx(st, "兽皮", 1, 5));
    // 部分扣减照常落盘（Kotlin 同语义：事务已提交，仅返回值 false）
    EXPECT_TRUE(st.materials.empty());
}

TEST(InventoryTxTest, ConsumeMaterialSkipsLockedAndOtherRarity) {
    GameState st = newState();
    st.materials.push_back(materialStack("m1", "兽皮", 1, 5));
    st.materials[0].isLocked = true;
    st.materials.push_back(materialStack("m2", "兽皮", 2, 5));
    st.materials.push_back(materialStack("m3", "兽骨", 1, 5));
    // 仅未锁定 + 同名同阶可消耗 → 无可扣 → false 且零写入
    EXPECT_FALSE(inventory_tx::consumeMaterialByNameTx(st, "兽皮", 1, 1));
    EXPECT_EQ(st.materials.size(), 3u);
    EXPECT_EQ(st.materials[0].quantity, 5);
}

TEST(InventoryTxTest, ConsumeMaterialZeroQuantityIsTrueNoWrite) {
    GameState st = newState();
    st.materials.push_back(materialStack("m1", "兽皮", 1, 2));
    // quantity==0 → remaining==0 → 恒 true、零写入
    EXPECT_TRUE(inventory_tx::consumeMaterialByNameTx(st, "兽皮", 1, 0));
    EXPECT_EQ(st.materials[0].quantity, 2);
}

TEST(InventoryTxTest, ConsumeMaterialNegativeQuantityIsFalseNoWrite) {
    GameState st = newState();
    st.materials.push_back(materialStack("m1", "兽皮", 1, 2));
    // 负数 → 提前 break，remaining != 0 → false、零写入
    EXPECT_FALSE(inventory_tx::consumeMaterialByNameTx(st, "兽皮", 1, -1));
    EXPECT_EQ(st.materials[0].quantity, 2);
}

TEST(InventoryTxTest, ConsumeMaterialUnknownNameIsFalse) {
    GameState st = newState();
    EXPECT_FALSE(inventory_tx::consumeMaterialByNameTx(st, "不存在", 1, 1));
}

// ── 分发面（GameCore::execute 信封 + 零 RNG 审计） ────────────────

class InventoryTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<gamecore::GameCore>(&clock_, &logger_);
        gamecore::GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    gamecore::FixedClock clock_;
    gamecore::ConsoleLogger logger_;
    std::unique_ptr<gamecore::GameCore> core_;
};

TEST_F(InventoryTxFixture, DispatchSellItemSuccessEnvelope) {
    auto& st = core_->state();
    st.gameData.spiritStones = 0;
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 4));
    const auto r = exec(gamecore::action::INV_SELL_ITEM,
                        {{"itemType", "equipment"}, {"itemId", "e1"},
                         {"quantity", 2}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("sold"), true);
    EXPECT_EQ(r.at("data").at("earned").get<int64_t>(), 6400);
    EXPECT_EQ(st.gameData.spiritStones, 6400);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 2);
}

TEST_F(InventoryTxFixture, DispatchSellItemUnsoldIsSuccessZeroWrite) {
    auto& st = core_->state();
    st.gameData.spiritStones = 0;
    const auto r = exec(gamecore::action::INV_SELL_ITEM,
                        {{"itemType", "equipment"}, {"itemId", "nope"},
                         {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("sold"), false);
    EXPECT_EQ(r.at("data").at("earned").get<int64_t>(), 0);
    EXPECT_EQ(st.gameData.spiritStones, 0);
}

TEST_F(InventoryTxFixture, DispatchBulkSellEnvelope) {
    auto& st = core_->state();
    st.manualStacks.push_back(manualStack("m1", 3, 2));
    const auto r = exec(gamecore::action::INV_BULK_SELL,
                        {{"operations",
                          nlohmann::json::array({{{"id", "m1"},
                                                  {"name", "测试功法"},
                                                  {"itemType", "manual"},
                                                  {"quantity", 2}}})}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("soldCount").get<int32_t>(), 1);
    EXPECT_EQ(r.at("data").at("totalEarned").get<int64_t>(), 128000);
    ASSERT_EQ(r.at("data").at("soldItemNames").size(), 1u);
    EXPECT_EQ(r.at("data").at("soldItemNames")[0], "测试功法 2");
    EXPECT_TRUE(r.at("data").at("failedItemNames").empty());
    EXPECT_TRUE(st.manualStacks.empty());
}

TEST_F(InventoryTxFixture, DispatchMerchantSellAndListAndRemove) {
    auto& st = core_->state();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));

    const auto sell = exec(gamecore::action::MERCHANT_SELL_ACQUISITION,
                           {{"acquisitionItemId", "a1"}, {"quantity", 2}});
    ASSERT_EQ(sell.at("status"), "success");
    EXPECT_EQ(sell.at("data").at("soldQuantity").get<int32_t>(), 2);
    EXPECT_EQ(sell.at("data").at("totalPrice").get<int64_t>(), 1000);

    const auto list = exec(gamecore::action::MERCHANT_LIST_ITEMS,
                           {{"items", nlohmann::json::array(
                                          {{{"itemId", "e1"}, {"quantity", 1}}})}});
    ASSERT_EQ(list.at("status"), "success");
    EXPECT_EQ(list.at("data").at("listedCount").get<int32_t>(), 1);
    ASSERT_EQ(st.gameData.playerListedItems.size(), 1u);

    const std::string listedId = st.gameData.playerListedItems[0].id;
    const auto removed = exec(gamecore::action::MERCHANT_REMOVE_LISTED,
                              {{"itemId", listedId}});
    ASSERT_EQ(removed.at("status"), "success");
    EXPECT_TRUE(st.gameData.playerListedItems.empty());
}

TEST_F(InventoryTxFixture, DispatchFailureEnvelopeZeroWrite) {
    auto& st = core_->state();
    st.gameData.spiritStones = 0;
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    const auto r = exec(gamecore::action::MERCHANT_SELL_ACQUISITION,
                        {{"acquisitionItemId", "a1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "NotFound");
    EXPECT_EQ(st.equipmentStacks[0].quantity, 5);
    EXPECT_EQ(st.gameData.spiritStones, 0);
}

TEST_F(InventoryTxFixture, DispatchInventoryTxConsumesZeroRng) {
    // 全分区 RNG 快照差分：事务前后逐分区状态不变（抽取集为空集）
    const auto before = core_->rng().exportStates();
    auto& st = core_->state();
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
    st.manualStacks.push_back(manualStack("m1", 3, 2));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "精铁剑", "equipment", 500, 10));

    ASSERT_EQ(exec(gamecore::action::INV_SELL_ITEM,
                   {{"itemType", "equipment"}, {"itemId", "e1"}, {"quantity", 1}})
                  .at("status"),
              "success");
    ASSERT_EQ(exec(gamecore::action::INV_BULK_SELL,
                   {{"operations", nlohmann::json::array({{{"id", "m1"},
                                                           {"name", "测试功法"},
                                                           {"itemType", "manual"},
                                                           {"quantity", 1}}})}})
                  .at("status"),
              "success");
    ASSERT_EQ(exec(gamecore::action::MERCHANT_SELL_ACQUISITION,
                   {{"acquisitionItemId", "a1"}, {"quantity", 1}})
                  .at("status"),
              "success");
    ASSERT_EQ(exec(gamecore::action::MERCHANT_LIST_ITEMS,
                   {{"items", nlohmann::json::array(
                                  {{{"itemId", "e1"}, {"quantity", 1}}})}})
                  .at("status"),
              "success");
    EXPECT_EQ(core_->rng().exportStates(), before);
}

TEST_F(InventoryTxFixture, DispatchConsumeMaterialEnvelopeAndZeroRng) {
    const auto before = core_->rng().exportStates();
    auto& st = core_->state();
    st.materials.push_back(materialStack("m1", "兽皮", 1, 3));
    const auto r = exec(gamecore::action::INV_CONSUME_MATERIAL,
                        {{"name", "兽皮"}, {"rarity", 1}, {"quantity", 3}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("consumed"), true);
    EXPECT_TRUE(st.materials.empty());
    EXPECT_EQ(core_->rng().exportStates(), before);
}

TEST_F(InventoryTxFixture, TwiceRunsProduceIdenticalExportedState) {
    // 双运行逐位一致（确定性证据之二）：同一初始状态跑同一事务序列，
    // 两次导出的全状态 JSON 逐字节相同（含确定性占位 id）
    gamecore::FixedClock clock2;
    gamecore::ConsoleLogger logger2;
    gamecore::GameCore other(&clock2, &logger2);
    gamecore::GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = 42;
    other.initialize(config);

    auto runOnce = [](gamecore::GameCore& core) {
        auto& st = core.state();
        st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 5));
        st.pills.push_back(pill("p1", 1, "HIGH", 2));
        st.gameData.merchantAcquisitionItems.push_back(
            acquisitionItem("a1", "精铁剑", "equipment", 500, 10));
        core.execute(gamecore::action::INV_SELL_ITEM,
                     R"({"itemType":"equipment","itemId":"e1","quantity":2})", 0);
        core.execute(gamecore::action::MERCHANT_SELL_ACQUISITION,
                     R"({"acquisitionItemId":"a1","quantity":3})", 0);
        core.execute(gamecore::action::MERCHANT_LIST_ITEMS,
                     R"({"items":[{"itemId":"p1","quantity":2}]})", 0);
        core.execute(gamecore::action::MERCHANT_REMOVE_LISTED,
                     R"({"itemId":"missing"})", 0);
    };

    runOnce(*core_);
    runOnce(other);
    EXPECT_EQ(core_->exportStateJson(), other.exportStateJson());
}

// ══ batch-11 库存收官：商人购买 / 充公（黄金用例）════════════════

/// 弟子行种子（id 为数字串——Kotlin Int id 协议形态）
gamecore::state::Disciple seedDisciple(const std::string& id) {
    gamecore::state::Disciple d;
    d.id = id;
    d.name = "测试弟子";
    d.isAlive = true;
    d.realm = 1;
    return d;
}

/// 堆叠类储物袋条目（丹药——真实模板：聚气丹 breakthrough_9_medium）
gamecore::state::StorageBagItem stackedBagItem(const std::string& itemId,
                                               int32_t quantity) {
    gamecore::state::StorageBagItem e;
    e.itemId = itemId;
    e.itemType = "pill";
    e.name = "聚气丹";
    e.rarity = 1;
    e.quantity = quantity;
    gamecore::state::BagStackedData sd;
    sd.minRealm = 9;
    e.stackedData = sd;
    return e;
}

/// 商人商品（装备类——真实模板：精铁剑）
MerchantItem merchantEquipment(const std::string& id, int64_t price,
                               int32_t quantity, int32_t rarity = 1) {
    MerchantItem item;
    item.id = id;
    item.name = "精铁剑";
    item.type = "equipment";
    item.rarity = rarity;
    item.price = price;
    item.quantity = quantity;
    return item;
}

TEST_F(InventoryTxFixture, BuyMerchantItemHappyPathExactChargeAndStock) {
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 2}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("bought"), true);
    EXPECT_EQ(r.at("data").at("itemName"), "精铁剑");
    EXPECT_EQ(r.at("data").at("itemType"), "equipment");
    EXPECT_EQ(r.at("data").at("rarity"), 1);
    // 灵石精确扣减：5000 - 1000×2 = 3000
    EXPECT_EQ(st.gameData.spiritStones, 3000);
    // 先加物品（新堆叠 quantity=2），后扣费
    ASSERT_EQ(st.equipmentStacks.size(), 1u);
    EXPECT_EQ(st.equipmentStacks[0].quantity, 2);
    EXPECT_EQ(st.equipmentStacks[0].name, "精铁剑");
    // 商家库存回写：5 - 2 = 3
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 3);
    // 年度报告：addXxx 按 "merchant:<rarity>" 记来源；扣费按 Purchase 记支出
    EXPECT_EQ(st.gameData.annualEquipmentBySource["merchant:1"], 2);
    EXPECT_EQ(st.gameData.annualExpenditureByReason["Purchase"], 2000);
}

TEST_F(InventoryTxFixture, BuyMerchantItemNotFoundZeroWrite) {
    auto& st = core_->state();
    st.gameData.spiritStones = 1000;
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "missing"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "NotFound");
    EXPECT_EQ(st.gameData.spiritStones, 1000);
    EXPECT_TRUE(st.equipmentStacks.empty());
}

TEST_F(InventoryTxFixture, BuyMerchantItemTamperedPriceZeroWrite) {
    // D-21 存档完整性防御：商人商品价格本应恒正，0/负价拒绝购买
    auto& st = core_->state();
    st.gameData.spiritStones = 1000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 0, 5));
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "InvalidParam");
    EXPECT_EQ(st.gameData.spiritStones, 1000);
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 5);
}

TEST_F(InventoryTxFixture, BuyMerchantItemInsufficientZeroWrite) {
    auto& st = core_->state();
    st.gameData.spiritStones = 999;  // < cost（1000×1）
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "Insufficient");
    EXPECT_EQ(st.gameData.spiritStones, 999);
    EXPECT_TRUE(st.equipmentStacks.empty());
    // 库存不足臂：quantity > merchantItem.quantity（同臂拒绝）
    const auto r2 = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                         {{"itemId", "bi1"}, {"quantity", 6}});
    ASSERT_EQ(r2.at("status"), "failure");
    EXPECT_EQ(r2.at("code"), "Insufficient");
    EXPECT_EQ(st.gameData.spiritStones, 999);
}

TEST_F(InventoryTxFixture, BuyMerchantItemTemplateMissFallsBack) {
    // 模板缺失（篡改档）：Kotlin 回退臂走 JVM Random.Default 不可复刻 →
    // C++ TemplateMiss failure 信封回退，零写入
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    MerchantItem tampered = merchantEquipment("bi1", 1000, 5);
    tampered.name = "不存在模板";
    st.gameData.travelingMerchantItems.push_back(tampered);
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "TemplateMiss");
    EXPECT_EQ(st.gameData.spiritStones, 5000);
    EXPECT_TRUE(st.equipmentStacks.empty());
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 5);
}

TEST_F(InventoryTxFixture, BuyMerchantItemCapacityFullZeroWrite) {
    // 按型槽位预算：同键堆叠无余量 + 总槽位满 → 容量拒绝（Kotlin
    // canAddEquipment = totalFree>0 || canAddItem 的双重条件）
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    // 49 个非装备堆叠（丹药，槽位各 1）+ 1 个满装匹配堆叠 = 50 槽满
    for (int i = 0; i < 49; ++i) {
        st.pills.push_back(pill("p" + std::to_string(i), 1, "HIGH", 1));
        st.pills.back().name = "占位丹" + std::to_string(i);
    }
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 999));
    st.equipmentStacks[0].slot = "WEAPON";  // 合并键与模板一致（name|rarity|slot）
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "CapacityFull");
    EXPECT_EQ(st.gameData.spiritStones, 5000);
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 5);
    EXPECT_EQ(st.equipmentStacks.size(), 1u);
}

TEST_F(InventoryTxFixture, BuyMerchantItemAddFailedValidationRarityZeroWrite) {
    // AddFailed 臂（确定性构造）：模板命中但篡改 rarity=0 → addXxx 校验链
    // InvalidRarity 失败，零写入（容量预测对 rarity=0 放行，写入段兜底）
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5, /*rarity=*/0));
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "AddFailed");
    EXPECT_EQ(st.gameData.spiritStones, 5000);
    EXPECT_TRUE(st.equipmentStacks.empty());
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 5);
}

TEST_F(InventoryTxFixture, BuyMerchantItemPartialOverflowToDrafts) {
    // 溢出转草稿：合并装满同键堆叠后无空槽 → Partial（溢出 3 转邮件草稿）
    // 语义升级：Partial 视为成功——照常扣费 + 商家库存扣减
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    for (int i = 0; i < 49; ++i) {
        st.pills.push_back(pill("p" + std::to_string(i), 1, "HIGH", 1));
        st.pills.back().name = "占位丹" + std::to_string(i);
    }
    st.equipmentStacks.push_back(equipmentStack("e1", "精铁剑", 1, 997));
    st.equipmentStacks[0].slot = "WEAPON";  // 同键合并（name|rarity|slot）
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 5}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("bought"), true);
    // 合并 2 入既有堆叠，溢出 3 → 草稿
    EXPECT_EQ(st.equipmentStacks[0].quantity, 999);
    EXPECT_EQ(st.gameData.spiritStones, 0);  // 5000 - 5000
    // quantity(5) >= stock(5) → 条目整条移除（Kotlin reduceMerchantStock 同语义）
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());
    // 年报只记实际入仓部分
    EXPECT_EQ(st.gameData.annualEquipmentBySource["merchant:1"], 2);
    const auto& drafts = r.at("data").at("overflowDrafts");
    ASSERT_EQ(drafts.size(), 1u);
    EXPECT_EQ(drafts[0].at("itemType"), "equipment");
    EXPECT_EQ(drafts[0].at("quantity"), 3);
    EXPECT_EQ(drafts[0].at("source"), "merchant");
}

TEST_F(InventoryTxFixture, BuyMerchantItemSpiritStoneGrantMidGrade) {
    // 灵石商品：中品灵石直加余额 + 下品扣费 + 商家库存扣减
    auto& st = core_->state();
    st.gameData.spiritStones = 1000;
    MerchantItem mid;
    mid.id = "bi2";
    mid.name = "中品灵石";
    mid.type = "spiritstone";
    mid.rarity = 3;
    mid.price = 500;
    mid.quantity = 2;
    st.gameData.travelingMerchantItems.push_back(mid);
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi2"}, {"quantity", 2}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("bought"), true);
    EXPECT_EQ(st.gameData.midGradeSpiritStones, 2);
    EXPECT_EQ(st.gameData.spiritStones, 0);  // 1000 - 500×2
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());  // 2 >= 2 移除
}

TEST_F(InventoryTxFixture, DispatchBuyAndConfiscateConsumeZeroRng) {
    // 零 RNG 全分区快照差分：购买 + 充公（实例/堆叠两轨）事务前后
    // 逐分区状态不变（抽取集为空集）
    const auto before = core_->rng().exportStates();
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    st.disciples.appendDisciple(seedDisciple("1"));
    st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 3));
    ASSERT_EQ(exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                   {{"itemId", "bi1"}, {"quantity", 2}})
                  .at("status"),
              "success");
    ASSERT_EQ(exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                   {{"discipleId", "1"}, {"itemId", "bk1"}})
                  .at("status"),
              "success");
    EXPECT_EQ(core_->rng().exportStates(), before);
}

TEST_F(InventoryTxFixture, ConfiscateStackedItemHappyDecrementsBag) {
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 3));
    const auto r = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                        {{"discipleId", "1"}, {"itemId", "bk1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("confiscated"), true);
    // 模板重建（聚气丹——itemId 非模板 id，按名首中 breakthrough_9_low）
    // 入仓 quantity=1，袋内减 1
    ASSERT_EQ(st.pills.size(), 1u);
    EXPECT_EQ(st.pills[0].name, "聚气丹");
    EXPECT_EQ(st.pills[0].grade, "LOW");
    EXPECT_EQ(st.pills[0].quantity, 1);
    EXPECT_EQ(st.disciples.storageBagItems[0][0].quantity, 2);
    // 年报：addPill 按 "confiscate:<grade>" 记来源（堆叠路径走 addXxx）
    EXPECT_EQ(st.gameData.annualPillBySource["confiscate:LOW"], 1);
}

TEST_F(InventoryTxFixture, ConfiscateEquipmentInstanceBareReturnNoAnnual) {
    // 实例条目：裸 store.add 回仓（Kotlin returnEquipmentToStack 语义——
    // 不记年报）+ 袋条目整条移除 + 实例表不动
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    gamecore::state::StorageBagItem entry;
    entry.itemId = "bi1";
    entry.itemType = "equipment_instance";
    entry.name = "精铁剑";
    entry.rarity = 2;
    entry.quantity = 1;
    gamecore::state::EquipmentInstance inst;
    inst.id = "inst-1";
    inst.name = "精铁剑";
    inst.rarity = 2;
    inst.slot = "WEAPON";
    inst.physicalAttack = 33;
    entry.equipmentInstance = inst;
    st.disciples.storageBagItems[0].push_back(entry);
    const auto r = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                        {{"discipleId", "1"}, {"itemId", "bi1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("confiscated"), true);
    ASSERT_EQ(st.equipmentStacks.size(), 1u);
    EXPECT_EQ(st.equipmentStacks[0].name, "精铁剑");
    EXPECT_EQ(st.equipmentStacks[0].quantity, 1);
    EXPECT_EQ(st.equipmentStacks[0].physicalAttack, 33);
    // 裸回仓：无年报写入（Kotlin 充公实例路径无 annual 段）
    EXPECT_TRUE(st.gameData.annualEquipmentBySource.empty());
    // 袋条目整条移除；仓库实例表不动（袋实例不入实例表）
    EXPECT_TRUE(st.disciples.storageBagItems[0].empty());
    EXPECT_TRUE(st.equipmentInstances.empty());
}

TEST_F(InventoryTxFixture, ConfiscateIdempotentStaleSnapshotNoDuplication) {
    // 幂等防线：以袋内当前条目为准——陈旧快照（已无匹配）不复制
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 1));
    ASSERT_EQ(exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                   {{"discipleId", "1"}, {"itemId", "bk1"}})
                  .at("status"),
              "success");
    ASSERT_EQ(st.pills.size(), 1u);
    // 二次没收（陈旧快照）：袋内已无匹配 → 静默 no-op
    const auto r2 = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                         {{"discipleId", "1"}, {"itemId", "bk1"}});
    ASSERT_EQ(r2.at("status"), "success");
    EXPECT_EQ(r2.at("data").at("confiscated"), false);
    ASSERT_EQ(st.pills.size(), 1u);  // 无复制
    // 弟子不存在：静默 no-op
    const auto r3 = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                         {{"discipleId", "999"}, {"itemId", "bk1"}});
    ASSERT_EQ(r3.at("status"), "success");
    EXPECT_EQ(r3.at("data").at("confiscated"), false);
}

TEST_F(InventoryTxFixture, ConfiscateTemplateMissingKeepsBagEntry) {
    // 模板缺失：堆叠条目无法重建 → 丢弃处理（保留袋条目，玩家可重试）
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    gamecore::state::StorageBagItem ghost = stackedBagItem("bk1", 1);
    ghost.name = "不存在模板丹";
    st.disciples.storageBagItems[0].push_back(ghost);
    const auto r = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                        {{"discipleId", "1"}, {"itemId", "bk1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("confiscated"), false);
    EXPECT_TRUE(st.pills.empty());
    ASSERT_EQ(st.disciples.storageBagItems[0].size(), 1u);  // 袋条目保留
}

TEST_F(InventoryTxFixture, ConfiscateInvalidQuantityRejected) {
    // 堆叠条目篡改防御：数量 <=0 且无实例 → 拒绝物化（防 0 数量白得）
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 0));
    const auto r = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                        {{"discipleId", "1"}, {"itemId", "bk1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("confiscated"), false);
    EXPECT_TRUE(st.pills.empty());
    ASSERT_EQ(st.disciples.storageBagItems[0].size(), 1u);
}

TEST_F(InventoryTxFixture, ConfiscateWarehouseFullKeepsBagEntryForRetry) {
    // 凭据类语义：仓库满（Failure Full，溢出抑制无邮件）→ 保留袋条目待
    // 重试（C1 防复制）。入仓量恒 1 → reachable 非成功臂为 Failure
    //（Partial 需 quantity>1 才可达，Kotlin 充堆叠 copy(quantity=1) 同理）
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 3));
    // 49 个非丹药堆叠 + 1 个满装同键丹药堆叠 = 50 槽满、同键无余量
    for (int i = 0; i < 49; ++i) {
        st.materials.push_back(Material());
        st.materials.back().id = "m" + std::to_string(i);
        st.materials.back().name = "占位材料" + std::to_string(i);
        st.materials.back().rarity = 1;
        st.materials.back().quantity = 1;
    }
    st.pills.push_back(pill("p0", 1, "LOW", 999));
    st.pills[0].name = "聚气丹";
    st.pills[0].category = "CULTIVATION";
    const auto r = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                        {{"discipleId", "1"}, {"itemId", "bk1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("confiscated"), false);
    // 满仓零写入：堆叠不动，袋条目保留待重试
    EXPECT_EQ(st.pills[0].quantity, 999);
    ASSERT_EQ(st.disciples.storageBagItems[0].size(), 1u);
    EXPECT_EQ(st.disciples.storageBagItems[0][0].quantity, 3);
}

TEST_F(InventoryTxFixture, TwiceRunsConfiscateAndBuyProduceIdenticalState) {
    // 双运行逐位一致（确定性证据）：同一初始状态跑同一购买+充公序列，
    // 两次导出的全状态 JSON 逐字节相同（含确定性占位 id）
    gamecore::FixedClock clock2;
    gamecore::ConsoleLogger logger2;
    gamecore::GameCore other(&clock2, &logger2);
    gamecore::GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = 42;
    other.initialize(config);

    auto runOnce = [](gamecore::GameCore& core) {
        // 进程级 id 计数器清零（等价重导入 reseed 语义——计数器不随种子
        // 协议走，两次运行各从 1 起号保证全状态逐位一致）
        gamecore::system::itemIdCounterRegistry().clear();
        auto& st = core.state();
        st.gameData.spiritStones = 5000;
        st.gameData.travelingMerchantItems.push_back(
            merchantEquipment("bi1", 1000, 5));
        st.disciples.appendDisciple(seedDisciple("1"));
        st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 3));
        core.execute(gamecore::action::INV_BUY_MERCHANT_ITEM,
                     R"({"itemId":"bi1","quantity":2})", 0);
        core.execute(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                     R"({"discipleId":"1","itemId":"bk1"})", 0);
    };

    runOnce(*core_);
    runOnce(other);
    EXPECT_EQ(core_->exportStateJson(), other.exportStateJson());
}

}  // namespace

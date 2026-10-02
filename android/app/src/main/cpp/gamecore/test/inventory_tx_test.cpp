// ============================================================
// inventory_tx_test.cpp — 库存出售/上架事务黄金用例（W2-a 下沉）
//
// 守护目标：sellItemTx / bulkSellTx / sellToMerchantTx /
// listItemsToMerchantTx / removePlayerListedItemTx 的校验链判定序与
// 取价公式（与 Kotlin InventoryFacadeImpl 逐字对齐）、灵石精确入账、
// 失败零写入、零 RNG 审计（签名级 + 分发面全分区快照差分）。
//
// 取价黄金值（Kotlin GameConfig.Rarity + 部件表同源；B3 装备=实例轨）：
//   装备实例：模板价（setId 部件表 priceByRarity）优先 → 品阶基准价回退；
//             1 件 = 1 条目整件出售（quantity 协议占位）
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

using gamecore::state::EquipmentInstance;
using gamecore::state::GameState;
using gamecore::state::Herb;
using gamecore::state::ManualStack;
using gamecore::state::Material;
using gamecore::state::MerchantItem;
using gamecore::state::Pill;
using gamecore::state::Seed;
namespace inventory_tx = gamecore::system::inventory_tx;

EquipmentInstance equipmentInstance(const std::string& id,
                                    const std::string& name, int32_t rarity,
                                    const std::string& part = "HANDS",
                                    const std::string& setId = "") {
    EquipmentInstance e;
    e.id = id;
    e.name = name;
    e.part = part;
    e.setId = setId;
    e.meta.rarity = rarity;
    return e;
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
    // B3 实例轨：整件出售（quantity 协议占位）；模板价 = 部件表 priceByRarity
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    const auto r = inventory_tx::sellItemTx(st, "equipment", "e1", 1);
    ASSERT_TRUE(r.ok);
    // 部件表品阶 1 价 4000 × 1 × 0.8 = 3200
    EXPECT_EQ(r.earned, 3200);
    EXPECT_EQ(st.gameData.spiritStones, 3200);
    // 实例整条移除
    EXPECT_TRUE(st.equipmentInstances.empty());
    // 年度报告来源键 = Kotlin SpiritStoneSource.Sell("equipment")
    EXPECT_EQ(st.gameData.annualIncomeBySource["Sell(equipment)"], 3200);
}

TEST(InventoryTxTest, SellEquipmentFallsBackToRarityBasePrice) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "未知装备", 2));
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
    // B3 实例轨守卫臂：锁定/不存在/未知类型 → 0 且零写入
    //（quantity 协议占位后，"数量 0/超持有"守卫臂不再适用——恒整件出售）
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances[0].meta.isLocked = true;
    // 锁定
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 1).earned, 0);
    // 不存在
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "nope", 1).earned, 0);
    // 未知类型
    EXPECT_EQ(inventory_tx::sellItemTx(st, "unknown", "e1", 1).earned, 0);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_TRUE(st.gameData.annualIncomeBySource.empty());
}

TEST(InventoryTxTest, SellUnlocksOnlyAfterLockCleared) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances[0].meta.isLocked = true;
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 1).earned, 0);
    st.equipmentInstances[0].meta.isLocked = false;
    EXPECT_EQ(inventory_tx::sellItemTx(st, "equipment", "e1", 1).earned, 3200);
    EXPECT_TRUE(st.equipmentInstances.empty());
}

// ── 批量出售 ────────────────────────────────────────────────────

TEST(InventoryTxTest, BulkSellAggregatesAndCreditsOnce) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.herbs.push_back(Herb{.id = "h1", .category = ""});
    st.herbs[0].name = "测试草药";
    st.herbs[0].rarity = 1;
    st.herbs[0].quantity = 2;
    st.herbs[0].isLocked = true;  // 锁定 → 失败项

    std::vector<inventory_tx::BulkSellRequest> ops = {
        {.id = "e1", .name = "裂天罡煞·战手", .itemType = "equipment", .quantity = 1},
        {.id = "h1", .name = "测试草药", .itemType = "herb", .quantity = 1},
        {.id = "nope", .name = "幽灵", .itemType = "pill", .quantity = 1},
    };
    const auto r = inventory_tx::bulkSellTx(st, ops);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldCount, 1);
    EXPECT_EQ(r.totalEarned, 3200);
    ASSERT_EQ(r.soldItemNames.size(), 1u);
    EXPECT_EQ(r.soldItemNames[0], "裂天罡煞·战手 1");
    ASSERT_EQ(r.failedItemNames.size(), 2u);
    EXPECT_EQ(r.failedItemNames[0], "测试草药");
    EXPECT_EQ(r.failedItemNames[1], "幽灵");
    // 一次入账（来源 Sell(bulk)），非逐条
    EXPECT_EQ(st.gameData.spiritStones, 3200);
    EXPECT_EQ(st.gameData.annualIncomeBySource["Sell(bulk)"], 3200);
    EXPECT_EQ(st.gameData.annualIncomeBySource.count("Sell(equipment)"), 0u);
    EXPECT_TRUE(st.equipmentInstances.empty());
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
    // B3 实例轨：收购需求 itemId 是模板锚而非实例 id——扣减按名称+品阶匹配
    // 未锁定实例逐件移除（与 warehouseCount 装备臂同谓词；锁定件保留）。
    // 旧「按 itemId 扣减」是复制 bug（同 id 至多 1 条，付 N 件款只扣 1 件）。
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances.push_back(
        equipmentInstance("e2", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances.push_back(
        equipmentInstance("e3", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances[2].meta.isLocked = true;
    MerchantItem acq = acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10);
    st.gameData.merchantAcquisitionItems.push_back(acq);

    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 3);
    ASSERT_TRUE(r.ok);
    // 可售计数排除锁定件 → 请求 3 钳到 2
    EXPECT_EQ(r.soldQuantity, 2);
    EXPECT_EQ(r.totalPrice, 1000);
    EXPECT_EQ(st.gameData.spiritStones, 1000);
    EXPECT_EQ(st.gameData.annualIncomeBySource["MerchantTrade"], 1000);
    // 扣减按名称+品阶：e1/e2 移除，锁定件 e3 保留
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_EQ(st.equipmentInstances[0].id, "e3");
    // 收购项数量回写
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 8);
}

TEST(InventoryTxTest, SellToMerchantClampsToWarehouseQuantity) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    MerchantItem acq = acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10);
    acq.itemId = "e1";
    st.gameData.merchantAcquisitionItems.push_back(acq);
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 5);
    ASSERT_TRUE(r.ok);
    // Kotlin actualQuantity = min(请求, 仓库, 收购上限)
    EXPECT_EQ(r.soldQuantity, 1);
    EXPECT_EQ(r.totalPrice, 500);
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 9);
}

TEST(InventoryTxTest, SellToMerchantZeroWarehouseIsSuccessNoop) {
    GameState st = newState();
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10));
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 2);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldQuantity, 0);
    EXPECT_EQ(r.totalPrice, 0);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 10);
}

TEST(InventoryTxTest, SellToMerchantRejectsInvalidRequests) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10));
    st.gameData.merchantAcquisitionItems.push_back(
        acquisitionItem("a2", "裂天罡煞·战手", "equipment", 0, 10));  // 篡改档 0 价
    // 不存在
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "nope", 1).ok);
    // 数量 0
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "a1", 0).ok);
    // 数量超收购上限
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "a1", 11).ok);
    // 价格非正（D-21 存档完整性防御）
    EXPECT_FALSE(inventory_tx::sellToMerchantTx(st, "a2", 1).ok);
    EXPECT_EQ(st.gameData.spiritStones, 0);
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
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

TEST(InventoryTxTest, SellToMerchantSkipsLockedInWarehouse) {
    // B3：锁定件不可售——计数与扣减同谓词排除锁定（防「锁定价凭空收款」）；
    // 全锁定 → actualQuantity=0 → SUCCESS + soldQuantity=0（零写入）。
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances[0].meta.isLocked = true;
    MerchantItem acq = acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10);
    st.gameData.merchantAcquisitionItems.push_back(acq);
    const auto r = inventory_tx::sellToMerchantTx(st, "a1", 2);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.soldQuantity, 0);
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_EQ(st.equipmentInstances[0].id, "e1");
    // 零成交不回写收购项
    EXPECT_EQ(st.gameData.merchantAcquisitionItems[0].quantity, 10);
}

// ── 上架 / 撤下 ─────────────────────────────────────────────────

TEST(InventoryTxTest, ListItemsToMerchantRegistersEquipmentManualPill) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.manualStacks.push_back(manualStack("m1", 3, 4));
    st.pills.push_back(pill("p1", 1, "HIGH", 2));
    st.pills[0].name = "测试丹药";

    std::vector<inventory_tx::ListItemRequest> items = {
        {.itemId = "e1", .quantity = 1},
        {.itemId = "m1", .quantity = 1},
        {.itemId = "p1", .quantity = 2},
        {.itemId = "nope", .quantity = 1},
    };
    const auto r = inventory_tx::listItemsToMerchantTx(st, items);
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.listedCount, 3);
    ASSERT_EQ(st.gameData.playerListedItems.size(), 3u);
    // 装备：模板价 4000 × 0.8 = 3200（roundToInt）；quantity 恒 1（整件上架）
    EXPECT_EQ(st.gameData.playerListedItems[0].type, "equipment");
    EXPECT_EQ(st.gameData.playerListedItems[0].price, 3200);
    EXPECT_EQ(st.gameData.playerListedItems[0].quantity, 1);
    EXPECT_EQ(st.gameData.playerListedItems[0].itemId, "e1");
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
    // 上架不扣仓库（实例保留）
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_EQ(st.manualStacks[0].quantity, 4);
    EXPECT_EQ(st.pills[0].quantity, 2);
}

TEST(InventoryTxTest, ListItemsToMerchantSkipsOverListedQuantity) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.gameData.playerListedItems.push_back(
        acquisitionItem("a1", "裂天罡煞·战手", "equipment", 3200, 1));
    st.gameData.playerListedItems[0].itemId = "e1";
    std::vector<inventory_tx::ListItemRequest> items = {
        {.itemId = "e1", .quantity = 1},
    };
    const auto r = inventory_tx::listItemsToMerchantTx(st, items);
    ASSERT_TRUE(r.ok);
    // 实例已上架（alreadyListed>=1）→ 静默跳过（不新增，也不抛错）
    EXPECT_EQ(r.listedCount, 0);
    EXPECT_EQ(st.gameData.playerListedItems.size(), 1u);
}

TEST(InventoryTxTest, ListItemsToMerchantSkipsLockedAndInvalidQuantity) {
    GameState st = newState();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.equipmentInstances[0].meta.isLocked = true;
    std::vector<inventory_tx::ListItemRequest> items = {
        {.itemId = "e1", .quantity = 1},
    };
    // 锁定装备段返回 false → 功法/丹药段亦未命中 → 不上架
    EXPECT_EQ(inventory_tx::listItemsToMerchantTx(st, items).listedCount, 0);
    st.equipmentInstances[0].meta.isLocked = false;
    EXPECT_EQ(inventory_tx::listItemsToMerchantTx(st, items).listedCount, 1);
    EXPECT_TRUE(st.equipmentInstances[0].meta.isLocked == false);
}

TEST(InventoryTxTest, RemovePlayerListedItemFiltersById) {
    GameState st = newState();
    st.gameData.playerListedItems.push_back(
        acquisitionItem("a1", "裂天罡煞·战手", "equipment", 3200, 1));
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
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    const auto r = exec(gamecore::action::INV_SELL_ITEM,
                        {{"itemType", "equipment"}, {"itemId", "e1"},
                         {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("sold"), true);
    EXPECT_EQ(r.at("data").at("earned").get<int64_t>(), 3200);
    EXPECT_EQ(st.gameData.spiritStones, 3200);
    EXPECT_TRUE(st.equipmentInstances.empty());
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
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    MerchantItem acq = acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10);
    acq.itemId = "e1";
    st.gameData.merchantAcquisitionItems.push_back(acq);

    const auto sell = exec(gamecore::action::MERCHANT_SELL_ACQUISITION,
                           {{"acquisitionItemId", "a1"}, {"quantity", 1}});
    ASSERT_EQ(sell.at("status"), "success");
    EXPECT_EQ(sell.at("data").at("soldQuantity").get<int32_t>(), 1);
    EXPECT_EQ(sell.at("data").at("totalPrice").get<int64_t>(), 500);

    const auto list = exec(gamecore::action::MERCHANT_LIST_ITEMS,
                           {{"items", nlohmann::json::array(
                                          {{{"itemId", "e1"}, {"quantity", 1}}})}});
    // e1 已被收购移除 → 上架静默跳过（listedCount 0）；补一件再上架
    ASSERT_EQ(list.at("status"), "success");
    st.equipmentInstances.push_back(
        equipmentInstance("e2", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    const auto list2 = exec(gamecore::action::MERCHANT_LIST_ITEMS,
                            {{"items", nlohmann::json::array(
                                           {{{"itemId", "e2"}, {"quantity", 1}}})}});
    ASSERT_EQ(list2.at("status"), "success");
    EXPECT_EQ(list2.at("data").at("listedCount").get<int32_t>(), 1);
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
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    const auto r = exec(gamecore::action::MERCHANT_SELL_ACQUISITION,
                        {{"acquisitionItemId", "a1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "NotFound");
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_EQ(st.gameData.spiritStones, 0);
}

TEST_F(InventoryTxFixture, DispatchInventoryTxConsumesZeroRng) {
    // 全分区 RNG 快照差分：事务前后逐分区状态不变（抽取集为空集）
    const auto before = core_->rng().exportStates();
    auto& st = core_->state();
    st.equipmentInstances.push_back(
        equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
    st.manualStacks.push_back(manualStack("m1", 3, 2));
    MerchantItem acq = acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10);
    acq.itemId = "e1";
    st.gameData.merchantAcquisitionItems.push_back(acq);

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
    // 上架目标：m1（功法段；装备 e1 已被收购移除）
    ASSERT_EQ(exec(gamecore::action::MERCHANT_LIST_ITEMS,
                   {{"items", nlohmann::json::array(
                                  {{{"itemId", "m1"}, {"quantity", 1}}})}})
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
        st.equipmentInstances.push_back(
            equipmentInstance("e1", "裂天罡煞·战手", 1, "HANDS", "lietian"));
        st.pills.push_back(pill("p1", 1, "HIGH", 2));
        MerchantItem acq = acquisitionItem("a1", "裂天罡煞·战手", "equipment", 500, 10);
        acq.itemId = "e1";
        st.gameData.merchantAcquisitionItems.push_back(acq);
        core.execute(gamecore::action::INV_SELL_ITEM,
                     R"({"itemType":"equipment","itemId":"e1","quantity":1})", 0);
        core.execute(gamecore::action::MERCHANT_SELL_ACQUISITION,
                     R"({"acquisitionItemId":"a1","quantity":1})", 0);
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

/// 商人商品（装备类——部件表真名：裂天罡煞·头冠）
MerchantItem merchantEquipment(const std::string& id, int64_t price,
                               int32_t quantity, int32_t rarity = 1) {
    MerchantItem item;
    item.id = id;
    item.name = "裂天罡煞·头冠";
    item.type = "equipment";
    item.rarity = rarity;
    item.price = price;
    item.quantity = quantity;
    return item;
}

TEST_F(InventoryTxFixture, BuyMerchantEquipmentProducesNInstances) {
    // B3：购买商人装备 = quantity 条实例（kEquipment 分区 roll 词条）
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    const int64_t equipRngBefore =
        core_->rng().getRng(gamecore::rng::RngPartition::kEquipment).snapshot();
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 2}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("bought"), true);
    EXPECT_EQ(r.at("data").at("itemName"), "裂天罡煞·头冠");
    EXPECT_EQ(r.at("data").at("itemType"), "equipment");
    EXPECT_EQ(r.at("data").at("rarity"), 1);
    // 灵石精确扣减：5000 - 1000×2 = 3000
    EXPECT_EQ(st.gameData.spiritStones, 3000);
    // 先加物品（2 条实例），后扣费
    ASSERT_EQ(st.equipmentInstances.size(), 2u);
    for (const auto& inst : st.equipmentInstances) {
        EXPECT_EQ(inst.name, "裂天罡煞·头冠");
        EXPECT_EQ(inst.setId, "lietian");
        EXPECT_EQ(inst.part, "HEAD");
        EXPECT_EQ(inst.meta.rarity, 1);
        EXPECT_FALSE(inst.id.empty());   // 实例 id 确定性占位非空
        EXPECT_EQ(inst.growth.affix.subStats.size(), 3u);   // 3 副词条
    }
    // 商家库存回写：5 - 2 = 3
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 3);
    // kEquipment 分区消费（词条 roll）
    EXPECT_NE(core_->rng().getRng(gamecore::rng::RngPartition::kEquipment).snapshot(),
              equipRngBefore);
    // 年度报告：实例轨 addEquipmentInstance 无年度追踪
    EXPECT_TRUE(st.gameData.annualEquipmentBySource.empty());
    EXPECT_EQ(st.gameData.annualExpenditureByReason["Purchase"], 2000);
}

TEST_F(InventoryTxFixture, BuyMerchantEquipmentStockExhaustedRemovesEntry) {
    // quantity >= stock → 商家条目整条移除（Kotlin reduceMerchantStock 同语义）
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 5}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(st.equipmentInstances.size(), 5u);
    EXPECT_EQ(st.gameData.spiritStones, 0);   // 5000 - 5000
    EXPECT_TRUE(st.gameData.travelingMerchantItems.empty());
}

TEST_F(InventoryTxFixture, BuyMerchantItemNotFoundZeroWrite) {
    auto& st = core_->state();
    st.gameData.spiritStones = 1000;
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "missing"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "NotFound");
    EXPECT_EQ(st.gameData.spiritStones, 1000);
    EXPECT_TRUE(st.equipmentInstances.empty());
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
    EXPECT_TRUE(st.equipmentInstances.empty());
    // 库存不足臂：quantity > merchantItem.quantity（同臂拒绝）
    const auto r2 = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                         {{"itemId", "bi1"}, {"quantity", 6}});
    ASSERT_EQ(r2.at("status"), "failure");
    EXPECT_EQ(r2.at("code"), "Insufficient");
    EXPECT_EQ(st.gameData.spiritStones, 999);
}

TEST_F(InventoryTxFixture, BuyMerchantItemTemplateMissFallsBack) {
    // 模板缺失（篡改档）：部件表按名反查——旧模板名"精铁剑"不在部件表
    // → TemplateMiss failure 信封回退，零写入
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    MerchantItem tampered = merchantEquipment("bi1", 1000, 5);
    tampered.name = "精铁剑";
    st.gameData.travelingMerchantItems.push_back(tampered);
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "TemplateMiss");
    EXPECT_EQ(st.gameData.spiritStones, 5000);
    EXPECT_TRUE(st.equipmentInstances.empty());
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 5);
}

TEST_F(InventoryTxFixture, BuyMerchantItemCapacityFullZeroWrite) {
    // B3 实例轨容量谓词 = 纯槽位检查（canAddEquipment == canAddItem）
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5));
    // 50 个非装备堆叠（丹药，槽位各 1）= 50 槽满
    for (int i = 0; i < 50; ++i) {
        st.pills.push_back(pill("p" + std::to_string(i), 1, "HIGH", 1));
        st.pills.back().name = "占位丹" + std::to_string(i);
    }
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "CapacityFull");
    EXPECT_EQ(st.gameData.spiritStones, 5000);
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 5);
    EXPECT_TRUE(st.equipmentInstances.empty());
}

TEST_F(InventoryTxFixture, BuyMerchantEquipmentClampsRarityFloorToOne) {
    // B3 clampRarity 收敛面：篡改档 rarity=0 → 品阶下限 1 产出（非 AddFailed）
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    st.gameData.travelingMerchantItems.push_back(
        merchantEquipment("bi1", 1000, 5, /*rarity=*/0));
    const auto r = exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                        {{"itemId", "bi1"}, {"quantity", 1}});
    ASSERT_EQ(r.at("status"), "success");
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_EQ(st.equipmentInstances[0].meta.rarity, 1);
    EXPECT_EQ(st.gameData.travelingMerchantItems[0].quantity, 4);
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
    // 零 RNG 全分区快照差分：购买（灵石商品——无词条 roll 面）+ 充公事务
    // 前后逐分区状态不变。装备购买走 kEquipment roll（见
    // BuyMerchantEquipmentProducesNInstances），不入本审计。
    const auto before = core_->rng().exportStates();
    auto& st = core_->state();
    st.gameData.spiritStones = 5000;
    MerchantItem mid;
    mid.id = "bi2";
    mid.name = "中品灵石";
    mid.type = "spiritstone";
    mid.rarity = 3;
    mid.price = 500;
    mid.quantity = 2;
    st.gameData.travelingMerchantItems.push_back(mid);
    st.disciples.appendDisciple(seedDisciple("1"));
    st.disciples.storageBagItems[0].push_back(stackedBagItem("bk1", 3));
    ASSERT_EQ(exec(gamecore::action::INV_BUY_MERCHANT_ITEM,
                   {{"itemId", "bi2"}, {"quantity", 2}})
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
    // 实例条目：裸实例入库（addEquipmentInstance 校验面——不记年报）+
    // 袋条目整条移除 + 实例表入表
    auto& st = core_->state();
    st.disciples.appendDisciple(seedDisciple("1"));
    gamecore::state::StorageBagItem entry;
    entry.itemId = "bi1";
    entry.itemType = "equipment_instance";
    entry.name = "裂天罡煞·战手";
    entry.rarity = 2;
    entry.quantity = 1;
    gamecore::state::EquipmentInstance inst;
    inst.id = "inst-1";
    inst.name = "裂天罡煞·战手";
    inst.part = "HANDS";
    inst.setId = "lietian";
    inst.meta.rarity = 2;
    entry.equipmentInstance = inst;
    st.disciples.storageBagItems[0].push_back(entry);
    const auto r = exec(gamecore::action::INV_CONFISCATE_BAG_ITEM,
                        {{"discipleId", "1"}, {"itemId", "bi1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("confiscated"), true);
    ASSERT_EQ(st.equipmentInstances.size(), 1u);
    EXPECT_EQ(st.equipmentInstances[0].id, "inst-1");
    EXPECT_EQ(st.equipmentInstances[0].name, "裂天罡煞·战手");
    // 裸入库：无年报写入（Kotlin 充公实例路径无 annual 段）
    EXPECT_TRUE(st.gameData.annualEquipmentBySource.empty());
    // 袋条目整条移除
    EXPECT_TRUE(st.disciples.storageBagItems[0].empty());
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
    // 两次导出的全状态 JSON 逐字节相同（含确定性占位 id + kEquipment roll 序）
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

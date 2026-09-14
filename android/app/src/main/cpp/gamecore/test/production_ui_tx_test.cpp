// ============================================================
// production_ui_tx_test.cpp — 生产 UI 面 + 灵田种植族事务黄金用例（batch-17）
//
// 守护目标：production.h `ui_tx`（生产槽任命/卸任/自动续炼翻转/惰性建槽）
// 与 spirit_field.h `spirit_field_tx`（灵田单/批播种、单/批移除）的
// 校验链判定序（与 Kotlin BuildingFacadeImpl 逐字对齐）、失败零写入、
// 零 RNG（全分区快照差分）、以及 execute_dispatch 段接线。
//
// Kotlin 语义权威 = BuildingFacadeImpl（生产子集）/ BuildingFacadeImpl同步Ops
// （plantFields 批量播种）、ProductionSlot.remainingTime（卸任剩余时长归一）、
// DiscipleSlotCleanup.clearAllSlotsDataOnly（任命全槽位清理）。
//
// RNG 审计（对拍命门，同 exploration_tx_test 方法）：本批全族**零抽取**——
// 事务签名不接 RngManager；测试另以 GameCore 全链路（execute → dispatch）
// 对照 exportStates() 全分区快照断言，确保 dispatch 层不引入隐式抽取。
// ============================================================
#include <gtest/gtest.h>

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "gamecore/action_ids.h"
#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/production.h"
#include "gamecore/system/spirit_field.h"

namespace {

using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::Herb;
using gamecore::state::LibrarySlot;
using gamecore::state::ProductionSlot;
using gamecore::state::Seed;
using gamecore::state::SpiritFieldPlant;
using gamecore::state::SpiritMineSlot;
using gamecore::rng::RngManager;
namespace prod = gamecore::system::production;
namespace sfx = gamecore::system::spirit_field_tx;
namespace action = gamecore::action;
using gamecore::GameCore;
using gamecore::GameCoreConfig;

// ── 构造器 ────────────────────────────────────────────────────

/// 最小存活弟子
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 20;
    d.lifespan = 80;
    d.status = "IDLE";
    return d;
}

/// 生产槽（可指定状态/占用者/时间锚）
ProductionSlot slotOf(int32_t index, const std::string& buildingType,
                      const std::string& occupant = "") {
    ProductionSlot s;
    s.id = "slot-" + buildingType + std::to_string(index);
    s.slotIndex = index;
    s.buildingType = buildingType;
    s.buildingId = buildingType == "ALCHEMY" ? "alchemy" : "forge";
    s.status = "IDLE";
    s.completionPhase = 1;
    if (!occupant.empty()) {
        s.assignedDiscipleId = occupant;
        s.assignedDiscipleName = "弟子" + occupant;
    }
    return s;
}

/// 灵田地块
SpiritFieldPlant field(const std::string& instanceId, const std::string& sectId = "") {
    SpiritFieldPlant p;
    p.buildingInstanceId = instanceId;
    p.sectId = sectId;
    p.completionPhase = 1;
    return p;
}

/// 种子条目（默认 3 颗、未锁定）
Seed seedOf(const std::string& id, int32_t quantity = 3, bool locked = false) {
    Seed s;
    s.id = id;
    s.name = "灵芝种子";
    s.rarity = 2;
    s.growTime = 4;
    s.yield = 2;
    s.quantity = quantity;
    s.isLocked = locked;
    return s;
}

/// 空状态（时间锚 (3,5)——绝对月 41）
GameState emptyState() {
    GameState st;
    st.gameData.gameYear = 3;
    st.gameData.gameMonth = 5;
    st.gameData.activeSectId = "sect_player";
    return st;
}

/// 分区状态快照（RNG 审计用）
std::map<int32_t, int64_t> rngSnapshot(const RngManager& rng) {
    return rng.exportStates();
}

/// 已初始化 GameCore（种子固定——dispatch 全链路用例）
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

// ── assignProductionSlotTx：校验链与零写入 ────────────────────

TEST(ProductionUiTxTest, AssignRejectsMissingSlotWithZeroWrite) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY"));
    st.disciples.appendDisciple(baseDisciple("1"));
    RngManager rng;
    rng.initSystemSeed(7);
    const auto before = rngSnapshot(rng);
    const auto slotCountBefore = st.gameData.productionSlots.size();

    const auto r = prod::assignProductionSlotTx(st, "ALCHEMY", 9, "1", "弟子1");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("InvalidSlot", r.errorType);
    EXPECT_EQ(slotCountBefore, st.gameData.productionSlots.size());
    EXPECT_FALSE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_TRUE(before == rngSnapshot(rng));  // 零抽取
}

TEST(ProductionUiTxTest, AssignWritesTargetAndCapturesOldOccupant) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY", "7"));
    st.disciples.appendDisciple(baseDisciple("1"));

    const auto r = prod::assignProductionSlotTx(st, "ALCHEMY", 0, "1", "弟子1");

    ASSERT_TRUE(r.ok);
    EXPECT_EQ("7", r.oldOccupantId);
    EXPECT_EQ("弟子7", r.oldOccupantName);
    ASSERT_TRUE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_EQ("1", *st.gameData.productionSlots[0].assignedDiscipleId);
    EXPECT_EQ("弟子1", st.gameData.productionSlots[0].assignedDiscipleName);
}

/// 全槽位清理穷尽性：11 类槽位中该弟子的引用全部清空（住所保留）
TEST(ProductionUiTxTest, AssignClearsAllSlotFamiliesButKeepsResidence) {
    auto st = emptyState();
    const std::string d = "1";
    // 生产槽他槽占用
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY"));
    st.gameData.productionSlots.push_back(slotOf(1, "FORGE", d));
    // 单字段槽位
    SpiritMineSlot mine;
    mine.index = 0;
    mine.discipleId = d;
    mine.discipleName = "弟子1";
    st.gameData.spiritMineSlots.push_back(mine);
    LibrarySlot lib;
    lib.index = 0;
    lib.discipleId = d;
    st.gameData.librarySlots.push_back(lib);
    // 长老单值槽
    st.gameData.elderSlots.alchemyElder = d;
    // 长老列表槽（保留索引）
    gamecore::state::DirectDiscipleSlot direct;
    direct.index = 0;
    direct.discipleId = d;
    st.gameData.elderSlots.preachingMasters.push_back(direct);
    // 住所（工作分配保留）
    gamecore::state::ResidenceSlot res;
    res.slotIndex = 0;
    res.discipleId = d;
    res.discipleName = "弟子1";
    st.gameData.residenceSlots.push_back(res);
    // 战斗队伍
    gamecore::state::BattleTeam team;
    gamecore::state::BattleTeamSlot bslot;
    bslot.index = 0;
    bslot.discipleId = d;
    bslot.discipleName = "弟子1";
    bslot.isAlive = false;
    team.slots.push_back(bslot);
    st.gameData.battleTeams.push_back(team);
    // 世界地图驻防
    gamecore::state::WorldSect sect;
    sect.id = "sect_player";
    sect.isPlayerSect = true;
    gamecore::state::GarrisonSlot gs;
    gs.index = 0;
    gs.discipleId = d;
    sect.garrisonSlots.push_back(gs);
    st.gameData.worldMapSects.push_back(sect);
    // 巡逻槽
    gamecore::state::PatrolSlot patrol;
    patrol.index = 0;
    patrol.discipleId = d;
    st.gameData.patrolSlots.push_back(patrol);
    // 仓库驻守
    gamecore::state::WarehouseGarrisonSlot wh;
    wh.slotIndex = 0;
    wh.discipleId = d;
    st.gameData.warehouseGarrisons.push_back(wh);

    const auto r = prod::assignProductionSlotTx(st, "ALCHEMY", 0, d, "弟子1");

    ASSERT_TRUE(r.ok);
    // 目标槽写入
    ASSERT_TRUE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_EQ(d, *st.gameData.productionSlots[0].assignedDiscipleId);
    // 他槽清空
    EXPECT_FALSE(st.gameData.productionSlots[1].assignedDiscipleId.has_value());
    // 其余槽族清空
    EXPECT_TRUE(st.gameData.spiritMineSlots[0].discipleId.empty());
    EXPECT_TRUE(st.gameData.librarySlots[0].discipleId.empty());
    EXPECT_TRUE(st.gameData.elderSlots.alchemyElder.empty());
    EXPECT_EQ(0, st.gameData.elderSlots.preachingMasters[0].index);
    EXPECT_TRUE(st.gameData.elderSlots.preachingMasters[0].discipleId.empty());
    EXPECT_TRUE(st.gameData.battleTeams[0].slots[0].discipleId.empty());
    EXPECT_TRUE(st.gameData.battleTeams[0].slots[0].isAlive);  // 清空时复位存活
    EXPECT_TRUE(st.gameData.worldMapSects[0].garrisonSlots[0].discipleId.empty());
    EXPECT_TRUE(st.gameData.patrolSlots[0].discipleId.empty());
    EXPECT_TRUE(st.gameData.warehouseGarrisons[0].discipleId.empty());
    // 住所保留（includeResidence=false）
    EXPECT_EQ(d, st.gameData.residenceSlots[0].discipleId);
}

// ── removeProductionSlotDiscipleTx ────────────────────────────

TEST(ProductionUiTxTest, RemoveRejectsMissingSlotWithZeroWrite) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY", "1"));

    const auto r = prod::removeProductionSlotDiscipleTx(st, "ALCHEMY", 5);

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("InvalidSlot", r.errorType);
    ASSERT_TRUE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_EQ("1", *st.gameData.productionSlots[0].assignedDiscipleId);
}

/// WORKING 且原占用者非空 → 剩余时长归一（start 归当前月 + duration=max(剩余,1)）
TEST(ProductionUiTxTest, RemoveNormalizesWorkingSlotRemainingTime) {
    auto st = emptyState();
    ProductionSlot s = slotOf(0, "ALCHEMY", "1");
    s.status = "WORKING";
    s.startYear = 3;
    s.startMonth = 2;   // elapsed = 3 月 → remaining = duration(5) - 3 = 2
    s.duration = 5;
    s.baseDuration = 5;
    st.gameData.productionSlots.push_back(s);

    const auto r = prod::removeProductionSlotDiscipleTx(st, "ALCHEMY", 0);

    ASSERT_TRUE(r.ok);
    EXPECT_EQ("1", r.discipleId);
    const auto& out = st.gameData.productionSlots[0];
    EXPECT_FALSE(out.assignedDiscipleId.has_value());
    EXPECT_TRUE(out.assignedDiscipleName.empty());
    EXPECT_EQ("WORKING", out.status);  // 卸任不改状态（与 Kotlin 同）
    EXPECT_EQ(3, out.startYear);
    EXPECT_EQ(5, out.startMonth);
    EXPECT_EQ(2, out.duration);
}

/// 剩余为 0 时 duration 归 1（coerceAtLeast(1)）
TEST(ProductionUiTxTest, RemoveClampsRemainingToOne) {
    auto st = emptyState();
    ProductionSlot s = slotOf(0, "FORGE", "2");
    s.status = "WORKING";
    s.startYear = 3;
    s.startMonth = 1;   // elapsed = 4 → remaining = 1 - 4 = -3 → 1
    s.duration = 1;
    st.gameData.productionSlots.push_back(s);

    const auto r = prod::removeProductionSlotDiscipleTx(st, "FORGE", 0);

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(1, st.gameData.productionSlots[0].duration);
}

// ── toggleAutoRestartTx ───────────────────────────────────────

TEST(ProductionUiTxTest, ToggleFlipsAndReportsValue) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY"));

    const auto r1 = prod::toggleAutoRestartTx(st, "ALCHEMY", 0);
    ASSERT_TRUE(r1.ok);
    EXPECT_TRUE(r1.newValue);
    EXPECT_TRUE(st.gameData.productionSlots[0].autoRestartEnabled);

    const auto r2 = prod::toggleAutoRestartTx(st, "ALCHEMY", 0);
    ASSERT_TRUE(r2.ok);
    EXPECT_FALSE(r2.newValue);
    EXPECT_FALSE(st.gameData.productionSlots[0].autoRestartEnabled);
}

TEST(ProductionUiTxTest, ToggleRejectsMissingSlotWithZeroWrite) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY"));

    const auto r = prod::toggleAutoRestartTx(st, "ALCHEMY", 3);

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("InvalidSlot", r.errorType);
    EXPECT_FALSE(st.gameData.productionSlots[0].autoRestartEnabled);
}

// ── addProductionSlotTx（惰性建槽 / 镜像槽维护） ───────────────

TEST(ProductionUiTxTest, AddSlotAppendsWhenMissing) {
    auto st = emptyState();
    const auto r = prod::addProductionSlotTx(st, slotOf(2, "ALCHEMY", "1"));

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(r.created);
    ASSERT_EQ(1u, st.gameData.productionSlots.size());
    EXPECT_EQ(2, st.gameData.productionSlots[0].slotIndex);
    EXPECT_TRUE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
}

TEST(ProductionUiTxTest, AddSlotOverwritesSameBuildingIdIndex) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(2, "ALCHEMY"));
    ProductionSlot updated = slotOf(2, "ALCHEMY", "9");
    updated.autoRestartEnabled = true;

    const auto r = prod::addProductionSlotTx(st, updated);

    ASSERT_TRUE(r.ok);
    EXPECT_FALSE(r.created);
    ASSERT_EQ(1u, st.gameData.productionSlots.size());
    ASSERT_TRUE(st.gameData.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_EQ("9", *st.gameData.productionSlots[0].assignedDiscipleId);
    EXPECT_TRUE(st.gameData.productionSlots[0].autoRestartEnabled);
}

// ── 生产事务零 RNG（全分区快照差分） ──────────────────────────

TEST(ProductionUiTxTest, ProductionTxFamilyConsumesNoRng) {
    auto st = emptyState();
    st.gameData.productionSlots.push_back(slotOf(0, "ALCHEMY", "7"));
    st.gameData.productionSlots.push_back(slotOf(1, "FORGE"));
    RngManager rng;
    rng.initSystemSeed(2026);
    const auto before = rngSnapshot(rng);

    prod::assignProductionSlotTx(st, "ALCHEMY", 0, "1", "弟子1");
    prod::removeProductionSlotDiscipleTx(st, "FORGE", 1);
    prod::toggleAutoRestartTx(st, "ALCHEMY", 0);
    prod::addProductionSlotTx(st, slotOf(3, "ALCHEMY"));

    EXPECT_TRUE(before == rngSnapshot(rng));  // 全分区逐位不变
}

// ── 灵田单块播种 ─────────────────────────────────────────────

TEST(SpiritFieldTxTest, PlantOneWritesFieldsAndConsumesOneSeed) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 3));
    st.gameData.spiritFieldPlants.push_back(field("f1"));

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(1, r.planted);
    const auto& p = st.gameData.spiritFieldPlants[0];
    EXPECT_EQ("seed_1", p.seedId);
    EXPECT_EQ("灵芝种子", p.seedName);
    EXPECT_EQ(4, p.growTime);
    EXPECT_EQ(2, p.expectedYield);
    EXPECT_EQ(3, p.plantYear);
    EXPECT_EQ(5, p.plantMonth);
    EXPECT_EQ("sect_player", p.sectId);
    EXPECT_EQ(3 * 12 + 5 + 4, p.completionMonth);  // 绝对月 + max(growTime,1)
    EXPECT_EQ(3, p.completionPhase);               // 种植下旬
    ASSERT_EQ(1u, st.seeds.size());
    EXPECT_EQ(2, st.seeds[0].quantity);
}

TEST(SpiritFieldTxTest, PlantOneRemovesSeedEntryWhenQuantityReachesZero) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 1));
    st.gameData.spiritFieldPlants.push_back(field("f1"));

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");

    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(st.seeds.empty());  // 余量归 0 → 移除条目（Kotlin add 语义）
}

TEST(SpiritFieldTxTest, PlantOneRejectsLockedSeedWithZeroWrite) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 3, /*locked=*/true));
    st.gameData.spiritFieldPlants.push_back(field("f1"));

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SeedLocked", r.errorType);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
    EXPECT_EQ(3, st.seeds[0].quantity);
}

TEST(SpiritFieldTxTest, PlantOneRejectsEmptySeedWithZeroWrite) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 0));
    st.gameData.spiritFieldPlants.push_back(field("f1"));

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SeedEmpty", r.errorType);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
}

TEST(SpiritFieldTxTest, PlantOneRejectsUnknownSeedWithZeroWrite) {
    auto st = emptyState();
    st.gameData.spiritFieldPlants.push_back(field("f1"));

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "nope", "sect_player");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SeedNotFound", r.errorType);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
}

/// 已占用地块不可播种（seedId 非空 → isPlantable 假）
TEST(SpiritFieldTxTest, PlantOneRejectsOccupiedFieldWithZeroWrite) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 3));
    SpiritFieldPlant occupied = field("f1");
    occupied.seedId = "seed_old";
    occupied.seedName = "旧种子";
    st.gameData.spiritFieldPlants.push_back(occupied);

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("NoPlantableField", r.errorType);
    EXPECT_EQ("seed_old", st.gameData.spiritFieldPlants[0].seedId);
    EXPECT_EQ(3, st.seeds[0].quantity);
}

/// 跨宗门地块隔离：非本宗地块不可播种（空 sectId 为旧数据兼容）
TEST(SpiritFieldTxTest, PlantOneRejectsForeignSectField) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 3));
    st.gameData.spiritFieldPlants.push_back(field("f1", "sect_ai"));

    const auto r = sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("NoPlantableField", r.errorType);
    EXPECT_EQ(3, st.seeds[0].quantity);
}

// ── 灵田批量播种 ─────────────────────────────────────────────

TEST(SpiritFieldTxTest, PlantBatchCapsBySeedQuantity) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 2));  // 余量 2 < 目标地块 4
    st.gameData.spiritFieldPlants = {field("f1"), field("f2"), field("f3"), field("f4")};

    const auto r = sfx::plantOnSpiritFieldsTx(
        st, {"f1", "f2", "f3", "f4"}, "seed_1", "sect_player");

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(2, r.planted);
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[0].seedId);
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[1].seedId);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[2].seedId.empty());
    EXPECT_TRUE(st.gameData.spiritFieldPlants[3].seedId.empty());
    EXPECT_TRUE(st.seeds.empty());  // 2 - 2 = 0 → 条目移除
}

/// 批内材料竞争：目标集合含已占用地块（不消耗种子）与部分未命中 id
TEST(SpiritFieldTxTest, PlantBatchSkipsOccupiedAndUnlistedFields) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 5));
    SpiritFieldPlant occupied = field("f2");
    occupied.seedId = "seed_old";
    st.gameData.spiritFieldPlants = {field("f1"), occupied, field("f3")};

    const auto r = sfx::plantOnSpiritFieldsTx(
        st, {"f1", "f2"}, "seed_1", "sect_player");

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(1, r.planted);  // f2 已占用；f3 不在集合内
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[0].seedId);
    EXPECT_EQ("seed_old", st.gameData.spiritFieldPlants[1].seedId);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[2].seedId.empty());
    EXPECT_EQ(4, st.seeds[0].quantity);  // 仅扣实际播种数
}

TEST(SpiritFieldTxTest, PlantBatchEmptyTargetListIsNoOp) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 5));
    st.gameData.spiritFieldPlants.push_back(field("f1"));

    const auto r = sfx::plantOnSpiritFieldsTx(st, {}, "seed_1", "sect_player");

    EXPECT_TRUE(r.ok);
    EXPECT_EQ(0, r.planted);
    EXPECT_EQ(5, st.seeds[0].quantity);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
}

TEST(SpiritFieldTxTest, PlantBatchRejectsLockedSeedWithZeroWrite) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 5, /*locked=*/true));
    st.gameData.spiritFieldPlants = {field("f1"), field("f2")};

    const auto r = sfx::plantOnSpiritFieldsTx(
        st, {"f1", "f2"}, "seed_1", "sect_player");

    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SeedLocked", r.errorType);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
    EXPECT_TRUE(st.gameData.spiritFieldPlants[1].seedId.empty());
    EXPECT_EQ(5, st.seeds[0].quantity);
}

// ── 灵田移除（单/批） ────────────────────────────────────────

TEST(SpiritFieldTxTest, RemoveOneClearsPlantFieldsKeepingInstanceAndSect) {
    auto st = emptyState();
    SpiritFieldPlant p = field("f1", "sect_player");
    p.seedId = "seed_1";
    p.seedName = "灵芝种子";
    p.growTime = 4;
    p.expectedYield = 2;
    p.plantYear = 3;
    p.plantMonth = 1;
    p.completionMonth = 41;
    p.completionPhase = 3;
    st.gameData.spiritFieldPlants.push_back(p);

    const auto r = sfx::removePlantFromSpiritFieldTx(st, "f1");

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(1, r.removed);
    const auto& out = st.gameData.spiritFieldPlants[0];
    EXPECT_TRUE(out.seedId.empty());
    EXPECT_TRUE(out.seedName.empty());
    EXPECT_EQ(0, out.growTime);
    EXPECT_EQ(0, out.expectedYield);
    EXPECT_EQ(0, out.plantYear);
    EXPECT_EQ(0, out.plantMonth);
    EXPECT_EQ(0, out.completionMonth);
    EXPECT_EQ(1, out.completionPhase);
    EXPECT_EQ("f1", out.buildingInstanceId);      // 实例保留
    EXPECT_EQ("sect_player", out.sectId);         // 宗门保留
}

TEST(SpiritFieldTxTest, RemoveOneUnknownInstanceIsSilentNoOp) {
    auto st = emptyState();
    SpiritFieldPlant p = field("f1");
    p.seedId = "seed_1";
    st.gameData.spiritFieldPlants.push_back(p);

    const auto r = sfx::removePlantFromSpiritFieldTx(st, "nope");

    EXPECT_TRUE(r.ok);
    EXPECT_EQ(0, r.removed);
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[0].seedId);
}

TEST(SpiritFieldTxTest, RemoveBatchClearsOnlyTargetedInstances) {
    auto st = emptyState();
    SpiritFieldPlant a = field("f1");
    a.seedId = "seed_1";
    SpiritFieldPlant b = field("f2");
    b.seedId = "seed_1";
    SpiritFieldPlant c = field("f3");
    c.seedId = "seed_1";
    st.gameData.spiritFieldPlants = {a, b, c};

    const auto r = sfx::removePlantsFromSpiritFieldsTx(st, {"f1", "f3"});

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(2, r.removed);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[0].seedId.empty());
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[1].seedId);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[2].seedId.empty());
}

TEST(SpiritFieldTxTest, RemoveBatchEmptyListIsNoOp) {
    auto st = emptyState();
    SpiritFieldPlant a = field("f1");
    a.seedId = "seed_1";
    st.gameData.spiritFieldPlants = {a};

    const auto r = sfx::removePlantsFromSpiritFieldsTx(st, {});

    EXPECT_TRUE(r.ok);
    EXPECT_EQ(0, r.removed);
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[0].seedId);
}

// ── 灵田事务零 RNG（全分区快照差分） ──────────────────────────

TEST(SpiritFieldTxTest, SpiritFieldTxFamilyConsumesNoRng) {
    auto st = emptyState();
    st.seeds.push_back(seedOf("seed_1", 3));
    st.gameData.spiritFieldPlants = {field("f1"), field("f2")};
    RngManager rng;
    rng.initSystemSeed(2026);
    const auto before = rngSnapshot(rng);

    sfx::plantOnSpiritFieldTx(st, "f1", "seed_1", "sect_player");
    sfx::plantOnSpiritFieldsTx(st, {"f2"}, "seed_1", "sect_player");
    sfx::removePlantFromSpiritFieldTx(st, "f1");
    sfx::removePlantsFromSpiritFieldsTx(st, {"f2"});

    EXPECT_TRUE(before == rngSnapshot(rng));  // 全分区逐位不变
}

// ── execute_dispatch 段接线（ActionId 全链路 + 零抽取） ───────

TEST(ProductionUiDispatchTest, AssignSlotActionRoutesThroughDispatch) {
    auto core = makeCore(4242);
    auto& gd = core->state().gameData;
    const int32_t year = gd.gameYear;
    const int32_t month = gd.gameMonth;

    ProductionSlot s = slotOf(0, "ALCHEMY");
    s.id = "slot-ALCHEMY0";
    nlohmann::json params = {
        {"buildingType", "ALCHEMY"}, {"slotIndex", 0},
        {"discipleId", "1"}, {"discipleName", "弟子1"},
    };
    (void)year;
    (void)month;
    gd.productionSlots.push_back(s);

    const auto rngBefore = rngSnapshot(core->rng());
    const auto raw = core->execute(action::PROD_UI_ASSIGN_SLOT, params.dump(), 0);
    const auto parsed = nlohmann::json::parse(raw);

    ASSERT_EQ("success", parsed.at("status").get<std::string>());
    EXPECT_TRUE(parsed.at("data").at("assigned").get<bool>());
    ASSERT_TRUE(gd.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_EQ("1", *gd.productionSlots[0].assignedDiscipleId);
    EXPECT_TRUE(rngBefore == rngSnapshot(core->rng()));  // dispatch 层零抽取
}

TEST(ProductionUiDispatchTest, AssignSlotFailureReturnsFailureEnvelope) {
    auto core = makeCore(4242);

    const auto raw = core->execute(
        action::PROD_UI_ASSIGN_SLOT,
        nlohmann::json({{"buildingType", "ALCHEMY"}, {"slotIndex", 4},
                        {"discipleId", "1"}, {"discipleName", "弟子1"}}).dump(),
        0);
    const auto parsed = nlohmann::json::parse(raw);

    EXPECT_EQ("failure", parsed.at("status").get<std::string>());
    EXPECT_EQ("InvalidSlot", parsed.at("code").get<std::string>());
}

TEST(ProductionUiDispatchTest, SpiritFieldPlantBatchRoutesThroughDispatch) {
    auto core = makeCore(4242);
    auto& st = core->state();
    st.seeds.push_back(seedOf("seed_1", 1));
    st.gameData.spiritFieldPlants = {field("f1"), field("f2")};

    const auto raw = core->execute(
        action::SPIRIT_FIELD_PLANT_BATCH,
        nlohmann::json({{"instanceIds", {"f1", "f2"}}, {"seedId", "seed_1"},
                        {"sectId", "sect_player"}}).dump(),
        0);
    const auto parsed = nlohmann::json::parse(raw);

    ASSERT_EQ("success", parsed.at("status").get<std::string>());
    EXPECT_EQ(1, parsed.at("data").at("planted").get<int32_t>());
    EXPECT_EQ("seed_1", st.gameData.spiritFieldPlants[0].seedId);
    EXPECT_TRUE(st.gameData.spiritFieldPlants[1].seedId.empty());
    EXPECT_TRUE(st.seeds.empty());
}

TEST(ProductionUiDispatchTest, SpiritFieldRemoveUnknownInstanceSucceeds) {
    auto core = makeCore(4242);

    const auto raw = core->execute(
        action::SPIRIT_FIELD_REMOVE_ONE,
        nlohmann::json({{"buildingInstanceId", "nope"}}).dump(), 0);
    const auto parsed = nlohmann::json::parse(raw);

    EXPECT_EQ("success", parsed.at("status").get<std::string>());
    EXPECT_EQ(0, parsed.at("data").at("removed").get<int32_t>());
}

}  // namespace

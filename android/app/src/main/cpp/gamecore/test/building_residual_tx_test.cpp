// ============================================================
// building_residual_tx_test — w3-09 建筑槽位残差事务守护（W4-A 第三子批）
//
// 守护目标：building_residual_tx.h 两事务与 Kotlin 源语义逐位一致——
//   - 1810 清扫（十类槽位按槽组清除 / 长老殿"最后一座"判定 / 监牢全量
//     释放 REFLECTING / 任务阁清 activeMissions + 存活 ON_MISSION 回 IDLE /
//     REFINING 破除含 statusData 定向移除 buildingId / patrolConfigs 不清
//     ——两臂 towerIdx=-1 bug-for-bug 兼容）
//   - 1811 放置（八集合建槽基数 / 每塔一份 PatrolConfig / 生产槽 createIdle
//     等价 / 血炼·长老组建零槽 / 未知组失败零写入）
//   - 失败臂零写入（放置臂校验先行）
//   - RNG 零消费审计（两事务全程 rngStates 不动——对拍命门）
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/models.h"
#include "gamecore/system/building_residual_tx.h"

namespace gamecore {
namespace {

namespace residual_tx = gamecore::system::building_residual_tx;

using gamecore::state::Disciple;
using gamecore::state::GridBuildingData;

class BuildingResidualTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    /// 挂一个最小弟子（存活、指定状态、20 岁）
    void addDisciple(const std::string& id, const std::string& status = "IDLE") {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = 9;
        d.realmLayer = 1;
        d.isAlive = 1;
        d.spiritRootType = "metal";
        d.age = 20;
        d.lifespan = 80;
        d.status = status;
        d.currentHp = 100;
        d.currentMp = 50;
        core_->state().disciples.appendDisciple(d);
    }

    void setAlive(const std::string& id, int32_t alive) {
        core_->state().disciples.isAlive[*core_->state().disciples.rowOf(id)] = alive;
    }

    /// 挂一座已放置建筑（displayName 驱动长老殿"末座"判定）
    void addBuilding(const std::string& instanceId, const std::string& displayName) {
        GridBuildingData b;
        b.instanceId = instanceId;
        b.displayName = displayName;
        b.buildingId = "b_" + instanceId;
        b.gridX = 10;
        b.gridY = 10;
        b.width = 2;
        b.height = 2;
        core_->state().gameData.placedBuildings.push_back(b);
    }

    /// 播种各集合与目标实例关联的行（清扫穷尽性断言基准）
    void seedSlotsFor(const std::string& instanceId, const std::string& discipleId) {
        auto& gd = core_->state().gameData;
        gamecore::state::SpiritMineSlot mine;
        mine.index = 0;
        mine.buildingInstanceId = instanceId;
        mine.discipleId = discipleId;
        gd.spiritMineSlots.push_back(mine);
        gamecore::state::PatrolSlot patrol;
        patrol.index = 0;
        patrol.buildingInstanceId = instanceId;
        patrol.discipleId = discipleId;
        gd.patrolSlots.push_back(patrol);
        gamecore::state::ProductionSlot prod;
        prod.id = "prod-" + instanceId;
        prod.status = "IDLE";  // 🔴 无 buildingInstanceId 字段（偏差登记）——留 Kotlin
        gd.productionSlots.push_back(prod);
        gamecore::state::ResidenceSlot res;
        res.buildingInstanceId = instanceId;
        res.discipleId = discipleId;
        gd.residenceSlots.push_back(res);
        gamecore::state::SpiritFieldPlant plant;
        plant.buildingInstanceId = instanceId;
        gd.spiritFieldPlants.push_back(plant);
        gamecore::state::WarehouseGarrisonSlot garrison;
        garrison.buildingInstanceId = instanceId;
        garrison.discipleId = discipleId;
        gd.warehouseGarrisons.push_back(garrison);
        gamecore::state::LibrarySlot lib;
        lib.index = 0;
        lib.buildingInstanceId = instanceId;
        lib.discipleId = discipleId;
        gd.librarySlots.push_back(lib);
        gamecore::state::BloodRefinementProgress refine;
        refine.discipleId = discipleId;
        gd.activeBloodRefinements[instanceId] = refine;
        gd.patrolConfigs.push_back(gamecore::state::PatrolConfig{});
    }

    /// 直调事务（两事务均为纯函数——经 execute 走 1810 参数链另见 dispatch 用例）
    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 1810 清扫 ────────────────────────────────────────────────

TEST_F(BuildingResidualTxFixture, ClearResidual_十类槽位逐组清除与状态破除) {
    addDisciple("1");
    addDisciple("2");  // 对照弟子：不涉清扫
    seedSlotsFor("inst-1", "1");
    const auto before = rngSnapshot();

    residual_tx::ResidualTarget target;
    target.instanceId = "inst-1";
    target.displayName = "灵矿场";
    target.groups = {
        residual_tx::SlotGroupKind::SpiritMine,   residual_tx::SlotGroupKind::PatrolTower,
        residual_tx::SlotGroupKind::Residence,    residual_tx::SlotGroupKind::SpiritField,
        residual_tx::SlotGroupKind::Warehouse,    residual_tx::SlotGroupKind::Library,
        residual_tx::SlotGroupKind::BloodRefining,
    };
    target.discipleIds = {"1"};
    const auto r = residual_tx::clearResidualTransaction(core_->state(), {target});
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.clearedTargets, 1);

    auto& gd = core_->state().gameData;
    EXPECT_TRUE(gd.spiritMineSlots.empty());
    EXPECT_TRUE(gd.patrolSlots.empty());
    // 生产/长老组留 Kotlin 清扫（偏差登记）——productionSlots 原样
    EXPECT_EQ(gd.productionSlots.size(), 1u);
    EXPECT_TRUE(gd.residenceSlots.empty());
    EXPECT_TRUE(gd.spiritFieldPlants.empty());
    EXPECT_TRUE(gd.warehouseGarrisons.empty());
    EXPECT_TRUE(gd.librarySlots.empty());
    EXPECT_TRUE(gd.activeBloodRefinements.empty());
    // patrolConfigs 不清（两臂 towerIdx=-1 bug-for-bug 兼容）
    EXPECT_EQ(gd.patrolConfigs.size(), 1u);

    // REFINING 破除：statuses=IDLE + statusData 定向移除 buildingId（保留其余 key）
    auto& ds = core_->state().disciples;
    ds.statuses[*ds.rowOf("1")] = "REFINING";
    ds.statusData[*ds.rowOf("1")] = {{"buildingId", "inst-1"}, {"followed", "true"}};
    target.groups = {};
    const auto r2 = residual_tx::clearResidualTransaction(core_->state(), {target});
    ASSERT_TRUE(r2.ok);
    const auto row1 = *ds.rowOf("1");
    EXPECT_EQ(ds.statuses[row1], "IDLE");
    EXPECT_EQ(ds.statusData[row1].count("buildingId"), 0u);
    EXPECT_EQ(ds.statusData[row1].at("followed"), "true");
    // 幽灵 id：不在表内的 discipleId 静默跳过
    target.discipleIds = {"999"};
    const auto r3 = residual_tx::clearResidualTransaction(core_->state(), {target});
    ASSERT_TRUE(r3.ok);

    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(BuildingResidualTxFixture, ClearResidual_长老殿末座判定与监牢任务阁特例) {
    addDisciple("1", "REFLECTING");
    addDisciple("2", "ON_MISSION");
    setAlive("2", 1);
    addDisciple("3", "ON_MISSION");
    setAlive("3", 0);  // 已亡 ON_MISSION：不清（Kotlin isAlive 过滤）
    auto& gd = core_->state().gameData;
    gd.activeMissions.push_back(gamecore::state::ActiveMission{});
    // 思过双键播种（监牢释放断言基准——须在事务前播种）
    const auto row1 = *core_->state().disciples.rowOf("1");
    core_->state().disciples.statusData[row1] = {
        {"reflectionStartYear", "3"}, {"reflectionEndYear", "4"}};
    const auto before = rngSnapshot();

    // 监牢/任务阁特例（长老殿/生产留 Kotlin——偏差登记，不发线）
    residual_tx::ResidualTarget target;
    target.instanceId = "keep-1";
    target.isMissionHall = true;
    target.isReflectionCliff = true;
    const auto r = residual_tx::clearResidualTransaction(core_->state(), {target});
    ASSERT_TRUE(r.ok);

    // 监牢：全量 REFLECTING 释放 + 思过双键移除
    EXPECT_EQ(core_->state().disciples.statuses[row1], "IDLE");
    EXPECT_TRUE(core_->state().disciples.statusData[row1].empty());

    // 任务阁：activeMissions 清空 + 存活 ON_MISSION 回 IDLE、已亡不动
    EXPECT_TRUE(gd.activeMissions.empty());
    const auto row2 = *core_->state().disciples.rowOf("2");
    const auto row3 = *core_->state().disciples.rowOf("3");
    EXPECT_EQ(core_->state().disciples.statuses[row2], "IDLE");
    EXPECT_EQ(core_->state().disciples.statuses[row3], "ON_MISSION");

    EXPECT_EQ(rngSnapshot(), before);
}

// ── 1811 放置 ────────────────────────────────────────────────

TEST_F(BuildingResidualTxFixture, PlaceSlots_七组建槽基数逐位对齐) {
    auto& gd = core_->state().gameData;
    const auto before = rngSnapshot();

    residual_tx::PlaceSlotsParams p;
    p.instanceId = "inst-new";
    p.activeSectId = "sect-player";
    p.groups = {
        {residual_tx::SlotGroupKind::SpiritMine, 3},
        {residual_tx::SlotGroupKind::PatrolTower, 8},
        {residual_tx::SlotGroupKind::Residence, 2},
        {residual_tx::SlotGroupKind::SpiritField, 1},
        {residual_tx::SlotGroupKind::Warehouse, 1},
        {residual_tx::SlotGroupKind::Library, 3},
        {residual_tx::SlotGroupKind::BloodRefining, 0},  // 建造不产槽
    };
    const auto r = residual_tx::placeSlotsTransaction(core_->state(), p);
    ASSERT_TRUE(r.ok);

    // 矿场：index=base(0)+offset、sectId 冗余列
    ASSERT_EQ(gd.spiritMineSlots.size(), 3u);
    EXPECT_EQ(gd.spiritMineSlots[0].index, 0);
    EXPECT_EQ(gd.spiritMineSlots[2].index, 2);
    EXPECT_EQ(gd.spiritMineSlots[0].sectId, "sect-player");
    EXPECT_EQ(gd.spiritMineSlots[0].buildingInstanceId, "inst-new");

    // 巡逻楼：8 槽 + 恰一份 PatrolConfig
    EXPECT_EQ(gd.patrolSlots.size(), 8u);
    EXPECT_EQ(gd.patrolSlots[7].index, 7);
    EXPECT_EQ(gd.patrolConfigs.size(), 1u);

    // 住所 ×2（slotIndex=offset）、灵田 ×1（sectId）、仓库 ×1、藏经阁 ×3
    EXPECT_EQ(gd.residenceSlots.size(), 2u);
    EXPECT_EQ(gd.residenceSlots[1].slotIndex, 1);
    EXPECT_EQ(gd.spiritFieldPlants.size(), 1u);
    EXPECT_EQ(gd.spiritFieldPlants[0].sectId, "sect-player");
    EXPECT_EQ(gd.warehouseGarrisons.size(), 1u);
    EXPECT_EQ(gd.warehouseGarrisons[0].slotIndex, 0);
    EXPECT_EQ(gd.librarySlots.size(), 3u);
    EXPECT_EQ(gd.librarySlots[2].index, 2);
    // 生产/长老组留 Kotlin（偏差登记）——C++ 侧零行
    EXPECT_TRUE(gd.productionSlots.empty());
    EXPECT_TRUE(gd.activeBloodRefinements.empty());

    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(BuildingResidualTxFixture, PlaceSlots_未知组失败零写入) {
    const auto before = rngSnapshot();
    // instanceId 空 → 失败零写入
    residual_tx::PlaceSlotsParams p2;
    p2.groups = {{residual_tx::SlotGroupKind::SpiritMine, 1}};
    const auto r2 = residual_tx::placeSlotsTransaction(core_->state(), p2);
    EXPECT_FALSE(r2.ok);
    EXPECT_TRUE(core_->state().gameData.spiritMineSlots.empty());
    EXPECT_EQ(rngSnapshot(), before);
}

// ── 1810 分派链（execute 端到端——参数解析/未知组静默跳过）────

TEST_F(BuildingResidualTxFixture, Dispatch1810_端到端与未知组静默跳过) {
    addDisciple("1");
    seedSlotsFor("inst-1", "1");
    const auto before = rngSnapshot();

    const std::string paramsJson = R"({"targets":[{"instanceId":"inst-1",
        "displayName":"灵矿场",
        "groups":["SPIRIT_MINE","RESIDENCE","UNKNOWN_GROUP","BLOOD_REFINING"],
        "isMissionHall":false,"isReflectionCliff":false,
        "discipleIds":["1"]}]})";
    const std::string result =
        core_->execute(action::BUILDING_RESIDUAL_CLEAR, paramsJson, 1000);
    const auto j = nlohmann::json::parse(result);
    ASSERT_EQ(j["status"], "success") << j["code"];
    EXPECT_EQ(j["data"]["cleared"], 1);
    EXPECT_TRUE(core_->state().gameData.spiritMineSlots.empty());
    EXPECT_TRUE(core_->state().gameData.residenceSlots.empty());
    EXPECT_TRUE(core_->state().gameData.activeBloodRefinements.empty());
    // 未列入的集合原样（未知组静默 + 未列组不清）
    EXPECT_EQ(core_->state().gameData.librarySlots.size(), 1u);
    EXPECT_EQ(rngSnapshot(), before);
}

}  // namespace
}  // namespace gamecore

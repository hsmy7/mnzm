// ============================================================
// patrol_tx_test — 巡逻 / 住所 / 矿场 / 年俸 UI 操作面事务守护（batch-12）
//
// 守护目标：patrol_tx.h 十事务与 Kotlin 源语义逐位一致——
//   - 住所 assign/remove：判定链六失败臂零写入、重复分配无操作、
//     跨住所搬迁清旧槽、释放原 occupant
//   - 巡逻 assign/remove/swap：释放原 occupant 且 buildingInstanceId 保留、
//     清新弟子其它槽位、空槽无操作、同索引无操作、展示字段重建
//   - autoAssign：六类前置/预检失败臂零写入、清空槽语义、
//     releasedIds/confirmedIds 逐序回执
//   - 覆写族：updatePatrolConfig 默认填位、updateSpiritMineSlots、
//     validateAndFixSpiritMineData（孤儿清空/索引重排/重锚/sectId 对齐）、
//     updateYearlySalary
//   - **零 RNG 全分区快照差分** + **双运行全状态逐位一致**
//   - 信封级：execute 通道 success data 面 + 失败信封（Kotlin 回退臂契约）
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/models.h"
#include "gamecore/system/patrol_tx.h"

namespace gamecore {
namespace {

namespace patrol_tx = gamecore::system::patrol_tx;

using gamecore::state::DirectDiscipleSlot;
using gamecore::state::Disciple;
using gamecore::state::GridBuildingData;
using gamecore::state::PatrolConfig;
using gamecore::state::PatrolSlot;
using gamecore::state::ResidenceSlot;
using gamecore::state::SpiritMineSlot;

class PatrolTxFixture : public ::testing::Test {
protected:
    void SetUp() override { core_ = makeCore(); }

    /// 独立 GameCore（双运行逐位一致测试用；时钟/日志由夹具容器持有保命）
    std::unique_ptr<GameCore> makeCore() {
        auto clock = std::make_unique<FixedClock>();
        auto logger = std::make_unique<ConsoleLogger>();
        auto core = std::make_unique<GameCore>(clock.get(), logger.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core->initialize(config);
        clocks_.push_back(std::move(clock));
        loggers_.push_back(std::move(logger));
        return core;
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// 挂一个最小存活弟子，返回行号（列直访用）
    std::size_t addDisciple(const std::string& id, int32_t realm = 9) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = realm;
        d.realmLayer = 1;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.age = 20;
        d.lifespan = 80;
        d.status = "IDLE";
        d.currentHp = 100;
        d.currentMp = 50;
        d.portraitRes = "portrait_" + id;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    void killDisciple(const std::string& id) {
        core_->state().disciples.isAlive[*core_->state().disciples.rowOf(id)] = 0;
    }

    /// 全部 12 类槽位各挂一条含该弟子的条目（穷尽性断言用）
    void seedAllSlotFamilies(const std::string& discipleId) {
        auto& gd = core_->state().gameData;

        SpiritMineSlot mine;
        mine.index = 0;
        mine.discipleId = discipleId;
        mine.discipleName = "矿工";
        gd.spiritMineSlots.push_back(mine);

        gamecore::state::LibrarySlot lib;
        lib.index = 0;
        lib.discipleId = discipleId;
        lib.discipleName = "藏书";
        gd.librarySlots.push_back(lib);

        DirectDiscipleSlot direct;
        direct.index = 0;
        direct.discipleId = discipleId;
        direct.discipleName = "亲传";
        gd.elderSlots.herbGardenDisciples.push_back(direct);
        gd.elderSlots.alchemyDisciples.push_back(direct);

        ResidenceSlot res;
        res.buildingInstanceId = "res_b1";
        res.slotIndex = 0;
        res.discipleId = discipleId;
        res.discipleName = "住户";
        gd.residenceSlots.push_back(res);

        gamecore::state::BloodRefinementProgress blood;
        blood.discipleId = discipleId;
        gd.activeBloodRefinements[discipleId] = blood;

        PatrolSlot patrol;
        patrol.index = 0;
        patrol.discipleId = discipleId;
        patrol.discipleName = "巡逻";
        gd.patrolSlots.push_back(patrol);

        gamecore::state::WarehouseGarrisonSlot garrison;
        garrison.slotIndex = 0;
        garrison.discipleId = discipleId;
        garrison.discipleName = "驻守";
        gd.warehouseGarrisons.push_back(garrison);

        gamecore::state::BattleTeam team;
        gamecore::state::BattleTeamSlot teamSlot;
        teamSlot.discipleId = discipleId;
        teamSlot.discipleName = "队员";
        team.slots.push_back(teamSlot);
        gd.battleTeams.push_back(team);

        gamecore::state::WorldSect sect;
        sect.id = "sect_player";
        sect.isPlayerSect = true;
        gamecore::state::GarrisonSlot gs;
        gs.index = 0;
        gs.discipleId = discipleId;
        gs.discipleName = "分舵";
        sect.garrisonSlots.push_back(gs);
        gd.worldMapSects.push_back(sect);

        gamecore::state::ProductionSlot prod;
        prod.id = "prod_1";
        prod.assignedDiscipleId = discipleId;
        prod.assignedDiscipleName = "工人";
        gd.productionSlots.push_back(prod);

        gamecore::state::CaveExplorationTeam cave;
        cave.memberIds.push_back(discipleId);
        cave.memberNames.push_back("洞府");
        gd.caveExplorationTeams.push_back(cave);

        gamecore::state::ActiveMission mission;
        mission.id = "mission_1";
        mission.discipleIds.push_back(discipleId);
        mission.discipleNames.push_back("任务");
        gd.activeMissions.push_back(mission);
    }

    /// 巡逻槽位构造（保留 index / buildingInstanceId）
    void seedPatrolSlots(int32_t count, const std::string& buildingInstanceId) {
        auto& slots = core_->state().gameData.patrolSlots;
        slots.clear();
        for (int32_t i = 0; i < count; ++i) {
            PatrolSlot s;
            s.index = i;
            s.buildingInstanceId = buildingInstanceId;
            slots.push_back(s);
        }
    }

    void seedResidenceSlots(const std::string& buildingInstanceId, int32_t count) {
        auto& slots = core_->state().gameData.residenceSlots;
        slots.clear();
        for (int32_t i = 0; i < count; ++i) {
            ResidenceSlot s;
            s.buildingInstanceId = buildingInstanceId;
            s.slotIndex = i;
            slots.push_back(s);
        }
    }

    void seedBuilding(const std::string& instanceId, const std::string& displayName) {
        GridBuildingData b;
        b.instanceId = instanceId;
        b.displayName = displayName;
        b.buildingId = "spirit_mine";
        b.width = 4;
        b.height = 4;
        b.sectId = "sect_player";
        core_->state().gameData.placedBuildings.push_back(b);
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 住所分配：判定链六失败臂零写入 ──────────────────────────────────────

TEST_F(PatrolTxFixture, AssignToResidenceFailureArmsZeroWrite) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    seedBuilding("res_b1", "住所");
    seedResidenceSlots("res_b1", 2);
    const auto before = core_->state().gameData.residenceSlots;

    // 弟子不存在
    auto r = patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "404");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");

    // 弟子已死亡
    r = patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "2");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");

    // 建筑不存在
    r = patrol_tx::assignToResidenceTx(core_->state(), "no_such_building", 0, "1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");

    // slotIndex 负
    r = patrol_tx::assignToResidenceTx(core_->state(), "res_b1", -1, "1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");

    // 槽位不存在
    r = patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 9, "1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");

    // 失败零写入
    EXPECT_EQ(core_->state().gameData.residenceSlots, before);
}

TEST_F(PatrolTxFixture, AssignToResidenceHappyWritesName) {
    addDisciple("1");
    seedBuilding("res_b1", "住所");
    seedResidenceSlots("res_b1", 1);

    const auto r = patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1");
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_TRUE(r.releasedOccupantId.empty());
    const auto& slot = core_->state().gameData.residenceSlots[0];
    EXPECT_EQ(slot.discipleId, "1");
    EXPECT_EQ(slot.discipleName, "弟子1");
}

TEST_F(PatrolTxFixture, AssignToResidenceReleasesOccupantAndMigratesOldSlot) {
    addDisciple("1");
    addDisciple("2");
    seedBuilding("res_b1", "住所");
    seedBuilding("res_b2", "住所");
    seedResidenceSlots("res_b1", 1);
    // 弟子 1 先住 res_b1/0；再给 res_b2/0 安排弟子 2
    {
        ResidenceSlot extra;
        extra.buildingInstanceId = "res_b2";
        extra.slotIndex = 0;
        core_->state().gameData.residenceSlots.push_back(extra);
    }
    ASSERT_TRUE(patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1").base.ok);
    ASSERT_TRUE(patrol_tx::assignToResidenceTx(core_->state(), "res_b2", 0, "2").base.ok);

    // 弟子 1 搬到 res_b2/0（释放弟子 2 + 清 res_b1/0 旧槽）
    const auto r = patrol_tx::assignToResidenceTx(core_->state(), "res_b2", 0, "1");
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.releasedOccupantId, "2");
    const auto& slots = core_->state().gameData.residenceSlots;
    for (const auto& s : slots) {
        if (s.buildingInstanceId == "res_b1" && s.slotIndex == 0) {
            EXPECT_TRUE(s.discipleId.empty());
            EXPECT_TRUE(s.discipleName.empty());
        }
        if (s.buildingInstanceId == "res_b2" && s.slotIndex == 0) {
            EXPECT_EQ(s.discipleId, "1");
        }
    }
}

TEST_F(PatrolTxFixture, AssignToResidenceSameDiscipleIsIdempotent) {
    addDisciple("1");
    seedBuilding("res_b1", "住所");
    seedResidenceSlots("res_b1", 1);
    ASSERT_TRUE(patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1").base.ok);
    const auto after1 = core_->state().gameData.residenceSlots;

    const auto r = patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1");
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_TRUE(r.releasedOccupantId.empty());
    EXPECT_EQ(core_->state().gameData.residenceSlots, after1);
}

// ── 住所移除 ────────────────────────────────────────────────────────────

TEST_F(PatrolTxFixture, RemoveFromResidenceFailureArmsAndEmptyNoop) {
    addDisciple("1");
    seedBuilding("res_b1", "住所");
    seedResidenceSlots("res_b1", 1);

    // 负索引
    auto r = patrol_tx::removeFromResidenceTx(core_->state(), "res_b1", -1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    // 槽不存在
    r = patrol_tx::removeFromResidenceTx(core_->state(), "res_b1", 5);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    // 空槽无操作成功
    r = patrol_tx::removeFromResidenceTx(core_->state(), "res_b1", 0);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.removedDiscipleId.empty());
}

TEST_F(PatrolTxFixture, RemoveFromResidenceHappyClearsSlot) {
    addDisciple("1");
    seedBuilding("res_b1", "住所");
    seedResidenceSlots("res_b1", 1);
    ASSERT_TRUE(patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1").base.ok);

    const auto r = patrol_tx::removeFromResidenceTx(core_->state(), "res_b1", 0);
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedDiscipleId, "1");
    const auto& slot = core_->state().gameData.residenceSlots[0];
    EXPECT_TRUE(slot.discipleId.empty());
    EXPECT_TRUE(slot.discipleName.empty());
}

// ── 巡逻分配 ────────────────────────────────────────────────────────────

TEST_F(PatrolTxFixture, AssignPatrolFailureArmsZeroWrite) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    seedPatrolSlots(2, "tower_1");
    const auto before = core_->state().gameData.patrolSlots;

    auto r = patrol_tx::assignPatrolTx(core_->state(), "404", 0);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");
    r = patrol_tx::assignPatrolTx(core_->state(), "2", 0);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");
    r = patrol_tx::assignPatrolTx(core_->state(), "1", -1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    r = patrol_tx::assignPatrolTx(core_->state(), "1", 5);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");

    EXPECT_EQ(core_->state().gameData.patrolSlots, before);
}

TEST_F(PatrolTxFixture, AssignPatrolWritesDisplayFieldsAndKeepsBuilding) {
    addDisciple("1");
    seedPatrolSlots(2, "tower_7");

    const auto r = patrol_tx::assignPatrolTx(core_->state(), "1", 1);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    const auto& slot = core_->state().gameData.patrolSlots[1];
    EXPECT_EQ(slot.index, 1);
    EXPECT_EQ(slot.discipleId, "1");
    EXPECT_EQ(slot.discipleName, "弟子1");
    EXPECT_EQ(slot.discipleRealm, "炼气1层");
    EXPECT_EQ(slot.portraitRes, "portrait_1");
    // buildingInstanceId 保留原槽值
    EXPECT_EQ(slot.buildingInstanceId, "tower_7");
}

TEST_F(PatrolTxFixture, AssignPatrolReleasesOccupantAndKeepsItsBuilding) {
    addDisciple("1");
    addDisciple("2");
    seedPatrolSlots(2, "tower_1");
    core_->state().gameData.patrolSlots[1].buildingInstanceId = "tower_2";
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 1).base.ok);

    const auto r = patrol_tx::assignPatrolTx(core_->state(), "2", 1);
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.releasedOccupantId, "1");
    const auto& slot = core_->state().gameData.patrolSlots[1];
    EXPECT_EQ(slot.discipleId, "2");
    EXPECT_EQ(slot.buildingInstanceId, "tower_2");
}

TEST_F(PatrolTxFixture, AssignPatrolClearsDiscipleOtherSlotsExhaustively) {
    addDisciple("1");
    seedPatrolSlots(2, "tower_1");
    seedAllSlotFamilies("1");

    const auto r = patrol_tx::assignPatrolTx(core_->state(), "1", 1);
    EXPECT_TRUE(r.base.ok);
    const auto& gd = core_->state().gameData;

    // 12 类槽位穷尽性：除目标巡逻槽 slot 1 外，该弟子全部槽位引用清空
    EXPECT_TRUE(gd.spiritMineSlots[0].discipleId.empty());
    EXPECT_TRUE(gd.librarySlots[0].discipleId.empty());
    EXPECT_TRUE(gd.elderSlots.herbGardenDisciples[0].discipleId.empty());
    EXPECT_TRUE(gd.elderSlots.alchemyDisciples[0].discipleId.empty());
    // 住所与工作共存（includeResidence=false）——住所槽位**保留**
    EXPECT_EQ(gd.residenceSlots[0].discipleId, "1");
    EXPECT_TRUE(gd.activeBloodRefinements.empty());
    EXPECT_TRUE(gd.warehouseGarrisons[0].discipleId.empty());
    EXPECT_TRUE(gd.battleTeams[0].slots[0].discipleId.empty());
    EXPECT_TRUE(gd.worldMapSects[0].garrisonSlots[0].discipleId.empty());
    EXPECT_FALSE(gd.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_TRUE(gd.caveExplorationTeams[0].memberIds.empty());
    EXPECT_TRUE(gd.activeMissions[0].discipleIds.empty());
    // 目标槽位已写入
    EXPECT_EQ(gd.patrolSlots[1].discipleId, "1");
}

TEST_F(PatrolTxFixture, AssignPatrolKeepsNonTargetMissionFieldsIntact) {
    addDisciple("1");
    addDisciple("2");
    seedPatrolSlots(1, "tower_1");
    seedAllSlotFamilies("1");
    // 同一任务内混入另一弟子 + 名称/ID 双列
    core_->state().gameData.activeMissions[0].discipleIds.push_back("2");
    core_->state().gameData.activeMissions[0].discipleNames.push_back("任务2");
    core_->state().gameData.activeMissions[0].startMonth = 37;

    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);

    const auto& mission = core_->state().gameData.activeMissions[0];
    ASSERT_EQ(mission.discipleIds.size(), 1u);
    EXPECT_EQ(mission.discipleIds[0], "2");
    ASSERT_EQ(mission.discipleNames.size(), 1u);
    EXPECT_EQ(mission.discipleNames[0], "任务2");
    // 非目标弟子的任务标量字段逐字段不变（Lite↔全量互转保真）
    EXPECT_EQ(mission.startMonth, 37);
    EXPECT_EQ(mission.id, "mission_1");
}

TEST_F(PatrolTxFixture, AssignPatrolSameDiscipleIsIdempotent) {
    addDisciple("1");
    seedPatrolSlots(1, "tower_1");
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);
    const auto after1 = core_->state().gameData.patrolSlots;

    const auto r = patrol_tx::assignPatrolTx(core_->state(), "1", 0);
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_TRUE(r.releasedOccupantId.empty());
    EXPECT_EQ(core_->state().gameData.patrolSlots, after1);
}

// ── 巡逻移除 ────────────────────────────────────────────────────────────

TEST_F(PatrolTxFixture, RemovePatrolFailureArmsAndEmptyNoop) {
    addDisciple("1");
    seedPatrolSlots(1, "tower_1");

    auto r = patrol_tx::removePatrolTx(core_->state(), -1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    r = patrol_tx::removePatrolTx(core_->state(), 3);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    r = patrol_tx::removePatrolTx(core_->state(), 0);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.removedDiscipleId.empty());
}

TEST_F(PatrolTxFixture, RemovePatrolKeepsIndexAndBuilding) {
    addDisciple("1");
    seedPatrolSlots(1, "tower_9");
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);

    const auto r = patrol_tx::removePatrolTx(core_->state(), 0);
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedDiscipleId, "1");
    const auto& slot = core_->state().gameData.patrolSlots[0];
    EXPECT_TRUE(slot.discipleId.empty());
    EXPECT_TRUE(slot.discipleName.empty());
    EXPECT_EQ(slot.index, 0);
    EXPECT_EQ(slot.buildingInstanceId, "tower_9");
}

// ── 巡逻交换 ────────────────────────────────────────────────────────────

TEST_F(PatrolTxFixture, SwapPatrolFailureArmsAndSameIndexNoop) {
    addDisciple("1");
    addDisciple("2");
    seedPatrolSlots(2, "tower_1");
    const auto before = core_->state().gameData.patrolSlots;

    auto r = patrol_tx::swapPatrolTx(core_->state(), -1, 0);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    r = patrol_tx::swapPatrolTx(core_->state(), 0, 9);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");
    r = patrol_tx::swapPatrolTx(core_->state(), 1, 1);
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(core_->state().gameData.patrolSlots, before);
}

TEST_F(PatrolTxFixture, SwapPatrolExchangesOccupantsAndDisplayFields) {
    addDisciple("1", 9);
    addDisciple("2", 7);
    seedPatrolSlots(2, "tower_1");
    core_->state().gameData.patrolSlots[0].buildingInstanceId = "tower_A";
    core_->state().gameData.patrolSlots[1].buildingInstanceId = "tower_B";
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "2", 1).base.ok);

    const auto r = patrol_tx::swapPatrolTx(core_->state(), 0, 1);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_EQ(r.fromDiscipleId, "1");
    EXPECT_EQ(r.toDiscipleId, "2");

    const auto& slots = core_->state().gameData.patrolSlots;
    EXPECT_EQ(slots[0].discipleId, "2");
    EXPECT_EQ(slots[0].discipleName, "弟子2");
    EXPECT_EQ(slots[0].discipleRealm, "金丹1层");
    EXPECT_EQ(slots[0].portraitRes, "portrait_2");
    EXPECT_EQ(slots[0].buildingInstanceId, "tower_A");  // 各自保留原槽 building
    EXPECT_EQ(slots[1].discipleId, "1");
    EXPECT_EQ(slots[1].discipleRealm, "炼气1层");
    EXPECT_EQ(slots[1].buildingInstanceId, "tower_B");
}

TEST_F(PatrolTxFixture, SwapPatrolWithOneEmptyIsMoveSemantics) {
    addDisciple("1");
    seedPatrolSlots(2, "tower_1");
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);

    const auto r = patrol_tx::swapPatrolTx(core_->state(), 0, 1);
    EXPECT_TRUE(r.base.ok);
    EXPECT_EQ(r.fromDiscipleId, "1");
    EXPECT_TRUE(r.toDiscipleId.empty());
    const auto& slots = core_->state().gameData.patrolSlots;
    EXPECT_TRUE(slots[0].discipleId.empty());
    EXPECT_EQ(slots[1].discipleId, "1");
}

// ── 批量分配 ────────────────────────────────────────────────────────────

TEST_F(PatrolTxFixture, AutoAssignValidationArmsZeroWrite) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    addDisciple("3");
    seedPatrolSlots(3, "tower_1");
    const auto before = core_->state().gameData.patrolSlots;

    using Pair = std::pair<int32_t, std::string>;

    // 重复槽位索引
    auto r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, "1"}, Pair{0, "3"}});
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");

    // 同一弟子多槽
    r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, "1"}, Pair{1, "1"}});
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");

    // 越界
    r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{9, "1"}});
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SlotInvalid");

    // 弟子不存在
    r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, "404"}});
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");

    // 弟子已死
    r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, "2"}});
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");

    // 失败零写入
    EXPECT_EQ(core_->state().gameData.patrolSlots, before);
}

TEST_F(PatrolTxFixture, AutoAssignAssignsAndReportsReceipts) {
    addDisciple("1");
    addDisciple("3");
    seedPatrolSlots(3, "tower_1");
    using Pair = std::pair<int32_t, std::string>;

    const auto r = patrol_tx::autoAssignPatrolTx(
        core_->state(), {Pair{0, "1"}, Pair{2, "3"}});
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    // releasedIds = 写段 1 的两个新分配者（逐槽序，不去重）
    ASSERT_EQ(r.releasedIds.size(), 2u);
    EXPECT_EQ(r.releasedIds[0], "1");
    EXPECT_EQ(r.releasedIds[1], "3");
    ASSERT_EQ(r.confirmedIds.size(), 2u);
    EXPECT_EQ(r.confirmedIds[0], "1");
    EXPECT_EQ(r.confirmedIds[1], "3");
    ASSERT_EQ(r.confirmedIndexes.size(), 2u);
    EXPECT_EQ(r.confirmedIndexes[0], 0);
    EXPECT_EQ(r.confirmedIndexes[1], 2);

    const auto& slots = core_->state().gameData.patrolSlots;
    EXPECT_EQ(slots[0].discipleId, "1");
    EXPECT_TRUE(slots[1].discipleId.empty());
    EXPECT_EQ(slots[2].discipleId, "3");
    EXPECT_EQ(slots[0].buildingInstanceId, "tower_1");
    EXPECT_EQ(slots[2].discipleName, "弟子3");
}

TEST_F(PatrolTxFixture, AutoAssignEmptyIdClearsSlotAndReportsReleased) {
    addDisciple("1");
    seedPatrolSlots(2, "tower_1");
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);

    using Pair = std::pair<int32_t, std::string>;
    const auto r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, ""}});
    EXPECT_TRUE(r.base.ok);
    ASSERT_EQ(r.releasedIds.size(), 1u);
    EXPECT_EQ(r.releasedIds[0], "1");
    EXPECT_TRUE(r.confirmedIds.empty());
    const auto& slot = core_->state().gameData.patrolSlots[0];
    EXPECT_TRUE(slot.discipleId.empty());
    EXPECT_EQ(slot.index, 0);
    EXPECT_EQ(slot.buildingInstanceId, "tower_1");
}

TEST_F(PatrolTxFixture, AutoAssignReleasesDisplacedOccupant) {
    addDisciple("1");
    addDisciple("2");
    seedPatrolSlots(2, "tower_1");
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);

    using Pair = std::pair<int32_t, std::string>;
    const auto r = patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, "2"}});
    EXPECT_TRUE(r.base.ok);
    // 写段 1: "2"（新分配者）；写段 2: 原 occupant "1"
    ASSERT_EQ(r.releasedIds.size(), 2u);
    EXPECT_EQ(r.releasedIds[0], "2");
    EXPECT_EQ(r.releasedIds[1], "1");
    EXPECT_EQ(core_->state().gameData.patrolSlots[0].discipleId, "2");
}

// ── 覆写族 ──────────────────────────────────────────────────────────────

TEST_F(PatrolTxFixture, UpdatePatrolConfigsOverwritesWholeTable) {
    auto& gd = core_->state().gameData;
    EXPECT_TRUE(gd.patrolConfigs.empty());

    PatrolConfig a;   // 默认值：requireFullStatus=true / maxBeastCount=1
    PatrolConfig b;
    b.requireFullStatus = false;
    b.maxBeastCount = 4;
    b.targetRealms = {7, 8};
    const auto r = patrol_tx::updatePatrolConfigsTx(core_->state(), {a, b});
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    ASSERT_EQ(gd.patrolConfigs.size(), 2u);
    EXPECT_TRUE(gd.patrolConfigs[0].requireFullStatus);
    EXPECT_EQ(gd.patrolConfigs[0].maxBeastCount, 1);
    EXPECT_FALSE(gd.patrolConfigs[1].requireFullStatus);
    EXPECT_EQ(gd.patrolConfigs[1].maxBeastCount, 4);
    EXPECT_EQ(gd.patrolConfigs[1].targetRealms, (std::vector<int32_t>{7, 8}));

    // 同内容再写：changed=false（整表相等性判定）
    const auto again = patrol_tx::updatePatrolConfigsTx(core_->state(), {a, b});
    EXPECT_TRUE(again.base.ok);
    EXPECT_FALSE(again.changed);

    // 空表覆写 = 清空（Kotlin copy(patrolConfigs = emptyList()) 语义）
    const auto cleared = patrol_tx::updatePatrolConfigsTx(core_->state(), {});
    EXPECT_TRUE(cleared.base.ok);
    EXPECT_TRUE(cleared.changed);
    EXPECT_TRUE(gd.patrolConfigs.empty());
}

TEST_F(PatrolTxFixture, UpdateSpiritMineSlotsOverwritesWholeTable) {
    auto& gd = core_->state().gameData;
    std::vector<SpiritMineSlot> slots(2);
    slots[0].index = 0;
    slots[1].index = 1;
    slots[1].discipleId = "1";
    slots[1].discipleName = "矿工1";

    const auto r = patrol_tx::updateSpiritMineSlotsTx(core_->state(), slots);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    ASSERT_EQ(gd.spiritMineSlots.size(), 2u);
    EXPECT_EQ(gd.spiritMineSlots[1].discipleId, "1");

    // 同内容再写：changed=false（引用稳定语义）
    const auto again = patrol_tx::updateSpiritMineSlotsTx(core_->state(), slots);
    EXPECT_TRUE(again.base.ok);
    EXPECT_FALSE(again.changed);
}

TEST_F(PatrolTxFixture, ValidateAndFixSpiritMineRebuildsSlots) {
    addDisciple("1");
    seedBuilding("mine_1", "灵矿场");
    seedBuilding("mine_2", "灵矿场");
    seedBuilding("hall_1", "议事堂");  // 非矿场：不参与

    auto& gd = core_->state().gameData;
    // 预置损坏态：孤儿引用 + 错误 index / buildingInstanceId / sectId
    SpiritMineSlot orphan;
    orphan.index = 99;
    orphan.discipleId = "404";  // 不在弟子表
    orphan.discipleName = "幽灵";
    orphan.buildingInstanceId = "stale";
    orphan.sectId = "other_sect";
    gd.spiritMineSlots.push_back(orphan);

    SpiritMineSlot real;
    real.index = 42;
    real.discipleId = "1";
    real.discipleName = "矿工1";
    real.buildingInstanceId = "stale";
    real.sectId = "other_sect";
    gd.spiritMineSlots.push_back(real);

    const auto r = patrol_tx::validateAndFixSpiritMineDataTx(core_->state());
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    // Kotlin 同源：仅**预置的 2 个槽** sectId 失配（"other_sect" → "sect_player"）；
    // 新建 4 槽按 `existing == null` 分支直接以 building.sectId 初始化 → 不计数
    EXPECT_EQ(r.alignedCount, 2);
    ASSERT_EQ(gd.spiritMineSlots.size(), 6u);
    for (int32_t i = 0; i < 6; ++i) {
        EXPECT_EQ(gd.spiritMineSlots[static_cast<std::size_t>(i)].index, i);
        EXPECT_EQ(gd.spiritMineSlots[static_cast<std::size_t>(i)].sectId, "sect_player");
    }
    // 前 3 槽归 mine_1，后 3 槽归 mine_2
    EXPECT_EQ(gd.spiritMineSlots[0].buildingInstanceId, "mine_1");
    EXPECT_EQ(gd.spiritMineSlots[2].buildingInstanceId, "mine_1");
    EXPECT_EQ(gd.spiritMineSlots[3].buildingInstanceId, "mine_2");
    EXPECT_EQ(gd.spiritMineSlots[5].buildingInstanceId, "mine_2");
    // 孤儿引用已清空
    EXPECT_TRUE(gd.spiritMineSlots[0].discipleId.empty());
    EXPECT_TRUE(gd.spiritMineSlots[0].discipleName.empty());
    // 有效引用保留
    EXPECT_EQ(gd.spiritMineSlots[1].discipleId, "1");
    EXPECT_EQ(gd.spiritMineSlots[1].discipleName, "矿工1");
}

TEST_F(PatrolTxFixture, ValidateAndFixSpiritMineNoMineIsNoChange) {
    auto& gd = core_->state().gameData;
    gd.spiritMineSlots.clear();
    seedBuilding("hall_1", "议事堂");

    const auto r = patrol_tx::validateAndFixSpiritMineDataTx(core_->state());
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(r.alignedCount, 0);
    EXPECT_TRUE(gd.spiritMineSlots.empty());
}

TEST_F(PatrolTxFixture, ValidateAndFixSpiritMineIdempotentOnCleanState) {
    addDisciple("1");
    seedBuilding("mine_1", "灵矿场");
    ASSERT_TRUE(patrol_tx::validateAndFixSpiritMineDataTx(core_->state()).base.ok);

    const auto second = patrol_tx::validateAndFixSpiritMineDataTx(core_->state());
    EXPECT_TRUE(second.base.ok);
    EXPECT_FALSE(second.changed);
    EXPECT_EQ(second.alignedCount, 0);
}

TEST_F(PatrolTxFixture, UpdateYearlySalaryOverwritesMap) {
    auto& gd = core_->state().gameData;
    const auto r = patrol_tx::updateYearlySalaryTx(core_->state(), {{9, 100}, {8, 250}});
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    ASSERT_EQ(gd.yearlySalary.size(), 2u);
    EXPECT_EQ(gd.yearlySalary[9], 100);
    EXPECT_EQ(gd.yearlySalary[8], 250);

    const auto again = patrol_tx::updateYearlySalaryTx(core_->state(), {{9, 100}, {8, 250}});
    EXPECT_FALSE(again.changed);
}

// ── RNG 红线：全分区快照差分 + 双运行逐位一致 ────────────────────────────

TEST_F(PatrolTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    addDisciple("1");
    addDisciple("2");
    seedBuilding("res_b1", "住所");
    seedBuilding("mine_1", "灵矿场");
    seedResidenceSlots("res_b1", 2);
    seedPatrolSlots(3, "tower_1");
    const auto baseline = rngSnapshot();

    using Pair = std::pair<int32_t, std::string>;
    ASSERT_TRUE(patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1").base.ok);
    ASSERT_TRUE(patrol_tx::removeFromResidenceTx(core_->state(), "res_b1", 0).base.ok);
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "1", 0).base.ok);
    ASSERT_TRUE(patrol_tx::assignPatrolTx(core_->state(), "2", 1).base.ok);
    ASSERT_TRUE(patrol_tx::swapPatrolTx(core_->state(), 0, 1).base.ok);
    ASSERT_TRUE(patrol_tx::removePatrolTx(core_->state(), 1).base.ok);
    ASSERT_TRUE(patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{2, "1"}}).base.ok);
    ASSERT_TRUE(patrol_tx::updatePatrolConfigsTx(core_->state(), {PatrolConfig{}}).base.ok);
    ASSERT_TRUE(patrol_tx::updateSpiritMineSlotsTx(core_->state(), {}).base.ok);
    ASSERT_TRUE(patrol_tx::validateAndFixSpiritMineDataTx(core_->state()).base.ok);
    ASSERT_TRUE(patrol_tx::updateYearlySalaryTx(core_->state(), {{9, 1}}).base.ok);

    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(PatrolTxFixture, ZeroRngFamilyLeavesRngStatesUntouchedOnFailureArms) {
    addDisciple("1");
    seedPatrolSlots(1, "tower_1");
    const auto baseline = rngSnapshot();

    using Pair = std::pair<int32_t, std::string>;
    EXPECT_FALSE(patrol_tx::assignPatrolTx(core_->state(), "404", 0).base.ok);
    EXPECT_FALSE(patrol_tx::removePatrolTx(core_->state(), 9).base.ok);
    EXPECT_FALSE(patrol_tx::swapPatrolTx(core_->state(), 0, 9).base.ok);
    EXPECT_FALSE(patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{9, "1"}}).base.ok);
    EXPECT_FALSE(patrol_tx::removeFromResidenceTx(core_->state(), "res_b1", 9).base.ok);
    EXPECT_FALSE(
        patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "404").base.ok);

    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(PatrolTxFixture, DoubleRunBitwiseIdentical) {
    using Pair = std::pair<int32_t, std::string>;

    auto run = [&]() {
        addDisciple("1");
        addDisciple("2");
        seedBuilding("res_b1", "住所");
        seedBuilding("mine_1", "灵矿场");
        seedResidenceSlots("res_b1", 1);
        seedPatrolSlots(2, "tower_1");
        patrol_tx::assignToResidenceTx(core_->state(), "res_b1", 0, "1");
        patrol_tx::assignPatrolTx(core_->state(), "1", 0);
        patrol_tx::swapPatrolTx(core_->state(), 0, 1);
        patrol_tx::autoAssignPatrolTx(core_->state(), {Pair{0, "2"}});
        patrol_tx::validateAndFixSpiritMineDataTx(core_->state());
        patrol_tx::updatePatrolConfigsTx(core_->state(), {PatrolConfig{}, PatrolConfig{}});
        patrol_tx::updateYearlySalaryTx(core_->state(), {{9, 7}});
        return core_->exportStateJson();
    };

    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 信封级：execute 通道（Kotlin 回退臂契约）────────────────────────────

TEST_F(PatrolTxFixture, DispatchEnvelopeSuccessAndFailure) {
    addDisciple("1");
    seedPatrolSlots(2, "tower_1");

    // 成功信封（C++ ok() → {"status":"success","data":{...}}）
    auto env = exec(action::PATROL_ASSIGN,
                    {{"discipleId", "1"}, {"globalIndex", 0}});
    ASSERT_TRUE(env.contains("status")) << env.dump();
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    ASSERT_TRUE(env.contains("data")) << env.dump();
    EXPECT_TRUE(env.at("data").at("changed").get<bool>());
    EXPECT_EQ(core_->state().gameData.patrolSlots[0].discipleId, "1");

    // 失败信封（弟子不存在 → Kotlin 回退臂重执行校验链）
    env = exec(action::PATROL_ASSIGN, {{"discipleId", "404"}, {"globalIndex", 0}});
    ASSERT_TRUE(env.contains("status")) << env.dump();
    EXPECT_EQ(env.at("status").get<std::string>(), "failure") << env.dump();
    EXPECT_EQ(env.at("code").get<std::string>(), "NotFound") << env.dump();

    // 住所分配成功信封
    seedBuilding("res_b1", "住所");
    seedResidenceSlots("res_b1", 1);
    env = exec(action::PATROL_ASSIGN_RESIDENCE,
               {{"buildingInstanceId", "res_b1"}, {"slotIndex", 0}, {"discipleId", "1"}});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_EQ(core_->state().gameData.residenceSlots[0].discipleId, "1");

    // 批量分配信封（三列同序回执）
    env = exec(action::PATROL_AUTO_ASSIGN,
               {{"assignments", {{{"globalIndex", 1}, {"discipleId", "1"}}}}});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_EQ(env.at("data").at("releasedIds")[0].get<std::string>(), "1");
    EXPECT_EQ(env.at("data").at("confirmedIds")[0].get<std::string>(), "1");
    EXPECT_EQ(env.at("data").at("confirmedIndexes")[0].get<int32_t>(), 1);

    // 巡逻移除信封
    env = exec(action::PATROL_REMOVE, {{"globalIndex", 1}});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_EQ(env.at("data").at("removedDiscipleId").get<std::string>(), "1");

    // 矿场自愈信封（alignedCount 面）
    seedBuilding("mine_1", "灵矿场");
    env = exec(action::PATROL_FIX_SPIRIT_MINE, {});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_TRUE(env.at("data").contains("alignedCount")) << env.dump();

    // 年俸覆写信封
    env = exec(action::PATROL_UPDATE_YEARLY_SALARY,
               {{"yearlySalaryEntries", {{{"realm", 9}, {"amount", 123}}}}});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_EQ(core_->state().gameData.yearlySalary[9], 123);
}

}  // namespace
}  // namespace gamecore

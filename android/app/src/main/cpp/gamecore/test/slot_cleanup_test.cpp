#include <gtest/gtest.h>

#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/slot_cleanup.h"

namespace gamecore::system {
namespace {

using gamecore::state::ActiveMissionLite;
using gamecore::state::BattleTeam;
using gamecore::state::CaveExplorationTeam;
using gamecore::state::ElderSlots;
using gamecore::state::LibrarySlot;
using gamecore::state::PatrolSlot;
using gamecore::state::ProductionSlot;
using gamecore::state::ResidenceSlot;
using gamecore::state::SpiritMineSlot;
using gamecore::state::WarehouseGarrisonSlot;
using gamecore::state::WorldSect;

SlotCleanupInput sampleInput() {
    SlotCleanupInput in;
    // 灵矿/藏经阁/巡逻/仓库驻防
    SpiritMineSlot mine;
    mine.discipleId = "1";
    mine.discipleName = "张三";
    in.spiritMineSlots.push_back(mine);
    SpiritMineSlot mine2;
    mine2.discipleId = "2";
    mine2.discipleName = "李四";
    in.spiritMineSlots.push_back(mine2);
    LibrarySlot lib;
    lib.discipleId = "1";
    lib.discipleName = "张三";
    in.librarySlots.push_back(lib);
    PatrolSlot patrol;
    patrol.discipleId = "1";
    in.patrolSlots.push_back(patrol);
    WarehouseGarrisonSlot warehouse;
    warehouse.discipleId = "1";
    warehouse.discipleName = "张三";
    in.warehouseGarrisons.push_back(warehouse);
    // 长老槽位
    ElderSlots elder;
    elder.viceSectMaster = "1";
    elder.innerElder = "2";
    gamecore::state::DirectDiscipleSlot preaching;
    preaching.index = 0;
    preaching.discipleId = "1";
    gamecore::state::DirectDiscipleSlot preaching2;
    preaching2.index = 1;
    preaching2.discipleId = "2";
    elder.preachingMasters = {preaching, preaching2};
    in.elderSlots = elder;
    // 住所
    ResidenceSlot residence;
    residence.discipleId = "1";
    residence.discipleName = "张三";
    in.residenceSlots = {residence};
    // 血炼
    gamecore::state::BloodRefinementProgress progress;
    progress.discipleId = "1";
    in.activeBloodRefinements["br-1"] = progress;
    // 战斗队伍
    BattleTeam team;
    gamecore::state::BattleTeamSlot slot;
    slot.index = 0;
    slot.discipleId = "1";
    slot.discipleName = "张三";
    gamecore::state::BattleTeamSlot slot2;
    slot2.index = 1;
    slot2.discipleId = "2";
    team.slots = {slot, slot2};
    in.battleTeams = {team};
    // 世界地图（玩家宗门驻防）
    WorldSect sect;
    sect.id = "player";
    sect.isPlayerSect = true;
    gamecore::state::GarrisonSlot gs;
    gs.index = 3;
    gs.discipleId = "1";
    gs.discipleName = "张三";
    sect.garrisonSlots = {gs};
    in.worldMapSects = {sect};
    // 生产槽位
    ProductionSlot prod;
    prod.id = "p1";
    prod.assignedDiscipleId = "1";
    prod.assignedDiscipleName = "张三";
    in.productionSlots = {prod};
    // 洞府探索队
    CaveExplorationTeam cave;
    cave.memberIds = {"1", "2"};
    cave.memberNames = {"张三", "李四"};
    in.caveExplorationTeams = {cave};
    // 悬赏任务
    ActiveMissionLite mission;
    mission.id = "m1";
    mission.discipleIds = {"1", "2"};
    mission.discipleNames = {"张三", "李四"};
    in.activeMissions = {mission};
    return in;
}

TEST(SlotCleanupTest, ClearsSimpleSlots) {
    const auto out = clearAllSlotsDataOnly(sampleInput(), "1", false);
    EXPECT_EQ(out.spiritMineSlots.size(), 2);
    EXPECT_TRUE(out.spiritMineSlots[0].discipleId.empty());
    EXPECT_TRUE(out.spiritMineSlots[0].discipleName.empty());
    EXPECT_EQ(out.spiritMineSlots[1].discipleId, "2");  // 其他弟子不受影响
    EXPECT_TRUE(out.librarySlots[0].discipleId.empty());
    EXPECT_TRUE(out.patrolSlots[0].discipleId.empty());
    EXPECT_TRUE(out.warehouseGarrisons[0].discipleId.empty());
}

TEST(SlotCleanupTest, ClearsElderSlotsAndLists) {
    const auto out = clearAllSlotsDataOnly(sampleInput(), "1", false);
    EXPECT_TRUE(out.elderSlots.viceSectMaster.empty());
    EXPECT_EQ(out.elderSlots.innerElder, "2");  // 其他长老保留
    ASSERT_EQ(out.elderSlots.preachingMasters.size(), 2);
    EXPECT_TRUE(out.elderSlots.preachingMasters[0].discipleId.empty());
    EXPECT_EQ(out.elderSlots.preachingMasters[0].index, 0);  // 降级保留索引
    EXPECT_EQ(out.elderSlots.preachingMasters[1].discipleId, "2");
}

TEST(SlotCleanupTest, ResidenceRespectsIncludeFlag) {
    const auto without = clearAllSlotsDataOnly(sampleInput(), "1", false);
    EXPECT_EQ(without.residenceSlots[0].discipleId, "1");  // 工作分配保留住所
    const auto with = clearAllSlotsDataOnly(sampleInput(), "1", true);
    EXPECT_TRUE(with.residenceSlots[0].discipleId.empty());  // 死亡/逐出清住所
}

TEST(SlotCleanupTest, ClearsBloodRefinementsAndBattleTeams) {
    const auto out = clearAllSlotsDataOnly(sampleInput(), "1", false);
    EXPECT_TRUE(out.activeBloodRefinements.empty());
    ASSERT_EQ(out.battleTeams.size(), 1);
    EXPECT_TRUE(out.battleTeams[0].slots[0].discipleId.empty());
    EXPECT_TRUE(out.battleTeams[0].slots[0].isAlive);
    EXPECT_EQ(out.battleTeams[0].slots[1].discipleId, "2");  // 其他槽位保留
}

TEST(SlotCleanupTest, ClearsPlayerSectGarrisonOnly) {
    SlotCleanupInput in = sampleInput();
    // 非玩家宗门驻防不受影响
    WorldSect ai;
    ai.id = "ai-1";
    ai.isPlayerSect = false;
    gamecore::state::GarrisonSlot gs;
    gs.index = 1;
    gs.discipleId = "1";
    ai.garrisonSlots = {gs};
    in.worldMapSects.push_back(ai);
    const auto out = clearAllSlotsDataOnly(in, "1", false);
    EXPECT_TRUE(out.worldMapSects[0].garrisonSlots[0].discipleId.empty());
    EXPECT_EQ(out.worldMapSects[0].garrisonSlots[0].index, 3);  // 保留索引
    EXPECT_EQ(out.worldMapSects[1].garrisonSlots[0].discipleId, "1");  // AI 不动
}

TEST(SlotCleanupTest, ClearsProductionAndCaveAndMissions) {
    const auto out = clearAllSlotsDataOnly(sampleInput(), "1", false);
    EXPECT_FALSE(out.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_TRUE(out.productionSlots[0].assignedDiscipleName.empty());
    // 洞府探索队移除死者成员
    ASSERT_EQ(out.caveExplorationTeams.size(), 1);
    EXPECT_EQ(out.caveExplorationTeams[0].memberIds.size(), 1);
    EXPECT_EQ(out.caveExplorationTeams[0].memberIds[0], "2");
    EXPECT_EQ(out.caveExplorationTeams[0].memberNames[0], "李四");
    EXPECT_NE(out.caveExplorationTeams[0].status, "COMPLETED");  // 仍有存活成员
    // 悬赏任务移除成员
    ASSERT_EQ(out.activeMissions.size(), 1);
    EXPECT_EQ(out.activeMissions[0].discipleIds.size(), 1);
    EXPECT_EQ(out.activeMissions[0].discipleIds[0], "2");
}

TEST(SlotCleanupTest, CaveTeamAllDeadBecomesCompleted) {
    SlotCleanupInput in = sampleInput();
    CaveExplorationTeam cave;
    cave.memberIds = {"1"};
    cave.memberNames = {"张三"};
    in.caveExplorationTeams = {cave};
    const auto out = clearAllSlotsDataOnly(in, "1", false);
    ASSERT_EQ(out.caveExplorationTeams.size(), 1);
    EXPECT_TRUE(out.caveExplorationTeams[0].memberIds.empty());
    EXPECT_EQ(out.caveExplorationTeams[0].status, "COMPLETED");
}

TEST(SlotCleanupTest, MissionAllDeadKeepsEmptyLists) {
    SlotCleanupInput in = sampleInput();
    ActiveMissionLite mission;
    mission.id = "m2";
    mission.discipleIds = {"1"};
    mission.discipleNames = {"张三"};
    in.activeMissions = {mission};
    const auto out = clearAllSlotsDataOnly(in, "1", false);
    ASSERT_EQ(out.activeMissions.size(), 1);
    EXPECT_TRUE(out.activeMissions[0].discipleIds.empty());
    EXPECT_TRUE(out.activeMissions[0].discipleNames.empty());
}

TEST(SlotCleanupTest, UnrelatedDiscipleNoChange) {
    const auto out = clearAllSlotsDataOnly(sampleInput(), "999", true);
    EXPECT_EQ(out.spiritMineSlots[0].discipleId, "1");
    EXPECT_EQ(out.elderSlots.viceSectMaster, "1");
    EXPECT_EQ(out.residenceSlots[0].discipleId, "1");
    EXPECT_EQ(out.caveExplorationTeams[0].memberIds.size(), 2);
    EXPECT_EQ(out.activeMissions[0].discipleIds.size(), 2);
    EXPECT_TRUE(out.productionSlots[0].assignedDiscipleId.has_value());  // 保留原弟子
    EXPECT_EQ(*out.productionSlots[0].assignedDiscipleId, "1");
}

}  // namespace
}  // namespace gamecore::system

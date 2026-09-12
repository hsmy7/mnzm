// 弟子强化派生 map 统一收口守卫（审计 P2-7 + P3-4 / 方案 D3 改动 4）
//
// 锁定的不变量（R2：弟子移除链的派生 map 清理有唯一收口点）：
//   1. eraseDiscipleDerivedMaps 清目标弟子四表键（血炼三 map + 功法熟练度）；
//   2. 其他弟子的键不受影响（按 id 精确清键）；
//   3. 不存在的键为 no-op（幂等，可重复调用）。

#include <gtest/gtest.h>

#include <string>

#include "gamecore/state/models.h"
#include "gamecore/system/blood_refinement.h"

namespace gamecore::system {
namespace {

TEST(DiscipleDerivedMapsTest, ClearsExactlyTheDiscipleKeys) {
    state::GameData gd;
    gd.bloodRefinementBonusTotals["d1"] = state::BloodRefinementBonusTotal{};
    gd.bloodRefinementBonusTotals["d2"] = state::BloodRefinementBonusTotal{};
    gd.bloodRefinementPctTotals["d1"] = state::BloodRefinementPctTotal{};
    gd.bloodRefinementPctTotals["d2"] = state::BloodRefinementPctTotal{};
    gd.bloodRefinements["d1"] = {"mat-a", "mat-b"};
    gd.bloodRefinements["d2"] = {"mat-c"};
    gd.manualProficiencies["d1"] = {};
    gd.manualProficiencies["d2"] = {};

    eraseDiscipleDerivedMaps(gd, "d1");
    eraseDiscipleDerivedMaps(gd, "d1");  // 幂等

    EXPECT_EQ(gd.bloodRefinementBonusTotals.count("d1"), 0u);
    EXPECT_EQ(gd.bloodRefinementPctTotals.count("d1"), 0u);
    EXPECT_EQ(gd.bloodRefinements.count("d1"), 0u);
    EXPECT_EQ(gd.manualProficiencies.count("d1"), 0u);
    // 其他弟子零波及
    EXPECT_EQ(gd.bloodRefinementBonusTotals.count("d2"), 1u);
    EXPECT_EQ(gd.bloodRefinementPctTotals.count("d2"), 1u);
    EXPECT_EQ(gd.bloodRefinements.count("d2"), 1u);
    EXPECT_EQ(gd.manualProficiencies.count("d2"), 1u);
}

TEST(DiscipleDerivedMapsTest, MissingKeysAreNoOp) {
    state::GameData gd;
    eraseDiscipleDerivedMaps(gd, "ghost");
    EXPECT_TRUE(gd.bloodRefinementBonusTotals.empty());
    EXPECT_TRUE(gd.bloodRefinementPctTotals.empty());
    EXPECT_TRUE(gd.bloodRefinements.empty());
    EXPECT_TRUE(gd.manualProficiencies.empty());
}

}  // namespace
}  // namespace gamecore::system

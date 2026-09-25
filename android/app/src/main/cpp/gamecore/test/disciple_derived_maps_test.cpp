// 弟子派生 map 单键清理守卫（G04 角色卡池重构批改写）
//
// 守护目标：功法熟练度 map（manualProficiencies）按弟子 id 的单键清理
// 语义——清理点在 disciple_tx 卸学链（同名键过滤，空则删键）。
//
// 锁定的不变量：
//   1. 卸学后目标弟子的熟练度条目按实例 id 过滤，列表为空则整键删除；
//   2. 其他弟子的键不受影响（按 id 精确清键）；
//   3. 无熟练度条目时卸学对 map 为 no-op（不新建键）。

#include <gtest/gtest.h>

#include <memory>
#include <string>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/models.h"
#include "gamecore/system/disciple_tx.h"

namespace gamecore::system {
namespace {

using gamecore::state::Disciple;
using gamecore::state::ManualProficiencyData;
using gamecore::state::ManualStack;

class DiscipleDerivedMapsFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    std::size_t addDisciple(const std::string& id) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = 9;
        d.realmLayer = 1;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.status = "IDLE";
        d.currentHp = 100;
        d.currentMp = 50;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    void addManualStack(const std::string& id) {
        ManualStack m;
        m.id = id;
        m.name = "功法" + id;
        m.rarity = 2;
        m.type = "BODY";
        m.minRealm = 9;
        m.quantity = 2;
        m.stats = {{"hp", 5}, {"mp", 3}};
        core_->state().manualStacks.push_back(m);
    }

    ManualProficiencyData proficiency(const std::string& manualId) {
        ManualProficiencyData prof;
        prof.manualId = manualId;
        prof.manualName = "功法" + manualId;
        prof.proficiency = 50.0;
        return prof;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

TEST_F(DiscipleDerivedMapsFixture, UnlearnErasesEmptyKeyAndKeepsOtherDisciples) {
    const std::size_t row = addDisciple("1");
    addDisciple("2");  // 对照弟子：零波及
    addManualStack("m1");
    ASSERT_TRUE(disciple_tx::learnManualTransaction(core_->state(), "1", "m1").ok);
    const std::string instId = core_->state().disciples.manualIds[row][0];
    auto& profMap = core_->state().gameData.manualProficiencies;
    profMap["1"] = {proficiency(instId)};
    profMap["2"] = {proficiency("other")};

    const auto r =
        disciple_tx::unlearnManualTransaction(core_->state(), "1", instId);
    ASSERT_TRUE(r.ok);

    // 目标弟子唯一条目被过滤 → 整键删除；对照弟子键不动
    EXPECT_EQ(profMap.count("1"), 0u);
    EXPECT_EQ(profMap.count("2"), 1u);
}

TEST_F(DiscipleDerivedMapsFixture, UnlearnWithoutProficiencyEntryIsMapNoOp) {
    const std::size_t row = addDisciple("1");
    addManualStack("m1");
    ASSERT_TRUE(disciple_tx::learnManualTransaction(core_->state(), "1", "m1").ok);
    const std::string instId = core_->state().disciples.manualIds[row][0];

    const auto r =
        disciple_tx::unlearnManualTransaction(core_->state(), "1", instId);
    ASSERT_TRUE(r.ok);

    // 无熟练度条目：不新建键（missing keys no-op 不变量延续）
    EXPECT_TRUE(core_->state().gameData.manualProficiencies.empty());
}

}  // namespace
}  // namespace gamecore::system

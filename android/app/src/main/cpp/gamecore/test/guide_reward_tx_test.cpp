// ============================================================
// guide_reward_tx_test — 引导领奖事务守护（W4-D/D2 · w3-11 下沉）
//
// 守护目标：guide_reward_tx.h 与 Kotlin claimGuideReward 语义逐位一致——
//   - 判定序：任务存在 → 未领取 → 条件全满足 → 可行性预检 → 抽取 → 发放
//   - 失败零写入 + 零抽取（UNKNOWN_TASK / ALREADY_CLAIMED /
//     CONDITIONS_NOT_MET / STORAGE_FULL 四码；SYSTEM 分区快照不变）
//   - 成功路径：SYSTEM 2×nextLong 造 UUID（Java UUID.toString 复刻锚点）+
//     凡品储物袋×2（rarity=1）+ 已领取标记
//   - 条件求值：建造计数 max(累计, 存量) 语义 / 长老单值槽 / 亲传列表 /
//     集合槽位 / 计数器 / 灵植 / 血炼键数
//   - 注册表形状锚点（25 任务 / 全部 rewardQuantity=2）
//   - 信封级：INVALID_PARAMS 参数面 + execute 通道可达
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
#include "gamecore/system/guide_reward_tx.h"

namespace gamecore {
namespace {

using nlohmann::json;

class GuideRewardTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        clock_ = std::make_unique<FixedClock>();
        logger_ = std::make_unique<ConsoleLogger>();
        core_ = std::make_unique<GameCore>(clock_.get(), logger_.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }
    void TearDown() override {
        core_->shutdown();
        core_.reset();
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// SYSTEM 分区（键 3）快照——抽取计数守卫用
    int64_t systemSnapshot() const {
        const json j = json::parse(core_->exportStateJson());
        return j.at("gameData").at("rngStates").at("3").get<int64_t>();
    }

    /// 播种"任务 8 可领"：天枢殿×1 + 自动采矿开启计数 ≥1
    void seedTask8Claimable() {
        auto& gd = core_->state().gameData;
        state::GridBuildingData b;
        b.displayName = "天枢殿";
        gd.placedBuildings.push_back(b);
        gd.guideCounters["autoMineActivated"] = 1;
    }

    std::unique_ptr<FixedClock> clock_;
    std::unique_ptr<ConsoleLogger> logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 成功路径 ─────────────────────────────────────────────────────────

TEST_F(GuideRewardTxFixture, ClaimHappyPathMarksClaimedAndGrantsBag) {
    seedTask8Claimable();
    const int64_t before = systemSnapshot();

    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 8}});
    ASSERT_EQ(reply["status"], "success");
    EXPECT_EQ(reply["data"]["claimed"], true);
    EXPECT_TRUE(reply["data"]["itemId"].is_string());

    const auto& gd = core_->state().gameData;
    ASSERT_EQ(gd.guideClaimedRewardIds.size(), std::size_t{1});
    EXPECT_EQ(gd.guideClaimedRewardIds[0], 8);
    // 凡品储物袋 ×2（rarity=1，按稀有度合并；storageBags 在 GameState 顶层）
    const auto& bags = core_->state().storageBags;
    ASSERT_EQ(bags.size(), std::size_t{1});
    EXPECT_EQ(bags[0].name, "凡品储物袋");
    EXPECT_EQ(bags[0].rarity, 1);
    EXPECT_EQ(bags[0].quantity, 2);
    // SYSTEM 分区恰消耗 2×nextLong（快照推进）
    EXPECT_NE(systemSnapshot(), before);
}

// ── 失败零写入 + 零抽取 ──────────────────────────────────────────────

TEST_F(GuideRewardTxFixture, UnknownTaskFailsZeroWriteZeroDraw) {
    const int64_t before = systemSnapshot();
    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 999}});
    EXPECT_EQ(reply["status"], "failure");
    EXPECT_EQ(reply["code"], "UNKNOWN_TASK");
    EXPECT_TRUE(core_->state().gameData.guideClaimedRewardIds.empty());
    EXPECT_TRUE(core_->state().storageBags.empty());
    EXPECT_EQ(systemSnapshot(), before);
}

TEST_F(GuideRewardTxFixture, AlreadyClaimedFailsZeroDraw) {
    seedTask8Claimable();
    ASSERT_EQ(exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 8}})["status"], "success");
    const int64_t before = systemSnapshot();
    const int64_t bagsBefore = core_->state().storageBags[0].quantity;

    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 8}});
    EXPECT_EQ(reply["status"], "failure");
    EXPECT_EQ(reply["code"], "ALREADY_CLAIMED");
    // 不重复发放、不重复标记
    EXPECT_EQ(core_->state().storageBags[0].quantity, bagsBefore);
    EXPECT_EQ(core_->state().gameData.guideClaimedRewardIds.size(), std::size_t{1});
    EXPECT_EQ(systemSnapshot(), before);
}

TEST_F(GuideRewardTxFixture, ConditionsNotMetFailsZeroDraw) {
    // 只开计数、不放天枢殿 → BuildingCount 不满足
    core_->state().gameData.guideCounters["autoMineActivated"] = 1;
    const int64_t before = systemSnapshot();

    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 8}});
    EXPECT_EQ(reply["status"], "failure");
    EXPECT_EQ(reply["code"], "CONDITIONS_NOT_MET");
    EXPECT_TRUE(core_->state().gameData.guideClaimedRewardIds.empty());
    EXPECT_EQ(systemSnapshot(), before);
}

TEST_F(GuideRewardTxFixture, StorageFullFailsZeroDraw) {
    seedTask8Claimable();
    // 64 个独立槽位全满（storageBagKey 按稀有度合并——64 种稀有度 + 各自满栈）
    auto& bags = core_->state().storageBags;
    for (int32_t rarity = 1; rarity <= 64; ++rarity) {
        state::StorageBag bag;
        bag.id = "fill-" + std::to_string(rarity);
        bag.name = "填充袋";
        bag.rarity = rarity;
        bag.quantity = 9999;  // getMaxStackSize("storageBag")
        bags.push_back(bag);
    }
    const int64_t before = systemSnapshot();

    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 8}});
    EXPECT_EQ(reply["status"], "failure");
    EXPECT_EQ(reply["code"], "STORAGE_FULL");
    // 预检先行 ⇒ 零抽取（与 Kotlin 回退臂重跑"抽取后失败"的抽取位终点对齐）
    EXPECT_EQ(systemSnapshot(), before);
    EXPECT_TRUE(core_->state().gameData.guideClaimedRewardIds.empty());
}

// ── 条件求值语义 ─────────────────────────────────────────────────────

TEST_F(GuideRewardTxFixture, BuildingCountUsesCumulativeMaxSemantics) {
    auto& gd = core_->state().gameData;
    // 存量 2 座灵田 + 累计计数 5（历史峰值）⇒ 任务 3 的 BuildingCount(灵田,5) 满足
    for (int i = 0; i < 2; ++i) {
        state::GridBuildingData b;
        b.displayName = "灵田";
        gd.placedBuildings.push_back(b);
    }
    gd.guideCounters["buildingBuilt:灵田"] = 5;
    gd.spiritFieldPlants.push_back(state::SpiritFieldPlant{});
    EXPECT_EQ(exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 3}})["status"], "success");

    // 拆除回退场景：存量 0 + 累计 5 ⇒ 仍满足（拆除不回退引导进度）
    SetUp();
    core_->state().gameData.guideCounters["buildingBuilt:灵田"] = 5;
    core_->state().gameData.spiritFieldPlants.push_back(state::SpiritFieldPlant{});
    EXPECT_EQ(exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 3}})["status"], "success");
}

TEST_F(GuideRewardTxFixture, ElderAndSlotConditionsGateClaim) {
    // 任务 7：天枢殿×1 + 副宗主任命
    auto& gd = core_->state().gameData;
    state::GridBuildingData b;
    b.displayName = "天枢殿";
    gd.placedBuildings.push_back(b);
    EXPECT_EQ(exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 7}})["status"], "failure");

    gd.elderSlots.viceSectMaster = "201";
    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 7}});
    EXPECT_EQ(reply["status"], "success");

    // 任务 12：藏经阁×1 + 藏经阁 3 槽填充
    SetUp();
    auto& gd2 = core_->state().gameData;
    state::GridBuildingData library;
    library.displayName = "藏经阁";
    gd2.placedBuildings.push_back(library);
    for (int i = 0; i < 2; ++i) {
        state::LibrarySlot s;
        s.discipleId = "30" + std::to_string(i);
        gd2.librarySlots.push_back(s);
    }
    EXPECT_EQ(exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 12}})["status"], "failure");
    state::LibrarySlot third;
    third.discipleId = "302";
    gd2.librarySlots.push_back(third);
    EXPECT_EQ(exec(action::GUIDE_REWARD_CLAIM_TX, {{"taskId", 12}})["status"], "success");
}

// ── 注册表形状锚点 + UUID 复刻锚点 ───────────────────────────────────

TEST_F(GuideRewardTxFixture, RegistryShapeAnchor) {
    const auto& reg = gamecore::system::guide_tx::registry();
    ASSERT_EQ(reg.size(), std::size_t{25});
    for (int32_t id = 1; id <= 25; ++id) {
        const auto* task = gamecore::system::guide_tx::findTask(id);
        ASSERT_NE(task, nullptr) << "task " << id << " missing";
        EXPECT_FALSE(task->conditions.empty());
        EXPECT_EQ(task->rewardQuantity, 2);
    }
    EXPECT_EQ(gamecore::system::guide_tx::findTask(26), nullptr);
}

TEST_F(GuideRewardTxFixture, UuidFormatMatchesJavaUuidToString) {
    // Java `new UUID(0x0123456789abcdefL, 0x1122334455667788L).toString()` 字面量锚点
    EXPECT_EQ(gamecore::system::guide_tx::formatUuid(
                  static_cast<int64_t>(0x0123456789abcdefULL),
                  static_cast<int64_t>(0x1122334455667788ULL)),
              "01234567-89ab-cdef-1122-334455667788");
    // 非负 nextLong 语义（符号位恒 0）下的零值边界
    EXPECT_EQ(gamecore::system::guide_tx::formatUuid(0, 0), "00000000-0000-0000-0000-000000000000");
}

// ── 信封级 ───────────────────────────────────────────────────────────

TEST_F(GuideRewardTxFixture, InvalidParamsFailsWithoutStateChange) {
    const json reply = exec(action::GUIDE_REWARD_CLAIM_TX, json::object());
    EXPECT_EQ(reply["status"], "failure");
    EXPECT_EQ(reply["code"], "INVALID_PARAMS");
    EXPECT_TRUE(core_->state().gameData.guideClaimedRewardIds.empty());
}

}  // namespace
}  // namespace gamecore

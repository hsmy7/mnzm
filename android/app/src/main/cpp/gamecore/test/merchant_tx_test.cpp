// ============================================================
// merchant_tx_test — 行商刷新族事务守护（W4-B/B3，w3-05，1770–1773）
//
// 守护目标：merchant_tx.h 四事务与 Kotlin MerchantAndRecruitService 语义逐位一致——
//   - grantMerchantRefreshChanceTx（1770）：year<=0 校验先行；达上限/未到间隔
//     零写入；发放 = chances+1 钳制 max + grantYear 覆写
//   - refreshMerchantAcquisitionTx（1771）：收购池整表覆写 + 年份
//   - refreshTravelingMerchantTx（1772）：池 + 年份 + 刷新计数覆写
//   - refreshTravelingMerchantManualTx（1773）：chances<=0 → 零写入失败信封；
//     扣减 + 池覆写单事务原子
//   - 零 RNG：四事务全族不动 rngStates（签名级：API 不收 RngManager）
//   - 双运行全状态 JSON 逐位一致
//   - 信封级：execute 通道（dispatchW4B 端口）success data 面 + failure 信封
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/merchant_tx.h"

namespace gamecore {
namespace {

namespace merchant_tx = gamecore::system::merchant_tx;

class MerchantTxFixture : public ::testing::Test {
protected:
    void SetUp() override { core_ = makeCore(); }

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

    /// 测试用商人商品（与 json_codec MerchantItem 键一致）
    state::MerchantItem item(const std::string& id, const std::string& name,
                             int32_t rarity, int64_t price) {
        state::MerchantItem m;
        m.id = id;
        m.name = name;
        m.type = "equipment";
        m.itemId = "tpl_" + id;
        m.rarity = rarity;
        m.price = price;
        m.quantity = 1;
        m.obtainedYear = 1;
        m.obtainedMonth = 1;
        return m;
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 事务 1：grantMerchantRefreshChanceTx（1770）──────────────────────────

TEST_F(MerchantTxFixture, ChanceGrantNormalGrantWritesChanceAndYear) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshChances = 2;
    gd.merchantLastRefreshChanceGrantYear = 10;

    const auto r = merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 40, 999, 30);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.granted);
    EXPECT_EQ(gd.merchantRefreshChances, 3);
    EXPECT_EQ(gd.merchantLastRefreshChanceGrantYear, 40);
}

TEST_F(MerchantTxFixture, ChanceGrantAtMaxIsZeroWrite) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshChances = 999;  // 达上限
    gd.merchantLastRefreshChanceGrantYear = 10;
    const auto before = core_->exportStateJson();

    const auto r = merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 40, 999, 30);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.granted);
    EXPECT_EQ(core_->exportStateJson(), before);  // 达上限零写入（不覆盖 grantYear）
}

TEST_F(MerchantTxFixture, ChanceGrantWithinIntervalIsZeroWrite) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshChances = 2;
    gd.merchantLastRefreshChanceGrantYear = 30;
    const auto before = core_->exportStateJson();

    const auto r = merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 55, 999, 30);
    ASSERT_TRUE(r.base.ok);
    EXPECT_FALSE(r.granted);
    EXPECT_EQ(core_->exportStateJson(), before);  // 55-30=25 < 30：零写入
}

TEST_F(MerchantTxFixture, ChanceGrantClampedAtMax) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshChances = 998;
    const auto r = merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 40, 999, 30);
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.granted);
    EXPECT_EQ(gd.merchantRefreshChances, 999);  // 钳制到 max
    EXPECT_EQ(r.chances, 999);
}

TEST_F(MerchantTxFixture, ChanceGrantInvalidYearZeroWrite) {
    const auto before = core_->exportStateJson();
    const auto r = merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 0, 999, 30);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_PARAMS");
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 事务 2/3：池整表覆写（1771/1772）─────────────────────────────────────

TEST_F(MerchantTxFixture, AcquisitionRefreshOverwritesPoolAndYear) {
    auto& gd = core_->state().gameData;
    gd.merchantAcquisitionItems = {item("old", "旧物品", 1, 10)};
    const std::vector<state::MerchantItem> items = {
        item("a1", "凡品铁剑", 1, 50), item("a2", "中品丹药", 3, 200)};

    const auto r = merchant_tx::refreshMerchantAcquisitionTx(core_->state(), items, 7);
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.itemCount, 2);
    EXPECT_EQ(gd.merchantAcquisitionItems.size(), 2u);
    EXPECT_EQ(gd.merchantAcquisitionItems[1].name, "中品丹药");
    EXPECT_EQ(gd.merchantAcquisitionLastRefreshYear, 7);
}

TEST_F(MerchantTxFixture, TravelingRefreshOverwritesPoolYearCount) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshCount = 9;  // 下一次 = 10 → 保底相位（Kotlin 预计算）
    const std::vector<state::MerchantItem> items = {
        item("t1", "保底上品", 5, 900)};

    const auto r = merchant_tx::refreshTravelingMerchantTx(core_->state(), items, 12, 10);
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.itemCount, 1);
    EXPECT_EQ(gd.travelingMerchantItems.size(), 1u);
    EXPECT_EQ(gd.travelingMerchantItems[0].name, "保底上品");
    EXPECT_EQ(gd.merchantLastRefreshYear, 12);
    EXPECT_EQ(gd.merchantRefreshCount, 10);
}

// ── 事务 4：refreshTravelingMerchantManualTx（1773）──────────────────────

TEST_F(MerchantTxFixture, ManualRefreshAtomicDecrementAndPoolWrite) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshChances = 2;
    gd.merchantRefreshCount = 3;
    const std::vector<state::MerchantItem> items = {item("m1", "新池", 2, 80)};

    const auto r = merchant_tx::refreshTravelingMerchantManualTx(core_->state(), items, 9, 4);
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(gd.merchantRefreshChances, 1);
    EXPECT_EQ(gd.travelingMerchantItems.size(), 1u);
    EXPECT_EQ(gd.merchantLastRefreshYear, 9);
    EXPECT_EQ(gd.merchantRefreshCount, 4);
    EXPECT_EQ(r.chances, 1);
}

TEST_F(MerchantTxFixture, ManualRefreshWithoutChancesIsZeroWriteFailure) {
    auto& gd = core_->state().gameData;
    gd.merchantRefreshChances = 0;
    const auto before = core_->exportStateJson();
    const std::vector<state::MerchantItem> items = {item("m1", "新池", 2, 80)};

    const auto r = merchant_tx::refreshTravelingMerchantManualTx(core_->state(), items, 9, 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INSUFFICIENT_CHANCES");
    EXPECT_EQ(core_->exportStateJson(), before);  // 凭据保留，池不动（失败零写入）
}

// ── 家族级：零 RNG + 双运行逐位一致 ─────────────────────────────────────

TEST_F(MerchantTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    const auto baseline = rngSnapshot();
    const std::vector<state::MerchantItem> items = {item("x1", "x", 1, 1)};
    ASSERT_TRUE(merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 40, 999, 30).base.ok);
    ASSERT_TRUE(merchant_tx::refreshMerchantAcquisitionTx(core_->state(), items, 7).base.ok);
    ASSERT_TRUE(merchant_tx::refreshTravelingMerchantTx(core_->state(), items, 8, 1).base.ok);
    ASSERT_TRUE(merchant_tx::refreshTravelingMerchantManualTx(core_->state(), items, 9, 2).base.ok);
    // 失败臂同样零抽取
    ASSERT_FALSE(merchant_tx::grantMerchantRefreshChanceTx(core_->state(), -1, 999, 30).base.ok);
    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(MerchantTxFixture, DoubleRunBitwiseIdentical) {
    auto run = [&]() {
        const std::vector<state::MerchantItem> items = {
            item("x1", "甲", 1, 10), item("x2", "乙", 2, 20)};
        merchant_tx::grantMerchantRefreshChanceTx(core_->state(), 40, 999, 30);
        merchant_tx::refreshTravelingMerchantTx(core_->state(), items, 8, 1);
        merchant_tx::refreshTravelingMerchantManualTx(core_->state(), items, 9, 2);
        merchant_tx::refreshMerchantAcquisitionTx(core_->state(), items, 7);
        return core_->exportStateJson();
    };
    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 信封级：execute 通道（dispatchW4B 端口，Kotlin 回退臂契约）──────────

TEST_F(MerchantTxFixture, DispatchEnvelopeSuccessAndFailure) {
    // 显式播种（GameCore initialize 赋新档初始 chances=1）
    core_->state().gameData.merchantRefreshChances = 0;
    core_->state().gameData.merchantLastRefreshChanceGrantYear = 0;
    // 成功信封（1770：发放 0→1）
    auto env = exec(1770, {{"year", 40}, {"maxChances", 999}, {"intervalYears", 30}});
    ASSERT_EQ(env["status"], "success");
    EXPECT_EQ(env["data"]["granted"], true);
    EXPECT_EQ(env["data"]["chances"], 1);

    // 失败信封（1773：无凭据 → INSUFFICIENT_CHANCES → Kotlin 回退臂契约）
    // （1770 的发放使 chances=1——重新播种为 0 以触发校验失败臂）
    core_->state().gameData.merchantRefreshChances = 0;
    auto bad = exec(1773, {{"year", 9}, {"newRefreshCount", 1},
                           {"items", nlohmann::json::array()}});
    EXPECT_EQ(bad["status"], "failure");
    EXPECT_EQ(bad["code"], "INSUFFICIENT_CHANCES");
}

TEST_F(MerchantTxFixture, DispatchManualRefreshRoundTripsItems) {
    // items 数组经 json_codec MerchantItem 键往返（Kotlin native 臂同键名）
    const nlohmann::json items = nlohmann::json::array({
        {{"id", "m1"}, {"name", "凡品铁剑"}, {"type", "equipment"},
         {"itemId", "tpl_m1"}, {"rarity", 1}, {"price", 50}, {"quantity", 1},
         {"description", ""}, {"obtainedYear", 1}, {"obtainedMonth", 1}},
    });
    auto env = exec(1773, {{"year", 9}, {"newRefreshCount", 1}, {"items", items}});
    ASSERT_EQ(env["status"], "success");
    EXPECT_EQ(env["data"]["itemCount"], 1);
    EXPECT_EQ(core_->state().gameData.travelingMerchantItems[0].name, "凡品铁剑");
    EXPECT_EQ(core_->state().gameData.travelingMerchantItems[0].price, 50);
}

}  // namespace
}  // namespace gamecore

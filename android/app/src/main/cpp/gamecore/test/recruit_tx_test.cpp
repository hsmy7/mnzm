// ============================================================
// recruit_tx_test — 招募/派遣/俘虏残余族事务守护（batch-16：
// 招募列表移除 / 年度刷新直调 / 老化净化直调）
//
// 守护目标：recruit_tx.h 三事务与 Kotlin 源语义逐位一致——
//   - removeRecruitTx：按 id 全量过滤幂等（同 id 多条全移），零 RNG
//   - ageRecruitTx：age+1 / 超寿元移除 / 损坏过滤 / 三级去重 / 跨表残留，
//     零 RNG
//   - refreshRecruitTx：差值门零抽取零写入；玩家宗门/兜底/政策加成数量面；
//     自动招募计数口径（generated = 列表净增 + autoRecruited）；
//     双运行逐位一致 + 终态 rngStates 锁定（SYSTEM 分区，复用
//     year_settlement 候选生成链）
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
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/recruit_settlement.h"
#include "gamecore/system/recruit_tx.h"

namespace gamecore {
namespace {

namespace recruit_tx = gamecore::system::recruit_tx;
namespace recruit_settle = gamecore::system::recruit_settle;

using gamecore::state::Disciple;
using gamecore::state::WorldSect;

class RecruitTxFixture : public ::testing::Test {
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

    /// 招募列表条目构造（其余字段 = 模型默认）
    static Disciple recruit(const std::string& id, const std::string& name,
                            int32_t age, int32_t lifespan = 80) {
        Disciple d;
        d.id = id;
        d.name = name;
        d.spiritRootType = "metal";
        d.age = age;
        d.realm = 9;
        d.realmLayer = 1;
        d.isAlive = true;
        d.lifespan = lifespan;
        return d;
    }

    /// 宗门侧最小存活弟子（跨表残留用：与 recruit 同签名字段面）
    std::size_t addSectDisciple(const std::string& id, const std::string& name,
                                int32_t age) {
        Disciple d = recruit(id, name, age);
        d.status = "IDLE";
        d.currentHp = 100;
        d.currentMp = 50;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    void addPlayerSect(int32_t level) {
        WorldSect player;
        player.id = "p1";
        player.isPlayerSect = true;
        player.level = level;
        core_->state().gameData.worldMapSects.push_back(player);
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── RECRUIT_REMOVE_TX：按 id 全量过滤幂等（零 RNG） ────────────────────────

TEST_F(RecruitTxFixture, RemoveTxRemovesAllMatchingIds) {
    auto& list = core_->state().gameData.recruitList;
    list.push_back(recruit("a", "甲一", 20));
    list.push_back(recruit("a", "甲一", 21));  // 同 id 第二条（全量过滤）
    list.push_back(recruit("b", "乙二", 22));

    const auto r = recruit_tx::removeRecruitTx(core_->state(), "a");

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(2, r.removed);
    EXPECT_EQ(1, r.remaining);
    ASSERT_EQ(1u, list.size());
    EXPECT_EQ("b", list[0].id);
}

TEST_F(RecruitTxFixture, RemoveTxMissingIdIsIdempotentSuccess) {
    auto& list = core_->state().gameData.recruitList;
    list.push_back(recruit("a", "甲一", 20));

    const auto r = recruit_tx::removeRecruitTx(core_->state(), "missing");

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(0, r.removed);
    EXPECT_EQ(1, r.remaining);
    ASSERT_EQ(1u, list.size());
}

TEST_F(RecruitTxFixture, RemoveTxLeavesRngStatesUntouched) {
    core_->state().gameData.recruitList.push_back(recruit("a", "甲一", 20));
    const auto before = rngSnapshot();

    (void)recruit_tx::removeRecruitTx(core_->state(), "a");

    EXPECT_EQ(before, rngSnapshot()) << "移除为零 RNG 纯事务";
}

// ── RECRUIT_AGE_TX：老化 + 净化一体（零 RNG） ─────────────────────────────

TEST_F(RecruitTxFixture, AgeTxAgesSurvivorAndRemovesDeadCorruptedDupesResidual) {
    // 宗门侧弟子（与条目 e 同签名 → 跨表残留移除）
    addSectDisciple("1", "同签名", 20);
    auto& list = core_->state().gameData.recruitList;
    list.push_back(recruit("y", "幸存者", 20));            // 存活：age 20→21
    list.push_back(recruit("d", "寿终", 99, /*lifespan=*/80));  // 超寿元移除
    list.push_back(recruit("c", "", 20));                  // 损坏（名空）移除
    list.push_back(recruit("u", "重复", 20));
    list.push_back(recruit("u", "重复", 20));              // 同 id 去重保首
    list.push_back(recruit("e", "同签名", 20));            // 已入宗门残留移除

    const auto r = recruit_tx::ageRecruitTx(core_->state(), core_->ecsWorld());

    ASSERT_TRUE(r.base.ok);
    // 移除：寿终 + 损坏 + 同 id 副本（首条保留）+ 跨表残留 = 4；剩幸存者 + 去重保留首条
    EXPECT_EQ(4, r.removed);
    EXPECT_EQ(2, r.remaining);
    ASSERT_EQ(2u, list.size());
    EXPECT_EQ("y", list[0].id);
    EXPECT_EQ(21, list[0].age) << "幸存条目 age+1";
    EXPECT_EQ("u", list[1].id) << "同 id 去重保首";
}

TEST_F(RecruitTxFixture, AgeTxLeavesRngStatesUntouched) {
    core_->state().gameData.recruitList.push_back(recruit("a", "甲一", 20));
    const auto before = rngSnapshot();

    (void)recruit_tx::ageRecruitTx(core_->state(), core_->ecsWorld());

    EXPECT_EQ(before, rngSnapshot()) << "老化净化为零 RNG 纯事务";
}

// ── RECRUIT_REFRESH_TX：年度刷新（SYSTEM 分区复用权威链） ─────────────────

TEST_F(RecruitTxFixture, RefreshTxIntervalGateZeroDrawZeroWrite) {
    auto& gd = core_->state().gameData;
    gd.gameYear = 4;
    gd.lastRecruitYear = 2;  // 差值 2 < 3
    gd.recruitList.push_back(recruit("keep", "原有", 20));
    const auto before = rngSnapshot();

    const auto r = recruit_tx::refreshRecruitTx(core_->state(), /*year=*/4,
                                                core_->rng());

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(0, r.generated);
    EXPECT_EQ(0, r.autoRecruited);
    EXPECT_EQ(1, r.remaining);
    EXPECT_EQ(2, gd.lastRecruitYear) << "差值未满不更新";
    EXPECT_EQ(before, rngSnapshot()) << "差值未满必须零 SYSTEM 抽取";
}

TEST_F(RecruitTxFixture, RefreshTxGeneratesWithPlayerSect) {
    auto& gd = core_->state().gameData;
    gd.gameYear = 5;
    gd.lastRecruitYear = 2;  // 差值 3 ≥ 3
    addPlayerSect(/*level=*/2);  // 大（1..10）

    const auto r = recruit_tx::refreshRecruitTx(core_->state(), /*year=*/5,
                                                core_->rng());

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(5, gd.lastRecruitYear);
    ASSERT_FALSE(gd.recruitList.empty());
    EXPECT_LE(gd.recruitList.size(), 10u);
    EXPECT_EQ(static_cast<int32_t>(gd.recruitList.size()), r.generated);
    EXPECT_EQ(0, r.autoRecruited) << "无自动招募过滤器 → 零入宗";
    EXPECT_EQ(r.generated, r.remaining) << "空列表起步 remaining = generated";
    for (const auto& cand : gd.recruitList) {
        EXPECT_FALSE(cand.name.empty());
        EXPECT_GE(cand.age, 16);
        EXPECT_LE(cand.age, 29);
        EXPECT_EQ(9, cand.realm);
    }
}

TEST_F(RecruitTxFixture, RefreshTxFallbackNoPlayerSect) {
    auto& gd = core_->state().gameData;
    gd.gameYear = 8;
    gd.lastRecruitYear = 5;

    const auto r = recruit_tx::refreshRecruitTx(core_->state(), /*year=*/8,
                                                core_->rng());

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(8, gd.lastRecruitYear);
    ASSERT_FALSE(gd.recruitList.empty());
    EXPECT_GE(gd.recruitList.size(), 1u);
    EXPECT_GE(r.generated, 1) << "兜底 coerceAtLeast 1";
}

TEST_F(RecruitTxFixture, RefreshTxCountsAutoRecruitIntoGenerated) {
    auto& gd = core_->state().gameData;
    gd.gameYear = 6;
    gd.lastRecruitYear = 3;
    addPlayerSect(/*level=*/3);           // 顶级（1..15）
    gd.autoRecruitSpiritRootFilter = {5};  // 五灵根候选自动入宗（权重最大段）

    const auto r = recruit_tx::refreshRecruitTx(core_->state(), /*year=*/6,
                                                core_->rng());

    ASSERT_TRUE(r.base.ok);
    // 计数口径：autoRecruited = recruitCountThisMonth 增量；
    // generated = 列表净增 + autoRecruited
    EXPECT_EQ(r.autoRecruited, gd.recruitCountThisMonth);
    EXPECT_EQ(r.generated,
              static_cast<int32_t>(gd.recruitList.size()) + r.autoRecruited);
    EXPECT_GT(r.generated, 0);
}

TEST_F(RecruitTxFixture, RefreshTxDualRunBitwiseIdentical) {
    // 同种子双运行：候选内容（discipleContentEquals——id/slotId 除外，
    // C++ 臂 id="" 镜像生成字段）+ 终态全分区 rngStates 逐位一致
    auto other = makeCore();
    for (GameCore* core : {core_.get(), other.get()}) {
        auto& gd = core->state().gameData;
        gd.gameYear = 5;
        gd.lastRecruitYear = 2;
        WorldSect player;
        player.id = "p1";
        player.isPlayerSect = true;
        player.level = 2;
        gd.worldMapSects.push_back(player);
    }

    const auto r1 = recruit_tx::refreshRecruitTx(core_->state(), 5, core_->rng());
    const auto r2 = recruit_tx::refreshRecruitTx(other->state(), 5, other->rng());

    ASSERT_TRUE(r1.base.ok);
    ASSERT_TRUE(r2.base.ok);
    ASSERT_EQ(r1.generated, r2.generated);
    const auto& a = core_->state().gameData.recruitList;
    const auto& b = other->state().gameData.recruitList;
    ASSERT_EQ(a.size(), b.size());
    for (std::size_t i = 0; i < a.size(); ++i) {
        EXPECT_TRUE(recruit_settle::discipleContentEquals(a[i], b[i]))
            << "候选 " << i << " 内容逐位一致";
    }
    EXPECT_EQ(rngSnapshot(), other->state().gameData.rngStates)
        << "终态全分区 rngStates 锁定";
}

// ── 信封级（execute 通道：success data 面 + 失败信封回退契约） ─────────────

TEST_F(RecruitTxFixture, DispatchEnvelopeHappyPaths) {
    auto& gd = core_->state().gameData;
    gd.recruitList.push_back(recruit("a", "甲一", 20));

    const auto rm = exec(action::RECRUIT_REMOVE_TX, {{"discipleId", "a"}});
    EXPECT_EQ(rm["status"], "success");
    EXPECT_EQ(rm["data"]["removed"], 1);
    EXPECT_EQ(rm["data"]["remaining"], 0);

    gd.gameYear = 8;
    gd.lastRecruitYear = 5;
    const auto rf = exec(action::RECRUIT_REFRESH_TX, {{"year", 8}});
    EXPECT_EQ(rf["status"], "success");
    EXPECT_GE(rf["data"]["generated"].get<int32_t>(), 1);

    const auto ag = exec(action::RECRUIT_AGE_TX, nlohmann::json::object());
    EXPECT_EQ(ag["status"], "success");
    EXPECT_GE(ag["data"]["removed"].get<int32_t>(), 0);
    EXPECT_GE(ag["data"]["remaining"].get<int32_t>(), 0);
}

TEST_F(RecruitTxFixture, DispatchEnvelopeFailureOnMissingParam) {
    // 缺参 → 顶层 catch → failure 信封（Kotlin 回退臂契约）
    const auto r = exec(action::RECRUIT_REMOVE_TX, nlohmann::json::object());
    EXPECT_EQ(r["status"], "failure");
}

}  // namespace
}  // namespace gamecore

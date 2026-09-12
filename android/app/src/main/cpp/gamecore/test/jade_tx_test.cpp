// ============================================================
// jade_tx_test — 玉符/宗门升级落账族事务守护（batch-19：宗门升级写回 /
// 宗门等级奖励领取落账 / 玉符购买商人刷新 / 玉符购买突破率加成）
//
// 守护目标：jade_tx.h 四事务与 Kotlin 源语义逐位一致——
//   - upgradeSectLevelTx：存在性/严格升级校验；仅玩家宗门条目改写；
//     任一校验失败零写入
//   - claimSectLevelRewardTx：7 天冷却校验先行；材料/储物袋/灵石/领取记录
//     四项同事务；**凭据类**溢出 → 整体回滚零写入（不产邮件草稿）
//   - purchaseMerchantRefreshTx / purchaseBreakthroughBonusTx：上限校验
//     先于扣款（达上限不扣玉符）；玉符扣减绝对值语义；statusData 写回串
//     与 Kotlin `Double.toString()` 逐值一致
//   - 零 RNG：四事务全族不动 rngStates（签名级：API 不收 RngManager）
//   - 信封级：execute 通道 success data 面 + failure 信封（Kotlin 回退臂契约）
// ============================================================

#include "gtest/gtest.h"

#include <cstdlib>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/jade_tx.h"

namespace gamecore {
namespace {

namespace jade_tx = gamecore::system::jade_tx;

using gamecore::state::Disciple;
using gamecore::state::Material;
using gamecore::state::SectLevelClaimRecord;
using gamecore::state::StorageBag;
using gamecore::state::WorldSect;

class JadeTxFixture : public ::testing::Test {
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

    /// 玩家宗门条目（其余字段 = 模型默认）
    WorldSect playerSect(int32_t level, const std::string& name,
                         const std::string& levelName = "小宗门") {
        WorldSect s;
        s.id = "p1";
        s.name = name;
        s.isPlayerSect = true;
        s.level = level;
        s.levelName = levelName;
        return s;
    }

    /// 非玩家宗门条目（升级只改玩家宗门——邻接项零改写断言用）
    WorldSect aiSect(int32_t level) {
        WorldSect s;
        s.id = "ai1";
        s.name = "AI 宗";
        s.isPlayerSect = false;
        s.level = level;
        s.levelName = "AI 级";
        return s;
    }

    /// 仓库灌满（默认 baseCapacity = 50；条目按 id 唯一 → 占 50 槽）
    void fillWarehouse(int32_t count) {
        for (int32_t i = 0; i < count; ++i) {
            Material m;
            m.id = "fill-" + std::to_string(i);
            m.name = "填充材料" + std::to_string(i);
            m.rarity = 1;
            m.quantity = 1;
            core_->state().materials.push_back(m);
        }
    }

    std::size_t addDisciple(const std::string& id, const std::string& name,
                            bool alive) {
        Disciple d;
        d.id = id;
        d.name = name;
        d.isAlive = alive;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    const std::vector<Material>& materials() const { return core_->state().materials; }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── SECT_LEVEL_UPGRADE_TX（宗门升级写回，零 RNG） ─────────────────────────

TEST_F(JadeTxFixture, UpgradeTxWritesPlayerSectOnly) {
    core_->state().gameData.worldMapSects.push_back(playerSect(1, "我的宗门"));
    core_->state().gameData.worldMapSects.push_back(aiSect(2));

    const auto r = jade_tx::upgradeSectLevelTx(core_->state(), 2, "中宗门");

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(2, r.newLevel);
    EXPECT_EQ("中宗门", r.levelName);
    const auto& sects = core_->state().gameData.worldMapSects;
    ASSERT_EQ(2u, sects.size());
    EXPECT_EQ(2, sects[0].level);
    EXPECT_EQ("中宗门", sects[0].levelName);
    // 非玩家宗门零改写
    EXPECT_EQ(2, sects[1].level);
    EXPECT_EQ("AI 级", sects[1].levelName);
}

TEST_F(JadeTxFixture, UpgradeTxMissingPlayerSectFailsZeroWrite) {
    core_->state().gameData.worldMapSects.push_back(aiSect(2));

    const auto r = jade_tx::upgradeSectLevelTx(core_->state(), 3, "大宗门");

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("PLAYER_SECT_NOT_FOUND", r.base.errorType);
    ASSERT_EQ(1u, core_->state().gameData.worldMapSects.size());
    EXPECT_EQ(2, core_->state().gameData.worldMapSects[0].level);
}

TEST_F(JadeTxFixture, UpgradeTxRejectsNonUpgrade) {
    core_->state().gameData.worldMapSects.push_back(playerSect(3, "我的宗门", "中宗门"));

    const auto r = jade_tx::upgradeSectLevelTx(core_->state(), 3, "中宗门");

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("NOT_AN_UPGRADE", r.base.errorType);
    EXPECT_EQ(3, core_->state().gameData.worldMapSects[0].level);
}

// ── SECT_LEVEL_CLAIM_TX（宗门等级奖励领取落账，凭据类） ───────────────────

TEST_F(JadeTxFixture, ClaimTxWritesMaterialsBagsStonesAndRecord) {
    const int64_t before = core_->state().gameData.spiritStones;
    std::vector<jade_tx::ClaimMaterial> claimMaterials = {
        {"兽血·凡", 1, "BLOOD", 2}, {"兽血·二", 2, "BLOOD", 1}};
    std::vector<jade_tx::ClaimStorageBag> bags = {{"凡品储物袋", 1, 3}};

    const auto r = jade_tx::claimSectLevelRewardTx(core_->state(), 2, 1'700'000'000'000LL,
                                                    claimMaterials, bags, 500);

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(2, r.materialCount);
    EXPECT_EQ(1, r.storageBagCount);
    EXPECT_EQ(500, r.spiritStones);
    EXPECT_EQ(2, r.claimedLevel);
    // 物品轨
    ASSERT_EQ(2u, materials().size());
    EXPECT_EQ("兽血·凡", materials()[0].name);
    EXPECT_EQ("BLOOD", materials()[0].category);
    EXPECT_EQ(2, materials()[0].quantity);
    EXPECT_FALSE(materials()[0].id.empty());   // 确定性 id 由 C++ 注入
    ASSERT_EQ(1u, core_->state().storageBags.size());
    EXPECT_EQ(3, core_->state().storageBags[0].quantity);
    // 灵石 + 年度来源（钱包 add 记年度收入）
    EXPECT_EQ(before + 500, core_->state().gameData.spiritStones);
    // 领取记录 upsert
    const auto& recs = core_->state().gameData.sectLevelClaimRecords;
    ASSERT_EQ(1u, recs.size());
    EXPECT_EQ(2, recs[0].level);
    EXPECT_EQ(1'700'000'000'000LL, recs[0].claimedAtEpochMs);
}

TEST_F(JadeTxFixture, ClaimTxCooldownFailsZeroWrite) {
    SectLevelClaimRecord rec;
    rec.level = 2;
    rec.claimedAtEpochMs = 1'700'000'000'000LL;
    core_->state().gameData.sectLevelClaimRecords.push_back(rec);
    // 未满 7 天（6 天 23 小时）
    const int64_t nowMs = rec.claimedAtEpochMs + 7LL * 24 * 3600 * 1000 - 3600 * 1000;
    const int64_t stonesBefore = core_->state().gameData.spiritStones;

    const auto r = jade_tx::claimSectLevelRewardTx(core_->state(), 2, nowMs,
                                                    {{"兽血·凡", 1, "BLOOD", 1}}, {}, 999);

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("ALREADY_CLAIMED", r.base.errorType);
    EXPECT_TRUE(materials().empty());
    EXPECT_EQ(stonesBefore, core_->state().gameData.spiritStones);
    EXPECT_EQ(1u, core_->state().gameData.sectLevelClaimRecords.size());
}

TEST_F(JadeTxFixture, ClaimTxAfterCooldownElapsedSucceeds) {
    SectLevelClaimRecord rec;
    rec.level = 2;
    rec.claimedAtEpochMs = 1'700'000'000'000LL;
    core_->state().gameData.sectLevelClaimRecords.push_back(rec);
    const int64_t nowMs = rec.claimedAtEpochMs + 7LL * 24 * 3600 * 1000;

    const auto r = jade_tx::claimSectLevelRewardTx(core_->state(), 2, nowMs,
                                                    {{"兽血·凡", 1, "BLOOD", 1}}, {}, 0);

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(1u, core_->state().gameData.sectLevelClaimRecords.size());
    EXPECT_EQ(nowMs, core_->state().gameData.sectLevelClaimRecords[0].claimedAtEpochMs);
}

// 凭据类溢出：仓库满 → 整体回滚（物品/灵石/记录均零写入，凭据保留可重试）
TEST_F(JadeTxFixture, ClaimTxCapacityOverflowRollsBackEverything) {
    fillWarehouse(50);  // baseCapacity = 50 → 满
    const auto beforeMaterials = materials().size();
    const int64_t beforeStones = core_->state().gameData.spiritStones;

    const auto r = jade_tx::claimSectLevelRewardTx(
        core_->state(), 3, 1'700'000'000'000LL,
        {{"兽血·凡", 1, "BLOOD", 5}, {"兽血·二", 2, "BLOOD", 1}}, {{"凡品储物袋", 1, 1}}, 800);

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("CAPACITY_INSUFFICIENT", r.base.errorType);
    // 零写入：仓库条目数不变（无部分入仓）、灵石不变、无领取记录
    EXPECT_EQ(beforeMaterials, materials().size());
    EXPECT_EQ(beforeStones, core_->state().gameData.spiritStones);
    EXPECT_TRUE(core_->state().storageBags.empty());
    EXPECT_TRUE(core_->state().gameData.sectLevelClaimRecords.empty());
}

// ── JADE_PURCHASE_MERCHANT_REFRESH_TX（玉符扣减 + 刷新次数写回） ──────────

TEST_F(JadeTxFixture, MerchantRefreshTxDeductsJadeAndAccumulates) {
    core_->state().gameData.jadeSymbols = 5;
    core_->state().gameData.merchantRefreshChances = 1;

    const auto r = jade_tx::purchaseMerchantRefreshTx(core_->state(), 1, 3, 999);

    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(4, r.jadeSymbols);
    EXPECT_EQ(4, r.value);
    EXPECT_EQ(4, core_->state().gameData.jadeSymbols);
    EXPECT_EQ(4, core_->state().gameData.merchantRefreshChances);
}

TEST_F(JadeTxFixture, MerchantRefreshTxLimitReachedDoesNotDeduct) {
    core_->state().gameData.jadeSymbols = 5;
    core_->state().gameData.merchantRefreshChances = 999;

    const auto r = jade_tx::purchaseMerchantRefreshTx(core_->state(), 1, 3, 999);

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("LIMIT_REACHED", r.base.errorType);
    EXPECT_EQ(5, core_->state().gameData.jadeSymbols);   // 达上限不扣玉符
    EXPECT_EQ(999, core_->state().gameData.merchantRefreshChances);
}

TEST_F(JadeTxFixture, MerchantRefreshTxInsufficientJadeZeroWrite) {
    core_->state().gameData.jadeSymbols = 0;
    core_->state().gameData.merchantRefreshChances = 1;

    const auto r = jade_tx::purchaseMerchantRefreshTx(core_->state(), 1, 3, 999);

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("INSUFFICIENT_JADE", r.base.errorType);
    EXPECT_EQ(0, core_->state().gameData.jadeSymbols);
    EXPECT_EQ(1, core_->state().gameData.merchantRefreshChances);
}

// ── JADE_PURCHASE_BREAKTHROUGH_BONUS_TX（statusData 写回 + 串格式） ───────

TEST_F(JadeTxFixture, BreakthroughBonusTxWritesStatusData) {
    addDisciple("7", "甲七", true);
    core_->state().gameData.jadeSymbols = 5;

    const auto r1 = jade_tx::purchaseBreakthroughBonusTx(core_->state(), "7", 1, 0.15, 0.30);

    ASSERT_TRUE(r1.base.ok);
    EXPECT_EQ(4, r1.jadeSymbols);
    EXPECT_EQ("0.15", r1.writtenValue);   // Kotlin Double.toString(0.15) 同串
    const auto d = core_->state().disciples.materialize(*core_->state().disciples.rowOf("7"));
    EXPECT_EQ("0.15", d.statusData.at("adBreakthroughBonus"));

    const auto r2 = jade_tx::purchaseBreakthroughBonusTx(core_->state(), "7", 1, 0.15, 0.30);

    ASSERT_TRUE(r2.base.ok);
    EXPECT_EQ(3, r2.jadeSymbols);
    EXPECT_EQ("0.3", r2.writtenValue);    // Kotlin Double.toString(0.3) 同串
}

TEST_F(JadeTxFixture, BreakthroughBonusTxLimitReachedDoesNotDeduct) {
    addDisciple("7", "甲七", true);
    core_->state().gameData.jadeSymbols = 5;
    {
        const auto seed = jade_tx::purchaseBreakthroughBonusTx(
            core_->state(), "7", 1, 0.15, 0.30);
        ASSERT_TRUE(seed.base.ok);
        const auto seed2 = jade_tx::purchaseBreakthroughBonusTx(
            core_->state(), "7", 1, 0.15, 0.30);
        ASSERT_TRUE(seed2.base.ok);
    }
    ASSERT_EQ(3, core_->state().gameData.jadeSymbols);

    const auto r = jade_tx::purchaseBreakthroughBonusTx(core_->state(), "7", 1, 0.15, 0.30);

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("LIMIT_REACHED", r.base.errorType);
    EXPECT_EQ(3, core_->state().gameData.jadeSymbols);   // 达上限不扣玉符
}

TEST_F(JadeTxFixture, BreakthroughBonusTxRejectsMissingOrDeadDisciple) {
    core_->state().gameData.jadeSymbols = 5;

    const auto missing = jade_tx::purchaseBreakthroughBonusTx(
        core_->state(), "404", 1, 0.15, 0.30);
    EXPECT_FALSE(missing.base.ok);
    EXPECT_EQ("DISCIPLE_NOT_FOUND", missing.base.errorType);

    addDisciple("8", "甲八", false);
    const auto dead = jade_tx::purchaseBreakthroughBonusTx(
        core_->state(), "8", 1, 0.15, 0.30);
    EXPECT_FALSE(dead.base.ok);
    EXPECT_EQ("DISCIPLE_DEAD", dead.base.errorType);
    EXPECT_EQ(5, core_->state().gameData.jadeSymbols);   // 零写入
}

TEST_F(JadeTxFixture, BreakthroughBonusTxInsufficientJadeZeroWrite) {
    addDisciple("7", "甲七", true);
    core_->state().gameData.jadeSymbols = 0;

    const auto r = jade_tx::purchaseBreakthroughBonusTx(core_->state(), "7", 1, 0.15, 0.30);

    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ("INSUFFICIENT_JADE", r.base.errorType);
    const auto d = core_->state().disciples.materialize(*core_->state().disciples.rowOf("7"));
    EXPECT_TRUE(d.statusData.empty());
}

// ── 零 RNG 全分区快照差分（四事务全族） ──────────────────────────────────

TEST_F(JadeTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    auto& st = core_->state();
    st.gameData.worldMapSects.push_back(playerSect(1, "我的宗门"));
    st.gameData.jadeSymbols = 9;
    addDisciple("7", "甲七", true);
    const auto before = rngSnapshot();

    const auto up = jade_tx::upgradeSectLevelTx(st, 2, "中宗门");
    const auto claim = jade_tx::claimSectLevelRewardTx(
        st, 2, 1'700'000'000'000LL, {{"兽血·凡", 1, "BLOOD", 1}}, {}, 100);
    const auto refresh = jade_tx::purchaseMerchantRefreshTx(st, 1, 3, 999);
    const auto bonus = jade_tx::purchaseBreakthroughBonusTx(st, "7", 1, 0.15, 0.30);

    ASSERT_TRUE(up.base.ok);
    ASSERT_TRUE(claim.base.ok);
    ASSERT_TRUE(refresh.base.ok);
    ASSERT_TRUE(bonus.base.ok);
    EXPECT_EQ(before, rngSnapshot());
}

// ── 串格式基准（Java Double.toString 等价，Kotlin `newBonus.toString()`） ──

TEST_F(JadeTxFixture, JavaDoubleStringFormatBaseline) {
    EXPECT_EQ("0.0", jade_tx::javaDoubleToString(0.0));
    EXPECT_EQ("0.15", jade_tx::javaDoubleToString(0.15));
    EXPECT_EQ("0.3", jade_tx::javaDoubleToString(0.3));
    // 往返不变式：串 → double 必须还原同一位模式（保 Kotlin Double.toString 可解析）
    const double stepped = 0.15 + 0.15;
    EXPECT_DOUBLE_EQ(stepped,
                     std::strtod(jade_tx::javaDoubleToString(stepped).c_str(), nullptr));
}

// ── 信封级：execute 通道 success data + failure（Kotlin 回退臂契约） ──────

TEST_F(JadeTxFixture, DispatchEnvelopeHappyAndFailure) {
    core_->state().gameData.worldMapSects.push_back(playerSect(1, "我的宗门"));
    core_->state().gameData.jadeSymbols = 4;

    const auto okUpgrade = exec(action::SECT_LEVEL_UPGRADE_TX,
                                {{"targetLevel", 2}, {"levelName", "中宗门"}});
    EXPECT_EQ("success", okUpgrade["status"].get<std::string>());
    EXPECT_EQ(2, okUpgrade["data"]["newLevel"].get<int32_t>());
    EXPECT_EQ("中宗门", okUpgrade["data"]["levelName"].get<std::string>());

    // 失败信封（Kotlin 臂据此回退原路径重执行校验链）
    const auto failClaim = exec(action::SECT_LEVEL_CLAIM_TX,
                                {{"level", 0}, {"nowMs", 1'700'000'000'000LL}});
    EXPECT_EQ("failure", failClaim["status"].get<std::string>());
    EXPECT_EQ("INVALID_LEVEL", failClaim["code"].get<std::string>());
    EXPECT_TRUE(core_->state().gameData.sectLevelClaimRecords.empty());

    const auto okRefresh = exec(action::JADE_PURCHASE_MERCHANT_REFRESH_TX,
                                {{"cost", 1}, {"perJade", 3}, {"maxChances", 999}});
    EXPECT_EQ("success", okRefresh["status"].get<std::string>());
    EXPECT_EQ(3, okRefresh["data"]["jadeSymbols"].get<int32_t>());
    EXPECT_EQ(4, okRefresh["data"]["merchantRefreshChances"].get<int32_t>());

    addDisciple("7", "甲七", true);
    const auto okBonus = exec(action::JADE_PURCHASE_BREAKTHROUGH_BONUS_TX,
                              {{"discipleId", "7"}, {"cost", 1}, {"perJade", 0.15},
                               {"maxBonus", 0.30}});
    EXPECT_EQ("success", okBonus["status"].get<std::string>());
    EXPECT_EQ(2, okBonus["data"]["jadeSymbols"].get<int32_t>());
    EXPECT_EQ("0.15", okBonus["data"]["bonus"].get<std::string>());

    // 段外动作码 → NOT_IMPLEMENTED（本段边界不进 handleJadeTx）
    const auto outside = exec(1694, nlohmann::json::object());
    EXPECT_EQ("failure", outside["status"].get<std::string>());
    EXPECT_EQ("NOT_IMPLEMENTED", outside["code"].get<std::string>());
}

}  // namespace
}  // namespace gamecore

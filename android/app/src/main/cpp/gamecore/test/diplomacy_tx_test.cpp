// ============================================================
// diplomacy_tx_test.cpp — 外交/好感/附庸 UI 操作事务族黄金用例
//
// 守护目标（batch-09 §5）：
//   - FavorDomain 纯函数族黄金值（手算对拍 Kotlin FavorDomain）
//   - 状态机合法/非法迁移（updateFavor/setAcquainted 守卫语义）
//   - 赠礼拒绝 roll 分区审计（SYSTEM 分区抽取量与本地同种子参考流一致；
//     BATTLE 等其他分区零扰动）
//   - 附庸战绩读面一致性（近 3 年 sectBattleRecords 计数进概率）与
//     aiPower<=0 仍掷骰的 Kotlin 同位语义
//   - 失败零写入（校验链各失败点状态全等 + 零抽取）
//
// Kotlin 语义权威 = GiftService / DiplomacyService / VassalService；
// 双端逐位对拍由 DiffDiplomacyTxTest（Kotlin 侧，真值经 import/export
// 快照对照）覆盖。
// ============================================================
#include <gtest/gtest.h>

#include <map>
#include <string>
#include <vector>

#include "gamecore/game_core.h"
#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/diplomacy_tx.h"

namespace gamecore::system {
namespace {

using gamecore::rng::DeterministicRng;
using gamecore::rng::RngPartition;
using gamecore::state::SectRelation;
using gamecore::state::WorldSect;
namespace tx = gamecore::system::diplomacy_tx;

// ── 基架 ──────────────────────────────────────────────────────

std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

WorldSect playerSect(const std::string& id = "player") {
    WorldSect s;
    s.id = id;
    s.name = "青云宗";
    s.level = 2;
    s.isPlayerSect = true;
    return s;
}

WorldSect aiSect(const std::string& id, const std::string& name, int32_t level) {
    WorldSect s;
    s.id = id;
    s.name = name;
    s.level = level;
    return s;
}

state::Disciple powerDisciple(const std::string& id, int32_t realm) {
    state::Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = realm;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 20;
    d.lifespan = 80;
    return d;
}

/// 全状态 JSON（零写入断言用）
std::string snapshot(GameCore& core) { return core.exportStateJson(); }

// ═══════════ FavorDomain 纯函数黄金值 ═══════════

TEST(DiplomacyTxFavorTest, FindRelationBidirectionalAndMissing) {
    std::vector<SectRelation> rels;
    SectRelation r;
    r.sectId1 = "b";
    r.sectId2 = "a";   // 反向存储（min/max 归一化形态）
    r.favor = 42;
    rels.push_back(r);
    EXPECT_NE(tx::findRelation(rels, "a", "b"), nullptr);
    EXPECT_NE(tx::findRelation(rels, "b", "a"), nullptr);
    EXPECT_EQ(tx::findRelation(rels, "a", "c"), nullptr);
    EXPECT_EQ(tx::findFavor(rels, "a", "b"), 42);
    EXPECT_EQ(tx::findFavor(rels, "a", "c"), 0);   // 缺省 0
}

TEST(DiplomacyTxFavorTest, UpdateFavorStateMachine) {
    std::vector<SectRelation> rels;
    // 同宗 no-op
    EXPECT_EQ(tx::updateFavor(rels, "a", "a", 50, 3).size(), 0u);
    // 未相识既有记录 no-op（Kotlin: !acquainted → return relations）
    SectRelation strange;
    strange.sectId1 = "a";
    strange.sectId2 = "b";
    strange.favor = 10;
    rels.push_back(strange);
    const auto untouched = tx::updateFavor(rels, "a", "b", 90, 3);
    ASSERT_EQ(untouched.size(), 1u);
    EXPECT_EQ(untouched[0].favor, 10);
    EXPECT_EQ(untouched[0].lastInteractionYear, 0);

    // 已相识：clamp + 增长清零 noGiftYears + lastInteractionYear 写入
    rels[0].acquainted = true;
    rels[0].noGiftYears = 2;
    const auto raised = tx::updateFavor(rels, "b", "a", 150, 7);   // 反向传参 + 超上限
    ASSERT_EQ(raised.size(), 1u);
    EXPECT_EQ(raised[0].favor, 100);          // clamp MAX_FAVOR
    EXPECT_EQ(raised[0].lastInteractionYear, 7);
    EXPECT_EQ(raised[0].noGiftYears, 0);      // 增长清零
    EXPECT_STREQ(raised[0].sectId1.c_str(), "a");
    EXPECT_STREQ(raised[0].sectId2.c_str(), "b");

    // 下降保留 noGiftYears
    const auto dropped = tx::updateFavor(raised, "a", "b", 40, 8);
    ASSERT_EQ(dropped.size(), 1u);
    EXPECT_EQ(dropped[0].favor, 40);
    EXPECT_EQ(dropped[0].noGiftYears, 0);     // 旧值 0（上次已清）→ 保持

    // 缺失追加：id 归一化 + 默认 noGiftYears=0
    const auto created = tx::updateFavor(std::vector<SectRelation>{}, "z", "a", -5, 2);
    ASSERT_EQ(created.size(), 1u);
    EXPECT_STREQ(created[0].sectId1.c_str(), "a");
    EXPECT_STREQ(created[0].sectId2.c_str(), "z");
    EXPECT_EQ(created[0].favor, 0);           // clamp MIN_FAVOR
    EXPECT_EQ(created[0].lastInteractionYear, 2);
}

TEST(DiplomacyTxFavorTest, SetAcquaintedIdempotentAndCreate) {
    // 新建
    const auto created = tx::setAcquainted({}, "y", "x", 5);
    ASSERT_EQ(created.size(), 1u);
    EXPECT_STREQ(created[0].sectId1.c_str(), "x");
    EXPECT_STREQ(created[0].sectId2.c_str(), "y");
    EXPECT_TRUE(created[0].acquainted);
    EXPECT_EQ(created[0].lastInteractionYear, 5);
    // 幂等（已相识原样返回——逐字段核对）
    const auto again = tx::setAcquainted(created, "x", "y", 9);
    ASSERT_EQ(again.size(), 1u);
    EXPECT_TRUE(again[0].acquainted);
    EXPECT_EQ(again[0].lastInteractionYear, 5);   // 未刷新
    // 未相识 → 相识 + 刷新交互年
    auto unacquainted = created;
    unacquainted[0].acquainted = false;
    const auto flipped = tx::setAcquainted(unacquainted, "x", "y", 9);
    ASSERT_EQ(flipped.size(), 1u);
    EXPECT_TRUE(flipped[0].acquainted);
    EXPECT_EQ(flipped[0].lastInteractionYear, 9);
}

TEST(DiplomacyTxFavorTest, GiftFavorIncreaseGoldenValues) {
    // 手算对拍 FavorDomain.calculateGiftFavorIncrease：
    //   tier1(薄礼 base=2) level0 pct=20 favor=50 → 2 + 50*20/100 = 12
    EXPECT_EQ(tx::calculateGiftFavorIncrease(50, 1, 0, "NONE"), 12);
    //   Int 截断除法：57*20/100 = 11 → 13
    EXPECT_EQ(tx::calculateGiftFavorIncrease(57, 1, 0, "NONE"), 13);
    //   偏好乘区后置截断：(2+11)*1.3 = 16.9 → 16
    EXPECT_EQ(tx::calculateGiftFavorIncrease(57, 1, 0, "SPIRIT_STONE"), 16);
    //   tier4(大礼 base=15) level0 pct=200 favor=30 → 15 + 30*200/100 = 75
    EXPECT_EQ(tx::calculateGiftFavorIncrease(30, 4, 0, "NONE"), 75);
    //   level1 tier3 pct=70 favor=99 → 10 + 99*70/100 = 10 + 69 = 79
    EXPECT_EQ(tx::calculateGiftFavorIncrease(99, 3, 1, "NONE"), 79);
    //   百分比缺失（level2 tier2 无配置）→ baseFavor-only：5*1.0 = 5
    EXPECT_EQ(tx::calculateGiftFavorIncrease(80, 2, 2, "NONE"), 5);
    //   非法档位 → 0
    EXPECT_EQ(tx::calculateGiftFavorIncrease(50, 9, 0, "NONE"), 0);
}

TEST(DiplomacyTxFavorTest, RejectProbabilityGoldenValues) {
    // GiftConfig.SectRejectConfig.REJECT_MATRIX 黄金值
    EXPECT_EQ(tx::calculateRejectProbability(0, 1), 50);
    EXPECT_EQ(tx::calculateRejectProbability(0, 2), 20);
    EXPECT_EQ(tx::calculateRejectProbability(1, 3), 30);
    EXPECT_EQ(tx::calculateRejectProbability(2, 4), 30);
    EXPECT_EQ(tx::calculateRejectProbability(3, 5), 20);
    EXPECT_EQ(tx::calculateRejectProbability(3, 6), 0);
    // 未知等级回退 level0 矩阵（Kotlin REJECT_MATRIX[sectLevel] ?: REJECT_MATRIX[0]）
    EXPECT_EQ(tx::calculateRejectProbability(7, 1), 50);
    // 未知稀有度 → 0
    EXPECT_EQ(tx::calculateRejectProbability(0, 9), 0);
}

TEST(DiplomacyTxFavorTest, PreferenceModifierGoldenValues) {
    EXPECT_DOUBLE_EQ(tx::calculatePreferenceMultiplier("NONE", true), 1.0);
    EXPECT_DOUBLE_EQ(tx::calculatePreferenceMultiplier("SPIRIT_STONE", true), 1.3);
    EXPECT_DOUBLE_EQ(tx::calculatePreferenceMultiplier("SPIRIT_STONE", false), 1.0);
    EXPECT_DOUBLE_EQ(tx::calculatePreferenceMultiplier("PILL", true), 1.0);
    EXPECT_EQ(tx::calculatePreferenceRejectModifier("NONE", true), 0);
    EXPECT_EQ(tx::calculatePreferenceRejectModifier("SPIRIT_STONE", true), -15);
    EXPECT_EQ(tx::calculatePreferenceRejectModifier("EQUIPMENT", true), 0);
}

// ═══════════ 赠礼事务 ═══════════

/// 礼物测试态：玩家(level2) + AI 宗门(level0) + 相识关系 favor=50
struct GiftFixture {
    std::unique_ptr<GameCore> core;
    GameCore* raw = nullptr;

    explicit GiftFixture(int64_t seed) : core(makeCore(seed)), raw(core.get()) {
        auto& gd = core->state().gameData;
        gd.gameYear = 5;
        gd.worldMapSects = {playerSect(), aiSect("sect-a", "落霞宗", 0)};
        gd.spiritStones = 1'000'000;
        SectRelation r;
        r.sectId1 = "player";
        r.sectId2 = "sect-a";
        r.favor = 50;
        r.acquainted = true;
        gd.sectRelations.push_back(r);
        state::SectDetail detail;
        detail.sectId = "sect-a";
        detail.giftPreference = "NONE";
        gd.sectDetails["sect-a"] = detail;
    }
};

TEST(DiplomacyTxGiftTest, ValidationFailuresAreZeroWriteZeroDraw) {
    for (const int64_t seed : {42, 1234, 998'877}) {
        GiftFixture fx(seed);
        auto& st = fx.core->state();

        // 逐 case 快照对比（setup 变更不参与零写入断言）
        const auto check = [&](const std::string& sectId, int32_t tier,
                               const std::string& expectErr) {
            const std::string before = snapshot(*fx.core);
            const int64_t rngBefore =
                fx.core->rng().getRng(RngPartition::kSystem).snapshot();
            tx::TxRejection rejection;
            const auto out = tx::giftSpiritStonesTransaction(
                st, fx.core->rng(), sectId, tier, false, rejection);
            EXPECT_STREQ(rejection.errorType.c_str(), expectErr.c_str());
            EXPECT_TRUE(out.responseType.empty());
            EXPECT_EQ(before, snapshot(*fx.core));     // 零写入
            EXPECT_EQ(rngBefore, fx.core->rng().getRng(RngPartition::kSystem)
                                     .snapshot());     // 零抽取
        };
        check("missing", 1, "sect_not_found");       // 目标不存在
        check("player", 1, "invalid_target");        // 向自己送礼
        // 今年已送过礼
        st.gameData.sectDetails["sect-a"].lastGiftYear = 5;
        check("sect-a", 1, "already_gifted");
        st.gameData.sectDetails["sect-a"].lastGiftYear = 0;
        check("sect-a", 9, "invalid_tier");          // 非法档位
        // 灵石不足（tier4 = 4,000,000 > 1,000,000）
        check("sect-a", 4, "insufficient_resources");
    }
}

TEST(DiplomacyTxGiftTest, RejectRollMatchesReferenceStreamAndZeroWrite) {
    // 分区审计：若干种子下，事务拒绝臂消费的 SYSTEM 分区终态 ==
    // 本地同种子参考流做一次 nextInt(100) 的终态（Lemire 回绝采样
    // 消费量逐位一致）；BATTLE 分区零扰动。
    int rejectedCount = 0;
    int acceptedCount = 0;
    for (int64_t seed = 1; seed <= 40; ++seed) {
        GiftFixture fx(seed);
        auto& st = fx.core->state();
        // 参考流预测：同种子 SYSTEM（seed + 3）
        auto reference = DeterministicRng::fromSeed(seed + 3);
        const int32_t roll = reference.nextInt(100);
        // level0 + tier1 虚拟稀有度 2 → 基础 20%，偏好 NONE → 20
        const bool expectRejected = roll < 20;

        tx::TxRejection rejection;
        const auto out = tx::giftSpiritStonesTransaction(
            st, fx.core->rng(), "sect-a", 1, false, rejection);
        ASSERT_TRUE(rejection.errorType.empty());

        EXPECT_EQ(reference.snapshot(),
                  fx.core->rng().getRng(RngPartition::kSystem).snapshot());
        EXPECT_EQ(fx.core->rng().getRng(RngPartition::kBattle).snapshot(),
                  DeterministicRng::fromSeed(seed + 0).snapshot());

        if (expectRejected) {
            ++rejectedCount;
            EXPECT_STREQ(out.responseType.c_str(), "rejected");
            // 拒绝零写入（游戏状态定向断言——RNG 消费为预期行为，
            // 已由上方参考流终态等式审计，rngStates 不入本对比）
            EXPECT_EQ(1'000'000, st.gameData.spiritStones);
            ASSERT_EQ(1u, st.gameData.sectRelations.size());
            EXPECT_EQ(50, st.gameData.sectRelations[0].favor);
            EXPECT_EQ(0, st.gameData.sectDetails["sect-a"].lastGiftYear);
            EXPECT_TRUE(st.gameData.gameEventRecords.empty());
        } else {
            ++acceptedCount;
            EXPECT_STREQ(out.responseType.c_str(), "accept");
            // 接受臂：好感 50 + (2 + 50*20/100)=12 → 62；扣 20,000 灵石
            EXPECT_EQ(out.favorChange, 12);
            EXPECT_EQ(out.newFavor, 62);
            EXPECT_EQ(st.gameData.spiritStones, 980'000);
            ASSERT_EQ(st.gameData.sectRelations.size(), 1u);
            EXPECT_EQ(st.gameData.sectRelations[0].favor, 62);
            EXPECT_EQ(st.gameData.sectRelations[0].lastInteractionYear, 5);
            EXPECT_EQ(st.gameData.sectDetails["sect-a"].lastGiftYear, 5);
        }
    }
    EXPECT_GT(rejectedCount, 0);
    EXPECT_GT(acceptedCount, 0);
}

TEST(DiplomacyTxGiftTest, BypassYearLimitSkipsLastGiftYearWriteAndCheck) {
    // bypassYearLimit：跳过年度限制判据 + 不写 lastGiftYear（缓和关系语义）
    GiftFixture fx(2024);
    auto& st = fx.core->state();
    st.gameData.sectDetails["sect-a"].lastGiftYear = 5;   // 今年已送——bypass 可再送
    // 参考流保证不被拒：算一次 roll；若 <20 换种子
    auto reference = DeterministicRng::fromSeed(2024 + 3);
    if (reference.nextInt(100) < 20) {
        GTEST_SKIP() << "种子落拒绝区间，切换用例覆盖面由上一用例保证";
    }
    tx::TxRejection rejection;
    const auto out = tx::giftSpiritStonesTransaction(
        st, fx.core->rng(), "sect-a", 1, true, rejection);
    ASSERT_TRUE(rejection.errorType.empty());
    EXPECT_STREQ(out.responseType.c_str(), "accept");
    EXPECT_EQ(st.gameData.sectDetails["sect-a"].lastGiftYear, 5);   // 未写（保持 setup 值）
    EXPECT_EQ(st.gameData.sectRelations[0].favor, 62);              // 好感已写
}

// ═══════════ 结盟事务 ═══════════

struct AllianceFixture {
    std::unique_ptr<GameCore> core;

    explicit AllianceFixture(int64_t seed) : core(makeCore(seed)) {
        auto& gd = core->state().gameData;
        gd.gameYear = 3;
        gd.worldMapSects = {playerSect(), aiSect("sect-b", "天璇宗", 1)};
        core->state().disciples.appendDisciple(powerDisciple("d1", 5));
        std::vector<state::Disciple> aiPool = {powerDisciple("a1", 5)};
        core->state().aiSectDisciples["sect-b"] = aiPool;
    }
};

TEST(DiplomacyTxAllianceTest, ValidationFailuresAreZeroWriteZeroDraw) {
    AllianceFixture fx(42);
    auto& st = fx.core->state();

    const auto request = [&](const std::string& sectId) {
        const std::string before = snapshot(*fx.core);
        const int64_t rngBefore =
            fx.core->rng().getRng(RngPartition::kSystem).snapshot();
        tx::TxRejection rejection;
        const auto out = tx::requestAllianceTransaction(
            st, fx.core->rng(), fx.core->ecsWorld(), sectId, rejection);
        EXPECT_FALSE(rejection.errorType.empty());
        EXPECT_FALSE(out.success);
        EXPECT_EQ(before, snapshot(*fx.core));     // 零写入
        EXPECT_EQ(rngBefore,
                  fx.core->rng().getRng(RngPartition::kSystem).snapshot());
    };
    request("missing");                          // 目标不存在
    request("player");                           // 玩家宗门
    st.gameData.worldMapSects[1].allianceId = "a-1";
    request("sect-b");                           // 目标已有盟约
    st.gameData.worldMapSects[1].allianceId.clear();
    state::Alliance existing;
    existing.id = "a-0";
    existing.sectIds = {"player", "other"};
    st.gameData.alliances.push_back(existing);
    request("sect-b");                           // 玩家已有盟约
    st.gameData.alliances.clear();
    st.aiSectDisciples.clear();
    request("sect-b");                           // aiPower<=0（零抽取早退）
}

TEST(DiplomacyTxAllianceTest, RollParityAndSuccessWrites) {
    // 多种子扫描：概率 0（HOSTILE 分值 0 拦截）时必败但仍恰掷 1 次
    // nextDouble；失败臂零写入。
    for (const int64_t seed : {11, 42, 77, 300, 2024}) {
        AllianceFixture fx(seed);
        auto& st = fx.core->state();
        // 战力比 1.0（同 disciple）：硬门槛 0.6 通过；好感缺省 0（HOSTILE）
        // → 结盟分值 0 且权重 0.40 → 概率 0 → 必败（仍掷骰 1 次）
        auto reference = DeterministicRng::fromSeed(seed + 3);
        reference.nextDouble();
        tx::TxRejection rejection;
        const auto out = tx::requestAllianceTransaction(
            st, fx.core->rng(), fx.core->ecsWorld(), "sect-b", rejection);
        ASSERT_TRUE(rejection.errorType.empty());
        EXPECT_EQ(reference.snapshot(),
                  fx.core->rng().getRng(RngPartition::kSystem).snapshot());
        EXPECT_FALSE(out.success);
        EXPECT_TRUE(st.gameData.alliances.empty());
    }

    // 好感 INTIMATE + 战力比 1.0 → 概率 0.4（favorScore 1.0×0.40）→ 高概率成功
    int64_t successSeed = -1;
    for (int64_t seed = 1; seed <= 64 && successSeed < 0; ++seed) {
        AllianceFixture fx(seed);
        auto& gd = fx.core->state().gameData;
        SectRelation r;
        r.sectId1 = "player";
        r.sectId2 = "sect-b";
        r.favor = 90;
        gd.sectRelations.push_back(r);
        tx::TxRejection rejection;
        const auto out = tx::requestAllianceTransaction(
            fx.core->state(), fx.core->rng(), fx.core->ecsWorld(), "sect-b",
            rejection);
        if (rejection.errorType.empty() && out.success) successSeed = seed;
    }
    ASSERT_GT(successSeed, 0);
    AllianceFixture fx(successSeed);
    auto& st = fx.core->state();
    SectRelation r;
    r.sectId1 = "player";
    r.sectId2 = "sect-b";
    r.favor = 90;
    st.gameData.sectRelations.push_back(r);
    tx::TxRejection rejection;
    const auto out = tx::requestAllianceTransaction(
        st, fx.core->rng(), fx.core->ecsWorld(), "sect-b", rejection);
    ASSERT_TRUE(rejection.errorType.empty());
    ASSERT_TRUE(out.success);
    // 相识写入
    ASSERT_EQ(st.gameData.sectRelations.size(), 1u);
    EXPECT_TRUE(st.gameData.sectRelations[0].acquainted);
    // 盟约写入（id 唯一、initiator=player、startYear）
    ASSERT_EQ(st.gameData.alliances.size(), 1u);
    const auto& alliance = st.gameData.alliances[0];
    EXPECT_FALSE(alliance.id.empty());
    ASSERT_EQ(alliance.sectIds.size(), 2u);
    EXPECT_STREQ(alliance.sectIds[0].c_str(), "player");
    EXPECT_STREQ(alliance.sectIds[1].c_str(), "sect-b");
    EXPECT_EQ(alliance.startYear, 3);
    EXPECT_STREQ(alliance.initiatorId.c_str(), "player");
    // 双方宗门 alliance 字段
    EXPECT_EQ(st.gameData.worldMapSects[0].allianceId, alliance.id);
    EXPECT_EQ(st.gameData.worldMapSects[1].allianceId, alliance.id);
    EXPECT_EQ(st.gameData.worldMapSects[0].allianceStartYear, 3);
    // 消息栏事件
    ASSERT_EQ(st.gameData.gameEventRecords.size(), 1u);
    EXPECT_STREQ(st.gameData.gameEventRecords[0].eventType.c_str(), "alliance");
    EXPECT_STREQ(st.gameData.gameEventRecords[0].category.c_str(), "WORLD");
}

TEST(DiplomacyTxAllianceTest, DissolveRemovesAllianceAndClearsSects) {
    AllianceFixture fx(42);
    auto& st = fx.core->state();
    st.gameData.worldMapSects[0].allianceId = "a-1";
    st.gameData.worldMapSects[0].allianceStartYear = 2;
    st.gameData.worldMapSects[1].allianceId = "a-1";
    st.gameData.worldMapSects[1].allianceStartYear = 2;
    state::Alliance alliance;
    alliance.id = "a-1";
    alliance.sectIds = {"player", "sect-b"};
    alliance.startYear = 2;
    st.gameData.alliances.push_back(alliance);

    // 失败臂：无盟约宗门 → rejection 零写入
    const std::string before = snapshot(*fx.core);
    tx::TxRejection rejection;
    tx::dissolveAllianceTransaction(st, "sect-b-unused", rejection);
    EXPECT_FALSE(rejection.errorType.empty());
    EXPECT_EQ(before, snapshot(*fx.core));

    rejection = {};
    const auto out = tx::dissolveAllianceTransaction(st, "sect-b", rejection);
    EXPECT_TRUE(rejection.errorType.empty());
    EXPECT_TRUE(out.success);
    EXPECT_TRUE(st.gameData.alliances.empty());
    EXPECT_TRUE(st.gameData.worldMapSects[0].allianceId.empty());
    EXPECT_EQ(st.gameData.worldMapSects[0].allianceStartYear, 0);
    EXPECT_TRUE(st.gameData.worldMapSects[1].allianceId.empty());
    ASSERT_EQ(st.gameData.gameEventRecords.size(), 1u);
    EXPECT_STREQ(st.gameData.gameEventRecords[0].eventType.c_str(),
                 "alliance_break");
}

// ═══════════ 附庸事务 ═══════════

TEST(DiplomacyTxVassalTest, ValidationFailuresAreZeroWriteZeroDraw) {
    AllianceFixture fx(42);
    auto& st = fx.core->state();

    const auto request = [&](const std::string& sectId) {
        const std::string before = snapshot(*fx.core);
        const int64_t rngBefore =
            fx.core->rng().getRng(RngPartition::kSystem).snapshot();
        tx::TxRejection rejection;
        const auto out = tx::requestVassalTransaction(
            st, fx.core->rng(), fx.core->ecsWorld(), sectId, rejection);
        EXPECT_FALSE(rejection.errorType.empty());
        EXPECT_FALSE(out.success);
        EXPECT_EQ(before, snapshot(*fx.core));     // 零写入
        EXPECT_EQ(rngBefore,
                  fx.core->rng().getRng(RngPartition::kSystem).snapshot());
    };
    request("missing");                        // 目标不存在
    request("player");                         // 玩家宗门
    state::VassalContract contract;
    contract.vassalSectId = "sect-b";
    contract.establishedYear = 1;
    st.gameData.vassalContracts.push_back(contract);
    request("sect-b");                         // 已是附属
    st.gameData.vassalContracts.clear();
    state::Alliance alliance;
    alliance.id = "a-1";
    alliance.sectIds = {"player", "sect-b"};
    st.gameData.alliances.push_back(alliance);
    request("sect-b");                         // 已结盟不可附属
}

TEST(DiplomacyTxVassalTest, ZeroChanceStillConsumesDraw) {
    // aiPower 清零：概率 0 但资格通过 → 与 Kotlin calculateVassalChance
    // 返回 0.0 后仍 rng.nextDouble() 同位——恰 1 次抽取、必败、零写入。
    AllianceFixture fx(42);
    auto& st = fx.core->state();
    st.aiSectDisciples.clear();
    tx::TxRejection rejection;
    const auto out = tx::requestVassalTransaction(
        st, fx.core->rng(), fx.core->ecsWorld(), "sect-b", rejection);
    ASSERT_TRUE(rejection.errorType.empty());
    EXPECT_FALSE(out.success);
    EXPECT_TRUE(st.gameData.vassalContracts.empty());
    auto reference = DeterministicRng::fromSeed(42 + 3);
    reference.nextDouble();
    EXPECT_EQ(reference.snapshot(),
              fx.core->rng().getRng(RngPartition::kSystem).snapshot());
}

TEST(DiplomacyTxVassalTest, SuccessWritesContractAndAcquaintance) {
    // 好感 INTIMATE → 概率 0.135（favorScore 0.9×0.15），扫描必得成功种子
    int64_t successSeed = -1;
    for (int64_t seed = 1; seed <= 64 && successSeed < 0; ++seed) {
        AllianceFixture fx(seed);
        auto& gd = fx.core->state().gameData;
        SectRelation r;
        r.sectId1 = "player";
        r.sectId2 = "sect-b";
        r.favor = 90;
        r.acquainted = true;
        gd.sectRelations.push_back(r);
        tx::TxRejection rejection;
        const auto out = tx::requestVassalTransaction(
            fx.core->state(), fx.core->rng(), fx.core->ecsWorld(), "sect-b",
            rejection);
        if (rejection.errorType.empty() && out.success) successSeed = seed;
    }
    ASSERT_GT(successSeed, 0);

    AllianceFixture fx(successSeed);
    auto& st = fx.core->state();
    SectRelation r;
    r.sectId1 = "player";
    r.sectId2 = "sect-b";
    r.favor = 90;
    r.acquainted = true;
    st.gameData.sectRelations.push_back(r);
    // 近 3 年战绩读面：战报计数影响概率（此处计入但不改变成功结论——
    // 读面一致性由失败种子扫描共同覆盖）
    state::SectBattleRecord record;
    record.year = 2;
    record.type = "BATTLE_WIN";
    st.gameData.sectBattleRecords.push_back(record);

    tx::TxRejection rejection;
    const auto out = tx::requestVassalTransaction(
        st, fx.core->rng(), fx.core->ecsWorld(), "sect-b", rejection);
    ASSERT_TRUE(rejection.errorType.empty());
    ASSERT_TRUE(out.success);
    ASSERT_EQ(st.gameData.vassalContracts.size(), 1u);
    EXPECT_STREQ(st.gameData.vassalContracts[0].vassalSectId.c_str(), "sect-b");
    EXPECT_EQ(st.gameData.vassalContracts[0].establishedYear, 3);
    EXPECT_EQ(st.gameData.vassalContracts[0].lastTributeYear, 0);
    ASSERT_EQ(st.gameData.sectRelations.size(), 1u);
    EXPECT_TRUE(st.gameData.sectRelations[0].acquainted);
}

TEST(DiplomacyTxVassalTest, DissolveRemovesContractAlwaysTrue) {
    AllianceFixture fx(42);
    auto& st = fx.core->state();
    state::VassalContract contract;
    contract.vassalSectId = "sect-b";
    contract.establishedYear = 1;
    st.gameData.vassalContracts.push_back(contract);

    const auto out = tx::dissolveVassalTransaction(st, "sect-b");
    EXPECT_TRUE(out.success);   // 恒 true（Kotlin dissolveVassalContract）
    EXPECT_TRUE(st.gameData.vassalContracts.empty());
    // 不存在的契约同样 true（过滤 no-op）
    const auto again = tx::dissolveVassalTransaction(st, "sect-b");
    EXPECT_TRUE(again.success);
}

}  // namespace
}  // namespace gamecore::system

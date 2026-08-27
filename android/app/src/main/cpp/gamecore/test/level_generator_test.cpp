#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/level_generator.h"

namespace gamecore::system {
namespace {

using gamecore::rng::RngManager;
using gamecore::state::WorldLevel;
using gamecore::state::WorldSect;

// ── beastRealmStats 表 ─────────────────────────────────────────

TEST(LevelGeneratorTest, BeastRealmStatsTable) {
    const auto s9 = beastRealmStats(9);   // 炼气
    EXPECT_EQ(s9.hp, 339);
    EXPECT_EQ(s9.speed, 16);
    const auto s0 = beastRealmStats(0);   // 仙人
    EXPECT_EQ(s0.hp, 846353);
    const auto sUnknown = beastRealmStats(99);  // 未知回退炼气
    EXPECT_EQ(sUnknown.hp, 339);
}

// ── beastTypeConfig ────────────────────────────────────────────

TEST(LevelGeneratorTest, BeastTypeConfigTable) {
    const auto& t0 = beastTypeConfig(0);  // 虎妖
    EXPECT_EQ(t0.name, "虎妖");
    EXPECT_EQ(t0.prefix, "狂暴");
    EXPECT_DOUBLE_EQ(t0.hpMod, 1.3);
    const auto& t7 = beastTypeConfig(7);  // 龟妖
    EXPECT_EQ(t7.name, "龟妖");
    EXPECT_DOUBLE_EQ(t7.defMod, 1.5);
    // 越界回退（Kotlin getOrElse { TYPES[0] }）
    EXPECT_EQ(beastTypeConfig(-1).name, "虎妖");
    EXPECT_EQ(beastTypeConfig(99).name, "虎妖");
}

// ── caveReward / maxRarityForRealm / realmName ─────────────────

TEST(LevelGeneratorTest, CaveRewardConfig) {
    EXPECT_DOUBLE_EQ(caveReward(5).baseSpiritStones, 20000.0);
    EXPECT_DOUBLE_EQ(caveReward(1).baseSpiritStones, 1500000.0);
    EXPECT_EQ(caveReward(2).rarityMax, 6);
    EXPECT_EQ(caveReward(99).rarityMin, 1);   // 默认化神档
}

TEST(LevelGeneratorTest, MaxRarityForRealm) {
    EXPECT_EQ(maxRarityForRealm(9), 1);
    EXPECT_EQ(maxRarityForRealm(7), 2);
    EXPECT_EQ(maxRarityForRealm(5), 4);
    EXPECT_EQ(maxRarityForRealm(2), 6);
    EXPECT_EQ(maxRarityForRealm(0), 6);
}

TEST(LevelGeneratorTest, RealmName) {
    EXPECT_EQ(realmName(9), "炼气");
    EXPECT_EQ(realmName(0), "仙人");
}

// ── selectBeastRealm ───────────────────────────────────────────

TEST(LevelGeneratorTest, SelectBeastRealmYear1Weights) {
    // 第 1 年权重：{1,3,8,15,30,60,120,220,320,400}——加权平均境界
    // = (0*1+1*3+2*8+3*15+4*30+5*60+6*120+7*220+8*320+9*400)/1177 ≈ 7.56。
    // 断言抽样平均落在 [7.0, 8.2]（宽松统计窗口，非严格边界）
    RngManager rng;
    rng.initSystemSeed(42);
    int64_t sum = 0;
    const int32_t kSamples = 2000;
    for (int32_t i = 0; i < kSamples; ++i) {
        sum += selectBeastRealm(rng, 1);
    }
    const double avg = static_cast<double>(sum) / kSamples;
    EXPECT_GT(avg, 7.0);
    EXPECT_LT(avg, 8.2);
}

TEST(LevelGeneratorTest, SelectBeastRealmClampToPlayerAvg) {
    RngManager rng;
    rng.initSystemSeed(7);
    // playerAvgRealm=4 → clamp [3,6]
    for (int32_t i = 0; i < 50; ++i) {
        const int32_t realm = selectBeastRealm(rng, 100, /*playerAvgRealm=*/&(const int32_t&)4);
        EXPECT_GE(realm, 3);
        EXPECT_LE(realm, 6);
    }
}

TEST(LevelGeneratorTest, SelectBeastRealmDeterministic) {
    auto run = [](int64_t seed) {
        RngManager rng;
        rng.initSystemSeed(seed);
        return selectBeastRealm(rng, 500);
    };
    EXPECT_EQ(run(42), run(42));
}

// ── isValidLevelPosition ───────────────────────────────────────

TEST(LevelGeneratorTest, PositionValidation) {
    const std::vector<std::pair<int32_t, int32_t>> used = {{100, 100}};
    std::vector<WorldSect> sects;
    WorldSect sect;
    sect.x = 200;
    sect.y = 200;
    sects.push_back(sect);
    std::vector<WorldLevel> levels;
    WorldLevel l;
    l.x = 300;
    l.y = 300;
    levels.push_back(l);

    EXPECT_FALSE(isValidLevelPosition(100, 100, used, sects, levels));  // 占用
    EXPECT_FALSE(isValidLevelPosition(190, 200, used, sects, levels));  // 离宗门 10 < 28
    EXPECT_FALSE(isValidLevelPosition(290, 300, used, sects, levels));  // 离关卡 10 < 20
    EXPECT_TRUE(isValidLevelPosition(400, 400, used, sects, levels));   // 合法
}

// ── generateBeastLevel ─────────────────────────────────────────

TEST(LevelGeneratorTest, GenerateBeastLevelFields) {
    RngManager rng;
    rng.initSystemSeed(42);
    const int32_t avg = 5;
    const auto level = generateBeastLevel(rng, 1, 3, 400, 400, &avg);
    EXPECT_EQ(level.type, "BEAST");
    EXPECT_GE(level.realm, 0);
    EXPECT_LE(level.realm, 9);
    EXPECT_GE(level.realmLayer, 1);
    EXPECT_LE(level.realmLayer, 9);
    EXPECT_FALSE(level.beastName.empty());
    EXPECT_GT(level.beastMaxHp, 0);
    EXPECT_GT(level.beastSpeed, 0);
    // 持续 6 个月：3 月 + 6 = 9 月
    EXPECT_EQ(level.spawnYear, 1);
    EXPECT_EQ(level.spawnMonth, 3);
    EXPECT_EQ(level.expiryYear, 1);
    EXPECT_EQ(level.expiryMonth, 9);
    // playerAvgRealm=5 → clamp [4,7]
    EXPECT_GE(level.realm, 4);
    EXPECT_LE(level.realm, 7);
}

TEST(LevelGeneratorTest, GenerateBeastLevelExpiryYearRollover) {
    RngManager rng;
    rng.initSystemSeed(1);
    const auto level = generateBeastLevel(rng, 3, 10, 400, 400);  // 10 月 + 6 = 16 → 次年 4 月
    EXPECT_EQ(level.expiryYear, 4);
    EXPECT_EQ(level.expiryMonth, 4);
}

// ── generateCaveLevel ──────────────────────────────────────────

TEST(LevelGeneratorTest, GenerateCaveLevelFields) {
    RngManager rng;
    rng.initSystemSeed(99);
    const auto level = generateCaveLevel(rng, 2, 5, 400, 400);
    EXPECT_EQ(level.type, "CAVE");
    EXPECT_GE(level.realm, 1);
    EXPECT_LE(level.realm, 5);
    EXPECT_FALSE(level.guardianName.empty());
    EXPECT_FALSE(level.caveName.empty());
    EXPECT_EQ(level.count, 2);
    EXPECT_GE(level.caveImageIndex, 0);
    EXPECT_LE(level.caveImageIndex, 2);
    EXPECT_EQ(level.expiryYear, 2);
    EXPECT_EQ(level.expiryMonth, 11);  // 5 + 6 = 11
}

// ── generateWorldLevels ────────────────────────────────────────

TEST(LevelGeneratorTest, GenerateWorldLevelsCountAndFields) {
    RngManager rng;
    rng.initSystemSeed(42);
    std::vector<WorldSect> sects;
    WorldSect playerSect;
    playerSect.x = 800;
    playerSect.y = 400;
    playerSect.isPlayerSect = true;
    sects.push_back(playerSect);
    std::vector<WorldLevel> existing;
    const auto r = generateWorldLevels(rng, sects, 1, 1, existing, 6, /*playerAvgRealm=*/nullptr);
    EXPECT_GT(r.levels.size(), 0u);
    EXPECT_LE(r.levels.size(), 6u);
    EXPECT_TRUE(r.generated);
    for (const auto& level : r.levels) {
        EXPECT_TRUE(level.type == "BEAST" || level.type == "CAVE");
        // 边界内
        EXPECT_GE(level.x, 34.0f);
        EXPECT_LE(level.x, 1664.0f);
        EXPECT_GE(level.y, 34.0f);
        EXPECT_LE(level.y, 892.0f);
        // 与玩家宗门距离 >= 28
        const float dx = level.x - playerSect.x;
        const float dy = level.y - playerSect.y;
        EXPECT_GE(std::sqrt(dx * dx + dy * dy), 28.0f);
    }
}

TEST(LevelGeneratorTest, GenerateWorldLevelsMaxZero) {
    RngManager rng;
    rng.initSystemSeed(42);
    const auto r = generateWorldLevels(rng, {}, 1, 1, {}, 0);
    EXPECT_FALSE(r.generated);
    EXPECT_TRUE(r.levels.empty());
}

TEST(LevelGeneratorTest, GenerateWorldLevelsDeterministic) {
    auto run = []() {
        RngManager rng;
        rng.initSystemSeed(42);
        std::vector<WorldSect> sects;
        WorldSect s;
        s.x = 800;
        s.y = 400;
        sects.push_back(s);
        return generateWorldLevels(rng, sects, 1, 1, {}, 6);
    };
    const auto a = run();
    const auto b = run();
    ASSERT_EQ(a.levels.size(), b.levels.size());
    for (std::size_t i = 0; i < a.levels.size(); ++i) {
        EXPECT_EQ(a.levels[i].type, b.levels[i].type);
        EXPECT_EQ(a.levels[i].realm, b.levels[i].realm);
        EXPECT_EQ(a.levels[i].beastName, b.levels[i].beastName);
        EXPECT_EQ(a.levels[i].x, b.levels[i].x);
        EXPECT_EQ(a.levels[i].y, b.levels[i].y);
        EXPECT_EQ(a.levels[i].beastMaxHp, b.levels[i].beastMaxHp);
    }
}

}  // namespace
}  // namespace gamecore::system

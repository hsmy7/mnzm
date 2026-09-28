// ============================================================
// game_config_parity_test — 结算/治理常量 C++ 侧锚点（方案 §9.1 缺陷 #15）
//
// 缺陷：kSpiritMineBoostMultiplier 等七常量为 C++ 硬编码副本，
// GameConfigConsistencyTest 只校验 Kotlin 两源、C++ 侧无任何校验面。
//
// 本测试 = **C++ 腿**：锁定七个常量的 C++ 值；Kotlin 腿 =
// android/app/src/test ConfigCppConstantsParityTest（读 C++ 头文件文本抽值
// 与 Kotlin 侧可达面/源码常量互断——改任一侧另一侧即红）。
// 改值须双端同步（GameTimeUnitsParityTest 先例）。
// ============================================================

#include "gtest/gtest.h"

#include "gamecore/system/exploration.h"
#include "gamecore/system/government.h"
#include "gamecore/system/month_settlement.h"
#include "gamecore/system/production.h"
#include "gamecore/system/secret_realm.h"

namespace gamecore {
namespace {
namespace stats = gamecore::stats;

TEST(GameConfigCppParityTest, SpiritMineBoostMultiplier) {
    // Kotlin 锚：CultivationSettlement.SPIRIT_MINE_BOOST_MULTIPLIER = 1.2
    //（乘区形态；GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_EFFECT = 0.20 = 1.2 − 1）
    EXPECT_EQ(1.2, system::kSpiritMineBoostMultiplier);
}

TEST(GameConfigCppParityTest, DeaconMoralityBonusRate) {
    // Kotlin 锚：CultivationSettlement.DEACON_MORALITY_BONUS_RATE = 0.01
    EXPECT_EQ(0.01, system::kDeaconMoralityBonusRate);
}

TEST(GameConfigCppParityTest, ElderSkillBaselineSingleValueAcrossHeaders) {
    // Kotlin 锚：GameConfig.Disciple.ELDER_SKILL_BASELINE = 80。
    // 同值在 government.h（system）/ production.h（system::production）/
    // disciple_stats.h（stats）三处独立定义——三处互等锁定（单点漂移即红）。
    EXPECT_EQ(80, system::kElderSkillBaseline);
    EXPECT_EQ(system::kElderSkillBaseline,
              system::production::kElderSkillBaseline);
    EXPECT_EQ(system::kElderSkillBaseline,
              stats::kElderSkillBaseline);
}

TEST(GameConfigCppParityTest, AiSkipCooldownMonths) {
    // Kotlin 锚：AISectBeastAttackProcessor.SKIP_COOLDOWN_MONTHS = 12
    EXPECT_EQ(12, system::detail::kAiSkipCooldownMonths);
}

TEST(GameConfigCppParityTest, LevelRefreshIntervalMonths) {
    // Kotlin 锚：WorldLevelManager.processMonthly 内联 `>= 3`（单一定义于此）
    EXPECT_EQ(3, system::kLevelRefreshIntervalMonths);
}

TEST(GameConfigCppParityTest, SecretRealmOpenYears) {
    // Kotlin 锚：GameConfig.SecretRealm.OPEN_YEARS = 5
    EXPECT_EQ(5, system::secret_realm_cfg::kOpenYears);
}

TEST(GameConfigCppParityTest, MonthlyDecayPhases) {
    // Kotlin 锚：HpMpRecoveryService.applyMonthlyDurationDecay 内联 `3`
    EXPECT_EQ(3, system::kMonthlyDecayPhases);
}

}  // namespace
}  // namespace gamecore

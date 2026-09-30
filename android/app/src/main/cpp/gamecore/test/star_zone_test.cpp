// ============================================================
// star_zone_test — 角色星级乘区守护（G09 §3.8 口径 A）
//
// 守护目标：
//   - 星级表逐位钉死（0/1★ ⇒ ×1.00；5★ ⇒ 战斗 +32% / 修炼 +20%）
//   - star=0 不得退化为 ×0.92/×0.95（口径 A 的 `(star-1)` 直接套用的后果）
//   - 星级反查走稀疏读法：0 星无键、空 templateId ⇒ 0 星，且**不写键**
//   - 战斗乘区在加权和之后整体乘、最后向零截断（与 Kotlin 同式）
//   - 1★ 弟子与"无星级"弟子的战力/修炼值逐位相同 ⇒ 既有属性/战力期望表零扰动
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <string>

#include "gamecore/state/models.h"
#include "gamecore/system/disciple.h"
#include "gamecore/system/sect_power.h"
#include "gamecore/system/star_zone.h"

namespace gamecore {
namespace {

using gamecore::system::battleStarMult;
using gamecore::system::cultivationStarBonus;
using gamecore::system::discipleCombatPower;
using gamecore::system::discipleCombatPowerWithStar;
using gamecore::system::resolveStar;
using gamecore::system::starZoneOf;
using gamecore::state::GameData;

constexpr const char* kTemplate = "zhouming";

// ── 口径 A 星级表 ────────────────────────────────────────────────

TEST(StarZoneTest, 星级表逐位符合口径A) {
    // 战斗：1★ 基线，之后每星 +8%
    EXPECT_DOUBLE_EQ(1.00, starZoneOf(0).battleMult);
    EXPECT_DOUBLE_EQ(1.00, starZoneOf(1).battleMult);
    EXPECT_DOUBLE_EQ(1.08, starZoneOf(2).battleMult);
    EXPECT_DOUBLE_EQ(1.16, starZoneOf(3).battleMult);
    EXPECT_DOUBLE_EQ(1.24, starZoneOf(4).battleMult);
    EXPECT_DOUBLE_EQ(1.32, starZoneOf(5).battleMult);
    // 修炼：1★ 基线，之后每星 +5%（以加成量表达，与其余乘区同构）
    EXPECT_DOUBLE_EQ(0.00, starZoneOf(0).cultivationBonus);
    EXPECT_DOUBLE_EQ(0.00, starZoneOf(1).cultivationBonus);
    EXPECT_DOUBLE_EQ(0.05, starZoneOf(2).cultivationBonus);
    EXPECT_DOUBLE_EQ(0.10, starZoneOf(3).cultivationBonus);
    EXPECT_DOUBLE_EQ(0.15, starZoneOf(4).cultivationBonus);
    EXPECT_DOUBLE_EQ(0.20, starZoneOf(5).cultivationBonus);
}

TEST(StarZoneTest, 零星不得退化为负加成) {
    // 直接把 star=0 套进 1+(star-1)*pct 会得到 ×0.92/×0.95——凭空削低下限。
    // 口径 A 要求 0 星与 1 星同为 ×1.00（未解锁 / 存量旧弟子不得被惩罚）
    const auto zone = starZoneOf(0);
    EXPECT_GE(zone.battleMult, 1.0);
    EXPECT_GE(zone.cultivationBonus, 0.0);
    EXPECT_DOUBLE_EQ(1.0, zone.battleMult);
}

TEST(StarZoneTest, 负数星级按基线处理) {
    // 账本被手改/污染成负值时不得放大成负数属性（下限保护）
    EXPECT_DOUBLE_EQ(1.0, starZoneOf(-3).battleMult);
    EXPECT_DOUBLE_EQ(0.0, starZoneOf(-3).cultivationBonus);
}

// ── 星级反查（稀疏账本）──────────────────────────────────────────

TEST(StarZoneTest, 未解锁与存量旧弟子恒零星且不写键) {
    GameData gd;
    EXPECT_EQ(0, resolveStar(gd, kTemplate));
    EXPECT_EQ(0, resolveStar(gd, ""));
    // 反查是只读操作：不得因为 operator[] 给稀疏账本建 0 星键
    EXPECT_TRUE(gd.gachaStarMap.empty());

    gd.gachaStarMap[kTemplate] = 3;
    EXPECT_EQ(3, resolveStar(gd, kTemplate));
    EXPECT_EQ(0, resolveStar(gd, "suqing"));
    EXPECT_EQ(1u, gd.gachaStarMap.size()) << "反查不得往账本里增键";
    // 空 templateId 即便账本里有别的键也恒 0 星（存量旧弟子）
    EXPECT_EQ(0, resolveStar(gd, ""));
}

TEST(StarZoneTest, 乘区快捷入口与直接取值同值) {
    GameData gd;
    gd.gachaStarMap[kTemplate] = 5;
    EXPECT_DOUBLE_EQ(1.32, battleStarMult(gd, kTemplate));
    EXPECT_DOUBLE_EQ(0.20, cultivationStarBonus(gd, kTemplate));
    EXPECT_DOUBLE_EQ(1.0, battleStarMult(gd, "unknown"));
    EXPECT_DOUBLE_EQ(0.0, cultivationStarBonus(gd, "unknown"));
}

// ── 战斗侧：先加权和、后乘、最后截断 ──────────────────────────────

TEST(StarZoneTest, 战力一星与无星逐位等于纯公式) {
    // 既有金标（单列口径 B1）：discipleCombatPower(180,1000,110,40) == 5310（旧双列取和等价）
    const int64_t base = discipleCombatPower(180, 1000, 110, 40);
    ASSERT_EQ(5310, base);
    EXPECT_EQ(base, discipleCombatPowerWithStar(180, 1000, 110, 40, 0));
    EXPECT_EQ(base, discipleCombatPowerWithStar(180, 1000, 110, 40, 1));
}

TEST(StarZoneTest, 战力满星按口径A上浮并向零截断) {
    const int64_t base = discipleCombatPower(180, 1000, 110, 40);
    // 5310 × 1.32 = 7009.2 → 7009（向零截断；Kotlin (base * mult).toLong() 同结果）
    EXPECT_EQ(7009, discipleCombatPowerWithStar(180, 1000, 110, 40, 5));
    // 中间星级用同式回算，防止有人把乘区挪到各属性上（整数逐项截断会偏离）
    EXPECT_EQ(static_cast<int64_t>(static_cast<double>(base) * 1.08),
              discipleCombatPowerWithStar(180, 1000, 110, 40, 2));
    EXPECT_GT(discipleCombatPowerWithStar(180, 1000, 110, 40, 3), base);
}

// ── 修炼侧：命名乘区（第 5 个）───────────────────────────────────

TEST(StarZoneTest, 修炼乘区星级为零或一时不改值) {
    gamecore::disciple::CultivationSpeedZones plain;
    plain.resourceBonus = 0.3;
    plain.socialBonus = 0.1;
    plain.statusBonus = 0.2;
    plain.temporaryBonus = 0.05;
    const double withoutStar = gamecore::disciple::calculateCultivationPerPhase(5, 2, plain);

    auto starred = plain;
    starred.starBonus = starZoneOf(0).cultivationBonus;
    EXPECT_DOUBLE_EQ(withoutStar, gamecore::disciple::calculateCultivationPerPhase(5, 2, starred));
    starred.starBonus = starZoneOf(1).cultivationBonus;
    EXPECT_DOUBLE_EQ(withoutStar, gamecore::disciple::calculateCultivationPerPhase(5, 2, starred));
}

TEST(StarZoneTest, 修炼乘区按每星五分上浮) {
    gamecore::disciple::CultivationSpeedZones plain;
    const double base = gamecore::disciple::calculateCultivationPerPhase(5, 2, plain);

    auto threeStar = plain;
    threeStar.starBonus = starZoneOf(3).cultivationBonus;
    EXPECT_DOUBLE_EQ(base * (1.0 + 0.10),
                     gamecore::disciple::calculateCultivationPerPhase(5, 2, threeStar));

    auto fiveStar = plain;
    fiveStar.starBonus = starZoneOf(5).cultivationBonus;
    EXPECT_DOUBLE_EQ(base * (1.0 + 0.20),
                     gamecore::disciple::calculateCultivationPerPhase(5, 2, fiveStar));
}

TEST(StarZoneTest, 乘区序不可交换性由固定顺序保证) {
    // 星级乘区排在最后一位（资源→社交→状态→临时→星级）。本用例把"星级与
    // 其余乘区相乘的顺序"钉成一个具体值：若有人调整顺序，浮点末位会漂。
    // 期望式刻意与实现同形（(1.0 + bonus) 逐个左结合），避免字面量二次舍入误判
    gamecore::disciple::CultivationSpeedZones zones;
    zones.resourceBonus = 0.333;
    zones.socialBonus = 0.111;
    zones.statusBonus = 0.222;
    zones.temporaryBonus = 0.444;
    zones.starBonus = 0.15;
    const double v = gamecore::disciple::calculateCultivationPerPhase(8, 1, zones);
    const double expected = gamecore::disciple::realmSpeedPerPhase(8) / 1.0
        * (1.0 + 0.333) * (1.0 + 0.111) * (1.0 + 0.222) * (1.0 + 0.444) * (1.0 + 0.15);
    EXPECT_DOUBLE_EQ(expected, v);
}

}  // namespace
}  // namespace gamecore

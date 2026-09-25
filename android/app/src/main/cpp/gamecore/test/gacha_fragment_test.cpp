// ============================================================
// gacha_fragment_test — 角色碎片入账守护
//
// 守护目标：gacha_fragment.h 的入账/升星语义与 Kotlin 回退臂逐位同式——
//   - 门槛进位（恰好满 / 跨多星 / 满星后继续累加）
//   - 拒绝臂零改动（空 templateId、非正数量、参数缺失或类型不符）
//   - 可加性（连续两次入账 == 一次合并入账）
//   - RNG 零消费审计（rngStates 快照差分——对拍命门）
// 常量与 Kotlin / 配置表的三向一致由 Kotlin 侧守卫测试看护。
// ============================================================

#include <gtest/gtest.h>

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/state/models.h"
#include "gamecore/system/gacha_fragment.h"

namespace gamecore {
namespace {

namespace gacha_fragment = gamecore::system::gacha_fragment;

using gamecore::state::GameData;
using gamecore::state::GameState;

constexpr const char* kTemplate = "zhouming";

/// 账本预置（星级 / 星级内进度）
GameData seededLedger(int32_t star, int32_t fragments) {
    GameData data;
    data.gachaStarMap[kTemplate] = star;
    data.gachaFragmentCounts[kTemplate] = fragments;
    return data;
}

/// 账本读出（断言用的 (星级, 星级内进度) 二元组；缺键按 0 读）
struct Ledger {
    int32_t star = 0;
    int32_t fragments = 0;
};

Ledger readLedger(const GameData& data) {
    Ledger out;
    const auto starIt = data.gachaStarMap.find(kTemplate);
    if (starIt != data.gachaStarMap.end()) out.star = starIt->second;
    const auto fragIt = data.gachaFragmentCounts.find(kTemplate);
    if (fragIt != data.gachaFragmentCounts.end()) out.fragments = fragIt->second;
    return out;
}

// ── 入账 + 升星 ─────────────────────────────────────────────────────

TEST(GachaFragmentTest, AddFragment_未达门槛_只累加不升星) {
    GameData data;

    const auto r = gacha_fragment::addFragment(data, kTemplate, 30);

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.starBefore, 0);
    EXPECT_EQ(r.starAfter, 0);
    EXPECT_EQ(r.fragmentsAfter, 30);
    const auto ledger = readLedger(data);
    EXPECT_EQ(ledger.star, 0);
    EXPECT_EQ(ledger.fragments, 30);
}

TEST(GachaFragmentTest, AddFragment_恰好满门槛_升一星且进度归零) {
    GameData data;

    const auto r = gacha_fragment::addFragment(data, kTemplate,
                                               gacha_fragment::kFragmentsPerStar);

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.starAfter, 1);
    EXPECT_EQ(r.fragmentsAfter, 0);
    const auto ledger = readLedger(data);
    EXPECT_EQ(ledger.star, 1);
    EXPECT_EQ(ledger.fragments, 0);
}

TEST(GachaFragmentTest, AddFragment_一次入账跨多星_按门槛连续进位) {
    GameData data = seededLedger(0, 0);

    const auto r = gacha_fragment::addFragment(data, kTemplate, 250);

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.starBefore, 0);
    EXPECT_EQ(r.starAfter, 2);
    EXPECT_EQ(r.fragmentsAfter, 50);
    const auto ledger = readLedger(data);
    EXPECT_EQ(ledger.star, 2);
    EXPECT_EQ(ledger.fragments, 50);
}

TEST(GachaFragmentTest, AddFragment_已满星_只累加不进位不降级) {
    GameData data = seededLedger(gacha_fragment::kMaxStar, 50);

    const auto r = gacha_fragment::addFragment(data, kTemplate, 100);

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.starBefore, gacha_fragment::kMaxStar);
    EXPECT_EQ(r.starAfter, gacha_fragment::kMaxStar);
    EXPECT_EQ(r.fragmentsAfter, 150);   // 不截断、不折算成其它货币
}

TEST(GachaFragmentTest, AddFragment_连续两次入账_与一次合并入账等价) {
    GameData stepwise;
    ASSERT_TRUE(gacha_fragment::addFragment(stepwise, kTemplate, 60).ok);
    const auto second = gacha_fragment::addFragment(stepwise, kTemplate, 60);
    ASSERT_TRUE(second.ok);

    GameData merged;
    const auto mergedResult = gacha_fragment::addFragment(merged, kTemplate, 120);

    ASSERT_TRUE(mergedResult.ok);
    EXPECT_EQ(second.starAfter, mergedResult.starAfter);
    EXPECT_EQ(second.fragmentsAfter, mergedResult.fragmentsAfter);
    EXPECT_EQ(readLedger(stepwise).star, readLedger(merged).star);
    EXPECT_EQ(readLedger(stepwise).fragments, readLedger(merged).fragments);
}

// ── 拒绝臂：账本零改动 ──────────────────────────────────────────────

TEST(GachaFragmentTest, AddFragment_空模板id_拒绝且账本零改动) {
    GameData data = seededLedger(2, 40);
    const auto fragmentsBefore = data.gachaFragmentCounts;
    const auto starsBefore = data.gachaStarMap;

    const auto r = gacha_fragment::addFragment(data, "", 100);

    EXPECT_FALSE(r.ok);
    EXPECT_EQ(r.errorType, "InvalidGrant");
    EXPECT_FALSE(r.message.empty());
    EXPECT_EQ(data.gachaFragmentCounts, fragmentsBefore);
    EXPECT_EQ(data.gachaStarMap, starsBefore);
}

TEST(GachaFragmentTest, AddFragment_非正数量_拒绝且不为被读键建空条目) {
    for (int32_t count : {0, -1, -100}) {
        GameData data;
        const auto r = gacha_fragment::addFragment(data, kTemplate, count);
        EXPECT_FALSE(r.ok) << "count=" << count;
        EXPECT_EQ(r.errorType, "InvalidGrant") << "count=" << count;
        EXPECT_TRUE(data.gachaFragmentCounts.empty()) << "count=" << count;
        EXPECT_TRUE(data.gachaStarMap.empty()) << "count=" << count;
    }
}

// ── 事务入口（params 盲取）──────────────────────────────────────────

TEST(GachaFragmentTest, GrantTx_参数齐备_入账并回星级) {
    GameState state;
    state.gameData = seededLedger(1, 80);
    const std::map<int32_t, int64_t> rngBefore = state.gameData.rngStates;

    const auto r = gacha_fragment::grantFragmentsTransaction(
        state, {{"templateId", kTemplate}, {"count", 130}});

    ASSERT_TRUE(r.ok);
    EXPECT_EQ(r.starBefore, 1);
    EXPECT_EQ(r.starAfter, 3);
    EXPECT_EQ(r.fragmentsAfter, 10);
    EXPECT_EQ(state.gameData.gachaStarMap[kTemplate], 3);
    EXPECT_EQ(state.gameData.gachaFragmentCounts[kTemplate], 10);
    // 零 RNG 审计：入账路径不消费随机数、不触碰 rngStates
    EXPECT_EQ(state.gameData.rngStates, rngBefore);
}

TEST(GachaFragmentTest, GrantTx_参数缺失或类型不符_走拒绝臂零改动) {
    const std::vector<nlohmann::json> cases = {
        nlohmann::json::object(),
        nlohmann::json{{"templateId", kTemplate}},
        nlohmann::json{{"count", 100}},
        nlohmann::json{{"templateId", 123}, {"count", 100}},          // 类型不符 → 空模板
        nlohmann::json{{"templateId", kTemplate}, {"count", "50"}},   // 类型不符 → 0
        nlohmann::json{{"templateId", kTemplate}, {"count", -3}},
    };
    for (const auto& params : cases) {
        GameState state;
        state.gameData = seededLedger(2, 40);
        const auto fragmentsBefore = state.gameData.gachaFragmentCounts;
        const auto starsBefore = state.gameData.gachaStarMap;

        const auto r = gacha_fragment::grantFragmentsTransaction(state, params);

        EXPECT_FALSE(r.ok) << "params=" << params.dump();
        EXPECT_EQ(r.errorType, "InvalidGrant") << "params=" << params.dump();
        EXPECT_EQ(state.gameData.gachaFragmentCounts, fragmentsBefore);
        EXPECT_EQ(state.gameData.gachaStarMap, starsBefore);
    }
}

}  // namespace
}  // namespace gamecore

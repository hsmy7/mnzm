// 物品 id 计数器注册表 + 导入即 reseed 守卫
//
// 锁定的不变量（R3：计数器生命周期与存档对齐——重启撞号在构造上不可能）：
//   1. 任何存档导入后，各 prefix 计数器 ≥ 存档已见最大后缀——新生成 id 与
//      存档既有 id 无交；
//   2. 重复导入幂等（计数器只推高不回退）；
//   3. observeItemIdForReseed 只消费 "gc-<prefix>-<纯数字>" 形态——Kotlin
//      UUID / 非 gc- 前缀（不相交的 id 空间）零影响；
//   4. 深前缀（gc-ai-d / gc-sr-team 等）按「最后一个 '-'」解析正确。

#include <gtest/gtest.h>

#include <nlohmann/json.hpp>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/ai_sect_recruit.h"
#include "gamecore/system/inventory.h"
#include "gamecore/system/merchant_settlement.h"
#include "gamecore/system/recruit_settlement.h"
#include "gamecore/system/secret_realm_settlement.h"
#include "gamecore/system/year_settlement.h"

namespace gamecore::system {
namespace {

/// 解析 "gc-<prefix>-<纯数字>" 的数字后缀；非此形态返回 nullopt
std::optional<uint64_t> numericSuffix(const std::string& id) {
    const auto pos = id.rfind('-');
    if (pos == std::string::npos || pos + 1 >= id.size()) return std::nullopt;
    const std::string s = id.substr(pos + 1);
    for (char c : s) {
        if (c < '0' || c > '9') return std::nullopt;
    }
    return std::stoull(s);
}

class ItemIdReseedGuardTest : public ::testing::Test {
protected:
    void SetUp() override {
        itemIdCounterRegistry().clear();  // = 新进程（注册表生命周期=进程）
        clock_.setNowMs(1'700'000'000'000L);
    }

    /// 初始化一个已初始化的 GameCore（带导出/导入能力）
    void initCore(GameCore& core) {
        GameCoreConfig config;
        config.systemSeed = 42;
        config.seedInitialized = true;
        ASSERT_TRUE(core.initialize(config));
    }

    FixedClock clock_;
    NullLogger logger_;
};

// ── 不变量 1：导入即 reseed——新生成 id 越过存档已见最大后缀 ──────────

TEST_F(ItemIdReseedGuardTest, ImportReseedsCounterAboveSeenMax) {
    GameCore core(&clock_, &logger_);
    initCore(core);

    // 基线存档 + 注入多前缀高序号 id（模拟旧档既有物品）
    auto j = nlohmann::json::parse(core.exportStateJson());
    j["pills"] = nlohmann::json::array({nlohmann::json{{"id", "gc-pill-150"}}});
    j["materials"] = nlohmann::json::array({nlohmann::json{{"id", "gc-material-260"}}});
    j["equipmentInstances"] = nlohmann::json::array({nlohmann::json{{"id", "gc-eq-99"}}});

    ASSERT_TRUE(core.importStateJson(j.dump()));

    // 撞号守卫：重启读档后新分配的 id 后缀必须越过存档已见最大值
    EXPECT_EQ(numericSuffix(nextItemId("gc-pill")), 151u);
    EXPECT_EQ(numericSuffix(nextItemId("gc-material")), 261u);
    EXPECT_EQ(numericSuffix(nextItemId("gc-eq")), 100u);
}

// ── 不变量 1'：重启后新生成 id 与存档既有 id 集合无交（主场景） ──

TEST_F(ItemIdReseedGuardTest, GeneratedIdsAfterImportNeverCollide) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    auto j = nlohmann::json::parse(core.exportStateJson());
    j["seeds"] = nlohmann::json::array({nlohmann::json{{"id", "gc-seed-40"}}});
    ASSERT_TRUE(core.importStateJson(j.dump()));

    // 存档既有 id 集合
    std::set<std::string> existing = {"gc-seed-40"};
    // 新进程连续生成 200 个 id，全部不得与既有集合相撞
    for (int i = 0; i < 200; ++i) {
        const std::string id = nextItemId("gc-seed");
        EXPECT_EQ(existing.count(id), 0u) << "撞号: " << id;
        existing.insert(id);
    }
}

// ── 不变量 2：重复导入幂等（计数器只推高不回退） ─────────────────────

TEST_F(ItemIdReseedGuardTest, ReimportIsIdempotent) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    auto j = nlohmann::json::parse(core.exportStateJson());
    j["herbs"] = nlohmann::json::array({nlohmann::json{{"id", "gc-herb-77"}}});

    ASSERT_TRUE(core.importStateJson(j.dump()));
    const auto afterFirst = numericSuffix(nextItemId("gc-herb"));  // 78
    ASSERT_TRUE(afterFirst.has_value());

    // 同一存档再次导入（AUTHORITATIVE 每旬回导即此模式）——不得回退
    ASSERT_TRUE(core.importStateJson(j.dump()));
    const auto afterSecond = numericSuffix(nextItemId("gc-herb"));
    EXPECT_EQ(afterSecond, afterFirst.value() + 1u);
}

// ── 不变量 3：UUID / 非 gc- 前缀与计数器空间不相交 ───────────────────

TEST_F(ItemIdReseedGuardTest, NonCounterIdFormatsAreIgnored) {
    observeItemIdForReseed("550e8400-e29b-41d4-a716-446655440000");  // Kotlin UUID
    observeItemIdForReseed("abc-5");                                  // 非 gc- 前缀
    observeItemIdForReseed("gc-herb-12ab");                           // 非纯数字后缀
    observeItemIdForReseed("gc-herb-");                               // 空后缀
    observeItemIdForReseed("gc-weird");                               // 无后缀分隔
    EXPECT_TRUE(itemIdCounterRegistry().empty());
}

// ── 不变量 4：深前缀解析（最后一个 '-' 分隔） ────────────────────────

TEST_F(ItemIdReseedGuardTest, DeepPrefixesParseCorrectly) {
    observeItemIdForReseed("gc-ai-d-41");     // AI 弟子（nextAiDiscipleId）
    observeItemIdForReseed("gc-sr-team-7");   // 秘境队伍（nextTeamId）
    observeItemIdForReseed("gc-equip-stack-90");  // 装备堆叠
    EXPECT_EQ(itemIdCounterRegistry()["gc-ai-d"], 41u);
    EXPECT_EQ(itemIdCounterRegistry()["gc-sr-team"], 7u);
    EXPECT_EQ(itemIdCounterRegistry()["gc-equip-stack"], 90u);
    // 只推高不回退
    observeItemIdForReseed("gc-ai-d-40");
    EXPECT_EQ(itemIdCounterRegistry()["gc-ai-d"], 41u);
}

// ── 生产生成器与注册表接线（全部 static 计数器已收敛的守卫） ─────────

TEST_F(ItemIdReseedGuardTest, AllGeneratorsRouteThroughRegistry) {
    // 生产 prefix 清单（与 C++ 生成器一一对应；新增生成器必须在此登记——
    // 生成器走 nextItemIdCounter 注册表后，reseed 自动覆盖）
    // gc-stack 经 StackableItemStore<Pill> 静态入口生成（类内 static）
    const std::vector<std::pair<std::string, std::string>> generators = {
        {"gc-stack", StackableItemStore<state::Pill>::generateNewId()},
        {"gc-merch", merchant_settle::nextItemId()},
        {"gc-inst", recruit_settle::nextInstanceId()},
        {"gc-ai-d", detail::nextAiDiscipleId()},
        {"gc-sr-team", secret_realm_settle::nextTeamId()},
        {"gc-trade", detail::nextTradeItemId()},
        {"gc-pill", nextItemId("gc-pill")},
    };
    for (const auto& [prefix, id] : generators) {
        EXPECT_EQ(id.rfind(prefix + "-", 0), 0u) << "前缀不符: " << id;
        // 生成的 id 立即可被 reseed 观察点消费（生成侧=扫描侧的闭环）
        observeItemIdForReseed(id);
        EXPECT_GE(itemIdCounterRegistry()[prefix], numericSuffix(id)) << prefix;
    }
}

}  // namespace
}  // namespace gamecore::system

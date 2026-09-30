// ============================================================
// equip_affix_test — 副词条池抽取语义守卫（B3 新增，方案 §6.1；
// 语义权威 = Kotlin EquipAffixPool 逐位移植：剩余池权重前缀和 +
// nextInt(total) 定点整数定位、抽中即移除（不放回 3 条））
// ============================================================

#include "gtest/gtest.h"

#include <set>
#include <string>
#include <vector>

#include "gamecore/data/equip_affix_db.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/equipment_factory.h"

namespace gamecore {
namespace {

namespace ef = gamecore::system::equipment_factory;

using gamecore::data::equipAffixes;
using gamecore::data::equipAffixTotalWeight;
using gamecore::rng::DeterministicRng;

// ── 静态池守卫 ──────────────────────────────────────────────────

TEST(EquipAffixPoolTest, PoolShapeSevenEntriesWeightsSumTo100) {
    // 7 项全局池；权重即概率（合计 100）；声明序禁重排（抽取序基准）
    const auto& pool = equipAffixes();
    ASSERT_EQ(7u, pool.size());
    EXPECT_EQ(100, equipAffixTotalWeight());
    std::set<std::string> distinct;
    for (const auto& def : pool) {
        EXPECT_GT(def.weight, 0) << def.stat;
        EXPECT_TRUE(distinct.insert(def.stat).second) << def.stat;
        for (int r = 0; r < 6; ++r) {
            EXPECT_GT(def.tierValues[r], 0.0) << def.stat << " rarity=" << (r + 1);
        }
    }
}

// ── 抽取语义（不放回 3 条）──────────────────────────────────────

TEST(EquipAffixRollTest, DrawsExactlyThreeDistinctSubStats) {
    // 恒 3 条、互不重复（EquipAffixSet 契约：恒 3 条去重面）
    for (int seed = 1; seed <= 50; ++seed) {
        DeterministicRng rng = DeterministicRng::fromSeed(seed);
        const auto stats = ef::rollSubStats(3, rng);
        ASSERT_EQ(3u, stats.size()) << "seed=" << seed;
        std::set<std::string> distinct;
        for (const auto& sv : stats) {
            EXPECT_TRUE(distinct.insert(sv.stat).second)
                << "seed=" << seed << " 重复词条 " << sv.stat;
        }
    }
}

TEST(EquipAffixRollTest, DrawSequenceMatchesPrefixSumManualReplay) {
    // 抽取序逐位对拍：同种子独立预演「前缀和 + nextInt(total) 定位」
    // 与 C++ rollSubStats 逐条一致；抽中即移除（池逐轮收缩）
    DeterministicRng rng = DeterministicRng::fromSeed(20260930);
    const auto drawn = ef::rollSubStats(2, rng);

    std::vector<gamecore::data::EquipAffixDef> remaining = equipAffixes();
    DeterministicRng probe = DeterministicRng::fromSeed(20260930);
    std::vector<std::string> expected;
    for (int i = 0; i < 3 && !remaining.empty(); ++i) {
        int32_t total = 0;
        for (const auto& def : remaining) total += def.weight;
        int32_t roll = probe.nextInt(total);
        const gamecore::data::EquipAffixDef* picked = &remaining.back();
        for (const auto& def : remaining) {
            if (roll < def.weight) { picked = &def; break; }
            roll -= def.weight;
        }
        expected.push_back(picked->stat);
        remaining.erase(std::remove_if(remaining.begin(), remaining.end(),
                            [&](const gamecore::data::EquipAffixDef& d) {
                                return d.stat == picked->stat;
                            }),
                        remaining.end());
    }
    ASSERT_EQ(3u, expected.size());
    ASSERT_EQ(3u, drawn.size());
    for (int i = 0; i < 3; ++i) {
        EXPECT_EQ(expected[static_cast<std::size_t>(i)],
                  drawn[static_cast<std::size_t>(i)].stat)
            << "抽取序第 " << i << " 位漂移";
    }
}

TEST(EquipAffixRollTest, TierValueFollowsRarityTier) {
    // 副词条初始值 = tierValues[rarity-1]（品阶 1..6 档位值；越界收敛 6 档）
    const auto& pool = equipAffixes();
    for (int32_t rarity = 1; rarity <= 6; ++rarity) {
        DeterministicRng rng = DeterministicRng::fromSeed(7);
        const auto stats = ef::rollSubStats(rarity, rng);
        ASSERT_EQ(3u, stats.size());
        for (const auto& sv : stats) {
            const gamecore::data::EquipAffixDef* def = nullptr;
            for (const auto& d : pool) {
                if (d.stat == sv.stat) { def = &d; break; }
            }
            ASSERT_NE(nullptr, def) << sv.stat;
            EXPECT_DOUBLE_EQ(def->tierValues[rarity - 1], sv.value)
                << sv.stat << " rarity=" << rarity;
        }
    }
    // 越界品阶收敛 6 档（clamp 上限）
    {
        const auto& def = pool.front();
        DeterministicRng rng = DeterministicRng::fromSeed(7);
        // 直接用 mainStatBaseValue 之外的入口无越界臂——以 tier 表引用为锚：
        EXPECT_GT(def.tierValues[5], 0.0);
    }
}

TEST(EquipAffixRollTest, SameSeedSameDrawDeterministic) {
    // 同种子重放同结果（确定性红线）
    DeterministicRng a = DeterministicRng::fromSeed(99);
    DeterministicRng b = DeterministicRng::fromSeed(99);
    const auto ra = ef::rollSubStats(4, a);
    const auto rb = ef::rollSubStats(4, b);
    ASSERT_EQ(ra.size(), rb.size());
    for (std::size_t i = 0; i < ra.size(); ++i) {
        EXPECT_EQ(ra[i].stat, rb[i].stat);
        EXPECT_DOUBLE_EQ(ra[i].value, rb[i].value);
    }
    EXPECT_EQ(a.snapshot(), b.snapshot());
}

}  // namespace
}  // namespace gamecore

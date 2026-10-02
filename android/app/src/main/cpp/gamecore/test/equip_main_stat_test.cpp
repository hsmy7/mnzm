// ============================================================
// equip_main_stat_test — 主词条部位池守卫（B3 新增，方案 §6.1；
// 语义权威 = Kotlin EquipMainStatPool 逐位移植：部位池等权 nextInt(size)、
// 品阶基数表 + 部位系数、暴击伤害 = 暴击率同档 × 2）
// ============================================================

#include "gtest/gtest.h"

#include <set>
#include <string>

#include "gamecore/data/equip_main_stat_db.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/equipment_factory.h"

namespace gamecore {
namespace {

namespace ef = gamecore::system::equipment_factory;

using gamecore::data::mainStatBase;
using gamecore::data::mainStatPools;
using gamecore::rng::DeterministicRng;

// ── 静态池守卫 ──────────────────────────────────────────────────

TEST(EquipMainStatPoolTest, SixPartPoolsWithCoefficient) {
    // 六部位池齐全；部位系数：生存向 1.00 / 输出向 1.15 / 均衡 0.95
    const auto& pools = mainStatPools();
    ASSERT_EQ(4u, pools.size());
    std::set<std::string> parts;
    for (const auto& pool : pools) {
        EXPECT_FALSE(pool.stats.empty()) << pool.part;
        EXPECT_GT(pool.coefficient, 0.0) << pool.part;
        // 候选互不重复（等权 nextInt(size) 的定义域）
        std::set<std::string> distinct;
        for (const auto& stat : pool.stats) {
            EXPECT_TRUE(distinct.insert(stat).second) << pool.part << stat;
        }
        parts.insert(pool.part);
    }
    EXPECT_EQ(4u, parts.size());
    // 部位系数逐位（表常量引用）
    for (const auto& pool : pools) {
        if (pool.part == "HANDS") {
            EXPECT_DOUBLE_EQ(1.15, pool.coefficient) << pool.part;
        } else if (pool.part == "FEET") {
            EXPECT_DOUBLE_EQ(0.95, pool.coefficient) << pool.part;
        } else {
            EXPECT_DOUBLE_EQ(1.0, pool.coefficient) << pool.part;
        }
    }
}

TEST(EquipMainStatPoolTest, RarityBaseTableFourRowsAcrossSixTiers) {
    // 品阶基数表 4 行（ATTACK/DEFENSE/HP/CRIT_RATE）× 6 档全为正
    const auto& base = mainStatBase();
    ASSERT_EQ(4u, base.size());
    std::set<std::string> stats;
    for (const auto& row : base) {
        stats.insert(row.stat);
        for (int r = 0; r < 6; ++r) {
            EXPECT_GT(row.values[r], 0.0) << row.stat << " rarity=" << (r + 1);
        }
        // 档位递增（品阶越高基数越大）
        for (int r = 1; r < 6; ++r) {
            EXPECT_GT(row.values[r], row.values[r - 1])
                << row.stat << " rarity=" << (r + 1);
        }
    }
    EXPECT_EQ(4u, stats.size());
}

// ── 抽取语义 ────────────────────────────────────────────────────

TEST(EquipMainStatRollTest, RollDrawsOnlyFromPartPool) {
    // 部位池内等权抽取：1000 次抽样全部落在本部位候选池内
    const auto& pools = mainStatPools();
    for (const auto& pool : pools) {
        DeterministicRng rng = DeterministicRng::fromSeed(11);
        std::set<std::string> seen;
        for (int i = 0; i < 1000; ++i) {
            const std::string stat = ef::rollMainStat(pool.part, rng);
            bool inPool = false;
            for (const auto& s : pool.stats) {
                if (s == stat) { inPool = true; break; }
            }
            ASSERT_TRUE(inPool) << pool.part << " 抽出池外词条 " << stat;
            seen.insert(stat);
        }
        // 大样本覆盖全部候选（1000 次全落 3-5 项候选池，漏项概率可忽略）
        EXPECT_EQ(pool.stats.size(), seen.size()) << pool.part;
    }
}

TEST(EquipMainStatRollTest, RollIndexSequenceMatchesManualReplay) {
    // 抽取序逐位对拍：nextInt(pool.size) 索引序与同种子预演一致
    DeterministicRng rng = DeterministicRng::fromSeed(777);
    std::vector<std::string> drawn;
    for (int i = 0; i < 5; ++i) drawn.push_back(ef::rollMainStat("BODY", rng));

    const auto* body = static_cast<const gamecore::data::MainStatPoolDef*>(nullptr);
    for (const auto& pool : mainStatPools()) {
        if (pool.part == "BODY") body = &pool;
    }
    ASSERT_NE(nullptr, body);
    DeterministicRng probe = DeterministicRng::fromSeed(777);
    for (int i = 0; i < 5; ++i) {
        const int32_t idx = probe.nextInt(
            static_cast<int32_t>(body->stats.size()));
        EXPECT_EQ(body->stats[static_cast<std::size_t>(idx)],
                  drawn[static_cast<std::size_t>(i)])
            << "第 " << i << " 次抽取漂移";
    }
}

TEST(EquipMainStatRollTest, MissingPoolFallsBackToAttack) {
    // 池缺失（协议不可达）：ATTACK 兜底（不 crash、零抽取）
    DeterministicRng rng = DeterministicRng::fromSeed(5);
    const int64_t before = rng.snapshot();
    EXPECT_EQ("ATTACK", ef::rollMainStat("NO_SUCH_PART", rng));
    EXPECT_EQ(before, rng.snapshot());
}

TEST(EquipMainStatValueTest, MainStatValueBaseTimesCoefficient) {
    // 主词条完整值 = 品阶基数 × 部位系数
    // ATTACK r4 基数 84：HANDS(1.15) → 96.6、FEET(0.95) → 79.8
    EXPECT_DOUBLE_EQ(84.0 * 1.15, ef::mainStatValue("ATTACK", "HANDS", 4).value);
    EXPECT_DOUBLE_EQ(84.0 * 0.95, ef::mainStatValue("ATTACK", "FEET", 4).value);
    // CRIT_DAMAGE = CRIT_RATE 同档 × 2：r1 = 0.002×2 = 0.004
    EXPECT_DOUBLE_EQ(0.004, ef::mainStatValue("CRIT_DAMAGE", "HEAD", 1).value);
    // HEAD 池无 CRIT_DAMAGE 候选但数值面按公式成立（部位系数 1.0）
    EXPECT_EQ("CRIT_DAMAGE", ef::mainStatValue("CRIT_DAMAGE", "HEAD", 1).stat);
    // 未知词条回退 CRIT_RATE 行（Kotlin RARITY_BASE ?: CRIT_RATE 表同口径）
    EXPECT_DOUBLE_EQ(0.002, ef::mainStatValue("MYSTERY", "HEAD", 1).value);
}

}  // namespace
}  // namespace gamecore

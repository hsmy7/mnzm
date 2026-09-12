// AI 尸体新陈代谢守卫
//
// 锁定的不变量（R2/R4：容器生命周期与语义生命周期对齐——条目数上界有界）：
//   1. 死亡超 AI_CORPSE_RETENTION_YEARS（3）年的条目被清出；活弟子全保留；
//   2. 保留窗口边界（恰好 3 年 = 清出；2 年 = 保留）；
//   3. truncateToAiLimit 活者优先——尸体不再挤占宗门池名额；
//   4. 导入侧幂等：旧档缺 deathYear 补「导入年」后再压缩（多次导入一致）。

#include <gtest/gtest.h>

#include <set>
#include <string>
#include <vector>

#include "gamecore/state/models.h"
#include "gamecore/system/ai_sect_recruit.h"
#include "gamecore/system/year_settlement.h"

namespace gamecore::system {
namespace {

using state::Disciple;
using state::GameState;

/// 构造 AI 弟子（id/存活/死亡年/战力）
Disciple makeDisciple(const std::string& id, bool alive, int deathYear,
                      int64_t power) {
    Disciple d;
    d.id = id;
    d.name = id;
    d.isAlive = alive;
    d.deathYear = deathYear;
    if (!alive) d.status = "DEAD";
    d.basePhysicalAttack = static_cast<int32_t>(power);
    d.baseMagicAttack = 0;
    d.baseHp = 0;
    return d;
}

class AiCorpseBudgetTest : public ::testing::Test {
protected:
    GameState state_;

    void addSect(const std::string& sectId, std::vector<Disciple> list) {
        state_.aiSectDisciples[sectId] = std::move(list);
    }
};

// ── 不变量 1：死亡超 3 年清出，活弟子全保留（500 年档回缩） ───────────

TEST_F(AiCorpseBudgetTest, CullsOldCorpsesKeepsAlive) {
    std::vector<Disciple> list;
    for (int i = 0; i < 800; ++i) {
        list.push_back(makeDisciple("corpse-" + std::to_string(i),
                                    /*alive=*/false, /*deathYear=*/300 + i % 100, 50));
    }
    for (int i = 0; i < 200; ++i) {
        list.push_back(makeDisciple("alive-" + std::to_string(i),
                                    /*alive=*/true, /*deathYear=*/0, 90));
    }
    addSect("sect-a", std::move(list));

    detail::cullAICorpseEntries(state_, /*gameYear=*/500);

    const auto& out = state_.aiSectDisciples["sect-a"];
    size_t aliveCount = 0;
    size_t corpseCount = 0;
    for (const auto& d : out) {
        if (d.isAlive) {
            aliveCount++;
        } else {
            corpseCount++;
            // 窗口内（死亡 < 3 年）尸体允许保留：死亡年 ≥ 498
            EXPECT_GE(d.deathYear, 498) << "窗口外尸体残留: " << d.id;
        }
    }
    EXPECT_EQ(aliveCount, 200u);   // 活弟子全保留
    EXPECT_LE(corpseCount, 6u);    // 死亡年 300..399 全部清出，仅边界样本可留
}

// ── 不变量 2：窗口边界（deathYear 0 补导入年不清出；2 年留；3 年清） ──

TEST_F(AiCorpseBudgetTest, RetentionWindowBoundary) {
    addSect("sect-b", {
        makeDisciple("just-died", false, 500, 10),  // 死亡 0 年 → 保留
        makeDisciple("died-2y", false, 498, 10),    // 2 年 → 保留
        makeDisciple("died-3y", false, 497, 10),    // 恰 3 年 → 清出
        makeDisciple("legacy-0y", false, 0, 10),    // 旧档缺省 → 保留（补导入年后计窗口）
    });

    detail::cullAICorpseEntries(state_, /*gameYear=*/500);

    const auto& out = state_.aiSectDisciples["sect-b"];
    std::set<std::string> ids;
    for (const auto& d : out) ids.insert(d.id);
    EXPECT_TRUE(ids.count("just-died"));
    EXPECT_TRUE(ids.count("died-2y"));
    EXPECT_FALSE(ids.count("died-3y"));
    EXPECT_TRUE(ids.count("legacy-0y"));  // normalize 之前 deathYear=0 不参与压缩
}

// ── 不变量 3：truncateToAiLimit 活者优先（尸体不挤占名额） ────────────

TEST_F(AiCorpseBudgetTest, TruncatePrefersAliveOverStrongerCorpses) {
    std::vector<Disciple> list;
    // 600 名高战力尸体 + 600 名低战力活弟子（总数 > kAiDisciplesPerSectLimit）
    for (int i = 0; i < 600; ++i) {
        list.push_back(makeDisciple("strong-corpse-" + std::to_string(i),
                                    false, 100, 9999));
    }
    for (int i = 0; i < 600; ++i) {
        list.push_back(makeDisciple("weak-alive-" + std::to_string(i),
                                    true, 0, 1));
    }

    const auto out = detail::truncateToAiLimit(std::move(list));

    size_t aliveCount = 0;
    for (const auto& d : out) {
        if (d.isAlive) aliveCount++;
    }
    // 修复语义：活弟子全部在保留集合内（排序键 isAlive 优先）
    EXPECT_EQ(aliveCount, 600u);
}

// ── 不变量 4：导入侧归一幂等（缺 deathYear 补导入年 + 压缩收敛） ──────

TEST_F(AiCorpseBudgetTest, ImportNormalizeIsIdempotent) {
    addSect("sect-c", {
        makeDisciple("legacy-old", false, 0, 10),    // 旧档缺省 → 补 500
        makeDisciple("recent", false, 499, 10),      // 窗口内 → 保留
        makeDisciple("survivor", true, 0, 10),
    });

    state_.gameData.gameYear = 500;
    detail::normalizeAICorpseEntries(state_);
    const size_t afterFirst = state_.aiSectDisciples["sect-c"].size();

    // 二次导入（幂等）：结果不变
    detail::normalizeAICorpseEntries(state_);
    const size_t afterSecond = state_.aiSectDisciples["sect-c"].size();
    EXPECT_EQ(afterFirst, afterSecond);

    // 窗口内条目在册；缺省条目已补导入年（保留窗口从导入年起算）
    std::set<std::string> ids;
    for (const auto& d : state_.aiSectDisciples["sect-c"]) {
        ids.insert(d.id);
        if (d.id == "legacy-old") EXPECT_EQ(d.deathYear, 500);
    }
    EXPECT_TRUE(ids.count("recent"));
    EXPECT_TRUE(ids.count("survivor"));

    // 推进 3 年后年结压缩 → 缺省条目清出（行为收敛正确）
    detail::cullAICorpseEntries(state_, 503);
    for (const auto& d : state_.aiSectDisciples["sect-c"]) {
        EXPECT_NE(d.id, "legacy-old");
    }
}

}  // namespace
}  // namespace gamecore::system

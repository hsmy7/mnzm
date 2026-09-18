#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/game_core.h"

// ============================================================
// column_export_equivalence_test — R2.4/B09 列级导出生产接线守卫
//
// 红线（batch-R2D.md）：列级导出仅序列化脏列必须与全量导出在
// **相同初态 + 相同写集**下语义等价（守卫测试对照），全量模式开关保留。
//
// 本守卫用**真实结算**驱动（非脚本化变更序列）：GameCore（AUTHORITATIVE
// core 模式 = 生产路径）带 300 名弟子跑 40 旬（跨月/年边界），每旬后
// 先列级导出（exportDirtyColumnJson——只消费 ColumnDirtyTracker）再全量
// 导出（exportDirtyJson——只消费 DirtyTracker），逐封对照：
//   1. gameData.* 与非弟子集合域：键集与值**逐位相等**（同一 diffTreeSegments
//      比对段，构造等价）；
//   2. removed：逐位相等；
//   3. disciples：全量封的变更 id ⊆ 列级封变更 id（无漏报——写屏障漏标即红），
//      且列级行的每个字段 == 全量行同名字段值（保守多报只许同值）。
// 事件流（R2.4）：proto 臂开启后 settleMonth/settleYear 产出 MONTH/YEAR_SETTLED
// 事件（field 4），exportDirty 导出即消费。
// ============================================================
namespace gamecore {
namespace {

using nlohmann::json;

class ColumnExportEquivalenceTest : public ::testing::Test {
protected:
    void SetUp() override { clock_.setNowMs(1'700'000'000'000L); }

    static state::Disciple makeDisciple(const std::string& id, int seed) {
        state::Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.surname = "试";
        d.realm = seed % 9;
        d.realmLayer = seed % 3 + 1;
        d.cultivation = 100.0 + seed;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.age = 16 + seed % 40;
        d.lifespan = 80 + seed % 40;
        d.gender = seed % 2 == 0 ? "male" : "female";
        d.discipleType = "outer";
        d.status = "IDLE";
        d.currentHp = 500 + seed;
        d.currentMp = 300 + seed;
        d.spiritStones = seed % 100;
        return d;
    }

    /// 逐封对照（语义等价断言，见文件头）
    void compareColumnVsFull(const json& col, const json& full) {
        ASSERT_TRUE(col.contains("changed") && col.contains("removed"));
        ASSERT_TRUE(full.contains("changed") && full.contains("removed"));
        const json& cc = col.at("changed");
        const json& fc = full.at("changed");

        // 1) gameData.* 与非弟子集合：键集相等 + 值逐位相等
        std::set<std::string> colKeys;
        for (auto it = cc.begin(); it != cc.end(); ++it) {
            if (it.key() == "disciples") continue;
            colKeys.insert(it.key());
        }
        std::set<std::string> fullKeys;
        for (auto it = fc.begin(); it != fc.end(); ++it) {
            if (it.key() == "disciples") continue;
            fullKeys.insert(it.key());
            EXPECT_TRUE(colKeys.count(it.key()) > 0)
                << "列级封缺 gameData/集合键 " << it.key();
        }
        for (const auto& key : colKeys) {
            EXPECT_TRUE(fullKeys.count(key) > 0)
                << "列级封多出 gameData/集合键 " << key;
        }
        for (const auto& key : colKeys) {
            ASSERT_TRUE(cc.at(key) == fc.at(key))
                << "键 " << key << " 两臂值不一致";
        }

        // 2) removed 逐位相等
        EXPECT_EQ(full.at("removed"), col.at("removed"));

        // 3) disciples：无漏报 + 多报同值
        std::set<std::string> colRowIds;
        if (cc.contains("disciples")) {
            for (const auto& row : cc.at("disciples")) {
                colRowIds.insert(row.at("id").get<std::string>());
            }
        }
        if (fc.contains("disciples")) {
            for (const auto& row : fc.at("disciples")) {
                const std::string id = row.at("id").get<std::string>();
                EXPECT_TRUE(colRowIds.count(id) > 0)
                    << "列级导出漏报变更弟子 " << id
                    << "（写屏障漏标 = 镜像静默丢变更）";
            }
        }
        if (cc.contains("disciples") && fc.contains("disciples")) {
            std::map<std::string, const json*> fullById;
            for (const auto& row : fc.at("disciples")) {
                fullById[row.at("id").get<std::string>()] = &row;
            }
            for (const auto& row : cc.at("disciples")) {
                const std::string id = row.at("id").get<std::string>();
                auto it = fullById.find(id);
                if (it == fullById.end()) continue;  // 保守多报行已由 id 守卫放行
                for (auto f = row.begin(); f != row.end(); ++f) {
                    ASSERT_TRUE(f.value() == it->second->at(f.key()))
                        << "弟子 " << id << " 字段 " << f.key()
                        << " 列级值与全量值不一致";
                }
            }
        }
    }

    FixedClock clock_;
    NullLogger logger_;
};

TEST_F(ColumnExportEquivalenceTest, RealSettlementsColumnMatchesFullExport) {
    GameCore core(&clock_, &logger_);
    GameCoreConfig config;
    config.seedInitialized = true;
    config.authoritativeTickMode = true;   // 生产 core 模式
    ASSERT_TRUE(core.initialize(config));

    // 种子态：300 名弟子（境界/修为/年龄/血蓝分层——修炼推进、恢复、
    // 突破候选、年结老化全路径有写入面）
    auto& st = core.state();
    for (int i = 0; i < 300; ++i) {
        state::Disciple d = makeDisciple(std::to_string(i + 1), i);
        // 一部分弟子修为顶格 → 突破候选命中（步骤 7 写面：境界/寿命/
        // checkpoint/statusData/guideCounters/gameEventRecords）
        if (i % 3 == 0) d.cultivation = 1.0e12;
        st.disciples.appendDisciple(d);
    }

    constexpr int kPhases = 40;
    int monthSettles = 0;
    int yearSettles = 0;
    for (int p = 0; p < kPhases; ++p) {
        const int flags = core.settleOnePhase();
        if ((flags & system::kSettleFlagMonthChanged) != 0) {
            static_cast<void>(core.settleMonth());
            ++monthSettles;
        }
        if ((flags & system::kSettleFlagYearChanged) != 0) {
            static_cast<void>(core.settleYear());
            ++yearSettles;
        }
        // 先列级后全量：两封分别消费各自追踪器（harness 同款双臂序）
        const json col = json::parse(core.exportDirtyColumnJson());
        const json full = json::parse(core.exportDirtyJson());
        compareColumnVsFull(col, full);
    }
    EXPECT_GT(monthSettles, 0) << "40 旬未跨月界（夹具失效）";
    EXPECT_GT(yearSettles, 0) << "40 旬未跨年界（夹具失效）";
}

}  // namespace
}  // namespace gamecore

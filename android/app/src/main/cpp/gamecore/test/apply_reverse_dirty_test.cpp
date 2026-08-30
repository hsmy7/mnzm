#include <gtest/gtest.h>

#include "gamecore/game_core.h"

namespace gamecore {
namespace {

// ============================================================
// applyReverseDirty 测试（计划 v2 阶段 3：反向增量通道）
//
// 覆盖：版本严格递增 / gameData 全量覆盖 + 基线同步 / 弟子 upsert+remove 保序 /
// 集合 upsert+remove / 未知集合宽松忽略 / 未初始化与非法 JSON 拒绝。
// ============================================================

class ApplyReverseDirtyTest : public ::testing::Test {
protected:
    void SetUp() override {
        clock_.setNowMs(1'700'000'000'000L);
    }

    void initCore(GameCore& core) {
        GameCoreConfig config;
        config.systemSeed = 42;
        config.seedInitialized = true;
        ASSERT_TRUE(core.initialize(config));
    }

    /// 基础导入：2 弟子 + 1 丹药 + gameData（含 rngStates 分区 1）
    void importBase(GameCore& core) {
        const std::string base = R"({
            "gameData": {
                "gameYear": 1, "gameMonth": 1, "gamePhase": 0,
                "spiritStones": 1000, "rngStates": {"1": 12345}
            },
            "disciples": [
                {"id": "1", "name": "甲", "realm": 9, "cultivation": 10.0},
                {"id": "2", "name": "乙", "realm": 8, "cultivation": 20.0}
            ],
            "pills": [{"id": "p1", "name": "丹", "quantity": 3}]
        })";
        ASSERT_TRUE(core.importStateJson(base));
    }

    FixedClock clock_;
    NullLogger logger_;
};

TEST_F(ApplyReverseDirtyTest, VersionMustIncrementStrictly) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    const std::string env1 = R"({"version":1,"changed":{},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(env1));
    // 重复应用拒绝
    EXPECT_FALSE(core.applyReverseDirty(env1));
    // 跳号拒绝
    const std::string env3 = R"({"version":3,"changed":{},"removed":{}})";
    EXPECT_FALSE(core.applyReverseDirty(env3));
    // 严格 +1 通过
    const std::string env2 = R"({"version":2,"changed":{},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(env2));
}

TEST_F(ApplyReverseDirtyTest, EmptyEnvelopeAccepted) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    EXPECT_TRUE(core.applyReverseDirty(R"({"version":1,"changed":{},"removed":{}})"));
}

TEST_F(ApplyReverseDirtyTest, GameDataReplacedAndBaselineSynced) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    // 先导出消费基线（模拟前一次 forward 同步）
    (void)core.exportDirtyJson();

    // 反向回导：spiritStones 900（rngStates 缺失 = Kotlin 侧剔除，不覆盖 native 真相）
    const std::string env = R"({"version":1,"changed":{"gameData":{"gameYear":1,"gameMonth":1,"gamePhase":0,"spiritStones":900}},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(env));
    EXPECT_EQ(900, core.state().gameData.spiritStones);

    // 基线已同步到应用后状态：反向应用值不应再出现在下一 forward 变更集
    const std::string dirty = core.exportDirtyJson();
    EXPECT_EQ(std::string::npos, dirty.find("spiritStones")) << dirty;
    // RNG 分区未被反向应用触碰（exportDirty 内 syncRngStates 回写的是 live 状态）
    EXPECT_NE(0LL, core.rngSnapshotPartition(1));
}

TEST_F(ApplyReverseDirtyTest, DiscipleUpsertInPlaceAndAppendAndRemove) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);

    // 弟子 1 原位覆盖（保序）+ 新增弟子 3（追加末尾）+ 删除弟子 2
    const std::string env = R"({
        "version": 1,
        "changed": {"disciples": [
            {"id": "1", "name": "甲改", "realm": 9, "cultivation": 99.0},
            {"id": "3", "name": "丁", "realm": 7, "cultivation": 1.0}
        ]},
        "removed": {"disciples": ["2"]}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env));
    ASSERT_EQ(2u, core.state().disciples.size());
    EXPECT_EQ("1", core.state().disciples.materialize(0).id);
    EXPECT_EQ("甲改", core.state().disciples.materialize(0).name);
    EXPECT_DOUBLE_EQ(99.0, core.state().disciples.materialize(0).cultivation);
    // 保序：既有弟子原位覆盖，新弟子追加末尾（RNG 对拍红线）
    EXPECT_EQ("3", core.state().disciples.materialize(1).id);
}

TEST_F(ApplyReverseDirtyTest, CollectionUpsertAndRemove) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);

    const std::string env = R"({
        "version": 1,
        "changed": {"pills": [{"id": "p1", "name": "丹改", "quantity": 5}]},
        "removed": {"pills": ["p2"]}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env));
    ASSERT_EQ(1u, core.state().pills.size());
    EXPECT_EQ("p1", core.state().pills[0].id);
    EXPECT_EQ("丹改", core.state().pills[0].name);
    EXPECT_EQ(5, core.state().pills[0].quantity);
}

TEST_F(ApplyReverseDirtyTest, UnknownCollectionIgnoredForForwardCompat) {
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    const std::string env = R"({"version":1,"changed":{"futureCollection":[{"id":"x"}]},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(env));
}

TEST_F(ApplyReverseDirtyTest, AiSectDisciplesSegmentAppliedAndOverwritten) {
    // S-15：aiSectDisciples 顶层段应用（GameState 顶层字段——Kotlin
    // GameData.aiSectDisciples @Transient 不入 gameData JSON，反向信封单独
    // 携带全量段）。语义 = 整体替换（非合并），与 Kotlin 侧缓存对齐。
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    EXPECT_TRUE(core.state().aiSectDisciples.empty());

    const std::string env = R"({
        "version": 1,
        "changed": {
            "gameData": {"gameYear": 2},
            "aiSectDisciples": {
                "ai-1": [{"id": "90", "name": "玄水弟子", "realm": 7, "isAlive": true}]
            }
        },
        "removed": {}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env));
    ASSERT_EQ(1u, core.state().aiSectDisciples.size());
    ASSERT_EQ(1u, core.state().aiSectDisciples.at("ai-1").size());
    EXPECT_EQ("90", core.state().aiSectDisciples.at("ai-1")[0].id);
    EXPECT_EQ("玄水弟子", core.state().aiSectDisciples.at("ai-1")[0].name);
    EXPECT_EQ(7, core.state().aiSectDisciples.at("ai-1")[0].realm);
    // gameData 覆盖不受影响（aiSectDisciples 已不在 GameData 内）
    EXPECT_EQ(2, core.state().gameData.gameYear);

    // 再次回导：整体替换语义（ai-1 消失，ai-2 新入）
    const std::string env2 = R"({
        "version": 2,
        "changed": {
            "aiSectDisciples": {
                "ai-2": [{"id": "91", "name": "赤火弟子", "realm": 8, "isAlive": true}]
            }
        },
        "removed": {}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env2));
    EXPECT_EQ(1u, core.state().aiSectDisciples.size());
    EXPECT_EQ(1u, core.state().aiSectDisciples.count("ai-2"));
    EXPECT_EQ(0u, core.state().aiSectDisciples.count("ai-1"));
}

TEST_F(ApplyReverseDirtyTest, RejectsBeforeInitAndMalformedJson) {
    GameCore core(&clock_, &logger_);
    EXPECT_FALSE(core.applyReverseDirty(R"({"version":1,"changed":{},"removed":{}})"));
    initCore(core);
    EXPECT_FALSE(core.applyReverseDirty("not json"));
}

}  // namespace
}  // namespace gamecore

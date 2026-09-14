#include <gtest/gtest.h>

#include "gamecore/game_core.h"

namespace gamecore {
namespace {

// ============================================================
// applyReverseDirty 测试（反向增量通道）
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

    /// 基础导入 JSON：2 弟子 + 1 丹药 + gameData（含 rngStates 分区 1）
    const std::string baseSnapshot() const {
        return R"({
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
    }

    /// 基础导入：2 弟子 + 1 丹药 + gameData（含 rngStates 分区 1）
    void importBase(GameCore& core) {
        ASSERT_TRUE(core.importStateJson(baseSnapshot()));
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

TEST_F(ApplyReverseDirtyTest, GameDataPartialPatchPreservesAbsentFields) {
    // gameData 段为字段级 dirty 集，C++ 按补丁应用——
    // 从当前 gameData 起步仅覆盖信封携带键（from_json 宽松读缺失键保持现值）。
    // 守护点：缺键字段必须保留现值（本用例 spiritStones/gameYear 不被清零）。
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);

    const std::string env =
        R"({"version":1,"changed":{"gameData":{"spiritStones":777}},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(env));
    EXPECT_EQ(777, core.state().gameData.spiritStones);
    // 缺失键保持导入值（补丁语义）
    EXPECT_EQ(1, core.state().gameData.gameYear);
    EXPECT_EQ(1, core.state().gameData.gameMonth);
    // rngStates 保持导入值（Kotlin 侧剔除，补丁不触碰 live 值）
    EXPECT_EQ(12345, core.state().gameData.rngStates.at(1));

    // 携带先前缺省的字段 → 正常写入，其余字段继续保留
    const std::string env2 =
        R"({"version":2,"changed":{"gameData":{"sectName":"青云宗"}},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(env2));
    EXPECT_EQ("青云宗", core.state().gameData.sectName);
    EXPECT_EQ(777, core.state().gameData.spiritStones);
    EXPECT_EQ(1, core.state().gameData.gameYear);
}

TEST_F(ApplyReverseDirtyTest, GameDataEmptyPatchObjectNoOp) {
    // 空补丁对象（锚点差分为空的窗口）：应用为无操作 + 基线同步，
    // 不把现有值当变更回流 forward 变更集
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    (void)core.exportDirtyJson();  // 消费基线

    EXPECT_TRUE(core.applyReverseDirty(
        R"({"version":1,"changed":{"gameData":{}},"removed":{}})"));
    EXPECT_EQ(1000, core.state().gameData.spiritStones);

    const std::string dirty = core.exportDirtyJson();
    EXPECT_EQ(std::string::npos, dirty.find("spiritStones")) << dirty;
}

TEST_F(ApplyReverseDirtyTest, AiSectDisciplesSegmentAppliedAndOverwritten) {
    // aiSectDisciples 顶层段应用（GameState 顶层字段——Kotlin
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

TEST_F(ApplyReverseDirtyTest, LockedBeastIdsSegmentAppliedAndOverwritten) {
    // 妖兽视图锁定顶层段（GameState.lockedBeastIds——Kotlin GameData
    // .lockedBeastIds @Transient 不入 gameData JSON，反向信封单独携带全量段）。
    // 语义 = 整体替换（非合并），与 Kotlin 侧变化检测缓存对齐；月结跳过
    // 判定（month_settlement）消费该字段——锁定/解锁须即时到达 C++，
    // 否则 AUTHORITATIVE 月结会忽略玩家弹窗锁定。
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    EXPECT_TRUE(core.state().lockedBeastIds.empty());

    const std::string env = R"({
        "version": 1,
        "changed": {
            "gameData": {"gameYear": 2},
            "lockedBeastIds": ["beast-1", "beast-2"]
        },
        "removed": {}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env));
    ASSERT_EQ(2u, core.state().lockedBeastIds.size());
    EXPECT_EQ("beast-1", core.state().lockedBeastIds[0]);
    EXPECT_EQ("beast-2", core.state().lockedBeastIds[1]);
    EXPECT_EQ(2, core.state().gameData.gameYear);

    // 再次回导：整体替换语义（unlock beast-1 → 只剩 beast-2；空数组 = 全解锁）
    const std::string env2 = R"({
        "version": 2,
        "changed": {"lockedBeastIds": ["beast-2"]},
        "removed": {}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env2));
    ASSERT_EQ(1u, core.state().lockedBeastIds.size());
    EXPECT_EQ("beast-2", core.state().lockedBeastIds[0]);

    const std::string env3 = R"({
        "version": 3,
        "changed": {"lockedBeastIds": []},
        "removed": {}
    })";
    EXPECT_TRUE(core.applyReverseDirty(env3));
    EXPECT_TRUE(core.state().lockedBeastIds.empty());
}

TEST_F(ApplyReverseDirtyTest, RejectsBeforeInitAndMalformedJson) {
    GameCore core(&clock_, &logger_);
    EXPECT_FALSE(core.applyReverseDirty(R"({"version":1,"changed":{},"removed":{}})"));
    initCore(core);
    EXPECT_FALSE(core.applyReverseDirty("not json"));
}

TEST_F(ApplyReverseDirtyTest, VersionNotAdvancedOnFailedApply) {
    // 守卫：应用失败（应用逻辑抛异常）时版本不得推进——否则与 Kotlin 侧
    // "发送成功才++"失去对称，之后所有增量回导永久 version mismatch，
    // 只剩全量兜底。
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    // 结构非法（disciples 实体 id 为数字非字符串 → 反序列化抛 type_error）
    const std::string bad = R"({
        "version": 1,
        "changed": {"disciples": [{"id": 123}]},
        "removed": {}
    })";
    EXPECT_FALSE(core.applyReverseDirty(bad));
    // 失败不推进版本：同版本（v1）的合法信封应成功
    const std::string good =
        R"({"version":1,"changed":{"gameData":{"spiritStones":900}},"removed":{}})";
    EXPECT_TRUE(core.applyReverseDirty(good));
    EXPECT_EQ(900, core.state().gameData.spiritStones);
}

TEST_F(ApplyReverseDirtyTest, ReverseVersionResetOnFullImport) {
    // 守卫：全量导入（读档/降级回导）必须重置反向版本——否则引擎侧
    // reverseVersion_ 与 Kotlin StateSyncService 的 reverseVersion 各自累积，
    // 跨会话/热重载后版本错位，增量回导永久被拒。
    GameCore core(&clock_, &logger_);
    initCore(core);
    importBase(core);
    // 建立版本 1
    EXPECT_TRUE(core.applyReverseDirty(R"({"version":1,"changed":{},"removed":{}})"));
    // 全量导入（模拟读档/降级回导）
    ASSERT_TRUE(core.importStateJson(baseSnapshot()));
    // 归零后 v1 可再次应用（若未重置则期望版本 2，v1 被 version mismatch 拒绝）
    EXPECT_TRUE(core.applyReverseDirty(R"({"version":1,"changed":{},"removed":{}})"));
}

}  // namespace
}  // namespace gamecore

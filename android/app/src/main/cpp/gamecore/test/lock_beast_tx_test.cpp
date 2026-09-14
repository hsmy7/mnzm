// ============================================================
// lock_beast_tx_test — 妖兽视图锁定 + 音频设置事务守护（batch-23）
//
// 守护目标：lock_beast_tx.h 两事务与 Kotlin 源语义逐位一致——
//   - lockBeastViewTx：锁定插入 / 重复锁定幂等 / 解锁剔除 /
//     解锁不存在的 id 幂等 / 空 id 无操作 / lockedCount 回执
//   - updateAudioSettingsTx：字段各自"同值不写、异值写入" / changed 并集 /
//     双字段独立（改一声不动另一）
//   - **零 RNG 全分区快照差分** + **双运行全状态逐位一致**
//   - 信封级：execute 通道 status/data 面 + 段内未注册动作 NOT_IMPLEMENTED
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/state/models.h"
#include "gamecore/system/lock_beast_tx.h"

namespace gamecore {
namespace {

namespace lock_beast_tx = gamecore::system::lock_beast_tx;

class LockBeastTxFixture : public ::testing::Test {
protected:
    void SetUp() override { core_ = makeCore(); }

    std::unique_ptr<GameCore> makeCore() {
        auto clock = std::make_unique<FixedClock>();
        auto logger = std::make_unique<ConsoleLogger>();
        auto core = std::make_unique<GameCore>(clock.get(), logger.get());
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core->initialize(config);
        clocks_.push_back(std::move(clock));
        loggers_.push_back(std::move(logger));
        return core;
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// 设置补丁信封参数（field/value 逐项构造——nlohmann 嵌套初始化列表
    /// 对 variant 值型推导不稳，显式构造）
    static nlohmann::json patchParams(
        const std::vector<std::pair<std::string, nlohmann::json>>& entries) {
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& [field, value] : entries) {
            nlohmann::json item = nlohmann::json::object();
            item["field"] = field;
            item["value"] = value;
            arr.push_back(std::move(item));
        }
        nlohmann::json params = nlohmann::json::object();
        params["patch"] = std::move(arr);
        return params;
    }

    const std::vector<std::string>& locked() const {
        return core_->state().lockedBeastIds;
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    /// Int 集字段值（Kotlin `Set<Int>` —— std::variant 构造需显式 vector）
    static lock_beast_tx::SettingValue ints(std::vector<int32_t> values) {
        return lock_beast_tx::SettingValue(std::move(values));
    }

    /// 布尔字段值
    static lock_beast_tx::SettingValue flag(bool value) {
        return lock_beast_tx::SettingValue(value);
    }

    /// 设置补丁构造（显式 variant 载体——花括号初始化无法向
    /// std::variant 推导 vector 分支）
    static lock_beast_tx::SettingPatch patch(
        std::initializer_list<std::pair<const std::string, lock_beast_tx::SettingValue>> entries) {
        lock_beast_tx::SettingPatch out;
        for (const auto& entry : entries) out.emplace(entry.first, entry.second);
        return out;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 妖兽视图锁定 / 解锁 ────────────────────────────────────────────────

TEST_F(LockBeastTxFixture, LockInsertsAndReportsCount) {
    const auto r = lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_EQ(r.lockedCount, 1);
    ASSERT_EQ(locked().size(), 1u);
    EXPECT_EQ(locked()[0], "beast_1");

    // 第二个 id 追加（保序）
    const auto r2 = lock_beast_tx::lockBeastViewTx(core_->state(), "beast_2", true);
    EXPECT_TRUE(r2.changed);
    EXPECT_EQ(r2.lockedCount, 2);
    ASSERT_EQ(locked().size(), 2u);
    EXPECT_EQ(locked()[1], "beast_2");
}

TEST_F(LockBeastTxFixture, LockDuplicateIsIdempotent) {
    lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
    const auto r = lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);  // Set 语义：重复插入无变化
    EXPECT_EQ(r.lockedCount, 1);
    EXPECT_EQ(locked().size(), 1u);
}

TEST_F(LockBeastTxFixture, UnlockRemovesAndReportsCount) {
    lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
    lock_beast_tx::lockBeastViewTx(core_->state(), "beast_2", true);

    const auto r = lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", false);
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_EQ(r.lockedCount, 1);
    ASSERT_EQ(locked().size(), 1u);
    EXPECT_EQ(locked()[0], "beast_2");  // 保序：仅剔除目标
}

TEST_F(LockBeastTxFixture, UnlockMissingIdIsIdempotent) {
    lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
    const std::string before = core_->exportStateJson();

    const auto r = lock_beast_tx::lockBeastViewTx(core_->state(), "beast_absent", false);
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(r.lockedCount, 1);
    EXPECT_EQ(core_->exportStateJson(), before);  // 零写入
}

TEST_F(LockBeastTxFixture, EmptyBeastIdIsNoopBothDirections) {
    const std::string before = core_->exportStateJson();
    const auto lock = lock_beast_tx::lockBeastViewTx(core_->state(), "", true);
    EXPECT_TRUE(lock.base.ok);
    EXPECT_FALSE(lock.changed);
    EXPECT_EQ(lock.lockedCount, 0);

    const auto unlock = lock_beast_tx::lockBeastViewTx(core_->state(), "", false);
    EXPECT_TRUE(unlock.base.ok);
    EXPECT_FALSE(unlock.changed);
    EXPECT_EQ(core_->exportStateJson(), before);  // 两方向均零状态变更
}

// ── 设置项字段补丁 ────────────────────────────────────────────────────

TEST_F(LockBeastTxFixture, SettingsPatchWritesBoolFields) {
    // 生产默认 true/true → 关掉音频两项 + 打开两个开关
    const auto r = lock_beast_tx::updateSettingsTx(core_->state(),
                                                   patch({{"soundEnabled", flag(false)},
                                                          {"musicEnabled", flag(false)},
                                                          {"patrolBattleResultPopup", flag(true)},
                                                          {"showAllAvailableDisciples", flag(true)}}));
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_EQ(r.appliedFields, 4);
    EXPECT_FALSE(core_->state().gameData.soundEnabled);
    EXPECT_FALSE(core_->state().gameData.musicEnabled);
    EXPECT_TRUE(core_->state().gameData.patrolBattleResultPopup);
    EXPECT_TRUE(core_->state().gameData.showAllAvailableDisciples);
}

TEST_F(LockBeastTxFixture, SettingsPatchWritesAllAutoAssignFields) {
    // AutoAssignDelegate 全域（自动装备/学习/突破丹药 + 道侣 + 俘虏过滤）
    const auto r = lock_beast_tx::updateSettingsTx(core_->state(),
                                                   patch({{"breakthroughAutoPillFocused", flag(true)},
                                                          {"breakthroughAutoPillRootCounts", ints({1, 2})},
                                                          {"autoEquipFromWarehouseFocused", flag(true)},
                                                          {"autoEquipFromWarehouseRootCounts", ints({3})},
                                                          {"autoLearnFromWarehouseFocused", flag(true)},
                                                          {"autoLearnFromWarehouseRootCounts", ints({2})},
                                                          {"daoCompanionConsentRequired", flag(true)},
                                                          {"daoCompanionBannedRootCounts", ints({1})},
                                                          {"prisonerSpiritRootFilter", ints({2, 3})},
                                                          {"autoSellMidGradeForPurchase", flag(true)},
                                                          {"autoSellHighGradeForPurchase", flag(true)},
                                                          {"autoRecruitSpiritRootFilter", ints({1})},
                                                          {"autoRejectSpiritRootFilter", ints({4})}}));
    EXPECT_TRUE(r.base.ok);
    EXPECT_TRUE(r.changed);
    EXPECT_EQ(r.appliedFields, 13);
    const auto& gd = core_->state().gameData;
    EXPECT_TRUE(gd.breakthroughAutoPillFocused);
    EXPECT_EQ(gd.breakthroughAutoPillRootCounts, (std::vector<int32_t>{1, 2}));
    EXPECT_TRUE(gd.autoEquipFromWarehouseFocused);
    EXPECT_TRUE(gd.autoLearnFromWarehouseFocused);
    EXPECT_TRUE(gd.daoCompanionConsentRequired);
    EXPECT_EQ(gd.daoCompanionBannedRootCounts, (std::vector<int32_t>{1}));
    EXPECT_EQ(gd.prisonerSpiritRootFilter, (std::vector<int32_t>{2, 3}));
    EXPECT_TRUE(gd.autoSellMidGradeForPurchase);
    EXPECT_TRUE(gd.autoSellHighGradeForPurchase);
    EXPECT_EQ(gd.autoRecruitSpiritRootFilter, (std::vector<int32_t>{1}));
    EXPECT_EQ(gd.autoRejectSpiritRootFilter, (std::vector<int32_t>{4}));
}

TEST_F(LockBeastTxFixture, SettingsPatchUnknownFieldFailsWithZeroWrite) {
    const std::string before = core_->exportStateJson();
    // 未知字段与已知字段同批 → 整批失败（判定链先行，零写入）
    const auto r = lock_beast_tx::updateSettingsTx(core_->state(),
                                                   patch({{"soundEnabled", flag(false)},
                                                          {"noSuchSetting", flag(true)}}));
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "UnknownSettingField");
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(LockBeastTxFixture, SettingsPatchTypeMismatchFailsWithZeroWrite) {
    const std::string before = core_->exportStateJson();
    // 已知字段但值类型不符（bool 字段收到 Int 集）→ 失败零写入
    const auto r = lock_beast_tx::updateSettingsTx(core_->state(),
                                                   patch({{"soundEnabled", ints({1})}}));
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "UnknownSettingField");
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(LockBeastTxFixture, SettingsPatchSameValueIsNoop) {
    const std::string before = core_->exportStateJson();
    // 与默认值同值（sound/music 默认 true）
    const auto r = lock_beast_tx::updateSettingsTx(core_->state(),
                                                   patch({{"soundEnabled", flag(true)},
                                                          {"musicEnabled", flag(true)}}));
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(r.appliedFields, 2);
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(LockBeastTxFixture, SettingsPatchIntSetUsesSetSemantics) {
    // 顺序不同但集合相同 → 不写（Kotlin Set<Int> == 与元素序无关）
    const std::string before = core_->exportStateJson();
    const auto same = lock_beast_tx::updateSettingsTx(
        core_->state(), patch({{"prisonerSpiritRootFilter", ints({})}}));
    EXPECT_TRUE(same.base.ok);
    EXPECT_FALSE(same.changed);  // 空集 == 默认空集
    EXPECT_EQ(core_->exportStateJson(), before);

    // 写入 {2,3} 后以 {3,2} 重写 → 集合相同，无变化
    lock_beast_tx::updateSettingsTx(core_->state(),
                                    patch({{"prisonerSpiritRootFilter", ints({2, 3})}}));
    const auto r1 = lock_beast_tx::updateSettingsTx(
        core_->state(), patch({{"prisonerSpiritRootFilter", ints({3, 2})}}));
    EXPECT_FALSE(r1.changed);
    EXPECT_EQ(core_->state().gameData.prisonerSpiritRootFilter,
              (std::vector<int32_t>{2, 3}));  // 首次写入值保持（未被等价重写覆盖）

    // 重复元素去重（Kotlin Set 构造语义）
    const auto r2 = lock_beast_tx::updateSettingsTx(
        core_->state(), patch({{"prisonerSpiritRootFilter", ints({2, 2, 3})}}));
    EXPECT_FALSE(r2.changed);
}

TEST_F(LockBeastTxFixture, SettingsPatchEmptyIsNoop) {
    const std::string before = core_->exportStateJson();
    const auto r = lock_beast_tx::updateSettingsTx(core_->state(), {});
    EXPECT_TRUE(r.base.ok);
    EXPECT_FALSE(r.changed);
    EXPECT_EQ(r.appliedFields, 0);
    EXPECT_EQ(core_->exportStateJson(), before);
}

// ── 零 RNG 审计 ────────────────────────────────────────────────────────

TEST_F(LockBeastTxFixture, ZeroRngAllPartitionsUnchanged) {
    const auto before = rngSnapshot();
    lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
    lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", false);
    lock_beast_tx::updateSettingsTx(core_->state(),
                                    patch({{"soundEnabled", flag(false)},
                                           {"prisonerSpiritRootFilter", ints({1, 2})}}));
    const auto after = rngSnapshot();
    EXPECT_EQ(before, after);  // 全分区 rngStates 逐键逐位不变
}

TEST_F(LockBeastTxFixture, DoubleRunProducesBitIdenticalState) {
    const auto run = [this]() {
        lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", true);
        lock_beast_tx::lockBeastViewTx(core_->state(), "beast_2", true);
        lock_beast_tx::lockBeastViewTx(core_->state(), "beast_1", false);
        lock_beast_tx::updateSettingsTx(core_->state(),
                                        patch({{"soundEnabled", flag(false)},
                                               {"prisonerSpiritRootFilter", ints({1, 2})}}));
        return core_->exportStateJson();
    };

    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 信封级（Kotlin 回退臂契约）──────────────────────────────────────────

TEST_F(LockBeastTxFixture, DispatchEnvelopeSuccess) {
    auto env = exec(action::BEAST_VIEW_LOCK_TX,
                    {{"beastId", "beast_1"}, {"locked", true}});
    ASSERT_TRUE(env.contains("status")) << env.dump();
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_TRUE(env.at("data").at("changed").get<bool>());
    EXPECT_EQ(env.at("data").at("lockedCount").get<int32_t>(), 1);
    ASSERT_EQ(locked().size(), 1u);

    env = exec(action::BEAST_VIEW_LOCK_TX,
               {{"beastId", "beast_1"}, {"locked", false}});
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_TRUE(env.at("data").at("changed").get<bool>());
    EXPECT_EQ(env.at("data").at("lockedCount").get<int32_t>(), 0);
    EXPECT_TRUE(locked().empty());

    env = exec(action::SETTINGS_PATCH_TX,
               patchParams({{"soundEnabled", false},
                            {"musicEnabled", false},
                            {"prisonerSpiritRootFilter", {1, 2}}}));
    EXPECT_EQ(env.at("status").get<std::string>(), "success") << env.dump();
    EXPECT_TRUE(env.at("data").at("changed").get<bool>());
    EXPECT_EQ(env.at("data").at("appliedFields").get<int32_t>(), 3);
    EXPECT_FALSE(core_->state().gameData.soundEnabled);
    EXPECT_FALSE(core_->state().gameData.musicEnabled);
    EXPECT_EQ(core_->state().gameData.prisonerSpiritRootFilter,
              (std::vector<int32_t>{1, 2}));
}

TEST_F(LockBeastTxFixture, DispatchEnvelopeUnknownFieldFails) {
    const std::string before = core_->exportStateJson();
    const auto env = exec(action::SETTINGS_PATCH_TX,
                          patchParams({{"noSuchSetting", true}}));
    EXPECT_EQ(env.at("status").get<std::string>(), "failure") << env.dump();
    EXPECT_EQ(env.at("code").get<std::string>(), "UnknownSettingField") << env.dump();
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(LockBeastTxFixture, DispatchEnvelopeUnknownActionFails) {
    // 段内未注册动作（1735）→ NOT_IMPLEMENTED（前向兼容：Kotlin 侧不注册即不可达）
    const std::string result = core_->execute(1735, "{}", 1000);
    const auto env = nlohmann::json::parse(result);
    EXPECT_EQ(env.at("status").get<std::string>(), "failure") << env.dump();
    EXPECT_EQ(env.at("code").get<std::string>(), "NOT_IMPLEMENTED") << env.dump();
}

}  // namespace
}  // namespace gamecore

// ============================================================
// appointment_tx_test — 弟子管理事务守护（batch-15：长老任命/卸任族）
//
// 守护目标：appointment_tx.h 长老两事务与 Kotlin 源语义逐位一致——
//   - 长老单值槽任命/卸任（10 字段 + 6 类亲传列表清空 + 全槽清理数据段，
//     被顶替者捕获序 = Kotlin collectReplacedIds：旧长老在前列表成员在后）
//   - 任命/卸任全程零抽取（签名级 API 不接受 rng + 快照差分双证据）
//   - 信封级：成功携带 data；失败为 failure 信封（Kotlin 回退臂契约）
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
#include "gamecore/system/appointment_tx.h"

namespace gamecore {
namespace {

namespace appointment_tx = gamecore::system::appointment_tx;

using gamecore::state::DirectDiscipleSlot;
using gamecore::state::Disciple;
using gamecore::state::PatrolSlot;

class AppointmentTxFixture : public ::testing::Test {
protected:
    void SetUp() override { core_ = makeCore(); }

    /// 独立 GameCore（双运行逐位一致测试用；时钟/日志由夹具容器持有保命）
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

    /// 挂一个最小存活弟子，返回行号（列直访用）
    std::size_t addDisciple(const std::string& id, int32_t realm = 9) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = realm;
        d.realmLayer = 1;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.status = "IDLE";
        d.currentHp = 100;
        d.currentMp = 50;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    void killDisciple(const std::string& id) {
        core_->state().disciples.isAlive[*core_->state().disciples.rowOf(id)] = 0;
    }

    /// 亲传槽构造（discipleId 其余默认）
    static DirectDiscipleSlot directSlot(int32_t index, const std::string& id) {
        DirectDiscipleSlot slot;
        slot.index = index;
        slot.discipleId = id;
        slot.discipleName = "亲传" + id;
        return slot;
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    std::vector<std::unique_ptr<FixedClock>> clocks_;
    std::vector<std::unique_ptr<ConsoleLogger>> loggers_;
    std::unique_ptr<GameCore> core_;
};

// ── 长老单值槽任命（10 字段逐类覆盖） ─────────────────────────────────

TEST_F(AppointmentTxFixture, AppointEachElderTypeWritesField) {
    const std::string kTypes[10] = {
        "VICE_SECT_MASTER", "HERB_GARDEN", "ALCHEMY", "FORGE", "OUTER_ELDER",
        "PREACHING", "LAW_ENFORCEMENT", "INNER_ELDER", "RECRUITING",
        "CLOUD_PREACHING",
    };
    for (const std::string& type : kTypes) {
        SetUp();  // 每类独立状态
        addDisciple("1");
        const auto r = appointment_tx::elderAppointTx(core_->state(), type, "1");
        ASSERT_TRUE(r.base.ok) << type << ": " << r.base.message;
        // 字段写入（10 字段穷举分派）
        const auto& slots = core_->state().gameData.elderSlots;
        if (type == "VICE_SECT_MASTER") EXPECT_EQ(slots.viceSectMaster, "1");
        else if (type == "HERB_GARDEN") EXPECT_EQ(slots.herbGardenElder, "1");
        else if (type == "ALCHEMY") EXPECT_EQ(slots.alchemyElder, "1");
        else if (type == "FORGE") EXPECT_EQ(slots.forgeElder, "1");
        else if (type == "OUTER_ELDER") EXPECT_EQ(slots.outerElder, "1");
        else if (type == "PREACHING") EXPECT_EQ(slots.preachingElder, "1");
        else if (type == "LAW_ENFORCEMENT") EXPECT_EQ(slots.lawEnforcementElder, "1");
        else if (type == "INNER_ELDER") EXPECT_EQ(slots.innerElder, "1");
        else if (type == "RECRUITING") EXPECT_EQ(slots.recruitingElder, "1");
        else if (type == "CLOUD_PREACHING") EXPECT_EQ(slots.qingyunPreachingElder, "1");
    }
}

TEST_F(AppointmentTxFixture, AppointClearsDirectListForSixTypes) {
    // 六类任命清空对应亲传列表（SLOT_TYPES_CLEARING_DIRECT_DISCIPLES）；
    // spiritMineDeaconDisciples（第 7 列表）不在任命清空族——仅全槽清理触达
    const char* kClearing[] = {"HERB_GARDEN", "ALCHEMY", "FORGE",
                               "PREACHING", "LAW_ENFORCEMENT", "CLOUD_PREACHING"};
    for (const std::string& type : kClearing) {
        SetUp();
        addDisciple("1");
        auto& slots = core_->state().gameData.elderSlots;
        if (type == "HERB_GARDEN") slots.herbGardenDisciples = {directSlot(0, "7")};
        else if (type == "ALCHEMY") slots.alchemyDisciples = {directSlot(0, "7")};
        else if (type == "FORGE") slots.forgeDisciples = {directSlot(0, "7")};
        else if (type == "PREACHING") slots.preachingMasters = {directSlot(0, "7")};
        else if (type == "LAW_ENFORCEMENT") slots.lawEnforcementDisciples = {directSlot(0, "7")};
        else slots.qingyunPreachingMasters = {directSlot(0, "7")};
        slots.spiritMineDeaconDisciples = {directSlot(0, "7")};

        const auto r = appointment_tx::elderAppointTx(core_->state(), type, "1");
        ASSERT_TRUE(r.base.ok) << type;
        if (type == "HERB_GARDEN") EXPECT_TRUE(slots.herbGardenDisciples.empty());
        else if (type == "ALCHEMY") EXPECT_TRUE(slots.alchemyDisciples.empty());
        else if (type == "FORGE") EXPECT_TRUE(slots.forgeDisciples.empty());
        else if (type == "PREACHING") EXPECT_TRUE(slots.preachingMasters.empty());
        else if (type == "LAW_ENFORCEMENT") EXPECT_TRUE(slots.lawEnforcementDisciples.empty());
        else EXPECT_TRUE(slots.qingyunPreachingMasters.empty());
        EXPECT_EQ(slots.spiritMineDeaconDisciples.size(), 1u);
    }
    // 非清空类：亲传列表原样保留
    SetUp();
    addDisciple("1");
    auto& slots = core_->state().gameData.elderSlots;
    slots.herbGardenDisciples = {directSlot(0, "7")};
    const auto r = appointment_tx::elderAppointTx(core_->state(), "VICE_SECT_MASTER", "1");
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(slots.herbGardenDisciples.size(), 1u);
}

TEST_F(AppointmentTxFixture, AppointReturnsReplacedIdsInKotlinOrder) {
    // 被顶替者捕获序 = Kotlin collectReplacedIds：旧长老在前 + 列表成员在后
    addDisciple("1");
    addDisciple("3");
    addDisciple("4");
    auto& slots = core_->state().gameData.elderSlots;
    slots.herbGardenElder = "3";
    slots.herbGardenDisciples = {directSlot(0, "4"), directSlot(1, "")};

    const auto r = appointment_tx::elderAppointTx(core_->state(), "HERB_GARDEN", "1");
    ASSERT_TRUE(r.base.ok);
    ASSERT_EQ(r.replacedIds.size(), 2u);
    EXPECT_EQ(r.replacedIds[0], "3");
    EXPECT_EQ(r.replacedIds[1], "4");
    EXPECT_EQ(slots.herbGardenElder, "1");
    EXPECT_TRUE(slots.herbGardenDisciples.empty());
}

TEST_F(AppointmentTxFixture, AppointSelfNotInReplacedIds) {
    // 已任目标槽的弟子再任命同槽：旧长老 == appointee → 不入 replacedIds
    addDisciple("1");
    auto& slots = core_->state().gameData.elderSlots;
    slots.viceSectMaster = "1";
    const auto r = appointment_tx::elderAppointTx(core_->state(), "VICE_SECT_MASTER", "1");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.replacedIds.empty());
}

TEST_F(AppointmentTxFixture, AppointClearsAppointeeOtherSlots) {
    // 全槽清理数据段（appointee 从巡逻/仓库驻守等岗位拉入长老，旧槽位不残留）
    addDisciple("1");
    auto& gd = core_->state().gameData;
    PatrolSlot patrol;
    patrol.index = 0;
    patrol.discipleId = "1";
    gd.patrolSlots = {patrol};

    const auto r = appointment_tx::elderAppointTx(core_->state(), "OUTER_ELDER", "1");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(gd.patrolSlots[0].discipleId.empty());
    EXPECT_EQ(gd.elderSlots.outerElder, "1");
}

TEST_F(AppointmentTxFixture, AppointGuardsFailWithZeroWrite) {
    addDisciple("1");
    auto& slots = core_->state().gameData.elderSlots;
    slots.viceSectMaster = "9";

    // 弟子不存在
    auto r = appointment_tx::elderAppointTx(core_->state(), "VICE_SECT_MASTER", "404");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");
    // 弟子已死亡
    addDisciple("2");
    killDisciple("2");
    r = appointment_tx::elderAppointTx(core_->state(), "VICE_SECT_MASTER", "2");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotAlive");
    // 未知槽位类型（协议违规防御臂）
    r = appointment_tx::elderAppointTx(core_->state(), "NO_SUCH_SLOT", "1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "UnknownSlotType");
    // 全部失败臂零写入
    EXPECT_EQ(slots.viceSectMaster, "9");
}

// ── 长老单值槽卸任 ───────────────────────────────────────────────────

TEST_F(AppointmentTxFixture, DismissClearsFieldAndList) {
    addDisciple("1");
    auto& slots = core_->state().gameData.elderSlots;
    slots.preachingElder = "1";
    slots.preachingMasters = {directSlot(0, "5")};
    slots.viceSectMaster = "8";  // 非目标字段不受影响

    const auto r = appointment_tx::elderDismissTx(core_->state(), "PREACHING");
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedId, "1");
    EXPECT_TRUE(slots.preachingElder.empty());
    EXPECT_TRUE(slots.preachingMasters.empty());
    EXPECT_EQ(slots.viceSectMaster, "8");
}

TEST_F(AppointmentTxFixture, DismissEmptySlotReturnsEmptyId) {
    const auto r = appointment_tx::elderDismissTx(core_->state(), "FORGE");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.removedId.empty());
}

TEST_F(AppointmentTxFixture, DismissUnknownSlotTypeFails) {
    const auto r = appointment_tx::elderDismissTx(core_->state(), "NO_SUCH_SLOT");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "UnknownSlotType");
}

// ── 零 RNG 族审计 + 信封级双保险 ─────────────────────────────────────

TEST_F(AppointmentTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    // 任命/卸任两事务全程零抽取——签名级（API 不接受 rng）+
    // 快照差分双证据
    addDisciple("1");
    const auto baseline = rngSnapshot();

    ASSERT_TRUE(appointment_tx::elderAppointTx(core_->state(), "INNER_ELDER", "1").base.ok);
    ASSERT_TRUE(appointment_tx::elderDismissTx(core_->state(), "INNER_ELDER").base.ok);
    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(AppointmentTxFixture, DispatchEnvelopeHappyAndFailure) {
    // 信封级：成功携带 data；失败为 failure 信封（Kotlin 回退臂契约）
    addDisciple("1");

    const auto okR = exec(action::ELDER_APPOINT_TX,
                          {{"slotType", "RECRUITING"}, {"discipleId", "1"}});
    EXPECT_EQ(okR["status"], "success");
    EXPECT_EQ(okR["data"]["appointed"], true);

    const auto failR = exec(action::ELDER_APPOINT_TX,
                            {{"slotType", "RECRUITING"}, {"discipleId", "404"}});
    EXPECT_EQ(failR["status"], "failure");
    EXPECT_EQ(failR["code"], "NotFound");
}

}  // namespace
}  // namespace gamecore

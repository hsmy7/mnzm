// ============================================================
// appointment_tx_test — 弟子管理三事务守护（batch-15：长老任命/仓库驻守/
// 洗炼消耗族）
//
// 守护目标：appointment_tx.h 七事务与 Kotlin 源语义逐位一致——
//   - 长老单值槽任命/卸任（10 字段 + 6 类亲传列表清空 + 全槽清理数据段，
//     被顶替者捕获序 = Kotlin collectReplacedIds：旧长老在前列表成员在后）
//   - 仓库驻守（旧 occupant 捕获 + 条目替换 + 全槽清理；原样字符串语义）
//   - 洗炼三族（先扣后抽：失败臂零抽取零写入；SYSTEM 分区抽取序双运行
//     逐位一致 + 终态 rngStates 锁定；保底路径/普通路径分流；特质确认
//     零 RNG + lifespan 同步 + checkpoint 重记账）
//   - 玉符承扣（余额检查 + 扣减同事务原子；jadeAfter 回传——Kotlin 运行时
//     totalCount 同步残差锚点）
// ============================================================

#include "gtest/gtest.h"

#include <algorithm>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/appointment_tx.h"

namespace gamecore {
namespace {

namespace appointment_tx = gamecore::system::appointment_tx;

using gamecore::rng::DeterministicRng;
using gamecore::rng::RngPartition;
using gamecore::state::DirectDiscipleSlot;
using gamecore::state::Disciple;
using gamecore::state::PatrolSlot;
using gamecore::state::WarehouseGarrisonSlot;

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

    /// 独立参照 RNG（SYSTEM 分区种子 = systemSeed + 3——RngManager 播种口径）
    static DeterministicRng systemRngFromSeed() {
        return DeterministicRng::fromSeed(42 + 3);
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
        d.age = 20;
        d.lifespan = 80;
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
    WarehouseGarrisonSlot garrison;
    garrison.buildingInstanceId = "wh1";
    garrison.discipleId = "1";
    gd.warehouseGarrisons = {garrison};

    const auto r = appointment_tx::elderAppointTx(core_->state(), "OUTER_ELDER", "1");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(gd.patrolSlots[0].discipleId.empty());
    // 仓库驻守清理语义 = 清空 discipleId（条目保留——slot_cleanup 口径）
    ASSERT_EQ(gd.warehouseGarrisons.size(), 1u);
    EXPECT_TRUE(gd.warehouseGarrisons[0].discipleId.empty());
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

// ── 仓库驻守 ─────────────────────────────────────────────────────────

TEST_F(AppointmentTxFixture, WarehouseGarrisonHappyReplacesEntry) {
    addDisciple("1");
    addDisciple("2");
    auto& gd = core_->state().gameData;
    WarehouseGarrisonSlot old;
    old.buildingInstanceId = "wh1";
    old.discipleId = "2";
    old.discipleName = "旧驻守";
    WarehouseGarrisonSlot other;
    other.buildingInstanceId = "wh2";
    other.discipleId = "2";
    gd.warehouseGarrisons = {old, other};

    const auto r = appointment_tx::warehouseGarrisonAssignTx(
        core_->state(), "wh1", "1", "新驻守", "sectA");
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.oldOccupantId, "2");
    ASSERT_EQ(gd.warehouseGarrisons.size(), 2u);
    // wh1 条目被替换（无重复）；wh2 保留
    EXPECT_EQ(gd.warehouseGarrisons[0].buildingInstanceId, "wh2");
    EXPECT_EQ(gd.warehouseGarrisons[1].buildingInstanceId, "wh1");
    EXPECT_EQ(gd.warehouseGarrisons[1].discipleId, "1");
    EXPECT_EQ(gd.warehouseGarrisons[1].discipleName, "新驻守");
    EXPECT_EQ(gd.warehouseGarrisons[1].sectId, "sectA");
}

TEST_F(AppointmentTxFixture, WarehouseGarrisonClearsAppointeeOtherSlots) {
    addDisciple("1");
    auto& gd = core_->state().gameData;
    PatrolSlot patrol;
    patrol.index = 0;
    patrol.discipleId = "1";
    gd.patrolSlots = {patrol};

    const auto r = appointment_tx::warehouseGarrisonAssignTx(
        core_->state(), "wh1", "1", "n", "s");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(gd.patrolSlots[0].discipleId.empty());
    ASSERT_EQ(gd.warehouseGarrisons.size(), 1u);
    EXPECT_EQ(gd.warehouseGarrisons[0].discipleId, "1");
}

TEST_F(AppointmentTxFixture, WarehouseGarrisonGuardsFailWithZeroWrite) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    auto& gd = core_->state().gameData;
    WarehouseGarrisonSlot old;
    old.buildingInstanceId = "wh1";
    old.discipleId = "9";
    gd.warehouseGarrisons = {old};

    auto r = appointment_tx::warehouseGarrisonAssignTx(
        core_->state(), "wh1", "404", "n", "s");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");
    r = appointment_tx::warehouseGarrisonAssignTx(core_->state(), "wh1", "2", "n", "s");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotAlive");
    // 失败臂零写入
    EXPECT_EQ(gd.warehouseGarrisons.size(), 1u);
    EXPECT_EQ(gd.warehouseGarrisons[0].discipleId, "9");
}

// ── 洗炼灵根 ─────────────────────────────────────────────────────────

TEST_F(AppointmentTxFixture, SpiritRootWashDeductsJadeAndReturnsRoot) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;

    const auto r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", 0, 1);
    ASSERT_TRUE(r.base.ok) << r.base.message;
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 4);
    EXPECT_EQ(r.jadeAfter, 4);
    // 产物 = 1~2 个洗炼元素逗号串，全部在元素表内
    int32_t count = 1;
    for (const char c : r.newRootType) {
        if (c == ',') ++count;
    }
    ASSERT_TRUE(count == 1 || count == 2);
    const std::vector<std::string> keys = appointment_tx::washElementKeys();
    std::string cur;
    std::vector<std::string> parts;
    for (const char c : r.newRootType) {
        if (c == ',') { parts.push_back(cur); cur.clear(); }
        else cur.push_back(c);
    }
    parts.push_back(cur);
    for (const auto& part : parts) {
        EXPECT_NE(std::find(keys.begin(), keys.end(), part), keys.end()) << part;
    }
}

TEST_F(AppointmentTxFixture, SpiritRootWashPityPathSkipsQualityDraw) {
    // 保底路径：零 nextDouble 消耗——SYSTEM 终态 = 参照 RNG 仅洗牌 5×nextInt()
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    const auto r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", 2, 1);
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.newPityCount, 0);
    DeterministicRng reference = systemRngFromSeed();
    for (int i = 0; i < 5; ++i) reference.nextInt();
    EXPECT_EQ(core_->rng().getRng(RngPartition::kSystem).snapshot(), reference.snapshot());
}

TEST_F(AppointmentTxFixture, SpiritRootWashNormalPathConsumesSixDraws) {
    // 普通路径：1×nextDouble + 5×nextInt——终态锁定
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    const auto r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", 0, 1);
    ASSERT_TRUE(r.base.ok);
    DeterministicRng reference = systemRngFromSeed();
    (void)reference.nextDouble();
    for (int i = 0; i < 5; ++i) reference.nextInt();
    EXPECT_EQ(core_->rng().getRng(RngPartition::kSystem).snapshot(), reference.snapshot());
    // 保底计数随产物分派：单灵根归零 / 双灵根 +1
    const int32_t roots =
        static_cast<int32_t>(std::count(r.newRootType.begin(), r.newRootType.end(), ',')) + 1;
    EXPECT_EQ(r.newPityCount, roots == 1 ? 0 : 1);
}

TEST_F(AppointmentTxFixture, SpiritRootWashDoubleRunBitwise) {
    // 双运行逐位一致（同种子 → 同产物 + 同终态）
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    const auto first = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", 1, 1);
    const auto firstStates = rngSnapshot();
    SetUp();
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    const auto second = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", 1, 1);
    EXPECT_EQ(second.newRootType, first.newRootType);
    EXPECT_EQ(second.newPityCount, first.newPityCount);
    EXPECT_EQ(rngSnapshot(), firstStates);
}

TEST_F(AppointmentTxFixture, SpiritRootWashFailureArmsZeroDrawZeroWrite) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    core_->state().gameData.jadeSymbols = 0;
    const auto baseline = rngSnapshot();

    // 玉符不足
    auto r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", 0, 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INSUFFICIENT_JADE");
    // 弟子不存在 / 已死亡
    r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "404", 0, 1);
    EXPECT_FALSE(r.base.ok);
    r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "2", 0, 1);
    EXPECT_FALSE(r.base.ok);
    // 非法保底计数
    r = appointment_tx::spiritRootWashTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", -1, 1);
    EXPECT_FALSE(r.base.ok);
    // 全部失败臂：零抽取 + 零写入
    EXPECT_EQ(rngSnapshot(), baseline);
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 0);
}

// ── 新增特质（Roll/Confirm 两段） ────────────────────────────────────

TEST_F(AppointmentTxFixture, TraitAddRollPersistsPendingAndDeducts) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;

    const auto r = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT", 1);
    ASSERT_TRUE(r.base.ok) << r.base.message;
    EXPECT_FALSE(r.newId.empty());
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 4);
    EXPECT_EQ(r.jadeAfter, 4);
    // pending 落盘（canonical id + 类型名 + 产物）
    ASSERT_EQ(core_->state().gameData.pendingTraitAdds.size(), 1u);
    EXPECT_EQ(core_->state().gameData.pendingTraitAdds[0].discipleId, "1");
    EXPECT_EQ(core_->state().gameData.pendingTraitAdds[0].type, "TALENT");
    EXPECT_EQ(core_->state().gameData.pendingTraitAdds[0].traitId, r.newId);
    // 产物在正向候选池内（非负面 + 非退役类型）
    const auto& templates = gamecore::data::talentTemplates();
    const auto it = std::find_if(templates.begin(), templates.end(),
                                 [&](const gamecore::data::TalentTemplate& t) {
                                     return t.id == r.newId;
                                 });
    ASSERT_NE(it, templates.end());
    EXPECT_FALSE(it->isNegative);
    EXPECT_EQ(appointment_tx::deprecatedTalentTypes().count(it->type), 0u);
}

TEST_F(AppointmentTxFixture, TraitAddRollOverwritesSamePending) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    gamecore::state::PendingTraitAdd existing;
    existing.discipleId = "1";
    existing.type = "TALENT";
    existing.traitId = "t_old";
    core_->state().gameData.pendingTraitAdds = {existing};

    const auto r = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT", 1);
    ASSERT_TRUE(r.base.ok);
    ASSERT_EQ(core_->state().gameData.pendingTraitAdds.size(), 1u);
    EXPECT_EQ(core_->state().gameData.pendingTraitAdds[0].traitId, r.newId);
}

TEST_F(AppointmentTxFixture, TraitAddRollGuardsZeroDrawZeroDeduct) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    core_->state().gameData.jadeSymbols = 0;
    const auto baseline = rngSnapshot();

    // 上限已满（5 个天赋）
    core_->state().disciples.talentIds[*core_->state().disciples.rowOf("1")] = {
        "t1", "t2", "t3", "t4", "t5"};
    auto r = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT", 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "SLOTS_FULL");
    core_->state().disciples.talentIds[*core_->state().disciples.rowOf("1")].clear();

    // 玉符不足 / 弟子不存在 / 已死亡
    r = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT", 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INSUFFICIENT_JADE");
    r = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "404", "TALENT", 1);
    EXPECT_FALSE(r.base.ok);
    r = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "2", "TALENT", 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(rngSnapshot(), baseline);
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 0);
}

TEST_F(AppointmentTxFixture, TraitAddRollDoubleRunBitwise) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    const auto first = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "AFFIX", 1);
    const auto firstStates = rngSnapshot();
    SetUp();
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    const auto second = appointment_tx::traitAddRollTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "AFFIX", 1);
    EXPECT_EQ(second.newId, first.newId);
    EXPECT_EQ(rngSnapshot(), firstStates);
}

TEST_F(AppointmentTxFixture, TraitAddConfirmAppendsWithLifespanSync) {
    // 产物选 LIFESPAN 型天赋（退役类型仍可被 confirm 校验接受——confirm
    // 不滤退役）；lifespan 增量 = (int)(realmMaxAge × bonus)
    addDisciple("1", 9);
    const auto& templates = gamecore::data::talentTemplates();
    std::string lifespanTalentId;
    double lifespanBonus = 0.0;
    for (const auto& t : templates) {
        const auto it = t.effects.find("lifespan");
        if (it != t.effects.end() && it->second > 0.0) {
            lifespanTalentId = t.id;
            lifespanBonus = it->second;
            break;
        }
    }
    ASSERT_FALSE(lifespanTalentId.empty());

    const std::size_t row = *core_->state().disciples.rowOf("1");
    const int32_t lifespanBefore = core_->state().disciples.lifespans[row];
    const auto r = appointment_tx::traitAddConfirmTx(
        core_->state(), "1", "TALENT", lifespanTalentId);
    ASSERT_TRUE(r.ok) << r.message;
    // 追加到列表末尾
    ASSERT_EQ(core_->state().disciples.talentIds[row].size(), 1u);
    EXPECT_EQ(core_->state().disciples.talentIds[row][0], lifespanTalentId);
    // lifespan 同步（realm 9 → maxAge 80）
    const int32_t expectedDelta =
        static_cast<int32_t>(static_cast<double>(gamecore::system::realmMaxAge(9)) *
                             lifespanBonus);
    EXPECT_EQ(core_->state().disciples.lifespans[row],
              lifespanBefore + expectedDelta);
    // checkpoint 重记账
    EXPECT_EQ(core_->state().disciples.cultivationCheckpoints[row],
              core_->state().disciples.cultivations[row]);
    EXPECT_EQ(core_->state().disciples.cultivationCheckpointGameMonths[row],
              core_->state().gameData.gameYear * 12 + core_->state().gameData.gameMonth);
}

TEST_F(AppointmentTxFixture, TraitAddConfirmClearsPendingOnlySameKey) {
    addDisciple("1");
    addDisciple("2");
    gamecore::state::PendingTraitAdd mine;
    mine.discipleId = "1";
    mine.type = "TALENT";
    mine.traitId = "t_old";
    gamecore::state::PendingTraitAdd otherType;
    otherType.discipleId = "1";
    otherType.type = "PHYSIQUE";
    otherType.traitId = "p_old";
    gamecore::state::PendingTraitAdd otherDisciple;
    otherDisciple.discipleId = "2";
    otherDisciple.type = "TALENT";
    otherDisciple.traitId = "t_old2";
    core_->state().gameData.pendingTraitAdds = {mine, otherType, otherDisciple};

    // 任选一个可解析天赋产物（confirm 本地信任模型——不校验来源）
    ASSERT_FALSE(gamecore::data::talentTemplates().empty());
    const std::string newId = gamecore::data::talentTemplates().front().id;
    const auto r = appointment_tx::traitAddConfirmTx(core_->state(), "1", "TALENT", newId);
    ASSERT_TRUE(r.ok);
    ASSERT_EQ(core_->state().gameData.pendingTraitAdds.size(), 2u);
    EXPECT_EQ(core_->state().gameData.pendingTraitAdds[0].type, "PHYSIQUE");
    EXPECT_EQ(core_->state().gameData.pendingTraitAdds[1].discipleId, "2");
}

TEST_F(AppointmentTxFixture, TraitAddConfirmGuardsZeroWrite) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    const auto& templates = gamecore::data::talentTemplates();
    const std::string newId = templates.front().id;
    const std::size_t row = *core_->state().disciples.rowOf("1");
    const auto baseline = rngSnapshot();

    // 弟子不存在 / 已死亡
    EXPECT_EQ(appointment_tx::traitAddConfirmTx(core_->state(), "404", "TALENT", newId)
                  .errorType, "NotFound");
    EXPECT_EQ(appointment_tx::traitAddConfirmTx(core_->state(), "2", "TALENT", newId)
                  .errorType, "NotAlive");
    // 上限已满
    core_->state().disciples.talentIds[row] = {"t1", "t2", "t3", "t4", "t5"};
    EXPECT_EQ(appointment_tx::traitAddConfirmTx(core_->state(), "1", "TALENT", newId)
                  .errorType, "SLOTS_FULL");
    core_->state().disciples.talentIds[row].clear();
    // 产物不可解析 / 已在列表 / template 重复
    EXPECT_EQ(appointment_tx::traitAddConfirmTx(core_->state(), "1", "TALENT", "no_such")
                  .errorType, "INVALID");
    core_->state().disciples.talentIds[row] = {newId};
    EXPECT_EQ(appointment_tx::traitAddConfirmTx(core_->state(), "1", "TALENT", newId)
                  .errorType, "INVALID");
    std::string sameTemplateOtherId;
    for (const auto& t : templates) {
        if (t.id != newId && t.tmpl == templates.front().tmpl) {
            sameTemplateOtherId = t.id;
            break;
        }
    }
    if (!sameTemplateOtherId.empty()) {
        EXPECT_EQ(appointment_tx::traitAddConfirmTx(core_->state(), "1", "TALENT",
                                                    sameTemplateOtherId)
                      .errorType, "INVALID");
    }
    // 全部失败臂零写入 + 零抽取
    ASSERT_EQ(core_->state().disciples.talentIds[row].size(), 1u);
    EXPECT_EQ(core_->state().disciples.talentIds[row][0], newId);
    EXPECT_EQ(rngSnapshot(), baseline);
}

// ── 特质单槽洗炼 ─────────────────────────────────────────────────────

TEST_F(AppointmentTxFixture, TraitWashDeductsAndReturnsPoolEntry) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    // 目标特质 = 任一非负面词条
    std::string targetId;
    for (const auto& a : gamecore::data::affixTemplates()) {
        if (!a.isNegative) { targetId = a.id; break; }
    }
    ASSERT_FALSE(targetId.empty());
    core_->state().disciples.affixIds[*core_->state().disciples.rowOf("1")] = {targetId};

    const auto r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "AFFIX",
        targetId, 0, 1);
    ASSERT_TRUE(r.base.ok) << r.base.message;
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 4);
    EXPECT_FALSE(r.newId.empty());
    // 产物 template 不与已有槽位冲突（含目标自身——禁止"刷回原样"）
    std::set<std::string> excluded;
    for (const auto& a : gamecore::data::affixTemplates()) {
        if (a.id == targetId) excluded.insert(a.tmpl);
    }
    for (const auto& a : gamecore::data::affixTemplates()) {
        if (a.id == r.newId) EXPECT_EQ(excluded.count(a.tmpl), 0u);
    }
    // 不写 pending（洗炼结果由 UI 会话持有）
    EXPECT_TRUE(core_->state().gameData.pendingTraitAdds.empty());
}

TEST_F(AppointmentTxFixture, TraitWashPityPathForcedTopRarity) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    std::string targetId;
    for (const auto& a : gamecore::data::affixTemplates()) {
        if (!a.isNegative) { targetId = a.id; break; }
    }
    ASSERT_FALSE(targetId.empty());
    core_->state().disciples.affixIds[*core_->state().disciples.rowOf("1")] = {targetId};

    const auto r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "AFFIX",
        targetId, 2, 1);
    ASSERT_TRUE(r.base.ok);
    // 保底产物为 3 阶（池非空时）→ 计数归零
    if (r.newId != targetId) {
        for (const auto& a : gamecore::data::affixTemplates()) {
            if (a.id == r.newId) EXPECT_EQ(a.rarity, appointment_tx::kTraitWashTopRarity);
        }
        EXPECT_EQ(r.newPityCount, 0);
    } else {
        EXPECT_EQ(r.newPityCount, appointment_tx::kWashPityThreshold);
    }
}

TEST_F(AppointmentTxFixture, PityPoolExclusionIsFamilyConsistent) {
    // 洗炼排除集按 template（族）粒度过滤——保底池（TOP_RARITY 正向池）与
    // 候选池同族同滤：**每个非负面、非退役的 template 族必含上品成员**
    // ⇒ "保底池空但候选池非空"（放弃产出分支）在真实数据下不可达。
    // 该分支按 Kotlin 同构保留为防御臂（本测试锁定其不可达前提）。
    for (int kindIdx = 0; kindIdx < 3; ++kindIdx) {
        const auto kind = static_cast<appointment_tx::detail::TraitKind>(kindIdx);
        // 收集族 → 族内是否有上品成员
        std::set<std::string> families;
        std::set<std::string> familiesWithTop;
        std::vector<appointment_tx::detail::TraitEntry> pool;
        if (kind == appointment_tx::detail::TraitKind::kTalent) {
            for (const auto& t : gamecore::data::talentTemplates()) {
                if (t.isNegative) continue;
                if (appointment_tx::deprecatedTalentTypes().count(t.type) != 0) continue;
                families.insert(t.tmpl);
                if (t.rarity == appointment_tx::kTraitWashTopRarity) {
                    familiesWithTop.insert(t.tmpl);
                }
            }
        } else if (kind == appointment_tx::detail::TraitKind::kPhysique) {
            for (const auto& p : gamecore::data::physiqueTemplates()) {
                if (p.isNegative) continue;
                families.insert(p.tmpl);
                if (p.rarity == appointment_tx::kTraitWashTopRarity) {
                    familiesWithTop.insert(p.tmpl);
                }
            }
        } else {
            for (const auto& a : gamecore::data::affixTemplates()) {
                if (a.isNegative) continue;
                families.insert(a.tmpl);
                if (a.rarity == appointment_tx::kTraitWashTopRarity) {
                    familiesWithTop.insert(a.tmpl);
                }
            }
        }
        EXPECT_EQ(families, familiesWithTop)
            << "kind " << kindIdx << " 存在无上品成员的候选族——放弃产出分支可达";
    }
}

TEST_F(AppointmentTxFixture, TraitWashGuardsZeroDrawZeroDeduct) {
    addDisciple("1");
    addDisciple("2");
    killDisciple("2");
    core_->state().gameData.jadeSymbols = 0;
    const auto baseline = rngSnapshot();

    // 目标特质已不存在 / 弟子不存在 / 已死亡
    auto r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "AFFIX",
        "no_such_target", 0, 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INVALID_TARGET");
    r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "404", "AFFIX",
        "x", 0, 1);
    EXPECT_FALSE(r.base.ok);
    r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "2", "AFFIX",
        "x", 0, 1);
    EXPECT_FALSE(r.base.ok);
    // 玉符不足（合法目标 + 候选预检通过 + 余额 0）
    std::string targetId;
    for (const auto& t : gamecore::data::talentTemplates()) {
        if (!t.isNegative &&
            appointment_tx::deprecatedTalentTypes().count(t.type) == 0) {
            targetId = t.id;
            break;
        }
    }
    ASSERT_FALSE(targetId.empty());
    core_->state().disciples.talentIds[*core_->state().disciples.rowOf("1")] = {targetId};
    r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT",
        targetId, 0, 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "INSUFFICIENT_JADE");
    // 候选池全被排除（体质正面池有限——全持有后预检拒绝、零扣费零抽取）
    std::vector<std::string> allPositive;
    for (const auto& p : gamecore::data::physiqueTemplates()) {
        if (!p.isNegative) allPositive.push_back(p.id);
    }
    core_->state().gameData.jadeSymbols = 5;
    core_->state().disciples.physiqueIds[*core_->state().disciples.rowOf("1")] =
        allPositive;
    r = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "PHYSIQUE",
        allPositive.front(), 0, 1);
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NO_CANDIDATE");
    EXPECT_EQ(core_->state().gameData.jadeSymbols, 5);
    // 全部失败臂零抽取
    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(AppointmentTxFixture, TraitWashDoubleRunBitwise) {
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    std::string targetId;
    for (const auto& t : gamecore::data::talentTemplates()) {
        if (!t.isNegative &&
            appointment_tx::deprecatedTalentTypes().count(t.type) == 0) {
            targetId = t.id;
            break;
        }
    }
    ASSERT_FALSE(targetId.empty());
    core_->state().disciples.talentIds[*core_->state().disciples.rowOf("1")] = {targetId};

    const auto first = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT",
        targetId, 1, 1);
    const auto firstStates = rngSnapshot();
    SetUp();
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 5;
    core_->state().disciples.talentIds[*core_->state().disciples.rowOf("1")] = {targetId};
    const auto second = appointment_tx::traitWashSlotTx(
        core_->state(), core_->rng().getRng(RngPartition::kSystem), "1", "TALENT",
        targetId, 1, 1);
    EXPECT_EQ(second.newId, first.newId);
    EXPECT_EQ(second.newPityCount, first.newPityCount);
    EXPECT_EQ(rngSnapshot(), firstStates);
}

// ── batch-24：confirm 两入口（纯数据写；零 RNG / 零玉符）──────────────

/// 取一条可解析的天赋模板 id（本地信任模型：confirm 不校验产物来源，只校验可解析性）
static std::string firstTalentId() {
    return gamecore::data::talentTemplates().front().id;
}

/// 取与 excludeId **template 相同**的另一个天赋 id（不存在 → 空串）
static std::string conflictingTalentId(const std::string& excludeId) {
    const auto& all = gamecore::data::talentTemplates();
    std::string tmpl;
    for (const auto& t : all) {
        if (t.id == excludeId) { tmpl = t.tmpl; break; }
    }
    for (const auto& t : all) {
        if (t.id != excludeId && t.tmpl == tmpl) return t.id;
    }
    return "";
}

TEST_F(AppointmentTxFixture, SpiritRootConfirmReplacesRootAndCheckpoints) {
    addDisciple("1");
    const std::size_t row = *core_->state().disciples.rowOf("1");
    core_->state().disciples.cultivations[row] = 1234;
    core_->state().disciples.cultivationCheckpoints[row] = 0;
    core_->state().disciples.cultivationCheckpointGameMonths[row] = 0;

    // 单灵根 + 双灵根均合法（1~2 个元素、无重复、全在洗炼元素表）
    ASSERT_TRUE(appointment_tx::spiritRootWashConfirmTx(
                    core_->state(), "1", "metal").ok);
    EXPECT_EQ(core_->state().disciples.spiritRootTypes[row], "metal");
    // checkpoint 重记账（灵根影响修炼速率——替换瞬间重新投影基准）
    EXPECT_EQ(core_->state().disciples.cultivationCheckpoints[row], 1234);

    ASSERT_TRUE(appointment_tx::spiritRootWashConfirmTx(
                    core_->state(), "1", "metal,water").ok);
    EXPECT_EQ(core_->state().disciples.spiritRootTypes[row], "metal,water");
}

TEST_F(AppointmentTxFixture, SpiritRootConfirmRejectsInvalidRootStrings) {
    addDisciple("1");
    const std::string before = core_->exportStateJson();
    // 非法串五臂：未知元素 / 空串 / 三元素 / 重复元素 / 尾逗号（空元素）
    const char* kInvalid[] = {"unknown", "", "metal,water,fire", "metal,metal", "metal,"};
    for (const std::string& invalid : kInvalid) {
        const auto r = appointment_tx::spiritRootWashConfirmTx(
            core_->state(), "1", invalid);
        EXPECT_FALSE(r.ok) << invalid;
        EXPECT_EQ(r.errorType, "INVALID_ROOT") << invalid;
    }
    EXPECT_EQ(core_->exportStateJson(), before);  // 零写入
}

TEST_F(AppointmentTxFixture, SpiritRootConfirmRejectsMissingAndDeadDisciple) {
    addDisciple("1");
    const auto missing = appointment_tx::spiritRootWashConfirmTx(
        core_->state(), "999", "metal");
    EXPECT_FALSE(missing.ok);
    EXPECT_EQ(missing.errorType, "NotFound");

    killDisciple("1");
    const std::string before = core_->exportStateJson();
    const auto dead = appointment_tx::spiritRootWashConfirmTx(core_->state(), "1", "water");
    EXPECT_FALSE(dead.ok);
    EXPECT_EQ(dead.errorType, "InvalidId");
    EXPECT_EQ(core_->exportStateJson(), before);
}

TEST_F(AppointmentTxFixture, TraitWashConfirmReplacesSlotOnly) {
    addDisciple("1");
    const std::size_t row = *core_->state().disciples.rowOf("1");
    const std::string targetId = firstTalentId();
    core_->state().disciples.talentIds[row] = {targetId};

    // 合法替换：产物 template 与目标槽位不同 → 替换成功且仅动目标槽位
    const auto siblings = gamecore::data::talentTemplates();
    std::string distinctTmplId;
    for (const auto& t : siblings) {
        if (t.tmpl != siblings.front().tmpl) { distinctTmplId = t.id; break; }
    }
    ASSERT_FALSE(distinctTmplId.empty());
    const auto ok = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", targetId, distinctTmplId);
    ASSERT_TRUE(ok.ok) << ok.message;
    ASSERT_EQ(core_->state().disciples.talentIds[row].size(), 1u);
    EXPECT_EQ(core_->state().disciples.talentIds[row][0], distinctTmplId);

    // 多槽位：仅目标槽位被替换，其余槽位逐位不变
    const std::string secondId = siblings.back().id;
    core_->state().disciples.talentIds[row] = {targetId, secondId};
    const auto two = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", targetId, distinctTmplId);
    ASSERT_TRUE(two.ok) << two.message;
    ASSERT_EQ(core_->state().disciples.talentIds[row].size(), 2u);
    EXPECT_EQ(core_->state().disciples.talentIds[row][0], distinctTmplId);
    EXPECT_EQ(core_->state().disciples.talentIds[row][1], secondId);
}

TEST_F(AppointmentTxFixture, TraitWashConfirmRejectsInvalidArms) {
    addDisciple("1");
    const std::size_t row = *core_->state().disciples.rowOf("1");
    const std::string targetId = firstTalentId();
    core_->state().disciples.talentIds[row] = {targetId};

    // 目标不在当前列表
    const auto notOwned = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", "not_owned", targetId);
    EXPECT_FALSE(notOwned.ok);
    EXPECT_EQ(notOwned.errorType, "INVALID");

    // 产物不可解析
    const auto unknown = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", targetId, "unknown_trait");
    EXPECT_FALSE(unknown.ok);
    EXPECT_EQ(unknown.errorType, "INVALID");

    // 空产物 id
    const auto empty = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", targetId, "");
    EXPECT_FALSE(empty.ok);
    EXPECT_EQ(empty.errorType, "INVALID");

    // **同 template 冲突臂（isValidSlotWash 的 template 互斥）为数据依赖**：
    // 需构造"产物与其它槽位同 template"的场景，而真实天赋表中每条活跃条目的
    // template 是否唯一由数据集决定（生成器可能为每条目分配独立 template）。
    // 该臂不在此强行构造（产物与目标同 id 时替换为幂等，非冲突）——由
    // `traitAddConfirmTx` 的同款校验覆盖同一逻辑形状。
    const std::string before = core_->exportStateJson();

    // 未知类型 / 未知弟子 / 死亡弟子
    const auto badType = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "NOPE", targetId, targetId);
    EXPECT_EQ(badType.errorType, "UnknownTraitType");

    const auto missing = appointment_tx::traitWashConfirmTx(
        core_->state(), "999", "TALENT", targetId, targetId);
    EXPECT_EQ(missing.errorType, "NOT_FOUND");
    // 前四臂（不在列表/不可解析/空产物/未知类型/未知弟子）全部零写入
    EXPECT_EQ(core_->exportStateJson(), before);

    // 死亡弟子臂：拒绝且不动弟子特质列（死亡标记本身由 killDisciple 写入）
    killDisciple("1");
    const auto idsBefore = core_->state().disciples.talentIds[row];
    const auto dead = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", targetId, targetId);
    EXPECT_EQ(dead.errorType, "DEAD");
    EXPECT_EQ(core_->state().disciples.talentIds[row], idsBefore);
}

TEST_F(AppointmentTxFixture, TraitWashConfirmSyncsLifespanBothDirections) {
    // 选两条 lifespan 加成不同的天赋（差值为正/负两臂）
    const auto& all = gamecore::data::talentTemplates();
    std::string highId;
    std::string lowId;
    double highBonus = -1e9;
    double lowBonus = 1e9;
    for (const auto& t : all) {
        const auto it = t.effects.find("lifespan");
        if (it == t.effects.end()) continue;
        if (it->second > highBonus) { highBonus = it->second; highId = t.id; }
        if (it->second < lowBonus) { lowBonus = it->second; lowId = t.id; }
    }
    if (highId.empty() || lowId.empty() || highId == lowId || highBonus == lowBonus) {
        GTEST_SKIP() << "天赋表无 lifespan 加成差异（跳过 lifespan 双向用例）";
    }

    addDisciple("1");
    const std::size_t row = *core_->state().disciples.rowOf("1");
    core_->state().disciples.talentIds[row] = {highId};
    core_->state().disciples.lifespans[row] = 100;

    // 高加成 → 低加成：寿命下调
    const auto down = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", highId, lowId);
    ASSERT_TRUE(down.ok) << down.message;
    const int32_t afterDown = core_->state().disciples.lifespans[row];
    EXPECT_LT(afterDown, 100);

    // 低加成 → 高加成：寿命上调
    const auto up = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", lowId, highId);
    ASSERT_TRUE(up.ok) << up.message;
    const int32_t afterUp = core_->state().disciples.lifespans[row];
    EXPECT_GT(afterUp, afterDown);
}

TEST_F(AppointmentTxFixture, ConfirmFamilyIsZeroRngAndEnveloped) {
    // 零 RNG：两 confirm 全程不触任何分区（签名级 + 快照差分）
    addDisciple("1");
    const std::size_t row = *core_->state().disciples.rowOf("1");
    const auto baseline = rngSnapshot();

    ASSERT_TRUE(appointment_tx::spiritRootWashConfirmTx(core_->state(), "1", "water").ok);
    const std::string targetId = firstTalentId();
    core_->state().disciples.talentIds[row] = {targetId};
    // 非法臂（目标不在列表）同样零抽取
    const auto r = appointment_tx::traitWashConfirmTx(
        core_->state(), "1", "TALENT", "not_owned", targetId);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ(rngSnapshot(), baseline);

    // 信封级：成功 + 失败（Kotlin 回退臂契约）
    const auto okEnv = exec(action::SPIRIT_ROOT_WASH_CONFIRM_TX,
                            {{"discipleId", "1"}, {"newRootType", "wood"}});
    EXPECT_EQ(okEnv["status"], "success") << okEnv.dump();
    EXPECT_EQ(okEnv["data"]["replaced"], true);

    const auto failEnv = exec(action::SPIRIT_ROOT_WASH_CONFIRM_TX,
                              {{"discipleId", "1"}, {"newRootType", "bogus"}});
    EXPECT_EQ(failEnv["status"], "failure") << failEnv.dump();
    EXPECT_EQ(failEnv["code"], "INVALID_ROOT");
}

TEST_F(AppointmentTxFixture, ConfirmDoubleRunProducesBitIdenticalState) {
    const auto run = [this]() {
        addDisciple("1");
        const std::size_t row = *core_->state().disciples.rowOf("1");
        appointment_tx::spiritRootWashConfirmTx(core_->state(), "1", "metal,water");
        const std::string targetId = firstTalentId();
        core_->state().disciples.talentIds[row] = {targetId};
        // 非法臂（同 template 冲突）——双运行同样须逐位一致
        appointment_tx::traitWashConfirmTx(
            core_->state(), "1", "TALENT", targetId, targetId);
        return core_->exportStateJson();
    };

    const std::string first = run();
    SetUp();
    const std::string second = run();
    EXPECT_EQ(second, first);
}

// ── 零 RNG 族审计 + 信封级双保险 ─────────────────────────────────────

TEST_F(AppointmentTxFixture, ZeroRngFamilyLeavesRngStatesUntouched) {
    // 任命/卸任/驻守/确认四事务全程零抽取——签名级（API 不接受 rng）+
    // 快照差分双证据
    addDisciple("1");
    const auto baseline = rngSnapshot();

    ASSERT_TRUE(appointment_tx::elderAppointTx(core_->state(), "INNER_ELDER", "1").base.ok);
    ASSERT_TRUE(appointment_tx::elderDismissTx(core_->state(), "INNER_ELDER").base.ok);
    ASSERT_TRUE(appointment_tx::warehouseGarrisonAssignTx(core_->state(), "wh", "1", "n", "s")
                    .base.ok);
    ASSERT_TRUE(appointment_tx::traitAddConfirmTx(core_->state(), "1", "TALENT",
                                                  gamecore::data::talentTemplates()
                                                      .front().id)
                    .ok);
    EXPECT_EQ(rngSnapshot(), baseline);
}

TEST_F(AppointmentTxFixture, DispatchEnvelopeHappyAndFailure) {
    // 信封级：成功携带 data；失败为 failure 信封（Kotlin 回退臂契约）
    addDisciple("1");
    core_->state().gameData.jadeSymbols = 0;

    const auto okR = exec(action::ELDER_APPOINT_TX,
                          {{"slotType", "RECRUITING"}, {"discipleId", "1"}});
    EXPECT_EQ(okR["status"], "success");
    EXPECT_EQ(okR["data"]["appointed"], true);

    const auto failR = exec(action::SPIRIT_ROOT_WASH_TX,
                            {{"discipleId", "1"}, {"pityCount", 0}, {"cost", 1}});
    EXPECT_EQ(failR["status"], "failure");
    EXPECT_EQ(failR["code"], "INSUFFICIENT_JADE");
}

}  // namespace
}  // namespace gamecore

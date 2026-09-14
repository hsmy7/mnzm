// ============================================================
// disciple_lifecycle_tx_test — 弟子生命周期 UI 操作事务守护（batch-14）
//
// 守护目标：disciple_lifecycle_tx.h 五事务与 Kotlin 源语义逐位一致——
//   - 逐出（校验链判定序 / 12 类槽位逐类清理含住所 / 实例销毁 /
//     派生 map 收口 / 行删除 / 年报计数 / bagItems 信封回传）
//   - 拜师（三相校验判定序 / masterIds 落表 / 双侧日志草稿）
//   - 婚姻批准（已有道侣防御零写入 / partnerIds 双向绑定 / MARRIAGE 事件）
//   - 婚姻拒绝（W4-A·w3-02 1750：拒绝事件直写 / 零弟子表写入 / 无失败臂）
//   - 释放思过（静默 no-op 同义 / statusData 定向移除保留其余 key /
//     状态回 IDLE）
//   - 年俸开关（盲写覆写）
//   - 失败臂零写入（校验链先行，任一臂失败不触碰状态）
//   - 死亡标记红线（CLAUDE.md 13.3）：逐出为行删除而非死亡标记——
//     断言行移除且 annualDeceasedDisciples 不变（markDead 路径不经本事务）
//   - RNG 零消费审计（全族 rngStates 快照差分——对拍命门）
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "gamecore/action_ids.h"
#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/system/disciple_lifecycle_tx.h"

namespace gamecore {
namespace {

namespace lifecycle_tx = gamecore::system::disciple_lifecycle_tx;

using gamecore::state::Disciple;
using gamecore::state::StorageBagItem;

class DiscipleLifecycleTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result = core_->execute(
            actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    /// 挂一个最小弟子（存活、IDLE、20 岁），返回行号（列直访用）
    std::size_t addDisciple(const std::string& id, bool alive = true,
                            const std::string& status = "IDLE") {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.realm = 9;
        d.realmLayer = 1;
        d.isAlive = alive;
        d.spiritRootType = "metal";
        d.age = 20;
        d.lifespan = 80;
        d.status = status;
        d.currentHp = 100;
        d.currentMp = 50;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    /// 为指定弟子布满 12 类槽位（逐出清理穷尽性断言基准）
    void seedAllSlots(const std::string& id) {
        auto& gd = core_->state().gameData;
        gd.spiritMineSlots = {{gamecore::state::SpiritMineSlot()}};
        gd.spiritMineSlots[0].index = 0;
        gd.spiritMineSlots[0].discipleId = id;
        gd.spiritMineSlots[0].discipleName = "N" + id;

        gd.librarySlots = {{gamecore::state::LibrarySlot()}};
        gd.librarySlots[0].index = 0;
        gd.librarySlots[0].discipleId = id;

        gd.elderSlots.viceSectMaster = id;
        gamecore::state::DirectDiscipleSlot direct;
        direct.index = 0;
        direct.discipleId = id;
        gd.elderSlots.herbGardenDisciples = {direct};

        gamecore::state::ResidenceSlot residence;
        residence.buildingInstanceId = "res-1";
        residence.slotIndex = 0;
        residence.discipleId = id;
        gd.residenceSlots = {residence};

        gamecore::state::BloodRefinementProgress refine;
        refine.discipleId = id;
        gd.activeBloodRefinements = {{"refine-1", refine}};

        gd.patrolSlots = {{gamecore::state::PatrolSlot()}};
        gd.patrolSlots[0].index = 0;
        gd.patrolSlots[0].discipleId = id;

        gamecore::state::WarehouseGarrisonSlot warehouse;
        warehouse.buildingInstanceId = "wh-1";
        warehouse.discipleId = id;
        gd.warehouseGarrisons = {warehouse};

        gamecore::state::BattleTeam team;
        team.id = "team-1";
        gamecore::state::BattleTeamSlot slot;
        slot.index = 0;
        slot.discipleId = id;
        team.slots = {slot};
        gd.battleTeams = {team};

        gamecore::state::WorldSect sect;
        sect.id = "sect-1";
        sect.name = "玩家宗门";
        sect.isPlayerSect = true;
        gamecore::state::GarrisonSlot garrison;
        garrison.index = 0;
        garrison.discipleId = id;
        sect.garrisonSlots = {garrison};
        gd.worldMapSects = {sect};

        gamecore::state::ProductionSlot production;
        production.id = "prod-1";
        production.buildingId = "alchemy-1";
        production.assignedDiscipleId = id;
        gd.productionSlots = {production};

        gamecore::state::CaveExplorationTeam cave;
        cave.id = "cave-1";
        cave.memberIds = {id};
        cave.memberNames = {"N" + id};
        cave.status = "EXPLORING";
        gd.caveExplorationTeams = {cave};

        gamecore::state::ActiveMission mission;
        mission.id = "mission-1";
        mission.discipleIds = {id};
        mission.discipleNames = {"N" + id};
        gd.activeMissions = {mission};
    }

    /// 派生 map 播种（血炼三 map + 功法熟练度——收口断言基准）
    void seedDerivedMaps(const std::string& id) {
        auto& gd = core_->state().gameData;
        gamecore::state::BloodRefinementBonusTotal bonusTotal;
        bonusTotal.hpBonus = 5;
        gd.bloodRefinementBonusTotals = {{id, bonusTotal}};
        gamecore::state::BloodRefinementPctTotal pctTotal;
        pctTotal.hpBonusPct = 0.5;
        gd.bloodRefinementPctTotals = {{id, pctTotal}};
        gd.bloodRefinements = {{id, {std::string("rec-1")}}};
        gd.manualProficiencies = {{id, {gamecore::state::ManualProficiencyData()}}};
    }

    /// 穿戴装备 + 功法实例播种（实例销毁断言基准）
    void seedWornInstances(const std::string& discipleId) {
        auto& state = core_->state();
        auto row = *state.disciples.rowOf(discipleId);
        gamecore::state::EquipmentInstance eq;
        eq.id = "eq-" + discipleId;
        eq.name = "铁剑";
        eq.ownerId = discipleId;
        eq.isEquipped = true;
        state.equipmentInstances.push_back(eq);
        state.disciples.weaponIds[row] = eq.id;

        gamecore::state::ManualInstance mn;
        mn.id = "mn-" + discipleId;
        mn.name = "长拳";
        mn.ownerId = discipleId;
        mn.isLearned = true;
        state.manualInstances.push_back(mn);
        state.disciples.manualIds[row] = {mn.id};
    }

    /// 袋内两条物品播种（信封回传断言基准）
    void seedBagItems(const std::string& discipleId) {
        auto row = *core_->state().disciples.rowOf(discipleId);
        StorageBagItem a;
        a.itemId = "bag-1";
        a.itemType = "material";
        a.name = "兽皮";
        a.quantity = 2;
        StorageBagItem b;
        b.itemId = "bag-2";
        b.itemType = "herb";
        b.name = "灵草";
        a.quantity = 1;
        core_->state().disciples.storageBagItems[row] = {a, b};
    }

    /// rngStates 快照（零消费审计基准）
    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 逐出 ─────────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, ExpelTx_Happy_12类槽位逐类清理与行删除) {
    addDisciple("1");
    addDisciple("2");  // 对照行：不受逐出波及
    seedAllSlots("1");
    seedWornInstances("1");
    seedDerivedMaps("1");
    seedBagItems("1");

    const auto before = rngSnapshot();
    const auto r = exec(action::DISCIPLE_LIFECYCLE_EXPEL,
                        {{"discipleId", "1"}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(r["data"]["expelled"].get<bool>());

    // 信封回传袋物品（Kotlin 物化回仓库）
    const auto& bag = r["data"]["bagItems"];
    ASSERT_TRUE(bag.is_array());
    ASSERT_EQ(bag.size(), 2u);
    ASSERT_EQ(bag[0]["itemId"], "bag-1");
    ASSERT_EQ(bag[1]["itemId"], "bag-2");

    auto& gd = core_->state().gameData;
    auto& ds = core_->state().disciples;

    // 行删除（非死亡标记——死亡红线：isAlive/status=DEAD 写入不经本事务）
    EXPECT_FALSE(ds.contains("1"));
    EXPECT_TRUE(ds.contains("2"));  // 对照行保留
    EXPECT_EQ(gd.annualDeceasedDisciples, 0);

    // 12 类槽位逐类清理
    EXPECT_TRUE(gd.spiritMineSlots[0].discipleId.empty());
    EXPECT_TRUE(gd.librarySlots[0].discipleId.empty());
    EXPECT_TRUE(gd.elderSlots.viceSectMaster.empty());
    EXPECT_TRUE(gd.elderSlots.herbGardenDisciples[0].discipleId.empty());
    EXPECT_TRUE(gd.residenceSlots[0].discipleId.empty());  // includeResidence=true
    EXPECT_TRUE(gd.activeBloodRefinements.empty());
    EXPECT_TRUE(gd.patrolSlots[0].discipleId.empty());
    EXPECT_TRUE(gd.warehouseGarrisons[0].discipleId.empty());
    EXPECT_TRUE(gd.battleTeams[0].slots[0].discipleId.empty());
    EXPECT_TRUE(gd.battleTeams[0].slots[0].isAlive);
    ASSERT_TRUE(gd.worldMapSects[0].isPlayerSect);
    EXPECT_TRUE(gd.worldMapSects[0].garrisonSlots[0].discipleId.empty());
    EXPECT_FALSE(gd.productionSlots[0].assignedDiscipleId.has_value());
    EXPECT_TRUE(gd.caveExplorationTeams[0].memberIds.empty());
    EXPECT_EQ(gd.caveExplorationTeams[0].status, "COMPLETED");  // 整队仅剩死者
    EXPECT_TRUE(gd.activeMissions[0].discipleIds.empty());

    // 实例销毁（不返还仓库）
    bool eqLeft = false;
    for (const auto& e : core_->state().equipmentInstances) {
        if (e.id == "eq-1") eqLeft = true;
    }
    EXPECT_FALSE(eqLeft);
    bool mnLeft = false;
    for (const auto& m : core_->state().manualInstances) {
        if (m.id == "mn-1") mnLeft = true;
    }
    EXPECT_FALSE(mnLeft);

    // 派生 map 收口（血炼三 map + 功法熟练度）
    EXPECT_EQ(gd.bloodRefinementBonusTotals.count("1"), 0u);
    EXPECT_EQ(gd.bloodRefinementPctTotals.count("1"), 0u);
    EXPECT_EQ(gd.bloodRefinements.count("1"), 0u);
    EXPECT_EQ(gd.manualProficiencies.count("1"), 0u);

    // 年报脱离弟子计数
    EXPECT_EQ(gd.annualDesertedDisciples, 1);

    // 零 RNG
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, ExpelTx_FailureArms_校验链全臂零写入) {
    addDisciple("1");
    seedAllSlots("1");
    const auto before = rngSnapshot();
    auto& gd = core_->state().gameData;

    // 臂 1：弟子不存在
    auto r1 = exec(action::DISCIPLE_LIFECYCLE_EXPEL, {{"discipleId", "999"}});
    EXPECT_EQ(r1["status"], "failure");
    EXPECT_EQ(r1["code"], "NotFound");

    // 臂 2：已死亡（NotAlive）
    addDisciple("3", /*alive=*/false);
    auto r2 = exec(action::DISCIPLE_LIFECYCLE_EXPEL, {{"discipleId", "3"}});
    EXPECT_EQ(r2["status"], "failure");
    EXPECT_EQ(r2["code"], "NotAlive");

    // 臂 3：血炼中（SlotInvalid）
    addDisciple("4", /*alive=*/true, /*status=*/"REFINING");
    auto r3 = exec(action::DISCIPLE_LIFECYCLE_EXPEL, {{"discipleId", "4"}});
    EXPECT_EQ(r3["status"], "failure");
    EXPECT_EQ(r3["code"], "SlotInvalid");

    // 零写入：行仍在、槽位未清、计数未动
    EXPECT_TRUE(core_->state().disciples.contains("1"));
    EXPECT_TRUE(core_->state().disciples.contains("3"));
    EXPECT_TRUE(core_->state().disciples.contains("4"));
    EXPECT_FALSE(gd.spiritMineSlots[0].discipleId.empty());
    EXPECT_EQ(gd.annualDesertedDisciples, 0);
    EXPECT_TRUE(gd.worldMapSects[0].garrisonSlots[0].discipleId == "1");
    EXPECT_EQ(rngSnapshot(), before);
}

// ── 拜师 ─────────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, ApprenticeTx_Happy_落表与双侧日志草稿) {
    addDisciple("1");
    addDisciple("2");
    const auto before = rngSnapshot();

    const auto r = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                        {{"discipleId", "1"}, {"masterId", "2"}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(r["data"]["apprenticed"].get<bool>());
    EXPECT_EQ(r["data"]["apprenticeLogLine"], "20岁：拜弟子2为师");
    EXPECT_EQ(r["data"]["masterLogLine"], "20岁：收弟子1为徒");

    const auto row = *core_->state().disciples.rowOf("1");
    EXPECT_EQ(core_->state().disciples.masterIds[row], "2");
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, ApprenticeTx_FailureArms_三相校验零写入) {
    addDisciple("1");
    addDisciple("2");
    addDisciple("3", /*alive=*/false);
    const auto before = rngSnapshot();

    // 相 1：徒弟不存在 / 师父不存在
    auto r1 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "999"}, {"masterId", "2"}});
    EXPECT_EQ(r1["code"], "NotFound");
    auto r2 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "999"}});
    EXPECT_EQ(r2["code"], "NotFound");

    // 相 2：自拜 / 徒弟死亡 / 师父死亡
    auto r3 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "1"}});
    EXPECT_EQ(r3["code"], "SlotInvalid");
    auto r4 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "3"}, {"masterId", "2"}});
    EXPECT_EQ(r4["code"], "NotAlive");
    auto r5 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "3"}});
    EXPECT_EQ(r5["code"], "NotAlive");

    // 零写入
    for (std::size_t row = 0; row < core_->state().disciples.ids.size(); ++row) {
        EXPECT_TRUE(core_->state().disciples.masterIds[row].empty());
    }
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, ApprenticeTx_Capacity_已有师父与名额满与死亡不计数) {
    addDisciple("1");
    addDisciple("2");  // 师父
    addDisciple("10");
    addDisciple("11");
    addDisciple("12");
    addDisciple("13");
    addDisciple("14");
    addDisciple("15", /*alive=*/false);  // 已死亡徒弟：不占名额
    addDisciple("16");
    const auto before = rngSnapshot();

    auto rowOf = [&](const std::string& id) {
        return *core_->state().disciples.rowOf(id);
    };
    auto& masterIds = core_->state().disciples.masterIds;
    // 弟子 1 已有师父（师父 9 非在册弟子字符串——名额相命中即回退，
    // 相 1 存在性校验的师父存在性另由 FailureArms 臂覆盖）
    masterIds[rowOf("1")] = "9";
    // 师父 2 已收 4 名存活徒弟 + 1 名已死亡徒弟（15——不计入名额）
    for (const std::string& id : {"10", "11", "12", "13"}) {
        masterIds[rowOf(id)] = "2";
    }
    masterIds[rowOf("15")] = "2";

    // 臂：弟子已有师父（不可更改）
    auto r1 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "1"}, {"masterId", "2"}});
    EXPECT_EQ(r1["code"], "SlotInvalid");

    // 臂：已死亡弟子不可拜师（相 2 早退先于名额相）
    auto r2 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "15"}, {"masterId", "2"}});
    EXPECT_EQ(r2["code"], "NotAlive");

    // 死亡徒弟不占名额：4 存活 + 1 死亡 → 第 5 名存活徒弟可拜（成功）
    auto r3 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "16"}, {"masterId", "2"}});
    ASSERT_EQ(r3["status"], "success") << r3["code"];
    EXPECT_EQ(masterIds[rowOf("16")], "2");

    // 名额满：5 名存活徒弟后再拜 → SlotInvalid（最多5名）
    auto r4 = exec(action::DISCIPLE_LIFECYCLE_APPRENTICE,
                   {{"discipleId", "14"}, {"masterId", "2"}});
    EXPECT_EQ(r4["code"], "SlotInvalid");
    EXPECT_NE(r4["message"].get<std::string>().find("5"), std::string::npos);

    EXPECT_EQ(rngSnapshot(), before);
}

// ── 婚姻批准 ─────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, MarryApproveTx_Happy_双向绑定与事件直写) {
    addDisciple("1");
    addDisciple("2");
    const auto before = rngSnapshot();

    const auto r = exec(action::DISCIPLE_LIFECYCLE_MARRY_APPROVE,
                        {{"maleId", "1"}, {"femaleId", "2"},
                         {"maleName", "张三"}, {"femaleName", "李四"}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(r["data"]["paired"].get<bool>());

    auto& ds = core_->state().disciples;
    EXPECT_EQ(ds.partnerIds[*ds.rowOf("1")], "2");
    EXPECT_EQ(ds.partnerIds[*ds.rowOf("2")], "1");

    // MARRIAGE 事件 C++ 直写（recordGameEvent 完整守卫对齐）
    auto& records = core_->state().gameData.gameEventRecords;
    ASSERT_EQ(records.size(), 1u);
    EXPECT_EQ(records[0].category, "SECT");
    EXPECT_EQ(records[0].eventType, "MARRIAGE");
    EXPECT_EQ(records[0].summary, "弟子张三与弟子李四结为道侣");
    EXPECT_EQ(records[0].relatedEntityId, "1");
    EXPECT_EQ(records[0].sequenceId, 1);

    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, MarryApproveTx_DefensiveSkip_已有道侣零写入) {
    addDisciple("1");
    addDisciple("2");
    addDisciple("3");
    auto& ds = core_->state().disciples;
    ds.partnerIds[*ds.rowOf("3")] = "1";  // 男方已有道侣
    const auto before = rngSnapshot();
    const auto eventsBefore = core_->state().gameData.gameEventRecords.size();

    const auto r = exec(action::DISCIPLE_LIFECYCLE_MARRY_APPROVE,
                        {{"maleId", "3"}, {"femaleId", "2"},
                         {"maleName", "王五"}, {"femaleName", "李四"}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_FALSE(r["data"]["paired"].get<bool>());

    // 零写入：既有绑定不动、无新事件
    EXPECT_EQ(ds.partnerIds[*ds.rowOf("3")], "1");
    EXPECT_TRUE(ds.partnerIds[*ds.rowOf("2")].empty());
    EXPECT_EQ(core_->state().gameData.gameEventRecords.size(), eventsBefore);
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, MarryApproveTx_NotFound_提议残留边界回退Kotlin) {
    addDisciple("1");
    const auto r = exec(action::DISCIPLE_LIFECYCLE_MARRY_APPROVE,
                        {{"maleId", "1"}, {"femaleId", "999"},
                         {"maleName", "张三"}, {"femaleName", "李四"}});
    EXPECT_EQ(r["status"], "failure");
    EXPECT_EQ(r["code"], "NotFound");
}

// ── 婚姻拒绝（W4-A·w3-02，1750）────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, MarryRejectTx_Happy_事件直写零弟子表写入) {
    addDisciple("1");
    addDisciple("2");
    auto& ds = core_->state().disciples;
    const auto before = rngSnapshot();

    const auto r = exec(action::DISCIPLE_LIFECYCLE_MARRY_REJECT,
                        {{"maleId", "1"}, {"femaleId", "2"},
                         {"maleName", "张三"}, {"femaleName", "李四"}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(r["data"]["rejected"].get<bool>());

    // MARRIAGE 拒绝事件 C++ 直写（recordGameEvent 完整守卫对齐）
    auto& records = core_->state().gameData.gameEventRecords;
    ASSERT_EQ(records.size(), 1u);
    EXPECT_EQ(records[0].category, "SECT");
    EXPECT_EQ(records[0].eventType, "MARRIAGE");
    EXPECT_EQ(records[0].summary, "弟子张三拒绝与弟子李四结为道侣");
    EXPECT_EQ(records[0].relatedEntityId, "1");
    EXPECT_EQ(records[0].sequenceId, 1);

    // 零弟子表写入：partnerIds 与行集合原样
    EXPECT_TRUE(ds.partnerIds[*ds.rowOf("1")].empty());
    EXPECT_TRUE(ds.partnerIds[*ds.rowOf("2")].empty());
    EXPECT_EQ(core_->state().disciples.ids.size(), 2u);
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, MarryRejectTx_无失败臂_零幽灵列) {
    // 拒绝 = 提议存在性为 Kotlin 前置（pendingMarriageProposals 运行态）；
    // C++ 侧无失败臂——弟子行不存在（提议残留边界）事件仍直写，但
    // **零弟子表写入** ⇒ 不产生 partnerIds 幽灵列条目（与批准事务的
    // NotFound 回退臂形成对照：批准需写行所以回退，拒绝无行写可直达）
    const auto r = exec(action::DISCIPLE_LIFECYCLE_MARRY_REJECT,
                        {{"maleId", "1"}, {"femaleId", "999"},
                         {"maleName", "张三"}, {"femaleName", "李四"}});
    ASSERT_EQ(r["status"], "success");
    auto& records = core_->state().gameData.gameEventRecords;
    ASSERT_EQ(records.size(), 1u);
    EXPECT_EQ(records[0].summary, "弟子张三拒绝与弟子李四结为道侣");
    EXPECT_TRUE(core_->state().disciples.ids.empty());
}

// ── 释放思过 ─────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, ReleaseReflectionTx_Happy_定向移除与状态回IDLE) {
    addDisciple("1", /*alive=*/true, /*status=*/"REFLECTING");
    const auto row = *core_->state().disciples.rowOf("1");
    core_->state().disciples.statusData[row] = {
        {"reflectionStartYear", "3"},
        {"reflectionEndYear", "4"},
        {"bloodRefineBuildingId", "alchemy-1"},  // 既有 key 必须保留
    };
    const auto before = rngSnapshot();

    const auto r = exec(action::DISCIPLE_LIFECYCLE_RELEASE_REFLECTION,
                        {{"discipleId", "1"}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(r["data"]["written"].get<bool>());

    const auto& statusData = core_->state().disciples.statusData[row];
    EXPECT_EQ(statusData.count("reflectionStartYear"), 0u);
    EXPECT_EQ(statusData.count("reflectionEndYear"), 0u);
    EXPECT_EQ(statusData.at("bloodRefineBuildingId"), "alchemy-1");
    EXPECT_EQ(core_->state().disciples.statuses[row], "IDLE");
    EXPECT_EQ(rngSnapshot(), before);
}

TEST_F(DiscipleLifecycleTxFixture, ReleaseReflectionTx_SilentArms_静默NoOp同义) {
    addDisciple("1");
    addDisciple("2", /*alive=*/false, /*status=*/"REFLECTING");
    const auto before = rngSnapshot();

    // 臂：不存在
    auto r1 = exec(action::DISCIPLE_LIFECYCLE_RELEASE_REFLECTION,
                   {{"discipleId", "999"}});
    ASSERT_EQ(r1["status"], "success");
    EXPECT_FALSE(r1["data"]["written"].get<bool>());

    // 臂：已死亡
    auto r2 = exec(action::DISCIPLE_LIFECYCLE_RELEASE_REFLECTION,
                   {{"discipleId", "2"}});
    ASSERT_EQ(r2["status"], "success");
    EXPECT_FALSE(r2["data"]["written"].get<bool>());

    EXPECT_EQ(rngSnapshot(), before);
}

// ── 年俸开关 ─────────────────────────────────────────────────

TEST_F(DiscipleLifecycleTxFixture, SalaryToggleTx_盲写覆写) {
    const auto before = rngSnapshot();

    auto r = exec(action::DISCIPLE_LIFECYCLE_SALARY_TOGGLE,
                  {{"realm", 3}, {"enabled", true}});
    ASSERT_EQ(r["status"], "success");
    ASSERT_TRUE(core_->state().gameData.yearlySalaryEnabled[3]);

    r = exec(action::DISCIPLE_LIFECYCLE_SALARY_TOGGLE,
             {{"realm", 3}, {"enabled", false}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_FALSE(core_->state().gameData.yearlySalaryEnabled[3]);

    EXPECT_EQ(rngSnapshot(), before);
}

}  // namespace
}  // namespace gamecore

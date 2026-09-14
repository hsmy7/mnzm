// ============================================================
// disciple_tx_test — 弟子管理 UI 操作事务守护（batch-08 第一子批）
//
// 守护目标：disciple_tx.h 六事务与 Kotlin 源语义逐位一致——
//   - 装备穿脱（堆叠轨道铸实例 / 实例轨道置位 / 旧装备入袋）
//   - 功法学习卸下（资格守卫判定序 / 堆叠精确消耗 / HP/MP 增量门）
//   - 任命卸任（clearAllSlots 11 类清理 / 亲传·藏经阁槽写 / occupant 捕获）
//   - 失败臂零写入（校验链先行，任一臂失败不触碰状态）
//   - 装备实例轨道完整性（无双持有：实例入袋即离表）
//   - RNG 零消费审计（六事务全程 rngStates 不动——对拍命门）
//
// RNG 审计方法：事务族零抽取，直接断言 gameData.rngStates 快照前后
// 逐位一致；execute_dispatch 信封级双保险（DispatchFailure 臂）。
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
#include "gamecore/system/disciple_tx.h"

namespace gamecore {
namespace {

namespace disciple_tx = gamecore::system::disciple_tx;

using gamecore::state::Disciple;
using gamecore::state::DirectDiscipleSlot;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipmentStack;
using gamecore::state::LibrarySlot;
using gamecore::state::ManualInstance;
using gamecore::state::ManualStack;

class DiscipleTxFixture : public ::testing::Test {
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

    /// 挂一个最小存活弟子（炼气一层=9），返回行号（列直访用）
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

    void addWeaponStack(const std::string& id, int32_t minRealm = 9,
                        int32_t quantity = 1) {
        EquipmentStack s;
        s.id = id;
        s.name = "铁剑" + id;
        s.rarity = 2;
        s.slot = "WEAPON";
        s.minRealm = minRealm;
        s.quantity = quantity;
        s.physicalAttack = 10;
        core_->state().equipmentStacks.push_back(s);
    }

    void addUnequippedInstance(const std::string& id, int32_t minRealm = 9) {
        EquipmentInstance e;
        e.id = id;
        e.name = "宝剑" + id;
        e.rarity = 3;
        e.slot = "WEAPON";
        e.minRealm = minRealm;
        e.physicalAttack = 20;
        e.isEquipped = false;
        core_->state().equipmentInstances.push_back(e);
    }

    void addManualStack(const std::string& id, const std::string& type = "BODY",
                        int32_t minRealm = 9) {
        ManualStack m;
        m.id = id;
        m.name = "功法" + id;
        m.rarity = 2;
        m.type = type;
        m.minRealm = minRealm;
        m.quantity = 2;
        m.stats = {{"hp", 5}, {"mp", 3}};
        core_->state().manualStacks.push_back(m);
    }

    /// rngStates 快照（零消费审计基准）
    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    FixedClock clock_;
    ConsoleLogger logger_;
    std::unique_ptr<GameCore> core_;
};

// ── 事务 1：装备穿戴 ─────────────────────────────────────────────

TEST_F(DiscipleTxFixture, EquipFromStackMintsInstance) {
    const std::size_t row = addDisciple("1");
    addWeaponStack("w1", 9, 3);

    const auto r = disciple_tx::equipTransaction(core_->state(), "1", "w1");
    ASSERT_TRUE(r.base.ok) << r.base.message;

    // 堆叠 -1、实例铸造（isEquipped/ownerId）、槽位列写
    EXPECT_EQ(core_->state().equipmentStacks.size(), 1u);
    EXPECT_EQ(core_->state().equipmentStacks[0].quantity, 2);
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
    const auto& inst = core_->state().equipmentInstances[0];
    EXPECT_TRUE(inst.isEquipped);
    EXPECT_EQ(inst.ownerId, "1");
    EXPECT_EQ(inst.slot, "WEAPON");
    EXPECT_EQ(core_->state().disciples.weaponIds[row], inst.id);
    // 日志草稿（20岁：装备了X）
    EXPECT_EQ(r.logLine, "20岁：装备了铁剑w1");
}

TEST_F(DiscipleTxFixture, EquipLastStackConsumesIt) {
    addDisciple("1");
    addWeaponStack("w1", 9, 1);

    const auto r = disciple_tx::equipTransaction(core_->state(), "1", "w1");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(core_->state().equipmentStacks.empty());
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
}

TEST_F(DiscipleTxFixture, EquipInstanceTrackMarksEquipped) {
    const std::size_t row = addDisciple("1");
    addUnequippedInstance("i1");

    const auto r = disciple_tx::equipTransaction(core_->state(), "1", "i1");
    ASSERT_TRUE(r.base.ok);
    // 实例轨道：不新增条目、置位 + 槽位列写
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
    EXPECT_TRUE(core_->state().equipmentInstances[0].isEquipped);
    EXPECT_EQ(core_->state().disciples.weaponIds[row], "i1");
    EXPECT_EQ(r.logLine, "20岁：装备了宝剑i1");
}

TEST_F(DiscipleTxFixture, EquipReplacesOldIntoBag) {
    const std::size_t row = addDisciple("1");
    addUnequippedInstance("old");
    addUnequippedInstance("new");
    ASSERT_TRUE(disciple_tx::equipTransaction(core_->state(), "1", "old").base.ok);
    ASSERT_EQ(core_->state().disciples.weaponIds[row], "old");

    const auto r = disciple_tx::equipTransaction(core_->state(), "1", "new");
    ASSERT_TRUE(r.base.ok);
    // 旧装备：入袋（带实例 payload，防双持有离实例表）
    const auto& bag = core_->state().disciples.storageBagItems[row];
    ASSERT_EQ(bag.size(), 1u);
    EXPECT_EQ(bag[0].itemId, "old");
    EXPECT_EQ(bag[0].itemType, "equipment_instance");
    ASSERT_TRUE(bag[0].equipmentInstance.has_value());
    EXPECT_EQ(bag[0].equipmentInstance->id, "old");
    // 实例表只剩 new
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
    EXPECT_EQ(core_->state().equipmentInstances[0].id, "new");
    EXPECT_EQ(core_->state().disciples.weaponIds[row], "new");
    // 日志为替换式（oldName 在卸下后查表 → Kotlin 活路径同款"旧装备"兜底）
    EXPECT_EQ(r.logLine, "20岁：将旧装备替换为宝剑new");
}

TEST_F(DiscipleTxFixture, EquipFailureArmsAreZeroWrite) {
    const std::size_t row = addDisciple("1");  // 境界 9；守卫臂装备 minRealm=5
    addWeaponStack("w1", /*minRealm=*/5, /*quantity=*/5);
    addUnequippedInstance("i1", /*minRealm=*/5);

    // 弟子不存在
    auto r = disciple_tx::equipTransaction(core_->state(), "999", "w1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");
    // 装备不存在
    r = disciple_tx::equipTransaction(core_->state(), "1", "nope");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "NotFound");
    // 境界不足（实例轨道：9 > 5）
    r = disciple_tx::equipTransaction(core_->state(), "1", "i1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "RealmTooLow");
    // 境界不足（堆叠轨道）
    r = disciple_tx::equipTransaction(core_->state(), "1", "w1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "RealmTooLow");

    // 零写入断言：堆叠/实例表/槽位列全不动
    EXPECT_EQ(core_->state().equipmentStacks.size(), 1u);
    EXPECT_EQ(core_->state().equipmentStacks[0].quantity, 5);
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
    EXPECT_FALSE(core_->state().equipmentInstances[0].isEquipped);
    EXPECT_TRUE(core_->state().disciples.weaponIds[row].empty());
    EXPECT_TRUE(core_->state().disciples.storageBagItems[row].empty());
}

TEST_F(DiscipleTxFixture, EquipAlreadyEquippedFails) {
    const std::size_t row = addDisciple("1");
    addUnequippedInstance("i1");
    ASSERT_TRUE(disciple_tx::equipTransaction(core_->state(), "1", "i1").base.ok);

    // 同一弟子重复穿同一件（Kotlin 判定序：弟子存在先过，isEquipped 命中）
    const auto r = disciple_tx::equipTransaction(core_->state(), "1", "i1");
    EXPECT_FALSE(r.base.ok);
    EXPECT_EQ(r.base.errorType, "AlreadyEquipped");
    // 槽位不被顶替
    EXPECT_EQ(core_->state().disciples.weaponIds[row], "i1");
}

// ── 事务 2：装备卸下 ─────────────────────────────────────────────

TEST_F(DiscipleTxFixture, UnequipMovesInstanceToBag) {
    const std::size_t row = addDisciple("1");
    addUnequippedInstance("i1");
    ASSERT_TRUE(disciple_tx::equipTransaction(core_->state(), "1", "i1").base.ok);

    const auto r = disciple_tx::unequipTransaction(core_->state(), "1", "i1");
    ASSERT_TRUE(r.ok);
    // 槽位清空 + 实例入袋（payload 保真）+ 离实例表
    EXPECT_TRUE(core_->state().disciples.weaponIds[row].empty());
    const auto& bag = core_->state().disciples.storageBagItems[row];
    ASSERT_EQ(bag.size(), 1u);
    ASSERT_TRUE(bag[0].equipmentInstance.has_value());
    EXPECT_EQ(bag[0].equipmentInstance->id, "i1");
    EXPECT_TRUE(core_->state().equipmentInstances.empty());
}

TEST_F(DiscipleTxFixture, UnequipNotWornFailsZeroWrite) {
    const std::size_t row = addDisciple("1");
    addUnequippedInstance("i1");

    const auto r = disciple_tx::unequipTransaction(core_->state(), "1", "i1");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ(r.errorType, "SlotInvalid");
    // 零写入：实例仍在表、未入袋
    ASSERT_EQ(core_->state().equipmentInstances.size(), 1u);
    EXPECT_TRUE(core_->state().disciples.storageBagItems[row].empty());
}

TEST_F(DiscipleTxFixture, UnequipMissingInstanceClearsSlotOnly) {
    // 损坏态：槽位有 id 但实例表缺失 → Kotlin 同分支仅清槽
    const std::size_t row = addDisciple("1");
    core_->state().disciples.weaponIds[row] = "ghost";

    const auto r = disciple_tx::unequipTransaction(core_->state(), "1", "ghost");
    ASSERT_TRUE(r.ok);
    EXPECT_TRUE(core_->state().disciples.weaponIds[row].empty());
    EXPECT_TRUE(core_->state().disciples.storageBagItems[row].empty());
}

// ── 事务 3：功法学习 ─────────────────────────────────────────────

TEST_F(DiscipleTxFixture, LearnManualConsumesStackAndAppliesStats) {
    const std::size_t row = addDisciple("1");
    addManualStack("m1");

    const auto r = disciple_tx::learnManualTransaction(core_->state(), "1", "m1");
    ASSERT_TRUE(r.ok);
    // 堆叠精确消耗（2 → 1）
    EXPECT_EQ(core_->state().manualStacks.size(), 1u);
    EXPECT_EQ(core_->state().manualStacks[0].quantity, 1);
    // 实例铸造（isLearned/ownerId）+ manualIds 挂载
    ASSERT_EQ(core_->state().manualInstances.size(), 1u);
    const auto& inst = core_->state().manualInstances[0];
    EXPECT_TRUE(inst.isLearned);
    EXPECT_EQ(inst.ownerId, "1");
    ASSERT_EQ(core_->state().disciples.manualIds[row].size(), 1u);
    EXPECT_EQ(core_->state().disciples.manualIds[row][0], inst.id);
    // HP/MP 增量（stats hp=5 / mp=3，100+5 / 50+3）
    EXPECT_EQ(core_->state().disciples.currentHps[row], 105);
    EXPECT_EQ(core_->state().disciples.currentMps[row], 53);
}

TEST_F(DiscipleTxFixture, LearnManualGuardsFailZeroWrite) {
    addDisciple("1");  // 境界 9；守卫臂堆叠 minRealm=5
    addManualStack("m1", "BODY", /*minRealm=*/5);
    // 名额守卫：塞满 6 本（kBaseManualSlots=6，无 manualSlot 天赋）
    addDisciple("2");
    for (int i = 0; i < 6; ++i) {
        ManualInstance filler;
        filler.id = "fill" + std::to_string(i);
        filler.name = "填位" + std::to_string(i);
        filler.type = "BODY";
        core_->state().manualInstances.push_back(filler);
        core_->state().disciples.manualIds[1].push_back(filler.id);
    }
    addManualStack("m2");
    // 心法唯一守卫
    addDisciple("3");
    ManualInstance mind;
    mind.id = "mind0";
    mind.name = "已有心法";
    mind.type = "MIND";
    core_->state().manualInstances.push_back(mind);
    core_->state().disciples.manualIds[2].push_back("mind0");
    addManualStack("m3", "MIND");
    // 同名唯一守卫
    addDisciple("4");
    ManualInstance sameName;
    sameName.id = "same0";
    sameName.name = "功法m4";
    sameName.type = "BODY";
    core_->state().manualInstances.push_back(sameName);
    core_->state().disciples.manualIds[3].push_back("same0");
    addManualStack("m4");

    EXPECT_EQ(disciple_tx::learnManualTransaction(core_->state(), "1", "m1").errorType,
              "RealmTooLow");
    EXPECT_EQ(disciple_tx::learnManualTransaction(core_->state(), "2", "m2").errorType,
              "SlotsFull");
    EXPECT_EQ(disciple_tx::learnManualTransaction(core_->state(), "3", "m3").errorType,
              "MindDuplicate");
    EXPECT_EQ(disciple_tx::learnManualTransaction(core_->state(), "4", "m4").errorType,
              "NameDuplicate");

    // 零写入：四摞堆叠原量、无新实例、第 1/2/3/4 行 manualIds 不动
    for (const auto& s : core_->state().manualStacks) {
        EXPECT_EQ(s.quantity, 2) << s.id;
    }
    EXPECT_EQ(core_->state().manualInstances.size(), 8u);
    EXPECT_EQ(core_->state().disciples.manualIds[0].size(), 0u);
}

// ── 事务 4：功法卸下 ─────────────────────────────────────────────

TEST_F(DiscipleTxFixture, UnlearnManualMovesInstanceToBagAndCleansProficiency) {
    const std::size_t row = addDisciple("1");
    addManualStack("m1");
    ASSERT_TRUE(disciple_tx::learnManualTransaction(core_->state(), "1", "m1").ok);
    const std::string instId = core_->state().disciples.manualIds[row][0];
    gamecore::state::ManualProficiencyData prof;
    prof.manualId = instId;
    prof.manualName = "功法m1";
    prof.proficiency = 50.0;
    gamecore::state::ManualProficiencyData other;
    other.manualId = "other";
    other.manualName = "其他功法";
    other.proficiency = 10.0;
    core_->state().gameData.manualProficiencies["1"] = {prof, other};

    const auto r =
        disciple_tx::unlearnManualTransaction(core_->state(), "1", instId);
    ASSERT_TRUE(r.ok);
    // 实例入袋（payload）+ manualIds 移除 + 离实例表
    const auto& bag = core_->state().disciples.storageBagItems[row];
    ASSERT_EQ(bag.size(), 1u);
    ASSERT_TRUE(bag[0].manualInstance.has_value());
    EXPECT_EQ(bag[0].manualInstance->id, instId);
    EXPECT_TRUE(core_->state().disciples.manualIds[row].empty());
    EXPECT_TRUE(core_->state().manualInstances.empty());
    // 熟练度条目移除（非目标保留）
    const auto& profs = core_->state().gameData.manualProficiencies["1"];
    ASSERT_EQ(profs.size(), 1u);
    EXPECT_EQ(profs[0].manualId, "other");
}

TEST_F(DiscipleTxFixture, UnlearnMissingInstanceFailsZeroWrite) {
    const std::size_t row = addDisciple("1");

    const auto r = disciple_tx::unlearnManualTransaction(core_->state(), "1", "nope");
    EXPECT_FALSE(r.ok);
    EXPECT_TRUE(core_->state().disciples.storageBagItems[row].empty());
}

// ── 事务 5/6：任命 / 卸任 ────────────────────────────────────────

TEST_F(DiscipleTxFixture, AssignDirectSlotClearsAllSlotsThenWrites) {
    addDisciple("1");
    // 弟子 1 已占藏经阁 0 号 + 炼丹亲传 0 号（clearAllSlots 应清）
    LibrarySlot lib;
    lib.index = 0;
    lib.discipleId = "1";
    lib.discipleName = "弟子1";
    core_->state().gameData.librarySlots.push_back(lib);
    DirectDiscipleSlot alch;
    alch.index = 0;
    alch.discipleId = "1";
    alch.discipleName = "弟子1";
    core_->state().gameData.elderSlots.alchemyDisciples.push_back(alch);
    core_->state().gameData.activeSectId = "sect-p";

    // 任命到药园亲传 0 号（旧 occupant = 他人 → 返回供 gate 释放）
    DirectDiscipleSlot herb;
    herb.index = 0;
    herb.discipleId = "9";
    herb.discipleName = "旧人";
    core_->state().gameData.elderSlots.herbGardenDisciples.push_back(herb);

    const auto r = disciple_tx::assignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "herbGarden",
        0, "1", "弟子1", "炼气一层", "#FF0000");
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.oldOccupantId, "9");
    // 11 类清理：藏经阁与炼丹亲传均已脱钩
    EXPECT_TRUE(core_->state().gameData.librarySlots[0].discipleId.empty());
    EXPECT_TRUE(core_->state().gameData.elderSlots.alchemyDisciples[0].discipleId.empty());
    // 目标槽写入（含 sectId/根色）
    const auto& slot = core_->state().gameData.elderSlots.herbGardenDisciples[0];
    EXPECT_EQ(slot.discipleId, "1");
    EXPECT_EQ(slot.discipleName, "弟子1");
    EXPECT_EQ(slot.sectId, "sect-p");
    EXPECT_EQ(slot.discipleSpiritRootColor, "#FF0000");
}

TEST_F(DiscipleTxFixture, AssignDirectSlotGrowsListWithDefaults) {
    addDisciple("1");

    const auto r = disciple_tx::assignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "forge",
        2, "1", "弟子1", "炼气一层", "#FFFFFF");
    ASSERT_TRUE(r.base.ok);
    EXPECT_TRUE(r.oldOccupantId.empty());
    const auto& list = core_->state().gameData.elderSlots.forgeDisciples;
    ASSERT_EQ(list.size(), 3u);
    // 占位槽 = Kotlin DirectDiscipleSlot() 默认（index 恒 0）
    EXPECT_EQ(list[0].index, 0);
    EXPECT_TRUE(list[0].discipleId.empty());
    EXPECT_EQ(list[2].discipleId, "1");
    EXPECT_EQ(list[2].index, 2);
}

TEST_F(DiscipleTxFixture, UnassignDirectSlotReturnsRemovedId) {
    DirectDiscipleSlot slot;
    slot.index = 0;
    slot.discipleId = "7";
    slot.discipleName = "卸任者";
    core_->state().gameData.elderSlots.forgeDisciples.push_back(slot);

    const auto r = disciple_tx::unassignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "forge", 0);
    ASSERT_TRUE(r.base.ok);
    EXPECT_EQ(r.removedDiscipleId, "7");
    const auto& after = core_->state().gameData.elderSlots.forgeDisciples[0];
    EXPECT_TRUE(after.discipleId.empty());
    EXPECT_EQ(after.index, 0);
    // 越界：Kotlin resetDirectSlotAt 同义 no-op 成功
    const auto r2 = disciple_tx::unassignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "forge", 5);
    ASSERT_TRUE(r2.base.ok);
    EXPECT_TRUE(r2.removedDiscipleId.empty());
    // 未知槽型：else 分支 no-op 成功
    const auto r3 = disciple_tx::unassignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "unknown", 0);
    ASSERT_TRUE(r3.base.ok);
}

TEST_F(DiscipleTxFixture, AssignAndUnassignLibrarySlot) {
    addDisciple("1");

    const auto a = disciple_tx::assignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kLibrary, "", 1,
        "1", "弟子1", "", "");
    ASSERT_TRUE(a.base.ok);
    EXPECT_TRUE(a.oldOccupantId.empty());
    const auto& slots = core_->state().gameData.librarySlots;
    ASSERT_EQ(slots.size(), 2u);
    // 扩容补空带位置 index（Kotlin LibrarySlot(index = slots.size)）
    EXPECT_EQ(slots[0].index, 0);
    EXPECT_TRUE(slots[0].discipleId.empty());
    EXPECT_EQ(slots[1].discipleId, "1");
    EXPECT_EQ(slots[1].discipleName, "弟子1");

    const auto u = disciple_tx::unassignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kLibrary, "", 1);
    ASSERT_TRUE(u.base.ok);
    EXPECT_EQ(u.removedDiscipleId, "1");
    EXPECT_TRUE(slots[1].discipleId.empty());
    // 越界：Kotlin bounds 早退同义静默成功
    const auto u2 = disciple_tx::unassignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kLibrary, "", 9);
    ASSERT_TRUE(u2.base.ok);
}

// ── RNG 零消费审计（对拍命门）────────────────────────────────────

TEST_F(DiscipleTxFixture, AllSixTransactionsConsumeZeroRng) {
    const std::size_t row = addDisciple("1");
    addWeaponStack("w1");
    addUnequippedInstance("i1");
    addManualStack("m1");
    const auto before = rngSnapshot();

    ASSERT_TRUE(disciple_tx::equipTransaction(core_->state(), "1", "w1").base.ok);
    ASSERT_TRUE(disciple_tx::unequipTransaction(
        core_->state(), "1", core_->state().disciples.weaponIds[row]).ok);
    ASSERT_TRUE(disciple_tx::equipTransaction(core_->state(), "1", "i1").base.ok);
    ASSERT_TRUE(disciple_tx::learnManualTransaction(core_->state(), "1", "m1").ok);
    ASSERT_TRUE(disciple_tx::unlearnManualTransaction(
        core_->state(), "1", core_->state().disciples.manualIds[row][0]).ok);
    ASSERT_TRUE(disciple_tx::assignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "forge",
        0, "1", "弟子1", "", "").base.ok);
    ASSERT_TRUE(disciple_tx::unassignSlotTransaction(
        core_->state(), disciple_tx::SlotFamily::kElderDirect, "forge", 0).base.ok);

    EXPECT_EQ(rngSnapshot(), before);
}

// ── execute_dispatch 信封（协议级）────────────────────────────────

TEST_F(DiscipleTxFixture, DispatchEquipEnvelope) {
    addDisciple("1");
    addWeaponStack("w1");

    const auto r = exec(gamecore::action::DISCIPLE_TX_EQUIP,
                        {{"discipleId", "1"}, {"equipmentId", "w1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("equipped"), true);
    EXPECT_EQ(r.at("data").at("logLine"), "20岁：装备了铁剑w1");
    EXPECT_EQ(core_->state().equipmentStacks[0].quantity, 1);
}

TEST_F(DiscipleTxFixture, DispatchFailureEnvelopeFallsBackSignal) {
    const std::size_t row = addDisciple("1");
    addWeaponStack("w1", /*minRealm=*/5);  // 境界 9 > 5 → RealmTooLow

    const auto r = exec(gamecore::action::DISCIPLE_TX_EQUIP,
                        {{"discipleId", "1"}, {"equipmentId", "w1"}});
    ASSERT_EQ(r.at("status"), "failure");
    EXPECT_EQ(r.at("code"), "RealmTooLow");
    // 失败零写入
    EXPECT_EQ(core_->state().equipmentStacks[0].quantity, 1);
    EXPECT_TRUE(core_->state().disciples.weaponIds[row].empty());
}

TEST_F(DiscipleTxFixture, DispatchAssignSlotEnvelope) {
    addDisciple("1");

    const auto r = exec(gamecore::action::DISCIPLE_TX_ASSIGN_SLOT,
                        {{"family", "library"}, {"slotIndex", 0},
                         {"discipleId", "1"}, {"discipleName", "弟子1"}});
    ASSERT_EQ(r.at("status"), "success");
    EXPECT_EQ(r.at("data").at("assigned"), true);
    EXPECT_EQ(core_->state().gameData.librarySlots[0].discipleId, "1");

    const auto u = exec(gamecore::action::DISCIPLE_TX_UNASSIGN_SLOT,
                        {{"family", "library"}, {"slotIndex", 0}});
    ASSERT_EQ(u.at("status"), "success");
    EXPECT_EQ(u.at("data").at("removedDiscipleId"), "1");
}

}  // namespace
}  // namespace gamecore

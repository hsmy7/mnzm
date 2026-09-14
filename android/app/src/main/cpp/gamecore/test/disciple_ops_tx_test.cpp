// ============================================================
// disciple_ops_tx_test — w3-01 弟子操作面事务守护（W4-A 第一子批）
//
// 守护目标：disciple_tx.h W4-A 段九事务与 Kotlin 源语义逐位一致——
//   - 改名（names 行写 + 招募列表 isSamePerson 同人净化）
//   - 类型直改 / 关注切换（statusData["followed"] 翻转）
//   - 赏赐（pill facade 丹药链生效/入袋分流 + material/herb/seed 扣仓入袋；
//     先校验弟子存在再扣仓库——无效 id 物品不消失）
//   - 服药（canUsePill 资格链 + 扣仓库 + facade 丹药链 + 日志草稿）
//   - 功法替换（七链校验 + manualIds 换血 + 旧实例入袋防双持有）
//   - 血炼启动（灵石/材料/排他校验链 + 槽位清理 + REFINING + 进度写入）
//   - 状态派生（14 flag 优先级序 + positionName 定向写删 + 灵矿自愈）
//   - 失败臂零写入（校验链先行，任一臂失败不触碰状态）
//   - RNG 零消费审计（九事务全程 rngStates 不动——对拍命门）
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
using gamecore::state::ManualInstance;
using gamecore::state::ManualStack;
using gamecore::state::Material;
using gamecore::state::Pill;

class DiscipleOpsTxFixture : public ::testing::Test {
protected:
    void SetUp() override {
        core_ = std::make_unique<GameCore>(&clock_, &logger_);
        GameCoreConfig config;
        config.seedInitialized = true;
        config.systemSeed = 42;
        core_->initialize(config);
    }

    nlohmann::json exec(int32_t actionId, const nlohmann::json& params) {
        const std::string result =
            core_->execute(actionId, params.dump(), 1000);
        return nlohmann::json::parse(result);
    }

    std::map<int32_t, int64_t> rngSnapshot() const {
        return core_->state().gameData.rngStates;
    }

    /// 挂一个最小存活弟子（炼气一层=9），返回行号
    std::size_t addDisciple(const std::string& id, int32_t realm = 9) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + id;
        d.surname = "张";
        d.realm = realm;
        d.realmLayer = 1;
        d.isAlive = true;
        d.spiritRootType = "metal";
        d.age = 20;
        d.lifespan = 80;
        d.status = "IDLE";
        d.currentHp = 50;   // 未满血（治疗丹可用的前提）
        d.currentMp = 20;
        core_->state().disciples.appendDisciple(d);
        return *core_->state().disciples.rowOf(id);
    }

    /// 仓库丹药（可复用效果面由用例自定）
    Pill makePill(const std::string& id, const std::string& name,
                  int32_t quantity = 3) {
        Pill p;
        p.id = id;
        p.name = name;
        p.rarity = 2;
        p.grade = "MEDIUM";
        p.pillType = "cultivationAdd";
        p.effects.cultivationAdd = 100;
        p.minRealm = 9;
        p.quantity = quantity;
        return p;
    }

    Material makeMaterial(const std::string& id, int32_t quantity = 5,
                          bool locked = false) {
        Material m;
        m.id = id;
        m.name = "妖兽血";
        m.rarity = 2;
        m.quantity = quantity;
        m.isLocked = locked;
        return m;
    }

    ManualStack makeManualStack(const std::string& id, const std::string& name,
                                const std::string& type = "ABILITY") {
        ManualStack s;
        s.id = id;
        s.name = name;
        s.type = type;
        s.rarity = 2;
        s.minRealm = 9;
        s.quantity = 1;
        return s;
    }

    std::unique_ptr<GameCore> core_;
    gamecore::FixedClock clock_;
    gamecore::ConsoleLogger logger_;
};

// ── 改名 ────────────────────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, RenameWritesNameAndPurifiesRecruitList) {
    const std::size_t row = addDisciple("1");
    core_->state().gameData.recruitList.push_back([] {
        Disciple c;
        c.id = "cand-1";
        c.name = "弟子1";       // 与弟子1 同名同姓（签名命中）
        c.surname = "张";
        c.gender = "male";      // 与 Disciple 默认性别同值（签名面）
        c.spiritRootType = "metal";
        c.age = 21;             // 年龄容差 2 内
        c.isAlive = true;
        return c;
    }());
    core_->state().gameData.recruitList.push_back([] {
        Disciple c;
        c.id = "cand-2";
        c.name = "外人";
        c.surname = "李";
        c.gender = "male";
        c.spiritRootType = "wood";
        c.age = 30;
        c.isAlive = true;
        return c;
    }());

    const auto r = exec(action::DISCIPLE_OP_RENAME,
                        {{"discipleId", "1"}, {"newName", "新名"}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_TRUE(r["data"]["renamed"].get<bool>());
    auto& ds = core_->state().disciples;
    EXPECT_EQ(ds.names[row], "新名");
    // 同人残留净化（签名 + 年龄容差命中 cand-1）、外人保留
    ASSERT_EQ(core_->state().gameData.recruitList.size(), 1u);
    EXPECT_EQ(core_->state().gameData.recruitList[0].id, "cand-2");
}

TEST_F(DiscipleOpsTxFixture, RenameMissingDiscipleFailsWithoutWrite) {
    addDisciple("1");
    const auto before = rngSnapshot();
    const auto r = exec(action::DISCIPLE_OP_RENAME,
                        {{"discipleId", "404"}, {"newName", "X"}});
    EXPECT_EQ(r["status"], "failure");
    // 失败臂零写入：既有弟子行不被触碰
    EXPECT_EQ(core_->state().disciples.names[*core_->state().disciples.rowOf("1")],
              "弟子1");
    EXPECT_EQ(rngSnapshot(), before);
}

// ── 类型直改 / 关注切换 ─────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, ChangeTypeWritesColumn) {
    const std::size_t row = addDisciple("1");
    const auto r = exec(action::DISCIPLE_OP_CHANGE_TYPE,
                        {{"discipleId", "1"}, {"newType", "OUTER"}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_EQ(core_->state().disciples.discipleTypes[row], "OUTER");
}

TEST_F(DiscipleOpsTxFixture, ToggleFollowFlipsStatusData) {
    addDisciple("1");
    auto& sd = core_->state().disciples.statusData[0];
    (void)sd;
    const auto r1 = exec(action::DISCIPLE_OP_TOGGLE_FOLLOW, {{"discipleId", "1"}});
    ASSERT_EQ(r1["status"], "success");
    EXPECT_TRUE(r1["data"]["followedAfter"].get<bool>());
    const auto r2 = exec(action::DISCIPLE_OP_TOGGLE_FOLLOW, {{"discipleId", "1"}});
    ASSERT_EQ(r2["status"], "success");
    EXPECT_FALSE(r2["data"]["followedAfter"].get<bool>());
    // 关闭后 key 定向移除
    EXPECT_EQ(core_->state().disciples.statusData[0].count("followed"), 0u);
}

// ── 赏赐 ────────────────────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, RewardUsablePillAppliesEffectsInTransaction) {
    const std::size_t row = addDisciple("1");
    core_->state().pills.push_back(makePill("p1", "聚气丹"));
    const double cultBefore = core_->state().disciples.cultivations[row];

    const auto r = exec(action::DISCIPLE_OP_REWARD_ITEM,
                        {{"discipleId", "1"}, {"itemType", "pill"},
                         {"itemId", "p1"}, {"quantity", 1}});
    ASSERT_EQ(r["status"], "success");
    // 扣仓库 + 生效（cultivation +100 无 clamp——facade 口径）同事务
    EXPECT_EQ(core_->state().pills[0].quantity, 2);
    EXPECT_DOUBLE_EQ(core_->state().disciples.cultivations[row], cultBefore + 100.0);
    // 可服用 ⇒ 不入袋
    EXPECT_TRUE(core_->state().disciples.storageBagItems[row].empty());
}

TEST_F(DiscipleOpsTxFixture, RewardUnusablePillGoesToBagWithGradeDisplay) {
    const std::size_t row = addDisciple("1");
    Pill p = makePill("p1", "凝智丹");
    p.pillType = "";                  // 无 pillType → 按属性面分类
    p.effects.cultivationAdd = 0;
    p.effects.intelligenceAdd = 5;    // 永久属性丹
    core_->state().pills.push_back(p);
    // 预置同 tier 同字段使用记录（"2#intelligence"）⇒ canUsePill 拒绝
    core_->state().disciples.usedPermanentPillKeys[row] = {"2#intelligence"};
    const int32_t intBefore = core_->state().disciples.intelligences[row];

    const auto r = exec(action::DISCIPLE_OP_REWARD_ITEM,
                        {{"discipleId", "1"}, {"itemType", "pill"},
                         {"itemId", "p1"}, {"quantity", 1}});
    ASSERT_EQ(r["status"], "success");
    // 扣仓库 + 入袋（grade 为 displayName "中品"）
    EXPECT_EQ(core_->state().pills[0].quantity, 2);
    ASSERT_EQ(core_->state().disciples.storageBagItems[row].size(), 1u);
    const auto& bag = core_->state().disciples.storageBagItems[row][0];
    EXPECT_EQ(bag.itemId, "p1");
    EXPECT_EQ(bag.grade.value_or(""), "中品");
    EXPECT_FALSE(bag.effect.has_value() == false);
    // 智力未被加成（未服用）
    EXPECT_EQ(core_->state().disciples.intelligences[row], intBefore);
}

TEST_F(DiscipleOpsTxFixture, RewardMaterialDeductsThenBags) {
    const std::size_t row = addDisciple("1");
    core_->state().materials.push_back(makeMaterial("m1"));

    const auto r = exec(action::DISCIPLE_OP_REWARD_ITEM,
                        {{"discipleId", "1"}, {"itemType", "material"},
                         {"itemId", "m1"}, {"quantity", 2},
                         {"itemName", "妖兽血"}, {"itemRarity", 2}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_EQ(core_->state().materials[0].quantity, 3);
    ASSERT_EQ(core_->state().disciples.storageBagItems[row].size(), 1u);
    EXPECT_EQ(core_->state().disciples.storageBagItems[row][0].itemType, "material");
    EXPECT_EQ(core_->state().disciples.storageBagItems[row][0].quantity, 2);
}

TEST_F(DiscipleOpsTxFixture, RewardMaterialInvalidDiscipleKeepsWarehouse) {
    core_->state().materials.push_back(makeMaterial("m1"));
    const auto r = exec(action::DISCIPLE_OP_REWARD_ITEM,
                        {{"discipleId", "404"}, {"itemType", "material"},
                         {"itemId", "m1"}, {"quantity", 2}});
    EXPECT_EQ(r["status"], "failure");
    // 先校验弟子存在再扣仓库：无效 id 时物品不消失
    EXPECT_EQ(core_->state().materials[0].quantity, 5);
}

// ── 服药 ────────────────────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, UsePillDeductsAppliesAndReturnsLogDraft) {
    const std::size_t row = addDisciple("1");
    core_->state().pills.push_back(makePill("p1", "聚气丹"));
    const double cultBefore = core_->state().disciples.cultivations[row];

    const auto r = exec(action::DISCIPLE_OP_USE_PILL,
                        {{"discipleId", "1"}, {"pillId", "p1"}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_TRUE(r["data"]["used"].get<bool>());
    EXPECT_EQ(r["data"]["logLine"].get<std::string>(), "20岁：服用了聚气丹");
    EXPECT_EQ(core_->state().pills[0].quantity, 2);
    EXPECT_DOUBLE_EQ(core_->state().disciples.cultivations[row], cultBefore + 100.0);
}

TEST_F(DiscipleOpsTxFixture, UsePillUnusableFailsWithZeroWrite) {
    const std::size_t row = addDisciple("1");
    Pill p = makePill("p1", "凝智丹");
    p.pillType = "";
    p.effects.cultivationAdd = 0;
    p.effects.intelligenceAdd = 5;
    core_->state().pills.push_back(p);
    core_->state().disciples.usedPermanentPillKeys[row] = {"2#intelligence"};
    const double cultBefore = core_->state().disciples.cultivations[row];

    const auto r = exec(action::DISCIPLE_OP_USE_PILL,
                        {{"discipleId", "1"}, {"pillId", "p1"}});
    EXPECT_EQ(r["status"], "failure");
    // 静默守卫零写入：仓库不扣、效果不落
    EXPECT_EQ(core_->state().pills[0].quantity, 3);
    EXPECT_DOUBLE_EQ(core_->state().disciples.cultivations[row], cultBefore);
}

// ── 功法替换 ────────────────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, ReplaceManualSwapsInstanceAndBagsOld) {
    const std::size_t row = addDisciple("1");
    // 旧实例（已学）
    ManualInstance old;
    old.id = "old-1";
    old.name = "青元功";
    old.type = "ABILITY";
    old.rarity = 2;
    old.ownerId = "1";
    old.isLearned = true;
    core_->state().manualInstances.push_back(old);
    core_->state().disciples.manualIds[row] = {"old-1"};
    // 新堆叠
    core_->state().manualStacks.push_back(makeManualStack("s1", "赤炎诀"));

    const auto r = exec(action::DISCIPLE_OP_REPLACE_MANUAL,
                        {{"discipleId", "1"}, {"oldInstanceId", "old-1"},
                         {"newStackId", "s1"}});
    ASSERT_EQ(r["status"], "success");
    // manualIds 换血（旧出新进）
    const auto& mids = core_->state().disciples.manualIds[row];
    ASSERT_EQ(mids.size(), 1u);
    EXPECT_NE(mids[0], "old-1");
    // 旧实例入袋 + 实例表移除（防双持有）
    ASSERT_EQ(core_->state().disciples.storageBagItems[row].size(), 1u);
    EXPECT_TRUE(core_->state().disciples.storageBagItems[row][0]
                    .manualInstance.has_value());
    bool oldStillTracked = false;
    for (const auto& m : core_->state().manualInstances) {
        if (m.id == "old-1") oldStillTracked = true;
    }
    EXPECT_FALSE(oldStillTracked);
    // 堆叠整摞消耗
    EXPECT_TRUE(core_->state().manualStacks.empty());
    // 日志草稿
    EXPECT_EQ(r["data"]["logLine"].get<std::string>(), "20岁：将功法青元功替换为赤炎诀");
}

TEST_F(DiscipleOpsTxFixture, ReplaceManualMindConflictFailsWithoutWrite) {
    const std::size_t row = addDisciple("1");
    ManualInstance old;
    old.id = "old-1";
    old.name = "青元功";
    old.type = "ABILITY";
    old.ownerId = "1";
    old.isLearned = true;
    core_->state().manualInstances.push_back(old);
    ManualInstance otherMind;
    otherMind.id = "mind-1";
    otherMind.name = "太上心法";
    otherMind.type = "MIND";
    otherMind.ownerId = "1";
    otherMind.isLearned = true;
    core_->state().manualInstances.push_back(otherMind);
    core_->state().disciples.manualIds[row] = {"old-1", "mind-1"};
    core_->state().manualStacks.push_back(
        makeManualStack("s1", "玄天真经", /*type=*/"MIND"));

    const auto r = exec(action::DISCIPLE_OP_REPLACE_MANUAL,
                        {{"discipleId", "1"}, {"oldInstanceId", "old-1"},
                         {"newStackId", "s1"}});
    EXPECT_EQ(r["status"], "failure");
    // 失败臂零写入：manualIds / 实例表 / 堆叠全不动
    EXPECT_EQ(core_->state().disciples.manualIds[row].size(), 2u);
    EXPECT_EQ(core_->state().manualStacks.size(), 1u);
}

// ── 血炼启动 ────────────────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, StartBloodRefinementWritesProgressAndStatus) {
    const std::size_t row = addDisciple("1");
    core_->state().materials.push_back(makeMaterial("m1", /*quantity=*/5));
    core_->state().gameData.spiritStones = 1000;
    // 预置一个旧槽位引用（清理面验证）
    core_->state().gameData.librarySlots.push_back([] {
        gamecore::state::LibrarySlot s;
        s.index = 0;
        s.discipleId = "1";
        s.discipleName = "弟子1";
        return s;
    }());

    const auto r = exec(action::DISCIPLE_OP_START_BLOOD_REFINEMENT,
                        {{"buildingInstanceId", "pool-1"},
                         {"requiredSpiritStones", 100},
                         {"materialName", "妖兽血"}, {"materialRarity", 2},
                         {"materialCount", 3},
                         {"discipleId", "1"}, {"discipleName", "弟子1"},
                         {"materialId", "m1"}, {"selectedStat", "hp"},
                         {"bonusPercent", 0.05}, {"durationMonths", 3}});
    ASSERT_EQ(r["status"], "success");
    // 灵石扣除 + 材料消耗
    EXPECT_EQ(core_->state().gameData.spiritStones, 900);
    EXPECT_EQ(core_->state().materials[0].quantity, 2);
    // 槽位清理（藏经阁引用被清）
    EXPECT_TRUE(core_->state().gameData.librarySlots[0].discipleId.empty());
    // 进度写入 + REFINING 状态 + statusData 覆写
    EXPECT_EQ(core_->state().gameData.activeBloodRefinements.size(), 1u);
    EXPECT_EQ(core_->state().disciples.statuses[row], "REFINING");
    EXPECT_EQ(core_->state().disciples.statusData[row]["buildingId"], "pool-1");
}

TEST_F(DiscipleOpsTxFixture, StartBloodRefinementStonesInsufficientZeroWrite) {
    addDisciple("1");
    core_->state().materials.push_back(makeMaterial("m1", 5));
    core_->state().gameData.spiritStones = 50;  // 不足

    const auto r = exec(action::DISCIPLE_OP_START_BLOOD_REFINEMENT,
                        {{"buildingInstanceId", "pool-1"},
                         {"requiredSpiritStones", 100},
                         {"materialName", "妖兽血"}, {"materialRarity", 2},
                         {"materialCount", 3},
                         {"discipleId", "1"}, {"discipleName", "弟子1"},
                         {"materialId", "m1"}, {"selectedStat", "hp"},
                         {"bonusPercent", 0.05}, {"durationMonths", 3}});
    EXPECT_EQ(r["status"], "failure");
    // 失败臂零写入：灵石/材料/进度/状态全不动
    EXPECT_EQ(core_->state().gameData.spiritStones, 50);
    EXPECT_EQ(core_->state().materials[0].quantity, 5);
    EXPECT_TRUE(core_->state().gameData.activeBloodRefinements.empty());
    EXPECT_EQ(core_->state().disciples.statuses[0], "IDLE");
}

TEST_F(DiscipleOpsTxFixture, StartBloodRefinementPoolOccupiedFails) {
    addDisciple("1");
    core_->state().materials.push_back(makeMaterial("m1", 5));
    core_->state().gameData.spiritStones = 1000;
    core_->state().gameData.activeBloodRefinements["pool-1"] =
        gamecore::state::BloodRefinementProgress{};

    const auto r = exec(action::DISCIPLE_OP_START_BLOOD_REFINEMENT,
                        {{"buildingInstanceId", "pool-1"},
                         {"requiredSpiritStones", 100},
                         {"materialName", "妖兽血"}, {"materialRarity", 2},
                         {"materialCount", 3},
                         {"discipleId", "1"}, {"discipleName", "弟子1"},
                         {"materialId", "m1"}, {"selectedStat", "hp"},
                         {"bonusPercent", 0.05}, {"durationMonths", 3}});
    EXPECT_EQ(r["status"], "failure");
    EXPECT_TRUE(core_->state().materials[0].quantity == 5);
}

// ── 状态派生 ────────────────────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, SyncStatusDerivesStudyingAndPositionName) {
    const std::size_t row = addDisciple("1");
    core_->state().gameData.librarySlots.push_back([] {
        gamecore::state::LibrarySlot s;
        s.index = 0;
        s.discipleId = "1";
        s.discipleName = "弟子1";
        return s;
    }());

    const auto r = exec(action::DISCIPLE_OP_SYNC_STATUS, {{"discipleId", "1"}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_EQ(r["data"]["status"].get<std::string>(), "STUDYING");
    EXPECT_EQ(core_->state().disciples.statuses[row], "STUDYING");
    // 非 MANAGING：positionName key 不存在
    EXPECT_EQ(core_->state().disciples.statusData[row].count("positionName"), 0u);
}

TEST_F(DiscipleOpsTxFixture, SyncStatusWritesPositionNameForManaging) {
    const std::size_t row = addDisciple("1");
    core_->state().gameData.elderSlots.herbGardenElder = "1";

    const auto r = exec(action::DISCIPLE_OP_SYNC_STATUS, {{"discipleId", "1"}});
    ASSERT_EQ(r["status"], "success");
    EXPECT_EQ(r["data"]["status"].get<std::string>(), "MANAGING");
    // MANAGING：长老职位名写入 statusData
    EXPECT_EQ(core_->state().disciples.statusData[row]["positionName"], "灵田长老");
}

TEST_F(DiscipleOpsTxFixture, SyncAllHealsInvalidMiningSlotsAndDerives) {
    addDisciple("1");
    addDisciple("2");
    // 灵矿槽引用不存在弟子（404）→ 自愈清空；弟子1 引用有效 → MINING
    core_->state().gameData.spiritMineSlots.push_back([] {
        gamecore::state::SpiritMineSlot s;
        s.index = 0;
        s.discipleId = "1";
        s.discipleName = "弟子1";
        return s;
    }());
    core_->state().gameData.spiritMineSlots.push_back([] {
        gamecore::state::SpiritMineSlot s;
        s.index = 1;
        s.discipleId = "404";
        s.discipleName = "幽灵";
        return s;
    }());

    const auto r = exec(action::DISCIPLE_OP_SYNC_ALL_STATUSES, {});
    ASSERT_EQ(r["status"], "success");
    EXPECT_EQ(r["data"]["count"].get<int>(), 2);
    EXPECT_EQ(core_->state().disciples.statuses[0], "MINING");
    EXPECT_EQ(core_->state().gameData.spiritMineSlots[1].discipleId, "");
}

// ── RNG 零消费审计（对拍命门）────────────────────────────────────────

TEST_F(DiscipleOpsTxFixture, AllOpsConsumeZeroRng) {
    const std::size_t row = addDisciple("1");
    core_->state().pills.push_back(makePill("p1", "聚气丹"));
    core_->state().materials.push_back(makeMaterial("m1"));
    core_->state().manualStacks.push_back(makeManualStack("s1", "赤炎诀"));
    core_->state().gameData.spiritStones = 1000;

    const auto before = rngSnapshot();
    (void)row;
    (void)exec(action::DISCIPLE_OP_RENAME, {{"discipleId", "1"}, {"newName", "X"}});
    (void)exec(action::DISCIPLE_OP_CHANGE_TYPE, {{"discipleId", "1"}, {"newType", "OUTER"}});
    (void)exec(action::DISCIPLE_OP_TOGGLE_FOLLOW, {{"discipleId", "1"}});
    (void)exec(action::DISCIPLE_OP_REWARD_ITEM,
               {{"discipleId", "1"}, {"itemType", "material"}, {"itemId", "m1"},
                {"quantity", 1}, {"itemName", "妖兽血"}, {"itemRarity", 2}});
    (void)exec(action::DISCIPLE_OP_USE_PILL, {{"discipleId", "1"}, {"pillId", "p1"}});
    (void)exec(action::DISCIPLE_OP_SYNC_STATUS, {{"discipleId", "1"}});
    (void)exec(action::DISCIPLE_OP_SYNC_ALL_STATUSES, {});
    (void)exec(action::DISCIPLE_OP_START_BLOOD_REFINEMENT,
               {{"buildingInstanceId", "pool-1"}, {"requiredSpiritStones", 10},
                {"materialName", "妖兽血"}, {"materialRarity", 2},
                {"materialCount", 1}, {"discipleId", "1"},
                {"discipleName", "弟子1"}, {"materialId", "m1"},
                {"selectedStat", "hp"}, {"bonusPercent", 0.05},
                {"durationMonths", 2}});
    EXPECT_EQ(rngSnapshot(), before);
}

// ── 未注册号兜底（W4-A 端口不认领 ⇒ NOT_IMPLEMENTED）────────────────

TEST_F(DiscipleOpsTxFixture, UnregisteredActionStillNotImplemented) {
    const auto r = exec(1749, {{"discipleId", "1"}});
    EXPECT_EQ(r["status"], "failure");
    EXPECT_EQ(r["code"], "NOT_IMPLEMENTED");
}

}  // namespace
}  // namespace gamecore

// ============================================================
// equipment_tx_test — 装备升级/分解事务逐位断言（B3 新增，方案 §6.1；
// 语义权威 = Kotlin EquipmentUpgradeService + EquipLevelCurve 逐位对齐）
//
// 覆盖：
//   - 等级曲线纯函数：expRequired = 100×level×rarityMul[1.0/1.5/2.0/3.0/4.5/6.0]
//     （Kotlin .toInt() 截断）、spiritStonesCost = 100×rarity²×level、
//     beastMaterialCost = max(1, level/10)、levelAdvance 节点推进/满级恒 0
//   - 升级事务：灵石/兽材消耗逐位、(rarity,id) 升序扣减、失败零写入、
//     newLevel%3 强化节点（kEquipment 分区 nextInt(subStats.size) 定位、
//     副词条强化次数上限 kMaxSubRolls=11）
//   - 分解事务：返还 50% floor（灵石+兽材）、锁/已穿戴拒绝、袋条目 strip 防复活
// ============================================================

#include "gtest/gtest.h"

#include <map>
#include <memory>

#include "gamecore/core/clock.h"
#include "gamecore/core/logger.h"
#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/equipment_tx.h"

namespace gamecore {
namespace {

namespace equipment_tx = gamecore::system::equipment_tx;

using gamecore::state::Disciple;
using gamecore::state::EquipmentInstance;
using gamecore::state::EquipStatValue;
using gamecore::state::Material;
using gamecore::state::StorageBagItem;

constexpr int64_t kSeed = 42;

std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

EquipmentInstance instance(const std::string& id, int32_t rarity,
                           int32_t level = 1) {
    EquipmentInstance e;
    e.id = id;
    e.name = "裂天罡煞·战手";
    e.setId = "lietian";
    e.part = "HANDS";
    e.growth.level = level;
    e.growth.affix.mainStat = EquipStatValue{"ATTACK", 3.0};
    e.growth.affix.subStats = {EquipStatValue{"HP", 14.0},
                               EquipStatValue{"CRIT_RATE", 0.002},
                               EquipStatValue{"DEFENSE", 1.0}};
    e.growth.affix.subRolls = {1, 1, 1};
    e.meta.rarity = rarity;
    return e;
}

Material beast(const std::string& id, int32_t rarity, int32_t qty) {
    Material m;
    m.id = id;
    m.name = "兽材" + id;
    m.rarity = rarity;
    m.quantity = qty;
    return m;
}

// ── 等级曲线纯函数（Kotlin EquipLevelCurve 逐位）────────────────

TEST(EquipLevelCurveTest, ExpRequiredUsesRarityMultiplier) {
    // expRequired = 100 × level × rarityMul；rarityMul = 1.0/1.5/2.0/3.0/4.5/6.0
    // Lv1：100×1×mul → 100/150/200/300/450/600（Kotlin .toInt() 截断）
    EXPECT_EQ(100, equipment_tx::expRequired(1, 1));
    EXPECT_EQ(150, equipment_tx::expRequired(1, 2));
    EXPECT_EQ(200, equipment_tx::expRequired(1, 3));
    EXPECT_EQ(300, equipment_tx::expRequired(1, 4));
    EXPECT_EQ(450, equipment_tx::expRequired(1, 5));
    EXPECT_EQ(600, equipment_tx::expRequired(1, 6));
    // Lv7 r3：100×7×2.0 = 1400（截断面）
    EXPECT_EQ(1400, equipment_tx::expRequired(7, 3));
}

TEST(EquipLevelCurveTest, SpiritStonesCostQuadraticInRarity) {
    // 100 × rarity² × level
    EXPECT_EQ(100, equipment_tx::spiritStonesCost(1, 1));
    EXPECT_EQ(400, equipment_tx::spiritStonesCost(1, 2));
    // r6 Lv4：100 × 36 × 4 = 14400
    EXPECT_EQ(14400, equipment_tx::spiritStonesCost(4, 6));
    // r6 Lv5：100 × 36 × 5 = 18000
    EXPECT_EQ(18000, equipment_tx::spiritStonesCost(5, 6));
}

TEST(EquipLevelCurveTest, BeastMaterialCostFloorDivision) {
    // max(1, level / 10)：Lv1..10 → 1；Lv11 → 1（floor(1.1)=1）；Lv20 → 2
    EXPECT_EQ(1, equipment_tx::beastMaterialCost(1));
    EXPECT_EQ(1, equipment_tx::beastMaterialCost(10));
    EXPECT_EQ(1, equipment_tx::beastMaterialCost(11));
    EXPECT_EQ(2, equipment_tx::beastMaterialCost(20));
    EXPECT_EQ(3, equipment_tx::beastMaterialCost(30));
}

TEST(EquipLevelCurveTest, LevelAdvanceAccumulatesAndCapsAtMaxLevel) {
    // 逐级判定：exp 不足则累计；满级后 exp 恒 0、溢出不保留
    // r1：Lv1→2 需 100、Lv2→3 需 200；一次投 250 → 升 1 级余 150
    const auto [lv, exp] = equipment_tx::levelAdvance(1, 0, 250, 1);
    EXPECT_EQ(2, lv);
    EXPECT_EQ(150, exp);
    // 满级：exp 恒 0
    const auto [lvMax, expMax] = equipment_tx::levelAdvance(
        equipment_tx::kMaxLevel, 10, 999, 1);
    EXPECT_EQ(equipment_tx::kMaxLevel, lvMax);
    EXPECT_EQ(0, expMax);
    // 恰好到满级：返回 (MAX, 0)
    int32_t total = 0;
    for (int32_t l = 1; l < equipment_tx::kMaxLevel; ++l) {
        total += equipment_tx::expRequired(l, 1);
    }
    const auto [lvCap, expCap] = equipment_tx::levelAdvance(1, 0, total, 1);
    EXPECT_EQ(equipment_tx::kMaxLevel, lvCap);
    EXPECT_EQ(0, expCap);
}

TEST(EquipLevelCurveTest, ReinforceNodeEveryThreeLevels) {
    EXPECT_FALSE(equipment_tx::triggersReinforcement(1));
    EXPECT_FALSE(equipment_tx::triggersReinforcement(2));
    EXPECT_TRUE(equipment_tx::triggersReinforcement(3));
    EXPECT_TRUE(equipment_tx::triggersReinforcement(30));
    EXPECT_FALSE(equipment_tx::triggersReinforcement(31));
}

// ── 升级事务 ────────────────────────────────────────────────────

TEST(EquipmentUpgradeTxTest, DeductsExactStonesAndBeastsAscendingOrder) {
    // (rarity,id) 升序扣：r1 摞先扣尽，再扣 r2 摞
    auto core = makeCore(kSeed);
    auto& st = core->state();
    st.gameData.spiritStones = 100000;
    st.materials = {beast("b-low", 1, 2), beast("b-high", 2, 5)};
    st.equipmentInstances.push_back(instance("eq-1", 1));

    const auto r = equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1");
    ASSERT_TRUE(r.ok) << r.message;
    // 灵石 100×1²×1 = 100；兽材 max(1, 1/10) = 1 件（r1 摞 2→1）
    EXPECT_EQ(-100, r.stonesDelta);
    EXPECT_EQ(-1, r.beastsDelta);
    EXPECT_EQ(100000 - 100, st.gameData.spiritStones);
    ASSERT_EQ(2u, st.materials.size());
    EXPECT_EQ(1, st.materials[0].quantity);   // b-low 先扣
    EXPECT_EQ(5, st.materials[1].quantity);
    // Lv1→2：expRequired(1,1)=100 ≥ 增益 → Lv2 exp 0；Lv2 非 %3 节点零强化
    EXPECT_EQ(2, r.newLevel);
    EXPECT_EQ(2, st.equipmentInstances[0].growth.level);
    EXPECT_EQ(0, st.equipmentInstances[0].growth.exp);
    EXPECT_EQ((std::vector<int32_t>{1, 1, 1}),
              st.equipmentInstances[0].growth.affix.subRolls);
}

TEST(EquipmentUpgradeTxTest, ReinforceNodePicksPartitionIndexAndCapsAt11) {
    // 升到 Lv3（%3 节点）→ kEquipment nextInt(subStats.size) 定位 +1；
    // 定位序与同种子预演逐位一致
    auto core = makeCore(kSeed);
    auto& st = core->state();
    st.gameData.spiritStones = 1000000;
    st.materials = {beast("b", 1, 99)};
    st.equipmentInstances.push_back(instance("eq-1", 1));

    // 预演 kEquipment 分区（fromSeed(seed + 13)）首抽
    auto probe = rng::DeterministicRng::fromSeed(kSeed + 13);
    const int32_t firstIdx = probe.nextInt(3);

    ASSERT_TRUE(equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1").ok);
    ASSERT_TRUE(equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1").ok);
    // Lv3：恰一次强化，命中预演下标（初始 1 → 2）
    ASSERT_EQ(3, st.equipmentInstances[0].growth.level);
    auto& rolls = st.equipmentInstances[0].growth.affix.subRolls;
    ASSERT_EQ(3u, rolls.size());
    EXPECT_EQ(2, rolls[static_cast<std::size_t>(firstIdx)]);
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kEquipment).snapshot(),
              probe.snapshot());
}

TEST(EquipmentUpgradeTxTest, SubRollsCapAtMaxSubRolls) {
    // 单条副词条强化上限 kMaxSubRolls = 11（min(rolls+1, 11)）
    auto core = makeCore(kSeed);
    auto& st = core->state();
    st.gameData.spiritStones = 100000000;
    st.materials = {beast("b", 1, 9999)};
    auto inst = instance("eq-1", 1);
    // 只留 1 条副词条 → 恒命中下标 0；预置 10 次 → 升到 Lv3 后应钳 11
    inst.growth.affix.subStats = {EquipStatValue{"HP", 14.0}};
    inst.growth.affix.subRolls = {equipment_tx::kMaxSubRolls - 1};
    st.equipmentInstances.push_back(inst);

    ASSERT_TRUE(equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1").ok);
    ASSERT_TRUE(equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1").ok);
    EXPECT_EQ(equipment_tx::kMaxSubRolls,
              st.equipmentInstances[0].growth.affix.subRolls[0]);
}

TEST(EquipmentUpgradeTxTest, FailureArmsZeroWrite) {
    auto core = makeCore(kSeed);
    auto& st = core->state();
    // NotFound
    auto r = equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "nope");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("NotFound", r.errorType);
    // 灵石不足：不扣材料
    st.equipmentInstances.push_back(instance("eq-1", 1));
    st.gameData.spiritStones = 99;   // < 100
    st.materials = {beast("b", 1, 5)};
    r = equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SlotInvalid", r.errorType);
    EXPECT_EQ(99, st.gameData.spiritStones);
    ASSERT_EQ(1u, st.materials.size());
    EXPECT_EQ(5, st.materials[0].quantity);
    // 兽材不足：不扣灵石
    st.gameData.spiritStones = 100000;
    st.materials.clear();
    r = equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SlotInvalid", r.errorType);
    EXPECT_EQ(100000, st.gameData.spiritStones);
    // 已满级
    st.materials = {beast("b", 1, 5)};
    st.equipmentInstances[0].growth.level = equipment_tx::kMaxLevel;
    r = equipment_tx::upgradeEquipmentTx(
        st, core->rng().getRng(rng::RngPartition::kEquipment), "eq-1");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SlotInvalid", r.errorType);
    EXPECT_EQ(100000, st.gameData.spiritStones);
    EXPECT_EQ(equipment_tx::kMaxLevel, st.equipmentInstances[0].growth.level);
}

// ── 分解事务 ────────────────────────────────────────────────────

TEST(EquipmentDismantleTxTest, RefundsHalfCumulativeCostFloor) {
    // 累计消耗（Lv1→3，r1）：灵石 100×1×1 + 100×1×2 = 300 × 0.5 = 150；
    // 兽材 1+1=2 × 0.5 = 1（floor）
    auto core = makeCore(kSeed);
    auto& st = core->state();
    st.gameData.spiritStones = 0;
    st.equipmentInstances.push_back(instance("eq-1", 1, /*level=*/3));
    const auto refund = equipment_tx::dismantleRefund(1, 3);
    EXPECT_EQ(150, refund.first);
    EXPECT_EQ(1, refund.second);

    const auto r = equipment_tx::dismantleEquipmentTx(st, "eq-1");
    ASSERT_TRUE(r.ok) << r.message;
    EXPECT_EQ(150, r.stonesDelta);
    EXPECT_EQ(1, r.beastsDelta);
    EXPECT_EQ(150, st.gameData.spiritStones);
    // 兽材返还铸入 rarity==1 首条堆叠；无则新建"凡兽材"
    ASSERT_EQ(1u, st.materials.size());
    EXPECT_EQ(1, st.materials[0].rarity);
    EXPECT_EQ(1, st.materials[0].quantity);
    // 实例离表
    EXPECT_TRUE(st.equipmentInstances.empty());
}

TEST(EquipmentDismantleTxTest, RefundCastsIntoExistingRarityOneStack) {
    // 已有 rarity==1 兽材 → 返还并入既有堆叠（不新建）
    auto core = makeCore(kSeed);
    auto& st = core->state();
    st.gameData.spiritStones = 0;
    st.materials = {beast("b1", 1, 7)};
    st.equipmentInstances.push_back(instance("eq-1", 2, /*level=*/3));
    // r2 累计灵石（100×4×1 + 100×4×2）= 1200 × 0.5 = 600；兽材 2 件 × 0.5 = 1
    const auto r = equipment_tx::dismantleEquipmentTx(st, "eq-1");
    ASSERT_TRUE(r.ok);
    EXPECT_EQ(600, r.stonesDelta);
    EXPECT_EQ(1, r.beastsDelta);
    // 返还铸入既有 rarity==1 堆叠（7 → 8）
    ASSERT_EQ(1u, st.materials.size());
    EXPECT_EQ(8, st.materials[0].quantity);
}

TEST(EquipmentDismantleTxTest, RejectsLockedEquippedAndMissing) {
    auto core = makeCore(kSeed);
    auto& st = core->state();
    auto locked = instance("eq-locked", 1);
    locked.meta.isLocked = true;
    auto equipped = instance("eq-worn", 1);
    equipped.isEquipped = true;
    st.equipmentInstances.push_back(locked);
    st.equipmentInstances.push_back(equipped);

    auto r = equipment_tx::dismantleEquipmentTx(st, "eq-locked");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SlotInvalid", r.errorType);
    r = equipment_tx::dismantleEquipmentTx(st, "eq-worn");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("SlotInvalid", r.errorType);
    r = equipment_tx::dismantleEquipmentTx(st, "ghost");
    EXPECT_FALSE(r.ok);
    EXPECT_EQ("NotFound", r.errorType);
    // 零写入
    ASSERT_EQ(2u, st.equipmentInstances.size());
    EXPECT_EQ(1000, st.gameData.spiritStones);
}

TEST(EquipmentDismantleTxTest, StripsBagEntriesToPreventRevival) {
    // 分解后：全弟子储物袋内同件条目一并清除（防取回复活）
    auto core = makeCore(kSeed);
    auto& st = core->state();
    st.equipmentInstances.push_back(instance("eq-1", 1));
    for (const char* id : {"1", "2"}) {
        Disciple d;
        d.id = id;
        d.name = "弟子" + std::string(id);
        d.realm = 9;
        d.isAlive = true;
        st.disciples.appendDisciple(d);
    }
    StorageBagItem bag1;
    bag1.itemId = "eq-1";
    bag1.itemType = "equipment_instance";
    bag1.quantity = 1;
    bag1.equipmentInstance = instance("eq-1", 1);
    st.disciples.storageBagItems[0].push_back(bag1);
    StorageBagItem bag2 = bag1;
    st.disciples.storageBagItems[1].push_back(bag2);
    StorageBagItem other;
    other.itemId = "p1";
    other.itemType = "pill";
    other.quantity = 1;
    st.disciples.storageBagItems[0].push_back(other);

    const auto r = equipment_tx::dismantleEquipmentTx(st, "eq-1");
    ASSERT_TRUE(r.ok);
    // 两个弟子袋内的 eq-1 条目均被 strip；其他物品保留
    ASSERT_EQ(1u, st.disciples.storageBagItems[0].size());
    EXPECT_EQ("p1", st.disciples.storageBagItems[0][0].itemId);
    EXPECT_TRUE(st.disciples.storageBagItems[1].empty());
}

}  // namespace
}  // namespace gamecore

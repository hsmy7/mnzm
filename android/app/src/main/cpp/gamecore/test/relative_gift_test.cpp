// ============================================================
// relative_gift_test — 师徒智能赠送单测 + 旬结算集成
//
// 守护目标：
//   1. relative_gift.h 纯函数语义（师徒查找插序 / 关系分类 /
//      选品优先级 / 袋转移合并）
//   2. 旬结算集成：突破（层变即可）触发师徒赠送（SYSTEM RNG 逐师徒一次
//      概率抽取，先于选品；跨师徒累计序 = 插序）
//
// RNG 审计方法：与 phase_settlement_test 同源——RngManager.initSystemSeed
// 按 fromSeed(seed + partitionId) 播种（kSystem=3 / kBreakthrough=1），
// 测试用独立 DeterministicRng 预演同种子序列，锁定抽取次数与顺序。
// ============================================================

#include "gtest/gtest.h"

#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/phase_settlement.h"
#include "gamecore/system/relative_gift.h"

namespace {

using namespace gamecore;
using gamecore::rng::DeterministicRng;
using gamecore::rng::RngManager;
using gamecore::state::Disciple;
using gamecore::state::EquipmentStack;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::StorageBagItem;

constexpr int32_t kSystemPartition = 3;      // RngPartition::kSystem
constexpr int32_t kBreakthroughPartition = 1;

std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 最小存活弟子（炼气一层，IDLE，无师徒关系）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.status = "IDLE";
    return d;
}

StorageBagItem herbItem(const std::string& itemId, int32_t rarity,
                        int32_t quantity) {
    StorageBagItem item;
    item.itemId = itemId;
    item.itemType = "herb";
    item.name = "灵草" + itemId;
    item.rarity = rarity;
    item.quantity = quantity;
    item.obtainedYear = 1;
    item.obtainedMonth = 1;
    return item;
}

// ── 师徒查找（插序 = 抽取序） ──────────────────────────────────────

TEST(RelativeGiftFindTest, ForwardThenReverseInsertionOrder) {
    auto core = makeCore(42);
    auto& st = core->state();
    // 行序 0..4：1(本人) 2(师父) 3(徒弟) 4(同门) 5(徒弟)
    for (const char* id : {"1", "2", "3", "4", "5"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";      // 1 的师父 = 2
    st.disciples.masterIds[2] = "1";      // 3 的师父 = 1 → 徒弟
    st.disciples.masterIds[3] = "2";      // 4 的师父 = 2 → 同门，非本人师徒
    st.disciples.masterIds[4] = "1";      // 5 的师父 = 1 → 徒弟

    const auto idx = system::relative_gift::detail::rowIndex(st.disciples);
    const auto relatives = system::relative_gift::findRelatives(
        st.disciples, 1, idx);
    // 正向：师父(2)；反向一次遍历按行序补徒弟：3、5（同门 4 不入集合）
    ASSERT_EQ(relatives.size(), std::size_t{3});
    const int32_t expected[] = {2, 3, 5};
    for (std::size_t i = 0; i < relatives.size(); ++i) {
        EXPECT_EQ(relatives[i], expected[i]) << "index " << i;
    }
}

TEST(RelativeGiftFindTest, DeadAndSelfExcluded) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";
    st.disciples.isAlive[1] = 0;          // 师父死亡 → 排除
    const auto idx = system::relative_gift::detail::rowIndex(st.disciples);
    const auto relatives = system::relative_gift::findRelatives(
        st.disciples, 1, idx);
    EXPECT_TRUE(relatives.empty());
}

TEST(RelativeGiftFindTest, DeadApprenticeExcluded) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[1] = "1";      // 2 的师父 = 1 → 徒弟
    st.disciples.isAlive[1] = 0;          // 徒弟死亡 → 反向遍历排除
    const auto idx = system::relative_gift::detail::rowIndex(st.disciples);
    EXPECT_TRUE(system::relative_gift::findRelatives(
        st.disciples, 1, idx).empty());
}

// ── 关系分类（师父 / 徒弟） ────────────────────────────────────────

TEST(RelativeGiftClassifyTest, MasterClassifiedByReceiverMasterId) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";      // 1 的师父 = 2 → giver 2 是师父
    const auto idx = system::relative_gift::detail::rowIndex(st.disciples);
    EXPECT_EQ(system::relative_gift::classifyRelationship(st.disciples, 2, 1,
                                                          idx),
              system::relative_gift::GiftRelationshipType::kMaster);
}

TEST(RelativeGiftClassifyTest, ApprenticeClassifiedByGiverMasterId) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[1] = "1";      // 2 的师父 = 1 → giver 2 是徒弟
    using R = system::relative_gift::GiftRelationshipType;
    const auto idx = system::relative_gift::detail::rowIndex(st.disciples);
    EXPECT_EQ(system::relative_gift::classifyRelationship(st.disciples, 2, 1,
                                                          idx), R::kApprentice);
}

TEST(RelativeGiftClassifyTest, ProbabilityMatchesRelationship) {
    using R = system::relative_gift::GiftRelationshipType;
    EXPECT_DOUBLE_EQ(system::relative_gift::giftProbability(R::kMaster), 0.40);
    EXPECT_DOUBLE_EQ(system::relative_gift::giftProbability(R::kApprentice),
                     0.30);
}

// ── 选品优先级 ────────────────────────────────────────────────────

TEST(RelativeGiftSelectTest, EquipmentBeatsManualBeatsPillBeatsHerb) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    // 接收者 1：空武器槽 + 空功法槽
    EquipmentStack eqStack;
    eqStack.id = "eq-1";
    eqStack.name = "青锋剑";
    eqStack.slot = "WEAPON";
    eqStack.rarity = 3;
    eqStack.minRealm = 9;
    st.equipmentStacks.push_back(eqStack);
    // giver 2 的袋子： herb(优先级5) < 其他丹(4) < 功法(2) < 装备(1)
    StorageBagItem herb = herbItem("h-1", 5, 1);
    StorageBagItem pill;
    pill.itemId = "p-1";
    pill.itemType = "pill";
    pill.rarity = 3;
    pill.quantity = 1;
    StorageBagItem mnStack;
    mnStack.itemId = "mn-1";
    mnStack.itemType = "manual_stack";
    mnStack.rarity = 2;
    mnStack.quantity = 1;
    StorageBagItem eqBag;
    eqBag.itemId = "eq-1";
    eqBag.itemType = "equipment_stack";
    eqBag.rarity = 1;
    eqBag.quantity = 1;
    st.disciples.storageBagItems[1] = {herb, pill, mnStack, eqBag};

    const auto selected = system::relative_gift::selectBestGift(
        st.disciples.storageBagItems[1], st.disciples, /*receiverRow=*/0,
        /*receiverRealm=*/9, st);
    ASSERT_TRUE(selected.has_value());
    EXPECT_EQ(selected->itemId, "eq-1");   // 装备最高优先
}

TEST(RelativeGiftSelectTest, ManualExcludedWhenLearnedSameName) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    gamecore::state::ManualInstance learned;
    learned.id = "m-inst-1";
    learned.name = "青云心法";
    learned.minRealm = 9;
    st.manualInstances.push_back(learned);
    st.disciples.manualIds[0] = {"m-inst-1"};   // 接收者已学会

    gamecore::state::ManualStack mnStack;
    mnStack.id = "mn-1";
    mnStack.name = "青云心法";                 // 同名 → 排除
    mnStack.minRealm = 9;
    mnStack.rarity = 2;
    st.manualStacks.push_back(mnStack);
    StorageBagItem mnBag;
    mnBag.itemId = "mn-1";
    mnBag.itemType = "manual_stack";
    mnBag.rarity = 2;
    mnBag.quantity = 1;
    StorageBagItem herb = herbItem("h-1", 1, 1);
    st.disciples.storageBagItems[1] = {mnBag, herb};

    const auto selected = system::relative_gift::selectBestGift(
        st.disciples.storageBagItems[1], st.disciples, 0, 9, st);
    ASSERT_TRUE(selected.has_value());
    EXPECT_EQ(selected->itemId, "h-1");    // 功法被排除 → 草药兜底
}

// ── 赠送执行（袋转移） ────────────────────────────────────────────

TEST(RelativeGiftGiveTest, TransferMovesQuantityAndMerges) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    // giver 2 袋 ≥2 条目（Kotlin MIN_BAG_ITEMS_TO_KEEP=1 按条目数守卫）：
    // 灵草 h-1×2（与接收者已有同 id 灵草×1 合并 → ×2）+ h-2 兜底条目
    st.disciples.storageBagItems[1] = {herbItem("h-1", 4, 2),
                                       herbItem("h-2", 1, 1)};
    st.disciples.storageBagItems[0] = {herbItem("h-1", 4, 1)};

    const auto idx =
        system::relative_gift::detail::rowIndex(st.disciples);
    const auto result = system::relative_gift::tryGiveGift(
        st, idx, /*giver=*/2, /*receiver=*/1, /*receiverRealm=*/9);
    EXPECT_EQ(result, system::relative_gift::GiftResult::kSuccess);
    // 选品 = 品阶最高（h-1 r4 > h-2 r1）→ h-1 数量 2-1；接收者 1+1 合并 = 2
    ASSERT_EQ(st.disciples.storageBagItems[1].size(), std::size_t{2});
    EXPECT_EQ(st.disciples.storageBagItems[1][0].quantity, 1);
    ASSERT_EQ(st.disciples.storageBagItems[0].size(), std::size_t{1});
    EXPECT_EQ(st.disciples.storageBagItems[0][0].quantity, 2);
}

TEST(RelativeGiftGiveTest, BagTooSmallAndBagEmpty) {
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2", "3"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.storageBagItems[1] = {herbItem("h-1", 4, 1)};   // 仅 1 件
    st.disciples.storageBagItems[2] = {};                        // 空

    const auto idx = system::relative_gift::detail::rowIndex(st.disciples);
    EXPECT_EQ(system::relative_gift::tryGiveGift(st, idx, 2, 1, 9),
              system::relative_gift::GiftResult::kBagTooSmall);
    EXPECT_EQ(system::relative_gift::tryGiveGift(st, idx, 3, 1, 9),
              system::relative_gift::GiftResult::kBagEmpty);
}

// ── 主入口：概率抽取门控（每师徒恰一次 SYSTEM nextDouble） ──────────

TEST(RelativeGiftProcessTest, DrawConsumedRegardlessOfOutcome) {
    // 探测种子：first SYSTEM draw ≥ 0.40（师父概率）→ 不赠送但抽取已消耗
    const int64_t seed = []() {
        for (int64_t s = 1;; ++s) {
            auto probe = DeterministicRng::fromSeed(s + kSystemPartition);
            if (probe.nextDouble() >= 0.40) return s;
        }
    }();
    auto core = makeCore(seed);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";      // 2 是 1 的师父
    st.disciples.storageBagItems[1] = {herbItem("h-1", 4, 3),
                                       herbItem("h-2", 1, 1)};

    RngManager rng;
    rng.initSystemSeed(seed);
    const auto before = rng.getRng(rng::RngPartition::kSystem).snapshot();
    system::relative_gift::processGiftsForBreakthrough(
        st, 1, rng.getRng(rng::RngPartition::kSystem));
    const auto after = rng.getRng(rng::RngPartition::kSystem).snapshot();
    EXPECT_NE(before, after);              // 概率抽取已发生
    EXPECT_EQ(st.disciples.storageBagItems[0].size(), std::size_t{0});
    EXPECT_EQ(st.disciples.storageBagItems[1][0].quantity, 3);   // 未转移
}

TEST(RelativeGiftProcessTest, SuccessTransfersGift) {
    // 探测种子：first SYSTEM draw < 0.40 → 赠送成功
    const int64_t seed = []() {
        for (int64_t s = 1;; ++s) {
            auto probe = DeterministicRng::fromSeed(s + kSystemPartition);
            if (probe.nextDouble() < 0.40) return s;
        }
    }();
    auto core = makeCore(seed);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";      // 2 是 1 的师父
    st.disciples.storageBagItems[1] = {herbItem("h-1", 4, 3),
                                       herbItem("h-2", 1, 1)};

    RngManager rng;
    rng.initSystemSeed(seed);
    system::relative_gift::processGiftsForBreakthrough(
        st, 1, rng.getRng(rng::RngPartition::kSystem));
    ASSERT_EQ(st.disciples.storageBagItems[0].size(), std::size_t{1});
    EXPECT_EQ(st.disciples.storageBagItems[0][0].itemId, "h-1");
    EXPECT_EQ(st.disciples.storageBagItems[0][0].quantity, 1);
    EXPECT_EQ(st.disciples.storageBagItems[1][0].quantity, 2);   // 3-1
}

// ── 旬结算集成：突破（层变即可）触发师徒赠送 ────────────────────────

TEST(PhaseSettlementGiftTest, LayerBreakthroughTriggersGiftFromMaster) {
    // 双探测：BREAKTHROUGH 首抽 < 0.90（炼气一层基础概率）→ 突破成功（层+1）；
    // SYSTEM 首抽 < 0.40（师父概率）→ 师父赠送灵草。
    int64_t seed = 1;
    for (;; ++seed) {
        auto b = DeterministicRng::fromSeed(seed + kBreakthroughPartition);
        if (b.nextDouble() >= 0.90) continue;
        auto s = DeterministicRng::fromSeed(seed + kSystemPartition);
        if (s.nextDouble() < 0.40) break;
    }
    auto core = makeCore(seed);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";      // 2 是 1 的师父
    st.disciples.storageBagItems[1] = {herbItem("h-1", 4, 2),
                                       herbItem("h-2", 1, 1)};
    st.disciples.cultivations[0] = 490.0;  // 满（maxCult(9,1)=490）
    st.disciples.currentHps[0] = -1;       // 哨兵 = 满
    st.disciples.currentMps[0] = -1;

    core->advancePhases(1);

    // 突破成功：层数 1→2（层变即触发赠送；大境界日志仅 realm 变化才记）
    EXPECT_EQ(st.disciples.realmLayers[0], 2);
    // 师父袋 h-1 -1、接收者袋 h-1 +1
    ASSERT_EQ(st.disciples.storageBagItems[1].size(), std::size_t{2});
    EXPECT_EQ(st.disciples.storageBagItems[1][0].quantity, 1);
    ASSERT_EQ(st.disciples.storageBagItems[0].size(), std::size_t{1});
    EXPECT_EQ(st.disciples.storageBagItems[0][0].itemId, "h-1");
    EXPECT_EQ(st.disciples.storageBagItems[0][0].quantity, 1);
}

TEST(PhaseSettlementGiftTest, NoBreakthroughNoGiftNoSystemDraw) {
    // 修为不满 → 无突破候选 → 无赠送、SYSTEM 分区零消耗
    auto core = makeCore(42);
    auto& st = core->state();
    for (const char* id : {"1", "2"}) {
        st.disciples.appendDisciple(baseDisciple(id));
    }
    st.disciples.masterIds[0] = "2";      // 2 是 1 的师父
    st.disciples.storageBagItems[1] = {herbItem("h-1", 4, 2)};

    RngManager probe;
    probe.initSystemSeed(42);

    core->advancePhases(1);

    EXPECT_EQ(st.disciples.realmLayers[0], 1);   // 未突破
    EXPECT_TRUE(st.disciples.storageBagItems[0].empty());
    EXPECT_EQ(st.disciples.storageBagItems[1][0].quantity, 2);
    // SYSTEM 分区快照与"零抽取"一致（月/年边界未跨，SYSTEM 无其他消耗点）
    EXPECT_EQ(st.gameData.rngStates[static_cast<int>(rng::RngPartition::kSystem)],
              probe.getRng(rng::RngPartition::kSystem).snapshot());
}

}  // namespace

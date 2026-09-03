// ============================================================
// phase_settlement_test — 每旬弟子结算黄金序列守护（T2.1）
//
// 守护目标：固定种子 + 固定状态 → SettlementEngine.onPhaseSettle
// （runPhaseSettlement）推进 N 旬 → 断言字段值序列逐位符合手算期望。
//
// 覆盖：修炼累积上限钳制 / HP·MP 恢复 / 功法熟练度（含藏经阁加成）/
// 装备孕养升级 / 自动丹药补服（含 checkpoint）/ 突破成功与失败双分支
// （RNG 抽取次数与顺序强校验）/ 秘境成员跳过 / 死亡弟子跳过 / 空状态安全。
//
// RNG 审计方法：BREAKTHROUGH 分区播种规则 fromSeed(seed + partitionId)
// （kBreakthrough=1）与 RngManager.initSystemSeed 一致——测试用独立
// DeterministicRng 预演同种子序列，断言结算后的分区快照精确等于
// "连续 K 次 nextDouble"后的快照（K = 候选数 × 尝试次数），同时锁定
// 抽取次数与顺序（对拍确定性的命门）。
// ============================================================

#include "gtest/gtest.h"

#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/json_codec.h"
#include "gamecore/system/phase_settlement.h"

namespace {

using namespace gamecore;
using gamecore::state::Disciple;
using gamecore::state::EquipmentInstance;
using gamecore::state::GameData;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::state::ManualProficiencyData;
using gamecore::state::StorageBagItem;

/// 构建已初始化 GameCore（钩子已注册），种子固定
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 填充一个最小存活弟子（炼气一层）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 16;
    d.lifespan = 80;
    return d;
}

TEST(PhaseSettlementTest, CultivationAccumulatesWithCapGoldenSequence) {
    // rate = 19.0（炼气单灵根、无任何乘区）；maxCult(9,1) = 98
    // 序列：50→69→88→min(107,98)=98。
    // 设非满血 HP/MP：避免第三旬累积到满值后当旬触发突破（该交互由
    // BreakthroughTriggersSamePhaseAsReachingCap 单独守护）
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.disciples.cultivations[0] = 50.0;
    // 低血量：三旬恢复窗口内 HP/MP 恒不满（0→40→80→120 / 0→15→30→45），
    // 防止第三旬累积到满值后当旬触发突破（该交互由
    // BreakthroughTriggersSamePhaseAsReachingCap 单独守护）
    st.disciples.currentHps[0] = 0;
    st.disciples.currentMps[0] = 0;

    const double seq[] = {69.0, 88.0, 98.0};
    for (int i = 0; i < 3; ++i) {
        core->advancePhases(1);
        EXPECT_DOUBLE_EQ(seq[i], st.disciples.materialize(0).cultivation) << "phase " << i;
    }
}

TEST(PhaseSettlementTest, BreakthroughTriggersSamePhaseAsReachingCap) {
    // 组合语义守护：每旬先累积后突破检测——修为当旬达到满值即当旬尝试突破。
    // seed=42 首抽 0.9629 ≥ 0.90 → 失败分支（确定性）。
    // 旬内轨迹：80 +19 → clamp 98（满）→ 候选成立 → 抽卡失败 → 修为清零 +
    // HP/MP 折算（基础口径 max×0.1 至少 1）+ checkpoint=写回前 live 值(98)
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 80.0;
    d.currentHp = -1;   // -1 = 满（血量哨兵）
    d.currentMp = -1;
    st.disciples.appendDisciple(d);

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    EXPECT_DOUBLE_EQ(0.0, after.cultivation);
    EXPECT_EQ(0, after.breakthroughCount);
    EXPECT_EQ(1, after.breakthroughFailCount);
    EXPECT_EQ(20, after.currentHp);
    EXPECT_EQ(7, after.currentMp);
    EXPECT_DOUBLE_EQ(98.0, after.cultivationCheckpoint);
}

TEST(PhaseSettlementTest, HpMpRecoveryGoldenSequence) {
    // 炼气一层方差 0：maxHp=203 maxMp=78；恢复量 = (max*0.2).toInt() 至少 1
    // → hp+40 / mp+15；超限钳制
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.disciples.currentHps[0] = 100;
    st.disciples.currentMps[0] = 50;

    struct Exp { int hp; int mp; };
    const Exp seq[] = {{140, 65}, {180, 78}, {203, 78}};
    for (int i = 0; i < 3; ++i) {
        core->advancePhases(1);
        EXPECT_EQ(seq[i].hp, st.disciples.materialize(0).currentHp) << "phase " << i;
        EXPECT_EQ(seq[i].mp, st.disciples.materialize(0).currentMp) << "phase " << i;
    }
}

TEST(PhaseSettlementTest, ManualProficiencyBatchCommitGoldenSequence) {
    // gain = 6 × (1+藏经阁0.5|0) × 2s = 12 或 18；MAX 30000；mastery 阈值 1000
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple plain = baseDisciple("1");
    plain.manualIds = {"m1"};
    Disciple scholar = baseDisciple("2");
    scholar.manualIds = {"m1"};
    st.disciples.appendDisciple(plain);
    st.disciples.appendDisciple(scholar);
    st.gameData.librarySlots.push_back(
        {0, "", "2", "弟子2"});   // 弟子2 在藏经阁

    ManualInstance manual;
    manual.id = "m1";
    manual.name = "青云心法";
    st.manualInstances.push_back(manual);

    core->advancePhases(2);
    const auto& gdProf = st.gameData.manualProficiencies;
    ASSERT_EQ(1, gdProf.count("1"));
    ASSERT_EQ(1, gdProf.count("2"));
    ASSERT_EQ(1u, gdProf.at("1").size());
    EXPECT_DOUBLE_EQ(24.0, gdProf.at("1")[0].proficiency);       // 12 × 2 旬
    EXPECT_EQ(0, gdProf.at("1")[0].masteryLevel);
    EXPECT_DOUBLE_EQ(36.0, gdProf.at("2")[0].proficiency);       // 18 × 2 旬
    EXPECT_STREQ("青云心法", gdProf.at("2")[0].manualName.c_str());
}

TEST(PhaseSettlementTest, EquipmentNurtureLevelsUpGoldenSequence) {
    // gain = 10/旬；rarity1 level0 升级需 100 → 第 10 旬升级且进度清零
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.weaponId = "w1";
    st.disciples.appendDisciple(d);

    EquipmentInstance eq;
    eq.id = "w1";
    eq.name = "木剑";
    eq.rarity = 1;
    st.equipmentInstances.push_back(eq);

    for (int i = 1; i <= 9; ++i) {
        core->advancePhases(1);
        EXPECT_EQ(0, st.equipmentInstances[0].nurtureLevel) << "phase " << i;
        EXPECT_DOUBLE_EQ(10.0 * i, st.equipmentInstances[0].nurtureProgress);
    }
    core->advancePhases(1);
    EXPECT_EQ(1, st.equipmentInstances[0].nurtureLevel);
    EXPECT_DOUBLE_EQ(0.0, st.equipmentInstances[0].nurtureProgress);
}

TEST(PhaseSettlementTest, AutoPillConsumptionAndCheckpoint) {
    // cultivationAdd 丹药：服用 +30（clamp 上限）、扣袋、checkpoint 同步
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 10.0;

    StorageBagItem pillItem;
    pillItem.itemId = "p1";
    pillItem.itemType = "pill";
    pillItem.name = "聚气丹";
    pillItem.rarity = 1;
    pillItem.effect = state::ItemEffect{};
    pillItem.effect->pillType = "cultivationAdd";
    pillItem.effect->cultivationAdd = 30;
    pillItem.effect->minRealm = 9;
    d.storageBagItems.push_back(pillItem);
    st.disciples.appendDisciple(d);

    core->advancePhases(1);
    // 结算顺序：先恢复/累积（10+19=29）→ 丹药 +30 → 59
    EXPECT_DOUBLE_EQ(59.0, st.disciples.materialize(0).cultivation);
    EXPECT_TRUE(st.disciples.materialize(0).storageBagItems.empty());
    // checkpoint 读写回后的修为；绝对月份 = 年1月1 → 13
    EXPECT_DOUBLE_EQ(59.0, st.disciples.materialize(0).cultivationCheckpoint);
    EXPECT_EQ(13, st.disciples.materialize(0).cultivationCheckpointGameMonth);

    // 第二旬：袋子已空，不再服用（仅修炼累积 59+19=78）
    core->advancePhases(1);
    EXPECT_DOUBLE_EQ(78.0, st.disciples.materialize(0).cultivation);
}

TEST(PhaseSettlementTest, BreakthroughBothBranchesAndRngAudit) {
    // 满修为满血弟子：chance = breakthroughChance(9, 单灵根, 层1) = 0.90
    // 首个 nextDouble 决定成败分支；两分支字段值均为手算期望
    const int64_t seed = 42;
    auto core = makeCore(seed);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 98.0;          // maxCult(9,1) = 98 满
    d.currentHp = -1;              // -1 = 满（血量哨兵）
    d.currentMp = -1;
    st.disciples.appendDisciple(d);

    // 预演同种子 BREAKTHROUGH 分区（fromSeed(seed+1)）的首个抽取值
    auto probe = gamecore::rng::DeterministicRng::fromSeed(seed + 1);
    const double firstDraw = probe.nextDouble();

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    if (firstDraw < 0.90) {
        // 成功：层数 +1，修为清零，引导计数 +1
        EXPECT_EQ(2, after.realmLayer);
        EXPECT_DOUBLE_EQ(0.0, after.cultivation);
        EXPECT_EQ(1L, st.gameData.guideCounters["breakthroughs"]);
        EXPECT_EQ(1, after.breakthroughCount);
        EXPECT_EQ(0, after.breakthroughFailCount);
        // 大境界未变 → 无消息栏事件；寿命不变
        EXPECT_TRUE(st.gameData.gameEventRecords.empty());
        EXPECT_EQ(80, after.lifespan);
    } else {
        // 失败：修为清零，HP/MP = (基础口径 max × 0.1).toInt() 至少 1
        EXPECT_EQ(1, after.realmLayer);
        EXPECT_DOUBLE_EQ(0.0, after.cultivation);
        EXPECT_EQ(20, after.currentHp);            // 203 × 0.1 = 20.3 → 20
        EXPECT_EQ(7, after.currentMp);             // 78 × 0.1 = 7.8 → 7
        EXPECT_EQ(0, after.breakthroughCount);
        EXPECT_EQ(1, after.breakthroughFailCount);
        EXPECT_EQ(0L, st.gameData.guideCounters.count("breakthroughs"));
    }

    // RNG 审计：本旬恰好消耗一次 nextDouble（快照 == 预演两次调用后状态）
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kBreakthrough).snapshot());

    // 第二旬：修为 0 未满 → 不再抽卡；快照保持不变
    core->advancePhases(1);
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kBreakthrough).snapshot());
}

TEST(PhaseSettlementTest, MajorRealmBreakthroughRecordsEvent) {
    // 满层大境界突破（炼气九层 → 筑基）：记录消息栏事件 + 寿命增益。
    // 层9满修为 = 98 + 8×(390-98)/9 = 357.56（非 98）；
    // seed=7 首抽 0.5313 < 0.80（层9 chance=表(8,1)）→ 成功分支
    const int64_t seed = 7;
    auto core = makeCore(seed);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.realmLayer = 9;               // 炼气满层
    d.cultivation = 98.0 + 8.0 * (390.0 - 98.0) / 9.0;   // 层9满值
    d.currentHp = -1;
    d.currentMp = -1;
    st.disciples.appendDisciple(d);

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    EXPECT_EQ(8, after.realm);      // 大境界推进：炼气 → 筑基
    EXPECT_EQ(1, after.realmLayer);
    EXPECT_DOUBLE_EQ(0.0, after.cultivation);
    // 寿命增益 = lifespanGainForRealm(8) = 50（无天赋词条加成）
    EXPECT_EQ(130, after.lifespan);
    ASSERT_EQ(1u, st.gameData.gameEventRecords.size());
    const auto& ev = st.gameData.gameEventRecords[0];
    EXPECT_EQ("breakthrough", ev.eventType);
    EXPECT_EQ("SECT", ev.category);
    EXPECT_EQ("1", ev.relatedEntityId);
    EXPECT_EQ(1, ev.year);
    EXPECT_EQ(1L, ev.sequenceId);
    // summary 含新境界的中文命名（筑基）
    EXPECT_NE(std::string::npos, ev.summary.find("筑基"));
}

TEST(PhaseSettlementTest, SecretRealmMembersAreSkipped) {
    // 秘境探索中弟子：不恢复/不累积/不服药/不突破
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 50.0;
    d.currentHp = 100;
    d.currentMp = 50;
    st.disciples.appendDisciple(d);

    state::SecretRealmMemberState member;
    member.discipleId = "1";
    member.isDead = false;
    st.gameData.secretRealmSession.members.push_back(member);

    core->advancePhases(2);
    EXPECT_DOUBLE_EQ(50.0, st.disciples.materialize(0).cultivation);
    EXPECT_EQ(100, st.disciples.materialize(0).currentHp);
    EXPECT_EQ(50, st.disciples.materialize(0).currentMp);
}

TEST(PhaseSettlementTest, DeadDisciplesAreSkipped) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.isAlive = false;
    d.cultivation = 50.0;
    d.currentHp = 100;
    st.disciples.appendDisciple(d);

    core->advancePhases(2);
    EXPECT_DOUBLE_EQ(50.0, st.disciples.materialize(0).cultivation);
    EXPECT_EQ(100, st.disciples.materialize(0).currentHp);
}

TEST(PhaseSettlementTest, EmptyStateIsSafe) {
    // 空档（无弟子）：结算为纯 no-op，仅时间推进（回归守护：不得误改资源）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.spiritStones = 99999;

    const auto r = core->advancePhases(5);
    EXPECT_EQ(5, r.phasesAdvanced);
    EXPECT_EQ(99999L, st.gameData.spiritStones);
}

// ── 2026-08-31 新增：A1 突破丹逐颗扣减 / C1-C3 服用门槛 / A2 孕养度丹 ──

TEST(PhaseSettlementTest, BreakthroughPillDeductsPerUnitFromWarehouseStack) {
    // A1 回归：仓库突破丹堆叠 quantity=3，一次突破尝试消耗 1 颗 → 剩 2
    //（此前 EntityStore.minus 整叠删除：3 颗全丢）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 98.0;          // maxCult(9,1) = 98 满
    d.currentHp = -1;              // 满血哨兵 → 满足突破前置
    d.currentMp = -1;
    d.statusData["followed"] = "true";
    st.disciples.appendDisciple(d);
    st.gameData.breakthroughAutoPillFocused = true;

    state::Pill pill;
    pill.id = "bp1";
    pill.name = "突破丹";
    pill.pillType = "breakthrough";
    pill.rarity = 3;
    pill.quantity = 3;
    pill.effects.targetRealm = 9;
    pill.effects.breakthroughChance = 0.5;
    st.pills.push_back(pill);

    core->advancePhases(1);
    ASSERT_EQ(1u, st.pills.size());
    EXPECT_EQ(2, st.pills[0].quantity);      // 逐颗扣减：剩 2
    EXPECT_DOUBLE_EQ(0.0, st.disciples.materialize(0).cultivation);  // 突破尝试已发生
}

TEST(PhaseSettlementTest, BreakthroughPillDeductsPerUnitFromBagStack) {
    // A1 回归：储物袋突破丹 quantity=3 同样逐颗扣减 → 剩 2
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 98.0;
    d.currentHp = -1;
    d.currentMp = -1;
    d.statusData["followed"] = "true";
    StorageBagItem bagPill;
    bagPill.itemId = "bp1";
    bagPill.itemType = "pill";
    bagPill.name = "突破丹";
    bagPill.rarity = 3;
    bagPill.quantity = 3;
    bagPill.effect = state::ItemEffect{};
    bagPill.effect->pillType = "breakthrough";
    bagPill.effect->breakthroughChance = 0.5;
    bagPill.effect->targetRealm = 9;
    d.storageBagItems.push_back(bagPill);
    st.disciples.appendDisciple(d);
    st.gameData.breakthroughAutoPillFocused = true;

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    ASSERT_EQ(1u, after.storageBagItems.size());
    EXPECT_EQ(2, after.storageBagItems[0].quantity);   // 逐颗扣减：剩 2
    EXPECT_DOUBLE_EQ(0.0, after.cultivation);
}

TEST(PhaseSettlementTest, HealPillSkippedAtFullHpConsumedWhenInjured) {
    // C1：满血（-1 哨兵）不自动吃治疗丹；受伤（< 基础口径 maxHp=203）后自动服用
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 10.0;
    d.currentHp = -1;
    d.currentMp = -1;
    StorageBagItem heal;
    heal.itemId = "h1";
    heal.itemType = "pill";
    heal.name = "疗伤丹";
    heal.rarity = 2;
    heal.quantity = 1;
    heal.effect = state::ItemEffect{};
    heal.effect->healMaxHpPercent = 30.0;
    d.storageBagItems.push_back(heal);
    st.disciples.appendDisciple(d);

    core->advancePhases(1);
    // 满血：不服用，丹药保留
    ASSERT_EQ(1u, st.disciples.materialize(0).storageBagItems.size());

    // 受伤后下一旬自动服用（恢复 40 → 140 < 203 → 服用 +60 → 200）
    st.disciples.currentHps[0] = 100;
    core->advancePhases(1);
    EXPECT_TRUE(st.disciples.materialize(0).storageBagItems.empty());
    EXPECT_GT(st.disciples.materialize(0).currentHp, 100);
}

TEST(PhaseSettlementTest, TempBattlePillNotAutoConsumed) {
    // C2：战斗临时丹不自动服用，保留袋内
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 10.0;
    StorageBagItem battlePill;
    battlePill.itemId = "b1";
    battlePill.itemType = "pill";
    battlePill.name = "狂暴丹";
    battlePill.rarity = 2;
    battlePill.quantity = 1;
    battlePill.effect = state::ItemEffect{};
    battlePill.effect->physicalAttackAdd = 20;
    d.storageBagItems.push_back(battlePill);
    st.disciples.appendDisciple(d);

    core->advancePhases(2);
    const auto after = st.disciples.materialize(0);
    ASSERT_EQ(1u, after.storageBagItems.size());
    EXPECT_EQ("b1", after.storageBagItems[0].itemId);
}

TEST(PhaseSettlementTest, CultivationPillSkippedAtFullCultivation) {
    // C3：满修为不浪费修为丹（不满血 → 不触发突破，丹药跳过保留）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 98.0;          // maxCult(9,1) = 98 满
    d.currentHp = 100;             // 不满血 → 突破候选不成立
    d.currentMp = 50;
    StorageBagItem pillItem;
    pillItem.itemId = "p1";
    pillItem.itemType = "pill";
    pillItem.name = "聚气丹";
    pillItem.rarity = 1;
    pillItem.quantity = 1;
    pillItem.effect = state::ItemEffect{};
    pillItem.effect->pillType = "cultivationAdd";
    pillItem.effect->cultivationAdd = 30;
    d.storageBagItems.push_back(pillItem);
    st.disciples.appendDisciple(d);

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    ASSERT_EQ(1u, after.storageBagItems.size());       // 满修为跳过
    EXPECT_DOUBLE_EQ(98.0, after.cultivation);         // 累积封顶仍 98
}

TEST(PhaseSettlementTest, NurturePillDistributesToEquippedInstances) {
    // A2：孕养度丹 100 均分到 2 件已装备实例（每件 +50，余数 0）；
    // 升级需 100 → 未升级
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.cultivation = 10.0;
    d.weaponId = "w1";
    d.armorId = "a1";
    st.disciples.appendDisciple(d);

    state::EquipmentInstance w1;
    w1.id = "w1";
    w1.name = "木剑";
    w1.rarity = 1;
    state::EquipmentInstance a1;
    a1.id = "a1";
    a1.name = "布衣";
    a1.rarity = 1;
    st.equipmentInstances.push_back(w1);
    st.equipmentInstances.push_back(a1);

    StorageBagItem nPill;
    nPill.itemId = "n1";
    nPill.itemType = "pill";
    nPill.name = "蕴器丹";
    nPill.rarity = 3;
    nPill.quantity = 1;
    nPill.effect = state::ItemEffect{};
    nPill.effect->pillType = "nurtureAdd";
    nPill.effect->nurtureAdd = 100;
    d.storageBagItems.push_back(nPill);
    st.disciples.appendDisciple(d);

    core->advancePhases(1);
    EXPECT_TRUE(st.disciples.materialize(0).storageBagItems.empty());
    // 丹药 100 均分 2 件（每件 +50）+ 本旬孕养自然增长（每件 +10）= 60
    EXPECT_DOUBLE_EQ(60.0, st.equipmentInstances[0].nurtureProgress);
    EXPECT_DOUBLE_EQ(60.0, st.equipmentInstances[1].nurtureProgress);
    EXPECT_EQ(0, st.equipmentInstances[0].nurtureLevel);
}

// ── 2026-08-31 B 批：自动装备/学习（仓库 + 储物袋候选 + 更高品阶替换） ──

TEST(PhaseSettlementTest, AutoEquipFromWarehouseFillsEmptySlot) {
    // 仓库有武器堆叠 + 空槽 → 自动装备且堆叠减一（回归：背包为空常态）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoEquipFromWarehouseRootCounts = {1};
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);

    state::EquipmentStack eq;
    eq.id = "e1";
    eq.name = "精铁剑";
    eq.rarity = 1;
    eq.slot = "WEAPON";
    eq.physicalAttack = 15;
    eq.minRealm = 9;
    eq.quantity = 2;
    st.equipmentStacks.push_back(eq);

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    EXPECT_FALSE(after.weaponId.empty());
    ASSERT_EQ(1u, st.equipmentStacks.size());
    EXPECT_EQ(1, st.equipmentStacks[0].quantity);
    ASSERT_EQ(1u, st.equipmentInstances.size());
    EXPECT_EQ("1", st.equipmentInstances[0].ownerId.value_or(""));
    EXPECT_TRUE(st.equipmentInstances[0].isEquipped);
}

TEST(PhaseSettlementTest, AutoEquipAllOffIsNoOp) {
    // 全开关关闭 → 仓库堆叠与弟子零变化
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);
    state::EquipmentStack eq;
    eq.id = "e1";
    eq.name = "精铁剑";
    eq.rarity = 1;
    eq.slot = "WEAPON";
    eq.minRealm = 9;
    eq.quantity = 3;
    st.equipmentStacks.push_back(eq);

    core->advancePhases(1);
    EXPECT_TRUE(st.disciples.materialize(0).weaponId.empty());
    ASSERT_EQ(1u, st.equipmentStacks.size());
    EXPECT_EQ(3, st.equipmentStacks[0].quantity);
    EXPECT_TRUE(st.equipmentInstances.empty());
}

TEST(PhaseSettlementTest, AutoEquipFromBagInstanceDirectly) {
    // 袋内 equipment_instance（卸装保真实例）直接装配：孕养保真 + 袋条目移除
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoEquipFromWarehouseRootCounts = {1};
    Disciple d = baseDisciple("1");
    state::EquipmentInstance inst;
    inst.id = "i1";
    inst.name = "青云剑";
    inst.rarity = 4;
    inst.slot = "WEAPON";
    inst.physicalAttack = 100;
    inst.minRealm = 9;
    inst.nurtureLevel = 2;
    inst.nurtureProgress = 30.0;
    inst.ownerId = "1";
    inst.isEquipped = false;
    state::StorageBagItem bag;
    bag.itemId = "i1";
    bag.itemType = "equipment_instance";
    bag.name = "青云剑";
    bag.rarity = 4;
    bag.quantity = 1;
    bag.equipmentInstance = inst;
    d.storageBagItems.push_back(bag);
    st.disciples.appendDisciple(d);
    // 真实不变量：袋内实例不在实例表（卸装入袋后已移除，防双持有）

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    EXPECT_EQ("i1", after.weaponId);
    EXPECT_TRUE(after.storageBagItems.empty());
    ASSERT_EQ(1u, st.equipmentInstances.size());
    EXPECT_EQ("i1", st.equipmentInstances[0].id);
    EXPECT_TRUE(st.equipmentInstances[0].isEquipped);
    EXPECT_EQ(2, st.equipmentInstances[0].nurtureLevel);   // 孕养保真
    // 30（入袋时）+ 10（本旬装备孕养自然增长）= 40
    EXPECT_DOUBLE_EQ(40.0, st.equipmentInstances[0].nurtureProgress);
}

TEST(PhaseSettlementTest, AutoEquipReplacesWithStrictlyBetterFromBag) {
    // 已装备 r1 铁剑 + 袋内 r4 青云剑实例 → 自动替换，旧装备回袋不丢失
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoEquipFromWarehouseRootCounts = {1};
    Disciple d = baseDisciple("1");
    d.weaponId = "w1";
    state::EquipmentInstance oldInst;
    oldInst.id = "w1";
    oldInst.name = "精铁剑";
    oldInst.rarity = 1;
    oldInst.slot = "WEAPON";
    oldInst.physicalAttack = 15;
    oldInst.minRealm = 9;
    oldInst.ownerId = "1";
    oldInst.isEquipped = true;
    state::EquipmentInstance newInst;
    newInst.id = "n1";
    newInst.name = "青云剑";
    newInst.rarity = 4;
    newInst.slot = "WEAPON";
    newInst.physicalAttack = 100;
    newInst.minRealm = 9;
    newInst.ownerId = "1";
    newInst.isEquipped = false;
    state::StorageBagItem bag;
    bag.itemId = "n1";
    bag.itemType = "equipment_instance";
    bag.name = "青云剑";
    bag.rarity = 4;
    bag.quantity = 1;
    bag.equipmentInstance = newInst;
    d.storageBagItems.push_back(bag);
    st.disciples.appendDisciple(d);
    st.equipmentInstances.push_back(oldInst);
    // 真实不变量：袋内新实例不在实例表（n1 仅随袋条目携带）

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    EXPECT_EQ("n1", after.weaponId);                       // 已替换为高品阶
    ASSERT_EQ(1u, after.storageBagItems.size());
    EXPECT_EQ("w1", after.storageBagItems[0].itemId);      // 旧装备回袋
    EXPECT_EQ("equipment_instance", after.storageBagItems[0].itemType);
    ASSERT_EQ(1u, st.equipmentInstances.size());           // 旧实例已移除
    EXPECT_EQ("n1", st.equipmentInstances[0].id);
    EXPECT_TRUE(st.equipmentInstances[0].isEquipped);
}

TEST(PhaseSettlementTest, AutoLearnFromWarehouseFillsSlot) {
    // 仓库功法堆叠 + 空槽 → 自动学习且堆叠减一
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoLearnFromWarehouseRootCounts = {1};
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);
    state::ManualStack mn;
    mn.id = "m1";
    mn.name = "青冥剑诀";
    mn.rarity = 1;
    mn.type = "ATTACK";
    mn.skillDamageType = "physical";
    mn.minRealm = 9;
    mn.quantity = 2;
    st.manualStacks.push_back(mn);

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    ASSERT_EQ(1u, after.manualIds.size());
    ASSERT_EQ(1u, st.manualInstances.size());
    EXPECT_TRUE(st.manualInstances[0].isLearned);
    EXPECT_EQ("1", st.manualInstances[0].ownerId.value_or(""));
    ASSERT_EQ(1u, st.manualStacks.size());
    EXPECT_EQ(1, st.manualStacks[0].quantity);
}

TEST(PhaseSettlementTest, AutoLearnReplacesWorstWhenSlotsFull) {
    // 已学 6 本 r1 功法（槽位满）→ 仓库 r4 功法 → 替换最差（首本 r1），
    // 旧功法回袋 + 残留熟练度清理
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoLearnFromWarehouseRootCounts = {1};
    Disciple d = baseDisciple("1");
    state::ManualInstance learned;
    for (int32_t i = 1; i <= 6; ++i) {
        state::ManualInstance mi;
        mi.id = "m" + std::to_string(i);
        mi.name = "基础功" + std::to_string(i);
        mi.rarity = 1;
        mi.type = "ATTACK";
        mi.skillDamageType = "physical";
        mi.minRealm = 9;
        mi.ownerId = "1";
        mi.isLearned = true;
        learned = mi;
        st.manualInstances.push_back(mi);
        d.manualIds.push_back(mi.id);
    }
    st.disciples.appendDisciple(d);
    // 首本功法带残留熟练度
    state::ManualProficiencyData prof;
    prof.manualId = "m1";
    prof.manualName = "基础功1";
    prof.proficiency = 500.0;
    prof.maxProficiency = 30000.0;
    prof.masteryLevel = 0;
    st.gameData.manualProficiencies["1"].push_back(prof);

    state::ManualStack better;
    better.id = "b1";
    better.name = "太乙剑诀";
    better.rarity = 4;
    better.type = "ATTACK";
    better.skillDamageType = "physical";
    better.minRealm = 9;
    better.quantity = 1;
    st.manualStacks.push_back(better);

    core->advancePhases(1);
    const auto after = st.disciples.materialize(0);
    ASSERT_EQ(6u, after.manualIds.size());                 // 替换后仍 6 本
    // 最差（m1，全同 r1 时取首本）被遗忘入袋
    const bool m1Gone = std::find(after.manualIds.begin(), after.manualIds.end(), "m1") ==
                        after.manualIds.end();
    EXPECT_TRUE(m1Gone);
    // 新功法已学（实例存在）
    bool learnedBetter = false;
    for (const std::string& mid : after.manualIds) {
        for (const auto& mi : st.manualInstances) {
            if (mi.id == mid && mi.name == "太乙剑诀") { learnedBetter = true; break; }
        }
    }
    EXPECT_TRUE(learnedBetter);
    // 旧功法回袋
    const bool bagged = std::any_of(after.storageBagItems.begin(),
        after.storageBagItems.end(),
        [](const state::StorageBagItem& x) { return x.itemId == "m1"; });
    EXPECT_TRUE(bagged);
    // 残留熟练度已清理：m1 条目已移除；其余 5 本 + 新学功法（本旬熟练度
    // 增长新建）共 6 条，且不含 m1
    const auto profIt = st.gameData.manualProficiencies.find("1");
    ASSERT_NE(profIt, st.gameData.manualProficiencies.end());
    ASSERT_EQ(6u, profIt->second.size());
    for (const auto& p : profIt->second) {
        EXPECT_NE("m1", p.manualId);
    }
}

TEST(PhaseSettlementTest, AutoGearSecretRealmMemberSkipped) {
    // 秘境探索中弟子不自动装备/学习（状态冻结语义）
    auto core = makeCore(42);
    auto& st = core->state();
    st.gameData.autoEquipFromWarehouseRootCounts = {1};
    st.gameData.autoLearnFromWarehouseRootCounts = {1};
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);
    state::SecretRealmMemberState member;
    member.discipleId = "1";
    member.isDead = false;
    st.gameData.secretRealmSession.members.push_back(member);
    state::EquipmentStack eq;
    eq.id = "e1";
    eq.name = "精铁剑";
    eq.rarity = 1;
    eq.slot = "WEAPON";
    eq.minRealm = 9;
    eq.quantity = 1;
    st.equipmentStacks.push_back(eq);

    core->advancePhases(1);
    EXPECT_TRUE(st.disciples.materialize(0).weaponId.empty());
    EXPECT_TRUE(st.equipmentInstances.empty());
}

// ============================================================
// P0 守护：每旬核心批次并行化 == 串行（确定性红线）
//
// runPhaseCoreBatchParallel（JobSystem 分块并行）必须与串行
// runPhaseCoreBatch **逐位一致**——零 RNG、逐弟子独立写、读静态列，
// 并行不改变任何抽取/写入序。本测试构造多弟子（含长老加成/父灵根/功法
// 熟练度/装备孕养/藏经阁加成/死亡跳过）场景，串行与并行各跑一遍，
// 全状态 JSON 逐字节比对。
// ============================================================

namespace {

GameState makePhaseCoreState() {
    GameState st;
    // 弟子 1：外门（既是讲道长老 teaching=90，又装备武器 e1、修功法 m1/m2）
    Disciple d1 = baseDisciple("1");
    d1.cultivation = 50.0;
    d1.currentHp = 100;
    d1.currentMp = 50;
    d1.teaching = 90;
    d1.manualIds = {"m1", "m2"};
    d1.weaponId = "e1";
    // 弟子 2：外门，父为弟子 1（父灵根加成），修功法 m1
    Disciple d2 = baseDisciple("2");
    d2.cultivation = 40.0;
    d2.currentHp = 80;
    d2.currentMp = 40;
    d2.manualIds = {"m1"};
    d2.parentId1 = "1";
    // 弟子 3：外门，藏经阁藏（熟练度加成）
    Disciple d3 = baseDisciple("3");
    d3.cultivation = 30.0;
    d3.currentHp = 60;
    d3.currentMp = 30;
    // 弟子 4：已故（跳过）
    Disciple d4 = baseDisciple("4");
    d4.cultivation = 20.0;
    d4.isAlive = false;
    st.disciples.appendDisciple(d1);
    st.disciples.appendDisciple(d2);
    st.disciples.appendDisciple(d3);
    st.disciples.appendDisciple(d4);

    // 装备实例 e1（弟子 1 武器，孕养增长）
    state::EquipmentInstance eq;
    eq.id = "e1";
    eq.name = "木剑";
    eq.rarity = 1;
    eq.slot = "WEAPON";
    eq.minRealm = 9;
    eq.ownerId = "1";
    eq.isEquipped = true;
    st.equipmentInstances.push_back(eq);

    // 功法实例 m1/m2（熟练度）
    state::ManualInstance m1;
    m1.id = "m1";
    m1.name = "功法甲";
    state::ManualInstance m2;
    m2.id = "m2";
    m2.name = "功法乙";
    st.manualInstances.push_back(m1);
    st.manualInstances.push_back(m2);

    // 讲道长老=弟子 1；藏经阁槽位=弟子 3
    st.gameData.elderSlots.preachingElder = "1";
    state::LibrarySlot lib;
    lib.index = 0;
    lib.discipleId = "3";
    st.gameData.librarySlots.push_back(lib);
    return st;
}

}  // namespace

TEST(PhaseSettlementTest, CoreBatchParallelMatchesSerial) {
    GameState serial = makePhaseCoreState();
    GameState par = makePhaseCoreState();

    system::runPhaseCoreBatch(serial);
    ecs::JobSystem jobs(4);   // 多线程并行，真正分块
    system::runPhaseCoreBatchParallel(par, jobs);

    // 全状态 JSON 逐字节比对（零 RNG 批次 → 并行必须与串行完全一致）
    nlohmann::json js;
    gamecore::state::to_json(js, serial);
    nlohmann::json jp;
    gamecore::state::to_json(jp, par);
    EXPECT_EQ(js.dump(), jp.dump()) << "并行核心批次必须与串行逐位一致";

    // 显式关键字段抽查：修为/HP/MP/熟练度/装备孕养确实变化（场景生效）
    EXPECT_GT(serial.disciples.materialize(1).cultivation, 40.0);
    EXPECT_GT(serial.disciples.materialize(0).currentHp, 100);
    EXPECT_EQ(1u, serial.gameData.manualProficiencies.count("1"));
    EXPECT_EQ(1u, serial.gameData.manualProficiencies.count("2"));
    EXPECT_GT(serial.equipmentInstances[0].nurtureProgress, 0.0);
}

}  // namespace

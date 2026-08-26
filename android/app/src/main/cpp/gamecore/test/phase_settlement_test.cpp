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

}  // namespace

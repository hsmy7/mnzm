// ============================================================
// mission_completion_test — 任务完成结算守护
//
// 守护目标：mission_completion.h 等价移植 Kotlin 子事件 5
// （MissionSystem.processMissionCompletion 三分支 + 奖励生成 +
// applyMissionRewards + 战斗组装 createBattle/convertDiscipleToCombatant/
// EnemyGenerator）语义逐位一致。
//
// RNG 审计（对拍命门，同 production_test 方法）：MISSION 分区播种
// fromSeed(seed + 8)——抽取序逐分支登记；BATTLE/ENEMY_GEN 分区由战斗
// 执行/敌人生成消费，用独立分区快照断言"分区隔离 + 非零消耗"。
// Kotlin 侧同序由 DiffMonthSettlement 对拍场景端到端守护（本文件为
// C++ 侧黄金锁定）。
// ============================================================

#include "gtest/gtest.h"

#include <cmath>
#include <memory>
#include <set>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/mission_completion.h"
#include "gamecore/system/mission_settlement.h"
#include "gamecore/state/json_codec.h"

namespace {

using namespace gamecore;
namespace mc = gamecore::system::mission_settle;
using gamecore::state::ActiveMission;
using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::ManualInstance;
using gamecore::rng::DeterministicRng;
using gamecore::rng::RngManager;

constexpr int64_t kSeed = 900;

/// 构建已初始化 GameCore（种子固定）
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 最小存活弟子（指定境界/层数）
Disciple baseDisciple(const std::string& id, int32_t realm = 9, int32_t layer = 1) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = realm;
    d.realmLayer = layer;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 20;
    d.lifespan = 80;
    d.status = "ON_MISSION";
    return d;
}

/// 带技能的功法实例（攻击技能 + 面板加成）
ManualInstance testManual(const std::string& id) {
    ManualInstance m;
    m.id = id;
    m.name = "测试功法";
    m.rarity = 1;
    m.type = "ATTACK";
    m.stats["maxHp"] = 50;
    m.stats["physicalAttack"] = 10;
    m.stats["critRate"] = 5;
    m.skillName = "测试斩";
    m.skillType = "attack";
    m.skillDamageType = "physical";
    m.skillDamageMultiplier = 2.0;
    m.skillMpCost = 5;
    m.skillCooldown = 1;
    m.skillHits = 1;
    m.skillTargetScope = "enemy";
    return m;
}

/// 推进游戏时间到任务到期 (1,4)（GameCore 初始 (1,1)）
void setDueDate(GameState& st) {
    st.gameData.gameYear = 1;
    st.gameData.gameMonth = 4;
}

/// NO_COMBAT 任务（可配置奖励段）
ActiveMission noCombatMission(const std::string& id,
                              const std::vector<std::string>& members) {
    ActiveMission m;
    m.id = id;
    m.missionId = "tpl-" + id;
    m.missionName = "简单押镖";
    m.template_ = "ESCORT_CARAVAN";
    m.difficulty = "SIMPLE";
    m.discipleIds = members;
    for (const auto& x : members) m.discipleNames.push_back("弟子" + x);
    m.startYear = 1;
    m.startMonth = 1;
    m.duration = 3;
    m.missionType = "NO_COMBAT";
    m.enemyType = "BEAST";
    m.rewards.spiritStones = 600;
    return m;
}

/// 到期任务：start (1,1)，duration 3，当前 (1,4) → 完成
ActiveMission dueMission(const std::string& id,
                         const std::vector<std::string>& members) {
    ActiveMission m = noCombatMission(id, members);
    m.startYear = 1;
    m.startMonth = 1;
    m.duration = 3;
    return m;
}

// ── 完成判定 ────────────────────────────────────────────────────────

TEST(MissionCompletionTest, DurationBoundaryComplete) {
    ActiveMission m = noCombatMission("m1", {"1"});
    m.duration = 3;
    // elapsed = 3 → remaining 0 → 完成
    EXPECT_TRUE(mc::detail::isMissionComplete(m, 1, 4));
    // elapsed = 2 → remaining 1 → 未完成
    EXPECT_FALSE(mc::detail::isMissionComplete(m, 1, 3));
    // 跨年
    EXPECT_TRUE(mc::detail::isMissionComplete(m, 2, 1));
}

// ── NO_COMBAT：奖励 roll + RNG 审计 ────────────────────────────────

TEST(MissionCompletionTest, NoCombatRewardsAndRngAudit) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    Disciple d = baseDisciple("1");
    st.disciples.appendDisciple(d);

    ActiveMission m = dueMission("m1", {"1"});
    m.rewards.spiritStones = 100;
    m.rewards.spiritStonesMax = 200;    // nextInt(101) 恰 1 抽
    m.rewards.materialCountMin = 1;     // nextInt(2) 恰 1 抽 + 逐条模板
    m.rewards.materialCountMax = 2;
    m.rewards.materialMinRarity = 1;
    m.rewards.materialMaxRarity = 1;
    m.rewards.pillCountMin = 1;         // nextInt(2) 恰 1 抽 + 逐条模板
    m.rewards.pillCountMax = 2;
    m.rewards.pillMinRarity = 1;
    m.rewards.pillMaxRarity = 1;
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);
    const int64_t stonesBefore = st.gameData.spiritStones;

    const int64_t missionBefore = core->rng().getRng(rng::RngPartition::kMission).snapshot();
    mc::processCompletedMissions(st, core->rng());
    const int64_t missionAfter = core->rng().getRng(rng::RngPartition::kMission).snapshot();

    // 独立预演同序（抽取序 = stones + matCount + 每条材料 + pillCount + 每颗丹）
    const std::size_t matPool = [&] {
        std::size_t n = 0;
        for (const auto& t : gamecore::data::beastMaterialTemplates()) {
            if (t.rarity == 1) ++n;
        }
        return n;
    }();
    const auto pillPool =
        gamecore::data::detail::pillTemplatesByRarityRange(1, 1);
    DeterministicRng replay = DeterministicRng::fromSeed(kSeed + 8);
    const int32_t stones = 100 + replay.nextInt(101);
    const int32_t matCount = 1 + replay.nextInt(2);
    for (int32_t i = 0; i < matCount; ++i) replay.nextInt(static_cast<int32_t>(matPool));
    const int32_t pillCount = 1 + replay.nextInt(2);
    for (int32_t i = 0; i < pillCount; ++i) replay.nextInt(static_cast<int32_t>(pillPool.size()));

    EXPECT_EQ(missionAfter, replay.snapshot());
    EXPECT_EQ(st.gameData.spiritStones, stonesBefore + stones);
    EXPECT_EQ(st.materials.size(), static_cast<std::size_t>(matCount));
    EXPECT_EQ(st.pills.size(), static_cast<std::size_t>(pillCount));
    // 任务消费、成员回 IDLE
    EXPECT_TRUE(st.gameData.activeMissions.empty());
    const auto row = st.disciples.rowOf("1");
    ASSERT_TRUE(row.has_value());
    EXPECT_EQ(st.disciples.statuses[*row], "IDLE");
}

TEST(MissionCompletionTest, NoCombatZeroMaxNoRoll) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    ActiveMission m = dueMission("m1", {"1"});
    m.rewards.spiritStones = 600;
    m.rewards.spiritStonesMax = 0;   // 不抽
    m.rewards.materialCountMin = 0;  // 不抽
    m.rewards.pillCountMin = 0;      // 不抽
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);
    const int64_t stonesBefore = st.gameData.spiritStones;
    setDueDate(st);

    const int64_t before = core->rng().getRng(rng::RngPartition::kMission).snapshot();
    mc::processCompletedMissions(st, core->rng());
    // MISSION 零抽取
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kMission).snapshot(), before);
    EXPECT_EQ(st.gameData.spiritStones, stonesBefore + 600);
    EXPECT_TRUE(st.gameData.activeMissions.empty());
}

TEST(MissionCompletionTest, NotDueMissionKeptWithoutDraws) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    ActiveMission m = noCombatMission("m1", {"1"});  // start (1,1)
    m.duration = 99;  // 未到期
    st.gameData.activeMissions.push_back(m);
    ASSERT_FALSE(mc::detail::isMissionComplete(m, 1, 4));

    const int64_t before = core->rng().getRng(rng::RngPartition::kMission).snapshot();
    mc::processCompletedMissions(st, core->rng());
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kMission).snapshot(), before);
    ASSERT_EQ(st.gameData.activeMissions.size(), 1u);
    EXPECT_EQ(st.gameData.activeMissions[0].id, "m1");
}

// ── COMBAT_REQUIRED：妖兽战斗（胜利臂 + 失败臂） ────────────────────

TEST(MissionCompletionTest, CombatRequiredBeastVictoryGolden) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    // 强队：仙人九层 ×2（baseHp 846353 层数乘区）+ 测试功法
    for (const std::string id : {"1", "2"}) {
        Disciple d = baseDisciple(id, 0, 9);
        d.manualIds.push_back("man-" + id);
        st.disciples.appendDisciple(d);
        st.manualInstances.push_back(testManual("man-" + id));
    }
    ActiveMission m = dueMission("m1", {"1", "2"});
    m.missionType = "COMBAT_REQUIRED";
    m.enemyType = "BEAST";   // beastRealm = (8+9)/2 = 8（7 只）；仙人队碾压
    m.rewards.spiritStones = 100;
    m.rewards.spiritStonesMax = 0;   // 胜利臂零 MISSION 抽取（便于审计）
    m.rewards.materialCountMin = 0;
    m.rewards.pillCountMin = 0;
    m.rewards.equipmentChance = 0.0;
    m.rewards.manualChance = 0.0;
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);
    const int64_t stonesBefore = st.gameData.spiritStones;

    const int64_t missionBefore = core->rng().getRng(rng::RngPartition::kMission).snapshot();
    const int64_t battleBefore = core->rng().getRng(rng::RngPartition::kBattle).snapshot();
    const int64_t enemyBefore = core->rng().getRng(rng::RngPartition::kEnemyGen).snapshot();
    mc::processCompletedMissions(st, core->rng());

    // 分区审计：MISSION/ENEMY_GEN 零消耗，BATTLE 消耗（战斗真实执行）
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kMission).snapshot(), missionBefore);
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kEnemyGen).snapshot(), enemyBefore);
    EXPECT_NE(core->rng().getRng(rng::RngPartition::kBattle).snapshot(), battleBefore);

    // 任务消费 + 灵石 + 成员回 IDLE + 幸存者魂力 +1
    EXPECT_TRUE(st.gameData.activeMissions.empty());
    EXPECT_EQ(st.gameData.spiritStones, stonesBefore + 100);
    for (const auto& id : {"1", "2"}) {
        const auto row = st.disciples.rowOf(id);
        ASSERT_TRUE(row.has_value());
        EXPECT_EQ(st.disciples.statuses[*row], "IDLE");
        EXPECT_EQ(st.disciples.soulPowers[*row], 1);  // 仙人队全存活
    }
}

TEST(MissionCompletionTest, CombatRequiredDefeatConsumedWithEmptyReward) {
    auto core = makeCore(kSeed + 1);
    GameState& st = core->state();
    // 弱队：炼气一层 ×1（hp ~120）vs 妖兽 realm 8（7 只，hp 1541）→ 必败
    st.disciples.appendDisciple(baseDisciple("1", 9, 1));
    ActiveMission m = dueMission("m1", {"1"});
    m.missionType = "COMBAT_REQUIRED";
    m.difficulty = "SIMPLE";
    m.rewards.spiritStones = 500;
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);

    const int64_t battleBefore = core->rng().getRng(rng::RngPartition::kBattle).snapshot();
    mc::processCompletedMissions(st, core->rng());

    // 失败臂：任务仍消费（Kotlin 失败臂进 rewards 收集——空奖励）、
    // 无灵石/物品、无幸存者、状态仍回 IDLE
    EXPECT_NE(core->rng().getRng(rng::RngPartition::kBattle).snapshot(), battleBefore);
    EXPECT_TRUE(st.gameData.activeMissions.empty());
    EXPECT_EQ(st.gameData.spiritStones, 1000);  // 初始 1000 + 失败臂零发放
    EXPECT_TRUE(st.pills.empty());
    EXPECT_TRUE(st.materials.empty());
    const auto row = st.disciples.rowOf("1");
    ASSERT_TRUE(row.has_value());
    EXPECT_EQ(st.disciples.statuses[*row], "IDLE");
    EXPECT_EQ(st.disciples.soulPowers[*row], 0);
}

// ── COMBAT_RANDOM：触发门 ───────────────────────────────────────────

TEST(MissionCompletionTest, CombatRandomUntriggeredBaseRewards) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    ActiveMission m = dueMission("m1", {"1"});
    m.missionType = "COMBAT_RANDOM";
    m.triggerChance = 0.0;   // 恒不触发
    m.rewards.baseSpiritStones = 200;
    m.rewards.baseMaterialCountMin = 1;   // nextInt(2) 1 抽 + 逐条
    m.rewards.baseMaterialCountMax = 2;
    m.rewards.baseMaterialMinRarity = 1;
    m.rewards.baseMaterialMaxRarity = 1;
    m.rewards.spiritStones = 500;    // 主奖励段（未触发不可达）
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);
    const int64_t stonesBefore = st.gameData.spiritStones;

    const int64_t battleBefore = core->rng().getRng(rng::RngPartition::kBattle).snapshot();
    mc::processCompletedMissions(st, core->rng());

    EXPECT_EQ(st.gameData.spiritStones, stonesBefore + 200);   // base 段
    // 分区审计：触发门恰 1 抽 + baseMaterial count 1 抽 + 逐条模板；零战斗
    const std::size_t matPool = [&] {
        std::size_t n = 0;
        for (const auto& t : gamecore::data::beastMaterialTemplates()) {
            if (t.rarity == 1) ++n;
        }
        return n;
    }();
    DeterministicRng replay = DeterministicRng::fromSeed(kSeed + 8);
    replay.nextDouble();                              // 触发门恰 1 抽（chance=0 恒不触发）
    const int32_t matCount = 1 + replay.nextInt(2);   // baseMaterial count
    for (int32_t i = 0; i < matCount; ++i) replay.nextInt(static_cast<int32_t>(matPool));
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kMission).snapshot(), replay.snapshot());
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kBattle).snapshot(), battleBefore);  // 未战斗
    EXPECT_TRUE(st.gameData.activeMissions.empty());
}

TEST(MissionCompletionTest, CombatRandomTriggeredFullArm) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    // 强队 vs 弱兽：胜利臂
    for (const std::string id : {"1", "2"}) {
        Disciple d = baseDisciple(id, 0, 9);
        d.manualIds.push_back("man-" + id);
        st.disciples.appendDisciple(d);
        st.manualInstances.push_back(testManual("man-" + id));
    }
    ActiveMission m = dueMission("m1", {"1", "2"});
    m.missionType = "COMBAT_RANDOM";
    m.triggerChance = 1.0;   // 恒触发
    m.difficulty = "FORBIDDEN";  // beastRealm = (2+3)/2 = 2（弱兽）→ 碾压
    m.rewards.spiritStones = 150000;
    m.rewards.spiritStonesMax = 0;
    m.rewards.materialCountMin = 0;
    m.rewards.pillCountMin = 0;
    m.rewards.equipmentChance = 0.0;
    m.rewards.manualChance = 0.0;
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);
    const int64_t stonesBefore = st.gameData.spiritStones;

    const int64_t battleBefore = core->rng().getRng(rng::RngPartition::kBattle).snapshot();
    mc::processCompletedMissions(st, core->rng());

    EXPECT_NE(core->rng().getRng(rng::RngPartition::kBattle).snapshot(), battleBefore);  // 战斗发生
    EXPECT_EQ(st.gameData.spiritStones, stonesBefore + 150000);
    EXPECT_TRUE(st.gameData.activeMissions.empty());
    for (const auto& id : {"1", "2"}) {
        const auto row = st.disciples.rowOf(id);
        ASSERT_TRUE(row.has_value());
        EXPECT_EQ(st.disciples.soulPowers[*row], 1);
    }
}

// ── 边界：无存活弟子 / 非数字 id ────────────────────────────────────

TEST(MissionCompletionTest, AllMembersDeadMissionKept) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    Disciple dead = baseDisciple("1");
    dead.isAlive = false;
    st.disciples.appendDisciple(dead);
    ActiveMission m = dueMission("m1", {"1"});
    m.rewards.spiritStones = 600;
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);

    const int64_t before = core->rng().getRng(rng::RngPartition::kMission).snapshot();
    mc::processCompletedMissions(st, core->rng());
    // 全员死亡 → runCatching null 等价 → 任务保留、零抽取
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kMission).snapshot(), before);
    ASSERT_EQ(st.gameData.activeMissions.size(), 1u);
}

TEST(MissionCompletionTest, NonNumericMemberIdSkipped) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    st.disciples.appendDisciple(baseDisciple("7"));
    ActiveMission m = dueMission("m1", {"7", "ghost-id"});
    m.rewards.spiritStones = 100;
    m.rewards.spiritStonesMax = 0;
    st.gameData.activeMissions.push_back(m);
    setDueDate(st);
    mc::processCompletedMissions(st, core->rng());
    // 存活弟子 7 正常结算（ghost-id 无行——rowOf 跳过）
    const auto row = st.disciples.rowOf("7");
    ASSERT_TRUE(row.has_value());
    EXPECT_EQ(st.disciples.statuses[*row], "IDLE");
    EXPECT_TRUE(st.gameData.activeMissions.empty());
}

// ── 妖兽组装黄金值（Kotlin createBeast 手算口径） ───────────────────

TEST(BeastAssemblyTest, RealmStatsGoldenValues) {
    // 虎妖（hpMod 1.3 / atkMod 1.4 / defMod 0.7 / speedMod 1.0），
    // beastRealm 8（rs 847/326/76/57/41），layerMult 1.4：
    //   hp = (847*1.4*1.3).toInt() = 1541
    //   mp = (326*1.4*1.3).toInt() = 593
    //   pa/ma = (76*1.4*1.4).toInt() = 148（atkMod=1.4）
    //   pd/md = (57*1.4*0.7).toInt() = 55
    //   speed = (41*1.4*1.0).toInt() = 57
    //   critRate = 0.05 + 8*0.01 = 0.13
    auto beast = mc::detail::createBeast(8, 3, 0);
    EXPECT_EQ(beast.id, "beast_3");
    EXPECT_EQ(beast.name, "狂暴虎妖");
    EXPECT_EQ(beast.hp, 1541);
    EXPECT_EQ(beast.maxHp, 1541);
    EXPECT_EQ(beast.mp, 593);
    EXPECT_EQ(beast.maxMp, 593);
    EXPECT_EQ(beast.physicalAttack, 148);
    EXPECT_EQ(beast.magicAttack, 148);
    EXPECT_EQ(beast.physicalDefense, 55);
    EXPECT_EQ(beast.magicDefense, 55);
    EXPECT_EQ(beast.speed, 57);
    EXPECT_DOUBLE_EQ(beast.critRate, 0.13);
    EXPECT_EQ(beast.realm, 8);
    EXPECT_EQ(beast.realmLayer, 5);
    EXPECT_EQ(beast.element, "metal");
    EXPECT_TRUE(beast.isBeast);
    // 技能表（虎妖 4 技能）
    ASSERT_EQ(beast.skills.size(), 4u);
    EXPECT_EQ(beast.skills[0].name, "猛虎下山");
    EXPECT_DOUBLE_EQ(beast.skills[0].damageMultiplier, 1.8);
    EXPECT_EQ(beast.skills[3].buffType, gamecore::battle::BuffType::kPhysicalAttackBoost);
}

TEST(BeastAssemblyTest, RealmFallbackBeyondBounds) {
    // 越界回退 9 档（rs 339/130/31/22/16）：(339*1.4*1.3).toInt() = 616
    auto beast = mc::detail::createBeast(20, 1, 0);
    EXPECT_EQ(beast.hp, 616);
    EXPECT_EQ(beast.realm, 9);
}

// ── java.util.Random LCG 黄金值（洗牌语义前置） ─────────────────────

TEST(JavaRandomTest, KnownLcgValues) {
    // new java.util.Random(42).nextInt() == -1170105035（JVM 黄金值）
    mc::detail::JavaRandom rnd(42);
    EXPECT_EQ(rnd.nextInt(2147483647) * 0, 0);  // 防编译器优化占位
    mc::detail::JavaRandom rnd2(42);
    // 直接验证 32 位输出：next(32) 对应 nextInt()
    EXPECT_EQ(rnd2.next(32), -1170105035);
    // 洗牌：固定 seed 的 4 槽排列确定性
    mc::detail::JavaRandom rnd3(7);
    std::vector<std::string> slots = {"WEAPON", "ARMOR", "BOOTS", "ACCESSORY"};
    auto shuffled = mc::detail::javaShuffled(slots, rnd3);
    EXPECT_EQ(shuffled.size(), 4u);
    // 同 seed 重放结果一致
    mc::detail::JavaRandom rnd4(7);
    auto again = mc::detail::javaShuffled(slots, rnd4);
    EXPECT_EQ(shuffled, again);
}

// ── HUMAN 敌人生成（ENEMY_GEN 分区消费 + 确定性） ───────────────────

TEST(HumanEnemyTest, DeterministicGenerationAndPartitions) {
    RngManager rngs;
    rngs.initSystemSeed(123);
    auto e1 = mc::detail::generateHumanEnemies(8, 9, 2, rngs.getRng(rng::RngPartition::kEnemyGen));
    ASSERT_EQ(e1.size(), 2u);
    EXPECT_EQ(e1[0].id, "human_enemy_1");
    EXPECT_EQ(e1[0].name.size() > 2, true);   // "魔修1" 等
    EXPECT_EQ(e1[0].side, gamecore::battle::CombatantSide::kAttacker);
    // 同 seed 重放逐位一致
    RngManager rngs2;
    rngs2.initSystemSeed(123);
    auto e2 = mc::detail::generateHumanEnemies(8, 9, 2, rngs2.getRng(rng::RngPartition::kEnemyGen));
    EXPECT_EQ(e1[0].name, e2[0].name);
    EXPECT_EQ(e1[0].hp, e2[0].hp);
    EXPECT_EQ(e1[0].physicalAttack, e2[0].physicalAttack);
    EXPECT_EQ(e1[1].element, e2[1].element);
    // 技能兜底（无功法生成时默认普通攻击——概率性，仅断言非空或兜底）
    for (const auto& e : e1) {
        EXPECT_FALSE(e.skills.empty());
    }
}

// ── 协议往返：ActiveMission JSON 编解码 ─────────────────────────────

TEST(ActiveMissionCodecTest, RoundTripPreservesFields) {
    ActiveMission m;
    m.id = "am-1";
    m.missionId = "tpl-1";
    m.missionName = "简单押镖";
    m.template_ = "ESCORT_CARAVAN";
    m.difficulty = "SIMPLE";
    m.discipleIds = {"1", "2"};
    m.discipleNames = {"甲", "乙"};
    m.discipleRealms = {"炼气", "炼气"};
    m.startYear = 2;
    m.startMonth = 5;
    m.duration = 3;
    m.rewards.spiritStones = 600;
    m.rewards.equipmentChance = 0.3;
    m.missionType = "COMBAT_RANDOM";
    m.enemyType = "HUMAN";
    m.triggerChance = 0.25;

    nlohmann::json j;
    gamecore::state::to_json(j, m);
    // kotlinx 键名（template 为关键字转义）
    EXPECT_EQ(j["template"], "ESCORT_CARAVAN");
    EXPECT_EQ(j["missionId"], "tpl-1");
    EXPECT_EQ(j["triggerChance"], 0.25);

    ActiveMission back;
    gamecore::state::from_json(j, back);
    EXPECT_EQ(back.id, "am-1");
    EXPECT_EQ(back.template_, "ESCORT_CARAVAN");
    EXPECT_EQ(back.discipleIds.size(), 2u);
    EXPECT_EQ(back.discipleRealms.size(), 2u);
    EXPECT_EQ(back.startYear, 2);
    EXPECT_EQ(back.rewards.spiritStones, 600);
    EXPECT_DOUBLE_EQ(back.rewards.equipmentChance, 0.3);
    EXPECT_EQ(back.missionType, "COMBAT_RANDOM");
    EXPECT_EQ(back.enemyType, "HUMAN");
}

}  // namespace

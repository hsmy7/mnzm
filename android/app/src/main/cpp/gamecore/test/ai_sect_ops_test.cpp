// ============================================================
// ai_sect_ops_test — AI 宗门月度运营与兽战余量守护
//
// 守护目标：ai_sect_ops.h 等价移植 Kotlin 子事件 6/9（AI 热控分批修炼/
// 突破/熟练度/孕养/装备补全/宗门等级同步 + 兽战余量组装/执行/事件/死亡）
// 语义逐位一致。
//
// RNG 审计：AI 独立 RNG（systemSeed 播种）——突破 roll / 洗牌种子 / 模板
// 抽取用独立 DeterministicRng 预演同序列断言快照；BATTLE 分区由战斗执行
// 消费（分区隔离断言）。
// ============================================================

#include "gtest/gtest.h"

#include <cmath>
#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/ai_sect_ops.h"
#include "gamecore/system/nurture_constants.h"

namespace {

using namespace gamecore;
namespace ops = gamecore::system::ai_ops;
using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::WorldLevel;
using gamecore::state::WorldSect;
using gamecore::rng::DeterministicRng;

constexpr int64_t kSeed = 900;

std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// AI 弟子（境界可配；无装备/功法——由补全/刷新路径生成）
Disciple aiDisciple(const std::string& id, int32_t realm = 9, int32_t layer = 1) {
    Disciple d;
    d.id = id;
    d.name = "AI" + id;
    d.realm = realm;
    d.realmLayer = layer;
    d.cultivation = 0.0;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 20;
    d.lifespan = 80;
    d.status = "IDLE";
    d.aptitude = 50;
    return d;
}

/// 妖兽关卡（SIMPLE 难度口径：realm 8，count 1，预生成属性弱于 AI 队）
WorldLevel beastLevel(const std::string& id, int32_t count = 1) {
    WorldLevel w;
    w.id = id;
    w.type = "BEAST";
    w.beastName = "测试妖兽";
    w.realm = 8;
    w.realmLayer = 1;
    w.count = count;
    w.beastMaxHp = 500;
    w.beastMaxMp = 100;
    w.beastPhysicalAttack = 50;
    w.beastMagicAttack = 50;
    w.beastPhysicalDefense = 30;
    w.beastMagicDefense = 30;
    w.beastSpeed = 20;
    w.spawnYear = 1;
    w.spawnMonth = 1;
    return w;
}

// ── 热控分批状态机（computeAIBatch 语义） ───────────────────────────

TEST(AiMonthBatchTest, FirstCallAlignsPhaseWithZeroBatch) {
    ops::AiMonthBatchState batch;
    EXPECT_EQ(ops::aiComputeBatch(batch, 100), 0);       // 首调：相位对齐、零批量
    EXPECT_EQ(batch.lastSettleMonth,
              (100 - 1) - ((100 - 1) % 3));              // 基准 = 99（向下取 3）
    EXPECT_EQ(ops::aiComputeBatch(batch, 101), 0);       // monthsSince 2 < 3
    EXPECT_EQ(ops::aiComputeBatch(batch, 102), 3);       // monthsSince 3 ≥ 3 → 批量 3
    EXPECT_EQ(batch.lastSettleMonth, 102);               // 基准推进
    EXPECT_EQ(ops::aiComputeBatch(batch, 103), 0);       // monthsSince 1 < 3
    EXPECT_EQ(ops::aiComputeBatch(batch, 105), 3);       // monthsSince 3 ≥ 3 → 批量 3
    EXPECT_EQ(ops::aiComputeBatch(batch, 104), 0);       // 时钟回退：0（不补修炼）
}

TEST(AiMonthBatchTest, ThermalBatchSizeControlsThreshold) {
    ops::AiMonthBatchState batch;
    batch.thermalBatchSize = 12;                          // 热紧急档
    batch.lastSettleMonth = 100;
    EXPECT_EQ(ops::aiComputeBatch(batch, 111), 0);        // monthsSince 11 < 12
    EXPECT_EQ(ops::aiComputeBatch(batch, 112), 12);       // 12 ≥ 12 → 批量 12
}

// ── AI 月度修炼（速率/突破/熟练度/孕养） ────────────────────────────

TEST(AiCultivationTest, ZeroRateFallsBackToMinPerPhase) {
    // realm 9（炼气）单灵根：base = realmSpeedPerPhase(9)，无加成 → 恒等乘区
    auto core = makeCore(kSeed);
    Disciple d = aiDisciple("1");
    const auto profs = ops::buildProficiencyDataFromMasteries(d);
    const double rate = ops::aiCultivationRate(d, profs);
    EXPECT_GE(rate, gamecore::disciple::kMinCultivationPerPhase);
    // 速率下限语义：floor 至少 1.0/旬
}

TEST(AiCultivationTest, CultivationAccumulatesAndBreakthroughRollConsumesAiRng) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    // 弟子修为推到突破线上（realm 9 layer 1，max = base + 0 层差）
    Disciple d = aiDisciple("1", 9, 1);
    d.cultivation = 1e6;   // 远超 max → while 循环触发突破尝试
    st.aiSectDisciples["ai-1"] = {d};

    const int64_t aiBefore = core->aiRng().snapshot();
    auto out = ops::aiProcessMonthlyCultivation(st.aiSectDisciples["ai-1"], 1, 0,
                                                core->aiRng());
    const int64_t aiAfter = core->aiRng().snapshot();
    EXPECT_NE(aiBefore, aiAfter);   // 突破尝试消耗 AI 分区
    ASSERT_EQ(out.size(), 1u);
    // 突破成功或失败：修为清零其一；境界/层数合法
    EXPECT_EQ(out[0].cultivation, 0.0);
    EXPECT_TRUE(out[0].realm <= 9);
    EXPECT_TRUE(out[0].realmLayer >= 1);
}

TEST(AiCultivationTest, ProficiencyGainMonthly) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    Disciple d = aiDisciple("1");
    d.manualIds = {"m1"};
    d.manualMasteries = {{"m1", 0}};
    st.aiSectDisciples["ai-1"] = {d};

    auto out = ops::aiProcessMonthlyCultivation(st.aiSectDisciples["ai-1"], 1, 0,
                                                core->aiRng());
    // 无模板 m1 → 熟练度原样保留（模板缺失键值不动——Kotlin 口径）
    EXPECT_EQ(out[0].manualMasteries.at("m1"), 0);

    // 真实模板 + 月度增益 = 6.0 × 2.0 × 3 = 36.0 → toInt 36
    const auto* tpl = &gamecore::data::manualTemplates().front();
    out[0].manualIds = {tpl->id};
    out[0].manualMasteries.clear();
    out[0].manualMasteries[tpl->id] = 0;
    out = ops::aiProcessMonthlyCultivation(out, 1, 0, core->aiRng());
    EXPECT_EQ(out[0].manualMasteries.at(tpl->id), 36);
}

TEST(AiNurtureTest, MonthlyGainProgressWithoutLevelUp) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    Disciple d = aiDisciple("1");
    // ironSword：rarity 1 → maxLevel 5；level 0 升级需 100.0×1×1.0 = 100
    d.weaponId = "ironSword";
    d.weaponNurture = {"ironSword", 1, 0, 0.0};
    st.aiSectDisciples["ai-1"] = {d};

    auto out = ops::aiProcessMonthlyCultivation(st.aiSectDisciples["ai-1"], 1, 0,
                                                core->aiRng());
    EXPECT_DOUBLE_EQ(out[0].weaponNurture.nurtureProgress, 30.0);   // 10×3
    EXPECT_EQ(out[0].weaponNurture.nurtureLevel, 0);
}

// ── 宗门等级同步 + 装备补全 ─────────────────────────────────────────

TEST(AiSectLevelTest, LevelUpOnQualifyingRealmAndGearTopUp) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    WorldSect sect;
    sect.id = "ai-1";
    sect.name = "万剑宗";
    sect.level = 0;   // SMALL
    sect.levelName = "小型宗门";
    st.gameData.worldMapSects.push_back(sect);
    st.aiSectDisciples["ai-1"] = {aiDisciple("1", 5, 1)};   // realm 5 ≤ 5 → 升 MEDIUM

    // 修炼零批量（等级同步与修炼独立——batch 0 时仍检查升级）
    ops::aiProcessSectOperations(st, core->aiRng(), 0);

    ASSERT_EQ(st.gameData.worldMapSects.size(), 1u);
    EXPECT_EQ(st.gameData.worldMapSects[0].level, 1);       // MEDIUM
    EXPECT_EQ(st.gameData.worldMapSects[0].levelName, "中型宗门");
    // 升级补全：装备 2 件 + 功法 3 本（MEDIUM 档）
    const auto& members = st.aiSectDisciples["ai-1"];
    ASSERT_EQ(members.size(), 1u);
    int equipCount = (members[0].weaponId.empty() ? 0 : 1) +
                     (members[0].armorId.empty() ? 0 : 1) +
                     (members[0].bootsId.empty() ? 0 : 1) +
                     (members[0].accessoryId.empty() ? 0 : 1);
    EXPECT_EQ(equipCount, 2);
    EXPECT_EQ(members[0].manualIds.size(), 3u);
    // 补全标记写入（防读档重复 roll）
    EXPECT_EQ(members[0].statusData.at("aiGearRolled"), "1");
}

TEST(AiSectWarehouseTest, NonPlayerSectWarehouseCleared) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    WorldSect sect;
    sect.id = "ai-1";
    sect.name = "万剑宗";
    sect.isPlayerSect = false;
    st.gameData.worldMapSects.push_back(sect);
    gamecore::state::SectDetail detail;
    detail.sectId = "ai-1";
    gamecore::state::WarehouseItem item;
    item.itemName = "测试物资";
    detail.warehouse.items.push_back(item);
    st.gameData.sectDetails["ai-1"] = detail;

    ops::aiProcessSectOperations(st, core->aiRng(), 0);
    EXPECT_TRUE(st.gameData.sectDetails["ai-1"].warehouse.items.empty());
}

// ── 兽战余量（processRemainingTargets） ─────────────────────────────

TEST(AiBeastBattleTest, VictoryMarksBeastDefeatedAndRecordsEvent) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    // 强 AI 弟子（仙人九层，preGen 妖兽 hp 500 → 碾压）
    Disciple d = aiDisciple("1", 0, 9);
    st.aiSectDisciples["ai-1"] = {d};
    WorldSect sect;
    sect.id = "ai-1";
    sect.name = "万剑宗";
    st.gameData.worldMapSects.push_back(sect);
    st.gameData.worldLevels.push_back(beastLevel("b1"));
    st.aiSectBeastDirectTargets["b1"] = {"ai-1"};

    const int64_t battleBefore = core->rng().getRng(rng::RngPartition::kBattle).snapshot();
    ops::aiProcessRemainingTargets(st, core->rng().getRng(rng::RngPartition::kBattle));

    // 击败标记 + 事件 + 目标清空 + 零死亡
    EXPECT_TRUE(st.gameData.worldLevels[0].defeated);
    ASSERT_EQ(st.gameData.gameEventRecords.size(), 1u);
    EXPECT_EQ(st.gameData.gameEventRecords[0].eventType, "ai_beast_hunt");
    EXPECT_TRUE(st.aiSectBeastDirectTargets.empty());
    EXPECT_TRUE(st.aiSectDisciples["ai-1"][0].isAlive);
    // BATTLE 分区消耗
    EXPECT_NE(core->rng().getRng(rng::RngPartition::kBattle).snapshot(), battleBefore);
}

TEST(AiBeastBattleTest, DefeatMarksAiDisciplesDeadAndRecordsFailEvent) {
    auto core = makeCore(kSeed + 1);
    GameState& st = core->state();
    // 弱 AI 弟子（炼气一层，hp 120）vs 妖兽 hp 500 → 必败
    st.aiSectDisciples["ai-1"] = {aiDisciple("1", 9, 1)};
    WorldSect sect;
    sect.id = "ai-1";
    sect.name = "万剑宗";
    st.gameData.worldMapSects.push_back(sect);
    st.gameData.worldLevels.push_back(beastLevel("b1"));
    st.aiSectBeastDirectTargets["b1"] = {"ai-1"};

    ops::aiProcessRemainingTargets(st, core->rng().getRng(rng::RngPartition::kBattle));

    ASSERT_EQ(st.gameData.gameEventRecords.size(), 1u);
    EXPECT_EQ(st.gameData.gameEventRecords[0].eventType, "ai_beast_fail");
    EXPECT_FALSE(st.gameData.worldLevels[0].defeated);   // 未击败
    EXPECT_FALSE(st.aiSectDisciples["ai-1"][0].isAlive); // 阵亡标记
    EXPECT_EQ(st.aiSectDisciples["ai-1"][0].status, "DEAD");
    EXPECT_TRUE(st.aiSectBeastDirectTargets.empty());    // 目标清空
}

TEST(AiBeastBattleTest, DefeatedBeastSkippedAndTargetsCleared) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    st.aiSectDisciples["ai-1"] = {aiDisciple("1", 0, 9)};
    WorldLevel b = beastLevel("b1");
    b.defeated = true;   // 已击败 → 消费方跳过
    st.gameData.worldLevels.push_back(b);
    st.aiSectBeastDirectTargets["b1"] = {"ai-1"};

    const int64_t battleBefore = core->rng().getRng(rng::RngPartition::kBattle).snapshot();
    ops::aiProcessRemainingTargets(st, core->rng().getRng(rng::RngPartition::kBattle));
    // 已击败妖兽跳过（零战斗），目标仍清空
    EXPECT_EQ(core->rng().getRng(rng::RngPartition::kBattle).snapshot(), battleBefore);
    EXPECT_TRUE(st.aiSectBeastDirectTargets.empty());
    EXPECT_TRUE(st.gameData.gameEventRecords.empty());
}

// ── 战前组装（prepareDisciplesForBattle） ───────────────────────────

TEST(AiPrepareBattleTest, BuildsInstancesFromPersistedFields) {
    auto core = makeCore(kSeed);
    GameState& st = core->state();
    Disciple d = aiDisciple("1", 8, 1);
    d.weaponId = "ironSword";
    d.weaponNurture = {"ironSword", 1, 2, 10.0};   // 孕养覆盖
    d.manualIds = {"ironSwordManualFake"};
    st.aiSectDisciples["ai-1"] = {d};
    st.gameData.worldLevels.push_back(beastLevel("b1"));

    const auto prepared = ops::aiPrepareDisciplesForBattle(st.aiSectDisciples["ai-1"]);
    // 装备实例：模板 id 即实例 id + 孕养覆盖
    const auto& eqMap = prepared.equipmentMapByDisciple.at("1");
    ASSERT_EQ(eqMap.size(), 1u);
    EXPECT_EQ(eqMap.at("ironSword").nurtureLevel, 2);
    EXPECT_DOUBLE_EQ(eqMap.at("ironSword").nurtureProgress, 10.0);
    EXPECT_EQ(eqMap.at("ironSword").physicalAttack,
              ops::aiEquipmentTemplateById("ironSword")->physicalAttack);
    // 未知功法模板跳过（manualMap 空）；熟练度映射仍建立
    EXPECT_TRUE(prepared.manualMap.empty());
    EXPECT_EQ(prepared.proficiencies.at("1").size(), 1u);
    EXPECT_EQ(prepared.proficiencies.at("1").at("ironSwordManualFake").manualId,
              "ironSwordManualFake");
}

}  // namespace

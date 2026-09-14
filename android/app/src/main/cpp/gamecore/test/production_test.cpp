// ============================================================
// production_test — 炼丹/锻造生产结算与自动排班守护
//
// 守护目标：production.h 等价移植 Kotlin ProductionProcessor（完成结算段）
// + FormulaService（乘区成功率/时长）+ ProfessionRules（晋升）+
// ProductionCoordinator（自动排班材料消耗）的语义逐位一致。
//
// RNG 审计（对拍命门，同 month_settlement_test 方法）：SYSTEM 分区播种
// fromSeed(seed + kSystem=3)——完成结算每到期槽恰 1 次成功 roll + 成功炼丹
// 再 1 次 grade roll；自动排班零抽取。测试用独立 DeterministicRng 预演同
// 序列断言分区快照。
// ============================================================

#include "gtest/gtest.h"

#include <cmath>
#include <memory>

#include "gamecore/game_core.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/production.h"

namespace {

using namespace gamecore;
namespace production = gamecore::system::production;
namespace profession = gamecore::system::profession;
using gamecore::state::Disciple;
using gamecore::state::GameState;
using gamecore::state::ProductionSlot;

/// herb 模板探针（detail 命名空间不外泄——测试内线性查）
const gamecore::data::HerbTemplate* herbTpl(const std::string& id) {
    for (const auto& t : gamecore::data::herbTemplates()) {
        if (t.id == id) return &t;
    }
    return nullptr;
}

/// 构建已初始化 GameCore（种子固定）
std::unique_ptr<GameCore> makeCore(int64_t seed) {
    auto core = std::unique_ptr<GameCore>(new GameCore(nullptr, nullptr));
    GameCoreConfig config;
    config.seedInitialized = true;
    config.systemSeed = seed;
    EXPECT_TRUE(core->initialize(config));
    return core;
}

/// 最小存活弟子（炼气一层；pillRefining/artifactRefining 默认 50）
Disciple baseDisciple(const std::string& id) {
    Disciple d;
    d.id = id;
    d.name = "弟子" + id;
    d.realm = 9;
    d.realmLayer = 1;
    d.isAlive = true;
    d.spiritRootType = "metal";
    d.age = 20;
    d.lifespan = 80;
    d.status = "IDLE";
    return d;
}

/// 炼丹槽（WORKING 到期：start (1,1)，duration 1 → (1,2) 完成）
ProductionSlot workingAlchemySlot(int32_t slotIndex, const std::string& recipeId,
                                  double successRate, const std::string& discipleId) {
    ProductionSlot s;
    s.id = "slot-a" + std::to_string(slotIndex);
    s.slotIndex = slotIndex;
    s.buildingType = "ALCHEMY";
    s.buildingId = "alchemy";
    s.status = "WORKING";
    s.recipeId = recipeId;
    s.recipeName = "引灵丹";
    s.startYear = 1;
    s.startMonth = 1;
    s.duration = 1;
    s.baseDuration = 1;
    s.successRate = successRate;
    s.assignedDiscipleId = discipleId;
    s.assignedDiscipleName = "弟子" + discipleId;
    return s;
}

/// 锻造槽（WORKING 到期）
ProductionSlot workingForgeSlot(int32_t slotIndex, const std::string& recipeId,
                                double successRate, const std::string& discipleId) {
    ProductionSlot s;
    s.id = "slot-f" + std::to_string(slotIndex);
    s.slotIndex = slotIndex;
    s.buildingType = "FORGE";
    s.buildingId = "forge";
    s.status = "WORKING";
    s.recipeId = recipeId;
    s.recipeName = "精铁剑";
    s.startYear = 1;
    s.startMonth = 1;
    s.duration = 1;
    s.baseDuration = 1;
    s.successRate = successRate;
    s.assignedDiscipleId = discipleId;
    s.assignedDiscipleName = "弟子" + discipleId;
    return s;
}

/// 灌入灵草堆（name/rarity 取 herb_db 模板——消耗按 name+rarity 匹配）
void addHerb(GameState& st, const std::string& herbId, int32_t qty) {
    const auto* tpl = herbTpl(herbId);
    ASSERT_NE(tpl, nullptr);
    state::Herb h;
    h.id = "herb-" + herbId;
    h.name = tpl->name;
    h.rarity = tpl->rarity;
    h.quantity = qty;
    st.herbs.push_back(h);
}

/// IDLE + autoRestart 炼丹槽
ProductionSlot idleAlchemySlot(const std::string& recipeId,
                               const std::string& discipleId) {
    ProductionSlot s;
    s.id = "slot-a0";
    s.slotIndex = 0;
    s.buildingType = "ALCHEMY";
    s.buildingId = "alchemy";
    s.status = "IDLE";
    s.autoRestartEnabled = true;
    if (!recipeId.empty()) s.recipeId = recipeId;
    if (!discipleId.empty()) {
        s.assignedDiscipleId = discipleId;
        s.assignedDiscipleName = "弟子" + discipleId;
    }
    return s;
}

/// IDLE + autoRestart 锻造槽
ProductionSlot idleForgeSlot(const std::string& discipleId) {
    ProductionSlot s;
    s.id = "slot-f0";
    s.slotIndex = 0;
    s.buildingType = "FORGE";
    s.buildingId = "forge";
    s.status = "IDLE";
    s.autoRestartEnabled = true;
    if (!discipleId.empty()) {
        s.assignedDiscipleId = discipleId;
        s.assignedDiscipleName = "弟子" + discipleId;
    }
    return s;
}

// ── ProfessionRules ────────────────────────────────────────────────

TEST(ProfessionRulesTest, MaxCraftableTierBounds) {
    EXPECT_EQ(profession::maxCraftableTier(0), 1);
    EXPECT_EQ(profession::maxCraftableTier(1), 2);
    EXPECT_EQ(profession::maxCraftableTier(5), 6);
    EXPECT_EQ(profession::maxCraftableTier(-3), 1);   // clamp 下界
    EXPECT_EQ(profession::maxCraftableTier(9), 6);    // clamp 上界
}

TEST(ProfessionRulesTest, PromotionTripleGateAndCountOnlyTopTier) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.alchemyLevel = 0;               // 无职业 → 可炼 tier 1
    d.alchemyPromotionCount = 0;
    d.pillRefining = 40;              // >= 首次晋升要求 40
    d.realm = 9;                      // <= 首次晋升要求 9
    st.disciples.appendDisciple(d);

    // 低阶不充数（tier != maxCraftableTier(0)=1）→ 零变化
    auto p0 = profession::applyPromotionProgress(st.disciples, 0, 2, true);
    EXPECT_FALSE(p0.promoted);
    EXPECT_EQ(st.disciples.alchemyPromotionCounts[0], 0);

    // 当前解锁最高阶（tier 1）成功 → 计数 1 >= 要求 1，三门槛全满足 → 晋升
    auto p1 = profession::applyPromotionProgress(st.disciples, 0, 1, true);
    EXPECT_TRUE(p1.promoted);
    EXPECT_EQ(p1.newLevel, 1);
    EXPECT_EQ(st.disciples.alchemyLevels[0], 1);
    EXPECT_EQ(st.disciples.alchemyPromotionCounts[0], 0);
}

TEST(ProfessionRulesTest, PromotionCounterAccumulatesWhenGateNotMet) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.alchemyLevel = 1;               // 炼丹师 → tier 2；成功次数要求 200
    d.pillRefining = 40;
    d.realm = 9;
    st.disciples.appendDisciple(d);

    auto p = profession::applyPromotionProgress(st.disciples, 0, 2, true);
    EXPECT_FALSE(p.promoted);
    EXPECT_EQ(st.disciples.alchemyPromotionCounts[0], 1);

    // 封顶（level 5）不再计数
    st.disciples.alchemyLevels[0] = 5;
    auto p2 = profession::applyPromotionProgress(st.disciples, 0, 6, true);
    EXPECT_FALSE(p2.promoted);
    EXPECT_EQ(st.disciples.alchemyPromotionCounts[0], 1);
}

// ── FormulaService 乘区 ────────────────────────────────────────────

TEST(FormulaTest, RealmSuccessRateBonusTable) {
    EXPECT_DOUBLE_EQ(production::detail::realmSuccessRateBonus(0), 0.30);
    EXPECT_DOUBLE_EQ(production::detail::realmSuccessRateBonus(8), 0.04);
    EXPECT_DOUBLE_EQ(production::detail::realmSuccessRateBonus(9), 0.0);
}

TEST(FormulaTest, SuccessRateZonesSynthesis) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.pillRefining = 80;              // skillZone = (80-30)*0.006 = 0.30
    d.alchemyLevel = 1;               // professionZone = (2-1)*0.20 = 0.20
    st.disciples.appendDisciple(d);

    // 无政策/长老/天赋：baseProb = clamp01(0 + 0.30 + 0.20) = 0.50；
    // realm 9 → 0 乘区 → final 0.50
    const double rate =
        production::detail::formulaSuccessRate(st, 0, "alchemy", 1, 0.0);
    EXPECT_DOUBLE_EQ(rate, 0.50);
}

TEST(FormulaTest, SkillZoneClampedAtHalf) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.pillRefining = 500;             // (500-30)*0.006 = 2.82 → clamp 0.50
    d.alchemyLevel = 0;
    st.disciples.appendDisciple(d);
    // baseProb = clamp01(0.50 + 0) = 0.50
    const double rate =
        production::detail::formulaSuccessRate(st, 0, "alchemy", 1, 0.0);
    EXPECT_DOUBLE_EQ(rate, 0.50);
}

// ── 完成结算 ──────────────────────────────────────────────────────

TEST(ProductionCompletionTest, AlchemySuccessProducesPillAndCounts) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.productionSlots.push_back(
        workingAlchemySlot(0, "cultivationSpeed_1_low", /*successRate=*/1.0, "1"));
    const size_t pillsBefore = st.pills.size();

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    // 产出（成功率 1.0 → grade roll 任一档模板必存在）
    ASSERT_EQ(st.pills.size(), pillsBefore + 1);
    EXPECT_EQ(st.pills.back().category, "CULTIVATION");
    EXPECT_EQ(st.pills.back().quantity, 1);
    EXPECT_FALSE(st.pills.back().id.empty());
    // 槽位重置 IDLE（保留弟子+配方供续炼；进度字段全清）
    const auto& slot = st.gameData.productionSlots[0];
    EXPECT_EQ(slot.status, "IDLE");
    ASSERT_TRUE(slot.assignedDiscipleId.has_value());
    EXPECT_EQ(*slot.assignedDiscipleId, "1");
    ASSERT_TRUE(slot.recipeId.has_value());
    EXPECT_EQ(*slot.recipeId, "cultivationSpeed_1_low");
    EXPECT_EQ(slot.startYear, 0);
    EXPECT_EQ(slot.duration, 0);
    EXPECT_EQ(slot.completionMonth, 0);
    // 弟子回 IDLE
    EXPECT_EQ(st.disciples.statuses[0], "IDLE");
    // 统计计数
    EXPECT_EQ(st.gameData.guideCounters["alchemyCompleted"], 1);
    EXPECT_EQ(st.gameData.annualAlchemyCount, 1);
}

TEST(ProductionCompletionTest, AlchemyRngAuditSuccessThenGradeRoll) {
    // RNG 审计：SYSTEM 分区恰 2 次抽取（成功率 roll + 成功 grade roll），
    // 快照 = 独立 DeterministicRng 连续 2 次后的值
    auto core = makeCore(77);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.productionSlots.push_back(
        workingAlchemySlot(0, "cultivationSpeed_1_low", 1.0, "1"));

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    auto probe = rng::DeterministicRng::fromSeed(77 + 3);
    (void)probe.nextDouble();
    (void)probe.nextDouble();
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(ProductionCompletionTest, AlchemyFailureStillCountsAndResets) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.productionSlots.push_back(
        workingAlchemySlot(0, "cultivationSpeed_1_low", /*successRate=*/0.0, "1"));

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    // 失败：无产出；槽位仍重置；计数无条件累加；弟子回 IDLE 零晋升
    EXPECT_TRUE(st.pills.empty());
    EXPECT_EQ(st.gameData.productionSlots[0].status, "IDLE");
    EXPECT_EQ(st.gameData.guideCounters["alchemyCompleted"], 1);
    EXPECT_EQ(st.disciples.statuses[0], "IDLE");
    EXPECT_EQ(st.disciples.alchemyLevels[0], 0);
    EXPECT_EQ(st.disciples.alchemyPromotionCounts[0], 0);
}

TEST(ProductionCompletionTest, ForgeSuccessProducesEquipment) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.productionSlots.push_back(
        workingForgeSlot(0, "ironSword", /*successRate=*/1.0, "1"));
    const size_t eqBefore = st.equipmentStacks.size();

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    ASSERT_EQ(st.equipmentStacks.size(), eqBefore + 1);
    EXPECT_EQ(st.equipmentStacks.back().name, "精铁剑");
    EXPECT_EQ(st.equipmentStacks.back().rarity, 1);
    EXPECT_EQ(st.gameData.productionSlots[0].status, "IDLE");
    EXPECT_EQ(st.gameData.guideCounters["forgeCompleted"], 1);
    EXPECT_EQ(st.gameData.annualForgeCount, 1);
    // 锻造恰 1 次 SYSTEM 抽取（无 grade roll）
    auto probe = rng::DeterministicRng::fromSeed(42 + 3);
    (void)probe.nextDouble();
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(ProductionCompletionTest, DeadDiscipleClearsSlotAssignment) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.isAlive = false;
    st.disciples.appendDisciple(d);
    st.gameData.productionSlots.push_back(
        workingAlchemySlot(0, "cultivationSpeed_1_low", 1.0, "1"));

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    // B3：弟子死亡 → 槽位重置同时清空弟子关联
    const auto& slot = st.gameData.productionSlots[0];
    EXPECT_EQ(slot.status, "IDLE");
    EXPECT_FALSE(slot.assignedDiscipleId.has_value());
    EXPECT_EQ(slot.assignedDiscipleName, "");
    // 配方仍保留（续炼语义）
    EXPECT_TRUE(slot.recipeId.has_value());
}

TEST(ProductionCompletionTest, InvalidRecipeRollsButNoPromotion) {
    // 无效配方：成功 roll 仍抽取（与 Kotlin rollProductionSuccess 先于产出一致），
    // 产出失败 → 不入库、晋升零进度（低阶不充数 recipeTier=0）
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.productionSlots.push_back(
        workingAlchemySlot(0, "nonexistent_recipe_low", 1.0, "1"));

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    EXPECT_TRUE(st.pills.empty());
    EXPECT_EQ(st.gameData.guideCounters["alchemyCompleted"], 1);
    EXPECT_EQ(st.disciples.alchemyPromotionCounts[0], 0);
}

TEST(ProductionCompletionTest, WorkingWithoutDiscipleSkipped) {
    // Kotlin：WORKING 且无弟子 → 跳过（不结算不重置、零抽取）
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.productionSlots.push_back(
        workingAlchemySlot(0, "cultivationSpeed_1_low", 1.0, ""));

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    EXPECT_EQ(st.gameData.productionSlots[0].status, "WORKING");
    EXPECT_TRUE(st.pills.empty());
    EXPECT_EQ(st.gameData.guideCounters.count("alchemyCompleted"), 0);
    // 零抽取：SYSTEM 分区保持初始
    auto probe = rng::DeterministicRng::fromSeed(42 + 3);
    EXPECT_EQ(probe.snapshot(),
              core->rng().getRng(rng::RngPartition::kSystem).snapshot());
}

TEST(ProductionCompletionTest, NotDueSlotUntouched) {
    // 未到期（start (1,2) duration 1 → (1,2) 未完成）→ 不结算零抽取
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    ProductionSlot s = workingAlchemySlot(0, "cultivationSpeed_1_low", 1.0, "1");
    s.startMonth = 2;
    st.gameData.productionSlots.push_back(s);

    production::processBuildingProductionStep(st, core->rng());

    EXPECT_EQ(st.gameData.productionSlots[0].status, "WORKING");
    EXPECT_TRUE(st.pills.empty());
}

TEST(ProductionCompletionTest, ForgeMatchedByIdAlchemyMatchedByType) {
    // 匹配口径守护：锻造按 buildingId（getSlotsByBuildingId）、炼丹按
    // buildingType（getSlotsByType）——脏 buildingType 的锻造槽不结算
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    ProductionSlot s = workingForgeSlot(0, "ironSword", 1.0, "1");
    s.buildingType = "WEIRD";  // buildingId 仍 "forge" → 按 id 匹配照常结算
    st.gameData.productionSlots.push_back(s);
    ProductionSlot a = workingAlchemySlot(0, "cultivationSpeed_1_low", 1.0, "1");
    a.buildingId = "weird";    // buildingType 仍 "ALCHEMY" → 按 type 匹配照常结算
    st.gameData.productionSlots.push_back(a);

    st.gameData.gameMonth = 2;  // 拨到 (1,2)：start (1,1) duration 1 → 到期
    production::processBuildingProductionStep(st, core->rng());

    EXPECT_EQ(st.gameData.productionSlots[0].status, "IDLE");
    EXPECT_EQ(st.gameData.productionSlots[1].status, "IDLE");
    EXPECT_EQ(st.gameData.annualForgeCount, 1);
    EXPECT_EQ(st.gameData.annualAlchemyCount, 1);
}

// ── 自动排班（autoRestart 续炼） ───────────────────────────────────

TEST(ProductionAutoTest, AutoRestartStartsAlchemyAndConsumesHerbs) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    addHerb(st, "spiritGrass1", 10);
    addHerb(st, "spiritFlower1", 10);
    st.gameData.productionSlots.push_back(idleAlchemySlot("cultivationSpeed_1_low", "1"));

    production::processAutoProductionStep(st);

    // 启动：WORKING + 材料扣减（各 -2）+ 公式成功率 + completionPhase 2
    const auto& slot = st.gameData.productionSlots[0];
    EXPECT_EQ(slot.status, "WORKING");
    ASSERT_TRUE(slot.recipeId.has_value());
    EXPECT_EQ(*slot.recipeId, "cultivationSpeed_1_low");
    EXPECT_EQ(slot.duration, 3);       // tier1 TIER_DURATION（无长老无政策 → ceil(3/1)=3）
    EXPECT_EQ(slot.baseDuration, 3);
    EXPECT_EQ(slot.completionPhase, 2);
    // 成功率：skill(50-30)*0.006=0.12 + 职业零阶差 0 → base 0.12；其余乘区 0
    EXPECT_DOUBLE_EQ(slot.successRate, 0.12);
    // 材料扣减（name+rarity 匹配）
    bool foundGrass = false;
    for (const auto& h : st.herbs) {
        if (h.name == "聚灵草") {
            foundGrass = true;
            EXPECT_EQ(h.quantity, 8);
        }
    }
    EXPECT_TRUE(foundGrass);
}

TEST(ProductionAutoTest, MaterialShortageSkips) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    addHerb(st, "spiritGrass1", 1);   // 不足 2
    st.gameData.productionSlots.push_back(idleAlchemySlot("cultivationSpeed_1_low", "1"));

    production::processAutoProductionStep(st);

    EXPECT_EQ(st.gameData.productionSlots[0].status, "IDLE");
    EXPECT_EQ(st.herbs[0].quantity, 1);
}

TEST(ProductionAutoTest, DeadDiscipleSkipped) {
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple d = baseDisciple("1");
    d.isAlive = false;
    st.disciples.appendDisciple(d);
    addHerb(st, "spiritGrass1", 10);
    addHerb(st, "spiritFlower1", 10);
    st.gameData.productionSlots.push_back(idleAlchemySlot("", "1"));

    production::processAutoProductionStep(st);

    EXPECT_EQ(st.gameData.productionSlots[0].status, "IDLE");
    EXPECT_EQ(st.herbs[0].quantity, 10);
}

TEST(ProductionAutoTest, AutoRestartForgeConsumesMaterials) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    state::Material m1;
    m1.id = "m1";
    m1.name = "凡虎血";
    m1.rarity = 1;
    m1.quantity = 10;
    state::Material m2;
    m2.id = "m2";
    m2.name = "凡虎牙";
    m2.rarity = 1;
    m2.quantity = 10;
    st.materials.push_back(m1);
    st.materials.push_back(m2);
    st.gameData.productionSlots.push_back(idleForgeSlot("1"));

    production::processAutoProductionStep(st);

    // tier1 无职业可锻 → ironSword（rarity 降序稳定排序后首个 tier<=1 的配方）
    const auto& slot = st.gameData.productionSlots[0];
    EXPECT_EQ(slot.status, "WORKING");
    ASSERT_TRUE(slot.recipeId.has_value());
    EXPECT_EQ(*slot.recipeId, "ironSword");
    EXPECT_EQ(slot.duration, 3);
    // 材料扣减 tigerBlood0 ×3 / tigerTooth0 ×2
    EXPECT_EQ(st.materials[0].quantity, 7);
    EXPECT_EQ(st.materials[1].quantity, 8);
}

TEST(ProductionAutoTest, PolicyAndElderAffectDurationAndRate) {
    // 丹道激励（成功率 +10%、时长 ×1.1）+ 长老（pillRefining 100 → 速度加成 0.20）
    auto core = makeCore(42);
    auto& st = core->state();
    Disciple elder = baseDisciple("9");
    elder.pillRefining = 100;         // getBaseStats → (100-80)*0.01 = 0.20
    st.disciples.appendDisciple(elder);
    st.disciples.appendDisciple(baseDisciple("1"));
    st.gameData.elderSlots.alchemyElder = "9";
    st.gameData.sectPolicies.alchemyIncentive = true;
    addHerb(st, "spiritGrass1", 10);
    addHerb(st, "spiritFlower1", 10);
    st.gameData.productionSlots.push_back(idleAlchemySlot("cultivationSpeed_1_low", "1"));

    production::processAutoProductionStep(st);

    const auto& slot = st.gameData.productionSlots[0];
    EXPECT_EQ(slot.status, "WORKING");
    // 时长：speed = 0.20（长老）→ ceil(3/1.2) = 3 → ×1.1 = 3.3 round 3 → max(3)=3
    EXPECT_EQ(slot.duration, 3);
    // 成功率：base 0.12；乘区 = 1 + realm0 + talent0 + policy0.10 + elder0.20 = 1.30
    // → 0.12 * 1.30 = 0.156
    EXPECT_DOUBLE_EQ(slot.successRate, 0.12 * 1.30);
}

// ── 同批多槽材料竞争（每槽实时余量语义——Kotlin 真实路径逐槽读同源） ──

TEST(ProductionAutoTest, AlchemyTwoSlotContentionSecondSkips) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    // 恰好一份配方材料（聚灵草×2 + 配方另味 ×2）——只够槽 1 启动
    addHerb(st, "spiritGrass1", 2);
    addHerb(st, "spiritFlower1", 2);
    ProductionSlot s1 = idleAlchemySlot("cultivationSpeed_1_low", "1");
    ProductionSlot s2 = idleAlchemySlot("cultivationSpeed_1_low", "1");
    s2.id = "slot-a1";
    s2.slotIndex = 1;
    st.gameData.productionSlots.push_back(s1);
    st.gameData.productionSlots.push_back(s2);

    production::processAutoProductionStep(st);

    // 槽 1 启动消耗全部材料；槽 2 必须按消耗后的实时余量判定 → 无配方可炼
    EXPECT_EQ(st.gameData.productionSlots[0].status, "WORKING");
    EXPECT_EQ(st.gameData.productionSlots[1].status, "IDLE");
    for (const auto& h : st.herbs) {
        EXPECT_EQ(h.quantity, 0) << "材料不得少扣/多扣";
    }
}

TEST(ProductionAutoTest, ForgeTwoSlotContentionSecondSkips) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    // 恰好一份 ironSword 材料（tigerBlood0 ×3 + tigerTooth0 ×2）——只够槽 1
    state::Material m1;
    m1.id = "m1";
    m1.name = "凡虎血";
    m1.rarity = 1;
    m1.quantity = 3;
    state::Material m2;
    m2.id = "m2";
    m2.name = "凡虎牙";
    m2.rarity = 1;
    m2.quantity = 2;
    st.materials.push_back(m1);
    st.materials.push_back(m2);
    ProductionSlot s1 = idleForgeSlot("1");
    ProductionSlot s2 = idleForgeSlot("1");
    s2.id = "slot-f1";
    s2.slotIndex = 1;
    st.gameData.productionSlots.push_back(s1);
    st.gameData.productionSlots.push_back(s2);

    production::processAutoProductionStep(st);

    // 守护点：材料余量索引逐槽实时重建——同批多槽竞争时不以旧余量选配方
    //（槽 2 无配方可锻 → IDLE，不白嫖启动）
    EXPECT_EQ(st.gameData.productionSlots[0].status, "WORKING");
    EXPECT_EQ(st.gameData.productionSlots[1].status, "IDLE");
    for (const auto& m : st.materials) {
        EXPECT_EQ(m.quantity, 0) << "材料不得少扣/多扣";
    }
}

// ── 交互排班事务（手动排班 C++ 真相先行） ─────────────────────

TEST(ProductionSchedulingTest, StartTransactionAlchemyHappyPath) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    addHerb(st, "spiritGrass1", 10);
    addHerb(st, "spiritFlower1", 10);
    st.gameData.productionSlots.push_back(idleAlchemySlot("cultivationSpeed_1_low", "1"));
    st.gameData.gameYear = 2;
    st.gameData.gameMonth = 3;

    const auto r = production::startProductionTransaction(
        st, "alchemy", 0, "cultivationSpeed_1_low", 0.25, 0.0, /*isAlchemy=*/true);
    EXPECT_TRUE(r.ok);
    const auto& slot = st.gameData.productionSlots[0];
    EXPECT_EQ(slot.status, "WORKING");
    ASSERT_TRUE(slot.recipeId.has_value());
    EXPECT_EQ(*slot.recipeId, "cultivationSpeed_1_low");
    EXPECT_EQ(slot.duration, 3);          // 重算合并（无长老无政策 ceil(3/1)=3）
    EXPECT_EQ(slot.baseDuration, 3);
    EXPECT_DOUBLE_EQ(slot.successRate, 0.25);
    EXPECT_EQ(slot.completionMonth, (2 * 12 + 3) + 3);
    EXPECT_EQ(slot.completionPhase, 2);
    EXPECT_EQ(slot.outputItemRarity, 1);
    // 材料精确扣减（各 -2）
    for (const auto& h : st.herbs) {
        if (h.name == "聚灵草") EXPECT_EQ(h.quantity, 8);
        if (h.name == "云雾花") EXPECT_EQ(h.quantity, 8);
    }
}

TEST(ProductionSchedulingTest, StartTransactionValidationChain) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    addHerb(st, "spiritGrass1", 1);   // 不足 2
    st.gameData.productionSlots.push_back(idleAlchemySlot("cultivationSpeed_1_low", "1"));
    // 配方不存在
    auto r = production::startProductionTransaction(
        st, "alchemy", 0, "no_such_recipe", 0.0, 0.0, true);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ(r.errorType, "RecipeNotFound");
    // 材料不足
    r = production::startProductionTransaction(
        st, "alchemy", 0, "cultivationSpeed_1_low", 0.0, 0.0, true);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ(r.errorType, "InsufficientMaterials");
    EXPECT_FALSE(r.missingMaterials.empty());
    // 槽位仍 IDLE（失败零写入）
    EXPECT_EQ(st.gameData.productionSlots[0].status, "IDLE");
    // 忙碌：强制 WORKING 后重试
    st.gameData.productionSlots[0].status = "WORKING";
    addHerb(st, "spiritGrass1", 10);
    r = production::startProductionTransaction(
        st, "alchemy", 0, "cultivationSpeed_1_low", 0.0, 0.0, true);
    EXPECT_FALSE(r.ok);
    EXPECT_EQ(r.errorType, "SlotBusy");
}

TEST(ProductionSchedulingTest, StartTransactionCreatesMissingSlot) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    addHerb(st, "spiritGrass1", 10);
    addHerb(st, "spiritFlower1", 10);
    // 无预置槽位 → ensure 创建 IDLE 后启动
    const auto r = production::startProductionTransaction(
        st, "alchemy", 2, "cultivationSpeed_1_low", 0.0, 0.0, true);
    EXPECT_TRUE(r.ok);
    ASSERT_EQ(st.gameData.productionSlots.size(), 1u);
    EXPECT_EQ(st.gameData.productionSlots[0].slotIndex, 2);
    EXPECT_EQ(st.gameData.productionSlots[0].buildingType, "ALCHEMY");
    EXPECT_EQ(st.gameData.productionSlots[0].status, "WORKING");
}

TEST(ProductionSchedulingTest, ResetTransactionClearsSlot) {
    auto core = makeCore(42);
    auto& st = core->state();
    auto slot = idleAlchemySlot("cultivationSpeed_1_low", "1");
    slot.status = "WORKING";
    st.gameData.productionSlots.push_back(slot);
    EXPECT_TRUE(production::resetProductionSlotTransaction(st, "alchemy", 0));
    const auto& s = st.gameData.productionSlots[0];
    EXPECT_EQ(s.status, "IDLE");
    // resetSlotToIdle 语义：无条件保留配方（供续炼——S4/B3 口径）+ 弟子不保
    ASSERT_TRUE(s.recipeId.has_value());
    EXPECT_EQ(*s.recipeId, "cultivationSpeed_1_low");
    EXPECT_FALSE(s.assignedDiscipleId.has_value());
    EXPECT_EQ(s.completionPhase, 1);
    // 不存在的槽位 → false
    EXPECT_FALSE(production::resetProductionSlotTransaction(st, "forge", 9));
}

TEST(ProductionSchedulingTest, StartTransactionNativeSuccessRate) {
    auto core = makeCore(42);
    auto& st = core->state();
    st.disciples.appendDisciple(baseDisciple("1"));
    addHerb(st, "spiritGrass1", 10);
    addHerb(st, "spiritFlower1", 10);
    st.gameData.productionSlots.push_back(idleAlchemySlot("cultivationSpeed_1_low", "1"));
    // successRate < 0 哨兵 → 原生 formulaSuccessRate（无长老无政策 → 0.12，
    // 与自动排班同式——AutoRestartStartsAlchemyAndConsumesHerbs 同值）
    const auto r = production::startProductionTransaction(
        st, "alchemy", 0, "cultivationSpeed_1_low", -1.0, 0.0, true);
    EXPECT_TRUE(r.ok);
    EXPECT_DOUBLE_EQ(st.gameData.productionSlots[0].successRate, 0.12);
}

}  // namespace

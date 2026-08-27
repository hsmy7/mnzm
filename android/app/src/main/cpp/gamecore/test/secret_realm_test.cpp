#include <gtest/gtest.h>

#include <cmath>
#include <map>
#include <string>
#include <vector>

#include "gamecore/rng/rng_manager.h"
#include "gamecore/state/models.h"
#include "gamecore/system/secret_realm.h"

namespace gamecore::system {
namespace {

using gamecore::rng::RngManager;
using gamecore::rng::RngPartition;
using gamecore::state::SecretRealmAITeam;
using gamecore::state::SecretRealmBackpack;
using gamecore::state::SecretRealmEventRecord;
using gamecore::state::SecretRealmMemberState;
using gamecore::state::WorldSect;
using gamecore::state::Disciple;

SecretRealmMemberState member(const std::string& id, int32_t realm, bool dead = false) {
    SecretRealmMemberState m;
    m.discipleId = id;
    m.name = "弟子" + id;
    m.realm = realm;
    m.isDead = dead;
    return m;
}

// ── playerAvgRealm ─────────────────────────────────────────────

TEST(SecretRealmTest, PlayerAvgRealmTruncates) {
    const std::vector<SecretRealmMemberState> members = {
        member("1", 3), member("2", 4), member("3", 5), member("4", 6)};
    EXPECT_EQ(secretRealmPlayerAvgRealm(members), 4);  // (3+4+5+6)/4 = 4.5 → 4
}

TEST(SecretRealmTest, PlayerAvgRealmSkipsDead) {
    const std::vector<SecretRealmMemberState> members = {
        member("1", 3), member("2", 4, /*dead=*/true), member("3", 5), member("4", 6, /*dead=*/true)};
    EXPECT_EQ(secretRealmPlayerAvgRealm(members), 4);  // (3+5)/2 = 4
}

TEST(SecretRealmTest, PlayerAvgRealmAllDeadTakesMax) {
    const std::vector<SecretRealmMemberState> members = {
        member("1", 3, /*dead=*/true), member("2", 4, /*dead=*/true)};
    EXPECT_EQ(secretRealmPlayerAvgRealm(members), 9);  // 全灭取 REALM_MAX
}

// ── rollBeastRealm ─────────────────────────────────────────────

TEST(SecretRealmTest, RollBeastRealmClampedRange) {
    RngManager rng;
    rng.initSystemSeed(42);
    for (int32_t avg : {0, 3, 5, 9}) {
        for (int32_t i = 0; i < 500; ++i) {
            const int32_t realm = rollSecretRealmBeastRealm(rng, avg);
            EXPECT_GE(realm, 0);
            EXPECT_LE(realm, 9);
            // clamp 后窗口 [max(0,avg-1), min(9,avg+2)]
            const int32_t lo = std::clamp(avg - 1, 0, 9);
            const int32_t hi = std::clamp(avg + 2, 0, 9);
            EXPECT_GE(realm, lo);
            EXPECT_LE(realm, hi);
        }
    }
}

TEST(SecretRealmTest, RollBeastRealmDeterministic) {
    RngManager a, b;
    a.initSystemSeed(7);
    b.initSystemSeed(7);
    for (int32_t i = 0; i < 100; ++i) {
        EXPECT_EQ(rollSecretRealmBeastRealm(a, 5), rollSecretRealmBeastRealm(b, 5));
    }
}

// ── generateBeastEvent ─────────────────────────────────────────

TEST(SecretRealmTest, GenerateBeastEventFields) {
    RngManager rng;
    rng.initSystemSeed(42);
    const auto event = generateSecretRealmBeastEvent(rng, 5);
    EXPECT_EQ(event.eventType, secret_realm_type::kBeastEncounter);
    EXPECT_EQ(event.title, "遭遇妖兽");
    EXPECT_EQ(event.options.size(), 3);
    EXPECT_GE(event.params.beastRealm, std::clamp(5 - 1, 0, 9));
    EXPECT_LE(event.params.beastRealm, std::clamp(5 + 2, 0, 9));
    EXPECT_GE(event.params.beastLayer, 1);
    EXPECT_LE(event.params.beastLayer, 9);
    EXPECT_GE(event.params.beastCount, 1);
    EXPECT_LE(event.params.beastCount, 6);
    EXPECT_FALSE(event.params.beastTypeName.empty());
    EXPECT_FALSE(event.description.empty());
}

TEST(SecretRealmTest, GenerateBeastEventConsumesFourRandomCalls) {
    // RNG 消费：类型 1 + 境界 1 + 层数 1 + 数量 1 = 4 次（确定性红线）
    RngManager a, b;
    a.initSystemSeed(99);
    b.initSystemSeed(99);
    const auto event = generateSecretRealmBeastEvent(a, 5);
    // b 模拟等价消费：4 次 nextInt 后状态应等于 a 的下一次调用前状态
    b.getRng(RngPartition::kSecretRealm).nextInt(8);
    b.getRng(RngPartition::kSecretRealm).nextInt(4);  // rollBeastRealm 窗口 (avg=5: [4,7] → 4 档)
    b.getRng(RngPartition::kSecretRealm).nextInt(9);
    b.getRng(RngPartition::kSecretRealm).nextInt(6);
    EXPECT_EQ(a.getRng(RngPartition::kSecretRealm).snapshot(),
              b.getRng(RngPartition::kSecretRealm).snapshot());
    (void)event;
}

// ── 无随机事件 ─────────────────────────────────────────────────

TEST(SecretRealmTest, RestAreaAndRuinsAndDirectionEvents) {
    const auto rest = generateSecretRealmRestAreaEvent();
    EXPECT_EQ(rest.eventType, secret_realm_type::kRestArea);
    EXPECT_EQ(rest.options.size(), 2);

    const auto ruins = generateSecretRealmRuinsEvent();
    EXPECT_EQ(ruins.eventType, secret_realm_type::kRuinExplore);
    EXPECT_EQ(ruins.options.size(), 3);
    EXPECT_EQ(ruins.options[2].staminaCost, 2);  // 仔细搜寻扣 2

    const auto dir = generateSecretRealmDirectionEvent("战斗胜利");
    EXPECT_EQ(dir.eventType, secret_realm_type::kDirectionChoice);
    EXPECT_EQ(dir.description, "战斗胜利，请选择探索方向");
    EXPECT_EQ(generateSecretRealmDirectionEvent("").description, "请选择探索方向");
}

// ── rollNextEvent 分段 ─────────────────────────────────────────

TEST(SecretRealmTest, RollNextEventDeterministicAndValid) {
    RngManager a, b;
    a.initSystemSeed(3);
    b.initSystemSeed(3);
    std::vector<SecretRealmAITeam> teams;
    SecretRealmAITeam t;
    t.sectId = "ai-1";
    t.sectName = "万剑宗";
    t.sectLevel = 1;
    teams.push_back(t);
    for (int32_t i = 0; i < 300; ++i) {
        const auto ea = rollSecretRealmNextEvent(a, 5, teams);
        const auto eb = rollSecretRealmNextEvent(b, 5, teams);
        EXPECT_EQ(ea.eventType, eb.eventType);  // 确定性
        EXPECT_TRUE(ea.eventType == secret_realm_type::kRestArea ||
                    ea.eventType == secret_realm_type::kRuinExplore ||
                    ea.eventType == secret_realm_type::kAiSectEncounter ||
                    ea.eventType == secret_realm_type::kBeastEncounter);
        if (ea.eventType == secret_realm_type::kAiSectEncounter) {
            EXPECT_EQ(ea.params.aiSectId, "ai-1");
        }
    }
}

// ── buildBeastPreGenStats ──────────────────────────────────────

TEST(SecretRealmTest, BuildBeastPreGenStatsBasics) {
    RngManager rng;
    rng.initSystemSeed(42);
    const auto stats = buildSecretRealmBeastPreGenStats(rng, 9, "虎妖", false, 1);
    EXPECT_GE(stats.maxHp, 1);
    EXPECT_GE(stats.maxMp, 1);
    EXPECT_GE(stats.physicalAttack, 1);
    EXPECT_EQ(stats.physicalAttack, stats.magicAttack);   // Kotlin atk 两用
    EXPECT_EQ(stats.physicalDefense, stats.magicDefense); // Kotlin def 两用
    EXPECT_EQ(stats.realmLayer, 1);
}

TEST(SecretRealmTest, BuildBeastPreGenStatsAmbushReducesHp) {
    RngManager a, b;
    a.initSystemSeed(7);
    b.initSystemSeed(7);
    const auto normal = buildSecretRealmBeastPreGenStats(a, 5, "狼妖", false, 3);
    const auto ambush = buildSecretRealmBeastPreGenStats(b, 5, "狼妖", true, 3);
    EXPECT_LT(ambush.maxHp, normal.maxHp);  // 偷袭 -10% 血量
    EXPECT_EQ(normal.maxMp, ambush.maxMp);   // 仅血量受影响
    EXPECT_EQ(normal.realmLayer, 3);
    EXPECT_EQ(ambush.realmLayer, 3);
}

TEST(SecretRealmTest, BuildBeastPreGenStatsClampsLayer) {
    RngManager rng;
    rng.initSystemSeed(42);
    EXPECT_EQ(buildSecretRealmBeastPreGenStats(rng, 9, "虎妖", false, 99).realmLayer, 9);
    EXPECT_EQ(buildSecretRealmBeastPreGenStats(rng, 9, "虎妖", false, -3).realmLayer, 1);
    // 未知妖兽类型回退 TYPES[0]（虎妖）
    EXPECT_GT(buildSecretRealmBeastPreGenStats(rng, 9, "未知妖", false, 1).maxHp, 0);
}

// ── rollBeastLoot ──────────────────────────────────────────────

TEST(SecretRealmTest, RollBeastLootCountAndWeights) {
    RngManager rng;
    rng.initSystemSeed(42);
    const auto rewards = rollSecretRealmBeastLoot(rng, "虎妖", 9, 3);
    EXPECT_EQ(rewards.size(), 6);  // beastCount * 2
    for (const auto& r : rewards) {
        EXPECT_EQ(r.type, "material");
        EXPECT_EQ(r.quantity, 1);
        EXPECT_FALSE(r.itemId.empty());
    }
}

TEST(SecretRealmTest, RollBeastLootUnknownTypeEmpty) {
    RngManager rng;
    rng.initSystemSeed(42);
    EXPECT_TRUE(rollSecretRealmBeastLoot(rng, "不存在妖", 5, 2).empty());
}

// ── generateRuinsTreasure ──────────────────────────────────────

TEST(SecretRealmTest, GenerateRuinsTreasureCountAndRarity) {
    RngManager rng;
    rng.initSystemSeed(42);
    SecretRealmTypeCandidates candidates;
    candidates["equipment"][3] = {{"eq1", "木剑"}, {"eq2", "铁剑"}};
    candidates["material"][3] = {{"m1", "铁块"}};
    candidates["herb"][3] = {{"h1", "灵芝"}};
    const auto rewards = generateSecretRealmRuinsTreasure(rng, 2, 4, 2, 3, candidates);
    EXPECT_GE(rewards.size(), 2);
    EXPECT_LE(rewards.size(), 4);
    for (const auto& r : rewards) {
        EXPECT_GE(r.rarity, 2);
        EXPECT_LE(r.rarity, 3);
        EXPECT_EQ(r.quantity, 1);
    }
}

TEST(SecretRealmTest, GenerateRuinsTreasureDataHoleDegrades) {
    // 候选全空 → 数据空洞 → 空列表（RNG 消费不越界）
    RngManager rng;
    rng.initSystemSeed(42);
    SecretRealmTypeCandidates empty;
    EXPECT_TRUE(generateSecretRealmRuinsTreasure(rng, 1, 3, 2, 4, empty).empty());
}

// ── resolveRuins ───────────────────────────────────────────────

TEST(SecretRealmTest, ResolveRuinsLeaveNoRandom) {
    RngManager a, b;
    a.initSystemSeed(42);
    b.initSystemSeed(42);
    SecretRealmTypeCandidates candidates;
    const std::vector<SecretRealmMemberState> members = {member("1", 5)};
    const SecretRealmBackpack backpack;
    const auto r = resolveSecretRealmRuinsExplore(0, members, backpack, a, candidates);
    EXPECT_EQ(r.resultText, "你方决定离开遗迹，继续探索");
    EXPECT_EQ(r.nextEvent.eventType, secret_realm_type::kDirectionChoice);
    // 离开不消费 RNG
    EXPECT_EQ(a.getRng(RngPartition::kSecretRealm).snapshot(),
              b.getRng(RngPartition::kSecretRealm).snapshot());
}

TEST(SecretRealmTest, ResolveRuinsSearchDeterministic) {
    RngManager a, b;
    a.initSystemSeed(7);
    b.initSystemSeed(7);
    SecretRealmTypeCandidates candidates;
    candidates["equipment"][3] = {{"eq1", "木剑"}, {"eq2", "铁剑"}};
    candidates["pill"][3] = {{"p1", "回春丹"}};
    const std::vector<SecretRealmMemberState> members = {member("1", 5)};
    const SecretRealmBackpack backpack;
    for (int32_t i = 0; i < 50; ++i) {
        const auto ra = resolveSecretRealmRuinsExplore(1, members, backpack, a, candidates);
        const auto rb = resolveSecretRealmRuinsExplore(1, members, backpack, b, candidates);
        EXPECT_EQ(ra.resultText, rb.resultText);
        EXPECT_EQ(ra.nextEvent.eventType, secret_realm_type::kDirectionChoice);
        EXPECT_EQ(ra.params.itemRewards.size(), rb.params.itemRewards.size());
    }
}

TEST(SecretRealmTest, ResolveRuinsResultKeepsParams) {
    SecretRealmEventRecord event;
    event.eventType = secret_realm_type::kRuinResult;
    gamecore::state::SecretRealmRewardItem item;
    item.type = "equipment";
    item.itemId = "eq1";
    item.name = "木剑";
    item.rarity = 2;
    event.params.itemRewards.push_back(item);
    const std::vector<SecretRealmMemberState> members = {member("1", 5)};
    const SecretRealmBackpack backpack;
    const auto r = resolveSecretRealmRuinsResult(members, backpack, event);
    EXPECT_EQ(r.resultText, "你方携秘宝离开遗迹，继续前行");
    EXPECT_EQ(r.params.itemRewards.size(), 1);  // 保留描述符
    const auto r2 = resolveSecretRealmRuinsResult(members, backpack, SecretRealmEventRecord{});
    EXPECT_EQ(r2.resultText, "遗迹中空无一物，你方继续前行");
}

// ── applyLootLoss ──────────────────────────────────────────────

TEST(SecretRealmTest, ApplyLootLossRatioRange) {
    RngManager rng;
    rng.initSystemSeed(42);
    SecretRealmBackpack backpack;
    backpack.spiritStones = 1000;
    gamecore::state::EquipmentStack eq;
    eq.id = "eq1";
    eq.name = "木剑";
    eq.rarity = 1;
    backpack.equipment = {eq, eq, eq, eq, eq};  // 5 件
    const auto r = applySecretRealmLootLoss(backpack, rng);
    // 比例 0.20~0.45；5 件 → ceil(1~2.25) ∈ {1,2,3}
    EXPECT_GE(r.lostItemCount, 1);
    EXPECT_LE(r.lostItemCount, 3);
    EXPECT_GE(r.lostSpiritStones, 200);
    EXPECT_LE(r.lostSpiritStones, 450);
    EXPECT_EQ(r.backpack.equipment.size() + r.lostItemCount, 5);
}

TEST(SecretRealmTest, ApplyLootLossDeterministic) {
    RngManager a, b;
    a.initSystemSeed(7);
    b.initSystemSeed(7);
    SecretRealmBackpack backpack;
    backpack.spiritStones = 999;
    gamecore::state::EquipmentStack eq;
    eq.id = "eq1";
    eq.name = "木剑";
    eq.rarity = 1;
    backpack.equipment = {eq, eq, eq};
    gamecore::state::Pill pill;
    pill.id = "p1";
    pill.name = "回春丹";
    backpack.pills = {pill, pill};
    for (int32_t i = 0; i < 20; ++i) {
        const auto ra = applySecretRealmLootLoss(backpack, a);
        const auto rb = applySecretRealmLootLoss(backpack, b);
        EXPECT_EQ(ra.lostItemCount, rb.lostItemCount);
        EXPECT_EQ(ra.lostSpiritStones, rb.lostSpiritStones);
        EXPECT_EQ(ra.backpack.equipment.size(), rb.backpack.equipment.size());
        EXPECT_EQ(ra.backpack.pills.size(), rb.backpack.pills.size());
    }
}

TEST(SecretRealmTest, ApplyLootLossEmptyNoOp) {
    RngManager rng;
    rng.initSystemSeed(42);
    SecretRealmBackpack backpack;  // 空背包 + 0 灵石
    const auto r = applySecretRealmLootLoss(backpack, rng);
    EXPECT_EQ(r.lostItemCount, 0);
    EXPECT_EQ(r.lostSpiritStones, 0);
}

// ── stamina ────────────────────────────────────────────────────

TEST(SecretRealmTest, StaminaAfterChoiceNormalAndClamped) {
    SecretRealmEventRecord event;
    event.options = {{"选项A", "", 1}, {"选项B", "", 2}};
    gamecore::state::SecretRealmExplorationSession session;
    session.stamina = 20;
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 0), 19);
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 1), 18);
    // 越界选项 → 默认扣 1
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 99), 19);
    // 非法消耗（0/负数/超大）clamp 到 1..20
    event.options[1].staminaCost = 0;
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 1), 19);
    event.options[1].staminaCost = -5;
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 1), 19);
    event.options[1].staminaCost = 100;
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 1), 0);  // 单次最多耗尽
    // 体力下限 0
    session.stamina = 1;
    EXPECT_EQ(secretRealmStaminaAfterChoice(session, event, 0), 0);
}

// ── findPosition / yearly spawn ────────────────────────────────

TEST(SecretRealmTest, FindPositionAvoidsSects) {
    RngManager rng;
    rng.initSystemSeed(42);
    std::vector<WorldSect> sects;
    WorldSect s;
    s.id = "player";
    s.name = "青云宗";
    s.x = 849.0f;
    s.y = 463.0f;
    sects.push_back(s);
    const auto pos = findSecretRealmPosition(rng, sects);
    EXPECT_GE(pos.first, 34);
    EXPECT_LT(pos.first, 1698 - 34);
    EXPECT_GE(pos.second, 34);
    EXPECT_LT(pos.second, 926 - 34);
    // 与宗门距离 ≥ 40
    const float dx = s.x - static_cast<float>(pos.first);
    const float dy = s.y - static_cast<float>(pos.second);
    EXPECT_GE(dx * dx + dy * dy, 1600.0f);
}

TEST(SecretRealmTest, FindPositionFallbackWhenNoFreeSpot) {
    // 全图铺满宗门 → 兜底扫描返回最远点（确定性，无 RNG 消费）
    RngManager rng;
    rng.initSystemSeed(42);
    std::vector<WorldSect> sects;
    for (int32_t x = 50; x < 1650; x += 40) {
        for (int32_t y = 50; y < 880; y += 40) {
            WorldSect s;
            s.id = "s" + std::to_string(sects.size());
            s.x = static_cast<float>(x);
            s.y = static_cast<float>(y);
            sects.push_back(s);
        }
    }
    const auto pos = findSecretRealmPosition(rng, sects);
    EXPECT_GE(pos.first, 34);
    EXPECT_GE(pos.second, 34);
}

TEST(SecretRealmTest, YearlySpawnEligible) {
    EXPECT_FALSE(secretRealmYearlySpawnEligible(1, 0));    // 首次第 50 年
    EXPECT_FALSE(secretRealmYearlySpawnEligible(49, 0));
    EXPECT_TRUE(secretRealmYearlySpawnEligible(50, 0));
    EXPECT_TRUE(secretRealmYearlySpawnEligible(51, 0));
    EXPECT_FALSE(secretRealmYearlySpawnEligible(100, 60)); // 上次消失 60 → 需到 110
    EXPECT_TRUE(secretRealmYearlySpawnEligible(110, 60));
    EXPECT_FALSE(secretRealmYearlySpawnEligible(49, -100)); // 负冷却 clamp 到 0 → 49-0=49 < 50
    EXPECT_TRUE(secretRealmYearlySpawnEligible(50, -100));
}

TEST(SecretRealmTest, RollSpriteIndexRange) {
    RngManager rng;
    rng.initSystemSeed(42);
    for (int32_t i = 0; i < 50; ++i) {
        const int32_t idx = rollSecretRealmSpriteIndex(rng);
        EXPECT_GE(idx, 0);
        EXPECT_LT(idx, 3);
    }
}

// ── dispatchAiTeams ────────────────────────────────────────────

TEST(SecretRealmTest, DispatchAiTeamsFiltersAndSorts) {
    std::vector<SecretRealmAiPool> pools;
    SecretRealmAiPool pool;
    pool.sectId = "ai-1";
    pool.sectName = "万剑宗";
    pool.sectFound = true;
    pool.sectLevel = 2;
    Disciple d;
    d.isAlive = true;
    d.realm = 9;
    for (int32_t i = 0; i < 6; ++i) {
        d.id = "d" + std::to_string(i);
        d.name = "弟子" + d.id;
        d.realm = 9 - i;  // d0 境界最高（0）
        pool.disciples.push_back(d);
    }
    d.id = "dead1";
    d.name = "亡者";
    d.realm = 0;
    d.isAlive = false;
    pool.disciples.push_back(d);
    pools.push_back(pool);

    const auto teams = dispatchSecretRealmAiTeams(pools);
    ASSERT_EQ(teams.size(), 1);
    EXPECT_EQ(teams[0].sectName, "万剑宗");
    EXPECT_EQ(teams[0].sectLevel, 2);
    EXPECT_EQ(teams[0].members.size(), 4);  // take AI_TEAM_SIZE
    // 境界升序（数值小 = 境界高）：d5(4) d4(5) d3(6) d2(7) 前 4 名（死亡 d0 除外）
    EXPECT_EQ(teams[0].members[0].realm, 4);
    EXPECT_EQ(teams[0].members[3].realm, 7);
    // 死亡弟子不参与
    for (const auto& m : teams[0].members) {
        EXPECT_NE(m.discipleId, "dead1");
    }
}

TEST(SecretRealmTest, DispatchAiTeamsSkipsEmptyAndFallsBackName) {
    std::vector<SecretRealmAiPool> pools;
    SecretRealmAiPool pool;
    pool.sectId = "ai-2";  // 无 sectName → 回退 sectId
    pool.sectLevel = 0;
    Disciple d;
    d.isAlive = false;
    d.id = "x";
    pool.disciples.push_back(d);  // 全部死亡 → 不派遣
    pools.push_back(pool);
    EXPECT_TRUE(dispatchSecretRealmAiTeams(pools).empty());
}

}  // namespace
}  // namespace gamecore::system

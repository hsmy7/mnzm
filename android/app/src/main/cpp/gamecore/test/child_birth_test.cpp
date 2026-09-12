// ============================================================
// child_birth_test.cpp — 月变步骤 4d 生育黄金序列
//
// 守护目标：固定种子 + 固定状态 → child_birth::processMonthlyBirth →
// 断言新生儿（recruitList）与母亲状态（lastChildYear/childBirthMonth）
// 逐字段黄金值。黄金值来源：Kotlin ChildBirthSystem.processMonthlyBirth
// 经 DiffMonthSettlementTest 场景⑯（同种子同消费序跨语言逐位一致）确认
// 后固化——本文件防 C++ 侧回归漂移。
//
// RNG：SYSTEM 分区 DeterministicRng（月变对拍中 kSystem 分区）；固定种子
// 固定输出。新生儿 id 为镜像生成字段（C++ 空串占位），黄金断言不含 id。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/ecs/disciple_component.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"
#include "gamecore/system/child_birth.h"

namespace {

using gamecore::rng::DeterministicRng;
using gamecore::state::Disciple;
using gamecore::state::DiscipleStore;
using gamecore::state::GameState;

/// 基础弟子（炼气一层；id 数字字符串——与 Kotlin DiscipleTables 列式存储
/// 解析约束一致）
Disciple baseDisciple(const std::string& id, const std::string& name,
                      const std::string& gender) {
    Disciple d;
    d.id = id;
    d.name = name;
    d.gender = gender;
    d.age = 20;
    d.realm = 9;
    d.realmLayer = 1;
    d.spiritRootType = "metal";
    d.isAlive = true;
    return d;
}

/// 场景⑯ 同形状状态：到期母亲（childBirthMonth=2）+ partner 互指父亲 +
/// 额外弟子（单男无女，配对早退无关）
GameState makeBirthState() {
    GameState state;
    state.gameData.gameYear = 1;
    state.gameData.gameMonth = 2;
    auto mother = baseDisciple("20", "母一", "female");
    mother.partnerId = "21";
    mother.childBirthMonth = 2;
    auto father = baseDisciple("21", "父一", "male");
    father.partnerId = "20";
    state.disciples.appendDisciple(mother);
    state.disciples.appendDisciple(father);
    state.disciples.appendDisciple(baseDisciple("22", "闲一", "male"));
    return state;
}

TEST(ChildBirth, GoldenSequenceSingleBirth) {
    gamecore::ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    // SYSTEM 分区种子对齐对拍场景⑯（fromSeed(seed + partitionId) +
    // 3 次 nextInt 预热——Kotlin initialRngStates 同式）——黄金值即 Kotlin
    // ChildBirthSystem 同消费序输出（DiffMonthSettlementTest 场景⑯ 对拍确认）
    auto rng = DeterministicRng::fromSeed(20260901 + 3);
    for (int i = 0; i < 3; ++i) rng.nextInt();
    auto state = makeBirthState();
    gamecore::system::child_birth::processMonthlyBirth(state, rng, world);

    ASSERT_EQ(1u, state.gameData.recruitList.size());
    const auto& child = state.gameData.recruitList.front();
    // 黄金值（Kotlin 同种子同消费序输出；id 为镜像生成字段——C++ 空串占位不断言）
    EXPECT_EQ("父丹青", child.name);
    EXPECT_EQ("父", child.surname);
    EXPECT_EQ("male", child.gender);
    EXPECT_EQ(1, child.age);
    EXPECT_EQ(0, child.realmLayer);
    EXPECT_EQ("metal", child.spiritRootType);
    EXPECT_EQ("20", child.parentId1);
    EXPECT_EQ("21", child.parentId2);
    EXPECT_EQ("male_disciple_1", child.portraitRes);
    EXPECT_EQ(-9, child.hpVariance);
    EXPECT_EQ(28, child.mpVariance);
    EXPECT_EQ(2, child.physicalAttackVariance);
    EXPECT_EQ(10, child.magicAttackVariance);
    EXPECT_EQ(26, child.physicalDefenseVariance);
    EXPECT_EQ(-7, child.magicDefenseVariance);
    EXPECT_EQ(-3, child.speedVariance);
    EXPECT_EQ(95, child.comprehension);
    EXPECT_EQ(80, child.aptitude);
    EXPECT_EQ(82, child.intelligence);
    EXPECT_EQ(59, child.charm);
    EXPECT_EQ(50, child.loyalty);
    EXPECT_EQ(43, child.morality);
    EXPECT_EQ(73, child.artifactRefining);
    EXPECT_EQ(28, child.pillRefining);
    EXPECT_EQ(77, child.spiritPlanting);
    EXPECT_EQ(62, child.mining);
    EXPECT_EQ(70, child.teaching);
    EXPECT_EQ(80, child.lifespan);
    EXPECT_TRUE(child.talentIds.empty());
    EXPECT_EQ(std::vector<std::string>({"r2_phys_hybrid_off", "neg_phys_offense"}),
              child.physiqueIds);
    EXPECT_EQ(std::vector<std::string>({"r2_aff_dmg_amp", "r1_aff_pos_alchemy"}),
              child.affixIds);

    // 母亲状态更新：lastChildYear=当前年、childBirthMonth 清空、partnerId 保留
    const auto mother = state.disciples.materialize(0);
    EXPECT_EQ(1, mother.lastChildYear);
    EXPECT_EQ(0, mother.childBirthMonth);
    EXPECT_EQ("21", mother.partnerId);
}

TEST(ChildBirth, FatherDeadClearsPregnancy) {
    gamecore::ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    auto rng = DeterministicRng::fromSeed(42);
    auto state = makeBirthState();
    // 父亲死亡：清 childBirthMonth + partnerId（增量 update 保序）
    state.disciples.upsertDisciple([&] {
        Disciple d = baseDisciple("21", "父一", "male");
        d.partnerId = "20";
        d.isAlive = false;
        return d;
    }());
    gamecore::system::child_birth::processMonthlyBirth(state, rng, world);

    EXPECT_TRUE(state.gameData.recruitList.empty());
    const auto mother = state.disciples.materialize(0);
    EXPECT_EQ(0, mother.childBirthMonth);
    EXPECT_TRUE(mother.partnerId.empty());
    // 父亲死亡状态保留（isAlive=false）
    EXPECT_EQ(0, state.disciples.isAlive[1]);
}

TEST(ChildBirth, NoDueMotherEarlyReturn) {
    gamecore::ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    auto rng = DeterministicRng::fromSeed(7);
    auto state = makeBirthState();
    // 母亲 childBirthMonth=3 ≠ 当前月 2 → 早退零效果
    state.disciples.upsertDisciple([&] {
        Disciple d = baseDisciple("20", "母一", "female");
        d.partnerId = "21";
        d.childBirthMonth = 3;
        return d;
    }());
    gamecore::system::child_birth::processMonthlyBirth(state, rng, world);
    EXPECT_TRUE(state.gameData.recruitList.empty());
    EXPECT_EQ(3, state.disciples.materialize(0).childBirthMonth);
}

TEST(ChildBirth, MultipleMothersBirthInOrder) {
    gamecore::ecs::World world;   // E2 残留：临时实体集（首调惰性装配）
    auto rng = DeterministicRng::fromSeed(20260901 + 3);
    for (int i = 0; i < 3; ++i) rng.nextInt();
    auto state = makeBirthState();
    // 第二位到期母亲（id 23；行序 = 追加序，RNG 消费序红线）
    auto mother2 = baseDisciple("23", "母二", "female");
    mother2.partnerId = "24";
    mother2.childBirthMonth = 2;
    auto father2 = baseDisciple("24", "父二", "male");
    father2.partnerId = "23";
    state.disciples.appendDisciple(mother2);
    state.disciples.appendDisciple(father2);
    gamecore::system::child_birth::processMonthlyBirth(state, rng, world);

    ASSERT_EQ(2u, state.gameData.recruitList.size());
    // 新生儿 1 的名字规避集合含新生儿 2 的名字（Kotlin 每轮重建 existingNames）
    const auto& first = state.gameData.recruitList[0];
    const auto& second = state.gameData.recruitList[1];
    EXPECT_NE(first.name, second.name);
    // 两位母亲均推进（行 0 = 母亲 20、行 3 = 母亲2 23）
    EXPECT_EQ(1, state.disciples.materialize(0).lastChildYear);
    EXPECT_EQ(1, state.disciples.materialize(3).lastChildYear);
    EXPECT_EQ(0, state.disciples.materialize(3).childBirthMonth);
}

}  // namespace

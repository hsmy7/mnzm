// ============================================================
// disciple_factory_test.cpp — 弟子创建黄金序列
//
// 确定性守护：固定种子 + 固定消费序 ⇒ 固定输出。黄金值来源：
// Kotlin `DiscipleFactory.create`（真相源）经 DiffDiscipleFactoryTest
// （JNI 对拍，同种子逐字段位级一致）确认后固化——本文件防 C++ 侧
// 回归漂移，正确性锚定在 Kotlin 对拍测试。
//
// 另含分布统计断言：数量 0-5 / 品阶四档 / 资质哨兵 50 规避 / 技能上限。
// ============================================================
#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <iostream>
#include <set>
#include <string>
#include <vector>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/disciple_factory.h"

namespace {

using gamecore::rng::DeterministicRng;
using gamecore::system::DiscipleCreationSeed;
using gamecore::system::createDisciple;

DiscipleCreationSeed kSeed() {
    DiscipleCreationSeed s;
    s.id = "golden-001";
    s.gender = "male";
    s.fullName = "李逍遥";
    s.surname = "李";
    s.spiritRootType = "火";
    s.age = 18;
    s.realm = 9;
    s.realmLayer = 1;
    return s;
}

TEST(DiscipleFactory, GoldenSequenceSeed42) {
    auto rng = DeterministicRng::fromSeed(42);
    const auto d = createDisciple(kSeed(), rng);
    // 黄金值（Kotlin DiscipleFactory.create 同种子输出，DiffDiscipleFactoryTest
    // 对拍逐字段确认后固化——seed=42/male/单灵根火）
    EXPECT_EQ("male_disciple_14", d.portraitRes);
    EXPECT_EQ(21, d.hpVariance);
    EXPECT_EQ(18, d.mpVariance);
    EXPECT_EQ(10, d.physicalAttackVariance);
    EXPECT_EQ(16, d.magicAttackVariance);
    EXPECT_EQ(8, d.physicalDefenseVariance);
    EXPECT_EQ(6, d.magicDefenseVariance);
    EXPECT_EQ(8, d.speedVariance);
    EXPECT_EQ(82, d.comprehension);
    EXPECT_EQ(95, d.aptitude);
    EXPECT_EQ(45, d.intelligence);
    EXPECT_EQ(32, d.charm);
    EXPECT_EQ(43, d.loyalty);
    EXPECT_EQ(48, d.morality);
    EXPECT_EQ(43, d.artifactRefining);
    EXPECT_EQ(33, d.pillRefining);
    EXPECT_EQ(66, d.spiritPlanting);
    EXPECT_EQ(51, d.mining);
    EXPECT_EQ(81, d.teaching);
    EXPECT_EQ(145, d.baseHp);
    EXPECT_EQ(70, d.baseMp);
    EXPECT_EQ(13, d.basePhysicalAttack);
    EXPECT_EQ(13, d.baseMagicAttack);
    EXPECT_EQ(10, d.basePhysicalDefense);
    EXPECT_EQ(8, d.baseMagicDefense);
    EXPECT_EQ(16, d.baseSpeed);
    EXPECT_EQ(80, d.lifespan);
    EXPECT_TRUE(d.talentIds.empty());
    EXPECT_EQ(std::vector<std::string>({"neg_phys_defense", "r1_phys_cult_speed"}),
              d.physiqueIds);
    EXPECT_TRUE(d.affixIds.empty());
}

TEST(DiscipleFactory, GoldenSequenceSeed987654321Female) {
    auto rng = DeterministicRng::fromSeed(987654321);
    DiscipleCreationSeed s = kSeed();
    s.id = "golden-002";
    s.gender = "female";
    s.fullName = "慕容雪";
    s.surname = "慕容";
    s.spiritRootType = "火,水,木,金,土";
    const auto d = createDisciple(s, rng);
    // 黄金值（Kotlin DiscipleFactory.create 同种子输出，DiffDiscipleFactoryTest
    // 对拍逐字段确认后固化——seed=987654321/female/五灵根，覆盖负面体质抽取
    // 与三分类多特质路径）
    EXPECT_EQ("female_disciple_8", d.portraitRes);
    EXPECT_EQ(-18, d.hpVariance);
    EXPECT_EQ(3, d.mpVariance);
    EXPECT_EQ(-9, d.physicalAttackVariance);
    EXPECT_EQ(-9, d.magicAttackVariance);
    EXPECT_EQ(-28, d.physicalDefenseVariance);
    EXPECT_EQ(-9, d.magicDefenseVariance);
    EXPECT_EQ(4, d.speedVariance);
    EXPECT_EQ(17, d.comprehension);
    EXPECT_EQ(5, d.aptitude);
    EXPECT_EQ(36, d.intelligence);
    EXPECT_EQ(58, d.charm);
    EXPECT_EQ(77, d.loyalty);
    EXPECT_EQ(59, d.morality);
    EXPECT_EQ(31, d.artifactRefining);
    EXPECT_EQ(54, d.pillRefining);
    EXPECT_EQ(5, d.spiritPlanting);
    EXPECT_EQ(45, d.mining);
    EXPECT_EQ(48, d.teaching);
    EXPECT_EQ(98, d.baseHp);
    EXPECT_EQ(61, d.baseMp);
    EXPECT_EQ(10, d.basePhysicalAttack);
    EXPECT_EQ(10, d.baseMagicAttack);
    EXPECT_EQ(7, d.basePhysicalDefense);
    EXPECT_EQ(7, d.baseMagicDefense);
    EXPECT_EQ(15, d.baseSpeed);
    EXPECT_EQ(80, d.lifespan);
    EXPECT_TRUE(d.talentIds.empty());
    EXPECT_EQ(std::vector<std::string>({"r2_phys_defense", "neg_phys_cult"}),
              d.physiqueIds);
    EXPECT_EQ(std::vector<std::string>({"r1_aff_pos_vice_sect_master",
                                        "r1_aff_pos_inner_elder",
                                        "r1_aff_cult_speed"}),
              d.affixIds);
}

TEST(DiscipleFactory, DeterministicAcrossInstances) {
    // 同种子重放 ⇒ 逐字段一致（防静态初始化序/未定义行为导致漂移）
    auto rngA = DeterministicRng::fromSeed(2024);
    auto rngB = DeterministicRng::fromSeed(2024);
    const auto a = createDisciple(kSeed(), rngA);
    const auto b = createDisciple(kSeed(), rngB);
    EXPECT_EQ(a.portraitRes, b.portraitRes);
    EXPECT_EQ(a.hpVariance, b.hpVariance);
    EXPECT_EQ(a.speedVariance, b.speedVariance);
    EXPECT_EQ(a.comprehension, b.comprehension);
    EXPECT_EQ(a.aptitude, b.aptitude);
    EXPECT_EQ(a.intelligence, b.intelligence);
    EXPECT_EQ(a.teaching, b.teaching);
    EXPECT_EQ(a.baseHp, b.baseHp);
    EXPECT_EQ(a.baseSpeed, b.baseSpeed);
    EXPECT_EQ(a.lifespan, b.lifespan);
    EXPECT_EQ(a.talentIds, b.talentIds);
    EXPECT_EQ(a.physiqueIds, b.physiqueIds);
    EXPECT_EQ(a.affixIds, b.affixIds);
}

TEST(DiscipleFactory, DistributionInvariants) {
    // 统计不变式：数量 0-5、技能 1-200（忠诚 ≤100）、方差 -50..50、
    // 资质避开哨兵 50、悟性/资质 1-200、lifespan ≥ 1
    std::set<int32_t> counts;
    std::set<int32_t> aptitudes;
    std::set<int32_t> comprehensions;
    for (int32_t i = 0; i < 500; ++i) {
        auto rng = DeterministicRng::fromSeed(9000 + i);
        DiscipleCreationSeed s = kSeed();
        s.id = "invariant-" + std::to_string(i);
        s.spiritRootType = (i % 5 == 0) ? "火" : "火,水,木,金,土";
        const auto d = createDisciple(s, rng);
        counts.insert(static_cast<int32_t>(d.talentIds.size()));
        aptitudes.insert(d.aptitude);
        comprehensions.insert(d.comprehension);
        EXPECT_GE(d.intelligence, 1);
        EXPECT_LE(d.intelligence, 200);
        EXPECT_GE(d.charm, 1);
        EXPECT_LE(d.charm, 200);
        EXPECT_GE(d.loyalty, 1);
        EXPECT_LE(d.loyalty, 100);
        EXPECT_GE(d.lifespan, 1);
        EXPECT_GE(d.hpVariance, -50);
        EXPECT_LE(d.hpVariance, 50);
        EXPECT_GE(d.speedVariance, -50);
        EXPECT_LE(d.speedVariance, 50);
    }
    // 数量全档可达（0-5）
    EXPECT_EQ(std::vector<int32_t>({0, 1, 2, 3, 4, 5}),
              std::vector<int32_t>(counts.begin(), counts.end()));
    // 资质永不等于哨兵 50
    EXPECT_EQ(0, aptitudes.count(50));
    // 五灵根悟性/资质 ∈ [1, 20]
    for (int32_t i = 0; i < 100; ++i) {
        auto rng = DeterministicRng::fromSeed(555 + i);
        DiscipleCreationSeed s = kSeed();
        s.id = "five-root-" + std::to_string(i);
        s.spiritRootType = "火,水,木,金,土";
        const auto d = createDisciple(s, rng);
        EXPECT_GE(d.comprehension, 1);
        EXPECT_LE(d.comprehension, 20);
        EXPECT_GE(d.aptitude, 1);
        EXPECT_LE(d.aptitude, 20);
        EXPECT_NE(d.aptitude, 50);
    }
    // 单灵根悟性 ∈ [80, 100]
    for (int32_t i = 0; i < 100; ++i) {
        auto rng = DeterministicRng::fromSeed(333 + i);
        DiscipleCreationSeed s = kSeed();
        s.id = "one-root-" + std::to_string(i);
        s.spiritRootType = "火";
        const auto d = createDisciple(s, rng);
        EXPECT_GE(d.comprehension, 80);
        EXPECT_LE(d.comprehension, 100);
    }
}

TEST(DiscipleFactory, RealmMaxAgeMattersForLifespan) {
    // 境界基准寿命参与计算：金丹（realm 7, maxAge 200）且无加成 ⇒ 200
    auto rng = DeterministicRng::fromSeed(20260901);
    DiscipleCreationSeed s = kSeed();
    s.id = "realm7";
    s.realm = 7;
    const auto d = createDisciple(s, rng);
    EXPECT_EQ(200, d.lifespan);
}

}  // namespace

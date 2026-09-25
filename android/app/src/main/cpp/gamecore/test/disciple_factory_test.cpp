// ============================================================
// disciple_factory_test.cpp — 弟子创建黄金序列
//
// 确定性守护：固定种子 + 固定消费序 ⇒ 固定输出。黄金值来源：
// Kotlin `DiscipleFactory.create`（真相源）经 DiffDiscipleFactoryTest
// （JNI 对拍，同种子逐字段位级一致）确认后固化——本文件防 C++ 侧
// 回归漂移，正确性锚定在 Kotlin 对拍测试。
//
// 另含分布统计断言：悟性阶梯 / 方差区间 / 技能上限。
// ============================================================
#include <gtest/gtest.h>

#include <cstdint>
#include <string>

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
    EXPECT_EQ(45, d.intelligence);
    EXPECT_EQ(32, d.charm);
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
    // 对拍逐字段确认后固化——seed=987654321/female/五灵根）
    EXPECT_EQ("female_disciple_8", d.portraitRes);
    EXPECT_EQ(-18, d.hpVariance);
    EXPECT_EQ(3, d.mpVariance);
    EXPECT_EQ(-9, d.physicalAttackVariance);
    EXPECT_EQ(-9, d.magicAttackVariance);
    EXPECT_EQ(-28, d.physicalDefenseVariance);
    EXPECT_EQ(-9, d.magicDefenseVariance);
    EXPECT_EQ(4, d.speedVariance);
    EXPECT_EQ(17, d.comprehension);
    EXPECT_EQ(36, d.intelligence);
    EXPECT_EQ(58, d.charm);
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
    EXPECT_EQ(a.intelligence, b.intelligence);
    EXPECT_EQ(a.teaching, b.teaching);
    EXPECT_EQ(a.baseHp, b.baseHp);
    EXPECT_EQ(a.baseSpeed, b.baseSpeed);
}

TEST(DiscipleFactory, DistributionInvariants) {
    // 统计不变式：技能 1-200、方差 -50..50、悟性阶梯 1-20/80-100
    for (int32_t i = 0; i < 500; ++i) {
        auto rng = DeterministicRng::fromSeed(9000 + i);
        DiscipleCreationSeed s = kSeed();
        s.id = "invariant-" + std::to_string(i);
        s.spiritRootType = (i % 5 == 0) ? "火" : "火,水,木,金,土";
        const auto d = createDisciple(s, rng);
        EXPECT_GE(d.intelligence, 1);
        EXPECT_LE(d.intelligence, 200);
        EXPECT_GE(d.charm, 1);
        EXPECT_LE(d.charm, 200);
        EXPECT_GE(d.hpVariance, -50);
        EXPECT_LE(d.hpVariance, 50);
        EXPECT_GE(d.speedVariance, -50);
        EXPECT_LE(d.speedVariance, 50);
    }
    // 五灵根悟性 ∈ [1, 20]
    for (int32_t i = 0; i < 100; ++i) {
        auto rng = DeterministicRng::fromSeed(555 + i);
        DiscipleCreationSeed s = kSeed();
        s.id = "five-root-" + std::to_string(i);
        s.spiritRootType = "火,水,木,金,土";
        const auto d = createDisciple(s, rng);
        EXPECT_GE(d.comprehension, 1);
        EXPECT_LE(d.comprehension, 20);
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

}  // namespace

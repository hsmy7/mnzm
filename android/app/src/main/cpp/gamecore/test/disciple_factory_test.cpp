// ============================================================
// disciple_factory_test.cpp — 弟子创建黄金序列
//
// 确定性守护：固定种子 + 固定消费序 ⇒ 固定输出。黄金值来源：
// Kotlin `DiscipleFactory.create`（真相源）经 DiffDiscipleFactoryTest
// （JNI 对拍，同种子逐字段位级一致）确认后固化——本文件防 C++ 侧
// 回归漂移，正确性锚定在 Kotlin 对拍测试。
//
// 另含分布统计断言（悟性阶梯 / 方差区间 / 技能上限）与模板分支断言
// （立绘覆盖键钉死且不消费肖像 nextInt / 空覆盖维持通用池 roll 序列）。
// ============================================================
#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <string>

#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/system/disciple_factory.h"

namespace {

using gamecore::rng::DeterministicRng;
using gamecore::state::Disciple;
using gamecore::system::createDisciple;
using gamecore::system::DiscipleCreationSeed;
using gamecore::system::femalePortraits;
using gamecore::system::malePortraits;

DiscipleCreationSeed kSeed() {
    DiscipleCreationSeed s;
    s.id = "golden-001";
    s.gender = "male";
    s.fullName = "李逍遥";
    s.surname = "李";
    s.spiritRootType = "火";
    s.realm = 9;
    s.realmLayer = 1;
    // 模板身份留空 ⇒ 走通用肖像 roll 路径（黄金序列口径）
    s.templateId = "";
    s.portraitResOverride = "";
    return s;
}

/// 通用肖像池成员判定（male 20 + female 17 = 37 张）
bool inGenericPortraitPool(const std::string& res) {
    const auto& male = malePortraits();
    const auto& female = femalePortraits();
    return std::find(male.begin(), male.end(), res) != male.end() ||
           std::find(female.begin(), female.end(), res) != female.end();
}

/// 逐数值字段一致（方差/悟性/技能/基础属性），不含肖像与身份字段
void expectNumericFieldsEqual(const Disciple& a, const Disciple& b) {
    EXPECT_EQ(a.hpVariance, b.hpVariance);
    EXPECT_EQ(a.mpVariance, b.mpVariance);
    EXPECT_EQ(a.physicalAttackVariance, b.physicalAttackVariance);
    EXPECT_EQ(a.magicAttackVariance, b.magicAttackVariance);
    EXPECT_EQ(a.physicalDefenseVariance, b.physicalDefenseVariance);
    EXPECT_EQ(a.magicDefenseVariance, b.magicDefenseVariance);
    EXPECT_EQ(a.speedVariance, b.speedVariance);
    EXPECT_EQ(a.comprehension, b.comprehension);
    EXPECT_EQ(a.intelligence, b.intelligence);
    EXPECT_EQ(a.charm, b.charm);
    EXPECT_EQ(a.morality, b.morality);
    EXPECT_EQ(a.artifactRefining, b.artifactRefining);
    EXPECT_EQ(a.pillRefining, b.pillRefining);
    EXPECT_EQ(a.spiritPlanting, b.spiritPlanting);
    EXPECT_EQ(a.mining, b.mining);
    EXPECT_EQ(a.teaching, b.teaching);
    EXPECT_EQ(a.baseHp, b.baseHp);
    EXPECT_EQ(a.baseMp, b.baseMp);
    EXPECT_EQ(a.basePhysicalAttack, b.basePhysicalAttack);
    EXPECT_EQ(a.baseMagicAttack, b.baseMagicAttack);
    EXPECT_EQ(a.basePhysicalDefense, b.basePhysicalDefense);
    EXPECT_EQ(a.baseMagicDefense, b.baseMagicDefense);
    EXPECT_EQ(a.baseSpeed, b.baseSpeed);
}

TEST(DiscipleFactory, GoldenSequenceSeed42) {
    auto rng = DeterministicRng::fromSeed(42);
    const auto d = createDisciple(kSeed(), rng);
    // 黄金值（Kotlin DiscipleFactory.create 同种子输出，DiffDiscipleFactoryTest
    // 对拍逐字段确认后固化——seed=42/male/单灵根火）
    EXPECT_EQ("male_disciple_15", d.portraitRes);
    EXPECT_EQ(21, d.hpVariance);
    EXPECT_EQ(18, d.mpVariance);
    EXPECT_EQ(10, d.physicalAttackVariance);
    EXPECT_EQ(16, d.magicAttackVariance);
    EXPECT_EQ(8, d.physicalDefenseVariance);
    EXPECT_EQ(6, d.magicDefenseVariance);
    EXPECT_EQ(8, d.speedVariance);
    EXPECT_EQ(82, d.comprehension);
    EXPECT_EQ(56, d.intelligence);
    EXPECT_EQ(73, d.charm);
    EXPECT_EQ(80, d.morality);
    EXPECT_EQ(57, d.artifactRefining);
    EXPECT_EQ(35, d.pillRefining);
    EXPECT_EQ(28, d.spiritPlanting);
    EXPECT_EQ(48, d.mining);
    EXPECT_EQ(43, d.teaching);
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
    EXPECT_EQ("female_disciple_9", d.portraitRes);
    EXPECT_EQ(-18, d.hpVariance);
    EXPECT_EQ(3, d.mpVariance);
    EXPECT_EQ(-9, d.physicalAttackVariance);
    EXPECT_EQ(-9, d.magicAttackVariance);
    EXPECT_EQ(-28, d.physicalDefenseVariance);
    EXPECT_EQ(-9, d.magicDefenseVariance);
    EXPECT_EQ(4, d.speedVariance);
    EXPECT_EQ(17, d.comprehension);
    EXPECT_EQ(72, d.intelligence);
    EXPECT_EQ(34, d.charm);
    EXPECT_EQ(54, d.morality);
    EXPECT_EQ(47, d.artifactRefining);
    EXPECT_EQ(59, d.pillRefining);
    EXPECT_EQ(29, d.spiritPlanting);
    EXPECT_EQ(36, d.mining);
    EXPECT_EQ(58, d.teaching);
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

TEST(DiscipleFactory, TemplatePortraitOverridePinsPortraitAndSkipsPoolRoll) {
    // 模板弟子：立绘键强制钉住、templateId 落到身份，肖像那次 nextInt 不消费
    auto rng = DeterministicRng::fromSeed(42);
    DiscipleCreationSeed s = kSeed();
    s.id = "template-zhouming";
    s.templateId = "zhouming";
    s.portraitResOverride = "portrait_zhouming";
    const auto d = createDisciple(s, rng);

    EXPECT_EQ("portrait_zhouming", d.portraitRes);
    EXPECT_EQ("zhouming", d.templateId);
    EXPECT_FALSE(inGenericPortraitPool(d.portraitRes));

    // 身份字段逐字取自种子（模板只钉身份）
    EXPECT_EQ("template-zhouming", d.id);
    EXPECT_EQ("李逍遥", d.name);
    EXPECT_EQ("李", d.surname);
    EXPECT_EQ("male", d.gender);
    EXPECT_EQ("火", d.spiritRootType);
    EXPECT_EQ(9, d.realm);
    EXPECT_EQ(1, d.realmLayer);
    EXPECT_EQ("IDLE", d.status);
    EXPECT_EQ("outer", d.discipleType);

    // 肖像 roll 前置各段（六维方差 / 悟性）与同 seed 的非模板路径逐项一致
    auto controlRng = DeterministicRng::fromSeed(42);
    const auto control = createDisciple(kSeed(), controlRng);
    EXPECT_EQ(control.hpVariance, d.hpVariance);
    EXPECT_EQ(control.mpVariance, d.mpVariance);
    EXPECT_EQ(control.physicalAttackVariance, d.physicalAttackVariance);
    EXPECT_EQ(control.magicAttackVariance, d.magicAttackVariance);
    EXPECT_EQ(control.physicalDefenseVariance, d.physicalDefenseVariance);
    EXPECT_EQ(control.magicDefenseVariance, d.magicDefenseVariance);
    EXPECT_EQ(control.speedVariance, d.speedVariance);
    EXPECT_EQ(control.comprehension, d.comprehension);

    // 立绘键与随机流无关：换 seed 仍恒等 —— 这是「模板路径不消费那次池 roll」
    // 的可观测契约。不比较两条路径的 rng 终态：PCG 终态只取决于总消费次数，
    // 而 nextInt(bound) 的 Lemire 回绝（low32 有符号比较）使总次数依赖取值，
    // 跨路径终态是否相同纯属巧合，据此断言会造出脆弱用例。
    auto otherRng = DeterministicRng::fromSeed(7);
    const auto other = createDisciple(s, otherRng);
    EXPECT_EQ("portrait_zhouming", other.portraitRes);
    EXPECT_EQ(s.templateId, other.templateId);
    EXPECT_FALSE(inGenericPortraitPool(other.portraitRes));

    // 模板路径自身仍是「同 seed ⇒ 同输出」的确定函数
    auto replayRng = DeterministicRng::fromSeed(42);
    const auto replay = createDisciple(s, replayRng);
    expectNumericFieldsEqual(d, replay);
    EXPECT_EQ(replay.portraitRes, d.portraitRes);
    EXPECT_EQ(replay.templateId, d.templateId);
}

TEST(DiscipleFactory, EmptyPortraitOverrideKeepsGenericPoolSequence) {
    // 空 override 路径：37 张通用池 + 未知性别回退女性池的行为逐字保持
    EXPECT_EQ(20u, malePortraits().size());
    EXPECT_EQ(17u, femalePortraits().size());

    for (const int32_t seedValue : {42, 987654321, 2024}) {
        auto rngA = DeterministicRng::fromSeed(seedValue);
        auto rngB = DeterministicRng::fromSeed(seedValue);
        const auto a = createDisciple(kSeed(), rngA);
        const auto b = createDisciple(kSeed(), rngB);
        EXPECT_EQ(a.portraitRes, b.portraitRes);
        EXPECT_TRUE(inGenericPortraitPool(a.portraitRes));
        EXPECT_EQ("", a.templateId);
        EXPECT_EQ(a.hpVariance, b.hpVariance);
        EXPECT_EQ(a.comprehension, b.comprehension);
        EXPECT_EQ(a.teaching, b.teaching);
        EXPECT_EQ(a.baseHp, b.baseHp);
        EXPECT_EQ(rngA.snapshot(), rngB.snapshot());
    }

    // 未知性别回退女性池（override 为空时的既有兜底）
    auto fallbackRng = DeterministicRng::fromSeed(2024);
    DiscipleCreationSeed odd = kSeed();
    odd.id = "unknown-gender";
    odd.gender = "other";
    const auto fallback = createDisciple(odd, fallbackRng);
    const auto& female = femalePortraits();
    EXPECT_NE(female.end(), std::find(female.begin(), female.end(), fallback.portraitRes));
    EXPECT_EQ("", fallback.templateId);

    // override 与 templateId 相互独立：只给立绘键同样生效
    auto portraitOnlyRng = DeterministicRng::fromSeed(2024);
    DiscipleCreationSeed portraitOnly = kSeed();
    portraitOnly.id = "override-without-template";
    portraitOnly.portraitResOverride = "portrait_custom";
    const auto pinned = createDisciple(portraitOnly, portraitOnlyRng);
    EXPECT_EQ("portrait_custom", pinned.portraitRes);
    EXPECT_EQ("", pinned.templateId);

    // 同种子去掉 override ⇒ 回到通用池 roll，rng 终态随之多走一步
    DiscipleCreationSeed genericSeed = portraitOnly;
    genericSeed.portraitResOverride = "";
    auto genericRng = DeterministicRng::fromSeed(2024);
    const auto generic = createDisciple(genericSeed, genericRng);
    EXPECT_TRUE(inGenericPortraitPool(generic.portraitRes));
    EXPECT_NE(pinned.portraitRes, generic.portraitRes);
    EXPECT_NE(portraitOnlyRng.snapshot(), genericRng.snapshot());
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

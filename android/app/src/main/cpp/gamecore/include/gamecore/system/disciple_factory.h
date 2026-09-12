// ============================================================
// disciple_factory.h — 弟子创建工厂
//
// 等价复刻 Kotlin `DiscipleFactory.create`（core/engine/domain/disciple/
// DiscipleFactory.kt）——三处构造站点（recruitDisciple / refreshRecruitList /
// createChild）字符级一致的六段逻辑：方差 / 悟性资质 / 技能 / 基础属性 /
// 寿命 / 三分类特质。调用方只需提供差异化种子（id / gender / 名字 / 灵根 /
// age / realm / realmLayer），其余由 createDisciple 统一完成。
//
// 确定性要点（与 Kotlin 逐位对齐，供 GTest 黄金序列 + Diff 对拍验证）：
//   - 单个 DeterministicRng 串行消费（Kotlin 侧 seed.nextInt 与 seed.random
//     是同一底层 PRNG 的两个适配器），消费序：
//     ① 六维方差 7 × gaussianInt（14 次 nextInt）
//     ② 悟性 1 次 + 资质 1 次 nextInt（灵根数阶梯）
//     ③ 天赋/体质/词条三分类生成（数量 1 次 nextDouble + 每轮品阶 1 次
//        nextDouble + 选池 1 次 nextInt(size)）
//     ④ 肖像 1 次 nextInt(size)
//     ⑤ 技能 9 × gaussianInt（18 次 nextInt）
//   - gaussianInt 用同族 fdlibm（log/cos）+ std::sqrt + floor(v+0.5)
//     复刻 Kotlin StrictMath.roundToInt（Math.round 语义，非远离零舍入）
//   - 三分类生成同构（Talent/Physique/Affix 共用模板）；唯一差异：天赋池
//     排除 DEPRECATED_TALENT_TYPES（CULT_SPEED/BREAK_CHANCE/LIFESPAN/
//     MANUAL_SLOT/WIN_GROWTH），体质/词条不过滤
//   - 基础属性 = Kotlin CombatAttributes.calculateBaseStatsWithVariance
//     （120/60/12/12/10/8/15 × (1 + 方差/100) 截断），**非** realm 乘区
//     的 computeBaseStats（getBaseStats 路径语义不同，勿混用）
// ============================================================
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gamecore/data/trait_db.h"
#include "gamecore/rng/fdlibm.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"

namespace gamecore::system {

// ============================================================
// 分布常量（与 Kotlin WeightedRoll.kt 逐值一致）
// ============================================================

/// 弟子特质数量分布：0个35% / 1个35% / 2个20% / 3个6% / 4个3% / 5个1%
inline const std::vector<std::pair<int32_t, double>>& kTraitCountDistribution() {
    static const std::vector<std::pair<int32_t, double>> kDist = {
        {0, 0.35}, {1, 0.35}, {2, 0.20}, {3, 0.06}, {4, 0.03}, {5, 0.01}};
    return kDist;
}

/// 单特质品阶分布：0=负面30% / 1=下品50% / 2=中品18% / 3=上品2%
inline const std::vector<std::pair<int32_t, double>>& kTraitQualityDistribution() {
    static const std::vector<std::pair<int32_t, double>> kDist = {
        {0, 0.30}, {1, 0.50}, {2, 0.18}, {3, 0.02}};
    return kDist;
}

/// 已从新生成池移除的旧天赋类型（Kotlin TalentDatabase.DEPRECATED_TALENT_TYPES）
inline const std::set<std::string>& kDeprecatedTalentTypes() {
    static const std::set<std::string> kTypes = {
        "CULT_SPEED", "BREAK_CHANCE", "LIFESPAN", "MANUAL_SLOT", "WIN_GROWTH"};
    return kTypes;
}

/// 弟子肖像池（Kotlin PortraitPool：male 1..20 / female 1..17）
inline const std::vector<std::string>& malePortraits() {
    static const std::vector<std::string> kPools = [] {
        std::vector<std::string> out;
        for (int32_t i = 1; i <= 20; ++i) {
            out.push_back("male_disciple_" + std::to_string(i));
        }
        return out;
    }();
    return kPools;
}

inline const std::vector<std::string>& femalePortraits() {
    static const std::vector<std::string> kPools = [] {
        std::vector<std::string> out;
        for (int32_t i = 1; i <= 17; ++i) {
            out.push_back("female_disciple_" + std::to_string(i));
        }
        return out;
    }();
    return kPools;
}

// ============================================================
// 基础工具（Kotlin DiscipleFactory 文件级私有函数同构）
// ============================================================

/// [from, until) 范围随机（Kotlin nextInt(from, until) = from + nextInt(until - from)）
inline int32_t nextIntBetween(rng::DeterministicRng& rng, int32_t from, int32_t until) {
    return from + rng.nextInt(until - from);
}

/// 正态分布整数值（Box-Muller；Kotlin gaussianInt）——每次恰好消耗 2 次
/// nextInt：u1 ∈ (0,1]（1 + nextInt(10000)）/10000，u2 ∈ [0,1]
/// nextInt(10001)/10000。sqrt/ln/cos 与 Kotlin StrictMath 同口径
/// （fdlibm 内嵌，已位级验证）；舍入用 floor(v + 0.5) 复刻
/// Math.round（半值向正无穷，非远离零）。
inline int32_t gaussianInt(rng::DeterministicRng& rng, double mean, double sigma,
                           int32_t min, int32_t max) {
    const double u1 = static_cast<double>(1 + rng.nextInt(10000)) / 10000.0;
    const double u2 = static_cast<double>(rng.nextInt(10001)) / 10000.0;
    // Kotlin: 2.0 * PI（double 乘法）——kTwoPi 高精度常量舍入后位级相同
    constexpr double kTwoPi = 6.2831853071795864769252867665590057683943387987502;
    const double z = std::sqrt(-2.0 * rng::fdlibm::log(u1)) *
                     rng::fdlibm::cos(kTwoPi * u2);
    // 先 clamp 到 [min, max] 再转 int32，避免越界转换 UB（实际 z 有界，防御性写法）
    const double raw = std::floor(z * sigma + mean + 0.5);
    const double clamped = std::clamp(raw, static_cast<double>(min), static_cast<double>(max));
    return static_cast<int32_t>(clamped);
}

/// 资质生成避开哨兵值 50（==50 强制 +1 收敛；Kotlin avoidSentinel50，与
/// DiscipleTables.healDefaultAptitudes 自愈判定保持一致）
inline int32_t avoidSentinel50(int32_t roll) {
    return roll == 50 ? 51 : roll;
}

/// 境界 → 基础寿命（Kotlin GameConfig.Realm.get(realm).maxAge）
inline int32_t realmMaxAge(int32_t realm) {
    switch (realm) {
        case 0: return 9999;
        case 1: return 4000;
        case 2: return 2500;
        case 3: return 1500;
        case 4: return 800;
        case 5: return 500;
        case 6: return 300;
        case 7: return 200;
        case 8: return 120;
        default: return 80;  // realm 9 炼气（含非法索引兜底）
    }
}

// ============================================================
// WeightedRoll（Kotlin WeightedRoll.kt 同构；每次调用恰好 1 次 nextDouble）
// ============================================================

namespace detail {

/// 累积分布抽取（roll <= cumulative 即命中；权重和 < 1.0 时回退末档）
inline int32_t rollCumulative(
    const std::vector<std::pair<int32_t, double>>& distribution,
    rng::DeterministicRng& rng) {
    const double roll = rng.nextDouble();
    double cumulative = 0.0;
    for (const auto& [value, probability] : distribution) {
        cumulative += probability;
        if (roll <= cumulative) return value;
    }
    return distribution.back().first;
}

/// 弟子特质数量 0-5（单次 nextDouble）
inline int32_t rollTraitCount(rng::DeterministicRng& rng) {
    return rollCumulative(kTraitCountDistribution(), rng);
}

/// 单特质品阶 0-3（单次 nextDouble；0=负面）
inline int32_t rollTraitQuality(rng::DeterministicRng& rng) {
    return rollCumulative(kTraitQualityDistribution(), rng);
}

/// 品阶抽取（Kotlin pickPositiveByRarity）：精确档优先；无精确匹配时取
/// 差值最小档（平局取较高档）；最终 1 次 nextInt(size) 选池。
template <typename T>
inline const T& pickPositiveByRarityT(const std::vector<const T*>& candidates,
                                      int32_t targetRarity, rng::DeterministicRng& rng) {
    std::vector<const T*> positives;
    for (const auto* c : candidates) {
        if (!c->isNegative) positives.push_back(c);
    }
    if (positives.empty()) {
        return *candidates[static_cast<size_t>(
            rng.nextInt(static_cast<int32_t>(candidates.size())))];
    }

    std::vector<const T*> exact;
    for (const auto* c : positives) {
        if (c->rarity == targetRarity) exact.push_back(c);
    }
    if (exact.empty()) {
        // distinct rarity 中 |rarity - target| 最小，平局取较大 rarity
        int32_t fallback = 0;
        int32_t bestDiff = std::numeric_limits<int32_t>::max();
        std::set<int32_t> seen;
        for (const auto* c : positives) {
            if (!seen.insert(c->rarity).second) continue;
            const int32_t diff = std::abs(c->rarity - targetRarity);
            if (diff < bestDiff || (diff == bestDiff && c->rarity > fallback)) {
                bestDiff = diff;
                fallback = c->rarity;
            }
        }
        for (const auto* c : positives) {
            if (c->rarity == fallback) exact.push_back(c);
        }
    }
    return *exact[static_cast<size_t>(
        rng.nextInt(static_cast<int32_t>(exact.size())))];
}

/// 按品阶分布抽取（Kotlin pickByDistribution）：1 次 nextDouble 消费四档；
/// 命中负面时负面池 1 次 nextInt(size)，负面池耗尽 → 品阶1兜底（不重滚）。
template <typename T>
inline const T& pickByDistributionT(const std::vector<const T*>& candidates,
                                    rng::DeterministicRng& rng) {
    const int32_t quality = rollTraitQuality(rng);
    if (quality == 0) {
        std::vector<const T*> negatives;
        for (const auto* c : candidates) {
            if (c->isNegative) negatives.push_back(c);
        }
        if (!negatives.empty()) {
            return *negatives[static_cast<size_t>(
                rng.nextInt(static_cast<int32_t>(negatives.size())))];
        }
        return pickPositiveByRarityT(candidates, 1, rng);
    }
    return pickPositiveByRarityT(candidates, quality, rng);
}

/// 三分类同构生成（Kotlin generateXForDisciple）：数量 1 次 nextDouble +
/// 每轮（非空轮）品阶 1 次 nextDouble + 选池 1 次 nextInt(size)；
/// 空轮次不消费 RNG。template 去重：选后从可用池移除全部同 template 项。
/// [isExcluded] 控制池过滤（天赋排 DEPRECATED 类型；体质/词条恒 false）。
template <typename T, typename IsExcluded>
inline std::vector<std::string> generateTraitsForDiscipleT(
    const std::vector<T>& pool, rng::DeterministicRng& rng, IsExcluded isExcluded) {
    std::vector<const T*> available;
    for (const auto& t : pool) {
        if (!isExcluded(t)) available.push_back(&t);
    }

    std::vector<std::string> result;
    std::set<std::string> selectedTemplates;
    const int32_t count = rollTraitCount(rng);
    for (int32_t i = 0; i < count; ++i) {
        if (available.empty()) break;

        std::vector<const T*> filtered;
        for (const auto* t : available) {
            if (!selectedTemplates.count(t->tmpl)) filtered.push_back(t);
        }
        if (filtered.empty()) break;

        const T& selected = pickByDistributionT(filtered, rng);
        result.push_back(selected.id);
        selectedTemplates.insert(selected.tmpl);
        available.erase(
            std::remove_if(available.begin(), available.end(),
                           [&](const T* t) { return t->tmpl == selected.tmpl; }),
            available.end());
    }
    return result;
}

}  // namespace detail

// ============================================================
// create 六段逻辑（Kotlin DiscipleFactory 文件级私有函数同构）
// ============================================================

/// 六维方差 + 技能随机结果（聚合避免超长参数表）
struct DiscipleRolls {
    int32_t hpVariance = 0;
    int32_t mpVariance = 0;
    int32_t physicalAttackVariance = 0;
    int32_t magicAttackVariance = 0;
    int32_t physicalDefenseVariance = 0;
    int32_t magicDefenseVariance = 0;
    int32_t speedVariance = 0;
    int32_t comprehension = 50;  // 灵根数阶梯直填（不消费 RNG）
    int32_t aptitude = 50;       // 同上（create 后经 avoidSentinel50）
    int32_t intelligence = 50;
    int32_t charm = 50;
    int32_t loyalty = 50;
    int32_t morality = 50;
    int32_t artifactRefining = 50;
    int32_t pillRefining = 50;
    int32_t spiritPlanting = 50;
    int32_t mining = 50;
    int32_t teaching = 50;
};

/// 六维方差（Kotlin rollVariances）：7 × gaussianInt(0, 16.667, -50, 50)
inline DiscipleRolls rollVariances(rng::DeterministicRng& rng) {
    DiscipleRolls out;
    out.hpVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.mpVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.physicalAttackVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.magicAttackVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.physicalDefenseVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.magicDefenseVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    out.speedVariance = gaussianInt(rng, 0.0, 16.667, -50, 50);
    return out;
}

/// 灵根数量 → 悟性（Kotlin rollComprehension）：1根80~100 … 5根1~20
inline int32_t rollComprehension(rng::DeterministicRng& rng, int32_t spiritRootCount) {
    switch (spiritRootCount) {
        case 1: return nextIntBetween(rng, 80, 101);
        case 2: return nextIntBetween(rng, 60, 81);
        case 3: return nextIntBetween(rng, 40, 61);
        case 4: return nextIntBetween(rng, 20, 41);
        default: return nextIntBetween(rng, 1, 21);
    }
}

/// 灵根数量 → 资质（Kotlin rollAptitude）：与悟性同阶梯
inline int32_t rollAptitude(rng::DeterministicRng& rng, int32_t spiritRootCount) {
    switch (spiritRootCount) {
        case 1: return nextIntBetween(rng, 80, 101);
        case 2: return nextIntBetween(rng, 60, 81);
        case 3: return nextIntBetween(rng, 40, 61);
        case 4: return nextIntBetween(rng, 20, 41);
        default: return nextIntBetween(rng, 1, 21);
    }
}

/// 技能（Kotlin rollSkills）：9 × gaussianInt(50.5, 16.5) + 悟性/资质直填；
/// RNG 消费序与 Kotlin SkillStats 构造参数序一致（忠诚上限 100 单独处理）
inline DiscipleRolls rollSkills(rng::DeterministicRng& rng, int32_t comprehension,
                                int32_t aptitude) {
    constexpr double kSkillMean = 50.5;
    constexpr double kSkillSigma = 16.5;
    constexpr int32_t kSkillMax = 200;    // GameConfig.Disciple.SKILL_MAX
    constexpr int32_t kMaxLoyalty = 100;  // GameConfig.Disciple.MAX_LOYALTY
    DiscipleRolls out;
    out.comprehension = comprehension;
    out.aptitude = aptitude;
    out.intelligence = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.charm = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.loyalty = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kMaxLoyalty);
    out.morality = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.artifactRefining = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.pillRefining = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.spiritPlanting = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.mining = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.teaching = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    return out;
}

/// 基础属性（Kotlin CombatAttributes.calculateBaseStatsWithVariance）：
/// 120/60/12/12/10/8/15 × (1 + 方差/100) 截断——创建期基准，**非** realm
/// 乘区（computeBaseStats 为 getBaseStats 路径，勿混用）
inline void applyBaseStats(state::Disciple& d, const DiscipleRolls& rolls) {
    d.baseHp = static_cast<int32_t>(120.0 * (1.0 + rolls.hpVariance / 100.0));
    d.baseMp = static_cast<int32_t>(60.0 * (1.0 + rolls.mpVariance / 100.0));
    d.basePhysicalAttack =
        static_cast<int32_t>(12.0 * (1.0 + rolls.physicalAttackVariance / 100.0));
    d.baseMagicAttack =
        static_cast<int32_t>(12.0 * (1.0 + rolls.magicAttackVariance / 100.0));
    d.basePhysicalDefense =
        static_cast<int32_t>(10.0 * (1.0 + rolls.physicalDefenseVariance / 100.0));
    d.baseMagicDefense =
        static_cast<int32_t>(8.0 * (1.0 + rolls.magicDefenseVariance / 100.0));
    d.baseSpeed = static_cast<int32_t>(15.0 * (1.0 + rolls.speedVariance / 100.0));
}

/// 寿命（Kotlin computeLifespan）：天赋 + 词条 effects["lifespan"] 之和，
/// baseLifespan × (1 + bonus) 截断，至少 1
inline int32_t computeLifespan(const std::vector<std::string>& talentIds,
                               const std::vector<std::string>& affixIds,
                               int32_t realm) {
    double bonus = 0.0;
    for (const auto& id : talentIds) {
        if (auto t = data::talentById(id)) {
            const auto it = t->effects.find("lifespan");
            if (it != t->effects.end()) bonus += it->second;
        }
    }
    for (const auto& id : affixIds) {
        if (auto a = data::affixById(id)) {
            const auto it = a->effects.find("lifespan");
            if (it != a->effects.end()) bonus += it->second;
        }
    }
    const double baseLifespan = static_cast<double>(realmMaxAge(realm));
    const int32_t lifespan = static_cast<int32_t>(baseLifespan * (1.0 + bonus));
    return std::max(1, lifespan);
}

// ============================================================
// createDisciple 主入口（Kotlin DiscipleFactory.create 同构）
// ============================================================

/// 弟子创建种子——仅包含三站点间差异化字段（名字由调用方经 NameService
/// 生成后传入，本函数不消费名字相关 RNG）
struct DiscipleCreationSeed {
    std::string id;
    std::string gender;
    std::string fullName;
    std::string surname;
    std::string spiritRootType;
    int32_t age = 16;
    int32_t realm = 9;
    int32_t realmLayer = 1;
};

/// 统一创建入口。同一 [rng] 按 Kotlin 消费序串行驱动（详见文件头注释）。
inline state::Disciple createDisciple(const DiscipleCreationSeed& seed,
                                      rng::DeterministicRng& rng) {
    state::Disciple d;
    d.id = seed.id;
    d.name = seed.fullName;
    d.surname = seed.surname;
    d.gender = seed.gender;
    d.age = seed.age;
    d.realm = seed.realm;
    d.realmLayer = seed.realmLayer;
    d.spiritRootType = seed.spiritRootType;
    d.status = "IDLE";       // DiscipleStatus.IDLE
    d.discipleType = "outer";

    // 1. 六维方差（7 × gaussianInt = 14 次 nextInt）
    const DiscipleRolls rolls = rollVariances(rng);
    d.hpVariance = rolls.hpVariance;
    d.mpVariance = rolls.mpVariance;
    d.physicalAttackVariance = rolls.physicalAttackVariance;
    d.magicAttackVariance = rolls.magicAttackVariance;
    d.physicalDefenseVariance = rolls.physicalDefenseVariance;
    d.magicDefenseVariance = rolls.magicDefenseVariance;
    d.speedVariance = rolls.speedVariance;

    // 2. 灵根数量 → 悟性/资质（2 次 nextInt；资质避开哨兵 50）
    const int32_t spiritRootCount =
        1 + static_cast<int32_t>(std::count(seed.spiritRootType.begin(),
                                            seed.spiritRootType.end(), ','));
    const int32_t comprehension = rollComprehension(rng, spiritRootCount);
    const int32_t aptitude = avoidSentinel50(rollAptitude(rng, spiritRootCount));

    // 3. 天赋/体质/词条三分类（各 0-5 个；同一 rng 串行消费）
    d.talentIds = detail::generateTraitsForDiscipleT(
        data::talentTemplates(), rng, [](const data::TalentTemplate& t) {
            return kDeprecatedTalentTypes().count(t.type) > 0;
        });
    d.physiqueIds = detail::generateTraitsForDiscipleT(
        data::physiqueTemplates(), rng,
        [](const data::PhysiqueTemplate&) { return false; });
    d.affixIds = detail::generateTraitsForDiscipleT(
        data::affixTemplates(), rng,
        [](const data::AffixTemplate&) { return false; });

    // 4. 肖像（1 次 nextInt(size)；未知性别回退女性池，与 Kotlin 一致）
    const auto& portraits = (seed.gender == "male") ? malePortraits() : femalePortraits();
    d.portraitRes = portraits[static_cast<size_t>(
        rng.nextInt(static_cast<int32_t>(portraits.size())))];

    // 5. 技能（9 × gaussianInt = 18 次 nextInt + 悟性/资质直填）
    const DiscipleRolls skills = rollSkills(rng, comprehension, aptitude);
    d.intelligence = skills.intelligence;
    d.charm = skills.charm;
    d.loyalty = skills.loyalty;
    d.comprehension = skills.comprehension;
    d.morality = skills.morality;
    d.artifactRefining = skills.artifactRefining;
    d.pillRefining = skills.pillRefining;
    d.spiritPlanting = skills.spiritPlanting;
    d.mining = skills.mining;
    d.teaching = skills.teaching;
    d.aptitude = skills.aptitude;

    // 6. 基础属性（创建期基准，无 realm 乘区）
    applyBaseStats(d, rolls);

    // 7. 寿命（天赋旧加成 + 词条加成）
    d.lifespan = computeLifespan(d.talentIds, d.affixIds, d.realm);

    return d;
}

}  // namespace gamecore::system

// ============================================================
// disciple_factory.h — 弟子创建工厂
//
// 等价复刻 Kotlin `DiscipleFactory.create`（core/engine/domain/disciple/
// DiscipleFactory.kt）——字符级一致的五段逻辑：方差 / 悟性 / 肖像 /
// 技能 / 基础属性。调用方只需提供差异化种子（id / gender / 名字 / 姓氏 /
// 灵根 / realm / realmLayer / 模板身份），其余由 createDisciple 统一完成。
//
// 模板只钉身份（姓名 / 性别 / 灵根 / 境界 / 立绘 / templateId）：六维方差、
// 悟性与技能一律沿用下述确定性 roll 链，模板分支不改任何数值生成口径。
//
// 确定性要点（与 Kotlin 逐位对齐，供 GTest 黄金序列 + Diff 对拍验证）：
//   - 单个 DeterministicRng 串行消费（Kotlin 侧 seed.nextInt 与 seed.random
//     是同一底层 PRNG 的两个适配器），消费序：
//     ① 六维方差 7 × gaussianInt（14 次 nextInt）
//     ② 悟性 1 次 nextInt（灵根数阶梯）
//     ③ 肖像 1 次 nextInt(size)——seed.portraitResOverride 非空时该次
//       消费不发生（立绘直接取覆盖值），其后各段消费整体前移一步
//     ④ 技能 8 × gaussianInt（16 次 nextInt）+ 悟性直填
//   - gaussianInt 用同族 fdlibm（log/cos）+ std::sqrt + floor(v+0.5)
//     复刻 Kotlin StrictMath.roundToInt（Math.round 语义，非远离零舍入）
//   - 基础属性 = Kotlin CombatAttributes.calculateBaseStatsWithVariance
//     （120/60/12/12/10/8/15 × (1 + 方差/100) 截断），**非** realm 乘区
//     的 computeBaseStats（getBaseStats 路径语义不同，勿混用）
// ============================================================
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#include "gamecore/rng/fdlibm.h"
#include "gamecore/rng/pcg_xsh_rr.h"
#include "gamecore/state/models.h"

namespace gamecore::system {

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

// ============================================================
// create 五段逻辑（Kotlin DiscipleFactory 文件级私有函数同构）
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
    int32_t intelligence = 50;
    int32_t charm = 50;
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

/// 技能（Kotlin rollSkills）：8 × gaussianInt(50.5, 16.5) + 悟性直填；
/// RNG 消费序与 Kotlin SkillStats 构造参数序一致
inline DiscipleRolls rollSkills(rng::DeterministicRng& rng, int32_t comprehension) {
    constexpr double kSkillMean = 50.5;
    constexpr double kSkillSigma = 16.5;
    constexpr int32_t kSkillMax = 200;    // GameConfig.Disciple.SKILL_MAX
    DiscipleRolls out;
    out.comprehension = comprehension;
    out.intelligence = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
    out.charm = gaussianInt(rng, kSkillMean, kSkillSigma, 1, kSkillMax);
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

// ============================================================
// createDisciple 主入口（Kotlin DiscipleFactory.create 同构）
// ============================================================

/// 弟子创建种子——仅包含调用方差异化字段（名字由调用方经 NameService
/// 生成后传入，本函数不消费名字相关 RNG；模板身份由调用方从角色模板表取）
struct DiscipleCreationSeed {
    std::string id;
    std::string gender;
    std::string fullName;
    std::string surname;
    std::string spiritRootType;
    int32_t realm = 9;
    int32_t realmLayer = 1;
    /// 角色模板 id；空串表示非模板弟子（写入 Disciple.templateId）
    std::string templateId;
    /// 立绘资源键；非空即强制采用该键（不消费肖像 nextInt），
    /// 空则按性别从通用肖像池 roll
    std::string portraitResOverride;
};

/// 统一创建入口。同一 [rng] 按 Kotlin 消费序串行驱动（详见文件头注释）。
inline state::Disciple createDisciple(const DiscipleCreationSeed& seed,
                                      rng::DeterministicRng& rng) {
    state::Disciple d;
    d.id = seed.id;
    d.name = seed.fullName;
    d.surname = seed.surname;
    d.gender = seed.gender;
    d.realm = seed.realm;
    d.realmLayer = seed.realmLayer;
    d.spiritRootType = seed.spiritRootType;
    d.templateId = seed.templateId;
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

    // 2. 灵根数量 → 悟性（1 次 nextInt）
    const int32_t spiritRootCount =
        1 + static_cast<int32_t>(std::count(seed.spiritRootType.begin(),
                                            seed.spiritRootType.end(), ','));
    const int32_t comprehension = rollComprehension(rng, spiritRootCount);

    // 3. 肖像（1 次 nextInt(size)；未知性别回退女性池，与 Kotlin 一致；
    //    seed.portraitResOverride 非空时直接取该键、不消费这次 nextInt）
    if (seed.portraitResOverride.empty()) {
        const auto& portraits = (seed.gender == "male") ? malePortraits() : femalePortraits();
        d.portraitRes = portraits[static_cast<size_t>(
            rng.nextInt(static_cast<int32_t>(portraits.size())))];
    } else {
        d.portraitRes = seed.portraitResOverride;
    }

    // 4. 技能（8 × gaussianInt = 16 次 nextInt + 悟性直填）
    const DiscipleRolls skills = rollSkills(rng, comprehension);
    d.intelligence = skills.intelligence;
    d.charm = skills.charm;
    d.comprehension = skills.comprehension;
    d.morality = skills.morality;
    d.artifactRefining = skills.artifactRefining;
    d.pillRefining = skills.pillRefining;
    d.spiritPlanting = skills.spiritPlanting;
    d.mining = skills.mining;
    d.teaching = skills.teaching;

    // 5. 基础属性（创建期基准，无 realm 乘区）
    applyBaseStats(d, rolls);

    return d;
}

}  // namespace gamecore::system

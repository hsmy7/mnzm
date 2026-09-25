package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

// ---- 魔法数字命名常量 ----
private const val COMPREHENSION_1_ROOT_MIN = 80
private const val COMPREHENSION_1_ROOT_MAX = 101
private const val COMPREHENSION_2_ROOT_MIN = 60
private const val COMPREHENSION_2_ROOT_MAX = 81
private const val COMPREHENSION_3_ROOT_MIN = 40
private const val COMPREHENSION_3_ROOT_MAX = 61
private const val COMPREHENSION_4_ROOT_MIN = 20
private const val COMPREHENSION_4_ROOT_MAX = 41
private const val COMPREHENSION_5_ROOT_MIN = 1
private const val COMPREHENSION_5_ROOT_MAX = 21

/** 正态分布参数 */
private const val SKILL_MEAN = 50.5       // 技能属性均值
private const val SKILL_SIGMA = 16.5       // 技能属性标准差（99/6，3-sigma覆盖[1,200]）
private const val VARIANCE_MEAN = 0.0      // 方差均值
private const val VARIANCE_SIGMA = 16.667   // 方差标准差（50/3，3-sigma覆盖[-50,50]）

/**
 * 通过 Box-Muller 变换从 [nextInt] 均匀随机源生成正态分布整数值。
 * 每次调用恰好消耗 2 次 nextInt(from, until) 调用。
 *
 * sqrt/ln/cos 改用 [StrictMath]（纯 Java fdlibm，
 * 无平台 intrinsic——与 DeterministicRng.nextGaussian 同口径），保证跨
 * 桌面 JVM/Android 位级一致；C++ 侧用内嵌 fdlibm（gamecore/rng/fdlibm.h）。
 */
private fun gaussianInt(
    nextInt: (Int, Int) -> Int,
    mean: Double,
    sigma: Double,
    min: Int,
    max: Int
): Int {
    val u1 = nextInt(1, 10001).toDouble() / 10000.0  // (0, 1]
    val u2 = nextInt(0, 10001).toDouble() / 10000.0  // [0, 1]
    val z = StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2)
    return (z * sigma + mean).roundToInt().coerceIn(min, max)
}

/**
 * 统一弟子构造工厂。
 *
 * 将构造站点（recruitDisciple / createChild）
 * 中字符级一致的多段逻辑收敛至此：variance / comprehension / skills /
 * baseStats。
 *
 * 调用方只需提供差异化的 [DiscipleSeed]（id / gender / name / spiritRoot /
 * realmLayer / nextInt），其余由 [create] 统一完成。
 *
 * [nextInt] 为 `(from, until) -> value` 函数，同时兼容
 * [kotlin.random.Random.nextInt] 与 [GameRandom.nextInt]。
 */
@GameService("DiscipleFactory")
@Singleton
class DiscipleFactory @Inject constructor() {

    /**
     * 弟子构造种子——仅包含三站点间的差异化字段。
     *
     * @param nextInt 随机整数生成函数 `(from, until) -> value`
     */
    data class DiscipleSeed(
        val id: String,
        val gender: String,
        val nameResult: NameService.NameResult,
        val spiritRootType: String,
        val realm: Int = 9,
        /** 小层境界（1~9），默认 0 表示未知（按初层 1 回退）；Combatant 版实现为 realmLayer */
        val realmLayer: Int,
        val nextInt: (Int, Int) -> Int
    )

    /** 统一构造入口。消除约 300 行重复代码。 */
    fun create(seed: DiscipleSeed): Disciple {
        val r = seed.nextInt

        // 1. 六维方差（正态分布，越接近0概率越高）
        val variances = rollVariances(r = r)

        // 2. 灵根数量 → 悟性
        val spiritRootCount = seed.spiritRootType.split(",").size
        val comprehension = rollComprehension(r = r, spiritRootCount = spiritRootCount)

        val disciple = Disciple(
            id = seed.id,
            name = seed.nameResult.fullName,
            surname = seed.nameResult.surname,
            gender = seed.gender,
            portraitRes = PortraitPool.getRandomPortrait(seed.gender) { bound ->
                r(0, bound)
            },
            realm = seed.realm,
            realmLayer = seed.realmLayer,
            spiritRootType = seed.spiritRootType,
            status = DiscipleStatus.IDLE,
            discipleType = TYPE_OUTER,
            combat = CombatAttributes(
                hpVariance = variances.hpVariance,
                mpVariance = variances.mpVariance,
                physicalAttackVariance = variances.physicalAttackVariance,
                magicAttackVariance = variances.magicAttackVariance,
                physicalDefenseVariance = variances.physicalDefenseVariance,
                magicDefenseVariance = variances.magicDefenseVariance,
                speedVariance = variances.speedVariance
            ),
            skills = rollSkills(r = r, comprehension = comprehension)
        ).apply {
            // 3. 基础属性
            applyBaseStats(variances = variances)
        }

        return disciple
    }
}

/** 六维方差值 */
private data class DiscipleVariances(
    val hpVariance: Int,
    val mpVariance: Int,
    val physicalAttackVariance: Int,
    val magicAttackVariance: Int,
    val physicalDefenseVariance: Int,
    val magicDefenseVariance: Int,
    val speedVariance: Int
)

/** 六维方差随机：正态分布，越接近0概率越高 */
private fun rollVariances(r: (Int, Int) -> Int): DiscipleVariances = DiscipleVariances(
    hpVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50),
    mpVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50),
    physicalAttackVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50),
    magicAttackVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50),
    physicalDefenseVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50),
    magicDefenseVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50),
    speedVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
)

/** 灵根数量 → 悟性：1根80~100 … 5根1~20 */
private fun rollComprehension(r: (Int, Int) -> Int, spiritRootCount: Int): Int = when (spiritRootCount) {
    1 -> r(COMPREHENSION_1_ROOT_MIN, COMPREHENSION_1_ROOT_MAX)
    2 -> r(COMPREHENSION_2_ROOT_MIN, COMPREHENSION_2_ROOT_MAX)
    3 -> r(COMPREHENSION_3_ROOT_MIN, COMPREHENSION_3_ROOT_MAX)
    4 -> r(COMPREHENSION_4_ROOT_MIN, COMPREHENSION_4_ROOT_MAX)
    else -> r(COMPREHENSION_5_ROOT_MIN, COMPREHENSION_5_ROOT_MAX)
}

/** 六维技能：正态分布 + 悟性 */
private fun rollSkills(
    r: (Int, Int) -> Int,
    comprehension: Int
): SkillStats = SkillStats(
    intelligence = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    charm = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    comprehension = comprehension,
    morality = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    artifactRefining = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    pillRefining = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    spiritPlanting = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    mining = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
    teaching = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX)
)

/** 基础属性落库 */
private fun Disciple.applyBaseStats(variances: DiscipleVariances) {
    val baseStats = Disciple.calculateBaseStatsWithVariance(
        variances.hpVariance, variances.mpVariance,
        variances.physicalAttackVariance, variances.magicAttackVariance,
        variances.physicalDefenseVariance, variances.magicDefenseVariance,
        variances.speedVariance
    )
    combat.baseHp = baseStats.baseHp
    combat.baseMp = baseStats.baseMp
    combat.basePhysicalAttack = baseStats.basePhysicalAttack
    combat.baseMagicAttack = baseStats.baseMagicAttack
    combat.basePhysicalDefense = baseStats.basePhysicalDefense
    combat.baseMagicDefense = baseStats.baseMagicDefense
    combat.baseSpeed = baseStats.baseSpeed
}

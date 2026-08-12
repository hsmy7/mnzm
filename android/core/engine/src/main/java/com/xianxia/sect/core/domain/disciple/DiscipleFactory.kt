package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---- 魔法数字命名常量 ----
private const val VARIANCE_MIN = -50
private const val VARIANCE_MAX = 51
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

// 资质阶梯（与悟性一致的按灵根数决定基础数值：1根80~100 … 5根1~20）
private const val APTITUDE_1_ROOT_MIN = 80
private const val APTITUDE_1_ROOT_MAX = 101
private const val APTITUDE_2_ROOT_MIN = 60
private const val APTITUDE_2_ROOT_MAX = 81
private const val APTITUDE_3_ROOT_MIN = 40
private const val APTITUDE_3_ROOT_MAX = 61
private const val APTITUDE_4_ROOT_MIN = 20
private const val APTITUDE_4_ROOT_MAX = 41
private const val APTITUDE_5_ROOT_MIN = 1
private const val APTITUDE_5_ROOT_MAX = 21

/** 正态分布参数 */
private const val SKILL_MEAN = 50.5       // 技能属性均值
private const val SKILL_SIGMA = 16.5       // 技能属性标准差（99/6，3-sigma覆盖[1,200]）
private const val VARIANCE_MEAN = 0.0      // 方差均值
private const val VARIANCE_SIGMA = 16.667   // 方差标准差（50/3，3-sigma覆盖[-50,50]）

/**
 * 通过 Box-Muller 变换从 [nextInt] 均匀随机源生成正态分布整数值。
 * 每次调用恰好消耗 2 次 nextInt(from, until) 调用。
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
    val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    return (z * sigma + mean).roundToInt().coerceIn(min, max)
}

/**
 * 资质生成避开哨兵值 50（==50 强制 +1 收敛）：与自愈 [DiscipleTables.healDefaultAptitudes]
 * 保持一致，保证"资质 == 50 ⇔ 未生成"判定在生成与读档之间稳定，不误触自愈重算。
 */
private fun avoidSentinel50(roll: Int): Int =
    if (roll == DiscipleTables.DEFAULT_APTITUDE) DiscipleTables.DEFAULT_APTITUDE + 1 else roll

/**
 * 统一弟子构造工厂。
 *
 * 将三处构造站点（recruitDisciple / refreshRecruitList / createChild）
 * 中字符级一致的六段逻辑收敛至此：variance / comprehension / skills /
 * baseStats / lifespan / talentIds。
 *
 * 调用方只需提供差异化的 [DiscipleSeed]（id / gender / name / spiritRoot /
 * age / realmLayer / social / nextInt），其余由 [create] 统一完成。
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
        val age: Int,
        val realm: Int = 9,
        val realmLayer: Int,
        val social: SocialData,
        val nextInt: (Int, Int) -> Int,
        /** 特质生成随机源。无默认值：强制调用方传入分区 PRNG 适配器（`rng.asKotlinRandom()`），杜绝 Random.Default 回漏 */
        val random: kotlin.random.Random
    )

    /** 统一构造入口。消除约 300 行重复代码。 */
    fun create(seed: DiscipleSeed): Disciple {
        val r = seed.nextInt

        // 1. 六维方差（正态分布，越接近0概率越高）
        val hpVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
        val mpVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
        val physicalAttackVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
        val magicAttackVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
        val physicalDefenseVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
        val magicDefenseVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)
        val speedVariance = gaussianInt(r, VARIANCE_MEAN, VARIANCE_SIGMA, -50, 50)

        // 2. 灵根数量 → 悟性（与资质同阶梯；资质为固定属性，创建后不再变化）
        val spiritRootCount = seed.spiritRootType.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> r(COMPREHENSION_1_ROOT_MIN, COMPREHENSION_1_ROOT_MAX)
            2 -> r(COMPREHENSION_2_ROOT_MIN, COMPREHENSION_2_ROOT_MAX)
            3 -> r(COMPREHENSION_3_ROOT_MIN, COMPREHENSION_3_ROOT_MAX)
            4 -> r(COMPREHENSION_4_ROOT_MIN, COMPREHENSION_4_ROOT_MAX)
            else -> r(COMPREHENSION_5_ROOT_MIN, COMPREHENSION_5_ROOT_MAX)
        }
        val aptitude = avoidSentinel50(
            when (spiritRootCount) {
                1 -> r(APTITUDE_1_ROOT_MIN, APTITUDE_1_ROOT_MAX)
                2 -> r(APTITUDE_2_ROOT_MIN, APTITUDE_2_ROOT_MAX)
                3 -> r(APTITUDE_3_ROOT_MIN, APTITUDE_3_ROOT_MAX)
                4 -> r(APTITUDE_4_ROOT_MIN, APTITUDE_4_ROOT_MAX)
                else -> r(APTITUDE_5_ROOT_MIN, APTITUDE_5_ROOT_MAX)
            }
        )

        // 3. 天赋 / 体质 / 词条（三分类，各 0-5 个；走 seed.random 分区 PRNG，保证读档可复现）
        val talentIds = TalentDatabase.generateTalentsForDisciple(seed.random)
            .map { it.id }
        val physiqueIds = PhysiqueDatabase.generateForDisciple(seed.random)
            .map { it.id }
        val affixIds = AffixDatabase.generateForDisciple(seed.random)
            .map { it.id }

        val disciple = Disciple(
            id = seed.id,
            name = seed.nameResult.fullName,
            surname = seed.nameResult.surname,
            gender = seed.gender,
            portraitRes = PortraitPool.getRandomPortrait(seed.gender) { bound ->
                r(0, bound)
            },
            age = seed.age,
            realm = seed.realm,
            realmLayer = seed.realmLayer,
            spiritRootType = seed.spiritRootType,
            status = DiscipleStatus.IDLE,
            discipleType = TYPE_OUTER,
            talentIds = talentIds,
            physiqueIds = physiqueIds,
            affixIds = affixIds,
            combat = CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            social = seed.social,
            skills = SkillStats(
                intelligence = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                charm = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                loyalty = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.MAX_LOYALTY),
                comprehension = comprehension,
                morality = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                artifactRefining = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                pillRefining = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                spiritPlanting = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                mining = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                teaching = gaussianInt(r, SKILL_MEAN, SKILL_SIGMA, 1, GameConfig.Disciple.SKILL_MAX),
                aptitude = aptitude
            )
        ).apply {
            // 4. 基础属性
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance,
                physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance,
                speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed

            // 5. 寿命（含天赋旧加成 + 词条加成，旧天赋 LIFESPAN 已迁移至 AffixDatabase）
            val talentEffects =
                TalentDatabase.calculateTalentEffects(talentIds)
            val affixEffects =
                AffixDatabase.calculateAffixEffects(affixIds)
            val lifespanBonus =
                (talentEffects["lifespan"] ?: 0.0) + (affixEffects["lifespan"] ?: 0.0)
            val baseLifespan = GameConfig.Realm.get(realm).maxAge
            lifespan =
                (baseLifespan * (1.0 + lifespanBonus)).toInt()
                    .coerceAtLeast(1)
        }

        return disciple
    }
}

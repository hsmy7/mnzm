package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import javax.inject.Inject
import javax.inject.Singleton

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
        val realmLayer: Int,
        val social: SocialData,
        val nextInt: (Int, Int) -> Int
    )

    /** 统一构造入口。消除约 300 行重复代码。 */
    fun create(seed: DiscipleSeed): Disciple {
        val r = seed.nextInt

        // 1. 六维方差（三站点字符级一致）
        val hpVariance = r(-50, 51)
        val mpVariance = r(-50, 51)
        val physicalAttackVariance = r(-50, 51)
        val magicAttackVariance = r(-50, 51)
        val physicalDefenseVariance = r(-50, 51)
        val magicDefenseVariance = r(-50, 51)
        val speedVariance = r(-50, 51)

        // 2. 灵根数量 → 悟性
        val spiritRootCount = seed.spiritRootType.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> r(80, 101)
            2 -> r(60, 101)
            3 -> r(40, 101)
            4 -> r(20, 101)
            else -> r(1, 101)
        }

        // 3. 天赋
        val talentIds = TalentDatabase.generateTalentsForDisciple()
            .map { it.id }

        val disciple = Disciple(
            id = seed.id,
            name = seed.nameResult.fullName,
            surname = seed.nameResult.surname,
            gender = seed.gender,
            portraitRes = PortraitPool.getRandomPortrait(seed.gender),
            age = seed.age,
            realm = 9,
            realmLayer = seed.realmLayer,
            spiritRootType = seed.spiritRootType,
            status = DiscipleStatus.IDLE,
            discipleType = "outer",
            talentIds = talentIds,
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
                intelligence = r(1, 101),
                charm = r(1, 101),
                loyalty = r(1, 101),
                comprehension = comprehension,
                morality = r(1, 101),
                artifactRefining = r(1, 101),
                pillRefining = r(1, 101),
                spiritPlanting = r(1, 101),
                mining = r(1, 101),
                teaching = r(1, 101)
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

            // 5. 寿命（含天赋加成）
            val talentEffects =
                TalentDatabase.calculateTalentEffects(talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val baseLifespan = GameConfig.Realm.get(realm).maxAge
            lifespan =
                (baseLifespan * (1.0 + lifespanBonus)).toInt()
                    .coerceAtLeast(1)
        }

        return disciple
    }
}

package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.GameRandom
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.SpiritRootGenerator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SystemPriority(order = 235)
class ChildBirthSystem @Inject constructor(
    private val stateStore: GameStateStore
) : GameSystem {

    override val systemName: String = "ChildBirthSystem"

    override fun initialize() {}
    override fun release() {}
    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onYearTick(state: MutableGameState) {
        processYearlyConception(state)
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        processMonthlyBirth(state)
    }

    private fun processYearlyConception(state: MutableGameState) {
        val allDisciples = state.disciples
        val discipleMap = allDisciples.associateBy { it.id }
        val currentYear = state.gameData.gameYear

        val eligibleMothers = allDisciples.filter { mother ->
            mother.isAlive &&
                mother.gender == "female" &&
                mother.social.partnerId != null &&
                mother.social.childBirthMonth == null &&
                (currentYear - mother.social.lastChildYear >= 1)
        }

        if (eligibleMothers.isEmpty()) return

        var currentList = state.disciples
        var updated = false

        for (mother in eligibleMothers) {
            val fatherId = mother.social.partnerId ?: continue
            val father = discipleMap[fatherId]
            if (father == null || !father.isAlive) continue

            if (GameRandom.nextDouble() < 0.005) {
                val birthMonth = GameRandom.nextInt(1, 13)
                currentList = currentList.map { disciple ->
                    if (disciple.id == mother.id) {
                        disciple.copyWith(childBirthMonth = birthMonth)
                    } else disciple
                }
                updated = true
            }
        }

        if (updated) {
            state.disciples = currentList
        }
    }

    private fun processMonthlyBirth(state: MutableGameState) {
        val allDisciples = state.disciples
        val discipleMap = allDisciples.associateBy { it.id }
        val currentYear = state.gameData.gameYear
        val currentMonth = state.gameData.gameMonth

        val mothersDueThisMonth = allDisciples.filter { mother ->
            mother.isAlive && mother.social.childBirthMonth == currentMonth
        }

        if (mothersDueThisMonth.isEmpty()) return

        var currentList = state.disciples

        for (mother in mothersDueThisMonth) {
            val fatherId = mother.social.partnerId ?: continue
            val father = discipleMap[fatherId]
            if (father == null || !father.isAlive) {
                currentList = currentList.map { disciple ->
                    if (disciple.id == mother.id) {
                        disciple.copyWith(childBirthMonth = null)
                    } else disciple
                }
                continue
            }

            val child = createChild(mother, father, currentYear, state)
            state.gameData = state.gameData.copy(
                recruitList = state.gameData.recruitList + child
            )

            currentList = currentList.map { disciple ->
                when (disciple.id) {
                    mother.id -> disciple.copyWith(
                        lastChildYear = currentYear,
                        childBirthMonth = null
                    )
                    else -> disciple
                }
            }
        }

        state.disciples = currentList
    }

    private fun createChild(mother: Disciple, father: Disciple, currentYear: Int, state: MutableGameState): Disciple {
        val id = UUID.randomUUID().toString()
        val gender = if (GameRandom.nextBoolean()) "male" else "female"

        val fatherSurname = if (father.surname.isNotEmpty()) father.surname
            else NameService.extractSurname(father.name)
        val existingNames = (state.disciples + state.gameData.recruitList).map { it.name }.toSet()
        val nameResult = NameService.inheritName(fatherSurname, gender, existingNames)

        val spiritRootType = when (GameRandom.nextInt(100)) {
            in 0..29 -> father.spiritRootType
            in 30..59 -> mother.spiritRootType
            else -> SpiritRootGenerator.generateWithGameRandom()
        }

        val hpVariance = GameRandom.nextInt(-50, 51)
        val mpVariance = GameRandom.nextInt(-50, 51)
        val physicalAttackVariance = GameRandom.nextInt(-50, 51)
        val magicAttackVariance = GameRandom.nextInt(-50, 51)
        val physicalDefenseVariance = GameRandom.nextInt(-50, 51)
        val magicDefenseVariance = GameRandom.nextInt(-50, 51)
        val speedVariance = GameRandom.nextInt(-50, 51)
        val spiritRootCount = spiritRootType.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> GameRandom.nextInt(80, 101)
            2 -> GameRandom.nextInt(60, 101)
            3 -> GameRandom.nextInt(40, 101)
            4 -> GameRandom.nextInt(20, 101)
            else -> GameRandom.nextInt(1, 101)
        }

        val talentIds = TalentDatabase.generateTalentsForDisciple().map { it.id }

        val disciple = Disciple(
            id = id,
            name = nameResult.fullName,
            surname = nameResult.surname,
            gender = gender,
            portraitRes = PortraitPool.getRandomPortrait(gender),
            age = 1,
            realm = 9,
            realmLayer = 0,
            spiritRootType = spiritRootType,
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
            social = SocialData(
                parentId1 = mother.id,
                parentId2 = father.id
            ),
            skills = SkillStats(
                intelligence = GameRandom.nextInt(1, 101),
                charm = GameRandom.nextInt(1, 101),
                loyalty = GameRandom.nextInt(1, 101),
                comprehension = comprehension,
                morality = GameRandom.nextInt(1, 101),
                artifactRefining = GameRandom.nextInt(1, 101),
                pillRefining = GameRandom.nextInt(1, 101),
                spiritPlanting = GameRandom.nextInt(1, 101),
                mining = GameRandom.nextInt(1, 101),
                teaching = GameRandom.nextInt(1, 101)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed

            val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val baseLifespan = GameConfig.Realm.get(realm).maxAge
            lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
        }

        return disciple
    }
}

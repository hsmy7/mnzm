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

// TickSystem: "ChildBirthSystem"
@Singleton
@SystemPriority(order = 235)
class ChildBirthSystem @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
) : GameSystem {

    override val systemName: String = "ChildBirthSystem"
    override val focusDomain = FocusDomain.BACKGROUND

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
        val allDisciples = state.discipleTables.assembleAll()
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

        var currentList = allDisciples
        var updated = false

        for (mother in eligibleMothers) {
            val fatherId = mother.social.partnerId ?: continue
            val father = discipleMap[fatherId]
            if (father == null || !father.isAlive) continue

            if (GameRandom.nextDouble() < 0.005) {
                val birthMonth = GameRandom.nextInt(1, 13)
                currentList = currentList.map { disciple ->
                    if (disciple.id == mother.id) {
                        disciple.copy(social = disciple.social.copy(childBirthMonth = birthMonth))
                    } else disciple
                }
                updated = true
            }
        }

        if (updated) {
            state.discipleTables.clear()
            currentList.forEach { state.discipleTables.insert(it) }
        }
    }

    private fun processMonthlyBirth(state: MutableGameState) {
        val allDisciples = state.discipleTables.assembleAll()
        val discipleMap = allDisciples.associateBy { it.id }
        val currentYear = state.gameData.gameYear
        val currentMonth = state.gameData.gameMonth

        val mothersDueThisMonth = allDisciples.filter { mother ->
            mother.isAlive && mother.social.childBirthMonth == currentMonth
        }

        if (mothersDueThisMonth.isEmpty()) return

        var currentList = allDisciples

        for (mother in mothersDueThisMonth) {
            val fatherId = mother.social.partnerId ?: continue
            val father = discipleMap[fatherId]
            if (father == null || !father.isAlive) {
                currentList = currentList.map { disciple ->
                    if (disciple.id == mother.id) {
                        // 清除 childBirthMonth 和 partnerId，
                        // 修复历史 bug：父亲死亡时仅清除 childBirthMonth，
                        // partnerId 仍指向死者，导致母亲永久无法重新配对
                        disciple.copy(social = disciple.social.copy(
                            childBirthMonth = null,
                            partnerId = null
                        ))
                    } else disciple
                }
                continue
            }

            val child = createChild(mother, father, currentYear, state)
            state.gameData = state.gameData.copy(
                recruitList = state.gameData.recruitList.toList() + child
            )

            currentList = currentList.map { disciple ->
                when (disciple.id) {
                    mother.id -> disciple.copy(
                        social = disciple.social.copy(
                            lastChildYear = currentYear,
                            childBirthMonth = null
                        )
                    )
                    else -> disciple
                }
            }
        }

        state.discipleTables.clear()
        currentList.forEach { state.discipleTables.insert(it) }
    }

    private fun createChild(mother: Disciple, father: Disciple, currentYear: Int, state: MutableGameState): Disciple {
        val id = UUID.randomUUID().toString()
        val gender = if (GameRandom.nextBoolean()) "male" else "female"

        val fatherSurname = if (father.surname.isNotEmpty()) father.surname
            else NameService.extractSurname(father.name)
        val existingNames = (state.discipleTables.assembleAll() + state.gameData.recruitList).map { it.name }.toSet()
        val nameResult = NameService.inheritName(fatherSurname, gender, existingNames)

        val spiritRootType = when (GameRandom.nextInt(100)) {
            in 0..29 -> father.spiritRootType
            in 30..59 -> mother.spiritRootType
            else -> SpiritRootGenerator.generateWithGameRandom()
        }

        return discipleFactory.create(
            com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory.DiscipleSeed(
                id = id,
                gender = gender,
                nameResult = nameResult,
                spiritRootType = spiritRootType,
                age = 1,
                realmLayer = 0,
                social = SocialData(
                    parentId1 = mother.id,
                    parentId2 = father.id
                ),
                nextInt = { from, until -> GameRandom.nextInt(from, until) }
            )
        )
    }
}

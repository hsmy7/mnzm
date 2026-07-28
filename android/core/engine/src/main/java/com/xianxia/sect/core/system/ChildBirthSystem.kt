package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.service.RecruitService
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

    companion object {
        private const val CONCEPTION_PROBABILITY = 0.005
    }

    override fun initialize() {}
    override fun release() {}
    override fun clearForSlot(slotId: Int) {}

    override fun onMonthlyEvent(state: MutableGameState) {
        processMonthlyBirth(state)
    }

    override fun onYearlyEvent(state: MutableGameState) {
        processYearlyConception(state)
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

            if (GameRandom.nextDouble() < CONCEPTION_PROBABILITY) {
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
            state.discipleTables.replaceAll(currentList)
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

        for (mother in mothersDueThisMonth) {
            val fatherId = mother.social.partnerId ?: continue
            val father = discipleMap[fatherId]
            if (father == null || !father.isAlive) {
                // 父亲死亡：清除 childBirthMonth 和 partnerId，
                // 使用增量 update 避免 replaceAll 清除自动招募的新生儿
                state.discipleTables.update(mother.copy(
                    social = mother.social.copy(
                        childBirthMonth = null,
                        partnerId = null
                    )
                ))
                continue
            }

            val child = createChild(mother, father, currentYear, state)
            state.gameData = state.gameData.copy(
                recruitList = state.gameData.recruitList.toList() + child
            )
            // 新生儿产生后立即执行自动招募检查 + 重置惰性
            RecruitService.RecruitLazyState.autoRecruitIdle = false
            RecruitService.RecruitLazyState.autoRejectIdle = false
            RecruitService.processAutoRecruit(state)

            // 增量更新母亲状态，避免 replaceAll 覆盖 processAutoRecruit 已插入的弟子
            state.discipleTables.update(mother.copy(
                social = mother.social.copy(
                    lastChildYear = currentYear,
                    childBirthMonth = null
                )
            ))
        }
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

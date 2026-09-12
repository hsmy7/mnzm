package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SocialData
import com.xianxia.sect.core.model.childBirthMonth
import com.xianxia.sect.core.model.lastChildYear
import com.xianxia.sect.core.model.parentId1
import com.xianxia.sect.core.model.parentId2
import com.xianxia.sect.core.model.partnerId
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.asKotlinRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton



// TickSystem: "ChildBirthSystem"
@Singleton
@SystemPriority(order = 235)
class ChildBirthSystem @Inject constructor(
    private val discipleFactory: com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory,
    private val rngManager: GameRngManager
) : GameSystem {

    override val systemName: String = "ChildBirthSystem"

    /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

    companion object {
        private const val CONCEPTION_PROBABILITY = 0.005
    }

    override fun initialize() = Unit
    override fun release() = Unit
    override fun clearForSlot(slotId: Int) = Unit

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

        val eligibleMothers = allDisciples.filter { isConceptionEligible(it, currentYear) }

        if (eligibleMothers.isEmpty()) return

        var currentList = allDisciples
        var updated = false

        for (mother in eligibleMothers) {
            val fatherId = mother.social.partnerId
            val father = fatherId?.let { discipleMap[it] }
            // 无伴侣或父亲死亡/缺失的母亲跳过受孕判定（RNG 消费集不变）
            if (fatherId == null || father == null || !father.isAlive) continue

            if (rng.nextDouble() < CONCEPTION_PROBABILITY) {
                val birthMonth = 1 + rng.nextInt(12)
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

    /** 受孕资格判定：存活育龄未孕且距上次生产满 1 年 */
    private fun isConceptionEligible(mother: Disciple, currentYear: Int): Boolean {
        return mother.isAlive &&
            mother.gender == "female" &&
            mother.social.partnerId != null &&
            mother.social.childBirthMonth == null &&
            (currentYear - mother.social.lastChildYear >= 1)
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
            val fatherId = mother.social.partnerId
            val father = fatherId?.let { discipleMap[it] }
            // 无伴侣：直接跳过；父亲死亡/缺失：清除产假标记后跳过
            val shouldSkip = fatherId == null || father == null || !father.isAlive
            if (shouldSkip) {
                if (fatherId != null) clearStaleBirthOnDeadPartner(mother, state)
                continue
            }

            val child = createChild(mother, father, currentYear, state)
            state.gameData = state.gameData.copy(
                recruitList = state.gameData.recruitList.toList() + child
            )
            // 新生儿产生后立即执行自动招募检查 + 重置惰性（同步 C++ 惰性门）
            RecruitService.resetAutoRecruitIdle()
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

    @Suppress("UnusedParameter") // currentYear: 语义时点形参：标注年变/月变触发编排的可读契约，函数体当前不消费
    private fun createChild(mother: Disciple, father: Disciple, currentYear: Int, state: MutableGameState): Disciple {
        val id = UUID.randomUUID().toString()
        val gender = if (rng.nextInt(2) == 0) "male" else "female"

        val fatherSurname = if (father.surname.isNotEmpty()) father.surname
            else NameService.extractSurname(father.name)
        val existingNames = (state.discipleTables.assembleAll() + state.gameData.recruitList).map { it.name }.toSet()
        // 名字随机源分区化（SYSTEM 分区 PRNG 适配器——与
        // DiscipleSeed.random 同源，确定性可对拍）
        val nameResult = NameService.inheritName(
            fatherSurname, gender, existingNames, rng.asKotlinRandom()
        )

        val spiritRootType = when (rng.nextInt(100)) {
            in 0..29 -> father.spiritRootType
            in 30..59 -> mother.spiritRootType
            else -> SpiritRootGenerator.generate(rng.asKotlinRandom())
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
                nextInt = { from, until -> from + rng.nextInt(until - from) },
                random = rng.asKotlinRandom()
            )
        )
    }
}

/**
 * 父亲死亡时清除母亲产假标记（childBirthMonth/partnerId）。
 * 使用增量 update 避免 replaceAll 清除自动招募的新生儿。
 */
private fun clearStaleBirthOnDeadPartner(mother: Disciple, state: MutableGameState) {
    state.discipleTables.update(mother.copy(
        social = mother.social.copy(
            childBirthMonth = null,
            partnerId = null
        )
    ))
}

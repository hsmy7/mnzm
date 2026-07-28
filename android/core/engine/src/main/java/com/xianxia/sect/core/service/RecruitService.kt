package com.xianxia.sect.core.engine.service

import kotlin.math.roundToInt
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弟子招募服务 — 负责招募列表刷新、自动招募、招募数量计算。
 *
 * 独立于 [MerchantAndRecruitService]，消除 SRP 违规。
 * 所有状态读取/写入均在 [MutableGameState] 事务内完成，无 TOCTOU 窗口。
 */
@Singleton
@GameService("RecruitService")
class RecruitService @Inject constructor(
    private val stateStore: GameStateStore,
    private val discipleFactory: DiscipleFactory,
    private val rngManager: GameRngManager
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

    companion object {
        private const val TAG = "RecruitService"

        /** 招募弟子最大合理年龄（超过此值的视为数据损坏） */
        private const val MAX_REASONABLE_AGE = 10000
        private val VALID_REALM_RANGE = GameConfig.Realm.CONFIGS.keys.let { it.min()..it.max() }

        /** 招募弟子基础年龄范围 */
        private const val RECRUIT_AGE_MIN = 16
        private const val RECRUIT_AGE_RANGE = 14

        /** 纳徒长老魅力加成公式参数 */
        private const val RECRUIT_CHARM_BASELINE = 80
        private const val RECRUIT_CHARM_DIVISOR = 4
        /** 魅力加成硬上限（防止极端值导致招募数爆炸） */
        private const val MAX_RECRUIT_BONUS_CAP = 20

        /** 招募数量兜底最小值（找不到玩家宗门时使用） */
        private const val FALLBACK_RECRUIT_COUNT = 7

        /**
         * 计算纳徒长老魅力带来的招募上限加成。
         *
         * @param charm 纳徒长老魅力值
         * @return 招募上限加成（不足 0 返回 0，上限 [MAX_RECRUIT_BONUS_CAP]）
         */
        fun calcRecruitBonusCap(charm: Int): Int =
            maxOf(0, (charm - RECRUIT_CHARM_BASELINE) / RECRUIT_CHARM_DIVISOR)
                .coerceAtMost(MAX_RECRUIT_BONUS_CAP)

        /**
         * 扫描 [state] 的 recruitList，将符合 autoRecruitSpiritRootFilter 的弟子自动加入宗门。
         * 必须在 [GameStateStore.update] 事务内调用（接收 [MutableGameState]）。
         * 任何新增待招募弟子的操作完成后均应调用此方法，确保自动招募及时生效。
         *
         * @param state 事务内的可变游戏状态
         * @return 实际自动招募的弟子数量
         */
        fun processAutoRecruit(state: MutableGameState): Int {
            val rawFilter = state.gameData.autoRecruitSpiritRootFilter
            if (rawFilter.isNullOrEmpty()) return 0
            // 守卫：只接受 1-5（有效灵根数量），剔除入库不合理值
            val filter = rawFilter.filter { it in 1..5 }.toSet()
            if (filter.isEmpty()) return 0

            val (autoRecruits, keepManual) = state.gameData.recruitList
                .distinctBy { it.id }
                .partition { disciple ->
                    disciple.spiritRootType.split(",")
                        .count { it.isNotBlank() } in filter
                }
            if (autoRecruits.isEmpty()) return 0

            val currentMonthIndex = state.gameData.gameYear * 12 + state.gameData.gameMonth
            var recruited = 0
            val corruptedIds = mutableSetOf<String>()
            for (disciple in autoRecruits) {
                if (disciple.name.isBlank() || disciple.age <= 0 || disciple.age > MAX_REASONABLE_AGE
                    || disciple.realm !in VALID_REALM_RANGE) {
                    DomainLog.w(TAG, "processAutoRecruit: skipping corrupted disciple ${disciple.id}")
                    corruptedIds.add(disciple.id)
                    continue
                }
                val newId = state.discipleTables.allocateAndInsert(
                    disciple.copy(usage = disciple.usage.copy(recruitedMonth = currentMonthIndex))
                        .also { it.lifeEvents = listOf("${disciple.age}岁：加入宗门") }
                )
                if (newId.isNotEmpty()) {
                    recruited++
                }
            }
            // 将损坏的 autoRecruits 追加回 keepManual，避免静默删除
            val corruptedKeep = autoRecruits.filter { it.id in corruptedIds }
            state.gameData = state.gameData.copy(
                recruitList = keepManual + corruptedKeep
            )
            DomainLog.i(TAG,
                "processAutoRecruit: auto-recruited $recruited disciples, " +
                "${keepManual.size} left for manual review")
            return recruited
        }
    }

    // ── 纳徒长老 ──────────────────────────────────────────────────────

    /** 计算纳徒长老魅力带来的当前招募上限加成 */
    private fun calcRecruitBonusCap(state: MutableGameState): Int {
        val recruitingElderId = state.gameData.elderSlots.recruitingElder
        if (recruitingElderId.isEmpty()) return 0
        val intId = recruitingElderId.toIntOrNull() ?: return 0
        val elderCharm = state.discipleTables.charms[intId] ?: return 0
        return calcRecruitBonusCap(elderCharm)
    }

    // ── 招募池刷新 ─────────────────────────────────────────────────────

    /**
     * 年度招募列表刷新。
     *
     * 全部读取/写入在 [MutableGameState] 事务内完成，消除 TOCTOU 窗口。
     * 使用 [rng.asKotlinRandom] 一次创建 Kotlin Random 适配器，消除循环内匿名对象开销。
     */
    fun refreshRecruitList(year: Int) {
        var generatedCount = 0
        stateStore.update {
            val playerSect = gameData.worldMapSects.find { it.isPlayerSect }
            var recruitCount = if (playerSect != null) {
                val range = SectLevel.recruitRange(playerSect.level)
                val bonusCap = calcRecruitBonusCap(this)
                val until = range.last + 1 + bonusCap
                // 防 Range 为空导致 rng.nextInt(bound) 抛 IllegalArgumentException
                if (until <= range.first) range.first
                else range.first + rng.nextInt(until - range.first)
            } else {
                rng.nextInt(FALLBACK_RECRUIT_COUNT).coerceAtLeast(1)  // 兜底：至少 1 人
            }
            // 广纳门徒政策：招募弟子数+50%
            if (recruitCount > 0 && gameData.sectPolicies.openRecruitment) {
                val bonus = GameConfig.PolicyConfig.OPEN_RECRUITMENT_POOL_BONUS
                recruitCount = (recruitCount * (1.0 + bonus)).roundToInt()
            }

            val newRecruitDisciples = mutableListOf<Disciple>()
            val existingDisciples = discipleTables.assembleAll()
            val usedNames = (existingDisciples + gameData.recruitList).map { it.name }.toMutableSet()

            // 一次创建 Kotlin Random 适配器，消除循环内匿名对象开销
            val kotlinRng = rng.asKotlinRandom()

            repeat(recruitCount) {
                val gender = if (rng.nextInt(2) == 0) "male" else "female"
                val nameResult = NameService.generateName(
                    gender, NameService.NameStyle.FULL, usedNames
                )
                val disciple = discipleFactory.create(
                    DiscipleFactory.DiscipleSeed(
                        id = java.util.UUID.randomUUID().toString(),
                        gender = gender,
                        nameResult = nameResult,
                        spiritRootType = SpiritRootGenerator.generate(kotlinRng),
                        age = RECRUIT_AGE_MIN + rng.nextInt(RECRUIT_AGE_RANGE),
                        realm = 9,
                        realmLayer = 1,
                        social = SocialData(),
                        nextInt = { from, until -> from + rng.nextInt(until - from) }
                    )
                )
                newRecruitDisciples.add(disciple)
                usedNames.add(disciple.name)
            }

            // 单事务：追加到 recruitList + 自动招募，保证原子性
            gameData = gameData.copy(
                recruitList = gameData.recruitList + newRecruitDisciples,
                lastRecruitYear = year
            )
            processAutoRecruit(this)
            generatedCount = newRecruitDisciples.size
        }
        DomainLog.d(TAG, "refreshRecruitList: year=$year, generated $generatedCount new recruits")
    }

    // ── 招募列表老化 ──────────────────────────────────────────────────

    /**
     * 每年对所有待招募弟子年龄 +1。
     * 在 [CultivationEventProcessor.processYearlyEvents] 中调用。
     */
    fun ageRecruitList(year: Int) {
        stateStore.update {
            val aged = gameData.recruitList.map { it.copy(age = it.age + 1) }
            gameData = gameData.copy(recruitList = aged)
        }
        val recruitCount = stateStore.gameDataSnapshot.recruitList.size
        DomainLog.d(TAG, "ageRecruitList: year=$year, aged $recruitCount recruits")
    }
}

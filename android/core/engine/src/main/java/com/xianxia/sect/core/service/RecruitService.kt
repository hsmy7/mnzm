package com.xianxia.sect.core.engine.service

import kotlin.math.roundToInt
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.materializeCaptiveGear
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.computeMaxAge
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
         * 惰性机制：若本轮 0 人可自动招募，标记 [RecruitLazyState.autoRecruitIdle] = true，
         * 后续调用直接跳过，直到有新弟子加入或玩家变更选项后重置。
         *
         * @param state 事务内的可变游戏状态
         * @return 实际自动招募的弟子数量
         */
        fun processAutoRecruit(state: MutableGameState): Int {
            // 惰性检查：无符合条件的弟子时跳过
            if (RecruitLazyState.autoRecruitIdle) return 0

            val rawFilter = state.gameData.autoRecruitSpiritRootFilter
            if (rawFilter.isNullOrEmpty()) return 0
            // 守卫：只接受 1-5（有效灵根数量），剔除入库不合理值
            val filter = rawFilter.filter { it in 1..5 }.toSet()
            if (filter.isEmpty()) return 0

            // 先过滤损坏条目，再三级去重（与净化逻辑一致：损坏先于去重，
            // 防损坏条目与正常条目同人签名时去重丢弃正常者；损坏条目
            // 随列表重建被移除，不再永久残留）
            val distinctRecruits = RecruitIntegrity.dedupeRecruits(
                state.gameData.recruitList.filter(RecruitIntegrity::isValidRecruit)
            ).also { deduped ->
                val dupCount = state.gameData.recruitList.size - deduped.size
                if (dupCount > 0) {
                    DomainLog.w(TAG, "processAutoRecruit: $dupCount corrupted/duplicate recruit entries removed")
                }
            }
            val (autoRecruits, keepManual) = distinctRecruits
                .partition { disciple ->
                    disciple.spiritRootType.split(",")
                        .count { it.isNotBlank() } in filter
                }
            if (autoRecruits.isEmpty()) {
                RecruitLazyState.autoRecruitIdle = true
                return 0
            }

            // 月度上限检查
            val currentCount = state.gameData.recruitCountThisMonth.coerceAtLeast(0)
            val remaining = GameConfig.RECRUIT_MONTHLY_LIMIT - currentCount
            if (remaining <= 0) {
                DomainLog.i(TAG, "processAutoRecruit: monthly limit reached ($currentCount/${GameConfig.RECRUIT_MONTHLY_LIMIT})")
                return 0
            }

            val currentMonthIndex = state.gameData.gameYear * 12 + state.gameData.gameMonth
            var recruited = 0
            val corruptedIds = mutableSetOf<String>()

            // 只招募剩余配额内的弟子，超额部分保留在手册招募列表
            val toRecruit = autoRecruits.take(remaining)
            val overflowKeep = autoRecruits.drop(remaining)

            for (disciple in toRecruit) {
                if (!RecruitIntegrity.isValidRecruit(disciple)) {
                    DomainLog.w(TAG, "processAutoRecruit: skipping corrupted disciple ${disciple.id}")
                    corruptedIds.add(disciple.id)
                    continue
                }
                val newId = state.discipleTables.allocateAndInsert(
                    disciple.copy(usage = disciple.usage.copy(recruitedMonth = currentMonthIndex))
                        .also { it.lifeEvents = listOf("${disciple.age}岁：加入宗门") }
                )
                if (newId.isNotEmpty()) {
                    // 俘虏自带装备/功法落库为玩家实例（幂等）
                    state.materializeCaptiveGear(disciple, newId)
                    recruited++
                }
            }
            // 将损坏的 autoRecruits 追加回 keepManual，避免静默删除
            val corruptedKeep = autoRecruits.filter { it.id in corruptedIds }
            val newRecruitCount = state.gameData.recruitCountThisMonth + recruited
            state.gameData = state.gameData.copy(
                recruitList = keepManual + overflowKeep + corruptedKeep,
                recruitCountThisMonth = newRecruitCount
            )

            // 若本轮实际招募 0 人（全部损坏），进入惰性
            if (recruited == 0) {
                RecruitLazyState.autoRecruitIdle = true
            }

            DomainLog.i(TAG,
                "processAutoRecruit: auto-recruited $recruited disciples (monthly $newRecruitCount/${GameConfig.RECRUIT_MONTHLY_LIMIT}), " +
                "${keepManual.size} left for manual review")
            return recruited
        }

        /**
         * 对招募列表中的弟子进行老化+死亡检测。
         * 必须在 [GameStateStore.update] 事务内调用（接收 [MutableGameState]）。
         */
        fun processRecruitAging(state: MutableGameState) {
            val agedRecruits = state.gameData.recruitList.map { it.copy(age = it.age + 1) }
            val (dead, alive) = agedRecruits.partition { it.age >= it.computeMaxAge() }
            if (dead.isNotEmpty()) {
                DomainLog.i(TAG, "processRecruitAging: ${dead.size} recruits died of old age")
                dead.forEach { d ->
                    DomainLog.d(TAG, "processRecruitAging: recruit ${d.name} died at age ${d.age}")
                }
            }
            state.gameData = state.gameData.copy(recruitList = alive)
        }

        /**
         * 扫描 [state] 的 recruitList，移除符合 autoRejectSpiritRootFilter 的弟子（自动拒绝）。
         * 游戏运行时拒绝的弟子直接删除（不入死亡记录）。
         * 必须在 [GameStateStore.update] 事务内调用（接收 [MutableGameState]）。
         *
         * 惰性机制：若本轮 0 人可自动拒绝，标记 [RecruitLazyState.autoRejectIdle] = true，
         * 后续调用直接跳过，直到有新弟子加入或玩家变更选项后重置。
         *
         * @param state 事务内的可变游戏状态
         * @return 实际自动拒绝的弟子数量
         */
        fun processAutoReject(state: MutableGameState): Int {
            // 惰性检查：无符合条件的弟子时跳过
            if (RecruitLazyState.autoRejectIdle) return 0

            val rawFilter = state.gameData.autoRejectSpiritRootFilter
            if (rawFilter.isNullOrEmpty()) return 0
            val validFilter = rawFilter.filter { it in 1..5 }.toSet()
            if (validFilter.isEmpty()) return 0

            val (rejected, kept) = state.gameData.recruitList
                .distinctBy { it.id }
                .partition { disciple ->
                    disciple.spiritRootType.split(",")
                        .count { it.isNotBlank() } in validFilter
                }

            // 损坏数据守卫：跳过空白名字/无效年龄/无效境界的条目
            val (validRejected, corruptedRejected) = rejected.partition {
                RecruitIntegrity.isValidRecruit(it)
            }

            if (validRejected.isEmpty()) {
                if (corruptedRejected.isNotEmpty()) {
                    DomainLog.w(TAG,
                        "processAutoReject: ${corruptedRejected.size} corrupted recruits skipped from auto-reject")
                }
                RecruitLazyState.autoRejectIdle = true
                return 0
            }

            state.gameData = state.gameData.copy(
                recruitList = kept + corruptedRejected
            )
            DomainLog.i(TAG,
                "processAutoReject: rejected ${validRejected.size} disciples, " +
                "${corruptedRejected.size} corrupted skipped")
            return validRejected.size
        }
        /**
         * 净化 recruitList：移除损坏条目 / 同 id 重复 / 同内容双胞胎 /
         * 已入宗门残留。必须在 [GameStateStore.update] 事务内调用
         * （接收 [MutableGameState]）。
         *
         * 有移除时复位双惰性状态，让后续 processAutoRecruit /
         * processAutoReject 重新评估列表。
         *
         * @param state 事务内的可变游戏状态
         * @return 实际移除的条目数量
         */
        fun sanitizeRecruitList(state: MutableGameState): Int {
            val report = RecruitIntegrity.sanitizeRecruitList(
                recruits = state.gameData.recruitList,
                sectDisciples = state.discipleTables.assembleAll()
            )
            if (report.removedCount > 0) {
                state.gameData = state.gameData.copy(recruitList = report.cleaned)
                RecruitLazyState.autoRecruitIdle = false
                RecruitLazyState.autoRejectIdle = false
                report.details.forEach { DomainLog.w(TAG, "sanitizeRecruitList: $it") }
            }
            return report.removedCount
        }

        /**
         * 重置自动招募惰性状态。
         * 当玩家变更自动招募筛选条件时调用，使每月招募检测重新活跃。
         */
        fun resetAutoRecruitIdle() {
            RecruitLazyState.autoRecruitIdle = false
        }

        /**
         * 重置自动拒绝惰性状态。
         * 当玩家变更自动拒绝筛选条件时调用，使每年拒绝检测重新活跃。
         */
        fun resetAutoRejectIdle() {
            RecruitLazyState.autoRejectIdle = false
        }
    }

    /**
     * 招募系统惰性检测运行时状态。
     * 纯内存（不序列化），engine 单线程保障线程安全。
     * - autoRecruitIdle：无符合条件的待招募弟子可自动招募后置 true
     * - autoRejectIdle：无符合条件的待招募弟子可自动拒绝后置 true
     * 新增弟子到列表或玩家变更选项后重置为 false。
     */
    internal object RecruitLazyState {
        var autoRecruitIdle: Boolean = false
        var autoRejectIdle: Boolean = false
    }

    // ── 纳徒长老 ──────────────────────────────────────────────────────

    /** 计算纳徒长老魅力带来的当前招募上限加成 */
    private fun calcRecruitBonusCap(state: MutableGameState): Int {
        val recruitingElderId = state.gameData.elderSlots.recruitingElder
        if (recruitingElderId.isEmpty()) return 0
        val intId = recruitingElderId.toIntOrNull() ?: return 0
        val elderCharm = state.discipleTables.charms[intId] ?: return 0
        // 体质/词条的职务加成：作为乘算因子作用于长老职能效果
        val elder = stateStore.disciples.value.find { it.id == recruitingElderId }
        val posBonus = elder?.let {
            DiscipleStatCalculator.getPositionEffectBonus(it, ElderSlotType.RECRUITING)
        } ?: 0.0
        return (calcRecruitBonusCap(elderCharm) * (1.0 + posBonus)).toInt()
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
                        nextInt = { from, until -> from + rng.nextInt(until - from) },
                        random = kotlinRng
                    )
                )
                newRecruitDisciples.add(disciple)
                usedNames.add(disciple.name)
            }

            // 单事务：追加到 recruitList + 自动招募 + 重置惰性，保证原子性
            gameData = gameData.copy(
                recruitList = gameData.recruitList + newRecruitDisciples,
                lastRecruitYear = year
            )
            // 新增弟子到列表 → 重置惰性状态
            RecruitLazyState.autoRecruitIdle = false
            RecruitLazyState.autoRejectIdle = false
            processAutoRecruit(this)
            generatedCount = newRecruitDisciples.size
        }
        DomainLog.d(TAG, "refreshRecruitList: year=$year, generated $generatedCount new recruits")
    }

    // ── 招募列表老化 ──────────────────────────────────────────────────

    /**
     * 每年对所有待招募弟子年龄 +1（超龄者死亡移除）后净化招募列表
     * （异常条目清理）。先老化后净化：老化产生的越界年龄（如 10000→10001）
     * 由净化即时清除，不留存至次年。
     * 在 [CultivationEventProcessor.processYearlyEvents] 中调用。
     */
    fun ageRecruitList(year: Int) {
        stateStore.update {
            processRecruitAging(this)
            sanitizeRecruitList(this)
        }
        val recruitCount = stateStore.gameDataSnapshot.recruitList.size
        DomainLog.d(TAG, "ageRecruitList: year=$year, remaining $recruitCount recruits")
    }
}

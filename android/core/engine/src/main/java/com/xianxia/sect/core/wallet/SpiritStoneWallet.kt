package com.xianxia.sect.core.wallet

import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.SpiritStonesChangedEvent
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灵石单一变更网关 — 所有灵石获取和消耗的唯一入口。
 *
 * ## 数据流
 * ```
 * 所有调用方 → SpiritStoneWallet.add/deduct → 账本记录 → EventBus 通知 → stateStore 写入
 * ```
 *
 * ## 职责
 * - 统一的余额校验（余额不足、溢出保护）
 * - 自动品阶转换（下品不足时自动售卖中品/上品补差价）
 * - 账本审计记录
 * - 变更事件通知
 *
 * ## 线程安全
 * 所有 suspend 方法在 GameEngine-Thread 单线程上调用，
 * 通过 stateStore.update/updateAndReturn 保证事务性。
 */
@Singleton
class SpiritStoneWallet @Inject constructor(
    private val stateStore: GameStateStore,
    private val ledger: SpiritStoneLedger,
    private val eventBus: EventBus
) {
    companion object {
        private const val TAG = "SpiritStoneWallet"
    }

    // ── 增加 ──────────────────────────────────────────────────────────────

    /**
     * 增加灵石。
     *
     * @param amount 增加数量（必须 > 0）
     * @param grade 品阶，默认为下品
     * @param source 来源类别
     * @param metadata 额外上下文（如 entityId）
     * @return 变更后该品阶的余额
     */
    suspend fun add(
        amount: Long,
        grade: SpiritStoneGrade = SpiritStoneGrade.LOW,
        source: SpiritStoneSource = SpiritStoneSource.Internal,
        metadata: Map<String, String> = emptyMap()
    ): Long {
        if (amount <= 0) return balance(grade)

        return stateStore.updateAndReturn {
            val current = gameData.spiritStoneCount(grade)
            // 溢出保护：不超过 Long.MAX_VALUE
            val newAmount = if (current > Long.MAX_VALUE - amount) Long.MAX_VALUE
            else current + amount

            gameData = updateGrade(gameData, grade, newAmount)

            val delta = newAmount - current
            recordAndEmit(this, delta, grade, current, newAmount,
                reason = SpiritStoneReason.Internal.key,
                source = source.key,
                metadata = metadata)

            newAmount
        }
    }

    /**
     * 批量增加（原子事务）。
     */
    suspend fun addAll(
        vararg operations: Pair<Long, SpiritStoneGrade>,
        source: SpiritStoneSource = SpiritStoneSource.Internal,
        metadata: Map<String, String> = emptyMap()
    ): Map<SpiritStoneGrade, Long> {
        return stateStore.updateAndReturn {
            val results = mutableMapOf<SpiritStoneGrade, Long>()
            for ((amount, grade) in operations) {
                if (amount <= 0) continue
                val current = gameData.spiritStoneCount(grade)
                val newAmount = if (current > Long.MAX_VALUE - amount) Long.MAX_VALUE
                else current + amount
                gameData = updateGrade(gameData, grade, newAmount)
                results[grade] = newAmount
                recordAndEmit(this, amount, grade, current, newAmount,
                    reason = SpiritStoneReason.Internal.key,
                    source = source.key, metadata = metadata)
            }
            results
        }
    }

    // ── 扣除 ──────────────────────────────────────────────────────────────

    /**
     * 扣除灵石。
     *
     * @param amount 扣除数量（必须 > 0）
     * @param grade 品阶，默认为下品
     * @param reason 消耗原因
     * @param source 来源类别
     * @param autoConvert 下品不足时是否自动售卖中品/上品补差价（默认 true）
     * @param metadata 额外上下文
     * @return [DeductResult]
     */
    suspend fun deduct(
        amount: Long,
        grade: SpiritStoneGrade = SpiritStoneGrade.LOW,
        reason: SpiritStoneReason = SpiritStoneReason.Internal,
        source: SpiritStoneSource = SpiritStoneSource.Internal,
        autoConvert: Boolean = true,
        metadata: Map<String, String> = emptyMap()
    ): DeductResult {
        if (amount <= 0) return DeductResult.Invalid

        return stateStore.updateAndReturn {
            val current = gameData.spiritStoneCount(grade)

            // 下品不足 & 允许自动售卖 → 尝试补差价
            var actualAmount = amount
            if (grade == SpiritStoneGrade.LOW && autoConvert && current < amount) {
                val plan = calculateAutoSell(this, amount - current)
                if (plan != null && gameData.spiritStones + plan.gainedLow >= amount) {
                    autoSellHigherGrades(this, plan)
                } else {
                    return@updateAndReturn DeductResult.Insufficient(
                        balance = current,
                        required = amount
                    )
                }
                // actualAmount 不变，因为补差价增加了 spiritStones
            } else if (current < amount) {
                return@updateAndReturn DeductResult.Insufficient(
                    balance = current,
                    required = amount
                )
            }

            val newAmount = (gameData.spiritStoneCount(grade) - actualAmount).coerceAtLeast(0L)
            gameData = updateGrade(gameData, grade, newAmount)

            val delta = -(actualAmount)
            recordAndEmit(this, delta, grade, current, newAmount,
                reason = reason.key,
                source = source.key,
                metadata = metadata)

            DeductResult.Success(balanceAfter = newAmount)
        }
    }

    /**
     * 尝试扣除，不足时返回 false（不抛出异常）。用于快速检查。
     */
    suspend fun tryDeduct(
        amount: Long,
        grade: SpiritStoneGrade = SpiritStoneGrade.LOW,
        reason: SpiritStoneReason = SpiritStoneReason.Internal,
        source: SpiritStoneSource = SpiritStoneSource.Internal,
        autoConvert: Boolean = true,
        metadata: Map<String, String> = emptyMap()
    ): Boolean {
        val result = deduct(amount, grade, reason, source, autoConvert, metadata)
        return result is DeductResult.Success
    }

    // ── 批量变更 ──────────────────────────────────────────────────────────

    /**
     * 批量执行多条变更，所有操作在一个原子事务中完成。
     */
    suspend fun batch(
        operations: List<SpiritStoneOperation>,
        autoConvert: Boolean = false
    ): BatchResult {
        if (operations.isEmpty()) return BatchResult(0, 0, emptyList())

        return stateStore.updateAndReturn {
            // 预检查所有扣除操作确保原子性
            for (op in operations) {
                if (op.delta >= 0) continue
                val absAmount = -op.delta
                val curr = gameData.spiritStoneCount(op.grade)
                if (curr < absAmount) {
                    if (!autoConvert || op.grade != SpiritStoneGrade.LOW) {
                        return@updateAndReturn BatchResult(0, operations.size, emptyList())
                    }
                    val plan = calculateAutoSell(this, absAmount - curr)
                    if (plan == null || gameData.spiritStones + plan.gainedLow < absAmount) {
                        return@updateAndReturn BatchResult(0, operations.size, emptyList())
                    }
                    autoSellHigherGrades(this, plan)
                }
            }

            val results = mutableListOf<DeductResult>()
            var successCount = 0
            var failedCount = 0

            for (op in operations) {
                if (op.delta >= 0) {
                    // 增加
                    val current = gameData.spiritStoneCount(op.grade)
                    val newAmount = if (current > Long.MAX_VALUE - op.delta) Long.MAX_VALUE
                    else current + op.delta
                    gameData = updateGrade(gameData, op.grade, newAmount)
                    recordAndEmit(this, op.delta, op.grade, current, newAmount,
                        reason = op.reason.key, source = op.source.key,
                        metadata = op.metadata)
                    successCount++
                    results.add(DeductResult.Success(newAmount))
                } else {
                    // 扣除（预检查已保证余额充足）
                    if (op.delta == Long.MIN_VALUE) {
                        failedCount++
                        results.add(DeductResult.Invalid)
                        continue
                    }
                    val absAmount = -op.delta
                    val current = gameData.spiritStoneCount(op.grade)

                    if (current < absAmount) {
                        failedCount++
                        results.add(DeductResult.Insufficient(current, absAmount))
                        continue
                    }

                    val newAmount = (current - absAmount).coerceAtLeast(0L)
                    gameData = updateGrade(gameData, op.grade, newAmount)
                    recordAndEmit(this, op.delta, op.grade, current, newAmount,
                        reason = op.reason.key, source = op.source.key,
                        metadata = op.metadata)
                    successCount++
                    results.add(DeductResult.Success(newAmount))
                }
            }

            BatchResult(successCount, failedCount, results)
        }
    }

    // ── 事务内方法（在 stateStore.update / modifyState 闭包内调用）─────────

    /**
     * 在已有事务内执行灵石增加。
     * @param state 事务中的 [MutableGameState]（即 `this` 或 `ts`）
     */
    fun applyAdd(
        state: MutableGameState,
        amount: Long,
        grade: SpiritStoneGrade = SpiritStoneGrade.LOW,
        source: SpiritStoneSource = SpiritStoneSource.Internal,
        metadata: Map<String, String> = emptyMap()
    ): Long {
        if (amount <= 0) return state.gameData.spiritStoneCount(grade)
        val current = state.gameData.spiritStoneCount(grade)
        val newAmount = if (current > Long.MAX_VALUE - amount) Long.MAX_VALUE
        else current + amount
        state.gameData = updateGrade(state.gameData, grade, newAmount)
        recordAndEmit(state, newAmount - current, grade, current, newAmount,
            reason = SpiritStoneReason.Internal.key, source = source.key, metadata = metadata)
        return newAmount
    }

    /**
     * 在已有事务内执行灵石扣除。
     * @param state 事务中的 [MutableGameState]（即 `this` 或 `ts`）
     */
    fun applyDeduct(
        state: MutableGameState,
        amount: Long,
        grade: SpiritStoneGrade = SpiritStoneGrade.LOW,
        reason: SpiritStoneReason = SpiritStoneReason.Internal,
        source: SpiritStoneSource = SpiritStoneSource.Internal,
        autoConvert: Boolean = true,
        metadata: Map<String, String> = emptyMap()
    ): DeductResult {
        if (amount <= 0) return DeductResult.Invalid
        val current = state.gameData.spiritStoneCount(grade)

        if (grade == SpiritStoneGrade.LOW && autoConvert && current < amount) {
            val plan = calculateAutoSell(state, amount - current)
            if (plan != null && state.gameData.spiritStones + plan.gainedLow >= amount) {
                autoSellHigherGrades(state, plan)
            } else {
                return DeductResult.Insufficient(balance = current, required = amount)
            }
        } else if (current < amount) {
            return DeductResult.Insufficient(balance = current, required = amount)
        }

        val newAmount = (state.gameData.spiritStoneCount(grade) - amount).coerceAtLeast(0L)
        state.gameData = updateGrade(state.gameData, grade, newAmount)
        recordAndEmit(state, -(amount), grade, current, newAmount,
            reason = reason.key, source = source.key, metadata = metadata)
        return DeductResult.Success(balanceAfter = newAmount)
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    /** 按品阶获取当前灵石数量 */
    fun balance(grade: SpiritStoneGrade): Long {
        return stateStore.gameData.value.spiritStoneCount(grade)
    }

    /** 检查下品灵石是否足够 */
    fun canAfford(amount: Long): Boolean = canAfford(amount, SpiritStoneGrade.LOW)

    /** 按品阶检查灵石是否足够 */
    fun canAfford(amount: Long, grade: SpiritStoneGrade): Boolean {
        val current = stateStore.gameData.value.spiritStoneCount(grade)
        if (current >= amount) return true
        // 下品不足时检查自动售卖补差价
        if (grade == SpiritStoneGrade.LOW) {
            val gd = stateStore.gameData.value
            val midValue = if (gd.autoSellMidGradeForPurchase)
                SpiritStoneExchange.toLowGrade(gd.midGradeSpiritStones, SpiritStoneGrade.MID) else 0L
            val highValue = if (gd.autoSellHighGradeForPurchase)
                SpiritStoneExchange.toLowGrade(gd.highGradeSpiritStones, SpiritStoneGrade.HIGH) else 0L
            val totalAvailable = gd.spiritStones + midValue + highValue
            return totalAvailable >= amount
        }
        return false
    }

    /** 按售卖价计算所有品阶灵石的总价值（下品等价） */
    fun totalSellValue(): Long {
        val gd = stateStore.gameData.value
        return SpiritStoneExchange.totalSellValue(
            gd.spiritStones, gd.midGradeSpiritStones, gd.highGradeSpiritStones
        )
    }

    // ── 内部方法 ──────────────────────────────────────────────────────────

    private data class AutoSellPlan(
        val sellMidCount: Long,
        val sellHighCount: Long,
        val gainedLow: Long
    )

    /**
     * 只读计算自动售卖计划，不修改状态。
     * @return 如果售卖后仍不足返回 null，否则返回售卖计划
     */
    private fun calculateAutoSell(
        state: MutableGameState,
        shortfall: Long
    ): AutoSellPlan? {
        val gd = state.gameData
        var remaining = shortfall
        var sellMidCount = 0L
        var sellHighCount = 0L
        var gainedLow = 0L

        if (gd.autoSellMidGradeForPurchase && remaining > 0 && gd.midGradeSpiritStones > 0) {
            sellMidCount = ((remaining + SpiritStoneExchange.EFFECTIVE_RATIO - 1) / SpiritStoneExchange.EFFECTIVE_RATIO)
                .coerceAtMost(gd.midGradeSpiritStones)
            if (sellMidCount > 0) {
                val gained = SpiritStoneExchange.toLowGrade(sellMidCount, SpiritStoneGrade.MID)
                gainedLow += gained
                remaining = (shortfall - gainedLow).coerceAtLeast(0L)
            }
        }
        if (gd.autoSellHighGradeForPurchase && remaining > 0 && gd.highGradeSpiritStones > 0) {
            val highRatio = SpiritStoneExchange.EFFECTIVE_RATIO * SpiritStoneExchange.EFFECTIVE_RATIO
            sellHighCount = ((remaining + highRatio - 1) / highRatio)
                .coerceAtMost(gd.highGradeSpiritStones)
            if (sellHighCount > 0) {
                val gained = SpiritStoneExchange.toLowGrade(sellHighCount, SpiritStoneGrade.HIGH)
                gainedLow += gained
            }
        }
        if (gainedLow <= 0) return null
        return AutoSellPlan(sellMidCount, sellHighCount, gainedLow)
    }

    /**
     * 根据售卖计划执行中品/上品灵石自动售卖补下品差价。
     */
    private fun autoSellHigherGrades(state: MutableGameState, plan: AutoSellPlan) {
        if (plan.sellMidCount > 0) {
            val gainedLow = SpiritStoneExchange.toLowGrade(plan.sellMidCount, SpiritStoneGrade.MID)
            state.gameData = state.gameData.copy(
                midGradeSpiritStones = state.gameData.midGradeSpiritStones - plan.sellMidCount,
                spiritStones = state.gameData.spiritStones + gainedLow
            )
        }
        if (plan.sellHighCount > 0) {
            val gainedLow = SpiritStoneExchange.toLowGrade(plan.sellHighCount, SpiritStoneGrade.HIGH)
            state.gameData = state.gameData.copy(
                highGradeSpiritStones = state.gameData.highGradeSpiritStones - plan.sellHighCount,
                spiritStones = state.gameData.spiritStones + gainedLow
            )
        }
    }

    /** 更新 GameData 中指定品阶的灵石数量 */
    private fun updateGrade(
        gd: GameData,
        grade: SpiritStoneGrade,
        newAmount: Long
    ): GameData = when (grade) {
        SpiritStoneGrade.LOW -> gd.copy(spiritStones = newAmount)
        SpiritStoneGrade.MID -> gd.copy(midGradeSpiritStones = newAmount)
        SpiritStoneGrade.HIGH -> gd.copy(highGradeSpiritStones = newAmount)
    }

    /** 记录账本 + 发送事件 */
    private fun recordAndEmit(
        state: MutableGameState,
        delta: Long,
        grade: SpiritStoneGrade,
        balanceBefore: Long,
        balanceAfter: Long,
        reason: String,
        source: String,
        metadata: Map<String, String>
    ) {
        // 账本记录
        val tx = SpiritStoneTransaction(
            delta = delta,
            grade = grade,
            balanceBefore = balanceBefore,
            balanceAfter = balanceAfter,
            reason = reason,
            source = source,
            metadata = metadata
        )
        ledger.record(tx)

        // 事件通知
        eventBus.emitTyped(SpiritStonesChangedEvent(
            delta = delta,
            newTotal = balanceAfter,
            reason = reason
        ))

        DomainLog.d(TAG, "灵石变更: ${if (delta >= 0) "+" else ""}$delta " +
                "$grade ($source/$reason) [${balanceBefore}→${balanceAfter}]")
    }
}

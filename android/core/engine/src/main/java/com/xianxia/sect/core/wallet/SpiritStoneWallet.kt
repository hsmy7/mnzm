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
 * Wallet 是纯 ledger，永不自行管理事务边界。
 * 所有 [add] / [deduct] / [batch] 方法接受 [MutableGameState] 参数，
 * 由调用方在 [stateStore.update] / [stateStore.updateAndReturn] 闭包内调用。
 *
 * ## 数据流
 * ```
 * stateStore.update { wallet.add(this, amount, ...) } → 账本记录 → EventBus 通知 → stateStore 写入
 * ```
 *
 * ## 线程安全
 * Wallet 方法均为非 suspend 纯函数，通过 [MutableGameState] 参数操作状态，
 * 由调用方保证在 GameEngine-Thread 单线程上调用。
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
     * 在已有事务内增加灵石。
     * @param state 事务中的 [MutableGameState]
     * @param amount 增加数量（必须 > 0）
     * @param grade 品阶，默认为下品
     * @param source 来源类别
     * @param metadata 额外上下文
     * @return 变更后该品阶的余额
     */
    fun add(
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

    // ── 扣除 ──────────────────────────────────────────────────────────────

    /**
     * 在已有事务内扣除灵石。
     * @param state 事务中的 [MutableGameState]
     * @param amount 扣除数量（必须 > 0）
     * @param grade 品阶，默认为下品
     * @param reason 消耗原因
     * @param source 来源类别
     * @param autoConvert 下品不足时是否自动售卖中品/上品补差价（默认 true）
     * @param metadata 额外上下文
     * @return [DeductResult]
     */
    fun deduct(
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
                autoSellHigherGrades(state, plan, metadata)
            } else {
                return DeductResult.Insufficient(balance = current, required = amount)
            }
        } else if (current < amount) {
            return DeductResult.Insufficient(balance = current, required = amount)
        }

        val balanceBefore = state.gameData.spiritStoneCount(grade)
        val newAmount = (balanceBefore - amount).coerceAtLeast(0L)
        state.gameData = updateGrade(state.gameData, grade, newAmount)
        recordAndEmit(state, -(amount), grade, balanceBefore, newAmount,
            reason = reason.key, source = source.key, metadata = metadata)
        return DeductResult.Success(balanceAfter = newAmount)
    }

    // ── 批量变更 ──────────────────────────────────────────────────────────

    /**
     * 在已有事务内批量执行多条变更，所有操作原子完成。
     * @param state 事务中的 [MutableGameState]
     * @param autoConvert 批量扣除是否允许自动售卖补差价（默认 false）
     */
    fun batch(
        state: MutableGameState,
        operations: List<SpiritStoneOperation>,
        autoConvert: Boolean = false
    ): BatchResult {
        if (operations.isEmpty()) return BatchResult(0, 0, emptyList())

        // 预检查所有扣除操作确保原子性
        // 先保存快照，预检查失败时回滚 autoSell
        val preSnapshot = state.gameData
        var hasAutoSold = false
        for (op in operations) {
            if (op.delta >= 0) continue
            val absAmount = -op.delta
            val curr = state.gameData.spiritStoneCount(op.grade)
            if (curr < absAmount) {
                if (!autoConvert || op.grade != SpiritStoneGrade.LOW) {
                    if (hasAutoSold) state.gameData = preSnapshot
                    return BatchResult(0, operations.size, emptyList())
                }
                val plan = calculateAutoSell(state, absAmount - curr)
                if (plan == null || state.gameData.spiritStones + plan.gainedLow < absAmount) {
                    if (hasAutoSold) state.gameData = preSnapshot
                    return BatchResult(0, operations.size, emptyList())
                }
                autoSellHigherGrades(state, plan)
                hasAutoSold = true
            }
        }

        val results = mutableListOf<DeductResult>()
        var successCount = 0
        var failedCount = 0

        for (op in operations) {
            if (op.delta >= 0) {
                val current = state.gameData.spiritStoneCount(op.grade)
                val newAmount = if (current > Long.MAX_VALUE - op.delta) Long.MAX_VALUE
                else current + op.delta
                state.gameData = updateGrade(state.gameData, op.grade, newAmount)
                recordAndEmit(state, op.delta, op.grade, current, newAmount,
                    reason = op.reason.key, source = op.source.key, metadata = op.metadata)
                successCount++
                results.add(DeductResult.Success(newAmount))
            } else {
                if (op.delta == Long.MIN_VALUE) {
                    failedCount++
                    results.add(DeductResult.Invalid)
                    continue
                }
                val absAmount = -op.delta
                val current = state.gameData.spiritStoneCount(op.grade)
                if (current < absAmount) {
                    failedCount++
                    results.add(DeductResult.Insufficient(current, absAmount))
                    continue
                }
                val newAmount = (current - absAmount).coerceAtLeast(0L)
                state.gameData = updateGrade(state.gameData, op.grade, newAmount)
                recordAndEmit(state, op.delta, op.grade, current, newAmount,
                    reason = op.reason.key, source = op.source.key, metadata = op.metadata)
                successCount++
                results.add(DeductResult.Success(newAmount))
            }
        }

        return BatchResult(successCount, failedCount, results)
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    /**
     * 按品阶获取当前灵石数量。
     * ⚠️ 在 [stateStore.update] 闭包内调用时读到的是闭包外的旧值，
     * 如需在事务内读取请直接用 [MutableGameState.gameData.spiritStoneCount]。
     */
    fun balance(grade: SpiritStoneGrade): Long {
        return stateStore.gameData.value.spiritStoneCount(grade)
    }

    /** 检查下品灵石是否足够 */
    fun canAfford(amount: Long): Boolean = canAfford(amount, SpiritStoneGrade.LOW)

    /**
     * 按品阶检查灵石是否足够。
     * ⚠️ 在 [stateStore.update] 闭包内调用时读到的是闭包外的旧值，
     * 如需在事务内检查请直接用 [MutableGameState.gameData.spiritStoneCount]。
     */
    fun canAfford(amount: Long, grade: SpiritStoneGrade): Boolean {
        val current = stateStore.gameData.value.spiritStoneCount(grade)
        if (current >= amount) return true
        if (grade == SpiritStoneGrade.LOW) {
            val gd = stateStore.gameData.value
            val midValue = if (gd.autoSellMidGradeForPurchase)
                SpiritStoneExchange.toLowGrade(gd.midGradeSpiritStones, SpiritStoneGrade.MID) else 0L
            val highValue = if (gd.autoSellHighGradeForPurchase)
                SpiritStoneExchange.toLowGrade(gd.highGradeSpiritStones, SpiritStoneGrade.HIGH) else 0L
            return gd.spiritStones + midValue + highValue >= amount
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

    private fun calculateAutoSell(state: MutableGameState, shortfall: Long): AutoSellPlan? {
        val gd = state.gameData
        var remaining = shortfall
        var sellMidCount = 0L
        var sellHighCount = 0L
        var gainedLow = 0L

        if (gd.autoSellMidGradeForPurchase && remaining > 0 && gd.midGradeSpiritStones > 0) {
            sellMidCount = ((remaining + SpiritStoneExchange.EFFECTIVE_RATIO - 1) / SpiritStoneExchange.EFFECTIVE_RATIO)
                .coerceAtMost(gd.midGradeSpiritStones)
            if (sellMidCount > 0) {
                gainedLow += SpiritStoneExchange.toLowGrade(sellMidCount, SpiritStoneGrade.MID)
                remaining = (remaining - SpiritStoneExchange.toLowGrade(sellMidCount, SpiritStoneGrade.MID)).coerceAtLeast(0L)
            }
        }
        if (gd.autoSellHighGradeForPurchase && remaining > 0 && gd.highGradeSpiritStones > 0) {
            val highRatio = SpiritStoneExchange.EFFECTIVE_RATIO * SpiritStoneExchange.EFFECTIVE_RATIO
            sellHighCount = ((remaining + highRatio - 1) / highRatio).coerceAtMost(gd.highGradeSpiritStones)
            if (sellHighCount > 0) {
                gainedLow += SpiritStoneExchange.toLowGrade(sellHighCount, SpiritStoneGrade.HIGH)
            }
        }
        if (gainedLow <= 0) return null
        return AutoSellPlan(sellMidCount, sellHighCount, gainedLow)
    }

    private fun autoSellHigherGrades(state: MutableGameState, plan: AutoSellPlan, metadata: Map<String, String> = emptyMap()) {
        if (plan.sellMidCount > 0) {
            val gainedLow = SpiritStoneExchange.toLowGrade(plan.sellMidCount, SpiritStoneGrade.MID)
            state.gameData = state.gameData.copy(
                midGradeSpiritStones = state.gameData.midGradeSpiritStones - plan.sellMidCount,
                spiritStones = state.gameData.spiritStones + gainedLow
            )
            recordAndEmit(state, -plan.sellMidCount, SpiritStoneGrade.MID,
                state.gameData.midGradeSpiritStones + plan.sellMidCount, state.gameData.midGradeSpiritStones,
                SpiritStoneReason.AutoSell.key, SpiritStoneSource.Internal.key, metadata)
            recordAndEmit(state, gainedLow, SpiritStoneGrade.LOW,
                state.gameData.spiritStones - gainedLow, state.gameData.spiritStones,
                SpiritStoneReason.AutoSell.key, SpiritStoneSource.Internal.key, metadata)
        }
        if (plan.sellHighCount > 0) {
            val gainedLow = SpiritStoneExchange.toLowGrade(plan.sellHighCount, SpiritStoneGrade.HIGH)
            state.gameData = state.gameData.copy(
                highGradeSpiritStones = state.gameData.highGradeSpiritStones - plan.sellHighCount,
                spiritStones = state.gameData.spiritStones + gainedLow
            )
            recordAndEmit(state, -plan.sellHighCount, SpiritStoneGrade.HIGH,
                state.gameData.highGradeSpiritStones + plan.sellHighCount, state.gameData.highGradeSpiritStones,
                SpiritStoneReason.AutoSell.key, SpiritStoneSource.Internal.key, metadata)
            recordAndEmit(state, gainedLow, SpiritStoneGrade.LOW,
                state.gameData.spiritStones - gainedLow, state.gameData.spiritStones,
                SpiritStoneReason.AutoSell.key, SpiritStoneSource.Internal.key, metadata)
        }
    }

    private fun updateGrade(gd: GameData, grade: SpiritStoneGrade, newAmount: Long): GameData = when (grade) {
        SpiritStoneGrade.LOW -> gd.copy(spiritStones = newAmount)
        SpiritStoneGrade.MID -> gd.copy(midGradeSpiritStones = newAmount)
        SpiritStoneGrade.HIGH -> gd.copy(highGradeSpiritStones = newAmount)
    }

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
        ledger.record(SpiritStoneTransaction(
            delta = delta, grade = grade,
            balanceBefore = balanceBefore, balanceAfter = balanceAfter,
            reason = reason, source = source, metadata = metadata
        ))
        eventBus.emitTyped(SpiritStonesChangedEvent(
            delta = delta, newTotal = balanceAfter, reason = reason
        ))
        DomainLog.d(TAG, "灵石变更: ${if (delta >= 0) "+" else ""}$delta " +
                "$grade ($source/$reason) [${balanceBefore}→${balanceAfter}]")
    }
}

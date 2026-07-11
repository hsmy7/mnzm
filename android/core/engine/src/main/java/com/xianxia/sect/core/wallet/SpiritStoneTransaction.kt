package com.xianxia.sect.core.wallet

import com.xianxia.sect.core.model.SpiritStoneGrade
import java.util.UUID

/**
 * 灵石流水记录 — 每次灵石变更生成一条事务记录，用于审计追踪和调试。
 *
 * @property id 唯一事务 ID，可用于幂等性校验
 * @property timestamp 变更发生时间戳
 * @property delta 变更量（正=增加，负=减少）
 * @property grade 变更品阶
 * @property balanceBefore 变更前余额
 * @property balanceAfter 变更后余额
 * @property reason 变更原因（[SpiritStoneReason] 的字符串表示）
 * @property source 变更来源（[SpiritStoneSource] 的字符串表示）
 * @property metadata 额外上下文（如 entityId、itemId 等）
 */
data class SpiritStoneTransaction(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val delta: Long,
    val grade: SpiritStoneGrade,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val reason: String,
    val source: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 灵石获取来源 — 区分"灵石从哪来"，用于审计和分析。
 */
sealed class SpiritStoneSource {

    /** 该来源的描述名称 */
    open val displayName: String get() = this::class.simpleName ?: "Unknown"
    /** 用于流水记录的字符串标识 */
    val key: String get() = displayName

    object Mine : SpiritStoneSource()
    object Battle : SpiritStoneSource()
    object Quest : SpiritStoneSource()
    data class Sell(val itemType: String) : SpiritStoneSource() {
        override val displayName: String get() = "Sell($itemType)"
    }
    object Mail : SpiritStoneSource()
    object SignIn : SpiritStoneSource()
    object MerchantTrade : SpiritStoneSource()
    object Exploration : SpiritStoneSource()
    object RedeemCode : SpiritStoneSource()
    object Cave : SpiritStoneSource()
    object HeavenlyTrial : SpiritStoneSource()
    object SectLevelReward : SpiritStoneSource()
    object Refund : SpiritStoneSource()
    object Salary : SpiritStoneSource()
    object StorageBag : SpiritStoneSource()

    /** 内部使用（从 InventorySystem 委托时的默认值） */
    object Internal : SpiritStoneSource()
}

/**
 * 灵石消耗原因 — 区分"灵石花在哪"，用于审计和分析。
 */
sealed class SpiritStoneReason {

    /** 该原因的描述名称 */
    open val displayName: String get() = this::class.simpleName ?: "Unknown"
    /** 用于流水记录的字符串标识 */
    val key: String get() = displayName

    object Building : SpiritStoneReason()
    object PolicyCost : SpiritStoneReason()
    object Salary : SpiritStoneReason()
    object Gift : SpiritStoneReason()
    object Diplomacy : SpiritStoneReason()
    object VassalTribute : SpiritStoneReason()
    object Purchase : SpiritStoneReason()
    object AutoSell : SpiritStoneReason()
    object Exchange : SpiritStoneReason()
    object Theft : SpiritStoneReason()
    object ExplorationLoot : SpiritStoneReason()
    object BeastTribute : SpiritStoneReason()

    /** 内部使用（从 InventorySystem 委托时的默认值） */
    object Internal : SpiritStoneReason()
}

/**
 * 灵石扣除操作的结果。
 */
sealed class DeductResult {
    /** 扣除成功 */
    data class Success(val balanceAfter: Long) : DeductResult()
    /** 余额不足 */
    data class Insufficient(val balance: Long, val required: Long) : DeductResult()
    /** 无效操作（数量 <= 0 等） */
    data object Invalid : DeductResult()
}

/**
 * 单条灵石变更操作（用于批量变更）。
 */
data class SpiritStoneOperation(
    val delta: Long,
    val grade: SpiritStoneGrade = SpiritStoneGrade.LOW,
    val reason: SpiritStoneReason,
    val source: SpiritStoneSource,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(delta != 0L) { "delta must not be zero" }
        require(delta != Long.MIN_VALUE) { "delta must not be Long.MIN_VALUE" }
    }
}

/**
 * 批量变更结果。
 */
data class BatchResult(
    val successCount: Int,
    val failedCount: Int,
    val results: List<DeductResult>
)

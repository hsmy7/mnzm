package com.xianxia.sect.core.domain.spiritstone

import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.wallet.SpiritStoneOperation
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灵石跨品阶兑换用例。
 *
 * 调用方只需指定 source（源品阶）、target（目标品阶）和 quantity（源品阶数量），
 * 实际兑换与余额修改由 SpiritStoneWallet 统一处理。
 */
@Singleton
class ExchangeSpiritStonesUseCase @Inject constructor(
    private val stateStore: GameStateStore,
    private val spiritStoneWallet: SpiritStoneWallet
) {
    sealed class Result {
        /**
         * 兑换成功
         * @param converted 成功转换到目标品阶的数量
         * @param remaining 源品阶剩余数量（因汇率取整产生）
         */
        data class Success(val converted: Long, val remaining: Long) : Result()

        /**
         * 余额不足
         * @param required 需要的源品阶数量
         * @param owned 当前拥有的源品阶数量
         */
        data class Insufficient(val required: Long, val owned: Long) : Result()

        /** 兑换数量非法或品阶相同 */
        data object Invalid : Result()
    }

    suspend operator fun invoke(
        quantity: Long,
        source: SpiritStoneGrade,
        target: SpiritStoneGrade
    ): Result {
        if (quantity <= 0 || source == target) {
            return Result.Invalid
        }

        val owned = spiritStoneWallet.balance(source)
        if (owned < quantity) {
            return Result.Insufficient(required = quantity, owned = owned)
        }

        val (converted, remaining) = SpiritStoneExchange.exchange(quantity, source, target)
        if (converted <= 0) return Result.Invalid

        val beforeTarget = spiritStoneWallet.balance(target)
        stateStore.update {
            val result = spiritStoneWallet.batch(this, listOf(
                SpiritStoneOperation(
                    delta = -(quantity - remaining), grade = source,
                    reason = SpiritStoneReason.Exchange, source = SpiritStoneSource.Internal
                ),
                SpiritStoneOperation(
                    delta = converted, grade = target,
                    reason = SpiritStoneReason.Exchange, source = SpiritStoneSource.Internal
                )
            ), autoConvert = true)
        }
        val afterTarget = spiritStoneWallet.balance(target)
        return Result.Success(
            converted = afterTarget - beforeTarget,
            remaining = spiritStoneWallet.balance(source)
        )
    }
}

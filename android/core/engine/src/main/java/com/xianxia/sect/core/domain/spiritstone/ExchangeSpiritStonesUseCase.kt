package com.xianxia.sect.core.domain.spiritstone

import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.engine.system.InventorySystem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灵石跨品阶兑换用例。
 *
 * 调用方只需指定 source（源品阶）、target（目标品阶）和 quantity（源品阶数量），
 * 实际兑换与余额修改由 [InventorySystem] 统一处理。
 */
@Singleton
class ExchangeSpiritStonesUseCase @Inject constructor(
    private val inventorySystem: InventorySystem
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

        val owned = inventorySystem.getSpiritStones(source)
        if (owned < quantity) {
            return Result.Insufficient(required = quantity, owned = owned)
        }

        val beforeTarget = inventorySystem.getSpiritStones(target)
        val success = inventorySystem.exchangeSpiritStones(quantity, source, target)
        if (!success) {
            return Result.Invalid
        }

        val afterTarget = inventorySystem.getSpiritStones(target)
        return Result.Success(
            converted = afterTarget - beforeTarget,
            remaining = inventorySystem.getSpiritStones(source)
        )
    }
}

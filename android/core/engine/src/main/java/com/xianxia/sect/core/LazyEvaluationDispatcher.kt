package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 事件驱动惰性求值调度器。
 * - completionMonth: 预期完成的游戏月份（绝对编号，如 year*12+month）
 * - completionPhase: 预期完成的旬（1=上旬, 2=中旬, 3=下旬）
 *
 * 规则（非焦点域）：
 *   currentMonth < completionMonth → 跳过
 *   currentMonth == completionMonth && currentPhase < completionPhase → 跳过
 *   否则 → 执行结算
 *
 * 规则（焦点域）：
 *   无视上述规则，强制立即结算
 */
@Singleton
class LazyEvaluationDispatcher @Inject constructor(
    private val stateStore: GameStateStore,
    private val thermalMonitor: ThermalMonitor
) {
    /**
     * 判断实体是否应该在本旬结算。
     *
     * @param completionMonth 实体预期的完成月份
     * @param completionPhase 实体预期的完成旬
     * @param currentMonth 当前游戏月份
     * @param currentPhase 当前游戏旬
     * @param isInFocusDomain 是否在焦点域中
     * @return true 表示应该结算
     */
    fun shouldSettle(
        completionMonth: Int,
        completionPhase: Int,
        currentMonth: Int,
        currentPhase: Int,
        isInFocusDomain: Boolean
    ): Boolean {
        if (isInFocusDomain) return true  // 焦点域强制
        if (currentMonth > completionMonth) return true  // 过期兜底
        if (currentMonth == completionMonth && currentPhase >= completionPhase) return true
        return false
    }

    /**
     * 判断实体是否在当前焦点域中。
     */
    fun isInFocusDomain(entity: Any, currentFocusDomain: FocusDomain): Boolean = when (currentFocusDomain) {
        FocusDomain.DISCIPLE_LIST -> entity is Disciple
        FocusDomain.BUILDING_LIST -> entity is ProductionSlot || entity is SpiritFieldPlant
        FocusDomain.MISSION_HALL -> entity is CultivatorCave
        else -> false
    }

    /**
     * 热状态联动：发热时中旬/下旬的实体延迟到下月。
     * 不影响焦点域。
     */
    fun shouldSettleWithThermal(
        completionMonth: Int,
        completionPhase: Int,
        currentMonth: Int,
        currentPhase: Int,
        isInFocusDomain: Boolean
    ): Boolean {
        if (!shouldSettle(completionMonth, completionPhase, currentMonth, currentPhase, isInFocusDomain)) {
            return false
        }
        // 发热时：仅上旬结算的实体才处理，中旬下旬延迟到下月
        if (thermalMonitor.shouldReduceWorkload() && !isInFocusDomain) {
            if (completionPhase > 1) return false
        }
        return true
    }

    /**
     * 系统级热状态联动判断。
     * 发热时跳过非焦点域的中旬/下旬系统（settlementPhase > 1）。
     *
     * @param settlementPhase 系统的结算旬（1=上旬, 2=中旬, 3=下旬, 0=每旬）
     * @param isInFocusDomain 是否在焦点域中
     * @return true 表示该系统应在当前 tick 执行
     */
    fun shouldSystemRunWithThermal(
        settlementPhase: Int,
        isInFocusDomain: Boolean
    ): Boolean {
        if (isInFocusDomain) return true
        if (settlementPhase == 0) return true
        // 发热时：仅上旬系统执行，中旬下旬延迟
        if (thermalMonitor.shouldReduceWorkload() && settlementPhase > 1) {
            return false
        }
        return true
    }

    companion object {
        /** 将游戏年月转换为绝对月份编号 */
        fun toAbsoluteMonth(year: Int, month: Int): Int = (year - 1) * 12 + month

        /** 每月 3 旬 */
        private const val PHASES_PER_MONTH = 3

        /** 估算到下次突破所需的月数，基于修炼速率（每旬）和剩余修为。 */
        fun estimateMonthsToNextBreakthrough(
            remainingCultivation: Double,
            cultivationRatePerPhase: Double,
            minMonths: Int = 1,
            maxMonths: Int = 120
        ): Int {
            val monthlyGain = cultivationRatePerPhase * PHASES_PER_MONTH
            return if (monthlyGain > 0 && remainingCultivation > 0) {
                (remainingCultivation / monthlyGain).toInt().coerceIn(minMonths, maxMonths)
            } else if (monthlyGain <= 0) {
                12
            } else {
                1
            }
        }
    }
}

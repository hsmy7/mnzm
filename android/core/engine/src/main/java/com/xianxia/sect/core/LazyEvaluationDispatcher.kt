package com.xianxia.sect.core.engine

import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 事件驱动惰性求值调度器。
 * - completionMonth: 预期完成的游戏月份（绝对编号，如 year*12+month）
 * - completionPhase: 预期完成的旬（1=上旬, 2=中旬, 3=下旬）
 *
 * 规则：
 *   currentMonth < completionMonth → 跳过
 *   currentMonth == completionMonth && currentPhase < completionPhase → 跳过
 *   其他 → 结算
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
     * @return true 表示应该结算
     */
    fun shouldSettle(
        completionMonth: Int,
        completionPhase: Int,
        currentMonth: Int,
        currentPhase: Int
    ): Boolean {
        if (currentMonth > completionMonth) return true  // 过期兜底
        if (currentMonth == completionMonth && currentPhase >= completionPhase) return true
        return false
    }

    /**
     * 热状态联动：发热时中旬/下旬的实体延迟到下月。
     */
    fun shouldSettleWithThermal(
        completionMonth: Int,
        completionPhase: Int,
        currentMonth: Int,
        currentPhase: Int
    ): Boolean {
        if (!shouldSettle(completionMonth, completionPhase, currentMonth, currentPhase)) {
            return false
        }
        // 发热时：仅上旬结算的实体才处理，中旬下旬延迟到下月
        if (thermalMonitor.shouldReduceWorkload() && currentPhase > 1) {
            return false
        }
        return true
    }

    companion object {
        /** 将 gameYear/gameMonth 转换为绝对月份编号 */
        fun toAbsoluteMonth(year: Int, month: Int): Int = year * 12 + month

        /**
         * 估算距离下一次突破所需月份数。
         * @param remainingCultivation 距突破还差多少修炼值
         * @param ratePerPhase 每旬修炼速度
         * @return 月份数（至少 1 个月，除非已满）
         */
        fun estimateMonthsToNextBreakthrough(
            remainingCultivation: Double,
            ratePerPhase: Double
        ): Int {
            if (remainingCultivation <= 0.0) return 0
            if (ratePerPhase <= 0.0) return Int.MAX_VALUE
            val phasesNeeded = kotlin.math.ceil(remainingCultivation / ratePerPhase).toInt()
            return (phasesNeeded + 2) / 3  // 3 phase = 1 月，向上取整
        }
    }
}

package com.xianxia.sect.core.engine

/**
 * 惰性求值静态助手（结算改造 2026-09-27 B5 收敛）。
 *
 * 本类型原为 `@Singleton` 注入式调度器（shouldSettle/shouldSettleWithThermal），
 * 全仓零调用点、实例从未被注入——名存实亡的死代码（方案 §3.4.4 / §9.1-8），
 * B5 随 L2 改造删除。真正的生产结算驱动是旬 tick（C++ 真相源），
 * UI 只读 `GameEngine.productionSlots` StateFlow 镜像。
 *
 * 仅保留两个仍有消费者的 companion 静态助手：
 * - [toAbsoluteMonth]：年月 → 绝对月编号（旧字段读写点仍在用，随 B6 毫秒化退役）
 * - [estimateMonthsToNextBreakthrough]：突破月份预估（UI 倒计时，随 B8 改毫秒口径）
 */
object LazyEvaluationDispatcher {

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

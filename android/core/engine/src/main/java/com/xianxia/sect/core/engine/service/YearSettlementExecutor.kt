package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService

/**
 * YearSettlementExecutor — 年变结算纯编排器（计划 v2 阶段 2 / T2.3）。
 *
 * 从 [com.xianxia.sect.core.GameEngineCore.processMonthYearChange] 的
 * yearChanged 分支原样提取的编排逻辑：生产 tick 与跨语言对拍测试共用同一入口。
 *
 * 编排（与 C++ `gamecore::system::runYearSettlement` 逐位对应）：
 * 1. processYearlyEvents —— L3b 分帧（T1 立即组单事务 + T2 延迟组入队由引擎
 *    tick 预算 drain）；C++ 侧已下沉年报快照段（garrisonAndReport 年报部分，
 *    驻军轮换恒等路径），其余子项场景规避/登记批次
 *    （.superpowers/sdd/t2-3-semantics.md §1）
 * 2. gameMonth==1 时年俸（calculateSalaryPlan + 发放/忠诚惩罚）
 *
 * 行为契约：与提取前的 yearChanged 分支逐行等价，生产行为零变化。
 * 注意本执行器与 Phase/Month 不同——内部方法各自开启独立事务
 * （processYearlyEvents 的 T1 单事务 + processAnnualSalary 的发放事务），
 * 调用方无需也不应包裹 stateStore.update（与 GameEngineCore 生产调用点一致）。
 */
@GameService("YearSettlementExecutor")
internal class YearSettlementExecutor(
    private val cultivationService: CultivationService
) {

    /**
     * 执行一次年变结算。
     *
     * @param gameYear 已推进到的年份（时间推进后的新年份）
     * @param isJanuary 新年是否为 1 月（年俸仅在 1 月发放——与生产
     *        `stateStore.gameData.value.gameMonth == 1` 判定一致）
     */
    suspend fun execute(gameYear: Int, isJanuary: Boolean) {
        // ① processYearlyEvents：L3b 分帧编排（T1 立即组 + T2 入队；
        //    无参版读 stateStore 当前年份 = 时间推进后的新年份）
        cultivationService.processYearlyEvents()
        // ② 年变为 1 月时发放年度年俸
        if (isJanuary) {
            cultivationService.processAnnualSalary(gameYear)
        }
    }
}

package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.state.MutableGameState

/**
 * 冻结的跨语言时间推进对拍基准（frozen golden reference）。
 *
 * **本文件是原 `TimeSystem.onPhaseTick` 的逐字搬运**（2026-09-15 自生产类
 * 移入测试源集，W4 实施文档 §2.C）：生产旬结算真相源为 C++ `nativeSettlePhase`，
 * 该方法在生产面零调用；移出生产类只为消除"形似生产 API 的死方法"误用面
 * （若被误接进 tick ⇒ 时间双推进），对拍基准本身不损失——它仍是唯一一份
 * Kotlin 实现。**禁止"优化"、禁止改数值语义：改动即等于改对拍标准答案。**
 *
 * 消费方：DiffTimeTest / DiffPhaseSettlementTest / DiffMonthSettlementFixture /
 * DiffYearSettlementTest / DiffAuthoritativeTickTest / SettlementTransactionMergeTest /
 * TimeSystemPureLogicTest。
 */
@Suppress("UnusedParameter") // phasesToSettle: 对拍基准时间驱动器（DiffTimeTest 契约面）——签名即协议
internal fun MutableGameState.advancePhaseBaseline(phasesToSettle: Int = 1) {
    val gd = gameData
    var newPhase = gd.gamePhase + 1
    var newMonth = gd.gameMonth
    var newYear = gd.gameYear

    if (newPhase >= TimeSystem.PHASES_PER_MONTH) {
        newPhase = 0
        newMonth++
        if (newMonth > TimeSystem.MONTHS_PER_YEAR) {
            newMonth = 1
            newYear++
        }
    }

    gameData = gd.copy(gamePhase = newPhase, gameMonth = newMonth, gameYear = newYear)
}

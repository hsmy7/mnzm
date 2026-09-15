package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.GamePhase
import com.xianxia.sect.core.state.GameStateStore
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "TimeSystem"
@SystemPriority(order = 0)
@Singleton
class TimeSystem @Inject constructor(
    private val stateStore: GameStateStore
) : GameSystem {

    companion object {
        private const val TAG = "TimeSystem"
        const val SYSTEM_NAME = "TimeSystem"

        const val PHASES_PER_MONTH = GamePhase.PHASES_PER_MONTH
        const val MONTHS_PER_YEAR = 12
        const val DAYS_PER_MONTH = 30  // 保留兼容
    }

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        DomainLog.d(TAG, "TimeSystem initialized")
    }

    override fun release() {
        DomainLog.d(TAG, "TimeSystem released")
    }

    @Suppress("UnusedParameter") // phasesToSettle: 对拍基准时间驱动器（DiffTimeTest 契约面）——签名即协议
    override fun clearForSlot(slotId: Int) = Unit

    // Kotlin 时间推进对拍基准已移入测试源集：TimeAdvanceBaseline.advancePhaseBaseline
    // （生产面零调用，保留生产类只会构成"形似 API 的死方法"误用面；见 W4 实施文档 §2.C）

    fun getCurrentTime(): Triple<Int, Int, Int> {
        val data = stateStore.gameData.value
        return Triple(data.gameYear, data.gameMonth, data.gamePhase)
    }

    fun getCurrentYear(): Int = stateStore.gameData.value.gameYear
    fun getCurrentMonth(): Int = stateStore.gameData.value.gameMonth
    fun getCurrentPhase(): Int = stateStore.gameData.value.gamePhase

    fun getTotalMonths(): Int {
        val data = stateStore.gameData.value
        return data.gameYear * MONTHS_PER_YEAR + data.gameMonth
    }

    /** 基于旬的总时间单位（用于时间比较，保留兼容语义） */
    fun getTotalPhases(): Int {
        val data = stateStore.gameData.value
        return data.gameYear * MONTHS_PER_YEAR * PHASES_PER_MONTH +
               (data.gameMonth - 1) * PHASES_PER_MONTH +
               data.gamePhase
    }

    fun isEndOfMonth(): Boolean = stateStore.gameData.value.gamePhase == GamePhase.LATE.value

    fun isEndOfYear(): Boolean =
        stateStore.gameData.value.gameMonth == MONTHS_PER_YEAR &&
        stateStore.gameData.value.gamePhase == GamePhase.LATE.value

    fun getMonthName(month: Int): String {
        return when (month) {
            1 -> "一月"
            2 -> "二月"
            3 -> "三月"
            4 -> "四月"
            5 -> "五月"
            6 -> "六月"
            7 -> "七月"
            8 -> "八月"
            9 -> "九月"
            10 -> "十月"
            11 -> "十一月"
            12 -> "十二月"
            else -> "未知"
        }
    }

    fun getPhaseName(phase: Int): String = GamePhase.fromValue(phase).displayName

    fun getFormattedTime(): String {
        val data = stateStore.gameData.value
        return "第${data.gameYear}年${getMonthName(data.gameMonth)}${getPhaseName(data.gamePhase)}"
    }

    fun calculateMonthsBetween(
        startYear: Int,
        startMonth: Int,
        endYear: Int,
        endMonth: Int
    ): Int {
        return (endYear - startYear) * MONTHS_PER_YEAR + (endMonth - startMonth)
    }

    /** 基于旬的时间差计算（1旬 ≈ 10天，保留兼容） */
    fun calculatePhasesBetween(
        startYear: Int,
        startMonth: Int,
        startPhase: Int,
        endYear: Int,
        endMonth: Int,
        endPhase: Int
    ): Int {
        val startTotal = startYear * MONTHS_PER_YEAR * PHASES_PER_MONTH +
                        (startMonth - 1) * PHASES_PER_MONTH + startPhase
        val endTotal = endYear * MONTHS_PER_YEAR * PHASES_PER_MONTH +
                      (endMonth - 1) * PHASES_PER_MONTH + endPhase
        return endTotal - startTotal
    }
}

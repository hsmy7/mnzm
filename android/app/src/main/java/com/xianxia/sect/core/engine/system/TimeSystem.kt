package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 0)
@Singleton
class TimeSystem @Inject constructor(
    private val stateStore: GameStateStore
) : GameSystem {

    companion object {
        private const val TAG = "TimeSystem"
        const val SYSTEM_NAME = "TimeSystem"

        const val DAYS_PER_MONTH = 30
        const val MONTHS_PER_YEAR = 12
    }

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        Log.d(TAG, "TimeSystem initialized")
    }

    override fun release() {
        Log.d(TAG, "TimeSystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onDayTick(state: MutableGameState) {
        val gd = state.gameData
        var newDay = gd.gameDay + 1
        var newMonth = gd.gameMonth
        var newYear = gd.gameYear

        if (newDay > DAYS_PER_MONTH) {
            newDay = 1
            newMonth++
            if (newMonth > MONTHS_PER_YEAR) {
                newMonth = 1
                newYear++
            }
        }

        state.gameData = gd.copy(gameDay = newDay, gameMonth = newMonth, gameYear = newYear)
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        // 日期推进已在 onDayTick 中处理（日期溢出时自动进月/进年）
        // 此方法保留为空，仅作为 GameSystem 接口的事件通知点
        // GameEngineCore.tickInternal 在检测到月份变更时会调用此方法
    }

    override suspend fun onYearTick(state: MutableGameState) {
        // 日期推进已在 onDayTick 中处理（日期溢出时自动进月/进年）
        // 此方法保留为空，仅作为 GameSystem 接口的事件通知点
        // GameEngineCore.tickInternal 在检测到年份变更时会调用此方法
    }

    fun getCurrentTime(): Triple<Int, Int, Int> {
        val data = stateStore.gameData.value
        return Triple(data.gameYear, data.gameMonth, data.gameDay)
    }

    fun getCurrentYear(): Int = stateStore.gameData.value.gameYear
    fun getCurrentMonth(): Int = stateStore.gameData.value.gameMonth
    fun getCurrentDay(): Int = stateStore.gameData.value.gameDay

    fun getTotalMonths(): Int {
        val data = stateStore.gameData.value
        return data.gameYear * MONTHS_PER_YEAR + data.gameMonth
    }

    fun getTotalDays(): Int {
        val data = stateStore.gameData.value
        return data.gameYear * MONTHS_PER_YEAR * DAYS_PER_MONTH +
               (data.gameMonth - 1) * DAYS_PER_MONTH +
               data.gameDay
    }

    fun isEndOfMonth(): Boolean = stateStore.gameData.value.gameDay == DAYS_PER_MONTH

    fun isEndOfYear(): Boolean =
        stateStore.gameData.value.gameMonth == MONTHS_PER_YEAR && stateStore.gameData.value.gameDay == DAYS_PER_MONTH

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

    fun getFormattedTime(): String {
        val data = stateStore.gameData.value
        return "第${data.gameYear}年${getMonthName(data.gameMonth)}${data.gameDay}日"
    }

    fun calculateMonthsBetween(
        startYear: Int,
        startMonth: Int,
        endYear: Int,
        endMonth: Int
    ): Int {
        return (endYear - startYear) * MONTHS_PER_YEAR + (endMonth - startMonth)
    }

    fun calculateDaysBetween(
        startYear: Int,
        startMonth: Int,
        startDay: Int,
        endYear: Int,
        endMonth: Int,
        endDay: Int
    ): Int {
        val startTotal = startYear * MONTHS_PER_YEAR * DAYS_PER_MONTH +
                        (startMonth - 1) * DAYS_PER_MONTH + startDay
        val endTotal = endYear * MONTHS_PER_YEAR * DAYS_PER_MONTH +
                      (endMonth - 1) * DAYS_PER_MONTH + endDay
        return endTotal - startTotal
    }
}

package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.GameData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeSystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "TimeSystem"
        const val SYSTEM_NAME = "TimeSystem"
        
        const val DAYS_PER_MONTH = 30
        const val MONTHS_PER_YEAR = 12
    }
    
    private val _gameData = MutableStateFlow(GameData())
    val gameData: StateFlow<GameData> = _gameData.asStateFlow()
    
    private val _isPaused = MutableStateFlow(true)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    private val _gameSpeed = MutableStateFlow(1)
    val gameSpeed: StateFlow<Int> = _gameSpeed.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "TimeSystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "TimeSystem released")
    }
    
    override suspend fun clear() {
        _gameData.value = GameData()
        _isPaused.value = true
        _gameSpeed.value = 1
    }
    
    fun loadGameData(gameData: GameData) {
        _gameData.value = gameData
    }
    
    fun updateGameData(gameData: GameData) {
        _gameData.value = gameData
    }
    
    fun getGameData(): GameData = _gameData.value
    
    data class TimeAdvanceResult(
        val year: Int,
        val month: Int,
        val day: Int,
        val isNewMonth: Boolean,
        val isNewYear: Boolean
    )
    
    fun advanceDay(): TimeAdvanceResult {
        val current = _gameData.value
        var newDay = current.gameDay + 1
        var newMonth = current.gameMonth
        var newYear = current.gameYear
        var isNewMonth = false
        var isNewYear = false
        
        if (newDay > DAYS_PER_MONTH) {
            newDay = 1
            newMonth++
            isNewMonth = true
            
            if (newMonth > MONTHS_PER_YEAR) {
                newMonth = 1
                newYear++
                isNewYear = true
            }
        }
        
        _gameData.value = current.copy(
            gameYear = newYear,
            gameMonth = newMonth,
            gameDay = newDay
        )
        
        return TimeAdvanceResult(
            year = newYear,
            month = newMonth,
            day = newDay,
            isNewMonth = isNewMonth,
            isNewYear = isNewYear
        )
    }
    
    fun advanceMonth(): TimeAdvanceResult {
        val current = _gameData.value
        var newMonth = current.gameMonth + 1
        var newYear = current.gameYear
        val isNewYear: Boolean
        
        if (newMonth > MONTHS_PER_YEAR) {
            newMonth = 1
            newYear++
            isNewYear = true
        } else {
            isNewYear = false
        }
        
        _gameData.value = current.copy(
            gameYear = newYear,
            gameMonth = newMonth,
            gameDay = 1
        )
        
        return TimeAdvanceResult(
            year = newYear,
            month = newMonth,
            day = 1,
            isNewMonth = true,
            isNewYear = isNewYear
        )
    }
    
    fun getCurrentTime(): Triple<Int, Int, Int> {
        val data = _gameData.value
        return Triple(data.gameYear, data.gameMonth, data.gameDay)
    }
    
    fun getCurrentYear(): Int = _gameData.value.gameYear
    fun getCurrentMonth(): Int = _gameData.value.gameMonth
    fun getCurrentDay(): Int = _gameData.value.gameDay
    
    fun getTotalMonths(): Int {
        val data = _gameData.value
        return data.gameYear * MONTHS_PER_YEAR + data.gameMonth
    }
    
    fun getTotalDays(): Int {
        val data = _gameData.value
        return data.gameYear * MONTHS_PER_YEAR * DAYS_PER_MONTH + 
               (data.gameMonth - 1) * DAYS_PER_MONTH + 
               data.gameDay
    }
    
    fun setTime(year: Int, month: Int, day: Int = 1) {
        if (year < 1 || month < 1 || month > MONTHS_PER_YEAR || day < 1 || day > DAYS_PER_MONTH) {
            Log.w(TAG, "Invalid time: year=$year, month=$month, day=$day")
            return
        }
        
        _gameData.value = _gameData.value.copy(
            gameYear = year,
            gameMonth = month,
            gameDay = day
        )
    }
    
    fun pause() {
        _isPaused.value = true
        Log.d(TAG, "Game paused")
    }
    
    fun resume() {
        _isPaused.value = false
        Log.d(TAG, "Game resumed")
    }
    
    fun togglePause() {
        _isPaused.value = !_isPaused.value
        Log.d(TAG, "Game ${if (_isPaused.value) "paused" else "resumed"}")
    }
    
    fun setGameSpeed(speed: Int) {
        if (speed < 1 || speed > 10) {
            Log.w(TAG, "Invalid game speed: $speed, must be in range [1, 10]")
            return
        }
        _gameSpeed.value = speed
        Log.d(TAG, "Game speed set to $speed")
    }
    
    fun isRunning(): Boolean = !_isPaused.value
    
    fun getGameSpeed(): Int = _gameSpeed.value
    
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
    
    fun isEndOfMonth(): Boolean = _gameData.value.gameDay == DAYS_PER_MONTH
    
    fun isEndOfYear(): Boolean = 
        _gameData.value.gameMonth == MONTHS_PER_YEAR && _gameData.value.gameDay == DAYS_PER_MONTH
    
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
        val data = _gameData.value
        return "第${data.gameYear}年${getMonthName(data.gameMonth)}${data.gameDay}日"
    }
}

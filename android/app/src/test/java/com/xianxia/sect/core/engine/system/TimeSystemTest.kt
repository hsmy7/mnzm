package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.GameData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimeSystemTest {

    private lateinit var system: TimeSystem

    @Before
    fun setUp() {
        system = TimeSystem()
        system.initialize()
    }

    // ========== advanceDay 测试 ==========

    @Test
    fun `advanceDay - 正常推进一天`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 1))
        val result = system.advanceDay()
        assertEquals(2, result.day)
        assertEquals(1, result.month)
        assertEquals(1, result.year)
        assertFalse(result.isNewMonth)
        assertFalse(result.isNewYear)
    }

    @Test
    fun `advanceDay - 第30天推进到下月`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 30))
        val result = system.advanceDay()
        assertEquals(1, result.day)
        assertEquals(2, result.month)
        assertTrue(result.isNewMonth)
        assertFalse(result.isNewYear)
    }

    @Test
    fun `advanceDay - 12月30天推进到下年`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 12, gameDay = 30))
        val result = system.advanceDay()
        assertEquals(1, result.day)
        assertEquals(1, result.month)
        assertEquals(2, result.year)
        assertTrue(result.isNewMonth)
        assertTrue(result.isNewYear)
    }

    @Test
    fun `advanceDay - 连续推进30天跨月`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 1))
        for (i in 1..29) {
            system.advanceDay()
        }
        val result = system.advanceDay()
        assertEquals(1, result.day)
        assertEquals(2, result.month)
        assertTrue(result.isNewMonth)
    }

    // ========== advanceMonth 测试 ==========

    @Test
    fun `advanceMonth - 正常推进一月`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 15))
        val result = system.advanceMonth()
        assertEquals(1, result.day)
        assertEquals(2, result.month)
        assertEquals(1, result.year)
        assertTrue(result.isNewMonth)
        assertFalse(result.isNewYear)
    }

    @Test
    fun `advanceMonth - 12月推进到下年`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 12, gameDay = 15))
        val result = system.advanceMonth()
        assertEquals(1, result.day)
        assertEquals(1, result.month)
        assertEquals(2, result.year)
        assertTrue(result.isNewMonth)
        assertTrue(result.isNewYear)
    }

    // ========== getCurrentTime 测试 ==========

    @Test
    fun `getCurrentTime - 获取当前时间`() {
        system.loadGameData(GameData(gameYear = 5, gameMonth = 3, gameDay = 12))
        val (year, month, day) = system.getCurrentTime()
        assertEquals(5, year)
        assertEquals(3, month)
        assertEquals(12, day)
    }

    @Test
    fun `getCurrentYear - 获取当前年`() {
        system.loadGameData(GameData(gameYear = 5, gameMonth = 3, gameDay = 12))
        assertEquals(5, system.getCurrentYear())
    }

    @Test
    fun `getCurrentMonth - 获取当前月`() {
        system.loadGameData(GameData(gameYear = 5, gameMonth = 3, gameDay = 12))
        assertEquals(3, system.getCurrentMonth())
    }

    @Test
    fun `getCurrentDay - 获取当前日`() {
        system.loadGameData(GameData(gameYear = 5, gameMonth = 3, gameDay = 12))
        assertEquals(12, system.getCurrentDay())
    }

    // ========== getTotalMonths / getTotalDays 测试 ==========

    @Test
    fun `getTotalMonths - 计算总月数`() {
        system.loadGameData(GameData(gameYear = 2, gameMonth = 6, gameDay = 1))
        assertEquals(30, system.getTotalMonths())
    }

    @Test
    fun `getTotalDays - 计算总天数`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 2, gameDay = 15))
        val expected = 1 * 12 * 30 + (2 - 1) * 30 + 15
        assertEquals(expected, system.getTotalDays())
    }

    // ========== setTime 测试 ==========

    @Test
    fun `setTime - 正常设置时间`() {
        system.setTime(5, 3, 12)
        assertEquals(5, system.getCurrentYear())
        assertEquals(3, system.getCurrentMonth())
        assertEquals(12, system.getCurrentDay())
    }

    @Test
    fun `setTime - 无效年份不设置`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 1))
        system.setTime(0, 1, 1)
        assertEquals(1, system.getCurrentYear())
    }

    @Test
    fun `setTime - 无效月份不设置`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 1))
        system.setTime(1, 0, 1)
        assertEquals(1, system.getCurrentMonth())
        system.setTime(1, 13, 1)
        assertEquals(1, system.getCurrentMonth())
    }

    @Test
    fun `setTime - 无效日期不设置`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 1))
        system.setTime(1, 1, 0)
        assertEquals(1, system.getCurrentDay())
        system.setTime(1, 1, 31)
        assertEquals(1, system.getCurrentDay())
    }

    // ========== 暂停/恢复测试 ==========

    @Test
    fun `pause - 暂停游戏`() {
        system.resume()
        system.pause()
        assertTrue(system.isPaused.value)
    }

    @Test
    fun `resume - 恢复游戏`() {
        system.resume()
        assertFalse(system.isPaused.value)
    }

    @Test
    fun `togglePause - 切换暂停状态`() {
        assertTrue(system.isPaused.value)
        system.togglePause()
        assertFalse(system.isPaused.value)
        system.togglePause()
        assertTrue(system.isPaused.value)
    }

    @Test
    fun `isRunning - 运行状态判断`() {
        assertFalse(system.isRunning())
        system.resume()
        assertTrue(system.isRunning())
    }

    // ========== 游戏速度测试 ==========

    @Test
    fun `setGameSpeed - 正常设置速度`() {
        system.setGameSpeed(5)
        assertEquals(5, system.getGameSpeed())
    }

    @Test
    fun `setGameSpeed - 速度1到10有效`() {
        for (speed in 1..10) {
            system.setGameSpeed(speed)
            assertEquals(speed, system.getGameSpeed())
        }
    }

    @Test
    fun `setGameSpeed - 速度0无效`() {
        system.setGameSpeed(1)
        system.setGameSpeed(0)
        assertEquals(1, system.getGameSpeed())
    }

    @Test
    fun `setGameSpeed - 速度11无效`() {
        system.setGameSpeed(1)
        system.setGameSpeed(11)
        assertEquals(1, system.getGameSpeed())
    }

    // ========== 计算方法测试 ==========

    @Test
    fun `calculateMonthsBetween - 计算月份差`() {
        val months = system.calculateMonthsBetween(1, 1, 2, 6)
        assertEquals(17, months)
    }

    @Test
    fun `calculateMonthsBetween - 同月返回0`() {
        val months = system.calculateMonthsBetween(1, 5, 1, 5)
        assertEquals(0, months)
    }

    @Test
    fun `calculateMonthsBetween - 负数差`() {
        val months = system.calculateMonthsBetween(2, 6, 1, 1)
        assertEquals(-17, months)
    }

    @Test
    fun `calculateDaysBetween - 计算天数差`() {
        val days = system.calculateDaysBetween(1, 1, 1, 1, 2, 1)
        assertEquals(30, days)
    }

    @Test
    fun `calculateDaysBetween - 同一天返回0`() {
        val days = system.calculateDaysBetween(1, 1, 1, 1, 1, 1)
        assertEquals(0, days)
    }

    // ========== 月末年末判断测试 ==========

    @Test
    fun `isEndOfMonth - 第30天返回true`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 30))
        assertTrue(system.isEndOfMonth())
    }

    @Test
    fun `isEndOfMonth - 非第30天返回false`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 1, gameDay = 15))
        assertFalse(system.isEndOfMonth())
    }

    @Test
    fun `isEndOfYear - 12月30天返回true`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 12, gameDay = 30))
        assertTrue(system.isEndOfYear())
    }

    @Test
    fun `isEndOfYear - 非年末返回false`() {
        system.loadGameData(GameData(gameYear = 1, gameMonth = 6, gameDay = 30))
        assertFalse(system.isEndOfYear())
    }

    // ========== getMonthName 测试 ==========

    @Test
    fun `getMonthName - 所有月份名称正确`() {
        val expected = mapOf(
            1 to "一月", 2 to "二月", 3 to "三月", 4 to "四月",
            5 to "五月", 6 to "六月", 7 to "七月", 8 to "八月",
            9 to "九月", 10 to "十月", 11 to "十一月", 12 to "十二月"
        )
        for ((month, name) in expected) {
            assertEquals(name, system.getMonthName(month))
        }
    }

    @Test
    fun `getMonthName - 无效月份返回未知`() {
        assertEquals("未知", system.getMonthName(0))
        assertEquals("未知", system.getMonthName(13))
    }

    // ========== getFormattedTime 测试 ==========

    @Test
    fun `getFormattedTime - 格式化时间`() {
        system.loadGameData(GameData(gameYear = 5, gameMonth = 3, gameDay = 12))
        val formatted = system.getFormattedTime()
        assertTrue(formatted.contains("5"))
        assertTrue(formatted.contains("三月"))
        assertTrue(formatted.contains("12"))
    }

    // ========== clear 测试 ==========

    @Test
    fun `clear - 清空数据`() = runBlocking {
        system.loadGameData(GameData(gameYear = 5, gameMonth = 3, gameDay = 12))
        system.resume()
        system.setGameSpeed(5)
        system.clear()
        assertEquals(1, system.getCurrentYear())
        assertTrue(system.isPaused.value)
        assertEquals(1, system.getGameSpeed())
    }
}

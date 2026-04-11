package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.EventType
import com.xianxia.sect.core.model.GameEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventSubsystemTest {

    private lateinit var system: EventSubsystem

    @Before
    fun setUp() {
        system = EventSubsystem()
        system.initialize()
    }

    @Test
    fun `addEvent - 添加消息事件`() {
        system.addEvent("测试事件", EventType.INFO)
        val events = system.getEvents()
        assertEquals(1, events.size)
        assertEquals("测试事件", events[0].message)
        assertEquals(EventType.INFO, events[0].type)
    }

    @Test
    fun `addEvent - 添加GameEvent对象`() {
        val event = GameEvent(
            message = "自定义事件",
            type = EventType.BATTLE,
            timestamp = 1000L
        )
        system.addEvent(event)
        val events = system.getEvents()
        assertEquals(1, events.size)
        assertEquals("自定义事件", events[0].message)
        assertEquals(EventType.BATTLE, events[0].type)
        assertEquals(1000L, events[0].timestamp)
    }

    @Test
    fun `addEvent - 默认类型为INFO`() {
        system.addEvent("默认类型事件")
        val events = system.getEvents()
        assertEquals(EventType.INFO, events[0].type)
    }

    @Test
    fun `addEvent - 各种事件类型`() {
        EventType.values().forEach { type ->
            system.addEvent("${type.name}事件", type)
        }
        val events = system.getEvents()
        assertEquals(EventType.values().size, events.size)
    }

    @Test
    fun `addEvent - 超过MAX_EVENTS时保留最新`() {
        for (i in 1..150) {
            system.addEvent("事件$i")
        }
        val events = system.getEvents()
        assertEquals(100, events.size)
        assertEquals("事件51", events[0].message)
        assertEquals("事件150", events.last().message)
    }

    @Test
    fun `getRecentEvents - 获取最近N条事件`() {
        for (i in 1..20) {
            system.addEvent("事件$i")
        }
        val recent = system.getRecentEvents(5)
        assertEquals(5, recent.size)
        assertEquals("事件16", recent[0].message)
        assertEquals("事件20", recent.last().message)
    }

    @Test
    fun `getRecentEvents - 请求数量超过实际数量`() {
        for (i in 1..5) {
            system.addEvent("事件$i")
        }
        val recent = system.getRecentEvents(10)
        assertEquals(5, recent.size)
    }

    @Test
    fun `getRecentEvents - 默认获取10条`() {
        for (i in 1..20) {
            system.addEvent("事件$i")
        }
        val recent = system.getRecentEvents()
        assertEquals(10, recent.size)
    }

    @Test
    fun `clearEvents - 清空所有事件`() {
        for (i in 1..10) {
            system.addEvent("事件$i")
        }
        system.clearEvents()
        assertTrue(system.getEvents().isEmpty())
    }

    @Test
    fun `addBattleLog - 添加战斗日志`() {
        val log = BattleLog(
            id = "b1",
            dungeonName = "虎啸岭",
            result = BattleResult.WIN
        )
        system.addBattleLog(log)
        val logs = system.getBattleLogs()
        assertEquals(1, logs.size)
        assertEquals("b1", logs[0].id)
    }

    @Test
    fun `addBattleLog - 超过MAX_BATTLE_LOGS时保留最新`() {
        for (i in 1..60) {
            system.addBattleLog(BattleLog(id = "b$i", dungeonName = "副本$i", result = BattleResult.WIN))
        }
        val logs = system.getBattleLogs()
        assertEquals(50, logs.size)
        assertEquals("b11", logs[0].id)
        assertEquals("b60", logs.last().id)
    }

    @Test
    fun `getRecentBattleLogs - 获取最近N条日志`() {
        for (i in 1..20) {
            system.addBattleLog(BattleLog(id = "b$i", dungeonName = "副本$i", result = BattleResult.WIN))
        }
        val recent = system.getRecentBattleLogs(5)
        assertEquals(5, recent.size)
        assertEquals("b16", recent[0].id)
    }

    @Test
    fun `clearBattleLogs - 清空所有战斗日志`() {
        system.addBattleLog(BattleLog(id = "b1", dungeonName = "副本1", result = BattleResult.WIN))
        system.clearBattleLogs()
        assertTrue(system.getBattleLogs().isEmpty())
    }

    @Test
    fun `loadEvents - 加载事件列表`() {
        val events = listOf(
            GameEvent(message = "事件1", type = EventType.INFO, timestamp = 1L),
            GameEvent(message = "事件2", type = EventType.BATTLE, timestamp = 2L)
        )
        system.loadEvents(events)
        assertEquals(2, system.getEvents().size)
    }

    @Test
    fun `loadBattleLogs - 加载战斗日志列表`() {
        val logs = listOf(
            BattleLog(id = "b1", dungeonName = "副本1", result = BattleResult.WIN),
            BattleLog(id = "b2", dungeonName = "副本2", result = BattleResult.LOSE)
        )
        system.loadBattleLogs(logs)
        assertEquals(2, system.getBattleLogs().size)
    }

    @Test
    fun `clear - 清空所有数据`() = runBlocking {
        system.addEvent("事件1")
        system.addBattleLog(BattleLog(id = "b1", dungeonName = "副本1", result = BattleResult.WIN))
        system.clear()
        assertTrue(system.getEvents().isEmpty())
        assertTrue(system.getBattleLogs().isEmpty())
    }

    @Test
    fun `addEvent - 事件按添加顺序排列`() {
        system.addEvent("第一")
        system.addEvent("第二")
        system.addEvent("第三")
        val events = system.getEvents()
        assertEquals("第一", events[0].message)
        assertEquals("第二", events[1].message)
        assertEquals("第三", events[2].message)
    }

    @Test
    fun `addBattleLog - 日志按添加顺序排列`() {
        system.addBattleLog(BattleLog(id = "b1", dungeonName = "副本1", result = BattleResult.WIN))
        system.addBattleLog(BattleLog(id = "b2", dungeonName = "副本2", result = BattleResult.WIN))
        val logs = system.getBattleLogs()
        assertEquals("b1", logs[0].id)
        assertEquals("b2", logs[1].id)
    }
}

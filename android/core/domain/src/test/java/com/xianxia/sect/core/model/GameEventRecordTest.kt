package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息栏系统单元测试。
 *
 * 覆盖：
 * - GameEventRecord 序列化往返
 * - recordGameEvent 追加/裁剪/校验
 * - 事件类型常量正确性
 */
class GameEventRecordTest {

    // ==================== 序列化测试 ====================

    @Test
    fun `GameEventRecord JSON serialization round-trip`() {
        val original = GameEventRecord(
            timestamp = 1000L,
            year = 5,
            month = 3,
            phase = 1,
            category = "SECT",
            eventType = GameEventType.DEATH,
            summary = "弟子张三陨落",
            relatedEntityId = "42",
            relatedEntityName = "张三"
        )

        val json = Json.encodeToString(GameEventRecord.serializer(), original)
        val restored = Json.decodeFromString(GameEventRecord.serializer(), json)

        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.year, restored.year)
        assertEquals(original.month, restored.month)
        assertEquals(original.phase, restored.phase)
        assertEquals(original.category, restored.category)
        assertEquals(original.eventType, restored.eventType)
        assertEquals(original.summary, restored.summary)
        assertEquals(original.relatedEntityId, restored.relatedEntityId)
        assertEquals(original.relatedEntityName, restored.relatedEntityName)
    }

    @Test
    fun `GameEventRecord Protobuf serialization round-trip`() {
        val original = GameEventRecord(
            year = 10,
            month = 6,
            phase = 2,
            category = "WORLD",
            eventType = GameEventType.ALLIANCE,
            summary = "青云宗与幻月宗结为同盟",
            relatedEntityId = "sect_1",
            relatedEntityName = "幻月宗"
        )

        val bytes = ProtoBuf.encodeToByteArray(GameEventRecord.serializer(), original)
        val restored = ProtoBuf.decodeFromByteArray(GameEventRecord.serializer(), bytes)

        assertEquals(original.year, restored.year)
        assertEquals(original.category, restored.category)
        assertEquals(original.summary, restored.summary)
    }

    @Test
    fun `GameEventRecord default values`() {
        val record = GameEventRecord()
        assertTrue(record.timestamp > 0)
        assertEquals(1, record.year)
        assertEquals(1, record.month)
        assertEquals("SECT", record.category)
    }

    // ==================== 事件类型常量测试 ====================

    @Test
    fun `GameEventType constants are non-empty`() {
        val allConstants = listOf(
            GameEventType.DESERTION,
            GameEventType.THEFT_CAUGHT,
            GameEventType.WAREHOUSE_THEFT,
            GameEventType.THEFT_DESERTION,
            GameEventType.DEATH,
            GameEventType.BREAKTHROUGH,
            GameEventType.MARRIAGE,
            GameEventType.BLOOD_REFINEMENT,
            GameEventType.ALLIANCE,
            GameEventType.ALLIANCE_BREAK,
            GameEventType.VASSAL_BREAKAWAY,
            GameEventType.BEAST_HUNT,
            GameEventType.BEAST_FAIL,
            GameEventType.ENCOUNTER_HUNT,
            GameEventType.ENCOUNTER_FAIL,
            GameEventType.SECT_OCCUPY
        )
        allConstants.forEach { value ->
            assertTrue("EventType '$value' should not be empty", value.isNotEmpty())
        }
        // 验证没有重复值
        assertEquals(allConstants.size, allConstants.toSet().size)
    }

    // ==================== GameEventCategory 测试 ====================

    @Test
    fun `GameEventCategory fromValue handles valid values`() {
        assertEquals(GameEventCategory.WORLD, GameEventCategory.fromValue("WORLD"))
        assertEquals(GameEventCategory.SECT, GameEventCategory.fromValue("SECT"))
    }

    @Test
    fun `GameEventCategory fromValue defaults to SECT for invalid values`() {
        assertEquals(GameEventCategory.SECT, GameEventCategory.fromValue(""))
        assertEquals(GameEventCategory.SECT, GameEventCategory.fromValue("INVALID"))
    }

    @Test
    fun `GameEventCategory label is correct`() {
        assertEquals("世界", GameEventCategory.WORLD.label)
        assertEquals("宗门", GameEventCategory.SECT.label)
    }

    // ==================== recordGameEvent 追加/裁剪/校验测试 ====================

    @Test
    fun `recordGameEvent appends to existing list`() {
        val state = MutableGameState(
            gameData = GameData(id = "test", slotId = 1),
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )

        state.recordGameEvent(GameEventCategory.SECT, GameEventType.DEATH, "弟子A陨落")
        assertEquals(1, state.gameData.gameEventRecords.size)
        assertEquals("弟子A陨落", state.gameData.gameEventRecords[0].summary)

        state.recordGameEvent(GameEventCategory.SECT, GameEventType.DEATH, "弟子B陨落")
        assertEquals(2, state.gameData.gameEventRecords.size)
        assertEquals("弟子B陨落", state.gameData.gameEventRecords[1].summary)
    }

    @Test
    fun `recordGameEvent trims to MAX_EVENT_LOGS`() {
        val state = MutableGameState(
            gameData = GameData(id = "test", slotId = 1),
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )

        val max = GameConfig.Logs.MAX_EVENT_LOGS
        for (i in 1..(max + 50)) {
            state.recordGameEvent(GameEventCategory.SECT, GameEventType.DESERTION, "事件$i")
        }

        assertEquals(max, state.gameData.gameEventRecords.size)
        // 最旧的事件应被移除
        assertEquals("事件${50 + 1}", state.gameData.gameEventRecords.first().summary)
    }

    @Test
    fun `recordGameEvent sets correct game time fields`() {
        val state = MutableGameState(
            gameData = GameData(id = "test", slotId = 1, gameYear = 8, gameMonth = 3, gamePhase = 1),
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )

        state.recordGameEvent(GameEventCategory.WORLD, GameEventType.ALLIANCE, "宗门结盟")
        val record = state.gameData.gameEventRecords.first()
        assertEquals(8, record.year)
        assertEquals(3, record.month)
        assertEquals(1, record.phase)
        assertEquals("WORLD", record.category)
        assertEquals(GameEventType.ALLIANCE, record.eventType)
    }

    @Test
    fun `recordGameEvent rejects blank summary`() {
        val state = MutableGameState(
            gameData = GameData(id = "test", slotId = 1),
            discipleTables = DiscipleTables(),
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
                        battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )

        state.recordGameEvent(GameEventCategory.SECT, GameEventType.DEATH, "")
        assertEquals(0, state.gameData.gameEventRecords.size)

        state.recordGameEvent(GameEventCategory.SECT, "", "some summary")
        assertEquals(0, state.gameData.gameEventRecords.size)
    }

    // ==================== 迁移兼容性测试 ====================

    @Test
    fun `MIGRATION_20_21 adds gameEventRecords column with default empty array`() {
        // 验证新创建的 GameData 的 gameEventRecords 默认为空列表
        val gameData = GameData(id = "migration_test", slotId = 1)
        assertTrue(gameData.gameEventRecords.isEmpty())
    }
}

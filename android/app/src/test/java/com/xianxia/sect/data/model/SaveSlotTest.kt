package com.xianxia.sect.data.model

import org.junit.Assert.*
import org.junit.Test

class SaveSlotTest {

    @Test
    fun `constructor sets all fields`() {
        val slot = SaveSlot(
            slot = 1,
            name = "Save 1",
            timestamp = 1700000000000L,
            gameYear = 5,
            gameMonth = 3,
            sectName = "青云宗",
            discipleCount = 42,
            spiritStones = 10000L,
            isEmpty = false,
            customName = "我的存档"
        )
        assertEquals(1, slot.slot)
        assertEquals("Save 1", slot.name)
        assertEquals(1700000000000L, slot.timestamp)
        assertEquals(5, slot.gameYear)
        assertEquals(3, slot.gameMonth)
        assertEquals("青云宗", slot.sectName)
        assertEquals(42, slot.discipleCount)
        assertEquals(10000L, slot.spiritStones)
        assertFalse(slot.isEmpty)
        assertEquals("我的存档", slot.customName)
    }

    @Test
    fun `isEmpty defaults to false`() {
        val slot = SaveSlot(1, "Save 1", 0L, 1, 1, "", 0, 0L)
        assertFalse(slot.isEmpty)
    }

    @Test
    fun `customName defaults to empty string`() {
        val slot = SaveSlot(1, "Save 1", 0L, 1, 1, "", 0, 0L)
        assertEquals("", slot.customName)
    }

    @Test
    fun `displayTime formats correctly`() {
        val slot = SaveSlot(1, "Save 1", 0L, gameYear = 5, gameMonth = 8, "", 0, 0L)
        assertEquals("第5年8月", slot.displayTime)
    }

    @Test
    fun `displayTime with year 1 month 1`() {
        val slot = SaveSlot(1, "Save 1", 0L, gameYear = 1, gameMonth = 1, "", 0, 0L)
        assertEquals("第1年1月", slot.displayTime)
    }

    @Test
    fun `saveTime formats timestamp`() {
        val slot = SaveSlot(1, "Save 1", 1700000000000L, 1, 1, "", 0, 0L)
        val saveTime = slot.saveTime
        assertNotNull(saveTime)
        assertTrue(saveTime.isNotEmpty())
    }

    @Test
    fun `displayName returns customName when not blank`() {
        val slot = SaveSlot(1, "Save 1", 0L, 1, 1, "", 0, 0L, customName = "我的存档")
        assertEquals("我的存档", slot.displayName)
    }

    @Test
    fun `displayName returns name when customName is blank`() {
        val slot = SaveSlot(1, "Save 1", 0L, 1, 1, "", 0, 0L, customName = "")
        assertEquals("Save 1", slot.displayName)
    }

    @Test
    fun `displayName returns name when customName is whitespace`() {
        val slot = SaveSlot(1, "Save 1", 0L, 1, 1, "", 0, 0L, customName = "   ")
        assertEquals("Save 1", slot.displayName)
    }
}

class SaveDataTest {

    @Test
    fun `default version matches GameConfig`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        assertEquals(com.xianxia.sect.core.GameConfig.Game.VERSION, data.version)
    }

    @Test
    fun `timestamp defaults to current time`() {
        val before = System.currentTimeMillis()
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        val after = System.currentTimeMillis()
        assertTrue(data.timestamp in before..after)
    }

    @Test
    fun `battleLogs defaults to empty list`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        assertTrue(data.battleLogs.isEmpty())
    }

    @Test
    fun `alliances defaults to empty list`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        assertTrue(data.alliances.isEmpty())
    }

    @Test
    fun `productionSlots defaults to empty list`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        assertTrue(data.productionSlots.isEmpty())
    }

    @Test
    fun `copy preserves all fields`() {
        val original = SaveData(
            version = "1.5.00",
            timestamp = 123456789L,
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = listOf(com.xianxia.sect.core.model.Disciple()),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList(),
            battleLogs = listOf(com.xianxia.sect.core.model.BattleLog()),
            alliances = emptyList(),
            productionSlots = emptyList()
        )
        val copy = original.copy(version = "1.6.00")
        assertEquals("1.6.00", copy.version)
        assertEquals(original.timestamp, copy.timestamp)
        assertEquals(1, copy.disciples.size)
        assertEquals(1, copy.battleLogs.size)
    }
}

package com.xianxia.sect.data.model

import com.xianxia.sect.data.unified.SaveInfo
import org.junit.Assert.*
import org.junit.Test

class SaveInfoTest {

    @Test
    fun `constructor sets all fields`() {
        val save = SaveInfo(
            timestamp = 1700000000000L,
            gameYear = 5,
            gameMonth = 3,
            sectName = "青云宗",
            discipleCount = 42,
            spiritStones = 10000L,
            isEmpty = false
        )
        assertEquals(1700000000000L, save.timestamp)
        assertEquals(5, save.gameYear)
        assertEquals(3, save.gameMonth)
        assertEquals("青云宗", save.sectName)
        assertEquals(42, save.discipleCount)
        assertEquals(10000L, save.spiritStones)
        assertFalse(save.isEmpty)
        assertFalse(save.isLoadError)
    }

    @Test
    fun `isEmpty defaults to false`() {
        val save = SaveInfo()
        assertFalse(save.isEmpty)
    }

    @Test
    fun `empty save has placeholder display fields`() {
        val save = SaveInfo(isEmpty = true)
        assertEquals("第1年1月", save.displayTime)
        assertEquals("--", save.saveTime)
    }

    @Test
    fun `displayTime formats correctly`() {
        val save = SaveInfo(gameYear = 5, gameMonth = 8)
        assertEquals("第5年8月", save.displayTime)
    }

    @Test
    fun `displayTime with year 1 month 1`() {
        val save = SaveInfo(gameYear = 1, gameMonth = 1)
        assertEquals("第1年1月", save.displayTime)
    }

    @Test
    fun `saveTime formats timestamp`() {
        val save = SaveInfo(timestamp = 1700000000000L)
        val saveTime = save.saveTime
        assertNotNull(saveTime)
        assertTrue(saveTime.isNotEmpty())
    }

    @Test
    fun `saveTime is placeholder when timestamp is zero`() {
        val save = SaveInfo(timestamp = 0L)
        assertEquals("--", save.saveTime)
    }
}

class SaveDataTest {

    @Test
    fun `default version matches GameConfig`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
        assertEquals(com.xianxia.sect.core.GameConfig.Game.VERSION, data.version)
    }

    @Test
    fun `timestamp defaults to current time`() {
        val before = System.currentTimeMillis()
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
        val after = System.currentTimeMillis()
        assertTrue(data.timestamp in before..after)
    }

    @Test
    fun `battleLogs defaults to empty list`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
        assertTrue(data.battleLogs.isEmpty())
    }

    @Test
    fun `alliances defaults to empty list`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
        assertTrue(data.alliances.isEmpty())
    }

    @Test
    fun `productionSlots defaults to empty list`() {
        val data = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
        assertTrue(data.productionSlots.isEmpty())
    }

    @Test
    fun `copy preserves all fields`() {
        val original = SaveData(
            version = "2.0.00",
            timestamp = 123456789L,
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = listOf(com.xianxia.sect.core.model.Disciple()),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                        battleLogs = listOf(com.xianxia.sect.core.model.BattleLog()),
            alliances = emptyList(),
            productionSlots = emptyList()
        )
        val copy = original.copy(version = "2.0.00")
        assertEquals("2.0.00", copy.version)
        assertEquals(original.timestamp, copy.timestamp)
        assertEquals(1, copy.disciples.size)
        assertEquals(1, copy.battleLogs.size)
    }
}

package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BattleLogRefRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(BattleLogRefRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `valid logs pass`() {
        val data = saveData(
            battleLogs = listOf(
                BattleLog(
                    id = "b-1", year = 5, month = 6, turns = 3,
                    teamCasualties = 1, attackerName = "甲", defenderName = "乙"
                ),
                BattleLog(id = "b-2", year = 1, month = 12, turns = 0, teamCasualties = 0, details = "巡山")
            )
        )
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `year zero log removed`() {
        val data = saveData(battleLogs = listOf(validLog(id = "b-1", year = 0)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.battleLogs.isEmpty())
    }

    @Test
    fun `invalid month log removed`() {
        val data = saveData(battleLogs = listOf(validLog(id = "b-1", month = 13)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.battleLogs.isEmpty())
    }

    @Test
    fun `negative turns log removed`() {
        val data = saveData(battleLogs = listOf(validLog(id = "b-1", turns = -1)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.battleLogs.isEmpty())
    }

    @Test
    fun `oversized turns log removed`() {
        val data = saveData(battleLogs = listOf(validLog(id = "b-1", turns = 100_001)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.battleLogs.isEmpty())
    }

    @Test
    fun `negative team casualties log removed`() {
        val data = saveData(battleLogs = listOf(validLog(id = "b-1", teamCasualties = -1)))
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.battleLogs.isEmpty())
    }

    @Test
    fun `blank stub log removed`() {
        // 全空条目：名称/详情空白且无成员/敌人
        val data = saveData(
            battleLogs = listOf(
                BattleLog(id = "b-blank", year = 3, month = 3, turns = 1, teamCasualties = 0)
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        assertTrue((result as IntegrityResult.Repaired).data.battleLogs.isEmpty())
    }

    @Test
    fun `mixed logs only remove invalid ones`() {
        val data = saveData(
            battleLogs = listOf(
                validLog(id = "b-ok1", year = 3, month = 3),
                validLog(id = "b-bad", year = 0),
                validLog(id = "b-ok2", year = 4, month = 9)
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        val kept = (result as IntegrityResult.Repaired).data.battleLogs
        assertEquals(listOf("b-ok1", "b-ok2"), kept.map { it.id })
    }

    private fun validLog(
        id: String = "b-1",
        year: Int = 3,
        month: Int = 3,
        turns: Int = 2,
        teamCasualties: Int = 0
    ) = BattleLog(
        id = id, year = year, month = month, turns = turns,
        teamCasualties = teamCasualties, attackerName = "甲", defenderName = "乙",
        details = "战报"
    )

    private fun saveData(battleLogs: List<BattleLog>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 5, gameMonth = 6),
        disciples = emptyList(), pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(), teams = emptyList(),
        battleLogs = battleLogs
    )
}

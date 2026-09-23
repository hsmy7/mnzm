package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * G07：玩家败北 → 重伤（HP=1 存活），不写死亡三元组、不计年报死亡。
 */
class DiscipleDeathHandlerTest {
    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var handler: DiscipleDeathHandler
    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        handler = DiscipleDeathHandler()
        tables = DiscipleTables()
    }

    /** 构造事务内 MutableGameState（markDead 需在 stateStore.update 事务内调用） */
    private fun createState(): MutableGameState = MutableGameState(
        gameData = GameData(),
        discipleTables = tables,
        equipmentStacks = com.xianxia.sect.core.state.EntityStore(emptyList()),
        equipmentInstances = com.xianxia.sect.core.state.EntityStore(emptyList()),
        manualStacks = com.xianxia.sect.core.state.EntityStore(emptyList()),
        manualInstances = com.xianxia.sect.core.state.EntityStore(emptyList()),
        pills = com.xianxia.sect.core.state.EntityStore(emptyList()),
        materials = com.xianxia.sect.core.state.EntityStore(emptyList()),
        herbs = com.xianxia.sect.core.state.EntityStore(emptyList()),
        seeds = com.xianxia.sect.core.state.EntityStore(emptyList()),
        storageBags = com.xianxia.sect.core.state.EntityStore(emptyList()),
        battleLogs = emptyList(),
        isPaused = false,
        isLoading = false,
        isSaving = false
    )

    private fun ensureId(id: Int) {
        if (tables.ids.contains(id)) {
            tables.isAlive[id] = 1
            tables.currentHps[id] = 100
            return
        }
        tables.insert(com.xianxia.sect.core.model.Disciple(id = id.toString(), name = "弟子$id"))
        tables.isAlive[id] = 1
        tables.currentHps[id] = 100
    }

    @Test
    fun `markDead writes injured HP and keeps alive`() {
        ensureId(1)
        val state = createState()
        handler.markDead(state, 1, 10)
        assertEquals(GameConfig.Disciple.INJURED_HP, tables.currentHps[1])
        assertEquals(1, tables.isAlive[1])
        assertNotEquals(
            com.xianxia.sect.core.model.DiscipleStatus.DEAD,
            tables.statuses[1],
        )
        // 年报死亡计数不得递增（重伤 ≠ 死亡）
        assertEquals(0, state.gameData.annualDeceasedDisciples)
    }

    @Test
    fun `markDead does not write deathYear`() {
        ensureId(1)
        handler.markDead(createState(), 1, 10)
        assertFalse(tables.deathYears.contains(1))
    }

    @Test
    fun `markDead multiple disciples independently`() {
        ensureId(1); ensureId(2); ensureId(3)
        handler.markDead(createState(), 1, 10)
        handler.markDead(createState(), 3, 10)

        assertEquals(1, tables.currentHps[1])
        assertEquals(100, tables.currentHps[2])
        assertEquals(1, tables.currentHps[3])
        assertEquals(1, tables.isAlive[1])
        assertEquals(1, tables.isAlive[3])
    }

    @Test
    fun `markDead string overload parses id`() {
        ensureId(7)
        handler.markDead(createState(), "7", 10)
        assertEquals(1, tables.currentHps[7])
        assertEquals(1, tables.isAlive[7])
    }

    @Test
    fun `markDead string overload skips unparseable id`() {
        ensureId(1)
        handler.markDead(createState(), "not_a_number", 10)
        assertEquals(100, tables.currentHps[1])
    }

    @Test
    fun `markAllDead injures all parseable ids`() {
        ensureId(1); ensureId(2); ensureId(3)
        handler.markAllDead(createState(), setOf("1", "2", "3"), 10)
        assertEquals(1, tables.currentHps[1])
        assertEquals(1, tables.currentHps[2])
        assertEquals(1, tables.currentHps[3])
        assertEquals(1, tables.isAlive[1])
    }

    @Test
    fun `isInjured derives from alive and HP equals 1`() {
        ensureId(1)
        assertTrue(DiscipleDeathHandler.isInjured(tables, 1).not())
        handler.markDead(createState(), 1, 10)
        assertTrue(DiscipleDeathHandler.isInjured(tables, 1))
    }
}

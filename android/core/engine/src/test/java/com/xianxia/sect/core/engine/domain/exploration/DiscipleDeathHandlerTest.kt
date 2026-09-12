package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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

    /** 确保 ID 在组件表中有槽位 */
    private fun ensureId(id: Int) {
        tables.isAlive[id] = 1
    }

    @Test
    fun `markDead sets isAlive to 0`() {
        ensureId(1)
        handler.markDead(createState(), 1, 10)
        assertEquals(0, tables.isAlive[1])
    }

    @Test
    fun `markDead sets deathYear`() {
        ensureId(1)
        handler.markDead(createState(), 1, 10)
        assertEquals(10, tables.deathYears[1])
    }

    @Test
    fun `markDead overwrites existing deathYear`() {
        ensureId(1)
        tables.deathYears[1] = 5
        handler.markDead(createState(), 1, 10)
        assertEquals(10, tables.deathYears[1])
    }

    @Test
    fun `markDead multiple disciples independently`() {
        ensureId(1); ensureId(2); ensureId(3)
        handler.markDead(createState(), 1, 10)
        ensureId(3) // re-ensure since markDead sets isAlive=0
        tables.isAlive[2] = 1 // re-ensure
        handler.markDead(createState(), 3, 10)

        assertEquals(0, tables.isAlive[1])
        assertEquals(1, tables.isAlive[2])
        assertEquals(0, tables.isAlive[3])
    }

    @Test
    fun `markDead with new ID allocates slot`() {
        // For ComponentTable, setting value for a new ID creates the slot automatically
        handler.markDead(createState(), 99, 10)
    }

    @Test
    fun `markDead does not affect other disciples`() {
        ensureId(1); ensureId(2)
        handler.markDead(createState(), 1, 10)
        assertEquals(1, tables.isAlive[2])
    }

    @Test
    fun `deathYears correctly stores different years`() {
        ensureId(1); ensureId(2)
        handler.markDead(createState(), 1, 5)
        handler.markDead(createState(), 2, 10)
        assertEquals(5, tables.deathYears[1])
        assertEquals(10, tables.deathYears[2])
    }

    // ══════════════════════════════════════════════════════════════════
    // 年报死亡计数（annualDeceasedDisciples 统一递增入口）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `markDead increments annualDeceasedDisciples once`() {
        ensureId(1)
        val state = createState()
        handler.markDead(state, 1, 10)
        assertEquals(1, state.gameData.annualDeceasedDisciples)
    }

    @Test
    fun `markDead counts every call`() {
        ensureId(1); ensureId(2)
        val state = createState()
        handler.markDead(state, 1, 10)
        handler.markDead(state, 2, 10)
        assertEquals(2, state.gameData.annualDeceasedDisciples)
    }

    @Test
    fun `markDead string overload counts after successful parse`() {
        ensureId(7)
        val state = createState()
        handler.markDead(state, "7", 10)
        assertEquals(1, state.gameData.annualDeceasedDisciples)
    }

    @Test
    fun `markDead string overload skips unparseable id without counting`() {
        val state = createState()
        handler.markDead(state, "not_a_number", 10)
        assertEquals(0, state.gameData.annualDeceasedDisciples)
    }

    @Test
    fun `markAllDead counts each dead disciple`() {
        ensureId(1); ensureId(2); ensureId(3)
        val state = createState()
        handler.markAllDead(state, setOf("1", "2", "3"), 10)
        assertEquals(3, state.gameData.annualDeceasedDisciples)
        assertEquals(0, tables.isAlive[1])
        assertEquals(0, tables.isAlive[2])
        assertEquals(0, tables.isAlive[3])
    }

    @Test
    fun `markAllDead counts only parseable ids`() {
        ensureId(1)
        val state = createState()
        handler.markAllDead(state, setOf("1", "bad"), 10)
        assertEquals(1, state.gameData.annualDeceasedDisciples)
    }

    // ══════════════════════════════════════════════════════════════════
    // backfillDeathYears — 列表 copy 模式补写（replaceAll 清空列后恢复）
    // ══════════════════════════════════════════════════════════════════

    private fun makeDeadDisciple(id: Int): com.xianxia.sect.core.model.Disciple {
        return com.xianxia.sect.core.model.Disciple(
            id = id.toString(),
            name = "弟子$id",
            isAlive = false,
            status = com.xianxia.sect.core.model.DiscipleStatus.DEAD
        )
    }

    @Test
    fun `backfillDeathYears writes year for dead disciples`() {
        ensureId(1)
        handler.backfillDeathYears(tables, listOf(makeDeadDisciple(1)), 10)
        assertEquals(10, tables.deathYears[1])
    }

    @Test
    fun `backfillDeathYears skips alive disciples`() {
        ensureId(1)
        val alive = com.xianxia.sect.core.model.Disciple(
            id = "1", name = "存活", isAlive = true, status = com.xianxia.sect.core.model.DiscipleStatus.IDLE
        )
        handler.backfillDeathYears(tables, listOf(alive), 10)
        assertFalse(tables.deathYears.contains(1))
    }

    @Test
    fun `backfillDeathYears does not overwrite existing deathYear`() {
        ensureId(1)
        tables.deathYears[1] = 5
        handler.backfillDeathYears(tables, listOf(makeDeadDisciple(1)), 10)
        assertEquals(5, tables.deathYears[1])
    }

    @Test
    fun `backfillDeathYears skips unparseable ids`() {
        val bad = com.xianxia.sect.core.model.Disciple(
            id = "not_a_number", name = "坏ID", isAlive = false,
            status = com.xianxia.sect.core.model.DiscipleStatus.DEAD
        )
        handler.backfillDeathYears(tables, listOf(bad), 10)
        assertFalse(tables.deathYears.contains(999))
    }
}

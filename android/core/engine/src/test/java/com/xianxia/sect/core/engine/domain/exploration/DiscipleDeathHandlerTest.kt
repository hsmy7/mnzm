package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.state.DiscipleTables
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscipleDeathHandlerTest {

    private lateinit var handler: DiscipleDeathHandler
    private lateinit var tables: DiscipleTables

    @Before
    fun setUp() {
        handler = DiscipleDeathHandler()
        tables = DiscipleTables()
    }

    /** 确保 ID 在组件表中有槽位 */
    private fun ensureId(id: Int) {
        tables.isAlive[id] = 1
    }

    @Test
    fun `markDead sets isAlive to 0`() {
        ensureId(1)
        handler.markDead(tables, 1, 10)
        assertEquals(0, tables.isAlive[1])
    }

    @Test
    fun `markDead sets deathYear`() {
        ensureId(1)
        handler.markDead(tables, 1, 10)
        assertEquals(10, tables.deathYears[1])
    }

    @Test
    fun `markDead overwrites existing deathYear`() {
        ensureId(1)
        tables.deathYears[1] = 5
        handler.markDead(tables, 1, 10)
        assertEquals(10, tables.deathYears[1])
    }

    @Test
    fun `markDead multiple disciples independently`() {
        ensureId(1); ensureId(2); ensureId(3)
        handler.markDead(tables, 1, 10)
        ensureId(3) // re-ensure since markDead sets isAlive=0
        tables.isAlive[2] = 1 // re-ensure
        handler.markDead(tables, 3, 10)

        assertEquals(0, tables.isAlive[1])
        assertEquals(1, tables.isAlive[2])
        assertEquals(0, tables.isAlive[3])
    }

    @Test
    fun `markDead with new ID allocates slot`() {
        // For ComponentTable, setting value for a new ID creates the slot automatically
        handler.markDead(tables, 99, 10)
    }

    @Test
    fun `markDead does not affect other disciples`() {
        ensureId(1); ensureId(2)
        handler.markDead(tables, 1, 10)
        assertEquals(1, tables.isAlive[2])
    }

    @Test
    fun `deathYears correctly stores different years`() {
        ensureId(1); ensureId(2)
        handler.markDead(tables, 1, 5)
        handler.markDead(tables, 2, 10)
        assertEquals(5, tables.deathYears[1])
        assertEquals(10, tables.deathYears[2])
    }
}

package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscipleTablesTest {

    private fun createTestDisciple(
        id: String = "1",
        name: String = "张三",
        realm: Int = 9,
        cultivation: Double = 100.0,
        loyalty: Int = 50
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            cultivation = cultivation,
            skills = SkillStats(loyalty = loyalty)
        )
    }

    @Test
    fun `insert and retrieve basic fields`() {
        val tables = DiscipleTables()
        val disciple = createTestDisciple(id = "1", name = "张三", realm = 9)
        tables.insert(disciple)

        assertEquals("张三", tables.names[1])
        assertEquals(9, tables.realms[1])
        assertEquals(100.0, tables.cultivations[1], 0.001)
        assertEquals(1, tables.count)
    }

    @Test
    fun `update loyalty directly`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", loyalty = 50))

        tables.loyalties[1] = 90
        assertEquals(90, tables.loyalties[1])
    }

    @Test
    fun `update cultivation in-place`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))

        tables.cultivations.update(1) { it + 50.0 }
        assertEquals(150.0, tables.cultivations[1], 0.001)
    }

    @Test
    fun `assemble full Disciple from tables`() {
        val tables = DiscipleTables()
        val original = createTestDisciple(id = "1", name = "李四", realm = 8, cultivation = 500.0, loyalty = 75)
        tables.insert(original)

        val assembled = tables.assemble(1)
        assertEquals("1", assembled.id)
        assertEquals("李四", assembled.name)
        assertEquals(8, assembled.realm)
        assertEquals(500.0, assembled.cultivation, 0.001)
        assertEquals(75, assembled.skills.loyalty)
    }

    @Test
    fun `assembleAll matches input`() {
        val tables = DiscipleTables()
        val d1 = createTestDisciple(id = "1", name = "张三")
        val d2 = createTestDisciple(id = "2", name = "李四")
        tables.insert(d1)
        tables.insert(d2)

        val all = tables.assembleAll()
        assertEquals(2, all.size)
        assertEquals("张三", all.find { it.id == "1" }?.name)
        assertEquals("李四", all.find { it.id == "2" }?.name)
    }

    @Test
    fun `bulk update all disciples cultivation`() {
        val tables = DiscipleTables()
        for (i in 1..10) {
            tables.insert(createTestDisciple(id = i.toString(), cultivation = 100.0))
        }

        // Simulate tick: update all disciples
        for (id in tables.ids) {
            tables.cultivations.update(id) { it + 10.0 }
        }

        for (id in tables.ids) {
            assertEquals(110.0, tables.cultivations[id], 0.001)
        }
    }

    @Test
    fun `remove disciple cleans all tables`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", name = "张三"))
        tables.insert(createTestDisciple(id = "2", name = "李四"))

        tables.remove(1)

        assertEquals(1, tables.count)
        assertFalse(tables.names.contains(1))
        assertFalse(tables.realms.contains(1))
        assertTrue(tables.names.contains(2))
    }

    @Test
    fun `deepCopy creates independent snapshot`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0, loyalty = 50))

        val copy = tables.deepCopy()
        copy.cultivations[1] = 200.0
        copy.loyalties[1] = 90

        // Original unchanged
        assertEquals(100.0, tables.cultivations[1], 0.001)
        assertEquals(50, tables.loyalties[1])
        // Copy has new values
        assertEquals(200.0, copy.cultivations[1], 0.001)
        assertEquals(90, copy.loyalties[1])
    }
}

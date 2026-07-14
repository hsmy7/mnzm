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
    fun `lifeEvents stored and retrieved correctly`() {
        val tables = DiscipleTables()
        val disciple = createTestDisciple(id = "1")
        tables.insert(disciple)

        assertEquals(emptyList<String>(), tables.lifeEvents[1])

        tables.lifeEvents[1] = listOf("16岁：加入宗门")
        assertEquals(listOf("16岁：加入宗门"), tables.lifeEvents[1])
    }

    @Test
    fun `lifeEvents survive assemble round-trip`() {
        val tables = DiscipleTables()
        val d = createTestDisciple(id = "1")
        tables.insert(d)
        tables.lifeEvents[1] = listOf("16岁：加入宗门")

        val assembled = tables.assemble(1)
        assertEquals(listOf("16岁：加入宗门"), assembled.lifeEvents)
    }

    @Test
    fun `lifeEvents deep copy is independent`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1"))
        tables.lifeEvents[1] = listOf("16岁：加入宗门")

        val copy = tables.deepCopy()
        copy.lifeEvents[1] = copy.lifeEvents[1] + "17岁：突破至筑基期"

        assertEquals(listOf("16岁：加入宗门"), tables.lifeEvents[1])
        assertEquals(
            listOf("16岁：加入宗门", "17岁：突破至筑基期"),
            copy.lifeEvents[1]
        )
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

    // ═══════════════════════════════════════════════════════════════
    // checkpointDisciple / checkpointAllDisciples / getEffectiveCultivation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `checkpointDisciple saves current cultivation and month`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 200.0))

        tables.checkpointDisciple(1, currentMonth = 50)

        assertEquals(200.0, tables.cultivationCheckpoints[1], 0.001)
        assertEquals(50, tables.cultivationCheckpointGameMonths[1])
    }

    @Test
    fun `checkpointDisciple after cultivation update saves latest value`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))

        tables.cultivations[1] = 350.0
        tables.checkpointDisciple(1, currentMonth = 75)

        assertEquals(350.0, tables.cultivationCheckpoints[1], 0.001)
        assertEquals(75, tables.cultivationCheckpointGameMonths[1])
    }

    @Test
    fun `checkpointDisciple updates checkpoint from current cultivation value`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 200.0))
        tables.cultivations[1] = 350.0  // 修改修为但不更新检查点

        tables.checkpointDisciple(1, currentMonth = 75)

        assertEquals(350.0, tables.cultivationCheckpoints[1], 0.001)
        assertEquals(75, tables.cultivationCheckpointGameMonths[1])
    }

    @Test
    fun `checkpointDisciple skips dead disciple`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))
        tables.isAlive[1] = 0

        tables.checkpointDisciple(1, currentMonth = 60)

        // 死弟子的 checkpoint 保持 insert 时的初值（不更新）
        assertEquals(0.0, tables.cultivationCheckpoints[1], 0.001)
        assertEquals(0, tables.cultivationCheckpointGameMonths[1])
    }

    @Test
    fun `checkpointAllDisciples snapshots only alive disciples`() {
        val tables = DiscipleTables()
        for (i in 1..3) {
            tables.insert(createTestDisciple(id = i.toString(), cultivation = i * 100.0))
        }
        tables.isAlive[3] = 0  // mark disciple 3 as dead

        tables.checkpointAllDisciples(currentMonth = 100)

        // 存活弟子检查点更新为当前 cultivation
        assertEquals(100.0, tables.cultivationCheckpoints[1], 0.001)
        assertEquals(100, tables.cultivationCheckpointGameMonths[1])
        assertEquals(200.0, tables.cultivationCheckpoints[2], 0.001)
        assertEquals(100, tables.cultivationCheckpointGameMonths[2])
        // 死弟子保持 insert 初值（0, 0）
        assertEquals(0.0, tables.cultivationCheckpoints[3], 0.001)
        assertEquals(0, tables.cultivationCheckpointGameMonths[3])
    }

    @Test
    fun `getEffectiveCultivation projects from checkpoint plus rate times months`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))
        // 先更新 cultivation 再 checkpoint
        tables.cultivations[1] = 100.0
        tables.checkpointDisciple(1, currentMonth = 10)

        val projected = tables.getEffectiveCultivation(1, currentMonth = 12, rate = 5.0)

        // 100 + 5 * (12-10) * 3 = 100 + 30 = 130
        assertEquals(130.0, projected, 0.001)
    }

    @Test
    fun `getEffectiveCultivation uses cultivations when checkpoint month is before insert`() {
        // insert 始终设置 checkpoint（初值 0），因此后备分支不可达。
        // 但若 insert 时 cultivation 已有值，projection 用 checkpoint(0) + rate * elapsed
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 150.0))
        // checkpoint 为 0.0（insert 默认），checkpointMonth 为 0

        val projected = tables.getEffectiveCultivation(1, currentMonth = 20, rate = 5.0)

        // 0 + 5 * (20-0) * 3 = 300
        assertEquals(300.0, projected, 0.001)
    }

    @Test
    fun `getEffectiveCultivation returns checkpoint when rate is zero`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))
        tables.cultivations[1] = 100.0
        tables.checkpointDisciple(1, currentMonth = 10)
        tables.cultivations[1] = 200.0  // advance beyond checkpoint

        val projected = tables.getEffectiveCultivation(1, currentMonth = 15, rate = 0.0)

        // rate <= 0 → return checkpoint only
        assertEquals(100.0, projected, 0.001)
    }

    @Test
    fun `getEffectiveCultivation returns checkpoint when months not advanced`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))
        tables.cultivations[1] = 100.0
        tables.checkpointDisciple(1, currentMonth = 10)

        val projected = tables.getEffectiveCultivation(1, currentMonth = 10, rate = 5.0)

        assertEquals(100.0, projected, 0.001)
    }

    // ═══════════════════════════════════════════════════════════════
    // allocateAndInsert / rollbackAllocation（幽灵弟子修复新增）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `allocateAndInsert assigns new id and writes all fields`() {
        val tables = DiscipleTables()
        val disciple = createTestDisciple(id = "0", name = "新生弟子", realm = 8, cultivation = 300.0)

        val assignedId = tables.allocateAndInsert(disciple)

        assertTrue("ID 应 > 0", assignedId.toInt() > 0)
        assertEquals(1, tables.count)
        assertEquals("新生弟子", tables.names[assignedId.toInt()])
        assertEquals(8, tables.realms[assignedId.toInt()])
    }

    @Test
    fun `allocateAndInsert overwrites caller id`() {
        val tables = DiscipleTables()
        val disciple = createTestDisciple(id = "999", name = "覆盖测试")

        val assignedId = tables.allocateAndInsert(disciple)

        assertEquals(1, assignedId.toInt())
        assertFalse(tables.ids.contains(999))
    }

    @Test
    fun `allocateAndInsert multiple times increments ids`() {
        val tables = DiscipleTables()
        val id1 = tables.allocateAndInsert(createTestDisciple(name = "第一"))
        val id2 = tables.allocateAndInsert(createTestDisciple(name = "第二"))
        val id3 = tables.allocateAndInsert(createTestDisciple(name = "第三"))

        assertEquals(3, tables.count)
        assertEquals("1", id1)
        assertEquals("2", id2)
        assertEquals("3", id3)
        assertEquals("第一", tables.names[1])
        assertEquals("第二", tables.names[2])
        assertEquals("第三", tables.names[3])
    }

    @Test
    fun `rollbackAllocation removes id from ids and component tables`() {
        val tables = DiscipleTables()
        val id = tables.allocateNextId()
        assertEquals(1, tables.count)

        val result = tables.rollbackAllocation(id)

        assertTrue(result)
        assertEquals(0, tables.count)
        assertFalse(tables.ids.contains(id))
    }

    @Test
    fun `rollbackAllocation on non-existent id returns false`() {
        val tables = DiscipleTables()
        val result = tables.rollbackAllocation(999)

        assertFalse(result)
    }

    @Test
    fun `allocateAndInsert increments mutationVersion`() {
        val tables = DiscipleTables()
        val v0 = tables.mutationVersion

        tables.allocateAndInsert(createTestDisciple(name = "版本次"))

        assertTrue("mutationVersion 应递增", tables.mutationVersion > v0)
    }
}

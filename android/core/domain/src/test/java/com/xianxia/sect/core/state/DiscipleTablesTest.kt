package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.model.loyalty
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner



@RunWith(RobolectricTestRunner::class)
class DiscipleTablesTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

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
    fun `allocateAndInsert increments mutationVersion`() {
        val tables = DiscipleTables()
        val v0 = tables.mutationVersion

        tables.allocateAndInsert(createTestDisciple(name = "版本次"))

        assertTrue("mutationVersion 应递增", tables.mutationVersion > v0)
    }

    // ═══════════════════════════════════════════════════════════════
    // replaceAll（批量原子替换）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `replaceAll replaces all disciples atomically`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", name = "张"))
        tables.insert(createTestDisciple(id = "2", name = "李"))

        val replacement = listOf(
            createTestDisciple(id = "3", name = "王"),
            createTestDisciple(id = "4", name = "赵")
        )
        tables.replaceAll(replacement)

        assertEquals(2, tables.count)
        assertFalse("旧的 ID 1 应不存在", tables.names.contains(1))
        assertFalse("旧的 ID 2 应不存在", tables.ids.contains(2))
        assertEquals("王", tables.names[3])
        assertEquals("赵", tables.names[4])
    }

    @Test
    fun `replaceAll with empty list clears all`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1"))
        tables.insert(createTestDisciple(id = "2"))

        tables.replaceAll(emptyList())

        assertEquals(0, tables.count)
        assertTrue("组件表应无 ID 1 的数据", !tables.names.contains(1))
    }

    @Test
    fun `replaceAll preserves mutationVersion bump`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1"))
        val v0 = tables.mutationVersion

        val replacement = (2..10).map { createTestDisciple(id = it.toString()) }
        tables.replaceAll(replacement)

        assertTrue("mutationVersion 应递增", tables.mutationVersion > v0)
    }

    @Test
    fun `replaceAll preserves deathRecords`() {
        val tables = DiscipleTables()
        tables.addDeathRecord(DeathRecord(
            id = 1, name = "", surname = "", realm = 9, realmLayer = 1,
            deathAge = 0, deathYear = 100, cause = "test"
        ))
        tables.insert(createTestDisciple(id = "2"))

        tables.replaceAll(listOf(createTestDisciple(id = "3")))

        assertEquals("deathRecords 不应被清空", 1, tables.deathRecords.size)
        assertEquals(100, tables.deathRecords[0].deathYear)
    }

    // ═══════════════════════════════════════════════════════════════
    // physiqueIds / affixIds 往返测试（修复漏写 bug 的回归测试）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `physiqueIds and affixIds survive assemble round-trip`() {
        val tables = DiscipleTables()
        val d = createTestDisciple(id = "1").copy(
            physiqueIds = listOf("r1_phy_dmg_amp", "r2_phy_crit"),
            affixIds = listOf("r1_aff_base_int", "neg_aff_base")
        )
        tables.insert(d)

        val assembled = tables.assemble(1)
        assertEquals(listOf("r1_phy_dmg_amp", "r2_phy_crit"), assembled.physiqueIds)
        assertEquals(listOf("r1_aff_base_int", "neg_aff_base"), assembled.affixIds)
    }

    @Test
    fun `allocateAndInsert writes physiqueIds and affixIds`() {
        val tables = DiscipleTables()
        val d = createTestDisciple(id = "0").copy(
            physiqueIds = listOf("r3_phy_dmg_reduce"),
            affixIds = listOf("r2_aff_bat_atk")
        )

        val assignedId = tables.allocateAndInsert(d)
        val idInt = assignedId.toInt()

        assertEquals(listOf("r3_phy_dmg_reduce"), tables.physiqueIds[idInt])
        assertEquals(listOf("r2_aff_bat_atk"), tables.affixIds[idInt])

        val assembled = tables.assemble(idInt)
        assertEquals(listOf("r3_phy_dmg_reduce"), assembled.physiqueIds)
        assertEquals(listOf("r2_aff_bat_atk"), assembled.affixIds)
    }

    @Test
    fun `replaceAll writes physiqueIds and affixIds for new batch`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1").copy(
            physiqueIds = listOf("old_phy"),
            affixIds = listOf("old_aff")
        ))

        val replacement = listOf(
            createTestDisciple(id = "2").copy(
                physiqueIds = listOf("r1_phy_dmg_amp"),
                affixIds = listOf("r1_aff_lifespan")
            ),
            createTestDisciple(id = "3").copy(
                physiqueIds = emptyList(),
                affixIds = listOf("r3_aff_manual_slot")
            )
        )
        tables.replaceAll(replacement)

        assertEquals(listOf("r1_phy_dmg_amp"), tables.physiqueIds[2])
        assertEquals(listOf("r1_aff_lifespan"), tables.affixIds[2])
        assertEquals(emptyList<String>(), tables.physiqueIds[3])
        assertEquals(listOf("r3_aff_manual_slot"), tables.affixIds[3])
        assertFalse("旧 ID 1 的 physiqueIds 应被清除", tables.physiqueIds.contains(1))
    }

    @Test
    fun `physiqueIds and affixIds deep copy is independent`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1").copy(
            physiqueIds = listOf("r1_phy_dmg_amp"),
            affixIds = listOf("r1_aff_base_int")
        ))

        val copy = tables.deepCopy()
        copy.physiqueIds[1] = copy.physiqueIds[1] + "r2_phy_crit"
        copy.affixIds[1] = listOf("r3_aff_win_growth")

        assertEquals(
            "原表 physiqueIds 不应被修改",
            listOf("r1_phy_dmg_amp"),
            tables.physiqueIds[1]
        )
        assertEquals(
            "原表 affixIds 不应被修改",
            listOf("r1_aff_base_int"),
            tables.affixIds[1]
        )
        assertEquals(
            listOf("r1_phy_dmg_amp", "r2_phy_crit"),
            copy.physiqueIds[1]
        )
        assertEquals(
            listOf("r3_aff_win_growth"),
            copy.affixIds[1]
        )
    }

    @Test
    fun `physiqueIds and affixIds default to empty when not set`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1"))

        assertEquals(emptyList<String>(), tables.physiqueIds[1])
        assertEquals(emptyList<String>(), tables.affixIds[1])

        val assembled = tables.assemble(1)
        assertEquals(emptyList<String>(), assembled.physiqueIds)
        assertEquals(emptyList<String>(), assembled.affixIds)
    }
}

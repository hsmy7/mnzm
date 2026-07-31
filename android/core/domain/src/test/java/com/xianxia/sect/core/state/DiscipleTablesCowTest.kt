package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 列级 Copy-on-Write 快照隔离专项测试。
 *
 * 核心不变量：
 * 1. deepCopy 后副本与源表共享 store，直到副本首次写入该列（O(1) 共享）
 * 2. 新快照的写入不影响任何旧快照（多代隔离）
 * 3. COW 副本 dirtyTracker 从空起步——copyTo 污染已消除，只有真实写入才标记脏
 * 4. 13 张 Mutable 列保持急切深拷贝（值对象原地修改不泄漏到源快照）
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesCowTest {

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
    fun `deepCopy shares store until first write`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))

        val copy = tables.deepCopy()
        // 共享存储：O(1) 零数据复制
        assertTrue("COW 后副本应与源表共享 store", copy.cultivations.store === tables.cultivations.store)
        assertTrue(copy.names.store === tables.names.store)
        assertTrue(copy.realms.store === tables.realms.store)

        // 首次写入私有化，且源表不受影响
        copy.cultivations[1] = 200.0
        assertFalse("写入后副本应私有化 store", copy.cultivations.store === tables.cultivations.store)
        assertEquals(100.0, tables.cultivations[1], 0.001)
        assertEquals(200.0, copy.cultivations[1], 0.001)
    }

    @Test
    fun `multi-generation snapshots are isolated`() {
        val t0 = DiscipleTables()
        t0.insert(createTestDisciple(id = "1", cultivation = 100.0, loyalty = 50))

        val s0 = t0.deepCopy()
        s0.cultivations[1] = 100.0          // s0 私有化 cultivations 列
        s0.loyalties[1] = 60

        val s1 = s0.deepCopy()
        s1.cultivations[1] = 200.0          // s1 私有化自己的 cultivations 列
        s1.loyalties[1] = 70

        // 各代快照读数互不干扰
        assertEquals(100.0, t0.cultivations[1], 0.001)
        assertEquals(50, t0.loyalties[1])
        assertEquals(100.0, s0.cultivations[1], 0.001)
        assertEquals(60, s0.loyalties[1])
        assertEquals(200.0, s1.cultivations[1], 0.001)
        assertEquals(70, s1.loyalties[1])
    }

    @Test
    fun `deepCopy with dirtyColumns set still yields complete assembleAll`() {
        // 历史 bug 回归：旧增量路径只复制脏列导致非脏列为空、assembleAll 返回 0 弟子
        val tables = DiscipleTables()
        for (i in 1..5) {
            tables.insert(createTestDisciple(id = i.toString(), name = "弟子$i", realm = 9))
        }

        val copy = tables.deepCopy(setOf(0))
        val all = copy.assembleAll()
        assertEquals(5, all.size)
        assertEquals("弟子1", all.find { it.id == "1" }?.name)
        assertEquals(9, all.find { it.id == "5" }?.realm)
    }

    @Test
    fun `copy dirtyTracker starts clean and only real writes mark dirty`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0))

        val copy = tables.deepCopy()
        // F1 修复验证：COW 路径不再逐元素 copyTo（不再把副本 mutationVersion/dirtyTracker 顶满）
        assertFalse("COW 副本 dirtyTracker 应从空起步", copy.dirtyTracker.isDirty)

        copy.cultivations[1] = 200.0
        assertTrue("真实写入才标记脏", copy.dirtyTracker.isDirty)
    }

    @Test
    fun `clear on copy does not affect source snapshot`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", name = "张三"))
        tables.insert(createTestDisciple(id = "2", name = "李四"))

        val copy = tables.deepCopy()
        copy.clear()

        assertEquals(2, tables.count)
        assertEquals("张三", tables.names[1])
        assertEquals(0, copy.count)
        assertTrue(copy.names.isEmpty())
    }

    @Test
    fun `remove on copy does not affect source snapshot`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", name = "张三"))
        tables.insert(createTestDisciple(id = "2", name = "李四"))

        val copy = tables.deepCopy()
        copy.remove(1)

        assertEquals(2, tables.count)
        assertTrue(tables.names.contains(1))
        assertEquals(1, copy.count)
        assertFalse(copy.names.contains(1))
    }

    @Test
    fun `markDead on copy does not affect source snapshot`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", name = "张三"))

        val copy = tables.deepCopy()
        copy.markDead(1, currentYear = 10, cause = "age")

        assertEquals(1, tables.isAlive[1])
        assertEquals(0, copy.isAlive[1])
        assertTrue(copy.deathRecords.isNotEmpty())
        assertTrue(tables.deathRecords.isEmpty())
    }

    @Test
    fun `mutable columns deep copy eagerly on share`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1"))
        tables.lifeEvents[1] = listOf("16岁：加入宗门")

        val copy = tables.deepCopy()
        // Mutable 列急切深拷贝：不共享 store
        assertFalse("Mutable 列应急切深拷贝而非共享", copy.lifeEvents.store === tables.lifeEvents.store)

        // 副本修改值对象不影响源快照
        copy.lifeEvents[1] = copy.lifeEvents[1] + "17岁：突破至筑基期"
        assertEquals(listOf("16岁：加入宗门"), tables.lifeEvents[1])
        assertEquals(listOf("16岁：加入宗门", "17岁：突破至筑基期"), copy.lifeEvents[1])
    }

    @Test
    fun `markDead records changed id for incremental assembly`() {
        // 对抗性审查回归：markDead 必须 recordChangedId——
        // 若同事务还包含其他 update（产生 changedIds），增量组装必须重排
        // 阵亡弟子，否则快照保留其"存活"旧数据（陈尸）。
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1"))
        tables.changedIdTracker.consumeChangedIds()  // 清空 insert 的记录

        tables.markDead(1, currentYear = 10, cause = "age")

        val changed = tables.changedIdTracker.consumeChangedIds()
        assertTrue("markDead 应记录 changedId（防陈尸）", 1 in changed)
    }

    @Test
    fun `forceFullCopy fallback path is semantically equivalent`() {
        val tables = DiscipleTables()
        tables.insert(createTestDisciple(id = "1", cultivation = 100.0, loyalty = 50))

        val fullCopy = try {
            DiscipleTables.forceFullCopy = true
            tables.deepCopy()
        } finally {
            DiscipleTables.forceFullCopy = false
        }
        val cowCopy = tables.deepCopy()

        // 两条路径读数据一致
        assertEquals(tables.assemble(1).cultivation, fullCopy.assemble(1).cultivation, 0.001)
        assertEquals(tables.assemble(1).cultivation, cowCopy.assemble(1).cultivation, 0.001)

        // 兜底路径写入同样不污染源表
        fullCopy.cultivations[1] = 300.0
        assertEquals(100.0, tables.cultivations[1], 0.001)
    }
}

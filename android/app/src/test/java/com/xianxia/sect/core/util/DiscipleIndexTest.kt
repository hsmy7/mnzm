package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscipleIndexTest {

    private lateinit var index: DiscipleIndex

    @Before
    fun setUp() {
        index = DiscipleIndex()
    }

    private fun createDisciple(
        id: String = "d1",
        realm: Int = 9,
        isAlive: Boolean = true,
        status: DiscipleStatus = DiscipleStatus.IDLE,
        discipleType: String = "outer"
    ): Disciple = Disciple(
        id = id,
        realm = realm,
        isAlive = isAlive,
        status = status,
        discipleType = discipleType
    )

    // ---- index ----

    @Test
    fun index_singleDisciple_canGetById() {
        val disciple = createDisciple(id = "d1")
        index.index(disciple)
        assertEquals(disciple, index.getById("d1"))
    }

    @Test
    fun index_multipleDisciples_canGetAll() {
        val d1 = createDisciple(id = "d1")
        val d2 = createDisciple(id = "d2")
        index.index(d1)
        index.index(d2)
        assertEquals(2, index.size())
    }

    // ---- indexAll ----

    @Test
    fun indexAll_replacesExistingIndex() {
        index.index(createDisciple(id = "old"))
        val disciples = listOf(
            createDisciple(id = "d1"),
            createDisciple(id = "d2")
        )
        index.indexAll(disciples)
        assertNull(index.getById("old"))
        assertEquals(2, index.size())
    }

    @Test
    fun indexAll_emptyList_clearsIndex() {
        index.index(createDisciple(id = "d1"))
        index.indexAll(emptyList())
        assertEquals(0, index.size())
    }

    // ---- update ----

    @Test
    fun update_existingDisciple_updatesFields() {
        val d1 = createDisciple(id = "d1", realm = 9, status = DiscipleStatus.IDLE)
        index.index(d1)
        val updated = createDisciple(id = "d1", realm = 5, status = DiscipleStatus.MINING)
        index.update(updated)
        val result = index.getById("d1")
        assertEquals(5, result!!.realm)
        assertEquals(DiscipleStatus.MINING, result.status)
    }

    @Test
    fun update_newDisciple_indexesIt() {
        val d1 = createDisciple(id = "d1")
        index.update(d1)
        assertEquals(d1, index.getById("d1"))
    }

    @Test
    fun update_realmChange_updatesRealmIndex() {
        val d1 = createDisciple(id = "d1", realm = 9)
        index.index(d1)
        val updated = createDisciple(id = "d1", realm = 5)
        index.update(updated)
        assertEquals(emptyList<Disciple>(), index.getByRealm(9))
        assertEquals(listOf(updated), index.getByRealm(5))
    }

    @Test
    fun update_statusChange_updatesStatusIndex() {
        val d1 = createDisciple(id = "d1", status = DiscipleStatus.IDLE)
        index.index(d1)
        val updated = createDisciple(id = "d1", status = DiscipleStatus.MINING)
        index.update(updated)
        assertEquals(emptyList<Disciple>(), index.getByStatus(DiscipleStatus.IDLE))
        assertEquals(listOf(updated), index.getByStatus(DiscipleStatus.MINING))
    }

    @Test
    fun update_aliveChange_updatesAliveIndex() {
        val d1 = createDisciple(id = "d1", isAlive = true)
        index.index(d1)
        val updated = createDisciple(id = "d1", isAlive = false)
        index.update(updated)
        assertEquals(emptyList<Disciple>(), index.getByAlive(true))
        assertEquals(listOf(updated), index.getByAlive(false))
    }

    @Test
    fun update_discipleTypeChange_updatesTypeIndex() {
        val d1 = createDisciple(id = "d1", discipleType = "outer")
        index.index(d1)
        val updated = createDisciple(id = "d1", discipleType = "inner")
        index.update(updated)
        assertEquals(emptyList<Disciple>(), index.getByDiscipleType("outer"))
        assertEquals(listOf(updated), index.getByDiscipleType("inner"))
    }

    // ---- getById ----

    @Test
    fun getById_notFound_returnsNull() {
        assertNull(index.getById("nonexistent"))
    }

    // ---- getByIds ----

    @Test
    fun getByIds_returnsMatchingDisciples() {
        val d1 = createDisciple(id = "d1")
        val d2 = createDisciple(id = "d2")
        val d3 = createDisciple(id = "d3")
        index.indexAll(listOf(d1, d2, d3))
        val result = index.getByIds(listOf("d1", "d3"))
        assertEquals(2, result.size)
    }

    @Test
    fun getByIds_missingIds_skipped() {
        index.index(createDisciple(id = "d1"))
        val result = index.getByIds(listOf("d1", "missing"))
        assertEquals(1, result.size)
    }

    // ---- getByStatus ----

    @Test
    fun getByStatus_returnsMatchingDisciples() {
        val d1 = createDisciple(id = "d1", status = DiscipleStatus.IDLE)
        val d2 = createDisciple(id = "d2", status = DiscipleStatus.MINING)
        index.indexAll(listOf(d1, d2))
        assertEquals(listOf(d1), index.getByStatus(DiscipleStatus.IDLE))
        assertEquals(listOf(d2), index.getByStatus(DiscipleStatus.MINING))
    }

    @Test
    fun getByStatus_noMatch_returnsEmpty() {
        index.index(createDisciple(id = "d1", status = DiscipleStatus.IDLE))
        assertEquals(emptyList<Disciple>(), index.getByStatus(DiscipleStatus.MINING))
    }

    // ---- getByStatuses ----

    @Test
    fun getByStatuses_returnsDisciplesInMultipleStatuses() {
        val d1 = createDisciple(id = "d1", status = DiscipleStatus.IDLE)
        val d2 = createDisciple(id = "d2", status = DiscipleStatus.MINING)
        val d3 = createDisciple(id = "d3", status = DiscipleStatus.STUDYING)
        index.indexAll(listOf(d1, d2, d3))
        val result = index.getByStatuses(DiscipleStatus.IDLE, DiscipleStatus.MINING)
        assertEquals(2, result.size)
    }

    // ---- getByRealm ----

    @Test
    fun getByRealm_returnsMatchingDisciples() {
        val d1 = createDisciple(id = "d1", realm = 9)
        val d2 = createDisciple(id = "d2", realm = 5)
        index.indexAll(listOf(d1, d2))
        assertEquals(listOf(d1), index.getByRealm(9))
        assertEquals(listOf(d2), index.getByRealm(5))
    }

    // ---- getByRealmRange ----

    @Test
    fun getByRealmRange_returnsDisciplesInRange() {
        val d1 = createDisciple(id = "d1", realm = 9)
        val d2 = createDisciple(id = "d2", realm = 7)
        val d3 = createDisciple(id = "d3", realm = 5)
        index.indexAll(listOf(d1, d2, d3))
        val result = index.getByRealmRange(7, 9)
        assertEquals(2, result.size)
    }

    // ---- getByAlive ----

    @Test
    fun getByAlive_returnsAliveDisciples() {
        val d1 = createDisciple(id = "d1", isAlive = true)
        val d2 = createDisciple(id = "d2", isAlive = false)
        index.indexAll(listOf(d1, d2))
        assertEquals(listOf(d1), index.getByAlive(true))
        assertEquals(listOf(d2), index.getByAlive(false))
    }

    // ---- getByDiscipleType ----

    @Test
    fun getByDiscipleType_returnsMatchingType() {
        val d1 = createDisciple(id = "d1", discipleType = "outer")
        val d2 = createDisciple(id = "d2", discipleType = "inner")
        index.indexAll(listOf(d1, d2))
        assertEquals(listOf(d1), index.getByDiscipleType("outer"))
        assertEquals(listOf(d2), index.getByDiscipleType("inner"))
    }

    // ---- getAliveByStatus ----

    @Test
    fun getAliveByStatus_returnsOnlyAlive() {
        val d1 = createDisciple(id = "d1", status = DiscipleStatus.IDLE, isAlive = true)
        val d2 = createDisciple(id = "d2", status = DiscipleStatus.IDLE, isAlive = false)
        index.indexAll(listOf(d1, d2))
        val result = index.getAliveByStatus(DiscipleStatus.IDLE)
        assertEquals(1, result.size)
        assertEquals("d1", result[0].id)
    }

    // ---- getAliveByDiscipleType ----

    @Test
    fun getAliveByDiscipleType_returnsOnlyAlive() {
        val d1 = createDisciple(id = "d1", discipleType = "outer", isAlive = true)
        val d2 = createDisciple(id = "d2", discipleType = "outer", isAlive = false)
        index.indexAll(listOf(d1, d2))
        val result = index.getAliveByDiscipleType("outer")
        assertEquals(1, result.size)
        assertEquals("d1", result[0].id)
    }

    // ---- getAliveByRealmRange ----

    @Test
    fun getAliveByRealmRange_returnsOnlyAlive() {
        val d1 = createDisciple(id = "d1", realm = 9, isAlive = true)
        val d2 = createDisciple(id = "d2", realm = 9, isAlive = false)
        index.indexAll(listOf(d1, d2))
        val result = index.getAliveByRealmRange(9, 9)
        assertEquals(1, result.size)
        assertEquals("d1", result[0].id)
    }

    // ---- remove ----

    @Test
    fun remove_existingDisciple_removesFromAllIndexes() {
        val d1 = createDisciple(id = "d1", realm = 9, status = DiscipleStatus.IDLE, discipleType = "outer", isAlive = true)
        index.index(d1)
        index.remove("d1")
        assertNull(index.getById("d1"))
        assertEquals(0, index.size())
        assertEquals(emptyList<Disciple>(), index.getByRealm(9))
        assertEquals(emptyList<Disciple>(), index.getByStatus(DiscipleStatus.IDLE))
        assertEquals(emptyList<Disciple>(), index.getByDiscipleType("outer"))
        assertEquals(emptyList<Disciple>(), index.getByAlive(true))
    }

    @Test
    fun remove_nonExistent_noEffect() {
        index.index(createDisciple(id = "d1"))
        index.remove("nonexistent")
        assertEquals(1, index.size())
    }

    // ---- contains ----

    @Test
    fun contains_existingId_returnsTrue() {
        index.index(createDisciple(id = "d1"))
        assertTrue(index.contains("d1"))
    }

    @Test
    fun contains_missingId_returnsFalse() {
        assertFalse(index.contains("nonexistent"))
    }

    // ---- size ----

    @Test
    fun size_returnsCorrectCount() {
        assertEquals(0, index.size())
        index.index(createDisciple(id = "d1"))
        assertEquals(1, index.size())
        index.index(createDisciple(id = "d2"))
        assertEquals(2, index.size())
    }

    // ---- getAll ----

    @Test
    fun getAll_returnsAllDisciples() {
        val d1 = createDisciple(id = "d1")
        val d2 = createDisciple(id = "d2")
        index.indexAll(listOf(d1, d2))
        val all = index.getAll()
        assertEquals(2, all.size)
    }

    // ---- clear ----

    @Test
    fun clear_removesAllData() {
        index.index(createDisciple(id = "d1"))
        index.clear()
        assertEquals(0, index.size())
        assertNull(index.getById("d1"))
    }

    // ---- getIndexStats ----

    @Test
    fun getIndexStats_returnsCorrectStats() {
        val d1 = createDisciple(id = "d1", realm = 9, status = DiscipleStatus.IDLE, discipleType = "outer", isAlive = true)
        val d2 = createDisciple(id = "d2", realm = 5, status = DiscipleStatus.MINING, discipleType = "inner", isAlive = false)
        index.indexAll(listOf(d1, d2))
        val stats = index.getIndexStats()
        assertEquals(2, stats.totalCount)
        assertEquals(1, stats.aliveCount)
        assertEquals(1, stats.deadCount)
        assertEquals(1, stats.outerDisciplesCount)
        assertEquals(1, stats.innerDisciplesCount)
    }
}

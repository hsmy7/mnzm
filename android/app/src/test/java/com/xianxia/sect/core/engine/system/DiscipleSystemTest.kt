package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SkillStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiscipleSystemTest {

    private lateinit var system: DiscipleSystem

    @Before
    fun setUp() {
        system = DiscipleSystem()
        system.initialize()
    }

    private fun createDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        realm: Int = 9,
        isAlive: Boolean = true,
        status: DiscipleStatus = DiscipleStatus.IDLE
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            isAlive = isAlive,
            status = status,
            skills = SkillStats(loyalty = 50)
        )
    }

    // ========== addDisciple 测试 ==========

    @Test
    fun `addDisciple - 正常添加弟子`() {
        val disciple = createDisciple()
        val result = system.addDisciple(disciple)
        assertTrue(result)
        assertEquals(1, system.getDiscipleCount())
    }

    @Test
    fun `addDisciple - id为空不能添加`() {
        val disciple = createDisciple(id = "")
        val result = system.addDisciple(disciple)
        assertFalse(result)
        assertEquals(0, system.getDiscipleCount())
    }

    @Test
    fun `addDisciple - 重复id不能添加`() {
        val disciple = createDisciple(id = "d1")
        system.addDisciple(disciple)
        val result = system.addDisciple(createDisciple(id = "d1"))
        assertFalse(result)
        assertEquals(1, system.getDiscipleCount())
    }

    // ========== addDisciples 测试 ==========

    @Test
    fun `addDisciples - 批量添加有效弟子`() {
        val disciples = listOf(
            createDisciple(id = "d1"),
            createDisciple(id = "d2"),
            createDisciple(id = "d3")
        )
        val count = system.addDisciples(disciples)
        assertEquals(3, count)
        assertEquals(3, system.getDiscipleCount())
    }

    @Test
    fun `addDisciples - 过滤空id和重复id`() {
        system.addDisciple(createDisciple(id = "d1"))
        val disciples = listOf(
            createDisciple(id = ""),
            createDisciple(id = "d1"),
            createDisciple(id = "d2")
        )
        val count = system.addDisciples(disciples)
        assertEquals(1, count)
    }

    @Test
    fun `addDisciples - 空列表返回0`() {
        val count = system.addDisciples(emptyList())
        assertEquals(0, count)
    }

    // ========== removeDisciple 测试 ==========

    @Test
    fun `removeDisciple - 删除存在的弟子`() {
        system.addDisciple(createDisciple(id = "d1"))
        val result = system.removeDisciple("d1")
        assertTrue(result)
        assertEquals(0, system.getDiscipleCount())
    }

    @Test
    fun `removeDisciple - 删除不存在的弟子返回false`() {
        val result = system.removeDisciple("nonexistent")
        assertFalse(result)
    }

    // ========== removeDisciples 测试 ==========

    @Test
    fun `removeDisciples - 批量删除弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1"),
            createDisciple(id = "d2"),
            createDisciple(id = "d3")
        ))
        val count = system.removeDisciples(listOf("d1", "d3"))
        assertEquals(2, count)
        assertEquals(1, system.getDiscipleCount())
        assertNotNull(system.getDiscipleById("d2"))
    }

    @Test
    fun `removeDisciples - 空列表返回0`() {
        val count = system.removeDisciples(emptyList())
        assertEquals(0, count)
    }

    // ========== updateDisciple 测试 ==========

    @Test
    fun `updateDisciple - 更新存在的弟子`() {
        system.addDisciple(createDisciple(id = "d1", name = "Original"))
        val result = system.updateDisciple("d1") { it.copy(name = "Updated") }
        assertTrue(result)
        assertEquals("Updated", system.getDiscipleById("d1")?.name)
    }

    @Test
    fun `updateDisciple - 更新不存在的弟子返回false`() {
        val result = system.updateDisciple("nonexistent") { it.copy(name = "Updated") }
        assertFalse(result)
    }

    // ========== getDiscipleById 测试 ==========

    @Test
    fun `getDiscipleById - 获取存在的弟子`() {
        system.addDisciple(createDisciple(id = "d1"))
        val disciple = system.getDiscipleById("d1")
        assertNotNull(disciple)
        assertEquals("d1", disciple!!.id)
    }

    @Test
    fun `getDiscipleById - 获取不存在的弟子返回null`() {
        val disciple = system.getDiscipleById("nonexistent")
        assertNull(disciple)
    }

    // ========== getDisciplesByIds 测试 ==========

    @Test
    fun `getDisciplesByIds - 批量获取弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1"),
            createDisciple(id = "d2"),
            createDisciple(id = "d3")
        ))
        val disciples = system.getDisciplesByIds(listOf("d1", "d3"))
        assertEquals(2, disciples.size)
    }

    @Test
    fun `getDisciplesByIds - 空列表返回空`() {
        val disciples = system.getDisciplesByIds(emptyList())
        assertTrue(disciples.isEmpty())
    }

    // ========== 状态管理测试 ==========

    @Test
    fun `setDiscipleStatus - 设置弟子状态`() {
        system.addDisciple(createDisciple(id = "d1"))
        val result = system.setDiscipleStatus("d1", DiscipleStatus.MINING)
        assertTrue(result)
        assertEquals(DiscipleStatus.MINING, system.getDiscipleById("d1")?.status)
    }

    @Test
    fun `updateDiscipleStatuses - 批量更新状态`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1"),
            createDisciple(id = "d2")
        ))
        val count = system.updateDiscipleStatuses(mapOf(
            "d1" to DiscipleStatus.MINING,
            "d2" to DiscipleStatus.STUDYING
        ))
        assertEquals(2, count)
        assertEquals(DiscipleStatus.MINING, system.getDiscipleById("d1")?.status)
        assertEquals(DiscipleStatus.STUDYING, system.getDiscipleById("d2")?.status)
    }

    @Test
    fun `updateDiscipleStatuses - 空map返回0`() {
        val count = system.updateDiscipleStatuses(emptyMap())
        assertEquals(0, count)
    }

    // ========== 查询测试 ==========

    @Test
    fun `getAliveDisciples - 只返回存活弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", isAlive = true),
            createDisciple(id = "d2", isAlive = false),
            createDisciple(id = "d3", isAlive = true)
        ))
        val alive = system.getAliveDisciples()
        assertEquals(2, alive.size)
        assertTrue(alive.all { it.isAlive })
    }

    @Test
    fun `getDisciplesByStatus - 按状态过滤`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", status = DiscipleStatus.IDLE),
            createDisciple(id = "d2", status = DiscipleStatus.MINING),
            createDisciple(id = "d3", status = DiscipleStatus.IDLE)
        ))
        val idle = system.getDisciplesByStatus(DiscipleStatus.IDLE)
        assertEquals(2, idle.size)
        assertTrue(idle.all { it.status == DiscipleStatus.IDLE })
    }

    @Test
    fun `getIdleDisciples - 只返回空闲存活弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", status = DiscipleStatus.IDLE, isAlive = true),
            createDisciple(id = "d2", status = DiscipleStatus.MINING, isAlive = true),
            createDisciple(id = "d3", status = DiscipleStatus.IDLE, isAlive = false)
        ))
        val idle = system.getIdleDisciples()
        assertEquals(1, idle.size)
        assertEquals("d1", idle[0].id)
    }

    @Test
    fun `getDisciplesByRealm - 按境界过滤`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", realm = 9),
            createDisciple(id = "d2", realm = 7),
            createDisciple(id = "d3", realm = 9)
        ))
        val result = system.getDisciplesByRealm(9)
        assertEquals(2, result.size)
    }

    @Test
    fun `getDisciplesByRealm - 无效境界返回空列表`() {
        system.addDisciple(createDisciple(realm = 9))
        assertTrue(system.getDisciplesByRealm(-1).isEmpty())
        assertTrue(system.getDisciplesByRealm(10).isEmpty())
    }

    @Test
    fun `getDisciplesByRealmRange - 按境界范围过滤`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", realm = 9),
            createDisciple(id = "d2", realm = 7),
            createDisciple(id = "d3", realm = 5)
        ))
        val result = system.getDisciplesByRealmRange(7, 9)
        assertEquals(2, result.size)
    }

    @Test
    fun `getDisciplesByRealmRange - 无效范围返回空列表`() {
        assertTrue(system.getDisciplesByRealmRange(-1, 5).isEmpty())
        assertTrue(system.getDisciplesByRealmRange(5, 3).isEmpty())
        assertTrue(system.getDisciplesByRealmRange(0, 10).isEmpty())
    }

    // ========== 统计测试 ==========

    @Test
    fun `getDiscipleCount - 只统计存活弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", isAlive = true),
            createDisciple(id = "d2", isAlive = false)
        ))
        assertEquals(1, system.getDiscipleCount())
    }

    @Test
    fun `getDiscipleCountByStatus - 按状态统计`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", status = DiscipleStatus.IDLE),
            createDisciple(id = "d2", status = DiscipleStatus.IDLE),
            createDisciple(id = "d3", status = DiscipleStatus.MINING)
        ))
        assertEquals(2, system.getDiscipleCountByStatus(DiscipleStatus.IDLE))
    }

    @Test
    fun `getDiscipleCountByRealm - 按境界统计`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", realm = 9),
            createDisciple(id = "d2", realm = 9),
            createDisciple(id = "d3", realm = 7)
        ))
        assertEquals(2, system.getDiscipleCountByRealm(9))
    }

    @Test
    fun `getAverageRealm - 计算平均境界`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", realm = 9),
            createDisciple(id = "d2", realm = 7)
        ))
        val avg = system.getAverageRealm()
        assertEquals(1.0, avg, 0.01)
    }

    @Test
    fun `getAverageRealm - 无存活弟子返回0`() {
        assertEquals(0.0, system.getAverageRealm(), 0.01)
    }

    @Test
    fun `getTopDisciples - 获取修为最高的弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", realm = 9),
            createDisciple(id = "d2", realm = 7),
            createDisciple(id = "d3", realm = 5)
        ))
        val top = system.getTopDisciples(2) { (9 - it.realm).toDouble() }
        assertEquals(2, top.size)
        assertEquals(5, top[0].realm)
        assertEquals(7, top[1].realm)
    }

    @Test
    fun `getTopDisciples - count为0返回空列表`() {
        system.addDisciple(createDisciple())
        assertTrue(system.getTopDisciples(0) { 1.0 }.isEmpty())
    }

    // ========== 存活和可用性测试 ==========

    @Test
    fun `hasDisciple - 存在的弟子返回true`() {
        system.addDisciple(createDisciple(id = "d1"))
        assertTrue(system.hasDisciple("d1"))
        assertFalse(system.hasDisciple("nonexistent"))
    }

    @Test
    fun `isDiscipleAlive - 存活弟子返回true`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", isAlive = true),
            createDisciple(id = "d2", isAlive = false)
        ))
        assertTrue(system.isDiscipleAlive("d1"))
        assertFalse(system.isDiscipleAlive("d2"))
        assertFalse(system.isDiscipleAlive("nonexistent"))
    }

    @Test
    fun `isDiscipleAvailable - 存活且空闲返回true`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", isAlive = true, status = DiscipleStatus.IDLE),
            createDisciple(id = "d2", isAlive = true, status = DiscipleStatus.MINING),
            createDisciple(id = "d3", isAlive = false, status = DiscipleStatus.IDLE)
        ))
        assertTrue(system.isDiscipleAvailable("d1"))
        assertFalse(system.isDiscipleAvailable("d2"))
        assertFalse(system.isDiscipleAvailable("d3"))
        assertFalse(system.isDiscipleAvailable("nonexistent"))
    }

    // ========== 死亡/复活测试 ==========

    @Test
    fun `handleDiscipleDeath - 处理弟子死亡`() {
        system.addDisciple(createDisciple(id = "d1", isAlive = true))
        val result = system.handleDiscipleDeath("d1")
        assertTrue(result)
        assertFalse(system.getDiscipleById("d1")!!.isAlive)
        assertEquals(DiscipleStatus.IDLE, system.getDiscipleById("d1")!!.status)
    }

    @Test
    fun `reviveDisciple - 复活弟子`() {
        system.addDisciple(createDisciple(id = "d1", isAlive = false))
        val result = system.reviveDisciple("d1")
        assertTrue(result)
        assertTrue(system.getDiscipleById("d1")!!.isAlive)
    }

    // ========== 招募测试 ==========

    @Test
    fun `recruitDisciple - 正常招募`() {
        val recruit = createDisciple(id = "r1", status = DiscipleStatus.IDLE)
        system.loadRecruitList(listOf(recruit), 0)
        val recruited = system.recruitDisciple("r1", 5)
        assertNotNull(recruited)
        assertEquals("r1", recruited!!.id)
        assertEquals(DiscipleStatus.IDLE, recruited.status)
        assertEquals(5, recruited.recruitedMonth)
        assertTrue(system.hasDisciple("r1"))
        assertFalse(system.recruitList.value.any { it.id == "r1" })
    }

    @Test
    fun `recruitDisciple - 不存在的弟子返回null`() {
        val recruited = system.recruitDisciple("nonexistent", 0)
        assertNull(recruited)
    }

    @Test
    fun `recruitDisciple - recruitedMonth为负返回null`() {
        val recruit = createDisciple(id = "r1")
        system.loadRecruitList(listOf(recruit), 0)
        val recruited = system.recruitDisciple("r1", -1)
        assertNull(recruited)
    }

    // ========== loadRecruitList 测试 ==========

    @Test
    fun `loadRecruitList - 负数year不加载`() {
        system.loadRecruitList(listOf(createDisciple(id = "r1")), -1)
        assertTrue(system.recruitList.value.isEmpty())
    }

    @Test
    fun `loadRecruitList - 正常加载`() {
        system.loadRecruitList(listOf(createDisciple(id = "r1")), 5)
        assertEquals(1, system.recruitList.value.size)
        assertEquals(5, system.lastRecruitYear.value)
    }

    // ========== refreshRecruitList 测试 ==========

    @Test
    fun `refreshRecruitList - 负数year返回false`() {
        val result = system.refreshRecruitList(emptyList(), -1)
        assertFalse(result)
    }

    @Test
    fun `refreshRecruitList - 正常刷新`() {
        val result = system.refreshRecruitList(listOf(createDisciple(id = "r1")), 10)
        assertTrue(result)
        assertEquals(10, system.lastRecruitYear.value)
    }

    // ========== syncDiscipleStatuses 测试 ==========

    @Test
    fun `syncDiscipleStatuses - 只同步存活弟子`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", isAlive = true, status = DiscipleStatus.IDLE),
            createDisciple(id = "d2", isAlive = false, status = DiscipleStatus.IDLE)
        ))
        val count = system.syncDiscipleStatuses(mapOf(
            "d1" to DiscipleStatus.MINING,
            "d2" to DiscipleStatus.STUDYING
        ))
        assertEquals(1, count)
        assertEquals(DiscipleStatus.MINING, system.getDiscipleById("d1")?.status)
        assertEquals(DiscipleStatus.IDLE, system.getDiscipleById("d2")?.status)
    }

    @Test
    fun `syncDiscipleStatuses - 空map返回0`() {
        val count = system.syncDiscipleStatuses(emptyMap())
        assertEquals(0, count)
    }

    // ========== getDiscipleStats 测试 ==========

    @Test
    fun `getDiscipleStats - 空系统统计正确`() {
        val stats = system.getDiscipleStats()
        assertEquals(0, stats.totalCount)
        assertEquals(0.0, stats.averageCultivation, 0.01)
        assertEquals(0.0, stats.totalCombatPower, 0.01)
    }

    @Test
    fun `getDiscipleStats - 有弟子时统计正确`() {
        system.addDisciples(listOf(
            createDisciple(id = "d1", realm = 9),
            createDisciple(id = "d2", realm = 7)
        ))
        val stats = system.getDiscipleStats()
        assertEquals(2, stats.totalCount)
        assertEquals(1, stats.byRealm[9])
        assertEquals(1, stats.byRealm[7])
    }

    // ========== clear 测试 ==========

    @Test
    fun `clear - 清空所有数据`() = runBlocking {
        system.addDisciples(listOf(
            createDisciple(id = "d1"),
            createDisciple(id = "d2")
        ))
        system.loadRecruitList(listOf(createDisciple(id = "r1")), 5)
        system.clear()
        assertEquals(0, system.getDiscipleCount())
        assertTrue(system.recruitList.value.isEmpty())
        assertEquals(0, system.lastRecruitYear.value)
    }
}

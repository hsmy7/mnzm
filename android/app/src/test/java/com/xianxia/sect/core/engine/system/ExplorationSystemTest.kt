package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.model.ExplorationStatus
import com.xianxia.sect.core.model.ExplorationTeam
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExplorationSystemTest {

    private lateinit var system: ExplorationSystem

    @Before
    fun setUp() {
        system = ExplorationSystem()
        system.initialize()
    }

    private fun createTeam(
        id: String = "t1",
        name: String = "TestTeam",
        status: ExplorationStatus = ExplorationStatus.EXPLORING,
        memberIds: List<String> = listOf("d1", "d2")
    ): ExplorationTeam {
        return ExplorationTeam(
            id = id,
            name = name,
            status = status,
            memberIds = memberIds
        )
    }

    @Test
    fun `addTeam - 正常添加探索队`() {
        val team = createTeam()
        system.addTeam(team)
        assertEquals(1, system.getTeams().size)
        assertEquals("t1", system.getTeams()[0].id)
    }

    @Test
    fun `addTeam - 添加多个探索队`() {
        system.addTeam(createTeam(id = "t1"))
        system.addTeam(createTeam(id = "t2"))
        system.addTeam(createTeam(id = "t3"))
        assertEquals(3, system.getTeams().size)
    }

    @Test
    fun `removeTeam - 删除存在的探索队`() {
        system.addTeam(createTeam(id = "t1"))
        val result = system.removeTeam("t1")
        assertTrue(result)
        assertEquals(0, system.getTeams().size)
    }

    @Test
    fun `removeTeam - 删除不存在的探索队返回false`() {
        val result = system.removeTeam("nonexistent")
        assertFalse(result)
    }

    @Test
    fun `getTeamById - 获取存在的探索队`() {
        val team = createTeam(id = "t1", name = "探索队1")
        system.addTeam(team)
        val found = system.getTeamById("t1")
        assertNotNull(found)
        assertEquals("探索队1", found!!.name)
    }

    @Test
    fun `getTeamById - 获取不存在的探索队返回null`() {
        assertNull(system.getTeamById("nonexistent"))
    }

    @Test
    fun `updateTeam - 更新探索队信息`() {
        system.addTeam(createTeam(id = "t1", name = "旧名"))
        val result = system.updateTeam("t1") { it.copy(name = "新名") }
        assertTrue(result)
        assertEquals("新名", system.getTeamById("t1")!!.name)
    }

    @Test
    fun `updateTeam - 更新不存在的探索队返回false`() {
        val result = system.updateTeam("nonexistent") { it.copy(name = "新名") }
        assertFalse(result)
    }

    @Test
    fun `isDiscipleInTeam - 弟子在探索队中`() {
        system.addTeam(createTeam(memberIds = listOf("d1", "d2")))
        assertTrue(system.isDiscipleInTeam("d1"))
        assertTrue(system.isDiscipleInTeam("d2"))
    }

    @Test
    fun `isDiscipleInTeam - 弟子不在探索队中`() {
        system.addTeam(createTeam(memberIds = listOf("d1")))
        assertFalse(system.isDiscipleInTeam("d3"))
    }

    @Test
    fun `getActiveTeams - 返回未完成的探索队`() {
        system.addTeam(createTeam(id = "t1", status = ExplorationStatus.EXPLORING))
        system.addTeam(createTeam(id = "t2", status = ExplorationStatus.COMPLETED))
        system.addTeam(createTeam(id = "t3", status = ExplorationStatus.EXPLORING))
        val active = system.getActiveTeams()
        assertEquals(2, active.size)
        assertTrue(active.all { it.status != ExplorationStatus.COMPLETED })
    }

    @Test
    fun `getIdleTeams - 返回已完成的探索队`() {
        system.addTeam(createTeam(id = "t1", status = ExplorationStatus.COMPLETED))
        system.addTeam(createTeam(id = "t2", status = ExplorationStatus.EXPLORING))
        val idle = system.getIdleTeams()
        assertEquals(1, idle.size)
        assertEquals(ExplorationStatus.COMPLETED, idle[0].status)
    }

    @Test
    fun `loadTeams - 加载探索队数据`() {
        val teams = listOf(
            createTeam(id = "t1"),
            createTeam(id = "t2")
        )
        system.loadTeams(teams)
        assertEquals(2, system.getTeams().size)
    }

    @Test
    fun `updateTeams - 批量更新探索队`() {
        system.addTeam(createTeam(id = "t1", name = "旧名"))
        system.updateTeams { teams ->
            teams.map { it.copy(name = "新名") }
        }
        assertEquals("新名", system.getTeamById("t1")!!.name)
    }

    @Test
    fun `clear - 清空所有探索队`() = runBlocking {
        system.addTeam(createTeam(id = "t1"))
        system.addTeam(createTeam(id = "t2"))
        system.clear()
        assertEquals(0, system.getTeams().size)
    }

    @Test
    fun `systemName 正确`() {
        assertEquals("ExplorationSystem", system.systemName)
    }
}
